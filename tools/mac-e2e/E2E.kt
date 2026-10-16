import com.k2fsa.sherpa.onnx.*
import io.github.korenkrita.simulsub.*
import java.io.File
import javax.sound.sampled.AudioSystem

fun main() {
    // Hard deadline for the whole run: a native call stuck in uninterruptible code would otherwise hang
    // awaitStopped() forever. halt() skips shutdown hooks and cannot be blocked by worker threads.
    val limitS = (System.getProperty("deadlineS") ?: "180").toLong()
    Thread({ Thread.sleep(limitS * 1000); System.err.println("E2E: deadline ${limitS}s exceeded"); Runtime.getRuntime().halt(2) }, "e2e-watchdog")
        .apply { isDaemon = true; start() }
    val m = System.getProperty("models")
    val vad = Vad(VadModelConfig.builder().setSileroVadModelConfig(SileroVadModelConfig.builder().setModel("$m/vad/silero_vad.onnx").setWindowSize(512).build()).setSampleRate(16000).setNumThreads(1).build())
    val rec = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder()
        .setTransducer(OfflineTransducerModelConfig.builder().setEncoder("$m/asr/encoder.int8.onnx").setDecoder("$m/asr/decoder.onnx").setJoiner("$m/asr/joiner.int8.onnx").build())
        .setTokens("$m/asr/tokens.txt").setNumThreads(2).setModelType("transducer").build()).setDecodingMethod("greedy_search").build())
    val asr = object : Asr {
        override fun recognize(samples: FloatArray) = recognizeTimed(samples).text
        override fun recognizeTimed(samples: FloatArray): Recognition {
            val s = rec.createStream()
            s.acceptWaveform(Recognition.padded(samples), 16000); rec.decode(s); val r = rec.getResult(s); s.release()
            return Recognition.of(r.text, r.tokens, r.timestamps, Recognition.PAD_S)
        }
    }
    val scene = Scene.DEFAULTS.first { it.id == (System.getProperty("scene") ?: "VTUBER") }
    // -Dsource=<ja|en|auto> -Dtarget=<code>: languages as in the app (default ja -> zh)
    val source = (System.getProperty("source") ?: "ja").let { if (it == "auto") null else Lang.of(it) ?: error("source $it") }
    // -Dtarget=ko for a preset, -Dtarget=custom:粤语 for a typed language
    val target = io.github.korenkrita.simulsub.Target.parse(System.getProperty("target") ?: "zh") ?: error("target")
    // translation: an OpenAI-compatible service, as in the app (BYOK_URL, BYOK_MODEL, BYOK_KEY); the switch that
    // turns thinking off is probed first, as the app does on a first use
    val url = ByokTranslator.chatUrl(System.getenv("BYOK_URL") ?: error("BYOK_URL")).let { it.second ?: error("BYOK_URL: ${it.first}") }
    val model = System.getenv("BYOK_MODEL") ?: error("BYOK_MODEL"); val key = System.getenv("BYOK_KEY") ?: error("BYOK_KEY")
    val system = byokSystem(scene, source, target)
    val service = ByokService(url, model, key)
    val variant = ByokTranslator(service, system, null).probe(mutableListOf())
    System.err.println("BYOK thinking switch: ${variant?.label ?: "none found"}")
    val mt: Mt = ByokTranslator(service, system, variant, maxTokens = mtMaxTokens(target))
    // -Djev=1 uses the real Jev (TYPESAFE_API_KEY); -Dlog=<file> writes the session events as JSONL
    // -Djev=zen: the free keyless Zen endpoint; any other value: TypeSafe official with TYPESAFE_API_KEY
    val judge = when (System.getProperty("jev")) {
        null -> null
        "zen" -> Jev(JevProvider.ZEN, "", source = source, target = target)
        else -> Jev(JevProvider.OFFICIAL, System.getenv("TYPESAFE_API_KEY") ?: error("TYPESAFE_API_KEY"), source = source, target = target)
    }
    // -DjevEnds=0: with -Djev, only check translations (as the app's check switch without sentence-end judging)
    val judgeEnds = judge != null && System.getProperty("jevEnds") != "0"
    val jsonl = System.getProperty("log")?.let { File(it).bufferedWriter() }
    val board = CaptionBoard(10)
    val t0 = System.currentTimeMillis()
    val submittedFinals = java.util.Collections.synchronizedList(ArrayList<Long>())
    val doneFinals = java.util.Collections.synchronizedList(ArrayList<Long>())
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    // -DdropFinal=<segmentId>: test hook that swallows one delivered final, to prove the harness fails when one is lost
    val dropFinal = System.getProperty("dropFinal")?.toLong()
    val p = Pipeline(asr, mt, { c ->
        val t = c.timing
        println("%6.2fs seg%d.%d%s audio=%d wait=%d asrQ=%d asr=%d mtQ=%d mt=%d mtDone=%d | %s → %s".format((System.currentTimeMillis()-t0)/1000.0, c.segmentId, c.revision, if (c.isFinal) "F" else "d", t.audioMs, t.segmentWaitMs, t.asrQueueMs, t.asrMs, t.mtQueueMs, t.mtMs, t.mtDoneMs, c.ja, c.zh))
        if (c.isFinal && c.segmentId != dropFinal) doneFinals.add(c.segmentId)
        jsonl?.let { w -> synchronized(w) { w.write(Json.encode(mapOf("t" to monoMs(), "ev" to "caption", "seg" to c.segmentId,
            "rev" to c.revision, "source_rev" to c.sourceRevision, "final" to c.isFinal, "ja" to c.ja, "zh" to c.zh,
            "audio_ms" to t.audioMs, "wait_ms" to t.segmentWaitMs, "asr_q_ms" to t.asrQueueMs, "asr_ms" to t.asrMs,
            "mt_q_ms" to t.mtQueueMs, "mt_ms" to t.mtMs, "check_ms" to t.checkMs, "done_ms" to t.mtDoneMs))); w.write("\n") } }
        board.accept(c)
    }, onOverload = { System.err.println("OVERLOAD") }, onFailure = { failure.set(it) }, scene = scene, source = source, target = target, judge = judge,
       judgeEnds = judgeEnds, checkTranslations = System.getProperty("jevCheck") == "1", // -DjevCheck=1: also check translations
       log = { e -> jsonl?.let { w -> synchronized(w) { w.write(Json.encode(mapOf("t" to monoMs()) + e)); w.write("\n") } } })
    p.start()
    try {
        val seg = Segmenter({ job ->
            // -DdumpJobs=<dir>: write every ASR job's audio as raw float32 (seg-rev.f32) to re-check recognition offline
            System.getProperty("dumpJobs")?.let { d ->
                val b = java.nio.ByteBuffer.allocate(job.samples.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                job.samples.forEach { b.putFloat(it) }
                File(d).mkdirs(); File(d, "${job.segmentId}-${job.revision}.f32").writeBytes(b.array())
            }
            val ok = p.submit(job)
            if (job.isFinal) { check(ok) { "final ${job.segmentId} refused (overload/failure)" }; submittedFinals.add(job.segmentId) }
        }, endSilenceMs = (System.getProperty("endSilenceMs") ?: "400").toInt(),
           maxUtteranceMs = (System.getProperty("maxUtteranceMs") ?: if (judgeEnds) "5000" else "7000").toInt(), // as the app
           minFinalMs = (System.getProperty("minFinalMs") ?: "400").toInt(), // as the app
           maxHoldSilenceMs = (System.getProperty("maxHoldSilenceMs") ?: "1500").toInt(),
           hintWaitMs = if (judgeEnds) (System.getProperty("hintWaitMs") ?: "1000").toInt() else 0,
           askSilenceMs = (System.getProperty("askSilenceMs") ?: System.getProperty("endSilenceMs") ?: "400").toInt(),
           holdHint = p::holdHint)
        if (System.getProperty("wav") == "-") {
            // live: 16 kHz mono float32 LE on stdin (tools/mac-capture/build/sysaudio), until it ends
            val input = java.io.DataInputStream(java.io.BufferedInputStream(System.`in`))
            val b = ByteArray(512 * 4)
            while (true) {
                try { input.readFully(b) } catch (_: java.io.EOFException) { break }
                val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val f = FloatArray(512) { bb.getFloat() }
                seg.accept(f, vad.compute(f), monoMs())
            }
        } else {
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
        }
        seg.flush()
        // wait until every accepted final has been delivered (bounded), instead of a fixed sleep
        val left = p.drainFinals(30_000)
        check(left == 0) { "$left accepted finals not delivered within 30 s" }
    } finally {
        p.stop(); p.awaitStopped()
        jsonl?.close()
    }
    println("--- final board ---"); board.snapshot().filter { it.isFinal }.forEach { println(it.zh) }
    failure.get()?.let { throw it }
    check(doneFinals == submittedFinals) { "finals submitted=$submittedFinals delivered=$doneFinals" }
    check(submittedFinals.isNotEmpty()) { "no speech detected" }
    println("OK: ${doneFinals.size} finals delivered in order")
}
