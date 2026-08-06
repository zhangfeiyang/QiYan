package com.zhongguoxiangqi.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 战术杀法检测测试(马后炮 / 铁门栓).
 */
class TacticsTest {

    @Test
    fun `horse behind cannon is detected`() {
        // 红炮(4,4)隔着红马(3,4)照黑将(0,4), 三点一线
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 4)] = Piece.R_KING.code
        pos.squares[Square.make(3, 4)] = Piece.R_HORSE.code
        pos.squares[Square.make(4, 4)] = Piece.R_CANNON.code
        // 走完一手后轮到黑方
        pos.sideToMove = Color.BLACK

        assertEquals("马后炮", Tactics.detect(pos, Color.RED))
    }

    @Test
    fun `iron door bolt is detected`() {
        // 红车(2,4)照黑将(0,4), 红兵(0,3)卡在将的侧翼出口
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
    fun `plain check without tactic returns null`() {
        // 只有车照将, 没有兵/帅卡位
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
