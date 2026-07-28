package com.zhongguoxiangqi.game

/**
 * 走法生成器.
 *
 * 生成所有合法走法(根据中国象棋规则):
 * - 帅/将: 九宫内,每步一格,上下左右
 * - 仕/士: 九宫内,每步一格,斜线
 * - 相/象: 己方半场(不过河),田字,塞象眼
 * - 马: 日字,蹩马腿
 * - 车: 直线任意距离
 * - 炮: 走时同车,吃时隔一个棋子(炮架)
 * - 兵/卒: 未过河只能前进;过河可前进和横走
 *
 * 同时检测:
 * - 送将(走完后己方被将军) → 非法
 * - 长将/长捉 → 简化版不做自动判罚,留作 UI 显示
 */
object MoveGenerator {

    /** 红方九宫格 (row 7..9, col 3..5) */
    private val RED_PALACE_ROWS = 7..9

    /** 黑方九宫格 (row 0..2, col 3..5) */
    private val BLACK_PALACE_ROWS = 0..2

    /** 红方过河限制(只能 row >= 5) */
    private const val RED_RIVER = 5

    /** 黑方过河限制(只能 row <= 4) */
    private const val BLACK_RIVER = 4

    /**
     * 生成所有合法吃子走法. 只给静止搜索用 —— 比 [generateAll] 后再 filter
     * 快得多, 因为非吃子走法在底层就跳过了, 不产生、不过滤、不进 legality check.
     */
    fun generateCaptures(pos: Position, moves: MutableList<Move>) {
        val savedSize = moves.size
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            if (Piece.fromCode(p).color != pos.sideToMove) continue
            generateForSquare(pos, sq, moves, capturesOnly = true)
        }
        val it = moves.listIterator(savedSize)
        while (it.hasNext()) {
            val m = it.next()
            if (!pos.leavesKingSafe(m)) it.remove()
        }
    }

    /** 生成所有合法走法 */
    fun generateAll(pos: Position, moves: MutableList<Move>) {
        val savedSize = moves.size
        generatePseudo(pos, moves)
        // 剔除送将走法(已是伪合法,只需检查是否自曝将)
        val it = moves.listIterator(savedSize)
        while (it.hasNext()) {
            val m = it.next()
            if (!pos.leavesKingSafe(m)) it.remove()
        }
    }

    /** 生成所有伪合法走法(不检查送将) - 供引擎内部使用 */
    fun generatePseudo(pos: Position, moves: MutableList<Move>, capturesOnly: Boolean = false) {
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            if (Piece.fromCode(p).color != pos.sideToMove) continue
            generateForSquare(pos, sq, moves, capturesOnly)
        }
    }

    /**
     * 生成某一格棋子的全部伪合法走法.
     *
     * 这是走子规则的唯一来源: [Rules.isPseudoLegal] 也走这条路径,
     * 保证"能生成的"与"判为合法的"永远一致.
     */
    fun generateForSquare(pos: Position, sq: Int, moves: MutableList<Move>,
                          capturesOnly: Boolean = false) {
        val p = pos.squares[sq]
        if (p == Piece.NONE.code) return
        val piece = Piece.fromCode(p)
        val color = piece.color
        when (piece.type) {
            1 -> genKing(pos, sq, color, moves, capturesOnly)
            2 -> genAdvisor(pos, sq, color, moves, capturesOnly)
            3 -> genElephant(pos, sq, color, moves, capturesOnly)
            4 -> genHorse(pos, sq, color, moves, capturesOnly)
            5 -> genRook(pos, sq, color, moves, capturesOnly)
            6 -> genCannon(pos, sq, color, moves, capturesOnly)
            7 -> genPawn(pos, sq, color, moves, capturesOnly)
        }
    }

    // ===== 帅/将 =====

    private fun genKing(pos: Position, from: Int, color: Int, moves: MutableList<Move>,
                        capturesOnly: Boolean = false) {
        val r = Square.row(from)
        val c = Square.col(from)
        val deltas = arrayOf(
            intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1)
        )
        for (d in deltas) {
            val toR = r + d[0]
            val toC = c + d[1]
            if (toR !in 0 until Square.ROWS || toC !in 0 until Square.COLS) continue
            val inPalace = if (color == Color.RED) {
                toR in 7..9 && toC in 3..5
            } else {
                toR in 0..2 && toC in 3..5
            }
            if (!inPalace) continue
            val to = Square.make(toR, toC)
            val tgt = pos.squares[to]
            if (tgt != Piece.NONE.code && Piece.fromCode(tgt).color == color) continue
            if (capturesOnly && tgt == Piece.NONE.code) continue
            moves.add(Move.make(from, to))
        }
    }

    // ===== 仕/士 =====

    private fun genAdvisor(pos: Position, from: Int, color: Int, moves: MutableList<Move>,
                           capturesOnly: Boolean = false) {
        val r = Square.row(from)
        val c = Square.col(from)
        val deltas = arrayOf(
            intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1)
        )
        for (d in deltas) {
            val toR = r + d[0]
            val toC = c + d[1]
            if (toR !in 0 until Square.ROWS || toC !in 0 until Square.COLS) continue
            val inPalace = if (color == Color.RED) {
                toR in 7..9 && toC in 3..5
            } else {
                toR in 0..2 && toC in 3..5
            }
            if (!inPalace) continue
            val to = Square.make(toR, toC)
            val tgt = pos.squares[to]
            if (tgt != Piece.NONE.code && Piece.fromCode(tgt).color == color) continue
            if (capturesOnly && tgt == Piece.NONE.code) continue
            moves.add(Move.make(from, to))
        }
    }

    // ===== 相/象 =====

    private fun genElephant(pos: Position, from: Int, color: Int, moves: MutableList<Move>,
                            capturesOnly: Boolean = false) {
        val r = Square.row(from)
        val c = Square.col(from)
        // 不可过河
        if (color == Color.RED && r <= 4) return
        if (color == Color.BLACK && r >= 5) return

        // 4 个田字方向
        val dirs = arrayOf(
            intArrayOf(-2, -2), intArrayOf(-2, 2), intArrayOf(2, -2), intArrayOf(2, 2)
        )
        for (dir in dirs) {
            val eyeR = r + dir[0] / 2
            val eyeC = c + dir[1] / 2
            val toR = r + dir[0]
            val toC = c + dir[1]
            if (!Square.valid(toR, toC)) continue
            // 不可过河
            if (color == Color.RED && toR <= 4) continue
            if (color == Color.BLACK && toR >= 5) continue
            // 塞象眼
            if (pos.pieceAt(eyeR, eyeC) != Piece.NONE.code) continue
            val to = Square.make(toR, toC)
            val tgt = pos.squares[to]
            if (tgt != Piece.NONE.code && Piece.fromCode(tgt).color == color) continue
            if (capturesOnly && tgt == Piece.NONE.code) continue
            moves.add(Move.make(from, to))
        }
    }

    // ===== 马 =====

    private fun genHorse(pos: Position, from: Int, color: Int, moves: MutableList<Move>,
                         capturesOnly: Boolean = false) {
        val fr = Square.row(from)
        val fc = Square.col(from)

        val dirs = arrayOf(
            intArrayOf(-2, -1, -1, 0),
            intArrayOf(-2, 1, -1, 0),
            intArrayOf(2, -1, 1, 0),
            intArrayOf(2, 1, 1, 0),
            intArrayOf(-1, -2, 0, -1),
            intArrayOf(1, -2, 0, -1),
            intArrayOf(-1, 2, 0, 1),
            intArrayOf(1, 2, 0, 1)
        )

        for (d in dirs) {
            val toR = fr + d[0]
            val toC = fc + d[1]
            if (toR !in 0 until Square.ROWS || toC !in 0 until Square.COLS) continue
            val legR = fr + d[2]
            val legC = fc + d[3]
            if (pos.squares[Square.make(legR, legC)] != Piece.NONE.code) continue
            val to = Square.make(toR, toC)
            val tgt = pos.squares[to]
            if (tgt != Piece.NONE.code && Piece.fromCode(tgt).color == color) continue
            if (capturesOnly && tgt == Piece.NONE.code) continue
            moves.add(Move.make(from, to))
        }
    }

    // ===== 车 =====

    private fun genRook(pos: Position, from: Int, color: Int, moves: MutableList<Move>,
                        capturesOnly: Boolean = false) {
        val fr = Square.row(from)
        val fc = Square.col(from)
        val dirs = arrayOf(
            intArrayOf(-1, 0),  // 上
            intArrayOf(1, 0),   // 下
            intArrayOf(0, -1),  // 左
            intArrayOf(0, 1)    // 右
        )
        for (dir in dirs) {
            var step = 1
            while (true) {
                val toR = fr + dir[0] * step
                val toC = fc + dir[1] * step
                if (toR !in 0 until Square.ROWS || toC !in 0 until Square.COLS) break
                val to = Square.make(toR, toC)
                val tgt = pos.squares[to]
                if (tgt == Piece.NONE.code) {
                    if (!capturesOnly) moves.add(Move.make(from, to))
                } else {
                    if (Piece.fromCode(tgt).color != color) {
                        moves.add(Move.make(from, to))  // capture, always keep
                    }
                    break
                }
                step++
            }
        }
    }

    // ===== 炮 =====

    private fun genCannon(pos: Position, from: Int, color: Int, moves: MutableList<Move>,
                          capturesOnly: Boolean = false) {
        val fr = Square.row(from)
        val fc = Square.col(from)
        val dirs = arrayOf(
            intArrayOf(-1, 0),  // 上
            intArrayOf(1, 0),   // 下
            intArrayOf(0, -1),  // 左
            intArrayOf(0, 1)    // 右
        )
        for (dir in dirs) {
            var step = 1
            var jumped = false
            while (true) {
                val toR = fr + dir[0] * step
                val toC = fc + dir[1] * step
                if (toR !in 0 until Square.ROWS || toC !in 0 until Square.COLS) break
                val to = Square.make(toR, toC)
                val tgt = pos.squares[to]
                if (!jumped) {
                    if (tgt == Piece.NONE.code) {
                        if (!capturesOnly) moves.add(Move.make(from, to))
                    } else {
                        jumped = true  // 找到炮架
                    }
                } else {
                    if (tgt != Piece.NONE.code) {
                        if (Piece.fromCode(tgt).color != color) {
                            moves.add(Move.make(from, to))  // capture over mount
                        }
                        break
                    }
                }
                step++
            }
        }
    }

    // ===== 兵/卒 =====

    private fun genPawn(pos: Position, from: Int, color: Int, moves: MutableList<Move>,
                        capturesOnly: Boolean = false) {
        val r = Square.row(from)
        val c = Square.col(from)
        val dForward = if (color == Color.RED) -1 else 1

        // 前进
        val toR = r + dForward
        if (toR in 0 until Square.ROWS) {
            val to = Square.make(toR, c)
            val tgt = pos.squares[to]
            if (tgt == Piece.NONE.code || Piece.fromCode(tgt).color != color) {
                if (!capturesOnly || tgt != Piece.NONE.code) {
                    moves.add(Move.make(from, to))
                }
            }
        }

        // 过河后可横走
        val crossedRiver = if (color == Color.RED) r <= 4 else r >= 5
        if (crossedRiver) {
            for (dc in intArrayOf(-1, 1)) {
                val toC = c + dc
                if (toC !in 0 until Square.COLS) continue
                val to = Square.make(r, toC)
                val tgt = pos.squares[to]
                if (tgt == Piece.NONE.code || Piece.fromCode(tgt).color != color) {
                    if (!capturesOnly || tgt != Piece.NONE.code) {
                        moves.add(Move.make(from, to))
                    }
                }
            }
        }
    }
}
