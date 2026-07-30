package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.engine.SimpleEngine
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 空着裁剪的收益(省多少节点)与代价(棋力漂移多少).
 *
 * 剪枝是拿准确性换速度, 做错了会"更快但更弱"。所以两个都要量, 而且都是
 * 确定性指标 —— 节点数和分数在固定深度下都是可复现的, 不受机器负载影响。
 */
class NullMoveTest {

    private fun net() = NnueNetwork.load(
        javaClass.classLoader!!.getResourceAsStream("nnue/test.nnue")!!)

    private fun positions(n: Int, seed: Int): List<Position> {
        val rng = Random(seed); val out = mutableListOf<Position>()
        val legal = mutableListOf<Move>()
        while (out.size < n) {
            val p = Position().also { it.setupStartPosition() }
            var ok = true
            repeat(8 + rng.nextInt(50)) {
                legal.clear(); Rules.generateAllLegal(p, legal)
                if (legal.isEmpty()) { ok = false; return@repeat }
                p.doMove(legal[rng.nextInt(legal.size)])
            }
            if (!ok) continue
            legal.clear(); Rules.generateAllLegal(p, legal)
            if (legal.isEmpty()) continue
            out.add(p.copy())
        }
        return out
    }

    @Test
    fun nullMoveCutsNodes() {
        val poss = positions(60, 987654)
        val off = SimpleEngine().apply { setNnue(NnueEvaluator(net())); useNullMove = false }
        val on  = SimpleEngine().apply { setNnue(NnueEvaluator(net())); useNullMove = true }
        for (depth in intArrayOf(4, 6, 7)) {
            var nOff = 0L; var nOn = 0L; var changed = 0; var sumAbs = 0L; var maxAbs = 0
            for (p in poss) {
                off.setPositionDirect(p); val so = off.searchWithScore(depth).second
                nOff += off.lastSearchNodes
                on.setPositionDirect(p);  val sn = on.searchWithScore(depth).second
                nOn += on.lastSearchNodes
                if (so != sn) { changed++; val d = abs(so - sn); sumAbs += d; if (d > maxAbs) maxAbs = d }
            }
            println("[nullmove] 深度 %d: %d → %d 节点 (省 %.1f%%, %.2f 倍)  分数变 %d/%d 平均|差|%.1f 最大%d"
                .format(depth, nOff, nOn, (1 - nOn.toDouble() / nOff) * 100,
                    nOff.toDouble() / nOn, changed, poss.size,
                    sumAbs.toDouble() / poss.size, maxAbs))
        }
    }
}
