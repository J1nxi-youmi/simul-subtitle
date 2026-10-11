package dev.krita.jpsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTest {
    private fun native(stop: Char, text: String, p: Int, o: Int) =
        (stop.toString() + text).toByteArray(Charsets.UTF_8) + byteArrayOf(0) + "$p,$o".toByteArray(Charsets.US_ASCII)

    @Test
    fun parsesNativeResultAndStripsThink() {
        val r = MtResult.parse(native('e', "<think>\n\n</think>\n\n各位，请祈祷B站不要倒闭吧</think>", 87, 12))
        assertEquals("各位，请祈祷B站不要倒闭吧", r.text)
        assertEquals(87, r.promptTokens); assertEquals(12, r.outputTokens); assertTrue(r.usable)
        assertFalse(MtResult.parse(native('p', "", 2100, 0)).usable)
        assertFalse(MtResult.parse(native('f', "", 0, 0)).usable)
        assertTrue(MtResult.parse(native('l', "很长", 50, 160)).usable)
        val timed = MtResult.parse((('e'.toString() + "好").toByteArray() + byteArrayOf(0) + "110,9,812,640".toByteArray()))
        assertEquals(812L, timed.prefillMs); assertEquals(640L, timed.decodeMs); assertEquals(9, timed.outputTokens)
    }

    @Test
    fun aNoteInTheTranslationIsNeverDropped() {
        // the source text itself carries a note; its characters also appear in the scene background
        val text = "下次直播见\n（注：这是B站直播）"
        assertEquals(text, MtResult.parse(native('e', text, 1, 1)).text)
    }

    @Test
    fun generalSceneUsesPlainInstruction() {
        val p = indexPrompt("こんにちは", Scene.GENERAL)
        assertEquals("请将以下日语文本翻译为中文，直接输出翻译结果，不要进行任何解释。\n\nこんにちは", p)
        assertEquals("<|im_start|>user\n$p<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", qwenChat(p))
    }

    @Test
    fun vtuberSceneAddsOnlyTermsPresentInTheSentence() {
        val p = indexPrompt("ビリビリが潰れないように祈っててください", Scene.VTUBER)
        assertTrue(p.contains("【源文】\nビリビリが潰れないように祈っててください\n\n【约束要求】\n1. 【硬性要求】专名/术语对照: ビリビリ→B站\n2. 【注意】这是日本虚拟主播"))
        assertFalse(p.contains("舰长"))
        val q = indexPrompt("館長も艦長もありがとう", Scene.VTUBER)
        assertTrue(q.contains("館長→舰长") && !q.contains("艦長→舰长")) // one rendering per Chinese term
    }

    @Test
    fun previousSentencesAreASoftConstraintAfterTheScene() {
        val p = indexPrompt("それな", Scene.GENERAL, previous = listOf("昨日ね", "ゲームした"))
        assertTrue(p, p.contains("【源文】\nそれな\n\n【约束要求】\n1. 【注意】前文（只用于理解当前句的指代和省略，不要翻译、不要输出）：昨日ね / ゲームした\n"))
        assertEquals(indexPrompt("それな", Scene.GENERAL), indexPrompt("それな", Scene.GENERAL, previous = emptyList()))
    }

    @Test
    fun otherLanguagePairsNameTheirLanguagesAndJapaneseToChineseIsUnchanged() {
        assertEquals("请将以下日语文本翻译为中文，直接输出翻译结果，不要进行任何解释。\n\nこんにちは", indexPrompt("こんにちは", Scene.GENERAL))
        assertEquals("请将以下英语文本翻译为日语，直接输出翻译结果，不要进行任何解释。\n\nhello",
            indexPrompt("hello", Scene.GENERAL, source = Lang.EN, target = Lang.JA))
        // auto: as the official client, no source language is named
        assertEquals("请将以下文本翻译为英语，直接输出翻译结果，不要进行任何解释。\n\nhello",
            indexPrompt("hello", Scene.GENERAL, source = null, target = Lang.EN))
    }

    @Test
    fun theJapaneseToChineseTermListIsOnlyUsedForAChineseTarget() {
        assertTrue(indexPrompt("館長ありがとう", Scene.VTUBER).contains("館長→舰长"))
        val en = indexPrompt("館長ありがとう", Scene.VTUBER, target = Lang.EN)
        assertTrue(en, !en.contains("舰长") && en.startsWith("请将以下日语直播字幕翻译成英语"))
        val fromEn = indexPrompt("thanks", Scene.VTUBER, source = Lang.EN)
        assertTrue(fromEn, fromEn.contains("这是虚拟主播（VTuber）") && !fromEn.contains("日本虚拟主播"))
    }

    @Test
    fun fixNotesWithJapaneseExamplesOnlyForJapaneseToChinese() {
        assertEquals(FIX_NOTES["negation"], fixNote("negation", Lang.JA, Lang.ZH))
        assertEquals(FIX_NOTES_GENERAL["negation"], fixNote("negation", Lang.EN, Lang.ZH))
        assertEquals(FIX_NOTES_GENERAL["reversal"], fixNote("reversal", Lang.JA, Lang.EN))
        assertEquals(null, fixNote("ok", Lang.JA, Lang.ZH))
    }
}
