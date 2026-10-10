package dev.krita.jpsub

import android.app.DownloadManager
import android.content.Context
import android.net.Uri

/**
 * Thin glue around the system [DownloadManager] (it handles retries, network changes and keeps going while
 * the app is in the background). Files go to "<path>.part" in the app's external files dir and are only
 * published by [ModelStore.publish] after size + SHA-256 checks. Ids live in [Prefs], so a recreated
 * activity picks up running downloads instead of starting them again.
 */
class ModelDownloader(private val ctx: Context, private val store: ModelStore, private val prefs: Prefs) {
    private val dm = ctx.getSystemService(DownloadManager::class.java)

    class State(val doneBytes: Long, val totalBytes: Long, val running: Boolean, val failed: List<String>, val toVerify: List<ModelSpec>)

    /** Enqueues every missing file that is not already downloading. Safe to call repeatedly. */
    fun start() {
        for (s in store.missing()) {
            if (statusOf(s)?.let { it == DownloadManager.STATUS_RUNNING || it == DownloadManager.STATUS_PENDING || it == DownloadManager.STATUS_PAUSED } == true) continue
            prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it) } // failed/finished-but-bad: start over
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

    fun cancel() {
        for (s in store.specs) prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it); prefs.setDownloadId(s.path, -1) }
    }

    /** Snapshot for the UI. Files whose download finished are returned in [State.toVerify]. */
    fun state(): State {
        var done = 0L; var running = false
        val failed = ArrayList<String>(); val toVerify = ArrayList<ModelSpec>()
        for (s in store.specs) {
            if (store.isInstalled(s)) { done += s.size; continue }
            val id = prefs.downloadId(s.path)
            if (id < 0) continue
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return@use
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { done += s.size; toVerify += s }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        failed += "${s.path.substringAfterLast('/')}：${reasonText(reason)}"
                    }
                    else -> { done += got.coerceAtLeast(0); running = true }
                }
            }
        }
        return State(done, store.totalBytes(), running, failed, toVerify)
    }

    /** Called after a verified publish (or a rejected file) so the next [start] does not reuse the id. */
    fun forget(s: ModelSpec) { prefs.downloadId(s.path).takeIf { it >= 0 }?.let { dm.remove(it) }; prefs.setDownloadId(s.path, -1) }

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
}
