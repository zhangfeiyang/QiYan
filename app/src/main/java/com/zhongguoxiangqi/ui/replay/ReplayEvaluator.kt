package com.zhongguoxiangqi.ui.replay

import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.analysis.WinRateAnalyzer
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.OpeningDetector
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.Square
import com.zhongguoxiangqi.game.UciCoords
import kotlin.math.abs

enum class MoveQuality(val labelRes: Int, val symbol: String, val colorHex: Int) {
    BRILLIANT(R.string.move_quality_brilliant, "💎", 0xFF00B0FF.toInt()),
    BEST(R.string.move_quality_best, "🟢", 0xFF4CAF50.toInt()),
    INACCURACY(R.string.move_quality_inaccuracy, "🟡", 0xFFFFC107.toInt()),
    MISTAKE(R.string.move_quality_mistake, "🟠", 0xFFFF9800.toInt()),
    BLUNDER(R.string.move_quality_blunder, "🔴", 0xFFE53935.toInt()),
    BOOK(R.string.move_quality_book, "📖", 0xFF9C27B0.toInt())
}

data class MoveEvaluation(
    val stepIndex: Int,
    val uci: String,
    val chineseNotation: String,
    val redWinRate: Float,
    val scoreCp: Int,
    val quality: MoveQuality,
    val deltaWinRate: Float,
    val openingName: String? = null,
)

data class ReplayAnalysisResult(
    val evaluations: List<MoveEvaluation>,
    val winRates: FloatArray,
    val brilliantCount: Int,
    val bestCount: Int,
    val inaccuracyCount: Int,
    val mistakeCount: Int,
    val blunderCount: Int,
)

object ReplayEvaluator {

    /**
     * 快速全盘复盘分析.
     * 顺序模拟每一步，计算红方胜率、局势评估、走法质量分类与开局定式.
     */
    fun analyze(initialFen: String, uciMoves: List<String>): ReplayAnalysisResult {
        val pos = Position()
        if (initialFen.isNotBlank()) {
            pos.setFromFen(initialFen)
        } else {
            pos.setupStartPosition()
        }

        val totalSteps = uciMoves.size
        val winRates = FloatArray(totalSteps + 1)
        val evaluations = ArrayList<MoveEvaluation>(totalSteps)

        // 步骤 0: 起始局面
        val initialScore = staticEval(pos)
        winRates[0] = WinRateAnalyzer.smoothWinRate(initialScore).toFloat()

        var brilliant = 0
        var best = 0
        var inaccuracy = 0
        var mistake = 0
        var blunder = 0

        for (i in 0 until totalSteps) {
            val uci = uciMoves[i]
            val move = UciCoords.parse(uci)
            val mover = pos.sideToMove

            val notation = if (move != null && pos.isLegalMove(move)) {
                Move.toChineseString(move, pos)
            } else {
                uci
            }

            if (move != null && pos.isLegalMove(move)) {
                pos.doMove(move)
            }

            val score = staticEval(pos)
            val redWr = WinRateAnalyzer.smoothWinRate(score).toFloat()
            winRates[i + 1] = redWr

            // 计算该走子方带来的胜率变动 (站在走子方立场)
            val prevMoverWr = if (mover == Color.RED) winRates[i] else (100f - winRates[i])
            val currMoverWr = if (mover == Color.RED) redWr else (100f - redWr)
            val delta = currMoverWr - prevMoverWr

            // 开局阶段识别
            val opening = if (i < 16) OpeningDetector.detect(uciMoves.take(i + 1)) else null

            // 终局绝杀
            val isCheckmate = Rules.isCheckmate(pos)

            val quality = when {
                isCheckmate -> {
                    brilliant++
                    MoveQuality.BRILLIANT
                }
                delta >= 12f -> {
                    brilliant++
                    MoveQuality.BRILLIANT
                }
                delta <= -18f -> {
                    blunder++
                    MoveQuality.BLUNDER
                }
                delta <= -8f -> {
                    mistake++
                    MoveQuality.MISTAKE
                }
                delta <= -3f -> {
                    inaccuracy++
                    MoveQuality.INACCURACY
                }
                opening != null && i < 6 -> {
                    best++
                    MoveQuality.BOOK
                }
                else -> {
                    best++
                    MoveQuality.BEST
                }
            }

            evaluations.add(
                MoveEvaluation(
                    stepIndex = i + 1,
                    uci = uci,
                    chineseNotation = notation,
                    redWinRate = redWr,
                    scoreCp = score,
                    quality = quality,
                    deltaWinRate = delta,
                    openingName = opening,
                )
            )
        }

        return ReplayAnalysisResult(
            evaluations = evaluations,
            winRates = winRates,
            brilliantCount = brilliant,
            bestCount = best,
            inaccuracyCount = inaccuracy,
            mistakeCount = mistake,
            blunderCount = blunder,
        )
    }

    /**
     * 对任意局面进行即时估值与红方胜率计算.
     */
    fun evaluatePosition(pos: Position): Pair<Int, Float> {
        val score = staticEval(pos)
        val wr = WinRateAnalyzer.smoothWinRate(score).toFloat()
        return Pair(score, wr)
    }

    /**
     * 高速静态局面估值 (红方视角，厘兵).
     * 结合子力价值、兵过河奖励、炮镇中路、车占要道、将军惩罚与将死检测.
     */
    fun staticEval(pos: Position): Int {
        if (Rules.isCheckmate(pos)) {
            // 轮到谁走被将死，谁就大负
            return if (pos.sideToMove == Color.RED) -9999 else 9999
        }

        var redMaterial = 0
        var blackMaterial = 0

        for (sq in 0 until Square.COUNT) {
            val code = pos.squares[sq]
            if (code == Piece.NONE.code) continue
            val p = Piece.fromCode(code)
            val r = Square.row(sq)
            val c = Square.col(sq)

            val v = when (p.type) {
                1 -> 10000 // 将/帅
                5 -> 900   // 车
                6 -> {     // 炮
                    var score = 450
                    if (c == 4) score += 35 // 镇中炮
                    if (p.isRed && r <= 2) score += 20 // 沉底炮
                    if (!p.isRed && r >= 7) score += 20
                    score
                }
                4 -> {     // 马
                    var score = 400
                    // 卧槽/过河马
                    if (p.isRed && r <= 4) score += 25
                    if (!p.isRed && r >= 5) score += 25
                    score
                }
                2, 3 -> 200 // 仕 / 相
                7 -> {     // 兵/卒
                    var score = 100
                    if (p.isRed) {
                        if (r <= 4) score += 80 // 过河
                        score += (9 - r) * 10  // 逼近九宫
                    } else {
                        if (r >= 5) score += 80
                        score += r * 10
                    }
                    score
                }
                else -> 0
            }

            if (p.isRed) redMaterial += v else blackMaterial += v
        }

        var diff = redMaterial - blackMaterial

        // 被将军惩罚
        if (Rules.isInCheck(pos, Color.RED)) diff -= 140
        if (Rules.isInCheck(pos, Color.BLACK)) diff += 140

        return diff
    }
}
