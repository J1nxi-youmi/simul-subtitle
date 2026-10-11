package io.github.korenkrita.simulsub

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * Process-wide owner of model download → verify → publish (see [get]), so a recreated activity never runs a
 * second verifier or restarts a finished download. The system [DownloadManager] does the transfer (retries,
 * network changes, keeps going in the background); files land in "<path>.part" and only [ModelStore.publish]
 * (size + SHA-256) moves them to the final path. All verification runs on one background thread.
 */
class ModelDownloader private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = Prefs(app)
    val store = ModelStore(app.getExternalFilesDir(null)!!, needsMt = { !prefs.mtOnline })
    private val dm = app.getSystemService(DownloadManager::class.java)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "jpsub-verify").apply { isDaemon = true } }
    private val queued = HashSet<String>() // paths submitted to [worker], guarded by this
    /** Verification failed: not resubmitted by [state] polling, only by an explicit [start] (重试). */
    private val failed = HashSet<String>() // guarded by this

    /** UI snapshot. [verifying] = file name currently being hash-checked. */
    class State(val doneBytes: Long, val totalBytes: Long, val running: Boolean, val verifying: String?, val errors: List<String>)

    @Volatile private var verifying: String? = null
    private val errors = LinkedHashMap<String, String>() // path → message, guarded by this
    /** path → (bytes, when they last changed) of the running transfer, for [stalled]; guarded by this */
    private val progress = HashMap<String, Pair<Long, Long>>()
    /** set by [cancel], cleared by [start]: no automatic switch to another source in between; guarded by this */
    private var cancelled = false

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
        errors.clear(); failed.clear(); cancelled = false
        // copies kept by older versions after a failed check: free the space before downloading again
        maintenanceWarning = store.removeRejected()
        maintenanceWarning?.let { errors["bad"] = it; return }
        for (s in store.missing()) {
            if (s.path in queued) continue
            // already at the final path but unmarked (e.g. marker write failed after the rename): verify, don't refetch
            if (store.file(s).isFile) { submit(s) { store.verifyInstalled(s) }; continue }
            adoptOrphan(s)
            when (statusOf(s)) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> continue
                DownloadManager.STATUS_SUCCESSFUL -> { submitPublish(s); continue } // finished, just not verified yet
            }
            enqueue(s, 0) // failed earlier (or never started): start over from the official source
        }
    }

    /**
     * Starts [s] from source [source] of [ModelSpec.urls], written to that source's own .part file: a removed
     * job may still clean up its file afterwards, so the replacement never writes to the same path.
     */
    private fun enqueue(s: ModelSpec, source: Int) {
        prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it) }
        progress.remove(s.path); errors.remove(s.path)
        val part = store.partFile(s, source)
        part.parentFile?.mkdirs()
        part.delete()
        val req = DownloadManager.Request(Uri.parse(s.urls[source]))
            .setTitle("同传字幕 · 模型 ${s.path.substringAfterLast('/')}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(part))
            .setAllowedOverMetered(true)
        prefs.setDownloadId(s.path, dm.enqueue(req))
    }

    /**
     * A transfer that is connecting or waiting to retry while its byte count does not move for [STALL_MS]
     * ([STALL_STARTED_MS] once bytes arrived) counts as unreachable; DownloadManager itself would keep retrying
     * the same host for minutes. The count only moves in steps of 64 KiB, so a source slower than about
     * 2 KiB/s (0.5 KiB/s once started) also counts as stalled, which is the right call for a 70 MB file.
     * Queued jobs and jobs waiting for the network are never cut off.
     */
    private fun stalled(s: ModelSpec, status: Int, reason: Int, bytes: Long): Boolean {
        val trying = status == DownloadManager.STATUS_RUNNING ||
            (status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_WAITING_TO_RETRY)
        val now = SystemClock.elapsedRealtime()
        val last = progress[s.path]
        if (!trying || last == null || last.first != bytes) { progress[s.path] = bytes to now; return false }
        // nothing received yet: the host is probably unreachable; already started: allow a longer gap
        return now - last.second > if (bytes == 0L) STALL_MS else STALL_STARTED_MS
    }

    /** Failures that another copy of the same file can fix; full storage or a file error cannot. */
    private fun sourceProblem(reason: Int) = reason !in setOf(DownloadManager.ERROR_INSUFFICIENT_SPACE,
        DownloadManager.ERROR_DEVICE_NOT_FOUND, DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_FILE_ALREADY_EXISTS)

    /** Index in [ModelSpec.urls] of the recorded job's source, -1 if there is no such job. */
    private fun sourceOf(s: ModelSpec): Int {
        val id = prefs.downloadId(s.path).takeIf { it >= 0 } ?: return -1
        return dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) s.urls.indexOf(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))) else -1
        } ?: -1
    }

    /**
     * Moves [s] to the next of its sources when [sourceFault] (the failure is about this source, not about the
     * phone), unless the user cancelled or the file is no longer needed (switched to online translation).
     * Each source is tried once per [start]; false when there is none left, so the error is shown.
     */
    private fun nextSource(s: ModelSpec, sourceFault: Boolean): Boolean {
        if (!sourceFault || cancelled || store.required().none { it.path == s.path }) return false
        val i = sourceOf(s)
        if (i < 0 || i + 1 >= s.urls.size) return false
        enqueue(s, i + 1)
        return true
    }

    @Synchronized
    fun cancel() {
        cancelled = true // also stops a verification still running from starting the next source
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

    /** Snapshot for the UI; also hands finished downloads to the verifier (once). */
    @Synchronized
    fun state(): State {
        var done = 0L; var running = false
        // a download of a file the mode no longer needs keeps running (cancel() stops it), it just is not counted
        for (s in store.required()) {
            if (store.isInstalled(s)) { done += s.size; continue }
            if (s.path in queued) { done += s.size; continue }
            val id = prefs.downloadId(s.path).takeIf { it >= 0 } ?: continue
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return@use
                when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (activeAliasBesides(s, id)) { adoptOrphan(s); running = true } // a newer attempt owns the file
                        else { done += s.size; if (s.path !in failed) submitPublish(s) }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        if (!nextSource(s, sourceProblem(reason)))
                            errors[s.path] = "${s.path.substringAfterLast('/')}：${reasonText(reason)}"
                        else running = true
                    }
                    else -> {
                        val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                        if (!stalled(s, st, reason, bytes)) { done += bytes; running = true }
                        else if (nextSource(s, true)) running = true
                        else if (!cancelled) {
                            // the last source is unreachable too: stop it, so 重试 starts over from the official one
                            dm.remove(id); prefs.setDownloadId(s.path, -1); progress.remove(s.path)
                            errors[s.path] = "${s.path.substringAfterLast('/')}：所有下载地址都连不上，请检查网络后重试"
                        }
                    }
                }
            }
        }
        return State(done, store.totalBytes(), running || queued.isNotEmpty(), verifying, errors.values.toList())
    }

    private fun submitPublish(s: ModelSpec) {
        val source = sourceOf(s).coerceAtLeast(0) // which .part the finished job wrote
        submit(s) { publishFrom(s, source) }
    }

    private fun publishFrom(s: ModelSpec, source: Int): ModelStore.Result {
        var r = store.publish(s, source)
        if (r == ModelStore.Result.Missing) {
            // the record says finished but there is no .part: either it was published already (marker missing)
            // or the bytes are gone; never loop on it from polling
            r = if (store.file(s).isFile) store.verifyInstalled(s) else ModelStore.Result.Io("下载的文件不见了")
        }
        // the transfer is used up once published, rejected or gone; on a read error keep it for 重试
        val keep = r is ModelStore.Result.Io && store.partFile(s, source).isFile
        if (!keep) synchronized(this) {
            // wrong bytes (e.g. a proxy answering with an HTML page): try the next source instead of failing
            val bad = r is ModelStore.Result.WrongSize || r is ModelStore.Result.WrongHash
            if (bad && nextSource(s, true)) return ModelStore.Result.Missing // not an error: retried
            prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it) }; prefs.setDownloadId(s.path, -1)
        }
        return r
    }

    /**
     * The process can die between [DownloadManager.enqueue] and saving its id. Before deleting the .part file
     * or enqueueing again, take over this app's own job for the same pinned URL if the system still has one
     * (the query only returns this app's downloads; every model file has its own URLs). Never removes the other
     * records: they all point at the same .part path, and removing a record deletes that file.
     */
    private fun adoptOrphan(s: ModelSpec) {
        val usable = setOf(DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED)
        if (statusOf(s) in usable && !activeAliasBesides(s, prefs.downloadId(s.path))) return // the recorded job is fine
        var adopt = -1L; var adoptActive = false
        dm.query(DownloadManager.Query())?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val stCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            while (c.moveToNext()) {
                if (c.getString(uriCol) !in s.urls) continue
                val st = c.getInt(stCol)
                if (st !in usable || adoptActive) continue
                // a still-running job owns the .part bytes: an older finished record for the same path must not
                // make us verify (and on mismatch delete) a half-written file
                adopt = c.getLong(idCol); adoptActive = st != DownloadManager.STATUS_SUCCESSFUL
            }
        }
        if (adopt >= 0) prefs.setDownloadId(s.path, adopt)
    }

    /** Another of this app's jobs for any of [s]'s URLs is still writing the shared .part file. */
    private fun activeAliasBesides(s: ModelSpec, id: Long): Boolean {
        var found = false
        dm.query(DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED))?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            while (c.moveToNext()) if (c.getLong(idCol) != id && c.getString(uriCol) in s.urls) found = true
        }
        return found
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

    private fun statusOf(s: ModelSpec): Int? {
        val id = prefs.downloadId(s.path).takeIf { it >= 0 } ?: return null
        return dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
        }
    }

    private fun reasonText(r: Int) = when (r) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足（需要约 1.3 GB）"
        DownloadManager.ERROR_CANNOT_RESUME -> "无法续传，请重试"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "网络错误，所有下载地址都连不上，请检查网络后重试"
        in 400..599 -> "服务器返回 $r"
        else -> "下载失败（$r）"
    }

    companion object {
        private const val STALL_MS = 30_000L
        private const val STALL_STARTED_MS = 120_000L
        @Volatile private var instance: ModelDownloader? = null
        fun get(ctx: Context): ModelDownloader =
            instance ?: synchronized(this) { instance ?: ModelDownloader(ctx).also { instance = it } }
    }
}
