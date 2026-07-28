package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * 自对弈训练数据生成器.
 *
 * 数据全部由**本项目自己的引擎**产生, 不接触任何 GPL 引擎的输出.
 *
 * 核心取巧: 自对弈时每走一步**本来就要搜索**, 那个搜索分数直接就是标签,
 * 不需要再单独深搜一遍. 一局一百来步就是一百来条样本, 成本翻倍变零.
 *
 * 三个影响数据质量的设计:
 *
 * 1. **开局随机化**. 前 [randomPlies] 步随机走, 否则每局开头都一样, 生成
 *    一百万局也只是把同一批开局局面抄了一百万遍.
 * 2. **中局偶尔乱走** ([randomMoveChance]). 开局随机之后引擎是确定性的,
 *    一局棋会一路走在同一条最佳线上 —— 那一百多条样本彼此只差一步棋,
 *    高度相关, 条数看着多, 有效信息远没那么多. 每步以小概率插一手随机着法,
 *    能把局面推到最佳线之外, 顺便覆盖到"引擎自己不会走进去"的局面.
 *    注意标签不受影响: 分数是**走这手之前**那个局面的搜索分, 后面走什么
 *    都不改变它的正确性.
 * 3. **跳过将军局面**. 被将时的分数被战术主导, 静态评估学不到东西, 反而
 *    污染回归目标.
 * 4. **分数统一为走子方视角**. 和引擎内部 negamax 约定一致, 训练侧不用再猜。
 *
 * ## 打标签用手写评估还是用自己训过的 NNUE?
 *
 * 默认(teacherNet=null)是手写评估: 蒸馏的天花板就是"手写评估 + 搜索"能
 * 表达的上限, 深度加到再深也只是把同一个老师的分打得更准, 不会学到手写
 * 评估本身没有的模式。这是刻意的第一步——用一个已知靠谱的老师验证整条
 * 流水线(特征提取/量化/训练)没有编码错误, gen0 vs 手写评估同深度打平就
 * 是这一步的验收。
 *
 * 流水线验证过之后, 传入 [teacherNet] 换成用自己训出的 NNUE 打分, 就是
 * 自我引导(bootstrap): 新数据里的分数带着网络自己学到的模式而不只是子力
 * +PST, 才有可能突破手写评估这个天花板。代价是单元测试跑在桌面 JVM 上
 * 加载不到 arm64 的 .so, NNUE 求值会退到纯 Kotlin 路径(~55k 次/秒, 比手写
 * 评估在 JVM 上的 1.7~5.7M 次/秒慢一到两个数量级), 所以自我引导这条路
 * 通常要配一个更浅的搜索深度, 不然生成速度会垮掉。
 */
class SelfPlayGenerator(
    private val searchDepth: Int = 5,
    private val randomPlies: Int = 8,
    private val maxPlies: Int = 200,
    private val randomMoveChance: Double = 0.05,
    private val seed: Long = System.nanoTime(),
    /** 非空则用这个网络当打分老师, 而不是手写评估. 见上面的类注释。 */
    private val teacherNet: NnueNetwork? = null,
) {

    private val rng = Random(seed)
    private val pieceBuf = IntArray(NnueFeatures.MAX_PIECES * 2)

    /** 一条样本 72 字节, 定长便于训练侧直接 reshape */
    private val record = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * 生成 [games] 局, 追加写入 [out].
     * @return 实际写出的样本条数
     */
    fun generate(games: Int, out: File, onProgress: (Int, Long) -> Unit = { _, _ -> }): Long {
        var written = 0L
        BufferedOutputStream(FileOutputStream(out, true), BUFFER_BYTES).use { sink ->
            repeat(games) { g ->
                written += playOneGame(sink)
                // 定期落盘. 这是个跑几天的任务, 全靠缓冲区自然写满的话:
                // 一是异常中断会丢掉整个缓冲区的数据, 二是文件大小长时间
                // 停在 0, 没法从外部判断到底还在不在干活.
                if ((g + 1) % FLUSH_EVERY_GAMES == 0) sink.flush()
                onProgress(g + 1, written)
            }
        }
        return written
    }

    private fun playOneGame(sink: java.io.OutputStream): Long {
        val engine = com.zhongguoxiangqi.engine.SimpleEngine()
        engine.start()
        // 每局一个新引擎实例, 这里配一个新 evaluator: NnueEvaluator 内部有
        // 可变缓冲, 不能跨线程/跨局共享, 但底下的权重(teacherNet)本身只读,
        // 多局共用同一份没问题.
        engine.setNnue(teacherNet?.let { NnueEvaluator(it) })
        val pos = Position().also { it.setupStartPosition() }
        val legal = mutableListOf<Move>()
        var samples = 0L

        for (ply in 0 until maxPlies) {
            legal.clear()
            Rules.generateAllLegal(pos, legal)
            if (legal.isEmpty()) break            // 将死或困毙, 本局结束

            val move: Move
            if (ply < randomPlies) {
                // 开局随机: 只为把局面撒开, 这几步不产出样本(分数没意义)
                move = legal[rng.nextInt(legal.size)]
            } else {
                engine.setPositionDirect(pos)
                val scored = engine.searchWithScore(searchDepth)
                move = scored.first
                // Move 没有 isEmpty(), 空着法就是 data==0 (见 Move.empty())
                if (move.data == 0) break

                // 将军中的局面不要: 分数由战术主导, 静态评估从中学不到规律
                if (!Rules.isInCheck(pos, pos.sideToMove)) {
                    writeSample(sink, pos, scored.second)
                    samples++
                }
            }

            // 样本已经写完再决定走哪一手 —— 标签属于当前局面, 与后面走什么无关
            val played = if (ply >= randomPlies && rng.nextDouble() < randomMoveChance) {
                legal[rng.nextInt(legal.size)]
            } else {
                move
            }
            pos.doMove(played)
        }
        engine.shutdown()
        return samples
    }

    private fun writeSample(sink: java.io.OutputStream, pos: Position, score: Int) {
        val n = NnueFeatures.extract(pos, pieceBuf)
        record.clear()
        record.put(n.toByte())
        record.put(if (NnueFeatures.sideToMoveIsBlack(pos)) 1 else 0)
        record.putShort(score.coerceIn(-SCORE_CLAMP, SCORE_CLAMP).toShort())
        for (i in 0 until NnueFeatures.MAX_PIECES) {
            if (i < n) {
                record.put(pieceBuf[i * 2].toByte())
                record.put(pieceBuf[i * 2 + 1].toByte())
            } else {
                record.put(0); record.put(0)
            }
        }
        record.putShort(searchDepth.toShort())
        record.putShort(0)                       // 保留位
        sink.write(record.array(), 0, RECORD_BYTES)
    }

    companion object {
        const val RECORD_BYTES = 72
        /** 64KB 就够: 再大只是延后落盘, 换不来吞吐 */
        const val BUFFER_BYTES = 64 * 1024
        /** 每这么多局强制落盘一次, 约合几十秒一次 */
        const val FLUSH_EVERY_GAMES = 5
        /** 杀棋分是 ±30000 量级, 会把回归目标的尺度带偏, 统一压到边界 */
        const val SCORE_CLAMP = 10_000
    }
}
