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
        override fun translate(prompt: String): MtResult? {
            inside.incrementAndGet()
            try {
                calls.add(prompt)
                val end = System.nanoTime() + delayMs * 1_000_000
                while (System.nanoTime() < end) if (cancelled.get()) return null
                return MtResult("zh(${source(prompt)})", 'e', 1, 1)
            } finally { inside.decrementAndGet() }
        }
        override fun cancel() = cancelled.set(true)
    }

    /** Blocks every call until [release]; records the order of the source texts. */
    private class GateMt : Mt {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val order = Collections.synchronizedList(ArrayList<String>())
        override fun translate(prompt: String): MtResult? {
            order.add(source(prompt)); entered.countDown(); gate.await()
            return MtResult("zh(${source(prompt)})", 'e', 1, 1)
        }
        override fun cancel() = gate.countDown()
    }

    private class CountingAsr : Asr {
        val seen = Collections.synchronizedList(ArrayList<Int>())
        override fun recognize(samples: FloatArray): String { seen.add(samples.size); return "ja${samples.size}" }
    }

    /** Blocks the first recognize() until [gate] opens. */
    private class GateAsr : Asr {
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        val seen = Collections.synchronizedList(ArrayList<Int>())
        override fun recognize(samples: FloatArray): String {
            if (entered.count > 0) { entered.countDown(); gate.await() }
            seen.add(samples.size); return "ja${samples.size}"
        }
    }

    companion object {
        /** The Japanese text inside a GENERAL-scene Index prompt. */
        fun source(prompt: String) = prompt.substringAfter("\n\n")
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
    fun drainDeliversAcceptedFinalsAndDropsDrafts() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(FakeAsr(), FakeMt(100), { out.add(it) })
        p.start()
        p.submit(job(1, 1, true, 1)); p.submit(job(2, 1, true, 2)); p.submit(job(3, 1, false, 3)); p.submit(job(3, 2, true, 4))
        p.submit(job(4, 1, false, 5))
        assertEquals(0, p.drainFinals(5_000))
        assertFalse("no new jobs after drain", p.submit(job(5, 1, true, 6)))
        p.stop(); p.awaitStopped()
        assertEquals(listOf(1L, 2L, 3L), out.filter { it.isFinal }.map { it.segmentId })
        assertTrue(out.none { it.segmentId == 4L })
    }

    @Test
    fun drainReportsWhatIsStillPending() {
        val p = Pipeline(FakeAsr(), FakeMt(2_000), {})
        p.start()
        p.submit(job(1, 1, true, 1)); p.submit(job(2, 1, true, 2))
        val left = p.drainFinals(200)
        p.stop(); p.awaitStopped()
        assertEquals(2, left) // one in MT, one queued
    }

    /** Review-2 #1: after a timed-out drain nothing may be delivered any more, so the reported count is final. */
    @Test
    fun drainTimeoutStopsWorkAndCountIsFinal() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val mt = FakeMt(300)
        val p = Pipeline(FakeAsr(), mt, { out.add(it) })
        p.start()
        p.submit(job(1, 1, true, 1)); p.submit(job(2, 1, true, 2))
        val left = p.drainFinals(80)
        val deliveredAtReturn = out.size
        Thread.sleep(800)
        assertEquals(2, left)
        assertEquals("nothing delivered after drain returned", deliveredAtReturn, out.size)
        assertEquals(0, out.size)
        assertTrue(mt.cancelled.get())
        assertEquals(0, mt.inside.get())
    }

    /** Review-2 #2: a stop during drain ends the drain immediately instead of waiting for the deadline. */
    @Test
    fun stopDuringDrainReturnsPromptly() {
        val p = Pipeline(FakeAsr(), FakeMt(10_000), {})
        p.start()
        p.submit(job(1, 1, true, 1)); p.submit(job(2, 1, true, 2))
        Thread({ Thread.sleep(100); p.stop() }).start()
        val t = System.nanoTime()
        val left = p.drainFinals(5_000)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("drain took ${ms}ms", ms < 1_000)
        assertEquals(2, left)
    }

    /** Review-2 #3: an interruption that is not caused by stop() is a worker failure. */
    @Test
    fun unexpectedInterruptIsAFailure() {
        val failures = AtomicInteger(0)
        val asr = object : Asr { override fun recognize(samples: FloatArray): String = throw InterruptedException("spurious") }
        val p = Pipeline(asr, FakeMt(1), {}, onFailure = { failures.incrementAndGet() })
        p.start()
        p.submit(job(1, 1, true, 1))
        val deadline = System.currentTimeMillis() + 2_000
        while (failures.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, failures.get())
        assertFalse(p.submit(job(2, 1, true, 2)))
        p.stop(); p.awaitStopped()
    }

    /** Review-2 #5: timestamp provenance travels with the job to the caption. */
    @Test
    fun captionCarriesTimestampProvenance() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(FakeAsr(), FakeMt(1), { out.add(it) })
        p.start()
        p.submit(AsrJob(1, 1, true, FloatArray(10), monoMs(), timeEstimated = true))
        p.submit(AsrJob(2, 1, true, FloatArray(20), monoMs(), timeEstimated = false))
        assertEquals(0, p.drainFinals(2_000))
        assertEquals(listOf(true, false), out.map { it.timeEstimated })
    }

    /** The UI measures latency from Caption.audioEndAt; a slow delivery must show up, not cancel out. */
    @Test
    fun captionCarriesCaptureTimeForUiLatency() {
        val done = CountDownLatch(1)
        var c: Caption? = null
        val p = Pipeline(FakeAsr(), FakeMt(1), { c = it; done.countDown() })
        p.start()
        val audioEnd = monoMs() - 500
        p.submit(AsrJob(1, 1, true, FloatArray(10), audioEnd))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        Thread.sleep(300) // UI thread busy before rendering
        assertEquals(audioEnd, c!!.audioEndAt)
        assertTrue(monoMs() - c!!.audioEndAt >= 800)
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

    // --- Jev ---

    private class FakeJudge(val unfinishedP: Double? = 0.9, val choice: String? = "ok", val confidence: Double = 0.95,
                            val delayMs: Long = 0) : Judge {
        val asked = Collections.synchronizedList(ArrayList<String>())
        val cancelled = AtomicBoolean(false)
        private fun call(answer: Map<String, Any?>?): JevCall {
            // like the real client: stop (interrupt here, disconnect there) ends the call with an error, not an exception
            try { Thread.sleep(delayMs) } catch (e: InterruptedException) { return JevCall("{req}", 0, null, 0, "cancelled", null) }
            return JevCall("{req}", if (answer == null) 0 else 200, answer?.let { Json.encode(it) }, delayMs,
                if (answer == null) "timeout" else null, answer)
        }
        override fun unfinished(text: String): JevCall { asked.add("u:$text"); return call(unfinishedP?.let { mapOf("noul" to it) }) }
        override fun mistranslation(ja: String, zh: String): JevCall {
            asked.add("m:$ja|$zh"); return call(choice?.let { mapOf("choice" to it, "confidence" to confidence) })
        }
        override fun cancel() = cancelled.set(true)
    }

    private fun runFinal(judge: Judge, events: MutableList<Map<String, Any?>> = Collections.synchronizedList(ArrayList()),
                         mt: FakeMt = FakeMt(1)): List<Caption> {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { out.add(it); if (it.isFinal) done.countDown() }, judge = judge, log = { events.add(it) })
        p.start()
        p.submit(job(1, 3, true, 10))
        assertTrue(done.await(3, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        return out
    }

    @Test
    fun reliableErrorClassIsTranslatedOnceMoreWithItsNote() {
        val mt = FakeMt(1)
        val events = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val out = runFinal(FakeJudge(choice = "negation"), events, mt)
        assertEquals(2, mt.calls.size)
        assertTrue(mt.calls[1].contains(FIX_NOTES["negation"]!!))
        // the first translation is shown as a draft while Jev checks it, then the final replaces it
        assertEquals(listOf(false to 3, true to 4), out.map { it.isFinal to it.revision })
        val b = CaptionBoard(); out.forEach { b.accept(it) }
        assertTrue(b.snapshot().single().isFinal)
        val mts = events.filter { it["ev"] == "mt" }
        assertEquals(listOf(1, 2), mts.map { it["attempt"] })
        assertEquals("retranslate:negation", events.single { it["ev"] == "jev" }["action"])
    }

    @Test
    fun otherClassesLowConfidenceAndFailuresKeepTheFirstTranslation() {
        for (judge in listOf(FakeJudge(choice = "ok"), FakeJudge(choice = "platform_term"),
                             FakeJudge(choice = "negation", confidence = 0.4), FakeJudge(choice = null))) {
            val mt = FakeMt(1)
            val out = runFinal(judge, mt = mt)
            assertEquals(1, mt.calls.size)
            assertEquals("zh(ja10)", out.last { it.isFinal }.zh)
        }
    }

    @Test
    fun endOfSpeechDraftIsJudgedAndTheAnswerIsKeyedByRevision() {
        val judge = FakeJudge(unfinishedP = 0.9)
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge)
        p.start()
        p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        val deadline = monoMs() + 2000
        while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
        assertEquals(true, p.holdHint(1, 2))
        assertEquals(null, p.holdHint(1, 3))
        p.stop(); p.awaitStopped()
        assertTrue(judge.cancelled.get())
    }

    @Test
    fun belowThresholdAnswersFinishAndFailedCallsGiveNoHint() {
        for ((pr, want) in listOf(0.3 to false, null to null)) {
            val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = FakeJudge(unfinishedP = pr))
            p.start()
            p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
            Thread.sleep(200)
            assertEquals(want, p.holdHint(1, 2))
            p.stop(); p.awaitStopped()
        }
    }

    @Test
    fun withoutAJudgeNothingChanges() {
        val out2 = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), FakeMt(1), { out2.add(it); done.countDown() })
        p.start(); p.submit(job(1, 3, true, 10))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(listOf(true to 3), out2.map { it.isFinal to it.revision })
    }

    @Test
    fun theJevCheckIsTimedApartFromTheTranslationAndTheFinalNamesItsSourceRevision() {
        val out = runFinal(FakeJudge(choice = "ok", delayMs = 150))
        val f = out.last { it.isFinal }
        assertTrue("check ${f.timing.checkMs}", f.timing.checkMs >= 150)
        assertTrue("mt ${f.timing.mtMs}", f.timing.mtMs < 150)
        assertEquals(4, f.revision); assertEquals(3, f.sourceRevision) // raw ASR/MT/Jev events are logged as rev 3
    }

    @Test
    fun aRetranslationCountsAsTranslationTimeNotAsCheckTime() {
        val mt = FakeMt(120)
        val f = runFinal(FakeJudge(choice = "negation", delayMs = 50), mt = mt).last { it.isFinal }
        assertEquals(2, mt.calls.size)
        assertTrue("mt ${f.timing.mtMs}", f.timing.mtMs >= 240)
        assertTrue("check ${f.timing.checkMs}", f.timing.checkMs in 50..200)
    }

    @Test
    fun aJevCallCutShortByStopIsStillLogged() {
        val events = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val judge = FakeJudge(unfinishedP = 0.9, delayMs = 400)
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge, log = { events.add(it) })
        p.start()
        p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        Thread.sleep(100)
        p.stop(); p.awaitStopped()
        assertEquals("stopped", events.single { it["ev"] == "jev" }["action"])
    }

    @Test
    fun theLogTellsWhetherTheSegmenterUsedAHintOrItCameTooLate() {
        val events = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = FakeJudge(unfinishedP = 0.9, delayMs = 150), log = { events.add(it) })
        p.start()
        // seg 1: the segmenter reads the answer (twice: logged once)
        p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        val deadline = monoMs() + 2000
        while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
        p.holdHint(1, 2)
        // seg 2: finalized before Jev answered
        p.submit(AsrJob(2, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        Thread.sleep(30)
        p.submit(AsrJob(2, 3, true, FloatArray(10), monoMs()))
        Thread.sleep(400)
        p.stop(); p.awaitStopped()
        val used = events.filter { it["ev"] == "hint_used" }
        assertEquals(listOf(1L to true), used.map { it["seg"] to it["hold"] })
        assertEquals("late", events.single { it["ev"] == "jev" && it["kind"] == "unfinished" && it["seg"] == 2L }["action"])
    }

    @Test
    fun whileAFinalIsWaitingNoDisplayDraftIsRecognizedOrTranslated() {
        val asr = CountingAsr(); val mt = GateMt()
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(2)
        val p = Pipeline(asr, mt, { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 3, true, 10))
        assertTrue(mt.entered.await(2, TimeUnit.SECONDS)) // seg 1's final is being translated (slow phone)
        // drafts of the next sentence arrive while it waits, spaced so the ASR thread would pick each one up
        p.submit(job(2, 1, false, 21)); Thread.sleep(60)
        p.submit(job(2, 2, false, 22)); Thread.sleep(60)
        p.submit(job(2, 3, true, 23)); Thread.sleep(60)
        mt.gate.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(listOf(10, 23), asr.seen)
        assertEquals(listOf("ja10", "ja23"), mt.order)
        assertEquals(listOf(1L to true, 2L to true), out.map { it.segmentId to it.isFinal })
    }

    @Test
    fun whenNothingIsWaitingDraftsAreStillShown() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val shown = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), FakeMt(1), { out.add(it); if (!it.isFinal) shown.countDown() })
        p.start(); p.submit(job(1, 1, false, 10))
        assertTrue(shown.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
    }

    @Test
    fun aLaterPlainDraftDoesNotReplaceAQueuedSentenceEndCandidate() {
        val asr = GateAsr(); val judge = FakeJudge(unfinishedP = 0.9)
        val p = Pipeline(asr, FakeMt(1), {}, judge = judge)
        p.start()
        p.submit(job(1, 1, true, 5)) // keeps the ASR thread busy
        assertTrue(asr.entered.await(2, TimeUnit.SECONDS))
        p.submit(AsrJob(2, 4, false, FloatArray(24), monoMs(), endOfSpeech = true)) // Segmenter waits for this answer
        p.submit(job(2, 5, false, 25)) // speech went on 32 ms later: a plain draft of the same segment
        asr.gate.countDown()
        val deadline = monoMs() + 2000
        while (p.holdHint(2, 4) == null && monoMs() < deadline) Thread.sleep(5)
        p.stop(); p.awaitStopped()
        assertEquals(true, p.holdHint(2, 4))
        assertTrue(judge.asked.toString(), judge.asked.contains("u:ja24"))
    }

    @Test
    fun aFinalWithTheSameTextAsItsLastDraftReusesTheTranslation() {
        val mt = FakeMt(1)
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val drafted = CountDownLatch(1); val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { out.add(it); if (it.isFinal) done.countDown() else drafted.countDown() })
        p.start()
        p.submit(job(1, 1, false, 10))
        assertTrue(drafted.await(2, TimeUnit.SECONDS))
        p.submit(job(1, 2, true, 10)) // speech ended: same audio, same recognized text
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(1, mt.calls.size)
        assertEquals("zh(ja10)", out.last { it.isFinal }.zh)
    }

    /** Returns null (cancel-like: nothing usable) for the first [failFirst] calls. */
    private class FlakyMt(var failFirst: Int) : Mt {
        val calls = Collections.synchronizedList(ArrayList<String>())
        override fun translate(prompt: String): MtResult? {
            calls.add(source(prompt))
            return if (calls.size <= failFirst) MtResult("", 'f', 0, 0) else MtResult("zh(${source(prompt)})", 'e', 1, 1)
        }
        override fun cancel() {}
    }

    @Test
    fun reuseIsPerSegmentAndNeverOfAFailure() {
        val mt = FlakyMt(failFirst = 1)
        val logs = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val finals = CountDownLatch(2)
        val drafted = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) finals.countDown() else drafted.countDown() }, log = { logs.add(it) })
        p.start()
        p.submit(job(1, 1, false, 10)) // fails: nothing shown, nothing cached
        Thread.sleep(100)
        p.submit(job(1, 2, true, 10)) // so the final translates again
        p.submit(job(2, 3, true, 10)) // same text in another segment: translated, not reused
        assertTrue(finals.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(listOf("ja10", "ja10", "ja10"), mt.calls)
        assertTrue(logs.filter { it["ev"] == "mt" }.none { it["reused"] == true })
    }

    @Test
    fun aReusedFinalIsStillCheckedByJev() {
        val mt = FakeMt(1); val judge = FakeJudge(choice = "ok")
        val logs = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val drafted = CountDownLatch(1); val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) done.countDown() else drafted.countDown() }, judge = judge, log = { logs.add(it) })
        p.start()
        p.submit(job(1, 1, false, 10)); assertTrue(drafted.await(2, TimeUnit.SECONDS))
        p.submit(job(1, 2, true, 10))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(1, mt.calls.size)
        assertTrue(judge.asked.toString(), judge.asked.contains("m:ja10|zh(ja10)"))
        val reused = logs.single { it["ev"] == "mt" && it["reused"] == true }
        assertEquals(1, reused["reused_from"]); assertEquals(2, reused["rev"])
    }

    @Test
    fun finalOnlySkipsDisplayDraftsButStillJudgesSentenceEnds() {
        val asr = CountingAsr(); val mt = FakeMt(1)
        val judge = FakeJudge(unfinishedP = 0.9)
        var live = false
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(asr, mt, { out.add(it) }, judge = judge, drafts = { live })
        p.start()
        p.submit(job(1, 1, false, 11)) // display draft: skipped
        p.submit(AsrJob(1, 2, false, FloatArray(12), monoMs(), endOfSpeech = true)) // sentence-end candidate
        val deadline = monoMs() + 2000
        while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
        assertEquals(true, p.holdHint(1, 2)) // Jev still got the candidate
        assertEquals(listOf(12), asr.seen)
        assertTrue(mt.calls.isEmpty()) // nothing translated just for display
        live = true // switched back to live while running: drafts resume
        val shown = CountDownLatch(1)
        p.submit(job(1, 3, false, 13))
        while (out.none { !it.isFinal } && monoMs() < deadline + 2000) Thread.sleep(5)
        p.stop(); p.awaitStopped()
        assertTrue(out.any { !it.isFinal && it.ja == "ja13" })
    }

    /** Real-log repro (session-20261010-195346 seg 2): the longer recognition lost the thanks for a superchat. */
    @Test
    fun aDraftTheLongerRecognitionDroppedIsKeptAndOnlyTheNewAudioIsRecognized() {
        val seen = Collections.synchronizedList(ArrayList<Int>())
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String { seen.add(samples.size); return when (samples.size) {
                25_600 -> "スパチャありがとうございます" // 1.6 s
                76_800 -> "いや相当でかいと思うけど" // whole 4.8 s: the start is gone
                76_800 - 25_600 -> "いや相当でかいと思うけど"
                else -> "x"
            } }
        }
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(asr, FakeMt(1), { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(2, 1, false, 25_600)); Thread.sleep(100)
        p.submit(job(2, 2, true, 76_800))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertEquals("スパチャありがとうございますいや相当でかいと思うけど", out.last { it.isFinal }.ja)
        assertEquals(listOf(25_600, 76_800, 51_200), seen.toList())
    }

    @Test
    fun aRecognitionThatOnlyRefinesTheDraftIsUsedAsIs() {
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String =
                if (samples.size == 25_600) "このうさぎひっくりかえってる" else "このウサギなんかひっくり返ってるうさぎ"
        }
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(asr, FakeMt(1), { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 1, false, 25_600)); Thread.sleep(100)
        p.submit(job(1, 2, true, 48_000))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertEquals("このウサギなんかひっくり返ってるうさぎ", out.last { it.isFinal }.ja)
    }

    @Test
    fun shortOrBriefDraftsAreNotKept() {
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String = when (samples.size) { 14_000 -> "えっとですね"; 4_000 -> "えっ"; else -> "別の話です" }
        }
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(asr, FakeMt(1), { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 1, false, 4_000)); Thread.sleep(50)
        p.submit(job(1, 2, false, 14_000)); Thread.sleep(50) // under 1.5 s of audio
        p.submit(job(1, 3, true, 40_000))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertEquals("別の話です", out.last { it.isFinal }.ja)
    }

    /** review 27a898c #1: a second loss must not drop the text the first repair kept */
    @Test
    fun twoSuccessiveLossesKeepBothEarlierParts() {
        val asr = object : Asr {
            // drafts at 1.6 s and 4.0 s, final at 6.0 s; tails are [1.6, 4.0) = 38_400 and [4.0, 6.0) = 32_000
            override fun recognize(samples: FloatArray): String = when (samples.size) {
                25_600 -> "スパチャありがとうございます"
                64_000, 38_400 -> "いや相当でかいと思うけど"
                else -> "猫が窓から飛び出した" // 96_000 whole and 32_000 tail
            }
        }
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(asr, FakeMt(1), { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(2, 1, false, 25_600)); Thread.sleep(100)
        p.submit(job(2, 2, false, 64_000)); Thread.sleep(100)
        p.submit(job(2, 3, true, 96_000))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertEquals("スパチャありがとうございますいや相当でかいと思うけど猫が窓から飛び出した", out.last { it.isFinal }.ja)
    }

    /** review 27a898c #2: an older draft that a newer, still-kept draft already corrected is not brought back */
    @Test
    fun anOlderCorrectedDraftIsNotResurrected() {
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String = when (samples.size) {
                25_600 -> "あしたはあめがふります"; 51_200 -> "あしたは晴れます今日はゲーム配信を始めます"
                else -> "明日は晴天。今日はゲーム配信を始めます"
            }
        }
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(asr, FakeMt(1), { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 1, false, 25_600)); Thread.sleep(100)
        p.submit(job(1, 2, false, 51_200)); Thread.sleep(100)
        p.submit(job(1, 3, true, 76_800))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertEquals("明日は晴天。今日はゲーム配信を始めます", out.last { it.isFinal }.ja)
    }

    /** review 27a898c #3: stop during the whole recognition starts no second decode */
    @Test
    fun noTailDecodeStartsAfterStop() {
        val inWhole = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = Collections.synchronizedList(ArrayList<Int>())
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String {
                calls.add(samples.size)
                if (samples.size == 76_800) { inWhole.countDown(); release.await() }
                return if (samples.size == 25_600) "スパチャありがとうございます" else "いや相当でかいと思うけど"
            }
        }
        val p = Pipeline(asr, FakeMt(1), { })
        p.start()
        p.submit(job(2, 1, false, 25_600)); Thread.sleep(100)
        p.submit(job(2, 2, true, 76_800))
        assertTrue(inWhole.await(3, TimeUnit.SECONDS))
        p.stop(); release.countDown(); p.awaitStopped()
        assertEquals(listOf(25_600, 76_800), calls.toList())
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
    fun keepsTwoFinalsPlusTheDraftAndIgnoresStaleOldDrafts() {
        val b = CaptionBoard(maxFinals = 2)
        b.accept(cap(1, 1, true)); b.accept(cap(2, 1, true)); b.accept(cap(3, 1, false))
        // the draft is an extra line: it does not push out a finished sentence
        assertEquals(listOf(1L, 2L, 3L), b.snapshot().map { it.segmentId })
        assertFalse(b.accept(cap(1, 5, false)))
        b.accept(cap(3, 2, true))
        assertEquals(listOf(2L, 3L), b.snapshot().map { it.segmentId })
    }

    /** User report: a sentence read as finished was later a different sentence. Finals never change on screen. */
    @Test
    fun aShownFinalNeverChangesWhileLaterSpeechComesIn() {
        val b = CaptionBoard(maxFinals = 2)
        b.accept(cap(1, 3, true, zh = "顺便一提啊……"))
        for (rev in 1..6) b.accept(cap(2, rev, false, zh = "草稿$rev"))
        assertEquals(listOf("顺便一提啊……", "草稿6"), b.snapshot().map { it.zh })
        assertEquals(listOf(true, false), b.snapshot().map { it.isFinal })
        b.accept(cap(2, 7, true, zh = "定稿2"))
        assertEquals(listOf("顺便一提啊……", "定稿2"), b.snapshot().map { it.zh })
    }

    @Test
    fun aDraftOfALaterSegmentReplacesTheDraftLineButNotFinals() {
        val b = CaptionBoard()
        b.accept(cap(1, 1, true)); b.accept(cap(2, 1, false)); b.accept(cap(3, 1, false))
        assertEquals(listOf(1L, 3L), b.snapshot().map { it.segmentId })
        // a late draft of segment 2 cannot come back once 3 is the draft
        assertFalse(b.accept(cap(2, 2, false)))
        // segment 2's final still lands, in order, and keeps the newer draft line
        assertTrue(b.accept(cap(2, 3, true)))
        assertEquals(listOf(1L, 2L, 3L), b.snapshot().map { it.segmentId })
        assertEquals(listOf(true, true, false), b.snapshot().map { it.isFinal })
    }

    @Test
    fun emptyFinalRemovesDraftLine() {
        val b = CaptionBoard()
        b.accept(cap(1, 1, false)); b.accept(cap(1, 2, true, zh = ""))
        assertTrue(b.snapshot().isEmpty())
    }
}
