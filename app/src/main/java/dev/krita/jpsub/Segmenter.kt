package dev.krita.jpsub

/**
 * Turns a 16 kHz mono stream into ASR jobs.
 *
 * Feed it fixed [frameSize] frames together with a speech probability for each frame
 * (Silero VAD on device, anything in tests). It emits:
 * - a draft with the whole utterance so far every [draftEveryMs] while speech continues;
 * - a final when silence lasts [endSilenceMs], or when the utterance reaches [maxUtteranceMs].
 *   A forced cut goes into the longest pause of the utterance (see [forcedCut]); the audio after the
 *   cut starts the next utterance, so no sample is lost or duplicated.
 */
class Segmenter(
    private val emit: (AsrJob) -> Unit,
    private val sampleRate: Int = 16_000,
    val frameSize: Int = 512,
    private val threshold: Float = 0.5f,
    private val endSilenceMs: Int = 400,
    private val draftEveryMs: Int = 700,
    private val minDraftMs: Int = 600,
    private val maxUtteranceMs: Int = 7_000,
    private val preRollMs: Int = 200,
    /**
     * A: an utterance with less speech than this is not finalized at [endSilenceMs]; it waits up to
     * [maxHoldSilenceMs] of silence for the speaker to continue, and continuation joins the same segment.
     * Short fragments ("えっと", a clause cut by a breath) are what ASR and MT get wrong alone. 0 = off.
     */
    private val minFinalMs: Int = 0,
    private val maxHoldSilenceMs: Int = 1_500,
    /**
     * > 0 enables the judged hold. A candidate end is a pause of [endSilenceMs] or a forced cut at [maxUtteranceMs];
     * for each one a draft with [AsrJob.endOfSpeech] holding exactly the audio up to that end is emitted, and
     * [holdHint] is asked about that revision: true = unfinished (keep listening), false = finished (final now),
     * null = no answer yet (wait at most this long after the candidate was emitted, then final).
     * Bounds: a pause still ends at [maxHoldSilenceMs]; a forced cut that was judged unfinished is cut for good at
     * twice [maxUtteranceMs]. Answers for an older revision are never used.
     */
    private val hintWaitMs: Int = 0,
    /** Read on the audio thread; must not block. */
    private val holdHint: (segmentId: Long, revision: Int) -> Boolean? = { _, _ -> null },
) {
    private val frameMs = frameSize * 1000 / sampleRate
    private val preRollFrames = preRollMs / frameMs

    private class Frame(val pcm: FloatArray, val speech: Boolean, val endAt: Long, val estimated: Boolean)

    private val preRoll = ArrayDeque<Frame>()
    private val frames = ArrayList<Frame>()
    private var inSpeech = false
    private var silentFrames = 0
    private var framesSinceDraft = 0
    private var segmentId = 0L
    private var revision = 0
    /** end-of-speech candidate for the current pause: its revision (-1 = none) and frames.size when it was emitted */
    private var tailRevision = -1
    private var tailAtFrames = 0
    /** forced-cut candidate: revision (-1 = none), where it cuts, and frames.size when it was emitted */
    private var cutRevision = -1
    private var cutIndex = 0
    private var cutAtFrames = 0
    private val hardMaxMs = if (hintWaitMs > 0) 2 * maxUtteranceMs else maxUtteranceMs

    /**
     * @param now [monoMs] capture time of this frame's last sample
     * @param estimated [now] is a read time rather than a HAL capture timestamp
     */
    fun accept(pcm: FloatArray, speechProb: Float, now: Long, estimated: Boolean = false) {
        val f = Frame(pcm, speechProb >= threshold, now, estimated)
        if (!inSpeech) {
            if (!f.speech) {
                preRoll.addLast(f)
                if (preRoll.size > preRollFrames) preRoll.removeFirst()
                return
            }
            inSpeech = true
            segmentId++
            revision = 0
            frames.addAll(preRoll)
            preRoll.clear()
            silentFrames = 0
            framesSinceDraft = 0
        }

        frames.add(f)
        framesSinceDraft++
        silentFrames = if (f.speech) 0 else silentFrames + 1
        if (f.speech) tailRevision = -1

        val durMs = frames.size * frameMs
        val silMs = silentFrames * frameMs
        // keep ~100 ms of the trailing silence
        val speechEnd = (frames.size - silentFrames + 100 / frameMs).coerceIn(1, frames.size)
        val inPause = silMs >= endSilenceMs
        when {
            // a held pause is bounded by maxHoldSilenceMs; no forced cut inside it (the final drops the silence)
            inPause && (durMs >= hardMaxMs || !holding(silMs)) -> finalize(speechEnd, continueSpeech = false)
            inPause -> if (hintWaitMs > 0 && tailRevision < 0 && speechMs() >= minFinalMs) {
                tailRevision = ++revision
                tailAtFrames = frames.size
                emit(AsrJob(segmentId, tailRevision, false, concat(speechEnd), frames[speechEnd - 1].endAt, frames[speechEnd - 1].estimated, endOfSpeech = true))
            }
            durMs >= maxUtteranceMs -> forcedCutStep(durMs)
            framesSinceDraft * frameMs >= draftEveryMs && durMs >= minDraftMs && f.speech -> {
                framesSinceDraft = 0
                emit(AsrJob(segmentId, ++revision, false, concat(frames.size), now, estimated))
            }
        }
    }

    private fun speechMs() = frames.count { it.speech } * frameMs

    /** Wait for continuation instead of finalizing at the normal end silence. Bounded by [maxHoldSilenceMs]. */
    private fun holding(silMs: Int): Boolean {
        if (silMs >= maxHoldSilenceMs) return false
        if (speechMs() < minFinalMs) return true
        if (hintWaitMs <= 0) return false
        if (tailRevision < 0) return true // the candidate is emitted on this frame
        return holdHint(segmentId, tailRevision) ?: ((frames.size - tailAtFrames) * frameMs < hintWaitMs)
    }

    /** Without a judge, cut now. With one, ask about the cut first; an unfinished head keeps growing up to [hardMaxMs]. */
    private fun forcedCutStep(durMs: Int) {
        if (hintWaitMs <= 0 || durMs >= hardMaxMs) { finalize(forcedCut(), continueSpeech = true); return }
        if (cutRevision < 0) {
            cutIndex = forcedCut()
            cutRevision = ++revision
            cutAtFrames = frames.size
            emit(AsrJob(segmentId, cutRevision, false, concat(cutIndex), frames[cutIndex - 1].endAt, frames[cutIndex - 1].estimated, endOfSpeech = true))
            return
        }
        when (holdHint(segmentId, cutRevision)) {
            true -> Unit
            false -> finalize(cutIndex, continueSpeech = true)
            null -> if ((frames.size - cutAtFrames) * frameMs >= hintWaitMs) finalize(cutIndex, continueSpeech = true)
        }
    }

    /**
     * Where to split an utterance that hit [maxUtteranceMs].
     * Prefer the longest VAD-silent run (a real pause, often a sentence boundary) in the later 2/3 of the
     * utterance, cutting in its middle. ASR models drop a half-spoken word at the end of a chunk, so
     * cutting mid-word loses it; a pause loses nothing. Without any pause, cut at the end and let the
     * next chunk start fresh.
     */
    private fun forcedCut(): Int {
        var bestStart = -1; var bestLen = 0
        var i = frames.size / 3
        while (i < frames.size) {
            if (frames[i].speech) { i++; continue }
            val s = i
            while (i < frames.size && !frames[i].speech) i++
            if (i - s >= bestLen) { bestLen = i - s; bestStart = s } // ties → later pause
        }
        return if (bestLen >= 2) bestStart + bestLen / 2 + 1 else frames.size
    }

    /** Force-final whatever is pending (stop button). */
    fun flush() {
        if (inSpeech && frames.isNotEmpty()) finalize(frames.size, continueSpeech = false)
    }

    private fun finalize(count: Int, continueSpeech: Boolean) {
        emit(AsrJob(segmentId, ++revision, true, concat(count), frames[count - 1].endAt, frames[count - 1].estimated))
        tailRevision = -1
        cutRevision = -1
        val tail = frames.subList(count, frames.size).toList()
        frames.clear()
        if (continueSpeech) {
            segmentId++
            revision = 0
            frames.addAll(tail)
            framesSinceDraft = 0
            silentFrames = 0
        } else {
            inSpeech = false
            preRoll.clear()
        }
    }

    private fun concat(count: Int): FloatArray {
        val out = FloatArray(count * frameSize)
        for (i in 0 until count) System.arraycopy(frames[i].pcm, 0, out, i * frameSize, frameSize)
        return out
    }
}
