package dev.krita.jpsub

import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * One JSONL file per capture session, for offline analysis (term extraction, tuning): every ASR result, every MT call
 * with its full prompt and raw output, every Jev request with its raw response, and every caption shown.
 * Each line is one JSON object with "ev" (event type) and "t" (monoMs). No audio, no API keys.
 *
 * Writes happen on one background thread so the pipeline never waits on storage. [write] never blocks; a failed write
 * sets [failed] (shown in the overlay) and later events are dropped instead of retried.
 */
class SessionLog(dir: File) {
    val file: File = File(dir, "session-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jsonl")
    @Volatile var failed: String? = null
        private set
    private val queue = LinkedBlockingQueue<Any>()
    private val end = Any()
    private val thread = Thread({
        var w: Writer? = null
        try {
            dir.mkdirs()
            w = file.bufferedWriter()
            while (true) {
                val e = queue.take()
                if (e === end) break
                w.write(e as String); w.write("\n")
                if (queue.isEmpty()) w.flush() // a crash loses at most what is still queued
            }
        } catch (t: Throwable) {
            failed = t.message ?: t.toString()
        } finally {
            runCatching { w?.close() }.onFailure { if (failed == null) failed = it.toString() }
        }
    }, "jpsub-log").apply { isDaemon = true; start() }

    fun write(event: Map<String, Any?>) {
        if (failed != null) return
        queue.add(Json.encode(mapOf("t" to monoMs()) + event))
    }

    /** Writes everything queued, then closes the file. Call once, after the pipeline workers have exited. */
    fun close(timeoutMs: Long = 3_000) {
        queue.add(end)
        thread.join(timeoutMs)
    }

    companion object {
        fun dir(root: File) = File(root, "logs")
        /** Newest first. */
        fun list(root: File): List<File> =
            dir(root).listFiles { f -> f.name.endsWith(".jsonl") }?.sortedByDescending { it.name } ?: emptyList()
    }
}
