package io.github.korenkrita.simulsub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    private val release = UpdateRelease(4, "0.4", "## 新功能\n\n- **更新**更方便\n- [说明](https://example.com)",
        "${UpdateRelease.REPO}/releases/download/v0.4/simul-subtitle-v0.4.apk", 13_000_000, "a".repeat(64))

    @Test fun manifestRoundTrip() {
        assertEquals(release, UpdateRelease.parse(release.json()))
        assertEquals(release.sha256, UpdateRelease.parse(JSONObject(release.json()).put("sha256", "A".repeat(64)).toString()).sha256)
    }

    @Test fun onlyHigherVersionCodeCounts() {
        assertTrue(release.isNewer(3))
        assertFalse(release.isNewer(4))
        assertFalse(release.isNewer(5))
        // Names are display text, not decimal numbers or lexicographic ordering.
        assertTrue(release.copy(versionCode = 10, versionName = "0.10").isNewer(9))
    }

    @Test fun manualIgnoresNeverSkipAndLaterButNotInstalledVersion() {
        assertTrue(release.shouldOffer(3, true, false, 4, 4, 2000, 1000))
        assertFalse(release.shouldOffer(4, true, false, 4, 4, 2000, 1000))
        assertFalse(release.shouldOffer(3, false, false, 0, 0, 0, 1000))
    }

    @Test fun skippingOneReleaseDoesNotHideTheNext() {
        assertFalse(release.shouldOffer(3, false, true, 4, 0, 0, 1000))
        assertTrue(release.copy(versionCode = 5).shouldOffer(3, false, true, 4, 0, 0, 1000))
    }

    @Test fun laterExpiresAndDoesNotHideAnotherRelease() {
        assertFalse(release.shouldOffer(3, false, true, 0, 4, 2000, 1999))
        assertTrue(release.shouldOffer(3, false, true, 0, 4, 2000, 2000))
        assertTrue(release.copy(versionCode = 5).shouldOffer(3, false, true, 0, 4, 2000, 1999))
    }

    @Test fun automaticCheckIsThrottledAndRecoversFromClockRollback() {
        assertTrue(UpdateRelease.checkDue(0, 100))
        assertFalse(UpdateRelease.checkDue(100, 100 + UpdateRelease.CHECK_INTERVAL - 1))
        assertTrue(UpdateRelease.checkDue(100, 100 + UpdateRelease.CHECK_INTERVAL))
        assertTrue(UpdateRelease.checkDue(100, 99))
    }

    @Test fun directFirstThenTheExistingDownloadProxies() {
        assertEquals(listOf(UpdateRelease.LATEST, "https://gh-proxy.com/${UpdateRelease.LATEST}",
            "https://gh.llkk.cc/${UpdateRelease.LATEST}"), UpdateRelease.sources(UpdateRelease.LATEST))
        assertEquals(release.apkUrl, UpdateRelease.sources(release.apkUrl).first())
    }

    @Test fun rejectIncompleteUnsafeOrInconsistentManifest() {
        for ((key, value) in listOf(
            "versionCode" to 0, "versionCode" to 4.5, "versionCode" to 2147483648L,
            "versionName" to "../0.4", "versionName" to "0.4-beta", "versionName" to "0.5",
            "size" to 0, "size" to 200_000_001L, "size" to 1.5,
            "sha256" to "bad", "apkUrl" to "http://github.com/file.apk",
            "apkUrl" to "https://example.com/file.apk",
            "apkUrl" to "${release.apkUrl}?redirect=other",
        )) {
            assertThrows("$key=$value", Exception::class.java) {
                UpdateRelease.parse(JSONObject(release.json()).put(key, value).toString())
            }
        }
        for (key in listOf("versionCode", "versionName", "notes", "apkUrl", "size", "sha256")) {
            assertThrows(Exception::class.java) { UpdateRelease.parse(JSONObject(release.json()).apply { remove(key) }.toString()) }
        }
        assertThrows(Exception::class.java) { UpdateRelease.parse("<html>proxy error</html>") }
        assertThrows(Exception::class.java) { UpdateRelease.parse(release.copy(notes = "中".repeat(UpdateRelease.MAX_MANIFEST)).json()) }
    }

    @Test fun releaseNotesRemainReadableWithoutMarkdownNoise() {
        assertEquals("新功能\n\n• 更新更方便\n• 说明", UpdateRelease.readableNotes(release.notes))
        assertFalse(UpdateRelease.readableNotes("   ").isBlank())
    }

    @Test fun hostileNotesFormatQuickly() {
        val t = System.nanoTime()
        UpdateRelease.readableNotes("[".repeat(120_000))
        UpdateRelease.readableNotes("[a](".repeat(30_000))
        assertTrue((System.nanoTime() - t) / 1_000_000 < 500)
    }
}
