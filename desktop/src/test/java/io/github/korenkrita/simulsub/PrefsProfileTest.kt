package io.github.korenkrita.simulsub

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The named LLM API profiles as [Prefs] keeps them: which fields a profile owns, what the update from the single
 * setting (1.0) reads, who takes over when the one in use is deleted, and which changes tell a running session to
 * switch. Keys go to a keychain service of their own, so a run never touches the app's real secrets.
 */
class PrefsProfileTest {
    private lateinit var file: File
    private lateinit var prefs: Prefs

    @Before fun setUp() {
        Keychain.service = "SimulSubTest"
        file = File.createTempFile("simulsub-prefs", ".json").also { it.delete() }
        prefs = Prefs(file)
    }

    @After fun tearDown() {
        // every account this run could have written, deleted (one that is not there counts as done)
        for (p in prefs.byokProfiles) {
            Keychain.set("byokKey${p.suffix}", null)
            Keychain.set("byokHeaders${p.suffix}", null)
        }
        file.delete()
    }

    private fun saved() = JSONObject(file.readText())

    /** nothing saved yet is an update from 1.0: the one setting there was, under the names it used then */
    @Test fun theSingleSettingBeforeReadsAsTheFirstProfile() {
        assertEquals("", LlmProfile.FIRST.suffix)
        prefs.saveByok("https://api.deepseek.com", "deepseek-chat", key = "sk-old")
        // the fields went to the unsuffixed names, and no profile list was written
        assertEquals("https://api.deepseek.com", saved().getString("byokUrl"))
        assertEquals("deepseek-chat", saved().getString("byokModel"))
        assertFalse(saved().has("byokProfiles"))
        // read back as 「默认」, with its key
        assertEquals(listOf(LlmProfile.FIRST), prefs.byokProfiles)
        assertEquals(LlmProfile.FIRST, prefs.byokProfile)
        assertEquals("https://api.deepseek.com", prefs.byokUrl)
        assertEquals("deepseek-chat", prefs.byokModel)
        assertEquals("sk-old", prefs.byokKey)
    }

    /** a new profile starts empty: none of the fields of the one left behind may show through */
    @Test fun aNewProfileStartsEmptyAndKeepsItsOwnFields() {
        prefs.saveByok("https://api.deepseek.com", "deepseek-chat", key = "sk-one")
        val p = prefs.addByokProfile("千问")
        assertEquals(p, prefs.byokProfile)
        assertEquals("", prefs.byokUrl)
        assertEquals("", prefs.byokModel)
        assertNull(prefs.byokKey)
        prefs.saveByok("https://dashscope.aliyuncs.com", "qwen-max", key = "sk-two")
        // each one's fields are its own; the first one is untouched
        assertEquals("https://api.deepseek.com", saved().getString("byokUrl"))
        assertEquals("deepseek-chat", saved().getString("byokModel"))
        assertEquals("https://dashscope.aliyuncs.com", saved().getString("byokUrl${p.suffix}"))
        assertEquals("sk-two", Keychain.get("byokKey${p.suffix}"))
        // switching back reads the first one again
        prefs.useByokProfile(LlmProfile.FIRST)
        assertEquals("https://api.deepseek.com", prefs.byokUrl)
        assertEquals("deepseek-chat", prefs.byokModel)
        assertEquals("sk-one", prefs.byokKey)
    }

    /** deleting the one in use: the first left takes over, and its fields are the ones read from then on */
    @Test fun deletingTheProfileInUseHandsOverToTheFirstLeft() {
        prefs.saveByok("https://api.deepseek.com", "deepseek-chat", key = "sk-one")
        val p = prefs.addByokProfile("千问")
        prefs.saveByok("https://dashscope.aliyuncs.com", "qwen-max", key = "sk-two")
        assertTrue(prefs.deleteByokProfile(p))
        assertEquals(LlmProfile.FIRST, prefs.byokProfile)
        assertEquals("https://api.deepseek.com", prefs.byokUrl)
        assertEquals("deepseek-chat", prefs.byokModel)
        assertEquals("sk-one", prefs.byokKey)
        // and what the deleted one owned is gone, in the file and in the keychain
        assertFalse(saved().has("byokUrl${p.suffix}"))
        assertFalse(saved().has("byokModel${p.suffix}"))
        assertNull(Keychain.get("byokKey${p.suffix}"))
    }

    /** deleting another one leaves the one in use exactly as it is */
    @Test fun deletingAnotherProfileLeavesTheOneInUse() {
        prefs.saveByok("https://api.deepseek.com", "deepseek-chat", key = "sk-one")
        val a = prefs.addByokProfile("A")
        prefs.saveByok("https://a.example.com", "a-model", key = "sk-a")
        val b = prefs.addByokProfile("B")
        prefs.saveByok("https://b.example.com", "b-model", key = "sk-b")
        assertTrue(prefs.deleteByokProfile(a))
        assertEquals(b, prefs.byokProfile)
        assertEquals("https://b.example.com", prefs.byokUrl)
        assertEquals("b-model", prefs.byokModel)
        assertEquals("sk-b", prefs.byokKey)
    }

    /** the last one is never deleted: there would be nothing left to translate with */
    @Test fun theLastProfileIsNotDeleted() {
        prefs.saveByok("https://api.deepseek.com", "deepseek-chat", key = "sk-one")
        assertFalse(prefs.deleteByokProfile(LlmProfile.FIRST))
        assertEquals(listOf(LlmProfile.FIRST), prefs.byokProfiles)
        assertEquals("deepseek-chat", prefs.byokModel)
        assertEquals("sk-one", prefs.byokKey)
    }

    /** a rename keeps the id, so the fields and the key stay with it */
    @Test fun renamingKeepsTheStoredFields() {
        prefs.saveByok("https://api.deepseek.com", "deepseek-chat", key = "sk-one")
        val p = prefs.addByokProfile("千问")
        prefs.saveByok("https://dashscope.aliyuncs.com", "qwen-max", key = "sk-two")
        prefs.renameByokProfile(p, "阿里千问")
        assertEquals("阿里千问", prefs.byokProfile.name)
        assertEquals(p.id, prefs.byokProfile.id)
        assertEquals("qwen-max", prefs.byokModel)
        assertEquals("sk-two", prefs.byokKey)
        assertEquals("https://dashscope.aliyuncs.com", saved().getString("byokUrl${p.suffix}"))
    }

    /**
     * Only a profile a session can actually use asks it to switch: a half-filled one keeps the running setup
     * (Session.Snap falls back to the service in use), so it must not look like a new save.
     */
    @Test fun onlyAFilledProfileAsksTheSessionToSwitch() {
        prefs.saveByok("https://api.deepseek.com", "deepseek-chat", key = "sk-one")
        val afterSave = saved().getLong("byokSaved")
        val empty = prefs.addByokProfile("空")
        assertEquals(afterSave, saved().getLong("byokSaved"))
        prefs.useByokProfile(empty)
        assertEquals(afterSave, saved().getLong("byokSaved"))
        // back to the filled one: that is a switch the next sentence must see
        prefs.useByokProfile(LlmProfile.FIRST)
        assertTrue(saved().getLong("byokSaved") > afterSave)
    }
}
