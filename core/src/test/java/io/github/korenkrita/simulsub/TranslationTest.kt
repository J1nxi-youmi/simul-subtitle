package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTest {
    @Test
    fun reasoningIsNeverShownAndOnlyEndedAnswersAreUsable() {
        val r = MtResult("<think>\n\n</think>\n\n各位，请祈祷B站不要倒闭吧</think>", 'e', 87, 12)
        assertEquals("各位，请祈祷B站不要倒闭吧", r.text); assertTrue(r.usable)
        assertFalse(MtResult("", 'f', 0, 0).usable)
        assertTrue(MtResult("很长", 'l', 50, 160).usable)
    }

    @Test
    fun aNoteInTheTranslationIsNeverDropped() {
        // the source text itself carries a note; its characters also appear in the scene background
        val text = "下次直播见\n（注：这是B站直播）"
        assertEquals(text, MtResult(text, 'e', 1, 1).text)
    }

    @Test
    fun theSystemMessageNamesTheLanguagesAndTheTermListIsOnlyForAChineseTarget() {
        val zh = byokSystem(Scene.VTUBER, Lang.JA, Lang.ZH)
        assertTrue(zh, zh.contains("日语翻译成自然、口语化的中文") && zh.contains("館長→舰长") && zh.contains("这是日本虚拟主播"))
        val en = byokSystem(Scene.VTUBER, Lang.JA, Lang.EN)
        assertTrue(en, !en.contains("舰长") && en.contains("翻译成自然、口语化的英语"))
        // another or an unknown source language: the background does not say the streamer is Japanese
        val auto = byokSystem(Scene.VTUBER, null, Lang.ZH)
        assertTrue(auto, auto.contains("外语") && auto.contains("这是虚拟主播（VTuber）") && !auto.contains("日本虚拟主播"))
        assertFalse(byokSystem(Scene.GENERAL, Lang.JA, Lang.ZH).contains("背景"))
    }

    @Test
    fun fixNotesWithJapaneseExamplesOnlyForJapaneseToChinese() {
        assertEquals(FIX_NOTES["negation"], fixNote("negation", Lang.JA, Lang.ZH))
        assertEquals(FIX_NOTES_GENERAL["negation"], fixNote("negation", Lang.EN, Lang.ZH))
        assertEquals(FIX_NOTES_GENERAL["reversal"], fixNote("reversal", Lang.JA, Lang.EN))
        assertEquals(null, fixNote("ok", Lang.JA, Lang.ZH))
    }
}
