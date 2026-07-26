package com.zhongguoxiangqi.game

/**
 * 规则判定.
 *
 * 主要职责:
 * - 检测将军 (isInCheck)
 * - 检测困毙 (no legal moves but not in check)
 * - 检测将死 (in check + no legal moves)
 * - 检测长将/重复 (handled elsewhere)
 */
object Rules {

    /** 是否处于被将军状态 */
    fun isInCheck(pos: Position, color: Int): Boolean {
        // 找到本方将/帅
        var kingSq = -1
        val kingCode = if (color == Color.RED) Piece.R_KING.code else Piece.B_KING.code
        for (sq in 0 until Square.COUNT) {
            if (pos.squares[sq] == kingCode) {
                kingSq = sq
                break
            }
        }
        if (kingSq == -1) return false  // 没找到(异常)

        val oppColor = Color.opposite(color)

        // 1. 飞行将军: 同列两将之间无子
        if (isFlyingGeneral(pos, kingSq)) return true

        // 2. 检测对方车/炮/兵是否能直接吃掉本方将
        val kr = Square.row(kingSq)
        val kc = Square.col(kingSq)

        // 车/炮沿四条直线
        val lineDirs = arrayOf(
            intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1)
        )
        for (dir in lineDirs) {
            var step = 1
            var screened = false  // 是否已越过炮架
            while (true) {
                val toR = kr + dir[0] * step
                val toC = kc + dir[1] * step
                if (toR !in 0 until Square.ROWS || toC !in 0 until Square.COLS) break
                step++
                val p = pos.squares[Square.make(toR, toC)]
                if (p == Piece.NONE.code) continue
                val pt = Piece.fromCode(p)
                if (!screened) {
                    // 第一个挡路的子: 只有对方的车能直接吃将
                    if (pt.color == oppColor && pt.type == 5) return true
                    // 炮架不分敌我 —— 自己的子同样能被对方的炮借用
                    screened = true
                } else {
                    // 炮架之后的第一个子: 只有对方的炮能隔子打将
                    if (pt.color == oppColor && pt.type == 6) return true
                    break
                }
            }
        }

        // 3. 检测对方马
        // 马腿紧挨着**马**而不是将: 从将往回看, 挡住的永远是那个对角格.
        // 例如马在 (kr-2, kc-1), 它要走的是 (kr-1, kc-1), 而不是将正前方的 (kr-1, kc).
        val horseDirs = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1),
            intArrayOf(2, -1), intArrayOf(2, 1),
            intArrayOf(-1, -2), intArrayOf(1, -2),
            intArrayOf(-1, 2), intArrayOf(1, 2)
        )
        for (d in horseDirs) {
            val toR = kr + d[0]
            val toC = kc + d[1]
            if (toR !in 0 until Square.ROWS || toC !in 0 until Square.COLS) continue
            val legR = kr + if (d[0] > 0) 1 else -1
            val legC = kc + if (d[1] > 0) 1 else -1
            if (pos.squares[Square.make(legR, legC)] != Piece.NONE.code) continue  // 蹩马腿
            val p = pos.squares[Square.make(toR, toC)]
            if (p != Piece.NONE.code && Piece.fromCode(p).color == oppColor && Piece.fromCode(p).type == 4) {
                return true
            }
        }

        // 4. 检测对方兵/卒(直线临近 + 过河后横向)
        // 我方将的前方紧邻格(对方兵的来源)
        val oppPawnForwardRow = if (color == Color.RED) kr - 1 else kr + 1  // 对方兵前进的方向
        if (oppPawnForwardRow in 0 until Square.ROWS) {
            val pawnSq = Square.make(oppPawnForwardRow, kc)
            val p = pos.squares[pawnSq]
            if (p != Piece.NONE.code && Piece.fromCode(p).color == oppColor && Piece.fromCode(p).type == 7) {
                return true
            }
        }
        // 横向 (过河后的兵): king 紧邻的左右
        for (dc in intArrayOf(-1, 1)) {
            val sideC = kc + dc
            if (sideC !in 0 until Square.COLS) continue
            val sideSq = Square.make(kr, sideC)
            val p = pos.squares[sideSq]
            if (p != Piece.NONE.code && Piece.fromCode(p).color == oppColor && Piece.fromCode(p).type == 7) {
                // 对方兵过河后可以横吃 king
                val pawnRow = kr  // 对方兵与 king 在同一行
                val pawnCrossed = if (oppColor == Color.RED) pawnRow <= 4 else pawnRow >= 5
                if (pawnCrossed) return true
            }
        }

        return false
    }

    /** 是否对脸将(飞行将军) */
    fun isFlyingGeneral(pos: Position, kingSq: Int): Boolean {
        val kingCol = Square.col(kingSq)
        val kingColor = Piece.fromCode(pos.squares[kingSq]).color
        val oppKingCode = if (kingColor == Color.RED) Piece.B_KING.code else Piece.R_KING.code
        // 同列找对方将
        for (r in 0 until Square.ROWS) {
            val sq = Square.make(r, kingCol)
            if (sq == kingSq) continue
            if (pos.squares[sq] == oppKingCode) {
                // 中间无子
                val minR = minOf(r, Square.row(kingSq))
                val maxR = maxOf(r, Square.row(kingSq))
                for (rr in (minR + 1) until maxR) {
                    val middleSq = Square.make(rr, kingCol)
                    if (pos.squares[middleSq] != Piece.NONE.code) return false
                }
                return true
            }
        }
        return false
    }

    /**
     * 检查走法是否伪合法(符合该棋子的行棋规则,但可能送将).
     *
     * 通过走法生成器验证,确保与 [MoveGenerator] 的规则完全一致 ——
     * 否则 UI/引擎可以走出"车走马步"这类非法着法.
     */
    fun isPseudoLegal(pos: Position, move: Move): Boolean {
        val from = move.from
        val to = move.to
        if (from == to) return false
        if (!Square.valid(from) || !Square.valid(to)) return false
        val piece = pos.squares[from]
        if (piece == Piece.NONE.code) return false
        if (Piece.fromCode(piece).color != pos.sideToMove) return false

        val candidates = mutableListOf<Move>()
        MoveGenerator.generateForSquare(pos, from, candidates)
        return candidates.any { it.to == to }
    }

    /** 是否将死 */
    fun isCheckmate(pos: Position): Boolean {
        if (!isInCheck(pos, pos.sideToMove)) return false
        val moves = mutableListOf<Move>()
        generateAllLegal(pos, moves)
        return moves.isEmpty()
    }

    /** 是否困毙 */
    fun isStalemate(pos: Position): Boolean {
        if (isInCheck(pos, pos.sideToMove)) return false
        val moves = mutableListOf<Move>()
        generateAllLegal(pos, moves)
        return moves.isEmpty()
    }

    /** 生成所有合法走法 */
    fun generateAllLegal(pos: Position, moves: MutableList<Move>) {
        MoveGenerator.generateAll(pos, moves)
    }

    /** 游戏结束原因 */
    enum class GameOver {
        NONE, CHECKMATE_RED, CHECKMATE_BLACK, STALEMATE, REPETITION, FIFTY_MOVES, RESIGN
    }

    /** 检测对局是否结束 */
    fun gameOver(pos: Position): GameOver {
        val moves = mutableListOf<Move>()
        generateAllLegal(pos, moves)
        if (moves.isEmpty()) {
            return if (isInCheck(pos, pos.sideToMove)) {
                if (pos.sideToMove == Color.RED) GameOver.CHECKMATE_BLACK
                else GameOver.CHECKMATE_RED
            } else {
                GameOver.STALEMATE
            }
        }
        if (pos.isThreefoldRepetition()) return GameOver.REPETITION
        if (pos.isFiftyMoveDraw()) return GameOver.FIFTY_MOVES
        return GameOver.NONE
    }
}