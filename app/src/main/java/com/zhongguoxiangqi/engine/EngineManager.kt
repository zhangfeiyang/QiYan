package com.zhongguoxiangqi.engine

import android.content.Context
import com.zhongguoxiangqi.engine.nnue.NnueProvider
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.UciCoords
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 引擎管理器.
 *
 * 根据难度等级选择合适的引擎:
 * - 1-5: SimpleEngine (Kotlin 实现)
 * - 6-10: PikafishNativeEngine (JNI + NNUE, 顶级棋力)
 *
 * 同时负责:
 * - 引擎生命周期
 * - 难度参数映射(深度、线程、随机性)
 * - 故障降级 (Pikafish 不可用 → SimpleEngine)
 */
class EngineManager(private val context: Context) {

    /** 难度等级 (1-10) */
    var difficultyLevel: Int = 5
        set(value) {
            field = value.coerceIn(1, 10)
            applyDifficulty()
        }

    /** 当前活动的引擎 */
    var currentEngine: ChessEngine? = null
        private set

    /** 是否使用 Pikafish (JNI 路线) */
    val usingPikafish: Boolean
        get() = currentEngine is PikafishNativeEngine

    /** 是否因为 Pikafish 不可用而降级到了内置引擎 */
    val isDowngraded: Boolean
        get() = difficultyLevel >= PIKAFISH_MIN_LEVEL && !usingPikafish

    /**
     * 当前**实际**对手棋力.
     *
     * 难度 6-10 标称的是 Pikafish 的水平; 一旦降级到内置引擎, 真实棋力
     * 就到不了那个档次. 显示和积分都必须用这个值 —— 否则赢一盘"降级的
     * 10 级"会按战胜 3300 分引擎计算,等级分会被凭空吹高.
     */
    fun effectiveElo(): Int {
        val nominal = nominalElo(difficultyLevel)
        return if (isDowngraded) minOf(nominal, SIMPLE_ENGINE_MAX_ELO) else nominal
    }

    private var simpleEngine: SimpleEngine? = null
    private var pikafishEngine: PikafishNativeEngine? = null
    private val settings = AppSettings(context)
    private val openingBook = OpeningBook(context)

    fun start(listener: EngineListener?): Boolean {
        // 优先尝试 Pikafish (高难度)
        if (difficultyLevel >= PIKAFISH_MIN_LEVEL) {
            val nnue = PikafishNativeInstaller.ensureNnueInstalled(context)
            if (nnue != null) {
                val engine = PikafishNativeEngine(nnue, listener)
                if (engine.start()) {
                    currentEngine = engine
                    pikafishEngine = engine
                    applyDifficulty()
                    return true
                }
            }
            // Pikafish 启动失败,降级到 SimpleEngine
        }

        // 简易引擎(低难度 或 Pikafish 失败)
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
        try { pikafishEngine?.shutdown() } catch (_: Exception) {}
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
        adaptParams(base, currentEngine is PikafishNativeEngine, difficultyLevel)

    private fun applyDifficulty() {
        simpleEngine?.configure(difficultyLevel)
    }

    /** 提示用快速搜索 */
    fun requestHint(): SearchParams {
        return when {
            currentEngine is PikafishNativeEngine ->
                SearchParams(movetime = 2500, threads = 2, hashMb = 64, multiPv = 3)
            else ->
                SearchParams(movetime = 800, threads = 1, hashMb = 16, multiPv = 1)
        }
    }

    /** 实时分析用 */
    fun requestAnalysis(): SearchParams {
        return when {
            currentEngine is PikafishNativeEngine ->
                SearchParams(infinite = true, threads = 2, hashMb = 128, multiPv = 3)
            else ->
                SearchParams(depth = 6, threads = 1, hashMb = 16, multiPv = 1)
        }
    }

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
            isPikafish: Boolean,
            difficultyLevel: Int,
        ): SearchParams {
            val strength = difficultyLevel.coerceIn(1, 10)
            if (base.strictMovetime) {
                return if (isPikafish) base.copy(
                    threads = base.threads.coerceAtLeast(2),
                    hashMb = base.hashMb.coerceAtLeast(64),
                ) else base
            }
            return when {
                isPikafish -> when (strength) {
                    6 -> base.copy(threads = base.threads.coerceAtLeast(1), hashMb = base.hashMb.coerceAtLeast(32), movetime = base.movetime.coerceAtLeast(2000))
                    7 -> base.copy(threads = base.threads.coerceAtLeast(2), hashMb = base.hashMb.coerceAtLeast(64), movetime = base.movetime.coerceAtLeast(3000))
                    8 -> base.copy(threads = base.threads.coerceAtLeast(2), hashMb = base.hashMb.coerceAtLeast(128), movetime = base.movetime.coerceAtLeast(4000))
                    9 -> base.copy(threads = base.threads.coerceAtLeast(4), hashMb = base.hashMb.coerceAtLeast(256), movetime = base.movetime.coerceAtLeast(6000))
                    10 -> base.copy(threads = base.threads.coerceAtLeast(4), hashMb = base.hashMb.coerceAtLeast(512), movetime = base.movetime.coerceAtLeast(8000))
                    else -> base
                }
                else -> when (strength) {
                    1 -> base.copy(movetime = base.movetime.coerceAtLeast(100))
                    2 -> base.copy(movetime = base.movetime.coerceAtLeast(300))
                    3 -> base.copy(movetime = base.movetime.coerceAtLeast(800))
                    4 -> base.copy(movetime = base.movetime.coerceAtLeast(1500))
                    5 -> base.copy(movetime = base.movetime.coerceAtLeast(2000))
                    else -> base
                }
            }
        }

        /** 从这一难度起才尝试 Pikafish */
        const val PIKAFISH_MIN_LEVEL = 6

        /** 内置引擎的棋力上限(经验值), 降级时用它封顶 */
        const val SIMPLE_ENGINE_MAX_ELO = 1700

        /** 各难度标称棋力 */
        fun nominalElo(difficulty: Int): Int = when (difficulty) {
            1 -> 600; 2 -> 900; 3 -> 1200; 4 -> 1500
            5 -> 1700; 6 -> 1900; 7 -> 2200; 8 -> 2500
            9 -> 2900; 10 -> 3300
            else -> 1500
        }
    }

    private fun moveToUci(move: Move): String = UciCoords.toUci(move)
}