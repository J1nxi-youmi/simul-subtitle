package dev.krita.jpsub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JevTest {
    // A one-request-at-a-time HTTP/1.1 server (com.sun.net.httpserver is not on the Android unit-test classpath).
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val port = server.localPort
    private val hits = AtomicInteger()
    private val lastAuth = AtomicReference<String?>()
    private val lastBody = AtomicReference<String?>()
    @Volatile private var reply: (Int) -> Pair<Int, String> = { 200 to "{}" }
    @Volatile private var delayMs = 0L
    @Volatile private var trickleMs = 0L
    @Volatile private var redirectTo: String? = null

    init {
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    s.use { sock ->
                        val inp = sock.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return@use; head.append(b.toChar()) }
                        val headers = head.lines().drop(1).filter { ':' in it }
                            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                        val len = headers["content-length"]?.toInt() ?: 0
                        val body = ByteArray(len).also { var o = 0; while (o < len) o += inp.read(it, o, len - o) }
                        hits.incrementAndGet()
                        lastAuth.set(headers["authorization"]); lastBody.set(String(body))
                        Thread.sleep(delayMs)
                        val out = sock.getOutputStream()
                        val r = redirectTo
                        if (r != null) { out.write("HTTP/1.1 307 Temporary Redirect\r\nLocation: $r\r\nContent-Length: 0\r\n\r\n".toByteArray()); return@use }
                        val (code, text) = reply(hits.get())
                        val b = text.toByteArray()
                        out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        // trickle: one byte every trickleMs, each gap shorter than any read timeout
                        if (trickleMs > 0) for (x in b) { out.write(x.toInt()); out.flush(); Thread.sleep(trickleMs) }
                        else { out.write(b); out.flush() }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun stop() = server.close()

    private fun jev(deadline: Long = 2_000) =
        Jev(JevProvider.OFFICIAL, "sk-secret", deadline, url = "http://127.0.0.1:$port/v1/systemone")

    private fun online(deadline: Long = 2_000) = OnlineTranslator(OnlineModel.B9, deadline, url = "http://127.0.0.1:$port/v1/chat/completions")

    @Test
    fun onlineTranslationSendsTheOfficialRequestAndKeepsTheRawResponse() {
        // shape recorded from index-translate.bilibili.com on 2026-10-10
        reply = { 200 to """{"model":"Index-Translate-9B","choices":[{"index":0,"message":{"role":"assistant","content":"真的吗？"},"finish_reason":"stop"}],"usage":{"prompt_tokens":34,"completion_tokens":4}}""" }
        val r = online().translate("请将以下日语文本翻译为中文，直接输出翻译结果，不要进行任何解释。\n\n本当に？")!!
        assertEquals("真的吗？", r.text); assertTrue(r.usable); assertEquals(200, r.http); assertNull(r.error)
        assertEquals(34, r.promptTokens); assertEquals(4, r.outputTokens); assertNull(r.prefillMs)
        val req = Json.parse(lastBody.get()!!) as Map<*, *>
        assertEquals("Index-Translate-9B", req["model"])
        assertEquals(mapOf("enable_thinking" to false), req["chat_template_kwargs"])
        assertEquals("本当に？", ((req["messages"] as List<*>).single() as Map<*, *>)["content"].toString().substringAfter("\n\n"))
        assertEquals(r.request, lastBody.get()); assertTrue(r.response!!.contains("finish_reason"))
    }

    @Test
    fun onlineFailuresAreUnusableAndLogged() {
        reply = { 503 to "busy" }
        val a = online().translate("x")!!
        assertFalse(a.usable); assertEquals(503, a.http); assertEquals("HTTP 503", a.error); assertEquals("busy", a.response)
        reply = { 200 to """{"choices":[{"message":{"content":"很长"},"finish_reason":"length"}]}""" }
        val b = online().translate("x")!!
        assertEquals('l', b.stop); assertTrue(b.usable); assertNull(b.promptTokens)
        delayMs = 3_000
        val c = online(deadline = 300).translate("x")!!
        assertFalse(c.usable); assertTrue(c.error!!, c.error!!.startsWith("deadline"))
    }

    @Test
    fun aStoppedOnlineTranslatorSendsNothingMore() {
        val t = online(); t.cancel()
        val r = t.translate("x")!!
        assertFalse(r.usable); assertEquals("cancelled", r.error)
        Thread.sleep(200); assertEquals(0, hits.get())
    }

    @Test
    fun aCancelledOnlineRequestIsStillLoggedWithWhatWasSent() {
        delayMs = 3_000
        val t = online(deadline = 5_000)
        val r = AtomicReference<MtResult?>()
        val th = Thread { r.set(t.translate("请翻译\n\n本当に？")) }.apply { start() }
        val until = monoMs() + 2_000
        while (hits.get() < 1 && monoMs() < until) Thread.sleep(10)
        t.cancel(); th.join(2_000)
        val res = r.get()!!
        assertFalse(res.usable); assertEquals("cancelled", res.error)
        assertTrue(res.request!!.contains("本当に"))
    }

    /** lookups of "stuck.invalid" hang until [release]; afterwards they resolve to the local test server */
    private inner class HangingDns : okhttp3.Dns {
        val release = java.util.concurrent.CountDownLatch(1)
        @Volatile var entered = java.util.concurrent.CountDownLatch(1)
        override fun lookup(hostname: String): List<InetAddress> {
            entered.countDown(); release.await()
            return listOf(InetAddress.getByName("127.0.0.1"))
        }
    }
    private fun stuckUrl(path: String) = "http://stuck.invalid:$port/$path"

    @Test(timeout = 20_000)
    fun aHangingDnsLookupStillEndsAtTheDeadlineAndOnStop() {
        val dns = HangingDns()
        try {
            val t0 = monoMs()
            val a = Calls(300, dns, java.net.Proxy.NO_PROXY).post(stuckUrl("x"), "{}", emptyMap())
            assertTrue("deadline: took ${monoMs() - t0} ms", monoMs() - t0 < 400)
            assertEquals("the lookup is what hangs", 0L, dns.entered.count)
            assertTrue("${a.error}", a.error?.startsWith("deadline") == true)

            dns.entered = java.util.concurrent.CountDownLatch(1)
            val c = Calls(5_000, dns, java.net.Proxy.NO_PROXY)
            val r = AtomicReference<HttpResult?>()
            val th = Thread { r.set(c.post(stuckUrl("y"), "{}", emptyMap())) }.apply { start() }
            assertTrue("second request reached the lookup", dns.entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val t1 = monoMs(); c.cancel(); th.join(1_000)
            assertFalse(th.isAlive); assertTrue("stop: took ${monoMs() - t1} ms", monoMs() - t1 < 500)
            assertEquals("cancelled", r.get()?.error)
        } finally { dns.release.countDown() }
        // both calls were cancelled while resolving: once the lookup returns they must not be sent
        Thread.sleep(500); assertEquals(0, hits.get())
    }

    @Test(timeout = 30_000)
    fun stuckWorkIsBoundedAcrossSessionsAndExtraRequestsFailAtOnce() {
        val dns = HangingDns()
        val results = mutableListOf<HttpResult>()
        try {
            repeat(Calls.MAX_PENDING + 4) { i ->
                // a fresh "session" each time, as after stop and start
                results += Calls(100, dns, java.net.Proxy.NO_PROXY).post(stuckUrl("$i"), "{}", emptyMap())
                assertTrue("pending ${Calls.pendingNow()}", Calls.pendingNow() <= Calls.MAX_PENDING)
            }
            assertEquals(Calls.MAX_PENDING, Calls.pendingNow())
            val t0 = monoMs()
            val busy = Calls(5_000, dns, java.net.Proxy.NO_PROXY).post(stuckUrl("z"), "{}", emptyMap())
            assertEquals("busy", busy.error); assertTrue(monoMs() - t0 < 200)
            assertEquals(4, results.count { it.error == "busy" })
        } finally { dns.release.countDown() }
        // slots come back when OkHttp has really finished the calls, and none of them is sent
        val until = monoMs() + 5_000
        while (Calls.pendingNow() > 0 && monoMs() < until) Thread.sleep(20)
        assertEquals(0, Calls.pendingNow())
        Thread.sleep(300); assertEquals(0, hits.get())
    }

    @Test
    fun anUnknownFinishReasonIsNotAUsableTranslation() {
        reply = { 200 to """{"choices":[{"message":{"content":"半分"},"finish_reason":"content_filter"}]}""" }
        val a = online().translate("x")!!
        assertFalse(a.usable); assertEquals("finish_reason", a.error); assertTrue(a.response!!.contains("content_filter"))
        reply = { 200 to """{"choices":[{"message":{"content":"半分"}}]}""" }
        assertFalse(online().translate("x")!!.usable)
    }

    @Test
    fun redactHandlesBodiesThatAreNotValidJsonAndKeysInNumbers() {
        // review 06a6be2: trailing text after JSON, and a numeric key echoed as a JSON number
        val a = Jev.redact("""{"error":"invalid key \u0073k-secret"} upstream error""", "sk-secret")
        assertFalse(a, a.contains("sk-secret") || a.contains("\\u0073k-secret")); assertTrue(a.contains("***"))
        val b = Jev.redact("""{"error":"invalid key","key":1234567890}""", "1234567890")
        assertEquals("""{"error":"invalid key","key":***}""", b)
        val c = Jev.redact("""{"e":"\u0053\u004B-SECRET \u0073\u006b\u002dsecret"}""", "sk-secret")
        assertFalse(c, c.contains("\\u0073\\u006b\\u002dsecret")); assertTrue(c, c.contains("SECRET")) // other case untouched
    }

    @Test
    fun redactKeepsCleanResponsesByteForByteAndCatchesEveryEscapedKey() {
        val raw = """{ "answers" : {"x":{"noul":0.25}},  "unicode":"\u672c" }"""
        assertEquals(raw, Jev.redact(raw, "sk-secret"))
        assertEquals("not json", Jev.redact("not json", "sk-secret"))
        val k = "sk-a\"b\\c"
        for (body in listOf(
            "\"invalid key \\u0073k-a\\\"b\\\\c\"", // a top-level JSON string
            """{"e":["x \u0073k-a\"b\\c"]}""",
            """{"\u0073k-a\"b\\c":1}""",
        )) {
            val out = Jev.redact(body, k)
            assertFalse(out, out.contains(k) || (runCatching { Json.parse(out) }.getOrNull()?.toString() ?: "").contains(k))
            assertTrue(out, out.contains("***"))
        }
    }

    @Test
    fun aSlowlyTricklingResponseIsStillCutAtTheDeadline() {
        // every byte arrives well within the read timeout, so only a whole-call deadline can stop this
        reply = { 200 to "{\"answers\":{\"x\":{\"noul\":0.5}}," + " ".repeat(200) + "\"pad\":1}" }
        trickleMs = 50
        val t0 = monoMs()
        val c = jev(deadline = 600).unfinished("はい")
        assertTrue("took ${monoMs() - t0} ms", monoMs() - t0 < 1_500)
        assertNull(c.answer); assertTrue(c.error!!, c.error!!.startsWith("deadline"))
    }

    @Test
    fun cancelAbortsBothKindsOfRequestInFlightAtOnce() {
        delayMs = 3_000
        val j = jev(deadline = 5_000)
        val a = AtomicReference<JevCall?>(); val b = AtomicReference<JevCall?>()
        val ta = Thread { a.set(j.unfinished("はい")) }.apply { start() }
        val tb = Thread { b.set(j.mistranslation("はい", "是")) }.apply { start() }
        val until = monoMs() + 2_000
        while (hits.get() < 2 && monoMs() < until) Thread.sleep(10)
        assertEquals(2, hits.get())
        val t0 = monoMs(); j.cancel(); ta.join(2_000); tb.join(2_000)
        assertFalse(ta.isAlive || tb.isAlive)
        assertTrue("took ${monoMs() - t0} ms", monoMs() - t0 < 1_500)
        assertEquals("cancelled", a.get()!!.error); assertEquals("cancelled", b.get()!!.error)
    }

    @Test
    fun parsesTheOfficialResponseShape() {
        // recorded from api.typesafe.ai on 2026-10-10
        reply = { 200 to """{"model":"jev-1.13.0","answers":{"x":{"type":"noul","noul":0.66}},"usage":{"input_tokens":379,"output_tokens":49}}""" }
        val c = jev().unfinished("簡単な中国語読めますかって言われて簡単")
        assertEquals(0.66, c.noul!!, 1e-9); assertNull(c.error); assertEquals(200, c.status)
        assertEquals("Bearer sk-secret", lastAuth.get())
        val req = Json.parse(lastBody.get()!!) as Map<*, *>
        assertEquals("jev-latest", req["model"])
        assertEquals(mapOf("current" to "簡単な中国語読めますかって言われて簡単"), req["state"])
        assertFalse("key must not be in the logged request", c.request.contains("sk-secret"))
        assertEquals(c.request, lastBody.get())

        reply = { 200 to """{"model":"jev-1.13.0","answers":{"x":{"type":"choice","choice":"negation","confidence":0.96,"probabilities":{"negation":0.98,"ok":0.02}}}}""" }
        val m = jev().mistranslation("ダウンロードするなんて", "不要下载")
        assertEquals("negation", m.choice); assertEquals(0.96, m.confidence!!, 1e-9)
    }

    @Test
    fun httpErrorsAndBadBodiesGiveNoAnswerButKeepTheRawResponse() {
        reply = { 403 to """{"error":{"message":"Key limit exceeded (total limit).","code":403}}""" }
        val c = jev().unfinished("はい")
        assertNull(c.answer); assertEquals(403, c.status); assertTrue(c.response!!.contains("Key limit"))
        reply = { 200 to "not json" }
        val d = jev().unfinished("はい")
        assertNull(d.answer); assertEquals("not json", d.response)
        reply = { 200 to """{"answers":{}}""" }
        assertNull(jev().unfinished("はい").noul)
    }

    @Test
    fun theWholeCallIsBoundedByTheDeadline() {
        delayMs = 3_000
        val t0 = monoMs()
        val c = jev(deadline = 300).unfinished("はい")
        assertTrue("took ${monoMs() - t0} ms", monoMs() - t0 < 1_500)
        assertNull(c.answer); assertTrue(c.error!!, c.error!!.startsWith("deadline"))
    }

    @Test
    fun redirectsAreNotFollowed() {
        redirectTo = "http://127.0.0.1:$port/elsewhere"
        val c = jev().unfinished("はい")
        assertEquals(307, c.status); assertNull(c.answer); assertEquals(1, hits.get())
    }

    @Test
    fun cancelAbortsARequestInProgress() {
        delayMs = 3_000
        val j = jev(deadline = 5_000)
        val r = AtomicReference<JevCall?>()
        val t = Thread { r.set(j.unfinished("はい")) }.apply { start() }
        Thread.sleep(200)
        val t0 = monoMs(); j.cancel(); t.join(2_000)
        assertFalse(t.isAlive); assertTrue(monoMs() - t0 < 1_500)
        assertEquals("cancelled", r.get()!!.error)
        assertEquals("cancelled", j.unfinished("はい").error) // no new request after cancel
        assertEquals(1, hits.get())
    }

    @Test
    fun aCancelAfterTheCallIsRegisteredButBeforeItRunsSendsNothing() {
        lateinit var j: Jev
        // cancel lands after the request is registered (so cancel() sees it) and before it is executed
        j = Jev(JevProvider.OFFICIAL, "sk-secret", 2_000, url = "http://127.0.0.1:$port/v1/systemone", beforeSend = { j.cancel() })
        assertEquals("cancelled", j.unfinished("はい").error)
        Thread.sleep(200)
        assertEquals("no request may reach the server", 0, hits.get())
    }

    @Test
    fun aKeyWithControlCharactersNeverReachesTheLog() {
        val c = Jev(JevProvider.OFFICIAL, "sk-secret\naccident", 2_000, url = "http://127.0.0.1:$port/v1/systemone").unfinished("はい")
        assertNull(c.answer); assertEquals("bad key", c.error)
        assertEquals(0, hits.get())
        val logged = Json.encode(mapOf("request" to c.request, "response" to c.response, "error" to c.error))
        assertFalse(logged, logged.contains("sk-secret") || logged.contains("accident"))
    }

    @Test
    fun aServerEchoingTheKeyIsRedactedAndErrorsCarryNoMessage() {
        reply = { 401 to """{"error":"invalid key sk-secret"}""" }
        val c = jev().unfinished("はい")
        assertEquals(401, c.status); assertFalse(c.response!!, c.response!!.contains("sk-secret"))
        // a JSON-escaped echo is the same key
        reply = { 401 to """{"error":"invalid key \u0073k-secret","sk-secret":1}""" }
        val e = jev().unfinished("はい")
        assertFalse(e.response!!, e.response!!.contains("sk-secret") || Json.parse(e.response!!).toString().contains("sk-secret"))
        server.close() // connection refused: only the exception type is logged
        val d = jev().unfinished("はい")
        assertTrue(d.error!!, d.error!!.matches(Regex("[A-Za-z.]+")))
    }

    @Test
    fun jsonRoundTrip() {
        val v = mapOf("s" to "引号\"\\\n\u0001", "n" to 1, "d" to 0.5, "b" to true, "z" to null, "l" to listOf(1, "a"))
        val back = Json.parse(Json.encode(v)) as Map<*, *>
        assertEquals(v["s"], back["s"]); assertEquals(1, (back["n"] as Number).toInt()); assertEquals(0.5, (back["d"] as Number).toDouble(), 0.0)
        assertEquals(true, back["b"]); assertTrue(back.containsKey("z")); assertNull(back["z"])
        assertEquals(listOf(1, "a"), (back["l"] as List<*>).map { if (it is Number) it.toInt() else it })
        assertEquals("\u00e9x", Json.parse("\"\\u00e9x\""))
        // invalid input is an error, not a guess
        for (bad in listOf("{\"a\":1} x", "\"\\q\"", "{\"a\":}", "")) {
            assertTrue(bad, runCatching { Json.parse(bad) }.exceptionOrNull() is java.io.IOException)
        }
    }
}
