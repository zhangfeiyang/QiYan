package com.zhongguoxiangqi.engine.eval

import com.zhongguoxiangqi.engine.SimpleEngine
import com.zhongguoxiangqi.engine.nnue.NnueEvaluator
import com.zhongguoxiangqi.engine.nnue.NnueNetwork
import com.zhongguoxiangqi.game.Position
import org.junit.Test
import java.io.FileInputStream

/**
 * NNUE 网络 vs 手写评估. 默认跳过.
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*NnueGauntletRunner*" --info \
 *     -Dnnue.match=1 -Dnnue.net=/tmp/real256.nnue -Dmatch.pairs=100 -Dmatch.depth=4
 * ```
 *
 * 这是"网络到底值不值"的唯一裁判. 两种口径都要看:
 *
 *   固定深度 —— 回答"评估是不是更准". 对 NNUE 有利, 因为不计它慢的代价.
 *   固定用时 —— 回答"值不值得装". 网络求值比手写慢一到两个数量级, 同样
 *               时间里能搜的层数少好几层, 这才是端上真实的处境.
 *
 * 只看固定深度赢了就上网络, 是这类项目最常见的自欺.
 */
class NnueGauntletRunner {

    private fun loadNet(prop: String): NnueNetwork? {
        val path = System.getProperty(prop) ?: return null
        return FileInputStream(path).use { NnueNetwork.load(it) }
    }

    private fun engine(net: NnueNetwork?, depth: Int) = MatchRunner.EngineFactory {
        val e = SimpleEngine()
        e.start()
        // 每个引擎实例一个 evaluator: 内部有可变缓冲, 多线程共用会互相踩
        e.setNnue(net?.let { NnueEvaluator(it) })
        MatchRunner.Engine { pos: Position ->
            e.setPositionDirect(pos)
            e.searchWithScore(depth).first
        }
    }

    /**
     * A vs B 对打。
     *
     *   -Dnnue.net=<A>            A 装这个网络
     *   -Dnnue.netB=<B>           B 装这个网络; 不给就是"不装网络"(手写评估)
     *
     * 换代验收要用的是 **新网络 vs 线上网络**, 而不是新网络 vs 手写评估 ——
     * 后者只能说明"网络有用", 说明不了"这一代比上一代强"。
     */
    @Test
    fun nnueVsHandcrafted() {
        if (System.getProperty("nnue.match") == null) {
            println("[nnue-match] 未设置 -Dnnue.match=1, 跳过")
            return
        }
        val net = loadNet("nnue.net") ?: error("需要 -Dnnue.net=/path/to/x.nnue")
        val netB = loadNet("nnue.netB")
        val pairs = System.getProperty("match.pairs", "100").toInt()
        val depth = System.getProperty("match.depth", "4").toInt()

        println("[nnue-match] A=NNUE(l1=${net.l1})  " +
            "B=${netB?.let { "NNUE(l1=${it.l1})" } ?: "手写评估"}  " +
            "固定深度 $depth  开局 $pairs 对 (${pairs * 2} 局)")

        // 按开局对分片给多个线程。每对内部的两局必须留在同一批 —— 拆开就
        // 失去了配对消除先手优势的意义。
        //
        // 之前这里是单线程的: 480 局要跑 53 分钟, 慢到没人愿意跑够局数,
        // 于是每次都拿 120 局的 ±31 分误差去判断几十分的差异, 等于白测。
        val threads = System.getProperty("match.threads", "8").toInt()
        val t0 = System.currentTimeMillis()
        val results = java.util.Collections.synchronizedList(mutableListOf<MatchRunner.Result>())
        val done = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val chunk = maxOf(1, pairs / threads)
        var assigned = 0
        var shard = 0
        while (assigned < pairs) {
            val nPairs = minOf(chunk, pairs - assigned)
            val seed = 20260727L + shard * 104729L
            pool.submit {
                results.add(
                    MatchRunner.play(
                        engine(net, depth), engine(netB, depth),
                        pairs = nPairs, seed = seed
                    ) { _, _ ->
                        val d = done.incrementAndGet()
                        if (d % 20 == 0) {
                            val el = (System.currentTimeMillis() - t0) / 1000
                            println("[nnue-match] 进度 $d/$pairs 对  用时 ${el}s")
                        }
                    }
                )
            }
            assigned += nPairs
            shard++
        }
        pool.shutdown()
        pool.awaitTermination(12, java.util.concurrent.TimeUnit.HOURS)
        val r = results.fold(MatchRunner.Result(0, 0, 0)) { acc, x ->
            MatchRunner.Result(acc.wins + x.wins, acc.losses + x.losses, acc.draws + x.draws)
        }
        val secs = (System.currentTimeMillis() - t0) / 1000

        println("[nnue-match] $r   耗时 ${secs}s")
        val e = r.elo
        val m = r.eloMargin
        if (e != null && m != null) {
            println("[nnue-match] Elo ${"%.0f".format(e)} ± ${"%.0f".format(m)}")
            println(
                if (kotlin.math.abs(e) > m)
                    "[nnue-match] 结论: 同深度下 A 比 B" +
                        (if (e > 0) "强" else "弱") +
                        " ${"%.0f".format(kotlin.math.abs(e))} 分"
                else
                    "[nnue-match] 结论: 差异不显著 (误差 ±${"%.0f".format(m)} 盖过分差), " +
                        "需要更多局数"
            )
        }
    }
}
