package io.github.korenkrita.simulsub

/**
 * DIY: 译文检查改用用户自己的 LLM API，不再依赖 Jev 的私有协议。
 *
 * 问法和 Jev 那套保持一致（[FIX_NOTES] 的分类），所以重译流程（Pipeline.checked → Retry）不用改：
 * 返回的 choice 仍然是 negation / reversal / literal_metaphor，confidence 低于阈值就不重译。
 * 任何失败（超时、返回看不懂）都当作「没有问题」，绝不因此丢掉已经翻好的字幕。
 */
class ByokJudge(private val mt: ByokTranslator) : Judge {

    override fun unfinished(text: String): JevCall = JevCall("", 0, null, 0, "disabled", null)

    override fun mistranslation(ja: String, zh: String): JevCall {
        if (zh.isBlank()) return JevCall("", 0, null, 0, "empty", null)
        val prompt = ask(ja, zh)
        val t0 = System.nanoTime()
        val r = mt.translate(prompt)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (r == null) return JevCall(prompt, 0, null, ms, "no answer", null)
        val o = pickJson(r.text)
        val choice = (o?.get("choice") as? String)?.trim()?.lowercase()
        val conf = (o?.get("confidence") as? Number)?.toDouble() ?: 0.0
        // 只要三个已知分类之一，其余（"ok" 或模型乱答）都按没问题处理
        val known = choice != null && fixNote(choice, Lang.JA, Target.ZH) != null
        val answer = if (known) mapOf("choice" to choice, "confidence" to conf) else mapOf("choice" to "ok", "confidence" to 1.0)
        return JevCall(prompt, 200, r.text, ms, if (known) null else "ok", answer)
    }

    override fun cancel() {}

    private fun ask(ja: String, zh: String) = buildString {
        append("判断下面这句字幕译文有没有错。只输出一行 JSON，不要解释：")
        append("{\"choice\":\"ok\",\"confidence\":0.9}\n")
        append("choice 只能取这些值之一：\n")
        append("- ok：译得对，或只有语感上的小差异\n")
        append("- negation：原文没有否定，译文却加了「不」「没」之类的否定\n")
        append("- reversal：把肯定/否定或转折关系翻反了\n")
        append("- literal_metaphor：原文是比喻、梗或直播用语，译文按字面直译了\n")
        append("confidence 是你对这个判断的把握，0 到 1 之间的小数。\n")
        append("原文：").append(ja).append('\n')
        append("译文：").append(zh)
    }

    private fun pickJson(text: String): Map<*, *>? {
        val i = text.indexOf('{')
        val j = text.lastIndexOf('}')
        if (i < 0 || j <= i) return null
        return runCatching { Json.parse(text.substring(i, j + 1)) as? Map<*, *> }.getOrNull()
    }

    companion object {
        /** 校验用的 system：短、只要求一行 JSON，省 token 也省时间。 */
        const val SYSTEM = "你在校对实时字幕的译文。只回答一行 JSON，不要任何解释、不要 Markdown。"
    }
}
