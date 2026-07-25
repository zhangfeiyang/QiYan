package com.zhongguoxiangqi.rating

import org.junit.Assert.*
import org.junit.Test

/**
 * 评分系统测试.
 */
class Glicko2Test {

    @Test
    fun `initial rating update`() {
        val player = Glicko2.Rating()
        val matches = listOf(
            Glicko2.Match(opponentRating = 1500.0, opponentRd = 200.0, score = 1.0)  // 胜
        )
        Glicko2.update(player, matches)
        assertTrue("胜后评分应上升: ${player.rating}", player.rating > 1500.0)
    }

    @Test
    fun `loss decreases rating`() {
        val player = Glicko2.Rating()
        val matches = listOf(
            Glicko2.Match(opponentRating = 1500.0, opponentRd = 200.0, score = 0.0)  // 负
        )
        Glicko2.update(player, matches)
        assertTrue("负后评分应下降: ${player.rating}", player.rating < 1500.0)
    }

    @Test
    fun `rd decreases with more games`() {
        val player = Glicko2.Rating()  // 初始 RD = 350
        val matches = listOf(
            Glicko2.Match(opponentRating = 1500.0, opponentRd = 200.0, score = 1.0)
        )
        Glicko2.update(player, matches)
        assertTrue("对局后 RD 应下降: ${player.rd}", player.rd < 350.0)
    }

    @Test
    fun `no matches slightly increases rd`() {
        val player = Glicko2.Rating(rating = 1500.0, rd = 100.0, sigma = 0.06)
        Glicko2.update(player, emptyList())
        assertTrue("无对局时 RD 应略增: ${player.rd}", player.rd > 100.0)
    }
}

class EloRatingTest {
    @Test
    fun `expected score equal ratings is 50 percent`() {
        val expected = EloRating.expectedScore(1500, 1500)
        assertEquals(0.5, expected, 0.001)
    }

    @Test
    fun `higher rating has higher expected score`() {
        val higher = EloRating.expectedScore(1700, 1500)
        assertTrue("高分选手应有更高期望胜率: $higher", higher > 0.5)
    }

    @Test
    fun `rating update on win increases rating`() {
        val newRating = EloRating.update(1500, 1500, score = 1.0)
        assertTrue("胜利后评分上升: $newRating", newRating > 1500)
    }

    @Test
    fun `rating update on loss decreases rating`() {
        val newRating = EloRating.update(1500, 1500, score = 0.0)
        assertTrue("失败后评分下降: $newRating", newRating < 1500)
    }
}