package com.zhongguoxiangqi.engine

import android.content.Context
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Square

/**
 * 引擎管理器.
 *
 * 根据难度等级选择合适的引擎:
 * - 1-5: SimpleEngine (Java/Kotlin 实现)
 * - 6-10: PikafishEngine (NNUE, 顶级)
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

    /** 是否使用 Pikafish */
    val usingPikafish: Boolean
        get() = currentEngine is PikafishEngine

    private var simpleEngine: SimpleEngine? = null
    private var pikafishEngine: PikafishEngine? = null

    fun start(listener: EngineListener?): Boolean {
        // 优先尝试 Pikafish (高难度)
        if (difficultyLevel >= 6) {
            val pair = PikafishEngine.ensureInstalled(context)
            if (pair != null) {
                val engine = PikafishEngine(pair.first, pair.second)
                if (engine.start()) {
                    engine.setListener(listener)
                    currentEngine = engine
                    pikafishEngine = engine
                    applyDifficulty()
                    return true
                }
            }
            // Pikafish 启动失败,降级到 SimpleEngine
        }

        // 简易引擎(低难度 或 Pikafish 失败)
        val engine = SimpleEngine().also { it.configure(difficultyLevel) }
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

    fun setPosition(position: Position, moves: List<Move>) {
        val engine = currentEngine ?: return
        val fen = position.toFen()
        val uciMoves = moves.map { moveToUci(it) }
        engine.setPosition(fen, uciMoves)
    }

    fun startSearch(params: SearchParams) {
        currentEngine?.startSearch(adaptParams(params))
    }

    fun stopSearch() {
        currentEngine?.stopSearch()
    }

    /** 根据难度调整搜索参数 */
    private fun adaptParams(base: SearchParams): SearchParams {
        val strength = difficultyLevel.coerceIn(1, 10)
        return when {
            // Pikafish: 高级别使用更多线程和哈希
            currentEngine is PikafishEngine -> when (strength) {
                6 -> base.copy(threads = base.threads.coerceAtLeast(1), hashMb = base.hashMb.coerceAtLeast(32), movetime = base.movetime.coerceAtLeast(2000))
                7 -> base.copy(threads = base.threads.coerceAtLeast(2), hashMb = base.hashMb.coerceAtLeast(64), movetime = base.movetime.coerceAtLeast(3000))
                8 -> base.copy(threads = base.threads.coerceAtLeast(2), hashMb = base.hashMb.coerceAtLeast(128), movetime = base.movetime.coerceAtLeast(5000))
                9 -> base.copy(threads = base.threads.coerceAtLeast(4), hashMb = base.hashMb.coerceAtLeast(256), movetime = base.movetime.coerceAtLeast(10000))
                10 -> base.copy(threads = base.threads.coerceAtLeast(4), hashMb = base.hashMb.coerceAtLeast(512), movetime = base.movetime.coerceAtLeast(15000))
                else -> base
            }
            // 简易引擎
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

    private fun applyDifficulty() {
        simpleEngine?.configure(difficultyLevel)
        // Pikafish 不需要单独设置难度,直接使用全部计算力
    }

    /** 提示用快速搜索(2秒以内,返回最佳着法) */
    fun requestHint(moves: List<Move>): SearchParams {
        return when {
            currentEngine is PikafishEngine ->
                SearchParams(movetime = 2500, threads = 2, hashMb = 64, multiPv = 3)
            else ->
                SearchParams(movetime = 800, threads = 1, hashMb = 16, multiPv = 1)
        }
    }

    /** 实时分析用(后台无限搜索) */
    fun requestAnalysis(): SearchParams {
        return when {
            currentEngine is PikafishEngine ->
                SearchParams(infinite = true, threads = 2, hashMb = 128, multiPv = 3)
            else ->
                SearchParams(depth = 6, threads = 1, hashMb = 16, multiPv = 1)
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
}