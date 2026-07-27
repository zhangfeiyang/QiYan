package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.game.Position
import org.junit.Test

/**
 * 搜索基准.
 *
 * 不断言"多少分", 那没法在单测里衡量; 断言的是**同样深度下搜了多少节点** ——
 * 着法排序变好, 剪枝就更早发生, 节点数下降. 这是引擎优化里最直接的量化指标.
 *
 * 跑法: ./gradlew :app:testDebugUnitTest --tests "*SearchBenchmark*" --info
 * 输出的节点数用来对比优化前后.
 */
class SearchBenchmarkTest {

    /** 标准开局 */
    private val START = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"

    /** 中局: 双方已展开, 分支因子最大, 最能体现排序质量 */
    private val MIDGAME = "r1ba1abnr/4k4/1cn3c2/p1p1p1p1p/9/2P6/P3P1P1P/1C2C1N2/9/RNBAKAB1R w - - 0 1"

    private fun nodesFor(fen: String, depth: Int): Long {
        val engine = SimpleEngine()
        engine.start()
        engine.setPosition(fen, emptyList())
        val n = engine.benchmark(depth)
        engine.shutdown()
        return n
    }

    @Test
    fun `开局固定深度的节点数`() {
        for (d in 3..5) {
            println("[bench] 开局 depth=$d nodes=${nodesFor(START, d)}")
        }
    }

    @Test
    fun `中局固定深度的节点数`() {
        for (d in 3..5) {
            println("[bench] 中局 depth=$d nodes=${nodesFor(MIDGAME, d)}")
        }
    }

    @Test
    fun `搜索结果必须是合法着法`() {
        val engine = SimpleEngine()
        engine.start()
        engine.setPosition(START, emptyList())
        val mv = engine.searchBestMoveForTest(depth = 4)
        engine.shutdown()
        val pos = Position().also { it.setFromFen(START) }
        val legal = mutableListOf<com.zhongguoxiangqi.game.Move>()
        com.zhongguoxiangqi.game.Rules.generateAllLegal(pos, legal)
        assert(legal.any { it.data == mv.data }) { "引擎给出了非法着法: $mv" }
    }
}
