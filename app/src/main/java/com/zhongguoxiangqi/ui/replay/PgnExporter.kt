package com.zhongguoxiangqi.ui.replay

import com.zhongguoxiangqi.data.GameRecord
import com.zhongguoxiangqi.game.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 象棋棋谱导出器 (标准 PGN、FEN 导出与战报生成).
 */
object PgnExporter {

    private val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    /**
     * 导出为标准中国象棋 PGN 格式文本.
     */
    fun exportPgn(
        record: GameRecord,
        chineseMoves: List<String>,
        openingName: String? = null,
    ): String {
        val dateStr = dateFormat.format(Date(record.startTime))
        val resultStr = when (record.result) {
            1 -> if (record.playerSide == Color.RED) "1-0" else "0-1"
            -1 -> if (record.playerSide == Color.RED) "0-1" else "1-0"
            else -> "1/2-1/2"
        }

        val redPlayer = if (record.playerSide == Color.RED) "玩家" else "引擎 (Lv.${record.difficulty})"
        val blackPlayer = if (record.playerSide == Color.BLACK) "玩家" else "引擎 (Lv.${record.difficulty})"

        val sb = StringBuilder()
        sb.appendLine("[Game \"Chinese Chess\"]")
        sb.appendLine("[Event \"杞言中国象棋对弈\"]")
        sb.appendLine("[Date \"$dateStr\"]")
        sb.appendLine("[Red \"$redPlayer\"]")
        sb.appendLine("[Black \"$blackPlayer\"]")
        sb.appendLine("[Result \"$resultStr\"]")
        if (!openingName.isNullOrBlank()) {
            sb.appendLine("[Opening \"$openingName\"]")
        }
        if (record.initialFen.isNotBlank()) {
            sb.appendLine("[FEN \"${record.initialFen}\"]")
        }
        sb.appendLine()

        // 每两步一个回合号 (1. 炮二平五 马8进7  2. 马二进三 车9平8 ...)
        var turn = 1
        for (i in chineseMoves.indices) {
            if (i % 2 == 0) {
                sb.append("${turn}. ")
            }
            sb.append(chineseMoves[i]).append("  ")
            if (i % 2 == 1) {
                turn++
                if (turn % 4 == 1) sb.appendLine()
            }
        }
        sb.append(resultStr).appendLine()

        return sb.toString()
    }

    /**
     * 导出为朋友圈/微信好友分享战报.
     */
    fun exportShareText(
        record: GameRecord,
        chineseMoves: List<String>,
        openingName: String? = null,
    ): String {
        val resultDesc = when (record.result) {
            1 -> "大获全胜 🏆"
            -1 -> "遗憾惜败 ⚔️"
            else -> "握手言和 🤝"
        }
        val openingDesc = openingName?.let { "【$it】" } ?: "常规开局"

        val sb = StringBuilder()
        sb.appendLine("♟️【杞言中国象棋 · 对局战报】")
        sb.appendLine("时间: ${timeFormat.format(Date(record.startTime))}")
        sb.appendLine("模式: 引擎 Lv.${record.difficulty} (Elo ${record.engineElo})")
        sb.appendLine("战况: $resultDesc  $openingDesc")
        sb.appendLine("回合: 共 ${chineseMoves.size} 步")
        sb.appendLine("-------------------")
        // 展示前 8 步核心招法
        val previewCount = minOf(chineseMoves.size, 8)
        var turn = 1
        for (i in 0 until previewCount) {
            if (i % 2 == 0) sb.append("${turn}. ")
            sb.append(chineseMoves[i]).append(" ")
            if (i % 2 == 1) {
                turn++
                sb.appendLine()
            }
        }
        if (chineseMoves.size > 8) {
            sb.appendLine("... 更多对局进程请载入完整棋谱")
        }
        return sb.toString().trimEnd()
    }
}
