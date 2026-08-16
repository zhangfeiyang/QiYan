package com.zhongguoxiangqi.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * movetime 用的是 coerceAtLeast, 会**静默抬高**调用方传的值.
 *
 * 这在对局里是想要的(难度越高想得越久), 但实时支招传 600ms 会被抬成几秒,
 * 隔着两层调用根本看不出来 —— 正是这类 bug 需要测试钉住的地方.
 *
 * Pikafish 那条分支已随引擎一起移除(GPL-3), 现在只有自研引擎一条路径。
 */
class SearchParamsAdaptTest {

    private val live = SearchParams(
        movetime = 600, threads = 2, hashMb = 64, strictMovetime = true
    )

    @Test
    fun `严格时限下任何难度都不抬高 movetime`() {
        for (level in 1..EngineManager.MAX_LEVEL) {
            val p = EngineManager.adaptParams(live, difficultyLevel = level)
            assertEquals("难度 $level 抬高了硬时限", 600L, p.movetime)
        }
    }

    @Test
    fun `严格时限不改动线程与哈希`() {
        val p = EngineManager.adaptParams(live, difficultyLevel = EngineManager.MAX_LEVEL)
        assertEquals(600L, p.movetime)
        assertEquals(2, p.threads)
        assertEquals(64, p.hashMb)
    }

    @Test
    fun `对局路径 难度 3 抬到 350ms`() {
        val p = EngineManager.adaptParams(SearchParams(movetime = 100), difficultyLevel = 3)
        assertEquals(350L, p.movetime)
    }

    @Test
    fun `对局路径 难度 6 抬到 1200ms`() {
        val p = EngineManager.adaptParams(SearchParams(movetime = 100), difficultyLevel = 6)
        assertEquals(1200L, p.movetime)
    }

    @Test
    fun `调用方传的值更大时不会被调低`() {
        val p = EngineManager.adaptParams(SearchParams(movetime = 9000), difficultyLevel = 6)
        assertEquals(9000L, p.movetime)
    }

    @Test
    fun `对局路径 最高难度(15 档)封顶 12 秒`() {
        // 上限是**产品约束**而不是引擎能力: 一手棋等 90 秒没人受得了, 顶档的棋力
        // 靠线程和哈希补(见 adaptParams), 不靠把时间拖长. 这条断言就是那个封顶.
        val p = EngineManager.adaptParams(SearchParams(movetime = 100), difficultyLevel = 15)
        assertEquals(12_000L, p.movetime)
    }

    @Test
    fun `任何难度都不会让玩家等过 12 秒`() {
        for (level in 1..EngineManager.MAX_LEVEL) {
            val t = EngineManager.adaptParams(SearchParams(movetime = 1), difficultyLevel = level).movetime
            assertEquals("难度 $level 的时限超过了 12 秒上限", true, t <= 12_000L)
        }
    }

    @Test
    fun `难度越高想得越久, 不能出现倒挂`() {
        // 梯子从 10 档砍到 6 档时容易漏掉某一档, 表现为"选了更难反而更快出手"
        var prev = 0L
        for (level in 1..EngineManager.MAX_LEVEL) {
            val t = EngineManager.adaptParams(SearchParams(movetime = 1), difficultyLevel = level).movetime
            assertEquals("难度 $level 的时限没有比上一档更长", true, t > prev)
            prev = t
        }
    }

    @Test
    fun `超出范围的难度被夹紧, 不会拿到空参数`() {
        for (bad in listOf(0, -3, 16, 99)) {
            val p = EngineManager.adaptParams(SearchParams(movetime = 1), difficultyLevel = bad)
            assertEquals("难度 $bad 应当被夹到合法档位", true, p.movetime >= 100L)
        }
    }
}
