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
import android.widget.Toast
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

    /** Re-reads the style prefs into the sliders and preview; set up in [setupStyle]. */
    private var loadStyle: () -> Unit = {}
    private var modeGroup: com.google.android.material.button.MaterialButtonToggleGroup? = null
    /** While visible: the overlay toolbar is not focusable, so using it does not pause/resume this screen. */
    private val styleListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in STYLE_KEYS) loadStyle()
        // the overlay toolbar flipped the mode: keep the home screen's choice in sync
        if (key == "finalOnly") syncMode()
    }

    private fun syncMode() = modeGroup?.let { g ->
        (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it.tag == prefs.finalOnly }?.let { if (g.checkedButtonId != it.id) g.check(it.id) }
    }

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
        dl = ModelDownloader.get(this)
        store = dl.store

        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusBody = findViewById(R.id.statusBody)
        progress = findViewById(R.id.progress)
        primary = findViewById(R.id.primary)
        secondary = findViewById(R.id.secondary)
        permList = findViewById(R.id.permList)
        setupStyle()
        renderTranslate()
    }

    override fun onResume() {
        super.onResume()
        loadStyle() // the overlay toolbar may have changed size / opacity / mode meanwhile (no listener while paused)
        syncMode()
        prefs.listen(styleListener)
        renderPermissions()
        renderLogs()
        render()
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        prefs.unlisten(styleListener)
        main.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            // the translation switches are disabled while a session runs
            if (CaptionService.running != translateRunning) renderTranslate()
            // a session started or finished (its .part renamed): the list must not keep buttons for files that moved
            val now = SessionLog.list(getExternalFilesDir(null)!!).map { it.name } to SessionLog.activeFiles()
            if (now != listedLogs) renderLogs()
            main.postDelayed(this, 800)
        }
    }

    // ---- main card -----------------------------------------------------------------------------------------

    private fun render() {
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
            val hint = if (missingPerm) "开始前需要授予下方的权限，点开始会依次引导。" else "点开始，然后在系统弹窗里选择要翻译的 App。"
            status(R.color.brand, "准备就绪", dl.maintenanceWarning?.let { "$hint\n⚠ $it（占用存储空间，可在系统设置里清除本 App 的缓存/数据后重新下载）" } ?: hint)
            primary.text = "开始字幕"; primary.setIconResource(R.drawable.ic_play)
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand))
            primary.setOnClickListener { start() }
        }
    }

    private fun renderModels() {
        val st = dl.state()
        val verifying = st.verifying
        val gb = { b: Long -> "%.2f GB".format(b / 1e9) }
        progress.visibility = if (st.running || st.doneBytes > 0) View.VISIBLE else View.GONE
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
            st.errors.isNotEmpty() -> {
                status(R.color.err, "下载未完成", st.errors.joinToString("\n"))
                primary.text = "重试"; primary.isEnabled = true
                primary.setOnClickListener { dl.start(); render() }
                secondary.visibility = View.GONE
            }
            else -> {
                val left = store.totalBytes() - store.installedBytes()
                if (prefs.mtOnline) status(R.color.warn, "需要下载识别模型",
                    "一次性下载约 ${gb(left)}，语音识别在手机本地运行，翻译用在线接口。\n来源：huggingface.co、github.com。")
                else status(R.color.warn, "需要下载识别和翻译模型",
                    "一次性下载约 ${gb(left)}，之后翻译也在手机本地运行（Jev 仍需联网）。\n来源：huggingface.co、github.com（建议连 Wi‑Fi）。")
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
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_ONLY)
        }
        permRow("悬浮窗", "在其他 App 上方显示字幕", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permRow("通知（可选）", "显示运行状态和下载进度", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM_ONLY)
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

    // ---- translation / Jev ---------------------------------------------------------------------------------

    private fun label(t: String, top: Int = 14) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(getColor(R.color.text2))
        setPadding(0, (top * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
    }

    /** Single-choice row of segmented buttons. */
    private fun <T> choice(options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) =
        com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            options.forEach { (v, t) ->
                val b = MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId(); text = t; tag = v
                }
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (v == current) check(b.id)
            }
            addOnButtonCheckedListener { g, id, checked ->
                @Suppress("UNCHECKED_CAST") if (checked) onPick(g.findViewById<View>(id).tag as T)
            }
        }

    /** Engine, scene, display mode and Jev. Engine, scene and Jev apply to the next start; the mode applies immediately. */
    private fun renderTranslate() {
        val box = findViewById<LinearLayout>(R.id.translateList)
        box.removeAllViews()
        box.addView(label("翻译（下次开始时生效）", top = 0))
        val running = CaptionService.running
        translateRunning = running
        val engine = choice(listOf(true to "在线 Index", false to "本地 2B"), prefs.mtOnline) {
            if (prefs.mtOnline != it) { prefs.mtOnline = it; renderTranslate(); render() } // local needs its model file
        }
        box.addView(engine)
        if (prefs.mtOnline) {
            val models = android.widget.Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    OnlineModel.entries.map { it.label })
                setSelection(prefs.onlineModel.ordinal, false)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (prefs.onlineModel.ordinal != pos) prefs.onlineModel = OnlineModel.entries[pos]
                    }
                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
                isEnabled = !running
            }
            box.addView(models, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * resources.displayMetrics.density).toInt()
            })
        }
        box.addView(TextView(this).apply {
            text = (if (prefs.mtOnline)
                "B站免费公网接口：识别出的日文和场景说明会发到 index-translate.bilibili.com（声音不上传），需联网，断网或接口出错时这句显示「翻译失败」，不会自动换本地。推荐 35B；9B/2B 是备选。"
            else "在手机上翻译，需要先下载约 1.3 GB 模型；比在线慢很多，长句可能积压。") +
                if (running) "\n运行中不能切换，停止后再改。" else ""
            textSize = 12.5f; setTextColor(getColor(R.color.text2))
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        if (running) for (i in 0 until engine.childCount) engine.getChildAt(i).isEnabled = false
        box.addView(label("场景（下次开始时生效）"))
        box.addView(choice(Scene.entries.map { it to it.label }, prefs.scene) { prefs.scene = it })
        box.addView(label("显示方式（运行中也可在悬浮窗工具栏切换）"))
        modeGroup = choice(listOf(false to "边出边改", true to "只看定稿"), prefs.finalOnly) { if (prefs.finalOnly != it) prefs.finalOnly = it }
        box.addView(modeGroup)

        val jev = MaterialSwitch(this).apply {
            text = "用 Jev 判断断句和检查译文（云端，需联网）"; textSize = 15f; setTextColor(getColor(R.color.text))
            isChecked = prefs.jevOn
        }
        box.addView(jev, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (10 * resources.displayMetrics.density).toInt()
        })
        val jevBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(jevBox)
        fun renderJev() {
            jevBox.removeAllViews()
            jevBox.visibility = if (prefs.jevOn) View.VISIBLE else View.GONE
            if (!prefs.jevOn) return
            val p = prefs.jevProvider
            jevBox.addView(label("接口"))
            jevBox.addView(choice(JevProvider.entries.map { it to it.short }, p) { prefs.jevProvider = it; main.post { renderJev() } })
            if (!p.needsKey) {
                jevBox.addView(TextView(this).apply {
                    text = "不用填 key。只把识别出的日语和译文发给 OpenCode Zen，不发送声音。这是非公开的免费接口，随时可能限速或停用；不可用时这句按没开 Jev 处理，可以换下面两个付费接口。"
                    textSize = 12.5f; setTextColor(getColor(R.color.text2))
                })
                return
            }
            val saved = prefs.jevKey(p)
            jevBox.addView(label(if (saved != null) "${p.label} API key：已保存（…${saved.takeLast(4)}）" else "${p.label} API key：未填写"))
            val input = android.widget.EditText(this).apply {
                hint = if (saved != null) "输入新 key 以替换" else "粘贴 API key"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true; textSize = 14f; setTextColor(getColor(R.color.text))
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            jevBox.addView(input)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "清除"; isEnabled = saved != null
                setOnClickListener { prefs.setJevKey(p, null); renderJev() }
            })
            row.addView(MaterialButton(this).apply {
                text = "保存"
                setOnClickListener {
                    val k = input.text.toString().trim()
                    when {
                        k.isEmpty() -> {}
                        // a key with spaces/newlines/non-ASCII would be rejected by HTTP (and echoed in its error)
                        !Jev.validKey(k) -> Toast.makeText(this@MainActivity, "key 里有空格、换行或非 ASCII 字符，请重新复制", Toast.LENGTH_LONG).show()
                        else -> { prefs.setJevKey(p, k); input.setText(""); renderJev() }
                    }
                }
            })
            jevBox.addView(row)
            jevBox.addView(TextView(this).apply {
                text = "只把识别出的日语和译文发给 ${p.label}，不发送声音。key 用 Android Keystore 加密保存在本机，不会写进使用记录。"
                textSize = 12.5f; setTextColor(getColor(R.color.text2))
            })
        }
        jev.setOnCheckedChangeListener { _, on -> prefs.jevOn = on; renderJev() }
        renderJev()
    }

    // ---- session logs --------------------------------------------------------------------------------------

    private fun renderLogs() {
        val box = findViewById<LinearLayout>(R.id.logList)
        box.removeAllViews()
        val files = SessionLog.list(getExternalFilesDir(null)!!)
        val active = SessionLog.activeFiles()
        listedLogs = files.map { it.name } to active
        if (files.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "还没有记录。每次开始字幕都会生成一个文件。"; textSize = 13.5f; setTextColor(getColor(R.color.text2))
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
            })
            return
        }
        val shown = if (showAllLogs) files else files.take(MAX_LOGS_SHOWN)
        for (f in shown) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // session-20261010-151200[-1].jsonl[.part] → 2026-10-10 15:12:00
            val n = f.name.removePrefix("session-").substringBefore(".jsonl")
            val title = if (n.length >= 15) "${n.substring(0, 4)}-${n.substring(4, 6)}-${n.substring(6, 8)} ${n.substring(9, 11)}:${n.substring(11, 13)}:${n.substring(13, 15)}${n.substring(15)}" else f.name
            texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.text)) })
            val complete = SessionLog.complete(f)
            texts.addView(TextView(this).apply {
                // .part: still running, or the session failed / was killed before the log was finished
                text = "%.1f KB".format(f.length() / 1024.0) + when {
                    complete -> ""
                    f in active -> " · 正在记录，结束后可分享"
                    else -> " · 未完成（中断）"
                }
                textSize = 12.5f; setTextColor(getColor(R.color.text2))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "分享"; isEnabled = f !in active; setOnClickListener { share(f) }
            })
            box.addView(row)
        }
        if (files.size > MAX_LOGS_SHOWN) box.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = if (showAllLogs) "只看最近 $MAX_LOGS_SHOWN 个" else "查看全部（共 ${files.size} 个）"
            setOnClickListener { showAllLogs = !showAllLogs; renderLogs() }
        })
    }
    private var showAllLogs = false
    private var listedLogs: Pair<List<String>, Set<java.io.File>>? = null
    private var translateRunning = false

    private fun share(f: java.io.File) {
        // the list may be up to one tick old: never hand out a URI for a file that is being written or no longer exists
        if (SessionLog.isActive(f) || !f.isFile) {
            Toast.makeText(this, "这个记录刚刚结束或已变动，请重新选择", Toast.LENGTH_SHORT).show(); renderLogs(); return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.logs", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream" // TG and file managers keep .jsonl as a file instead of pasting text
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "分享 ${f.name}"))
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
        val keepS = findViewById<Slider>(R.id.sliderKeepSentences)
        val keepC = findViewById<Slider>(R.id.sliderKeepChars)
        val lKeepS = findViewById<TextView>(R.id.labelKeepSentences)
        val lKeepC = findViewById<TextView>(R.id.labelKeepChars)
        val keepHint = findViewById<TextView>(R.id.keepHint)

        var loading = false // programmatic updates must not be written back
        fun load() {
            loading = true
            size.value = prefs.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP).let { Math.round(it).toFloat() }
            alpha.value = (Math.round(prefs.bgAlpha * 20) * 5f).coerceIn(0f, 100f)
            width.value = (Math.round(prefs.widthFrac * 20) * 5f).coerceIn(40f, 100f)
            ja.isChecked = prefs.showJa
            stats.isChecked = prefs.showStats
            keepS.value = prefs.keepSentences.coerceIn(1, 6).toFloat()
            keepC.value = (Math.round(prefs.keepChars / 10f) * 10f).coerceIn(0f, 300f)
            loading = false
        }
        fun apply() {
            lSize.text = "字号  ${size.value.toInt()}"
            lAlpha.text = "背景不透明度  ${alpha.value.toInt()}%"
            lWidth.text = "字幕宽度  ${width.value.toInt()}%"
            lKeepS.text = "至少保留  ${keepS.value.toInt()} 句"
            lKeepC.text = "超过  ${keepC.value.toInt()} 字后移除旧句"
            keepHint.text = "已说完的句子超过 ${keepS.value.toInt()} 句，并且总字数超过 ${keepC.value.toInt()} 字时，才移走最早的一句（正在说的那句不算）。窗口放不下时只显示最新的部分。"
            preview.applyStyle(size.value, alpha.value / 100f)
            preview.layoutParams = FrameLayout.LayoutParams((findViewById<View>(R.id.previewStage).width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 0.8f).toInt()).let { (it * width.value / 100f).toInt() }, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            preview.setLines(
                listOf(CaptionOverlayView.Line("明天的会议改到下午三点了。", if (ja.isChecked) "明日の会議は午後三時に変更になりました" else null, true),
                    CaptionOverlayView.Line("资料我今天之内发给你…", if (ja.isChecked) "資料は今日中に送ります" else null, false)),
                if (stats.isChecked) "识别 52ms · 翻译 310ms · 延迟 0.7s" else null,
            )
        }
        loadStyle = { load(); apply() }
        load(); apply()
        findViewById<View>(R.id.previewStage).post { apply() } // the stage's real width is known after layout
        size.addOnChangeListener { _, v, user -> if (user) { prefs.textSp = v; apply() } }
        alpha.addOnChangeListener { _, v, user -> if (user) { prefs.bgAlpha = v / 100f; apply() } }
        width.addOnChangeListener { _, v, user -> if (user) { prefs.widthFrac = v / 100f; apply() } }
        ja.setOnCheckedChangeListener { _, v -> if (!loading) { prefs.showJa = v; apply() } }
        stats.setOnCheckedChangeListener { _, v -> if (!loading) { prefs.showStats = v; apply() } }
        keepS.addOnChangeListener { _, v, user -> if (user) { prefs.keepSentences = v.toInt(); apply() } }
        keepC.addOnChangeListener { _, v, user -> if (user) { prefs.keepChars = v.toInt(); apply() } }
        findViewById<MaterialButton>(R.id.resetStyle).setOnClickListener {
            prefs.setStyle(20f, 0.55f); prefs.widthFrac = Prefs.DEFAULT_WIDTH; prefs.showJa = false; prefs.showStats = false
            prefs.keepSentences = CaptionBoard.DEFAULT_SENTENCES; prefs.keepChars = CaptionBoard.DEFAULT_CHARS
            prefs.overlayX = -1; prefs.overlayY = -1
            load(); apply()
        }
    }

    // ---- start ---------------------------------------------------------------------------------------------

    private fun start() {
        if (!store.ready()) { render(); return } // never start without verified models
        if (!hasAudio()) {
            val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS // optional
            requestPermissions(ask.toTypedArray(), REQ_START)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        renderPermissions()
        render()
        if (requestCode == REQ_START && hasAudio()) start()
        else if (requestCode == REQ_START) status(R.color.err, "需要录音权限", "只用于捕获被共享 App 的声音，不会使用麦克风。")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            CaptionService.start(this, resultCode, data)
            moveTaskToBack(true)
        }
    }

    private companion object {
        const val REQ_START = 1      // runtime permission asked on the way to starting subtitles
        const val REQ_PROJECTION = 2
        val STYLE_KEYS = setOf("textSp", "bgAlpha", "widthFrac", "showJa", "showStats", "keepSentences", "keepChars")
        const val REQ_PERM_ONLY = 3  // "去开启" in the permission list: grant only, never start
        const val MAX_LOGS_SHOWN = 20
    }
}
