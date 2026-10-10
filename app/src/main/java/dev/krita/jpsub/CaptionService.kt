package dev.krita.jpsub

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
    @Volatile private var status = "加载模型…"

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
        Thread({ session(p) }, "jpsub-session").start()
        return START_NOT_STICKY
    }

    private fun session(p: MediaProjection) {
        val root = getExternalFilesDir(null)!!
        val files = ModelFiles(root)
        var vad: SileroProb? = null
        var asr: SherpaAsr? = null
        var local: LlamaTranslator? = null
        var log: SessionLog? = null
        var keepOverlay = false
        try {
            // fixed for the whole session: the home screen disables these switches while running
            val online = if (prefs.mtOnline) prefs.onlineModel else null
            // the home screen only starts with verified models; if they vanished since, end cleanly (finally → stopSelf)
            if (!ModelStore(root, needsMt = { online == null }).ready()) { Log.w(TAG, "models missing, not starting"); return }
            val t0 = monoMs()
            vad = SileroProb(files)
            asr = SherpaAsr(files, ASR_THREADS)
            val t1 = monoMs()
            if (stopping) return
            val mt: Mt = if (online != null) OnlineTranslator(online) else {
                setStatus("加载翻译模型…")
                LlamaTranslator(files, MT_THREADS).also { local = it }
            }
            synchronized(cancelLock) { translator = mt }
            val t2 = monoMs()
            // Warm-up so the first real sentence does not pay page-in / first-run costs. Online: nothing to warm up,
            // and no request is sent that the session log would not show.
            asr.recognize(FloatArray(16_000))
            local?.translate(indexPrompt("こんにちは。", Scene.GENERAL))
            val t3 = monoMs()
            Log.i(TAG, "load asr=${t1 - t0}ms mt=${t2 - t1}ms warmup=${t3 - t2}ms")
            if (stopping) return
            setStatus("就绪（加载 ${(t3 - t0) / 1000.0}s）")

            val scene = prefs.scene
            val provider = prefs.jevProvider
            val key = if (!prefs.jevOn) null else if (provider.needsKey) prefs.jevKey(provider) else ""
            val judge = key?.let { Jev(provider, it) }
            val sl = SessionLog(SessionLog.dir(root), onFail = { Log.w(TAG, "session log: $it"); logWarned = true; main.post { refresh() } })
            log = sl
            sl.write(mapOf("ev" to "session_start", "app" to BuildConfig.VERSION_NAME, "scene" to scene.name,
                "jev" to (if (judge != null) provider.name else null), "jev_model" to judge?.let { provider.model },
                "jev_requested" to prefs.jevOn, "final_only" to prefs.finalOnly,
                "mt_engine" to (if (online != null) "online" else "local"),
                "mt_model" to (online?.id ?: files.mt.name), "mt_url" to online?.let { OnlineTranslator.URL },
                "load_ms" to (t3 - t0), "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
            if (prefs.jevOn && judge == null) setStatus("⚠ 已开启 Jev 但没有填 ${provider.label} 的 key，本次不用 Jev")
            val pl = Pipeline(
                asr, mt,
                onCaption = { onCaption(it, sl) },
                onBacklog = { backlog = it; refresh() },
                onOverload = {
                    overloaded = true // capture loop exits, then accepted finals are drained below
                    setStatus("⚠ 手机处理跟不上，已停止采集，正在处理已收到的句子…")
                },
                onFailure = { e ->
                    Log.e(TAG, "pipeline worker failed", e)
                    setStatus("出错：${e.message}")
                    main.post { stopSelf() }
                },
                scene = scene,
                judge = judge,
                log = sl::write,
                drafts = { !prefs.finalOnly }, // "only finished sentences" saves the draft work, not just hides it
            )
            jevActive = judge != null
            synchronized(cancelLock) { pipeline = pl }
            if (stopping) return // onDestroy ran before the pipeline was visible to it
            pl.start()
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
    private fun capture(p: MediaProjection, pl: Pipeline, vad: SileroProb, log: SessionLog) {
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
            val seg = Segmenter({ pl.submit(it) }, minFinalMs = 1_200, maxHoldSilenceMs = 1_500,
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
                    log.write(mapOf("ev" to "capture", "samples" to framesRead, "rms" to rms, "vad_max" to statMaxP.toDouble(), "hal" to hal))
                    statSq = 0.0; statN = 0; statMaxP = 0f
                }
            }
            seg.flush()
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun onCaption(c: Caption, log: SessionLog) {
        val t = c.timing
        Log.i(TAG, "seg=${c.segmentId}.${c.revision}${if (c.isFinal) "F" else "d"} audio=${t.audioMs} wait=${t.segmentWaitMs} " +
            "asrQ=${t.asrQueueMs} asr=${t.asrMs} mtQ=${t.mtQueueMs} mt=${t.mtMs} mtDone=${t.mtDoneMs} | ${c.ja} → ${c.zh}")
        log.write(mapOf("ev" to "caption", "seg" to c.segmentId, "rev" to c.revision, "source_rev" to c.sourceRevision,
            "final" to c.isFinal, "ja" to c.ja,
            "zh" to c.zh, "audio_ms" to t.audioMs, "wait_ms" to t.segmentWaitMs, "asr_q_ms" to t.asrQueueMs, "asr_ms" to t.asrMs,
            "mt_q_ms" to t.mtQueueMs, "mt_ms" to t.mtMs, "check_ms" to t.checkMs, "done_ms" to t.mtDoneMs, "hal" to !c.timeEstimated))
        if (board.accept(c)) refresh()
    }

    private fun setStatus(s: String) { status = s; refresh() }

    private fun refresh() = main.post {
        val v = overlay ?: return@post
        // "only finished sentences": drafts are hidden (and a draft already on screen disappears), read live from prefs
        val shown = board.snapshot().let { s -> if (prefs.finalOnly) s.filter { it.isFinal } else s }
        val showJa = prefs.showJa
        val lines = shown.map { CaptionOverlayView.Line(it.zh, if (showJa) it.ja else null, it.isFinal) }
        // status messages (loading, errors, overload) always show; timings only when enabled in settings
        val diag = buildString {
            if (status.isNotEmpty()) append(status)
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
        // Keeps the card at the user's width and fully on screen. While the toolbar is open the window is
        // widened around the card's centre so the toolbar fits; closed, the window is exactly the card, so no
        // invisible area blocks touches to the app underneath.
        fun layout() {
            val a = area(); val sw = a.width()
            cardW = cardW.coerceIn((sw * Prefs.MIN_WIDTH).toInt(), sw)
            val win = maxOf(cardW, if (toolbarShown) v.toolbarWidthPx() else 0).coerceAtMost(sw)
            lp.x -= (win - lp.width) / 2
            lp.width = win
            v.setCardWidth(cardW)
            // captions ≤ 40 %, status ≤ 20 % of the usable height: card + toolbar always fit on screen
            v.setMaxContentHeight((a.height() * 0.4f).toInt(), (a.height() * 0.2f).toInt())
            lp.x = lp.x.coerceIn(0, (sw - lp.width).coerceAtLeast(0))
            lp.y = lp.y.coerceIn(0, (a.height() - v.height.coerceAtLeast(1)).coerceAtLeast(0))
            if (v.isAttachedToWindow) wm.updateViewLayout(v, lp)
        }
        // rotation / resize: the caption height may not change, so re-fit explicitly from the saved width fraction
        onDisplayChanged = { cardW = (area().width() * prefs.widthFrac).toInt(); layout() }
        v.onMove = { dx, dy -> lp.x += dx; lp.y += dy; layout() }
        v.onResize = { dw -> cardW += dw; layout() }
        v.onMoveEnd = { prefs.overlayX = lp.x + (lp.width - cardW) / 2; prefs.overlayY = lp.y; prefs.widthFrac = cardW.toFloat() / area().width() }
        v.onStyleChanged = { prefs.setStyle(v.textSp, v.bgAlpha) }
        v.onToolbarShown = { toolbarShown = it; layout() }
        v.onStop = { stopSelf() }
        v.setFinalOnly(prefs.finalOnly)
        v.onToggleMode = { prefs.finalOnly = !prefs.finalOnly } // the listener below updates the button and captions
        // taller text / toolbar / rotation: pull the window back on screen once the new height is known
        v.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) v.post { layout() }
        }
        // settings changed on the home screen while subtitles run
        prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "textSp", "bgAlpha" -> if (prefs.textSp != v.textSp || prefs.bgAlpha != v.bgAlpha)
                    v.applyStyle(prefs.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP), prefs.bgAlpha)
                "widthFrac" -> (area().width() * prefs.widthFrac).toInt().let { if (it != cardW) { cardW = it; layout() } }
                "showJa", "showStats" -> refresh()
                "keepSentences", "keepChars" -> if (board.setLimits(prefs.keepSentences, prefs.keepChars)) refresh()
                "finalOnly" -> { v.setFinalOnly(prefs.finalOnly); refresh() }
            }
        }.also { prefs.listen(it) }
        v.setCardWidth(cardW)
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
        synchronized(cancelLock) { pipeline?.stop() ?: translator?.cancel() }
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
            .setContentTitle("日语字幕运行中")
            .setContentText("点字幕可调整样式；点这里的「停止」结束")
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "jpsub"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        // 8 Gen 3: 1×X4 + 5×A720 + 2×A520. Keep ASR + MT below the 6 big cores so they don't thrash.
        private const val ASR_THREADS = 2
        private const val MT_THREADS = 4
        private const val DRAIN_TIMEOUT_MS = 60_000L
        /** How long after a sentence-end candidate to wait for Jev before finalizing (ASR ~0.1 s + Jev p90 ~0.7 s). */
        private const val JEV_WAIT_MS = 1_000

        /** true from start until onDestroy (main thread); the home screen uses it to show 开始/停止. */
        @Volatile var running = false
            private set

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
