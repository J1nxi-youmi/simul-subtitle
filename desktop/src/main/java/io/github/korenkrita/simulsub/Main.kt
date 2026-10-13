package io.github.korenkrita.simulsub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.system.exitProcess

/** What the window shows; written by the session thread and the pipeline, read by Compose. */
private val shown = mutableStateOf<List<Caption>>(emptyList())
private val status = mutableStateOf("正在加载模型…")

/** The sysaudio child, and whether the app is quitting; both under [lock], so a launch in flight is never missed. */
private val lock = Object()
private var capture: Process? = null
private var quitting = false

/** Ends capture: the reader then sees EOF. Safe from any thread, also before capture started. */
private fun stopCapture() = synchronized(lock) { capture?.destroy() }

/**
 * One listening session: system audio (tools/mac-capture's sysaudio, 16 kHz mono f32 LE on its stdout) → Silero VAD →
 * [Segmenter] → [Pipeline] (ReazonSpeech, local Index-Translate) → [CaptionBoard]. Settings are the phone's defaults
 * without Jev (Japanese → Chinese). Runs until sysaudio ends, the pipeline overloads or fails, or the app quits.
 */
private fun listen(root: File) {
    val m = File(root, "models").path
    val vad = Vad(VadModelConfig.builder().setSileroVadModelConfig(SileroVadModelConfig.builder()
        .setModel("$m/vad/silero_vad.onnx").setWindowSize(512).build()).setSampleRate(16000).setNumThreads(1).build())
    val rec = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder()
        .setTransducer(OfflineTransducerModelConfig.builder().setEncoder("$m/asr/encoder.int8.onnx")
            .setDecoder("$m/asr/decoder.onnx").setJoiner("$m/asr/joiner.int8.onnx").build())
        .setTokens("$m/asr/tokens.txt").setNumThreads(2).setModelType("transducer").build())
        .setDecodingMethod("greedy_search").build())
    // 0.3 s of silence on both sides, as on the phone: the transducer drops the last syllable without it
    val asr = object : Asr {
        override fun recognize(samples: FloatArray): String {
            val s = rec.createStream()
            try {
                val padded = FloatArray(samples.size + 9600).also { System.arraycopy(samples, 0, it, 4800, samples.size) }
                s.acceptWaveform(padded, 16000); rec.decode(s)
                return rec.getResult(s).text
            } finally { s.release() }
        }
    }
    val target = Lang.ZH
    val llama = MacLlama.nativeLoad("$m/mt/index-translate-2b.gguf", 4)
    check(llama != 0L) { "翻译模型加载失败" }
    val mt = object : Mt {
        override fun translate(prompt: String): MtResult? = qwenChat(prompt).let { full ->
            MacLlama.nativeTranslate(llama, full, mtMaxTokens(target))?.let { MtResult.parse(it, request = full) }
        }
        override fun cancel() = MacLlama.nativeCancel(llama)
    }
    val board = CaptionBoard()
    // overload and failure are final for a Pipeline: stop listening (the reader sees EOF), as the phone does
    val overloaded = java.util.concurrent.atomic.AtomicBoolean(false)
    val failed = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    val pl = Pipeline(asr, mt,
        onCaption = { c -> if (board.accept(c)) shown.value = board.snapshot() },
        onOverload = { overloaded.set(true); status.value = "⚠ 电脑忙不过来，已停止听新的声音"; stopCapture() },
        onFailure = { e -> failed.set(e); e.printStackTrace(); status.value = "出错：${e.message}"; stopCapture() },
        target = target)
    pl.start()
    try {
        val seg = Segmenter({ pl.submit(it) }, minFinalMs = 1_200, maxHoldSilenceMs = 1_500, maxUtteranceMs = 7_000)
        val p = synchronized(lock) {
            if (quitting) return
            ProcessBuilder(File(root, "tools/mac-capture/build/sysaudio").path)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start().also { capture = it }
        }
        status.value = ""
        val input = java.io.DataInputStream(java.io.BufferedInputStream(p.inputStream))
        val bytes = ByteArray(seg.frameSize * 4)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (!overloaded.get() && failed.get() == null) {
            try { input.readFully(bytes) } catch (_: java.io.IOException) { break } // EOF, or the stream closed by destroy
            bb.rewind()
            val frame = FloatArray(seg.frameSize) { bb.getFloat() }
            seg.accept(frame, vad.compute(frame), monoMs())
        }
        p.destroy()
        val code = p.waitFor()
        if (failed.get() == null) {
            // what was already heard is still shown, as on the phone
            if (!overloaded.get()) seg.flush()
            pl.drainFinals(30_000)
            if (!overloaded.get()) status.value = "系统声音已停止（sysaudio 退出码 $code）"
        }
    } finally {
        stopCapture()
        pl.stop(); pl.awaitStopped()
        MacLlama.nativeFree(llama); rec.release(); vad.release()
    }
}

fun main() {
    val root = File(System.getProperty("simulsub.root") ?: ".").absoluteFile
    // also on Cmd-Q / SIGTERM: the helper only notices a lost reader when it next writes
    Runtime.getRuntime().addShutdownHook(Thread { synchronized(lock) { quitting = true; capture?.destroy() } })
    Thread({
        try { listen(root) } catch (e: Throwable) { e.printStackTrace(); status.value = "出错：${e.message}" }
    }, "session").apply { isDaemon = true; start() }
    application {
        val state = rememberWindowState(width = 900.dp, height = 200.dp, position = WindowPosition(Alignment.BottomCenter))
        val quit = { exitProcess(0) } // the shutdown hook ends capture
        Window(onCloseRequest = quit, state = state, title = "同传字幕", undecorated = true, transparent = true,
            alwaysOnTop = true, resizable = true) {
            WindowDraggableArea {
                Box(Modifier.fillMaxSize().background(Color(0xB0000000), RoundedCornerShape(12.dp)).padding(16.dp, 10.dp)) {
                    Captions()
                    Text("×", color = Color(0xFFAAAAAA), fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.TopEnd).clickable { quit() })
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Captions() {
    val captions by shown
    val s by status
    Column(Modifier.fillMaxSize().padding(end = 20.dp)) {
        // the status stays visible however long the captions are
        if (s.isNotEmpty()) Text(s, color = Color(0xFFFDD663), fontSize = 14.sp)
        // newest at the bottom; what does not fit leaves at the top
        Column(Modifier.weight(1f).fillMaxWidth().clipToBounds().wrapContentHeight(Alignment.Bottom, unbounded = true)) {
            if (captions.isEmpty() && s.isEmpty()) Text("正在听系统声音…", color = Color(0xFF8B8B94), fontSize = 16.sp)
            for (c in captions) {
                val dim = if (c.isFinal) 1f else 0.7f
                Text(c.ja, color = Color(0xFFBBBBBB).copy(alpha = dim), fontSize = 14.sp)
                Text(c.zh, color = Color.White.copy(alpha = dim), fontSize = 24.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}
