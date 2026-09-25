package com.zhongguoxiangqi.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 战术杀法检测测试(包含10大经典象棋绝杀杀法).
 */
class TacticsTest {

    @Test
    fun `horse behind cannon is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(3, 4)] = Piece.R_HORSE.code
        pos.squares[Square.make(4, 4)] = Piece.R_CANNON.code
        pos.sideToMove = Color.BLACK

        assertEquals("马后炮", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `iron door bolt is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(2, 4)] = Piece.R_ROOK.code
        pos.squares[Square.make(0, 3)] = Piece.R_PAWN.code
        pos.sideToMove = Color.BLACK

        assertEquals("铁门栓", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `double cannon is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(2, 4)] = Piece.R_CANNON.code
        pos.squares[Square.make(4, 4)] = Piece.R_CANNON.code
        pos.sideToMove = Color.BLACK

        assertEquals("重炮", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `double rooks is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(0, 0)] = Piece.R_ROOK.code
        pos.squares[Square.make(1, 8)] = Piece.R_ROOK.code
        pos.sideToMove = Color.BLACK

        assertEquals("双车错", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `wocao horse is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(1, 2)] = Piece.R_HORSE.code
        pos.sideToMove = Color.BLACK

        assertEquals("卧槽马", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `corner horse is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(2, 3)] = Piece.R_HORSE.code
        pos.sideToMove = Color.BLACK

        assertEquals("挂角马", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `heart carving rook is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(1, 4)] = Piece.R_ROOK.code
        pos.sideToMove = Color.BLACK

        assertEquals("大刀剜心", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `face to face kings is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 4)] = Piece.R_KING.code
        pos.sideToMove = Color.BLACK

        assertEquals("白脸将", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `heaven earth cannons is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(0, 0)] = Piece.R_CANNON.code
        pos.squares[Square.make(0, 2)] = Piece.B_PAWN.code // 炮架
        pos.squares[Square.make(5, 4)] = Piece.R_CANNON.code // 中炮
        pos.sideToMove = Color.BLACK

        assertEquals("天地炮", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `smothered palace is detected`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(0, 3)] = Piece.B_ADVISOR.code
        pos.squares[Square.make(0, 5)] = Piece.B_ADVISOR.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(2, 4)] = Piece.B_PAWN.code // 炮架
        pos.squares[Square.make(4, 4)] = Piece.R_CANNON.code
        pos.sideToMove = Color.BLACK

        assertEquals("闷宫", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `plain check without tactic returns null`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(2, 4)] = Piece.R_ROOK.code
        pos.sideToMove = Color.BLACK

        assertNull(Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `no check returns null`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(5, 0)] = Piece.R_ROOK.code
        pos.sideToMove = Color.BLACK

        assertNull(Tactics.detect(pos, Color.RED))
    }
}
