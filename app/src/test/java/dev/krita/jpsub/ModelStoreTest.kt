package dev.krita.jpsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val good = "hello model".toByteArray()
    private fun spec(bytes: ByteArray = good, path: String = "asr/a.onnx") =
        ModelSpec(path, "https://example.invalid/a", bytes.size.toLong(), sha(bytes))
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun write(f: File, b: ByteArray) { f.parentFile.mkdirs(); f.writeBytes(b) }

    @Test
    fun missingAndZeroByteFilesAreNotReady() {
        val s = spec()
        val store = ModelStore(tmp.root, listOf(s))
        assertFalse(store.ready())
        write(store.file(s), ByteArray(0))
        assertFalse("zero-byte file must not count", store.ready())
    }

    @Test
    fun verifiedDownloadIsPublishedAndReady() {
        val s = spec()
        val store = ModelStore(tmp.root, listOf(s))
        write(store.partFile(s), good)
        assertEquals(ModelStore.Result.Ok, store.publish(s))
        assertTrue(store.ready())
        assertFalse(store.partFile(s).exists())
        assertEquals(good.toList(), store.file(s).readBytes().toList())
    }

    @Test
    fun wrongContentWithRightSizeIsRejectedAndNeverPublished() {
        val s = spec()
        val store = ModelStore(tmp.root, listOf(s))
        write(store.partFile(s), "hello MODEL".toByteArray()) // same length, different bytes
        assertTrue(store.publish(s) is ModelStore.Result.WrongHash)
        assertFalse(store.file(s).exists())
        assertFalse(store.ready())
        assertFalse("rejected download is moved away", store.partFile(s).exists())
    }

    @Test
    fun truncatedOrErrorPageDownloadIsRejected() {
        val s = spec()
        val store = ModelStore(tmp.root, listOf(s))
        write(store.partFile(s), "<html>401</html>".toByteArray())
        assertTrue(store.publish(s) is ModelStore.Result.WrongSize)
        assertFalse(store.ready())
    }

    @Test
    fun failedRetryKeepsTheFilesAlreadyVerified() {
        val a = spec(path = "asr/a.onnx")
        val b = spec("other".toByteArray(), path = "mt/b.gguf")
        val store = ModelStore(tmp.root, listOf(a, b))
        write(store.partFile(a), good); assertEquals(ModelStore.Result.Ok, store.publish(a))
        write(store.partFile(b), "broken".toByteArray())
        assertTrue(store.publish(b) !is ModelStore.Result.Ok)
        assertEquals(listOf(b), store.missing())
        assertTrue(store.isInstalled(a))
    }

    @Test
    fun sameLengthBadFinalFileIsNotReadyAndIsRejectedOnVerify() {
        val s = spec()
        val store = ModelStore(tmp.root, listOf(s))
        write(store.file(s), "hello MODEL".toByteArray()) // e.g. pushed by hand, never verified
        assertFalse("unverified file must not count as installed", store.ready())
        assertEquals(listOf(s), store.unverified())
        assertTrue(store.verifyInstalled(s) is ModelStore.Result.WrongHash)
        assertFalse(store.file(s).exists())
        assertTrue(store.unverified().isEmpty())
        assertEquals(listOf(s), store.missing())
    }

    @Test
    fun goodUnverifiedFinalFileBecomesReadyAfterVerify() {
        val s = spec()
        val store = ModelStore(tmp.root, listOf(s))
        write(store.file(s), good)
        assertFalse(store.ready())
        assertEquals(ModelStore.Result.Ok, store.verifyInstalled(s))
        assertTrue(store.ready())
    }

    @Test
    fun publishTwiceIsHarmless() {
        // a second verifier (e.g. a recreated screen) must not turn a published file into a failure
        val s = spec()
        val store = ModelStore(tmp.root, listOf(s))
        write(store.partFile(s), good)
        assertEquals(ModelStore.Result.Ok, store.publish(s))
        assertEquals(ModelStore.Result.Missing, store.publish(s))
        assertTrue(store.ready())
    }

    @Test
    fun pinnedManifestMatchesWhatWasMeasured() {
        // The Mac e2e and the phone must use the same bytes; guard against accidental edits.
        assertEquals(1_210_825_907L, ModelStore(tmp.root).totalBytes())
        assertTrue(ModelStore.MODELS.all { it.url.startsWith("https://") && it.sha256.length == 64 })
        assertEquals(6, ModelStore.MODELS.map { it.path }.toSet().size)
    }
}
