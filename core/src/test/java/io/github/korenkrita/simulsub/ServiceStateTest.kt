package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.ServiceStatus.Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overlay's service lights and the warning line ([ServiceStatus.light], [ServiceStatus.serviceWarning]). */
class ServiceStateTest {
    private val now = 1_000_000L
    private val wall = java.time.ZonedDateTime.of(2026, 10, 12, 19, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val ok = ServiceCall(true, 200, null, null, 0)
    private val down = ServiceCall(false, 0, "java.net.UnknownHostException", null, 0)
    private fun limited(free: Boolean, resumeAt: Long?, until: Long = now + 60_000) =
        ServiceCall(false, 429, "HTTP 429", JevLimit(until, resumeAt, free), 0)
    private fun warn(mt: ServiceCall?, jev: ServiceCall?, ends: Boolean = true, check: Boolean = false) =
        ServiceStatus.serviceWarning(mt, jev, ends, check, now, wall)

    @Test
    fun lights() {
        assertEquals(Light.GREY, ServiceStatus.light(null, true))
        assertEquals(Light.GREY, ServiceStatus.light(ok, false)) // Jev switched off
        assertEquals(Light.GREEN, ServiceStatus.light(ok, true))
        assertEquals(Light.YELLOW, ServiceStatus.light(limited(true, null), true))
        assertEquals(Light.RED, ServiceStatus.light(down, true))
    }

    @Test
    fun workingServicesAndJevOffSayNothing() {
        assertNull(warn(ok, ok))
        assertNull(warn(null, null))
        // both Jev switches off: whatever an old state says, no warning
        assertNull(warn(ok, limited(true, null), ends = false, check = false))
    }

    @Test
    fun aFreeLimitSaysWhenAndWhatStillWorks() {
        val w = warn(ok, limited(true, wall + 13 * 3_600_000L))
        assertEquals("⚠ Jev 免费额度已用完，预计 明天 08:00 可重试 · 翻译照常，断句改按停顿", w)
        // another 429 without a time
        assertEquals("⚠ Jev 请求受限，稍后重试 · 翻译照常，断句改按停顿", warn(ok, limited(false, null)))
        // the pause is over: the next question tries again
        assertTrue(warn(ok, limited(false, null, until = now))!!.contains("下一句重试"))
    }

    @Test
    fun theWarningNamesOnlyTheFeaturesInUse() {
        assertEquals("⚠ Jev 请求受限，稍后重试 · 翻译照常，译文不检查", warn(ok, limited(false, null), ends = false, check = true))
        // the translator fails too: no "翻译照常"
        assertEquals("⚠ 翻译：网络连不上  ·  ⚠ Jev 请求受限，稍后重试 · 断句改按停顿", warn(down, limited(false, null)))
        assertEquals("⚠ 翻译：服务出错（HTTP 502）", warn(ServiceCall(false, 502, "HTTP 502", null, 0), ok))
        assertEquals("⚠ 翻译：响应超时", warn(ServiceCall(false, 0, "deadline 8000ms", null, 0), null))
    }

    @Test
    fun aLimitedTranslatorIsYellowAndAnUnknownOneIsNotCalledFine() {
        val mt429 = ServiceCall(false, 429, "HTTP 429", null, 0)
        assertEquals(Light.YELLOW, ServiceStatus.light(mt429, true))
        assertEquals("⚠ 翻译：请求受限", warn(mt429, ok))
        // the translator not asked yet: nothing claimed about it
        assertEquals("⚠ Jev 请求受限，稍后重试 · 断句改按停顿", warn(null, limited(false, null)))
    }

    @Test
    fun aLimitReportedWhileBeingInstalledShowsTheInstalledOneLater() {
        // the result was taken before the 429's end was known: no end of its own
        val last = ServiceCall(false, 429, "deadline 2000ms", null, 0)
        val installed = JevLimit(now - 1, null, false) // installed, and already over
        val shown = ServiceStatus.jevShown(last, null, installed, 0)!!
        assertEquals(Light.YELLOW, ServiceStatus.light(shown, true))
        assertTrue(warn(ok, shown)!!.contains("下一句重试"))
        // while still being installed (or live) the live pause wins
        val live = JevLimit(now + 60_000, null, false)
        assertEquals(live, ServiceStatus.jevShown(last, live, installed, 0)!!.limit)
        // a working last result stays as it was
        assertEquals(ok, ServiceStatus.jevShown(ok, null, installed, 0))
    }
}
