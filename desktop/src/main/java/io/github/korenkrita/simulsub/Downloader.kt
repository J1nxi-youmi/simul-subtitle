package io.github.korenkrita.simulsub

import androidx.compose.runtime.mutableStateOf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads the missing model files in the app, one at a time, as the phone does with DownloadManager: the route is
 * chosen first (every tier tries the smallest file at once, see [bestTier]), then each file comes from the chosen
 * tier and falls through to the later ones when a source fails or sends nothing for [STALL_MS]. A transfer resumes
 * where it stopped ("<path>.part", HTTP Range) on the same source; [ModelStore.publish] checks size and SHA-256 before
 * the file reaches its final path.
 */
class Downloader(private val prefs: Prefs) {
    val store = ModelStore(modelsDir, needsMt = { !prefs.mtOnline })

    /** UI snapshot. [verifying] = file being hash-checked; [choosing] = the route check runs. */
    class State(val doneBytes: Long, val totalBytes: Long, val running: Boolean, val verifying: String?, val errors: List<String>,
        val choosing: Boolean = false)

    val state = mutableStateOf(snapshot(false, null, emptyList(), false, 0))
    @Volatile private var worker: Thread? = null
    @Volatile private var cancelled = false
    @Volatile private var call: okhttp3.Call? = null
    /** Old rejected copies that could not be deleted; shown even when the models are ready. */
    @Volatile var maintenanceWarning: String? = store.removeRejected(); private set

    private fun snapshot(running: Boolean, verifying: String?, errors: List<String>, choosing: Boolean, partial: Long) =
        State(store.installedBytes() + partial, store.totalBytes().coerceAtLeast(1), running, verifying, errors, choosing)

    private fun post(running: Boolean, verifying: String? = null, errors: List<String> = emptyList(), choosing: Boolean = false, partial: Long = 0) {
        val s = snapshot(running, verifying, errors, choosing, partial)
        java.awt.EventQueue.invokeLater { state.value = s }
    }

    /** Recomputes what is installed (after the mode changed, or files were deleted). UI thread. */
    fun refresh() { if (worker == null) state.value = snapshot(false, null, state.value.errors, false, 0) }

    /** Starts downloading every missing file; does nothing while a download runs. UI thread. */
    fun start() {
        if (worker != null) return
        cancelled = false
        maintenanceWarning = store.removeRejected()
        maintenanceWarning?.let { state.value = snapshot(false, null, listOf(it), false, 0); return }
        worker = Thread({
            val errors = ArrayList<String>()
            try { run(errors) } catch (e: Throwable) { if (!cancelled) errors += "下载出错：${e.message}" }
            finally { java.awt.EventQueue.invokeLater { worker = null; state.value = snapshot(false, null, if (cancelled) emptyList() else errors, false, 0) } }
        }, "jpsub-download").apply { isDaemon = true; start() }
        state.value = snapshot(true, null, emptyList(), true, 0)
    }

    /** Stops the running download; what was received stays for the next start. UI thread. */
    fun cancel() { cancelled = true; call?.cancel(); worker?.interrupt() }

    private fun run(errors: MutableList<String>) {
        // files already at the final path but never verified (copied by hand, a marker lost): check, don't refetch
        for (s in store.missing()) if (store.file(s).isFile) { post(true, verifying = s.path); store.verifyInstalled(s) }
        val todo = store.missing()
        if (todo.isEmpty()) return
        val probe = store.specs.minBy { it.size }
        post(true, choosing = true)
        val tier = bestTier(probe.tiers, CHOOSE_MS) { url -> fetches(url, probe) } ?: 0
        for (s in todo) {
            if (cancelled) return
            var done = false
            var last: String? = null
            for (src in s.route(tier)) {
                if (cancelled) return
                val part = store.partFile(s)
                // a .part from another source might be another revision of the bytes: start over when switching
                val from = prefs.downloadSource(s.path)
                if (from != src) { part.delete(); prefs.setDownloadSource(s.path, src) }
                // a dropped connection after some bytes: resume once more on the same source before moving on
                last = (fetch(s, s.urls[src], part)?.let { why -> if (!cancelled && part.length() > 0) fetch(s, s.urls[src], part) else why }) ?: run {
                    post(true, verifying = s.path)
                    when (val r = store.publish(s, part)) {
                        ModelStore.Result.Ok -> { done = true; null }
                        is ModelStore.Result.WrongHash, is ModelStore.Result.WrongSize -> "文件校验失败"
                        is ModelStore.Result.Io -> r.msg
                        ModelStore.Result.Missing -> "文件不见了"
                    }
                }
                if (done || cancelled) break
                System.err.println("download ${s.path} from ${s.urls[src]}: $last")
            }
            if (cancelled) return
            if (done) prefs.setDownloadSource(s.path, -1)
            else errors += "${s.path.substringAfterLast('/')}：${last ?: "所有下载地址都失败了"}"
        }
    }

    /**
     * Fetches [url] into [part], resuming from its length. null when complete; otherwise why it failed. A source that
     * sends nothing for [STALL_MS] is given up (the next one is tried); once bytes arrive, only an error ends it.
     */
    private fun fetch(s: ModelSpec, url: String, part: File): String? {
        part.parentFile?.mkdirs()
        val have = if (part.isFile) part.length() else 0L
        if (have == s.size) return null
        if (have > s.size) part.delete()
        val start = if (part.isFile) part.length() else 0L
        val req = Request.Builder().url(url).apply { if (start > 0) header("Range", "bytes=$start-") }.build()
        val c = CLIENT.newCall(req).also { call = it }
        try {
            c.execute().use { r ->
                if (!r.isSuccessful) return "服务器返回 ${r.code}"
                val append = start > 0 && r.code == 206
                RandomAccessFile(part, "rw").use { out ->
                    if (!append) out.setLength(0) else out.seek(start)
                    var got = if (append) start else 0L
                    val buf = ByteArray(256 * 1024)
                    val input = r.body!!.byteStream()
                    var lastPost = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (got > s.size) return "文件比预期大"
                        val now = System.nanoTime() / 1_000_000
                        if (now - lastPost > 300) { lastPost = now; post(true, partial = got) }
                    }
                    return if (got == s.size) null else "下载中断（${got * 100 / s.size}%）"
                }
            }
        } catch (e: java.io.IOException) {
            return if (cancelled) "已取消" else "连接失败：${e.message ?: e.javaClass.simpleName}"
        } finally { call = null }
    }

    /** The probe file comes back whole and right from [url] (a proxy may answer with an error page). */
    private fun fetches(url: String, probe: ModelSpec): Boolean = try {
        PROBE_CLIENT.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body
            if (!r.isSuccessful || body == null) false
            else {
                val bytes = body.byteStream().use { it.readNBytes(probe.size.toInt() + 1) }
                bytes.size.toLong() == probe.size && MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) } == probe.sha256
            }
        }
    } catch (_: Exception) { false }

    companion object {
        private const val STALL_MS = 15_000L
        /** how long the route check waits for a tier that has not answered yet */
        private const val CHOOSE_MS = 15_000L
        private val PROBE_CLIENT = OkHttpClient.Builder().connectTimeout(CHOOSE_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CHOOSE_MS, TimeUnit.MILLISECONDS).build()
        /** no overall limit (1.3 GB takes a while); a read that waits [STALL_MS] for the next bytes fails */
        private val CLIENT = OkHttpClient.Builder().connectTimeout(STALL_MS, TimeUnit.MILLISECONDS)
            .readTimeout(STALL_MS, TimeUnit.MILLISECONDS).build()

        /**
         * Tries every source of every tier at once ([fetch] blocks). The answer is the lowest tier with a source that
         * worked, decided as soon as every lower tier failed, or after [timeoutMs] among those that worked by then;
         * null when none did. Late answers are ignored. (The phone's ModelDownloader.bestTier.)
         */
        fun bestTier(tiers: List<List<String>>, timeoutMs: Long, fetch: (String) -> Boolean): Int? {
            val lock = Object()
            val ok = BooleanArray(tiers.size)
            val left = IntArray(tiers.size) { tiers[it].size } // sources not answered yet
            for ((t, sources) in tiers.withIndex()) for (url in sources) Thread({
                val good = fetch(url)
                synchronized(lock) { if (good) ok[t] = true; left[t]--; lock.notifyAll() }
            }, "jpsub-probe").apply { isDaemon = true }.start()
            val end = System.nanoTime() / 1_000_000 + timeoutMs
            synchronized(lock) {
                while (true) {
                    // the first tier not known to have failed: it decides once it worked
                    val first = tiers.indices.firstOrNull { ok[it] || left[it] > 0 } ?: return null
                    if (ok[first]) return first
                    val wait = end - System.nanoTime() / 1_000_000
                    if (wait <= 0) return tiers.indices.firstOrNull { ok[it] }
                    lock.wait(wait)
                }
            }
        }
    }
}
