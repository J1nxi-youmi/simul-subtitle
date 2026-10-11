package io.github.korenkrita.simulsub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    /** streamed reply: (text, pause before it) pieces written one by one after the headers, no Content-Length */
    @Volatile private var pieces: List<Pair<String, Long>>? = null

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
                        pieces?.let { ps ->
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                            for ((p, pause) in ps) { Thread.sleep(pause); out.write(p.toByteArray()); out.flush() }
                            return@use
                        }
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
    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    @Test
    fun onlineTranslationSendsTheOfficialRequestAndKeepsTheRawResponse() {
        // stream shape recorded from index-translate.bilibili.com on 2026-10-11
        reply = { 200 to sse("""{"choices":[{"finish_reason":null,"index":0,"delta":{"role":"assistant","content":null}}]}""",
            """{"choices":[{"finish_reason":null,"index":0,"delta":{"content":"真的"}}]}""",
            """{"choices":[{"finish_reason":null,"index":0,"delta":{"content":"吗？"}}]}""",
            """{"choices":[{"finish_reason":"stop","index":0,"delta":{}}]}""",
            """{"choices":[],"usage":{"prompt_tokens":34,"completion_tokens":4}}""") }
        val r = online().translate("请将以下日语文本翻译为中文，直接输出翻译结果，不要进行任何解释。\n\n本当に？")!!
        assertEquals("真的吗？", r.text); assertTrue(r.usable); assertEquals(200, r.http); assertNull(r.error)
        assertEquals(34, r.promptTokens); assertEquals(4, r.outputTokens); assertNotNull(r.firstContentMs); assertNull(r.prefillMs)
        val req = Json.parse(lastBody.get()!!) as Map<*, *>
        assertEquals("Index-Translate-9B", req["model"]); assertEquals(true, req["stream"])
        assertEquals(mapOf("enable_thinking" to false), req["chat_template_kwargs"])
        assertEquals("本当に？", ((req["messages"] as List<*>).single() as Map<*, *>)["content"].toString().substringAfter("\n\n"))
        assertEquals(r.request, lastBody.get()); assertTrue(r.response!!.contains("finish_reason"))
    }

    @Test
    fun onlineFailuresAreUnusableAndLogged() {
        reply = { 503 to "busy" }
        val a = online().translate("x")!!
        assertFalse(a.usable); assertEquals(503, a.http); assertEquals("HTTP 503", a.error); assertEquals("busy", a.response)
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"很长"},"finish_reason":"length"}]}""") }
        val b = online().translate("x")!!
        assertEquals('l', b.stop); assertTrue(b.usable); assertNull(b.promptTokens)
        delayMs = 3_000
        val c = online(deadline = 300).translate("x")!!
        assertFalse(c.usable); assertTrue(c.error!!, c.error!!.startsWith("deadline"))
    }

    private fun streamed(firstContentMs: Long, deadline: Long = 8_000) =
        OnlineTranslator(OnlineModel.B9, deadline, url = "http://127.0.0.1:$port/v1/chat/completions", firstContentMs = firstContentMs)

    @Test
    fun noTranslatedTextWithinTheFirstContentWaitFailsTheCaption() {
        // a queueing server: nothing at all
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"晚"},"finish_reason":"stop"}]}""") }
        delayMs = 3_000
        val t0 = monoMs(); val r = streamed(300).translate("x")!!
        assertFalse(r.usable); assertEquals("no content in 300ms", r.error); assertTrue(monoMs() - t0 < 2_000)
        // headers, a role-only chunk and a keep-alive at once, the text only later: still no content in time
        delayMs = 0
        pieces = listOf("data: " + """{"choices":[{"delta":{"role":"assistant","content":null}}]}""" + "\n\n" to 0L,
            ": keep-alive\n\n" to 50L,
            "data: " + """{"choices":[{"delta":{"content":"晚上好"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" to 1_000L)
        val late = streamed(300).translate("x")!!
        assertFalse(late.usable); assertEquals("no content in 300ms", late.error)
    }

    @Test
    fun onceTextFlowsTheRestIsWaitedForUpToTheDeadline() {
        val chunk = { s: String -> "data: " + """{"choices":[{"delta":{"content":"$s"}}]}""" + "\n\n" }
        pieces = listOf(chunk("晚") to 50L, chunk("上") to 600L, chunk("好") to 600L,
            "data: " + """{"choices":[{"delta":{},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" to 0L)
        val r = streamed(300).translate("x")!!
        assertTrue(r.error, r.usable); assertEquals("晚上好", r.text); assertTrue(r.firstContentMs!! < 300)
        // but the whole call still ends at the deadline, counted from sending
        pieces = listOf(chunk("晚") to 50L, chunk("上") to 2_000L)
        val slow = streamed(300, deadline = 1_000).translate("x")!!
        assertFalse(slow.usable); assertTrue(slow.error!!, slow.error!!.startsWith("deadline")); assertNotNull(slow.firstContentMs)
    }

    @Test
    fun stoppingDuringTheFirstContentWaitReturnsAtOnce() {
        delayMs = 3_000
        val u = streamed(2_000)
        val got = AtomicReference<MtResult?>()
        val th = Thread { got.set(u.translate("x")) }.apply { start() }
        Thread.sleep(300); val c0 = monoMs(); u.cancel(); th.join(1_000)
        assertEquals("cancelled", got.get()!!.error); assertTrue(monoMs() - c0 < 100)
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
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"半分"},"finish_reason":"content_filter"}]}""") }
        val a = online().translate("x")!!
        assertFalse(a.usable); assertEquals("finish_reason", a.error); assertTrue(a.response!!.contains("content_filter"))
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"半分"}}]}""") }
        assertFalse(online().translate("x")!!.usable)
        reply = { 200 to """{"choices":[{"message":{"content":"不是流"},"finish_reason":"stop"}]}""" }
        assertEquals("bad response", online().translate("x")!!.error)
        // a broken chunk in the middle could have held part of the text
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"我"}}]}""", """{"choices":[{"delta":{"content":"不"}}]""",
            """{"choices":[{"delta":{"content":"同意"},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
        // an error event between good chunks, a stream cut off before [DONE], a wrongly typed content
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"我"}}]}""", """{"error":{"message":"overloaded"}}""",
            """{"choices":[{"delta":{"content":"同意"},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
        reply = { 200 to "data: " + """{"choices":[{"delta":{"content":"我"},"finish_reason":"stop"}]}""" + "\n\n" }
        assertEquals("bad response", online().translate("x")!!.error)
        reply = { 200 to sse("""{"choices":[{"delta":{"content":5},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"我"}}]}""", """{"choices":[null]}""",
            """{"choices":[{"delta":{"content":"同意"},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
        reply = { 200 to "event: error\n" + sse("""{"choices":[]}""", """{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
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
    fun zenIsCalledWithoutAnyKeyAndParsesTheSameShape() {
        // recorded from opencode.ai/zen/v1/systemone on 2026-10-10 (no Authorization header; a wrong key gets 401)
        reply = { 200 to """{"model":"jev-1.13-free","answers":{"x":{"type":"noul","noul":0.96}},"usage":{"input_tokens":320,"output_tokens":20}}""" }
        val c = Jev(JevProvider.ZEN, "", url = "http://127.0.0.1:$port/zen/v1/systemone").unfinished("スパチャありがとうございま")
        assertEquals(0.96, c.noul!!, 1e-9); assertNull(c.error)
        assertNull("no Authorization header for Zen", lastAuth.get())
        assertEquals("jev-1.13-free", (Json.parse(lastBody.get()!!) as Map<*, *>)["model"])
        assertEquals(JevProvider.ZEN, JevProvider.entries.first())
    }

    @Test
    fun questionsFollowTheSessionLanguagesAndJapaneseToChineseKeepsTheTunedWording() {
        assertSame(Jev.UNFINISHED, Jev.unfinishedQuestion(Lang.JA))
        assertSame(Jev.MISTRANSLATION, Jev.mistranslationQuestion(Lang.JA, Lang.ZH))
        val en = Jev.unfinishedQuestion(Lang.EN)["instructions"] as String
        assertTrue(en, en.contains("English livestream") && !en.contains("けど"))
        assertTrue((Jev.unfinishedQuestion(null)["instructions"] as String).contains("Japanese or English livestream"))
        val m = Jev.mistranslationQuestion(Lang.EN, Lang.JA)["instructions"] as String
        assertTrue(m, m.contains("English VTuber") && m.contains("Japanese machine translation"))
        // the request sends the question of the configured pair
        reply = { 200 to """{"answers":{"x":{"type":"choice","choice":"ok","confidence":0.9}}}""" }
        Jev(JevProvider.ZEN, "", url = "http://127.0.0.1:$port/zen/v1/systemone", source = Lang.EN, target = Lang.KO).mistranslation("hi", "안녕")
        assertTrue(lastBody.get()!!, lastBody.get()!!.contains("Korean machine translation"))
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

    // ---- BYOK

    private fun byok(variant: ByokTranslator.ThinkingOff? = null, deadline: Long = 2_000) =
        ByokTranslator("http://127.0.0.1:$port/v1/chat/completions", "m-1", "sk-test", "SYS", variant, deadlineMs = deadline)
    private val plain = sse("""{"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}""")
    private val thought = sse("""{"choices":[{"delta":{"reasoning_content":"嗯…"}}]}""",
        """{"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}""")

    @Test
    fun byokSendsAFixedSystemMessageTheKeyAndTheFoundSwitch() {
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":90,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":64}}}""") }
        val r = byok(ByokTranslator.variant("thinking")).translate(byokUser("こんにちは"))!!
        assertTrue(r.error, r.usable); assertEquals("你好", r.text); assertEquals(64, r.cachedTokens); assertFalse(r.thinking)
        assertEquals("Bearer sk-test", lastAuth.get())
        val req = Json.parse(lastBody.get()!!) as Map<*, *>
        val msgs = req["messages"] as List<*>
        assertEquals("SYS", (msgs[0] as Map<*, *>)["content"]); assertEquals("system", (msgs[0] as Map<*, *>)["role"])
        assertEquals(mapOf("type" to "disabled"), req["thinking"]); assertEquals(true, req["stream"])
        // the key never reaches the log, even when the service echoes it
        reply = { 401 to """{"error":"bad key sk-test"}""" }
        val bad = byok().translate("x")!!
        assertFalse(bad.usable); assertFalse(bad.response!!, bad.response!!.contains("sk-test"))
    }

    @Test
    fun theProbeTakesTheFirstSwitchThatAnswersWithoutThinking() {
        // rejects the DeepSeek field, thinks with the template switch, is quiet with enable_thinking
        reply = { _ ->
            val b = lastBody.get()!!
            when {
                b.contains("\"thinking\":{") -> 400 to """{"error":"unknown field thinking"}"""
                b.contains("chat_template_kwargs") -> 200 to thought
                else -> 200 to plain
            }
        }
        val steps = mutableListOf<ByokTranslator.Step>()
        val v = byok().probe(steps)
        assertEquals("enable", v?.name); assertEquals(listOf("thinking", "template", "enable"), steps.map { it.variant.name })
        assertTrue(steps[1].result.thinking)
    }

    @Test
    fun theProbeStopsAtAWrongKeyAndReportsWhenNothingTurnsThinkingOff() {
        reply = { 401 to "{}" }
        val steps = mutableListOf<ByokTranslator.Step>()
        assertNull(byok().probe(steps)); assertEquals(1, steps.size)
        reply = { 200 to thought }
        steps.clear()
        assertNull(byok().probe(steps)); assertEquals(ByokTranslator.THINKING_OFF.size, steps.size)
        assertTrue(steps.all { it.result.usable && it.result.thinking })
        assertTrue(byok().exhausted(steps))
        // a rate limit half way: not "none works", try again next time
        reply = { _ -> if (lastBody.get()!!.contains("chat_template_kwargs")) 429 to "{}" else 200 to thought }
        steps.clear()
        assertNull(byok().probe(steps)); assertEquals(2, steps.size); assertFalse(byok().exhausted(steps))
        // a token-capped answer is not a complete one: the next switch is tried
        reply = { _ -> if (lastBody.get()!!.contains("\"thinking\":{")) 200 to sse("""{"choices":[{"delta":{"content":"你"},"finish_reason":"length"}]}""") else 200 to plain }
        steps.clear()
        assertEquals("template", byok().probe(steps)?.name)
    }

    @Test
    fun byokNeverShowsReasoningOrTheKeyAndThinkingIsNotTheFirstText() {
        // the key echoed in the answer itself
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"key sk-test"},"finish_reason":"stop"}]}""") }
        val r = byok().translate("x")!!
        assertFalse(r.raw.contains("sk-test")); assertFalse(r.response!!.contains("sk-test"))
        // ran out of tokens while thinking: nothing to show
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"<think>let me"},"finish_reason":"length"}]}""") }
        val t = byok().translate("x")!!
        assertFalse(t.usable); assertTrue(t.thinking)
        // thinking in the content at once, the translation only after 1 s: no translated text within 1 s
        pieces = listOf("data: " + """{"choices":[{"delta":{"content":"<think>嗯"}}]}""" + "\n\n" to 0L,
            "data: " + """{"choices":[{"delta":{"content":"</think>你好"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" to 1_500L)
        val late = ByokTranslator("http://127.0.0.1:$port/v1/chat/completions", "m-1", "sk-test", "SYS", null,
            deadlineMs = 4_000, firstContentMs = 1_000).translate("x")!!
        assertEquals("no content in 1000ms", late.error)
    }

    @Test
    fun byokUrlsGetV1OnceAndOnlyHttps() {
        val ok = "https://api.example.com/v1/chat/completions"
        for (u in listOf("https://api.example.com", "https://api.example.com/", "https://api.example.com/v1", "https://API.example.com/v1/",
            "https://api.example.com/v1/chat/completions")) assertEquals(u, ok, ByokTranslator.chatUrl(u).second)
        assertEquals("https://h.io:8443/api/openai/v1/chat/completions", ByokTranslator.chatUrl("https://h.io:8443/api/openai").second)
        for (u in listOf("", "http://api.example.com", "https://u:p@h.io", "https://h.io/v1?x=1", "https://h.io/#a", "api.example.com"))
            assertNull(u, ByokTranslator.chatUrl(u).second)
        for (u in listOf("https://h.io:65536", "https://h.io:0")) assertNull(u, ByokTranslator.chatUrl(u).second)
        assertEquals("https://[::1]:8443/v1/chat/completions", ByokTranslator.chatUrl("HTTPS://[::1]:8443/v1/").second)
    }

    @Test
    fun byokPromptKeepsTheFixedPartFirstAndTheSentenceLast() {
        val a = byokSystem(Scene.VTUBER, Lang.JA, Lang.ZH)
        assertTrue(a.contains("スパチャ→SC")); assertTrue(a.contains("背景"))
        assertFalse(byokSystem(Scene.VTUBER, Lang.JA, Lang.EN).contains("→SC")) // the term list is Chinese-only
        val u = byokUser("今", listOf("note"), listOf("前1", "前2"))
        assertTrue(u.indexOf("前1") < u.indexOf("今")); assertTrue(u.indexOf("今") < u.indexOf("note"))
        assertEquals("【当前句】\n今", byokUser("今"))
    }
}
