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

    private const val SCALING = 600.0  // 厘兵: 每 600 厘兵 = 100% 优势

    /**
     * 将评估值(厘兵,从红方角度)转换为红方胜率.
     * @param scoreCp 厘兵评估值,正值=红方优势
     * @param scoreMate 杀棋步数(0=非杀棋,>0=红方N步杀,<0=黑方N步杀)
     */
    fun redWinRate(scoreCp: Int, scoreMate: Int = 0): Double {
        if (scoreMate != 0) {
            return if (scoreMate > 0) 99.9 else 0.1
        }
        return 100.0 / (1.0 + Math.pow(10.0, -scoreCp / SCALING))
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

    /** 用于UI: 平滑动画用的对数缩放 */
    fun smoothWinRate(rawCp: Int): Double {
        return tanh(rawCp / 400.0) * 50.0 + 50.0
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