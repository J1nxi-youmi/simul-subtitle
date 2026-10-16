package io.github.korenkrita.simulsub

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
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
