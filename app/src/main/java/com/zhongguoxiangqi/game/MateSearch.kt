package com.zhongguoxiangqi.game

/**
 * 浅层杀棋判断, 不依赖引擎.
 *
 * 只认“红方一步将死”: 当前黑方未被将军, 红方有一步合法着法使黑方被将死.
 * 困毙不算. 用来校验千题库, 避免每题都跑一遍 Pikafish.
 */
object MateSearch {

    fun matingMove(pos: Position): Move? {
        if (Rules.isInCheck(pos, Color.RED) || Rules.isInCheck(pos, Color.BLACK)) return null
        val moves = ArrayList<Move>(48)
        val replies = ArrayList<Move>(48)
        Rules.generateAllLegal(pos, moves)
        for (move in moves) {
            val target = Piece.fromCode(pos.pieceAt(move.to))
            if (target.type == 1) continue
            if (!pos.doMove(move)) continue
            val mate = pos.sideToMove == Color.BLACK &&
                Rules.isInCheck(pos, Color.BLACK) &&
                run {
                    replies.clear()
                    Rules.generateAllLegal(pos, replies)
                    replies.isEmpty()
                }
            pos.undoMove()
            if (mate) return move
        }
        return null
    }

    fun isMateInOne(pos: Position): Boolean = matingMove(pos) != null
}
