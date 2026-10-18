package io.github.korenkrita.simulsub

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * The models ship inside the APK (app/src/main/assets, put there by setup.sh) and sherpa-onnx reads them from the
 * assets directly:
 *   models/asr/encoder.int8.onnx, decoder.onnx, joiner.int8.onnx, tokens.txt  (ReazonSpeech zipformer)
 *   models/vad/silero_vad.onnx
 */
private const val ASR_DIR = "models/asr"
private const val VAD = "models/vad/silero_vad.onnx"

/**
 * DIY: SenseVoice（中/英/日/韩/粤，语种自动判断）。给一段声音里中日混说的情况用——
 * ReazonSpeech 只认日英，中文会被硬套成日语。代价是模型大（约 239 MB）且更慢，
 * 所以默认仍用 [SherpaAsr]，只有在设置里选了「中英日韩自动」才用它。
 */
private const val SV_DIR = "models/asr_sv"

/** SenseVoice 在中文/日文词之间会插空格（它按词切分），字幕里不该有：只删两侧都是 CJK 的那个空格。 */
private val CJK_GAP = Regex("(?<=[\\u3000-\\u30ff\\u3400-\\u9fff\\uff00-\\uffef])[ \\u3000](?=[\\u3000-\\u30ff\\u3400-\\u9fff\\uff00-\\uffef])")

internal fun tidySenseVoice(s: String): String = s.replace(CJK_GAP, "")

/**
 * @param language "auto" 让它自己判语种；"ja" / "zh" / "en" / "ko" 锁死一种。
 *   直播里的碎句只有零点几秒，自动判语种很容易把日语听成中文或英文，锁死语种能消掉这类误判。
 */
class SenseVoiceAsr(assets: AssetManager, threads: Int, language: String = "auto") : Asr {
    private val rec = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$SV_DIR/model.int8.onnx",
                    language = language,
                    useInverseTextNormalization = true,
                ),
                tokens = "$SV_DIR/tokens.txt",
                numThreads = threads,
                modelType = "sensevoice",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun recognize(samples: FloatArray): String = recognizeTimed(samples).text

    override fun recognizeTimed(samples: FloatArray): Recognition {
        val s = rec.createStream()
        try {
            s.acceptWaveform(Recognition.padded(samples), 16_000)
            rec.decode(s)
            val r = rec.getResult(s)
            return Recognition.of(tidySenseVoice(r.text), r.tokens, r.timestamps, Recognition.PAD_S)
        } finally {
            s.release()
        }
    }

    fun release() = rec.release()
}

class SherpaAsr(assets: AssetManager, threads: Int) : Asr {
    private val rec = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$ASR_DIR/encoder.int8.onnx", decoder = "$ASR_DIR/decoder.onnx", joiner = "$ASR_DIR/joiner.int8.onnx",
                ),
                tokens = "$ASR_DIR/tokens.txt",
                numThreads = threads,
                modelType = "transducer",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun recognize(samples: FloatArray): String = recognizeTimed(samples).text

    override fun recognizeTimed(samples: FloatArray): Recognition {
        val s = rec.createStream()
        try {
            s.acceptWaveform(Recognition.padded(samples), 16_000)
            rec.decode(s)
            val r = rec.getResult(s)
            return Recognition.of(r.text, r.tokens, r.timestamps, Recognition.PAD_S)
        } finally {
            s.release()
        }
    }

    fun release() = rec.release()
}

/** Silero VAD used only for per-frame speech probability; segmentation is done by [Segmenter]. */
class SileroProb(assets: AssetManager) {
    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = VAD, windowSize = 512),
            sampleRate = 16_000,
            numThreads = 1,
        ),
    )
    fun prob(frame: FloatArray): Float = vad.compute(frame)
    fun release() = vad.release()
}
