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
    fun aCancelJustBeforeTheRequestIsRegisteredSendsNothing() {
        lateinit var j: Jev
        // cancel lands after the first "cancelled?" check but before the connection is registered
        j = Jev(JevProvider.OFFICIAL, "sk-secret", 2_000, url = "http://127.0.0.1:$port/v1/systemone", onOpen = { j.cancel() })
        assertEquals("cancelled", j.unfinished("はい").error)
        Thread.sleep(200)
        assertEquals("no request may reach the server", 0, hits.get())
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
