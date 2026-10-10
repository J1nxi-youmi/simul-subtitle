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
 * Index-Translate user message in the format the models were trained and benchmarked with (bilibili/Index-Translate
 * inference/llm/translate.py): the plain instruction when there are no constraints, otherwise 【源文】/【约束要求】 with
 * the terms found in [text] as a hard constraint and the scene / [notes] as soft ones. The same text goes to the online
 * API (as the user message) and to the local model (wrapped by [qwenChat]).
 */
fun indexPrompt(text: String, scene: Scene, notes: List<String> = emptyList()): String {
    val terms = scene.terms.filter { text.contains(it.first) }.distinctBy { it.second }
    val constraints = buildList {
        if (terms.isNotEmpty()) add("【硬性要求】专名/术语对照: " + terms.joinToString("、") { "${it.first}→${it.second}" })
        scene.background?.let { add("【注意】$it") }
        notes.forEach { add("【注意】$it") }
    }
    return if (constraints.isEmpty()) "请将以下日语文本翻译为中文，直接输出翻译结果，不要进行任何解释。\n\n$text"
    else "请将以下日语直播字幕翻译成中文，并且严格遵循所有约束要求。\n\n【源文】\n$text\n\n【约束要求】\n" +
        constraints.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n") + "\n\n只输出译文，不要有任何额外说明。"
}

/** Qwen3.5 chat template with thinking disabled (empty think block), as rendered by the model's own template. */
fun qwenChat(user: String) = "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

/** The model occasionally still emits a think block or a stray "</think>"; the official client strips it too. */
fun stripThink(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").replace("</think>", "").replace("<think>", "").trim()


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
    /** tests point this at a local server */
    private val url: String = URL,
) : Mt {
    private val calls = Calls(deadlineMs)

    override fun translate(prompt: String): MtResult? {
        val body = Json.encode(mapOf(
            "model" to model.id,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
            "temperature" to 0, "max_tokens" to MAX_TOKENS,
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
        /** same cap as the local model */
        const val MAX_TOKENS = 160
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

