package dev.krita.jpsub

// Translator prompt and native result format. Pure Kotlin so the Mac harness (tools/mac-e2e) compiles the same code.

/** What the subtitles are about; changes the background text and the term list given to the translator. */
enum class Scene(val label: String, val background: String?, val terms: List<Pair<String, String>>) {
    GENERAL("通用", null, emptyList()),
    VTUBER(
        "B站 VTuber 直播",
        "这是日本虚拟主播（VTuber）在B站的直播，主播在和观众闲聊、念弹幕和SC（醒目留言）。原文是语音识别结果，可能有听错的词；口语里常有语气词和重复。",
        listOf(
            "ビリビリ" to "B站", "スパチャ" to "SC", "スーパーチャット" to "SC", "館長" to "舰长", "艦長" to "舰长",
            "コメント" to "弹幕", "流れ" to "（弹幕）刷屏", "配信" to "直播", "ギフト" to "礼物",
        ),
    ),
}

/**
 * Index-Translate-2B (Qwen3.5) prompt in the format the model was trained and benchmarked with (bilibili/Index-Translate
 * inference/llm/translate.py): the plain instruction when there are no constraints, otherwise 【源文】/【约束要求】 with
 * the terms found in [text] as a hard constraint and the scene / [notes] as soft ones.
 */
fun indexPrompt(text: String, scene: Scene, notes: List<String> = emptyList()): String {
    val terms = scene.terms.filter { text.contains(it.first) }.distinctBy { it.second }
    val constraints = buildList {
        if (terms.isNotEmpty()) add("【硬性要求】专名/术语对照: " + terms.joinToString("、") { "${it.first}→${it.second}" })
        scene.background?.let { add("【注意】$it") }
        notes.forEach { add("【注意】$it") }
    }
    val user = if (constraints.isEmpty()) "请将以下日语文本翻译为中文，直接输出翻译结果，不要进行任何解释。\n\n$text"
    else "请将以下日语直播字幕翻译成中文，并且严格遵循所有约束要求。\n\n【源文】\n$text\n\n【约束要求】\n" +
        constraints.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n") + "\n\n只输出译文，不要有任何额外说明。"
    // Qwen3.5 chat template with thinking disabled (empty think block), as rendered by the model's own template.
    return "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
}

/** The model occasionally still emits a think block or a stray "</think>"; the official client strips it too. */
fun stripThink(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").replace("</think>", "").replace("<think>", "").trim()


/**
 * One translation call as the native code reported it. stop: e=end, l=hit max tokens, p=prompt too long, f=failure.
 * [raw] is the model output exactly as generated (kept for the session log); [text] is what a caption shows.
 */
class MtResult(val raw: String, val stop: Char, val promptTokens: Int, val outputTokens: Int) {
    val text: String get() = stripThink(raw)

    /** Text usable as a caption: generation ended normally or ran into the token cap (kept, it is mostly complete). */
    val usable get() = (stop == 'e' || stop == 'l') && text.isNotEmpty()

    companion object {
        fun parse(b: ByteArray): MtResult {
            val nul = b.indexOf(0)
            val counts = String(b, nul + 1, b.size - nul - 1, Charsets.US_ASCII).split(',')
            return MtResult(String(b, 1, nul - 1, Charsets.UTF_8), b[0].toInt().toChar(), counts[0].toInt(), counts[1].toInt())
        }
    }
}

/**
 * Index notes for the translation errors Jev names reliably (37-sentence test, /tmp/jps/case/mt-compare.md): for the
 * other error classes its attribution was mostly wrong, so those are not acted on.
 */
val FIX_NOTES = mapOf(
    "negation" to "原文没有否定或拒绝的意思，译文不要凭空加入“不”“不用”“没有”等否定；“〜なんて”表示惊讶或感叹，不是否定。",
    "reversal" to "注意保持原文的肯定/否定和转折关系，不要把意思翻反（例如“寂しくない”是“不寂寞”）。",
    "literal_metaphor" to "原文里的比喻或直播用语不要按字面直译（例如“流れる”指弹幕/评论滚动得快，不是水流），按语境意译。",
)

