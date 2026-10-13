package io.github.korenkrita.simulsub

/**
 * What the overlay shows about the translator and Jev: a light per service, why it is not green, and the always-shown
 * warning line. Shared by the phone and desktop overlays.
 */
object ServiceStatus {
    /** Grey = off or not asked yet, green = the last request worked, yellow = limited (paused), red = failed. */
    enum class Light(val color: Int, val word: String) {
        GREY(0xFF8B8B94.toInt(), "未用到"), GREEN(0xFF81C995.toInt(), "正常"),
        YELLOW(0xFFFDD663.toInt(), "受限"), RED(0xFFF28B82.toInt(), "出错"),
    }

    fun light(c: ServiceCall?, on: Boolean): Light = when {
        !on || c == null -> Light.GREY
        c.limited -> Light.YELLOW
        c.ok -> Light.GREEN
        else -> Light.RED
    }

    /**
     * Jev's light state: a pause the client is in ([paused]) wins (also one from before this session, or newer than
     * the last result's). Otherwise the last real result ([last]); when that was limited, with the service's
     * installed limit ([installed]): the result may have been taken while it was still being installed.
     */
    fun jevShown(last: ServiceCall?, paused: JevLimit?, installed: JevLimit?, from: Int): ServiceCall? {
        if (paused != null) return ServiceCall(false, 429, "paused", paused, from)
        if (last == null || !last.limited) return last
        return ServiceCall(last.ok, last.http, last.error, installed ?: last.limit, last.setup, true)
    }

    /** Why a light is not green, short; null for a working one. */
    fun reason(c: ServiceCall, jev: Boolean, nowMono: Long, nowWall: Long): String? {
        c.limit?.let { l ->
            val what = if (l.free) "免费额度已用完" else "请求受限"
            val whenText = when {
                nowMono >= l.untilMs -> "下一句重试"
                l.resumeAt != null -> "预计 ${clockText(l.resumeAt, nowWall)} 可重试"
                else -> "稍后重试"
            }
            return "$what，$whenText"
        }
        if (c.ok) return null
        return when {
            c.http == 401 || c.http == 403 -> "key 无效或没有权限"
            c.limited -> "请求受限，稍后重试".takeIf { jev } ?: "请求受限"
            c.http >= 500 -> "服务出错（HTTP ${c.http}）"
            c.http in 400..499 -> "请求被拒绝（HTTP ${c.http}）"
            c.http == 200 -> if (jev) "回答无法使用" else "没有得到译文"
            c.error?.startsWith("deadline") == true || c.error?.startsWith("no content") == true -> "响应超时"
            c.error == "bad key" -> "key 格式不对"
            c.error == "busy" -> "请求太多，没有发出"
            c.http == 0 && c.error != null && c.error.contains('.') -> "网络连不上"
            else -> "请求失败"
        }
    }

    /** The time of [at] (wall ms) as the device shows it, with the day when it is not today. */
    private fun clockText(at: Long, nowWall: Long): String {
        val z = java.time.ZoneId.systemDefault()
        val t = java.time.Instant.ofEpochMilli(at).atZone(z)
        val today = java.time.Instant.ofEpochMilli(nowWall).atZone(z).toLocalDate()
        val hm = "%02d:%02d".format(t.hour, t.minute)
        return when (t.toLocalDate()) { today -> hm; today.plusDays(1) -> "明天 $hm"; else -> "${t.monthValue}月${t.dayOfMonth}日 $hm" }
    }

    /**
     * The always-shown line for a failing service, or null. It says what still works: sentence ends by pauses
     * only when that switch is on, "翻译照常" only when the translator's last request worked.
     */
    fun serviceWarning(mt: ServiceCall?, jev: ServiceCall?, jevEnds: Boolean, jevCheck: Boolean, nowMono: Long, nowWall: Long): String? {
        fun bad(c: ServiceCall?) = c != null && light(c, true).let { it == Light.RED || it == Light.YELLOW }
        val mtBad = bad(mt)
        val jevBad = (jevEnds || jevCheck) && bad(jev)
        val parts = mutableListOf<String>()
        if (mtBad) parts += "⚠ 翻译：${reason(mt!!, false, nowMono, nowWall)}"
        if (jevBad) {
            val effects = mutableListOf<String>()
            if (jevEnds) effects += "断句改按停顿"
            if (jevCheck) effects += "译文不检查"
            if (mt?.ok == true) effects.add(0, "翻译照常")
            parts += "⚠ Jev ${reason(jev!!, true, nowMono, nowWall)} · ${effects.joinToString("，")}"
        }
        return parts.joinToString("  ·  ").ifEmpty { null }
    }
}
