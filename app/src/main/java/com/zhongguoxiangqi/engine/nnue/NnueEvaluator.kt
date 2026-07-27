package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.game.Position

/**
 * NNUE 整数推理. **必须与 nnue/export.py 的 int_infer 逐步对应** ——
 * 那边是训练侧的参考实现, 两边算出来不一样的话, 网络训得再好也没用.
 *
 * 每个搜索线程一个实例 (内部有可变缓冲区), 权重从 [NnueNetwork] 共享.
 *
 * 尺度约定:
 *   累加器 / 各层激活值    尺度 QA   (即 1.0 对应 127)
 *   权重                  尺度 QW   (即 1.0 对应 64)
 *   乘积                  尺度 QA*QW, 除以 QW 回到 QA, 再 clamp(0, QA)
 *   输出层不 clamp, 且要除以 QA*QW 才回到厘兵分
 *
 * 除法一律用 Kotlin 的 `/` (截断向零), Python 侧用 np.fix 对齐. 用向下取整
 * 会在负数上差 1 —— 隐藏层因为紧接着 clamp 到 0 看不出来, 输出层会直接差一分.
 */
class NnueEvaluator(private val net: NnueNetwork) {

    private val scratchPieces = IntArray(NnueFeatures.MAX_PIECES * 2)

    /** 两个视角的累加器拼在一起, 正好是 fc1 的输入 */
    private val acc = IntArray(net.l1 * 2)
    private val h1 = IntArray(net.l2)
    private val h2 = IntArray(net.l3)

    /**
     * 返回**走子方视角**的评估值, 单位厘兵. 正数表示走子方占优.
     *
     * 注意这里是每次从头重算累加器 (32 个子 × 256 维 × 2 视角). NNUE 的
     * 惯常做法是随着走子/悔棋增量更新, 只改动到的那两三个特征, 快一个数量级.
     * 先把正确性做对, 增量更新等接进搜索、测出实际瓶颈之后再做 ——
     * 增量更新一旦和 make/unmake 对不上, 错的是"累加器慢慢漂移", 比现在
     * 这种全量重算难查得多.
     */
    fun evaluate(pos: Position): Int {
        val n = NnueFeatures.extract(pos, scratchPieces)
        return evaluatePieces(scratchPieces, n, NnueFeatures.sideToMoveIsBlack(pos))
    }

    /**
     * 直接吃 (pieceIdx, sq) 列表的版本. 存在的理由是可测:
     * 训练数据的 72 字节记录里存的就是这个形式, 于是可以拿同一批记录喂给
     * Python 的 int_infer 和这里, 逐条比对输出是否**完全相同**.
     *
     * [pieces] 每子两项, 长度至少 2*[n]. 允许传入外部数组, 不做拷贝.
     */
    fun evaluatePieces(pieces: IntArray, n: Int, blackToMove: Boolean): Int {
        refresh(pieces, n, blackToMove)

        // fc1: (L2 × L1*2) @ acc
        val inDim = net.l1 * 2
        for (o in 0 until net.l2) {
            var sum = net.fc1Bias[o]
            val row = o * inDim
            for (i in 0 until inDim) {
                sum += net.fc1Weight[row + i].toInt() * acc[i]
            }
            h1[o] = clipped(sum / NnueNetwork.QW)
        }

        // fc2: (L3 × L2) @ h1
        for (o in 0 until net.l3) {
            var sum = net.fc2Bias[o]
            val row = o * net.l2
            for (i in 0 until net.l2) {
                sum += net.fc2Weight[row + i].toInt() * h1[i]
            }
            h2[o] = clipped(sum / NnueNetwork.QW)
        }

        // 输出层: h2 带着 QA 的尺度, 权重带着 QW 的尺度, 所以除 QA*QW
        var out = net.outBias
        for (i in 0 until net.l3) {
            out += net.outWeight[i].toInt() * h2[i]
        }
        return out / (NnueNetwork.QA * NnueNetwork.QW)
    }

    /**
     * 重算两个视角的累加器.
     *
     * "我方"永远是走子方: 黑走棋时把棋盘翻过来看. 网络因此只需要学
     * "我方/敌方"的概念, 不必分别学一遍红方和黑方 —— 参数量减半, 而且
     * 天然带上了红黑对称的先验.
     */
    private fun refresh(pieces: IntArray, n: Int, blackToMove: Boolean) {
        val l1 = net.l1
        for (i in 0 until l1) {
            val b = net.ftBias[i].toInt()
            acc[i] = b
            acc[l1 + i] = b
        }

        for (p in 0 until n) {
            val idx = pieces[p * 2]
            val sq = pieces[p * 2 + 1]

            // 红走棋时"我方"就是原样看棋盘, 黑走棋时两个视角对调
            val raw = NnueFeatures.ownFeature(idx, sq)
            val flipped = NnueFeatures.oppFeature(idx, sq)
            val ownRow = (if (blackToMove) flipped else raw) * l1
            val oppRow = (if (blackToMove) raw else flipped) * l1

            for (i in 0 until l1) {
                acc[i] += net.ftWeight[ownRow + i].toInt()
                acc[l1 + i] += net.ftWeight[oppRow + i].toInt()
            }
        }

        for (i in acc.indices) {
            acc[i] = clipped(acc[i])
        }
    }

    /** ClippedReLU 的定点版本 */
    private fun clipped(v: Int): Int =
        if (v < 0) 0 else if (v > NnueNetwork.QA) NnueNetwork.QA else v
}
