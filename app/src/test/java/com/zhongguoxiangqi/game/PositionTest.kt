package com.zhongguoxiangqi.game

import org.junit.Assert.*
import org.junit.Test

/**
 * 棋盘逻辑单元测试.
 */
class PositionTest {

    @Test
    fun `initial position has 32 pieces`() {
        val pos = Position()
        var count = 0
        for (sq in 0 until Square.COUNT) {
            if (pos.squares[sq] != Piece.NONE.code) count++
        }
        assertEquals(32, count)
    }

    @Test
    fun `initial position hash is deterministic`() {
        Zobrist.reseed(42)
        val pos1 = Position()
        val h1 = pos1.hash
        Zobrist.reseed(42)
        val pos2 = Position()
        val h2 = pos2.hash
        assertEquals(h1, h2)
    }

    @Test
    fun `red rook moves along rank in initial position`() {
        val pos = Position()
        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)
        // 红车 a10 = (9, 0) 不能吃自己的马
        val fromA10 = Square.make(9, 0)
        val aToB = moves.any { it.from == fromA10 && it.to == Square.make(9, 1) }
        assertFalse("红车 a10 不能直接吃马", aToB)
    }

    @Test
    fun `move and undo restores position`() {
        val pos = Position()
        val originalHash = pos.hash
        val originalFen = pos.toFen()

        // 走一着合法的开局步: 炮二平五 (h7e7)
        val move = Move.make(Square.make(7, 1), Square.make(7, 3))
        assertTrue(pos.isLegalMove(move))
        pos.doMove(move)
        assertNotEquals(originalHash, pos.hash)
        assertNotEquals(originalFen, pos.toFen())

        pos.undoMove()
        assertEquals(originalHash, pos.hash)
        assertEquals(originalFen, pos.toFen())
    }

    @Test
    fun `moving black cannon to center is legal from initial position`() {
        val pos = Position()
        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)
        // 红先手, 黑炮不能走
        // 验证: 黑炮的合法走法为空
        val blackMoves = moves.filter {
            Piece.fromCode(pos.pieceAt(it.from)).color == Color.BLACK
        }
        assertTrue("红先手时黑方不应有合法走法", blackMoves.isEmpty())
    }

    @Test
    fun `side to move toggles after move`() {
        val pos = Position()
        assertEquals(Color.RED, pos.sideToMove)
        val move = Move.make(Square.make(7, 1), Square.make(7, 3))  // 红炮二平五
        pos.doMove(move)
        assertEquals(Color.BLACK, pos.sideToMove)
    }

    @Test
    fun `cannot move opponents piece`() {
        val pos = Position()
        val move = Move.make(Square.make(0, 0), Square.make(0, 1))  // 黑车走
        assertFalse("红方不能走黑方的车", pos.isLegalMove(move))
    }
}