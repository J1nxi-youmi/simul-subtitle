package dev.krita.jpsub

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

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
)

data class Caption(
    val segmentId: Long,
    val revision: Int,
    val isFinal: Boolean,
    val ja: String,
    val zh: String,
    val timing: Timing,
)

data class Timing(
    val audioMs: Long,
    /** from the last sample of the chunk until the segmenter handed it over (end-of-speech wait) */
    val segmentWaitMs: Long,
    val asrQueueMs: Long,
    val asrMs: Long,
    val mtQueueMs: Long,
    val mtMs: Long,
    /** from the last captured sample until the caption is handed to the UI thread */
    val totalMs: Long,
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
    @Volatile private var running = false
    @Volatile private var overloaded = false
    private var threads: List<Thread> = emptyList()

    fun start() {
        check(threads.isEmpty()) { "Pipeline is single-use" }
        running = true
        threads = listOf(Thread(::asrLoop, "jpsub-asr"), Thread(::mtLoop, "jpsub-mt")).onEach { it.start() }
    }

    /** Non-blocking. Results produced after this call are discarded. */
    fun stop() {
        running = false
        mt.cancel()
        threads.forEach { it.interrupt() }
    }

    /** Blocks until both workers have exited (native calls finish on their own; MT is cancelled). */
    fun awaitStopped() = threads.forEach { it.join() }

    /** Called from the audio thread. Never blocks. @return false if refused (stopped or overloaded). */
    fun submit(job: AsrJob): Boolean {
        val backlog: Int
        synchronized(lock) {
            if (!running || overloaded) return false
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

    private fun asrLoop() {
        while (running) {
            val q = try { asrQueue.pollFirst(200, TimeUnit.MILLISECONDS) ?: continue } catch (_: InterruptedException) { return }
            val start = clock()
            val ja = asr.recognize(q.job.samples).trim()
            val end = clock()
            if (!running) return
            synchronized(lock) {
                if (!q.job.isFinal && asrQueue.any { it.job.segmentId == q.job.segmentId }) return@synchronized // already stale
                if (!q.job.isFinal && ja.isEmpty()) return@synchronized
                mtQueue.removeIf { it.q.job.segmentId == q.job.segmentId && !it.q.job.isFinal }
                mtQueue.addLast(MtJob(q, ja, start, end))
            }
        }
    }

    private fun mtLoop() {
        while (running) {
            val j = try { mtQueue.pollFirst(200, TimeUnit.MILLISECONDS) ?: continue } catch (_: InterruptedException) { return }
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
                pendingFinals()
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
                        totalMs = end - job.audioEndAt,
                    ),
                )
            )
            onBacklog(backlog)
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
