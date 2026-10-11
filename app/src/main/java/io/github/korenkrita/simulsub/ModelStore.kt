package io.github.korenkrita.simulsub

import java.io.File
import java.security.MessageDigest

/**
 * One model file: where it lives in the app's files dir, where it comes from, and what it must be.
 * [urls] are tried in order (official source first, then mirrors of the identical file); size + SHA-256 decide.
 */
class ModelSpec(val path: String, val urls: List<String>, val size: Long, val sha256: String)

/**
 * The fixed model set, pinned to exact revisions so a download is byte-identical to what was measured.
 * A file counts as installed only once it has been verified and published under [ModelSpec.path];
 * downloads land in "<path>.part" first, so a half-written or wrong file never reaches the native code.
 */
class ModelStore(
    val root: File,
    val specs: List<ModelSpec> = MODELS,
    /** read live: false when translating online, so the local model is neither required nor downloaded (nor deleted) */
    private val needsMt: () -> Boolean = { true },
) {
    fun file(s: ModelSpec) = File(root, s.path)
    /** Where a download is written before it is verified; tests and older versions use the plain name. */
    fun partFile(s: ModelSpec) = File(root, s.path + ".part")

    /**
     * A fresh file for one download attempt ("<path>.<attempt>.part"). A removed DownloadManager job may still
     * clean up its file afterwards, so no later attempt ever writes to the same path.
     */
    fun newPartFile(s: ModelSpec, attempt: String) = File(root, "${s.path}.$attempt.part")

    /** Every download file of [s]: the plain "<path>.part" and all "<path>.<attempt>.part". */
    fun partFiles(s: ModelSpec): List<File> {
        val f = file(s)
        return f.parentFile?.listFiles()?.filter {
            it.isFile && it.name.startsWith(f.name + ".") && it.name.endsWith(".part")
        }.orEmpty()
    }

    /** Written next to a file after its SHA-256 matched, so readiness never needs to re-hash 1.4 GB. */
    fun okMarker(s: ModelSpec) = File(root, s.path + ".ok")

    /** Cheap check used on every screen resume: verified marker + right size at the final path. */
    fun isInstalled(s: ModelSpec) = okMarker(s).isFile && file(s).let { it.isFile && it.length() == s.size }

    /** A file at the final path that was never verified (e.g. copied by adb from an older version). */
    fun unverified() = specs.filter { !okMarker(it).isFile && file(it).isFile }

    /**
     * Hash-checks a file already at the final path. Good → marker written; bad → deleted so it
     * is downloaded again. Slow: call off the main thread.
     */
    fun verifyInstalled(s: ModelSpec): Result {
        val f = file(s)
        if (!f.isFile) return Result.Missing
        val r = check(s, f)
        if (r != Result.Ok) return reject(f, r)
        okMarker(s).writeText(s.sha256)
        return r
    }

    private fun check(s: ModelSpec, f: File): Result {
        if (f.length() != s.size) return Result.WrongSize(f.length())
        val got = sha256(f)
        return if (got.equals(s.sha256, ignoreCase = true)) Result.Ok else Result.WrongHash(got)
    }
    /** The files the current mode needs: the local translation model only when translating locally. */
    fun required() = specs.filter { needsMt() || !it.path.startsWith("mt/") }
    fun missing() = required().filter { !isInstalled(it) }
    fun ready() = missing().isEmpty()
    fun totalBytes() = required().sumOf { it.size }
    fun installedBytes() = required().filter(::isInstalled).sumOf { it.size }

    /**
     * Verifies a finished download in [partFile] and, only if size and SHA-256 match, renames it to the final
     * path. A bad file is deleted so the retry has room (the MT model alone is 1.3 GB).
     * Slow for the 1.3 GB model: call off the main thread.
     */
    fun publish(s: ModelSpec, part: File = partFile(s)): Result {
        if (!part.isFile) return Result.Missing
        val r = check(s, part)
        if (r != Result.Ok) return reject(part, r)
        val dst = file(s)
        dst.parentFile?.mkdirs()
        okMarker(s).delete()
        if (!part.renameTo(dst)) return Result.Io("无法写入 ${dst.path}")
        okMarker(s).writeText(s.sha256)
        return Result.Ok
    }

    /** Deletes "<path>.bad" copies kept by older versions; returns an error message if one could not be deleted. */
    fun removeRejected(): String? {
        val stuck = specs.map { File(root, it.path + ".bad") }.filter { !it.delete() && it.exists() }
        return if (stuck.isEmpty()) null else "无法删除旧的损坏文件：" + stuck.joinToString("、") { it.name }
    }

    enum class Kind { CURRENT, OLD, PARTIAL }
    /** A file under a model folder; [path] relative to [root]. */
    data class StoredFile(val path: String, val bytes: Long, val kind: Kind)

    /**
     * Every file in the model folders (asr/, vad/, mt/), current or left behind by older versions / downloads;
     * `.ok` markers are not listed (they go with their file). Unused files first, then by size.
     */
    fun listFiles(): List<StoredFile> {
        val current = specs.map { it.path }.toSet()
        return MODEL_DIRS.flatMap { d -> File(root, d).listFiles()?.filter { it.isFile }.orEmpty() }
            .filter { !it.name.endsWith(".ok") }
            .map { f ->
                val path = f.relativeTo(root).invariantSeparatorsPath
                StoredFile(path, f.length(), when {
                    path in current -> Kind.CURRENT
                    path.endsWith(".part") -> Kind.PARTIAL
                    else -> Kind.OLD
                })
            }
            .sortedWith(compareBy<StoredFile> { it.kind == Kind.CURRENT }.thenByDescending { it.bytes })
    }

    /** Deletes a listed file and its `.ok` marker. Only inside the model folders. @return true if both are gone */
    fun delete(f: StoredFile): Boolean {
        val file = File(root, f.path).canonicalFile
        if (file.parentFile?.name !in MODEL_DIRS || file.parentFile?.parentFile != root.canonicalFile) return false
        val ok = File(file.path + ".ok")
        // the marker first: a file without a marker is re-verified, never trusted
        val okGone = ok.delete() || !ok.exists()
        return okGone && (file.delete() || !file.exists())
    }

    private fun reject(f: File, r: Result): Result {
        if (!f.delete() && f.exists()) return Result.Io("无法删除损坏的 ${f.name}")
        return r
    }

    sealed class Result {
        object Ok : Result()
        object Missing : Result()
        data class WrongSize(val got: Long) : Result()
        data class WrongHash(val got: String) : Result()
        data class Io(val msg: String) : Result()
    }

    companion object {
        private val MODEL_DIRS = listOf("asr", "vad", "mt")
        private const val ASR = "https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870"
        private const val MT = "https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/resolve/449c9e6457b3632d328c6cbb78ae8e8e0c8059a5"
        private const val MT_MODELSCOPE = "https://www.modelscope.cn/models/IndexTeam/Index-Translate-2B-GGUF/resolve/master"
        /** this project's copies of the small models, for when the official source cannot be reached */
        private const val BACKUP = "https://github.com/KorenKrita/simul-subtitle/releases/download/models-v1"
        /** third-party GitHub download proxies, for when github.com itself cannot be reached */
        private val GH_PROXIES = listOf("https://gh-proxy.com/", "https://gh.llkk.cc/")

        private fun withBackup(official: String, name: String) =
            listOf(official, "$BACKUP/$name") + GH_PROXIES.map { "$it$BACKUP/$name" }

        val MODELS = listOf(
            ModelSpec("asr/encoder.int8.onnx", withBackup("$ASR/encoder-epoch-35-avg-1.int8.onnx", "encoder.int8.onnx"), 70_876_409,
                "ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268"),
            ModelSpec("asr/decoder.onnx", withBackup("$ASR/decoder-epoch-35-avg-1.onnx", "decoder.onnx"), 5_165_084,
                "90b60985672df5023e187e9d25aa2dc93abc4286bfacf71a35a78a6b76d88df5"),
            ModelSpec("asr/joiner.int8.onnx", withBackup("$ASR/joiner-epoch-35-avg-1.int8.onnx", "joiner.int8.onnx"), 1_033_417,
                "c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495"),
            ModelSpec("asr/tokens.txt", withBackup("$ASR/tokens.txt", "tokens.txt"), 26_631,
                "144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24"),
            ModelSpec("vad/silero_vad.onnx", withBackup("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                "silero_vad.onnx"), 643_854,
                "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
            ModelSpec("mt/index-translate-2b.gguf", listOf("$MT/Index-Translate-2B.Q4_K_M.gguf",
                "$MT_MODELSCOPE/Index-Translate-2B.Q4_K_M.gguf"), 1_312_164_352,
                "044b313d29342bd3b2c77cbb64023ca0d209bd9b3247763f9d162767ef2d746a"),
        )

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
