package com.zhongguoxiangqi.game

/**
 * 棋盘局面 (Position).
 *
 * 维护:
 * - 90 个格子上的棋子
 * - 当前轮到哪方走
 * - Zobrist 哈希(用于置换表和重复检测)
 * - 上一步走法(用于撤销)
 */
class Position {

    /** 90 格,每格存储棋子代码 (0..15) */
    val squares: IntArray = IntArray(Square.COUNT)

    /** 轮到哪方走 */
    var sideToMove: Int = Color.RED

    /** 当前局面 Zobrist 哈希值 */
    var hash: Long = 0L

    /** 距上次吃子的半回合数(自然限着判定) */
    var halfmoveClock: Int = 0
        private set

    /** 回合数(红黑各走一步算一个回合) */
    var fullmoveNumber: Int = 1
        private set

    /** 走法栈(用于悔棋) */
    private val moveStack: ArrayList<UndoInfo> = ArrayList()

    /** 历史哈希栈(用于重复检测) */
    val historyHashes: MutableList<Long> = mutableListOf()

    /** 空着只存在于搜索树；它及其后代不能参与真实对局的重复/限着判定。 */
    private var nullMoveDepth: Int = 0

    init {
        setupStartPosition()
    }

    /** 初始局面(标准中国象棋开局) */
    fun setupStartPosition() {
        squares.fill(0)
        // 黑方 (上方, row 0..4)
        squares[Square.make(0, 0)] = Piece.B_ROOK.code
        squares[Square.make(0, 1)] = Piece.B_HORSE.code
        squares[Square.make(0, 2)] = Piece.B_ELEPHANT.code
        squares[Square.make(0, 3)] = Piece.B_ADVISOR.code
        squares[Square.make(0, 4)] = Piece.B_KING.code
        squares[Square.make(0, 5)] = Piece.B_ADVISOR.code
        squares[Square.make(0, 6)] = Piece.B_ELEPHANT.code
        squares[Square.make(0, 7)] = Piece.B_HORSE.code
        squares[Square.make(0, 8)] = Piece.B_ROOK.code
        squares[Square.make(2, 1)] = Piece.B_CANNON.code
        squares[Square.make(2, 7)] = Piece.B_CANNON.code
        squares[Square.make(3, 0)] = Piece.B_PAWN.code
        squares[Square.make(3, 2)] = Piece.B_PAWN.code
        squares[Square.make(3, 4)] = Piece.B_PAWN.code
        squares[Square.make(3, 6)] = Piece.B_PAWN.code
        squares[Square.make(3, 8)] = Piece.B_PAWN.code
        // 红方 (下方, row 5..9)
        squares[Square.make(9, 0)] = Piece.R_ROOK.code
        squares[Square.make(9, 1)] = Piece.R_HORSE.code
        squares[Square.make(9, 2)] = Piece.R_ELEPHANT.code
        squares[Square.make(9, 3)] = Piece.R_ADVISOR.code
        squares[Square.make(9, 4)] = Piece.R_KING.code
        squares[Square.make(9, 5)] = Piece.R_ADVISOR.code
        squares[Square.make(9, 6)] = Piece.R_ELEPHANT.code
        squares[Square.make(9, 7)] = Piece.R_HORSE.code
        squares[Square.make(9, 8)] = Piece.R_ROOK.code
        squares[Square.make(7, 1)] = Piece.R_CANNON.code
        squares[Square.make(7, 7)] = Piece.R_CANNON.code
        squares[Square.make(6, 0)] = Piece.R_PAWN.code
        squares[Square.make(6, 2)] = Piece.R_PAWN.code
        squares[Square.make(6, 4)] = Piece.R_PAWN.code
        squares[Square.make(6, 6)] = Piece.R_PAWN.code
        squares[Square.make(6, 8)] = Piece.R_PAWN.code

        sideToMove = Color.RED
        halfmoveClock = 0
        fullmoveNumber = 1
        hash = computeHash()
        historyHashes.clear()
        historyHashes.add(hash)
        moveStack.clear()
        nullMoveDepth = 0
    }

    /** 计算整个局面的 Zobrist 哈希 */
    fun computeHash(): Long {
        var h = 0L
        for (sq in 0 until Square.COUNT) {
            val p = squares[sq]
            if (p != Piece.NONE.code) {
                h = h xor Zobrist.pieceSquare[p][sq]
            }
        }
        if (sideToMove == Color.BLACK) {
            h = h xor Zobrist.side
        }
        return h
    }

    /** 读取某格棋子 */
    fun pieceAt(sq: Int): Int = squares[sq]
    fun pieceAt(row: Int, col: Int): Int = squares[Square.make(row, col)]

    /** 设置某格棋子(不更新 hash,仅用于初始化) */
    internal fun setPiece(row: Int, col: Int, piece: Int) {
        squares[Square.make(row, col)] = piece
    }

    /** 计数某列同种同色棋子(用于着法记法的前缀判断). piece 编码已含颜色. */
    fun countPiecesInCol(col: Int, piece: Int): Int {
        var cnt = 0
        for (r in 0 until Square.ROWS) {
            if (squares[Square.make(r, col)] == piece) cnt++
        }
        return cnt
    }

    /**
     * 空着: 只把行棋权交给对方, 棋子一个不动. 供搜索的空着裁剪用.
     *
     * 刻意**不**推 [moveStack] 和 [historyHashes]:
     * - 空着不是真实着法, 推进 historyHashes 会污染三次重复判定
     * - 不推 moveStack 也就不能用 [undoMove] 撤销, 必须配对调用 [undoNullMove];
     *   而空着子树内部正常的 doMove/undoMove 仍然自己配对, 互不干扰
     *
     * 半回合计数和回合数也不动 —— 搜索内部用不到, 而改了反倒要多存一份来还原。
     *
     * NNUE 累加器**不需要更新**: 棋子没变, 只是走子方翻了, 而求值时哪一半累加器
     * 算"我方"是由走子方参数决定的(见 nnue_core 的 eval_acc)。
     */
    fun doNullMove() {
        hash = hash xor Zobrist.side
        sideToMove = Color.opposite(sideToMove)
        nullMoveDepth++
    }

    /** 与 [doNullMove] 配对 */
    fun undoNullMove() {
        if (nullMoveDepth > 0) nullMoveDepth--
        sideToMove = Color.opposite(sideToMove)
        hash = hash xor Zobrist.side
    }

    /**
     * 走子方是否还有"能主动制造威胁"的子(车/炮/马).
     *
     * 空着裁剪的前提是"我不走, 对手也占不到便宜"。这个假设在子力稀薄的残局会
     * 失效 —— 那里"必须走"本身就是负担(欠行), 不走反而更好, 于是裁剪会把真实的
     * 危险剪掉。只剩兵士象时禁用空着裁剪。
     */
    fun hasThreateningPiece(color: Int): Boolean {
        for (sq in 0 until Square.COUNT) {
            val p = squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != color) continue
            when (pc) {
                Piece.R_ROOK, Piece.R_CANNON, Piece.R_HORSE,
                Piece.B_ROOK, Piece.B_CANNON, Piece.B_HORSE -> return true
                else -> {}
            }
        }
        return false
    }

    /** 执行走法(返回 false 表示走法非法) */
    fun doMove(move: Move): Boolean {
        val from = move.from
        val to = move.to
        if (!Square.valid(from) || !Square.valid(to)) return false

        val piece = squares[from]
        if (piece == Piece.NONE.code) return false
        if (Piece.fromCode(piece).color != sideToMove) return false

        val captured = squares[to]

        // 更新 hash: 棋子离开起点、落到终点,移除被吃子,切换走子方
        hash = hash xor Zobrist.pieceSquare[piece][from]
        hash = hash xor Zobrist.pieceSquare[piece][to]
        if (captured != Piece.NONE.code) {
            hash = hash xor Zobrist.pieceSquare[captured][to]
        }
        hash = hash xor Zobrist.side

        // 执行走法
        squares[from] = Piece.NONE.code
        squares[to] = piece

        // 压栈(先记录旧的限着计数,便于 undo 恢复)
        moveStack.add(UndoInfo(from, to, piece, captured, halfmoveClock))

        // 切换走子方
        if (sideToMove == Color.BLACK) fullmoveNumber++
        sideToMove = Color.opposite(sideToMove)
        halfmoveClock = if (captured != Piece.NONE.code) 0 else halfmoveClock + 1

        historyHashes.add(hash)
        return true
    }

    /** 撤销最后一步走法 */
    fun undoMove(): UndoInfo? {
        if (moveStack.isEmpty()) return null
        val info = moveStack.removeAt(moveStack.size - 1)
        historyHashes.removeAt(historyHashes.size - 1)

        // 切换走子方
        sideToMove = Color.opposite(sideToMove)
        if (sideToMove == Color.BLACK) fullmoveNumber--
        halfmoveClock = info.prevHalfmoveClock

        // 恢复
        squares[info.to] = info.captured
        squares[info.from] = info.moved

        // 更新 hash(与 doMove 严格对称)
        hash = hash xor Zobrist.side
        hash = hash xor Zobrist.pieceSquare[info.moved][info.from]
        hash = hash xor Zobrist.pieceSquare[info.moved][info.to]
        if (info.captured != Piece.NONE.code) {
            hash = hash xor Zobrist.pieceSquare[info.captured][info.to]
        }
        return info
    }

    /** 是否走法合法(行棋规则 + 不送将) */
    fun isLegalMove(move: Move): Boolean {
        if (!Rules.isPseudoLegal(this, move)) return false
        return leavesKingSafe(move)
    }

    /**
     * 走完这一步后本方是否不被将军.
     *
     * 前提: [move] 已经是伪合法走法. 走法生成器内部用这条快路径,
     * 避免对每个候选着法重复做规则校验.
     */
    fun leavesKingSafe(move: Move): Boolean {
        val moverColor = sideToMove
        if (!doMove(move)) return false
        val safe = !Rules.isInCheck(this, moverColor)
        undoMove()
        return safe
    }

    /** 复制局面 */
    fun copy(): Position {
        val p = Position()
        System.arraycopy(squares, 0, p.squares, 0, Square.COUNT)
        p.sideToMove = sideToMove
        p.hash = hash
        p.halfmoveClock = halfmoveClock
        p.fullmoveNumber = fullmoveNumber
        p.moveStack.clear()
        p.moveStack.addAll(moveStack)
        p.historyHashes.clear()
        p.historyHashes.addAll(historyHashes)
        p.nullMoveDepth = nullMoveDepth
        return p
    }

    /** 生成所有走法(供搜索或 UI 提示) */
    fun generateMoves(moves: MutableList<Move>) {
        MoveGenerator.generateAll(this, moves)
    }

    /** 检测循环(三次重复) */
    fun isThreefoldRepetition(): Boolean {
        if (historyHashes.size < 9) return false  // 至少 9 个局面(4 次重复)
        val current = hash
        var count = 0
        for (i in historyHashes.indices) {
            if (historyHashes[i] == current) count++
        }
        return count >= 3
    }

    /** 自然限着: 连续 120 个半回合(60 回合)无吃子判和 */
    fun isFiftyMoveDraw(): Boolean = halfmoveClock >= 120

    /** 与对局裁判同语义，但排除空着验证形成的伪历史。 */
    fun isSearchRuleDraw(): Boolean = nullMoveDepth == 0 &&
        (isFiftyMoveDraw() || isThreefoldRepetition())

    /**
     * 导出标准象棋 FEN.
     *
     * 例如初始局面:
     * `rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1`
     *
     * 必须是标准字母格式 —— Pikafish 等 UCI 引擎按这个格式解析 `position fen`.
     */
    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 0 until Square.ROWS) {
            var emptyCount = 0
            for (c in 0 until Square.COLS) {
                val p = squares[Square.make(r, c)]
                if (p == Piece.NONE.code) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    sb.append(Piece.fromCode(p).fen)
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            if (r < Square.ROWS - 1) sb.append('/')
        }
        sb.append(if (sideToMove == Color.RED) " w" else " b")
        sb.append(" - - ").append(halfmoveClock).append(' ').append(fullmoveNumber)
        return sb.toString()
    }

    /**
     * 从标准 FEN 载入局面. 解析失败时保持原局面并返回 false.
     */
    fun setFromFen(fen: String): Boolean {
        val parts = fen.trim().split(" ")
        if (parts.isEmpty()) return false
        val rows = parts[0].split("/")
        if (rows.size != Square.ROWS) return false

        val parsed = IntArray(Square.COUNT)
        for (r in 0 until Square.ROWS) {
            var c = 0
            for (ch in rows[r]) {
                if (ch.isDigit()) {
                    c += ch - '0'
                } else {
                    val piece = Piece.fromFen(ch)
                    if (piece == Piece.NONE) return false
                    if (c >= Square.COLS) return false
                    parsed[Square.make(r, c)] = piece.code
                    c++
                }
            }
            if (c != Square.COLS) return false
        }

        System.arraycopy(parsed, 0, squares, 0, Square.COUNT)
        sideToMove = if (parts.getOrNull(1) == "b") Color.BLACK else Color.RED
        halfmoveClock = parts.getOrNull(4)?.toIntOrNull() ?: 0
        fullmoveNumber = parts.getOrNull(5)?.toIntOrNull() ?: 1
        hash = computeHash()
        historyHashes.clear()
        historyHashes.add(hash)
        moveStack.clear()
        nullMoveDepth = 0
        return true
    }

    /** 打印棋盘到字符串(调试用) */
    fun prettyPrint(): String {
        val sb = StringBuilder()
        for (r in 0 until Square.ROWS) {
            for (c in 0 until Square.COLS) {
                val p = squares[Square.make(r, c)]
                sb.append(if (p == Piece.NONE.code) ". " else "${Piece.fromCode(p).chinese} ")
            }
            sb.append("\n")
        }
        sb.append("Side: ${Color.name(sideToMove)}, Hash: $hash")
        return sb.toString()
    }

    data class UndoInfo(
        val from: Int,
        val to: Int,
        val moved: Int,
        val captured: Int,
        val prevHalfmoveClock: Int = 0
    )
}
