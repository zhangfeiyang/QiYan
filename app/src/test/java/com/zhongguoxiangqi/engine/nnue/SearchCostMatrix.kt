package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.engine.SimpleEngine
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import org.junit.Test
import kotlin.random.Random

/**
 * 把"迭代加深"和"静止搜索"两项改动的成本拆开归因. `-Dnnue.costmx=1` 打开.
 *
 * 之前靠节点数推算总成本, 推出来是持平, 实测数据生成却慢了 2.5 倍 ——
 * 说明模型漏了东西, 只能把四种组合都实测一遍.
 */
class SearchCostMatrix {

    @Test
    fun matrix() {
        if (System.getProperty("nnue.costmx") == null) {
            println("[costmx] 未设置 -Dnnue.costmx=1, 跳过"); return
        }
        val depth = System.getProperty("nnue.costmxDepth", "6").toInt()
        val nPos = System.getProperty("nnue.costmxPositions", "80").toInt()
        val netPath = System.getProperty("nnue.net")
        fun net() = if (netPath != null) NnueNetwork.load(java.io.FileInputStream(netPath))
            else NnueNetwork.load(javaClass.classLoader!!.getResourceAsStream("nnue/test.nnue")!!)

        // 用自对弈实际会遇到的局面: 前 8 步随机, 之后引擎自己走
        val rng = Random(777)
        val poss = mutableListOf<Position>()
        val legal = mutableListOf<Move>()
        val driver = SimpleEngine().apply { setNnue(NnueEvaluator(net())) }
        while (poss.size < nPos) {
            val p = Position().also { it.setupStartPosition() }
            var ok = true
            repeat(8 + rng.nextInt(60)) {
                legal.clear(); Rules.generateAllLegal(p, legal)
                if (legal.isEmpty()) { ok = false; return@repeat }
                p.doMove(legal[rng.nextInt(legal.size)])
            }
            if (!ok) continue
            legal.clear(); Rules.generateAllLegal(p, legal)
            if (legal.isEmpty()) continue
            poss.add(p.copy())
        }
        println("[costmx] 深度=$depth 局面=${poss.size}")
        println("[costmx] 迭代加深  静止预算   节点数       耗时      节点/秒     相对基线")

        var baseMs = 0L
        for ((id, budget) in listOf(false to 0, true to 0, false to 6, true to 6)) {
            val eng = SimpleEngine().apply {
                setNnue(NnueEvaluator(net())); useIterativeDeepening = id; quiescenceBudget = budget
            }
            // 预热
            for (p in poss.take(10)) { eng.setPositionDirect(p); eng.searchWithScore(depth) }
            var n = 0L
            val t0 = System.nanoTime()
            for (p in poss) { eng.setPositionDirect(p); eng.searchWithScore(depth); n += eng.lastSearchNodes }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (baseMs == 0L) baseMs = ms
            println("[costmx]   %-7s   %-6d   %-11d %5dms   %8.0f   %.2fx".format(
                if (id) "开" else "关", budget, n, ms, n * 1000.0 / ms, ms.toDouble() / baseMs))
        }
    }
}
