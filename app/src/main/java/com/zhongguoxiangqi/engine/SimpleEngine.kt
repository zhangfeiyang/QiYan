package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.engine.nnue.NnueFeatures
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.MoveGenerator
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.Square
import com.zhongguoxiangqi.game.UciCoords
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

    /**
     * 杀手着法: 每一层记两个"曾经造成 beta 截断的安静着法".
     *
     * 同一层的不同分支往往能被同一步棋反驳(比如对方老是那一炮打过来), 记下来
     * 优先试, 截断能来得更早.
     */
    private val killers = Array(MAX_PLY) { IntArray(2) }

    /**
     * 历史启发: 按 (起点, 终点) 累计"这一步造成过多少次截断", 深度越深权重越大.
     * 和杀手表互补 —— 杀手只管本层, 历史是跨层的全局倾向.
     */
    private val history = Array(Square.COUNT) { IntArray(Square.COUNT) }

    // quiescence 复用不走分配，避免每层 new ArrayList → GC 风暴
    private val qMoves = mutableListOf<Move>()

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
        // NnueEvaluator 里握着 native 句柄(权重 + 缓冲, 六百多 KB), GC 管不到
        // 它。每开一局漏一份, 反复进出对局就是稳定的原生内存泄漏。
        nnue?.close()
        nnue = null
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
        // 启发表也要清: 上一盘的统计对这一盘没有参考价值, 留着反而误导排序
        for (k in killers) { k[0] = 0; k[1] = 0 }
        for (row in history) row.fill(0)

        val rootMoves = mutableListOf<Move>()
        Rules.generateAllLegal(currentPosition, rootMoves)
        if (rootMoves.isEmpty()) return Move.empty()
        accRefreshRoot()

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
                if (!doMoveAcc(currentPosition, m)) continue
                val score = -alphaBeta(currentPosition, depth - 1, MAX_SCORE.unaryMinus(), -alpha, nodes, 1)
                undoMoveAcc(currentPosition)
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

        orderMoves(pos, moves, ttEntry?.move ?: 0, ply)

        var bestScore = Int.MIN_VALUE
        var bestFlag = TT_UPPER
        var bestMoveData = 0
        var alphaLocal = alpha
        var searched = 0

        for (m in moves) {
            val capture = pos.pieceAt(m.to) != Piece.NONE.code
            if (!doMoveAcc(pos, m)) continue
            // PVS: 第一手用完整窗口, 后面的先用零窗口试探.
            // 排序做得好时绝大多数着法都会在零窗口下被直接否掉, 省下大量节点;
            // 万一试探失败再用完整窗口重搜一次, 那点重复成本远小于收益.
            var score: Int
            if (searched == 0) {
                score = -alphaBeta(pos, depth - 1, -beta, -alphaLocal, nodes, ply + 1)
            } else {
                // LMR: 排在后面的安静着法多半是坏棋, 先按减一层的深度草草看一眼,
                // 真有价值再补搜. 吃子和将军不减 —— 那些正是战术发生的地方.
                val reduction = if (depth >= LMR_MIN_DEPTH && searched >= LMR_MIN_MOVES &&
                    !capture && !Rules.isInCheck(pos, pos.sideToMove)
                ) 1 else 0
                score = -alphaBeta(pos, depth - 1 - reduction, -alphaLocal - 1, -alphaLocal, nodes, ply + 1)
                if (score > alphaLocal && (reduction > 0 || score < beta)) {
                    score = -alphaBeta(pos, depth - 1, -beta, -alphaLocal, nodes, ply + 1)
                }
            }
            undoMoveAcc(pos)
            searched++

            if (aborted(nodes[0])) return 0
            if (score > bestScore) {
                bestScore = score
                bestMoveData = m.data
            }
            if (score > alphaLocal) {
                alphaLocal = score
                bestFlag = TT_EXACT
            }
            if (alphaLocal >= beta) {
                bestFlag = TT_LOWER
                // 造成截断的安静着法记进杀手/历史表, 下次在同层优先试
                if (!capture) recordCutoff(m, depth, ply)
                break
            }
        }

        storeTT(key, depth, bestScore, bestFlag, bestMoveData)
        return bestScore
    }

    /** 记录造成截断的安静着法 */
    private fun recordCutoff(m: Move, depth: Int, ply: Int) {
        if (ply < MAX_PLY) {
            val k = killers[ply]
            if (k[0] != m.data) { k[1] = k[0]; k[0] = m.data }
        }
        // depth 平方: 深处的截断比浅处更有说服力
        history[m.from][m.to] += depth * depth
    }

    /**
     * 着法排序. 顺序决定剪枝效率, 是搜索里性价比最高的一环.
     *
     *   1. 置换表着法  —— 上一轮迭代加深算出的最佳步, 命中率最高
     *   2. 吃子        —— 按 MVV-LVA (吃大子优先, 用小子吃优先)
     *   3. 杀手着法    —— 本层曾造成截断的安静着法
     *   4. 历史分      —— 全局统计上更容易造成截断的安静着法
     */
    private fun orderMoves(pos: Position, moves: MutableList<Move>, ttMove: Int, ply: Int) {
        val k0 = if (ply < MAX_PLY) killers[ply][0] else 0
        val k1 = if (ply < MAX_PLY) killers[ply][1] else 0
        moves.sortByDescending { m ->
            when {
                m.data == ttMove -> ORDER_TT
                else -> {
                    val victim = pos.pieceAt(m.to)
                    if (victim != Piece.NONE.code) {
                        // 复用已有的 MVV-LVA 打分, 不另造一套子力估值
                        ORDER_CAPTURE + m.mvvLva(victim, pos.pieceAt(m.from))
                    } else when (m.data) {
                        k0 -> ORDER_KILLER_1
                        k1 -> ORDER_KILLER_2
                        else -> history[m.from][m.to].coerceAtMost(ORDER_HISTORY_CAP)
                    }
                }
            }
        }
    }

    /**
     * 写置换表, 深度优先替换.
     *
     * 原来是 `if (tt.size < TT_MAX_ENTRIES) tt[key] = ...` —— 一旦写满 40 万条
     * 就**彻底停止接受任何写入**, 连"同一局面已搜到更深结果"的覆盖也被挡掉.
     * 于是长搜索里置换表冻结成早期浅层结果的快照, 不但失效, 深度浅的旧条目
     * 还会一直被当成有效命中返回, 反过来误导搜索.
     */
    private fun storeTT(key: Long, depth: Int, score: Int, flag: Int, moveData: Int) {
        val old = tt[key]
        // 已有条目更深就别覆盖: 深的结果更可信
        if (old != null && old.depth > depth) return
        if (old == null && tt.size >= TT_MAX_ENTRIES) {
            // 满了就整表清空重来. 比逐条淘汰简单, 而且一次搜索里通常只会发生
            // 一两次; 真正要避免的是"满了之后永远不写".
            tt.clear()
        }
        tt[key] = TTEntry(depth, score, flag, moveData)
    }

    /** 静态搜索: 只继续搜吃子着法,直到局面"安静"下来 */
    private fun quiescence(pos: Position, alpha: Int, beta: Int, nodes: LongArray, ply: Int): Int {
        nodes[0]++
        if (aborted(nodes[0])) return 0

        val standPat = evaluate(pos)
        if (standPat >= beta) return standPat
        if (ply >= MAX_QUIESCENCE_PLY) return standPat

        var alphaLocal = if (standPat > alpha) standPat else alpha

        qMoves.clear()
        MoveGenerator.generateCaptures(pos, qMoves)
        if (qMoves.isEmpty()) return standPat
        sortByMvvLva(pos, qMoves)
        // 递归 quiescence 会清 qMoves, 必须在进入循环前拷出来
        val caps = ArrayList(qMoves)

        var best = standPat
        for (m in caps) {
            if (!doMoveAcc(pos, m)) continue
            val score = -quiescence(pos, -beta, -alphaLocal, nodes, ply + 1)
            undoMoveAcc(pos)
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

    /**
     * 固定深度搜一次, 返回访问的节点数. 只给基准测试用.
     *
     * 衡量搜索优化只能看"同深度下的节点数" —— 排序变好, 剪枝更早发生, 节点数
     * 就下降. 这比"感觉变强了"实在得多.
     */
    internal fun benchmark(depth: Int): Long {
        deadline = Long.MAX_VALUE
        timeUp = false
        stopRequested = false
        tt.clear()
        for (k in killers) { k[0] = 0; k[1] = 0 }
        for (row in history) row.fill(0)

        val nodes = longArrayOf(0)
        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(currentPosition, moves)
        accRefreshRoot()
        var alpha = MIN_SCORE
        for (m in moves) {
            if (!doMoveAcc(currentPosition, m)) continue
            val sc = -alphaBeta(currentPosition, depth - 1, MAX_SCORE.unaryMinus(), -alpha, nodes, 1)
            undoMoveAcc(currentPosition)
            if (sc > alpha) alpha = sc
        }
        return nodes[0]
    }

    /** 固定深度搜出最佳着法, 只给测试用 */
    internal fun searchBestMoveForTest(depth: Int): Move {
        deadline = Long.MAX_VALUE
        timeUp = false
        stopRequested = false
        return search(SearchParams(depth = depth, movetime = 0))
    }

    /** 直接设定局面, 跳过 FEN 往返. 数据生成时每步都要调, 省掉字符串开销 */
    /**
     * 只跑评估函数, 返回耗时纳秒. 用来和 NNUE 的求值速度做同口径比较 ——
     * NNUE 换来的棋力只有在搜索速度没被拖垮的前提下才成立.
     */
    internal fun benchmarkEval(pos: Position, times: Int): Long {
        var sink = 0
        repeat(20_000) { sink += handcraftedEval(pos) }   // 预热 JIT
        val t0 = System.nanoTime()
        repeat(times) { sink += handcraftedEval(pos) }
        val ns = System.nanoTime() - t0
        if (sink == Int.MIN_VALUE) println("")     // 防止被优化掉
        return ns
    }

    internal fun setPositionDirect(pos: Position) {
        currentPosition = pos.copy()
    }

    /**
     * 固定深度搜出 (最佳着法, 分数). 分数是**走子方视角**的厘兵分.
     *
     * 自对弈数据生成用: 走这一步本来就要搜, 顺手把分数带出来当标签, 不必
     * 再单独深搜一遍 —— 这一下把数据生成的成本砍掉一半.
     */
    internal fun searchWithScore(depth: Int): Pair<Move, Int> {
        deadline = Long.MAX_VALUE
        timeUp = false
        stopRequested = false
        tt.clear()
        for (k in killers) { k[0] = 0; k[1] = 0 }
        for (row in history) row.fill(0)

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(currentPosition, moves)
        if (moves.isEmpty()) return Move.empty() to 0

        val nodes = longArrayOf(0)
        accRefreshRoot()

        // 迭代加深 + 根节点重排序.
        //
        // 以前这里直接冲到目标深度, 而且根节点的着法就按走法生成的顺序搜
        // (基本等于按格子编号, 也就是随机). alpha-beta 的剪枝效率全靠"最好的
        // 着法先搜": 第一个搜的着法定下初始 alpha, 它要是平庸, 后面几乎剪不掉。
        // 逐层加深的额外开销是等比级数(浅层加起来才占深层的几分之一), 换来的
        // 是每层都拿上一层的最佳着法打头 —— 深度越大越划算。
        //
        // 这不改变返回的分数: 同一深度下 alpha-beta 的极小极大值是确定的,
        // 变的只是到达它要走多少节点。IterativeDeepeningTest 锁住这一点。
        var best = moves[0]
        var alpha = MIN_SCORE
        val firstDepth = if (useIterativeDeepening) 1 else depth
        for (d in firstDepth..depth) {
            var iterBest = moves[0]
            var iterAlpha = MIN_SCORE
            for (m in moves) {
                if (!doMoveAcc(currentPosition, m)) continue
                val sc = -alphaBeta(currentPosition, d - 1, MAX_SCORE.unaryMinus(), -iterAlpha, nodes, 1)
                undoMoveAcc(currentPosition)
                if (sc > iterAlpha) { iterAlpha = sc; iterBest = m }
            }
            best = iterBest
            alpha = iterAlpha
            // 最佳着法提到最前, 下一层第一个就搜它
            moves.remove(iterBest)
            moves.add(0, iterBest)
        }
        lastSearchNodes = nodes[0]
        return best to alpha
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
     * 测试用: 每次求值都拿增量结果和全量重算对一遍, 不一致就计数.
     *
     * 增量累加器一旦和 make/unmake 对不上, 症状是分数慢慢漂移 —— 不崩溃,
     * 只是棋力莫名其妙地差. 单测在真实搜索树上打开这个开关跑一遍, 任何一处
     * push/pop 没配对都会立刻暴露.
     */
    internal var accVerify = false
    internal var accMismatches = 0L

    /**
     * 基准测试用: 关掉迭代加深, 直接冲到目标深度(改动前的行为).
     *
     * 只为了能在**同一个进程**里做 A/B —— 换进程量到的是 JIT 预热和睿频的
     * 差别, 上一轮 SIMD 实验就被这么骗过一次.
     */
    internal var useIterativeDeepening = true

    /** 上一次 [searchWithScore] 访问的节点数. 给基准测试看剪枝效果. */
    internal var lastSearchNodes = 0L

    /**
     * 局面评估. 返回**走子方视角**的厘兵分(正值 = 走子方占优),
     * 与 negamax 约定一致.
     */
    private fun evaluate(pos: Position): Int {
        val ev = nnue ?: return handcraftedEval(pos)
        // 累加器跟着搜索的 make/unmake 一路增量维护, 到这里直接读栈顶就行,
        // 省掉 32 个子 × 256 维 × 2 视角的全量重算.
        if (ev.accUsable) {
            val inc = ev.accEval(pos.sideToMove == Color.BLACK)
            if (accVerify && inc != ev.evaluate(pos)) accMismatches++
            return inc
        }
        return ev.evaluate(pos)
    }

    // ---- 增量累加器与搜索的 make/unmake 绑定 ----
    //
    // 只包搜索自己的走子/悔棋. **不能**下沉到 Position.doMove ——
    // 走法生成器为每个伪合法着法都要调一次 leavesKingSafe(内部就是一次
    // doMove/undoMove) 来试将军, 那些是纯试探, 位置原样还回来. 把累加器
    // 更新挂到 Position 上, 等于给每个候选着法都算一遍, 比不做增量还慢.

    /** 进搜索前重建累加器. 之后每一步都靠 [doMoveAcc] / [undoMoveAcc] 推进. */
    private fun accRefreshRoot() {
        nnue?.takeIf { it.incrementalAvailable }?.accRefresh(currentPosition)
    }

    /**
     * 走子并同步累加器. 返回 false 表示走法非法(累加器已还原).
     *
     * 棋子编码必须在 `doMove` **之前**读: 走完之后起点已经空了, 落点上原本
     * 被吃的子也被覆盖掉了.
     */
    private fun doMoveAcc(pos: Position, m: Move): Boolean {
        val ev = nnue?.takeIf { it.incrementalAvailable }
        var moverIdx = 0
        var capIdx = -1
        if (ev != null) {
            moverIdx = NnueFeatures.pieceIndex(pos.pieceAt(m.from))
            val capCode = pos.pieceAt(m.to)
            if (capCode != Piece.NONE.code) capIdx = NnueFeatures.pieceIndex(capCode)
        }
        if (!pos.doMove(m)) return false
        ev?.accPush(moverIdx, m.from, m.to, capIdx)
        return true
    }

    private fun undoMoveAcc(pos: Position) {
        pos.undoMove()
        nnue?.takeIf { it.incrementalAvailable }?.accPop()
    }

    /**
     * 装上 NNUE 之后, 求值改走网络; 传 null 退回手写评估.
     *
     * 做成可切换而不是直接替换, 是因为"网络到底值不值"只能靠两者实战对打
     * 来回答 —— 网络求值比手写评估慢一到两个数量级, 准是准了, 但搜索会浅
     * 好几层, 净收益是正是负得测. 见 NnueGauntletRunner.
     */
    fun setNnue(evaluator: com.zhongguoxiangqi.engine.nnue.NnueEvaluator?) {
        if (nnue === evaluator) return
        nnue?.close()
        nnue = evaluator
    }

    private var nnue: com.zhongguoxiangqi.engine.nnue.NnueEvaluator? = null

    /** 子力价值 + 棋子位置分(PST) */
    private fun handcraftedEval(pos: Position): Int {
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

    /** 解析 UCI 坐标, 如 "b2e2". 行方向的换算见 [UciCoords]. */
    private fun parseUci(uci: String): Move? = UciCoords.parse(uci)

    private fun moveToUci(move: Move): String = UciCoords.toUci(move)

    // ====== 置换表 ======

    /**
     * [move] 是这个局面上次搜出来的最佳着法.
     *
     * 存它是为了下一轮迭代加深时**优先试这一步** —— 这是所有着法排序技术里
     * 收益最大的一项: 上一层已经算出的好棋, 这一层大概率还是好棋, 先试它能
     * 立刻把 beta 截断推到最前面.
     */
    private data class TTEntry(val depth: Int, val score: Int, val flag: Int, val move: Int)

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
        private const val MAX_QUIESCENCE_PLY = 6
        /** 未指定时限时的默认思考时间 */
        private const val DEFAULT_MOVETIME_MS = 3000L
        /** 停止搜索时等待工作线程退出的上限 */
        private const val STOP_JOIN_TIMEOUT_MS = 2000L
        /** 置换表条目上限,防止长时间搜索吃光内存 */
        private const val TT_MAX_ENTRIES = 400_000

        /** 杀手表按层存, 这是层数上限 */
        private const val MAX_PLY = 64

        // 排序分档. 拉开足够大的间距, 保证高优先级永远排在低优先级前面
        private const val ORDER_TT = 1_000_000
        private const val ORDER_CAPTURE = 100_000
        private const val ORDER_KILLER_1 = 90_000
        private const val ORDER_KILLER_2 = 80_000
        private const val ORDER_HISTORY_CAP = 70_000

        /** LMR: 到这个深度以上、且已经试过这么多手之后才开始减层 */
        private const val LMR_MIN_DEPTH = 3
        private const val LMR_MIN_MOVES = 4
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