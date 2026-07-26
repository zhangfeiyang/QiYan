package com.zhongguoxiangqi.game

import org.junit.Assert.*
import org.junit.Test

/**
 * 针对已修复缺陷的回归测试.
 *
 * 每个用例对应一个真实存在过的 bug, 防止再次退化.
 */
class RulesRegressionTest {

    // ===== 行棋规则校验 =====

    @Test
    fun `rook cannot move like a horse`() {
        val pos = Position()
        // 红车 (9,0) 走"日"字到 (7,1) —— 不符合车的走法
        val bogus = Move.make(Square.make(9, 0), Square.make(7, 1))
        assertFalse("车不能走马步", pos.isLegalMove(bogus))
    }

    @Test
    fun `king cannot leave the palace`() {
        val pos = Position()
        // 先把帅前面的兵路让开: 帅 (9,4) -> (8,4) 合法
        assertTrue(pos.isLegalMove(Move.make(Square.make(9, 4), Square.make(8, 4))))
        // 帅横向走出九宫 (9,4) -> (9,2) 既不是一步也出宫
        assertFalse("帅不能一次走两格", pos.isLegalMove(Move.make(Square.make(9, 4), Square.make(9, 2))))
    }

    @Test
    fun `elephant cannot cross the river`() {
        val pos = Position()
        // 红相 (9,2) -> (7,4) 合法(未过河)
        assertTrue(pos.isLegalMove(Move.make(Square.make(9, 2), Square.make(7, 4))))
        // 相不能跨到黑方半场
        assertFalse(pos.isLegalMove(Move.make(Square.make(9, 2), Square.make(3, 4))))
    }

    @Test
    fun `cannot capture own piece`() {
        val pos = Position()
        // 红车 (9,0) 吃自己的马 (9,1)
        assertFalse(pos.isLegalMove(Move.make(Square.make(9, 0), Square.make(9, 1))))
    }

    @Test
    fun `arbitrary teleport is rejected`() {
        val pos = Position()
        // 红车 (9,0) 瞬移到棋盘另一头的空格
        assertFalse(pos.isLegalMove(Move.make(Square.make(9, 0), Square.make(4, 4))))
    }

    // ===== Zobrist 哈希 =====

    @Test
    fun `hash distinguishes different destinations`() {
        // 同一个子走到不同格子必须得到不同哈希 ——
        // 缺少"落点"异或项时这两个局面哈希会相同
        val a = Position()
        a.doMove(Move.make(Square.make(7, 1), Square.make(7, 3)))  // 炮二平五
        val b = Position()
        b.doMove(Move.make(Square.make(7, 1), Square.make(7, 4)))
        assertNotEquals("走到不同落点的局面哈希不能相同", a.hash, b.hash)
    }

    @Test
    fun `incremental hash matches full recompute`() {
        val pos = Position()
        val moves = listOf(
            Move.make(Square.make(7, 1), Square.make(7, 4)),
            Move.make(Square.make(2, 7), Square.make(2, 4)),
            Move.make(Square.make(9, 1), Square.make(7, 2)),
            Move.make(Square.make(0, 7), Square.make(2, 6))
        )
        for (m in moves) {
            assertTrue("着法应合法: $m", pos.isLegalMove(m))
            pos.doMove(m)
            assertEquals("增量哈希应与全量重算一致", pos.computeHash(), pos.hash)
        }
        repeat(moves.size) {
            pos.undoMove()
            assertEquals("撤销后哈希应与全量重算一致", pos.computeHash(), pos.hash)
        }
    }

    // ===== FEN =====

    @Test
    fun `initial fen is standard notation`() {
        val pos = Position()
        assertEquals(
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1",
            pos.toFen()
        )
    }

    @Test
    fun `fen round trips`() {
        val pos = Position()
        pos.doMove(Move.make(Square.make(7, 1), Square.make(7, 4)))
        val fen = pos.toFen()

        val restored = Position()
        assertTrue(restored.setFromFen(fen))
        assertEquals(fen, restored.toFen())
        assertEquals(pos.sideToMove, restored.sideToMove)
        assertEquals(pos.computeHash(), restored.hash)
    }

    @Test
    fun `malformed fen is rejected and leaves position untouched`() {
        val pos = Position()
        val before = pos.toFen()
        assertFalse(pos.setFromFen("这不是FEN"))
        assertEquals(before, pos.toFen())
    }

    // ===== 终局与限着 =====

    @Test
    fun `halfmove clock resets on capture and rewinds on undo`() {
        val pos = Position()
        pos.doMove(Move.make(Square.make(7, 1), Square.make(7, 4)))   // 红炮平中, 非吃子
        assertEquals(1, pos.halfmoveClock)
        pos.doMove(Move.make(Square.make(2, 7), Square.make(2, 4)))   // 黑炮平中
        assertEquals(2, pos.halfmoveClock)
        pos.doMove(Move.make(Square.make(7, 4), Square.make(2, 4)))   // 红炮吃黑炮
        assertEquals(0, pos.halfmoveClock)
        pos.undoMove()
        assertEquals("撤销吃子后应恢复原来的限着计数", 2, pos.halfmoveClock)
    }

    @Test
    fun `flying general is detected as check`() {
        val pos = Position()
        // 清空棋盘, 只留两个将同列且中间无子
        pos.squares.fill(Piece.NONE.code)
        pos.squares[Square.make(9, 4)] = Piece.R_KING.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        assertTrue("白脸将应判为将军", Rules.isInCheck(pos, Color.RED))
        // 中间塞一个子就不再对脸
        pos.squares[Square.make(5, 4)] = Piece.R_PAWN.code
        assertFalse(Rules.isInCheck(pos, Color.RED))
    }

    @Test
    fun `cannon checks over a friendly screen`() {
        val pos = Position()
        pos.squares.fill(Piece.NONE.code)
        pos.squares[Square.make(9, 4)] = Piece.R_KING.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(7, 4)] = Piece.R_PAWN.code   // 红方自己的兵当炮架
        pos.squares[Square.make(5, 4)] = Piece.B_CANNON.code
        assertTrue("炮可以借对方的子作炮架将军", Rules.isInCheck(pos, Color.RED))
    }

    @Test
    fun `cannon checks over an enemy screen`() {
        val pos = Position()
        pos.squares.fill(Piece.NONE.code)
        pos.squares[Square.make(9, 4)] = Piece.R_KING.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(7, 4)] = Piece.B_HORSE.code  // 黑方自己的马当炮架
        pos.squares[Square.make(5, 4)] = Piece.B_CANNON.code
        assertTrue("非车非炮的棋子同样是有效炮架", Rules.isInCheck(pos, Color.RED))
    }

    @Test
    fun `horse leg is the square next to the horse`() {
        val pos = Position()
        pos.squares.fill(Piece.NONE.code)
        pos.squares[Square.make(9, 4)] = Piece.R_KING.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(7, 3)] = Piece.B_HORSE.code  // 马 (7,3) 可吃帅 (9,4), 马腿在 (8,3)
        pos.squares[Square.make(4, 4)] = Piece.B_PAWN.code   // 堵住四路, 排除白脸将的干扰

        // 帅正前方 (8,4) 有子并不能解将 —— 它不是这只马的马腿
        pos.squares[Square.make(8, 4)] = Piece.R_ADVISOR.code
        assertTrue("帅前方的子不是马腿, 仍应判将军", Rules.isInCheck(pos, Color.RED))

        // 真正的马腿 (8,3) 被塞住才解将
        pos.squares[Square.make(8, 4)] = Piece.NONE.code
        pos.squares[Square.make(8, 3)] = Piece.R_ADVISOR.code
        assertFalse("蹩住马腿 (8,3) 后不应判将军", Rules.isInCheck(pos, Color.RED))
    }

    // ===== 中文记谱 =====

    @Test
    fun `red cannon to center is recorded as pao er ping wu`() {
        val pos = Position()
        // 红炮 (7,1): 红方从右数是第八路 -> "炮八平五"
        val move = Move.make(Square.make(7, 1), Square.make(7, 4))
        assertEquals("炮八平五", Move.toChineseString(move, pos))
    }

    @Test
    fun `red forward move is jin not tui`() {
        val pos = Position()
        // 红兵 (6,4) 前进一步: row 变小才是"进"
        val move = Move.make(Square.make(6, 4), Square.make(5, 4))
        val text = Move.toChineseString(move, pos)
        assertTrue("红方向前应记作'进', 实际: $text", text.contains("进"))
    }

    @Test
    fun `black uses arabic numerals and opposite direction`() {
        val pos = Position()
        pos.doMove(Move.make(Square.make(7, 1), Square.make(7, 4)))  // 让黑方走
        // 黑卒 (3,4) 前进: 黑方 row 变大才是"进"; 黑方列号自左数为 5
        val move = Move.make(Square.make(3, 4), Square.make(4, 4))
        val text = Move.toChineseString(move, pos)
        assertEquals("卒5进1", text)
    }
}
