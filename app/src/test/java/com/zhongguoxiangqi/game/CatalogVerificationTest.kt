package com.zhongguoxiangqi.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogVerificationTest {
    private val file = File("src/main/assets/puzzles/catalog.txt")

    @Test fun catalogHasOneThousandUniqueMateInOnes() {
        val puzzles = PuzzleCatalog.parse(file.readText())
        assertEquals(1000, puzzles.size)
        assertEquals((1001..2000).toList(), puzzles.map { it.id })
        assertEquals(puzzles.size, puzzles.map { it.fen.substringBefore(' ') }.toSet().size)
        val pos = Position()
        for (puzzle in puzzles) {
            assertTrue(puzzle.fen, pos.setFromFen(puzzle.fen))
            assertEquals(Color.RED, pos.sideToMove)
            assertEquals(1, puzzle.maxMoves)
            assertTrue(puzzle.fen, MateSearch.isMateInOne(pos))
        }
    }

    @Test fun everyHistoricalBattleResolvesToItsTactic() {
        val puzzles = PuzzleCatalog.parse(file.readText())
        for (battle in HistoryCampaign.battles) {
            val puzzle = HistoryCampaign.resolve(battle, puzzles)
            assertNotNull("${battle.tactic} #${battle.occurrence}", puzzle)
            assertEquals(battle.tactic, puzzle!!.tactic)
        }
        assertEquals(20, HistoryCampaign.battles.size)
        assertEquals(5, HistoryCampaign.chapters.size)
    }
}
