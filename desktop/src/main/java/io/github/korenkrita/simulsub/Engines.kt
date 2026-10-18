package io.github.korenkrita.simulsub

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** SenseVoice 在中文/日文词之间会插空格（它按词切分），字幕里不该有：只删两侧都是 CJK 的那个空格。 */
private val CJK_GAP = Regex("(?<=[\\u3000-\\u30ff\\u3400-\\u9fff\\uff00-\\uffef])[ \\u3000](?=[\\u3000-\\u30ff\\u3400-\\u9fff\\uff00-\\uffef])")

private fun tidy(s: String): String = s.replace(CJK_GAP, "")

/**
 * The speech models under [modelsDir], same layout as in the phone's APK assets:
 *   asr_sv/model.int8.onnx, asr_sv/tokens.txt  (SenseVoice)
 *   vad/silero_vad.onnx
 *
 * @param language "auto" 让它自己判语种；"ja"/"zh"/"en"/"ko"/"yue" 锁死一种。
 */
class SenseVoiceAsr(root: File, threads: Int, language: String = "ja") : Asr {
    private val rec = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder()
        .setSenseVoice(OfflineSenseVoiceModelConfig.builder()
            .setModel(File(root, "asr_sv/model.int8.onnx").path)
            .setLanguage(language)
            .setUseInverseTextNormalization(true).build())
        .setTokens(File(root, "asr_sv/tokens.txt").path).setNumThreads(threads).setModelType("sensevoice").build())
        .setDecodingMethod("greedy_search").build())

    override fun recognize(samples: FloatArray): String = recognizeTimed(samples).text

    override fun recognizeTimed(samples: FloatArray): Recognition {
        val s = rec.createStream()
        try {
            s.acceptWaveform(Recognition.padded(samples), 16_000)
            rec.decode(s)
            val r = rec.getResult(s)
            return Recognition.of(tidy(r.text), r.tokens, r.timestamps, Recognition.PAD_S)
        } finally { s.release() }
    }

    fun release() = rec.release()
}

/** Silero VAD for per-frame speech probability only; [Segmenter] cuts. */
class SileroProb(root: File) {
    private val vad = Vad(VadModelConfig.builder().setSileroVadModelConfig(SileroVadModelConfig.builder()
        .setModel(File(root, "vad/silero_vad.onnx").path).setWindowSize(512).build()).setSampleRate(16_000).setNumThreads(1).build())
    fun prob(frame: FloatArray): Float = vad.compute(frame)
    fun release() = vad.release()
}
