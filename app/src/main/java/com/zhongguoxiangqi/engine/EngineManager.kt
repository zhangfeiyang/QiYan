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
        val effective = applyUserLimits(adaptParams(params))
        android.util.Log.i(
            "QiYan.Engine",
            "startSearch diff=$difficultyLevel movetime=${effective.movetime}ms threads=${effective.threads} hash=${effective.hashMb}MB strict=${params.strictMovetime}"
        )
        currentEngine?.startSearch(effective)
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

    /** 提示用快速搜索 —— strictMovetime=true, 延迟是硬约束, 不许被 adaptParams 按难度抬高 */
    fun requestHint(): SearchParams =
        SearchParams(movetime = 800, threads = 1, hashMb = 16, multiPv = 1, strictMovetime = true)

    /** 实时分析用 —— 同样: 玩家按下去就要看到数字跳动, 不能按 Lv.15 等 12s */
    fun requestAnalysis(): SearchParams =
        SearchParams(depth = 6, threads = 1, hashMb = 16, multiPv = 1, strictMovetime = true)

    companion object {

        /**
         * 按难度调整搜索参数. 唯一来源 —— GameViewModel 不再各自起一套.
         *
         * 三件事一起调: 思考时长 + 线程数 + 哈希. 三者都要随难度递增, 否则
         * "难度高 = 棋力强"这件事对不上 —— NNUE 网络给的是评估质量, 搜索
         * 时长和资源才决定"看多远", 任何一项掉链子都会让 Lv.15 的真实棋力
         * 反而不如 Lv.10.
         *
         * 旧版 Lv.10 = 14s / Lv.15 = 90s 是真有人等了再骂的, 收回到顶档 12s.
         * 顶档仍然是 12s(配合 4 线程 + 256MB 哈希, 引擎实际能下到的深度远超
         * Lv.10), 不是简单砍时间, 是把曲线压扁在"玩家等得起"的范围内.
         *
         * [SearchParams.strictMovetime] 仍是给"延迟是硬约束"的调用方(支招/分
         * 析)的出口, 这种场景必须原样放行.
         */
        internal fun adaptParams(
            base: SearchParams,
            difficultyLevel: Int,
        ): SearchParams {
            val strength = difficultyLevel.coerceIn(1, MAX_LEVEL)
            if (base.strictMovetime) return base
            return when (strength) {
                1 -> base.copy(movetime = maxOf(base.movetime, 100L),   threads = maxOf(base.threads, 1), hashMb = maxOf(base.hashMb, 16))
                2 -> base.copy(movetime = maxOf(base.movetime, 200L),   threads = maxOf(base.threads, 1), hashMb = maxOf(base.hashMb, 16))
                3 -> base.copy(movetime = maxOf(base.movetime, 350L),   threads = maxOf(base.threads, 1), hashMb = maxOf(base.hashMb, 16))
                4 -> base.copy(movetime = maxOf(base.movetime, 500L),   threads = maxOf(base.threads, 1), hashMb = maxOf(base.hashMb, 16))
                5 -> base.copy(movetime = maxOf(base.movetime, 800L),   threads = maxOf(base.threads, 1), hashMb = maxOf(base.hashMb, 16))
                6 -> base.copy(movetime = maxOf(base.movetime, 1200L),  threads = maxOf(base.threads, 2), hashMb = maxOf(base.hashMb, 32))
                7 -> base.copy(movetime = maxOf(base.movetime, 1800L),  threads = maxOf(base.threads, 2), hashMb = maxOf(base.hashMb, 64))
                8 -> base.copy(movetime = maxOf(base.movetime, 2500L),  threads = maxOf(base.threads, 2), hashMb = maxOf(base.hashMb, 64))
                9 -> base.copy(movetime = maxOf(base.movetime, 3500L),  threads = maxOf(base.threads, 4), hashMb = maxOf(base.hashMb, 128))
                10 -> base.copy(movetime = maxOf(base.movetime, 5000L), threads = maxOf(base.threads, 4), hashMb = maxOf(base.hashMb, 128))
                11 -> base.copy(movetime = maxOf(base.movetime, 7000L), threads = maxOf(base.threads, 4), hashMb = maxOf(base.hashMb, 256))
                12 -> base.copy(movetime = maxOf(base.movetime, 9000L), threads = maxOf(base.threads, 4), hashMb = maxOf(base.hashMb, 256))
                13 -> base.copy(movetime = maxOf(base.movetime, 10500L), threads = maxOf(base.threads, 4), hashMb = maxOf(base.hashMb, 256))
                14 -> base.copy(movetime = maxOf(base.movetime, 11500L), threads = maxOf(base.threads, 4), hashMb = maxOf(base.hashMb, 256))
                15 -> base.copy(movetime = maxOf(base.movetime, 12000L), threads = maxOf(base.threads, 4), hashMb = maxOf(base.hashMb, 256))
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
