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
    private lateinit var updater: AppUpdater

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
            addView(hint("这个页面用白天还是夜间颜色。不影响悬浮字幕，字幕颜色在「字幕」里选。").apply { setPadding(0, 0, 0, (10 * dpx).toInt()) })
            // recreate re-reads resources with the new mode; the overlay lives in the service and is untouched
            addView(choice(listOf("system" to "跟随系统", "light" to "白天", "dark" to "夜间"), prefs.theme) {
                if (prefs.theme != it) { prefs.theme = it; recreate() }
            })
        }
        // the settings categories, magpie's preferences tabs: one list shown at a time
        findViewById<android.widget.FrameLayout>(R.id.tabBox).addView(choice(TABS.indices.map { it to TABS[it].second }, tab) { showTab(it) })
        findViewById<View>(R.id.openSettings).setOnClickListener { showSettings(true) }
        findViewById<View>(R.id.closeSettings).setOnClickListener { back() }
        findViewById<View>(R.id.openSize).setOnClickListener { showSize(true) }
        findViewById<TextView>(R.id.aboutVersion).text = "${getString(R.string.app_name)}  版本 ${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.openRepo).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            catch (_: android.content.ActivityNotFoundException) { }
        }
        // a theme switch recreates the activity from the settings page: stay there, on the same category / page
        tab = savedInstanceState?.getInt("tab", 0)?.coerceIn(TABS.indices) ?: 0
        showTab(tab)
        showSettings(savedInstanceState?.getBoolean("settings") == true)
        if (inSettings && savedInstanceState?.getBoolean("sizePage") == true) showSize(true)
        updater = AppUpdater(this)
        updater.start()
    }

    private var inSettings = false
    /** Index into [TABS]: the settings category shown. */
    private var tab = 0
    /** The size page (字幕 › 字幕框大小和保留几句) is open over the 字幕 category. */
    private var inSize = false

    private fun showSettings(on: Boolean) {
        inSettings = on
        findViewById<View>(R.id.homePage).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.settingsPage).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
        if (on) {
            loadStyle() // the preview needs the stage's real width, known once the page is laid out
            findViewById<View>(R.id.previewStage).post { loadStyle() }
        } else { showSize(false); renderLang() }
    }

    private fun showTab(i: Int) {
        tab = i
        TABS.forEachIndexed { n, (id, _) -> findViewById<View>(id).visibility = if (n == i && !inSize) View.VISIBLE else View.GONE }
        findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
        if (TABS[i].first == R.id.tabCaption && inSettings) findViewById<View>(R.id.previewStage).post { loadStyle() }
    }

    /** The size page replaces the categories and the 字幕 list; back returns to them. */
    private fun showSize(on: Boolean) {
        inSize = on
        findViewById<View>(R.id.sizePage).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tabBox).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.settingsTitle).text = if (on) "字幕框大小和保留几句" else "设置"
        showTab(if (on) TABS.indexOfFirst { it.first == R.id.tabCaption } else tab)
    }

    /** One step back: size page → settings → home → leave. */
    private fun back(): Boolean = when {
        inSize -> { showSize(false); true }
        inSettings -> { showSettings(false); true }
        else -> false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("settings", inSettings)
        outState.putInt("tab", tab)
        outState.putBoolean("sizePage", inSize)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!back()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** The home page's two questions: what is spoken, what the captions should be in. Next start, like the settings. */
    private fun renderLang() {
        val box = findViewById<LinearLayout>(R.id.langBox)
        box.removeAllViews()
        val running = CaptionService.running
        val sources = Lang.SOURCES
        box.addView(pickRow("视频里说的是", sources.map { it?.zh ?: "不确定（日语或英语）" }, sources.indexOf(prefs.sourceLang).coerceAtLeast(0), !running) {
            if (prefs.sourceLang != sources[it]) { prefs.sourceLang = sources[it]; renderTranslate() }
        })
        box.addView(divider())
        val targets = Lang.entries
        box.addView(pickRow("字幕翻译成", targets.map { it.zh }, prefs.targetLang.ordinal, !running) {
            if (prefs.targetLang != targets[it]) { prefs.targetLang = targets[it]; renderTranslate() }
        })
        findViewById<TextView>(R.id.langHint).text = if (running) "字幕运行中，停止后才能改语言。" else "知道是哪种语言就选上，翻译会更准。"
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
        updater.resume()
    }

    override fun onPause() {
        super.onPause()
        prefs.unlisten(styleListener)
        main.removeCallbacks(ticker)
        updater.pause()
    }

    override fun onDestroy() {
        updater.destroy()
        byokTest?.cancel(); byokTest = null
        super.onDestroy()
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
            onPrimary(R.color.on_err)
            primary.setOnClickListener { CaptionService.stop(this); main.postDelayed({ render() }, 300) }
        } else {
            val missingPerm = !hasAudio() || !Settings.canDrawOverlays(this)
            val hint = if (missingPerm) "点下面的按钮，按提示允许下方两项，就能开始。" else "点下面的按钮，再在弹出的窗口里选你要看的 App。"
            if (startRefused) status(R.color.err, "手机暂时不让字幕启动", "请再点一次开始。如果还是不行，请反馈出现这个提示前做了什么。")
            else status(R.color.brand, if (missingPerm) "还差一步" else "可以开始了", dl.maintenanceWarning?.let { "$hint\n⚠ $it（占用存储空间，可在系统设置里清除本 App 的缓存/数据后重新下载）" } ?: hint)
            primary.text = "开始字幕"; primary.setIconResource(R.drawable.ic_play)
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand))
            onPrimary(R.color.on_brand)
            primary.setOnClickListener { start() }
        }
    }

    /** Text and icon colour of the main button: each fill has its own readable foreground (indigo / red, day / night). */
    private fun onPrimary(color: Int) {
        primary.setTextColor(getColor(color)); primary.iconTint = ColorStateList.valueOf(getColor(color))
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
        permRow("① 听手机里播放的声音", "只听 App 播放出来的声音，不用麦克风", hasAudio(), permList) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_ONLY)
        }
        permRow("② 在屏幕上显示字幕", "让字幕浮在其他 App 上面", Settings.canDrawOverlays(this), permList) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        // optional: on the home card while it shows, and always in Settings (the home card hides once the two are granted)
        val notifBox = findViewById<LinearLayout>(R.id.notifList)
        notifBox.removeAllViews()
        findViewById<View>(R.id.notifSection).visibility = if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val ask = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM_ONLY) }
            permRow("通知（可以不开）", "在通知栏显示运行状态、下载进度和「停止」按钮", granted, permList, ask)
            permRow("通知", "在通知栏显示运行状态、下载进度和「停止」按钮", granted, notifBox, ask)
        }
    }


    private fun permRow(title: String, sub: String, granted: Boolean, parent: LinearLayout, grant: () -> Unit) {
        val dp = resources.displayMetrics.density
        if (parent.childCount > 0) parent.addView(divider())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * dp).toInt()
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.text)) })
        texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(getColor(R.color.muted)) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (granted) {
            row.addView(TextView(this).apply { text = "✓ 已允许"; textSize = 14f; setTextColor(getColor(R.color.ok)); setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0) })
        } else {
            row.addView(pillButton("去允许") { grant() })
        }
        parent.addView(row)
    }

    // ---- translation / Jev ---------------------------------------------------------------------------------

    private val dpx get() = resources.displayMetrics.density

    /** Small grey caption above a control. */
    private fun label(t: String, top: Int = 14) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(getColor(R.color.text))
        setPadding(0, (top * dpx).toInt(), 0, (8 * dpx).toInt())
    }

    /** Heading of a group inside a settings card. */
    private fun heading(t: String, top: Int = 26) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(getColor(R.color.text)); typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, (top * dpx).toInt(), 0, (4 * dpx).toInt())
    }

    /** One or two lines saying what an option does (magpie's grey .sub). */
    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(getColor(R.color.muted)); setLineSpacing(2 * dpx, 1f)
        setPadding(0, (6 * dpx).toInt(), 0, 0)
    }

    /** A risk / cost / "takes effect later" note: magpie's amber note, never cut short. */
    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(getColor(R.color.warn)); setLineSpacing(2 * dpx, 1f)
        setBackgroundResource(R.drawable.note_warn)
        setPadding((10 * dpx).toInt(), (8 * dpx).toInt(), (10 * dpx).toInt(), (8 * dpx).toInt())
    }

    /** The hairline between two rows of a list. */
    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.line2))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dpx).toInt().coerceAtLeast(1)).apply { marginStart = (14 * dpx).toInt() }
    }

    /** A small grey button at the end of a row (magpie's .text.action on a pill). */
    private fun pillButton(t: String, onClick: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = t; textSize = 14f; setTextColor(getColor(R.color.text)); minHeight = (44 * dpx).toInt(); minimumHeight = minHeight
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.pill))
        setOnClickListener { onClick() }
    }

    /** Group title + white list card on the settings page; returns the card's content box. */
    private fun group(root: LinearLayout, title: String): LinearLayout {
        root.addView(TextView(this, null, 0, R.style.SectionTitle).apply { text = title }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (20 * dpx).toInt(); bottomMargin = (6 * dpx).toInt()
        })
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dpx).toInt(), (6 * dpx).toInt(), (14 * dpx).toInt(), (14 * dpx).toInt())
        }
        root.addView(com.google.android.material.card.MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.card)); radius = 12 * dpx; cardElevation = 0f
            strokeColor = getColor(R.color.line); strokeWidth = (1 * dpx).toInt().coerceAtLeast(1)
            addView(inner)
        })
        return inner
    }

    /** A switch with its title, and the description under it. */
    private fun switchRow(box: LinearLayout, title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        val s = MaterialSwitch(this).apply {
            text = title; textSize = 15f; setTextColor(getColor(R.color.text)); isChecked = on
            minHeight = (48 * dpx).toInt()
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        box.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dpx).toInt()
        })
        box.addView(hint(desc).apply { setPadding(0, 0, (56 * dpx).toInt(), 0) })
        return s
    }

    /** A dropdown: magpie's grey pill with a chevron; full width unless placed at the end of a row. */
    private fun dropdown(items: List<String>, sel: Int, enabled: Boolean, onPick: (Int) -> Unit) = android.widget.Spinner(this).apply {
        adapter = object : android.widget.ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(p: Int, c: View?, g: android.view.ViewGroup) =
                (super.getView(p, c, g) as TextView).apply {
                    textSize = 14f; setTextColor(getColor(R.color.text)); minHeight = (44 * dpx).toInt()
                    setPadding((12 * dpx).toInt(), 0, (8 * dpx).toInt(), 0)
                }
            override fun getDropDownView(p: Int, c: View?, g: android.view.ViewGroup) =
                (super.getDropDownView(p, c, g) as TextView).apply { textSize = 15f; minHeight = (48 * dpx).toInt() }
        }
        setBackgroundResource(R.drawable.spinner_pill)
        setPadding(0, 0, (26 * dpx).toInt(), 0) // room for the chevron
        setSelection(sel, false)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        isEnabled = enabled; alpha = if (enabled) 1f else 0.5f
        minimumHeight = (44 * dpx).toInt()
    }

    /** One list row: the name on the left, a dropdown at the end (magpie's row with a pop-up button). */
    private fun pickRow(title: String, items: List<String>, sel: Int, enabled: Boolean, onPick: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (56 * dpx).toInt()
        setPadding((14 * dpx).toInt(), (6 * dpx).toInt(), (10 * dpx).toInt(), (6 * dpx).toInt())
        addView(TextView(this@MainActivity).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.text)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(dropdown(items, sel, enabled, onPick).apply { dropDownHorizontalOffset = 0 },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
    }

    /** A segment of [choice]: the group squares off its inner buttons on every measure; magpie's thumb keeps all corners round. */
    private class SegButton(ctx: android.content.Context) : MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) {
        override fun setShapeAppearanceModel(m: com.google.android.material.shape.ShapeAppearanceModel) =
            super.setShapeAppearanceModel(m.toBuilder().setAllCornerSizes(8 * resources.displayMetrics.density).build())
    }

    /** Single-choice row of segmented buttons (magpie's .seg: a grey track, the chosen option a raised thumb). */
    private fun <T> choice(options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) =
        com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            // a LinearLayout lines its children up on the text baseline: a button whose label shrank would sit lower
            isBaselineAligned = false
            setBackgroundResource(R.drawable.seg_track)
            setPadding((3 * dpx).toInt(), 0, (3 * dpx).toInt(), 0)
            options.forEach { (v, t) ->
                val b = SegButton(this@MainActivity).apply {
                    id = View.generateViewId(); text = t; tag = v; textSize = 14f; minHeight = (48 * dpx).toInt()
                    // narrow phones: the label shrinks (down to 10 sp) until the laid-out text is uncut, instead of
                    // being cut to "在…"; judged on the real layout, so the button's paddings and stroke count. Still
                    // cut at 10 sp (very large system font): it wraps over as many lines as it needs, never cut.
                    isAllCaps = false
                    setPadding((4 * dpx).toInt(), paddingTop, (4 * dpx).toInt(), paddingBottom)
                    val min = 10 * resources.displayMetrics.scaledDensity
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        val b = v as MaterialButton
                        val lay = b.layout ?: return@addOnLayoutChangeListener
                        val cut = lay.lineCount > b.maxLines || (0 until lay.lineCount).any { lay.getEllipsisCount(it) > 0 }
                        if (!cut) return@addOnLayoutChangeListener
                        if (b.textSize > min) b.post {
                            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, maxOf(min, b.textSize - resources.displayMetrics.scaledDensity / 2))
                        } else b.post { b.maxLines = Int.MAX_VALUE; b.ellipsize = null }
                    }
                }
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                // the group's addView sets one line and an ellipsis on every button: anything set before is lost
                b.maxLines = 1; b.ellipsize = android.text.TextUtils.TruncateAt.END
                if (v == current) check(b.id)
            }
            addOnButtonCheckedListener { g, id, checked ->
                @Suppress("UNCHECKED_CAST") if (checked) onPick(g.findViewById<View>(id).tag as T)
            }
        }

    /** Engine, scene, display mode and Jev. Engine, scene and Jev apply to the next start; the mode applies immediately. */
    private fun renderTranslate() {
        val root = findViewById<LinearLayout>(R.id.translateList)
        root.removeAllViews()
        val running = CaptionService.running
        translateRunning = running
        if (running) root.addView(note("字幕正在运行：下面的改动从下次开始字幕时生效（显示方式除外，马上生效）。"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dpx).toInt() })

        // -- how it translates
        var box = group(root, "怎么翻译")
        box.addView(hint("视频里说的语言和要翻译成的语言，在首页选（现在是：${prefs.sourceLang?.zh ?: "不确定"} → ${prefs.targetLang.zh}）。"))
        // one line each, like the other two: the "experimental" warning is shown right below when it is picked
        val engine = choice(listOf(MtMode.INDEX to "在线翻译", MtMode.LOCAL to "手机上翻译", MtMode.BYOK to "自己的模型"), prefs.mtMode) {
            if (prefs.mtMode != it) { prefs.mtMode = it; renderTranslate(); render() } // local needs its model file
        }
        if (running) for (i in 0 until engine.childCount) engine.getChildAt(i).isEnabled = false
        box.addView(engine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (12 * dpx).toInt()
        })
        when (prefs.mtMode) {
            MtMode.INDEX -> {
                box.addView(label("翻译模型"))
                box.addView(dropdown(OnlineModel.entries.map { it.label }, prefs.onlineModel.ordinal, !running) {
                    if (prefs.onlineModel.ordinal != it) prefs.onlineModel = OnlineModel.entries[it]
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                box.addView(hint("用 B 站提供的免费 Index 翻译服务，需要联网。只发送识别出的文字，不发送声音；网络断开时这句会显示「翻译失败」。较大的模型通常更擅长复杂内容，较小的模型通常更快，实际速度也受网络和服务繁忙程度影响。"))
            }
            MtMode.LOCAL -> box.addView(hint("翻译也在手机上完成，不需要联网，但要多下载约 1.3 GB 的文件，而且比在线翻译慢，句子密的时候字幕会跟不上。"))
            MtMode.BYOK -> renderByok(box, running)
        }

        box = group(root, "内容")
        box.addView(label("内容类型"))
        box.addView(choice(Scene.entries.map { it to it.label }, prefs.scene) { prefs.scene = it })
        box.addView(hint("选「B站 VTuber 直播」时，翻译会知道这是虚拟主播在 B 站和观众闲聊、念弹幕，并按 B 站的说法翻译「SC」「舰长」「弹幕」这类直播用语。"))

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
        switchRow(box, "结合上文来翻译", "翻译每句话时，把前面刚说过的几句一起交给翻译参考，帮助它理解人称、指代和省略的内容。参考的句子越多，翻译要处理的文字越多，可能更慢。",
            prefs.contextOn) { prefs.contextOn = it; showCtx() }
        box.addView(ctxCount)
        box.addView(ctxSlider)
        ctxSlider.addOnChangeListener { _, v, user -> if (user) { prefs.contextSentences = v.toInt(); showCtx() } }
        showCtx()

        // -- how captions appear
        box = group(root, "字幕怎么出现")
        modeGroup = choice(listOf(false to "边听边出", true to "说完再出"), prefs.finalOnly) { if (prefs.finalOnly != it) prefs.finalOnly = it }
        box.addView(modeGroup, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dpx).toInt()
        })
        box.addView(hint("「边听边出」：一边说一边显示，灰色的字还会修改，说完一句后变成正式字幕。「说完再出」：每句话说完才显示，画面更干净，但会晚一点。字幕上的工具栏也能随时切换。"))
        switchRow(box, "去掉重复的字", "一句话分段识别时，接口处偶尔会把同一段话识别两遍。打开后自动去掉多出来的那一遍；如果说话人真的重复说了，也可能被去掉。",
            prefs.dedupeJoin) { prefs.dedupeJoin = it }

        // -- Jev (optional cloud judge)
        box = group(root, "AI 辅助（可选，需要联网）")
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

    /** The running BYOK test, so a second tap, a new render or leaving the screen does not show a stale result. */
    @Volatile private var byokTest: ByokTranslator? = null

    /** BYOK: the user's OpenAI-compatible service; URL, key, model id and a test that also finds the thinking switch. */
    private fun renderByok(box: LinearLayout, running: Boolean) {
        box.addView(note("⚠ 实验性功能：翻译效果和速度都无法预测，取决于你填的服务和模型。开启后风险由你自己承担，包括费用、额度和服务商对内容的处理。"),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * dpx).toInt() })
        fun field(title: String, value: String, hintText: String, password: Boolean = false): android.widget.EditText {
            box.addView(label(title))
            return android.widget.EditText(this).apply {
                setText(value); hint = hintText; isSingleLine = true; textSize = 16f; setTextColor(getColor(R.color.text))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    if (password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isEnabled = !running
                box.addView(this)
            }
        }
        val url = field("服务地址（OpenAI 兼容）", prefs.byokUrl, "例如 https://api.deepseek.com，结尾有没有 /v1 都可以")
        val model = field("模型 ID", prefs.byokModel, "例如 deepseek-chat")
        val saved = prefs.byokKey
        val key = field(if (saved != null) "API Key：已保存（…${saved.takeLast(4)}）" else "API Key：还没填", "",
            if (saved != null) "输入新的 key 来替换" else "在这里粘贴 key", password = true)
        val result = hint("")
        // save what is in the boxes; null when something is wrong (and says what)
        fun save(): String? {
            val u = url.text.toString().trim(); val m = model.text.toString().trim(); val k = key.text.toString().trim()
            val (bad, _) = ByokTranslator.chatUrl(u)
            when {
                bad != null -> { result.text = "⚠ $bad"; return null }
                m.isEmpty() -> { result.text = "⚠ 还没填模型 ID"; return null }
                m.any { it.isWhitespace() } -> { result.text = "⚠ 模型 ID 里不要有空格"; return null }
                k.isNotEmpty() && !Jev.validKey(k) -> { result.text = "⚠ key 里有空格、换行或特殊字符，请重新复制"; return null }
                k.isEmpty() && saved == null -> { result.text = "⚠ 还没填 API Key"; return null }
            }
            prefs.byokUrl = u; prefs.byokModel = m
            if (k.isNotEmpty()) { prefs.byokKey = k; key.setText("") }
            return ByokTranslator.chatUrl(u).second
        }
        val testing = byokTest != null
        // while a test runs the boxes are locked, so its result always belongs to what they show
        listOf(url, model, key).forEach { it.isEnabled = !running && !testing }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "清除 key"; textSize = 16f; isEnabled = saved != null && !running && !testing
            setOnClickListener { prefs.byokKey = null; renderTranslate() }
        })
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "保存"; textSize = 16f; isEnabled = !running && !testing
            setOnClickListener { if (save() != null) renderTranslate() }
        })
        val test = MaterialButton(this).apply { text = if (testing) "测试中…" else "测试连接"; textSize = 16f; isEnabled = !running && !testing }
        row.addView(test)
        box.addView(row)
        box.addView(result)
        val chat = ByokTranslator.chatUrl(prefs.byokUrl).second
        val known = chat?.let { prefs.byokVariant(it, prefs.byokModel) }
        result.text = byokResult?.also { byokResult = null } ?: when {
            testing -> "正在依次尝试关闭思考的参数（最多 6 次，每次最多 8 秒，每次都会计费）…"
            chat == null || prefs.byokModel.isEmpty() || prefs.byokKey == null -> "填好地址、模型 ID 和 key 后点「测试连接」。第一次使用时会依次试最多 6 种关闭思考的参数（每种是一次计费请求），找到没检测到思考的就记住。"
            known == ByokTranslator.NONE_WORKED -> "⚠ 上次测试：已知的参数都关不掉这个模型的思考，翻译可能很慢。仍然可以使用。"
            known != null -> "上次测试可用（用的参数：${ByokTranslator.variant(known)?.label}，那次没检测到思考）。实际请求地址：$chat"
            else -> "还没测试，第一次开始字幕时会自动测。实际请求地址：$chat"
        }
        test.setOnClickListener {
            val chatUrl = save() ?: return@setOnClickListener
            val m = prefs.byokModel; val k = prefs.byokKey ?: return@setOnClickListener
            val t = ByokTranslator(chatUrl, m, k, byokSystem(prefs.scene, prefs.sourceLang, prefs.targetLang), null,
                maxTokens = mtMaxTokens(prefs.targetLang))
            byokTest?.cancel(); byokTest = t
            renderTranslate() // shows "testing" and locks the boxes
            Thread {
                val steps = mutableListOf<ByokTranslator.Step>()
                val v = t.probe(steps)
                main.post {
                    if (byokTest !== t || isDestroyed) return@post // cancelled (captions started), or the screen is gone
                    byokTest = null
                    val ok = steps.firstOrNull { it.variant === v }?.result
                    val none = v == null && t.exhausted(steps)
                    when {
                        v != null -> prefs.setByokVariant(chatUrl, m, v.name)
                        none -> prefs.setByokVariant(chatUrl, m, ByokTranslator.NONE_WORKED)
                    }
                    // shown once by the next render
                    byokResult = when {
                        ok != null -> "✓ 可以用。参数：${v!!.label}（这次没检测到思考，已记住）；收到第一个字 ${ok.firstContentMs ?: "?"} ms" +
                            (if ((ok.firstContentMs ?: 0) > FIRST_CONTENT_MS) "（超过 ${FIRST_CONTENT_MS / 1000} 秒，翻译时这句会判为失败）" else "") + "。\n测试译文：${ok.text.take(60)}"
                        none -> "⚠ 连得上，但试过的 ${steps.size} 种参数都没能关掉思考，翻译可能很慢或经常失败。仍然可以使用，风险自负。"
                        else -> "✗ 不能用：${steps.lastOrNull()?.result?.let { ByokTranslator.why(it) } ?: "没有回应"}"
                    }
                    renderTranslate()
                }
            }.start()
        }
    }
    /** The finished test's outcome, shown once by the next render. */
    private var byokResult: String? = null

    // ---- session logs --------------------------------------------------------------------------------------

    private fun renderLogs() {
        val box = findViewById<LinearLayout>(R.id.logList)
        box.removeAllViews()
        val files = SessionLog.list(getExternalFilesDir(null)!!)
        val active = SessionLog.activeFiles()
        listedLogs = files.map { it.name } to active
        // off by default; the switch takes effect from the next start (a running session keeps what it began with)
        switchRow(box, "记录每次使用", "打开后，每次开始字幕会在手机上留一份文字记录：识别结果、翻译的提示词和译文、AI 辅助的请求和回答，不含声音和 key。只有你点「分享」才会离开手机，出问题时可以发给开发者帮助排查。从下次开始字幕起生效。",
            prefs.keepLogs) { prefs.keepLogs = it; renderLogs() }
        if (files.isEmpty()) {
            box.addView(TextView(this).apply {
                text = if (prefs.keepLogs) "还没有记录。" else "还没有记录。现在不记录，打开上面的开关后才会记录。"
                textSize = 15f; setTextColor(getColor(R.color.text2))
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
        // one chip per palette (magpie's .chip): a round sample of its background and text colour, then its name;
        // the chosen one gets an ink outline
        fun renderPalettes() {
            paletteRow.removeAllViews()
            for (p in CaptionOverlayView.PALETTES) {
                val sel = p.id == CaptionOverlayView.palette(prefs.palette).id
                paletteRow.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    contentDescription = p.label + if (sel) "（已选）" else ""
                    minimumHeight = (44 * dpx).toInt()
                    setPadding((5 * dpx).toInt(), 0, (12 * dpx).toInt(), 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 22 * dpx; setColor(getColor(R.color.card))
                        setStroke(((if (sel) 2 else 1) * dpx).toInt().coerceAtLeast(1), getColor(if (sel) R.color.text else R.color.line))
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = "字"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(p.zh); typeface = android.graphics.Typeface.DEFAULT_BOLD
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(p.bg or 0xFF000000.toInt()); setStroke((1 * dpx).toInt().coerceAtLeast(1), 0x33888888)
                        }
                    }, LinearLayout.LayoutParams((32 * dpx).toInt(), (32 * dpx).toInt()))
                    addView(TextView(this@MainActivity).apply {
                        text = p.label; textSize = 14f
                        setTextColor(getColor(if (sel) R.color.text else R.color.text2))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
                    setOnClickListener { prefs.palette = p.id; renderPalettes(); preview.setColors(p) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * dpx).toInt() })
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
            lAlpha.text = "背景浓淡  ${alpha.value.toInt()}%"
            lWidth.text = "字幕宽度  ${width.value.toInt()}%"
            lHeight.text = if (prefs.heightFrac <= 0f) "字幕高度  自动（跟着内容变）" else "字幕高度  屏幕的 ${Math.round(prefs.heightFrac * 100)}%"
            lKeepS.text = "屏幕上至少留  ${keepS.value.toInt()} 句"
            lKeepC.text = "字数超过  ${keepC.value.toInt()} 字后，收起最早的句子"
            keepHint.text = "屏幕上会留着刚说完的几句话，方便回看；句数和字数都超过上面的数字时，最早的一句才会收起。字幕框放不下时，只显示最新的内容。"
            // the row that opens the size page says what is set there now
            findViewById<TextView>(R.id.sizeSummary).text = "宽 ${width.value.toInt()}% · 高 " +
                (if (prefs.heightFrac <= 0f) "自动" else "${Math.round(prefs.heightFrac * 100)}%") +
                " · 至少留 ${keepS.value.toInt()} 句 · ${keepC.value.toInt()} 字"
            preview.setColors(CaptionOverlayView.palette(prefs.palette))
            preview.applyStyle(size.value, alpha.value / 100f)
            // same caption height as the overlay will get (the stage grows with it)
            preview.setMaxContentHeight((resources.displayMetrics.heightPixels * CaptionService.MAX_CAPTION_FRAC).toInt(), Int.MAX_VALUE)
            preview.setCaptionHeight((resources.displayMetrics.heightPixels * prefs.heightFrac).toInt())
            preview.layoutParams = FrameLayout.LayoutParams((findViewById<View>(R.id.previewStage).width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 0.8f).toInt()).let { (it * width.value / 100f).toInt() }, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            preview.setLines(
                listOf(CaptionOverlayView.Line("这一句已经说完，是正式字幕", if (ja.isChecked) "（这里显示原文）" else null, true),
                    CaptionOverlayView.Line("正在说的这一句…", if (ja.isChecked) "（原文）" else null, false)),
                if (stats.isChecked) "这里显示识别和翻译用了多久" else null,
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
            prefs.setStyle(20f, CaptionOverlayView.DEFAULT_ALPHA); prefs.widthFrac = Prefs.DEFAULT_WIDTH; prefs.showJa = false; prefs.showStats = false
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
        if (prefs.mtMode == MtMode.BYOK && !prefs.byokReady) {
            status(R.color.err, "自己的模型还没填完整", "请在下面「怎么翻译」里填好服务地址、模型 ID 和 API Key 并保存，或者换回在线翻译。")
            return
        }
        // a connectivity test still running would probe the same service again alongside the session's own probe
        byokTest?.let { it.cancel(); byokTest = null; renderTranslate() }
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
        else if (requestCode == REQ_START) status(R.color.err, "需要允许「听手机里播放的声音」", "只用来听 App 播放出来的声音，不会用麦克风。请再点一次开始，并选择「允许」。")
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
        /** Settings categories: the list shown for each, and its tab label. */
        val TABS: List<Pair<Int, String>> = listOf(R.id.tabCaption to "字幕", R.id.tabTranslate to "翻译", R.id.tabPage to "页面", R.id.tabRecords to "记录", R.id.tabAbout to "关于")
    }
}
