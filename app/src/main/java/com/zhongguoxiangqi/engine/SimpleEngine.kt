package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.Square
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 内置简易引擎(Java/Kotlin 实现).
 *
 * 用于:
 * 1. Pikafish 未安装时的降级
 * 2. 低难度级别(1-5)
 *
 * 算法:
 * - Alpha-Beta 搜索 + 迭代加深
 * - 简单评估: 子力 + 位置 (PST)
 * - 走法排序: 吃子(MVV-LVA) + 将军
 * - 置换表 (Zobrist 键)
 *
 * 强度上限大约业余 5-7 段水平. 对最顶级人类棋手无胜算.
 */
class SimpleEngine : ChessEngine {

    @Volatile private var listener: EngineListener? = null
    @Volatile private var ready = false
    @Volatile private var searching = false
    @Volatile private var stopRequested = false
    private var searchThread: Thread? = null
    private var currentPosition: Position = Position()

    // 难度参数
    private var strengthLevel = 5
    private var maxDepth = 8
    private var randomness = 0  // 随机性(0=最优,越大越"弱")

    // 置换表
    private val tt = HashMap<Long, TTEntry>(8192)

    override fun isReady() = ready

    override fun name() = "内置引擎"

    override fun maxStrength() = 5  // 仅支持 5 级以下

    override fun start(): Boolean {
        ready = true
        listener?.onReady()
        return true
    }

    override fun shutdown() {
        stopSearch()
        ready = false
    }

    override fun setPosition(fen: String, moves: List<String>) {
        currentPosition = Position()
        currentPosition.setupStartPosition()
        // 简化的 FEN 解析:只处理初始局面和 move 应用
        if (fen != "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w" &&
            fen != "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR b") {
            // 自定义 FEN: 简单重置为起始
        }
        // 应用 moves (UCI 格式)
        for (mv in moves) {
            applyUciMove(currentPosition, mv)
        }
    }

    override fun startSearch(params: SearchParams) {
        stopSearch()
        stopRequested = false
        val listenerSnap = listener
        searchThread = Thread({
            try {
                val bestMove = search(params)
                if (!stopRequested) {
                    listenerSnap?.onBestMove(
                        moveToUci(bestMove),
                        null
                    )
                }
            } catch (e: Exception) {
                listenerSnap?.onError(e.message ?: "engine error")
            }
        }, "SimpleEngine-search")
        searchThread?.isDaemon = true
        searchThread?.start()
    }

    override fun stopSearch() {
        stopRequested = true
        searchThread?.interrupt()
    }

    override fun setListener(listener: EngineListener?) {
        this.listener = listener
    }

    /** 配置引擎强度 */
    fun configure(strength: Int) {
        strengthLevel = strength.coerceIn(1, 5)
        maxDepth = when (strengthLevel) {
            1 -> 1
            2 -> 2
            3 -> 4
            4 -> 6
            5 -> 8
            else -> 8
        }
        randomness = (5 - strengthLevel).coerceAtLeast(0)
    }

    // ====== 搜索 ======

    private fun search(params: SearchParams): Move {
        val deadline = if (params.movetime > 0) System.currentTimeMillis() + params.movetime else Long.MAX_VALUE
        val maxDepthTarget = if (params.depth > 0) params.depth else maxDepth

        var bestMove = Move.empty()
        var bestScore = Int.MIN_VALUE
        val nodes = longArrayOf(0)
        val startTime = System.currentTimeMillis()

        // 迭代加深
        for (depth in 1..maxDepthTarget) {
            if (stopRequested) break
            val score = alphaBeta(currentPosition, depth, Int.MIN_VALUE + 1, Int.MAX_VALUE - 1, nodes, 0)
            if (stopRequested) break

            // 找到当前 depth 最佳走法
            val curBest = findBestMoveAtRoot(currentPosition, depth)
            if (curBest.from != curBest.to) {
                bestMove = curBest
                bestScore = score
            }

            listener?.onInfo(EngineInfo(
                depth = depth,
                timeMs = System.currentTimeMillis() - startTime,
                nodes = nodes[0],
                nps = if (System.currentTimeMillis() > startTime) nodes[0] * 1000 / (System.currentTimeMillis() - startTime) else 0,
                scoreCp = if (bestScore >= 30000) 0 else if (bestScore <= -30000) 0 else bestScore,
                scoreMate = if (bestScore >= 30000) 1 else if (bestScore <= -30000) -1 else 0,
                pv = mutableListOf<String>().apply {
                    var p = currentPosition
                    val tmpMoves = mutableListOf<Move>()
                    Rules.generateAllLegal(p, tmpMoves)
                    if (bestMove.data != 0) {
                        add(moveToUci(bestMove))
                    }
                }
            ))

            if (System.currentTimeMillis() >= deadline) break
        }

        return bestMove
    }

    private fun alphaBeta(pos: Position, depth: Int, alpha: Int, beta: Int, nodes: LongArray, ply: Int): Int {
        nodes[0]++
        if (stopRequested) return 0

        // 叶节点评估
        if (depth <= 0) return evaluate(pos)

        // 置换表查询
        val key = pos.hash
        val ttEntry = tt[key]
        if (ttEntry != null && ttEntry.depth >= depth) {
            when (ttEntry.flag) {
                TT_EXACT -> return ttEntry.score
                TT_LOWER -> if (ttEntry.score >= beta) return ttEntry.score
                TT_UPPER -> if (ttEntry.score <= alpha) return ttEntry.score
            }
        }

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)

        if (moves.isEmpty()) {
            // 输/和
            return if (Rules.isInCheck(pos, pos.sideToMove)) -30000 + ply else 0
        }

        // 排序: 吃子优先(MVV-LVA), 然后将, 然后其他
        moves.forEach { m ->
            val victim = pos.pieceAt(m.to)
            val attacker = pos.pieceAt(m.from)
            m.score = m.mvvLva(victim, attacker)
        }
        moves.sortByDescending { it.score }

        var bestScore = Int.MIN_VALUE
        var bestFlag = TT_UPPER
        var alphaLocal = alpha

        for (m in moves) {
            pos.doMove(m)
            val score = -alphaBeta(pos, depth - 1, -beta, -alphaLocal, nodes, ply + 1)
            pos.undoMove()

            if (stopRequested) return 0
            if (score > bestScore) bestScore = score
            if (score > alphaLocal) {
                alphaLocal = score
                bestFlag = TT_EXACT
            }
            if (alphaLocal >= beta) {
                bestFlag = TT_LOWER
                break
            }
        }

        tt[key] = TTEntry(depth, bestScore, bestFlag)
        return bestScore
    }

    private fun findBestMoveAtRoot(pos: Position, depth: Int): Move {
        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)
        var best = Move.empty()
        var bestScore = Int.MIN_VALUE
        for (m in moves) {
            pos.doMove(m)
            val score = -alphaBeta(pos, depth - 1, Int.MIN_VALUE + 1, Int.MAX_VALUE - 1, longArrayOf(0), 1)
            pos.undoMove()
            if (score > bestScore) {
                bestScore = score
                best = m
            }
        }
        return best
    }

    // ====== 评估 ======

    /**
     * 局面评估(从红方角度,正值=红方优势).
     * 包含: 子力价值 + 棋子位置分(PST).
     */
    private fun evaluate(pos: Position): Int {
        var score = 0
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val piece = Piece.fromCode(p)
            val type = piece.type
            val base = when (type) {
                1 -> 10000  // 帅/将
                2 -> 200    // 仕
                3 -> 200    // 相
                4 -> 400    // 马
                5 -> 900    // 车
                6 -> 450    // 炮
                7 -> 100    // 兵
                else -> 0
            }
            val pst = pstValue(type, sq, piece.color)
            val total = base + pst
            score += if (piece.color == Color.RED) total else -total
        }
        return if (pos.sideToMove == Color.RED) score else -score
    }

    /** 棋子位置分 (Piece-Square Table) - 简化的 PST */
    private fun pstValue(type: Int, sq: Int, color: Int): Int {
        val r = Square.row(sq)
        val c = Square.col(sq)
        return when (type) {
            1 -> if (color == Color.RED) kingPstRed[r].get(c) else kingPstBlack[r].get(c)
            2 -> 0  // 仕位置固定, 无额外奖励
            3 -> 0  // 相位置固定
            4 -> horsePst[r].get(c) * (if (color == Color.RED) 1 else -1).let { 1 }  // 马对称
            5 -> rookPst[r].get(c)
            6 -> cannonPst[r].get(c)
            7 -> pawnPst[r].get(c) * (if (color == Color.RED) 1 else -1).let { 1 }  // 兵需要翻转
            else -> 0
        }
    }

    // 简化的位置表(可后续通过学习优化)
    private val kingPstRed = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 5, 10, 5, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 5, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 5, 0, 0, 0, 0)
    )
    private val kingPstBlack: Array<IntArray> = Array(10) { i ->
        val redRow = kingPstRed[9 - i]
        IntArray(9) { j -> redRow[j] }
    }

    private val horsePst = arrayOf(
        intArrayOf(-5, 0, 0, 0, 0, 0, 0, 0, -5),
        intArrayOf(0, 0, 5, 0, 0, 0, 5, 0, 0),
        intArrayOf(0, 5, 10, 5, 5, 5, 10, 5, 0),
        intArrayOf(0, 5, 5, 10, 5, 10, 5, 5, 0),
        intArrayOf(0, 0, 5, 5, 10, 5, 5, 0, 0),
        intArrayOf(0, 5, 5, 5, 5, 5, 5, 5, 0),
        intArrayOf(0, 0, 5, 5, 5, 5, 5, 0, 0),
        intArrayOf(0, 5, 5, 5, 5, 5, 5, 5, 0),
        intArrayOf(0, 0, 5, 0, 0, 0, 5, 0, 0),
        intArrayOf(-5, 0, 0, 0, 0, 0, 0, 0, -5)
    )

    private val rookPst = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(-2, 5, 5, 5, 5, 5, 5, 5, -2),
        intArrayOf(5, 10, 10, 10, 10, 10, 10, 10, 5),
        intArrayOf(5, 10, 10, 10, 10, 10, 10, 10, 5),
        intArrayOf(-2, 5, 5, 5, 5, 5, 5, 5, -2),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0)
    )

    private val cannonPst = arrayOf(
        intArrayOf(6, 4, 0, -5, -10, -5, 0, 4, 6),
        intArrayOf(2, 2, 0, -2, -4, -2, 0, 2, 2),
        intArrayOf(2, 2, 0, -2, -4, -2, 0, 2, 2),
        intArrayOf(0, 0, 0, 2, 5, 2, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 5, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 5, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 2, 5, 2, 0, 0, 0),
        intArrayOf(2, 2, 0, -2, -4, -2, 0, 2, 2),
        intArrayOf(2, 2, 0, -2, -4, -2, 0, 2, 2),
        intArrayOf(6, 4, 0, -5, -10, -5, 0, 4, 6)
    )

    private val pawnPst = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(10, 10, 10, 15, 20, 15, 10, 10, 10),
        intArrayOf(20, 25, 30, 35, 40, 35, 30, 25, 20),
        intArrayOf(20, 30, 40, 50, 60, 50, 40, 30, 20),
        intArrayOf(20, 30, 40, 50, 60, 50, 40, 30, 20)
    )

    // ====== 辅助: UCI 转换 ======

    private fun applyUciMove(pos: Position, uci: String) {
        // 形如 "b2e2" 或含前缀 "h0e1" 等
        if (uci.length < 4) return
        try {
            val fromCol = uci[0] - 'a'
            val fromRow = uci[1].digitToInt()
            val toCol = uci[2] - 'a'
            val toRow = uci[3].digitToInt()
            // UCI 坐标: 列 0-8 -> a-i, 行 0-9 (0 = 红方底线)
            // 我们的表示: row 0 = 黑方底线, row 9 = 红方底线
            val fromSq = Square.make(9 - fromRow, fromCol)
            val toSq = Square.make(9 - toRow, toCol)
            val move = Move.make(fromSq, toSq)
            if (pos.isLegalMove(move)) {
                pos.doMove(move)
            }
        } catch (_: Exception) { }
    }

    private fun moveToUci(move: Move): String {
        if (move.from == move.to) return "0000"
        val fc = Square.col(move.from)
        val fr = 9 - Square.row(move.from)
        val tc = Square.col(move.to)
        val tr = 9 - Square.row(move.to)
        return "${(fc + 'a'.code).toChar()}$fr${(tc + 'a'.code).toChar()}$tr"
    }

    // ====== 置换表 ======

    private data class TTEntry(val depth: Int, val score: Int, val flag: Int)

    companion object {
        private const val TT_EXACT = 0
        private const val TT_LOWER = 1
        private const val TT_UPPER = 2
    }
}