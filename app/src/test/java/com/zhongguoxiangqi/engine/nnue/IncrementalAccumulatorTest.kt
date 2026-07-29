package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.engine.SimpleEngine
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import org.junit.Assume
import org.junit.Test
import kotlin.random.Random
import org.junit.Assert.assertEquals

/**
 * 增量累加器与全量重算必须给出**完全相同**的分数.
 *
 * 这是整套增量改造里唯一真正危险的地方: push/pop 少配一次不会崩, 只会让
 * 累加器随着搜索慢慢漂移, 症状是"棋力莫名其妙地差". 所以这里不比"差不多",
 * 只比逐位相同, 而且是在**真实搜索树**上比 —— 单独测 acc_apply 已经由
 * host_test / nativeAccSelfCheck 覆盖了, 这里要抓的是搜索里漏配对的那种错.
 *
 * 需要 native 库 (桌面上由 build_host.sh 产出); 没有就跳过.
 */
class IncrementalAccumulatorTest {

    private fun loadNet(): NnueNetwork =
        NnueNetwork.load(javaClass.classLoader!!.getResourceAsStream("nnue/test.nnue")!!)

    /** 开局局面搜到底, 每次求值都比对增量 vs 全量 */
    @Test
    fun incrementalMatchesFullRecomputeFromStartPosition() {
        val ev = NnueEvaluator(loadNet())
        Assume.assumeTrue("native 不可用, 跳过增量测试", ev.incrementalAvailable)

        val eng = SimpleEngine()
        eng.setNnue(ev)
        eng.accVerify = true
        eng.setPositionDirect(Position().also { it.setupStartPosition() })

        val nodes = eng.benchmark(5)
        assertEquals(
            "增量累加器与全量重算不一致 ${eng.accMismatches} 次 (搜了 $nodes 个节点)",
            0L, eng.accMismatches)
        // 顺带确认真的搜到了东西, 否则 0 次不一致毫无意义
        assert(nodes > 10_000) { "只搜了 $nodes 个节点, 覆盖不够" }
    }

    /**
     * 随机中局 + 残局各来一批.
     *
     * 开局阶段吃子少, 而 acc_apply 里最容易写错的恰恰是吃子那一支
     * (被吃的子和落点重合). 随机走一二十步之后局面里到处是吃子.
     */
    @Test
    fun incrementalMatchesFullRecomputeInMidgameAndEndgame() {
        val ev = NnueEvaluator(loadNet())
        Assume.assumeTrue("native 不可用, 跳过增量测试", ev.incrementalAvailable)

        val eng = SimpleEngine()
        eng.setNnue(ev)
        eng.accVerify = true

        val rng = Random(20260729)
        var totalNodes = 0L
        var positions = 0
        val legal = mutableListOf<com.zhongguoxiangqi.game.Move>()

        for (trial in 0 until 12) {
            val pos = Position().also { it.setupStartPosition() }
            // 随机走 8..40 步, 覆盖中局到残局
            val plies = 8 + rng.nextInt(33)
            for (p in 0 until plies) {
                legal.clear()
                Rules.generateAllLegal(pos, legal)
                if (legal.isEmpty()) break
                pos.doMove(legal[rng.nextInt(legal.size)])
            }
            legal.clear()
            Rules.generateAllLegal(pos, legal)
            if (legal.isEmpty()) continue

            eng.setPositionDirect(pos)
            totalNodes += eng.benchmark(4)
            positions++
        }

        assertEquals(
            "增量累加器与全量重算不一致 ${eng.accMismatches} 次 " +
                "($positions 个局面, 共 $totalNodes 个节点)",
            0L, eng.accMismatches)
        assert(positions >= 8) { "只测到 $positions 个局面" }
    }

    /**
     * 深度超过累加器栈容量时必须自动退回全量重算, 而不是读到脏数据.
     *
     * 栈是定长的 (native 侧 kAccCapacity), 超出之后 push 只计数不写栈.
     * 弹回容量以内之后原来那些层还应该是对的 —— 这条路径平时跑不到,
     * 只有搜索特别深时才会碰上, 所以专门测一次.
     */
    @Test
    fun deepSearchStillMatches() {
        val ev = NnueEvaluator(loadNet())
        Assume.assumeTrue("native 不可用, 跳过增量测试", ev.incrementalAvailable)

        val eng = SimpleEngine()
        eng.setNnue(ev)
        eng.accVerify = true
        eng.setPositionDirect(Position().also { it.setupStartPosition() })

        val nodes = eng.benchmark(7)
        assertEquals(
            "深搜时增量与全量不一致 ${eng.accMismatches} 次 (搜了 $nodes 个节点)",
            0L, eng.accMismatches)
    }
}
