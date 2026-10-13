package io.github.korenkrita.simulsub

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Process-wide owner of model download → verify → publish (see [get]), so a recreated activity never runs a
 * second verifier or restarts a finished download. The system [DownloadManager] does the transfer (retries,
 * network changes, keeps going in the background); files land in a ".part" file and only [ModelStore.publish]
 * (size + SHA-256) moves them to the final path. All verification runs on one background thread.
 *
 * Every file has several sources ([ModelSpec.urls]). Switching to the next one happens only in [state], which the
 * UI polls on the main thread while it is open: never from the verifier, so it cannot race [cancel], a switch to
 * online translation, or run in the background.
 */
class ModelDownloader private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = Prefs(app)
    val store = ModelStore(app.getExternalFilesDir(null)!!, needsMt = { !prefs.mtOnline })
    private val dm = app.getSystemService(DownloadManager::class.java)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "jpsub-verify").apply { isDaemon = true } }
    private val queued = HashSet<String>() // paths submitted to [worker], guarded by this
    /** Verification failed: not resubmitted by [state] polling, only by an explicit [start] (重试). */
    private val failed = HashSet<String>() // guarded by this

    /** UI snapshot. [verifying] = file name currently being hash-checked. */
    class State(val doneBytes: Long, val totalBytes: Long, val running: Boolean, val verifying: String?, val errors: List<String>,
        val choosing: Boolean = false)

    @Volatile private var verifying: String? = null
    /** [start] is checking which tier to download from; [chooseGen] tells a stale check apart. Guarded by this. */
    private var choosing = false
    private var chooseGen = 0
    private val errors = LinkedHashMap<String, String>() // path → message, guarded by this
    /** path → (bytes, when they last changed) of the running transfer, for [stalled]; guarded by this */
    private val progress = HashMap<String, Pair<Long, Long>>()
    /**
     * Finished job ids whose file failed the check: [state] moves them to the next source; guarded by this.
     * Only a shortcut: after process death the job is verified again, finds no file and lands here again.
     */
    private val rejected = HashSet<Long>()

    /** Non-blocking warning shown even when models are ready (e.g. an old rejected copy could not be deleted). */
    @Volatile var maintenanceWarning: String? = null; private set

    init {
        maintenanceWarning = store.removeRejected()
        // files copied by an older version (adb push) were never hash-checked: check them once, in the background
        for (s in store.unverified()) submit(s) { store.verifyInstalled(s) }
    }

    /** Enqueues every missing file that is not already downloading or waiting for verification. Idempotent. */
    @Synchronized
    fun start() {
        errors.clear(); failed.clear(); rejected.clear(); store.specs.forEach { prefs.setPendingSource(it.path, -1) }; prefs.downloadCancelled = false
        if (!choosing) prefs.downloadBatch = emptySet() // set again below if a check starts
        // copies kept by older versions after a failed check: free the space before downloading again
        maintenanceWarning = store.removeRejected()
        maintenanceWarning?.let { errors["bad"] = it; return }
        val fresh = ArrayList<ModelSpec>()
        for (s in store.missing()) {
            if (s.path in queued) continue
            // already at the final path but unmarked (e.g. marker write failed after the rename): verify, don't refetch
            if (store.file(s).isFile) { submit(s) { store.verifyInstalled(s) }; continue }
            adoptOrphan(s)
            when (recorded(s)?.status) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> continue
                DownloadManager.STATUS_SUCCESSFUL -> { submitPublish(s); continue } // finished, just not verified yet
            }
            // failed earlier (or never started): start over, on the route chosen below
            prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it); prefs.setDownloadId(s.path, -1) }
            fresh += s
        }
        if (fresh.isEmpty() || choosing) return
        // [Prefs.downloadBatch] keeps these files until each is enqueued, so a process that ends in between resumes
        // them, and only them (a file rejected meanwhile has its own continuation or error)
        prefs.downloadTier = -1
        prefs.downloadBatch = fresh.map { it.path }.toSet()
        choose()
    }

    /**
     * Chooses this batch's route: every tier tries the smallest file at once (a few KB, thrown away). The official
     * tier wins if it answers within the check, otherwise the lowest tier that answered; the batch's files then start
     * there. Nothing answered (e.g. no network yet): the full route, which DownloadManager waits out.
     */
    private fun choose() {
        choosing = true
        val gen = ++chooseGen
        val probe = store.specs.minBy { it.size }
        Thread({
            val tier = bestTier(probe.tiers, CHOOSE_MS) { url -> fetches(url, probe) } ?: 0
            // enqueue on the main thread, where the mode changes too: a switch to online translation is seen
            main.post {
                synchronized(this) {
                    if (gen != chooseGen) return@post // cancelled, or a newer start
                    choosing = false
                    prefs.downloadTier = tier
                    schedule()
                }
            }
        }, "jpsub-route").apply { isDaemon = true }.start()
    }

    /**
     * Enqueues, on the chosen tier, the batch's files the mode still needs and that have no job yet, then ends the
     * batch. Main thread; run again after a process death in between (enqueued files are skipped).
     */
    private fun schedule() {
        val batch = prefs.downloadBatch
        for (s in store.required()) {
            if (s.path !in batch || store.isInstalled(s) || store.file(s).isFile || s.path in queued) continue
            adoptOrphan(s)
            if (recorded(s) != null) continue
            enqueue(s, route(s).first())
        }
        prefs.downloadBatch = emptySet()
    }

    /** The probe file comes back whole and right from [url] (a proxy may answer with an error page). */
    private fun fetches(url: String, probe: ModelSpec): Boolean = try {
        PROBE_CLIENT.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body
            if (!r.isSuccessful || body == null) false
            else {
                // at most one byte more than expected (readNBytes needs API 33)
                val bytes = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (bytes.size() <= probe.size) { val n = input.read(buf); if (n < 0) break; bytes.write(buf, 0, n) }
                }
                bytes.size().toLong() == probe.size && MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
                    .joinToString("") { "%02x".format(it) } == probe.sha256
            }
        }
    } catch (_: Exception) { false }

    /** This batch's order of [s]'s sources: the chosen tier, then the later ones (fixed, so it survives process death). */
    private fun route(s: ModelSpec) = s.route(prefs.downloadTier.coerceAtLeast(0))

    /** The source to try after [source] for [s] on this batch's route (null: none left). */
    private fun next(s: ModelSpec, source: Int): Int? {
        val route = route(s)
        val i = route.indexOf(source)
        return if (i < 0) route.firstOrNull() else route.getOrNull(i + 1)
    }

    /**
     * Starts [s] from source [source] of [ModelSpec.urls] into a file of its own: a removed job may still clean up
     * its file afterwards, so no later attempt writes to the same path. The source and the file are stored in the
     * job itself ([describe]), so they belong to this attempt even if the process dies before the id is saved or
     * DownloadManager rewrites the URL on a redirect.
     */
    private fun enqueue(s: ModelSpec, source: Int) {
        prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it) }
        prefs.setDownloadId(s.path, -1)
        progress.remove(s.path); errors.remove(s.path); prefs.setPendingSource(s.path, -1)
        // earlier attempts' files no job owns any more are garbage (the MT one is 1.3 GB); never one a job still has
        val owned = jobs(s).map { it.file.canonicalPath }.toSet()
        store.partFiles(s).filter { it.canonicalPath !in owned }.forEach { it.delete() }
        val part = store.newPartFile(s, UUID.randomUUID().toString().replace("-", "").take(16))
        part.parentFile?.mkdirs()
        val req = DownloadManager.Request(Uri.parse(s.urls[source]))
            .setTitle("同传字幕 · 模型 ${s.path.substringAfterLast('/')}")
            .setDescription(describe(source, part))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(part))
            .setAllowedOverMetered(true)
        prefs.setDownloadId(s.path, dm.enqueue(req))
    }

    /** One of this app's DownloadManager jobs for a model file, with the source and file it was started with. */
    private class Job(val id: Long, val status: Int, val reason: Int, val bytes: Long, val source: Int, val file: File)

    private fun describe(source: Int, part: File) = "线路 ${source + 1}：${part.name}"

    /**
     * Every job of this app for [s]. New jobs carry their source and file in the description (set at enqueue,
     * unlike COLUMN_LOCAL_URI which is empty until the server answers); jobs of version 0.2 have none and only
     * ever used the official URL with the plain .part file.
     */
    private fun jobs(s: ModelSpec): List<Job> {
        val out = ArrayList<Job>()
        val name = store.file(s).name
        dm.query(DownloadManager.Query())?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val stCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val rsCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val byCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val dsCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)
            val urCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            while (c.moveToNext()) {
                val desc = c.getString(dsCol).orEmpty()
                val current = DESCRIPTION.matchEntire(desc)
                val m = current ?: LEGACY_DESCRIPTION.matchEntire(desc)
                val source: Int; val file: File
                if (m != null) {
                    if (!m.groupValues[2].startsWith("$name.")) continue
                    val n = m.groupValues[1].toInt() - 1
                    source = if (current == null) legacySource(s.path, n) else n
                    file = File(store.file(s).parentFile, m.groupValues[2])
                } else {
                    if (c.getString(urCol) != s.urls[0]) continue
                    source = 0; file = store.partFile(s)
                }
                if (source !in s.urls.indices) continue
                out += Job(c.getLong(idCol), c.getInt(stCol), c.getInt(rsCol), c.getLong(byCol).coerceAtLeast(0), source, file)
            }
        }
        return out
    }

    /** The job whose id is saved for [s], if the system still has it. */
    private fun recorded(s: ModelSpec): Job? {
        val id = prefs.downloadId(s.path).takeIf { it >= 0 } ?: return null
        return jobs(s).firstOrNull { it.id == id }
    }

    /**
     * A transfer that is connecting or waiting to retry while its counted bytes stay 0 for [STALL_MS] counts as
     * unreachable; DownloadManager itself would keep retrying the same host for minutes. Once bytes are counted it is
     * never cut off: it waits for the connection to come back (switching would start the file over). The count moves
     * in steps of 64 KiB, so a source stuck before that (a few KB at most) is switched too, as agreed. Queued jobs and
     * jobs waiting for the network are never cut off. Only checked while the app is open (the UI polls [state]).
     */
    private fun stalled(s: ModelSpec, j: Job): Boolean {
        val trying = j.status == DownloadManager.STATUS_RUNNING ||
            (j.status == DownloadManager.STATUS_PAUSED && j.reason == DownloadManager.PAUSED_WAITING_TO_RETRY)
        val now = SystemClock.elapsedRealtime()
        val last = progress[s.path]
        if (!trying || last == null || last.first != j.bytes) { progress[s.path] = j.bytes to now; return false }
        return cutOff(j.bytes, now - last.second)
    }

    /** Failures that another copy of the same file can fix; full storage or a file error cannot. */
    private fun sourceProblem(reason: Int) = reason !in setOf(DownloadManager.ERROR_INSUFFICIENT_SPACE,
        DownloadManager.ERROR_DEVICE_NOT_FOUND, DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_FILE_ALREADY_EXISTS)

    /**
     * Moves [s] from job [j] to the next source on the route, unless the user cancelled or the file is no longer needed
     * (switched to online translation). Each source is tried once per [start]; false when there is none left.
     */
    private fun nextSource(s: ModelSpec, j: Job): Boolean {
        if (prefs.downloadCancelled || store.required().none { it.path == s.path }) return false
        enqueue(s, next(s, j.source) ?: return false)
        return true
    }

    /** Gives up on [j] after its last source: removes it so 重试 starts over from the official source. */
    private fun exhausted(s: ModelSpec, j: Job, message: String) {
        dm.remove(j.id); prefs.setDownloadId(s.path, -1); progress.remove(s.path); rejected.remove(j.id)
        if (!prefs.downloadCancelled) errors[s.path] = "${s.path.substringAfterLast('/')}：$message"
    }

    @Synchronized
    fun cancel() {
        prefs.downloadCancelled = true; store.specs.forEach { prefs.setPendingSource(it.path, -1) } // remembered across process death: no source switch until 下载 / 重试
        chooseGen++; choosing = false; prefs.downloadBatch = emptySet() // a route check still running starts nothing
        for (s in store.specs) {
            if (s.path in queued) continue // already downloaded; let verification finish
            prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it); prefs.setDownloadId(s.path, -1) }
        }
    }

    /**
     * Any of this app's DownloadManager jobs still pending / running / paused, whatever the mode needs and also an
     * orphan whose id was never saved: its .part must not be deleted. (The query only returns this app's jobs.)
     */
    @Synchronized
    fun anyTransfer(): Boolean = queued.isNotEmpty() || (dm.query(DownloadManager.Query().setFilterByStatus(
        DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED))?.use { it.count > 0 } ?: false)

    /** Snapshot for the UI; also hands finished downloads to the verifier (once) and switches sources. */
    @Synchronized
    fun state(): State {
        var done = 0L; var running = false
        // a batch the process did not live to set up: enqueue the rest on its tier, or choose again if none was
        // chosen. Not start(): that would reset continuations and errors saved meanwhile, which only 下载 / 重试 do.
        if (prefs.downloadBatch.isNotEmpty() && !choosing && !prefs.downloadCancelled) {
            if (prefs.downloadTier >= 0) schedule() else choose()
        }
        // a download of a file the mode no longer needs keeps running (cancel() stops it), it just is not counted
        for (s in store.required()) {
            if (store.isInstalled(s)) { done += s.size; continue }
            if (s.path in queued) { done += s.size; continue }
            val j = recorded(s) ?: run {
                // a rejected download whose record DownloadManager already cleaned up: continue from the saved source
                val next = prefs.pendingSource(s.path)
                if (next >= 0) {
                    prefs.setPendingSource(s.path, -1)
                    if (!prefs.downloadCancelled && next < s.urls.size) { enqueue(s, next); running = true }
                }
                null
            } ?: continue
            when (j.status) {
                DownloadManager.STATUS_SUCCESSFUL -> when {
                    j.id in rejected -> // its file failed the check (and is deleted): next source, or give up
                        if (nextSource(s, j)) running = true
                        else exhausted(s, j, "下载的文件不完整，所有下载地址都试过了，请重试")
                    activeAliasBesides(s, j.id) -> { adoptOrphan(s); running = true } // a newer attempt owns the file
                    else -> { done += s.size; if (s.path !in failed) submitPublish(s) }
                }
                DownloadManager.STATUS_FAILED ->
                    if (sourceProblem(j.reason) && nextSource(s, j)) running = true
                    else if (sourceProblem(j.reason)) exhausted(s, j, reasonText(j.reason))
                    else errors[s.path] = "${s.path.substringAfterLast('/')}：${reasonText(j.reason)}"
                else ->
                    if (!stalled(s, j)) { done += j.bytes; running = true }
                    else if (nextSource(s, j)) running = true
                    else if (!prefs.downloadCancelled) exhausted(s, j, "所有下载地址都连不上，请检查网络后重试")
            }
        }
        return State(done, store.totalBytes(), running || choosing || queued.isNotEmpty(), verifying, errors.values.toList(), choosing)
    }

    private fun submitPublish(s: ModelSpec) {
        val j = recorded(s) ?: return
        submit(s) { publishFrom(s, j) }
    }

    /** Verifies the file finished job [j] wrote; afterwards only that job's record is touched. */
    private fun publishFrom(s: ModelSpec, j: Job): ModelStore.Result {
        var r = store.publish(s, j.file)
        if (r == ModelStore.Result.Missing) {
            // the record says finished but there is no file: published already (marker missing), or the bytes are
            // gone (e.g. rejected just before the process died): then this copy is unusable like a wrong one
            r = if (store.file(s).isFile) store.verifyInstalled(s) else ModelStore.Result.WrongSize(0)
        }
        // the transfer is used up once published, rejected or gone; on a read error keep it for 重试
        if (r is ModelStore.Result.Io && j.file.isFile) return r
        synchronized(this) {
            if (prefs.downloadId(s.path) != j.id) return r // a newer attempt took over meanwhile
            // wrong bytes (e.g. a proxy answering with an HTML page): not an error yet. Keep the finished record;
            // [state] tries the next source from the main thread
            val bad = r is ModelStore.Result.WrongSize || r is ModelStore.Result.WrongHash
            val then = if (bad) next(s, j.source) else null
            if (then != null) { prefs.setPendingSource(s.path, then); rejected += j.id; return ModelStore.Result.Missing }
            dm.remove(j.id); prefs.setDownloadId(s.path, -1)
        }
        return r
    }

    /**
     * The process can die between [DownloadManager.enqueue] and saving its id. Before deleting a .part file or
     * enqueueing again, take over this app's own job for the file if the system still has one (the query only
     * returns this app's downloads). Never removes the other records: removing a record deletes its file.
     */
    private fun adoptOrphan(s: ModelSpec) {
        val usable = setOf(DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED)
        val all = jobs(s)
        val id = prefs.downloadId(s.path)
        if (all.any { it.id == id && it.status in usable } && !activeAliasBesides(s, id)) return // the recorded job is fine
        // a still-running job owns its bytes: prefer it over a finished record, so a half-written file is never
        // verified (and on mismatch deleted); among equals the newest
        all.filter { it.status in usable }
            .maxWithOrNull(compareBy<Job> { it.status != DownloadManager.STATUS_SUCCESSFUL }.thenBy { it.id })
            ?.let { prefs.setDownloadId(s.path, it.id) }
    }

    /** Another of this app's jobs for [s] is still pending / running / paused. */
    private fun activeAliasBesides(s: ModelSpec, id: Long): Boolean = jobs(s).any {
        it.id != id && it.status in setOf(DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED)
    }

    /** Runs [job] for [s] once on the verifier thread; any failure becomes a retryable error, never a crash. */
    @Synchronized
    private fun submit(s: ModelSpec, job: () -> ModelStore.Result) {
        if (!queued.add(s.path)) return
        worker.execute {
            verifying = s.path.substringAfterLast('/')
            val r = try { job() } catch (t: Throwable) { ModelStore.Result.Io(t.message ?: t.toString()) }
            verifying = null
            synchronized(this) {
                queued.remove(s.path)
                if (r != ModelStore.Result.Ok && r != ModelStore.Result.Missing) {
                    failed += s.path
                    errors[s.path] = "${s.path.substringAfterLast('/')} 校验失败（${describe(r)}），请重试"
                }
            }
        }
    }

    private fun describe(r: ModelStore.Result) = when (r) {
        is ModelStore.Result.WrongSize -> "大小不对"
        is ModelStore.Result.WrongHash -> "内容不完整"
        is ModelStore.Result.Io -> r.msg
        else -> r.toString()
    }

    private fun reasonText(r: Int) = when (r) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足（需要约 1.3 GB）"
        DownloadManager.ERROR_CANNOT_RESUME -> "无法续传，请重试"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "网络错误，所有下载地址都连不上，请检查网络后重试"
        in 400..599 -> "服务器返回 $r"
        else -> "下载失败（$r）"
    }

    companion object {
        /** "线路 <1-based source>：<part file name>", the job description that identifies an attempt */
        private val DESCRIPTION = Regex("线路 (\\d+)：(.+\\.part)")
        /** the same up to 0.6.3, numbering the sources of that version */
        private val LEGACY_DESCRIPTION = Regex("来源 (\\d+)：(.+\\.part)")
        private const val STALL_MS = 15_000L
        /** how long the route check waits for a tier that has not answered yet */
        private const val CHOOSE_MS = 15_000L
        private val PROBE_CLIENT = OkHttpClient.Builder().connectTimeout(CHOOSE_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CHOOSE_MS, TimeUnit.MILLISECONDS).build()

        /** A transfer stuck for [idleMs] is given up for the next source: only while no bytes are counted yet. */
        fun cutOff(bytes: Long, idleMs: Long) = bytes == 0L && idleMs > STALL_MS

        /**
         * Tries every source of every tier at once ([fetch] blocks). The answer is the lowest tier with a source that
         * worked, decided as soon as every lower tier failed, or after [timeoutMs] among those that worked by then;
         * null when none did. Late answers are ignored.
         */

        /** A source index saved by 0.6.3 or older: the translation model had no GitHub copy, ModelScope moved 1 → 2. */
        fun legacySource(path: String, n: Int) = if (path.startsWith("mt/") && n >= 1) n + 1 else n

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
        @Volatile private var instance: ModelDownloader? = null
        fun get(ctx: Context): ModelDownloader =
            instance ?: synchronized(this) { instance ?: ModelDownloader(ctx).also { instance = it } }
    }
}
