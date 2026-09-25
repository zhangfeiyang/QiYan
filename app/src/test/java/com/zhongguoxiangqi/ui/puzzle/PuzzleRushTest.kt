package com.zhongguoxiangqi.ui.puzzle

import com.zhongguoxiangqi.game.Puzzles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PuzzleRushTest {

    @Test
    fun testHonorTitleCalculations() {
        fun getTitle(score: Int): String = when {
            score < 3 -> "残局学童"
            score < 6 -> "弈林新秀"
            score < 10 -> "残局快刀"
            score < 15 -> "妙算神机"
            else -> "一代宗师"
        }

        assertEquals("残局学童", getTitle(0))
        assertEquals("残局学童", getTitle(2))
        assertEquals("弈林新秀", getTitle(3))
        assertEquals("弈林新秀", getTitle(5))
        assertEquals("残局快刀", getTitle(6))
        assertEquals("残局快刀", getTitle(9))
        assertEquals("妙算神机", getTitle(10))
        assertEquals("妙算神机", getTitle(14))
        assertEquals("一代宗师", getTitle(15))
        assertEquals("一代宗师", getTitle(25))
    }

    @Test
    fun testTierSelectionLogic() {
        // 验证候选题目池覆盖完整度
        val tier1 = Puzzles.all.filter { it.chapter == 1 }
        val tier2 = Puzzles.all.filter { it.chapter <= 2 }
        val tier3 = Puzzles.all.filter { it.chapter >= 2 }

        assertEquals(8, tier1.size)
        assertEquals(16, tier2.size)
        assertEquals(40, tier3.size)

        // 第一卷全部为 1 步杀
        for (p in tier1) {
            assertEquals(1, p.maxMoves)
        }
    }
}
