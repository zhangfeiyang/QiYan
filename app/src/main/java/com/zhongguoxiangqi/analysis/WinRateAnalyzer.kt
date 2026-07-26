package com.zhongguoxiangqi.analysis

import com.zhongguoxiangqi.game.Color
import kotlin.math.ln
import kotlin.math.tanh

/**
 * 胜率分析器.
 *
 * 将引擎评估(厘兵 / mate distance)转换为胜率百分比.
 *
 * 公式:
 * - 非杀局面: WinRate = 100 / (1 + 10^(-eval/600))
 *   其中 eval 视为红方优势(厘兵)
 * - 杀局面: 必胜方胜率 = 100% (残留 0.1% 给对手)
 *
 * 参考:
 * - Lichess 象棋使用类似公式
 * - 这是从 Elo 公式反推: P = 1 / (1 + 10^(-Δ/400))
 *   1 厘兵 ≈ 1/100 兵 ≈ 6 分优势 (经验值)
 */
object WinRateAnalyzer {

    // Pikafish 内部单位到厘兵的换算. 上游 PawnValueEg ≈ 200,
    // 所以 1 兵 ≈ 200 内部单位 ≈ 100 厘兵 → 乘 0.5 换算到厘兵域.
    private const val INTERNAL_TO_CP = 0.50

    // 温度系数: 每 N 厘兵 = 一次 e 倍的胜率变化. 值越大曲线越平缓,
    // 胜率波动越小. 400 表示 ±400 cp 优势对应约 10:1 的胜率差.
    private const val SCALING = 400.0

    /**
     * 将引擎内部评估值转换为红方胜率.
     *
     * @param scorePikafish Pikafish 内部评估单位 (1 兵 ≈ 200 单位), 正值=红方优势
     * @param scoreMate 杀棋步数 (0=非杀, >0=红方 N 步杀, <0=黑方 N 步杀)
     */
    fun redWinRate(scorePikafish: Int, scoreMate: Int = 0): Double {
        if (scoreMate != 0) {
            return if (scoreMate > 0) 99.9 else 0.1
        }
        // 先把内部单位换算到厘兵域, 再用温度系数压平
        val cp = scorePikafish * INTERNAL_TO_CP
        return 100.0 / (1.0 + Math.pow(10.0, -cp / SCALING))
    }

    /** 对数平滑 —— 比直接值更适合 UI 显示 (变化更柔和) */
    fun smoothWinRate(scorePikafish: Int, scoreMate: Int = 0): Double {
        if (scoreMate != 0) {
            return if (scoreMate > 0) 100.0 else 0.0
        }
        val cp = scorePikafish * INTERNAL_TO_CP
        return tanh(cp / (SCALING * 1.8)) * 50.0 + 50.0
    }

    /**
     * 当前走子方胜率.
     */
    fun sideToMoveWinRate(scoreCp: Int, sideToMove: Int, scoreMate: Int = 0): Double {
        val rwr = redWinRate(scoreCp, scoreMate)
        return if (sideToMove == Color.RED) rwr else 100.0 - rwr
    }

    /**
     * 难度等级的平均局面评估差异(用于"难度估算").
     * 难度越低,引擎越容易犯错,用户胜率越高.
     */
    fun expectedUserWinRate(
        difficultyLevel: Int,
        playerElo: Int = 1500
    ): Double {
        // 简易估算: 难度 1-10 对应引擎Elo 600-3300
        val engineElo = when (difficultyLevel) {
            1 -> 600; 2 -> 900; 3 -> 1200; 4 -> 1500
            5 -> 1700; 6 -> 1900; 7 -> 2200; 8 -> 2500
            9 -> 2900; 10 -> 3300
            else -> 1500
        }
        // Elo 期望胜率公式
        val diff = playerElo - engineElo
        return 100.0 / (1.0 + Math.pow(10.0, -diff / 400.0))
    }

    /**
     * 将胜率转换为 Elo 评分差异.
     */
    fun winRateToEloDiff(winRate: Double): Double {
        // Elo diff = -400 * log10(1/P - 1)
        val p = (winRate / 100.0).coerceIn(0.001, 0.999)
        return -400.0 * (ln(1.0 / p - 1.0) / ln(10.0))
    }

    }

/**
 * 实时胜率快照 - 给 UI 显示用.
 */
data class WinRateSnapshot(
    /** 红方胜率 (0..100) */
    val redWinRate: Double,
    /** 黑方胜率 (0..100) */
    val blackWinRate: Double = 100.0 - redWinRate,
    /** 当前引擎评估 (厘兵) */
    val scoreCp: Int = 0,
    /** 杀棋步数(0=非杀棋) */
    val scoreMate: Int = 0,
    /** 搜索深度 */
    val depth: Int = 0,
    /** 已用时间(ms) */
    val timeMs: Long = 0,
    /** 已搜索节点数 */
    val nodes: Long = 0,
    /** NPS */
    val nps: Long = 0,
    /** 引擎名称 */
    val engineName: String = "",
    /** 主变例(走法列表,中文格式) */
    val pv: List<String> = emptyList(),
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) {
    val advantage: Double get() = redWinRate - 50.0
    val isDecisive: Boolean get() = redWinRate > 95.0 || redWinRate < 5.0
}