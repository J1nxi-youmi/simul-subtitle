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
    /** a draft cut at a pause: everything the speaker said before it; the segmenter asks whether it is finished */
    val endOfSpeech: Boolean = false,
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
    /** the ASR/MT revision this caption comes from; a checked final is shown as revision + 1 but logged under this */
    val sourceRevision: Int = revision,
)

data class Timing(
    val audioMs: Long,
    /** from the last sample of the chunk until the segmenter handed it over (end-of-speech wait) */
    val segmentWaitMs: Long,
    val asrQueueMs: Long,
    val asrMs: Long,
    val mtQueueMs: Long,
    /** the local translation(s) only; a Jev check and its retry are in [checkMs] */
    val mtMs: Long,
    /** from the last captured sample until MT finished (UI delivery is measured separately by the app) */
    val mtDoneMs: Long,
    /** Jev mistranslation check of a final, including a retranslation it asked for (0 without Jev) */
    val checkMs: Long = 0,
)

interface Asr { fun recognize(samples: FloatArray): String }

interface Mt {
    /** One generation for a complete prompt; null when cancelled (only used on stop) or when nothing ran. */
    fun translate(prompt: String): MtResult?
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
    private val scene: Scene = Scene.GENERAL,
    /**
     * Optional cloud judge (Jev). With it: endOfSpeech drafts are asked "unfinished?" on a separate thread and the
     * answer is offered to the segmenter through [holdHint]; every final translation is checked once and, for the
     * error classes in [FIX_NOTES], translated again with the matching note. Any failure keeps the local result.
     */
    private val judge: Judge? = null,
    /** Raw session events (asr / mt / jev) for the session log. Called on worker threads; must not block. */
    private val log: (Map<String, Any?>) -> Unit = {},
) {
    private class Queued(val job: AsrJob, val submittedAt: Long)
    private class MtJob(val q: Queued, val ja: String, val asrStartAt: Long, val asrEndAt: Long)

    /** Jev answers for endOfSpeech drafts, by (segment, revision); true = unfinished. */
    private val hints = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, Boolean>()
    /** The newest endOfSpeech draft waiting for Jev; an older one that has not started is replaced. */
    private val pendingJudge = java.util.concurrent.atomic.AtomicReference<Pair<AsrJob, String>?>(null)

    /** For [Segmenter.holdHint]: never blocks; null until Jev answered for exactly this revision. */
    fun holdHint(segmentId: Long, revision: Int): Boolean? {
        val h = hints[segmentId to revision] ?: return null
        // the segmenter asks on every frame: log only the first read, which is when the answer took effect
        if (hintsUsed.add(segmentId to revision)) log(mapOf("ev" to "hint_used", "seg" to segmentId, "rev" to revision, "hold" to h))
        return h
    }
    private val hintsUsed: MutableSet<Pair<Long, Int>> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** newest segment handed over as final: a Jev answer for it or an older one came too late to matter */
    @Volatile private var lastFinalSeg = -1L

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
        threads = listOfNotNull(
            Thread(guarded(::asrLoop), "jpsub-asr"),
            Thread(guarded(::mtLoop), "jpsub-mt"),
            judge?.let { Thread(guarded(::judgeLoop), "jpsub-jev") },
        ).onEach { it.start() }
    }

    /** Non-blocking. Results produced after this call are discarded. */
    fun stop() {
        running = false
        mt.cancel()
        judge?.cancel()
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
                if (job.segmentId > lastFinalSeg) lastFinalSeg = job.segmentId
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
            val job = q.job
            log(mapOf("ev" to "asr", "seg" to job.segmentId, "rev" to job.revision, "final" to job.isFinal,
                "eos" to job.endOfSpeech, "audio_ms" to audioMs(job), "ms" to end - start, "text" to ja))
            if (judge != null && job.endOfSpeech) {
                // nothing recognised = nothing to wait for; otherwise ask, replacing an older question not yet sent
                if (ja.isEmpty()) hints[job.segmentId to job.revision] = false else pendingJudge.set(job to ja)
            }
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

    private fun judgeLoop() {
        val judge = judge!!
        while (running && !failed) {
            val (job, ja) = pendingJudge.getAndSet(null) ?: run { Thread.sleep(10); null } ?: continue
            val call = judge.unfinished(ja)
            if (!running) { logJev("unfinished", job.segmentId, job.revision, call, "stopped"); return }
            val p = call.noul
            val unfinished = p?.let { it >= UNFINISHED_THRESHOLD }
            if (unfinished != null) {
                hints[job.segmentId to job.revision] = unfinished
                hints.keys.removeIf { it.first < job.segmentId - 8 }
                hintsUsed.removeIf { it.first < job.segmentId - 8 }
            }
            // "hold"/"finish" is only the advice; a "hint_used" event shows when the segmenter acted on it
            logJev("unfinished", job.segmentId, job.revision, call, when {
                unfinished == null -> "none"
                job.segmentId <= lastFinalSeg -> "late"
                unfinished -> "hold"
                else -> "finish"
            })
        }
    }

    private fun logJev(kind: String, seg: Long, rev: Int, c: JevCall, action: String) = log(mapOf(
        "ev" to "jev", "kind" to kind, "seg" to seg, "rev" to rev, "request" to c.request, "status" to c.status,
        "response" to c.response, "ms" to c.ms, "error" to c.error, "action" to action))

    /** One MT call, logged with everything needed to replay it. */
    private fun translate(job: AsrJob, attempt: Int, ja: String, notes: List<String>): MtResult? {
        val prompt = indexPrompt(ja, scene, notes)
        val t0 = clock()
        val r = mt.translate(prompt)
        log(mapOf("ev" to "mt", "seg" to job.segmentId, "rev" to job.revision, "final" to job.isFinal, "attempt" to attempt,
            "prompt" to prompt, "raw" to r?.raw, "stop" to r?.stop?.toString(), "prompt_tokens" to r?.promptTokens,
            "output_tokens" to r?.outputTokens, "ms" to clock() - t0))
        return r
    }

    /**
     * Final only: ask Jev what is wrong with [zh]; for a class it names reliably, translate once more with the
     * matching note. Returns the text to show (the retry if it produced one, else [zh]).
     */
    private fun checked(j: MtJob, zh: String, interim: (String) -> Unit): String {
        val judge = judge ?: return zh
        if (zh.isEmpty()) return zh
        interim(zh) // live mode shows the first translation while Jev checks it
        val job = j.q.job
        val call = judge.mistranslation(j.ja, zh)
        if (!running) { logJev("mistranslation", job.segmentId, job.revision, call, "stopped"); return zh }
        val note = call.choice?.let { FIX_NOTES[it] }?.takeIf { (call.confidence ?: 0.0) >= FIX_MIN_CONFIDENCE }
        if (note == null) {
            logJev("mistranslation", job.segmentId, job.revision, call, if (call.answer == null) "none" else "keep")
            return zh
        }
        logJev("mistranslation", job.segmentId, job.revision, call, "retranslate:${call.choice}")
        val r = translate(job, 2, j.ja, listOf(note))
        return r?.takeIf { it.usable }?.let { dropEchoedNote(it.text, indexPrompt(j.ja, scene, listOf(note))) } ?: zh
    }

    private fun mtLoop() {
        while (running && !failed) {
            val j = take(mtQueue) { it.q.job.isFinal } ?: continue
            val job = j.q.job
            val start = clock()
            var checkMs = 0L
            val r = if (j.ja.isEmpty()) null else translate(job, 1, j.ja, emptyList())
            if (!running) return
            var zh = if (j.ja.isEmpty()) "" else r?.takeIf { it.usable }?.let { dropEchoedNote(it.text, indexPrompt(j.ja, scene)) }
            if (zh == null) {
                if (!job.isFinal) continue
                zh = "[翻译失败] ${j.ja}"
            } else if (job.isFinal) {
                // the interim line takes the final's revision; the final then goes out as revision + 1
                val c0 = clock()
                zh = checked(j, zh) { onCaption(caption(j, false, job.revision, it, start, clock())) }
                checkMs = clock() - c0
                if (!running) return
            }
            val end = clock()
            val backlog = synchronized(lock) {
                if (job.isFinal) pendingFinalAudioMs -= audioMs(job)
                pendingFinals() + inFlightFinals
            }
            onCaption(caption(j, job.isFinal, job.revision + if (judge != null && job.isFinal) 1 else 0, zh, start, end, checkMs))
            if (job.isFinal) synchronized(lock) { inFlightFinals-- }
            onBacklog(backlog - if (job.isFinal) 1 else 0)
        }
    }

    private fun caption(j: MtJob, final: Boolean, revision: Int, zh: String, start: Long, end: Long, checkMs: Long = 0): Caption {
        val job = j.q.job
        return Caption(
            job.segmentId, revision, final, j.ja, zh.trim(),
            Timing(
                audioMs = audioMs(job),
                segmentWaitMs = j.q.submittedAt - job.audioEndAt,
                asrQueueMs = j.asrStartAt - j.q.submittedAt,
                asrMs = j.asrEndAt - j.asrStartAt,
                mtQueueMs = start - j.asrEndAt,
                mtMs = end - start - checkMs,
                mtDoneMs = end - job.audioEndAt,
                checkMs = checkMs,
            ),
            job.audioEndAt,
            job.timeEstimated,
            sourceRevision = job.revision,
        )
    }

    private fun audioMs(job: AsrJob) = job.samples.size * 1000L / sampleRate

    companion object {
        /** Jev "unfinished" probability from which a pause is held (140-pair test: catches 30/31, waits wrongly 16/109). */
        const val UNFINISHED_THRESHOLD = 0.65
        /** Below this Jev confidence an error class is not acted on (untested guess). */
        const val FIX_MIN_CONFIDENCE = 0.6
    }

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
