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
    private var lastTiming: Timing? = null
    private var shownMs = 0L
    @Volatile private var backlog = 0
    @Volatile private var overloaded = false

    private var overlay: TextView? = null
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
        showOverlay()
        Thread({ session(p) }, "jpsub-session").start()
        return START_NOT_STICKY
    }

    private fun session(p: MediaProjection) {
        val files = ModelFiles(getExternalFilesDir(null)!!)
        val missing = files.missing()
        if (missing.isNotEmpty()) {
            setStatus("缺少模型文件：\n" + missing.joinToString("\n") { it.path })
            return
        }
        var vad: SileroProb? = null
        var asr: SherpaAsr? = null
        var mt: LlamaTranslator? = null
        var log: FileWriter? = null
        try {
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
                    overloaded = true
                    setStatus("⚠ 手机处理跟不上，已停止接收新音频（未丢句）。长按字幕结束后重开。")
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
            while (!stopping) {
                var off = 0
                while (off < frame.size && !stopping) {
                    val n = rec.read(frame, off, frame.size - off, AudioRecord.READ_NON_BLOCKING)
                    check(n >= 0) { "读取音频失败 ($n)" }
                    if (n == 0) Thread.sleep(10)
                    off += n
                }
                if (stopping) break
                framesRead += frame.size
                // Capture time of this frame's last sample, from the HAL timestamp when available,
                // so time spent waiting in the AudioRecord buffer counts as latency.
                val capturedAt = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    (ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / 16_000) / 1_000_000
                } else monoMs()
                val f = frame.copyOf()
                seg.accept(f, vad.prob(f), capturedAt)
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
        if (board.accept(c)) {
            val audioEndAt = monoMs() - t.mtDoneMs // reconstruct the capture time of the last sample
            refresh { shownMs = monoMs() - audioEndAt; lastTiming = t }
        }
    }

    private fun setStatus(s: String) { status = s; refresh() }

    /** Re-renders on the main thread; [onShown] runs right after the text is set (UI-delivery timing). */
    private fun refresh(onShown: () -> Unit = {}) = main.post {
        val v = overlay ?: return@post
        onShown()
        val sb = SpannableStringBuilder()
        for (c in board.snapshot()) {
            if (sb.isNotEmpty()) sb.append('\n')
            val start = sb.length
            sb.append(c.zh)
            if (!c.isFinal) sb.setSpan(ForegroundColorSpan(0xFFBBBBBB.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val diag = buildString {
            if (status.isNotEmpty()) append(status)
            lastTiming?.let { t ->
                if (isNotEmpty()) append("  ")
                append("识别${t.asrMs}ms 翻译${t.mtMs}ms")
                append(" 上屏延迟${"%.1f".format(shownMs / 1000.0)}s")
            }
            if (backlog >= 2 && !overloaded) append("  ⚠处理跟不上(积压$backlog 句)")
        }
        if (diag.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            val start = sb.length
            sb.append(diag)
            sb.setSpan(RelativeSizeSpan(0.55f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(0xFF88CCFF.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        v.text = sb
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 12, 24, 12)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = resources.displayMetrics.heightPixels * 2 / 3
        }
        // Drag vertically to move; long-press to stop.
        var downY = 0f; var startY = 0; var downAt = 0L; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; downAt = e.eventTime; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > 10) moved = true
                    lp.y = startY + dy; wm.updateViewLayout(tv, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved && e.eventTime - downAt > 800) stopSelf()
            }
            true
        }
        wm.addView(tv, lp)
        overlay = tv
        refresh()
    }

    override fun onDestroy() {
        // Never block the main thread and never free native objects here: the session thread
        // notices `stopping`, finishes, waits for the pipeline workers and releases everything itself.
        stopping = true
        // Only flags + an atomic store into a still-alive translator (the session thread detaches it under the
        // same lock before freeing). The session thread notices `stopping` within ~10 ms (non-blocking reads).
        synchronized(cancelLock) { pipeline?.stop() ?: translator?.cancel() }
        projection?.stop()
        projection = null
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
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("日语字幕运行中")
            .setContentText("长按字幕或点“停止”结束")
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

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, CaptionService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
            )
        }
    }
}
