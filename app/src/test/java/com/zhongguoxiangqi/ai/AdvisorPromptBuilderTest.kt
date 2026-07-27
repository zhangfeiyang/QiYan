package com.zhongguoxiangqi.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MOVE:xxxx] 标记要从正文里干净剥掉 —— 漏一次用户就会在气泡里看到
 * 一串给机器看的坐标.
 */
class AdvisorPromptBuilderTest {

    @Test
    fun `没有标记时原样返回`() {
        val a = AdvisorPromptBuilder.parseAnswer("局面接近均势。")
        assertEquals("局面接近均势。", a.text)
        assertNull(a.fromUci)
        assertNull(a.toUci)
    }

    @Test
    fun `抠出坐标并从正文里删掉标记`() {
        val a = AdvisorPromptBuilder.parseAnswer("推荐炮二平五。\n\n[MOVE:h2e2]")
        assertEquals("推荐炮二平五。", a.text)
        assertEquals("h2", a.fromUci)
        assertEquals("e2", a.toUci)
    }

    @Test
    fun `标记里有多余空格也认`() {
        val a = AdvisorPromptBuilder.parseAnswer("走这步。\n[MOVE: b7 b0 ]")
        assertEquals("走这步。", a.text)
        assertEquals("b7", a.fromUci)
        assertEquals("b0", a.toUci)
    }

    @Test
    fun `大写坐标归一成小写`() {
        val a = AdvisorPromptBuilder.parseAnswer("这样走。[MOVE:H2E2]")
        assertEquals("h2", a.fromUci)
        assertEquals("e2", a.toUci)
    }

    @Test
    fun `格式不对的标记不当着法, 但也不能吞掉正文`() {
        val raw = "我建议出车。[MOVE:zz99]"
        val a = AdvisorPromptBuilder.parseAnswer(raw)
        assertNull(a.fromUci)
        assertEquals(raw, a.text)
    }

    @Test
    fun `标记出现在中间也要剥掉`() {
        val a = AdvisorPromptBuilder.parseAnswer("先手不错。[MOVE:a0a1] 后面继续观察。")
        assertEquals("a0", a.fromUci)
        assertEquals("a1", a.toUci)
        assertEquals(false, a.text.contains("MOVE"))
    }

    @Test
    fun `空串不崩`() {
        val a = AdvisorPromptBuilder.parseAnswer("")
        assertEquals("", a.text)
        assertNull(a.fromUci)
    }
}
