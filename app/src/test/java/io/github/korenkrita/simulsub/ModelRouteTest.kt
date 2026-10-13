package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRouteTest {
    private val tiers = listOf(listOf("hf"), listOf("gh"), listOf("ms", "p1", "p2"))
    private val spec = ModelSpec("mt/m.gguf", tiers, 1, "0".repeat(64))

    /** [ok] answer after [delay] ms, the rest fail after [delay] ms; [hang] never answer within the test */
    private fun fetch(ok: Set<String>, delay: Map<String, Long> = emptyMap(), hang: Set<String> = emptySet()) = { url: String ->
        Thread.sleep(if (url in hang) 5_000 else delay[url] ?: 0)
        url in ok
    }

    @Test fun officialWinsWhenItWorksEvenIfAMirrorAnswersFirst() {
        assertEquals(0, ModelDownloader.bestTier(tiers, 2_000, fetch(setOf("hf", "gh", "p1"), mapOf("hf" to 300))))
    }

    @Test fun githubTierWhenOnlyTheOfficialSourceFails() {
        assertEquals(1, ModelDownloader.bestTier(tiers, 2_000, fetch(setOf("gh", "p1"))))
    }

    @Test fun mirrorsWhenGithubCannotBeReachedEither() {
        assertEquals(2, ModelDownloader.bestTier(tiers, 2_000, fetch(setOf("p2"))))
    }

    @Test fun anOfficialSourceThatHangsLosesAtTheDeadlineNotLater() {
        val t0 = System.nanoTime()
        assertEquals(2, ModelDownloader.bestTier(tiers, 500, fetch(setOf("ms"), hang = setOf("hf", "gh"))))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms in 450..2_000)
    }

    @Test fun decidedAsSoonAsEveryLowerTierFailed() {
        val t0 = System.nanoTime()
        assertEquals(1, ModelDownloader.bestTier(tiers, 5_000, fetch(setOf("gh"), hang = setOf("p1", "p2", "ms"))))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun nothingAnswersNull() {
        assertNull(ModelDownloader.bestTier(tiers, 300, fetch(emptySet(), hang = setOf("hf"))))
        assertNull(ModelDownloader.bestTier(tiers, 2_000, fetch(emptySet())))
    }

    @Test fun aStartedDownloadIsNeverCutOffAndAnAnsweredOneOnlyWhenDead() {
        assertTrue(ModelDownloader.cutOff(0, answered = false, idleMs = 15_001))
        assertTrue(!ModelDownloader.cutOff(0, answered = false, idleMs = 14_000))
        // the server answered (e.g. 32 KiB on disk, below the 64 KiB step of the counter): only after a minute
        assertTrue(!ModelDownloader.cutOff(0, answered = true, idleMs = 30_000))
        assertTrue(ModelDownloader.cutOff(0, answered = true, idleMs = 60_001))
        assertTrue(!ModelDownloader.cutOff(65_536, answered = true, idleMs = 3_600_000))
    }

    @Test fun sourcesSavedBy063AreRenumberedOnlyForTheTranslationModel() {
        assertEquals(0, ModelDownloader.legacySource("mt/index-translate-2b.gguf", 0)) // Hugging Face
        assertEquals(2, ModelDownloader.legacySource("mt/index-translate-2b.gguf", 1)) // ModelScope
        assertEquals(1, ModelDownloader.legacySource("asr/tokens.txt", 1)) // unchanged
        assertEquals(3, ModelDownloader.legacySource("asr/tokens.txt", 3))
        val mt = ModelStore.MODELS.single { it.path.startsWith("mt/") }
        assertTrue(mt.urls[ModelDownloader.legacySource(mt.path, 1)].startsWith("https://www.modelscope.cn/"))
    }

    @Test fun routeSkipsTiersThatLostAndKeepsTheLaterOnes() {
        assertEquals(listOf(0, 1, 2, 3, 4), spec.route(0))
        assertEquals(listOf(1, 2, 3, 4), spec.route(1))
        assertEquals(listOf(2, 3, 4), spec.route(2))
    }
}
