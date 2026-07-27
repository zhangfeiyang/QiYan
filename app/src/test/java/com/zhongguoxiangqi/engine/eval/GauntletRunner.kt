package com.zhongguoxiangqi.engine.eval

import com.zhongguoxiangqi.engine.SimpleEngine
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 实战评测入口. 和 DataGenRunner 一样, 借 JUnit 当运行器.
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*GauntletRunner*" --info \
 *     -Dmatch.run=1 -Dmatch.pairs=200 -Dmatch.depthA=5 -Dmatch.depthB=4 -Dmatch.threads=8
 * ```
 *
 * 默认跳过, 免得每次跑单测都触发几十分钟的对局.
 *
 * 目前 A/B 只能按搜索深度区分. 等 NNUE 网络就位后, 这里换成"装网络的引擎
 * vs 不装网络的引擎", 就是每一代自举的验收标准.
 */
class GauntletRunner {

    private fun engineAt(depth: Int) = MatchRunner.EngineFactory {
        val e = SimpleEngine()
        e.start()
        MatchRunner.Engine { pos: Position ->
            e.setPositionDirect(pos)
            e.searchWithScore(depth).first
        }
    }

    @Test
    fun gauntlet() {
        if (System.getProperty("match.run") == null) {
            println("[match] 未设置 -Dmatch.run=1, 跳过实战评测")
            return
        }
        val pairs = System.getProperty("match.pairs", "100").toInt()
        val depthA = System.getProperty("match.depthA", "5").toInt()
        val depthB = System.getProperty("match.depthB", "4").toInt()
        val threads = System.getProperty("match.threads", "8").toInt()

        println("[match] A=depth$depthA  B=depth$depthB  开局对数=$pairs " +
            "(共 ${pairs * 2} 局, 线程=$threads)")

        // 按开局对切分给多个线程. 每对内部的两局必须留在同一批里 —— 拆开
        // 就失去了配对消除先手优势的意义.
        val chunk = maxOf(1, pairs / threads)
        val pool = Executors.newFixedThreadPool(threads)
        val results = java.util.Collections.synchronizedList(mutableListOf<MatchRunner.Result>())
        val done = AtomicInteger()
        val t0 = System.currentTimeMillis()

        var assigned = 0
        var shard = 0
        while (assigned < pairs) {
            val n = minOf(chunk, pairs - assigned)
            val seed = 20260727L + shard * 104729L
            pool.submit {
                val r = MatchRunner.play(
                    engineAt(depthA), engineAt(depthB),
                    pairs = n, seed = seed
                ) { p, _ ->
                    val d = done.incrementAndGet()
                    if (d % 10 == 0) {
                        val secs = (System.currentTimeMillis() - t0) / 1000
                        println("[match] 进度 $d/$pairs 对  用时 ${secs}s")
                    }
                }
                results.add(r)
            }
            assigned += n
            shard++
        }
        pool.shutdown()
        pool.awaitTermination(12, TimeUnit.HOURS)

        val total = results.fold(MatchRunner.Result(0, 0, 0)) { acc, r ->
            MatchRunner.Result(acc.wins + r.wins, acc.losses + r.losses, acc.draws + r.draws)
        }
        val secs = (System.currentTimeMillis() - t0) / 1000
        println("[match] ===== 结果 =====")
        println("[match] $total")
        println("[match] 耗时 ${secs}s")

        val e = total.elo
        val m = total.eloMargin
        if (e != null && m != null) {
            println(if (kotlin.math.abs(e) > m)
                "[match] 结论: 差异显著, A 比 B ${if (e > 0) "强" else "弱"} ${"%.0f".format(kotlin.math.abs(e))} 分"
            else
                "[match] 结论: 差异不显著 —— 误差(±${"%.0f".format(m)})盖过了分差, 需要更多局数才能下判断")
        }
    }
}
