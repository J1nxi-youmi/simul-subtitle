package dev.krita.jpsub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore
    private lateinit var dl: ModelDownloader
    private lateinit var preview: CaptionOverlayView

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton
    private lateinit var permList: LinearLayout

    /** a verification of finished downloads is running on [verifier] */
    @Volatile private var verifying: String? = null
    private var verifyError: String? = null
    private var verifier: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_JpSub)
        setContentView(R.layout.activity_main)
        // targetSdk 35 draws edge-to-edge: pad the scroll content by the system bars instead of a black status bar
        val scroll = findViewById<View>(R.id.scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION") // API 29 has no WindowInsets.Type; these still work on 30+
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
        }
        prefs = Prefs(this)
        store = ModelStore(getExternalFilesDir(null)!!)
        dl = ModelDownloader(this, store, prefs)

        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusBody = findViewById(R.id.statusBody)
        progress = findViewById(R.id.progress)
        primary = findViewById(R.id.primary)
        secondary = findViewById(R.id.secondary)
        permList = findViewById(R.id.permList)
        setupStyle()
    }

    override fun onResume() {
        super.onResume()
        render()
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 800) }
    }

    // ---- main card -----------------------------------------------------------------------------------------

    private fun render() {
        renderPermissions()
        if (!store.ready()) { renderModels(); return }
        progress.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.isEnabled = true
        if (CaptionService.running) {
            status(R.color.ok, "字幕运行中", "回到要翻译的 App 即可。点一下字幕可调整字号、透明度或停止。")
            primary.text = "停止字幕"; primary.setIconResource(R.drawable.ic_stop)
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.err))
            primary.setOnClickListener { CaptionService.stop(this); main.postDelayed({ render() }, 300) }
        } else {
            val missingPerm = !hasAudio() || !Settings.canDrawOverlays(this)
            status(R.color.brand, "准备就绪", if (missingPerm) "开始前需要授予下方的权限，点开始会依次引导。" else "点开始，然后在系统弹窗里选择要翻译的 App。")
            primary.text = "开始字幕"; primary.setIconResource(R.drawable.ic_play)
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand))
            primary.setOnClickListener { start() }
        }
    }

    private fun renderModels() {
        val st = dl.state()
        if (st.toVerify.isNotEmpty() && verifier?.isAlive != true) verify(st.toVerify)
        val gb = { b: Long -> "%.2f GB".format(b / 1e9) }
        progress.visibility = if (st.running || verifying != null || st.doneBytes > 0) View.VISIBLE else View.GONE
        progress.setProgressCompat((st.doneBytes * 1000 / st.totalBytes).toInt(), true)
        primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand))
        primary.setIconResource(R.drawable.ic_download)
        when {
            verifying != null -> {
                status(R.color.brand, "正在校验模型…", "${verifying}（确保文件完整，不会用坏文件）")
                primary.text = "校验中…"; busy()
                secondary.visibility = View.GONE
            }
            st.running -> {
                status(R.color.brand, "正在下载模型", "${gb(st.doneBytes)} / ${gb(st.totalBytes)} · 可以切到后台，系统会继续下载。")
                primary.text = "下载中 ${st.doneBytes * 100 / st.totalBytes}%"; busy()
                secondary.visibility = View.VISIBLE; secondary.text = "取消下载"
                secondary.setOnClickListener { dl.cancel(); render() }
            }
            st.failed.isNotEmpty() || verifyError != null -> {
                status(R.color.err, "下载未完成", (st.failed + listOfNotNull(verifyError)).joinToString("\n"))
                primary.text = "重试"; primary.isEnabled = true
                primary.setOnClickListener { verifyError = null; dl.start(); render() }
                secondary.visibility = View.GONE
            }
            else -> {
                val left = store.totalBytes() - store.installedBytes()
                status(R.color.warn, "需要下载识别和翻译模型",
                    "一次性下载约 ${gb(left)}，之后完全在手机本地运行、无需联网。\n来源：huggingface.co、github.com（建议连 Wi‑Fi）。")
                primary.text = "下载模型"; primary.isEnabled = true
                primary.setOnClickListener { dl.start(); render() }
                secondary.visibility = View.GONE
            }
        }
    }

    /** Busy state: no action, but keep the brand look (a disabled button is grey and hard to read). */
    private fun busy() {
        primary.isEnabled = true
        primary.setOnClickListener(null)
        primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand) and 0x00FFFFFF or 0xB3000000.toInt())
    }

    /** Hash-checks finished downloads off the main thread; only verified files are published. */
    private fun verify(specs: List<ModelSpec>) {
        verifier = Thread({
            for (s in specs) {
                verifying = s.path.substringAfterLast('/')
                val r = store.publish(s)
                dl.forget(s)
                if (r !is ModelStore.Result.Ok) verifyError = "${verifying} 校验失败（${r}），请重试"
            }
            verifying = null
            main.post { render() }
        }, "jpsub-verify").apply { start() }
    }

    private fun status(color: Int, title: String, body: String) {
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(color)) }
        statusTitle.text = title
        statusBody.text = body
    }

    // ---- permissions ---------------------------------------------------------------------------------------

    private fun hasAudio() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun renderPermissions() {
        permList.removeAllViews()
        permRow("捕获 App 声音", "只录制被共享 App 的播放声音，不使用麦克风", hasAudio()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        permRow("悬浮窗", "在其他 App 上方显示字幕", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permRow("通知（可选）", "显示运行状态和下载进度", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            }
        }
    }

    private fun permRow(title: String, sub: String, granted: Boolean, grant: () -> Unit) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.text)) })
        texts.addView(TextView(this).apply { text = sub; textSize = 12.5f; setTextColor(getColor(R.color.text2)) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (granted) {
            row.addView(TextView(this).apply { text = "已开启"; textSize = 13f; setTextColor(getColor(R.color.ok)) })
        } else {
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "去开启"; setOnClickListener { grant() }
            })
        }
        permList.addView(row)
    }

    // ---- subtitle style ------------------------------------------------------------------------------------

    private fun setupStyle() {
        preview = CaptionOverlayView(this, interactive = false)
        findViewById<FrameLayout>(R.id.previewHolder).addView(preview)
        val size = findViewById<Slider>(R.id.sliderSize)
        val alpha = findViewById<Slider>(R.id.sliderAlpha)
        val width = findViewById<Slider>(R.id.sliderWidth)
        val ja = findViewById<MaterialSwitch>(R.id.switchJa)
        val stats = findViewById<MaterialSwitch>(R.id.switchStats)
        val lSize = findViewById<TextView>(R.id.labelSize)
        val lAlpha = findViewById<TextView>(R.id.labelAlpha)
        val lWidth = findViewById<TextView>(R.id.labelWidth)

        fun load() {
            size.value = prefs.textSp.coerceIn(12f, 36f).let { Math.round(it).toFloat() }
            alpha.value = (Math.round(prefs.bgAlpha * 20) * 5f).coerceIn(0f, 100f)
            width.value = (Math.round(prefs.widthFrac * 20) * 5f).coerceIn(40f, 100f)
            ja.isChecked = prefs.showJa
            stats.isChecked = prefs.showStats
        }
        fun apply() {
            lSize.text = "字号  ${size.value.toInt()}"
            lAlpha.text = "背景不透明度  ${alpha.value.toInt()}%"
            lWidth.text = "字幕宽度  ${width.value.toInt()}%"
            preview.applyStyle(size.value, alpha.value / 100f)
            preview.layoutParams = FrameLayout.LayoutParams((findViewById<View>(R.id.previewStage).width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 0.8f).toInt()).let { (it * width.value / 100f).toInt() }, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            preview.setLines(
                listOf(CaptionOverlayView.Line("明天的会议改到下午三点了。", if (ja.isChecked) "明日の会議は午後三時に変更になりました" else null, true),
                    CaptionOverlayView.Line("资料我今天之内发给你…", if (ja.isChecked) "資料は今日中に送ります" else null, false)),
                if (stats.isChecked) "识别 52ms · 翻译 310ms · 延迟 0.7s" else null,
            )
        }
        load(); apply()
        findViewById<View>(R.id.previewStage).post { apply() } // the stage's real width is known after layout
        size.addOnChangeListener { _, v, user -> if (user) { prefs.textSp = v; apply() } }
        alpha.addOnChangeListener { _, v, user -> if (user) { prefs.bgAlpha = v / 100f; apply() } }
        width.addOnChangeListener { _, v, user -> if (user) { prefs.widthFrac = v / 100f; apply() } }
        ja.setOnCheckedChangeListener { _, v -> prefs.showJa = v; apply() }
        stats.setOnCheckedChangeListener { _, v -> prefs.showStats = v; apply() }
        findViewById<MaterialButton>(R.id.resetStyle).setOnClickListener {
            prefs.textSp = 20f; prefs.bgAlpha = 0.55f; prefs.widthFrac = 0.92f; prefs.showJa = false; prefs.showStats = false
            prefs.overlayX = -1; prefs.overlayY = -1
            load(); apply()
        }
    }

    // ---- start ---------------------------------------------------------------------------------------------

    private fun start() {
        if (!hasAudio()) {
            val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS // optional
            requestPermissions(ask.toTypedArray(), 1)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
        if (requestCode == 1 && hasAudio()) start()
        else if (requestCode == 1) status(R.color.err, "需要录音权限", "只用于捕获被共享 App 的声音，不会使用麦克风。")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 2 && resultCode == RESULT_OK && data != null) {
            CaptionService.start(this, resultCode, data)
            moveTaskToBack(true)
        }
    }
}
