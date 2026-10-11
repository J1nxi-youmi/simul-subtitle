package io.github.korenkrita.simulsub

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
 * Index-Translate user message in the format the models were trained and benchmarked with (bilibili/Index-Translate
 * inference/llm/translate.py): the plain instruction when there are no constraints, otherwise 【源文】/【约束要求】 with
 * the terms found in [text] as a hard constraint and the scene / [notes] / [previous] sentences as soft ones. The same
 * text goes to the online API (as the user message) and to the local model (wrapped by [qwenChat]).
 */
fun indexPrompt(text: String, scene: Scene, notes: List<String> = emptyList(), previous: List<String> = emptyList(),
                source: Lang? = Lang.JA, target: Lang = Lang.ZH): String {
    // the term list maps Japanese words to Chinese renderings: only for a Chinese target
    val terms = if (target != Lang.ZH) emptyList() else scene.terms.filter { text.contains(it.first) }.distinctBy { it.second }
    val constraints = buildList {
        if (terms.isNotEmpty()) add("【硬性要求】专名/术语对照: " + terms.joinToString("、") { "${it.first}→${it.second}" })
        // the VTuber background says the streamer is Japanese; with another or an unknown source language it does not
        scene.background?.let { add("【注意】" + if (source == Lang.JA) it else it.replace("日本虚拟主播", "虚拟主播")) }
        notes.forEach { add("【注意】$it") }
        // the speaker's last sentences, for references and omissions (A/B on the live log: more fixes than regressions)
        if (previous.isNotEmpty()) add("【注意】前文（只用于理解当前句的指代和省略，不要翻译、不要输出）：" + previous.joinToString(" / "))
    }
    // as the official client: the source language is named unless it is auto (then the model works it out)
    val src = source?.zh ?: ""
    return if (constraints.isEmpty()) "请将以下${src}文本翻译为${target.zh}，直接输出翻译结果，不要进行任何解释。\n\n$text"
    else "请将以下${src}直播字幕翻译成${target.zh}，并且严格遵循所有约束要求。\n\n【源文】\n$text\n\n【约束要求】\n" +
        constraints.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n") + "\n\n只输出译文，不要有任何额外说明。"
}

/**
 * The 22 core languages of Index-Translate (technical report, table 31), with the names its prompt uses (official
 * client LANG_NAMES) and English names for the Jev questions. Targets: all. Sources: [SOURCES].
 */
enum class Lang(val code: String, val zh: String, val en: String) {
    ZH("zh", "中文", "Chinese"), EN("en", "英语", "English"), JA("ja", "日语", "Japanese"), KO("ko", "韩语", "Korean"),
    DE("de", "德语", "German"), FR("fr", "法语", "French"), ES("es", "西班牙语", "Spanish"), PT("pt", "葡萄牙语", "Portuguese"),
    RU("ru", "俄语", "Russian"), AR("ar", "阿拉伯语", "Arabic"), IT("it", "意大利语", "Italian"), NL("nl", "荷兰语", "Dutch"),
    PL("pl", "波兰语", "Polish"), RO("ro", "罗马尼亚语", "Romanian"), SV("sv", "瑞典语", "Swedish"), TR("tr", "土耳其语", "Turkish"),
    HI("hi", "印地语", "Hindi"), VI("vi", "越南语", "Vietnamese"), TH("th", "泰语", "Thai"), ID("id", "印尼语", "Indonesian"),
    MS("ms", "马来语", "Malay"), FIL("fil", "菲律宾语", "Filipino");

    companion object {
        /**
         * What ReazonSpeech (ja-en, one model, no language setting) is used for. The choice only tells the translator
         * and Jev the language; null = auto: Japanese or English, the translator works it out.
         */
        val SOURCES: List<Lang?> = listOf(JA, EN, null)
        fun of(code: String?): Lang? = entries.firstOrNull { it.code == code }
    }
}

/** Qwen3.5 chat template with thinking disabled (empty think block), as rendered by the model's own template. */
fun qwenChat(user: String) = "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

/** The model occasionally still emits a think block or a stray "</think>"; the official client strips it too. */
fun stripThink(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").replace("</think>", "").replace("<think>", "").trim()

/**
 * Output cap of one translation, local and online. 160 tokens covered the longest Chinese output measured (segments are
 * up to ~10 s with Jev), and the local model rejects a prompt when prompt + cap exceeds its 2048-token context, so
 * Chinese keeps 160. Other targets (Thai, Hindi, Arabic…) take more tokens per sentence: 256, not tuned or measured.
 */
fun mtMaxTokens(target: Lang) = if (target == Lang.ZH) 160 else 256

/**
 * One translation call. stop: e=end, l=hit max tokens, p=prompt too long, f=failure.
 * [raw] is the model output exactly as generated; [text] is what a caption shows. For the session log: [request] is
 * exactly what was sent (local: the full chat prompt; online: the JSON body), [response] the raw HTTP body (online),
 * [http] its status and [error] a fixed code or exception class. Timings the backend does not report stay null.
 */
class MtResult(
    val raw: String, val stop: Char, val promptTokens: Int?, val outputTokens: Int?,
    val prefillMs: Long? = null, val decodeMs: Long? = null,
    val request: String? = null, val response: String? = null, val http: Int? = null, val error: String? = null,
) {
    val text: String get() = stripThink(raw)

    /** Text usable as a caption: generation ended normally or ran into the token cap (kept, it is mostly complete). */
    val usable get() = (stop == 'e' || stop == 'l') && text.isNotEmpty()

    companion object {
        fun parse(b: ByteArray, request: String? = null): MtResult {
            val nul = b.indexOf(0)
            val c = String(b, nul + 1, b.size - nul - 1, Charsets.US_ASCII).split(',')
            return MtResult(String(b, 1, nul - 1, Charsets.UTF_8), b[0].toInt().toChar(), c[0].toInt(), c[1].toInt(),
                c.getOrNull(2)?.toLong(), c.getOrNull(3)?.toLong(), request = request)
        }
    }
}

/** The models behind the free public Index-Translate API (only 35B-A3B is listed by /v1/models; all three answer). */
enum class OnlineModel(val id: String, val label: String) {
    B35("Index-Translate-35B-A3B", "35B-A3B（推荐）"),
    B9("Index-Translate-9B", "9B"),
    B2("Index-Translate-2B", "2B（最快）"),
}

/**
 * bilibili's free public Index-Translate API (inference/llm/call_api.py): no key, the client's User-Agent, thinking off.
 * No retry and no fallback: a failure is a failed caption, visible as such. [cancel] is terminal (stop).
 */
class OnlineTranslator(
    private val model: OnlineModel,
    deadlineMs: Long = 8_000,
    private val maxTokens: Int = mtMaxTokens(Lang.ZH),
    /** tests point this at a local server */
    private val url: String = URL,
) : Mt {
    private val calls = Calls(deadlineMs)

    override fun translate(prompt: String): MtResult? {
        val body = Json.encode(mapOf(
            "model" to model.id,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
            "temperature" to 0, "max_tokens" to maxTokens,
            "chat_template_kwargs" to mapOf("enable_thinking" to false),
        ))
        val r = calls.post(url, body, mapOf("User-Agent" to "Index-Translate-Client/1.0"))
        // a stopped call is still logged with what was sent; the pipeline checks running before showing anything
        if (r.error == "cancelled") return MtResult("", 'f', null, null, request = body, http = r.status, error = "cancelled")
        val j = r.body?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }
        val choice = (j?.get("choices") as? List<*>)?.firstOrNull() as? Map<*, *>
        val content = (choice?.get("message") as? Map<*, *>)?.get("content") as? String
        val usage = j?.get("usage") as? Map<*, *>
        val stop = when {
            r.status != 200 || content == null -> 'f'
            choice["finish_reason"] == "stop" -> 'e'
            choice["finish_reason"] == "length" -> 'l'
            else -> 'f' // missing or unknown: not a translation we can trust; the raw response is kept
        }
        val error = r.error ?: when {
            r.status != 200 -> "HTTP ${r.status}"; content == null -> "bad response"; stop == 'f' -> "finish_reason"; else -> null
        }
        return MtResult(content ?: "", stop, (usage?.get("prompt_tokens") as? Number)?.toInt(),
            (usage?.get("completion_tokens") as? Number)?.toInt(), request = body, response = r.body, http = r.status, error = error)
    }

    override fun cancel() = calls.cancel()

    companion object {
        const val URL = "https://index-translate.bilibili.com/v1/chat/completions"
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

/** [FIX_NOTES] without the Japanese→Chinese examples, for every other language pair. */
val FIX_NOTES_GENERAL = mapOf(
    "negation" to "原文没有否定或拒绝的意思，译文不要凭空加入否定。",
    "reversal" to "注意保持原文的肯定/否定和转折关系，不要把意思翻反。",
    "literal_metaphor" to "原文里的比喻或直播用语不要按字面直译，按语境意译。",
)
fun fixNote(choice: String, source: Lang?, target: Lang) =
    (if (source == Lang.JA && target == Lang.ZH) FIX_NOTES else FIX_NOTES_GENERAL)[choice]

