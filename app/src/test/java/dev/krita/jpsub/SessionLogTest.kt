package dev.krita.jpsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringWriter
import java.io.File
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class SessionLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun sessionsStartedInTheSameSecondGetTheirOwnFiles() {
        val root = tmp.newFolder()
        val a = SessionLog(SessionLog.dir(root)); val b = SessionLog(SessionLog.dir(root))
        a.write(mapOf("ev" to "a")); b.write(mapOf("ev" to "b"))
        assertTrue(a.close()); assertTrue(b.close())
        assertTrue(a.file.name != b.file.name)
        assertTrue(a.file.readText().contains("\"ev\":\"a\""))
        assertTrue(b.file.readText().contains("\"ev\":\"b\""))
        assertEquals(2, SessionLog.list(root).count(SessionLog::complete))
    }

    @Test
    fun aFileIsOnlyListedAsCompleteAfterAClose() {
        val root = tmp.newFolder()
        val log = SessionLog(SessionLog.dir(root))
        log.write(mapOf("ev" to "x"))
        val open = SessionLog.list(root).single()
        assertFalse(SessionLog.complete(open))
        assertEquals(open, SessionLog.active) // the home screen does not offer it for sharing
        assertTrue(log.close())
        assertNull(SessionLog.active)
        val done = SessionLog.list(root).single()
        assertTrue(SessionLog.complete(done))
        assertFalse(open.exists()) // a URI made from the listed .part before close would point at nothing
        assertTrue(done.readLines().single().contains("\"ev\":\"x\""))
    }

    @Test
    fun aStalledDiskDropsEventsVisiblyInsteadOfGrowingWithoutBound() {
        val release = CountDownLatch(1)
        val stuck = object : Writer() {
            val out = StringWriter()
            override fun write(c: CharArray, off: Int, len: Int) { release.await(); out.write(c, off, len) }
            override fun flush() {}
            override fun close() {}
        }
        val reported = AtomicReference<String?>()
        val log = SessionLog(tmp.root, capacity = 4, open = { stuck }, onFail = { reported.set(it) })
        repeat(20) { log.write(mapOf("ev" to "e$it")) }
        assertNotNull(log.failed); assertNotNull(reported.get())
        release.countDown()
        assertFalse("an incomplete log is never reported as complete", log.close())
    }

    @Test
    fun aCloseThatTimesOutIsReported() {
        val never = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stuck = object : Writer() {
            override fun write(c: CharArray, off: Int, len: Int) { never.await() }
            override fun flush() {}
            override fun close() { closed.countDown() }
        }
        val reported = AtomicReference<String?>()
        val log = SessionLog(tmp.root, open = { stuck }, onFail = { reported.set(it) })
        log.write(mapOf("ev" to "x"))
        assertFalse(log.close(timeoutMs = 200))
        assertNotNull(reported.get())
        // the disk recovers: the writer must still finish and close the file, not wait for events forever
        never.countDown()
        assertTrue("writer never closed", closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun aFinishedLogIsNeverOverwrittenByAnotherSession() {
        // another session finished a file with this name between our name choice and our own finish
        val root = tmp.newFolder()
        val log = SessionLog(SessionLog.dir(root))
        log.write(mapOf("ev" to "mine"))
        val target = File(SessionLog.dir(root), log.file.name.removeSuffix(".part"))
        target.writeText("theirs\n")
        assertTrue(log.close())
        assertEquals("theirs\n", target.readText())
        assertTrue(log.file.readText().contains("\"ev\":\"mine\""))
        assertTrue(log.file != target)
    }

    @Test
    fun aWriteErrorOnTheLastEventIsStillReported() {
        val broken = object : Writer() {
            override fun write(c: CharArray, off: Int, len: Int) { throw java.io.IOException("disk full") }
            override fun flush() {}
            override fun close() {}
        }
        val reported = AtomicReference<String?>()
        val log = SessionLog(tmp.root, open = { broken }, onFail = { reported.set(it) })
        log.write(mapOf("ev" to "session_end"))
        assertFalse(log.close())
        assertEquals("disk full", reported.get())
        assertNull(SessionLog.list(tmp.root).firstOrNull()?.takeIf { SessionLog.complete(it) })
    }
}
