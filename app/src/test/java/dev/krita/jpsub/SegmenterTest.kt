package dev.krita.jpsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {
    private val frame = 512 // 32 ms @16k

    /** Feeds (speech?, ms) runs; each frame's samples carry its global index so cuts can be located exactly. */
    private fun run(pattern: List<Pair<Boolean, Int>>, seg: Segmenter): Int {
        var idx = 0
        for ((speech, ms) in pattern) repeat(ms / 32) {
            idx++
            seg.accept(FloatArray(frame) { idx.toFloat() }, if (speech) 0.9f else 0.1f, idx * 32L)
        }
        return idx
    }

    private fun frameIds(j: AsrJob) = (0 until j.samples.size / frame).map { j.samples[it * frame].toInt() }

    @Test
    fun draftsThenOneFinalPerUtterance() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add)
        run(listOf(false to 512, true to 2016, false to 608, true to 800, false to 608), s)
        val finals = jobs.filter { it.isFinal }
        assertEquals(listOf(1L, 2L), finals.map { it.segmentId })
        assertTrue(jobs.count { !it.isFinal && it.segmentId == 1L } >= 2)
        val seg1 = jobs.filter { it.segmentId == 1L }
        assertEquals(seg1.map { it.revision }.sorted(), seg1.map { it.revision })
        assertTrue(seg1.last().isFinal)
        val sec = finals[0].samples.size / 16_000f
        assertTrue("final length $sec", sec in 2.0f..2.5f)
        // audioEndAt is the end of the last sample *in the job*, not the time the silence was confirmed
        assertEquals(frameIds(finals[0]).last() * 32L, finals[0].audioEndAt)
    }

    /**
     * The final's provenance is that of its last *kept* frame (end of speech + ~100 ms), not of the later
     * silent frame that triggered the cut. Both directions, so neither value can pass by accident.
     */
    @Test
    fun finalTakesProvenanceOfItsLastKeptFrameNotTheTriggeringOne() {
        for (keptEstimated in listOf(true, false)) {
            val jobs = ArrayList<AsrJob>()
            val s = Segmenter(jobs::add)
            var idx = 0
            fun feed(speech: Boolean, frames: Int, est: Boolean) = repeat(frames) { idx++; s.accept(FloatArray(frame), if (speech) 0.9f else 0.1f, idx * 32L, est) }
            feed(false, 10, false); feed(true, 50, false)
            val speechEnd = idx
            feed(false, 3, keptEstimated)    // the ~100 ms of trailing silence the final keeps
            feed(false, 20, !keptEstimated)  // later silence, including the frame that triggers the final
            val f = jobs.single { it.isFinal }
            assertEquals((speechEnd + 3) * 32L, f.audioEndAt)
            assertEquals("keptEstimated=$keptEstimated", keptEstimated, f.timeEstimated)
        }
    }

    @Test
    fun forcedCutLandsInThePauseAndLosesNoFrame() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 7_000, endSilenceMs = 400)
        // 4.5 s speech, 256 ms pause (shorter than endSilence → no natural final), 4 s speech
        val total = run(listOf(true to 4512, false to 256, true to 4000), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        assertEquals(2, finals.size)
        val ids = finals.flatMap(::frameIds)
        assertEquals("every frame exactly once", (1..total).toList(), ids)
        // first cut is inside the pause (frames 142..149)
        val cutAfter = frameIds(finals[0]).last()
        assertTrue("cut after frame $cutAfter", cutAfter in 142..149)
    }

    @Test
    fun forcedCutWithoutPauseAlsoLosesNothing() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000)
        val total = run(listOf(true to 10_016), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        assertTrue(finals.size >= 3)
        assertEquals((1..total).toList(), finals.flatMap(::frameIds))
    }

    @Test
    fun silenceProducesNothing() {
        val jobs = ArrayList<AsrJob>()
        run(listOf(false to 5000), Segmenter(jobs::add))
        assertTrue(jobs.isEmpty())
    }

    // --- A: a short fragment is not finalized at the first short pause; it waits for continuation ---

    @Test
    fun shortFragmentFollowedQuicklyBySpeechBecomesOneFinal() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        // 640 ms speech, 600 ms pause (> endSilence 400, < hold 1500), 1500 ms speech, long silence
        val total = run(listOf(false to 320, true to 640, false to 608, true to 1504, false to 1600), s)
        val finals = jobs.filter { it.isFinal }
        assertEquals(1, finals.size)
        assertEquals(setOf(1L), jobs.map { it.segmentId }.toSet())
        val ids = frameIds(finals[0])
        assertTrue("starts in pre-roll", ids.first() <= 11)
        assertTrue("covers both parts", ids.last() >= 10 + 20 + 19 + 47)
        assertTrue(ids.last() <= total)
        assertEquals("contiguous", (ids.first()..ids.last()).toList(), ids)
    }

    @Test
    fun shortFragmentFollowedByLongSilenceIsFinalizedAlone() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        run(listOf(false to 320, true to 640, false to 1600, true to 1504, false to 1600), s)
        val finals = jobs.filter { it.isFinal }
        assertEquals(listOf(1L, 2L), finals.map { it.segmentId })
        // the held final still keeps only ~100 ms of trailing silence, not the whole wait
        assertTrue(frameIds(finals[0]).last() <= 10 + 20 + 4)
    }

    @Test
    fun longEnoughUtteranceIsFinalizedAtNormalEndSilence() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        run(listOf(true to 1504, false to 608, true to 1504, false to 608), s)
        assertEquals(listOf(1L, 2L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun holdHintKeepsALongUtteranceOpen() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900, holdHint = { seg, _ -> seg == 1L })
        run(listOf(true to 1504, false to 608, true to 1504, false to 1600), s)
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun speechLengthIgnoresPreRollAndInternalSilence() {
        // 200 ms pre-roll + 704 ms speech + 288 ms pause + 320 ms speech: frames ≈ 1.5 s, speech ≈ 1.0 s < 1.2 s → still held
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        run(listOf(false to 320, true to 704, false to 288, true to 320, false to 608), s)
        assertTrue(jobs.none { it.isFinal })
        // just over the threshold → normal final at endSilence
        val jobs2 = ArrayList<AsrJob>()
        run(listOf(false to 320, true to 1248, false to 608), Segmenter(jobs2::add, minFinalMs = 1200, maxHoldSilenceMs = 1500))
        assertEquals(1, jobs2.count { it.isFinal })
    }

    /** The judged hold, with an answer that arrives at a given silence length (fake Jev latency). */
    private class FakeJudge(val answer: Boolean, val readyAfterFrames: Int) {
        val asked = ArrayList<Pair<Long, Int>>()
        var tail: AsrJob? = null
        var framesSinceTail = 0
        fun onJob(j: AsrJob) { if (j.endOfSpeech) { tail = j; framesSinceTail = 0 } }
        fun hint(seg: Long, rev: Int): Boolean? {
            asked.add(seg to rev)
            val t = tail ?: return null
            if (t.segmentId != seg || t.revision != rev) return null
            return if (++framesSinceTail >= readyAfterFrames) answer else null
        }
    }

    private fun judged(answer: Boolean, readyAfterFrames: Int, pattern: List<Pair<Boolean, Int>>): Pair<List<AsrJob>, FakeJudge> {
        val jobs = ArrayList<AsrJob>()
        val j = FakeJudge(answer, readyAfterFrames)
        val s = Segmenter({ jobs.add(it); j.onJob(it) }, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900, holdHint = j::hint)
        run(pattern, s); s.flush()
        return jobs to j
    }

    // middle pause 1408 ms: past the wait (candidate at ~416 ms + 900 ms), short of maxHold (1500) → the answer decides
    private val midPause = listOf(true to 1504, false to 1408, true to 1504, false to 1600)
    private val shortPause = listOf(true to 1504, false to 608, true to 1504, false to 1600)

    @Test
    fun judgeSaysFinishedInTimeFinalizesBeforeTheWaitEnds() {
        val (jobs, _) = judged(answer = false, readyAfterFrames = 3, pattern = shortPause)
        assertEquals(listOf(1L, 2L), jobs.filter { it.isFinal }.map { it.segmentId })
        // the end-of-speech draft carries exactly the audio of the final that follows it
        val tail = jobs.first { it.endOfSpeech && it.segmentId == 1L }
        assertEquals(frameIds(jobs.first { it.isFinal }), frameIds(tail))
    }

    @Test
    fun judgeSaysUnfinishedInTimeJoinsTheContinuation() {
        val (jobs, _) = judged(answer = true, readyAfterFrames = 3, pattern = shortPause)
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun lateAnswerFallsBackToFinalAtTheWaitLimit() {
        // the answer would come after ~1.3 s of silence; the segmenter gives up at 900 ms and finalizes
        val (jobs, _) = judged(answer = true, readyAfterFrames = 40, pattern = midPause)
        val f = jobs.filter { it.isFinal }
        assertEquals(listOf(1L, 2L), f.map { it.segmentId })
        val firstFinalAt = jobs.indexOf(f[0])
        val tailAt = jobs.indexOfFirst { it.endOfSpeech && it.segmentId == 1L }
        assertTrue(tailAt in 0 until firstFinalAt)
    }

    @Test
    fun answerForAnOlderRevisionIsNotUsed() {
        // speech resumes during the wait, so a new pause asks about a new revision; the judge only knows the old one
        val jobs = ArrayList<AsrJob>()
        var firstTail: AsrJob? = null
        val s = Segmenter({ jobs.add(it); if (it.endOfSpeech && firstTail == null) firstTail = it }, minFinalMs = 1200,
            maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { seg, rev -> if (firstTail?.let { it.segmentId == seg && it.revision == rev } == true) true else null })
        run(listOf(true to 1504, false to 608, true to 1504, false to 1408, true to 1504, false to 1600), s)
        val tails = jobs.filter { it.endOfSpeech && it.segmentId == 1L }
        assertEquals(2, tails.size)
        assertTrue(tails[1].revision > tails[0].revision)
        // second pause: no answer for its revision → final at hintWaitMs (two segments), not held by the stale "true"
        assertEquals(listOf(1L, 2L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun answerUnfinishedHoldsAcrossAPauseLongerThanTheWait() {
        val (jobs, _) = judged(answer = true, readyAfterFrames = 3, pattern = midPause)
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    /** A judge that answers [latencyMs] after the end-of-speech draft was emitted (ASR + network), in audio time. */
    private fun timedJudge(answer: Boolean, latencyMs: Int, pattern: List<Pair<Boolean, Int>>, hintWaitMs: Int = 1000,
                           maxHoldSilenceMs: Int = 2500, maxUtteranceMs: Int = 7000): List<AsrJob> {
        val jobs = ArrayList<AsrJob>()
        var now = 0L
        val sentAt = HashMap<Pair<Long, Int>, Long>()
        val s = Segmenter({ jobs.add(it); if (it.endOfSpeech) sentAt[it.segmentId to it.revision] = now },
            minFinalMs = 1200, maxHoldSilenceMs = maxHoldSilenceMs, hintWaitMs = hintWaitMs, maxUtteranceMs = maxUtteranceMs,
            holdHint = { seg, rev -> sentAt[seg to rev]?.let { if (now - it >= latencyMs) answer else null } })
        var idx = 0
        for ((speech, ms) in pattern) repeat(ms / 32) {
            idx++; now = idx * 32L
            s.accept(FloatArray(frame) { idx.toFloat() }, if (speech) 0.9f else 0.1f, now)
        }
        s.flush()
        return jobs
    }

    @Test
    fun realisticJudgeLatencyStillJoinsTheContinuation() {
        // ASR ~100 ms + Jev ~550 ms after the candidate; the speaker resumes ~800 ms after the candidate (1216 ms pause)
        val jobs = timedJudge(answer = true, latencyMs = 650, pattern = listOf(true to 1504, false to 1216, true to 1504, false to 3000))
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun realisticJudgeLatencyFinishedAnswerFinalizesWithoutWaitingForMaxHold() {
        val jobs = timedJudge(answer = false, latencyMs = 650, pattern = listOf(true to 1504, false to 3000))
        val f = jobs.single { it.isFinal }
        // final at ~416 ms (candidate) + 650 ms answer, well before the 2.5 s hold limit
        val tail = jobs.single { it.endOfSpeech }
        assertEquals(frameIds(tail), frameIds(f))
        assertTrue(jobs.indexOf(f) > jobs.indexOf(tail))
    }

    @Test
    fun judgeSlowerThanTheBudgetFinalizesAtTheBudget() {
        val jobs = timedJudge(answer = true, latencyMs = 5000, pattern = listOf(true to 1504, false to 1700, true to 1504, false to 3000))
        assertEquals(listOf(1L, 2L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun longSilenceDuringAHoldProducesNoSilentFinalAndLosesNothing() {
        // utterance near the forced-cut limit, then a held pause that crosses maxUtteranceMs, then more speech
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { _, _ -> true })
        val total = run(listOf(true to 2496, false to 1600, true to 1504, false to 1600), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        val ids = finals.flatMap(::frameIds)
        // trailing silence of a final is dropped as usual; every speech frame is kept exactly once, in order
        assertEquals(ids.sorted().distinct(), ids)
        assertTrue(ids.containsAll((1..78).toList() + (129..175).toList()))
        assertEquals(2, finals.size)
    }

    /** A forced cut is a candidate too: an unfinished head is carried into the next chunk, up to the hard limit. */
    @Test
    fun forcedCutWithUnfinishedAnswerCarriesTheHeadIntoTheNextChunk() {
        // 3 s speech, short pause (no natural final), 2 s speech; normal cut limit 3 s, hard limit 6 s
        val jobs = timedJudge(answer = true, latencyMs = 100, maxUtteranceMs = 3000,
            pattern = listOf(true to 2496, false to 256, true to 2016, false to 3000))
        val finals = jobs.filter { it.isFinal }
        assertEquals(1, finals.size)
        assertTrue(finals[0].samples.size <= 6000 * 16 + 512)
    }

    @Test
    fun forcedCutNeverExceedsTheHardLimit() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { _, _ -> true })
        val total = run(listOf(true to 20_000), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        assertTrue(finals.all { it.samples.size <= 6000 * 16 + 512 })
        assertEquals((1..total).toList(), finals.flatMap(::frameIds))
    }

    @Test
    fun holdingLosesNoFrameAndForcedCutStillApplies() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900, holdHint = { _, _ -> true })
        val total = run(listOf(true to 640, false to 608, true to 640, false to 608, true to 6016), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        assertTrue(finals.size >= 2)
        // with a judge saying "unfinished", a forced cut waits up to the hard limit (2 × maxUtteranceMs)
        assertTrue(finals.all { it.samples.size <= 6000 * 16 + frame })
        assertEquals((1..total).toList(), finals.flatMap(::frameIds))
    }

    @Test
    fun flushFinalizesAHeldFragment() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        run(listOf(true to 640, false to 608), s)
        assertTrue(jobs.none { it.isFinal })
        s.flush()
        assertEquals(1, jobs.count { it.isFinal })
    }
}
