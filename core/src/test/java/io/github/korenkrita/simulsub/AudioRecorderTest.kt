package io.github.korenkrita.simulsub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The optional session audio: WAV header, `.part` semantics, clamping, trimming and failure paths. */
class AudioRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 512 samples (one capture frame) alternating 0 and 0.25 — in range, but not silence. */
    private fun frame() = FloatArray(512) { if (it % 2 == 1) 0.25f else 0f }

    /** the two length fields of a 44-byte WAV header, little-endian: RIFF chunk size (at 4) and data size (at 40) */
    private fun lengthFields(f: File): Pair<Long, Long> {
        val b = ByteArray(44)
        RandomAccessFile(f, "r").use { it.readFully(b) }
        val head = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return head.getInt(4).toLong() to head.getInt(40).toLong()
    }

    /** the PCM samples of a finished WAV */
    private fun pcm(f: File): ShortArray {
        val all = f.readBytes()
        val buf = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((all.size - 44) / 2) { buf.getShort(44 + it * 2) }
    }

    /** waits until the writer thread has created the part file and written its reserved header */
    private fun awaitHeader(part: File) {
        val until = System.currentTimeMillis() + 5_000
        while (part.length() < 44 && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue("the .part file with its reserved header never appeared", part.length() >= 44)
    }

    @Test
    fun theHeaderIsBackfilledWithTheRealLengthsOnClose() {
        val dir = tmp.newFolder()
        val part = File(dir, "session-20261023-214100.wav.part")
        val rec = AudioRecorder(part)
        repeat(3) { rec.write(frame()) }
        awaitHeader(part)
        // until the close backfills them, both length fields are zero: a half-written file shows it
        assertEquals(0L to 0L, lengthFields(part))
        val dataBytes = 3L * 512 * 2
        assertTrue(rec.close(AudioRecorder.CLOSE_TIMEOUT_MS))
        assertFalse("the recording left the .part behind", part.exists())
        val wav = rec.file
        assertEquals(File(dir, "session-20261023-214100.wav"), wav)
        assertEquals(36L + dataBytes to dataBytes, lengthFields(wav))
        assertEquals(44L + dataBytes, wav.length())
        // the whole 44-byte header: RIFF/WAVE, 16-bit PCM, 16 kHz, mono, 32 000 bytes/s, 2-byte frames
        val b = wav.readBytes()
        val head = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(b, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(b, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(b, 12, 4, Charsets.US_ASCII))
        assertEquals(16, head.getInt(16)) // fmt chunk size
        assertEquals(1, head.getShort(20).toInt()) // PCM
        assertEquals(1, head.getShort(22).toInt()) // mono
        assertEquals(16_000, head.getInt(24)) // sample rate
        assertEquals(32_000, head.getInt(28)) // byte rate
        assertEquals(2, head.getShort(32).toInt()) // block align
        assertEquals(16, head.getShort(34).toInt()) // bits per sample
        assertEquals("data", String(b, 36, 4, Charsets.US_ASCII))
        // the samples came through: 0.25f becomes 8191, silence stays 0
        val data = pcm(wav)
        assertEquals(1536, data.size)
        assertEquals(768, data.count { it == 8191.toShort() })
        assertTrue(data.all { it == 0.toShort() || it == 8191.toShort() })
    }

    @Test
    fun aNormalCloseRenamesThePartFileToWav() {
        val dir = tmp.newFolder()
        val part = File(dir, "session-20261023-214100.wav.part")
        val rec = AudioRecorder(part)
        rec.write(frame())
        assertTrue(rec.close(AudioRecorder.CLOSE_TIMEOUT_MS))
        assertFalse("the .part is gone once the recording is complete", part.exists())
        assertEquals(File(dir, "session-20261023-214100.wav"), rec.file)
        assertTrue(rec.file.isFile)
        assertNull(rec.failed)
        assertEquals("a second close is idempotent", true, rec.close(AudioRecorder.CLOSE_TIMEOUT_MS))
        // a write after the close is dropped: the finished file keeps its length
        val len = rec.file.length(); rec.write(frame())
        assertEquals(len, rec.file.length())
    }

    @Test
    fun anUnclosedRecorderLeavesOnlyThePartFile() {
        val dir = tmp.newFolder()
        val part = File(dir, "session-20261023-214100.wav.part")
        val rec = AudioRecorder(part)
        rec.write(frame()); rec.write(frame())
        awaitHeader(part)
        assertFalse(File(dir, "session-20261023-214100.wav").exists())
        assertTrue("no close, no rename", part.isFile)
        assertTrue(rec.close(AudioRecorder.CLOSE_TIMEOUT_MS)) // cleanup; also shows a late close still completes
    }

    @Test
    fun aFailedCloseKeepsThePartFile() {
        val dir = tmp.newFolder()
        // something else occupies the finished name: the close must not overwrite it, the recording stays unfinished
        val taken = File(dir, "session-20261023-214100.wav").apply { mkdirs() }
        val part = File(dir, "session-20261023-214100.wav.part")
        val reported = AtomicReference<String?>()
        val calls = AtomicInteger()
        val rec = AudioRecorder(part, onFail = { reported.set(it); calls.incrementAndGet() })
        rec.write(frame())
        assertFalse(rec.close(AudioRecorder.CLOSE_TIMEOUT_MS))
        assertNotNull(rec.failed)
        assertNotNull(reported.get())
        assertEquals("onFail once", 1, calls.get())
        assertTrue("the unfinished recording stays a .part", part.isFile)
        assertTrue("the file that took the name was not touched", taken.isDirectory)
    }

    @Test
    fun aFullQueueFailsTheRecordingCallsOnFailOnceAndDropsLateWrites() {
        val dir = tmp.newFolder()
        val part = File(dir, "session-20261023-214100.wav.part")
        val reported = AtomicReference<String?>()
        val calls = AtomicInteger()
        val rec = AudioRecorder(part, queueCapacity = 4, onFail = { reported.set(it); calls.incrementAndGet() })
        // far more than the queue holds, offered faster than the writer drains: the recording is given up
        repeat(2_000) { rec.write(frame()) }
        assertNotNull("the queue overflow must fail the recording", rec.failed)
        assertEquals("音频写入队列满", reported.get())
        assertEquals("onFail once, not once per dropped frame", 1, calls.get())
        assertEquals("the file stays the .part", part, rec.file)
        assertFalse(rec.close(AudioRecorder.CLOSE_TIMEOUT_MS))
        assertTrue("a failed recording is never renamed", part.isFile)
        assertFalse(File(dir, "session-20261023-214100.wav").exists())
        rec.write(frame()) // late writes after the failure are dropped quietly
        assertEquals(1, calls.get())
    }

    @Test
    fun outOfRangeFloatsAreClampedInsteadOfWrapping() {
        fun le(vararg s: Float): Int {
            val b = AudioRecorder.samplesToBytes(floatArrayOf(*s))
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        }
        // without the clamp, (2.0f * 32767).toInt().toShort() wraps to -2 and -2.0f wraps to 2
        assertEquals(32767, le(2.0f))
        assertEquals(-32767, le(-2.0f))
        assertEquals(32767, le(1.0f))
        assertEquals(-32767, le(-1.0f))
        assertEquals(0, le(0.0f))
        assertEquals(16383, le(0.5f))
        // and the same through a real file
        val dir = tmp.newFolder()
        val rec = AudioRecorder(File(dir, "session-20261023-214100.wav.part"))
        rec.write(floatArrayOf(2.0f, -2.0f, 1.0f, -1.0f, 0f))
        assertTrue(rec.close(AudioRecorder.CLOSE_TIMEOUT_MS))
        assertArrayEquals(shortArrayOf(32767, -32767, 32767, -32767, 0), pcm(rec.file))
    }

    @Test
    fun trimDeletesTheOldestCompleteWavsButNeverPartFiles() {
        val dir = tmp.newFolder()
        val now = System.currentTimeMillis()
        fun file(name: String, size: Int, age: Long) = File(dir, name).apply {
            writeText("x".repeat(size)); setLastModified(now - age)
        }
        val a = file("session-20261023-100000.wav", 1_000, 300_000)
        val b = file("session-20261023-110000.wav", 2_000, 200_000)
        val c = file("session-20261023-120000.wav", 3_000, 100_000)
        val p1 = file("session-20261023-130000.wav.part", 5_000, 50_000) // a running or abandoned recording
        val p2 = file("session-20261023-140000.wav.part", 100, 10_000)
        AudioRecorder.trim(dir, maxBytes = 4_000) // 6 000 bytes of .wav: the two oldest go
        assertFalse(a.exists()); assertFalse(b.exists())
        assertTrue(c.exists())
        assertTrue("a .wav.part is never trimmed", p1.exists()); assertTrue(p2.exists())
        // nothing is deleted when the total already fits
        AudioRecorder.trim(dir, maxBytes = 4_000)
        AudioRecorder.trim(dir)
        assertTrue(c.exists()); assertTrue(p1.exists()); assertTrue(p2.exists())
    }

    @Test
    fun anEmptyRecordingClosesToAValidEmptyWav() {
        val dir = tmp.newFolder()
        val rec = AudioRecorder(File(dir, "session-20261023-214100.wav.part"))
        assertTrue(rec.close(AudioRecorder.CLOSE_TIMEOUT_MS))
        assertTrue(rec.file.isFile)
        assertEquals(36L to 0L, lengthFields(rec.file))
        assertEquals(44L, rec.file.length())
    }

    @Test
    fun anEmptyFrameAddsNoData() {
        val dir = tmp.newFolder()
        val rec = AudioRecorder(File(dir, "session-20261023-214100.wav.part"))
        rec.write(frame()); rec.write(FloatArray(0)); rec.write(frame())
        assertTrue(rec.close(AudioRecorder.CLOSE_TIMEOUT_MS))
        assertEquals(2L * 512 * 2, lengthFields(rec.file).second)
        assertEquals(44L + 2L * 512 * 2, rec.file.length())
    }

    @Test
    fun theAutoCloseableCloseReallyCloses() {
        // 无参 close 曾写成 `close()` 调用自己（无参重载优先于默认参数重载，Kotlin 精确匹配在先）：
        // 真拿它收尾的会话线程必然 StackOverflowError，还会跳过 session_end 与模型释放
        val dir = tmp.newFolder()
        val part = File(dir, "session-20261023-214100.wav.part")
        val rec = AudioRecorder(part)
        rec.write(frame())
        rec.close() // the AutoCloseable overload: must finalize, not recurse
        assertFalse(part.exists())
        assertTrue(rec.file.isFile)
        assertEquals(36L + 512L * 2 to 512L * 2, lengthFields(rec.file))
    }
}
