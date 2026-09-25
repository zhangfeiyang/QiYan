package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.UciCoords
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DifficultyEngineTest {
    private class Observer : EngineListener {
        val completed = CountDownLatch(1)
        var move: String? = null
        var error: String? = null
        override fun onReady() {}
        override fun onInfo(info: EngineInfo) {}
        override fun onBestMove(bestMove: String, ponderMove: String?) {
            move = bestMove
            completed.countDown()
        }
        override fun onError(message: String) { error = message; completed.countDown() }
        fun awaitMove() {
            assertTrue("search callback timed out", completed.await(3, TimeUnit.SECONDS))
            assertNull(error)
            assertNotNull(move)
        }
    }

    private class StrongEngine : ChessEngine {
        var started = false
        var failStartup = false
        var params: SearchParams? = null
        var positionMoves = emptyList<String>()
        var callback: EngineListener? = null
        var onStart: (() -> Unit)? = null
        val shutdownCompleted = CountDownLatch(1)
        val stopCompleted = CountDownLatch(1)
        override fun isReady() = started
        override fun name() = "test strong engine"
        override fun maxStrength() = 5
        override fun start(): Boolean {
            onStart?.invoke()
            if (failStartup) { callback?.onError("native unavailable"); return false }
            started = true
            return true
        }
        override fun shutdown() { started = false; shutdownCompleted.countDown() }
        override fun setPosition(fen: String, moves: List<String>) { positionMoves = moves }
        override fun startSearch(params: SearchParams) {
            this.params = params
            callback?.onBestMove("b0c2", null)
        }
        override fun stopSearch() { stopCompleted.countDown() }
        override fun setListener(listener: EngineListener?) { callback = listener }
    }

    @Test
    fun `lowest two levels start and play without touching native factory`() {
        for (level in 1..2) {
            val router = DifficultyEngine({ level }) { error("native must not be loaded") }
            val observer = Observer()
            router.setListener(observer)
            assertTrue(router.start())
            router.setPosition(Position().toFen(), emptyList())
            router.startSearch(SearchParams())
            observer.awaitMove()
            assertTrue(Position().isLegalMove(UciCoords.parse(observer.move!!)!!))
            router.shutdown()
        }
    }

    @Test
    fun `strict hints lazily use strong engine and preserve parameters and move history`() {
        val strong = StrongEngine()
        val factories = AtomicInteger()
        val router = DifficultyEngine({ 1 }) { factories.incrementAndGet(); strong }
        assertTrue(router.start())
        assertEquals(0, factories.get())
        val observer = Observer()
        router.setListener(observer)
        router.setPosition(Position().toFen(), listOf("b0c2", "b9c7"))
        val hint = SearchParams(movetime = 80, depth = 6, strictMovetime = true)
        router.startSearch(hint)
        observer.awaitMove()
        assertEquals(1, factories.get())
        assertSame(strong, router.activeEngine)
        assertEquals(hint, strong.params)
        assertEquals(listOf("b0c2", "b9c7"), strong.positionMoves)
        router.shutdown()
        assertTrue(strong.shutdownCompleted.await(3, TimeUnit.SECONDS))
        assertFalse(strong.started)
    }

    @Test
    fun `failed analysis startup does not prevent a subsequent practice move`() {
        val strong = StrongEngine().apply { failStartup = true }
        val router = DifficultyEngine({ 1 }) { strong }
        assertTrue(router.start())
        val failed = Observer()
        router.setListener(failed)
        router.startSearch(SearchParams(strictMovetime = true))
        assertTrue(failed.completed.await(3, TimeUnit.SECONDS))
        assertEquals("native unavailable", failed.error)
        val practice = Observer()
        router.setListener(practice)
        router.startSearch(SearchParams())
        practice.awaitMove()
        assertTrue(router.activeEngine is BeginnerEngine)
        router.shutdown()
    }

    @Test
    fun `changing from master to practice changes the selected engine`() {
        var level = 5
        val strong = StrongEngine()
        val router = DifficultyEngine({ level }) { strong }
        assertTrue(router.start())
        assertSame(strong, router.activeEngine)
        level = 1
        val observer = Observer()
        router.setListener(observer)
        router.startSearch(SearchParams())
        observer.awaitMove()
        assertTrue(router.activeEngine is BeginnerEngine)
        router.shutdown()
    }

    @Test
    fun `stop during native load returns immediately and practice stays available`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val strong = StrongEngine().apply {
            onStart = { entered.countDown(); release.await() }
        }
        val observer = Observer()
        val router = DifficultyEngine({ 1 }) { strong }
        router.setListener(observer)
        router.start()
        router.startSearch(SearchParams(strictMovetime = true))
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val startTime = System.nanoTime()
            router.stopSearch()
            assertTrue("stop blocked on native load", System.nanoTime() - startTime < 200_000_000L)
            val practice = Observer()
            router.setListener(practice)
            router.startSearch(SearchParams())
            practice.awaitMove()
            assertNull(strong.params)
            assertNull(observer.move)
            assertNull(observer.error)
        } finally {
            release.countDown()
            router.shutdown()
        }
        assertTrue(strong.shutdownCompleted.await(3, TimeUnit.SECONDS))
        assertNull(strong.params)
    }
}
