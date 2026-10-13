package io.github.korenkrita.simulsub

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** The speech models under [modelsDir], same layout as in the phone's APK assets. */
class SherpaAsr(root: File, threads: Int) : Asr {
    private val rec = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder()
        .setTransducer(OfflineTransducerModelConfig.builder().setEncoder(File(root, "asr/encoder.int8.onnx").path)
            .setDecoder(File(root, "asr/decoder.onnx").path).setJoiner(File(root, "asr/joiner.int8.onnx").path).build())
        .setTokens(File(root, "asr/tokens.txt").path).setNumThreads(threads).setModelType("transducer").build())
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
class SileroProb(root: File) {
    private val vad = Vad(VadModelConfig.builder().setSileroVadModelConfig(SileroVadModelConfig.builder()
        .setModel(File(root, "vad/silero_vad.onnx").path).setWindowSize(512).build()).setSampleRate(16_000).setNumThreads(1).build())
    fun prob(frame: FloatArray): Float = vad.compute(frame)
    fun release() = vad.release()
}
