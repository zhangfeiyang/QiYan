package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.engine.SimpleEngine
import com.zhongguoxiangqi.game.Position
import org.junit.Test

/**
 * 增量累加器带来的**搜索**加速. 默认跳过, 用 `-Dnnue.incBench=1` 打开.
 *
 * 量的是同深度搜索的墙钟时间, 不是单次求值的吞吐 —— 后者会高估收益, 因为
 * 搜索里还有走法生成、排序、置换表这些跟 NNUE 无关的开销.
 *
 * 两条路径的**节点数必须完全相同**: 分数一样 → 剪枝一样 → 节点数一样.
 * 对不上就说明增量算错了, 那种情况下再快也没意义.
 */
class IncrementalSpeedBench {

    @Test
    fun bench() {
        if (System.getProperty("nnue.incBench") == null) {
            println("[inc-bench] 未设置 -Dnnue.incBench=1, 跳过")
            return
        }
        val path = System.getProperty("nnue.net")
        val depth = System.getProperty("nnue.incDepth", "6").toInt()
        val reps = System.getProperty("nnue.incReps", "3").toInt()

        fun freshNet(): NnueNetwork =
            if (path != null) NnueNetwork.load(java.io.FileInputStream(path))
            else NnueNetwork.load(javaClass.classLoader!!.getResourceAsStream("nnue/test.nnue")!!)

        val probe = NnueEvaluator(freshNet())
        println("[inc-bench] native=${probe.usingNative} 增量可用=${probe.incrementalAvailable} " +
            "深度=$depth 重复=$reps 网络=${path ?: "test.nnue"}")
        if (!probe.incrementalAvailable) {
            println("[inc-bench] 增量不可用 (native 缺失?), 跳过")
            return
        }
        probe.close()

        // 同一进程里交替跑, 避免 JIT 预热 / 睿频把结果带偏
        fun run(incremental: Boolean): Pair<Long, Double> {
            val ev = NnueEvaluator(freshNet())
            if (!incremental) ev.disableIncrementalForBench()
            val eng = SimpleEngine()
            eng.setNnue(ev)
            eng.setPositionDirect(Position().also { it.setupStartPosition() })
            eng.benchmark(depth - 2)          // 预热
            val t0 = System.nanoTime()
            val nodes = eng.benchmark(depth)
            val ms = (System.nanoTime() - t0) / 1e6
            return nodes to ms
        }

        var bestInc = Double.MAX_VALUE
        var bestFull = Double.MAX_VALUE
        var nodesInc = 0L
        var nodesFull = 0L
        repeat(reps) {
            val (nf, mf) = run(false)
            val (ni, mi) = run(true)
            nodesFull = nf; nodesInc = ni
            if (mf < bestFull) bestFull = mf
            if (mi < bestInc) bestInc = mi
            println("[inc-bench]   全量 ${"%.0f".format(mf)}ms / 增量 ${"%.0f".format(mi)}ms")
        }

        // 取各自最快的一次: 墙钟时间的噪声都是单向的(被别的进程抢走 CPU),
        // 最小值比平均值更接近真实成本
        println("[inc-bench] 全量重算 ${"%.0f".format(bestFull)}ms " +
            "(${"%.0f".format(nodesFull / (bestFull / 1000.0))} 节点/秒)")
        println("[inc-bench] 增量更新 ${"%.0f".format(bestInc)}ms " +
            "(${"%.0f".format(nodesInc / (bestInc / 1000.0))} 节点/秒)")
        println("[inc-bench] 搜索加速 ${"%.2f".format(bestFull / bestInc)} 倍")
        if (nodesInc != nodesFull) {
            println("[inc-bench] !! 节点数不一致 (增量 $nodesInc vs 全量 $nodesFull) —— 增量算错了")
        } else {
            println("[inc-bench] 节点数一致 ($nodesInc), 两条路径给出了相同的搜索树")
        }
    }
}
