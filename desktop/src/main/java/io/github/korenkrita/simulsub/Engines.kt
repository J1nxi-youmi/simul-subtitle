package io.github.korenkrita.simulsub

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** Final model paths, as on the phone; [ModelStore] downloads, verifies and publishes them there. */
class ModelFiles(root: File) {
    val encoder = File(root, "asr/encoder.int8.onnx")
    val decoder = File(root, "asr/decoder.onnx")
    val joiner = File(root, "asr/joiner.int8.onnx")
    val tokens = File(root, "asr/tokens.txt")
    val vad = File(root, "vad/silero_vad.onnx")
    val mt = File(root, "mt/index-translate-2b.gguf")
}

class SherpaAsr(m: ModelFiles, threads: Int) : Asr {
    private val rec = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder()
        .setTransducer(OfflineTransducerModelConfig.builder().setEncoder(m.encoder.path)
            .setDecoder(m.decoder.path).setJoiner(m.joiner.path).build())
        .setTokens(m.tokens.path).setNumThreads(threads).setModelType("transducer").build())
        .setDecodingMethod("greedy_search").build())

    override fun recognize(samples: FloatArray): String {
        val s = rec.createStream()
        try {
            // 0.3 s of silence on both sides, as on the phone: ReazonSpeech drops a word at the very edge without it
            s.acceptWaveform(FloatArray(samples.size + 2 * PAD).also { System.arraycopy(samples, 0, it, PAD, samples.size) }, 16_000)
            rec.decode(s)
            return rec.getResult(s).text
        } finally { s.release() }
    }

    fun release() = rec.release()

    private companion object { const val PAD = 4_800 }
}

/** Silero VAD for per-frame speech probability only; [Segmenter] cuts. */
class SileroProb(m: ModelFiles) {
    private val vad = Vad(VadModelConfig.builder().setSileroVadModelConfig(SileroVadModelConfig.builder()
        .setModel(m.vad.path).setWindowSize(512).build()).setSampleRate(16_000).setNumThreads(1).build())
    fun prob(frame: FloatArray): Float = vad.compute(frame)
    fun release() = vad.release()
}

/** The local Index-Translate model (llama.cpp, CPU) through libjpsub_mac; one per session, see the phone's. */
class LlamaTranslator(m: ModelFiles, threads: Int) : Mt {
    @Volatile private var handle: Long = MacLlama.nativeLoad(m.mt.path, threads)

    init { check(handle != 0L) { "翻译模型加载失败" } }

    override fun translate(prompt: String): MtResult? = translate(prompt, mtMaxTokens(Lang.ZH))

    fun translate(prompt: String, cap: Int): MtResult? =
        qwenChat(prompt).let { full -> MacLlama.nativeTranslate(handle, full, cap)?.let { MtResult.parse(it, request = full) } }

    override fun cancel() { val h = handle; if (h != 0L) MacLlama.nativeCancel(h) }

    fun release() { val h = handle; handle = 0; if (h != 0L) MacLlama.nativeFree(h) }

    /** One [Mt] per setup over the shared model: the caption length cap follows the target language. */
    class Capped(private val inner: LlamaTranslator, private val cap: Int) : Mt {
        override fun translate(prompt: String): MtResult? = inner.translate(prompt, cap)
        override fun cancel() = inner.cancel()
    }
}
