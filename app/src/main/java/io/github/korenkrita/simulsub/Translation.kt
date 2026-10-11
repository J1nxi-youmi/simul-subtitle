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
    /** online, streamed: ms from sending until the first translated text arrived */
    val firstContentMs: Long? = null,
    val request: String? = null, val response: String? = null, val http: Int? = null, val error: String? = null,
    /** online: the answer carried reasoning (thinking was not off); never shown */
    val thinking: Boolean = false,
    /** online: prompt tokens the service reports as served from its prefix cache (null: not reported) */
    val cachedTokens: Int? = null,
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
 * Streamed, as the Index developers advise: their gateway answers 504 to a request that waits over 20 s for a free
 * slot, a streamed one is answered early. No translated text within [firstContentMs] (queueing, a slow network, ...)
 * fails this caption; once text flows, the whole call still ends [deadlineMs] after sending. The caption shows the
 * whole sentence at once. No retry and no fallback: a failure is a failed caption, visible as such.
 * [cancel] is terminal (stop).
 */
class OnlineTranslator(
    private val model: OnlineModel,
    private val deadlineMs: Long = 8_000,
    private val maxTokens: Int = mtMaxTokens(Lang.ZH),
    /** tests point this at a local server */
    private val url: String = URL,
    private val firstContentMs: Long = 1_000,
) : Mt {
    private val calls = Calls(deadlineMs)

    override fun translate(prompt: String): MtResult? {
        val body = Json.encode(mapOf(
            "model" to model.id,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
            "temperature" to 0, "max_tokens" to maxTokens, "stream" to true,
            "stream_options" to mapOf("include_usage" to true),
            "chat_template_kwargs" to mapOf("enable_thinking" to false),
        ))
        val r = calls.post(url, body, mapOf("User-Agent" to "Index-Translate-Client/1.0"),
            firstContentMs = firstContentMs, isContent = ::hasText, isEnd = ::isDone)
        return streamResult(r, body, r.body)
    }

    override fun cancel() = calls.cancel()

    class Streamed(val content: String, val finish: String?, val promptTokens: Int?, val outputTokens: Int?,
                   /** reasoning text, a <think> block or reasoning tokens were seen: thinking was not off */
                   val thinking: Boolean = false,
                   /** prompt tokens the service says it served from its prefix cache (null: not reported) */
                   val cachedTokens: Int? = null)

    companion object {
        /** The end marker of an OpenAI-style stream ("data: [DONE]", with or without the space). */
        fun isDone(line: String) = line.startsWith("data:") && line.removePrefix("data:").trim() == "[DONE]"

        /**
         * A streamed call as an [MtResult]: [request] is what was sent, [response] the body for the log (BYOK: with the
         * key removed). A stopped call keeps what was sent; the pipeline checks running before showing anything.
         */
        fun streamResult(r: HttpResult, request: String, response: String?): MtResult {
            if (r.error == "cancelled") return MtResult("", 'f', null, null, firstContentMs = r.firstContentMs, request = request, http = r.status, error = "cancelled")
            val s = if (r.status == 200) r.body?.let { parseStream(it) } else null
            val stop = when {
                r.status != 200 || s == null -> 'f'
                s.finish == "stop" -> 'e'
                s.finish == "length" -> 'l'
                else -> 'f' // missing or unknown: not a translation we can trust; the raw response is kept
            }
            val error = r.error ?: when {
                r.status != 200 -> "HTTP ${r.status}"; s == null -> "bad response"; stop == 'f' -> "finish_reason"; else -> null
            }
            return MtResult(s?.content ?: "", stop, s?.promptTokens, s?.outputTokens, firstContentMs = r.firstContentMs,
                request = request, response = response, http = r.status, error = error,
                thinking = s?.thinking ?: false, cachedTokens = s?.cachedTokens)
        }

        const val URL = "https://index-translate.bilibili.com/v1/chat/completions"
        private val REASONING_FIELDS = listOf("reasoning_content", "reasoning")

        /** One SSE line carrying translated text (not a role-only, empty, usage or keep-alive event). */
        fun hasText(line: String): Boolean {
            val data = line.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: return false
            val j = runCatching { Json.parse(data) as? Map<*, *> }.getOrNull() ?: return false
            val choice = (j["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>
            return ((choice?.get("delta") as? Map<*, *>)?.get("content") as? String).orEmpty().isNotEmpty()
        }

        /**
         * OpenAI-style server-sent events: "data: {chunk}" lines up to "data: [DONE]"; other lines (comments, event
         * names, blank) are skipped. Null (not trusted) when the stream does not end with [DONE], a chunk is not a
         * JSON object, reports an error, or its choices / delta / content have the wrong type: part of the text may
         * be missing. Role-only and usage-only chunks are fine.
         */
        fun parseStream(body: String): Streamed? {
            val text = StringBuilder(); var finish: String? = null; var done = false; var thinking = false
            var promptTokens: Int? = null; var outputTokens: Int? = null; var cached: Int? = null
            for (line in body.lineSequence()) {
                if (line.startsWith("event:") && line.removePrefix("event:").trim() == "error") return null
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") { done = true; break }
                val j = runCatching { Json.parse(data) as? Map<*, *> }.getOrNull() ?: return null
                if (j["error"] != null) return null
                val choices = j["choices"] as? List<*> ?: return null
                if (choices.isNotEmpty()) {
                    val choice = choices[0] as? Map<*, *> ?: return null
                    val delta = choice["delta"]
                    if (delta != null && delta !is Map<*, *>) return null
                    when (val c = (delta as? Map<*, *>)?.get("content")) { null -> {}; is String -> text.append(c); else -> return null }
                    when (val f = choice["finish_reason"]) { null -> {}; is String -> finish = f; else -> return null }
                    // DeepSeek / vLLM / SGLang: reasoning_content; OpenRouter and others: reasoning
                    if (REASONING_FIELDS.any { ((delta as? Map<*, *>)?.get(it) as? String).orEmpty().isNotEmpty() }) thinking = true
                }
                (j["usage"] as? Map<*, *>)?.let { u ->
                    promptTokens = (u["prompt_tokens"] as? Number)?.toInt(); outputTokens = (u["completion_tokens"] as? Number)?.toInt()
                    // OpenAI-style prompt_tokens_details.cached_tokens, DeepSeek prompt_cache_hit_tokens
                    cached = ((u["prompt_tokens_details"] as? Map<*, *>)?.get("cached_tokens") as? Number)?.toInt()
                        ?: (u["prompt_cache_hit_tokens"] as? Number)?.toInt() ?: cached
                    val reasoningTokens = ((u["completion_tokens_details"] as? Map<*, *>)?.get("reasoning_tokens") as? Number)?.toInt()
                    if ((reasoningTokens ?: 0) > 0) thinking = true
                }
            }
            if (text.contains("<think>")) thinking = true
            return if (!done) null else Streamed(text.toString(), finish, promptTokens, outputTokens, thinking, cached)
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

/** [FIX_NOTES] without the Japanese→Chinese examples, for every other language pair. */
val FIX_NOTES_GENERAL = mapOf(
    "negation" to "原文没有否定或拒绝的意思，译文不要凭空加入否定。",
    "reversal" to "注意保持原文的肯定/否定和转折关系，不要把意思翻反。",
    "literal_metaphor" to "原文里的比喻或直播用语不要按字面直译，按语境意译。",
)
fun fixNote(choice: String, source: Lang?, target: Lang) =
    (if (source == Lang.JA && target == Lang.ZH) FIX_NOTES else FIX_NOTES_GENERAL)[choice]


/** Where captions are translated. LOCAL needs the model file; INDEX and BYOK need the network. */
enum class MtMode { INDEX, LOCAL, BYOK }

/**
 * BYOK system message: everything that is the same for every sentence of a session (instructions, languages, scene
 * background, the scene's whole term list), so that every request starts with the same bytes and a service with prefix
 * caching can reuse it. The Chinese-only term list is given only for a Chinese target, as in [indexPrompt].
 */
fun byokSystem(scene: Scene, source: Lang?, target: Lang): String = buildString {
    val src = source?.zh ?: "外语"
    append("你是直播字幕翻译。把【当前句】的${src}翻译成自然、口语化的${target.zh}，准确保留原意、肯定否定和人物关系；")
    append("保留人名，不凭空补充内容。字幕要简短，可以省略无意义的口头重复。")
    append("【前文】只用于理解当前句的指代和省略，不要翻译、不要输出。【注意】是这一句的额外要求。")
    append("只输出当前句的译文，不要解释、注释或其他文字。")
    scene.background?.let { append("\n\n背景：" + if (source == Lang.JA) it else it.replace("日本虚拟主播", "虚拟主播")) }
    if (target == Lang.ZH && scene.terms.isNotEmpty())
        append("\n\n术语（原文出现时必须这样译）：" + scene.terms.joinToString("、") { "${it.first}→${it.second}" })
}

/** BYOK user message: the parts that change, oldest first, the current sentence and its one-off notes last. */
fun byokUser(text: String, notes: List<String> = emptyList(), previous: List<String> = emptyList()): String = buildString {
    if (previous.isNotEmpty()) append("【前文】\n" + previous.joinToString("\n") + "\n\n")
    append("【当前句】\n$text")
    notes.forEach { append("\n\n【注意】$it") }
}

/**
 * The user's own OpenAI-compatible service (BYOK, experimental): streamed, same rules as [OnlineTranslator] (no text
 * within [firstContentMs] fails the caption, the call ends [deadlineMs] after sending, whole sentence at once, no
 * retry). [system] is fixed for the session; [translate] takes the [byokUser] text.
 *
 * Thinking must be off, but there is no standard switch: [THINKING_OFF] lists the ones we know. [variant] is the one
 * [probe] found for this URL + model (null: none worked, the request carries no switch and may think and be slow).
 * Reasoning in an answer is never shown; it is only flagged ([MtResult.thinking]).
 */
class ByokTranslator(
    private val url: String,
    private val model: String,
    private val apiKey: String,
    private val system: String,
    private val variant: ThinkingOff?,
    private val deadlineMs: Long = 8_000,
    private val maxTokens: Int = mtMaxTokens(Lang.ZH),
    private val firstContentMs: Long = 1_000,
) : Mt {
    private val calls = Calls(deadlineMs)

    override fun translate(prompt: String): MtResult? = call(prompt, variant, firstContentMs)

    private fun call(user: String, v: ThinkingOff?, firstMs: Long): MtResult {
        val body = Json.encode(mapOf(
            "model" to model,
            "messages" to listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user)),
            "temperature" to 0, "max_tokens" to maxTokens, "stream" to true,
            "stream_options" to mapOf("include_usage" to true),
        ) + (v?.params ?: emptyMap()))
        val r = calls.post(url, body, mapOf("Authorization" to "Bearer $apiKey"),
            firstContentMs = firstMs, isContent = OnlineTranslator::hasText, isEnd = OnlineTranslator::isDone)
        // the key is in a header, not the body; a service may still echo it in an error
        return OnlineTranslator.streamResult(r, body, r.body?.let { Jev.redact(it, apiKey) })
    }

    override fun cancel() = calls.cancel()

    /** One way to ask for no thinking; [params] are added to the request body. */
    class ThinkingOff(val name: String, val label: String, val params: Map<String, Any>)

    /** One probe step: which variant, what came back. */
    class Step(val variant: ThinkingOff, val result: MtResult)

    /**
     * Tries [THINKING_OFF] in order with a short test sentence until one answers in full and without reasoning; that
     * one is returned (the caller keeps it for this URL + model). Null = none did, see [steps]. Waits up to the whole
     * deadline for each (no 1 s first-text cutoff: a slow first answer is not a wrong switch). Stops early when the
     * service clearly fails for a reason no switch changes (wrong key, unknown model, not reachable).
     */
    fun probe(steps: MutableList<Step>): ThinkingOff? {
        for (v in THINKING_OFF) {
            val r = call(byokUser(PROBE_TEXT), v, deadlineMs)
            steps += Step(v, r)
            if (r.error == "cancelled") return null
            if (r.usable && !r.thinking) return v
            // wrong key / no access / unknown model or path / rate limited / not reachable: no switch changes that
            // (a timeout is not among them: thinking can make an answer slow)
            if (r.http in listOf(401, 403, 404, 429) || (r.http == 0 && r.error?.startsWith("deadline") != true)) return null
        }
        return null
    }

    companion object {
        const val PROBE_TEXT = "こんにちは、今日もよろしくお願いします。"
        /** Stored instead of a variant when the service answered but every switch still thought: do not probe again. */
        const val NONE_WORKED = "-"

        /** A failed call in words for the user (no response text: it may hold anything). */
        fun why(r: MtResult): String = when {
            r.http == 401 || r.http == 403 -> "key 不对或没有权限（HTTP ${r.http}）"
            r.http == 404 -> "地址或模型 ID 不对（HTTP 404）"
            r.http == 429 -> "请求太频繁或额度用完（HTTP 429）"
            r.http != null && r.http != 0 && r.http != 200 -> "服务返回错误（HTTP ${r.http}）"
            r.error?.startsWith("deadline") == true -> "8 秒内没有翻完"
            r.error?.startsWith("no content") == true -> "1 秒内没有收到译文"
            r.error == "bad response" || r.error == "finish_reason" -> "回复的格式不对，可能不是 OpenAI 兼容的流式接口"
            r.error == null -> "没有收到译文"
            r.error!!.contains("UnknownHost") -> "找不到这个域名"
            r.error!!.contains("SSL") || r.error!!.contains("Certificate") -> "安全连接失败（证书问题）"
            else -> "连不上（${r.error!!.substringAfterLast('.')}）"
        }

        /**
         * Known ways to turn thinking off, most specific first. A service that does not know a field usually ignores
         * it, but some reject unknown fields (HTTP 400); then the next one is tried.
         */
        val THINKING_OFF = listOf(
            // DeepSeek API and services copying it (also GLM / Kimi style "thinking")
            ThinkingOff("thinking", "thinking: disabled", mapOf("thinking" to mapOf("type" to "disabled"))),
            // vLLM / SGLang / many Qwen3 hosts: the chat template switch
            ThinkingOff("template", "chat_template_kwargs", mapOf("chat_template_kwargs" to mapOf("enable_thinking" to false, "thinking" to false))),
            // SiliconFlow, DashScope (Qwen) and others: a top-level flag
            ThinkingOff("enable", "enable_thinking: false", mapOf("enable_thinking" to false)),
            // OpenRouter: the unified reasoning object
            ThinkingOff("reasoning", "reasoning: enabled false", mapOf("reasoning" to mapOf("enabled" to false))),
            // OpenAI-style reasoning models and Gemini's OpenAI endpoint
            ThinkingOff("effort", "reasoning_effort: none", mapOf("reasoning_effort" to "none")),
            // the model may not think at all: nothing extra
            ThinkingOff("none", "不加参数", emptyMap()),
        )
        fun variant(name: String?) = THINKING_OFF.firstOrNull { it.name == name }

        /**
         * The chat completions URL for what the user typed: https only (the key must not travel in clear text), no
         * user info, query or fragment; a path ending in /v1 (or /v1/chat/completions) is used as is, otherwise /v1 is
         * added. Returns (error text, null) or (null, url).
         */
        fun chatUrl(input: String): Pair<String?, String?> {
            val s = input.trim()
            if (s.isEmpty()) return "还没填地址" to null
            val u = runCatching { java.net.URI(s) }.getOrNull() ?: return "地址格式不对" to null
            if (u.scheme?.lowercase() != "https") return "地址要以 https:// 开头" to null
            if (u.host.isNullOrEmpty()) return "地址里没有域名" to null
            if (u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return "地址里不要带用户名、?参数 或 #" to null
            var path = (u.rawPath ?: "").trimEnd('/')
            path = path.removeSuffix("/chat/completions")
            if (!path.endsWith("/v1")) path += "/v1"
            val port = if (u.port > 0) ":${u.port}" else ""
            return null to "https://${u.host.lowercase()}$port$path/chat/completions"
        }
    }
}
