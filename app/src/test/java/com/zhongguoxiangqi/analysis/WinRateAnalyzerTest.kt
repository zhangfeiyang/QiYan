package com.zhongguoxiangqi.analysis

import org.junit.Assert.*
import org.junit.Test

/**
 * 胜率分析测试.
 */
class WinRateAnalyzerTest {

    @Test
    fun `equal position is 50 percent`() {
        val rwr = WinRateAnalyzer.redWinRate(0)
        assertEquals(50.0, rwr, 0.01)
    }

    @Test
    fun `positive cp favors red`() {
        val rwr = WinRateAnalyzer.redWinRate(300)  // +3 兵优势
        assertTrue("红方应有较大优势: $rwr", rwr > 70.0)
    }

    @Test
    fun `negative cp favors black`() {
        val rwr = WinRateAnalyzer.redWinRate(-300)
        assertTrue("黑方应有较大优势: $rwr", rwr < 30.0)
    }

    @Test
    fun `mate score is near 100 percent`() {
        val rwr = WinRateAnalyzer.redWinRate(0, scoreMate = 5)
        assertEquals(99.9, rwr, 0.01)
    }

    @Test
    fun `negative mate is near 0 percent`() {
        val rwr = WinRateAnalyzer.redWinRate(0, scoreMate = -5)
        assertEquals(0.1, rwr, 0.01)
    }

    @Test
    fun `side to move win rate is mirrored for black`() {
        val rwr = WinRateAnalyzer.redWinRate(300)
        val stmR = WinRateAnalyzer.sideToMoveWinRate(300, Color.RED)
        val stmB = WinRateAnalyzer.sideToMoveWinRate(300, Color.BLACK)
        assertEquals(rwr, stmR, 0.01)
        assertEquals(100.0 - rwr, stmB, 0.01)
    }

    @Test
    fun `expected user win rate against higher elo engine`() {
        // 玩家 1500 vs 引擎 3000, 期望胜率 < 1%
        val rate = WinRateAnalyzer.expectedUserWinRate(difficultyLevel = 10, playerElo = 1500)
        assertTrue("高难度应有极低胜率: $rate", rate < 1.0)
    }

    @Test
    fun `expected user win rate at appropriate level`() {
        // 玩家 1500 vs 引擎 1500 (难度 4), 期望胜率 ≈ 50%
        val rate = WinRateAnalyzer.expectedUserWinRate(difficultyLevel = 4, playerElo = 1500)
        assertTrue("同级应有约 50% 胜率: $rate", rate in 40.0..60.0)
    }
}