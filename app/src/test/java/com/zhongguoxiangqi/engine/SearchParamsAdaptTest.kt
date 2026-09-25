package com.zhongguoxiangqi.engine

import org.junit.Assert.*
import org.junit.Test

class SearchParamsAdaptTest {
    @Test
    fun `strict analysis parameters remain untouched across every level`() {
        val analysis = SearchParams(depth = 8, movetime = 600, threads = 2, hashMb = 64,
            multiPv = 3, strictMovetime = true)
        for (level in 1..EngineManager.MAX_LEVEL) {
            assertEquals(analysis, EngineManager.adaptParams(analysis, level))
        }
    }

    @Test
    fun `eight levels have responsive and strictly increasing search budgets`() {
        assertEquals(8, EngineManager.MAX_LEVEL)
        assertEquals(listOf(350L, 550L, 1200L, 3200L, 6000L, 7500L, 9000L, 12000L),
            (1..8).map { EngineManager.adaptParams(SearchParams(), it).movetime })
        assertEquals(listOf(1, 1, 2, 4, 4, 4, 4, 4),
            (1..8).map { EngineManager.adaptParams(SearchParams(), it).threads })
        assertEquals(listOf(16, 16, 64, 128, 256, 256, 256, 256),
            (1..8).map { EngineManager.adaptParams(SearchParams(), it).hashMb })
    }

    @Test
    fun `explicit larger search budget is respected`() {
        val result = EngineManager.adaptParams(SearchParams(movetime = 15000, threads = 8, hashMb = 512), 8)
        assertEquals(15000L, result.movetime)
        assertEquals(8, result.threads)
        assertEquals(512, result.hashMb)
    }

    @Test
    fun `out of range levels use nearest valid tier`() {
        val base = SearchParams()
        assertEquals(EngineManager.adaptParams(base, 1), EngineManager.adaptParams(base, -9))
        assertEquals(EngineManager.adaptParams(base, 8), EngineManager.adaptParams(base, 99))
    }
}
