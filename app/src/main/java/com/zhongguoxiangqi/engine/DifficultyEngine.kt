package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.game.Position
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Owns practice/native lifetimes; strict hint/analysis searches always use the native engine. */
internal class DifficultyEngine(
    private val difficulty: () -> Int,
    private val style: () -> PlayStyle = { PlayStyle.BALANCED },
    private val strongFactory: () -> ChessEngine,
) : ChessEngine {
    private val beginner = BeginnerEngine(difficulty, style = style)
    @Volatile private var strong: ChessEngine? = null
    @Volatile var activeEngine: ChessEngine = beginner
        private set
    @Volatile private var listener: EngineListener? = null
    @Volatile private var ready = false
    private var fen = Position().toFen()
    private var moves = emptyList<String>()
    private val generation = AtomicLong()

    override fun isReady() = ready
    override fun name() = activeEngine.name()
    override fun maxStrength() = DifficultySettings.MAX_LEVEL
    override fun setListener(listener: EngineListener?) { this.listener = listener }

    /** Called by EngineManager on its existing IO startup path for native difficulty tiers. */
    override fun start(): Boolean {
        if (ready) return true
        val token = generation.get()
        if (DifficultySettings.isTraining(difficulty())) {
            beginner.setListener(forwardingListener(token))
            ready = beginner.start()
            return ready
        }
        return nativeDispatch.submit<Boolean> {
            if (generation.get() != token) return@submit false
            val engine = getStrong()
            activeEngine = engine
            engine.setListener(forwardingListener(token))
            val started = engine.start()
            ready = started && generation.get() == token
            ready
        }.get()
    }

    override fun setPosition(fen: String, moves: List<String>) {
        stopSearch()
        this.fen = fen
        this.moves = moves.toList()
    }

    override fun startSearch(params: SearchParams) {
        stopSearch()
        if (!ready) {
            listener?.onError("对弈引擎尚未启动")
            return
        }
        val token = generation.get()
        val fenSnapshot = fen
        val movesSnapshot = moves
        if (!usesStrongEngine(difficulty(), params)) {
            // Practice remains responsive even while a cancelled NNUE load finishes in the background.
            activeEngine = beginner
            beginner.setListener(forwardingListener(token, reportReady = false))
            if (!beginner.isReady()) beginner.start()
            beginner.setPosition(fenSnapshot, movesSnapshot)
            beginner.startSearch(params)
            return
        }
        nativeDispatch.execute {
            try {
                if (generation.get() != token || !ready) return@execute
                val engine = getStrong()
                activeEngine = engine
                engine.setListener(forwardingListener(token, reportReady = false))
                if (!engine.isReady() && !engine.start()) return@execute
                if (generation.get() != token || !ready) return@execute
                engine.setPosition(fenSnapshot, movesSnapshot)
                if (generation.get() == token && ready) engine.startSearch(params)
            } catch (t: Throwable) {
                if (generation.get() == token && ready) {
                    listener?.onError(t.message ?: "引擎启动失败")
                }
            }
        }
    }

    /** Cancel callbacks immediately; never join a potentially non-interruptible NNUE load on the UI. */
    override fun stopSearch() {
        generation.incrementAndGet()
        beginner.stopSearch()
        nativeDispatch.execute { strong?.stopSearch() }
    }

    override fun shutdown() {
        ready = false
        generation.incrementAndGet()
        beginner.shutdown()
        // All native lifecycle operations share a queue, including operations from a new game.
        // Thus an old load/shutdown cannot tear down a newer manager's native runtime.
        nativeDispatch.execute {
            strong?.shutdown()
            strong = null
        }
    }

    private fun getStrong(): ChessEngine = strong ?: strongFactory().also { strong = it }

    private fun forwardingListener(token: Long, reportReady: Boolean = true) = object : EngineListener {
        override fun onReady() {
            if (reportReady && generation.get() == token) listener?.onReady()
        }
        override fun onInfo(info: EngineInfo) {
            if (generation.get() == token) listener?.onInfo(info)
        }
        override fun onBestMove(bestMove: String, ponderMove: String?) {
            if (generation.get() == token) listener?.onBestMove(bestMove, ponderMove)
        }
        override fun onError(message: String) {
            if (generation.get() == token) listener?.onError(message)
        }
    }

    companion object {
        // PikafishNative owns one process-wide runtime. Serialize load, search dispatch and teardown.
        private val nativeDispatch = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Xiangqi-native-dispatch").apply { isDaemon = true }
        }

        internal fun usesStrongEngine(level: Int, params: SearchParams): Boolean =
            params.strictMovetime || !DifficultySettings.isTraining(level)
    }
}
