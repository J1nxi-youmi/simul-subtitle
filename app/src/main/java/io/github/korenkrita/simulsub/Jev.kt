package io.github.korenkrita.simulsub

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

// TypeSafe Jev (System One decision model) client and the two questions the pipeline asks it.
// Pure Kotlin + OkHttp so the Mac harness (tools/mac-e2e) runs the same code.

/** [needsKey] false: no Authorization header is sent (Zen rejects a wrong key with 401 but answers without one). */
enum class JevProvider(val label: String, val url: String, val model: String, val needsKey: Boolean = true, val note: String) {
    // free and keyless, but undocumented: it may get rate limits or a login at any time (tested 2026-10-10)
    ZEN("OpenCode Zen", "https://opencode.ai/zen/v1/systemone", "jev-1.13-free", needsKey = false, note = "免费，不用 key"),
    OFFICIAL("TypeSafe 官方", "https://api.typesafe.ai/v1/systemone", "jev-latest", note = "需要 key"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/systemone", "~typesafe/jev-latest", note = "需要 key"),
}

/**
 * One request with everything the session log needs. [answer] is the parsed `answers.x`, null on any failure.
 * [limit]: the service limited requests (HTTP 429), now or before (then nothing was sent, [error] "paused").
 */
class JevCall(val request: String, val status: Int, val response: String?, val ms: Long, val error: String?, val answer: Map<*, *>?,
              val limit: JevLimit? = null) {
    val noul: Double? get() = (answer?.get("noul") as? Number)?.toDouble()
    val choice: String? get() = answer?.get("choice") as? String
    val confidence: Double? get() = (answer?.get("confidence") as? Number)?.toDouble()
}

/**
 * A 429 from a Jev service: no request is sent to it before [untilMs] (monotonic). [resumeAt] is the wall time
 * its Retry-After named, null when it named none (then a short backoff, retried by the next request). [free]:
 * OpenCode Zen's free usage limit (FreeUsageLimitError).
 */
class JevLimit(val untilMs: Long, val resumeAt: Long?, val free: Boolean)

/** What the pipeline asks; an interface so tests can answer without a network. */
interface Judge {
    /** Is the speech-recognition text that ended at a pause an unfinished sentence? */
    fun unfinished(text: String): JevCall
    /** What is wrong with this translation, if anything? */
    fun mistranslation(ja: String, zh: String): JevCall
    /** Abort the request in progress (stop). */
    fun cancel()
    /** The service limited requests and the pause has not ended: a question now is not sent. */
    fun paused(): JevLimit? = null
}

class Jev(
    private val provider: JevProvider,
    private val apiKey: String,
    private val deadlineMs: Long = 2_000,
    /** tests point this at a local server */
    private val url: String = provider.url,
    /** tests: runs after the request is registered for cancel() and before it is executed */
    private val beforeSend: () -> Unit = {},
    /** the session's languages; Japanese→Chinese uses the wording tuned on the test sets */
    private val source: Lang? = Lang.JA,
    private val target: Lang = Lang.ZH,
    /** tests */
    private val clock: () -> Long = ::monoMs,
    private val wall: () -> Long = System::currentTimeMillis,
) : Judge {
    private val calls = Calls(deadlineMs)
    private val unfinishedQ = unfinishedQuestion(source)
    private val mistranslationQ = mistranslationQuestion(source, target)
    /** the pause is per service and credentials, so a rebuilt setup (new client) does not end it */
    private val limitKey = "$url\n$apiKey"

    override fun unfinished(text: String) = ask(mapOf("current" to text), unfinishedQ)
    override fun mistranslation(ja: String, zh: String) = ask(mapOf("ja" to ja, "zh" to zh), mistranslationQ)

    override fun cancel() = calls.cancel()
    override fun paused(): JevLimit? = LIMITS[limitKey]?.takeIf { clock() < it.untilMs }

    private fun ask(state: Map<String, String>, question: Map<String, Any>): JevCall {
        val body = Json.encode(mapOf("model" to provider.model, "state" to state, "questions" to mapOf("x" to question)))
        // an illegal header value would make the HTTP library put the key into its exception message
        if (provider.needsKey && !validKey(apiKey)) return JevCall(body, 0, null, 0, "bad key", null)
        paused()?.let { return JevCall(body, 0, null, 0, "paused", null, it) }
        val auth = if (provider.needsKey) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
        val r = calls.post(url, body, auth, beforeSend)
        val answer = if (r.status == 200 && r.body != null)
            runCatching { ((Json.parse(r.body) as? Map<*, *>)?.get("answers") as? Map<*, *>)?.get("x") as? Map<*, *> }.getOrNull()
        else null
        // a later end wins: an older request's answer arriving now never shortens (or ends) a pause
        val limit = if (r.status != 429) null else {
            val secs = retrySeconds(r.retryAfter, wall())?.coerceAtMost(MAX_PAUSE_S)
            val l = JevLimit(clock() + (secs ?: BACKOFF_S) * 1000, secs?.let { wall() + it * 1000 },
                r.body?.contains("FreeUsageLimitError") == true)
            LIMITS.merge(limitKey, l) { a, b -> if (b.untilMs > a.untilMs) b else a }
        }
        // a server may echo the key back (also JSON-escaped); the session log is shareable
        return JevCall(body, r.status, r.body?.let { redact(it, apiKey) }, r.ms, r.error ?: if (answer == null) "HTTP ${r.status}" else null, answer, limit)
    }

    companion object {
        /** Printable ASCII only: a valid bearer token, and nothing the HTTP library would reject (and echo). */
        fun validKey(k: String) = k.isNotEmpty() && k.all { it in '!'..'~' }
        /** pauses after a 429, by service and credentials, for the whole process (a new session still waits) */
        private val LIMITS = java.util.concurrent.ConcurrentHashMap<String, JevLimit>()
        /** a 429 without a usable Retry-After: tried again after this */
        const val BACKOFF_S = 60L
        /** a longer Retry-After is taken as this (a day limit resets within a day) */
        const val MAX_PAUSE_S = 86_400L

        /** Seconds a Retry-After value ([h]: delay-seconds or an HTTP-date) asks to wait; null: none or not usable. */
        fun retrySeconds(h: String?, nowWall: Long): Long? {
            val v = h?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            v.toLongOrNull()?.let { return it.takeIf { s -> s > 0 } }
            val at = runCatching {
                java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull() ?: return null
            return ((at - nowWall + 999) / 1000).takeIf { it > 0 }
        }

        /**
         * [body] for the log without [secret], as text (a body need not be valid JSON): every spelling of the key, with any
         * of its characters written literally or as a JSON escape (\uXXXX, \" \\ \/), is replaced by ***. A body without
         * the key is returned unchanged (the raw response).
         */
        fun redact(body: String, secret: String): String {
            if (secret.isEmpty()) return body
            val alt = secret.toList().joinToString("") { c: Char ->
                val u = "%04x".format(c.code)
                val esc = when (c) { '"' -> "|\\\\\""; '\\' -> "|\\\\\\\\"; '/' -> "|\\\\/"; else -> "" }
                "(?:${Regex.escape(c.toString())}|\\\\u(?i:$u)$esc)"
            }
            return body.replace(Regex(alt), "***")
        }
        /** Wording tuned on 140 labelled segment pairs without the following text (AUC 0.96, /tmp/jps/case/decision-bench.md). */
        val UNFINISHED = mapOf(
            "type" to "noul",
            "instructions" to "`current` is raw automatic speech-recognition (ASR) output for a Japanese livestream, ending where the streamer just paused. The ASR often mishears words: a short fragment may be a misrecognized piece of a longer word or phrase, even if it looks like a valid word. Decide whether the speaker's sentence is still unfinished at the end of `current`, so we should wait and join it with what the speaker says next. Unfinished: `current` ends with a dangling particle or connective (は, が, を, に, で, と, て, けど, から, たら, とか, って), a modifier waiting for its noun, a word cut in half, or is a short fragment that makes no sense in context and is probably misheard. Finished: `current` ends with a sentence-final form (です, ます, よ, ね, な, じゃない, ありがとう, ごめんね, こんばんは) or is a standalone filler/reaction (はい, えっ, あの, えっと).",
            "criteria" to mapOf(
                "true" to "unfinished or misheard fragment: wait for the next words",
                "false" to "finished: current is a complete utterance or a standalone filler/reaction",
            ),
        )
        /** Same classes as the 37-sentence test; only [FIX_NOTES] keys are acted on. */
        val MISTRANSLATION = mapOf(
            "type" to "choice",
            "instructions" to "`ja` is Japanese VTuber livestream speech (raw speech recognition, may contain misheard words); `zh` is a Chinese machine translation that may be wrong. What is the main problem with `zh`?",
            "criteria" to mapOf(
                "negation" to "zh adds a negation or refusal that ja does not contain",
                "reversal" to "zh reverses the meaning or the positive/negative polarity of ja",
                "literal_metaphor" to "zh translates an idiom or livestream expression literally (e.g. comments 'flowing' rendered as water flowing)",
                "platform_term" to "zh mistranslates a livestream/platform term such as a platform name, superchat, member, gift",
                "untranslated" to "zh leaves Japanese words untranslated",
                "misheard" to "ja itself contains misheard nonsense words and zh translates them literally",
                "hallucination" to "zh adds content that ja does not say",
                "ok" to "zh is an acceptable translation",
            ),
        )

        /**
         * The "unfinished?" question for [source]: Japanese keeps the tuned wording; English and auto (Japanese or
         * English) get the same criteria without the Japanese word lists (not tuned or measured).
         */
        fun unfinishedQuestion(source: Lang?): Map<String, Any> = if (source == Lang.JA) UNFINISHED else mapOf(
            "type" to "noul",
            "instructions" to "`current` is raw automatic speech-recognition (ASR) output for a ${source?.en ?: "Japanese or English"} livestream, ending where the streamer just paused. The ASR often mishears words: a short fragment may be a misrecognized piece of a longer word or phrase, even if it looks like a valid word. Decide whether the speaker's sentence is still unfinished at the end of `current`, so we should wait and join it with what the speaker says next. Unfinished: `current` ends with a dangling preposition, conjunction, article or particle, a modifier waiting for its noun, a word cut in half, or is a short fragment that makes no sense in context and is probably misheard. Finished: `current` ends with a complete sentence or is a standalone filler/reaction.",
            "criteria" to UNFINISHED["criteria"]!!,
        )

        /** The check for a language pair: Japanese→Chinese keeps the tested wording, others name their languages. */
        fun mistranslationQuestion(source: Lang?, target: Lang): Map<String, Any> {
            if (source == Lang.JA && target == Lang.ZH) return MISTRANSLATION
            val src = source?.en ?: "Japanese or English"
            return mapOf(
                "type" to "choice",
                "instructions" to "`ja` is $src VTuber livestream speech (raw speech recognition, may contain misheard words); `zh` is a ${target.en} machine translation of it that may be wrong. What is the main problem with `zh`?",
                "criteria" to mapOf(
                    "negation" to "zh adds a negation or refusal that ja does not contain",
                    "reversal" to "zh reverses the meaning or the positive/negative polarity of ja",
                    "literal_metaphor" to "zh translates an idiom or livestream expression literally",
                    "platform_term" to "zh mistranslates a livestream/platform term such as a platform name, superchat, member, gift",
                    "untranslated" to "zh leaves $src words untranslated",
                    "misheard" to "ja itself contains misheard nonsense words and zh translates them literally",
                    "hallucination" to "zh adds content that ja does not say",
                    "ok" to "zh is an acceptable translation",
                ),
            )
        }
    }
}

/**
 * One POST as the session log records it. [error] is a fixed code or an exception class name, never a message.
 * [retryAfter]: the Retry-After header of a 429, as sent.
 */
class HttpResult(val status: Int, val body: String?, val ms: Long, val error: String?, val firstContentMs: Long? = null,
                 val retryAfter: String? = null)

/**
 * POSTs of one session (Jev and online MT). Each request is an OkHttp [Call], registered before it runs:
 * [cancel] marks the session stopped and cancels every registered call, and a call cancelled before it is
 * enqueued never sends anything (OkHttp's cancellation is terminal). Data already sent cannot be taken back.
 * The caller waits at most [deadlineMs] (and returns at once on [cancel]) even where OkHttp cannot interrupt the
 * work, e.g. a blocking DNS lookup. Such leftover work is bounded process-wide: at most [MAX_PENDING] requests
 * (all sessions) may be unfinished in OkHttp; beyond that a request fails at once with "busy" instead of queueing.
 */
class Calls(private val deadlineMs: Long, dns: Dns = Dns.SYSTEM, /** tests: bypass a system proxy */ proxy: java.net.Proxy? = null) {
    // one dispatcher / connection pool for the process; per-session settings are a cheap derived client
    private val client = BASE.newBuilder()
        .callTimeout(deadlineMs, TimeUnit.MILLISECONDS) // the whole call, including a slowly trickling body
        .dns(dns).apply { proxy?.let { proxy(it) } }
        .build()
    /** what a caller waits on: the end of its call, and (streamed) the first content */
    private class Waiters(val done: CountDownLatch, val started: CountDownLatch)
    /** registered calls and their callers' latches */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Call, Waiters>()
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; inFlight.forEach { (c, w) -> c.cancel(); w.done.countDown(); w.started.countDown() } }

    /**
     * Streamed answers (online MT): [firstContentMs] is set and [isContent] tells, per body line, whether real output
     * has started (for SSE: the first chunk with non-empty text). Without such a line within [firstContentMs] of the
     * start the call is given up; afterwards the whole call still ends [deadlineMs] after the start. Reading stops
     * after a line [isEnd] accepts (the stream's end marker). [HttpResult.firstContentMs] reports when the content
     * started. Without [firstContentMs] (Jev) the body is read whole, as before.
     */
    fun post(url: String, json: String, headers: Map<String, String>, beforeSend: () -> Unit = {},
             firstContentMs: Long? = null, isContent: (String) -> Boolean = { false },
             isEnd: (String) -> Boolean = { false }): HttpResult {
        val t0 = monoMs()
        if (cancelled) return HttpResult(0, null, 0, "cancelled")
        val req = Request.Builder().url(url).post(json.toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = client.newCall(req)
        // counted down by the end of the call, by cancel(), and (streamed) by the first content line
        val done = CountDownLatch(1)
        val started = CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicLong(-1)
        fun firstOrNull() = first.get().takeIf { it >= 0 }
        val result = AtomicReference<HttpResult?>()
        inFlight[call] = Waiters(done, started)
        // cancel() either saw the call in the map or set the flag before this check
        if (cancelled) { inFlight.remove(call); return HttpResult(0, null, 0, "cancelled") }
        var admitted = false
        try {
            beforeSend()
            if (cancelled) return HttpResult(0, null, monoMs() - t0, "cancelled")
            // the slot is returned when OkHttp is really done with the call, not when this caller gives up
            if (!PENDING.tryAcquire()) return HttpResult(0, null, monoMs() - t0, "busy")
            admitted = true
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    PENDING.release()
                    result.set(HttpResult(0, null, monoMs() - t0, why(e, t0), firstOrNull())); done.countDown(); started.countDown()
                }
                override fun onResponse(call: Call, response: Response) {
                    var r: HttpResult? = null
                    val ra = if (response.code == 429) response.header("Retry-After") else null
                    try {
                        val src = response.body?.source()
                        r = when {
                            src == null -> HttpResult(response.code, null, monoMs() - t0, null, retryAfter = ra)
                            firstContentMs != null && response.code == 200 -> readStream(src, t0, first, started, isContent, isEnd)
                            // a real answer is a few KB; anything far larger is not one
                            src.request(MAX_RESPONSE + 1L) -> HttpResult(response.code, null, monoMs() - t0, "response over $MAX_RESPONSE bytes", retryAfter = ra)
                            else -> HttpResult(response.code, src.buffer.readUtf8(), monoMs() - t0, null, retryAfter = ra)
                        }
                    } catch (e: IOException) { r = HttpResult(response.code, null, monoMs() - t0, why(e, t0), firstOrNull(), ra) }
                    finally {
                        if (firstContentMs != null) {
                            // a stream: publish first, then drop the rest, as closing an unfinished chunked body may
                            // first try to drain it
                            result.set(r); done.countDown(); started.countDown()
                            call.cancel(); response.close(); PENDING.release()
                        } else {
                            response.close(); PENDING.release()
                            result.set(r); done.countDown(); started.countDown()
                        }
                    }
                }
            })
            // no output yet [firstContentMs] after the start (queueing, a slow connection, ...): give up; the end of
            // the call (also a failure) or cancel() ends this wait at once. Decided by when the text arrived, not by
            // when this thread happens to wake up.
            if (firstContentMs != null && firstContentMs < deadlineMs) {
                started.await(maxOf(0, t0 + firstContentMs - monoMs()), TimeUnit.MILLISECONDS)
                if (!cancelled) {
                    val finished = done.count == 0L // read before [first]: text recorded after this read is seen too
                    val f = first.get()
                    // finished without text (an HTTP error, a failure, an empty answer): reported as what it is
                    val late = if (f >= 0) f > firstContentMs else !finished
                    if (late) { call.cancel(); return HttpResult(0, null, monoMs() - t0, "no content in ${firstContentMs}ms", firstOrNull()) }
                }
            }
            // the deadline is absolute from the start of post(); cancel() wakes this at once
            done.await(maxOf(0, t0 + deadlineMs - monoMs()), TimeUnit.MILLISECONDS)
            return when {
                cancelled -> HttpResult(result.get()?.status ?: 0, null, monoMs() - t0, "cancelled", firstOrNull())
                else -> result.get() ?: run { call.cancel(); HttpResult(0, null, monoMs() - t0, "deadline ${deadlineMs}ms", firstOrNull()) }
            }
        } catch (e: InterruptedException) {
            call.cancel(); Thread.currentThread().interrupt()
            return HttpResult(0, null, monoMs() - t0, "cancelled", firstOrNull())
        } finally {
            inFlight.remove(call)
            if (!admitted) call.cancel()
        }
    }

    private fun why(e: IOException, t0: Long) = when {
        cancelled -> "cancelled"
        // callTimeout cancels the call itself, so it is told apart by elapsed time, not isCanceled()
        monoMs() - t0 >= deadlineMs - 50 -> "deadline ${deadlineMs}ms"
        e.message == "Canceled" -> "cancelled"
        else -> e.javaClass.name
    }

    /**
     * Reads a streamed body line by line up to [isEnd], never holding more than [MAX_STREAM] bytes (also for one
     * endless line), and records when the first line [isContent] accepts arrived.
     */
    private fun readStream(src: okio.BufferedSource, t0: Long, first: java.util.concurrent.atomic.AtomicLong,
                           started: CountDownLatch, isContent: (String) -> Boolean, isEnd: (String) -> Boolean): HttpResult {
        val out = StringBuilder(); var used = 0L
        while (true) {
            val nl = src.indexOf('\n'.code.toByte(), 0, MAX_STREAM - used + 1)
            if (nl < 0) {
                // no line end within the budget: the rest is over the cap, or the body ended without a newline
                if (src.request(MAX_STREAM - used + 1)) return HttpResult(200, null, monoMs() - t0, "response over $MAX_STREAM bytes", first.get().takeIf { it >= 0 })
                break // ended: a last unterminated line is not an event (and [DONE] never came after it)
            }
            val line = src.readUtf8(nl).removeSuffix("\r"); src.skip(1); used += nl + 1
            out.append(line).append('\n')
            if (first.get() < 0 && isContent(line)) { first.set(monoMs() - t0); started.countDown() }
            if (isEnd(line)) break
        }
        return HttpResult(200, out.toString(), monoMs() - t0, null, first.get().takeIf { it >= 0 })
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_RESPONSE = 64 * 1024
        /** a streamed answer repeats ~200 bytes of JSON per token: 160 tokens are ~40 KB */
        private const val MAX_STREAM = 256 * 1024
        /** Jev asks from two threads and MT from one, so a few requests per session; more means stuck work */
        const val MAX_PENDING = 8
        private val PENDING = java.util.concurrent.Semaphore(MAX_PENDING)
        private val BASE = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false) // never resend a key to another host
            .retryOnConnectionFailure(false)
            .build()
        /** tests */
        fun pendingNow() = MAX_PENDING - PENDING.availablePermits()
    }
}

/**
 * Request bodies, Jev responses and the session log, on org.json (part of Android; tests and the Mac harness use the
 * reference jar). Converts to and from plain Kotlin maps/lists so callers stay library-free; null is kept as JSON null.
 */
object Json {
    fun encode(v: Any?): String = when (v) {
        is Map<*, *> -> toJson(v).toString()
        is Iterable<*> -> toJson(v).toString()
        else -> JSONArray().put(toJson(v)).toString().removePrefix("[").removeSuffix("]")
    }

    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        is String, is Boolean, is Int, is Long -> v
        is Number -> v.toDouble().takeIf { it.isFinite() } ?: JSONObject.NULL
        else -> v.toString()
    }

    /** @throws IOException if [s] is not one JSON value */
    fun parse(s: String): Any? = try {
        val t = JSONTokener(s)
        val v = t.nextValue()
        if (t.more() && t.nextClean() != 0.toChar()) throw IOException("trailing data after JSON")
        fromJson(v)
    } catch (e: JSONException) { throw IOException("bad JSON: ${e.message}") }

    private fun fromJson(v: Any?): Any? = when (v) {
        JSONObject.NULL, null -> null
        is JSONObject -> LinkedHashMap<String, Any?>().also { m -> v.keys().forEach { k -> m[k] = fromJson(v.get(k)) } }
        is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
        else -> v
    }
}
