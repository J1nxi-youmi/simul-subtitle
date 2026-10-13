package io.github.korenkrita.simulsub

import androidx.compose.runtime.mutableStateOf
import io.github.korenkrita.simulsub.ServiceStatus.jevShown
import io.github.korenkrita.simulsub.ServiceStatus.light
import io.github.korenkrita.simulsub.ServiceStatus.reason
import io.github.korenkrita.simulsub.ServiceStatus.serviceWarning
import java.io.File

/** What the caption window shows; written on the UI thread, read by Compose. */
class OverlayState {
    class Line(val zh: String, val ja: String?, val isFinal: Boolean)
    val lines = mutableStateOf<List<Line>>(emptyList())
    val diag = mutableStateOf<String?>(null)
    val mtLight = mutableStateOf(ServiceStatus.Light.GREY)
    val jevLight = mutableStateOf(ServiceStatus.Light.GREY)
    val mtReason = mutableStateOf<String?>(null)
    val jevReason = mutableStateOf<String?>(null)
}

/** The UI thread (Compose / AWT), as the phone's main looper. */
private object Main {
    fun post(r: () -> Unit) = java.awt.EventQueue.invokeLater(r)
    fun post(r: Runnable) = java.awt.EventQueue.invokeLater(r)
    fun postDelayed(r: Runnable, ms: Long): javax.swing.Timer =
        javax.swing.Timer(ms.toInt().coerceAtLeast(0)) { r.run() }.apply { isRepeats = false; start() }
}

private object Log {
    fun i(tag: String, m: String) = System.err.println("I/$tag: $m")
    fun w(tag: String, m: String, e: Throwable? = null) { System.err.println("W/$tag: $m"); e?.printStackTrace() }
}

/**
 * One captioning session, the phone's CaptionService on the Mac: system audio (tools/mac-capture's sysaudio, 16 kHz
 * mono f32 LE on its stdout) → Silero VAD → [Segmenter] → [Pipeline] → the caption window ([overlay]).
 * Everything heavy happens on its own threads; the UI thread only renders.
 */
class CaptionService private constructor(private val sysaudio: File) {
    private val main = Main

    /**
     * One "session" thread owns every native object (VAD, ASR, MT) and the sysaudio child from creation to release:
     * load → capture loop → stop pipeline → wait for its workers → release. [destroy] only flips [stopping]
     * and ends the child, so nothing is ever freed while another thread is still inside it.
     */
    @Volatile private var stopping = false
    /** Guards [pipeline]/[translator] so stop's cancel never races the session thread's native free. */
    private val cancelLock = Any()
    private var pipeline: Pipeline? = null
    private var translator: Mt? = null
    /** A BYOK first-use probe in progress (at start or after "保存" while running), so stop can cancel it. */
    private var probing: ByokTranslator? = null
    /** The running session's settings versions and BYOK probe (shares [cancelLock]); null outside a session. */
    @Volatile private var order: SetupOrder<Triple<String, String, String>, ByokTranslator.ThinkingOff?>? = null
    /** The running session's rebuild trigger; null before its pipeline exists and after it ended. */
    @Volatile private var onSetupChange: (() -> Unit)? = null
    /** the sysaudio child, under [cancelLock]: destroyed by stop, also while it is being launched */
    private var capture: Process? = null

    private val board by lazy { CaptionBoard(prefs.keepSentences, prefs.keepChars) }
    // main thread only
    private val lastUpdateMs = HashMap<Pair<Long, Int>, Long>()
    @Volatile private var backlog = 0
    @Volatile private var overloaded = false
    /** a pipeline worker failed: its error stays on screen */
    @Volatile private var failed = false
    /**
     * The Jev client of the setup published last; its pause shows without a new request, and a 429 of its service
     * installed by any client (also one of an earlier session, or after the last result) refreshes. Main thread only.
     */
    private var activeJudge: Judge? = null
        set(j) {
            if (j === field) return
            jevWatch?.close()
            // an activation queued before stop and run after it must not watch again
            jevWatch = if (stopping) null else j?.watch { refresh() }
            field = j
        }
    private var jevWatch: AutoCloseable? = null
    /** Jev's light state now ([jevShown]); main thread only. */
    private fun jevNow(): ServiceCall? = jevShown(jevState, activeJudge?.paused(), activeJudge?.lastLimit(), jevFrom)
    /** shows a pause that just ended as such; scheduled by [refresh] for the pause on screen */
    private val pauseOver: Runnable = Runnable { refresh() }
    private var pauseTimer: javax.swing.Timer? = null
    /** the translator in use: BYOK by the service its probe accepted, others by model */
    private fun mtService(s: Snap, byok: Triple<String, String, String>?) = byok?.let { "${it.first}\n${it.second}\n${it.third}" } ?: s.mtLabel
    /** Last real result of the translator / Jev in this session (null: not asked yet); main thread only. */
    private var mtState: ServiceCall? = null
    private var jevState: ServiceCall? = null
    /** Jev is used this session (one of its switches on) and which of them; set before the pipeline starts. */
    @Volatile private var jevEndsOn = false
    @Volatile private var jevCheckOn = false
    /**
     * Oldest setup version whose results count for each light: raised when a published setup uses another
     * translator / Jev service, so a slow request to the old one does not light the new one. Main thread only.
     */
    private var mtFrom = 0
    private var jevFrom = 0
    /** this session has a Jev judge: the segmenter asks it at sentence ends */
    @Volatile private var jevActive = false
    @Volatile private var logWarned = false

    val overlay = OverlayState()
    private val prefs = Prefs.prefs
    @Volatile private var status = "正在准备…"
    /** the session ended by itself (overload, error, capture lost): the window stays with its last message */
    @Volatile var ended = false; private set

    private fun begin() {
        listenPrefs()
        refresh()
        // fixed at the start: switching it while models load affects the next session, as the switch says
        val keepLogs = prefs.keepLogs
        sessions.incrementAndGet() // until the session thread has freed every native model (see [modelsInUse])
        Thread({ try { session(keepLogs) } finally { sessions.decrementAndGet(); main.post { modelsBusy.value = modelsInUse } } },
            "jpsub-session").start()
    }

    private fun session(keepLogs: Boolean) {
        val root = modelsDir
        val files = ModelFiles(root)
        var vad: SileroProb? = null
        var asr: SherpaAsr? = null
        var local: LlamaTranslator? = null
        var log: SessionLog? = null
        var keepOverlay = false
        try {
            // Fixed for the whole session (the home screen disables them while running): how it translates and the two
            // Jev switches. Everything else a sentence is translated with is a [Setup], rebuilt when it changes (below).
            val mode = prefs.mtMode
            val jevEnds = prefs.jevOn; val jevCheck = prefs.jevCheck
            val wantJev = jevEnds || jevCheck
            // a change from here on is seen by the snapshot below or rebuilt after the start
            val order = SetupOrder<Triple<String, String, String>, ByokTranslator.ThinkingOff?>(cancelLock).also { this.order = it }
            val v0 = order.version()
            val s0 = Snap(prefs, mode, wantJev, files.mt.name)
            if (mode == MtMode.BYOK && (s0.byokUrl == null || s0.byokModel.isEmpty() || s0.byokKey == null)) {
                setStatus("⚠ 自己的模型还没填完整（地址、key、模型 ID），请到设置里填好"); Log.w(TAG, "byok incomplete"); return
            }
            // the home screen only starts with verified models; if they vanished since, end cleanly (finally → stop)
            if (!ModelStore(root, needsMt = { mode == MtMode.LOCAL }).ready()) { Log.w(TAG, "models missing, not starting"); return }
            val t0 = monoMs()
            vad = SileroProb(files)
            asr = SherpaAsr(files, ASR_THREADS)
            val t1 = monoMs()
            if (stopping) return
            if (mode == MtMode.LOCAL) {
                setStatus("正在准备翻译…")
                // one native model for the session; each setup uses it through its own length cap
                local = LlamaTranslator(files, MT_THREADS).also { synchronized(cancelLock) { translator = it } }
            }
            val t2 = monoMs()
            // Warm-up so the first real sentence does not pay page-in / first-run costs. Online: nothing to warm up,
            // and no request is sent that the session log would not show.
            asr.recognize(FloatArray(16_000))
            local?.translate(indexPrompt("こんにちは。", Scene.GENERAL))
            val t3 = monoMs()
            Log.i(TAG, "load asr=${t1 - t0}ms mt=${t2 - t1}ms warmup=${t3 - t2}ms")
            if (stopping) return
            setStatus("准备好了，开始播放就会出字幕")

            // off by default: a log is written only after the user turned it on in settings
            val sl = if (!keepLogs) null
                else SessionLog(SessionLog.dir(dataDir), onFail = { Log.w(TAG, "session log: $it"); logWarned = true; main.post { refresh() } })
            log = sl
            sl?.write(mapOf("ev" to "session_start", "app" to APP_VERSION,
                "jev_requested" to jevEnds, "jev_ends" to jevEnds, "jev_check" to jevCheck, "final_only" to prefs.finalOnly,
                "mt_engine" to (when (mode) { MtMode.INDEX -> "online"; MtMode.LOCAL -> "local"; MtMode.BYOK -> "byok" }),
                "load_ms" to (t3 - t0), "device" to "Mac ${System.getProperty("os.version")} ${System.getProperty("os.arch")}") + s0.log())

            /**
             * BYOK probes: a URL + model used for the first time is first probed for the switch that turns thinking off
             * (logged like any MT call). At the start the session waits for it; while running it runs on [probeExec] and
             * the service in use keeps translating until it is done, so other changes are not held up behind it.
             * [order] keeps this session's results, also an inconclusive one (no switch stored, used without one), and
             * tells a wanted probe from one a newer save or the end made pointless.
             */
            val probeExec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "jpsub-probe") }
            /** The BYOK service of the setup published last (session / setup worker thread only). */
            var inUse: Triple<String, String, String>? = null

            /** Runs probe [token] of [t]; false: stopped, closed or overtaken by a newer save (nothing recorded). */
            fun runProbe(t: Triple<String, String, String>, token: Long, system: String, target: Lang): Boolean {
                val asking = "第一次用这个模型，正在试怎样关闭思考…"
                val before = status
                val probe = ByokTranslator(t.first, t.second, t.third, system, null, maxTokens = mtMaxTokens(target))
                synchronized(cancelLock) {
                    // registered under the lock a newer save or the session end cancels under: neither slips in between
                    if (stopping || !order.current(token)) return false
                    probing = probe
                }
                setStatus(asking)
                try {
                    val steps = mutableListOf<ByokTranslator.Step>()
                    val variant = probe.probe(steps)
                    steps.forEach { st -> sl?.write(mapOf("ev" to "byok_probe", "variant" to st.variant.name, "request" to st.result.request,
                        "http" to st.result.http, "response" to st.result.response, "error" to st.result.error, "usable" to st.result.usable,
                        "thinking" to st.result.thinking, "first_content_ms" to st.result.firstContentMs)) }
                    val none = variant == null && probe.exhausted(steps)
                    // stopped early (service error): no switch is stored, the next session tries again
                    val trouble = if (variant == null && !none)
                        "⚠ 你的翻译服务现在不能用：${steps.lastOrNull()?.result?.let { ByokTranslator.why(it) } ?: "没有回应"}" else null
                    synchronized(cancelLock) {
                        if (probing === probe) probing = null
                        // cancelled by a newer save or the end: a partial result says nothing about this model
                        if (stopping || !order.endProbe(t, token, variant, trouble)) return false
                    }
                    when {
                        variant != null -> prefs.setByokVariant(t.first, t.second, variant.name)
                        none -> prefs.setByokVariant(t.first, t.second, ByokTranslator.NONE_WORKED)
                    }
                    return true
                } finally {
                    synchronized(cancelLock) { if (probing === probe) probing = null }
                    if (status == asking) setStatus(before) // unless something more important replaced it meanwhile
                }
            }

            /** A built setup, the warnings it comes with, and for BYOK the service it really uses and its log entry. */
            class Built(val setup: Setup, val notice: String, val byok: Triple<String, String, String>?, val byokEv: Map<String, Any?>?)

            /**
             * The translator for BYOK and the service it uses. [wait]: probe a new service here (the start); otherwise
             * start its probe in the background and keep the service in use (with the new prompt) until it is done.
             * null: stopped, or overtaken by a newer change (whose own rebuild follows).
             */
            fun byok(s: Snap, version: Int, wait: Boolean, notices: MutableList<String>): Triple<ByokTranslator, Triple<String, String, String>, Map<String, Any?>>? {
                val t = Triple(s.byokUrl!!, s.byokModel, s.byokKey!!)
                val system = byokSystem(s.scene, s.source, s.target)
                fun known(k: Triple<String, String, String>): Pair<Boolean, ByokTranslator.ThinkingOff?> {
                    val stored = prefs.byokVariant(k.first, k.second)
                    if (stored != null) return true to ByokTranslator.variant(stored)
                    return if (order.knows(k)) true to order.result(k) else false to null
                }
                // the log names the service really used (never the key): the one saved last may still be in its probe
                fun use(k: Triple<String, String, String>, variant: ByokTranslator.ThinkingOff?) = Triple(
                    ByokTranslator(k.first, k.second, k.third, system, variant, maxTokens = mtMaxTokens(s.target)), k,
                    mapOf("variant" to variant?.name, "system" to system, "mt_url" to k.first, "mt_model" to k.second))
                var (ready, variant) = known(t)
                if (!ready) {
                    val keep = inUse
                    val token = order.beginProbe(t, version)
                    when {
                        // refused because its probe finished since known() above: use that result
                        token == null && order.knows(t) -> variant = known(t).second
                        wait || keep == null -> {
                            if (token == null || !runProbe(t, token, system, s.target)) return null
                            variant = known(t).second
                        }
                        else -> {
                            if (token != null) {
                                val started = runCatching {
                                    probeExec.execute {
                                        if (runProbe(t, token, system, s.target) && order.changed())
                                            onSetupChange?.invoke() // now build with the tested service
                                    }
                                }.isSuccess
                                if (!started) return null // the session is ending
                            } else if (!order.isProbing(t)) return null // overtaken: the newer change builds again
                            notices += "正在测试新保存的模型，测好之前先用原来的"
                            return use(keep, known(keep).second)
                        }
                    }
                }
                order.trouble(t)?.let { notices += it }
                if (variant == null && prefs.byokVariant(t.first, t.second) == ByokTranslator.NONE_WORKED)
                    notices += "⚠ 关不掉这个模型的思考，翻译可能很慢或经常失败"
                return use(t, variant)
            }
            /** A setup for [s] and the warnings it comes with; null: stopped, or overtaken (the newer change builds). */
            fun make(s: Snap, version: Int, wait: Boolean): Built? {
                val notices = mutableListOf<String>()
                var used: Triple<String, String, String>? = null
                var ev: Map<String, Any?>? = null
                val mt: Mt = when (mode) {
                    MtMode.INDEX -> OnlineTranslator(s.online!!, maxTokens = mtMaxTokens(s.target))
                    MtMode.LOCAL -> LlamaTranslator.Capped(local!!, mtMaxTokens(s.target))
                    MtMode.BYOK -> {
                        val (t, k, e) = byok(s, version, wait, notices) ?: return null
                        used = k; ev = e; t
                    }
                }
                // one Jev client for both features; no key = both paused until one is entered
                // pauses count deep sleep: one kept across sessions ends at the time the service named
                val judge = s.jevKey?.let {
                    Jev(s.provider, it, source = s.source, target = s.target)
                }
                if (wantJev && judge == null) notices += "⚠ 在线 Decisions 模型辅助已暂停：还没填 ${s.provider.label} 的 key"
                return Built(Setup(mt, s.scene, s.source, s.target, mode == MtMode.BYOK, judge, s.dedupe, s.contextN, version),
                    notices.joinToString("  ·  "), used, ev)
            }
            /** Log a built setup that is now in use: its BYOK details, and [change] = also as a config_change of [s]. */
            fun logUsed(b: Built, s: Snap, change: Boolean) {
                b.byokEv?.let { sl?.write(mapOf("ev" to "byok") + it) }
                // the service really in use: while a newly saved one is probed that is still the previous one
                if (change) sl?.write(mapOf("ev" to "config_change", "setup" to b.setup.version) + s.log() +
                    (b.byokEv?.filterKeys { it == "mt_url" || it == "mt_model" } ?: emptyMap()))
            }
            // A change during the start probe overtakes it: build again from the newest settings (stop ends the loop).
            // Taken only while its version is still the newest, under the lock every change bumps it under, like a
            // setup published while running; a dropped one is not cancelled (a Capped shares the native model).
            var firstSnap = s0
            var v = v0
            var built = make(s0, v0, wait = true)
            while (!stopping && (built == null || !order.publish(v) { true })) {
                v = order.version()
                val s = Snap(prefs, mode, wantJev, files.mt.name)
                if (mode == MtMode.BYOK && (s.byokUrl == null || s.byokModel.isEmpty() || s.byokKey == null)) return
                firstSnap = s
                built = make(s, v, wait = true)
            }
            if (stopping || built == null) return
            val first = built.setup
            notice = built.notice
            inUse = built.byok
            jevEndsOn = jevEnds; jevCheckOn = jevCheck
            // the services really in use (BYOK: the one its probe accepted, not a newly saved one still probed)
            var mtId = mtService(firstSnap, built.byok); var jevId = firstSnap.jevKey?.let { firstSnap.provider.name + "\n" + it }
            first.judge.let { j -> main.post { activeJudge = j; refresh() } }
            // the settings in use are not the ones session_start logged: say so, like a change while running
            logUsed(built, firstSnap, change = firstSnap !== s0)
            val pl = Pipeline(
                asr, first.mt,
                onCaption = { onCaption(it, sl) },
                onBacklog = { backlog = it; refresh() },
                onOverload = {
                    overloaded = true // capture loop exits, then accepted finals are drained below
                    setStatus("⚠ 电脑忙不过来，先停止听新的声音，正在处理已经听到的句子…")
                },
                onFailure = { e ->
                    Log.w(TAG, "pipeline worker failed", e)
                    setStatus("出错：${e.message}。点 ✕ 结束后可以重新开始。")
                    failed = true
                    stopCapture() // the loop sees EOF; the window stays with the error until the user stops
                },
                scene = first.scene,
                source = first.source,
                target = first.target,
                byokPrompt = first.byokPrompt,
                judge = first.judge,
                judgeEnds = jevEnds,
                checkTranslations = jevCheck,
                dedupeJoin = first.dedupeJoin,
                previousSentences = first.previousSentences,
                setupVersion = first.version,
                log = { e -> sl?.write(e) },
                drafts = { !prefs.finalOnly }, // "only finished sentences" saves the draft work, not just hides it
                onService = { jev, c ->
                    main.post {
                        // a slow request to a service replaced since says nothing about the current one
                        if (c.setup < if (jev) jevFrom else mtFrom) return@post
                        if (jev) jevState = c else mtState = c
                        refresh()
                    }
                },
            )
            // The segmenter's judged hold and its timings follow the sentence-end switch; while its key is missing the
            // pipeline answers "finished" at once, so a cut happens at the shorter Jev limit.
            jevActive = jevEnds

            // Settings changed while running: one worker rebuilds the setup from a fresh snapshot, so a burst of changes
            // (a slider) makes one or two setups, and setups are published in order. A rebuild that a newer change
            // overtook is dropped; the newer one's own task builds again.
            var published = first.version
            val rebuild = Runnable {
                try {
                    val v = order.version()
                    if (v == published || stopping) return@Runnable
                    val s = Snap(prefs, mode, wantJev, files.mt.name)
                    // BYOK is only switched by "保存", which checks the fields; a half-filled one keeps the current setup
                    if (mode == MtMode.BYOK && (s.byokUrl == null || s.byokModel.isEmpty() || s.byokKey == null)) return@Runnable
                    val b = make(s, v, wait = false) ?: return@Runnable
                    // the version check and the publication under the lock every change bumps the version under: a newer
                    // change or the session end either comes before (this setup is dropped) or after (it rebuilds again)
                    // another translator or Jev service: its light starts unknown (grey), never the old one's. The
                    // switch is queued on the main thread before the new setup can produce any result
                    val m = mtService(s, b.byok); val j = s.jevKey?.let { s.provider.name + "\n" + it }
                    val mtNew = m != mtId; val jevNew = j != jevId
                    val judge = b.setup.judge
                    val activate = Runnable {
                        activeJudge = judge
                        if (mtNew) { mtFrom = v; mtState = null }
                        if (jevNew) { jevFrom = v; jevState = null }
                        refresh()
                    }
                    if (!order.publish(v) { pl.setSetup(b.setup) { main.post(activate) } }) return@Runnable
                    mtId = m; jevId = j
                    published = v
                    inUse = b.byok
                    notice = b.notice
                    logUsed(b, s, change = true)
                    refresh()
                } catch (t: Throwable) {
                    // a failed rebuild keeps the current settings; it must not end the session
                    Log.w(TAG, "setup rebuild failed", t)
                    notice = "⚠ 新的设置没能生效：${t.message}"; refresh()
                }
            }
            synchronized(cancelLock) { pipeline = pl }
            if (stopping) return // onDestroy ran before the pipeline was visible to it
            pl.start()
            // after start: setSetup refuses a pipeline that is not running
            val exec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "jpsub-setup") }
            onSetupChange = { runCatching { exec.execute(rebuild) } } // rejected once the session ends
            exec.execute(rebuild) // a change made since the first snapshot only bumped the version
            try {
                val lost = capture(pl, vad, sl)
                if (failed && !stopping) keepOverlay = true
                else if (lost != null && !stopping) {
                    // what was already heard is still translated and shown, with the reason the capture ended
                    pl.drainFinals(DRAIN_TIMEOUT_MS)
                    if (!stopping) setStatus("$lost。点 ✕ 结束后可以重新开始。")
                    keepOverlay = true
                }
                if (overloaded && !stopping) {
                    // Drain is bounded; afterwards it waits for in-progress native calls to return (cancelled, but
                    // not interruptible), so the count is final and nothing is delivered later.
                    val left = pl.drainFinals(DRAIN_TIMEOUT_MS)
                    if (!stopping) setStatus(if (left == 0) "⚠ 电脑处理跟不上，已停止采集；已收到的句子都已翻译。点 ✕ 结束。"
                                             else "⚠ 电脑处理跟不上，已停止采集；还有 $left 句未翻译被放弃。点 ✕ 结束。")
                    keepOverlay = true // models are released below; the captions stay until the user stops
                }
            } finally {
                // No new setup or probe from here on (closed under the lock both check), then end the rebuild worker:
                // it is either in a probe (cancelled now, returns within the request's cancel) or in quick local work,
                // so the wait ends; only then the log is closed and the models are freed below.
                onSetupChange = null
                synchronized(cancelLock) { order.close(); probing?.cancel() }
                exec.shutdownNow(); probeExec.shutdownNow()
                while (!exec.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) ||
                    !probeExec.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) Log.w(TAG, "waiting for the setup worker")
                pl.stop()
                pl.awaitStopped() // ASR/MT workers are out of native code after this
            }
        } catch (e: Throwable) {
            Log.w(TAG, "session failed", e)
            setStatus("出错：${e.message}。点 ✕ 结束后可以重新开始。")
            keepOverlay = true
        } finally {
            // the pipeline workers have exited (or never started), so this is the last event
            log?.let { it.write(mapOf("ev" to "session_end", "overloaded" to overloaded)); it.close() } // failure → onFail
            // Detach the translator under the same lock onDestroy uses for cancel(), so no thread can call
            // into it after this point; the (slow) native free then happens outside the lock.
            synchronized(cancelLock) { pipeline = null; translator = null }
            local?.release(); asr?.release(); vad?.release()
            Log.i(TAG, "session released")
            // the captions and the reason stay until the user ends them (✕, the menu bar or the home screen)
            if (keepOverlay) { ended = true; main.post { if (current === this@CaptionService) endedState.value = true } }
            else if (!stopping) main.post { if (current === this@CaptionService) stop() }
        }
    }

    /**
     * Blocking capture loop on the session thread: reads sysaudio's stdout until stop (which ends the child, so the
     * read sees EOF), overload, or the child ending by itself. @return the child's exit message when it ended by itself
     */
    private fun capture(pl: Pipeline, vad: SileroProb, log: SessionLog?): String? {
        val p = synchronized(cancelLock) {
            if (stopping || quitting) return null
            ProcessBuilder(sysaudio.path).redirectError(ProcessBuilder.Redirect.PIPE).start().also { capture = it }
        }
        // its messages (permission refused, no tap) explain an early exit: keep the last ones
        val errTail = java.util.concurrent.ConcurrentLinkedDeque<String>()
        Thread({
            p.errorStream.bufferedReader().forEachLine { l ->
                System.err.println("sysaudio: $l"); errTail.addLast(l); while (errTail.size > 5) errTail.pollFirst()
            }
        }, "jpsub-sysaudio-err").apply { isDaemon = true }.start()
        try {
            // with Jev the forced cut asks first and an "unfinished" head may grow to twice this (10 s); without Jev it
            // cuts at the default 7 s. An utterance that starts while Jev is paused (its key is missing) is cut as without Jev.
            val seg = Segmenter({ pl.submit(it) }, minFinalMs = 1_200, maxHoldSilenceMs = 1_500,
                maxUtteranceMs = if (jevActive) JEV_MAX_UTTERANCE_MS else PLAIN_MAX_UTTERANCE_MS,
                hintWaitMs = if (jevActive) JEV_WAIT_MS else 0, holdHint = pl::holdHint,
                judged = pl::judged, plainMaxUtteranceMs = PLAIN_MAX_UTTERANCE_MS)
            val input = java.io.DataInputStream(java.io.BufferedInputStream(p.inputStream))
            val bytes = ByteArray(seg.frameSize * 4)
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var framesRead = 0L
            var statSq = 0.0; var statN = 0; var statMaxP = 0f
            var first = true
            while (!stopping && !overloaded) {
                try { input.readFully(bytes) } catch (_: java.io.IOException) { break } // EOF: the child ended or was ended
                if (first) { first = false; setStatus("") } // audio arrives: the tap is running
                // read time: the pipe buffers little (sysaudio writes every 10 ms), so this is close to capture time
                val capturedAt = monoMs()
                bb.rewind()
                val f = FloatArray(seg.frameSize) { bb.getFloat() }
                framesRead += f.size
                val prob = vad.prob(f)
                seg.accept(f, prob, capturedAt, estimated = true)
                // once every ~5 s: proves audio arrives and what the VAD makes of it (silence vs. no capture)
                var e = 0.0; for (x in f) e += x * x
                statSq += e; statN += f.size; if (prob > statMaxP) statMaxP = prob
                if (framesRead % (16_000 * 5) < f.size) {
                    val rms = Math.sqrt(statSq / statN.coerceAtLeast(1))
                    Log.i(TAG, "capture: frames=$framesRead rms=${"%.4f".format(rms)} vadMax=${"%.2f".format(statMaxP)}")
                    log?.write(mapOf("ev" to "capture", "samples" to framesRead, "rms" to rms, "vad_max" to statMaxP.toDouble(), "hal" to false))
                    statSq = 0.0; statN = 0; statMaxP = 0f
                }
            }
            if (stopping || overloaded) return null
            seg.flush()
            val code = p.waitFor()
            log?.write(mapOf("ev" to "capture_end", "exit" to code, "stderr" to errTail.joinToString("\n")))
            return "⚠ 系统声音采集停止了（退出码 $code）" + (errTail.lastOrNull()?.let { "：$it" } ?: "")
        } finally {
            synchronized(cancelLock) { capture = null }
            p.destroy()
        }
    }

    private fun onCaption(c: Caption, log: SessionLog?) {
        val t = c.timing
        // timings only: the text of what was said stays out of logcat (it goes to the session log when that is on)
        Log.i(TAG, "seg=${c.segmentId}.${c.revision}${if (c.isFinal) "F" else "d"} audio=${t.audioMs} wait=${t.segmentWaitMs} " +
            "asrQ=${t.asrQueueMs} asr=${t.asrMs} mtQ=${t.mtQueueMs} mt=${t.mtMs} mtDone=${t.mtDoneMs} ja=${c.ja.length} zh=${c.zh.length}")
        log?.write(mapOf("ev" to "caption", "seg" to c.segmentId, "rev" to c.revision, "source_rev" to c.sourceRevision,
            "final" to c.isFinal, "ja" to c.ja,
            "zh" to c.zh, "audio_ms" to t.audioMs, "wait_ms" to t.segmentWaitMs, "asr_q_ms" to t.asrQueueMs, "asr_ms" to t.asrMs,
            "mt_q_ms" to t.mtQueueMs, "mt_ms" to t.mtMs, "check_ms" to t.checkMs, "done_ms" to t.mtDoneMs, "hal" to !c.timeEstimated))
        if (board.accept(c)) refresh()
    }

    private fun setStatus(s: String) { status = s; refresh() }
    /** A warning that stays for the whole session (the status line is cleared once capture starts). */
    @Volatile private var notice = ""

    private fun refresh() = main.post {
        if (current !== this@CaptionService) return@post // a stopped session's late events never touch the next one's window
        val v = overlay
        // "only finished sentences": drafts are hidden (and a draft already on screen disappears), read live from prefs
        val shown = board.snapshot().let { s -> if (prefs.finalOnly) s.filter { it.isFinal } else s }
        val showJa = prefs.showJa
        val lines = shown.map { OverlayState.Line(it.zh, if (showJa) it.ja else null, it.isFinal) }
        // status messages (loading, errors, overload) always show; timings only when enabled in settings
        val diag = buildString {
            if (status.isNotEmpty()) append(status)
            if (notice.isNotEmpty()) append("${if (isNotEmpty()) "  ·  " else ""}$notice")
            if (logWarned) append("${if (isNotEmpty()) "  ·  " else ""}⚠ 日志写入失败，本次日志不完整")
            // a failing service stays shown, also with timings off; one that works says nothing here
            serviceWarning(mtState, jevNow(), jevEndsOn, jevCheckOn, monoMs(), System.currentTimeMillis())?.let {
                append("${if (isNotEmpty()) "  ·  " else ""}$it")
            }
            // an operational status (stopped, error) must never be pushed out of the capped area by timings
            if (prefs.showStats && status.isEmpty()) shown.lastOrNull()?.let { c ->
                val t = c.timing
                if (isNotEmpty()) append("  ·  ")
                append("识别 ${t.asrMs}ms · 翻译 ${t.mtMs}ms")
                // capture → this text update, for the caption actually rendered now ("~": read time, not capture time)
                lastUpdateMs[c.segmentId to c.revision]?.let { ms ->
                    append(" · 延迟 ${if (c.timeEstimated) "~" else ""}${"%.1f".format(ms / 1000.0)}s")
                }
            }
            if (backlog >= 2 && !overloaded) append("${if (isNotEmpty()) "  ·  " else ""}⚠ 处理跟不上（积压 $backlog 句）")
        }
        v.lines.value = if (lines.isEmpty() && status.isEmpty()) listOf(OverlayState.Line("正在听…", null, false)) else lines
        v.diag.value = diag.ifEmpty { null }
        val jevNow = jevNow()
        v.mtLight.value = light(mtState, true)
        v.jevLight.value = light(jevNow, jevEndsOn || jevCheckOn)
        v.mtReason.value = mtState?.let { reason(it, false, monoMs(), System.currentTimeMillis()) }
        v.jevReason.value = if (jevEndsOn || jevCheckOn) jevNow?.let { reason(it, true, monoMs(), System.currentTimeMillis()) } else "未开启"
        // a pause on screen says "下一句重试" once it is over: show that then, without a request
        pauseTimer?.stop(); pauseTimer = null
        jevNow?.limit?.untilMs?.takeIf { it != Long.MAX_VALUE }?.let { until ->
            val left = until - monoMs()
            if (left > 0) pauseTimer = main.postDelayed(pauseOver, left + 50)
        }
        // Record capture → text-update latency once per (segment, revision), right after it was set.
        val now = monoMs()
        for (c in shown) {
            val key = c.segmentId to c.revision
            if (key !in lastUpdateMs && c.audioEndAt > 0) {
                lastUpdateMs[key] = now - c.audioEndAt
                Log.i(TAG, "shown seg=${c.segmentId}.${c.revision}${if (c.isFinal) "F" else "d"} update=${now - c.audioEndAt}ms")
            }
        }
        if (lastUpdateMs.size > 64) lastUpdateMs.keys.removeAll { k -> shown.none { it.segmentId to it.revision == k } }
    }

    /** Settings changed in the main window while subtitles run; the look itself is read by the window directly. */
    private val prefListener: (String) -> Unit = { key ->
        when (key) {
            // a newer BYOK save makes an unfinished probe of the previous one pointless (and billed): stop it
            "byokSaved" -> {
                synchronized(cancelLock) { order?.byokSaved(); probing?.cancel() }
                onSetupChange?.invoke()
            }
            "showJa", "showStats", "finalOnly" -> refresh()
            "keepSentences", "keepChars" -> main.post { if (board.setLimits(prefs.keepSentences, prefs.keepChars)) refresh() }
            // translation settings: the session rebuilds its setup off the UI thread, from the next sentence on
            in setupKeys -> { order?.changed(); onSetupChange?.invoke() }
            else -> if (key.startsWith("jevKey:")) { order?.changed(); onSetupChange?.invoke() }
        }
    }

    private fun listenPrefs() { prefs.listen(prefListener) }

    /** Ends the sysaudio child (its reader sees EOF). Any thread. */
    private fun stopCapture() { synchronized(cancelLock) { capture?.destroy() } }

    /**
     * Never blocks and never frees native objects: the session thread notices [stopping], finishes, waits for the
     * pipeline workers and releases everything itself. UI thread.
     */
    private fun destroy() {
        stopping = true
        pauseTimer?.stop(); pauseTimer = null
        activeJudge = null // stops watching its service
        prefs.unlisten(prefListener)
        // Only flags + an atomic store into a still-alive translator (the session thread detaches it under the
        // same lock before freeing), and the end of the child, which the capture loop sees as EOF.
        synchronized(cancelLock) { probing?.cancel(); pipeline?.stop() ?: translator?.cancel(); capture?.destroy() }
    }

    /**
     * The settings a [Setup] is built from, read once so a setup never mixes two states of the preferences.
     * Only fields of [mode] are filled (the others never change the setup). [log] leaves out the keys.
     */
    private class Snap(prefs: Prefs, mode: MtMode, wantJev: Boolean, localModel: String) {
        val scene = prefs.scene
        val source = prefs.sourceLang
        val target = prefs.targetLang
        val dedupe = prefs.dedupeJoin
        val contextN = if (prefs.contextOn) prefs.contextSentences else 0
        val online = if (mode == MtMode.INDEX) prefs.onlineModel else null
        // the three BYOK fields from one read, so the address of one save never goes out with the key of another
        private val byok = if (mode == MtMode.BYOK) prefs.byokTriple() else null
        val byokUrl = byok?.let { ByokTranslator.chatUrl(it.first).second }
        val byokModel = byok?.second.orEmpty()
        val byokKey = byok?.third
        val provider = prefs.jevProvider
        /** null: no Jev wanted, or its key is missing; "" for a provider without a key */
        val jevKey = if (!wantJev) null else if (provider.needsKey) prefs.jevKey(provider) else ""
        private val mtModel = online?.id ?: if (mode == MtMode.BYOK) byokModel else localModel
        /** the translation model of a non-BYOK setup (local or the online one), for [mtService] */
        val mtLabel get() = "$mtModel\n${online?.let { OnlineTranslator.URL }.orEmpty()}"

        fun log() = mapOf("scene" to scene.name, "source_lang" to (source?.code ?: "auto"), "target_lang" to target.code,
            "jev" to jevKey?.let { provider.name }, "jev_model" to jevKey?.let { provider.model },
            "dedupe_join" to dedupe, "context_sentences" to contextN,
            "mt_model" to mtModel, "mt_url" to (online?.let { OnlineTranslator.URL } ?: byokUrl))
    }

    companion object {
        private const val TAG = "jpsub"
        /**
         * Settings a running session takes from the next sentence. BYOK changes as one: "保存" writes its fields and
         * then bumps byokSaved, so a half-saved address or model never becomes a setup. jevKey:* is matched apart.
         */
        private val setupKeys = setOf("scene", "contextOn", "contextSentences", "dedupeJoin", "jevProvider",
            "sourceLang", "targetLang", "onlineModel")
        // Apple silicon: ASR and MT together stay below the performance cores
        private const val ASR_THREADS = 2
        private const val MT_THREADS = 4
        private const val DRAIN_TIMEOUT_MS = 60_000L
        /** How long after a sentence-end candidate to wait for Jev before finalizing (ASR ~0.1 s + Jev p90 ~0.7 s). */
        private const val JEV_WAIT_MS = 1_000
        private const val JEV_MAX_UTTERANCE_MS = 5_000
        private const val PLAIN_MAX_UTTERANCE_MS = 7_000

        /** The session on screen (running, or ended with its message still shown); UI thread. */
        @Volatile var current: CaptionService? = null; private set
        /** set by the shutdown hook: no session starts sysaudio after it */
        @Volatile private var quitting = false
        /**
         * shutdown hook: ends the sysaudio child of the session on screen; any thread. [quitting] is set first and read
         * under the launch lock, so a launch either happened before (and is ended here) or sees it and does not start.
         */
        fun killCapture() { quitting = true; current?.stopCapture() }
        /** Compose mirrors of [current] != null and of [ended], for the home screen and the menu bar */
        val runningState = mutableStateOf(false)
        val endedState = mutableStateOf(false)
        private val sessions = java.util.concurrent.atomic.AtomicInteger()
        /** A session thread may still have model files open (also after stop, until its native free is done). */
        val modelsInUse: Boolean get() = sessions.get() > 0
        val modelsBusy = mutableStateOf(false)
        val running: Boolean get() = current != null

        /** UI thread. A session that ended by itself (its message still shown) is replaced. */
        fun start(sysaudio: File) {
            current?.let { if (it.ended) stop() else return }
            val s = CaptionService(sysaudio)
            current = s; runningState.value = true; endedState.value = false; modelsBusy.value = true
            s.begin()
        }

        /** UI thread. */
        fun stop() {
            val s = current ?: return
            current = null; runningState.value = false; endedState.value = false
            s.destroy()
        }
    }
}
