package io.github.korenkrita.simulsub

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// A distinct component name keeps Android from reusing the existing log provider for this authority.
class UpdateFileProvider : FileProvider()

/** One fixed-name asset on the latest stable GitHub release; no API or HTML scraping through proxies. */
internal data class UpdateRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
) {
    fun isNewer(installedCode: Int) = versionCode > installedCode

    fun shouldOffer(installedCode: Int, manual: Boolean, auto: Boolean, skipped: Int,
                    laterCode: Int, laterUntil: Long, now: Long) =
        isNewer(installedCode) && (manual || (auto && versionCode != skipped &&
            (versionCode != laterCode || now >= laterUntil)))

    fun json(): String = JSONObject().put("versionCode", versionCode).put("versionName", versionName)
        .put("notes", notes).put("apkUrl", apkUrl).put("size", size).put("sha256", sha256).toString()

    companion object {
        const val REPO = "https://github.com/KorenKrita/simul-subtitle"
        const val LATEST = "$REPO/releases/latest/download/update.json"
        const val REMIND_INTERVAL = 24 * 60 * 60 * 1000L
        const val MAX_MANIFEST = 128 * 1024
        const val MAX_NOTES = 8_000 // characters shown in the dialog
        // Same order as ModelStore.GH_PROXIES. Never send api.github.com through these download proxies.
        fun sources(url: String) = listOf(url, "https://gh-proxy.com/$url", "https://gh.llkk.cc/$url")

        /**
         * An automatic check when the app is entered: [auto] on, nothing unfinished ([pending]: an update found,
         * downloading or downloaded, which the app shows instead), and not a start that only follows a rotation or
         * theme change ([configRecreated]).
         */
        fun checksOnEntry(auto: Boolean, pending: Boolean, configRecreated: Boolean) = auto && !pending && !configRecreated


        fun parse(text: String): UpdateRelease {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST)
            val j = JSONObject(text)
            val code = j.get("versionCode").toString().toInt()
            val name = j.getString("versionName")
            val size = j.get("size").toString().toLong()
            val hash = j.getString("sha256").lowercase()
            val url = j.getString("apkUrl")
            require(code > 0 && name.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")))
            require(size in 1..200_000_000 && hash.matches(Regex("[0-9a-f]{64}")))
            // A proxy-supplied manifest cannot point the downloader at an arbitrary site or file.
            require(url == "$REPO/releases/download/v$name/simul-subtitle-v$name.apk")
            return UpdateRelease(code, name, j.getString("notes"), url, size, hash)
        }

        // Notes come from the network (possibly a proxy): cap the length and keep the link patterns bounded and
        // free of nested brackets, so no input makes the regexes backtrack for seconds on the main thread.
        fun readableNotes(markdown: String): String = markdown.take(MAX_NOTES)
            .replace(Regex("!\\[([^\\[\\]\\n]{0,200})]\\([^()\\n]{0,500}\\)"), "$1")
            .replace(Regex("\\[([^\\[\\]\\n]{1,200})]\\([^()\\n]{0,500}\\)"), "$1")
            .replace(Regex("(?m)^[\\t ]{0,3}#{1,6}[\\t ]+"), "")
            .replace(Regex("(?m)^[\\t ]*[-*+][\\t ]+"), "• ")
            .replace("**", "").replace("__", "").replace("`", "")
            .trim().ifBlank { "这个版本暂时没有填写更新内容。" }
    }
}

/** Activity-owned work: no service, notification, or model-download changes. Network and hashing stay off the UI. */
internal class AppUpdater(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("updates", Activity.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val autoSwitch = activity.findViewById<MaterialSwitch>(R.id.autoUpdate)
    private val checkButton = activity.findViewById<MaterialButton>(R.id.checkUpdate)
    private val apk = File(activity.cacheDir, "updates/update.apk")
    private var job: Job? = null
    @Volatile private var call: Call? = null
    private var dialog: AlertDialog? = null
    private var resumed = false
    private var deferred: (() -> Unit)? = null
    private var waitingForPermission = false
    private val auto get() = prefs.getBoolean("auto", true)

    init {
        autoSwitch.isChecked = auto
        autoSwitch.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("auto", on).apply() }
        checkButton.setOnClickListener { check(manual = true) }
    }

    /** the running update check, apart from a download / install (both use [job]); [checkManual]: its kind */
    private var checkJob: Job? = null
    private var checkManual = false
    /** an update check is running (a recreation cancels it: the new activity checks again) */
    val checking get() = checkJob?.isActive == true
    /** ... and it is a manual one, which the new activity redoes as manual (skip / remind-later / off ignored) */
    val checkingManual get() = checking && checkManual

    /**
     * Each entry into the app checks once ([configRecreated]: this start only follows a rotation or theme change).
     * [manualInterrupted]: a recreation cancelled a manual check; it is redone as one, whatever the settings.
     */
    fun start(configRecreated: Boolean, manualInterrupted: Boolean) {
        // something to show or answer first (a result kept for the foreground, an open dialog): no check over it,
        // not even a redone manual one (the saved result is what that check found)
        val pending = prefs.contains("pendingInstall") || prefs.contains("pendingDownload") || prefs.contains("pendingOffer") ||
            deferred != null || dialog?.isShowing == true
        if (manualInterrupted && !pending) check(manual = true)
        else if (UpdateRelease.checksOnEntry(auto, pending, configRecreated)) check(manual = false)
    }

    fun resume() {
        resumed = true
        val action = deferred
        deferred = null
        if (action != null) { action(); return }
        if (job?.isActive == true || dialog?.isShowing == true) return
        val offered = prefs.getString("pendingOffer", null)
        if (offered != null) {
            val release = runCatching { UpdateRelease.parse(offered) }.getOrNull()
            if (release != null && shouldOffer(release, prefs.getBoolean("pendingOfferManual", false))) offer(release)
            else prefs.edit().remove("pendingOffer").apply()
            return
        }
        val interrupted = prefs.getString("pendingDownload", null)
        if (interrupted != null && !prefs.contains("pendingInstall")) {
            val release = runCatching { UpdateRelease.parse(interrupted) }.getOrNull()
            if (release != null && release.isNewer(BuildConfig.VERSION_CODE)) {
                // kept until answered: a rotation or the process ending while this shows brings it back
                error("刚才的下载中断了", "请点「重试」重新下载，并保持这个页面打开。",
                    later = { prefs.edit().remove("pendingDownload").apply() }) { download(release) }
            } else prefs.edit().remove("pendingDownload").apply()
            return
        }
        val pending = prefs.getString("pendingInstall", null) ?: return
        val release = runCatching { UpdateRelease.parse(pending) }.getOrNull()
        if (release == null || !release.isNewer(BuildConfig.VERSION_CODE)) {
            prefs.edit().remove("pendingInstall").apply()
            return
        }
        if (waitingForPermission) {
            waitingForPermission = false
            if (!activity.packageManager.canRequestPackageInstalls()) {
                error("还没有允许安装", "刚才没有开启安装权限。点「重试」，按提示开启后就能继续。") { install(release) }
                return
            }
        }
        install(release)
    }

    fun pause() { resumed = false }

    fun destroy() {
        scope.cancel()
        call?.cancel()
        dialog?.dismiss()
        deferred = null
    }

    private fun foreground(action: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (resumed) action() else deferred = action
    }

    private fun shouldOffer(release: UpdateRelease, manual: Boolean) =
        release.shouldOffer(BuildConfig.VERSION_CODE, manual, auto, prefs.getInt("skipped", 0),
            prefs.getInt("laterCode", 0), prefs.getLong("laterUntil", 0), System.currentTimeMillis())

    private fun check(manual: Boolean) {
        if (job?.isActive == true) return
        checkButton.isEnabled = false
        checkButton.text = "正在检查更新…"
        job = scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchRelease() }
                if (shouldOffer(release, manual)) {
                    // Save before deferring UI: a result received in the background must also survive recreation.
                    prefs.edit().putString("pendingOffer", release.json()).putBoolean("pendingOfferManual", manual).apply()
                    foreground {
                        // an automatic result does not replace a dialog the user is answering; it stays saved for the
                        // next return to the page
                        if (!manual && dialog?.isShowing == true) Unit
                        else if (shouldOffer(release, manual)) offer(release)
                        else prefs.edit().remove("pendingOffer").apply()
                    }
                } else if (manual) foreground {
                    Toast.makeText(activity, "已是最新版本（${BuildConfig.VERSION_NAME}）", Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (manual) foreground {
                    error("暂时没查到新版本", "网络没有连上，或更新信息暂时还没准备好。请稍后重试。") { check(true) }
                }
            } finally {
                checkButton.isEnabled = true
                checkButton.text = "检查更新"
            }
        }
        checkJob = job; checkManual = manual
    }

    private suspend fun fetchRelease(): UpdateRelease {
        for (url in UpdateRelease.sources(UpdateRelease.LATEST)) {
            currentCoroutineContext().ensureActive()
            try {
                return request(url, CHECK_CLIENT) { response ->
                    val body = response.body ?: throw IOException("Empty manifest")
                    val bytes = body.source().let { source ->
                        source.request(UpdateRelease.MAX_MANIFEST + 1L)
                        source.buffer.readByteArray()
                    }
                    UpdateRelease.parse(bytes.toString(Charsets.UTF_8))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { currentCoroutineContext().ensureActive() }
        }
        throw IOException("All update sources failed")
    }

    private fun offer(release: UpdateRelease) {
        // The saved pendingOffer is an unfinished choice, not a fresh check after rotation/process recreation.
        val box = column()
        box.addView(text("当前版本 ${BuildConfig.VERSION_NAME}，新版本 ${release.versionName}\n\n${UpdateRelease.readableNotes(release.notes)}"))
        // A single scroll container keeps both long notes and all four large actions reachable at large font sizes.
        val alert = MaterialAlertDialogBuilder(activity).setTitle("有新版本了")
            .setView(ScrollView(activity).apply { addView(box) }).create()
        fun action(label: String, run: () -> Unit) {
            box.addView(MaterialButton(activity).apply {
                text = label; textSize = 17f; minHeight = dp(56); isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { prefs.edit().remove("pendingOffer").apply(); alert.dismiss(); run() }
            })
        }
        action("立即更新") { download(release) }
        action("稍后提醒") { remindLater(release) }
        action("跳过本次更新") { prefs.edit().putInt("skipped", release.versionCode).apply() }
        action("永不检查") { autoSwitch.isChecked = false }
        alert.setOnCancelListener { remindLater(release) }
        show(alert)
    }

    private fun remindLater(release: UpdateRelease) {
        prefs.edit().remove("pendingOffer").putInt("laterCode", release.versionCode)
            .putLong("laterUntil", System.currentTimeMillis() + UpdateRelease.REMIND_INTERVAL).apply()
    }

    private fun download(release: UpdateRelease) {
        // the user's download wins over an automatic check still running (its result would only offer this again)
        if (checking && !checkManual) job?.cancel()
        else if (job?.isActive == true) return
        prefs.edit().remove("pendingInstall").putString("pendingDownload", release.json()).apply()
        val box = column()
        val label = text("正在准备下载…\n请保持这个页面打开。")
        val bar = LinearProgressIndicator(activity).apply { max = 100; isIndeterminate = false }
        box.addView(label); box.addView(bar)
        val alert = MaterialAlertDialogBuilder(activity).setTitle("正在下载 ${release.versionName}")
            .setView(box).setCancelable(false).setNegativeButton("取消下载") { _, _ ->
                prefs.edit().remove("pendingDownload").apply()
                job?.cancel(); call?.cancel()
            }.create()
        show(alert)
        checkButton.isEnabled = false
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!apk.parentFile!!.isDirectory && !apk.parentFile!!.mkdirs()) throw IOException("No cache directory")
                    var downloaded = false
                    for ((index, url) in UpdateRelease.sources(release.apkUrl).withIndex()) {
                        currentCoroutineContext().ensureActive()
                        withContext(Dispatchers.Main) {
                            label.text = if (index == 0) "正在下载…\n请保持这个页面打开。" else "正在换一个地址下载…\n请保持这个页面打开。"
                            bar.progress = 0
                        }
                        try {
                            request(url, DOWNLOAD_CLIENT) { response ->
                                val body = response.body ?: throw IOException("Empty APK")
                                if (body.contentLength() >= 0 && body.contentLength() != release.size) throw IOException("Wrong size")
                                body.byteStream().use { input ->
                                    apk.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        var count = 0L
                                        var percent = -1
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            count += n
                                            if (count > release.size) throw IOException("APK exceeds expected size")
                                            output.write(buffer, 0, n)
                                            val next = (count * 100 / release.size).toInt()
                                            if (next != percent) {
                                                percent = next
                                                withContext(Dispatchers.Main) { bar.progress = next; label.text = "已下载 $next%\n请保持这个页面打开。" }
                                            }
                                        }
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) { label.text = "下载好了，正在检查安装包…" }
                            verify(release)
                            downloaded = true
                            break
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { currentCoroutineContext().ensureActive() }
                    }
                    if (!downloaded) throw IOException("All APK sources failed")
                }
                // withContext delivers cancellation before returning to Main. Publish on Main, serialized with
                // the cancel button, so cancelling during synchronous verify() can never resurrect an install.
                prefs.edit().remove("pendingDownload").putString("pendingInstall", release.json()).apply()
                alert.dismiss()
                foreground { install(release) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // pendingDownload stays until answered: a rotation or the process ending first brings the retry back
                alert.dismiss()
                foreground {
                    error("这次没有下载好", "网络不通、手机空间不足，或安装包不完整。请检查网络和剩余空间后重试。",
                        later = { prefs.edit().remove("pendingDownload").apply() }) { download(release) }
                }
            } finally { checkButton.isEnabled = true }
        }
    }

    @Suppress("DEPRECATION") // minSdk 29; the typed flags overload requires API 33.
    private fun verify(release: UpdateRelease) {
        if (!apk.isFile || apk.length() != release.size || ModelStore.sha256(apk) != release.sha256) throw IOException("APK checksum mismatch")
        val pm = activity.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: throw IOException("Invalid APK")
        val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val expectedSigners = signers(installed)
        if (archive.packageName != activity.packageName || archive.longVersionCode != release.versionCode.toLong() ||
            archive.versionName != release.versionName || expectedSigners.isNullOrEmpty() || signers(archive) != expectedSigners) {
            throw IOException("APK identity mismatch")
        }
    }

    private fun install(release: UpdateRelease) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            show(MaterialAlertDialogBuilder(activity).setTitle("还差一步就能更新")
                .setMessage("请在接下来的页面里，开启「允许来自此来源的应用」，然后返回这里继续安装。")
                .setNegativeButton("稍后安装") { _, _ -> prefs.edit().remove("pendingInstall").apply() }
                .setPositiveButton("去允许") { _, _ ->
                    try {
                        waitingForPermission = true
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    } catch (_: Exception) {
                        waitingForPermission = false
                        error("没有打开安装设置", "请到手机设置里找到「同传字幕」，允许安装未知应用，再回来重试。") { install(release) }
                    }
                }.create())
            return
        }
        // Recheck after a settings round-trip (or process recreation); the cache could have been evicted.
        checkButton.isEnabled = false
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { verify(release) }
                foreground {
                    try {
                        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        prefs.edit().remove("pendingInstall").apply()
                    } catch (_: Exception) {
                        error("没有打开安装页面", "请确认手机允许「同传字幕」安装应用，然后重试。") { install(release) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                prefs.edit().remove("pendingInstall").apply()
                foreground { error("安装包需要重新下载", "手机清理了安装包，或文件检查没有通过。请重新下载。") { download(release) } }
            } finally { checkButton.isEnabled = true }
        }
    }

    private suspend fun <T> request(url: String, client: OkHttpClient, read: suspend (okhttp3.Response) -> T): T {
        currentCoroutineContext().ensureActive()
        val next = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
        call = next
        try {
            currentCoroutineContext().ensureActive()
            return next.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                read(response)
            }
        } finally { call = null }
    }

    private fun error(title: String, message: String, later: () -> Unit = {}, retry: () -> Unit) = show(
        MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message)
            .setNegativeButton("稍后再说") { _, _ -> later() }.setPositiveButton("重试") { _, _ -> retry() }
            .setOnCancelListener { later() }.create())

    private fun show(alert: AlertDialog) {
        dialog?.dismiss()
        dialog = alert
        alert.show()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(16)) }
    private fun text(value: String) = TextView(activity).apply {
        text = value; textSize = 17f; setTextColor(activity.getColor(R.color.text)); setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(0, 0, 0, dp(16))
    }

    companion object {
        private val CHECK_CLIENT = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .followSslRedirects(false).retryOnConnectionFailure(false).build()
        private val DOWNLOAD_CLIENT = CHECK_CLIENT.newBuilder().readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES).build()
    }
}
