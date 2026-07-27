package com.zhongguoxiangqi.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * movetime 用的是 coerceAtLeast, 会**静默抬高**调用方传的值.
 *
 * 这在对局里是想要的(难度越高想得越久), 但实时支招传 600ms 会被抬成 4000ms,
 * 隔着两层调用根本看不出来 —— 正是这类 bug 需要测试钉住的地方.
 */
class SearchParamsAdaptTest {

    private val live = SearchParams(
        movetime = 600, threads = 2, hashMb = 64, strictMovetime = true
    )

    @Test
    fun `严格时限下 Pikafish 不抬高 movetime`() {
        for (level in 1..10) {
            val p = EngineManager.adaptParams(live, isPikafish = true, difficultyLevel = level)
            assertEquals("难度 $level 抬高了硬时限", 600L, p.movetime)
        }
    }

    @Test
    fun `严格时限下简易引擎也不抬高 movetime`() {
        for (level in 1..10) {
            val p = EngineManager.adaptParams(live, isPikafish = false, difficultyLevel = level)
            assertEquals("难度 $level 抬高了硬时限", 600L, p.movetime)
        }
    }

    @Test
    fun `严格时限仍然保证 Pikafish 拿到够用的线程和哈希`() {
        val p = EngineManager.adaptParams(
            SearchParams(movetime = 600, threads = 1, hashMb = 16, strictMovetime = true),
            isPikafish = true, difficultyLevel = 8
        )
        assertEquals(600L, p.movetime)
        assertEquals(2, p.threads)
        assertEquals(64, p.hashMb)
    }

    @Test
    fun `对局路径不受影响 —— 难度 8 仍然抬到 4 秒`() {
        val p = EngineManager.adaptParams(
            SearchParams(movetime = 600, threads = 2, hashMb = 64),
            isPikafish = true, difficultyLevel = 8
        )
        assertEquals(4000L, p.movetime)
    }

    @Test
    fun `对局路径 简易引擎难度 3 抬到 800ms`() {
        val p = EngineManager.adaptParams(
            SearchParams(movetime = 100), isPikafish = false, difficultyLevel = 3
        )
        assertEquals(800L, p.movetime)
    }

    @Test
    fun `调用方传的值更大时不会被调低`() {
        val p = EngineManager.adaptParams(
            SearchParams(movetime = 9000), isPikafish = true, difficultyLevel = 6
        )
        assertEquals(9000L, p.movetime)
    }
}
