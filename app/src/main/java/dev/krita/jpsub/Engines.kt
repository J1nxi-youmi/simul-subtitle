package dev.krita.jpsub

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Model files live in the app's external files dir (pushed with adb, see README):
 *   asr/encoder.int8.onnx, asr/decoder.onnx, asr/joiner.int8.onnx, asr/tokens.txt  (ReazonSpeech zipformer)
 *   vad/silero_vad.onnx
 *   mt/hy-mt.gguf  (HY-MT1.5-1.8B)
 */
class ModelFiles(root: File) {
    val encoder = File(root, "asr/encoder.int8.onnx")
    val decoder = File(root, "asr/decoder.onnx")
    val joiner = File(root, "asr/joiner.int8.onnx")
    val tokens = File(root, "asr/tokens.txt")
    val vad = File(root, "vad/silero_vad.onnx")
    val mt = File(root, "mt/hy-mt.gguf")
    fun missing(): List<File> = listOf(encoder, decoder, joiner, tokens, vad, mt).filter { !it.isFile }
}

class SherpaAsr(m: ModelFiles, threads: Int) : Asr {
    private val rec = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = m.encoder.path, decoder = m.decoder.path, joiner = m.joiner.path,
                ),
                tokens = m.tokens.path,
                numThreads = threads,
                modelType = "transducer",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun recognize(samples: FloatArray): String {
        val s = rec.createStream()
        try {
            // ReazonSpeech drops the first word when speech starts right at sample 0
            // (measured: すみません/そんな/まっすぐ/おじいさん lost). 0.3 s of silence on both ends fixes it.
            s.acceptWaveform(padded(samples), 16_000)
            rec.decode(s)
            return rec.getResult(s).text
        } finally {
            s.release()
        }
    }

    fun release() = rec.release()

    companion object {
        private const val PAD = 4_800 // 0.3 s @16k
        fun padded(x: FloatArray): FloatArray = FloatArray(x.size + 2 * PAD).also { System.arraycopy(x, 0, it, PAD, x.size) }
    }
}

/** Silero VAD used only for per-frame speech probability; segmentation is done by [Segmenter]. */
class SileroProb(m: ModelFiles) {
    private val vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = m.vad.path, windowSize = 512),
            sampleRate = 16_000,
            numThreads = 1,
        ),
    )
    fun prob(frame: FloatArray): Float = vad.compute(frame)
    fun release() = vad.release()
}

class LlamaTranslator(m: ModelFiles, threads: Int) : Mt {
    private var handle: Long = nativeLoad(m.mt.path, threads)

    init {
        check(handle != 0L) { "failed to load ${m.mt}" }
    }

    override fun translate(text: String): String? {
        // HY-MT1.5 official ZH<=>XX template. Cross-sentence context was tried and made finals worse
        // (re-translated the previous sentence / changed meaning), so every sentence stands alone.
        val prompt = "<｜hy_begin▁of▁sentence｜><｜hy_User｜>将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n$text<｜hy_Assistant｜>"
        val bytes = nativeTranslate(handle, prompt, 128) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    override fun cancel() = nativeCancel(handle)

    fun release() {
        nativeFree(handle)
        handle = 0
    }

    companion object {
        init { System.loadLibrary("jpsub") }
        @JvmStatic private external fun nativeLoad(path: String, threads: Int): Long
        @JvmStatic private external fun nativeTranslate(h: Long, prompt: String, maxTokens: Int): ByteArray?
        @JvmStatic private external fun nativeCancel(h: Long)
        @JvmStatic private external fun nativeFree(h: Long)
    }
}
