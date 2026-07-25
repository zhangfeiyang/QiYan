package com.zhongguoxiangqi.rating

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Glicko-2 评分系统.
 *
 * Glicko-2 是 Glicko 的改进版,由 Mark Glickman 开发,
 * 用于国际象棋、中国象棋、围棋、魔兽争霸等游戏的等级分计算.
 *
 * 相比传统 Elo 的优势:
 * - 评分偏差(RD)反映对选手真实水平的"不确定度"
 * - 评分波动率(Volatility)反映近期表现的稳定性
 * - 评分更新精度更高,数据稀疏时仍可靠
 *
 * 参考资料:
 * - http://www.glicko.net/glicko/glicko2.pdf
 */
object Glicko2 {

    /** 系统常量 */
    private const val SCALE = 173.7178      // 评分缩放因子
    private const val TAU = 0.5              // 系统常量(控制波动率变化)
    private const val PI_SQUARED = PI * PI

    /**
     * 玩家当前 Glicko-2 状态.
     */
    data class Rating(
        var rating: Double = 1500.0,   // 评分
        var rd: Double = 350.0,        // 评分偏差
        var sigma: Double = 0.06       // 波动率
    ) {
        val displayedRating: Int get() = (rating - 0).toInt().coerceAtLeast(0)
        val displayedRd: Int get() = rd.toInt()

        /** 转换为字符串(显示用) */
        fun display(): String {
            val rdInt = rd.toInt().coerceAtLeast(0)
            return "${displayedRating} ± $rdInt"
        }
    }

    /**
     * 对局结果.
     * @param opponentRating 对手评分 (Glicko-2 内部值)
     * @param opponentRd 对手 RD
     * @param score 1.0 = 胜, 0.5 = 和, 0.0 = 负
     */
    data class Match(
        val opponentRating: Double,
        val opponentRd: Double,
        val score: Double
    )

    /**
     * 批量更新玩家评分.
     *
     * @param player 玩家当前评分
     * @param matches 对局列表(可为空)
     * @param systemConstant 通常为 0.5-1.0,值越大评分变化越大
     */
    fun update(player: Rating, matches: List<Match>, systemConstant: Double = TAU) {
        if (matches.isEmpty()) {
            // 没有新对局,RD 缓慢增大
            player.rd = sqrt(player.rd * player.rd + player.sigma * player.sigma)
            return
        }

        // 1. 转换为内部评分尺度
        val mu = (player.rating - 1500.0) / SCALE
        val phi = player.rd / SCALE

        // 2. 计算 v 和 delta
        var v = 0.0
        var delta = 0.0
        for (m in matches) {
            val muJ = (m.opponentRating - 1500.0) / SCALE
            val phiJ = m.opponentRd / SCALE
            val gPhiJ = g(phiJ)
            val eVal = E(mu, muJ, phiJ)
            v += gPhiJ * gPhiJ * eVal * (1 - eVal)
            delta += gPhiJ * (m.score - eVal)
        }
        v = 1.0 / v

        // 3. 新波动率(迭代计算)
        val a = ln(player.sigma * player.sigma)
        val fA = f(a, delta * delta, v, phi, systemConstant)

        // 二分搜索
        var aLow: Double
        var aHigh: Double
        if (delta * delta > phi * phi + v) {
            aLow = a
            aHigh = a + systemConstant
        } else {
            val k = (a - systemConstant).let { x ->
                var count = 0
                var xx = x
                while (f(xx, delta * delta, v, phi, systemConstant) < 0 && count < 100) {
                    xx -= systemConstant
                    count++
                }
                xx
            }
            aLow = k
            aHigh = a
        }

        // 迭代
        var aNew = a
        var iter = 0
        while (iter < 100) {
            aNew = (aLow + aHigh) / 2
            val fNew = f(aNew, delta * delta, v, phi, systemConstant)
            if (fNew < 0) aHigh = aNew else aLow = aNew
            if (abs(aHigh - aLow) < 1e-6) break
            iter++
        }
        val newSigma = exp(aNew / 2)

        // 4. 新 phi*
        val phiStar = sqrt(phi * phi + newSigma * newSigma)

        // 5. 新 phi 和 mu
        val newPhi = 1.0 / sqrt(1.0 / (phiStar * phiStar) + 1.0 / v)
        val newMu = mu + newPhi * newPhi * delta / v

        // 6. 转换回常规尺度
        player.rating = newMu * SCALE + 1500.0
        player.rd = newPhi * SCALE
        player.sigma = newSigma

        // 限制范围
        if (player.rd < 30) player.rd = 30.0
        if (player.rd > 350) player.rd = 350.0
        if (player.rating < 0) player.rating = 0.0
        if (player.sigma < 0.01) player.sigma = 0.01
        if (player.sigma > 0.5) player.sigma = 0.5
    }

    private fun g(phi: Double): Double = 1.0 / sqrt(1.0 + 3.0 * phi * phi / PI_SQUARED)

    private fun E(mu: Double, muJ: Double, phiJ: Double): Double =
        1.0 / (1.0 + exp(-g(phiJ) * (mu - muJ)))

    private fun f(x: Double, deltaSq: Double, v: Double, phi: Double, tau: Double): Double {
        val ex = exp(x)
        val term1 = ex * (deltaSq - phi * phi - v - ex) / (2.0 * (phi * phi + v + ex).pow(2))
        val term2 = (x - ln(tau * tau)) / tau / tau
        return term1 - term2
    }

    private fun exp(x: Double): Double = kotlin.math.exp(x)
    private fun ln(x: Double): Double = kotlin.math.ln(x)
}

/**
 * 简化 Elo 评分(用于快速显示和兼容).
 */
object EloRating {
    private const val K_BASE = 32.0

    /**
     * 计算 Elo 期望胜率.
     */
    fun expectedScore(ratingA: Int, ratingB: Int): Double {
        return 1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / 400.0))
    }

    /**
     * 更新 Elo 评分.
     * @param playerRating 玩家当前评分
     * @param opponentRating 对手评分
     * @param score 实际得分 (1=胜, 0.5=和, 0=负)
     * @param kFactor K因子(基于评分的动态 K 值)
     */
    fun update(
        playerRating: Int,
        opponentRating: Int,
        score: Double,
        kFactor: Double = K_BASE
    ): Int {
        val expected = expectedScore(playerRating, opponentRating)
        val newRating = playerRating + kFactor * (score - expected)
        return newRating.toInt()
    }

    /** 基于评分的 K 因子 */
    fun dynamicK(rating: Int, gamesPlayed: Int): Double {
        return when {
            rating >= 2400 -> 16.0
            gamesPlayed < 30 -> 40.0
            else -> 32.0
        }
    }
}