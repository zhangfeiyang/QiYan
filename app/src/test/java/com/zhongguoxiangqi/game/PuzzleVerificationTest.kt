package com.zhongguoxiangqi.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 残局谜题常驻校验 —— 两道保险, 防止谜题库"看着像杀其实杀不动":
 *
 * 1. [引擎强制杀校验] 每道谜题用 PikaFish 搜索(宿主机 so), 必须报出
 *    mate 优势, 且引擎 PV 在 [Puzzle.maxMoves] 步内完成 —— 保证"可解"。
 * 2. [App规则可判胜校验] 把引擎 PV 逐半步喂给 **App 自己的**
 *    Position/Rules: 每一步都必须合法, 且最后一手(红方)之后 App 规则
 *    判定对局结束(将死/困毙) —— 保证"玩家走到杀招时 App 真的会判赢"。
 *
 * 跑在宿主机 JVM 上(需要 third_party 的 pikafish.nnue 与 host_lib 的
 * libpikafish.so, 见 gradle 测试配置的 java.library.path)。
 */
class PuzzleVerificationTest {

    private val nnue = System.getProperty("pikafish.nnue")
        ?: "/home/zhangfy/zhongguoxiangqi/third_party/Pikafish/src/pikafish.nnue"

    /** 引擎 PV 的一步(半步) */
    private data class PvMove(val uci: String, val move: Move)

    @Test
    fun allPuzzlesHaveForcedMateAndAppRulesDeclareWin() {
        com.zhongguoxiangqi.engine.PikafishNative.initGlobal(nnue)
        try {
            com.zhongguoxiangqi.engine.PikafishNative.setoption("Threads", "2")
            com.zhongguoxiangqi.engine.PikafishNative.setoption("Hash", "64")
            for (puzzle in Puzzles.all) {
                verifyPuzzle(puzzle)
            }
        } finally {
            com.zhongguoxiangqi.engine.PikafishNative.shutdown()
        }
    }

    private fun verifyPuzzle(puzzle: Puzzle) {
        val context = "谜题${puzzle.id}(${fenOf(puzzle)})"

        // 1) 引擎搜索: 必须有强制杀
        val (mate, pvMoves) = enginePv(puzzle.fen)
        assertTrue("$context: 引擎未找到强制杀 (mate=$mate)", mate > 0)
        assertTrue(
            "$context: PV 为空",
            pvMoves.isNotEmpty(),
        )
        val redMovesInPv = (pvMoves.size + 1) / 2
        assertTrue(
            "$context: 引擎杀棋需要 $redMovesInPv 步, 超出谜题上限 ${puzzle.maxMoves}",
            redMovesInPv <= puzzle.maxMoves,
        )

        // 2) PV 逐半步喂给 App 的 Position/Rules
        val pos = Position()
        assertTrue("$context: FEN 解析失败", pos.setFromFen(puzzle.fen))
        assertEquals("$context: 应为红先", Color.RED, pos.sideToMove)

        var appMoveCount = 0
        for ((index, pvMove) in pvMoves.withIndex()) {
            val move = pvMove.move
            assertTrue(
                "$context: PV 第${index + 1}步 ${pvMove.uci} 在 App 规则下不合法",
                pos.isLegalMove(move),
            )
            pos.doMove(move)
            if (pos.sideToMove == Color.RED) appMoveCount++

            if (index == pvMoves.lastIndex) {
                // 最后一手是红方的杀招: App 规则必须判定对局结束
                val over = Rules.gameOver(pos)
                assertTrue(
                    "$context: PV 走完后 App 规则未判负 (over=$over)",
                    over == Rules.GameOver.CHECKMATE_BLACK || over == Rules.GameOver.STALEMATE,
                )
                assertTrue(
                    "$context: PV 杀棋步数 $appMoveCount 超出谜题上限 ${puzzle.maxMoves}",
                    appMoveCount <= puzzle.maxMoves,
                )
            }
        }
    }

    /** 引擎搜索, 返回 (mate 步数, PV 逐半步) */
    private fun enginePv(fen: String): Pair<Int, List<PvMove>> {
        com.zhongguoxiangqi.engine.PikafishNative.setposition(fen, emptyArray(), 0)
        com.zhongguoxiangqi.engine.PikafishNative.go(3000L, 0, 0)
        com.zhongguoxiangqi.engine.PikafishNative.waitForSearch()
        val buf = ByteArray(8)
        val n = com.zhongguoxiangqi.engine.PikafishNative.getBestmove(buf)
        val best = if (n > 0) String(buf, 0, n) else ""
        val info = IntArray(6)
        com.zhongguoxiangqi.engine.PikafishNative.getLastInfo(info)
        val mate = info[2]
        // getLastInfo 不带 PV; PV 从最后一条 info 的解析里拿不到 ——
        // 改为反复问引擎: 用 "走一步看一步" 的方式把 PV 重新走一遍:
        // 引擎 bestmove → App 应用 → 引擎对新局面再搜索, 直到 App 判定终局或超限。
        return if (mate > 0) mate to walkForcedLine(fen) else mate to emptyList()
    }

    /**
     * 强制杀线重放: 引擎执红时取其 bestmove, 执黑时也取其 bestmove(最佳防守),
     * 逐步走完这条"引擎自证"的杀线, 返回全部半步 (UCI + App Move)。
     */
    private fun walkForcedLine(fen: String): List<PvMove> {
        val pos = Position()
        assertTrue(pos.setFromFen(fen))
        val moves = mutableListOf<PvMove>()
        var guard = 0
        while (Rules.gameOver(pos) == Rules.GameOver.NONE && guard < 12) {
            guard++
            val uci = engineBestMove(fen, moves.map { it.uci })
            if (uci == null || uci == "0000") break
            val move = UciCoords.parse(uci)
            assertNotNull("引擎着法 $uci 无法解析", move)
            if (!pos.isLegalMove(move!!)) {
                throw AssertionError("引擎着法 $uci 在 App 规则下不合法")
            }
            pos.doMove(move)
            moves += PvMove(uci, move)
        }
        return moves
    }

    private fun engineBestMove(fen: String, played: List<String>): String? {
        com.zhongguoxiangqi.engine.PikafishNative.setposition(fen, played.toTypedArray(), played.size)
        com.zhongguoxiangqi.engine.PikafishNative.go(2000L, 0, 0)
        com.zhongguoxiangqi.engine.PikafishNative.waitForSearch()
        val buf = ByteArray(8)
        val n = com.zhongguoxiangqi.engine.PikafishNative.getBestmove(buf)
        return if (n > 0) String(buf, 0, n) else null
    }

    private fun fenOf(puzzle: Puzzle): String = puzzle.fen.split(" ").first()
}
