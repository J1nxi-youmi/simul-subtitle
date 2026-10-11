package io.github.korenkrita.simulsub

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

    // App-chosen day / night: override the night bit before any resource is read, so values-night and the
    // DayNight theme follow it. "system" leaves the configuration alone.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        val night = when (Prefs(base).theme) {
            "light" -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            "dark" -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> return
        }
        applyOverrideConfiguration(android.content.res.Configuration().apply {
            fontScale = 0f // "unset": a fresh Configuration says 1.0, which would override the user's font size
            uiMode = (base.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
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
        findViewById<LinearLayout>(R.id.themeBox).apply {
            addView(label("这个页面用白天还是夜间颜色（不影响悬浮字幕，字幕颜色在上面选）", top = 0))
            // recreate re-reads resources with the new mode; the overlay lives in the service and is untouched
            addView(choice(listOf("system" to "跟随系统", "light" to "白天", "dark" to "夜间"), prefs.theme) {
                if (prefs.theme != it) { prefs.theme = it; recreate() }
            })
        }
        findViewById<View>(R.id.openSettings).setOnClickListener { showSettings(true) }
        findViewById<View>(R.id.closeSettings).setOnClickListener { showSettings(false) }
        findViewById<TextView>(R.id.aboutVersion).text = "${getString(R.string.app_name)}  版本 ${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.openRepo).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            catch (_: android.content.ActivityNotFoundException) { }
        }
        // a theme switch recreates the activity from the settings page: stay there
        showSettings(savedInstanceState?.getBoolean("settings") == true)
    }

    private var inSettings = false

    private fun showSettings(on: Boolean) {
        inSettings = on
        findViewById<View>(R.id.homePage).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.settingsPage).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
        if (on) {
            loadStyle() // the preview needs the stage's real width, known once the page is laid out
            findViewById<View>(R.id.previewStage).post { loadStyle() }
        } else renderLang()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("settings", inSettings)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inSettings) showSettings(false) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** The home page's two questions: what is spoken, what the captions should be in. Next start, like the settings. */
    private fun renderLang() {
        val box = findViewById<LinearLayout>(R.id.langBox)
        box.removeAllViews()
        val running = CaptionService.running
        val dp = resources.displayMetrics.density
        fun big(t: String, top: Int) = TextView(this).apply {
            text = t; textSize = 18f; setTextColor(getColor(R.color.text)); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, (top * dp).toInt(), 0, (8 * dp).toInt())
        }
        fun spinner(items: List<String>, sel: Int, onPick: (Int) -> Unit) = android.widget.Spinner(this).apply {
            adapter = object : android.widget.ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items) {
                override fun getView(p: Int, c: View?, g: android.view.ViewGroup) =
                    (super.getView(p, c, g) as TextView).apply { textSize = 20f; minHeight = (52 * dp).toInt() }
                override fun getDropDownView(p: Int, c: View?, g: android.view.ViewGroup) =
                    (super.getDropDownView(p, c, g) as TextView).apply { textSize = 19f; minHeight = (52 * dp).toInt() }
            }
            setSelection(sel, false)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            isEnabled = !running
        }
        box.addView(big("视频里说的是", 0))
        val sources = Lang.SOURCES
        box.addView(spinner(sources.map { it?.zh ?: "不确定（日语或英语）" }, sources.indexOf(prefs.sourceLang).coerceAtLeast(0)) {
            if (prefs.sourceLang != sources[it]) { prefs.sourceLang = sources[it]; renderTranslate() }
        })
        box.addView(big("字幕翻译成", 18))
        val targets = Lang.entries
        box.addView(spinner(targets.map { it.zh }, prefs.targetLang.ordinal) {
            if (prefs.targetLang != targets[it]) { prefs.targetLang = targets[it]; renderTranslate() }
        })
        box.addView(TextView(this).apply {
            text = if (running) "字幕运行中，停止后才能改语言。" else "知道是哪种语言就选上，翻译会更准。"
            textSize = 14f; setTextColor(getColor(R.color.text2))
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })
    }

    override fun onResume() {
        super.onResume()
        loadStyle() // the overlay toolbar may have changed size / opacity / mode meanwhile (no listener while paused)
        renderLang()
        syncMode()
        prefs.listen(styleListener)
        renderPermissions()
        renderLogs()
        renderModelFiles()
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
            if (CaptionService.running != translateRunning) { renderTranslate(); renderLang() }
            // a session started or finished (its .part renamed): the list must not keep buttons for files that moved
            val now = SessionLog.list(getExternalFilesDir(null)!!).map { it.name } to SessionLog.activeFiles()
            if (now != listedLogs) renderLogs()
            // a session or download started/finished, or a download published a file
            if ((store.listFiles() to modelFilesBusy()) != listedModels) renderModelFiles()
            main.postDelayed(this, 800)
        }
    }

    // ---- main card -----------------------------------------------------------------------------------------

    /** The last start was refused by the system (foreground service not allowed); kept until the next start tap. */
    private var startRefused = false

    private fun render() {
        if (!store.ready()) { renderModels(); return }
        progress.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.isEnabled = true
        if (CaptionService.running) {
            status(R.color.ok, "字幕正在显示", "回到你要看的视频或直播就行。\n点一下字幕，可以调大小或停止。")
            primary.text = "停止字幕"; primary.setIconResource(R.drawable.ic_stop)
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.err))
            primary.setOnClickListener { CaptionService.stop(this); main.postDelayed({ render() }, 300) }
        } else {
            val missingPerm = !hasAudio() || !Settings.canDrawOverlays(this)
            val hint = if (missingPerm) "点下面的按钮，按提示允许下方两项，就能开始。" else "点下面的按钮，再在弹出的窗口里选你要看的 App。"
            if (startRefused) status(R.color.err, "手机暂时不让字幕启动", "请再点一次开始。如果还是不行，请反馈出现这个提示前做了什么。")
            else status(R.color.brand, if (missingPerm) "还差一步" else "可以开始了", dl.maintenanceWarning?.let { "$hint\n⚠ $it（占用存储空间，可在系统设置里清除本 App 的缓存/数据后重新下载）" } ?: hint)
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
                if (prefs.mtOnline) status(R.color.warn, "第一次用，先下载一下",
                    "要下载约 ${gb(left)} 的识别文件，只需要下载一次。听声音在手机上完成，翻译用网络。\n文件来自 huggingface.co、github.com。")
                else status(R.color.warn, "第一次用，先下载一下",
                    "要下载约 ${gb(left)} 的识别和翻译文件，只需要下载一次，之后翻译也在手机上完成（Jev 仍需联网）。建议连 Wi‑Fi。\n文件来自 huggingface.co、github.com。")
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
        // the home page shows this card only while something required is missing; after that it is just noise
        findViewById<View>(R.id.permCard).visibility =
            if (hasAudio() && Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        permRow("① 听 App 的声音", "只听你选的那个 App 播放的声音，不用麦克风", hasAudio()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_ONLY)
        }
        permRow("② 在屏幕上显示字幕", "让字幕浮在其他 App 上面", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permRow("通知（可以不开）", "显示运行状态和下载进度", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
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
        texts.addView(TextView(this).apply { text = title; textSize = 18f; setTextColor(getColor(R.color.text)) })
        texts.addView(TextView(this).apply { text = sub; textSize = 14f; setTextColor(getColor(R.color.text2)) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (granted) {
            row.addView(TextView(this).apply { text = "✓ 已允许"; textSize = 16f; setTextColor(getColor(R.color.ok)) })
        } else {
            row.addView(MaterialButton(this).apply {
                text = "去允许"; textSize = 16f; minHeight = (52 * dp).toInt(); setOnClickListener { grant() }
            })
        }
        permList.addView(row)
    }

    // ---- translation / Jev ---------------------------------------------------------------------------------

    private val dpx get() = resources.displayMetrics.density

    /** Small grey caption above a control. */
    private fun label(t: String, top: Int = 14) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(getColor(R.color.text2))
        setPadding(0, (top * dpx).toInt(), 0, (8 * dpx).toInt())
    }

    /** Heading of a group inside a settings card. */
    private fun heading(t: String, top: Int = 26) = TextView(this).apply {
        text = t; textSize = 18f; setTextColor(getColor(R.color.text)); typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, (top * dpx).toInt(), 0, (4 * dpx).toInt())
    }

    /** One or two lines saying what an option does. */
    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(getColor(R.color.text2)); setLineSpacing(3 * dpx, 1f)
        setPadding(0, (6 * dpx).toInt(), 0, 0)
    }

    /** A switch with its title, and the description under it. */
    private fun switchRow(box: LinearLayout, title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        val s = MaterialSwitch(this).apply {
            text = title; textSize = 17f; setTextColor(getColor(R.color.text)); isChecked = on
            minHeight = (52 * dpx).toInt()
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        box.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (14 * dpx).toInt()
        })
        box.addView(hint(desc).apply { setPadding(0, 0, (56 * dpx).toInt(), 0) })
        return s
    }

    /** A full-width dropdown. */
    private fun dropdown(items: List<String>, sel: Int, enabled: Boolean, onPick: (Int) -> Unit) = android.widget.Spinner(this).apply {
        adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel, false)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        isEnabled = enabled
        minimumHeight = (52 * dpx).toInt()
    }

    /** Single-choice row of segmented buttons. */
    private fun <T> choice(options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) =
        com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            options.forEach { (v, t) ->
                val b = MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId(); text = t; tag = v; textSize = 16f; minHeight = (52 * dpx).toInt()
                    maxLines = 2
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
        val running = CaptionService.running
        translateRunning = running
        if (running) box.addView(TextView(this).apply {
            text = "字幕正在运行：下面的改动从下次开始字幕时生效（显示方式除外，马上生效）。"
            textSize = 15f; setTextColor(getColor(R.color.warn)); setPadding(0, 0, 0, (6 * dpx).toInt())
        })

        // -- how it translates
        box.addView(heading("怎么翻译", top = 0))
        box.addView(hint("视频里说的语言和要翻译成的语言，在首页选（现在是：${prefs.sourceLang?.zh ?: "不确定"} → ${prefs.targetLang.zh}）。"))
        val engine = choice(listOf(true to "在线翻译", false to "手机上翻译"), prefs.mtOnline) {
            if (prefs.mtOnline != it) { prefs.mtOnline = it; renderTranslate(); render() } // local needs its model file
        }
        if (running) for (i in 0 until engine.childCount) engine.getChildAt(i).isEnabled = false
        box.addView(engine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (12 * dpx).toInt()
        })
        if (prefs.mtOnline) {
            box.addView(label("翻译模型"))
            box.addView(dropdown(OnlineModel.entries.map { it.label }, prefs.onlineModel.ordinal, !running) {
                if (prefs.onlineModel.ordinal != it) prefs.onlineModel = OnlineModel.entries[it]
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            box.addView(hint("用 B 站提供的免费 Index 翻译服务，速度快、译得好，需要联网。只发送识别出的文字，不发送声音；网络断开时这句会显示「翻译失败」。模型越大译得越好，越小越快。"))
        } else {
            box.addView(hint("翻译也在手机上完成，不需要联网，但要多下载约 1.3 GB 的文件，而且比在线翻译慢，句子密的时候字幕会跟不上。"))
        }

        box.addView(label("内容类型"))
        box.addView(choice(Scene.entries.map { it to it.label }, prefs.scene) { prefs.scene = it })
        box.addView(hint("选「VTuber 直播」时，翻译会按直播闲聊的口吻来译，并认得直播里的常用词。"))

        // translation context: on/off and how many finished sentences (applies to the next start)
        val ctxCount = label("")
        val ctxSlider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 1f; valueTo = Pipeline.MAX_PREVIOUS_SENTENCES.toFloat(); stepSize = 1f; isTickVisible = false
            value = prefs.contextSentences.toFloat()
        }
        fun showCtx() {
            ctxCount.text = "参考前面 ${prefs.contextSentences} 句"
            ctxCount.visibility = if (prefs.contextOn) View.VISIBLE else View.GONE
            ctxSlider.visibility = ctxCount.visibility
        }
        switchRow(box, "结合上文来翻译", "翻译每句话时，把前面刚说过的几句一起交给翻译参考，人称、指代和省略的内容会译得更连贯。参考的句子越多，翻译越慢。",
            prefs.contextOn) { prefs.contextOn = it; showCtx() }
        box.addView(ctxCount)
        box.addView(ctxSlider)
        ctxSlider.addOnChangeListener { _, v, user -> if (user) { prefs.contextSentences = v.toInt(); showCtx() } }
        showCtx()

        // -- how captions appear
        box.addView(heading("字幕怎么出现"))
        modeGroup = choice(listOf(false to "边听边出", true to "说完再出"), prefs.finalOnly) { if (prefs.finalOnly != it) prefs.finalOnly = it }
        box.addView(modeGroup, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dpx).toInt()
        })
        box.addView(hint("「边听边出」：一边说一边显示，灰色的字还会修改，说完一句后变成正式字幕。「说完再出」：每句话说完才显示，画面更干净，但会晚一点。字幕上的工具栏也能随时切换。"))
        switchRow(box, "去掉重复的字", "一句话分段识别时，接口处偶尔会把同一段话识别两遍。打开后自动去掉多出来的那一遍；如果说话人真的重复说了，也可能被去掉。",
            prefs.dedupeJoin) { prefs.dedupeJoin = it }

        // -- Jev (optional cloud judge)
        box.addView(heading("AI 辅助（可选，需要联网）"))
        val jev = switchRow(box, "智能断句", "由 Jev 判断一句话是否真的说完，减少把一句话切成两半的情况。", prefs.jevOn) {}
        val jevCheck = switchRow(box, "检查译文", "由 Jev 检查每句译文是否把意思译反、漏了否定等，发现问题就重新翻译这一句。打开后每句字幕会稍晚出现。", prefs.jevCheck) {}
        val jevBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(jevBox)
        fun renderJev() {
            jevBox.removeAllViews()
            val used = prefs.jevOn || prefs.jevCheck // the provider and key serve both features
            jevBox.visibility = if (used) View.VISIBLE else View.GONE
            if (!used) return
            val p = prefs.jevProvider
            jevBox.addView(label("Jev 服务"))
            // a full-width dropdown: three segmented buttons do not fit a phone's width with readable names
            jevBox.addView(dropdown(JevProvider.entries.map { "${it.label}（${it.note}）" }, p.ordinal, true) {
                if (prefs.jevProvider.ordinal != it) { prefs.jevProvider = JevProvider.entries[it]; main.post { renderJev() } }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (!p.needsKey) {
                jevBox.addView(hint("不用填 key，打开就能用。这是免费的公共服务，可能随时限速或停用；服务不可用时，字幕照常显示，只是不做辅助。只发送识别出的文字和译文，不发送声音。"))
                return
            }
            val saved = prefs.jevKey(p)
            jevBox.addView(label(if (saved != null) "${p.label} 的 key：已保存（…${saved.takeLast(4)}）" else "${p.label} 的 key：还没填"))
            val input = android.widget.EditText(this).apply {
                hint = if (saved != null) "输入新的 key 来替换" else "在这里粘贴 key"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true; textSize = 16f; setTextColor(getColor(R.color.text))
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            jevBox.addView(input)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "清除"; textSize = 16f; isEnabled = saved != null
                setOnClickListener { prefs.setJevKey(p, null); renderJev() }
            })
            row.addView(MaterialButton(this).apply {
                text = "保存"; textSize = 16f
                setOnClickListener {
                    val k = input.text.toString().trim()
                    when {
                        k.isEmpty() -> {}
                        // a key with spaces/newlines/non-ASCII would be rejected by HTTP (and echoed in its error)
                        !Jev.validKey(k) -> Toast.makeText(this@MainActivity, "key 里有空格、换行或特殊字符，请重新复制", Toast.LENGTH_LONG).show()
                        else -> { prefs.setJevKey(p, k); input.setText(""); renderJev() }
                    }
                }
            })
            jevBox.addView(row)
            jevBox.addView(hint("只发送识别出的文字和译文，不发送声音。key 加密保存在这台手机上，不会写进使用记录。"))
        }
        jev.setOnCheckedChangeListener { _, on -> prefs.jevOn = on; renderJev() }
        jevCheck.setOnCheckedChangeListener { _, on -> prefs.jevCheck = on; renderJev() }
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
                text = "还没有记录。每次开始字幕都会留下一份文字记录，出问题时可以分享出去帮助排查。"; textSize = 15f; setTextColor(getColor(R.color.text2))
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
            })
            return
        }
        val dp = resources.displayMetrics.density
        // all logs stay reachable: beyond 5 the list scrolls inside a fixed-height box instead of growing the page
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // session-20261010-151200[-1].jsonl[.part] → 2026-10-10 15:12:00
            val n = f.name.removePrefix("session-").substringBefore(".jsonl")
            val title = if (n.length >= 15) "${n.substring(0, 4)}-${n.substring(4, 6)}-${n.substring(6, 8)} ${n.substring(9, 11)}:${n.substring(11, 13)}:${n.substring(13, 15)}${n.substring(15)}" else f.name
            texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(getColor(R.color.text)) })
            val complete = SessionLog.complete(f)
            texts.addView(TextView(this).apply {
                // .part: still running, or the session failed / was killed before the log was finished
                text = "%.1f KB".format(f.length() / 1024.0) + when {
                    complete -> ""
                    f in active -> " · 正在记录，结束后可以分享"
                    else -> " · 没有正常结束"
                }
                textSize = 13.5f; setTextColor(getColor(R.color.text2))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "删除"; setTextColor(getColor(R.color.err)); isEnabled = f !in active
                minWidth = 0; minimumWidth = 0; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
                setOnClickListener { confirmDelete("删除 $title 的记录？", "删除后不能恢复。") {
                    if (!SessionLog.delete(f)) Toast.makeText(this@MainActivity, "删除失败：记录正在写入或文件被占用", Toast.LENGTH_SHORT).show()
                    renderLogs()
                } }
            })
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "分享"; isEnabled = f !in active; setOnClickListener { share(f) }
            })
            rows.addView(row)
        }
        if (files.size > LOGS_BEFORE_SCROLL) {
            val scroller = object : android.widget.ScrollView(this) {
                // the page is a ScrollView too: keep the gesture in this list while it can scroll
                override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                    parent.requestDisallowInterceptTouchEvent(canScrollVertically(1) || canScrollVertically(-1))
                    return super.onTouchEvent(e)
                }
                override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
                    return super.onInterceptTouchEvent(e)
                }
            }.apply { isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false; addView(rows) }
            // about 5½ rows: the cut-off row shows there is more
            box.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (LOGS_BEFORE_SCROLL * 58 * dp + 28 * dp).toInt()))
        } else box.addView(rows)
        val deletable = files.count { it !in active }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "共 ${files.size} 个，" + files.sumOf { it.length() }.let { if (it < 1048576) "%.1f KB".format(it / 1024.0) else "%.1f MB".format(it / 1048576.0) }
                textSize = 12.5f; setTextColor(getColor(R.color.text2))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "全部清空"; setTextColor(getColor(R.color.err)); isEnabled = deletable > 0
                setOnClickListener { confirmDelete("删除全部 $deletable 个记录？", "正在记录的不会删除。删除后不能恢复。") {
                    val left = SessionLog.deleteAll(getExternalFilesDir(null)!!)
                    if (left > 0) Toast.makeText(this@MainActivity, "还剩 $left 个（正在记录或无法删除）", Toast.LENGTH_SHORT).show()
                    renderLogs()
                } }
            })
        })
    }

    private fun confirmDelete(title: String, msg: String, ok: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title).setMessage(msg)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> ok() }
            .show()
    }

    // ---- model files ---------------------------------------------------------------------------------------

    /** Files in the model folders, current and left over, each deletable while nothing can be reading or writing it. */
    private fun renderModelFiles() {
        val box = findViewById<LinearLayout>(R.id.modelFileList)
        box.removeAllViews()
        val dp = resources.displayMetrics.density
        val files = store.listFiles()
        val busy = modelFilesBusy()
        listedModels = files to busy
        if (files.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "还没有下载文件。"; textSize = 15f; setTextColor(getColor(R.color.text2))
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            })
            return
        }
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = f.path; textSize = 15f; setTextColor(getColor(R.color.text)) })
            texts.addView(TextView(this).apply {
                text = sizeText(f.bytes) + " · " + when (f.kind) {
                    ModelStore.Kind.CURRENT -> if (f.path.startsWith("mt/")) "手机上翻译时用，在线翻译用不到" else "正在使用，删除后要重新下载"
                    ModelStore.Kind.OLD -> "旧版本留下的，可以删除"
                    ModelStore.Kind.PARTIAL -> "没下载完的文件，可以删除"
                }
                textSize = 13.5f
                setTextColor(getColor(if (f.kind == ModelStore.Kind.CURRENT) R.color.text2 else R.color.warn))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "删除"; setTextColor(getColor(R.color.err)); isEnabled = busy == null
                setOnClickListener {
                    val warn = if (f.kind == ModelStore.Kind.CURRENT) "这是当前要用的模型，删除后需要重新下载（${sizeText(f.bytes)}）才能用。" else "这个文件现在不用，删除后可以腾出 ${sizeText(f.bytes)}。"
                    confirmDelete("删除 ${f.path}？", warn) {
                        // re-checked now: a session or download may have started while the dialog was open
                        val why = modelFilesBusy()
                        when {
                            why != null -> Toast.makeText(this@MainActivity, why, Toast.LENGTH_SHORT).show()
                            !store.delete(f) -> Toast.makeText(this@MainActivity, "删除失败：${f.path}", Toast.LENGTH_SHORT).show()
                        }
                        renderModelFiles(); render()
                    }
                }
            })
            box.addView(row)
        }
        box.addView(TextView(this).apply {
            text = "共 ${sizeText(files.sumOf { it.bytes })}" + (busy?.let { "\n$it" } ?: "")
            textSize = 12.5f; setTextColor(getColor(R.color.text2))
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }

    /** Why model files cannot be deleted right now, or null. */
    private fun modelFilesBusy(): String? = when {
        // also true after stopping until the session thread has freed the native models
        CaptionService.modelsInUse -> "字幕运行中（或正在释放模型），停止后才能删除"
        // anyTransfer: also a local-model download still running after switching to online (state() skips it)
        dl.anyTransfer() || dl.state().let { it.running || it.verifying != null } -> "正在下载或校验模型，完成或取消后才能删除"
        else -> null
    }

    private fun sizeText(b: Long) = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.1f KB".format(b / 1024.0)
    }
    private var listedModels: Pair<List<ModelStore.StoredFile>, String?>? = null
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
        val height = findViewById<Slider>(R.id.sliderHeight)
        val lHeight = findViewById<TextView>(R.id.labelHeight)
        val keepS = findViewById<Slider>(R.id.sliderKeepSentences)
        val keepC = findViewById<Slider>(R.id.sliderKeepChars)
        val lKeepS = findViewById<TextView>(R.id.labelKeepSentences)
        val lKeepC = findViewById<TextView>(R.id.labelKeepChars)
        val keepHint = findViewById<TextView>(R.id.keepHint)
        val paletteRow = findViewById<LinearLayout>(R.id.paletteRow)
        val dpx = resources.displayMetrics.density
        // one swatch per palette: its background with a sample glyph in its text colour, brand outline when chosen
        fun renderPalettes() {
            paletteRow.removeAllViews()
            for (p in CaptionOverlayView.PALETTES) {
                val sel = p.id == CaptionOverlayView.palette(prefs.palette).id
                paletteRow.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    contentDescription = p.label + if (sel) "（已选）" else ""
                    setPadding((4 * dpx).toInt(), 0, (4 * dpx).toInt(), 0)
                    addView(TextView(this@MainActivity).apply {
                        text = "字"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(p.zh); typeface = android.graphics.Typeface.DEFAULT_BOLD
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 14 * dpx; setColor(p.bg or 0xFF000000.toInt())
                            setStroke(((if (sel) 3 else 1) * dpx).toInt(), if (sel) getColor(R.color.brand) else 0x33888888)
                        }
                    }, LinearLayout.LayoutParams((52 * dpx).toInt(), (52 * dpx).toInt()))
                    addView(TextView(this@MainActivity).apply {
                        text = p.label; textSize = 12f; gravity = Gravity.CENTER
                        setTextColor(getColor(if (sel) R.color.brand else R.color.text2))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dpx).toInt() })
                    setOnClickListener { prefs.palette = p.id; renderPalettes(); preview.setColors(p) }
                }, LinearLayout.LayoutParams((68 * dpx).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }

        var loading = false // programmatic updates must not be written back
        fun load() {
            loading = true
            size.value = prefs.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP).let { Math.round(it).toFloat() }
            alpha.value = (Math.round(prefs.bgAlpha * 20) * 5f).coerceIn(0f, 100f)
            width.value = (Math.round(prefs.widthFrac * 20) * 5f).coerceIn(40f, 100f)
            ja.isChecked = prefs.showJa
            stats.isChecked = prefs.showStats
            // only a real 0 is "auto": a small dragged height still shows as fixed (at least the first step)
            height.value = if (prefs.heightFrac <= 0f) 0f else (Math.round(prefs.heightFrac * 20) * 5f).coerceIn(5f, 40f)
            keepS.value = prefs.keepSentences.coerceIn(1, 6).toFloat()
            keepC.value = (Math.round(prefs.keepChars / 10f) * 10f).coerceIn(0f, 300f)
            loading = false
        }
        fun apply() {
            lSize.text = "文字大小  ${size.value.toInt()}"
            lAlpha.text = "背景深浅  ${alpha.value.toInt()}%"
            lWidth.text = "字幕宽度  ${width.value.toInt()}%"
            lHeight.text = if (prefs.heightFrac <= 0f) "字幕高度  自动（跟着内容变）" else "字幕高度  屏幕的 ${Math.round(prefs.heightFrac * 100)}%"
            lKeepS.text = "屏幕上至少留  ${keepS.value.toInt()} 句"
            lKeepC.text = "字数超过  ${keepC.value.toInt()} 字后，收起最早的句子"
            keepHint.text = "屏幕上会留着刚说完的几句话，方便回看；句数和字数都超过上面的数字时，最早的一句才会收起。字幕框放不下时，只显示最新的内容。"
            preview.setColors(CaptionOverlayView.palette(prefs.palette))
            preview.applyStyle(size.value, alpha.value / 100f)
            // same caption height as the overlay will get (the stage grows with it)
            preview.setMaxContentHeight((resources.displayMetrics.heightPixels * CaptionService.MAX_CAPTION_FRAC).toInt(), Int.MAX_VALUE)
            preview.setCaptionHeight((resources.displayMetrics.heightPixels * prefs.heightFrac).toInt())
            preview.layoutParams = FrameLayout.LayoutParams((findViewById<View>(R.id.previewStage).width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 0.8f).toInt()).let { (it * width.value / 100f).toInt() }, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            preview.setLines(
                listOf(CaptionOverlayView.Line("明天的会议改到下午三点了。", if (ja.isChecked) "明日の会議は午後三時に変更になりました" else null, true),
                    CaptionOverlayView.Line("资料我今天之内发给你…", if (ja.isChecked) "資料は今日中に送ります" else null, false)),
                if (stats.isChecked) "识别 52ms · 翻译 310ms · 延迟 0.7s" else null,
            )
        }
        loadStyle = { load(); renderPalettes(); apply() }
        load(); renderPalettes(); apply()
        findViewById<View>(R.id.previewStage).post { apply() } // the stage's real width is known after layout
        size.addOnChangeListener { _, v, user -> if (user) { prefs.textSp = v; apply() } }
        alpha.addOnChangeListener { _, v, user -> if (user) { prefs.bgAlpha = v / 100f; apply() } }
        width.addOnChangeListener { _, v, user -> if (user) { prefs.widthFrac = v / 100f; apply() } }
        ja.setOnCheckedChangeListener { _, v -> if (!loading) { prefs.showJa = v; apply() } }
        stats.setOnCheckedChangeListener { _, v -> if (!loading) { prefs.showStats = v; apply() } }
        height.addOnChangeListener { _, v, user -> if (user) { prefs.heightFrac = v / 100f; apply() } }
        keepS.addOnChangeListener { _, v, user -> if (user) { prefs.keepSentences = v.toInt(); apply() } }
        keepC.addOnChangeListener { _, v, user -> if (user) { prefs.keepChars = v.toInt(); apply() } }
        findViewById<MaterialButton>(R.id.resetStyle).setOnClickListener {
            prefs.setStyle(20f, 0.55f); prefs.widthFrac = Prefs.DEFAULT_WIDTH; prefs.showJa = false; prefs.showStats = false
            prefs.keepSentences = CaptionBoard.DEFAULT_SENTENCES; prefs.keepChars = CaptionBoard.DEFAULT_CHARS; prefs.heightFrac = 0f
            prefs.palette = CaptionOverlayView.PALETTES[0].id; renderPalettes()
            prefs.overlayX = -1; prefs.overlayY = -1
            load(); apply()
        }
    }

    // ---- start ---------------------------------------------------------------------------------------------

    private fun start() {
        startRefused = false
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
        else if (requestCode == REQ_START) status(R.color.err, "需要允许「听 App 的声音」", "只用来听你选的那个 App 播放的声音，不会用麦克风。请再点一次开始，并选择「允许」。")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            // Seen on Android 15 with "a single app": the system refused the foreground service here
            // (mAllowStartForeground false). Show it instead of crashing; the exact trigger is not pinned down.
            try {
                CaptionService.start(this, resultCode, data)
            } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                startRefused = true; render()
                return
            }
            moveTaskToBack(true)
        }
    }

    private companion object {
        const val REQ_START = 1      // runtime permission asked on the way to starting subtitles
        const val REQ_PROJECTION = 2
        val STYLE_KEYS = setOf("textSp", "bgAlpha", "widthFrac", "heightFrac", "showJa", "showStats", "keepSentences", "keepChars", "palette")
        const val REQ_PERM_ONLY = 3  // "去开启" in the permission list: grant only, never start
        const val LOGS_BEFORE_SCROLL = 5
        /** where the project lives (set by the author; the repository may not be public yet) */
        const val REPO_URL = "https://github.com/KorenKrita/simul-subtitle"
    }
}
