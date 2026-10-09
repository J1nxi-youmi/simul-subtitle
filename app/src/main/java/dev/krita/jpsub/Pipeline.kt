package dev.krita.jpsub

import java.util.concurrent.LinkedBlockingDeque

/** Monotonic milliseconds; every timestamp in the pipeline uses this clock. */
fun monoMs(): Long = System.nanoTime() / 1_000_000

/** A chunk of Japanese speech; draft = the speaker is still talking. */
data class AsrJob(
    val segmentId: Long,
    val revision: Int,
    val isFinal: Boolean,
    val samples: FloatArray,
    /** [monoMs] when the last sample of [samples] was captured */
    val audioEndAt: Long,
    /** [audioEndAt] is the read time, not a HAL capture timestamp (underestimates latency) */
    val timeEstimated: Boolean = false,
)

data class Caption(
    val segmentId: Long,
    val revision: Int,
    val isFinal: Boolean,
    val ja: String,
    val zh: String,
    val timing: Timing,
    /** [monoMs] capture time of the last sample, so the UI can measure capture → text update itself */
    val audioEndAt: Long = 0,
    /** see [AsrJob.timeEstimated] */
    val timeEstimated: Boolean = false,
)

data class Timing(
    val audioMs: Long,
    /** from the last sample of the chunk until the segmenter handed it over (end-of-speech wait) */
    val segmentWaitMs: Long,
    val asrQueueMs: Long,
    val asrMs: Long,
    val mtQueueMs: Long,
    val mtMs: Long,
    /** from the last captured sample until MT finished (UI delivery is measured separately by the app) */
    val mtDoneMs: Long,
)

interface Asr { fun recognize(samples: FloatArray): String }

interface Mt {
    /** Returns null when cancelled (only used on stop) or on failure. */
    fun translate(text: String): String?
    fun cancel()
}

/**
 * Two worker threads (ASR, MT) connected by small queues.
 *
 * "Draft then correct" contract:
 * - Finals are processed in order and never dropped.
 * - Drafts are disposable: a newer job for the same segment replaces a draft that has not started yet.
 *   A running draft is never pre-empted, so a slow phone still shows a draft while the speaker talks.
 * - The UI ([CaptionBoard]) only moves forward per segment and freezes a segment after its final.
 * - Overload is explicit: when un-translated finals hold more than [maxPendingAudioMs] of speech,
 *   [onOverload] fires once and new jobs are refused; nothing is dropped silently.
 * - [stop] is non-blocking; call [awaitStopped] (off the main thread) before releasing [asr]/[mt].
 */
class Pipeline(
    private val asr: Asr,
    private val mt: Mt,
    private val onCaption: (Caption) -> Unit,
    private val onBacklog: (pendingFinals: Int) -> Unit = {},
    private val onOverload: () -> Unit = {},
    /** A worker threw; the pipeline refuses new jobs from now on. Called on the worker thread. */
    private val onFailure: (Throwable) -> Unit = {},
    private val maxPendingAudioMs: Long = 30_000,
    private val clock: () -> Long = ::monoMs,
    private val sampleRate: Int = 16_000,
) {
    private class Queued(val job: AsrJob, val submittedAt: Long)
    private class MtJob(val q: Queued, val ja: String, val asrStartAt: Long, val asrEndAt: Long)

    // Shared lock for both queues so pending-final accounting is consistent.
    private val lock = Object()
    private val asrQueue = LinkedBlockingDeque<Queued>()
    private val mtQueue = LinkedBlockingDeque<MtJob>()
    private var pendingFinalAudioMs = 0L
    /** finals taken off a queue and not yet delivered (guarded by [lock]) */
    private var inFlightFinals = 0
    @Volatile private var running = false
    @Volatile private var overloaded = false
    @Volatile private var failed = false
    private var threads: List<Thread> = emptyList()

    fun start() {
        check(threads.isEmpty()) { "Pipeline is single-use" }
        running = true
        threads = listOf(Thread(guarded(::asrLoop), "jpsub-asr"), Thread(guarded(::mtLoop), "jpsub-mt")).onEach { it.start() }
    }

    /** Non-blocking. Results produced after this call are discarded. */
    fun stop() {
        running = false
        mt.cancel()
        threads.forEach { it.interrupt() }
    }

    /** Blocks until both workers have exited (native calls finish on their own; MT is cancelled). */
    fun awaitStopped() = threads.forEach { it.join() }

    /**
     * Stops accepting jobs, drops queued drafts, and waits up to [timeoutMs] for the accepted finals to be
     * delivered; returns early if the pipeline is stopped or fails. Always ends with the pipeline stopped
     * and its workers joined, so nothing is delivered after it returns. Not for the worker threads.
     * @return the number of accepted finals that were not delivered (0 = fully drained).
     */
    fun drainFinals(timeoutMs: Long): Int {
        synchronized(lock) {
            overloaded = true
            asrQueue.removeIf { !it.job.isFinal }
            mtQueue.removeIf { !it.q.job.isFinal }
        }
        val deadline = clock() + timeoutMs
        while (running && !failed && clock() < deadline && synchronized(lock) { pendingFinals() + inFlightFinals } > 0) {
            Thread.sleep(20)
        }
        stop(); awaitStopped()
        // in-flight finals are decremented only after onCaption returns, so after the join this count is exact
        return synchronized(lock) { pendingFinals() + inFlightFinals }
    }

    /** Called from the audio thread. Never blocks. @return false if refused (stopped or overloaded). */
    fun submit(job: AsrJob): Boolean {
        val backlog: Int
        synchronized(lock) {
            if (!running || overloaded || failed) return false
            asrQueue.removeIf { it.job.segmentId == job.segmentId && !it.job.isFinal }
            if (job.isFinal) {
                pendingFinalAudioMs += audioMs(job)
                if (pendingFinalAudioMs > maxPendingAudioMs) overloaded = true
            }
            asrQueue.addLast(Queued(job, clock()))
            backlog = pendingFinals()
        }
        onBacklog(backlog)
        if (overloaded) onOverload()
        return true
    }

    /** Runs a worker body; any exception puts the pipeline into the failed state instead of killing the process. */
    private fun guarded(body: () -> Unit) = Runnable {
        try {
            body()
        } catch (e: InterruptedException) {
            // stop() interrupts the workers after clearing `running`; any other interruption is a failure
            if (running) { failed = true; onFailure(e) }
        } catch (t: Throwable) {
            failed = true
            if (running) onFailure(t)
        }
    }

    /** Polls [q]; a final that leaves the queue is counted in [inFlightFinals] atomically, so drain never misses it. */
    private fun <T> take(q: LinkedBlockingDeque<T>, isFinal: (T) -> Boolean): T? {
        synchronized(lock) {
            val x = q.pollFirst() ?: return@synchronized
            if (isFinal(x)) inFlightFinals++
            return x
        }
        Thread.sleep(10)
        return null
    }

    private fun asrLoop() {
        while (running && !failed) {
            val q = take(asrQueue) { it.job.isFinal } ?: continue
            val start = clock()
            val ja = asr.recognize(q.job.samples).trim()
            val end = clock()
            if (!running) return
            synchronized(lock) {
                // A draft that already ran is kept even if a newer draft is queued: on a slow phone every
                // draft would otherwise be superseded before it reaches MT and nothing would be shown.
                if (!q.job.isFinal && ja.isEmpty()) return@synchronized
                mtQueue.removeIf { it.q.job.segmentId == q.job.segmentId && !it.q.job.isFinal }
                mtQueue.addLast(MtJob(q, ja, start, end))
                if (q.job.isFinal) inFlightFinals-- // now counted by mtQueue
            }
        }
    }

    private fun mtLoop() {
        while (running && !failed) {
            val j = take(mtQueue) { it.q.job.isFinal } ?: continue
            val job = j.q.job
            val start = clock()
            var zh = if (j.ja.isEmpty()) "" else mt.translate(j.ja)
            val end = clock()
            if (!running) return
            if (zh == null) {
                if (!job.isFinal) continue
                zh = "[翻译失败] ${j.ja}"
            }
            val backlog = synchronized(lock) {
                if (job.isFinal) pendingFinalAudioMs -= audioMs(job)
                pendingFinals() + inFlightFinals
            }
            onCaption(
                Caption(
                    job.segmentId, job.revision, job.isFinal, j.ja, zh.trim(),
                    Timing(
                        audioMs = audioMs(job),
                        segmentWaitMs = j.q.submittedAt - job.audioEndAt,
                        asrQueueMs = j.asrStartAt - j.q.submittedAt,
                        asrMs = j.asrEndAt - j.asrStartAt,
                        mtQueueMs = start - j.asrEndAt,
                        mtMs = end - start,
                        mtDoneMs = end - job.audioEndAt,
                    ),
                    job.audioEndAt,
                    job.timeEstimated,
                )
            )
            if (job.isFinal) synchronized(lock) { inFlightFinals-- }
            onBacklog(backlog - if (job.isFinal) 1 else 0)
        }
    }

    private fun audioMs(job: AsrJob) = job.samples.size * 1000L / sampleRate

    // caller holds lock
    private fun pendingFinals() = asrQueue.count { it.job.isFinal } + mtQueue.count { it.q.job.isFinal }
}

/** UI-side guard: newer revisions only, nothing after a segment's final, no late drafts of older segments. */
class CaptionBoard(private val maxLines: Int = 2) {
    private val lines = LinkedHashMap<Long, Caption>()

    /** @return true if the board changed */
    @Synchronized
    fun accept(c: Caption): Boolean {
        val cur = lines[c.segmentId]
        if (cur != null && (cur.isFinal || cur.revision >= c.revision)) return false
        if (cur == null && !c.isFinal && lines.keys.any { it > c.segmentId }) return false
        if (c.zh.isEmpty() && c.isFinal) lines.remove(c.segmentId) else lines[c.segmentId] = c
        while (lines.size > maxLines) lines.remove(lines.keys.first())
        return true
    }

    @Synchronized
    fun snapshot(): List<Caption> = lines.values.toList()
}
