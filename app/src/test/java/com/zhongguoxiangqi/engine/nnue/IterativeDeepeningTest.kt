package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.engine.SimpleEngine
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import org.junit.Test
import kotlin.random.Random

/**
 * 数据生成走的 `searchWithScore` 加上迭代加深 + 根排序之后的两件事:
 *   - 节点数下降多少 (收益)
 *   - 标签漂移多少 (代价)
 *
 * 一开始我以为分数是不变量 —— 同深度下 alpha-beta 的极小极大值确定, 排序
 * 只该影响到达它的快慢. 实测不是: LMR 的减层条件看的是**着法序号**
 * (`SimpleEngine` 里 `searched >= LMR_MIN_MOVES`), 换了顺序就换了一批被减层
 * 的着法, 而减层搜索本身是不精确的; 置换表跨迭代复用边界也会带来同样的
 * 不确定性. 这是标准的 search instability, 不是 bug.
 *
 * 所以这里不断言相等, 只量漂移幅度, 并守一条上界: 漂移必须远小于静止搜索
 * 那个缺陷造成的 138 厘兵. 真涨到那个量级就说明排序改动引入了别的问题.
 *
 * 节点数和分数都是确定性的, 所以这个测试不受机器上同时跑别的东西影响.
 */
class IterativeDeepeningTest {

    private fun net(): NnueNetwork =
        NnueNetwork.load(javaClass.classLoader!!.getResourceAsStream("nnue/test.nnue")!!)

    /** 随机中局/残局若干, 覆盖不同子力密度 */
    private fun positions(n: Int, seed: Int): List<Position> {
        val rng = Random(seed)
        val out = mutableListOf<Position>()
        val legal = mutableListOf<Move>()
        while (out.size < n) {
            val pos = Position().also { it.setupStartPosition() }
            var dead = false
            repeat(6 + rng.nextInt(50)) {
                legal.clear()
                Rules.generateAllLegal(pos, legal)
                if (legal.isEmpty()) { dead = true; return@repeat }
                pos.doMove(legal[rng.nextInt(legal.size)])
            }
            if (dead) continue
            legal.clear()
            Rules.generateAllLegal(pos, legal)
            if (legal.isEmpty()) continue
            out.add(pos.copy())
        }
        return out
    }

    @Test
    fun iterativeDeepeningLabelDelta() {
        val depth = 5
        val poss = positions(60, 424242)

        val direct = SimpleEngine().apply {
            setNnue(NnueEvaluator(net())); useIterativeDeepening = false
        }
        val iter = SimpleEngine().apply {
            setNnue(NnueEvaluator(net())); useIterativeDeepening = true
        }

        var nodesDirect = 0L
        var nodesIter = 0L
        var moveDiff = 0
        var changed = 0
        var sumAbs = 0L
        val diffs = mutableListOf<Int>()
        for ((i, p) in poss.withIndex()) {
            direct.setPositionDirect(p)
            val (dm, ds) = direct.searchWithScore(depth)
            nodesDirect += direct.lastSearchNodes

            iter.setPositionDirect(p)
            val (im, isc) = iter.searchWithScore(depth)
            nodesIter += iter.lastSearchNodes

            // 分数**不是**不变量: LMR 的减层条件看着法序号, 换了顺序就换了
            // 一批被减层的着法, 而减层搜索本身不精确; 置换表跨迭代复用边界
            // 也会带来同样的不确定性. 这是标准的 search instability, 不是 bug.
            // 所以这里只统计差异, 不断言相等.
            if (ds != isc) { changed++; sumAbs += kotlin.math.abs(ds - isc).toLong() }
            diffs.add(kotlin.math.abs(ds - isc))
            if (dm.data != im.data) moveDiff++
        }
        diffs.sort()

        println("[id-test] 深度=$depth 局面=${poss.size}")
        println("[id-test] 直接搜到底 $nodesDirect 节点, 迭代加深 $nodesIter 节点, " +
            "省了 ${"%.1f".format((1 - nodesIter.toDouble() / nodesDirect) * 100)}%  " +
            "(${"%.2f".format(nodesDirect.toDouble() / nodesIter)} 倍)")
        val pct = { q: Double -> diffs[(diffs.size * q).toInt().coerceAtMost(diffs.size - 1)] }
        println("[id-test] 标签改变: $changed/${poss.size} " +
            "(${"%.1f".format(changed * 100.0 / poss.size)}%)  " +
            "平均|差|=${"%.1f".format(sumAbs.toDouble() / poss.size)} 厘兵  " +
            "中位=${pct(0.5)} p90=${pct(0.9)} 最大=${diffs.last()}")
        println("[id-test] 最佳着法不同: $moveDiff/${poss.size}")
        // 这不是不变量, 但差异应该远小于静止搜索那个缺陷造成的 138 厘兵.
        // 真涨到那个量级就说明排序改动引入了别的问题, 值得停下来看.
        assert(sumAbs.toDouble() / poss.size < 100) {
            "迭代加深带来的标签漂移过大 (平均 ${sumAbs.toDouble() / poss.size} 厘兵), 需要排查"
        }
    }

    /** 收益随深度放大: 深度越大, 排序的价值越高 */
    @Test
    fun savingsGrowWithDepth() {
        if (System.getProperty("nnue.idBench") == null) {
            println("[id-bench] 未设置 -Dnnue.idBench=1, 跳过逐深度对比")
            return
        }
        val poss = positions(24, 20260729)
        val direct = SimpleEngine().apply {
            setNnue(NnueEvaluator(net())); useIterativeDeepening = false
        }
        val iter = SimpleEngine().apply {
            setNnue(NnueEvaluator(net())); useIterativeDeepening = true
        }
        for (depth in 3..7) {
            var nd = 0L
            var ni = 0L
            for (p in poss) {
                direct.setPositionDirect(p); direct.searchWithScore(depth); nd += direct.lastSearchNodes
                iter.setPositionDirect(p); iter.searchWithScore(depth); ni += iter.lastSearchNodes
            }
            println("[id-bench] 深度 $depth: 直接 $nd → 迭代加深 $ni  " +
                "(${"%.2f".format(nd.toDouble() / ni)} 倍, 省 ${"%.1f".format((1 - ni.toDouble() / nd) * 100)}%)")
        }
    }
}
