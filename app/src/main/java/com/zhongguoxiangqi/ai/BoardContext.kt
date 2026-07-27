package com.zhongguoxiangqi.ai

import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.UciCoords

/**
 * 把"当前棋局"序列化成给 LLM 看的文字上下文.
 *
 * 拼出来的字符串会作为 system prompt 的 **当前棋局** 段塞进去. 形如:
 * ```
 * 起始 FEN: rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/...
 * 全走法 (23 步): h2e2 h7e7 c0d2 c9d2 ...
 * 当前局面: rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/...
 * 玩家执红, 当前轮到黑方
 * 难度 6 级 (约 1700 分)
 * ```
 *
 * 注意:
 * - 起始 FEN 走 `initialFen` 参数传入, 复盘用 record.initialFen, 对弈用
 *   Position().toFen().
 * - 全走法用 UCI 列表, 空格分隔. 顺序: 红先黑后交替. 棋眼 store 的
 *   [com.zhongguoxiangqi.game.Position.doMove] 一次一次, moveHistory 顺序就是
 *   走过顺序.
 */
object BoardContext {

    fun build(
        initialFen: String,
        currentPosition: Position,
        moveHistory: List<Move>,
        playerSide: Int,
        difficulty: Int,
        engineElo: Int,
    ): String = buildString {
        append("起始 FEN: ").append(initialFen).append('\n')

        if (moveHistory.isNotEmpty()) {
            val uciList = moveHistory.joinToString(" ") { UciCoords.toUci(it) }
            append("全走法 (").append(moveHistory.size).append(" 步): ")
                .append(uciList).append('\n')
        } else {
            append("全走法: 尚未走子\n")
        }

        append("当前局面: ").append(currentPosition.toFen()).append('\n')
        append("玩家执").append(if (playerSide == 1) "红" else "黑")
            .append(", 当前轮到")
            .append(if (currentPosition.sideToMove == 1) "红" else "黑")
            .append("方\n")
        append("难度 ").append(difficulty).append(" 级 (约 ").append(engineElo).append(" 分)")
    }
}
