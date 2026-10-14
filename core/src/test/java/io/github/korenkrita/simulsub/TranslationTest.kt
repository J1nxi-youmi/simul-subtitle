package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
    fun scenesTheUserEditedOrAddedAreStoredAndBuiltInsReset() {
        // nothing saved: the two built-ins, as shipped
        assertEquals(Scene.DEFAULTS, Scene.all(null))
        // a row with only one box filled, or the arrow typed into a box, is refused with what is wrong
        assertEquals("第 3 条术语没写译文", Scene.edit("u1", "x", "", listOf("a" to "b", "" to "", "c" to " ")).second)
        assertEquals("第 1 条术语没写原文", Scene.edit("u1", "x", "", listOf(" " to "b")).second)
        assertEquals("第 1 条术语里不要写「→」，左右分开填就行", Scene.edit("u1", "x", "", listOf("a→b" to "c")).second)
        val game = Scene.edit("u1", " 游戏  实况 ", "主播在玩 RPG", listOf(" ヘルパー " to "帮手", "" to "", "ボス" to "头目")).first!!
        assertEquals("游戏 实况", game.label); assertEquals(listOf("ヘルパー" to "帮手", "ボス" to "头目"), game.terms)
        val vt = Scene.VTUBER.copy(terms = Scene.VTUBER.terms + ("メン限" to "会限"))
        val saved = Scene.store(listOf(Scene.GENERAL, vt, game))
        // only what differs from the shipped built-ins is saved; read back in place, the user's after the built-ins
        assertFalse(saved.contains("\"GENERAL\""))
        assertEquals(listOf(Scene.GENERAL, vt, game), Scene.all(saved))
        // 恢复默认 = the shipped one again, so it is not saved any more
        assertEquals(listOf(Scene.GENERAL, Scene.VTUBER, game), Scene.all(Scene.store(listOf(Scene.GENERAL, Scene.VTUBER, game))))
        assertEquals("名字不能空着", Scene.edit("u2", "  ", "", emptyList()).second)
        assertEquals(Scene.DEFAULTS, Scene.all("not json"))
        // an entry not as store() writes it is skipped, so the built-in stays as shipped
        assertEquals(Scene.DEFAULTS, Scene.all("""[{"id":"VTUBER","label":"x","background":"","terms":"broken"}]"""))
        assertEquals(Scene.DEFAULTS, Scene.all("""[{"id":"VTUBER","label":"x","background":"","terms":[1]}]"""))
        assertEquals(Scene.DEFAULTS, Scene.all("""[{"id":"VTUBER","label":"x","background":"","terms":["bad line"]}]"""))
        assertEquals(Scene.DEFAULTS, Scene.all("""[{"id":"VTUBER","label":"x","background":"","terms":["a→b→c"]}]"""))
    }

    @Test
    fun termsTheUserWroteGoToEveryCaptionLanguage() {
        val game = Scene.edit("u1", "游戏实况", "主播在玩 RPG", listOf("ボス" to "Boss")).first!!
        assertTrue(byokSystem(game, Lang.JA, Target.of(Lang.EN)).contains("ボス→Boss"))
        assertTrue(byokSystem(game, Lang.JA, Target.custom("粤语")!!).contains("ボス→Boss"))
        // an edited built-in is the user's too; the shipped list stays Chinese-only
        val vt = Scene.VTUBER.copy(terms = listOf("館長" to "captain"))
        assertTrue(byokSystem(vt, Lang.JA, Target.of(Lang.EN)).contains("館長→captain"))
        assertFalse(byokSystem(Scene.VTUBER, Lang.JA, Target.of(Lang.EN)).contains("館長→"))
        // a background the user wrote is given as it is, whatever the spoken language
        val bg = Scene.edit("u2", "x", "日本虚拟主播的直播", emptyList()).first!!
        assertTrue(byokSystem(bg, Lang.EN, Target.ZH).contains("背景：日本虚拟主播的直播"))
        // the shipped background is still the shipped one after only the name or the terms were edited
        val renamed = Scene.VTUBER.copy(label = "我的 VTuber")
        assertEquals(byokSystem(Scene.VTUBER, Lang.EN, Target.ZH).substringAfter("背景：").substringBefore("\n\n术语"),
            byokSystem(renamed, Lang.EN, Target.ZH).substringAfter("背景：").substringBefore("\n\n术语"))
        assertFalse(byokSystem(renamed, null, Target.ZH).contains("日本虚拟主播"))
    }

    @Test
    fun generatingTermsOnlyAddsNewOnes() {
        val answer = "<think>x</think>好的：\n1. ボス→BOSS\n- ヘルパー→帮手\n• ガチャ → 抽卡\n乱写一行\nガチャ→扭蛋\n`レア→稀有`\nあ→" +
            "\nイベ→活动"
        // the user's ボス stays as it is (not replaced by BOSS), a repeat counts once, junk lines are dropped
        assertEquals(listOf("ヘルパー" to "帮手", "ガチャ" to "抽卡", "レア" to "稀有"), newTerms(answer, setOf("ボス"), 3))
        assertEquals(4, newTerms(answer, setOf("ボス"), 10).size)
        val sys = termsSystem("游戏实况", "主播在玩 RPG", setOf("ボス"), 20, Lang.JA, Target.ZH)
        assertTrue(sys.contains("最多 20 条")); assertTrue(sys.contains("不要重复：ボス")); assertTrue(sys.contains("主播在玩 RPG"))
        // a row whose 译文 is still empty and one starting with "-" keep their exact 原文
        val rows = listOf("ボス" to "", "-foo" to "旧", "" to "")
        assertEquals(setOf("ボス", "-foo"), Scene.sources(rows))
        // added after the rows as they are now: nothing there changes, their 原文 are skipped
        val (next, added) = appendTerms(rows, listOf("ボス" to "BOSS", "foo" to "新", "ガチャ" to "抽卡"))
        assertEquals(rows + listOf("foo" to "新", "ガチャ" to "抽卡"), next); assertEquals(2, added)
        // never past the save limit
        val full = (1 until Scene.MAX_TERMS).map { "w$it" to "v" }
        val (t2, n2) = appendTerms(full, listOf("x" to "1", "y" to "2"))
        assertEquals(1, n2); assertNotNull(Scene.edit("u", "a", "", t2).first)
        assertEquals(0, appendTerms(t2, listOf("z" to "3")).second)
        // an answer line with two arrows is not one term
        assertEquals(emptyList<Pair<String, String>>(), newTerms("a→b→c", emptySet(), 5))
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
