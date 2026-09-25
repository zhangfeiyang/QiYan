package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.game.Position
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class BeginnerEngineTest {
    private fun position(fen: String) = Position().apply { check(setFromFen(fen)) }

    @Test
    fun `practice moves remain legal through complete seeded games and preserve position`() {
        for (level in 1..2) {
            repeat(8) { seed ->
                val pos = Position()
                val random = Random(seed)
                repeat(100) moveLoop@ {
                    val fen = pos.toFen()
                    val hash = pos.hash
                    val history = pos.historyHashes.toList()
                    val move = BeginnerEngine.chooseMove(pos, level, random) ?: return@moveLoop
                    assertEquals(fen, pos.toFen())
                    assertEquals(hash, pos.hash)
                    assertEquals(history, pos.historyHashes)
                    assertTrue("level=$level seed=$seed move=$move", pos.isLegalMove(move))
                    pos.doMove(move)
                }
            }
        }
    }

    @Test
    fun `easy opponent often misses a free rook while entry level notices it more often`() {
        val pos = position("4k4/9/9/9/4p4/9/9/r8/9/R3K4 w - - 0 1")
        val captures = IntArray(2)
        for (level in 1..2) {
            val random = Random(72)
            repeat(400) {
                val move = BeginnerEngine.chooseMove(pos, level, random)!!
                assertTrue(pos.isLegalMove(move))
                if (pos.pieceAt(move.to) != 0) captures[level - 1]++
            }
        }
        assertTrue("Easy must miss most free captures: ${captures[0]}", captures[0] < 140)
        assertTrue("Entry should notice simple opportunities: ${captures[1]}", captures[1] > 260)
    }

    @Test
    fun `random mistakes never leave own king in check`() {
        val pos = position("3k5/9/9/9/9/9/9/4r4/9/R3K4 w - - 0 1")
        for (level in 1..2) repeat(50) {
            val move = BeginnerEngine.chooseMove(pos, level, Random(it))
            assertNotNull(move)
            assertTrue(pos.isLegalMove(move!!))
        }
    }

    @Test
    fun `stop is synchronous and never emits a delayed move`() {
        val moves = AtomicInteger()
        val engine = BeginnerEngine({ 1 })
        engine.setListener(object : EngineListener {
            override fun onReady() {}
            override fun onInfo(info: EngineInfo) {}
            override fun onBestMove(bestMove: String, ponderMove: String?) { moves.incrementAndGet() }
            override fun onError(message: String) { fail(message) }
        })
        engine.start()
        engine.startSearch(SearchParams(movetime = 650))
        engine.stopSearch()
        assertEquals(0, moves.get())
        engine.shutdown()
    }

    @Test
    fun `position replay validates moves and refuses to search a stale position`() {
        val errors = AtomicInteger()
        val moves = AtomicInteger()
        val engine = BeginnerEngine({ 1 })
        engine.setListener(object : EngineListener {
            override fun onReady() {}
            override fun onInfo(info: EngineInfo) {}
            override fun onBestMove(bestMove: String, ponderMove: String?) { moves.incrementAndGet() }
            override fun onError(message: String) { errors.incrementAndGet() }
        })
        engine.start()
        engine.setPosition(Position().toFen(), listOf("a0a9"))
        engine.startSearch(SearchParams())
        assertEquals(2, errors.get())
        assertEquals(0, moves.get())
        engine.shutdown()
    }
}
