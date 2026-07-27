package com.zhongguoxiangqi.engine

import android.content.Context
import com.zhongguoxiangqi.engine.nnue.NnueProvider
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.UciCoords
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 引擎管理器.
 *
 * 只有一个引擎: 自研的 SimpleEngine (alpha-beta + 自研 NNUE 评估)。
 *
 * 原来 6-10 档走 Pikafish, 它因 GPL-3 已从包中移除 —— 经 JNI 加载构成
 * 结合作品, 分发就要整个应用按 GPL-3 开源。随之去掉的还有"降级"这套
 * 逻辑: 现在没有可降的对象, 标称棋力就是实际棋力。
 *
 * 负责: 引擎生命周期、难度参数映射、开局库与 NNUE 的注入。
 */
class EngineManager(private val context: Context) {

    /** 难度等级 (1-6) */
    var difficultyLevel: Int = 3
        set(value) {
            field = value.coerceIn(1, MAX_LEVEL)
            applyDifficulty()
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

    private var simpleEngine: SimpleEngine? = null
    private val settings = AppSettings(context)
    private val openingBook = OpeningBook(context)

    fun start(listener: EngineListener?): Boolean {
        val engine = SimpleEngine().also {
            it.configure(difficultyLevel)
            it.openingBook = openingBook
            // 自研 NNUE。真机实测求值速度与手写评估持平, 所以装上它不用
            // 拿搜索深度去换; assets 里没有网络文件时返回 null, 自动退回
            // 手写评估。更新网络只需替换 assets/models/xiangqi.nnue。
            it.setNnue(NnueProvider.newEvaluator(context))
        }
        simpleEngine = engine
        if (engine.start()) {
            engine.setListener(listener)
            currentEngine = engine
            applyDifficulty()
            return true
        }
        return false
    }

    fun shutdown() {
        try { simpleEngine?.shutdown() } catch (_: Exception) {}
        currentEngine = null
    }

    fun setListener(listener: EngineListener?) {
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
        engine.setPosition(initialFen, moves.map { moveToUci(it) })
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

    private fun applyDifficulty() {
        simpleEngine?.configure(difficultyLevel)
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
                2 -> base.copy(movetime = base.movetime.coerceAtLeast(300))
                3 -> base.copy(movetime = base.movetime.coerceAtLeast(800))
                4 -> base.copy(movetime = base.movetime.coerceAtLeast(1500))
                5 -> base.copy(movetime = base.movetime.coerceAtLeast(2500))
                6 -> base.copy(movetime = base.movetime.coerceAtLeast(4000))
                else -> base
            }
        }

        /** 最高难度. 原来是 10, 7-10 档靠的是已移除的 Pikafish。 */
        const val MAX_LEVEL = 6

        /**
         * 各难度标称棋力, 用于玩家积分换算.
         *
         * **这些是估计值**: 绝对 Elo 要有已知等级分的对手才标定得出来,
         * 而我们没有。所以界面上不再显示具体分数, 只给"对手大概什么水平"
         * 的描述; 这里的数字仅用于积分涨落的相对换算。
         */
        fun nominalElo(difficulty: Int): Int = when (difficulty) {
            1 -> 600; 2 -> 900; 3 -> 1200
            4 -> 1450; 5 -> 1600; 6 -> 1700
            else -> 1200
        }
    }

    private fun moveToUci(move: Move): String = UciCoords.toUci(move)
}