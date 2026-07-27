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

    private fun loadNet(): NnueNetwork {
        val path = System.getProperty("nnue.net")
            ?: error("需要 -Dnnue.net=/path/to/x.nnue")
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

    @Test
    fun nnueVsHandcrafted() {
        if (System.getProperty("nnue.match") == null) {
            println("[nnue-match] 未设置 -Dnnue.match=1, 跳过")
            return
        }
        val net = loadNet()
        val pairs = System.getProperty("match.pairs", "100").toInt()
        val depth = System.getProperty("match.depth", "4").toInt()

        println("[nnue-match] l1=${net.l1}  固定深度 $depth  开局 $pairs 对 " +
            "(${pairs * 2} 局)")

        val t0 = System.currentTimeMillis()
        val r = MatchRunner.play(
            engine(net, depth), engine(null, depth),
            pairs = pairs, seed = 20260727L
        )
        val secs = (System.currentTimeMillis() - t0) / 1000

        println("[nnue-match] $r   耗时 ${secs}s")
        val e = r.elo
        val m = r.eloMargin
        if (e != null && m != null) {
            println("[nnue-match] Elo ${"%.0f".format(e)} ± ${"%.0f".format(m)}")
            println(
                if (kotlin.math.abs(e) > m)
                    "[nnue-match] 结论: 同深度下网络比手写评估" +
                        (if (e > 0) "强" else "弱") +
                        " ${"%.0f".format(kotlin.math.abs(e))} 分 " +
                        "—— 但这没算它慢的代价, 还要看固定用时的结果"
                else
                    "[nnue-match] 结论: 差异不显著 (误差 ±${"%.0f".format(m)} 盖过分差), " +
                        "需要更多局数"
            )
        }
    }
}
