package com.zhongguoxiangqi.engine.nnue

import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 训练数据生成入口. **不是测试**, 是借 JUnit 当运行器.
 *
 * 引擎代码在 Android app 模块里, 纯 JVM 模块没法依赖它, 单独为此拆模块又要
 * 动整个构建结构. 借测试任务跑是这里成本最低的可行做法.
 *
 * 用法:
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*DataGenRunner*" \
 *     -Dnnue.run=1 -Dnnue.games=20000 -Dnnue.threads=8 -Dnnue.depth=4 \
 *     -Dnnue.out=/home/zhangfy/nnue-data
 * ```
 * 默认 `nnue.run` 未设置时**直接跳过**, 免得每次跑单元测试都误触发几天的任务.
 *
 * 每个线程写自己的文件 (`shard-0.bin` ...), 训练时一起读即可 —— 多线程往
 * 同一个文件写会交错撕裂记录.
 */
class DataGenRunner {

    @Test
    fun generate() {
        if (System.getProperty("nnue.run") == null) {
            println("[gen] 未设置 -Dnnue.run=1, 跳过数据生成")
            return
        }
        val games = System.getProperty("nnue.games", "1000").toInt()
        val threads = System.getProperty("nnue.threads", "8").toInt()
        val depth = System.getProperty("nnue.depth", "4").toInt()
        val outDir = File(System.getProperty("nnue.out", "nnue-data")).apply { mkdirs() }
        val seedBase = System.getProperty("nnue.seed", "1000").toLong()

        println("[gen] 开始: games=$games threads=$threads depth=$depth " +
            "seed=$seedBase out=$outDir")
        val perThread = maxOf(1, games / threads)
        val total = AtomicLong()
        val startedAt = System.currentTimeMillis()

        val pool = Executors.newFixedThreadPool(threads)
        for (t in 0 until threads) {
            pool.submit {
                val shard = File(outDir, "shard-$t.bin")
                val gen = SelfPlayGenerator(
                    searchDepth = depth,
                    // 每个分片换一个种子, 否则八个线程生成一模一样的八份数据。
                    // 再叠一个可配的偏移: 种子写死的话, 想再生成一批"训练时
                    // 没见过"的局面(比如验证集)就只能拿到同样的棋局。
                    seed = seedBase + t * 7919L,
                )
                gen.generate(perThread, shard) { g, n ->
                    if (g % 50 == 0) {
                        val all = total.get() + n
                        val secs = (System.currentTimeMillis() - startedAt) / 1000.0
                        println("[gen] shard=$t 局=$g 本片样本=$n 全局约=$all " +
                            "速率=${if (secs > 0) (all / secs).toInt() else 0}/秒")
                    }
                }.also { total.addAndGet(it) }
            }
        }
        pool.shutdown()
        // 生成是以天计的任务, 这里给足上限, 真正的结束条件是 games 跑完
        pool.awaitTermination(30, TimeUnit.DAYS)

        val secs = (System.currentTimeMillis() - startedAt) / 1000.0
        println("[gen] 完成: 样本=${total.get()} 耗时=${"%.1f".format(secs)}s " +
            "平均=${if (secs > 0) (total.get() / secs).toInt() else 0}/秒")
        outDir.listFiles()?.sortedBy { it.name }?.forEach {
            println("[gen]   ${it.name}  ${it.length() / SelfPlayGenerator.RECORD_BYTES} 条")
        }
    }
}
