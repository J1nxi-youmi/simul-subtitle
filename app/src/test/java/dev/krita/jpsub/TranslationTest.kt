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
    }

    @Test
    fun onlyANoteThatEchoesThePromptIsDropped() {
        // real output for "あ" in the VTuber scene (Mac e2e, 2026-10-10)
        val prompt = indexPrompt("あ", Scene.VTUBER)
        val raw = "啊\n\n（注：这是日本虚拟主播在B站的直播，主播在和观众闲聊、念弹幕和SC（醒目留言）。原文是语音识别结果，可能有听错的词；口语里常有语气词和重复。）"
        val r = MtResult.parse(native('e', raw, 120, 46))
        assertEquals(raw, r.raw)
        assertEquals("啊", dropEchoedNote(r.text, prompt))
        // a note that is not the prompt's own text is part of the translation
        val real = "下周休播\n（注：周三的回放照常上传）"
        assertEquals(real, dropEchoedNote(real, indexPrompt("来週はお休み（注：水曜のアーカイブは通常通り）", Scene.VTUBER)))
        assertEquals("注意：这里很滑", dropEchoedNote("注意：这里很滑", prompt))
        assertEquals("（注：只有注释）", dropEchoedNote("（注：只有注释）", prompt))
    }

    @Test
    fun generalSceneUsesPlainInstruction() {
        val p = indexPrompt("こんにちは", Scene.GENERAL)
        assertTrue(p.startsWith("<|im_start|>user\n请将以下日语文本翻译为中文，直接输出翻译结果，不要进行任何解释。\n\nこんにちは<|im_end|>"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun vtuberSceneAddsOnlyTermsPresentInTheSentence() {
        val p = indexPrompt("ビリビリが潰れないように祈っててください", Scene.VTUBER)
        assertTrue(p.contains("【源文】\nビリビリが潰れないように祈っててください\n\n【约束要求】\n1. 【硬性要求】专名/术语对照: ビリビリ→B站\n2. 【注意】这是日本虚拟主播"))
        assertFalse(p.contains("舰长"))
        val q = indexPrompt("館長も艦長もありがとう", Scene.VTUBER)
        assertTrue(q.contains("館長→舰长") && !q.contains("艦長→舰长")) // one rendering per Chinese term
    }
}
