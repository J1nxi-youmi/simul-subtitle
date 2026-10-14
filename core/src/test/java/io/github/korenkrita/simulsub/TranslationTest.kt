package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        val zh = byokSystem(Scene.VTUBER, Lang.JA, Target.ZH)
        assertTrue(zh, zh.contains("日语翻译成自然、口语化的中文") && zh.contains("館長→舰长") && zh.contains("这是日本虚拟主播"))
        val en = byokSystem(Scene.VTUBER, Lang.JA, Target.of(Lang.EN))
        assertTrue(en, !en.contains("舰长") && en.contains("翻译成自然、口语化的英语"))
        // another or an unknown source language: the background does not say the streamer is Japanese
        val auto = byokSystem(Scene.VTUBER, null, Target.ZH)
        assertTrue(auto, auto.contains("外语") && auto.contains("这是虚拟主播（VTuber）") && !auto.contains("日本虚拟主播"))
        assertFalse(byokSystem(Scene.GENERAL, Lang.JA, Target.ZH).contains("背景"))
    }

    @Test
    fun aTypedTargetGoesIntoThePromptAsItIsWithoutTheChineseOnlyParts() {
        val t = Target.custom("  繁体中文（台湾用语）\n")!!
        assertEquals("繁体中文（台湾用语）", t.name)
        val s = byokSystem(Scene.VTUBER, Lang.JA, t)
        assertTrue(s, s.contains("翻译成自然、口语化的繁体中文（台湾用语）") && !s.contains("館長→舰长"))
        // a typed "中文" is still a typed language: no term list, the general check notes, the longer cap
        val typed = Target.custom("中文")!!
        assertFalse(byokSystem(Scene.VTUBER, Lang.JA, typed).contains("館長→舰长"))
        assertEquals(FIX_NOTES_GENERAL["negation"], fixNote("negation", Lang.JA, typed))
        assertEquals(256, mtMaxTokens(typed)); assertEquals(160, mtMaxTokens(Target.ZH))
        // one target, one system message for every sentence (prefix caching); another target, another message
        assertEquals(s, byokSystem(Scene.VTUBER, Lang.JA, Target.custom("繁体中文（台湾用语）")!!))
        assertNotEquals(s, byokSystem(Scene.VTUBER, Lang.JA, Target.custom("粤语")!!))
    }

    @Test
    fun targetsAreStoredAsAPresetCodeOrATypedName() {
        assertEquals(Target.of(Lang.KO), Target.parse("ko"))
        assertEquals("ko", Target.of(Lang.KO).stored)
        // the list offers only a few; a language that is not one of them (an old "fr" too) reads as nothing
        assertEquals(listOf(Lang.ZH, Lang.EN, Lang.JA, Lang.KO), Target.PRESETS.map { it.lang })
        assertNull(Target.parse("fr"))
        val t = Target.custom("粤语")!!
        assertEquals(t, Target.parse(t.stored))
        assertNotEquals(Target.ZH, Target.parse(Target.custom("中文")!!.stored))
        assertNull(Target.custom("   "))
        assertNull(Target.custom("x".repeat(Target.MAX_NAME + 1)))
        assertNull(Target.parse("custom:  "))
        assertNull(Target.parse("xx")); assertNull(Target.parse(null))
    }

    @Test
    fun fixNotesWithJapaneseExamplesOnlyForJapaneseToChinese() {
        assertEquals(FIX_NOTES["negation"], fixNote("negation", Lang.JA, Target.ZH))
        assertEquals(FIX_NOTES_GENERAL["negation"], fixNote("negation", Lang.EN, Target.ZH))
        assertEquals(FIX_NOTES_GENERAL["reversal"], fixNote("reversal", Lang.JA, Target.of(Lang.EN)))
        assertEquals(null, fixNote("ok", Lang.JA, Target.ZH))
    }
}
