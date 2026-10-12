package io.github.korenkrita.simulsub

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import java.io.File

/**
 * Foreground service: playback capture → Silero VAD → [Segmenter] → [Pipeline] → overlay.
 * Everything heavy happens on its own threads; the main thread only renders the overlay.
 */
class CaptionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null

    /**
     * One "session" thread owns every native object (VAD, ASR, MT, AudioRecord) from creation to release:
     * load → capture loop → stop pipeline → wait for its workers → release. onDestroy only flips [stopping]
     * and unblocks the read, so nothing is ever freed while another thread is still inside it.
     */
    @Volatile private var stopping = false
    /** Guards [pipeline]/[translator] so onDestroy's cancel never races the session thread's native free. */
    private val cancelLock = Any()
    private var pipeline: Pipeline? = null
    private var translator: Mt? = null
    /** A BYOK first-use probe in progress (at start or after "保存" while running), so stop can cancel it. */
    private var probing: ByokTranslator? = null

    /** Bumped by every settings change a running session takes from the next sentence (see [setupKeys]). */
    private val setupWanted = java.util.concurrent.atomic.AtomicInteger()
    /** The running session's rebuild trigger; null before its pipeline exists and after it ended. */
    @Volatile private var onSetupChange: (() -> Unit)? = null

    private val board by lazy { CaptionBoard(prefs.keepSentences, prefs.keepChars) }
    // main thread only
    private val lastUpdateMs = HashMap<Pair<Long, Int>, Long>()
    @Volatile private var backlog = 0
    @Volatile private var overloaded = false
    /** this session has a Jev judge: the segmenter asks it at sentence ends */
    @Volatile private var jevActive = false
    @Volatile private var logWarned = false

    private var overlay: CaptionOverlayView? = null
    private val prefs by lazy { Prefs(this) }
    @Volatile private var status = "正在准备…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY

        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() } // user revoked sharing
        }, main)
        projection = p
        running = true
        showOverlay()
        // fixed at the start: switching it while models load affects the next session, as the switch says
        val keepLogs = prefs.keepLogs
        sessions.incrementAndGet() // until the session thread has freed every native model (see [modelsInUse])
        Thread({ try { session(p, keepLogs) } finally { sessions.decrementAndGet() } }, "jpsub-session").start()
        return START_NOT_STICKY
    }

    private fun session(p: MediaProjection, keepLogs: Boolean) {
        val root = getExternalFilesDir(null)!!
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
            setupWanted.set(0) // a change from here on is seen by the snapshot below or rebuilt after the start
            val s0 = Snap(prefs, mode, wantJev, files.mt.name)
            if (mode == MtMode.BYOK && (s0.byokUrl == null || s0.byokModel.isEmpty() || s0.byokKey == null)) {
                setStatus("⚠ 自己的模型还没填完整（地址、key、模型 ID），请到设置里填好"); Log.w(TAG, "byok incomplete"); return
            }
            // the home screen only starts with verified models; if they vanished since, end cleanly (finally → stopSelf)
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
                else SessionLog(SessionLog.dir(root), onFail = { Log.w(TAG, "session log: $it"); logWarned = true; main.post { refresh() } })
            log = sl
            sl?.write(mapOf("ev" to "session_start", "app" to BuildConfig.VERSION_NAME,
                "jev_requested" to jevEnds, "jev_ends" to jevEnds, "jev_check" to jevCheck, "final_only" to prefs.finalOnly,
                "mt_engine" to (when (mode) { MtMode.INDEX -> "online"; MtMode.LOCAL -> "local"; MtMode.BYOK -> "byok" }),
                "load_ms" to (t3 - t0), "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}") + s0.log())

            /**
             * The translator for BYOK. A URL + model used for the first time is first probed for the switch that turns
             * thinking off (logged like any MT call; cancelled by stop). null: stopped meanwhile.
             */
            fun byok(s: Snap, notices: MutableList<String>): ByokTranslator? {
                val url = s.byokUrl!!; val model = s.byokModel; val key = s.byokKey!!
                val system = byokSystem(s.scene, s.source, s.target)
                val stored = prefs.byokVariant(url, model)
                var variant = ByokTranslator.variant(stored)
                if (variant == null && stored != ByokTranslator.NONE_WORKED) {
                    val asking = "第一次用这个模型，正在试怎样关闭思考…"
                    val before = status
                    setStatus(asking)
                    val probe = ByokTranslator(url, model, key, system, null, maxTokens = mtMaxTokens(s.target))
                    synchronized(cancelLock) { probing = probe }
                    if (stopping) return null
                    val steps = mutableListOf<ByokTranslator.Step>()
                    variant = probe.probe(steps)
                    synchronized(cancelLock) { probing = null }
                    steps.forEach { st -> sl?.write(mapOf("ev" to "byok_probe", "variant" to st.variant.name, "request" to st.result.request,
                        "http" to st.result.http, "response" to st.result.response, "error" to st.result.error, "usable" to st.result.usable,
                        "thinking" to st.result.thinking, "first_content_ms" to st.result.firstContentMs)) }
                    if (stopping) return null
                    val none = variant == null && probe.exhausted(steps)
                    when {
                        variant != null -> prefs.setByokVariant(url, model, variant.name)
                        none -> prefs.setByokVariant(url, model, ByokTranslator.NONE_WORKED)
                    }
                    // stopped early (service error): no switch is stored, the next use tries again
                    if (variant == null && !none) notices += "⚠ 你的翻译服务现在不能用：${steps.lastOrNull()?.result?.let { ByokTranslator.why(it) } ?: "没有回应"}"
                    if (status == asking) setStatus(before) // unless something more important replaced it meanwhile
                }
                if (variant == null && prefs.byokVariant(url, model) == ByokTranslator.NONE_WORKED)
                    notices += "⚠ 关不掉这个模型的思考，翻译可能很慢或经常失败"
                sl?.write(mapOf("ev" to "byok", "variant" to variant?.name, "system" to system))
                return ByokTranslator(url, model, key, system, variant, maxTokens = mtMaxTokens(s.target))
            }
            /** A setup for [s] and the warnings it comes with; null: stopped meanwhile. */
            fun make(s: Snap, version: Int): Pair<Setup, String>? {
                val notices = mutableListOf<String>()
                val mt: Mt = when (mode) {
                    MtMode.INDEX -> OnlineTranslator(s.online!!, maxTokens = mtMaxTokens(s.target))
                    MtMode.LOCAL -> LlamaTranslator.Capped(local!!, mtMaxTokens(s.target))
                    MtMode.BYOK -> byok(s, notices) ?: return null
                }
                // one Jev client for both features; no key = both paused until one is entered
                val judge = s.jevKey?.let { Jev(s.provider, it, source = s.source, target = s.target) }
                if (wantJev && judge == null) notices += "⚠ 在线 Decisions 模型辅助已暂停：还没填 ${s.provider.label} 的 key"
                return Setup(mt, s.scene, s.source, s.target, mode == MtMode.BYOK, judge, s.dedupe, s.contextN, version) to
                    notices.joinToString("  ·  ")
            }
            val (first, firstNotice) = make(s0, 0) ?: return
            notice = firstNotice
            if (stopping) return
            val pl = Pipeline(
                asr, first.mt,
                onCaption = { onCaption(it, sl) },
                onBacklog = { backlog = it; refresh() },
                onOverload = {
                    overloaded = true // capture loop exits, then accepted finals are drained below
                    setStatus("⚠ 手机忙不过来，先停止听新的声音，正在处理已经听到的句子…")
                },
                onFailure = { e ->
                    Log.e(TAG, "pipeline worker failed", e)
                    setStatus("出错：${e.message}")
                    main.post { stopSelf() }
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
                log = { e -> sl?.write(e) },
                drafts = { !prefs.finalOnly }, // "only finished sentences" saves the draft work, not just hides it
            )
            // The segmenter's judged hold and its timings follow the sentence-end switch; while its key is missing the
            // pipeline answers "finished" at once, so a cut happens at the shorter Jev limit.
            jevActive = jevEnds

            // Settings changed while running: one worker rebuilds the setup from a fresh snapshot, so a burst of changes
            // (a slider) makes one or two setups, and setups are published in order. A rebuild that a newer change
            // overtook is dropped; the newer one's own task builds again.
            var published = 0
            val rebuild = Runnable {
                try {
                    val v = setupWanted.get()
                    if (v == published || stopping) return@Runnable
                    val s = Snap(prefs, mode, wantJev, files.mt.name)
                    // BYOK is only switched by "保存", which checks the fields; a half-filled one keeps the current setup
                    if (mode == MtMode.BYOK && (s.byokUrl == null || s.byokModel.isEmpty() || s.byokKey == null)) return@Runnable
                    val (setup, warn) = make(s, v) ?: return@Runnable
                    if (v != setupWanted.get() || !pl.setSetup(setup)) return@Runnable
                    published = v
                    notice = warn
                    sl?.write(mapOf("ev" to "config_change", "setup" to v) + s.log())
                    refresh()
                } catch (t: Throwable) {
                    // a failed rebuild keeps the current settings; it must not end the session
                    Log.e(TAG, "setup rebuild failed", t)
                    notice = "⚠ 新的设置没能生效：${t.message}"; refresh()
                }
            }
            synchronized(cancelLock) { pipeline = pl }
            if (stopping) return // onDestroy ran before the pipeline was visible to it
            pl.start()
            // after start: setSetup refuses a pipeline that is not running
            val exec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "jpsub-setup") }
            onSetupChange = { runCatching { exec.execute(rebuild) } } // rejected once the session ends
            exec.execute(rebuild) // a change made since the first snapshot only counted in setupWanted
            try {
                capture(p, pl, vad, sl)
                if (overloaded && !stopping) {
                    // Drain is bounded; afterwards it waits for in-progress native calls to return (cancelled, but
                    // not interruptible), so the count is final and nothing is delivered later.
                    val left = pl.drainFinals(DRAIN_TIMEOUT_MS)
                    if (!stopping) setStatus(if (left == 0) "⚠ 手机处理跟不上，已停止采集；已收到的句子都已翻译。点字幕再点 ✕ 结束。"
                                             else "⚠ 手机处理跟不上，已停止采集；还有 $left 句未翻译被放弃。点字幕再点 ✕ 结束。")
                    keepOverlay = true // models are released below; the captions stay until the user stops
                }
            } finally {
                // no new setup from here on: end the rebuild worker (a probe it runs is cancelled) before the pipeline
                onSetupChange = null
                synchronized(cancelLock) { probing?.cancel() }
                exec.shutdownNow()
                exec.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
                pl.stop()
                pl.awaitStopped() // ASR/MT workers are out of native code after this
            }
        } catch (e: Throwable) {
            Log.e(TAG, "session failed", e)
            setStatus("出错：${e.message}")
        } finally {
            // the pipeline workers have exited (or never started), so this is the last event
            log?.let { it.write(mapOf("ev" to "session_end", "overloaded" to overloaded)); it.close() } // failure → onFail
            // Detach the translator under the same lock onDestroy uses for cancel(), so no thread can call
            // into it after this point; the (slow) native free then happens outside the lock.
            synchronized(cancelLock) { pipeline = null; translator = null }
            local?.release(); asr?.release(); vad?.release()
            Log.i(TAG, "session released")
            if (keepOverlay) while (!stopping) Thread.sleep(100) // only the overlay is alive now
            if (!stopping) main.post { stopSelf() }
        }
    }

    /** Blocking capture loop on the session thread; returns when stopping or on a read error. */
    @SuppressLint("MissingPermission") // checked in MainActivity
    private fun capture(p: MediaProjection, pl: Pipeline, vad: SileroProb, log: SessionLog?) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(16_000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf, 16_000 * 4 * 2)) // 2 s of slack
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
            if (stopping) return
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音（可能被其他 App 占用）" }
            setStatus("")
            // with Jev the forced cut asks first and an "unfinished" head may grow to twice this (10 s); without Jev it
            // cuts at the default 7 s. The live log showed long waits were utterances that reached the forced cut.
            val seg = Segmenter({ pl.submit(it) }, minFinalMs = 1_200, maxHoldSilenceMs = 1_500,
                maxUtteranceMs = if (jevActive) JEV_MAX_UTTERANCE_MS else 7_000,
                hintWaitMs = if (jevActive) JEV_WAIT_MS else 0, holdHint = pl::holdHint)
            val frame = FloatArray(seg.frameSize)
            val ts = AudioTimestamp()
            var framesRead = 0L
            var statSq = 0.0; var statN = 0; var statMaxP = 0f
            // Non-blocking reads: stopping is only a flag, so onDestroy never touches AudioRecord and the
            // session thread is the only one that calls stop()/release().
            while (!stopping && !overloaded) {
                var off = 0
                while (off < frame.size && !stopping) {
                    val n = rec.read(frame, off, frame.size - off, AudioRecord.READ_NON_BLOCKING)
                    check(n >= 0) { "读取音频失败 ($n)" }
                    if (n == 0) Thread.sleep(10)
                    off += n
                }
                if (stopping || overloaded) break
                framesRead += frame.size
                // Capture time of this frame's last sample, from the HAL timestamp when available,
                // so time spent waiting in the AudioRecord buffer counts as latency.
                val hal = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                val capturedAt = if (hal) {
                    (ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / 16_000) / 1_000_000
                } else monoMs() // read time: underestimates latency by the time spent in the AudioRecord buffer
                val f = frame.copyOf()
                val prob = vad.prob(f)
                seg.accept(f, prob, capturedAt, estimated = !hal)
                // once every ~5 s: proves audio arrives and what the VAD makes of it (silence vs. no capture)
                var e = 0.0; for (x in f) e += x * x
                statSq += e; statN += f.size; if (prob > statMaxP) statMaxP = prob
                if (framesRead % (16_000 * 5) < frame.size) {
                    val rms = Math.sqrt(statSq / statN.coerceAtLeast(1))
                    Log.i(TAG, "capture: frames=$framesRead rms=${"%.4f".format(rms)} vadMax=${"%.2f".format(statMaxP)} hal=$hal")
                    log?.write(mapOf("ev" to "capture", "samples" to framesRead, "rms" to rms, "vad_max" to statMaxP.toDouble(), "hal" to hal))
                    statSq = 0.0; statN = 0; statMaxP = 0f
                }
            }
            seg.flush()
        } finally {
            runCatching { rec.stop() }
            rec.release()
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
        val v = overlay ?: return@post
        // "only finished sentences": drafts are hidden (and a draft already on screen disappears), read live from prefs
        val shown = board.snapshot().let { s -> if (prefs.finalOnly) s.filter { it.isFinal } else s }
        val showJa = prefs.showJa
        val lines = shown.map { CaptionOverlayView.Line(it.zh, if (showJa) it.ja else null, it.isFinal) }
        // status messages (loading, errors, overload) always show; timings only when enabled in settings
        val diag = buildString {
            if (status.isNotEmpty()) append(status)
            if (notice.isNotEmpty()) append("${if (isNotEmpty()) "  ·  " else ""}$notice")
            if (logWarned) append("${if (isNotEmpty()) "  ·  " else ""}⚠ 日志写入失败，本次日志不完整")
            // an operational status (stopped, error) must never be pushed out of the capped area by timings
            if (prefs.showStats && status.isEmpty()) shown.lastOrNull()?.let { c ->
                val t = c.timing
                if (isNotEmpty()) append("  ·  ")
                append("识别 ${t.asrMs}ms · 翻译 ${t.mtMs}ms")
                // capture → this text update, for the caption actually rendered now; "~" = no HAL timestamp
                lastUpdateMs[c.segmentId to c.revision]?.let { ms ->
                    append(" · 延迟 ${if (c.timeEstimated) "~" else ""}${"%.1f".format(ms / 1000.0)}s")
                }
            }
            if (backlog >= 2 && !overloaded) append("${if (isNotEmpty()) "  ·  " else ""}⚠ 处理跟不上（积压 $backlog 句）")
        }
        if (lines.isEmpty() && status.isEmpty()) v.setLines(listOf(CaptionOverlayView.Line("正在听…", null, false)), diag.ifEmpty { null })
        else v.setLines(lines, diag.ifEmpty { null })
        // Record capture → text-update latency once per (segment, revision), right after it was set.
        val now = monoMs()
        for (c in shown) {
            val key = c.segmentId to c.revision
            if (key !in lastUpdateMs && c.audioEndAt > 0) {
                lastUpdateMs[key] = now - c.audioEndAt
                Log.i(TAG, "shown seg=${c.segmentId}.${c.revision}${if (c.isFinal) "F" else "d"} update=${now - c.audioEndAt}ms hal=${!c.timeEstimated}")
            }
        }
        if (lastUpdateMs.size > 64) lastUpdateMs.keys.removeAll { k -> shown.none { it.segmentId to it.revision == k } }
    }

    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val dm = resources.displayMetrics
        val v = CaptionOverlayView(this, interactive = true)
        v.setColors(CaptionOverlayView.palette(prefs.palette))
        v.applyStyle(prefs.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP), prefs.bgAlpha)
        // usable area: current window bounds (follows rotation) minus status / navigation bars. Only its size is
        // used: the overlay's x/y are already relative to that area (its parent frame excludes the bars).
        fun area(): android.graphics.Rect {
            if (android.os.Build.VERSION.SDK_INT < 30) return android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
            val m = wm.currentWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            return android.graphics.Rect(m.bounds.left + i.left, m.bounds.top + i.top, m.bounds.right - i.right, m.bounds.bottom - i.bottom)
        }
        var cardW = (area().width() * prefs.widthFrac).toInt()
        var toolbarShown = false
        val lp = WindowManager.LayoutParams(
            cardW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.overlayX.takeIf { it >= 0 } ?: ((area().width() - cardW) / 2)
            y = prefs.overlayY.takeIf { it >= 0 } ?: (area().height() * 2 / 3)
        }
        // Where the user put the card (its top-left in the usable area). The window is derived from it on every
        // layout: closed, the window is exactly the card, so no invisible area blocks touches to the app underneath;
        // open, it also covers the toolbar, which is centred under the card but shifted to stay on screen (and put
        // above the card when there is no room below). The card itself never moves when the toolbar opens.
        var cardX = lp.x; var cardY = lp.y
        var shownX = cardX; var shownY = cardY // where layout() last put the card (cardX / cardY clamped to the screen)
        fun layout() {
            val a = area(); val sw = a.width(); val sh = a.height()
            cardW = cardW.coerceIn((sw * Prefs.MIN_WIDTH).toInt(), sw)
            v.setCardWidth(cardW)
            // captions ≤ 40 %, status ≤ 20 % of the usable height: card + toolbar always fit on screen
            v.setMaxContentHeight((a.height() * MAX_CAPTION_FRAC).toInt(), (a.height() * 0.2f).toInt())
            // measured now at this width (a resize just changed width or caption height; the last layout is stale)
            val cardH = v.measureCardHeight(cardW).coerceAtLeast(1)
            // cardX / cardY stay what the user chose; only the shown position is clamped (a rotation or a taller card
            // must not overwrite the placement, so rotating back puts the card where it was)
            shownX = cardX.coerceIn(0, sw - cardW)
            shownY = cardY.coerceIn(0, (sh - cardH).coerceAtLeast(0))
            val tbW = if (toolbarShown) v.toolbarWidthPx().coerceAtMost(sw) else 0
            val tbH = if (toolbarShown) v.toolbarHeightPx() else 0
            val tbX = (shownX + (cardW - tbW) / 2).coerceIn(0, sw - tbW)
            // below if it fits, else above if that fits; on a short screen with room on neither side, the roomier
            // side, the toolbar overlapping the card's edge by what is missing (it draws over the card)
            val below = sh - (shownY + cardH); val aboveRoom = shownY
            val above = toolbarShown && tbH > below && (tbH <= aboveRoom || aboveRoom > below)
            val overlap = if (!toolbarShown) 0 else (tbH - if (above) aboveRoom else below).coerceAtLeast(0)
            val left = if (toolbarShown) minOf(shownX, tbX) else shownX
            val right = if (toolbarShown) maxOf(shownX + cardW, tbX + tbW) else shownX + cardW
            v.placeCard(shownX - left, tbX - left, above, overlap)
            lp.x = left; lp.width = right - left
            lp.y = if (above) shownY - (tbH - overlap) else shownY
            if (v.isAttachedToWindow) wm.updateViewLayout(v, lp)
        }
        // rotation / resize: the caption height may not change, so re-fit explicitly from the saved width fraction
        // caption height from the saved fraction (0 = fit the text); kept within the 40 % cap by the view
        fun applyHeight() = v.setCaptionHeight((area().height() * prefs.heightFrac).toInt())
        var captionH = 0 // px while a resize drag runs
        onDisplayChanged = { cardW = (area().width() * prefs.widthFrac).toInt(); applyHeight(); layout() }
        // gestures start from where the card is shown, and what they end on is the new placement
        v.onMove = { dx, dy -> cardX = shownX + dx; cardY = shownY + dy; layout() }
        // a corner drag moves that corner; the opposite edges stay where they are. The whole card rectangle is
        // derived from the fixed edges: the caption height is frozen at what is shown, and a width change that
        // rewraps the status line is measured at the new width, then the caption height absorbs the difference.
        v.onResize = { dx, dy, left, top ->
            val a = area(); val sh = a.height(); val oldW = cardW
            val minW = (a.width() * Prefs.MIN_WIDTH).toInt()
            val oldH = v.measureCardHeight(oldW)
            val fixedTop = shownY; val fixedBottom = shownY + oldH
            val roomW = if (left) shownX + oldW else a.width() - shownX
            cardW = (cardW + if (left) -dx else dx).coerceIn(minW, roomW.coerceAtLeast(minW))
            cardX = if (left) shownX + oldW - cardW else shownX
            if (captionH == 0) captionH = v.captionHeightPx() // start from what is on screen (also when auto)
            val maxCap = (sh * MAX_CAPTION_FRAC).toInt()
            var want = (captionH + if (top) -dy else dy).coerceIn(v.minCaptionPx(), maxCap.coerceAtLeast(v.minCaptionPx()))
            v.setCaptionHeight(want)
            // the card may not grow past the screen edge it is growing toward
            val newH = v.measureCardHeight(cardW)
            val limit = if (top) fixedBottom else sh - fixedTop
            if (newH > limit) { want = (want - (newH - limit)).coerceAtLeast(v.minCaptionPx()); v.setCaptionHeight(want) }
            captionH = want
            cardY = if (top) fixedBottom - v.measureCardHeight(cardW) else fixedTop
            layout()
        }
        v.onMoveEnd = {
            cardX = shownX; cardY = shownY
            prefs.overlayX = cardX; prefs.overlayY = cardY; prefs.widthFrac = cardW.toFloat() / area().width()
            if (captionH > 0) { prefs.heightFrac = captionH.toFloat() / area().height(); captionH = 0 }
        }
        v.onStyleChanged = { prefs.setStyle(v.textSp, v.bgAlpha) }
        v.onToolbarShown = { toolbarShown = it; layout() }
        v.onStop = { stopSelf() }
        v.setFinalOnly(prefs.finalOnly)
        v.onToggleMode = { prefs.finalOnly = !prefs.finalOnly } // the listener below updates the button and captions
        v.setShown(prefs.showJa, prefs.showStats)
        v.onToggleJa = { prefs.showJa = !prefs.showJa }
        v.onToggleStats = { prefs.showStats = !prefs.showStats }
        // taller text / toolbar / rotation: pull the window back on screen once the new height is known
        v.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) v.post { layout() }
        }
        // settings changed on the home screen while subtitles run
        prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                // a newer BYOK save makes an unfinished probe of the previous one pointless (and billed): stop it
                "byokSaved" -> {
                    setupWanted.incrementAndGet()
                    if (onSetupChange != null) synchronized(cancelLock) { probing?.cancel() }
                    onSetupChange?.invoke()
                }
                "textSp", "bgAlpha" -> if (prefs.textSp != v.textSp || prefs.bgAlpha != v.bgAlpha)
                    v.applyStyle(prefs.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP), prefs.bgAlpha)
                "widthFrac" -> (area().width() * prefs.widthFrac).toInt().let { if (it != cardW) { cardW = it; layout() } }
                "heightFrac" -> if (captionH == 0) { applyHeight(); layout() }
                "showJa", "showStats" -> { v.setShown(prefs.showJa, prefs.showStats); refresh() }
                "palette" -> v.setColors(CaptionOverlayView.palette(prefs.palette))
                "keepSentences", "keepChars" -> if (board.setLimits(prefs.keepSentences, prefs.keepChars)) refresh()
                "finalOnly" -> { v.setFinalOnly(prefs.finalOnly); refresh() }
                // translation settings: the session rebuilds its setup off the main thread, from the next sentence on
                in setupKeys -> { setupWanted.incrementAndGet(); onSetupChange?.invoke() }
                else -> if (key?.startsWith("jevKey:") == true) { setupWanted.incrementAndGet(); onSetupChange?.invoke() }
            }
        }.also { prefs.listen(it) }
        v.setCardWidth(cardW)
        applyHeight()
        wm.addView(v, lp)
        overlay = v
        layout()
        refresh()
    }

    private var onDisplayChanged: (() -> Unit)? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post { onDisplayChanged?.invoke() } // after the new window bounds are in effect
    }

    /** Strong reference: SharedPreferences only keeps listeners weakly. */
    private var prefListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onDestroy() {
        // Never block the main thread and never free native objects here: the session thread
        // notices `stopping`, finishes, waits for the pipeline workers and releases everything itself.
        stopping = true
        running = false
        // Only flags + an atomic store into a still-alive translator (the session thread detaches it under the
        // same lock before freeing). The session thread notices `stopping` within ~10 ms (non-blocking reads).
        synchronized(cancelLock) { probing?.cancel(); pipeline?.stop() ?: translator?.cancel() }
        projection?.stop()
        projection = null
        prefListener?.let { prefs.unlisten(it) }; prefListener = null
        onDisplayChanged = null
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "字幕", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, "cap")
            .setSmallIcon(R.drawable.ic_stat_caption)
            .setColor(getColor(R.color.brand))
            .setContentTitle("同传字幕运行中")
            .setContentText("点字幕可调整样式；点这里的「停止」结束")
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
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
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        // 8 Gen 3: 1×X4 + 5×A720 + 2×A520. Keep ASR + MT below the 6 big cores so they don't thrash.
        private const val ASR_THREADS = 2
        private const val MT_THREADS = 4
        private const val DRAIN_TIMEOUT_MS = 60_000L
        /** How long after a sentence-end candidate to wait for Jev before finalizing (ASR ~0.1 s + Jev p90 ~0.7 s). */
        private const val JEV_WAIT_MS = 1_000
        private const val JEV_MAX_UTTERANCE_MS = 5_000
        /** captions never take more than this part of the usable height, so the toolbar always fits */
        const val MAX_CAPTION_FRAC = 0.4f

        /** true from start until onDestroy (main thread); the home screen uses it to show 开始/停止. */
        @Volatile var running = false
            private set
        private val sessions = java.util.concurrent.atomic.AtomicInteger()
        /** A session thread may still have model files open (also after onDestroy, until its native free is done). */
        val modelsInUse: Boolean get() = sessions.get() > 0

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, CaptionService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptionService::class.java).setAction(ACTION_STOP))
        }
    }
}
