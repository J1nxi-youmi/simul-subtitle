package dev.krita.jpsub

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import kotlin.concurrent.schedule
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

// TypeSafe Jev (System One decision model) client and the two questions the pipeline asks it.
// Pure Kotlin / java.net so the Mac harness (tools/mac-e2e) runs the same code.

enum class JevProvider(val label: String, val url: String, val model: String) {
    OFFICIAL("TypeSafe 官方", "https://api.typesafe.ai/v1/systemone", "jev-latest"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/systemone", "~typesafe/jev-latest"),
}

/** One request with everything the session log needs. [answer] is the parsed `answers.x`, null on any failure. */
class JevCall(val request: String, val status: Int, val response: String?, val ms: Long, val error: String?, val answer: Map<*, *>?) {
    val noul: Double? get() = (answer?.get("noul") as? Number)?.toDouble()
    val choice: String? get() = answer?.get("choice") as? String
    val confidence: Double? get() = (answer?.get("confidence") as? Number)?.toDouble()
}

/** What the pipeline asks; an interface so tests can answer without a network. */
interface Judge {
    /** Is the speech-recognition text that ended at a pause an unfinished sentence? */
    fun unfinished(text: String): JevCall
    /** What is wrong with this translation, if anything? */
    fun mistranslation(ja: String, zh: String): JevCall
    /** Abort the request in progress (stop). */
    fun cancel()
}

class Jev(
    private val provider: JevProvider,
    private val apiKey: String,
    private val deadlineMs: Long = 2_000,
    /** tests point this at a local server */
    private val url: String = provider.url,
) : Judge {
    /** Every request in flight (the pipeline asks from two threads); cancel closes all of them. */
    private val inFlight: MutableSet<HttpURLConnection> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var cancelled = false
    private val timer = Timer("jev-deadline", true)

    override fun unfinished(text: String) = ask(mapOf("current" to text), UNFINISHED)
    override fun mistranslation(ja: String, zh: String) = ask(mapOf("ja" to ja, "zh" to zh), MISTRANSLATION)

    override fun cancel() { cancelled = true; inFlight.forEach { it.disconnect() }; timer.cancel() }

    private fun ask(state: Map<String, String>, question: Map<String, Any>): JevCall {
        val body = Json.encode(mapOf("model" to provider.model, "state" to state, "questions" to mapOf("x" to question)))
        val t0 = monoMs()
        if (cancelled) return JevCall(body, 0, null, 0, "cancelled", null)
        var status = 0; var resp: String? = null
        // connect/read timeouts bound each phase; the timer bounds the whole call by closing the socket
        val c = URL(url).openConnection() as HttpURLConnection
        val deadline = runCatching { timer.schedule(deadlineMs) { c.disconnect() } }.getOrNull()
        inFlight.add(c)
        if (cancelled) c.disconnect() // cancel() ran between the check above and the registration
        return try {
            c.instanceFollowRedirects = false // never resend the key to another host
            c.connectTimeout = deadlineMs.toInt(); c.readTimeout = deadlineMs.toInt()
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.outputStream.use { it.write(body.toByteArray()) }
            status = c.responseCode
            // read in chunks against the deadline: disconnect() does not always unblock a body read in progress
            resp = (if (status in 200..299) c.inputStream else c.errorStream)?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
                while (true) {
                    if (cancelled) throw IOException("cancelled")
                    if (monoMs() - t0 >= deadlineMs) throw IOException("deadline")
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_RESPONSE) throw IOException("response over $MAX_RESPONSE bytes")
                }
                out.toString(Charsets.UTF_8.name())
            }
            val answer = if (status == 200) ((Json.parse(resp!!) as? Map<*, *>)?.get("answers") as? Map<*, *>)?.get("x") as? Map<*, *> else null
            JevCall(body, status, resp, monoMs() - t0, if (answer == null) "HTTP $status" else null, answer)
        } catch (e: Exception) {
            val why = when { cancelled -> "cancelled"; monoMs() - t0 >= deadlineMs -> "deadline ${deadlineMs}ms"; else -> e.toString() }
            JevCall(body, status, resp, monoMs() - t0, why, null)
        } finally {
            deadline?.cancel(); inFlight.remove(c); c.disconnect()
        }
    }

    companion object {
        /** A real answer is < 1 KB; anything far larger is not a Jev response. */
        private const val MAX_RESPONSE = 64 * 1024
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
