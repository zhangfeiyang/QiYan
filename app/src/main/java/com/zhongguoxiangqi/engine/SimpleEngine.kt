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

    // 搜索时限
    @Volatile private var deadline = Long.MAX_VALUE
    @Volatile private var timeUp = false

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

    /** 开局库(可选). 由 EngineManager 注入,因为它需要 Context 读 assets. */
    var openingBook: OpeningBook? = null

    private var startFen: String = ""
    private var movesPlayed: List<String> = emptyList()

    override fun setPosition(fen: String, moves: List<String>) {
        val pos = Position()
        if (fen.isBlank() || !pos.setFromFen(fen)) {
            pos.setupStartPosition()
        }
        // 应用 moves (UCI 格式)
        for (mv in moves) {
            applyUciMove(pos, mv)
        }
        currentPosition = pos
        startFen = if (fen.isBlank()) pos.toFen() else fen
        movesPlayed = moves
    }

    /**
     * 开局库着法. 库数据可能有误,这里必须验证合法性 ——
     * 宁可回退到搜索,也不能走出非法着法.
     */
    private fun bookMove(): Move? {
        val uci = openingBook?.lookup(startFen, movesPlayed) ?: return null
        val move = parseUci(uci) ?: return null
        return if (currentPosition.isLegalMove(move)) move else null
    }

    override fun startSearch(params: SearchParams) {
        // 必须等上一次搜索真正结束再放开 stopRequested,
        // 否则旧线程会看到复位后的标志继续跑完并吐出一个过期的 bestmove.
        stopSearch()
        stopRequested = false
        searching = true
        val listenerSnap = listener
        val thread = Thread({
            try {
                val bestMove = search(params)
                if (!stopRequested) {
                    listenerSnap?.onBestMove(moveToUci(bestMove), null)
                }
            } catch (e: Exception) {
                if (!stopRequested) listenerSnap?.onError(e.message ?: "engine error")
            } finally {
                searching = false
            }
        }, "SimpleEngine-search")
        thread.isDaemon = true
        searchThread = thread
        thread.start()
    }

    override fun stopSearch() {
        stopRequested = true
        val thread = searchThread
        searchThread = null
        if (thread != null && thread.isAlive) {
            // 搜索循环会主动检查 stopRequested,这里等它退出即可(不 interrupt,
            // 免得把中断状态留给后续代码)
            try {
                thread.join(STOP_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        searching = false
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

    /**
     * 迭代加深搜索.
     *
     * 根节点自己跑着法循环并记录最佳着法 —— 不再另外调用一次全深度搜索来
     * "找回"最佳着法(那等于每层把整棵树搜两遍).
     */
    private fun search(params: SearchParams): Move {
        // 开局阶段直接用棋谱, 省搜索时间, 也让低难度开局像样一些
        bookMove()?.let { move ->
            // 查库是瞬时的. 直接返回会让对手"零秒落子", 既不自然, 也会把
            // 玩家那一步的走子动画立刻打断, 所以补一段最短思考时间.
            waitBriefly(minOf(BOOK_MIN_THINK_MS, params.movetime.takeIf { it > 0 } ?: BOOK_MIN_THINK_MS))
            return move
        }

        deadline = when {
            params.movetime > 0 -> System.currentTimeMillis() + params.movetime
            params.infinite -> Long.MAX_VALUE
            else -> System.currentTimeMillis() + DEFAULT_MOVETIME_MS
        }
        timeUp = false
        // 置换表按局面复用没有意义(每次搜索局面都变了),清空避免无限增长
        tt.clear()

        val rootMoves = mutableListOf<Move>()
        Rules.generateAllLegal(currentPosition, rootMoves)
        if (rootMoves.isEmpty()) return Move.empty()

        val maxDepthTarget = if (params.depth > 0) params.depth else maxDepth
        val nodes = longArrayOf(0)
        val startTime = System.currentTimeMillis()
        val rootScores = HashMap<Int, Int>(rootMoves.size * 2)

        var bestMove = rootMoves[0]
        var bestScore = 0

        for (depth in 1..maxDepthTarget) {
            // 剩余时间不够搜完下一层就别开了 —— 下一层通常要花上一层数倍的时间,
            // 硬开只会大幅超时,而且中途放弃的结果也用不上
            if (depth > 1 && System.currentTimeMillis() + (System.currentTimeMillis() - startTime) *
                NEXT_ITERATION_COST_RATIO > deadline
            ) break

            var iterBest = Move.empty()
            var iterBestScore = Int.MIN_VALUE
            var alpha = MIN_SCORE
            val iterScores = HashMap<Int, Int>(rootMoves.size * 2)

            for (m in rootMoves) {
                currentPosition.doMove(m)
                val score = -alphaBeta(currentPosition, depth - 1, MAX_SCORE.unaryMinus(), -alpha, nodes, 1)
                currentPosition.undoMove()
                if (aborted(nodes[0])) break

                iterScores[m.data] = score
                if (score > iterBestScore) {
                    iterBestScore = score
                    iterBest = m
                }
                if (score > alpha) alpha = score
            }

            // 本层至少完整评估了一个着法就采用它的结果
            if (iterBestScore > Int.MIN_VALUE) {
                bestMove = iterBest
                bestScore = iterBestScore
                rootScores.clear()
                rootScores.putAll(iterScores)
                // 最佳着法提到最前,下一层优先搜索,剪枝更有效
                rootMoves.remove(iterBest)
                rootMoves.add(0, iterBest)
            }

            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
            listener?.onInfo(
                EngineInfo(
                    depth = depth,
                    timeMs = elapsed,
                    nodes = nodes[0],
                    nps = nodes[0] * 1000 / elapsed,
                    scoreCp = if (isMateScore(bestScore)) 0 else bestScore,
                    scoreMate = mateDistance(bestScore),
                    pv = listOf(moveToUci(bestMove))
                )
            )

            if (aborted(nodes[0]) || isMateScore(bestScore)) break
        }

        return pickWithRandomness(rootMoves, rootScores, bestMove, bestScore)
    }

    /**
     * 低难度时故意不总选最优着法, 让 1-4 级真的"下得弱"而不只是"想得浅".
     */
    private fun pickWithRandomness(
        rootMoves: List<Move>,
        scores: Map<Int, Int>,
        bestMove: Move,
        bestScore: Int
    ): Move {
        if (randomness <= 0 || scores.isEmpty()) return bestMove
        val tolerance = randomness * RANDOMNESS_CP_PER_LEVEL
        val candidates = rootMoves.filter { (scores[it.data] ?: Int.MIN_VALUE) >= bestScore - tolerance }
        return if (candidates.isEmpty()) bestMove else candidates[Random.nextInt(candidates.size)]
    }

    private fun alphaBeta(pos: Position, depth: Int, alpha: Int, beta: Int, nodes: LongArray, ply: Int): Int {
        nodes[0]++
        if (aborted(nodes[0])) return 0

        // 叶节点: 先做静态搜索消化吃子连锁, 否则会出现"地平线效应"(白送子)
        if (depth <= 0) return quiescence(pos, alpha, beta, nodes, ply)

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
            // 将死 / 困毙. 减去 ply 让"更快的杀"分数更高
            return if (Rules.isInCheck(pos, pos.sideToMove)) -MATE_SCORE + ply else 0
        }

        sortByMvvLva(pos, moves)

        var bestScore = Int.MIN_VALUE
        var bestFlag = TT_UPPER
        var alphaLocal = alpha

        for (m in moves) {
            pos.doMove(m)
            val score = -alphaBeta(pos, depth - 1, -beta, -alphaLocal, nodes, ply + 1)
            pos.undoMove()

            if (aborted(nodes[0])) return 0
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

        if (tt.size < TT_MAX_ENTRIES) {
            tt[key] = TTEntry(depth, bestScore, bestFlag)
        }
        return bestScore
    }

    /** 静态搜索: 只继续搜吃子着法,直到局面"安静"下来 */
    private fun quiescence(pos: Position, alpha: Int, beta: Int, nodes: LongArray, ply: Int): Int {
        nodes[0]++
        if (aborted(nodes[0])) return 0

        val standPat = evaluate(pos)
        if (standPat >= beta) return standPat
        if (ply >= MAX_QUIESCENCE_PLY) return standPat

        var alphaLocal = if (standPat > alpha) standPat else alpha

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)
        val captures = moves.filter { pos.pieceAt(it.to) != Piece.NONE.code }
        if (captures.isEmpty()) return standPat
        val ordered = captures.toMutableList()
        sortByMvvLva(pos, ordered)

        var best = standPat
        for (m in ordered) {
            pos.doMove(m)
            val score = -quiescence(pos, -beta, -alphaLocal, nodes, ply + 1)
            pos.undoMove()
            if (aborted(nodes[0])) return 0
            if (score > best) best = score
            if (score > alphaLocal) alphaLocal = score
            if (alphaLocal >= beta) break
        }
        return best
    }

    /** 吃子优先排序 (MVV-LVA) */
    private fun sortByMvvLva(pos: Position, moves: MutableList<Move>) {
        for (m in moves) {
            m.score = m.mvvLva(pos.pieceAt(m.to), pos.pieceAt(m.from))
        }
        moves.sortByDescending { it.score }
    }

    /** 分段等待, 期间随时响应 stop */
    private fun waitBriefly(totalMs: Long) {
        val until = System.currentTimeMillis() + totalMs
        while (!stopRequested && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** 是否该中止搜索(收到 stop 或超时). 每 1024 个节点才查一次时钟. */
    private fun aborted(nodeCount: Long): Boolean {
        if (stopRequested || timeUp) return true
        if (nodeCount and TIME_CHECK_MASK == 0L && System.currentTimeMillis() >= deadline) {
            timeUp = true
            return true
        }
        return false
    }

    private fun isMateScore(score: Int): Boolean = kotlin.math.abs(score) >= MATE_SCORE - MAX_MATE_PLY

    private fun mateDistance(score: Int): Int = when {
        score >= MATE_SCORE - MAX_MATE_PLY -> (MATE_SCORE - score + 1) / 2
        score <= -MATE_SCORE + MAX_MATE_PLY -> -((MATE_SCORE + score + 1) / 2)
        else -> 0
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

    /**
     * 棋子位置分 (Piece-Square Table).
     *
     * 所有表都按**红方视角**定义(row 9 = 己方底线, row 0 = 对方底线);
     * 黑方沿横轴镜像取值,不需要各自维护一份表.
     */
    private fun pstValue(type: Int, sq: Int, color: Int): Int {
        val row = Square.row(sq)
        val r = if (color == Color.RED) row else Square.ROWS - 1 - row
        val c = Square.col(sq)
        return when (type) {
            1 -> kingPst[r][c]
            2, 3 -> 0  // 仕/相 位置基本固定, 不额外加分
            4 -> horsePst[r][c]
            5 -> rookPst[r][c]
            6 -> cannonPst[r][c]
            7 -> pawnPst[r][c]
            else -> 0
        }
    }

    // 简化的位置表(红方视角, 可后续通过学习优化)
    private val kingPst = arrayOf(
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

    // 兵: 红方向 row 变小的方向前进, 所以越靠近 row 0 分越高.
    // (row 0 是对方底线, 兵到底线只能横move, 价值反而回落)
    private val pawnPst = arrayOf(
        intArrayOf(0, 0, 0, 10, 15, 10, 0, 0, 0),
        intArrayOf(10, 15, 25, 40, 50, 40, 25, 15, 10),
        intArrayOf(15, 25, 40, 55, 60, 55, 40, 25, 15),
        intArrayOf(10, 20, 30, 40, 45, 40, 30, 20, 10),
        intArrayOf(5, 10, 15, 20, 25, 20, 15, 10, 5),
        intArrayOf(0, 0, 5, 8, 10, 8, 5, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0)
    )

    // ====== 辅助: UCI 转换 ======

    private fun applyUciMove(pos: Position, uci: String) {
        val move = parseUci(uci) ?: return
        if (pos.isLegalMove(move)) pos.doMove(move)
    }

    /**
     * 解析 UCI 坐标, 如 "b2e2".
     * UCI: 列 a-i = 0-8, 行 0-9 且 0 = 红方底线;
     * 内部表示: row 0 = 黑方底线, row 9 = 红方底线, 故 row = 9 - rank.
     */
    private fun parseUci(uci: String): Move? {
        if (uci.length < 4) return null
        return try {
            val fromCol = uci[0] - 'a'
            val fromRow = 9 - uci[1].digitToInt()
            val toCol = uci[2] - 'a'
            val toRow = 9 - uci[3].digitToInt()
            if (!Square.valid(fromRow, fromCol) || !Square.valid(toRow, toCol)) return null
            Move.make(Square.make(fromRow, fromCol), Square.make(toRow, toCol))
        } catch (_: Exception) {
            null
        }
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

        /** 将死分值. 实际分数是 MATE_SCORE - ply, 越快的杀分越高 */
        private const val MATE_SCORE = 30000
        private const val MAX_MATE_PLY = 200
        private const val MAX_SCORE = Int.MAX_VALUE - 1
        private const val MIN_SCORE = Int.MIN_VALUE + 1

        /** 静态搜索最大深度,防止连吃链把栈搜爆 */
        private const val MAX_QUIESCENCE_PLY = 32
        /** 未指定时限时的默认思考时间 */
        private const val DEFAULT_MOVETIME_MS = 3000L
        /** 停止搜索时等待工作线程退出的上限 */
        private const val STOP_JOIN_TIMEOUT_MS = 2000L
        /** 置换表条目上限,防止长时间搜索吃光内存 */
        private const val TT_MAX_ENTRIES = 400_000
        /** 每级随机性容忍的分差(厘兵) */
        private const val RANDOMNESS_CP_PER_LEVEL = 60
        /** 走开局库时的最短"思考"时间,避免零秒落子 */
        private const val BOOK_MIN_THINK_MS = 450L
        /** 预估下一层迭代相对已用时间的倍数, 用于判断还要不要再加深 */
        private const val NEXT_ITERATION_COST_RATIO = 0.6
        /** 检查时钟的节点间隔掩码 */
        private const val TIME_CHECK_MASK = 0x1FFL
    }
}