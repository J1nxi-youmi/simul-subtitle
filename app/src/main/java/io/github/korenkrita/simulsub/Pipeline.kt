package io.github.korenkrita.simulsub

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
    /** the Jev mistranslation call of a final (0 without Jev); a retranslation it asked for is in [mtMs] */
    val checkMs: Long = 0,
)

interface Asr { fun recognize(samples: FloatArray): String }

interface Mt {
    /** One generation for a complete prompt; null when cancelled (only used on stop) or when nothing ran. */
    fun translate(prompt: String): MtResult?
    fun cancel()
}

/**
 * What a segment is translated with: the translator, the Jev client and the prompt settings. Immutable; the
 * session swaps in a new one when the user changes a setting while subtitles run ([Pipeline.setSetup]).
 * A segment takes the setup current when its first job reaches the ASR thread and keeps it to the end, so its
 * drafts, its final, the check and a retranslation never mix two settings. [version] tells setups apart (it
 * is part of the translation reuse key, also when two setups share one translator instance).
 */
class Setup(
    val mt: Mt,
    val scene: Scene = Scene.GENERAL,
    /** null = auto (Japanese or English) */
    val source: Lang? = Lang.JA,
    val target: Lang = Lang.ZH,
    /** The translator's prompt format: false = Index ([indexPrompt]), true = BYOK ([byokUser]). */
    val byokPrompt: Boolean = false,
    /** null: no Jev for this setup (not configured, or paused because its key is missing) */
    val judge: Judge? = null,
    val dedupeJoin: Boolean = false,
    /** finished sentences given as context; 0 = none */
    val previousSentences: Int = Pipeline.PREVIOUS_SENTENCES,
    val version: Int = 0,
)

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
    mt: Mt,
    private val onCaption: (Caption) -> Unit,
    private val onBacklog: (pendingFinals: Int) -> Unit = {},
    private val onOverload: () -> Unit = {},
    /** A worker threw; the pipeline refuses new jobs from now on. Called on the worker thread. */
    private val onFailure: (Throwable) -> Unit = {},
    private val maxPendingAudioMs: Long = 30_000,
    private val clock: () -> Long = ::monoMs,
    private val sampleRate: Int = 16_000,
    scene: Scene = Scene.GENERAL,
    /** null = auto (Japanese or English); only the translator and the join of kept drafts use it */
    source: Lang? = Lang.JA,
    target: Lang = Lang.ZH,
    /** The translator's prompt format: false = Index ([indexPrompt]), true = BYOK ([byokUser]; its system message is fixed). */
    byokPrompt: Boolean = false,
    /**
     * Optional cloud judge (Jev). With it: endOfSpeech drafts are asked "unfinished?" on a separate thread and the
     * answer is offered to the segmenter through [holdHint]; every final translation is checked once and, for the
     * error classes in [FIX_NOTES], translated again with the matching note. Any failure keeps the local result.
     */
    private val judge: Judge? = null,
    /** Ask [judge] at sentence-end candidates (the "unfinished?" question). Default on, as before the check switch. */
    private val judgeEnds: Boolean = true,
    /**
     * Where a kept draft and the recognition of the audio after it are joined, drop a repeat of the kept end at the
     * start of the new part (see [joinWithoutRepeat]). Off by default: a repeat the speaker really said may be dropped.
     */
    dedupeJoin: Boolean = false,
    /** Finished sentences given to the translator as context (Index prompt 前文); 0 = none. */
    previousSentences: Int = PREVIOUS_SENTENCES,
    /** Also ask [judge] to check every final translation (off by default: little gain in logs, adds time per final). */
    private val checkTranslations: Boolean = false,
    /** Raw session events (asr / mt / jev) for the session log. Called on worker threads; must not block. */
    private val log: (Map<String, Any?>) -> Unit = {},
    /** Read live: false = "only finished sentences", display drafts are not recognized or translated at all. */
    private val drafts: () -> Boolean = { true },
) {
    private class Queued(val job: AsrJob, val submittedAt: Long)
    private class MtJob(val q: Queued, val ja: String, val asrStartAt: Long, val asrEndAt: Long, val setup: Setup)

    /** The setup new segments take; see [Setup]. The constructor's settings are the first one. */
    @Volatile private var current = Setup(mt, scene, source, target, byokPrompt, judge, dedupeJoin, previousSentences)
    /** Every setup a pipeline worker may still call (current + those of segments in flight), so [stop] cancels all. */
    private val live: MutableSet<Setup> = java.util.concurrent.ConcurrentHashMap.newKeySet<Setup>().apply { add(current) }
    /** The setup each recent segment took (ASR thread writes, MT / check threads read through their jobs). */
    private val segSetup = java.util.concurrent.ConcurrentHashMap<Long, Setup>()

    /**
     * New segments use [s] from now on; a segment already started keeps its own. Settings fixed for the session
     * ([judgeEnds], [checkTranslations], the drafts switch) are not part of a setup. Thread-safe; returns false
     * once the pipeline is stopped (the caller then releases [s] itself).
     */
    fun setSetup(s: Setup): Boolean {
        synchronized(lock) {
            if (!running) return false
            live.add(s)
            current = s
        }
        if (!running) { s.mt.cancel(); s.judge?.cancel() } // stop() ran meanwhile and did not see it
        return true
    }
    /**
     * The setup of [seg], fixed by whichever asks first: the audio thread when the utterance starts ([judged]) or the
     * ASR thread at its first recognition. An entry stays until the segment's final is delivered ([forget]); [live]
     * keeps every setup of the session, since a slow MT or check may still use an old one when stop() cancels it.
     */
    private fun setupOf(seg: Long): Setup = segSetup.getOrPut(seg) { current }
    /** The final of [seg] was delivered: nothing asks for its setup any more (its jobs carry their own). */
    private fun forget(seg: Long) {
        segSetup.remove(seg)
        // a segment that ended without a final (empty, dropped by stop) would otherwise stay: keep the table bounded
        if (segSetup.size > 64) segSetup.keys.removeIf { it < seg - 32 }
    }

    /**
     * For the [Segmenter] when an utterance starts (audio thread, never blocks): true = it may wait for Jev answers.
     * Fixes the segment's setup now, so its sentence ends and its translation use the same one.
     */
    fun judged(segmentId: Long): Boolean = judgeEnds && setupOf(segmentId).judge != null

    /** Jev answers for endOfSpeech drafts, by (segment, revision); true = unfinished. */
    private val hints = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, Boolean>()
    /** The newest endOfSpeech draft waiting for Jev; an older one that has not started is replaced. */
    private val pendingJudge = java.util.concurrent.atomic.AtomicReference<Triple<AsrJob, String, Judge>?>(null)

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
            // The session switches decide whether these run at all (both are fixed for the session); a setup without
            // a judge (its key is missing for now) simply finds nothing to ask and pauses the feature.
            if (judgeEnds) Thread(guarded(::judgeLoop), "jpsub-jev") else null,
            if (checkTranslations) Thread(guarded(::checkLoop), "jpsub-check") else null,
        ).onEach { it.start() }
    }

    /** Non-blocking. Results produced after this call are discarded. */
    fun stop() {
        synchronized(lock) { running = false }
        live.forEach { it.mt.cancel(); it.judge?.cancel() }
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
            // A sentence-end candidate is a question the Segmenter is waiting on: a plain draft must not evict it,
            // only a newer candidate or the final (which ends the wait) may.
            val evicts = job.isFinal || job.endOfSpeech
            asrQueue.removeIf { it.job.segmentId == job.segmentId && !it.job.isFinal && (evicts || !it.job.endOfSpeech) }
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

    /** Raw recognitions of this segment's drafts (audio length, text), newest last; asr thread only. */
    private val segDrafts = HashMap<Long, MutableList<Pair<Int, String>>>()

    /**
     * ReazonSpeech sometimes drops everything before an internal pause when the same utterance is recognized again
     * with more audio (measured: "スパチありがとうございま" at 1.6 s became "いや相当でかいと思うけど" at 4.8 s; the audio is
     * an exact prefix, fp32 does the same). If the newest earlier draft that is long enough to be real content is
     * mostly missing from the new result, its text is kept and only the audio after it is recognized.
     */
    private fun recognizeKeepingLostDraft(job: AsrJob, setup: Setup): String {
        val whole = asr.recognize(job.samples).trim()
        val hist = segDrafts.getOrPut(job.segmentId) { ArrayList() }
        segDrafts.keys.removeIf { it < job.segmentId - 4 }
        // only the newest eligible draft counts: an older one it already replaced is not brought back
        val last = hist.lastOrNull { (n, t) -> n < job.samples.size && n >= LOST_MIN_SAMPLES && t.length >= LOST_MIN_CHARS }
        val lost = last?.takeIf { keptRatio(it.second, whole) < 0.5 }
        // a stopped pipeline must not start another native decode (stop waits for it)
        val text = if (lost == null || !running) whole else {
            val rest = job.samples.copyOfRange(lost.first, job.samples.size)
            val tail = if (rest.size >= sampleRate / 5) asr.recognize(rest).trim() else ""
            log(mapOf("ev" to "asr_keep", "seg" to job.segmentId, "rev" to job.revision, "whole" to whole,
                "kept" to lost.second, "kept_samples" to lost.first, "tail" to tail))
            if (setup.dedupeJoin) joinWithoutRepeat(lost.second, tail, whole) else join(lost.second, tail)
        }
        // the history holds what was shown, so a second loss keeps the text an earlier repair saved
        if (!job.isFinal) hist += job.samples.size to text
        return text
    }

    private fun asrLoop() {
        while (running && !failed) {
            val q = take(asrQueue) { it.job.isFinal } ?: continue
            if (!q.job.isFinal && !q.job.endOfSpeech && skipDisplayDraft()) continue
            // the segment's settings are fixed before its first recognition, so a change during it applies to the next one
            val setup = setupOf(q.job.segmentId)
            val start = clock()
            val ja = recognizeKeepingLostDraft(q.job, setup)
            val end = clock()
            if (!running) return
            val job = q.job
            log(mapOf("ev" to "asr", "seg" to job.segmentId, "rev" to job.revision, "final" to job.isFinal,
                "eos" to job.endOfSpeech, "audio_ms" to audioMs(job), "ms" to end - start, "text" to ja))
            // judgeEnds alone: a session may start without a key and get one later (the setup then has a judge)
            if (judgeEnds && job.endOfSpeech) {
                // nothing recognised = nothing to wait for; otherwise ask, replacing an older question not yet sent.
                // No judge in this segment's setup (key missing for now): no question, the segmenter waits as without Jev
                if (ja.isEmpty() || setup.judge == null) hints[job.segmentId to job.revision] = false
                else pendingJudge.set(Triple(job, ja, setup.judge))
            }
            synchronized(lock) {
                // A draft that already ran is kept even if a newer draft is queued: on a slow phone every
                // draft would otherwise be superseded before it reaches MT and nothing would be shown.
                if (!q.job.isFinal && ja.isEmpty()) return@synchronized
                mtQueue.removeIf { it.q.job.segmentId == q.job.segmentId && !it.q.job.isFinal }
                // a sentence-end candidate was recognized for Jev; it is only translated if drafts are wanted now
                if (!q.job.isFinal && !drafts()) return@synchronized
                mtQueue.addLast(MtJob(q, ja, start, end, setup))
                if (q.job.isFinal) inFlightFinals-- // now counted by mtQueue
            }
            // the final carries its setup on (MtJob): the segment needs no lookup any more
            if (q.job.isFinal) forget(q.job.segmentId)
        }
    }

    private fun judgeLoop() {
        while (running && !failed) {
            val (job, ja, judge) = pendingJudge.getAndSet(null) ?: run { Thread.sleep(10); null } ?: continue
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

    /** Japanese of the last [MAX_PREVIOUS_SENTENCES] non-empty finals translated, oldest first. MT thread only. */
    private val recentFinals = ArrayDeque<String>()
    /** newest segment whose final went through [rememberFinal] (MT thread only) */
    private var rememberedSeg = 0L
    /**
     * The context of a segment, fixed once the final before it was translated, so its later drafts, its final and a
     * retry share one prompt (and the reuse cache keeps working). Until then (a draft of the next segment translated
     * before the previous final) it is computed fresh and not kept. MT thread only.
     */
    private val contexts = HashMap<Long, List<String>>()
    private fun previousFor(seg: Long, n: Int): List<String> {
        contexts[seg]?.let { return it }
        val now = recentFinals.toList().takeLast(n)
        if (rememberedSeg >= seg - 1) { contexts.keys.removeIf { it < seg - 8 }; contexts[seg] = now }
        return now
    }
    private fun rememberFinal(seg: Long, ja: String) {
        if (seg > rememberedSeg) rememberedSeg = seg
        if (ja.isBlank()) return
        recentFinals.addLast(ja)
        while (recentFinals.size > MAX_PREVIOUS_SENTENCES) recentFinals.removeFirst() // a later setup may ask for more
    }

    /** One MT call, logged with everything needed to replay it. */
    private fun translate(job: AsrJob, setup: Setup, attempt: Int, ja: String, notes: List<String>,
                          previous: List<String>): MtResult? {
        val prompt = if (setup.byokPrompt) byokUser(ja, notes, previous)
            else indexPrompt(ja, setup.scene, notes, previous, setup.source, setup.target)
        // Greedy decoding with state cleared per call: the same prompt gives the same output. A final usually
        // repeats its last draft's text, so that segment's last good translation is reused instead of computed again.
        // The pipeline (and so this cache) lives for one session, with one translator.
        val hit = lastMt?.takeIf { it.seg == job.segmentId && it.prompt == prompt && it.setup == setup.version }
        val t0 = clock()
        val r = hit?.result ?: setup.mt.translate(prompt)
        log(mapOf("ev" to "mt", "seg" to job.segmentId, "rev" to job.revision, "final" to job.isFinal, "attempt" to attempt,
            "prompt" to prompt, "request" to r?.request, "raw" to r?.raw, "stop" to r?.stop?.toString(),
            "http" to r?.http, "response" to r?.response, "error" to r?.error, "prompt_tokens" to r?.promptTokens,
            "output_tokens" to r?.outputTokens, "prefill_ms" to r?.prefillMs, "decode_ms" to r?.decodeMs,
            "first_content_ms" to r?.firstContentMs, "cached_tokens" to r?.cachedTokens, "thinking" to r?.thinking, "ms" to clock() - t0,
            // reused: everything but ms is that of the earlier call (rev reused_from)
            "reused" to (hit != null), "reused_from" to hit?.rev, "setup" to setup.version))
        if (hit == null && r != null && r.usable) lastMt = LastMt(job.segmentId, job.revision, prompt, r, setup.version)
        return r
    }

    private class LastMt(val seg: Long, val rev: Int, val prompt: String, val result: MtResult, val setup: Int)
    /** MT thread only. */
    private var lastMt: LastMt? = null

    /**
     * Final only: ask Jev what is wrong with [zh]; for a class it names reliably, translate once more with the
     * matching note. Returns the text to show (the retry if it produced one, else [zh]).
     */
    private fun checked(j: MtJob, zh: String, previous: List<String>, judgeMs: (Long) -> Unit, retryMs: (Long) -> Unit): String {
        // the segment's setup: no judge there (key missing for now) = this final is not checked
        val judge = j.setup.judge?.takeIf { checkTranslations } ?: return zh
        if (zh.isEmpty()) return zh
        val job = j.q.job
        val c0 = clock()
        val call = judge.mistranslation(j.ja, zh)
        judgeMs(clock() - c0) // the Jev call only; a retranslation below is translation time
        if (!running) { logJev("mistranslation", job.segmentId, job.revision, call, "stopped"); return zh }
        val note = call.choice?.let { fixNote(it, j.setup.source, j.setup.target) }?.takeIf { (call.confidence ?: 0.0) >= FIX_MIN_CONFIDENCE }
        if (note == null) {
            logJev("mistranslation", job.segmentId, job.revision, call, if (call.answer == null) "none" else "keep")
            return zh
        }
        logJev("mistranslation", job.segmentId, job.revision, call, "retranslate:${call.choice}")
        // only the MT thread translates (one translator, one cache): hand the retry over and wait for it
        val req = Retry(j, note, previous)
        synchronized(lock) { retryQueue.addLast(req) }
        while (running && !failed && !req.done.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)) Unit
        // an internal failure of the MT thread fails the pipeline (guarded); never publish on top of it
        if (req.crashed) throw IllegalStateException("retranslation failed on the MT thread")
        retryMs(req.ms)
        return req.result?.takeIf { it.usable }?.text ?: zh
    }

    /** A retranslation the check thread asks the MT thread for; [done] is counted down whatever happened. */
    private class Retry(val j: MtJob, val note: String, val previous: List<String>) {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var result: MtResult? = null
        @Volatile var ms = 0L
        /** the MT thread threw while translating it (not a failed or unusable translation, which keeps the first one) */
        @Volatile var crashed = false
    }
    private val retryQueue = LinkedBlockingDeque<Retry>()

    /** A final on its way through the check thread: [zh] is the first translation (or the failure text). */
    private class ToCheck(val j: MtJob, val zh: String, val needsCheck: Boolean, val start: Long, val mtMs: Long,
                          /** the context the first translation used: a retry uses the same, however late it runs */
                          val previous: List<String>)
    private val checkQueue = LinkedBlockingDeque<ToCheck>()
    /** finals queued for or inside the check (guarded by [lock]); they are also in [inFlightFinals] */
    private var inCheck = 0

    /**
     * With [checkTranslations], every final goes through here in order (also one that is not checked, so a later
     * segment never overtakes an earlier one on the board). The MT thread meanwhile goes on with the next segments.
     */
    private fun checkLoop() {
        while (running && !failed) {
            val c = synchronized(lock) { checkQueue.pollFirst() } ?: run { Thread.sleep(10); null } ?: continue
            val job = c.j.q.job
            var checkMs = 0L; var retryMs = 0L
            val zh = if (c.needsCheck) checked(c.j, c.zh, c.previous, { checkMs = it }, { retryMs = it }) else c.zh
            if (!running || failed) return
            val end = clock()
            // the interim line took the final's revision; the final goes out as revision + 1
            onCaption(caption(c.j, true, job.revision + 1, zh, c.start, end, c.mtMs + retryMs, checkMs))
            val backlog = synchronized(lock) {
                pendingFinalAudioMs -= audioMs(job); inCheck--; inFlightFinals--
                pendingFinals() + inFlightFinals
            }
            onBacklog(backlog)
        }
    }

    private fun mtLoop() {
        while (running && !failed) {
            // a retranslation the check thread waits on goes first; it is part of a final already counted
            val req = synchronized(lock) { retryQueue.pollFirst() }
            if (req != null) {
                try {
                    val t0 = clock()
                    req.result = translate(req.j.q.job, req.j.setup, 2, req.j.ja, listOf(req.note), req.previous)
                    req.ms = clock() - t0
                } catch (t: Throwable) {
                    // visible to the waiting check thread before it wakes: it must not publish this final as checked
                    req.crashed = true; throw t
                } finally { req.done.countDown() }
                continue
            }
            val j = take(mtQueue) { it.q.job.isFinal } ?: continue
            if (!j.q.job.isFinal && skipDisplayDraft()) continue
            val job = j.q.job
            val start = clock()
            val ctx = previousFor(job.segmentId, j.setup.previousSentences)
            val r = if (j.ja.isEmpty()) null else translate(job, j.setup, 1, j.ja, emptyList(), ctx)
            if (job.isFinal) rememberFinal(job.segmentId, j.ja) // context for the following segments, also when this one fails
            if (!running) return
            var zh = if (j.ja.isEmpty()) "" else r?.takeIf { it.usable }?.text
            if (zh == null) {
                if (!job.isFinal) continue
                zh = "[翻译失败] ${j.ja}"
            }
            val end = clock()
            if (job.isFinal && checkTranslations) {
                val real = r?.takeIf { it.usable } != null && zh.isNotEmpty()
                // live mode shows the first translation while Jev checks it; "only finished sentences" hides it
                if (real) onCaption(caption(j, false, job.revision, zh, start, end, end - start))
                synchronized(lock) { inCheck++; checkQueue.addLast(ToCheck(j, zh, real, start, end - start, ctx)) }
                continue
            }
            val backlog = synchronized(lock) {
                if (job.isFinal) pendingFinalAudioMs -= audioMs(job)
                pendingFinals() + inFlightFinals
            }
            onCaption(caption(j, job.isFinal, job.revision, zh, start, end, end - start))
            if (job.isFinal) synchronized(lock) { inFlightFinals-- }
            onBacklog(backlog - if (job.isFinal) 1 else 0)
        }
    }

    private fun caption(j: MtJob, final: Boolean, revision: Int, zh: String, start: Long, end: Long, mtMs: Long, checkMs: Long = 0): Caption {
        val job = j.q.job
        return Caption(
            job.segmentId, revision, final, j.ja, zh.trim(),
            Timing(
                audioMs = audioMs(job),
                segmentWaitMs = j.q.submittedAt - job.audioEndAt,
                asrQueueMs = j.asrStartAt - j.q.submittedAt,
                asrMs = j.asrEndAt - j.asrStartAt,
                mtQueueMs = start - j.asrEndAt,
                mtMs = mtMs,
                mtDoneMs = end - job.audioEndAt,
                checkMs = checkMs,
            ),
            job.audioEndAt,
            job.timeEstimated,
            sourceRevision = job.revision,
        )
    }

    private fun audioMs(job: AsrJob) = job.samples.size * 1000L / sampleRate

    /**
     * A draft only updates the live line. Skip it when drafts are off, or when a final is waiting for ASR or MT:
     * on a slow phone drafts otherwise take the time finals need and the backlog keeps growing.
     */
    // finals that only wait for their Jev check do not hold drafts back (the MT thread is free for them)
    private fun skipDisplayDraft() = !drafts() || synchronized(lock) { pendingFinals() + inFlightFinals - inCheck > 0 }

    companion object {
        /** Jev "unfinished" probability from which a pause is held (140-pair test: catches 30/31, waits wrongly 16/109). */
        const val UNFINISHED_THRESHOLD = 0.65
        /** Below this Jev confidence an error class is not acted on (untested guess). */
        const val FIX_MIN_CONFIDENCE = 0.6
        /** a draft that may be kept (see recognizeKeepingLostDraft): at least 1.5 s of audio and 5 characters */
        const val LOST_MIN_SAMPLES = 24_000
        const val LOST_MIN_CHARS = 5
        /** finished sentences given to the translator as context (Index prompt 前文): default, and the most offered */
        const val PREVIOUS_SENTENCES = 2
        const val MAX_PREVIOUS_SENTENCES = 5
        /**
         * [kept] + [tail], without the start of [tail] when it repeats the end of [kept] (at least [MIN_REPEAT] chars)
         * and the recognition of the whole audio ([whole]) has that piece at most once. A heuristic: [whole] may itself
         * miss words, so a real repeat can be dropped; with the piece twice in [whole] both are kept.
         * A piece with Latin letters (English) must be whole words, in [kept], [tail] and each counted occurrence in
         * [whole] ("PLANET" + "NETWORK" shares "NET" but no word); Japanese pieces match as before.
         */
        fun joinWithoutRepeat(kept: String, tail: String, whole: String): String {
            for (n in minOf(kept.length, tail.length) downTo MIN_REPEAT) {
                val piece = tail.substring(0, n)
                if (!kept.endsWith(piece)) continue
                val words = latin(piece)
                if (words && !(boundary(kept, kept.length - n) && boundary(tail, n))) continue
                val inWhole = (0..whole.length - n).count { i -> whole.startsWith(piece, i) && (!words || boundary(whole, i) && boundary(whole, i + n)) }
                // Latin: the separator after the dropped words is rebuilt by join(); Japanese keeps the rest exactly as before
                val rest = tail.substring(n).let { if (words) it.trimStart() else it }
                return if (inWhole >= 2) join(kept, tail) else join(kept, rest)
            }
            return join(kept, tail)
        }
        const val MIN_REPEAT = 3

        /** a letter of the Latin (incl. full-width), Cyrillic or Greek script: text whose words are separated by spaces */
        private fun spacedLetter(c: Char) = c.isLetter() && Character.UnicodeScript.of(c.code).let {
            it == Character.UnicodeScript.LATIN || it == Character.UnicodeScript.CYRILLIC || it == Character.UnicodeScript.GREEK
        }
        private fun latin(s: String) = s.any(::spacedLetter)
        /** no word character on both sides of position [i] */
        private fun boundary(s: String, i: Int) = i <= 0 || i >= s.length || !word(s, i - 1) || !word(s, i)
        /** a word character; an apostrophe inside a word (CAN'T, DON’T, MP3'S) is part of it */
        private fun word(s: String, i: Int) = s[i].isLetterOrDigit() ||
            (s[i] == '\'' || s[i] == '’') && i > 0 && i < s.length - 1 && s[i - 1].isLetterOrDigit() && s[i + 1].isLetterOrDigit()
        /**
         * Latin-script text is joined with a space when neither side of the join is CJK (or already whitespace), so
         * "THANK YOU." + "SO" gets one; Japanese, also with a Latin word in it ("SC" + "です"), is joined directly.
         */
        fun join(a: String, b: String) = when {
            a.isEmpty() -> b; b.isEmpty() -> a
            (latin(a) || latin(b)) && !a.last().isWhitespace() && !b.first().isWhitespace() &&
                !cjk(a.last()) && !cjk(b.first()) -> "$a $b"
            else -> a + b
        }
        /** a character of text written without spaces: anything at or above the CJK ranges except spaced letters (full-width Latin) */
        private fun cjk(c: Char) = c.code >= 0x2E80 && !spacedLetter(c)

        /** share of [a]'s characters found, in order, in [b] */
        fun keptRatio(a: String, b: String): Double {
            // longest common subsequence; strings are a sentence long
            val dp = IntArray(b.length + 1)
            for (x in a) {
                var prev = 0
                for (j in 1..b.length) { val cur = dp[j]; dp[j] = if (x == b[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1]); prev = cur }
            }
            return dp[b.length].toDouble() / maxOf(a.length, 1)
        }
    }

    // caller holds lock
    private fun pendingFinals() = asrQueue.count { it.job.isFinal } + mtQueue.count { it.q.job.isFinal }
}

/**
 * What the overlay shows: finished sentences, which never change once shown, and below them one separate draft
 * line for the sentence still being spoken. A draft never replaces or pushes out a final.
 * The oldest final leaves only while there are more than [minFinals] finals AND their Chinese text is longer than
 * [maxChars] code points (drafts and Japanese are not counted).
 * Guards: newer revisions only, nothing after a segment's final, no late drafts of older segments.
 */
class CaptionBoard(private var minFinals: Int = DEFAULT_SENTENCES, private var maxChars: Int = DEFAULT_CHARS) {
    private val finals = ArrayDeque<Caption>()
    private var draft: Caption? = null
    /** newest final ever accepted: a removed sentence must not come back */
    private var lastFinal = Long.MIN_VALUE

    /** @return true if the board changed */
    @Synchronized
    fun accept(c: Caption): Boolean {
        // a segment at or before the newest final is done: no second final, no late draft, also after it was trimmed
        if (c.segmentId <= lastFinal) return false
        val d = draft
        if (c.isFinal) {
            if (d != null && d.segmentId <= c.segmentId) draft = null
            lastFinal = c.segmentId
            if (c.zh.isEmpty()) return d != null && draft == null
            finals.addLast(c)
            trim()
            return true
        }
        if (d != null && (c.segmentId < d.segmentId || (c.segmentId == d.segmentId && c.revision <= d.revision))) return false
        draft = c
        return true
    }

    /** Settings changed while running. @return true if sentences were removed */
    @Synchronized
    fun setLimits(sentences: Int, chars: Int): Boolean {
        minFinals = sentences.coerceAtLeast(1); maxChars = chars.coerceAtLeast(0)
        return trim()
    }

    private fun trim(): Boolean {
        var removed = false
        while (finals.size > minFinals && finals.sumOf { it.zh.codePointCount(0, it.zh.length) } > maxChars) {
            finals.removeFirst(); removed = true
        }
        return removed
    }

    /** finals oldest first, then the draft (if any) */
    @Synchronized
    fun snapshot(): List<Caption> = finals.toList() + listOfNotNull(draft)

    companion object {
        const val DEFAULT_SENTENCES = 2
        const val DEFAULT_CHARS = 100
    }
}
