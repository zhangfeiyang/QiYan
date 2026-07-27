package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.game.Position
import org.junit.Test

/**
 * NNUE 求值速度. 默认跳过, 用 `-Dnnue.bench=1` 打开.
 *
 * 存在的理由: NNUE 换来的棋力只有在**搜索速度没被拖垮**的前提下才成立。
 * 现在是每次求值都从头重算累加器 (32 子 × 256 维 × 2 视角), 而手写评估
 * 只要几百次操作。如果差出一个数量级以上, 那就是"评估准了但搜索浅了两层",
 * 净收益可能是负的 —— 所以接进搜索之前先把这个数量测出来。
 *
 * 注意这是 JVM 上的数字, 手机上要再打个折。它只用来判断数量级, 以及
 * 增量更新做完之后有没有真的变快。
 */
class NnueSpeedBench {

    @Test
    fun bench() {
        if (System.getProperty("nnue.bench") == null) {
            println("[bench] 未设置 -Dnnue.bench=1, 跳过")
            return
        }

        // 默认用测试素材; 想量别的宽度就用 -Dnnue.net=/path/to/x.nnue
        val path = System.getProperty("nnue.net")
        val stream = if (path != null) java.io.FileInputStream(path)
        else javaClass.classLoader!!.getResourceAsStream("nnue/test.nnue")!!
        val net = NnueNetwork.load(stream)
        val ev = NnueEvaluator(net)
        val pos = Position().also { it.setupStartPosition() }

        // 预热: 让 JIT 把热点编译掉, 否则量到的是解释执行的速度
        repeat(20_000) { ev.evaluate(pos) }

        val n = 200_000
        val t0 = System.nanoTime()
        var sink = 0
        repeat(n) { sink += ev.evaluate(pos) }
        val ms = (System.nanoTime() - t0) / 1_000_000.0

        val perSec = n / (ms / 1000.0)
        println("[bench] l1=${net.l1} 全量重算: ${"%.0f".format(perSec)} 次/秒 " +
            "(${"%.2f".format(ms * 1000 / n)} 微秒/次, sink=$sink)")

        // 同口径量一遍现有的手写评估. 光看 NNUE 的绝对速度没有意义,
        // 有意义的是"慢了多少倍" —— 那才决定搜索要浅几层.
        val eng = com.zhongguoxiangqi.engine.SimpleEngine()
        val hcNs = eng.benchmarkEval(pos, n)
        val hcPerSec = n / (hcNs / 1e9)
        println("[bench] 手写评估: ${"%.0f".format(hcPerSec)} 次/秒")
        println("[bench] NNUE 慢 ${"%.1f".format(hcPerSec / perSec)} 倍 " +
            "—— 分支因子按 3 折算, 大约相当于少搜 " +
            "${"%.1f".format(kotlin.math.ln(hcPerSec / perSec) / kotlin.math.ln(3.0))} 层")

        // 增量更新最多能省掉多少: 累加器的开销正比于子数, 增量更新等于把
        // 每次求值的子数从 32 降到 2~3. 拿一个 3 子残局量一遍, 就是增量
        // 更新做完之后速度的上界 —— 剩下的 fc1 是每次求值都躲不掉的.
        val sparse = IntArray(NnueFeatures.MAX_PIECES * 2)
        sparse[0] = 0; sparse[1] = 4      // 帅
        sparse[2] = 7; sparse[3] = 85     // 将
        sparse[4] = 4; sparse[5] = 40     // 车
        repeat(20_000) { ev.evaluatePieces(sparse, 3, false) }
        val t1 = System.nanoTime()
        repeat(n) { sink += ev.evaluatePieces(sparse, 3, false) }
        val sparsePerSec = n / ((System.nanoTime() - t1) / 1e9)
        println("[bench] 3 子局面: ${"%.0f".format(sparsePerSec)} 次/秒 " +
            "(增量更新的速度上界, 仍慢 ${"%.1f".format(hcPerSec / sparsePerSec)} 倍)")
    }
}
