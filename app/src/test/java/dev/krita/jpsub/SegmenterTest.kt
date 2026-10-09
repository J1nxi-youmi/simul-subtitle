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
}
