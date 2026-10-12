package io.github.korenkrita.simulsub

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
 *   mt/index-translate-2b.gguf  (Index-Translate-2B, Qwen3.5)
 */
/** Final model paths; the files are downloaded, verified and published by [ModelStore] (same paths). */
class ModelFiles(root: File) {
    val encoder = File(root, "asr/encoder.int8.onnx")
    val decoder = File(root, "asr/decoder.onnx")
    val joiner = File(root, "asr/joiner.int8.onnx")
    val tokens = File(root, "asr/tokens.txt")
    val vad = File(root, "vad/silero_vad.onnx")
    val mt = File(root, "mt/index-translate-2b.gguf")
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

class LlamaTranslator(m: ModelFiles, threads: Int, private val maxTokens: Int = mtMaxTokens(Lang.ZH)) : Mt {
    @Volatile private var handle: Long = nativeLoad(m.mt.path, threads)

    init {
        check(handle != 0L) { "failed to load ${m.mt}" }
    }

    /** Every sentence stands alone: cross-sentence context made finals worse with HY-MT and is untested with Index. */
    override fun translate(prompt: String): MtResult? = translate(prompt, maxTokens)

    /**
     * [cap] is the caller's: the caption length follows the target language, which the user may change while
     * subtitles run, so it cannot live in this shared instance (one native model serves the whole session).
     */
    fun translate(prompt: String, cap: Int): MtResult? =
        qwenChat(prompt).let { full -> nativeTranslate(handle, full, cap)?.let { MtResult.parse(it, request = full) } }

    /** Safe from any thread until [release]; the owner must make sure no cancel() runs concurrently with release(). */
    override fun cancel() { val h = handle; if (h != 0L) nativeCancel(h) }

    fun release() {
        val h = handle
        handle = 0
        if (h != 0L) nativeFree(h)
    }

    /**
     * The local model with a caption-length cap of its own: the target language may change while subtitles run,
     * and the cap follows it, so one shared [LlamaTranslator] (one native model) serves one [Mt] per setup.
     */
    class Capped(private val inner: LlamaTranslator, private val cap: Int) : Mt {
        override fun translate(prompt: String): MtResult? = inner.translate(prompt, cap)
        override fun cancel() = inner.cancel() // the shared model's own call; safe while it is not released
    }

    companion object {
        init { System.loadLibrary("jpsub") }
        @JvmStatic private external fun nativeLoad(path: String, threads: Int): Long
        @JvmStatic private external fun nativeTranslate(h: Long, prompt: String, maxTokens: Int): ByteArray?
        @JvmStatic private external fun nativeCancel(h: Long)
        @JvmStatic private external fun nativeFree(h: Long)
    }
}
