package dev.krita.jpsub

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
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
        errors.clear(); failed.clear()
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
            prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it) } // failed earlier: start over
            store.partFile(s).parentFile?.mkdirs()
            store.partFile(s).delete()
            val req = DownloadManager.Request(Uri.parse(s.url))
                .setTitle("日语字幕 · 模型 ${s.path.substringAfterLast('/')}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(store.partFile(s)))
                .setAllowedOverMetered(true)
            prefs.setDownloadId(s.path, dm.enqueue(req))
        }
    }

    @Synchronized
    fun cancel() {
        for (s in store.specs) {
            if (s.path in queued) continue // already downloaded; let verification finish
            prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it); prefs.setDownloadId(s.path, -1) }
        }
    }

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
                        errors[s.path] = "${s.path.substringAfterLast('/')}：${reasonText(reason)}"
                    }
                    else -> {
                        done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                        running = true
                    }
                }
            }
        }
        return State(done, store.totalBytes(), running || queued.isNotEmpty(), verifying, errors.values.toList())
    }

    private fun submitPublish(s: ModelSpec) = submit(s) {
        var r = store.publish(s)
        if (r == ModelStore.Result.Missing) {
            // the record says finished but there is no .part: either it was published already (marker missing)
            // or the bytes are gone; never loop on it from polling
            r = if (store.file(s).isFile) store.verifyInstalled(s) else ModelStore.Result.Io("下载的文件不见了")
        }
        // the transfer is used up once published, rejected or gone; on a read error keep it for 重试
        val keep = r is ModelStore.Result.Io && store.partFile(s).isFile
        if (!keep) synchronized(this) {
            prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it) }; prefs.setDownloadId(s.path, -1)
        }
        r
    }

    /**
     * The process can die between [DownloadManager.enqueue] and saving its id. Before deleting the .part file
     * or enqueueing again, take over this app's own job for the same pinned URL if the system still has one
     * (the query only returns this app's downloads; every model file has its own URL). Never removes the other
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
                if (c.getString(uriCol) != s.url) continue
                val st = c.getInt(stCol)
                if (st !in usable || adoptActive) continue
                // a still-running job owns the .part bytes: an older finished record for the same path must not
                // make us verify (and on mismatch delete) a half-written file
                adopt = c.getLong(idCol); adoptActive = st != DownloadManager.STATUS_SUCCESSFUL
            }
        }
        if (adopt >= 0) prefs.setDownloadId(s.path, adopt)
    }

    /** Another of this app's jobs for [s]'s URL is still writing the shared .part file. */
    private fun activeAliasBesides(s: ModelSpec, id: Long): Boolean {
        var found = false
        dm.query(DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED))?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            while (c.moveToNext()) if (c.getLong(idCol) != id && c.getString(uriCol) == s.url) found = true
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
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "网络错误（能访问 huggingface.co / github.com 吗？）"
        in 400..599 -> "服务器返回 $r"
        else -> "下载失败（$r）"
    }

    companion object {
        @Volatile private var instance: ModelDownloader? = null
        fun get(ctx: Context): ModelDownloader =
            instance ?: synchronized(this) { instance ?: ModelDownloader(ctx).also { instance = it } }
    }
}
