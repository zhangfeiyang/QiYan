package com.zhongguoxiangqi.vision

import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Position
import org.junit.Assert.*
import org.junit.Test

/**
 * 识别结果 -> 局面 的转换与校验.
 *
 * 校验这一层是拍照识别能不能用的关键: 模型整盘全对只有 ~83%, 错一个子
 * 引擎就会给出完全错误的建议, 所以宁可拦住也不能放过去.
 */
class BoardLayoutTest {

    private fun layoutOf(vararg rows: String): Array<CharArray> {
        require(rows.size == 10) { "需要 10 行" }
        return Array(10) { r ->
            require(rows[r].length == 9) { "第 $r 行必须 9 列" }
            rows[r].toCharArray()
        }
    }

    private val startLayout = layoutOf(
        "rnbakabnr",
        ".........",
        ".c.....c.",
        "p.p.p.p.p",
        ".........",
        ".........",
        "P.P.P.P.P",
        ".C.....C.",
        ".........",
        "RNBAKABNR"
    )

    @Test
    fun `start layout produces the same fen as the engine's start position`() {
        // 行序三方一致(模型/App/FEN 都是 row 0 = 黑方底线), 不需要翻转 ——
        // 这里直接和内置开局局面比对, 方向错了立刻暴露
        assertEquals(Position().toFen(), BoardLayout.toFen(startLayout, Color.RED))
    }

    @Test
    fun `start layout is valid`() {
        assertEquals(emptyList<BoardLayout.Issue>(), BoardLayout.validate(startLayout, Color.RED))
    }

    @Test
    fun `round trip through Position preserves the layout`() {
        val pos = BoardLayout.toPosition(startLayout, Color.RED)
        assertNotNull(pos)
        val back = BoardLayout.fromPosition(pos!!)
        for (r in 0 until 10) {
            assertArrayEquals("第 $r 行", startLayout[r], back[r])
        }
    }

    @Test
    fun `off-board cells are treated as empty in the fen`() {
        // 'x' 是"不在棋盘上", 不是棋子; 留在 FEN 里会让局面无法解析
        val l = layoutOf(
            "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
            ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABxx"
        )
        assertTrue(BoardLayout.toFen(l, Color.RED).startsWith("rnbakabnr/9/1c5c1"))
        assertTrue(BoardLayout.toFen(l, Color.RED).contains("/RNBAKAB2 w"))
    }

    @Test
    fun `off-board cells are reported but do not block`() {
        val l = layoutOf(
            "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
            ".........", "P.P.P.P.P", ".C.....C.", "........x", "RNBAKABNR"
        )
        val issues = BoardLayout.validate(l, Color.RED)
        assertTrue(issues.any { it.message.contains("棋盘外") })
        assertFalse("四角没对准只是提醒, 不该拦死", issues.any { it.blocking })
    }

    @Test
    fun `missing king is blocking`() {
        val l = layoutOf(
            "rnba.abnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
            ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABNR"
        )
        val issues = BoardLayout.validate(l, Color.RED)
        assertTrue(issues.any { it.blocking && it.message.contains("将") })
    }

    @Test
    fun `too many pieces of one kind is blocking`() {
        val l = layoutOf(
            "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
            ".........", "P.P.P.P.P", ".C.....C.", "RRR......", "RNBAKABNR"
        )
        val issues = BoardLayout.validate(l, Color.RED)
        assertTrue(issues.any { it.blocking && it.message.contains("车") })
    }

    @Test
    fun `king outside the palace is blocking`() {
        val l = layoutOf(
            "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
            ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABNK"
        )
        val issues = BoardLayout.validate(l, Color.RED)
        assertTrue(issues.any { it.blocking && it.message.contains("九宫") })
    }

    @Test
    fun `elephant across the river is blocking`() {
        val l = layoutOf(
            "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", "B........",
            ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABNR"
        )
        val issues = BoardLayout.validate(l, Color.RED)
        assertTrue(issues.any { it.blocking && it.message.contains("过河") })
    }

    @Test
    fun `position where the side not to move is already in check is rejected`() {
        // 红车直接照着黑将, 却说轮到红走 —— 那黑方上一步是不合法的
        val l = layoutOf(
            "....k....", ".........", ".........", ".........", ".........",
            ".........", ".........", ".........", ".........", "....K...R"
        )
        // 先把车挪到将的那一路上
        l[9][8] = '.'
        l[5][4] = 'R'
        val issues = BoardLayout.validate(l, Color.RED)
        assertTrue("黑方被将却轮红走, 应当拦下",
            issues.any { it.blocking && it.message.contains("将军") })
    }

    @Test
    fun `low confidence cells are listed`() {
        val conf = Array(10) { FloatArray(9) { 0.99f } }
        conf[3][4] = 0.40f
        conf[7][1] = 0.10f
        val cells = BoardLayout.lowConfidenceCells(conf)
        assertEquals(2, cells.size)
        assertTrue(cells.contains(BoardLayout.Cell(3, 4)))
        assertTrue(cells.contains(BoardLayout.Cell(7, 1)))
    }
}
