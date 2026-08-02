package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.engine.nnue.NnueFeatures
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.MoveGenerator
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.See
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

    // 置换表: 两个平行的定长数组, 不用 HashMap.
    //
    // HashMap<Long,TTEntry> 每次探测都要给 key 装箱、走一遍哈希桶、再解引用一个
    // 对象; 一次深度 6 的搜索有十几万节点, 就是十几万次装箱加对象分配, GC 压力
    // 和缓存不友好都在这里。扁平数组只有两次数组访问。
    //
    // 用**世代号**而不是每次搜索清表: 清 8MB 要 0.8ms, 而数据生成每秒几百次
    // 搜索, 光清表就能吃掉一半 CPU。世代号对不上的条目直接当空的, 零成本失效。
    private val ttKey = LongArray(TT_SIZE)
    private val ttData = LongArray(TT_SIZE)
    private var ttGen = 0

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
        // 置换表按局面复用没有意义(每次搜索局面都变了), 世代号 +1 让旧条目
        // 整体失效 —— 比清 8MB 数组便宜得多
        ttGen = (ttGen + 1) and 0xFF
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

        var prevScore = 0
        for (depth in 1..maxDepthTarget) {
            // 剩余时间不够搜完下一层就别开了 —— 下一层通常要花上一层数倍的时间,
            // 硬开只会大幅超时,而且中途放弃的结果也用不上
            if (depth > 1 && System.currentTimeMillis() + (System.currentTimeMillis() - startTime) *
                NEXT_ITERATION_COST_RATIO > deadline
            ) break

            // 渐进窗口, 原理同 searchWithScore 里那份(那边的注释写了为什么
            // 只收紧下限、不做 fail-high 重搜)。这里额外多一条: 猜错要重搜时,
            // 如果这一遍是被"没时间了"打断的(不是真的没找到超过下限的着法),
            // 就不重搜 —— 时间都不够跑完一遍窄窗口, 再来一遍全窗口只会白费,
            // 该用的是已经拿到的部分结果(下面 iterBestScore > MIN_VALUE 那条
            // 老规矩), 而不是徒劳地多等一轮。
            var seededAlpha = if (depth >= ASPIRATION_MIN_DEPTH && depth > 1) {
                (prevScore - ASPIRATION_WINDOW).coerceAtLeast(MIN_SCORE)
            } else {
                MIN_SCORE
            }
            var iterBest: Move
            var iterBestScore: Int
            var iterScores: HashMap<Int, Int>
            var completed: Boolean
            while (true) {
                iterBest = Move.empty()
                iterBestScore = Int.MIN_VALUE
                var alpha = seededAlpha
                iterScores = HashMap(rootMoves.size * 2)
                completed = true

                for (m in rootMoves) {
                    if (!doMoveAcc(currentPosition, m)) continue
                    val score = -alphaBeta(currentPosition, depth - 1, MAX_SCORE.unaryMinus(), -alpha, nodes, 1)
                    undoMoveAcc(currentPosition)
                    if (aborted(nodes[0])) { completed = false; break }

                    iterScores[m.data] = score
                    if (score > iterBestScore) {
                        iterBestScore = score
                        iterBest = m
                    }
                    if (score > alpha) alpha = score
                }

                if (completed && iterBestScore <= seededAlpha && seededAlpha > MIN_SCORE) {
                    seededAlpha = MIN_SCORE
                    continue
                }
                break
            }

            // 本层至少完整评估了一个着法就采用它的结果
            if (iterBestScore > Int.MIN_VALUE) {
                prevScore = iterBestScore
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

    private fun alphaBeta(
        pos: Position, depth: Int, alpha: Int, beta: Int, nodes: LongArray, ply: Int,
        allowNull: Boolean = true,
    ): Int {
        nodes[0]++
        if (aborted(nodes[0])) return 0

        // 叶节点: 先做静态搜索消化吃子连锁, 否则会出现"地平线效应"(白送子)
        if (depth <= 0) return quiescence(pos, alpha, beta, nodes, ply, 0)

        // 置换表查询
        val key = pos.hash
        val slot = ttSlot(key)
        val hit = ttKey[slot] == key && ttGenOf(ttData[slot]) == ttGen
        val ttMove = if (hit) ttMoveOf(ttData[slot]) else 0
        if (hit && ttDepthOf(ttData[slot]) >= depth) {
            val sc = ttScoreOf(ttData[slot])
            when (ttFlagOf(ttData[slot])) {
                TT_EXACT -> return sc
                TT_LOWER -> if (sc >= beta) return sc
                TT_UPPER -> if (sc <= alpha) return sc
            }
        }

        // 本节点是否被将. 提到这里算一次, 下面的空着裁剪和将死判定共用 ——
        // 以前只在 moves 为空时才算, 现在空着裁剪也要用, 分开算等于算两遍。
        val inCheck = Rules.isInCheck(pos, pos.sideToMove)

        // 静态裁剪(reverse futility): 静态分已经高出 beta 一大截, 而剩余深度
        // 有限, 对手不太可能把这么大的差距翻回来 —— 直接返回, 不展开子树。
        // 余量按深度线性给: 深度越大, 局面还能变化的空间越大, 门槛就要越高。
        //
        // 被将时不裁: 那时静态分毫无意义(下一步可能就被将死)。
        // 逼近杀棋分时也不裁: 杀棋分不是"子力优势", 拿它做线性外推没有意义。
        if (useRfp && !inCheck && depth <= RFP_MAX_DEPTH && beta < MATE_SCORE - MAX_PLY) {
            val staticEval = evaluate(pos)
            if (staticEval - RFP_MARGIN_PER_DEPTH * depth >= beta) return staticEval
        }

        // 空着裁剪: 把行棋权白送给对手, 用一个缩减深度的零窗口搜一下。
        // 如果连"白送一步"都还能把对手压在 beta 之下, 说明这个分支我方优势足够大,
        // 不值得细搜, 直接剪掉。这是标准引擎里单项收益最大的剪枝。
        //
        // 三个前提缺一不可:
        // - 不能被将: 被将时"不走"是非法的, 搜出来的分毫无意义
        // - 不能在零窗口验证里递归用: 靠 allowNull 断掉, 否则会连续空着自欺
        // - 子力不能太薄: 残局里"必须走"本身就是负担(欠行), "我不走反而更好"
        //   确实会发生, 那时空着裁剪会把真实的危险剪掉
        if (useNullMove && allowNull && !inCheck && depth >= NULL_MOVE_MIN_DEPTH &&
            beta < MATE_SCORE - MAX_PLY && pos.hasThreateningPiece(pos.sideToMove)
        ) {
            pos.doNullMove()
            val nullScore = -alphaBeta(
                pos, depth - 1 - NULL_MOVE_REDUCTION, -beta, -beta + 1,
                nodes, ply + 1, allowNull = false
            )
            pos.undoNullMove()
            if (aborted(nodes[0])) return 0
            // 只在非杀棋分上采信: 空着搜出来的杀是"对手不走才成立"的假杀
            if (nullScore >= beta && nullScore < MATE_SCORE - MAX_PLY) return nullScore
        }

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)

        if (moves.isEmpty()) {
            // 将死 / 困毙. 减去 ply 让"更快的杀"分数更高
            return if (inCheck) -MATE_SCORE + ply else 0
        }

        orderMoves(pos, moves, ttMove, ply)

        var bestScore = Int.MIN_VALUE
        var bestFlag = TT_UPPER
        var bestMoveData = 0
        var alphaLocal = alpha
        var searched = 0
        // 本节点已试过的安静着法, 顺序记录, 用来给"没造成截断"的那些打历史惩罚
        val quietsTried = ArrayList<Move>(8)

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
                // LMR: 排在后面的安静着法多半是坏棋, 先按缩减的深度草草看一眼,
                // 真有价值再补搜. 吃子和将军不减 —— 那些正是战术发生的地方.
                //
                // 削减量按 ln(深度)*ln(着法序号) 查表, 不再是固定减 1。理由是
                // 固定值在两头都不对: 深度 12 的第 30 手明显该多减几层, 而深度 3
                // 的第 5 手减 1 层已经嫌多。这个形状取自 Pikafish(同为象棋引擎,
                // 参数在象棋上调过), 但**削减量刻意取保守**: 它敢那么激进是因为
                // 有 singular extension 把"明显最好的那手"从削减里捞出来, 我们
                // 没有那层保护, 照抄会把关键着法一起削掉。
                val reduction = if (useLmrTable) {
                    if (depth >= LMR_MIN_DEPTH && searched >= LMR_MIN_MOVES &&
                        !capture && !Rules.isInCheck(pos, pos.sideToMove)
                    ) lmrReduction(depth, searched).coerceAtMost(depth - 1) else 0
                } else {
                    if (depth >= LMR_MIN_DEPTH && searched >= LMR_MIN_MOVES &&
                        !capture && !Rules.isInCheck(pos, pos.sideToMove)
                    ) 1 else 0
                }
                score = -alphaBeta(pos, depth - 1 - reduction, -alphaLocal - 1, -alphaLocal, nodes, ply + 1)
                if (score > alphaLocal && (reduction > 0 || score < beta)) {
                    score = -alphaBeta(pos, depth - 1, -beta, -alphaLocal, nodes, ply + 1)
                }
            }
            undoMoveAcc(pos)
            searched++
            if (!capture) quietsTried.add(m)

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
                if (!capture) recordCutoff(m, depth, ply, quietsTried)
                break
            }
        }

        storeTT(key, depth, bestScore, bestFlag, bestMoveData)
        return bestScore
    }

    /**
     * 记录造成截断的安静着法.
     *
     * 只给"造成截断的那手"加分不够: 会让"从没试过"和"试过但没用"的着法都
     * 停在 0 分附近, 排序时区分不出谁是真被验证过的坏棋。所以本节点里排在
     * 它前面、却没能造成截断的安静着法一起打惩罚(malus) —— 这是通行做法
     * (Stockfish 系一直这么做), 不是抄 Pikafish 的具体实现。
     */
    private fun recordCutoff(m: Move, depth: Int, ply: Int, quietsTried: List<Move>) {
        if (ply < MAX_PLY) {
            val k = killers[ply]
            if (k[0] != m.data) { k[1] = k[0]; k[0] = m.data }
        }
        // depth 平方: 深处的截断比浅处更有说服力
        val bonus = depth * depth
        updateHistory(m.from, m.to, bonus)
        for (i in 0 until quietsTried.size - 1) {
            val qm = quietsTried[i]
            updateHistory(qm.from, qm.to, -bonus)
        }
    }

    /**
     * 历史分按"引力"公式更新, 而不是简单 +=.
     *
     * new = old + bonus - old * |bonus| / HISTORY_MAX
     *
     * 越接近上限, 同方向的分越难再涨 —— 天然有界, 不会无限增长溢出;
     * 也让惩罚(负 bonus)真正生效, 而不是单调只加分。
     */
    private fun updateHistory(from: Int, to: Int, bonus: Int) {
        val clamped = bonus.coerceIn(-HISTORY_MAX, HISTORY_MAX)
        val current = history[from][to]
        history[from][to] = current + clamped - current * kotlin.math.abs(clamped) / HISTORY_MAX
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
                        // 引力公式已把历史分天然限制在 ±HISTORY_MAX 内, 且远小于
                        // 杀手分档, 无需再额外 coerce
                        else -> history[m.from][m.to]
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
        // 分数超出打包范围就别存. 正常分数在 ±30000 以内, 但根节点的
        // bestScore 初值是 Int.MIN_VALUE, 万一一个着法都没搜成会漏出来。
        if (score < -TT_SCORE_LIMIT || score > TT_SCORE_LIMIT) return
        val slot = ttSlot(key)
        val old = ttData[slot]
        // 同一局面已有更深的结果就别覆盖: 深的更可信。
        // 槽位被**别的**局面占着时直接覆盖 —— 定长表里冲突是常态, 留着旧局面
        // 反而让当前搜索一直命中不了。
        if (ttKey[slot] == key && ttGenOf(old) == ttGen && ttDepthOf(old) > depth) return
        ttKey[slot] = key
        ttData[slot] = (moveData.toLong() and 0x3FFFFL) or
            ((score + TT_SCORE_BIAS).toLong() shl 18) or
            (depth.coerceIn(0, 255).toLong() shl 40) or
            (flag.toLong() shl 48) or
            (ttGen.toLong() shl 50)
    }

    // 打包布局: move 18 位 | score 22 位(偏移存储) | depth 8 位 | flag 2 位 | gen 8 位
    private fun ttSlot(key: Long): Int = (key.toInt() and TT_MASK)
    private fun ttMoveOf(d: Long): Int = (d and 0x3FFFFL).toInt()
    private fun ttScoreOf(d: Long): Int = ((d ushr 18) and 0x3FFFFFL).toInt() - TT_SCORE_BIAS
    private fun ttDepthOf(d: Long): Int = ((d ushr 40) and 0xFFL).toInt()
    private fun ttFlagOf(d: Long): Int = ((d ushr 48) and 0x3L).toInt()
    private fun ttGenOf(d: Long): Int = ((d ushr 50) and 0xFFL).toInt()

    /**
     * 静态搜索: 只继续搜吃子着法,直到局面"安静"下来.
     *
     * [qply] 是**相对叶子节点**的层数, [ply] 是从根算起的绝对层数。这里必须
     * 用 qply 判停 —— 以前比的是绝对 ply, 于是深度 >= MAX_QUIESCENCE_PLY 的
     * 搜索一进来 ply 就已经到顶, 直接返回 standPat, 静止搜索等于没开。
     *
     * 那个写法还有个更坏的性质: LMR 每减一层, 叶子就提前一层出现, ply 越小
     * 越不到顶, 静止搜索反而搜得越深。于是同一棵树里混着"完全没消化兑子"和
     * "消化了三层兑子"两种尺度的分数再拿去比大小 —— 深浅取决于 LMR 恰好削了
     * 哪几步, 与局面无关。统一不搜至少是个一致的偏差, 忽深忽浅是结构性噪声。
     *
     * 实测(深度 6, 300 个局面): 改过来之后 97% 的局面分数变了, 平均差 138
     * 厘兵, 三分之二的局面差一个兵以上, 36.7% 连最佳着法都不同; 代价是节点数
     * 1.53 倍。此前生成的深度 6 数据都带着这个噪声。
     */
    private fun quiescence(
        pos: Position, alpha: Int, beta: Int, nodes: LongArray, ply: Int, qply: Int
    ): Int {
        nodes[0]++
        if (aborted(nodes[0])) return 0

        val inCheckQ = Rules.isInCheck(pos, pos.sideToMove)
        val standPat = evaluate(pos)
        if (standPat >= beta) return standPat
        // ply 那一条只是防跑飞的兜底: 累加器栈和杀手表都按 MAX_PLY 定长
        if (qply >= quiescenceBudget || ply >= MAX_PLY - 1) return standPat

        var alphaLocal = if (standPat > alpha) standPat else alpha

        qMoves.clear()
        MoveGenerator.generateCaptures(pos, qMoves)
        if (qMoves.isEmpty()) return standPat
        sortByMvvLva(pos, qMoves)
        // 递归 quiescence 会清 qMoves, 必须在进入循环前拷出来
        val caps = ArrayList(qMoves)

        var best = standPat
        for (m in caps) {
            // delta 裁剪: 就算白吃下这个子、再补一个兵的机动余量, 还够不到 alpha,
            // 那这一支不可能翻盘。被将时不裁 —— 那时的"吃子"往往是唯一的应将手段,
            // 按子力算账会把救命的一手剪掉。
            if (!inCheckQ) {
                val victim = pos.pieceAt(m.to)
                if (useDelta && victim != Piece.NONE.code &&
                    standPat + captureValue(victim) + DELTA_MARGIN <= alphaLocal
                ) continue
                // SEE 裁剪: 兑完之后净亏子力的吃子直接跳过。
                //
                // 这比 MVV-LVA 严格 —— MVV-LVA 只看"吃的子值不值钱", 看不出
                // 落点有没有保护。"车吃有保护的兵"在 MVV-LVA 里排得不低, 但
                // 兑完是净亏 800, 搜它纯属浪费。
                //
                // 被将时不裁: 那时的吃子往往是唯一应将手段, 按子力算账会把
                // 救命的一手剪掉(delta 裁剪同理)。
                if (useSee && See.evaluate(pos, m) < 0) continue
            }
            if (!doMoveAcc(pos, m)) continue
            val score = -quiescence(pos, -beta, -alphaLocal, nodes, ply + 1, qply + 1)
            undoMoveAcc(pos)
            if (aborted(nodes[0])) return 0
            if (score > best) best = score
            if (score > alphaLocal) alphaLocal = score
            if (alphaLocal >= beta) break
        }
        return best
    }

    /** 被吃子的子力价值, 与 Move.mvvLva 用的是同一套 —— 两处不一致会让排序和
     *  裁剪各说各话 */
    private fun captureValue(pieceCode: Int): Int = when (Piece.fromCode(pieceCode).type) {
        1 -> 10000   // 将/帅
        2, 3 -> 200  // 仕 / 相
        4 -> 400     // 马
        5 -> 900     // 车
        6 -> 450     // 炮
        7 -> 100     // 兵
        else -> 0
    }

    /** 基准测试用: 分别关掉两种裁剪, 以便同进程 A/B */
    internal var useDelta = true
    internal var useSee = true          // 静止搜索里的 SEE 裁剪
    internal var useLmrTable = true     // LMR 用查表削减, 关掉则退回固定减 1

    /**
     * LMR 削减层数, 查表算.
     *
     * r = LMR_BASE + ln(depth)*ln(moveCount)/LMR_DIV, 这个形状是通行做法:
     * 深度越大、着法排得越后, 减得越多。分母越大越保守。
     */
    private fun lmrReduction(depth: Int, moveCount: Int): Int {
        val d = depth.coerceAtMost(LMR_TABLE_MAX)
        val m = moveCount.coerceAtMost(LMR_TABLE_MAX)
        return lmrTable[d][m]
    }
    internal var useRfp = true

    /**
     * 吃子排序 (MVV-LVA).
     *
     * 试过在这里叠一层 SEE 分层(亏损吃子整体压后), **实测零收益**: 节点数
     * 1.00 倍、分数一次都没变。原因是 SEE 只对亏损吃子降权, 而那些着法本来
     * 就排在 MVV-LVA 后段(吃便宜子 + 用贵子), 多半在被搜到之前就 beta 截断了;
     * 真正有价值的重排("不亏的便宜吃子提到亏损的贵吃子之前")在象棋里少见。
     *
     * 走法排序在**每个节点**都跑, 给每个吃子加一次 SEE 是净亏, 所以删掉。
     * SEE 保留在静止搜索的裁剪里(那里有 1.03 倍)。
     */
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
        ttGen = (ttGen + 1) and 0xFF
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
     *
     * [movetimeMs] > 0 时改成限时搜索(给"真实力对战"用, 而不是评估函数抽查):
     * deadline 一设, aborted() 里现成的检查(每 1024 节点查一次时钟)就会在
     * 时间到时打断当前这层, 外层循环只采信跑完整的那一层, 跟 search() 走的
     * 是同一套机制。depth 参数此时不再是硬顶, 换成 32(与本文件另一处
     * "nodeLimit>0 时把名义深度放开到 32"用的是同一个数) —— 迭代加深真正的
     * 上限应该是时间, 不该是一个更早为"几分钟出一步"场景写死的小深度。
     *
     * 副作用: 限时搜索本质上不确定(同样种子跑两次, 搜多深取决于当时机器
     * 有多忙), 类头注释里"同样参数跑两次结果必须逐字节相同"这条对 movetime
     * 模式不成立——这是拿"真实力"换"可重复"的必要代价, 不是 bug。
     */
    internal fun searchWithScore(depth: Int, movetimeMs: Int = 0): Pair<Move, Int> {
        deadline = if (movetimeMs > 0) System.currentTimeMillis() + movetimeMs else Long.MAX_VALUE
        val effectiveDepth = if (movetimeMs > 0) 32 else depth
        timeUp = false
        stopRequested = false
        ttGen = (ttGen + 1) and 0xFF
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
        // 注意分数**会**变: LMR 的减层条件看着法序号, 换了排序就换了一批被减层
        // 的着法, 而减层搜索本身不精确。实测 26.7% 的局面分数有变化, 平均 8 厘兵
        // (IterativeDeepeningTest 量的就是这个, 并守住一条上界)。
        var best = moves[0]
        var alpha = MIN_SCORE
        var prevScore = 0
        val firstDepth = if (useIterativeDeepening) 1 else effectiveDepth
        for (d in firstDepth..effectiveDepth) {
            // 渐进窗口: 有上一层的分数、且深度够了才收紧起始 alpha。
            // 猜错(没有任何着法真正超过这个下限)就在下面的 while 里退回
            // MIN_SCORE 整层重搜, 不会返回一个"猜出来的假结果"。
            var seededAlpha = if (d >= ASPIRATION_MIN_DEPTH && d > firstDepth) {
                (prevScore - ASPIRATION_WINDOW).coerceAtLeast(MIN_SCORE)
            } else {
                MIN_SCORE
            }
            var iterBest: Move
            var iterAlpha: Int
            var completed: Boolean
            while (true) {
                iterBest = moves[0]
                iterAlpha = seededAlpha
                completed = true
                for (m in moves) {
                    if (!doMoveAcc(currentPosition, m)) continue
                    val sc = -alphaBeta(currentPosition, d - 1, MAX_SCORE.unaryMinus(), -iterAlpha, nodes, 1)
                    undoMoveAcc(currentPosition)
                    if (aborted(nodes[0])) { completed = false; break }
                    if (sc > iterAlpha) { iterAlpha = sc; iterBest = m }
                }
                if (!completed) break
                // fail-low: 窄口袋里没有一手能超过下限, 说明猜高了(比如对手
                // 找到了反制), 不能拿"垫底没人达到"的 iterAlpha 当真分数,
                // 放宽到底整层重搜。
                if (iterAlpha <= seededAlpha && seededAlpha > MIN_SCORE) {
                    seededAlpha = MIN_SCORE
                    continue
                }
                break
            }
            // 只采信跑完整的那一层. 被节点/时间上限打断的一层只搜了前几个着法,
            // 它的"最佳"是残缺的, 拿它覆盖上一层的结果会让棋力忽好忽坏。
            if (!completed) break
            best = iterBest
            alpha = iterAlpha
            prevScore = iterAlpha
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
        if (nodeLimit > 0L && nodeCount >= nodeLimit) return true
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

    /** 基准测试用: 关掉空着裁剪, 以便同进程 A/B */
    internal var useNullMove = true

    /**
     * 节点预算; 0 表示不限.
     *
     * 存在的理由: 固定**深度**对战只惩罚剪枝的精度代价, 不奖励它省下的节点,
     * 所以没法回答"这个剪枝到底净赚不赚"。固定**时间**能回答, 但墙钟带随机性,
     * 这个会话里已经被它坑过好几次。限定节点数两头都占: 与深度无关地奖励效率,
     * 而节点数本身是确定性的 —— 同参数重跑结果逐字节相同。
     */
    internal var nodeLimit = 0L

    /**
     * 静止搜索的层数预算, **相对叶子节点**.
     *
     * 做成变量是为了能扫参: 这个数直接决定"消化多少兑子"与"多花多少节点"的
     * 取舍, 而它以前是在绝对 ply 语义下调的, 换成相对语义后含义完全变了 ——
     * 同样写 6, 绝对语义在深度 6 上等于**不搜**, 相对语义是搜满 6 层。
     */
    internal var quiescenceBudget = MAX_QUIESCENCE_PLY

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
        val king = ev != null && NnueFeatures.isKing(moverIdx)
        if (!pos.doMove(m)) return false
        when {
            ev == null -> {}
            // 走将: 所有特征换桶, 增量无效, 只能对新局面全量重算
            king -> ev.accPushRefresh(pos)
            else -> ev.accPush(moverIdx, m.from, m.to, capIdx)
        }
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
        /**
         * 置换表槽位数, 必须是 2 的幂 (下标用位与取模).
         *
         * 2^19 = 524288 槽 × 16 字节 = 8MB。旧的 HashMap 上限是 40 万条, 但每条
         * 还要摊上 Long 装箱、Node 对象和引用, 实际内存远不止 8MB。
         */
        private const val TT_SIZE = 1 shl 19
        private const val TT_MASK = TT_SIZE - 1

        /** score 用 22 位偏移存储, 覆盖 ±2097151, 而实际分数在 ±30000 以内 */
        private const val TT_SCORE_BIAS = 1 shl 21
        private const val TT_SCORE_LIMIT = (1 shl 21) - 1

        /** 杀手表按层存, 这是层数上限 */
        private const val MAX_PLY = 64

        // 排序分档. 拉开足够大的间距, 保证高优先级永远排在低优先级前面
        private const val ORDER_TT = 1_000_000
        private const val ORDER_CAPTURE = 100_000
        private const val ORDER_KILLER_1 = 90_000
        private const val ORDER_KILLER_2 = 80_000
        /** 历史分"引力公式"的饱和上限, 必须小于 ORDER_KILLER_2 才不会抢杀手的位置 */
        private const val HISTORY_MAX = 16_384

        /** LMR: 到这个深度以上、且已经试过这么多手之后才开始减层 */
        /**
         * 空着裁剪的最小深度与缩减层数.
         *
         * R=2 是通行取法: 再大剪得更狠但漏算风险上升, 而象棋的欠行局面本来就比
         * 国象多。深度 3 以下不做 —— 缩减之后剩不下什么, 省的节点抵不上风险。
         */
        /**
         * 静止搜索的 delta 裁剪余量, 以及静态裁剪的每层门槛.
         *
         * DELTA_MARGIN 取一个兵多一点: 吃子之后局面还可能有后续得子, 留一点
         * 机动余量, 免得把"吃一子再叫将得子"这类两步棋剪掉。
         * RFP 的门槛按深度线性涨 —— 深度越大局面还能变化的空间越大。
         * 两个数都是常见取值, 没有针对本引擎调过, 要调得用节点预算口径的对战。
         */
        private const val DELTA_MARGIN = 150
        private const val RFP_MAX_DEPTH = 4
        private const val RFP_MARGIN_PER_DEPTH = 140

        private const val NULL_MOVE_MIN_DEPTH = 3
        private const val NULL_MOVE_REDUCTION = 2

        /**
         * LMR 查表的尺寸与形状参数.
         *
         * LMR_DIV 越大削减越保守。取 2.6 而不是 Pikafish 那种更激进的取值,
         * 是因为我们没有 singular extension —— 它敢深削是因为有那层保护把
         * "明显最好的那手"排除在外。这个数没调过, 是保守的起点。
         */
        private const val LMR_TABLE_MAX = 63
        private const val LMR_BASE = 0.20
        private const val LMR_DIV = 2.60

        /** 预先算好, 搜索热路径上只做两次数组访问 */
        private val lmrTable: Array<IntArray> = Array(LMR_TABLE_MAX + 1) { d ->
            IntArray(LMR_TABLE_MAX + 1) { m ->
                if (d < 1 || m < 1) 0
                else (LMR_BASE + kotlin.math.ln(d.toDouble()) *
                    kotlin.math.ln(m.toDouble()) / LMR_DIV).toInt().coerceAtLeast(0)
            }
        }

        private const val LMR_MIN_DEPTH = 3
        private const val LMR_MIN_MOVES = 4
        /** 每级随机性容忍的分差(厘兵) */
        private const val RANDOMNESS_CP_PER_LEVEL = 60
        /** 走开局库时的最短"思考"时间,避免零秒落子 */
        private const val BOOK_MIN_THINK_MS = 450L
        /** 预估下一层迭代相对已用时间的倍数, 用于判断还要不要再加深 */
        private const val NEXT_ITERATION_COST_RATIO = 0.6

        /**
         * 渐进窗口(aspiration window): 新一层不再从 alpha=MIN_SCORE 搜起,
         * 而是拿上一层收敛的分数上下各留 ASPIRATION_WINDOW 厘兵开一个窄口袋。
         * 局面通常一层比一层稳定, 大多数时候这一猜就中, 省下的是"用垫底的
         * alpha 去搜第一手"那段几乎不剪枝的搜索。
         *
         * 只做了"防低估"这一半(alpha 收紧, beta 仍是 MAX_SCORE): 根节点原来
         * 的写法是 beta 恒为 MAX_SCORE、永不 fail-high, 真要做两头都收紧的窗口
         * 得给根节点补一条 fail-high 重搜路径, 收益未必抵得过复杂度和出错
         * 风险。窄口袋没猜中(哪个着法都没能超过下限, 说明这层比上一层差,
         * 对手大概率找到了反制)时退回 MIN_SCORE 整层重搜, 保正确性优先。
         *
         * MIN_DEPTH=3 以下不开: 那几层本来就快, 省不了多少, 却要多担一次
         * "猜错要重搜"的开销和风险。
         */
        private const val ASPIRATION_MIN_DEPTH = 3
        private const val ASPIRATION_WINDOW = 25
        /** 检查时钟的节点间隔掩码 */
        private const val TIME_CHECK_MASK = 0x1FFL
    }
}