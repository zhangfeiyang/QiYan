package com.zhongguoxiangqi.game

import org.junit.Assert.*
import org.junit.Test

/**
 * 锁死 UCI ↔ 内部坐标的方向.
 *
 * 曾经因为这里方向搞反, 引擎返回的合法着法在本地被判成非法
 * ("引擎给出非法着法: b0b9"), 所以用棋子实际位置做断言, 而不是
 * 只做自洽的往返测试 —— 方向整体反了往返照样能过.
 */
class UciCoordsTest {

    @Test
    fun `uci rank 0 is red's back rank`() {
        val pos = Position()
        // Pikafish: rank 0 = 红方底线, 所以 "a0" 必须落在红车上
        val a0 = UciCoords.parse("a0a1")!!.from
        assertEquals(Piece.R_ROOK.code, pos.pieceAt(a0))
        // "b0" 是红马 —— 就是当年报错的那个格子
        val b0 = UciCoords.parse("b0b1")!!.from
        assertEquals(Piece.R_HORSE.code, pos.pieceAt(b0))
    }

    @Test
    fun `uci rank 9 is black's back rank`() {
        val pos = Position()
        val b9 = UciCoords.parse("b9b8")!!.from
        assertEquals(Piece.B_HORSE.code, pos.pieceAt(b9))
        val e9 = UciCoords.parse("e9e8")!!.from
        assertEquals(Piece.B_KING.code, pos.pieceAt(e9))
    }

    @Test
    fun `internal row 9 is red so it maps to uci rank 0`() {
        // 内部 row 9 = 红方底线 → UCI rank 0
        assertEquals("a0", UciCoords.squareToUci(Square.make(9, 0)))
        // 内部 row 0 = 黑方底线 → UCI rank 9
        assertEquals("a9", UciCoords.squareToUci(Square.make(0, 0)))
        // 列不翻转
        assertEquals("i0", UciCoords.squareToUci(Square.make(9, 8)))
    }

    @Test
    fun `round trip preserves the move`() {
        for (from in 0 until Square.COUNT) {
            for (to in 0 until Square.COUNT) {
                if (from == to) continue
                val m = Move.make(from, to)
                val back = UciCoords.parse(UciCoords.toUci(m))
                assertNotNull("往返丢失: ${UciCoords.toUci(m)}", back)
                assertEquals(from, back!!.from)
                assertEquals(to, back.to)
            }
        }
    }

    @Test
    fun `engine's b0b9 is red's rook sweeping the b file`() {
        // 报错现场的那步棋: 只有 b 路清空后才合法, 但方向必须是
        // 红方底线 → 黑方底线
        val mv = UciCoords.parse("b0b9")!!
        assertEquals(9, Square.row(mv.from))  // 红方底线
        assertEquals(0, Square.row(mv.to))    // 黑方底线
        assertEquals(1, Square.col(mv.from))
        assertEquals(1, Square.col(mv.to))
    }

    @Test
    fun `malformed input is rejected instead of throwing`() {
        assertNull(UciCoords.parse(""))
        assertNull(UciCoords.parse("b0"))
        assertNull(UciCoords.parse("0000"))
        assertNull(UciCoords.parse("z0b9"))   // 列越界
        assertNull(UciCoords.parse("bxb9"))   // 行不是数字
    }
}
