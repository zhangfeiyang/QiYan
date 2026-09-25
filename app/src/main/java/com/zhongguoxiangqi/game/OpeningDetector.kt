package com.zhongguoxiangqi.game

/**
 * 经典象棋开局定式检测器.
 *
 * 识别中国象棋常见经典开局与演变定式:
 * - 中炮类: 当头炮、中炮对屏风马、顺手炮、列手炮、中炮对反宫马、中炮对单提马、中炮对左三步虎
 * - 兵局类: 仙人指路、仙人指路对卒底炮、仙人指路互进卒、仙人指路对中炮、仙人指路对进马
 * - 相局类: 飞相局、飞相局对左中炮、飞相局对进马、飞相局对士角炮
 * - 马局类: 起马局、起马局对挺卒、起马局对中炮、边马局
 * - 特种局: 过宫炮、士角炮、金钩炮、铁滑车、边兵局
 */
object OpeningDetector {

    private data class OpeningLine(
        val name: String,
        val prefix: List<String>,
    )

    private val lines: List<OpeningLine> = listOf(
        // === 中炮对屏风马 ===
        OpeningLine("中炮对屏风马", listOf("h2e2", "h9g7", "h0g2", "i9h9", "i0h0")),
        OpeningLine("中炮对屏风马", listOf("h2e2", "h9g7", "h0g2", "i9h9")),
        OpeningLine("中炮对屏风马", listOf("h2e2", "h9g7")),
        OpeningLine("中炮对屏风马", listOf("b2e2", "b9c7", "b0c2", "a9b9", "a0b0")),
        OpeningLine("中炮对屏风马", listOf("b2e2", "b9c7", "b0c2", "a9b9")),
        OpeningLine("中炮对屏风马", listOf("b2e2", "b9c7")),

        // === 顺手炮 ===
        OpeningLine("顺手炮", listOf("h2e2", "h7e7", "h0g2", "b9c7")),
        OpeningLine("顺手炮", listOf("h2e2", "h7e7")),
        OpeningLine("顺手炮", listOf("b2e2", "b7e7")),

        // === 列手炮 ===
        OpeningLine("列手炮", listOf("h2e2", "b7e7")),
        OpeningLine("列手炮", listOf("b2e2", "h7e7")),

        // === 中炮对反宫马 ===
        OpeningLine("中炮对反宫马", listOf("h2e2", "b9c7", "h0g2", "h7f7")),
        OpeningLine("中炮对反宫马", listOf("h2e2", "h9g7", "b0c2", "b7d7")),

        // === 中炮对单提马 ===
        OpeningLine("中炮对单提马", listOf("h2e2", "b9c7", "h0g2", "h9i7")),
        OpeningLine("中炮对单提马", listOf("h2e2", "h9g7", "h0g2", "b9a7")),

        // === 中炮对三步虎 ===
        OpeningLine("中炮对三步虎", listOf("h2e2", "i9h9", "h0g2", "h9g7")),

        // === 仙人指路 (兵局) ===
        OpeningLine("仙人指路对卒底炮", listOf("c3c4", "b7c7")),
        OpeningLine("仙人指路对卒底炮", listOf("c3c4", "h7g7")),
        OpeningLine("仙人指路对卒底炮", listOf("g3g4", "h7g7")),
        OpeningLine("仙人指路对卒底炮", listOf("g3g4", "b7c7")),
        OpeningLine("仙人指路互进卒", listOf("c3c4", "c6c5")),
        OpeningLine("仙人指路互进卒", listOf("g3g4", "g6g5")),
        OpeningLine("仙人指路对中炮", listOf("c3c4", "h7e7")),
        OpeningLine("仙人指路对中炮", listOf("c3c4", "b7e7")),
        OpeningLine("仙人指路对进马", listOf("c3c4", "h9g7")),
        OpeningLine("仙人指路对进马", listOf("g3g4", "b9c7")),
        OpeningLine("仙人指路对飞相", listOf("c3c4", "g9e7")),

        // === 飞相局 ===
        OpeningLine("飞相局对左中炮", listOf("g0e2", "h7e7")),
        OpeningLine("飞相局对右中炮", listOf("c0e2", "b7e7")),
        OpeningLine("飞相局对进马", listOf("g0e2", "h9g7")),
        OpeningLine("飞相局对进马", listOf("c0e2", "b9c7")),
        OpeningLine("飞相局对进卒", listOf("g0e2", "g6g5")),
        OpeningLine("飞相局对进卒", listOf("c0e2", "c6c5")),
        OpeningLine("飞相局对士角炮", listOf("g0e2", "h7f7")),
        OpeningLine("飞相局对过宫炮", listOf("g0e2", "h7d7")),

        // === 起马局 ===
        OpeningLine("起马局对挺卒", listOf("h0g2", "c6c5")),
        OpeningLine("起马局对挺卒", listOf("b0c2", "g6g5")),
        OpeningLine("起马局对中炮", listOf("h0g2", "h7e7")),
        OpeningLine("起马局对中炮", listOf("b0c2", "b7e7")),
        OpeningLine("起马局对进马", listOf("h0g2", "h9g7")),
        OpeningLine("起马局对进马", listOf("b0c2", "b9c7")),

        // === 第一步基础定型 ===
        OpeningLine("当头炮", listOf("h2e2")),
        OpeningLine("当头炮", listOf("b2e2")),
        OpeningLine("仙人指路", listOf("c3c4")),
        OpeningLine("仙人指路", listOf("g3g4")),
        OpeningLine("飞相局", listOf("g0e2")),
        OpeningLine("飞相局", listOf("c0e2")),
        OpeningLine("起马局", listOf("h0g2")),
        OpeningLine("起马局", listOf("b0c2")),
        OpeningLine("过宫炮", listOf("h2d2")),
        OpeningLine("过宫炮", listOf("b2f2")),
        OpeningLine("士角炮", listOf("h2f2")),
        OpeningLine("士角炮", listOf("b2d2")),
        OpeningLine("金钩炮", listOf("h2g2")),
        OpeningLine("金钩炮", listOf("b2c2")),
        OpeningLine("铁滑车", listOf("i0i1")),
        OpeningLine("铁滑车", listOf("a0a1")),
        OpeningLine("边马局", listOf("h0i2")),
        OpeningLine("边马局", listOf("b0a2")),
        OpeningLine("边兵局", listOf("a3a4")),
        OpeningLine("边兵局", listOf("i3i4")),
    )

    /**
     * 根据当前所走 UCI 招法序列检测开局定式.
     * @param uciMoves 招法列表 (按顺序排列)
     * @return 识别出的最精准开局名称，无法识别则返回 null
     */
    fun detect(uciMoves: List<String>): String? {
        if (uciMoves.isEmpty()) return null
        var bestMatch: String? = null
        var maxLen = 0

        for (line in lines) {
            val prefix = line.prefix
            if (prefix.size <= uciMoves.size && prefix.size > maxLen) {
                var matched = true
                for (i in prefix.indices) {
                    if (uciMoves[i] != prefix[i]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    bestMatch = line.name
                    maxLen = prefix.size
                }
            }
        }
        return bestMatch
    }
}
