package com.zhongguoxiangqi.engine.nnue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NEON 求值核心的正确性. **必须在真机或模拟器上跑**:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest --tests "*NnueNativeTest*"
 * ```
 *
 * 为什么非要有这个: NEON 那段代码在开发机上没有任何办法验证 —— 手边没有
 * ARM 硬件。而 NNUE 算错不会崩溃, 只会让棋力莫名其妙地差, 是最难查的那类
 * 问题。所以正确性必须在能真正执行 NEON 指令的地方证明一遍。
 *
 * 注意在 x86 模拟器上跑时, arm64 代码是被翻译执行的: **结论只对正确性有效,
 * 速度数字没有意义**。
 */
@RunWith(AndroidJUnit4::class)
class NnueNativeTest {

    private companion object {
        // 用 Log 而不是 println: 部分机型上 println 既不进 logcat 也不进
        // instrumentation 的 stream, 数字打了等于没打
        const val TAG = "NnueBench"
    }

    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun loadNet() = assets.open("nnue/test.nnue").use { NnueNetwork.load(it) }

    @Test
    fun native库应当可用() {
        assertTrue(
            "libxqnnue 没加载起来 —— 后面的测试会静默地退回 Kotlin 实现, " +
                "看着全过其实什么都没验证",
            NnueNative.available
        )
        assertTrue("arm64 上应当有 SIMD", NnueNative.nativeHasSimd())
    }

    @Test
    fun NEON与标量路径必须逐位相同() {
        val net = loadNet()
        val h = NnueNative.nativeCreate(net.raw)
        assertTrue("native 加载网络失败", h != 0L)
        try {
            // 样本数给大一些: 这是唯一能真正执行 NEON 的场合, 不差这点时间
            val bad = NnueNative.nativeSelfCheck(h, 20_000)
            assertEquals("NEON 与标量结果不一致的条数", 0, bad)
        } finally {
            NnueNative.nativeDestroy(h)
        }
    }

    @Test
    fun native结果必须与黄金向量完全相等() {
        // 自检只证明"NEON 和标量一致", 万一两条路径错得一样就漏过去了。
        // 黄金向量的期望值出自 Python 参考实现, 是独立的第三方基准。
        val net = loadNet()
        val ev = NnueEvaluator(net)
        try {
            assertTrue(
                "没走上 native —— 那这个测试验的还是 Kotlin 实现",
                ev.usingNative
            )
            val lines = assets.open("nnue/golden.txt").bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
            assertTrue("素材太少", lines.size >= 100)

            var checked = 0
            for ((i, line) in lines.withIndex()) {
                val parts = line.split("|")
                val black = parts[0].trim() == "1"
                val pairs = parts[1].trim().split(" ").filter { it.isNotEmpty() }
                val expected = parts[2].trim().toInt()

                val pieces = IntArray(NnueFeatures.MAX_PIECES * 2)
                for ((k, p) in pairs.withIndex()) {
                    val (idx, sq) = p.split(",")
                    pieces[k * 2] = idx.toInt()
                    pieces[k * 2 + 1] = sq.toInt()
                }
                assertEquals(
                    "第 ${i + 1} 条: native 与 Python 参考实现不一致",
                    expected, ev.evaluatePieces(pieces, pairs.size, black)
                )
                checked++
            }
            android.util.Log.i(TAG, "与黄金向量比对 $checked 条, 全部相同")
        } finally {
            ev.close()
        }
    }

    @Test
    fun native与Kotlin实现必须逐位相同() {
        // 第三重保险: 同一个 evaluator 的两条路径直接对拆。
        val net = loadNet()
        val ev = NnueEvaluator(net)
        try {
            assertTrue("没走上 native", ev.usingNative)
            val rng = java.util.Random(12345)
            val pieces = IntArray(NnueFeatures.MAX_PIECES * 2)
            repeat(2000) {
                val n = 2 + rng.nextInt(NnueFeatures.MAX_PIECES - 1)
                for (i in 0 until n) {
                    pieces[i * 2] = rng.nextInt(14)
                    pieces[i * 2 + 1] = rng.nextInt(NnueFeatures.SQUARES)
                }
                val black = rng.nextBoolean()
                assertEquals(
                    "native 与 Kotlin 求值不一致 ($n 子)",
                    ev.evaluateKotlin(pieces, n, black),
                    ev.evaluatePieces(pieces, n, black)
                )
            }
        } finally {
            ev.close()
        }
    }

    /**
     * 随 App 一起发布的那个网络能不能真的被用起来.
     *
     * 前面几个测试用的是 test/resources 里的测试网络, 它只验证"算得对";
     * 这个验证的是"发布路径通不通" —— assets 里的文件在不在、NnueProvider
     * 找不找得到、走没走上 native。更新网络时只换那个文件, 这个测试就是
     * 换完之后唯一要跑的验收。
     */
    @Test
    fun 随包发布的网络必须能加载并走native() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val net = NnueProvider.get(app)
        assertTrue(
            "assets/${NnueProvider.ASSET_PATH} 没加载出来 —— " +
                "引擎会静默退回手写评估, 界面上看不出任何异常",
            net != null
        )
        val ev = NnueProvider.newEvaluator(app)!!
        try {
            assertTrue("随包网络没走上 native", ev.usingNative)
            // 开局满盘应当接近均势: 离谱的数字说明权重错位或尺度不对
            val pieces = IntArray(NnueFeatures.MAX_PIECES * 2)
            var n = 0
            for (idx in 0 until 14) {
                pieces[n * 2] = idx
                pieces[n * 2 + 1] = (n * 7) % NnueFeatures.SQUARES
                n++
            }
            val score = ev.evaluatePieces(pieces, n, false)
            android.util.Log.i(TAG, "随包网络 l1=${net!!.l1}, 样例评估 $score 厘兵")
            assertTrue("评估值离谱 ($score), 权重多半错位了",
                kotlin.math.abs(score) < 5000)
            assertEquals("native 与 Kotlin 必须一致",
                ev.evaluateKotlin(pieces, n, false), score)
        } finally {
            ev.close()
        }
    }

    @Test
    fun 求值速度() {
        val net = loadNet()
        val ev = NnueEvaluator(net)
        try {
            val pieces = IntArray(NnueFeatures.MAX_PIECES * 2)
            var n = 0
            // 摆一个满盘局面: 32 个子是累加器开销最大的情形
            for (idx in 0 until 14) {
                for (k in 0 until if (idx % 7 == 0) 1 else 2) {
                    if (n >= 32) break
                    pieces[n * 2] = idx
                    pieces[n * 2 + 1] = (n * 7) % NnueFeatures.SQUARES
                    n++
                }
            }

            // 预热要给足。ART 是解释器起步, 热到一定次数才编译; 预热不够量到的
            // 是解释执行的速度 —— 第一版这里 Kotlin 只跑了 5000 次, 量出 568
            // 次/秒, 比桌面 JVM 还慢 50 倍, 那不是手机慢, 是根本没编译。
            // 分阶段打日志: 上一次整个测试卡了 15 分钟, 而结果只在最后一行
            // 打印, 完全看不出卡在哪一段。
            var sink = 0
            android.util.Log.i(TAG, "预热 native...")
            repeat(60_000) { sink += ev.evaluatePieces(pieces, n, false) }
            val iters = 200_000
            val t0 = System.nanoTime()
            repeat(iters) { sink += ev.evaluatePieces(pieces, n, false) }
            val nativePerSec = iters / ((System.nanoTime() - t0) / 1e9)

            android.util.Log.i(TAG, "native ${nativePerSec.toLong()} 次/秒, 预热 Kotlin...")
            repeat(20_000) { sink += ev.evaluateKotlin(pieces, n, false) }
            val kIters = 20_000
            val t1 = System.nanoTime()
            repeat(kIters) { sink += ev.evaluateKotlin(pieces, n, false) }
            val kotlinPerSec = kIters / ((System.nanoTime() - t1) / 1e9)

            android.util.Log.i(TAG, "Kotlin 完成, 量手写评估...")
            // 真正要比的是它要取代的那个东西: 手写评估。
            val eng = com.zhongguoxiangqi.engine.SimpleEngine()
            val pos = com.zhongguoxiangqi.game.Position()
                .also { it.setupStartPosition() }
            val hcPerSec = 200_000 / (eng.benchmarkEval(pos, 200_000) / 1e9)

            android.util.Log.i(TAG, "手写评估 ${hcPerSec.toLong()} 次/秒")
            android.util.Log.i(TAG, "NNUE native ${nativePerSec.toLong()} 次/秒 " +
                "(慢 ${"%.1f".format(hcPerSec / nativePerSec)} 倍, 折合少搜 " +
                "${"%.1f".format(kotlin.math.ln(hcPerSec / nativePerSec) / kotlin.math.ln(3.0))} 层)")
            android.util.Log.i(TAG, "NNUE Kotlin ${kotlinPerSec.toLong()} 次/秒 " +
                "(native 比它快 ${"%.1f".format(nativePerSec / kotlinPerSec)} 倍)")
            android.util.Log.i(TAG, "sink=$sink")
        } finally {
            ev.close()
        }
    }
}
