package io.github.korenkrita.simulsub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

private val prefs get() = Prefs.prefs
private const val REPO = "https://github.com/KorenKrita/simul-subtitle"

private fun open(url: String) = runCatching { Desktop.getDesktop().browse(URI(url)) }
private fun reveal(f: File) = runCatching { ProcessBuilder("/usr/bin/open", "-R", f.path).start() }
private fun sizeText(b: Long) = if (b >= 1e6) "%.1f MB".format(b / 1e6) else "%.0f KB".format(b / 1e3)

/** Starts captions from the home screen, the menu bar or the toolbar; null when it cannot, with the reason. */
private fun startCaptions(): String? {
    if (!prefs.byokReady) return "翻译模型还没填完整：请在设置「翻译」里填好服务地址、模型 ID 和 API Key 并保存。"
    if (!sysaudioFile.canExecute()) return "找不到系统声音采集程序（${sysaudioFile.path}）"
    // a 测试连接 still running would probe the same service the session probes on first use (billed twice)
    byokTest.value?.cancel(); byokTest.value = null
    CaptionService.start(sysaudioFile)
    return null
}

fun app() = application {
    var mainOpen by remember { mutableStateOf(true) }
    var startError by remember { mutableStateOf<String?>(null) }
    val running by CaptionService.runningState
    val dark = when (prefs.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }

    Tray(icon = TrayIcon(running), tooltip = "同传字幕", menu = {
        if (running) Item("停止字幕", onClick = { CaptionService.stop() })
        else Item("开始字幕", onClick = { startError = startCaptions(); if (startError != null) mainOpen = true })
        Item("打开同传字幕", onClick = { mainOpen = true })
        Separator()
        Item("退出", onClick = { CaptionService.stop(); exitApplication() })
    })

    // the caption window lives with the session, not with the main window
    CaptionService.current?.takeIf { running }?.let { s ->
        Themed(true) { OverlayWindow(s.overlay, prefs, onStop = { CaptionService.stop() }) }
    }

    if (mainOpen) {
        val state = rememberWindowState(size = DpSize(520.dp, 820.dp))
        // closing the window keeps the app (and its captions) in the menu bar
        Window(onCloseRequest = { mainOpen = false }, state = state, title = "同传字幕") {
            // the title bar is the system's: follow the page colour, not just macOS's own setting
            LaunchedEffect(dark) { window.rootPane.putClientProperty("apple.awt.windowAppearance", if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua") }
            Themed(dark) {
                Box(Modifier.fillMaxSize().background(C.bg)) { MainScreen(startError) { startError = it } }
            }
        }
    }
    AutoUpdateCheck()
}

/** The menu bar icon: a caption card, filled while captions run. Drawn, so it follows no file. */
private class TrayIcon(val on: Boolean) : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val w = size.width; val h = size.height
        val c = Color.Black
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.14f)
        val tl = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.2f)
        val sz = Size(w * 0.84f, h * 0.6f)
        if (on) drawRoundRect(c, tl, sz, r)
        else drawRoundRect(c, tl, sz, r, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.07f))
        val line = if (on) Color.White else c
        drawRect(line, androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.42f), Size(w * 0.52f, h * 0.07f))
        drawRect(line, androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.56f), Size(w * 0.36f, h * 0.07f))
    }
    override fun equals(other: Any?) = other is TrayIcon && other.on == on
    override fun hashCode() = on.hashCode()
}

@Composable
private fun MainScreen(startError: String?, setStartError: (String?) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (!settings) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("同传字幕", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Grow()
                TextBtn("设置") { settings = true }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) { Home(startError, setStartError) }
        } else {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
                TextBtn("‹ 返回") { settings = false }
                Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            }
            Box(Modifier.padding(horizontal = 18.dp)) {
                Seg(listOf(0 to "字幕", 1 to "翻译", 2 to "页面", 3 to "记录", 4 to "关于"), tab) { tab = it }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
                when (tab) { 0 -> CaptionTab(); 1 -> TranslateTab(); 2 -> PageTab(); 3 -> LogsTab(); else -> AboutTab() }
            }
        }
    }
}

// ---- home --------------------------------------------------------------------------------------------------

@Composable
private fun Home(startError: String?, setStartError: (String?) -> Unit) {
    val running by CaptionService.runningState
    val ended by CaptionService.endedState
    Text("把视频、直播里的日语和英语，实时变成字幕", color = C.text2, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
    Card {
        @Composable fun status(color: Color, title: String, body: String) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color); Spacer(Modifier.width(10.dp)); Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(body, color = C.text2, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 19.dp, top = 6.dp, bottom = 14.dp))
        }
        when {
            running && ended -> {
                status(C.warn, "字幕已经停了", "原因写在字幕框里。点下面的按钮关掉它，然后可以重新开始。")
                Btn("关掉字幕", modifier = Modifier.fillMaxWidth()) { CaptionService.stop() }
            }
            running -> {
                status(C.ok, "字幕正在显示", "回到你要看的视频或直播就行。\n点一下字幕，可以调大小或停止；关掉这个窗口字幕也会继续，菜单栏图标里可以停止。")
                Box(Modifier.fillMaxWidth().height(44.dp).background(C.errSoft, RoundedCornerShape(10.dp)).clickable { CaptionService.stop() },
                    contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(C.err, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp)); Text("停止字幕", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            else -> {
                if (startError != null) status(C.err, "还不能开始", startError)
                else status(C.brand, "可以开始了", "点下面的按钮开始。第一次会问你是否允许同传字幕录制系统声音，请选「允许」。")
                Box(Modifier.fillMaxWidth().height(44.dp).background(C.brand, RoundedCornerShape(10.dp))
                    .clickable { setStartError(startCaptions()) }, contentAlignment = Alignment.Center) {
                    Text("▶  开始字幕", color = C.onBrand, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    GroupTitle("语言")
    Card {
        val sources = Lang.SOURCES
        PickRow("视频里说的是", sources.map { it?.zh ?: "不确定（日语或英语）" }, sources.indexOf(prefs.sourceLang).coerceAtLeast(0)) {
            prefs.sourceLang = sources[it]
        }
        Divider()
        PickRow("字幕翻译成", Lang.entries.map { it.zh }, prefs.targetLang.ordinal) { prefs.targetLang = Lang.entries[it] }
    }
    Hint(if (running) "字幕运行中，改了从下一句开始生效。" else "知道是哪种语言就选上，翻译会更准。")
    Hint("声音只在电脑上识别，不会上传；用在线翻译时，会把识别出的文字发给翻译接口。")
}

// ---- 字幕 --------------------------------------------------------------------------------------------------

@Composable
private fun CaptionTab() {
    val sample = remember { OverlayState() }
    sample.lines.value = listOf(OverlayState.Line("这一句已经说完，是正式字幕", if (prefs.showJa) "（这里显示原文）" else null, true),
        OverlayState.Line("正在说的这一句…", if (prefs.showJa) "（原文）" else null, false))
    sample.diag.value = if (prefs.showStats) "这里显示识别和翻译用了多久" else null
    Group("外观") {
        Box(Modifier.fillMaxWidth().background(Color(0xFF3A3A40), RoundedCornerShape(10.dp)).padding(vertical = 16.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            CaptionCard(sample, prefs, modifier = Modifier.fillMaxWidth(prefs.widthFrac.coerceIn(Prefs.MIN_WIDTH, 1f)))
        }
        Label("字幕配色")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in PALETTES) {
                val sel = p.id == palette(prefs.palette).id
                Row(Modifier.height(40.dp).background(C.card, RoundedCornerShape(20.dp))
                    .border(if (sel) 2.dp else 1.dp, if (sel) C.text else C.line, RoundedCornerShape(20.dp))
                    .clickable { prefs.palette = p.id }.padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(Color(p.bg or 0xFF000000), CircleShape).border(1.dp, Color(0x33888888), CircleShape),
                        contentAlignment = Alignment.Center) { Text("字", color = Color(p.zh), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    Text(p.label, fontSize = 14.sp, color = if (sel) C.text else C.text2)
                }
            }
        }
        Label("文字大小  ${Math.round(prefs.textSp.coerceIn(MIN_SP, MAX_SP))}")
        Slider2(Math.round(prefs.textSp.coerceIn(MIN_SP, MAX_SP)).toFloat(), MIN_SP..MAX_SP, (MAX_SP - MIN_SP).toInt() - 1) { prefs.textSp = Math.round(it).toFloat() }
        Label("背景浓淡  ${Math.round(prefs.bgAlpha * 100)}%")
        Slider2(prefs.bgAlpha * 100, 0f..100f, 19) { prefs.bgAlpha = Math.round(it / 5) * 5 / 100f }
    }
    Group("显示") {
        SwitchRow("同时显示原文", null, prefs.showJa) { prefs.showJa = it }
        Divider()
        SwitchRow("显示识别和翻译用时", null, prefs.showStats) { prefs.showStats = it }
    }
    Group("字幕框大小和保留几句") {
        Label("字幕宽度  ${Math.round(prefs.widthFrac * 100)}%")
        Slider2((Math.round(prefs.widthFrac * 20) * 5f).coerceIn(40f, 100f), 40f..100f, 11) { prefs.widthFrac = Math.round(it / 5) * 5 / 100f }
        Label(if (prefs.heightFrac <= 0f) "字幕高度  自动（跟着内容变）" else "字幕高度  屏幕的 ${Math.round(prefs.heightFrac * 100)}%")
        Slider2(if (prefs.heightFrac <= 0f) 0f else (Math.round(prefs.heightFrac * 20) * 5f).coerceIn(5f, 40f), 0f..40f, 7) { prefs.heightFrac = Math.round(it / 5) * 5 / 100f }
        Label("屏幕上至少留  ${prefs.keepSentences.coerceIn(1, 6)} 句")
        Slider2(prefs.keepSentences.coerceIn(1, 6).toFloat(), 1f..6f, 4) { prefs.keepSentences = Math.round(it) }
        Label("字数超过  ${(Math.round(prefs.keepChars / 10f) * 10).coerceIn(0, 300)} 字后，收起最早的句子")
        Slider2((Math.round(prefs.keepChars / 10f) * 10f).coerceIn(0f, 300f), 0f..300f, 29) { prefs.keepChars = Math.round(it / 10) * 10 }
        Hint("屏幕上会留着刚说完的几句话，方便回看；句数和字数都超过上面的数字时，最早的一句才会收起。字幕框放不下时，只显示最新的内容。字幕框也可以直接拖动：点一下字幕，拖四个角上的小箭头。")
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Grow()
        TextBtn("恢复默认") { prefs.resetStyle(); prefs.setOverlay(null, null, Prefs.DEFAULT_WIDTH, 0f) }
    }
}

// ---- 翻译 --------------------------------------------------------------------------------------------------

/** the running BYOK connection test, so a second click or a new screen does not show a stale result */
private val byokTest = mutableStateOf<ByokTranslator?>(null)
private val byokResult = mutableStateOf<String?>(null)

@Composable
private fun TranslateTab() {
    val running by CaptionService.runningState
    if (running) Note("字幕正在运行：内容类型、结合上文、去掉重复的字、翻译模型（点保存后）和 Jev 在线模型服务、key 改了从下一句开始生效；智能断句和检查译文从下次开始字幕时生效；显示方式马上生效。")
    Group("怎么翻译") {
        Hint("视频里说的语言和要翻译成的语言，在首页选（现在是：${prefs.sourceLang?.zh ?: "不确定"} → ${prefs.targetLang.zh}）。")
        Byok(running)
    }
    Group("内容") {
        Label("内容类型")
        Seg(Scene.entries.map { it to it.label }, prefs.scene) { prefs.scene = it }
        Hint("选「B站 VTuber 直播」时，翻译会知道这是虚拟主播在 B 站和观众闲聊、念弹幕，并按 B 站的说法翻译「SC」「舰长」「弹幕」这类直播用语。")
        SwitchRow("结合上文来翻译", "翻译每句话时，把前面刚说过的几句一起交给翻译参考，帮助它理解人称、指代和省略的内容。参考的句子越多，翻译要处理的文字越多，可能更慢。",
            prefs.contextOn) { prefs.contextOn = it }
        if (prefs.contextOn) {
            Label("参考前面 ${prefs.contextSentences} 句")
            Slider2(prefs.contextSentences.toFloat(), 1f..Pipeline.MAX_PREVIOUS_SENTENCES.toFloat(), Pipeline.MAX_PREVIOUS_SENTENCES - 2) {
                prefs.contextSentences = Math.round(it)
            }
        }
    }
    Group("字幕怎么出现") {
        Seg(listOf(false to "边听边出", true to "说完再出"), prefs.finalOnly) { prefs.finalOnly = it }
        Hint("「边听边出」：一边说一边显示，灰色的字还会修改，说完一句后变成正式字幕。「说完再出」：每句话说完才显示，画面更干净，但会晚一点。字幕上的工具栏也能随时切换。")
        SwitchRow("去掉重复的字", "一句话分段识别时，接口处偶尔会把同一段话识别两遍。打开后自动去掉多出来的那一遍；如果说话人真的重复说了，也可能被去掉。",
            prefs.dedupeJoin) { prefs.dedupeJoin = it }
    }
    Group("在线 Decisions 模型辅助（可选，需要联网）") {
        SwitchRow("智能断句", "由 Jev 模型判断一句话是否真的说完，减少把一句话切成两半的情况。", prefs.jevOn) { prefs.jevOn = it }
        SwitchRow("检查译文", "由 Jev 模型检查每句译文是否把意思译反、漏了否定等，发现问题就重新翻译这一句。打开后每句字幕会稍晚出现。", prefs.jevCheck) { prefs.jevCheck = it }
        if (prefs.jevOn || prefs.jevCheck) {
            val p = prefs.jevProvider
            Label("Jev 在线模型服务")
            Dropdown(JevProvider.entries.map { "${it.label}（${it.note}）" }, p.ordinal) { prefs.jevProvider = JevProvider.entries[it] }
            if (!p.needsKey) Hint("不用填 key，打开就能用。这是免费的公共服务，可能随时限速或停用；服务不可用时，字幕照常显示，只是不做辅助。只发送识别出的文字和译文，不发送声音。")
            else {
                val saved = prefs.jevKey(p)
                Label(if (saved != null) "${p.label} 的 key：已保存（…${saved.takeLast(4)}）" else "${p.label} 的 key：还没填")
                var input by remember(p) { mutableStateOf("") }
                var err by remember(p) { mutableStateOf<String?>(null) }
                Field(input, { input = it; err = null }, if (saved != null) "输入新的 key 来替换" else "在这里粘贴 key", password = true)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    err?.let { Text(it, color = C.err, fontSize = 13.sp) }
                    Grow()
                    TextBtn("清除", enabled = saved != null) { if (!prefs.setJevKey(p, null)) err = "没能从钥匙串删掉 key，请再试一次" }
                    Btn("保存", primary = true) {
                        val k = input.trim()
                        when {
                            k.isEmpty() -> {}
                            // a key with spaces/newlines/non-ASCII would be rejected by HTTP (and echoed in its error)
                            !Jev.validKey(k) -> err = "key 里有空格、换行或特殊字符，请重新复制"
                            !prefs.setJevKey(p, k) -> err = "key 没能存进钥匙串，请再试一次"
                            else -> input = ""
                        }
                    }
                }
                Hint("只发送识别出的文字和译文，不发送声音。key 保存在这台 Mac 的钥匙串里，不会写进使用记录。")
            }
        }
    }
}

@Composable
private fun Byok(running: Boolean) {
    Hint("翻译用你自己的模型服务（OpenAI 兼容接口）。只发送识别出的文字，不发送声音。\n模型要求：必须能关闭思考（深度思考 / reasoning），否则每句都要等很久；尽量选回复速度快的模型，字幕才跟得上。")
    var url by remember { mutableStateOf(prefs.byokUrl) }
    var model by remember { mutableStateOf(prefs.byokModel) }
    var key by remember { mutableStateOf("") }
    val saved = prefs.byokKey
    val testing = byokTest.value != null
    var msg by byokResult
    Label("服务地址（OpenAI 兼容）")
    Field(url, { url = it }, "例如 https://api.deepseek.com，结尾有没有 /v1 都可以", enabled = !testing)
    Label("模型 ID")
    Field(model, { model = it }, "例如 deepseek-chat", enabled = !testing)
    Label(if (saved != null) "API Key：已保存（…${saved.takeLast(4)}）" else "API Key：还没填")
    Field(key, { key = it }, if (saved != null) "输入新的 key 来替换" else "在这里粘贴 key", password = true, enabled = !testing)
    // save what is in the boxes; null when something is wrong (and says what)
    fun save(): String? {
        val u = url.trim(); val m = model.trim(); val k = key.trim()
        val (bad, _) = ByokTranslator.chatUrl(u)
        when {
            bad != null -> { msg = "⚠ $bad"; return null }
            m.isEmpty() -> { msg = "⚠ 还没填模型 ID"; return null }
            m.any { it.isWhitespace() } -> { msg = "⚠ 模型 ID 里不要有空格"; return null }
            k.isNotEmpty() && !Jev.validKey(k) -> { msg = "⚠ key 里有空格、换行或特殊字符，请重新复制"; return null }
            k.isEmpty() && saved == null -> { msg = "⚠ 还没填 API Key"; return null }
        }
        // one edit; a running session switches to the saved service from the next sentence, as one change
        if (!prefs.saveByok(u, m, k.ifEmpty { null })) { msg = "⚠ key 没能存进钥匙串，什么都没改。请再试一次；钥匙串要求授权时请选「允许」。"; return null }
        key = ""
        return ByokTranslator.chatUrl(u).second
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Grow()
        // running: "保存" switches the session from the next sentence (it tests a new model itself), so no test
        // (two probes would bill twice) and no clearing the key (the session needs one)
        TextBtn("清除 key", enabled = saved != null && !running && !testing) {
            msg = if (prefs.clearByokKey()) null else "⚠ 没能从钥匙串删掉 key，请再试一次。"
        }
        Btn("保存", enabled = !testing) {
            if (save() != null) msg = if (running) "已保存，字幕从下一句开始用新的设置；第一次用这个模型时会先自动测试怎样关闭思考。" else null
        }
        Btn(if (testing) "测试中…" else "测试连接", primary = true, enabled = !running && !testing) {
            val chatUrl = save() ?: return@Btn
            val m = prefs.byokModel; val k = prefs.byokKey ?: return@Btn
            val t = ByokTranslator(chatUrl, m, k, byokSystem(prefs.scene, prefs.sourceLang, prefs.targetLang), null,
                maxTokens = mtMaxTokens(prefs.targetLang))
            byokTest.value = t; msg = null
            Thread {
                val steps = mutableListOf<ByokTranslator.Step>()
                val v = t.probe(steps)
                java.awt.EventQueue.invokeLater {
                    if (byokTest.value !== t) return@invokeLater // cancelled (captions started)
                    byokTest.value = null
                    val ok = steps.firstOrNull { it.variant === v }?.result
                    val none = v == null && t.exhausted(steps)
                    when {
                        v != null -> prefs.setByokVariant(chatUrl, m, v.name)
                        none -> prefs.setByokVariant(chatUrl, m, ByokTranslator.NONE_WORKED)
                    }
                    byokResult.value = when {
                        ok != null -> "✓ 可以用。参数：${v!!.label}（这次没检测到思考，已记住）；收到第一个字 ${ok.firstContentMs ?: "?"} ms" +
                            (if ((ok.firstContentMs ?: 0) > FIRST_CONTENT_MS) "（超过 ${FIRST_CONTENT_MS / 1000} 秒，翻译时这句会判为失败）" else "") + "。\n测试译文：${ok.text.take(60)}"
                        none -> "⚠ 连得上，但试过的 ${steps.size} 种参数都没能关掉思考，翻译可能很慢或经常失败。仍然可以使用。"
                        else -> "✗ 不能用：${steps.lastOrNull()?.result?.let { ByokTranslator.why(it) } ?: "没有回应"}"
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }
    val chat = ByokTranslator.chatUrl(prefs.byokUrl).second
    val known = chat?.let { prefs.byokVariant(it, prefs.byokModel) }
    Hint(msg ?: when {
        testing -> "正在依次尝试关闭思考的参数（最多 6 次，每次最多 8 秒，每次都会计费）…"
        chat == null || prefs.byokModel.isEmpty() || saved == null -> "填好地址、模型 ID 和 key 后点「测试连接」。第一次使用时会依次试最多 6 种关闭思考的参数（每种是一次计费请求），找到没检测到思考的就记住。"
        known == ByokTranslator.NONE_WORKED -> "⚠ 上次测试：已知的参数都关不掉这个模型的思考，翻译可能很慢。仍然可以使用。"
        known != null -> "上次测试可用（用的参数：${ByokTranslator.variant(known)?.label}，那次没检测到思考）。实际请求地址：$chat"
        else -> "还没测试，第一次开始字幕时会自动测。实际请求地址：$chat"
    })
}

// ---- 页面 --------------------------------------------------------------------------------------------------

@Composable
private fun PageTab() {
    Group("页面颜色") {
        Hint("这个窗口用白天还是夜间颜色。不影响悬浮字幕，字幕颜色在「字幕」里选。")
        Seg(listOf("system" to "跟随系统", "light" to "白天", "dark" to "夜间"), prefs.theme) { prefs.theme = it }
    }
}

// ---- 记录 --------------------------------------------------------------------------------------------------

@Composable
private fun LogsTab() {
    var tick by remember { mutableIntStateOf(0) } // relists after a change, and every few seconds (a session may end)
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
    Group("使用记录") {
        SwitchRow("记录每次使用", "打开后，每次开始字幕会在电脑上留一份文字记录：识别结果、翻译的提示词和译文、在线 Decisions 模型辅助的请求和回答，不含声音和 key。只有你自己发出去才会离开电脑，出问题时可以发给开发者帮助排查。从下次开始字幕起生效。",
            prefs.keepLogs) { prefs.keepLogs = it }
        tick.let { }
        val files = SessionLog.list(dataDir)
        var confirm by remember { mutableStateOf<File?>(null) }
        var confirmAll by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }
        if (files.isEmpty()) Hint(if (prefs.keepLogs) "还没有记录。" else "还没有记录。现在不记录，打开上面的开关后才会记录。")
        for (f in files) {
            Divider()
            val active = SessionLog.isActive(f)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name.removeSuffix(".part").removeSuffix(".jsonl"), fontSize = 14.sp)
                    Text(sizeText(f.length()) + when { active -> " · 正在记录，结束后可以发送"; !SessionLog.complete(f) -> " · 没有正常结束"; else -> "" },
                        fontSize = 12.sp, color = C.muted)
                }
                if (confirm == f) {
                    TextBtn("取消") { confirm = null }
                    TextBtn("删除", danger = true) { confirm = null; if (!SessionLog.delete(f)) msg = "删除失败：记录正在写入或文件被占用"; tick++ }
                } else {
                    TextBtn("删除", enabled = !active) { confirm = f }
                    TextBtn("在访达中显示", enabled = !active) { reveal(f) }
                }
            }
        }
        msg?.let { Hint(it, C.err) }
        if (files.isNotEmpty()) {
            Divider()
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${files.size} 个，${sizeText(files.sumOf { it.length() })}", fontSize = 13.sp, color = C.muted)
                Grow()
                if (confirmAll) {
                    TextBtn("取消") { confirmAll = false }
                    TextBtn("删除全部（正在记录的不删）", danger = true) {
                        confirmAll = false
                        val left = SessionLog.deleteAll(dataDir)
                        msg = if (left > 0) "还剩 $left 个（正在记录或无法删除）" else null; tick++
                    }
                } else TextBtn("全部清空", danger = true) { confirmAll = true }
            }
        }
    }
}


// ---- 关于 --------------------------------------------------------------------------------------------------

@Composable
private fun AboutTab() {
    Group("使用说明") {
        Text("1. 点首页的「开始字幕」。第一次会问你是否允许录制系统声音，请选「允许」（以后可以在 系统设置 › 隐私与安全性 › 屏幕与系统录音 里改）。\n" +
            "2. 字幕浮在所有窗口上面：按住拖动可以换位置；点一下字幕，四个角会出现小箭头，拖它可以改大小。\n" +
            "3. 点一下字幕会出现工具栏：调字号、调背景、切换显示方式、开关原文和用时、锁定，或者停止。锁定后位置和大小都固定，点字幕不再出工具栏，只在右下角出一个解锁按钮，点它就解锁。\n" +
            "4. 字幕听的是这台 Mac 播放出来的所有声音，不用麦克风；同时有别的 App 在放声音时，它的声音也会出现在字幕里。\n" +
            "5. 关掉这个窗口字幕也会继续，菜单栏的同传字幕图标里可以开始、停止，或重新打开这个窗口。\n" +
            "6. 在「记录」里打开「记录每次使用」后，每次使用会留下一份文字记录（不含声音和 key），可以发给开发者帮助反馈问题。默认不记录。",
            fontSize = 14.sp, lineHeight = 22.sp, color = C.text)
    }
    Group("关于") {
        Text("同传字幕  版本 $APP_VERSION", fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
        Divider()
        SwitchRow("自动检查更新", null, prefs.autoUpdate) { prefs.autoUpdate = it }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (update.value is Update.Checking) "检查中…" else "检查更新", enabled = update.value !is Update.Checking,
                modifier = Modifier.weight(1f)) { checkNow(manual = true) }
            Btn("GitHub 项目主页", modifier = Modifier.weight(1f)) { open(REPO) }
        }
        when (val u = update.value) {
            is Update.Found -> UpdateBox(u.release)
            is Update.Latest -> Hint("已经是最新版本。")
            is Update.Failed -> Hint("检查失败：${u.why}", C.err)
            else -> {}
        }
        Hint(REPO)
        Hint("语音识别用的是 ReazonSpeech 模型和 Silero VAD（通过 sherpa-onnx 在电脑上运行，模型已经装在 App 里）。感谢这些项目。")
    }
}

// ---- updates -----------------------------------------------------------------------------------------------

/** The latest release as update.json describes it; the Mac only needs its version and notes. */
private class Release(val code: Int, val name: String, val notes: String)

private sealed class Update {
    object Idle : Update(); object Checking : Update(); object Latest : Update()
    class Found(val release: Release) : Update(); class Failed(val why: String) : Update()
}
private val update = mutableStateOf<Update>(Update.Idle)
/** a newer release found by the automatic check, shown as a window until the user decides */
private val offered = mutableStateOf<Release?>(null)

private val UPDATE_CLIENT = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

/** update.json from GitHub, then the download proxies (same order as the phone); null with the last error otherwise */
private fun fetchRelease(): Pair<Release?, String?> {
    var why: String? = null
    val latest = "$REPO/releases/latest/download/update.json"
    for (u in listOf(latest, "https://gh-proxy.com/$latest", "https://gh.llkk.cc/$latest")) {
        try {
            UPDATE_CLIENT.newCall(Request.Builder().url(u).build()).execute().use { r ->
                if (!r.isSuccessful) { why = "服务器返回 ${r.code}"; return@use }
                val text = r.body!!.byteStream().use { it.readNBytes(128 * 1024 + 1) }
                require(text.size <= 128 * 1024)
                val j = JSONObject(String(text))
                val code = j.get("versionCode").toString().toInt()
                val name = j.getString("versionName")
                require(code > 0 && name.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")))
                return Release(code, name, j.optString("notes").take(8_000)) to null
            }
        } catch (e: Exception) { why = e.message ?: e.javaClass.simpleName }
    }
    return null to (why ?: "没有回应")
}

private fun checkNow(manual: Boolean) {
    if (update.value is Update.Checking) return
    update.value = Update.Checking
    Thread {
        val (r, why) = fetchRelease()
        java.awt.EventQueue.invokeLater {
            update.value = when {
                r == null -> if (manual) Update.Failed(why ?: "") else Update.Idle
                r.code > APP_VERSION_CODE -> Update.Found(r)
                else -> if (manual) Update.Latest else Update.Idle
            }
            if (!manual && r != null && r.code > APP_VERSION_CODE && r.code != prefs.skippedUpdate) offered.value = r
        }
    }.apply { isDaemon = true }.start()
}

@Composable
private fun UpdateBox(r: Release) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(C.brandSoft, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text("有新版本 ${r.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (r.notes.isNotBlank()) Text(r.notes, fontSize = 13.sp, lineHeight = 20.sp, color = C.text2, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("打开下载页", primary = true) { open("$REPO/releases/tag/v${r.name}") }
        }
        Hint("下载页里的 .dmg 是 Mac 版。打开后把同传字幕拖进「应用程序」替换旧版即可，设置和模型都会保留。")
    }
}

/** Checks once at launch when automatic checks are on; a newer release opens a small window. */
@Composable
private fun ApplicationScope.AutoUpdateCheck() {
    LaunchedEffect(Unit) { if (prefs.autoUpdate && APP_VERSION_CODE > 0) { delay(3_000); checkNow(manual = false) } }
    val r = offered.value ?: return
    val dark = when (prefs.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    Window(onCloseRequest = { offered.value = null }, title = "同传字幕 有新版本", state = rememberWindowState(size = DpSize(440.dp, 360.dp))) {
        Themed(dark) {
            Column(Modifier.fillMaxSize().background(C.bg).padding(18.dp).verticalScroll(rememberScrollState())) {
                UpdateBox(r)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Grow()
                    TextBtn("跳过这个版本") { prefs.skippedUpdate = r.code; offered.value = null }
                    TextBtn("以后再说") { offered.value = null }
                }
            }
        }
    }
}
