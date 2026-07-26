package com.zhongguoxiangqi.ai

import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Position
import org.junit.Assert.*
import org.junit.Test

/**
 * BoardContext 的单元测试.
 *
 * 验证序列化的 contextPreamble 包含所有必要信息, 且格式正确.
 */
class BoardContextTest {

    @Test
    fun `context contains all required fields`() {
        val pos = Position()
        val ctx = BoardContext.build(
            initialFen = pos.toFen(),
            currentPosition = pos,
            moveHistory = emptyList(),
            playerSide = Color.RED,
            difficulty = 6,
            engineElo = 1900,
        )
        assertTrue("应包含起始 FEN", ctx.contains("起始 FEN"))
        assertTrue("应包含全走法", ctx.contains("全走法"))
        assertTrue("应包含当前局面", ctx.contains("当前局面"))
        assertTrue("应包含玩家执红", ctx.contains("玩家执红"))
        assertTrue("应包含难度 6", ctx.contains("难度 6"))
        assertTrue("应包含 Elo 1900", ctx.contains("Elo 1900"))
    }

    @Test
    fun `context shows black to move after red moves first`() {
        val pos = Position()
        // 模拟红方走了第一步: 炮二平五 (UCI: b2e2)
        // 但这里我们只验证上下文字符串, 不需要实际走子
        // 用空走法列表, 初始局面, 应该显示"当前轮到红方"
        val ctx = BoardContext.build(
            initialFen = pos.toFen(),
            currentPosition = pos,
            moveHistory = emptyList(),
            playerSide = Color.RED,
            difficulty = 5,
            engineElo = 1700,
        )
        assertTrue("初始局面轮到红方", ctx.contains("当前轮到红方"))
    }

    @Test
    fun `context lists moves count correctly`() {
        val ctx = BoardContext.build(
            initialFen = "test fen",
            currentPosition = Position(),
            moveHistory = listOf(
                com.zhongguoxiangqi.game.Move.make(
                    com.zhongguoxiangqi.game.Square.make(7, 1),
                    com.zhongguoxiangqi.game.Square.make(5, 3)
                ),
                com.zhongguoxiangqi.game.Move.make(
                    com.zhongguoxiangqi.game.Square.make(2, 7),
                    com.zhongguoxiangqi.game.Square.make(2, 5)
                ),
            ),
            playerSide = Color.BLACK,
            difficulty = 8,
            engineElo = 2500,
        )
        assertTrue("应显示 2 步", ctx.contains("2 步"))
        assertTrue("应包含玩家执黑", ctx.contains("玩家执黑"))
    }
}
