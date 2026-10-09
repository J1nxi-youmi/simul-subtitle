package dev.krita.jpsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PipelineTest {
    /** The "audio" encodes its text as sample count, so the test can tell jobs apart. */
    private class FakeAsr(val delayMs: Long = 0) : Asr {
        override fun recognize(samples: FloatArray): String {
            Thread.sleep(delayMs)
            return "ja${samples.size}"
        }
    }

    /** Busy-waits (ignores interrupts like native code) unless cancelled. */
    private class FakeMt(val delayMs: Long) : Mt {
        val cancelled = AtomicBoolean(false)
        val inside = AtomicInteger(0)
        val calls = Collections.synchronizedList(ArrayList<String>())
        override fun translate(text: String): String? {
            inside.incrementAndGet()
            try {
                calls.add(text)
                val end = System.nanoTime() + delayMs * 1_000_000
                while (System.nanoTime() < end) if (cancelled.get()) return null
                return "zh($text)"
            } finally { inside.decrementAndGet() }
        }
        override fun cancel() = cancelled.set(true)
    }

    private fun job(seg: Long, rev: Int, final: Boolean, n: Int) = AsrJob(seg, rev, final, FloatArray(n), monoMs())

    @Test
    fun finalsAreNeverDroppedAndStayInOrder() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(5)
        val p = Pipeline(FakeAsr(), FakeMt(30), { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        for (seg in 1L..5L) {
            for (rev in 1..5) p.submit(job(seg, rev, false, rev))
            p.submit(job(seg, 6, true, 100 + seg.toInt()))
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), out.filter { it.isFinal }.map { it.segmentId })
    }

    /** Drafts every 700 ms, MT takes 900 ms: a draft must still reach the screen while the speaker talks. */
    @Test
    fun slowMtStillShowsDraftsBeforeTheFinal() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val mt = FakeMt(900)
        val p = Pipeline(FakeAsr(), mt, { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        for (rev in 1..5) { p.submit(job(1, rev, false, rev)); Thread.sleep(700) }
        val draftsBeforeFinal = out.count { !it.isFinal }
        p.submit(job(1, 6, true, 6))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertTrue("drafts shown while talking: $draftsBeforeFinal", draftsBeforeFinal >= 2)
        // queued stale drafts were merged: fewer MT calls than submitted drafts
        assertTrue("MT calls ${mt.calls}", mt.calls.size < 6)
        assertEquals("ja6", out.last().ja)
    }

    /** Reviewer repro: ASR slower than the draft interval, a newer draft is always queued when one finishes. */
    @Test
    fun slowAsrStillShowsDrafts() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val gate = java.util.concurrent.Semaphore(0)
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String { gate.acquire(); return "ja${samples.size}" }
        }
        val p = Pipeline(asr, FakeMt(1), { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 1, false, 1))
        for (rev in 2..5) {
            Thread.sleep(30)
            p.submit(job(1, rev, false, rev)) // newer draft queued *before* the running one finishes
            gate.release()
        }
        Thread.sleep(30)
        p.submit(job(1, 6, true, 6))
        gate.release(10)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertTrue("drafts shown: ${out.filter { !it.isFinal }.map { it.revision }}", out.count { !it.isFinal } >= 3)
    }

    @Test
    fun workerExceptionIsReportedAndPipelineRefusesNewJobs() {
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val asr = object : Asr { override fun recognize(samples: FloatArray): String = throw IllegalStateException("boom") }
        val p = Pipeline(asr, FakeMt(1), {}, onFailure = { failures.add(it) })
        p.start()
        assertTrue(p.submit(job(1, 1, true, 10)))
        val deadline = System.currentTimeMillis() + 2_000
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("boom", failures.single().message)
        assertFalse("must refuse after failure", p.submit(job(2, 1, true, 10)))
        p.stop(); p.awaitStopped()
    }

    @Test
    fun throwingCaptionSinkIsAFailureNotACrash() {
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val p = Pipeline(FakeAsr(), FakeMt(1), { throw java.io.IOException("disk full") }, onFailure = { failures.add(it) })
        p.start()
        p.submit(job(1, 1, true, 10))
        val deadline = System.currentTimeMillis() + 2_000
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("disk full", failures.single().message)
        p.stop(); p.awaitStopped()
    }

    @Test
    fun overloadIsReportedAndNewJobsRefused() {
        val overloads = AtomicInteger()
        val p = Pipeline(FakeAsr(), FakeMt(10_000), {}, onOverload = { overloads.incrementAndGet() }, maxPendingAudioMs = 3_000)
        p.start()
        assertTrue(p.submit(job(1, 1, true, 16_000)))  // 1 s
        assertTrue(p.submit(job(2, 1, true, 16_000)))  // 2 s
        assertTrue(p.submit(job(3, 1, true, 32_000)))  // 4 s → overloaded (accepted, then flag)
        assertFalse(p.submit(job(4, 1, true, 16_000)))
        assertTrue(overloads.get() >= 1)
        p.stop(); p.awaitStopped()
    }

    /** Workers stuck in non-interruptible "native" code: awaitStopped must wait for them, and late results are dropped. */
    @Test
    fun awaitStoppedWaitsForNativeCallsAndDropsLateResults() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val asrEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val asrInside = AtomicBoolean(false)
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String {
                asrInside.set(true); asrEntered.countDown()
                while (release.count > 0) { /* spin, ignores interrupt */ }
                asrInside.set(false)
                return "late"
            }
        }
        val mt = FakeMt(1)
        val p = Pipeline(asr, mt, { out.add(it) })
        p.start()
        p.submit(job(1, 1, true, 10))
        assertTrue(asrEntered.await(2, TimeUnit.SECONDS))
        p.stop()
        val waiter = Thread { p.awaitStopped() }.apply { start() }
        Thread.sleep(200)
        assertTrue("awaitStopped returned while ASR still running", waiter.isAlive && asrInside.get())
        release.countDown()
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertFalse(asrInside.get())
        assertEquals(0, mt.inside.get())
        assertTrue(out.isEmpty())
    }

    @Test
    fun timingIsFilled() {
        val done = CountDownLatch(1)
        var c: Caption? = null
        val p = Pipeline(FakeAsr(20), FakeMt(30), { c = it; done.countDown() })
        p.start()
        val audioEnd = monoMs() - 100 // the segmenter handed it over 100 ms after the audio ended
        p.submit(AsrJob(1, 1, true, FloatArray(16_000), audioEnd))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        val t = c!!.timing
        assertEquals(1000L, t.audioMs)
        assertTrue("wait ${t.segmentWaitMs}", t.segmentWaitMs >= 100)
        assertTrue(t.asrMs >= 20 && t.mtMs >= 30)
        assertTrue(t.mtDoneMs >= t.segmentWaitMs + t.asrMs + t.mtMs)
    }
}

class CaptionBoardTest {
    private fun cap(seg: Long, rev: Int, final: Boolean, zh: String = "z$seg.$rev") =
        Caption(seg, rev, final, "", zh, Timing(0, 0, 0, 0, 0, 0, 0))

    @Test
    fun revisionsOnlyMoveForward() {
        val b = CaptionBoard()
        assertTrue(b.accept(cap(1, 2, false)))
        assertFalse(b.accept(cap(1, 1, false)))
        assertTrue(b.accept(cap(1, 3, true)))
        assertFalse(b.accept(cap(1, 4, false)))
        assertEquals("z1.3", b.snapshot().single().zh)
    }

    @Test
    fun keepsLastTwoSegmentsAndIgnoresStaleOldDrafts() {
        val b = CaptionBoard(maxLines = 2)
        b.accept(cap(1, 1, true)); b.accept(cap(2, 1, true)); b.accept(cap(3, 1, false))
        assertEquals(listOf(2L, 3L), b.snapshot().map { it.segmentId })
        assertFalse(b.accept(cap(1, 5, false)))
    }

    @Test
    fun emptyFinalRemovesDraftLine() {
        val b = CaptionBoard()
        b.accept(cap(1, 1, false)); b.accept(cap(1, 2, true, zh = ""))
        assertTrue(b.snapshot().isEmpty())
    }
}
