package com.zhongguoxiangqi.engine

import android.content.Context
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.UciCoords
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 引擎管理器.
 *
 * 前两档使用有限的一步判断与随机合法走法，给新手真实的犯错和获胜空间。
 * 后三档与提示/分析使用 PikaFish（GPL-3）；整个应用仍以 GPL-3 开源。
 *
 * 负责: 引擎生命周期、难度参数映射、开局库与 NNUE 的注入。
 */
class EngineManager(private val context: Context) {

    /** 五档：轻松陪练、入门、进阶、高手、大师。 */
    @Volatile var difficultyLevel: Int = DifficultySettings.DEFAULT_LEVEL
        set(value) { field = DifficultySettings.normalize(value) }

    /**
     * 棋手风格.
     *
     * 难度管"想多久", 风格管"想好了挑哪步" —— 见 [PlayStyle]。改动即时生效:
     * 搜索参数在每次 [startSearch] 现算, MultiPV 候选数由 [PlayStyle.multiPv] 给出.
     */
    var playStyle: PlayStyle = PlayStyle.BALANCED

    private var managedEngine: DifficultyEngine? = null

    /** 实际执行搜索的引擎，保留 PikaFish MultiPV 风格选择的现有接口。 */
    val currentEngine: ChessEngine? get() = managedEngine?.activeEngine

    /**
     * 当前对手棋力, 用于玩家积分换算.
     *
     * 这些是相对积分换算的估计值，未经外部等级分标定。
     */
    fun effectiveElo(): Int = nominalElo(difficultyLevel)

    private var listener: EngineListener? = null
    private val settings = AppSettings(context)
    private val openingBook = OpeningBook(context)

    fun start(listener: EngineListener?): Boolean {
        this.listener = listener
        managedEngine?.shutdown()
        managedEngine = DifficultyEngine({ difficultyLevel }, { playStyle }) {
            PikafishEngine(context).also { it.openingBook = openingBook }
        }.also { it.setListener(listener) }
        return managedEngine!!.start()
    }

    fun shutdown() {
        try { managedEngine?.shutdown() } catch (_: Exception) {}
        managedEngine = null
    }

    fun setListener(listener: EngineListener?) {
        this.listener = listener
        managedEngine?.setListener(listener)
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
        managedEngine?.setPosition(initialFen, moves.map { moveToUci(it) })
    }

    fun startSearch(params: SearchParams) {
        // 用户上限必须最后套用: adaptParams 里用的是 coerceAtLeast, 先限制会被它顶回去
        val effective = applyUserLimits(adaptParams(params))
        android.util.Log.i(
            "QiYan.Engine",
            "startSearch diff=$difficultyLevel style=${playStyle.name} movetime=${effective.movetime}ms threads=${effective.threads} hash=${effective.hashMb}MB strict=${params.strictMovetime}"
        )
        managedEngine?.startSearch(effective)
    }

    /** 用设置界面里的线程/哈希上限收敛搜索参数 */
    private fun applyUserLimits(params: SearchParams): SearchParams = params.copy(
        threads = params.threads.coerceAtMost(settings.threads),
        hashMb = params.hashMb.coerceAtMost(settings.hashMb)
    )

    fun stopSearch() {
        managedEngine?.stopSearch()
    }

    /** 根据难度调整搜索参数 */
    private fun adaptParams(base: SearchParams): SearchParams =
        adaptParams(base, difficultyLevel)

    /** 提示用快速搜索 —— strictMovetime=true, 延迟是硬约束, 不许被 adaptParams 按难度抬高 */
    fun requestHint(): SearchParams =
        SearchParams(movetime = 800, threads = 1, hashMb = 16, multiPv = 1, strictMovetime = true)

    /** 实时分析始终使用专业引擎，不跟随陪练难度。 */
    fun requestAnalysis(): SearchParams =
        SearchParams(depth = 6, threads = 1, hashMb = 16, multiPv = 1, strictMovetime = true)

    companion object {

        /** 对局时限；前两档的降难度由 BeginnerEngine 实现，绝非只缩短 PikaFish 搜索。 */
        internal fun adaptParams(base: SearchParams, difficultyLevel: Int): SearchParams {
            if (base.strictMovetime) return base
            val level = DifficultySettings.normalize(difficultyLevel)
            val time = longArrayOf(350, 550, 1200, 3200, 6000, 7500, 9000, 12000)[level - 1]
            val threads = if (level >= 4) 4 else if (level == 3) 2 else 1
            val hash = intArrayOf(16, 16, 64, 128, 256, 256, 256, 256)[level - 1]
            return base.copy(
                movetime = maxOf(base.movetime, time),
                threads = maxOf(base.threads, threads),
                hashMb = maxOf(base.hashMb, hash),
            )
        }

        const val MAX_LEVEL = DifficultySettings.MAX_LEVEL

        /** Estimates for relative rating changes, not externally calibrated Elo ratings. */
        fun nominalElo(difficulty: Int): Int = when (DifficultySettings.normalize(difficulty)) {
            1 -> 350
            2 -> 800
            3 -> 1450
            4 -> 2000
            5 -> 2500
            6 -> 2600
            7 -> 2700
            else -> 2800
        }
    }

    private fun moveToUci(move: Move): String = UciCoords.toUci(move)
}
