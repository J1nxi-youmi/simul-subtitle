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
     * > 0 enables the judged hold: at [endSilenceMs] a draft with [AsrJob.endOfSpeech] covering the utterance up to the
     * pause is emitted, and [holdHint] is asked about exactly that revision. true = unfinished (hold up to
     * [maxHoldSilenceMs]), false = finished (final now), null = no answer yet (wait until this much silence, then final).
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
    /** revision of the end-of-speech draft for the current pause, -1 = none yet */
    private var tailRevision = -1

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
        when {
            silMs >= endSilenceMs && !holding(silMs) -> finalize(speechEnd, continueSpeech = false)
            durMs >= maxUtteranceMs -> finalize(forcedCut(), continueSpeech = true)
            silMs >= endSilenceMs -> {
                if (hintWaitMs > 0 && tailRevision < 0 && speechMs() >= minFinalMs) {
                    tailRevision = ++revision
                    emit(AsrJob(segmentId, tailRevision, false, concat(speechEnd), frames[speechEnd - 1].endAt, frames[speechEnd - 1].estimated, endOfSpeech = true))
                }
            }
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
        if (tailRevision < 0) return true // the end-of-speech draft is emitted on this frame
        return holdHint(segmentId, tailRevision) ?: (silMs < hintWaitMs)
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
