package com.zhongguoxiangqi.game

import org.junit.Assert.*
import org.junit.Test

/**
 * 攻防威胁识别的单元测试.
 *
 * 这是用户对局时**视觉反馈**的来源, 错一格都可能让玩家看不见/多看见警告.
 * 这里用尽可能小的局面验证:
 * - 开局走子方(红)一步能吃的对方子 —— 炮打隔山就是这样的合法着法, 必须算
 * - 自己的子无保护送对方嘴边的情形
 */
class ThreatsTest {

    @Test
    fun `start position already has cannon shots`() {
        // 双方炮隔着自己一子都可以打对方. 红方走子方, 至少 1 个威胁.
        val pos = Position()
        val threats = Threats.findThreats(pos)
        assertTrue("开局红方至少有 1 个威胁 (炮打隔山), 实际: $threats",
            threats.isNotEmpty())
    }

    @Test
    fun `red cannon can capture black horse on the second file`() {
        // 开局面里, 红炮在 (7,1), 隔红兵 (6,1) 可以打黑马 (0,1)
        val pos = Position()
        val threats = Threats.findThreats(pos)
        assertTrue("红炮应该瞄黑马 (0,1), 实际: $threats",
            threats.any { it.target == Square.make(0, 1) && it.attacker == Square.make(7, 1) })
    }

    @Test
    fun `hanging piece is detected`() {
        // 干净局面: 红车在 (5, 4), 黑将 (0, 4) + 黑士 (0, 3) (0, 5), 黑车 (5, 0)
        // 黑方走子, 黑车可吃红车
        val pos = Position().apply {
            for (i in 0 until Square.COUNT) squares[i] = 0
            squares[Square.make(5, 4)] = Piece.R_ROOK.code
            squares[Square.make(0, 4)] = Piece.B_KING.code
            squares[Square.make(0, 3)] = Piece.B_ADVISOR.code
            squares[Square.make(0, 5)] = Piece.B_ADVISOR.code
            squares[Square.make(5, 0)] = Piece.B_ROOK.code
            sideToMove = Color.BLACK
        }
        val threats = Threats.findThreats(pos)
        assertTrue("黑车应该能吃红车, 实际: $threats",
            threats.any {
                it.target == Square.make(5, 4) && it.attacker == Square.make(5, 0)
            })
    }

    @Test
    fun `threat color is the attacker color`() {
        val pos = Position().apply {
            for (i in 0 until Square.COUNT) squares[i] = 0
            squares[Square.make(5, 4)] = Piece.R_ROOK.code
            squares[Square.make(0, 4)] = Piece.B_KING.code
            sideToMove = Color.BLACK
        }
        pos.squares[Square.make(5, 0)] = Piece.B_ROOK.code
        val threats = Threats.findThreats(pos)
        assertTrue(threats.isNotEmpty())
        assertEquals(Color.BLACK, threats.first().attackerColor)
    }

    @Test
    fun `no pieces means no threats`() {
        val pos = Position().apply {
            for (i in 0 until Square.COUNT) squares[i] = 0
            sideToMove = Color.RED
        }
        assertTrue(Threats.findThreats(pos).isEmpty())
    }
}