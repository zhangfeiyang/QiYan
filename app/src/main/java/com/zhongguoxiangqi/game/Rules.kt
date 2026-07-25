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
            var jumped = false
            while (true) {
                val toR = kr + dir[0] * step
                val toC = kc + dir[1] * step
                if (toR !in 0 until Square.ROWS || toC !in 0 until Square.COLS) break
                val sq = Square.make(toR, toC)
                val p = pos.squares[sq]
                if (p == Piece.NONE.code) {
                    step++
                    continue
                }
                val pt = Piece.fromCode(p)
                if (pt.color == color) {
                    // 我方棋子,挡住
                    break
                }
                // 对方棋子
                if (!jumped) {
                    if (pt.type == 5) return true  // 车
                    if (pt.type == 6) {
                        jumped = true  // 炮架,继续
                    } else {
                        break  // 其他棋子,挡住
                    }
                } else {
                    // 已跨过炮架
                    if (pt.type == 6) return true  // 炮
                    break
                }
                step++
            }
        }

        // 3. 检测对方马
        // 8 个方向: (目标行列偏移, 马腿行列偏移)
        val horseDirs = arrayOf(
            intArrayOf(-2, -1, -1, 0), intArrayOf(-2, 1, -1, 0),
            intArrayOf(2, -1, 1, 0), intArrayOf(2, 1, 1, 0),
            intArrayOf(-1, -2, 0, -1), intArrayOf(1, -2, 0, -1),
            intArrayOf(-1, 2, 0, 1), intArrayOf(1, 2, 0, 1)
        )
        for (d in horseDirs) {
            val toR = kr + d[0]
            val toC = kc + d[1]
            val legR = kr + d[2]
            val legC = kc + d[3]
            if (toR !in 0 until Square.ROWS || toC !in 0 until Square.COLS) continue
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

    /** 检查走法是否伪合法(规则正确但可能送将) */
    fun isPseudoLegal(pos: Position, move: Move): Boolean {
        val from = move.from
        val to = move.to
        val piece = pos.squares[from]
        if (piece == Piece.NONE.code) return false
        val p = Piece.fromCode(piece)
        if (p.color != pos.sideToMove) return false
        val captured = pos.squares[to]
        if (captured != Piece.NONE.code && Piece.fromCode(captured).color == pos.sideToMove) {
            return false
        }
        return true
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
        val saved = moves.size
        MoveGenerator.generatePseudo(pos, moves)
        val it = moves.listIterator(saved)
        while (it.hasNext()) {
            val m = it.next()
            if (!pos.isLegalMove(m)) it.remove()
        }
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
        return GameOver.NONE
    }
}