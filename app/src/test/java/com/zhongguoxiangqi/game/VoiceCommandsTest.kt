package com.zhongguoxiangqi.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音着法解析测试: "车九进四" 这类中文记谱 -> 具体走法.
 */
class VoiceCommandsTest {

    /** 空棋盘 + 双方将 + 红车在九路(屏幕最左列), 红先 */
    private fun redRookPosition(rookRow: Int): Position {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(rookRow, 0)] = Piece.R_ROOK.code
        pos.sideToMove = Color.RED
        return pos
    }

    @Test
    fun `red move with chinese numeral parses`() {
        val pos = redRookPosition(9)
        val moves = VoiceCommands.parse(pos, "车九进四")
        assertEquals(1, moves.size)
        val m = moves[0]
        assertEquals(Square.make(9, 0), m.from)
        assertEquals(Square.make(5, 0), m.to)
    }

    @Test
    fun `black move with arabic numeral parses`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(0, 8)] = Piece.B_ROOK.code
        pos.sideToMove = Color.BLACK

        val moves = VoiceCommands.parse(pos, "车9进4")
        assertEquals(1, moves.size)
        val m = moves[0]
        assertEquals(Square.make(0, 8), m.from)
        assertEquals(Square.make(4, 8), m.to)
    }

    @Test
    fun `front and back prefix disambiguates same file`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) pos.squares[i] = Piece.NONE.code
        pos.squares[Square.make(9, 3)] = Piece.R_KING.code
        pos.squares[Square.make(0, 4)] = Piece.B_KING.code
        // 同一列两个红车: 5 行在前, 9 行在后
        pos.squares[Square.make(5, 0)] = Piece.R_ROOK.code
        pos.squares[Square.make(9, 0)] = Piece.R_ROOK.code
        pos.sideToMove = Color.RED

        val front = VoiceCommands.parse(pos, "前车进一")
        assertEquals(1, front.size)
        assertEquals(Square.make(5, 0), front[0].from)
        assertEquals(Square.make(4, 0), front[0].to)

        val back = VoiceCommands.parse(pos, "后车进一")
        assertEquals(1, back.size)
        assertEquals(Square.make(9, 0), back[0].from)
        assertEquals(Square.make(8, 0), back[0].to)
    }

    @Test
    fun `trailing capture and check suffixes are ignored`() {
        val pos = redRookPosition(9)
        val base = VoiceCommands.parse(pos, "车九进四")
        assertEquals(1, base.size)
        assertTrue(VoiceCommands.parse(pos, "车九进四吃马").isNotEmpty())
        assertTrue(VoiceCommands.parse(pos, "车九进四将军").isNotEmpty())
    }

    @Test
    fun `gibberish yields no move`() {
        val pos = redRookPosition(9)
        assertTrue(VoiceCommands.parse(pos, "随便说点什么").isEmpty())
        assertTrue(VoiceCommands.parse(pos, "将军").isEmpty())
    }

    @Test
    fun `normalize converts full width digits and variants`() {
        assertEquals("车9进4", VoiceCommands.normalize("车９进４"))
        assertEquals("车九进四", VoiceCommands.normalize("俥九進四"))
    }
}
