package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.engine.SimpleEngine
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 静止搜索预算的成本/收益扫描. `-Dnnue.qsweep=1` 打开.
 *
 * 以预算最大的那档为"标签参照"(消化得最彻底), 看每一档离它多远、花多少节点.
 * 目的是找拐点, 而不是沿用一个为绝对 ply 语义调出来的常量.
 */
class QuiescenceBudgetSweep {

    @Test
    fun sweep() {
        if (System.getProperty("nnue.qsweep") == null) {
            println("[qsweep] 未设置 -Dnnue.qsweep=1, 跳过"); return
        }
        val depth = System.getProperty("nnue.qsweepDepth", "6").toInt()
        val nPos = System.getProperty("nnue.qsweepPositions", "120").toInt()
        val netPath = System.getProperty("nnue.net")
        fun net() = if (netPath != null) NnueNetwork.load(java.io.FileInputStream(netPath))
            else NnueNetwork.load(javaClass.classLoader!!.getResourceAsStream("nnue/test.nnue")!!)

        val rng = Random(31337)
        val poss = mutableListOf<Position>()
        val legal = mutableListOf<Move>()
        while (poss.size < nPos) {
            val p = Position().also { it.setupStartPosition() }
            var dead = false
            repeat(6 + rng.nextInt(50)) {
                legal.clear(); Rules.generateAllLegal(p, legal)
                if (legal.isEmpty()) { dead = true; return@repeat }
                p.doMove(legal[rng.nextInt(legal.size)])
            }
            if (dead) continue
            legal.clear(); Rules.generateAllLegal(p, legal)
            if (legal.isEmpty()) continue
            poss.add(p.copy())
        }

        val budgets = listOf(0, 1, 2, 3, 4, 6, 8)
        val scores = HashMap<Int, IntArray>()
        val nodes = HashMap<Int, Long>()
        val millis = HashMap<Int, Long>()
        for (b in budgets) {
            val eng = SimpleEngine().apply { setNnue(NnueEvaluator(net())); quiescenceBudget = b }
            val sc = IntArray(poss.size); var n = 0L
            val t0 = System.nanoTime()
            for ((i, p) in poss.withIndex()) {
                eng.setPositionDirect(p)
                sc[i] = eng.searchWithScore(depth).second
                n += eng.lastSearchNodes
            }
            millis[b] = (System.nanoTime() - t0) / 1_000_000
            scores[b] = sc; nodes[b] = n
        }

        val ref = scores[budgets.last()]!!
        val base = nodes[0]!!
        println("[qsweep] 深度=$depth 局面=${poss.size} 参照=预算${budgets.last()}")
        println("[qsweep] 预算  节点数      倍数    耗时   离参照平均|差|  差>=100厘兵")
        for (b in budgets) {
            val sc = scores[b]!!
            var sum = 0L; var big = 0
            for (i in poss.indices) { val d = abs(sc[i] - ref[i]); sum += d; if (d >= 100) big++ }
            val nb = nodes[b]!!
            println("[qsweep]  %d    %-11d %.2fx   %5ds   %6.1f 厘兵   %d/%d (%.0f%%)".format(
                b, nb, nb.toDouble() / base, millis[b]!! / 1000,
                sum.toDouble() / poss.size, big, poss.size, big * 100.0 / poss.size))
        }
    }
}
