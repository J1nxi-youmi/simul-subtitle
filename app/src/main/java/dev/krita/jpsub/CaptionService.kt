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
import java.io.FileWriter

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
    private var translator: LlamaTranslator? = null

    private val board = CaptionBoard(maxLines = 2)
    // main thread only
    private val lastUpdateMs = HashMap<Pair<Long, Int>, Long>()
    @Volatile private var backlog = 0
    @Volatile private var overloaded = false

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
        var mt: LlamaTranslator? = null
        var log: FileWriter? = null
        var keepOverlay = false
        try {
            // the home screen only starts with verified models; if they vanished since, end cleanly (finally → stopSelf)
            if (!ModelStore(root).ready()) { Log.w(TAG, "models missing, not starting"); return }
            val t0 = monoMs()
            vad = SileroProb(files)
            asr = SherpaAsr(files, ASR_THREADS)
            val t1 = monoMs()
            setStatus("加载翻译模型…")
            if (stopping) return
            mt = LlamaTranslator(files, MT_THREADS)
            synchronized(cancelLock) { translator = mt }
            val t2 = monoMs()
            // Warm-up so the first real sentence does not pay page-in / first-run costs.
            asr.recognize(FloatArray(16_000))
            mt.translate("こんにちは。")
            val t3 = monoMs()
            Log.i(TAG, "load asr=${t1 - t0}ms mt=${t2 - t1}ms warmup=${t3 - t2}ms")
            if (stopping) return
            setStatus("就绪（加载 ${(t3 - t0) / 1000.0}s）")

            log = runCatching {
                FileWriter(File(getExternalFilesDir(null), "timing.csv"), true).apply {
                    write("mono_ms,segment,revision,final,audio_ms,segment_wait_ms,asr_queue_ms,asr_ms,mt_queue_ms,mt_ms,mt_done_ms,ja,zh\n")
                }
            }.getOrNull()
            val csv = log
            val pl = Pipeline(
                asr, mt,
                onCaption = { onCaption(it, csv) },
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
            )
            synchronized(cancelLock) { pipeline = pl }
            if (stopping) return // onDestroy ran before the pipeline was visible to it
            pl.start()
            try {
                capture(p, pl, vad)
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
            runCatching { log?.close() }
            // Detach the translator under the same lock onDestroy uses for cancel(), so no thread can call
            // into it after this point; the (slow) native free then happens outside the lock.
            synchronized(cancelLock) { pipeline = null; translator = null }
            mt?.release(); asr?.release(); vad?.release()
            Log.i(TAG, "session released")
            if (keepOverlay) while (!stopping) Thread.sleep(100) // only the overlay is alive now
            if (!stopping) main.post { stopSelf() }
        }
    }

    /** Blocking capture loop on the session thread; returns when stopping or on a read error. */
    @SuppressLint("MissingPermission") // checked in MainActivity
    private fun capture(p: MediaProjection, pl: Pipeline, vad: SileroProb) {
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
            val seg = Segmenter({ pl.submit(it) })
            val frame = FloatArray(seg.frameSize)
            val ts = AudioTimestamp()
            var framesRead = 0L
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
                seg.accept(f, vad.prob(f), capturedAt, estimated = !hal)
            }
            seg.flush()
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun onCaption(c: Caption, log: FileWriter?) {
        val t = c.timing
        Log.i(TAG, "seg=${c.segmentId}.${c.revision}${if (c.isFinal) "F" else "d"} audio=${t.audioMs} wait=${t.segmentWaitMs} " +
            "asrQ=${t.asrQueueMs} asr=${t.asrMs} mtQ=${t.mtQueueMs} mt=${t.mtMs} mtDone=${t.mtDoneMs} | ${c.ja} → ${c.zh}")
        // Diagnostics must never stop captions (e.g. storage full).
        runCatching {
            fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            log?.write("${monoMs()},${c.segmentId},${c.revision},${c.isFinal},${t.audioMs},${t.segmentWaitMs},${t.asrQueueMs}," +
                "${t.asrMs},${t.mtQueueMs},${t.mtMs},${t.mtDoneMs},${q(c.ja)},${q(c.zh)}\n")
            if (c.isFinal) log?.flush()
        }
        if (board.accept(c)) refresh()
    }

    private fun setStatus(s: String) { status = s; refresh() }

    private fun refresh() = main.post {
        val v = overlay ?: return@post
        val shown = board.snapshot()
        val showJa = prefs.showJa
        val lines = shown.map { CaptionOverlayView.Line(it.zh, if (showJa) it.ja else null, it.isFinal) }
        // status messages (loading, errors, overload) always show; timings only when enabled in settings
        val diag = buildString {
            if (status.isNotEmpty()) append(status)
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
