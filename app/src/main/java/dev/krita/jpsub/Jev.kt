package dev.krita.jpsub

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import kotlin.concurrent.schedule

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
    @Volatile private var current: HttpURLConnection? = null
    @Volatile private var cancelled = false
    private val timer = Timer("jev-deadline", true)

    override fun unfinished(text: String) = ask(mapOf("current" to text), UNFINISHED)
    override fun mistranslation(ja: String, zh: String) = ask(mapOf("ja" to ja, "zh" to zh), MISTRANSLATION)

    override fun cancel() { cancelled = true; current?.disconnect(); timer.cancel() }

    private fun ask(state: Map<String, String>, question: Map<String, Any>): JevCall {
        val body = Json.encode(mapOf("model" to provider.model, "state" to state, "questions" to mapOf("x" to question)))
        val t0 = monoMs()
        if (cancelled) return JevCall(body, 0, null, 0, "cancelled", null)
        var status = 0; var resp: String? = null
        // connect/read timeouts bound each phase; the timer bounds the whole call by closing the socket
        val c = URL(url).openConnection() as HttpURLConnection
        val deadline = runCatching { timer.schedule(deadlineMs) { c.disconnect() } }.getOrNull()
        current = c
        return try {
            c.instanceFollowRedirects = false // never resend the key to another host
            c.connectTimeout = deadlineMs.toInt(); c.readTimeout = deadlineMs.toInt()
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.outputStream.use { it.write(body.toByteArray()) }
            status = c.responseCode
            resp = (if (status in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }
            val answer = if (status == 200) ((Json.parse(resp!!) as? Map<*, *>)?.get("answers") as? Map<*, *>)?.get("x") as? Map<*, *> else null
            JevCall(body, status, resp, monoMs() - t0, if (answer == null) "HTTP $status" else null, answer)
        } catch (e: Exception) {
            val why = when { cancelled -> "cancelled"; monoMs() - t0 >= deadlineMs -> "deadline ${deadlineMs}ms"; else -> e.toString() }
            JevCall(body, status, resp, monoMs() - t0, why, null)
        } finally {
            deadline?.cancel(); current = null; c.disconnect()
        }
    }

    companion object {
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

/** Minimal JSON for request bodies, Jev responses and the session log (org.json is not on the JVM test classpath). */
object Json {
    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> {
                sb.append('"')
                for (ch in v) when {
                    ch == '"' -> sb.append("\\\""); ch == '\\' -> sb.append("\\\\")
                    ch == '\n' -> sb.append("\\n"); ch == '\r' -> sb.append("\\r"); ch == '\t' -> sb.append("\\t")
                    ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                    else -> sb.append(ch)
                }
                sb.append('"')
            }
            is Boolean, is Int, is Long -> sb.append(v)
            is Number -> v.toDouble().let { if (it.isFinite()) sb.append(it) else sb.append("null") }
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, e -> if (i > 0) sb.append(','); write(sb, e.key.toString()); sb.append(':'); write(sb, e.value) }
                sb.append('}')
            }
            is Iterable<*> -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }; sb.append(']') }
            else -> write(sb, v.toString())
        }
    }

    fun parse(s: String): Any? = P(s).run { val v = value(); ws(); if (i != s.length) err(); v }

    private class P(val s: String) {
        var i = 0
        fun err(): Nothing = throw IOException("bad JSON at $i")
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) err()
            return when (s[i]) {
                '{' -> {
                    i++; val m = LinkedHashMap<String, Any?>(); ws()
                    if (s.getOrNull(i) == '}') { i++; return m }
                    while (true) {
                        ws(); val k = value() as? String ?: err(); ws()
                        if (s.getOrNull(i++) != ':') err()
                        m[k] = value(); ws()
                        when (s.getOrNull(i++)) { ',' -> continue; '}' -> return m; else -> err() }
                    }
                    @Suppress("UNREACHABLE_CODE") m
                }
                '[' -> {
                    i++; val l = ArrayList<Any?>(); ws()
                    if (s.getOrNull(i) == ']') { i++; return l }
                    while (true) {
                        l.add(value()); ws()
                        when (s.getOrNull(i++)) { ',' -> continue; ']' -> return l; else -> err() }
                    }
                    @Suppress("UNREACHABLE_CODE") l
                }
                '"' -> {
                    i++; val sb = StringBuilder()
                    while (true) {
                        val ch = s.getOrNull(i++) ?: err()
                        when (ch) {
                            '"' -> return sb.toString()
                            '\\' -> when (val e = s.getOrNull(i++) ?: err()) {
                                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                                'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                                'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                                else -> sb.append(e)
                            }
                            else -> sb.append(ch)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") sb
                }
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> {
                    val st = i
                    while (i < s.length && s[i] in "+-0123456789.eE") i++
                    s.substring(st, i).toDoubleOrNull() ?: err()
                }
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) err(); i += w.length; return v }
    }
}
