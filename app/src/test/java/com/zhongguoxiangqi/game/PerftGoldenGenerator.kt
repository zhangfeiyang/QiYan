package com.zhongguoxiangqi.game

import org.junit.Test

/**
 * 不是断言型测试, 是"金标准"生成器: 把 Kotlin 走法生成器在几个局面上的 perft
 * (逐层合法着法数)打印出来, 供 C++ 移植版(app/src/main/cpp/perft_test.cpp)
 * 硬编码同一批数字做交叉验证。
 *
 * Kotlin 引擎已经在真实对局和 SeeTest/MoveGeneratorTest 里跑了很久, 拿它当
 * ground truth 比凭空断言"某局面应该有 N 个着法"更可信 —— 后者本身就可能错。
 *
 * 用法: ./gradlew :app:testDebugUnitTest --tests "*PerftGoldenGenerator*" 2>&1 | grep PERFT
 */
class PerftGoldenGenerator {

    private fun perft(pos: Position, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)
        if (depth == 1) return moves.size.toLong()
        var nodes = 0L
        for (m in moves) {
            pos.doMove(m)
            nodes += perft(pos, depth - 1)
            pos.undoMove()
        }
        return nodes
    }

    @Test
    fun printGolden() {
        // (标签, FEN 或 null=起始局面, 最大深度)
        val cases = listOf(
            Triple("start", null, 4),
            // 中局: 复用 Depth3TimingProbe 的开局着法序列走到底, 双方车马炮
            // 都已出动, 覆盖更多分支类型(过河兵横走、炮隔子吃等)。
            Triple("midgame", "opening:b2e2 h9g7 g3g4 c6c5 b0c2 b7d7 h0g2 d7d2", 3),
            // 残局: 子力稀薄, 覆盖"空着裁剪场景"附近的着法生成
            Triple("endgame", "4k4/9/9/9/9/9/9/4C4/4R4/4K4 w - - 0 1", 4),
        )

        for ((label, spec, maxDepth) in cases) {
            val pos = Position()
            when {
                spec == null -> pos.setupStartPosition()
                spec.startsWith("opening:") -> {
                    pos.setupStartPosition()
                    for (uci in spec.removePrefix("opening:").split(" ")) {
                        val mv = UciCoords.parse(uci) ?: error("非法着法 $uci")
                        check(pos.doMove(mv)) { "着法失败 $uci" }
                    }
                }
                else -> check(pos.setFromFen(spec)) { "FEN 解析失败 $spec" }
            }
            println("[PERFT] fen=${pos.toFen()}")
            for (d in 1..maxDepth) {
                val posCopy = pos.copy()
                val nodes = perft(posCopy, d)
                println("[PERFT] $label depth=$d nodes=$nodes")
            }
        }
    }
}
