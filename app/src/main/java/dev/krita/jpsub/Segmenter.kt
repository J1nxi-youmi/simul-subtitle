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
) {
    private val frameMs = frameSize * 1000 / sampleRate
    private val preRollFrames = preRollMs / frameMs

    private class Frame(val pcm: FloatArray, val speech: Boolean, val endAt: Long)

    private val preRoll = ArrayDeque<Frame>()
    private val frames = ArrayList<Frame>()
    private var inSpeech = false
    private var silentFrames = 0
    private var framesSinceDraft = 0
    private var segmentId = 0L
    private var revision = 0

    /** @param now [monoMs] at the end of this frame */
    fun accept(pcm: FloatArray, speechProb: Float, now: Long) {
        val f = Frame(pcm, speechProb >= threshold, now)
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

        val durMs = frames.size * frameMs
        when {
            silentFrames * frameMs >= endSilenceMs -> {
                // keep ~100 ms of the trailing silence
                finalize((frames.size - silentFrames + 100 / frameMs).coerceIn(1, frames.size), continueSpeech = false)
            }
            durMs >= maxUtteranceMs -> finalize(forcedCut(), continueSpeech = true)
            framesSinceDraft * frameMs >= draftEveryMs && durMs >= minDraftMs && f.speech -> {
                framesSinceDraft = 0
                emit(AsrJob(segmentId, ++revision, false, concat(frames.size), now))
            }
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
        emit(AsrJob(segmentId, ++revision, true, concat(count), frames[count - 1].endAt))
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
