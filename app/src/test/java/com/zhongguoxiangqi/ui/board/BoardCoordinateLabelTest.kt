package com.zhongguoxiangqi.ui.board

import com.zhongguoxiangqi.game.Square
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 棋盘四边的列号标注.
 *
 * 值得单独钉住是因为翻面这一步很容易反: 号数认的是**棋盘上的列**, 而 setFlipped
 * 把行列都反了一遍. 反错了界面上看不出破绽(还是 1..9 一排数字), 只有在按谱走
 * "炮二平五"时才会发现指的是另一路 —— 这种错最难在体验中发现.
 */
class BoardCoordinateLabelTest {

    private fun row(flipped: Boolean, top: Boolean): String =
        (0 until Square.COLS).joinToString("") { BoardView.fileLabel(it, flipped, top) }

    @Test
    fun `红在下 底排从右往左是一到九`() {
        assertEquals("987654321", row(flipped = false, top = false))
    }

    @Test
    fun `红在下 顶排是黑方视角 从左往右是一到九`() {
        assertEquals("123456789", row(flipped = false, top = true))
    }

    @Test
    fun `翻面后红那一路跟着棋盘走到屏幕左边`() {
        assertEquals("123456789", row(flipped = true, top = true))
    }

    @Test
    fun `翻面后黑的一路跟着棋盘走到屏幕右边`() {
        assertEquals("987654321", row(flipped = true, top = false))
    }

    @Test
    fun `同一列在翻面前后号数不变`() {
        // 屏幕列 c 翻面后跑到 COLS-1-c; 两边标的必须是同一个数字
        for (c in 0 until Square.COLS) {
            val mirrored = Square.COLS - 1 - c
            assertEquals(
                "红方第 $c 列翻面后号数变了",
                BoardView.fileLabel(c, flipped = false, top = false),
                BoardView.fileLabel(mirrored, flipped = true, top = true)
            )
            assertEquals(
                "黑方第 $c 列翻面后号数变了",
                BoardView.fileLabel(c, flipped = false, top = true),
                BoardView.fileLabel(mirrored, flipped = true, top = false)
            )
        }
    }
}
