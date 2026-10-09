import com.k2fsa.sherpa.onnx.*
import dev.krita.jpsub.*
import java.io.File
import javax.sound.sampled.AudioSystem

fun main() {
    val m = System.getProperty("models")
    val vad = Vad(VadModelConfig.builder().setSileroVadModelConfig(SileroVadModelConfig.builder().setModel("$m/vad/silero_vad.onnx").setWindowSize(512).build()).setSampleRate(16000).setNumThreads(1).build())
    val rec = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder()
        .setTransducer(OfflineTransducerModelConfig.builder().setEncoder("$m/asr/encoder.int8.onnx").setDecoder("$m/asr/decoder.onnx").setJoiner("$m/asr/joiner.int8.onnx").build())
        .setTokens("$m/asr/tokens.txt").setNumThreads(2).setModelType("transducer").build()).setDecodingMethod("greedy_search").build())
    val asr = object : Asr { override fun recognize(samples: FloatArray): String { val s = rec.createStream(); val pd = FloatArray(samples.size + 9600); System.arraycopy(samples, 0, pd, 4800, samples.size); s.acceptWaveform(pd, 16000); rec.decode(s); val t = rec.getResult(s).text; s.release(); return t } }
    val mt = object : Mt {
        val h = MacLlama.nativeLoad("$m/mt/hy-mt.gguf", 4)
        override fun translate(text: String): String? {
            val u = "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
            return MacLlama.nativeTranslate(h, "<｜hy_begin▁of▁sentence｜><｜hy_User｜>$u<｜hy_Assistant｜>", 128)?.toString(Charsets.UTF_8)
        }
        override fun cancel() = MacLlama.nativeCancel(h)
    }
    val board = CaptionBoard(10)
    val t0 = System.currentTimeMillis()
    val p = Pipeline(asr, mt, { c ->
        val t = c.timing
        println("%6.2fs seg%d.%d%s audio=%d wait=%d asrQ=%d asr=%d mtQ=%d mt=%d total=%d | %s → %s".format((System.currentTimeMillis()-t0)/1000.0, c.segmentId, c.revision, if (c.isFinal) "F" else "d", t.audioMs, t.segmentWaitMs, t.asrQueueMs, t.asrMs, t.mtQueueMs, t.mtMs, t.totalMs, c.ja, c.zh))
        board.accept(c)
    })
    p.start()
    val seg = Segmenter(p::submit)
    // stream the wav in real time
    val ais = AudioSystem.getAudioInputStream(File(System.getProperty("wav")))
    val bytes = ais.readAllBytes()
    val pcm = FloatArray(bytes.size / 2) { ((bytes[2*it].toInt() and 0xff) or (bytes[2*it+1].toInt() shl 8)).toShort() / 32768f }
    var i = 0; val start = System.nanoTime()
    while (i + 512 <= pcm.size) {
        val f = pcm.copyOfRange(i, i + 512); i += 512
        val due = start + i * 1_000_000_000L / 16000
        while (System.nanoTime() < due) Thread.sleep(1)
        seg.accept(f, vad.compute(f), monoMs())
    }
    seg.flush()
    Thread.sleep(3000)
    p.stop(); p.awaitStopped()
    println("--- final board ---"); board.snapshot().filter { it.isFinal }.forEach { println(it.zh) }
}
