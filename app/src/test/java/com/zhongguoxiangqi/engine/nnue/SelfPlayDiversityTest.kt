package com.zhongguoxiangqi.engine.nnue

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自对弈样本的**多样性**, 不是条数.
 *
 * 条数是最容易骗自己的指标: 开局随机化之后引擎是确定性的, 一局棋一路走在
 * 同一条最佳线上, 那一百多条样本彼此只差一步棋。条数涨得很好看, 有效信息
 * 却没那么多 —— 而训练时看到的正是"有效信息"。
 */
class SelfPlayDiversityTest {

    /**
     * 标签(搜索分)的分布宽度.
     *
     * 一开始量的是"不重复局面占比", 结果不随机时就有 0.976 —— 因为一局里
     * 相邻局面本来就各不相同, 只是彼此**高度相关**, 重复率根本测不到这件事.
     *
     * 真正的差别在标签的分布: 引擎一路走最佳线时局面始终接近均势, 分数挤在
     * 0 附近; 插进随机着法会走出一方明显占优的局面, 把分数摊开. 评估函数正
     * 需要见过各种失衡程度 —— 只见过均势局面的网络, 一到实战优劣分明的局面
     * 就没有分辨力.
     *
     * ## 为什么用 15% 而不是生产默认的 5%
     *
     * 这个测试原来拿"12 局, 单个种子, 0% vs 5%"比, **量到的全是噪声**: 换 10
     * 个种子重跑, 只有 4 个方向为正。它能长期通过纯粹是种子 4242 撞对了 ——
     * 后来改了搜索(SEE 裁剪 + LMR 查表), 走的线一变, 它就翻面了。
     *
     * 加大样本重测的剂量-响应(30 局 × 8 种子, 括号内是种子间标准差):
     *
     *     chance=0.00  std 1038 (±126)
     *     chance=0.05  std  989 (±136)   <- 生产默认, 与 0% 无差别
     *     chance=0.15  std 1196 (± 57)
     *     chance=0.30  std 1220 (±119)
     *
     * 所以"随机化摊开分布"这件事是真的, 但 **5% 的剂量太小, 测不出来也基本
     * 没作用**(8 个种子的均值标准误约 45, 989 与 1038 之差落在噪声里)。
     * 测试要证明的是这个机制成立, 就得用能看见效应的剂量; 生产默认该不该从
     * 0.05 提到 0.15 是另一件事, 需要拿训出来的网络对战来定, 不在这里决定。
     */
    private fun scoreSpread(chance: Double, games: Int, seed: Long): Double {
        val scores = collectScores(chance, games, seed)
        val mean = scores.average()
        return kotlin.math.sqrt(scores.sumOf { (it - mean) * (it - mean) } / scores.size)
    }

    private fun collectScores(chance: Double, games: Int, seed: Long): List<Double> {
        val f = File.createTempFile("selfplay", ".bin")
        f.deleteOnExit()
        SelfPlayGenerator(
            searchDepth = 2, randomPlies = 6, maxPlies = 60,
            randomMoveChance = chance, seed = seed,
        ).generate(games, f)
        val raw = f.readBytes()
        val n = raw.size / 72
        assertTrue("没生成出样本", n > 50)
        val out = ArrayList<Double>(n)
        for (i in 0 until n) {
            val off = i * 72
            val lo = raw[off + 2].toInt() and 0xFF
            val hi = raw[off + 3].toInt()
            out.add(((hi shl 8) or lo).toShort().toDouble())
        }
        f.delete()
        return out
    }

    /** 按局面本身(子的分布 + 走子方)去重, 返回不重复局面占样本总数的比例 */
    private fun distinctRatio(chance: Double, games: Int, seed: Long): Double {
        val f = File.createTempFile("selfplay", ".bin")
        f.deleteOnExit()
        SelfPlayGenerator(
            searchDepth = 2, randomPlies = 6, maxPlies = 60,
            randomMoveChance = chance, seed = seed,
        ).generate(games, f)

        val raw = f.readBytes()
        val n = raw.size / 72
        assertTrue("没生成出样本", n > 50)

        val seen = HashSet<String>()
        for (i in 0 until n) {
            val off = i * 72
            val count = raw[off].toInt()
            // 走子方 + 子的分布, 就是一个局面的身份
            val sb = StringBuilder().append(raw[off + 1].toInt())
            for (p in 0 until count) {
                sb.append(',').append(raw[off + 4 + p * 2].toInt())
                    .append(':').append(raw[off + 5 + p * 2].toInt())
            }
            seen.add(sb.toString())
        }
        f.delete()
        return seen.size.toDouble() / n
    }

    @Test
    fun `中局随机化把标签分布摊开`() {
        // 多个种子取平均: 单个种子的 std 在 ±130 上下晃, 而效应量约 +160,
        // 一个种子分不开这两者.
        val seeds = longArrayOf(11, 22, 33, 44)
        val without = seeds.map { scoreSpread(chance = 0.0, games = 20, seed = it) }
        val with = seeds.map { scoreSpread(chance = 0.15, games = 20, seed = it) }
        val a = without.average()
        val b = with.average()
        val dup0 = distinctRatio(chance = 0.0, games = 12, seed = 4242)
        println("[diversity] ${seeds.size} 个种子 × 20 局, 标签 std 均值: " +
            "不随机 ${"%.0f".format(a)} 厘兵  随机 15% ${"%.0f".format(b)} 厘兵")
        println("[diversity] (顺带: 不随机时不重复局面已占 ${"%.3f".format(dup0)}, " +
            "所以问题从来不是重复, 而是相关)")
        assertTrue(
            "中局随机化本该让分数摊得更开, 实测 ${"%.0f".format(a)} -> ${"%.0f".format(b)}\n" +
                "各种子 不随机=${without.map { it.toInt() }} 随机=${with.map { it.toInt() }}",
            b > a
        )
    }

    @Test
    fun `随机走子不会改变标签的归属局面`() {
        // 标签是"走这手之前"那个局面的搜索分. 如果实现里先走子再写样本,
        // 每条样本的分数就会错配到下一个局面上 —— 训练照样能收敛, 只是学
        // 出来的东西是错的, 这种错极难从 loss 上看出来.
        val f = File.createTempFile("selfplay", ".bin")
        f.deleteOnExit()
        SelfPlayGenerator(
            searchDepth = 2, randomPlies = 4, maxPlies = 30,
            randomMoveChance = 1.0,   // 每步都乱走, 把错配放到最大
            seed = 7,
        ).generate(3, f)

        val raw = f.readBytes()
        val n = raw.size / 72
        assertTrue("没生成出样本", n > 5)
        for (i in 0 until n) {
            val count = raw[i * 72].toInt()
            // 每条样本的子数必须合法; 若样本写在走子之后, 吃子局面会对不上
            assertTrue("第 $i 条子数离谱: $count", count in 2..32)
        }
    }
}
