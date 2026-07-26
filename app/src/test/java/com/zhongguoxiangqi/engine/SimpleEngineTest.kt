package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.Square
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 内置引擎测试.
 *
 * 重点覆盖曾经出问题的地方: 搜索不看时钟(会一直算下去)、
 * 停止搜索后旧线程仍吐出过期着法.
 */
class SimpleEngineTest {

    private fun engine(strength: Int): SimpleEngine =
        SimpleEngine().apply {
            configure(strength)
            start()
        }

    private class RecordingListener : EngineListener {
        val bestMove = AtomicReference<String?>(null)
        val bestMoveCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        override fun onReady() {}
        override fun onInfo(info: EngineInfo) {}
        override fun onBestMove(bestMove: String, ponderMove: String?) {
            this.bestMove.set(bestMove)
            bestMoveCount.incrementAndGet()
            latch.countDown()
        }
        override fun onError(message: String) {}
    }

    @Test
    fun `search respects its movetime budget`() {
        val e = engine(5)  // maxDepth 8: 没有时钟检查会算到天荒地老
        val listener = RecordingListener()
        e.setListener(listener)
        e.setPosition(Position().toFen(), emptyList())

        val budget = 1000L
        val start = System.currentTimeMillis()
        e.startSearch(SearchParams(movetime = budget))
        assertTrue("引擎应在预算内返回着法", listener.latch.await(10, TimeUnit.SECONDS))
        val elapsed = System.currentTimeMillis() - start

        assertTrue("超时过多: ${elapsed}ms (预算 ${budget}ms)", elapsed < budget * 3)
        e.shutdown()
    }

    @Test
    fun `search returns a legal move from the start position`() {
        val e = engine(3)
        val listener = RecordingListener()
        e.setListener(listener)
        e.setPosition(Position().toFen(), emptyList())
        e.startSearch(SearchParams(movetime = 800))
        assertTrue(listener.latch.await(10, TimeUnit.SECONDS))

        val uci = listener.bestMove.get()
        assertNotNull(uci)
        assertNotEquals("0000", uci)

        val pos = Position()
        val legal = mutableListOf<Move>()
        Rules.generateAllLegal(pos, legal)
        val asMove = uciToMove(uci!!)
        assertTrue("引擎返回的着法必须合法: $uci", legal.any { it.from == asMove.from && it.to == asMove.to })
    }

    @Test
    fun `stopping a search suppresses its best move`() {
        val e = engine(5)
        val listener = RecordingListener()
        e.setListener(listener)
        e.setPosition(Position().toFen(), emptyList())

        e.startSearch(SearchParams(movetime = 5000))
        Thread.sleep(120)
        e.stopSearch()
        // 被取消的搜索不应再回调 bestmove, 否则会变成"引擎替玩家落子"
        assertFalse(
            "stopSearch 之后不应再有 bestmove 回调",
            listener.latch.await(1500, TimeUnit.MILLISECONDS)
        )
        assertEquals(0, listener.bestMoveCount.get())
        e.shutdown()
    }

    @Test
    fun `engine follows the moves applied to the start fen`() {
        val e = engine(2)
        val listener = RecordingListener()
        e.setListener(listener)
        // 起始 FEN + 两步着法, 轮到红方走第三步
        e.setPosition(Position().toFen(), listOf("b2e2", "b7e7"))
        e.startSearch(SearchParams(movetime = 500))
        assertTrue(listener.latch.await(10, TimeUnit.SECONDS))

        // 用同样的方式重建局面, 验证引擎给的着法在该局面下合法
        val pos = Position()
        pos.doMove(uciToMove("b2e2"))
        pos.doMove(uciToMove("b7e7"))
        val mv = uciToMove(listener.bestMove.get()!!)
        assertTrue("引擎着法应在同步后的局面下合法", pos.isLegalMove(mv))
        e.shutdown()
    }

    private fun uciToMove(uci: String): Move {
        val fromCol = uci[0] - 'a'
        val fromRow = uci[1].digitToInt()
        val toCol = uci[2] - 'a'
        val toRow = uci[3].digitToInt()
        return Move.make(
            Square.make(9 - fromRow, fromCol),
            Square.make(9 - toRow, toCol)
        )
    }
}
