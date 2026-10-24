package io.github.korenkrita.simulsub

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Writes a mono 16 kHz 16-bit PCM WAV file on a background thread so the capture loop never waits on storage.
 * The file is written as `*.wav.part` and only renamed to `*.wav` after a successful [close], which also backfills
 * the RIFF/data length fields. A failed or still-running session therefore never leaves a file that looks complete.
 *
 * Float samples are expected in [-1, 1] and are clamped before conversion to int16. Out-of-range values therefore
 * clip instead of wrapping.
 */
class AudioRecorder(
    /** the `*.wav.part` file being written */
    val partFile: File,
    private val queueCapacity: Int = 200,
    private val onFail: (String) -> Unit = {},
) : AutoCloseable {
    @Volatile var failed: String? = null
        private set
    @Volatile var file: File = partFile
        private set

    private val queue = LinkedBlockingQueue<Packet>(queueCapacity)
    @Volatile private var closing = false
    @Volatile private var completed = false

    private data class Packet(val bytes: ByteArray)

    private val thread = Thread({ writerLoop() }, "jpsub-audio").apply { isDaemon = true; start() }

    private fun writerLoop() {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(partFile, "rwd")
            // Reserve the 44-byte WAV header; it is rewritten with real lengths on close().
            raf.write(ByteArray(HEADER_SIZE))
            while (true) {
                val p = queue.poll(50, TimeUnit.MILLISECONDS)
                if (p == null) {
                    if (closing) break
                    continue
                }
                raf.write(p.bytes)
                if (closing && queue.isEmpty()) break
            }
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        } finally {
            runCatching { raf?.close() }.onFailure { fail(it.toString()) }
        }
    }

    /** Queue one frame for writing. Never blocks; a full queue fails the recorder for this session. */
    fun write(samples: FloatArray) {
        if (failed != null || closing) return
        val bytes = samplesToBytes(samples)
        if (!queue.offer(Packet(bytes))) fail("音频写入队列满")
    }

    /**
     * Flushes the queued audio, writes a correct WAV header, and renames the part file to `*.wav`.
     * @return true only if the file is complete; otherwise [failed] says why.
     */
    fun close(timeoutMs: Long = CLOSE_TIMEOUT_MS): Boolean {
        if (completed) return failed == null
        closing = true
        thread.join(timeoutMs)
        if (thread.isAlive) {
            fail("关闭音频超时")
            return false
        }
        if (failed != null) return false
        return finalizeWav().also { completed = it }
    }

    /**
     * [AutoCloseable] for use{} / try-with-resources, same semantics without the result.
     * 必须显式指向带参数的重载：无参调用会再次解析到这个无参 close 本身（Kotlin 里精确匹配
     * 优先于默认参数），那是一开始就写成的无限递归，开启录音的会话收尾时必然栈溢出。
     */
    override fun close() { close(CLOSE_TIMEOUT_MS) }

    private fun finalizeWav(): Boolean {
        val raf = runCatching { RandomAccessFile(partFile, "rwd") }.getOrNull()
            ?: run { fail("无法打开音频文件"); return false }
        try {
            val dataBytes = raf.length() - HEADER_SIZE
            if (dataBytes < 0) { fail("音频文件长度异常"); return false }
            val header = makeWavHeader(dataBytes)
            raf.seek(0)
            raf.write(header)
        } catch (t: Throwable) {
            fail(t.message ?: t.toString()); return false
        } finally {
            runCatching { raf.close() }
        }
        val target = File(partFile.parentFile, partFile.name.removeSuffix(PART))
        // 同名文件已被占用（stem 的选择在 SessionLog 那边避开了，这里只防进程外的文件）：宁可留下 .part，
        // 也不覆盖别人的完整录音——renameTo 在 Windows 会失败，在 Linux 却会静默覆盖
        if (target != partFile && target.exists()) { fail("无法完成音频文件：${target.name} 已存在"); return false }
        if (!partFile.renameTo(target)) { fail("无法完成音频文件"); return false }
        file = target
        return true
    }

    @Synchronized private fun fail(why: String) {
        if (failed != null) return
        failed = why
        queue.clear()
        onFail(why)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val HEADER_SIZE = 44
        private const val PART = ".part"
        /** how long [close] waits for the writer thread to drain its queue */
        const val CLOSE_TIMEOUT_MS = 3_000L

        fun samplesToBytes(samples: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val clamped = s.coerceIn(-1f, 1f)
                val pcm = (clamped * 32767f).toInt().toShort()
                buf.putShort(pcm)
            }
            return buf.array()
        }

        private fun makeWavHeader(dataBytes: Long): ByteArray {
            val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
            val blockAlign = (CHANNELS * BITS_PER_SAMPLE / 8).toShort()
            return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36 + dataBytes).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(CHANNELS.toShort())
                putInt(SAMPLE_RATE)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(BITS_PER_SAMPLE.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes.toInt())
            }.array()
        }

        /**
         * Finalizes recordings a killed process left behind: a `*.wav.part` whose header was never backfilled gets a
         * correct one and is renamed to `*.wav`, so a crashed session's audio is playable and shares like a finished
         * one. The part of a session that may still write it ([SessionLog.isActive]) is left untouched, as is a part
         * holding nothing but the reserved header, and one whose `.wav` name is already taken — a complete recording
         * is never overwritten. A killed write can end mid-frame; the odd trailing byte is dropped, not shifted.
         * @return how many recordings were recovered
         */
        fun recover(dir: File): Int {
            val parts = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav$PART") } ?: return 0
            var recovered = 0
            for (part in parts) {
                val stem = part.name.removeSuffix(".wav$PART")
                // the writer of a live session is still appending to its own part file: never repair that one
                if (SessionLog.isActive(File(dir, "$stem.jsonl$PART"))) continue
                if (recoverOne(part, File(dir, "$stem.wav"))) recovered++
            }
            return recovered
        }

        private fun recoverOne(part: File, target: File): Boolean {
            val len = runCatching { part.length() }.getOrNull() ?: return false
            if (len < HEADER_SIZE + 2L) return false // nothing but the reserved header: no audio to save
            if (target.exists()) return false // a complete recording already owns this name: keep the part as evidence
            val dataBytes = ((len - HEADER_SIZE) / 2) * 2 // drop a half-written sample so frames stay aligned
            val raf = runCatching { RandomAccessFile(part, "rwd") }.getOrNull() ?: return false
            try {
                if (dataBytes < len - HEADER_SIZE) raf.setLength(HEADER_SIZE + dataBytes)
                raf.seek(0)
                raf.write(makeWavHeader(dataBytes))
            } catch (t: Throwable) {
                return false // the part stays as it was: a later attempt can still recover it
            } finally {
                runCatching { raf.close() }
            }
            return part.renameTo(target)
        }

        /** Deletes the oldest complete `.wav` files in [dir] until their total size is <= [maxBytes]. `.part` files are kept. */
        fun trim(dir: File, maxBytes: Long = 200L * 1024 * 1024) {
            val wavs = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
                ?.sortedBy { it.lastModified() } ?: return
            var total = wavs.sumOf { it.length() }
            for (f in wavs) {
                if (total <= maxBytes) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }
}
