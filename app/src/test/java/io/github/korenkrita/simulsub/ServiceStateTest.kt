package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.CaptionOverlayView.Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overlay's service lights and the warning line ([CaptionService.light], [CaptionService.serviceWarning]). */
class ServiceStateTest {
    private val now = 1_000_000L
    private val wall = java.time.ZonedDateTime.of(2026, 10, 12, 19, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val ok = ServiceCall(true, 200, null, null, 0)
    private val down = ServiceCall(false, 0, "java.net.UnknownHostException", null, 0)
    private fun limited(free: Boolean, resumeAt: Long?, until: Long = now + 60_000) =
        ServiceCall(false, 429, "HTTP 429", JevLimit(until, resumeAt, free), 0)
    private fun warn(mt: ServiceCall?, jev: ServiceCall?, ends: Boolean = true, check: Boolean = false) =
        CaptionService.serviceWarning(mt, jev, ends, check, now, wall)

    @Test
    fun lights() {
        assertEquals(Light.GREY, CaptionService.light(null, true))
        assertEquals(Light.GREY, CaptionService.light(ok, false)) // Jev switched off
        assertEquals(Light.GREEN, CaptionService.light(ok, true))
        assertEquals(Light.YELLOW, CaptionService.light(limited(true, null), true))
        assertEquals(Light.RED, CaptionService.light(down, true))
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
}
