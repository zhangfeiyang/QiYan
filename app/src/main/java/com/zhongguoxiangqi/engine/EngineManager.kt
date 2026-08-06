package com.zhongguoxiangqi.engine

import android.content.Context
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.UciCoords
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 引擎管理器.
 *
 * 全部难度统一使用 PikaFish（GPL-3）。整个应用因此以 GPL-3 开源，
 * 代码与说明见仓库 LICENSE 与 README。难度只影响思考时间，棋力下限
 * 由 movetime 限制实现。
 *
 * 负责: 引擎生命周期、难度参数映射、开局库与 NNUE 的注入。
 */
class EngineManager(private val context: Context) {

    /** 难度等级 (1-10) */
    var difficultyLevel: Int = 3
        set(value) {
            val old = field
            field = value.coerceIn(1, MAX_LEVEL)
            // 统一 PikaFish 引擎，难度只改 movetime，无需重启。
        }

    /** 当前活动的引擎 */
    var currentEngine: ChessEngine? = null
        private set

    /**
     * 当前对手棋力, 用于玩家积分换算.
     *
     * 现在只有一个引擎, 标称即实际, 不再需要"降级封顶"。
     */
    fun effectiveElo(): Int = nominalElo(difficultyLevel)

    private var listener: EngineListener? = null
    private var lastFen: String? = null
    private var lastMoves: List<String> = emptyList()
    private val settings = AppSettings(context)
    private val openingBook = OpeningBook(context)

    fun start(listener: EngineListener?): Boolean {
        this.listener = listener
        currentEngine?.shutdown()
        currentEngine = null
        return startPreferredEngine(listener)
    }

    fun shutdown() {
        try { currentEngine?.shutdown() } catch (_: Exception) {}
        currentEngine = null
    }

    fun setListener(listener: EngineListener?) {
        this.listener = listener
        currentEngine?.setListener(listener)
    }

    /**
     * 把局面同步给引擎.
     *
     * @param initialFen 对局**起始**局面的 FEN
     * @param moves 从起始局面走到当前局面的完整着法序列
     *
     * 注意必须传起始 FEN: `position fen <X> moves ...` 的语义是"从 X 开始依次走这些着法".
     * 传当前局面的 FEN 再附上全部着法,等于让引擎在当前盘面上把整局重走一遍.
     */
    fun setPosition(initialFen: String, moves: List<Move>) {
        val engine = currentEngine ?: return
        val uciMoves = moves.map { moveToUci(it) }
        lastFen = initialFen
        lastMoves = uciMoves
        engine.setPosition(initialFen, uciMoves)
    }

    fun startSearch(params: SearchParams) {
        // 用户上限必须最后套用: adaptParams 里用的是 coerceAtLeast, 先限制会被它顶回去
        currentEngine?.startSearch(applyUserLimits(adaptParams(params)))
    }

    /** 用设置界面里的线程/哈希上限收敛搜索参数 */
    private fun applyUserLimits(params: SearchParams): SearchParams = params.copy(
        threads = params.threads.coerceAtMost(settings.threads),
        hashMb = params.hashMb.coerceAtMost(settings.hashMb)
    )

    fun stopSearch() {
        currentEngine?.stopSearch()
    }

    /** 根据难度调整搜索参数 */
    private fun adaptParams(base: SearchParams): SearchParams =
        adaptParams(base, difficultyLevel)

    private fun startPreferredEngine(listener: EngineListener?): Boolean {
        val pika = PikafishEngine(context).also {
            it.openingBook = openingBook
            it.setListener(listener)
        }
        if (!pika.start()) return false
        currentEngine = pika
        return true
    }

    /** 提示用快速搜索 */
    fun requestHint(): SearchParams =
        SearchParams(movetime = 800, threads = 1, hashMb = 16, multiPv = 1)

    /** 实时分析用 */
    fun requestAnalysis(): SearchParams =
        SearchParams(depth = 6, threads = 1, hashMb = 16, multiPv = 1)

    companion object {

        /**
         * 按难度调整搜索参数. 纯函数, 便于测试.
         *
         * 注意 movetime 用的是 **coerceAtLeast** —— 难度越高想得越久, 这对
         * 对局是对的. 但它会静默抬高调用方传的值: 实时支招传 600ms, 难度 8
         * 会被抬成 4000ms, "实时"直接名存实亡. [SearchParams.strictMovetime]
         * 就是给这种"延迟是硬约束"的调用方留的出口.
         */
        internal fun adaptParams(
            base: SearchParams,
            difficultyLevel: Int,
        ): SearchParams {
            val strength = difficultyLevel.coerceIn(1, MAX_LEVEL)
            if (base.strictMovetime) return base
            return when (strength) {
                1 -> base.copy(movetime = base.movetime.coerceAtLeast(100))
                2 -> base.copy(movetime = base.movetime.coerceAtLeast(250))
                3 -> base.copy(movetime = base.movetime.coerceAtLeast(500))
                4 -> base.copy(movetime = base.movetime.coerceAtLeast(900))
                5 -> base.copy(movetime = base.movetime.coerceAtLeast(1500))
                6 -> base.copy(movetime = base.movetime.coerceAtLeast(2500))
                7 -> base.copy(movetime = base.movetime.coerceAtLeast(4000))
                8 -> base.copy(movetime = base.movetime.coerceAtLeast(6000))
                9 -> base.copy(movetime = base.movetime.coerceAtLeast(9000))
                10 -> base.copy(movetime = base.movetime.coerceAtLeast(14000))
                11 -> base.copy(movetime = base.movetime.coerceAtLeast(20000))
                12 -> base.copy(movetime = base.movetime.coerceAtLeast(30000))
                13 -> base.copy(movetime = base.movetime.coerceAtLeast(45000))
                14 -> base.copy(movetime = base.movetime.coerceAtLeast(60000))
                15 -> base.copy(movetime = base.movetime.coerceAtLeast(90000))
                else -> base
            }
        }

        /** 最高难度: 15 档 (从绝对新手到传奇级). */
        const val MAX_LEVEL = 15

        /**
         * 各难度标称棋力, 用于玩家积分换算.
         *
         * **这些是估计值**: 绝对 Elo 要有已知等级分的对手才标定得出来,
         * 而我们没有。所以界面上不再显示具体分数, 只给"对手大概什么水平"
         * 的描述; 这里的数字仅用于积分涨落的相对换算。
         */
        fun nominalElo(difficulty: Int): Int = when (difficulty) {
            1 -> 600; 2 -> 900; 3 -> 1100
            4 -> 1300; 5 -> 1450; 6 -> 1600
            7 -> 1750; 8 -> 1850; 9 -> 1950; 10 -> 2050
            11 -> 2150; 12 -> 2250; 13 -> 2320
            14 -> 2400; 15 -> 2500
            else -> 1200
        }
    }

    private fun moveToUci(move: Move): String = UciCoords.toUci(move)
}
