package com.zhongguoxiangqi.ui.advisor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * parseBold 用 range.first / range.last 做切片, 差一个字符就会吃掉正文
 * 或漏掉星号, 值得钉住.
 *
 * 测的是 parseBold 而不是 markdownBold: 后者要构造 SpannableStringBuilder,
 * 那是 android.jar 的桩类, 普通单元测试里一碰就抛 "not mocked".
 */
class MessageAdapterRenderTest {

    private fun text(raw: String) = MessageAdapter.parseBold(raw).text
    private fun bold(raw: String) = MessageAdapter.parseBold(raw).bold

    @Test
    fun `没有星号时原样返回且无加粗区间`() {
        assertEquals("双方子力完整", text("双方子力完整"))
        assertEquals(0, bold("双方子力完整").size)
    }

    @Test
    fun `去掉成对星号且不吃掉相邻文字`() {
        assertEquals(
            "目前局面为开局初始状态, 完全均势。",
            text("目前局面为**开局初始状态**, 完全均势。")
        )
    }

    @Test
    fun `加粗区间正好圈住原来被星号包住的那段`() {
        val raw = "目前局面为**开局初始状态**, 完全均势。"
        val parsed = MessageAdapter.parseBold(raw)
        val r = parsed.bold.single()
        assertEquals("开局初始状态", parsed.text.substring(r.start, r.end))
    }

    @Test
    fun `一行多处加粗`() {
        val raw = "**出车要快**: 控制**横线**"
        val parsed = MessageAdapter.parseBold(raw)
        assertEquals("出车要快: 控制横线", parsed.text)
        assertEquals(2, parsed.bold.size)
        assertEquals("出车要快", parsed.text.substring(parsed.bold[0].start, parsed.bold[0].end))
        assertEquals("横线", parsed.text.substring(parsed.bold[1].start, parsed.bold[1].end))
    }

    @Test
    fun `跨行加粗也要处理`() {
        assertEquals("第一行\n第二行", text("**第一行\n第二行**"))
    }

    @Test
    fun `落单的星号原样保留, 不能吞掉后面的正文`() {
        assertEquals("这里只有 **一个开头", text("这里只有 **一个开头"))
    }

    @Test
    fun `空串不崩`() {
        assertEquals("", text(""))
    }

    private fun bullets(raw: String) = MessageAdapter.bulletize(raw)

    @Test
    fun `行首单星号变成圆点`() {
        assertEquals("• 形势判断: 均势", bullets("* 形势判断: 均势"))
    }

    @Test
    fun `行首减号也变成圆点`() {
        assertEquals("• 出车要快", bullets("- 出车要快"))
    }

    @Test
    fun `加粗标记不能被当成列表符`() {
        assertEquals("**均势**", bullets("**均势**"))
    }

    @Test
    fun `句中的星号不受影响`() {
        assertEquals("红方 * 黑方", bullets("红方 * 黑方"))
    }

    @Test
    fun `保留缩进`() {
        assertEquals("    • 子项", bullets("    * 子项"))
    }

    @Test
    fun `列表符与加粗同时出现`() {
        assertEquals("• 形势判断: 均势", text(bullets("* **形势判断**: 均势")))
    }

    @Test
    fun `流式中且尚无内容时显示等待文案`() {
        val m = MessageAdapter.Message.Assistant("", streaming = true)
        assertEquals("正在分析…", MessageAdapter.renderAssistant(m).toString())
    }

    @Test
    fun `流式中已有内容时显示内容而非等待文案`() {
        val m = MessageAdapter.Message.Assistant("红方", streaming = true)
        assertEquals("红方", MessageAdapter.renderAssistant(m).toString())
    }

    @Test
    fun `已完成的空回复不显示等待文案`() {
        val m = MessageAdapter.Message.Assistant("", streaming = false)
        assertEquals("", MessageAdapter.renderAssistant(m).toString())
    }
}

/**
 * submit 的刷新决策.
 *
 * 判错的两个后果都很严重: 该刷不刷 -> 界面卡住不更新; 该全刷却局部刷 ->
 * 出错时 Assistant 换成 Error, viewType 变了, 新数据会绑到旧类型的
 * ViewHolder 上.
 */
class MessageAdapterDiffTest {

    private fun user(t: String) = MessageAdapter.Message.User(t)
    private fun bot(t: String, streaming: Boolean = true) =
        MessageAdapter.Message.Assistant(t, streaming)
    private fun err(t: String) = MessageAdapter.Message.Error(t)

    @Test
    fun `内容完全一样时不刷新`() {
        val a = listOf(user("你好"), bot("在"))
        assertEquals(MessageAdapter.Update.None, MessageAdapter.diff(a, a.toList()))
    }

    @Test
    fun `流式加字只刷那一条`() {
        val old = listOf(user("问"), bot("红方"))
        val new = listOf(user("问"), bot("红方占优"))
        assertEquals(MessageAdapter.Update.Item(1), MessageAdapter.diff(old, new))
    }

    @Test
    fun `新增消息要全量刷`() {
        val old = listOf(user("问"))
        val new = listOf(user("问"), bot(""))
        assertEquals(MessageAdapter.Update.All, MessageAdapter.diff(old, new))
    }

    @Test
    fun `Assistant 变成 Error 必须全量刷, 不能走 payload`() {
        // viewType 变了, 局部刷会绑错 ViewHolder
        val old = listOf(user("问"), bot("正在分析"))
        val new = listOf(user("问"), err("请求失败"))
        assertEquals(MessageAdapter.Update.All, MessageAdapter.diff(old, new))
    }

    @Test
    fun `streaming 标志翻转也算这一条变了`() {
        val old = listOf(bot("完整回答", streaming = true))
        val new = listOf(bot("完整回答", streaming = false))
        assertEquals(MessageAdapter.Update.Item(0), MessageAdapter.diff(old, new))
    }

    @Test
    fun `多处同时变化退回全量刷`() {
        val old = listOf(user("a"), bot("b"))
        val new = listOf(user("x"), bot("y"))
        assertEquals(MessageAdapter.Update.All, MessageAdapter.diff(old, new))
    }

    @Test
    fun `清空对话要全量刷`() {
        val old = listOf(user("问"), bot("答"))
        assertEquals(MessageAdapter.Update.All, MessageAdapter.diff(old, emptyList()))
    }

    @Test
    fun `两个空列表不刷新`() {
        assertEquals(MessageAdapter.Update.None,
            MessageAdapter.diff(emptyList(), emptyList()))
    }
}
