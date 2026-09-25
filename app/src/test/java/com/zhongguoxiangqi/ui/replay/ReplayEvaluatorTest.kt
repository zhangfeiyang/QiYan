package com.zhongguoxiangqi.ui.replay

import com.zhongguoxiangqi.data.GameRecord
import com.zhongguoxiangqi.game.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEvaluatorTest {

    @Test
    fun testAnalyzeBasicMoves() {
        val moves = listOf("h2e2", "h9g7", "b0c2", "b9a7")
        val result = ReplayEvaluator.analyze("", moves)

        assertEquals(4, result.evaluations.size)
        assertEquals(5, result.winRates.size)

        for (wr in result.winRates) {
            assertTrue("Win rate should be in 0..100, was $wr", wr in 0f..100f)
        }

        // 起始胜率应在 50% 附近
        assertTrue("Initial win rate around 50%", result.winRates[0] in 45f..55f)

        // 验证第一手是炮二平五
        val firstEval = result.evaluations[0]
        assertEquals("炮二平五", firstEval.chineseNotation)
        assertTrue(firstEval.quality == MoveQuality.BOOK || firstEval.quality == MoveQuality.BEST)
    }

    @Test
    fun testPgnExport() {
        val record = GameRecord(
            id = 1L,
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 60000,
            playerSide = Color.RED,
            difficulty = 5,
            engineElo = 1800,
            playerRating = 1750,
            result = 1,
            ratingDelta = 15,
            moves = listOf("h2e2", "h9g7"),
            initialFen = "",
            endReason = "绝杀"
        )

        val pgn = PgnExporter.exportPgn(
            record = record,
            chineseMoves = listOf("炮二平五", "马8进7"),
            openingName = "中炮局"
        )

        assertTrue(pgn.contains("[Game \"Chinese Chess\"]"))
        assertTrue(pgn.contains("[Red \"玩家\"]"))
        assertTrue(pgn.contains("[Result \"1-0\"]"))
        assertTrue(pgn.contains("[Opening \"中炮局\"]"))
        assertTrue(pgn.contains("1. 炮二平五  马8进7"))
    }

    @Test
    fun testShareBattleReport() {
        val record = GameRecord(
            id = 2L,
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 120000,
            playerSide = Color.RED,
            difficulty = 6,
            engineElo = 1950,
            playerRating = 1800,
            result = 1,
            ratingDelta = 18,
            moves = listOf("h2e2", "h9g7", "b0c2", "b9a7"),
            initialFen = "",
            endReason = "绝杀"
        )

        val report = PgnExporter.exportShareText(
            record = record,
            chineseMoves = listOf("炮二平五", "马8进7", "马二进三", "马2进3"),
            openingName = "中炮对屏风马"
        )

        assertTrue(report.contains("杞言中国象棋 · 对局战报"))
        assertTrue(report.contains("大获全胜"))
        assertTrue(report.contains("中炮对屏风马"))
        assertTrue(report.contains("Lv.6"))
        assertTrue(report.contains("1. 炮二平五 马8进7"))
    }
}
