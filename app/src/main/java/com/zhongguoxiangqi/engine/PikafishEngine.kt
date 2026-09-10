package com.zhongguoxiangqi.engine

import android.content.Context
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.UciCoords
import kotlin.math.abs

/**
 * 基于 PikaFish（GPL-3）的引擎实现。
 *
 * 走法生成、搜索、评估全部由 PikaFish 的 C++ 完成，Kotlin 只负责生命周期、
 * 开局库、难度参数映射与监听器回调。
 */
class PikafishEngine(private val context: Context) : ChessEngine {

    @Volatile private var listener: EngineListener? = null
    @Volatile private var ready = false
    @Volatile private var searching = false
    @Volatile private var stopRequested = false

    private val stateLock = Any()
    private var searchThread: Thread? = null

    private var startFen: String = Position().toFen()
    private var movesPlayed: List<String> = emptyList()
    private var currentPosition: Position = Position()
    private var positionValid = true

    /** 由 [EngineManager] 注入，因为开局库需要 Android Context。 */
    var openingBook: OpeningBook? = null

    override fun isReady(): Boolean = ready

    override fun name(): String = "PikaFish（GPL-3）"

    override fun maxStrength(): Int = 10

    override fun start(): Boolean {
        synchronized(stateLock) {
            if (ready) return true
            val ok = try {
                // 权重打包在 assets/models/ 下（见 build.gradle.kts 的
                // copyPikafishNnue），assets.open 必须带 models/ 前缀，
                // 否则 FileNotFoundException 会吞成"引擎启动失败"。
                // 缓存文件名保持扁平，避免 cache/models 目录不存在导致
                // outputStream() 创建文件失败。
                val nnuePath = copyAssetToCache("models/pikafish.nnue", "pikafish.nnue")
                PikafishNative.initGlobal(nnuePath) == 0
            } catch (t: Throwable) {
                listener?.onError("PikaFish 初始化失败: ${t.message}")
                false
            }
            if (!ok) {
                listener?.onError("PikaFish 初始化失败")
                return false
            }
            ready = true
        }
        listener?.onReady()
        return true
    }

    override fun shutdown() {
        stopSearch()
        synchronized(stateLock) {
            if (ready) {
                PikafishNative.shutdown()
                ready = false
            }
        }
    }

    override fun setPosition(fen: String, moves: List<String>) {
        stopSearch()

        val pos = Position()
        val baseFen = if (fen.isBlank()) {
            pos.setupStartPosition()
            pos.toFen()
        } else {
            if (!pos.setFromFen(fen)) {
                positionValid = false
                listener?.onError("局面 FEN 无效")
                return
            }
            fen
        }
        for (uci in moves) {
            val move = UciCoords.parse(uci)
            if (move == null || !pos.isLegalMove(move) || !pos.doMove(move)) {
                positionValid = false
                listener?.onError("局面着法无效: $uci")
                return
            }
        }

        val nativeOk = synchronized(stateLock) {
            if (!ready) true
            else PikafishNative.setposition(baseFen, moves.toTypedArray(), moves.size) == 0
        }
        if (!nativeOk) {
            positionValid = false
            listener?.onError("PikaFish 无法重放局面")
            return
        }

        synchronized(stateLock) {
            startFen = baseFen
            movesPlayed = moves.toList()
            currentPosition = pos
            positionValid = true
        }
    }

    override fun startSearch(params: SearchParams) {
        stopSearch()
        if (!ready) {
            listener?.onError("PikaFish 尚未启动")
            return
        }
        if (!positionValid) {
            listener?.onError("当前局面无效，不能搜索")
            return
        }

        stopRequested = false
        searching = true
        val listenerSnap = listener
        val thread = Thread({
            try {
                val book = bookMove()
                if (book != null) {
                    waitBriefly(minOf(BOOK_MIN_THINK_MS, params.movetime.takeIf { it > 0 }
                        ?: BOOK_MIN_THINK_MS))
                    if (!stopRequested) listenerSnap?.onBestMove(UciCoords.toUci(book), null)
                    return@Thread
                }

                val depth = params.depth.coerceAtMost(MAX_DEPTH)
                val movetime = if (params.infinite) 0L else params.movetime.coerceAtLeast(0L)
                val threads = params.threads.coerceIn(1, 8)
                val hashMb = params.hashMb.coerceAtLeast(1)
                val multiPv = params.multiPv.coerceIn(1, 8)

                synchronized(stateLock) {
                    PikafishNative.setoption("Threads", threads.toString())
                    PikafishNative.setoption("Hash", hashMb.toString())
                    PikafishNative.setoption("MultiPV", multiPv.toString())
                    PikafishNative.go(movetime, depth, if (params.infinite) 1 else 0)
                }
                streamInfoWhileSearching(listenerSnap)
                PikafishNative.waitForSearch()

                val mvBuf = ByteArray(8)
                val n = PikafishNative.getBestmove(mvBuf)
                val bestUci = if (n > 0) String(mvBuf, 0, n) else ""
                if (bestUci.isBlank() || stopRequested) return@Thread

                val info = readLastInfo()
                if (info != null) listenerSnap?.onInfo(info)
                listenerSnap?.onBestMove(bestUci, readPonder())
            } catch (t: Throwable) {
                if (!stopRequested) listenerSnap?.onError(t.message ?: "PikaFish 搜索异常")
            } finally {
                searching = false
            }
        }, "PikafishEngine-search")
        thread.isDaemon = true
        searchThread = thread
        thread.start()
    }

    override fun stopSearch() {
        stopRequested = true
        PikafishNative.stop()
        val thread = searchThread
        if (thread != null && thread !== Thread.currentThread() && thread.isAlive) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (thread === searchThread) searchThread = null
        searching = false
    }

    override fun setListener(listener: EngineListener?) {
        this.listener = listener
    }

    /**
     * 读取本次搜索的 MultiPV 候选着法(引擎评分从好到差, 首条即 bestmove).
     *
     * 给"棋手风格"挑选用 —— 只能在收到 onBestMove 之后、下一次 go() 之前读,
     * 那个窗口里 g_multiPvLines 才属于刚结束的这轮搜索。
     */
    fun lastCandidates(): List<PlayStyle.Candidate> {
        if (!ready) return emptyList()
        return synchronized(stateLock) {
            val buf = ByteArray(CANDIDATES_BUF_BYTES)
            val n = PikafishNative.getMultiPvCandidates(buf)
            if (n <= 0) return@synchronized emptyList()
            String(buf, 0, n).split(';').mapNotNull { part ->
                val seg = part.split(':')
                if (seg.size < 3) return@mapNotNull null
                PlayStyle.Candidate(
                    moveUci = seg[0],
                    scoreCp = seg[1].toIntOrNull() ?: 0,
                    scoreMate = seg[2].toIntOrNull() ?: 0,
                )
            }
        }
    }

    /**
     * 搜索**过程中**把 native 侧的即时评估喂给监听器.
     *
     * C++ 每加深一层就刷新 g_lastInfo(pikafish_jni.cpp 的 set_on_update_full),
     * 但 Kotlin 侧原来只在搜索结束后 readLastInfo() 一次 —— 于是高难度那十几秒
     * 里胜率条一直冻在上一手的数字上, 玩家除了"AI thinking…"看不到任何进展,
     * 等待感被放大了好几倍。
     *
     * 只在深度真的推进时回调: 同一层重复上报只会让 UI 空转.
     */
    private fun streamInfoWhileSearching(listener: EngineListener?) {
        if (listener == null) return
        var reportedDepth = 0
        while (!stopRequested && PikafishNative.isSearching() != 0) {
            try {
                Thread.sleep(INFO_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            val info = readLastInfo() ?: continue
            if (info.depth <= reportedDepth) continue
            reportedDepth = info.depth
            listener.onInfo(info)
        }
    }

    private fun readLastInfo(): EngineInfo? {
        val out = IntArray(6)
        PikafishNative.getLastInfo(out)
        val depth = out[0]
        val cp = out[1]
        val mate = out[2]
        val timeMs = out[3].toLong() and 0xFFFFFFFFL
        val nodes = out[4].toLong() and 0xFFFFFFFFL
        val nps = out[5].toLong() and 0xFFFFFFFFL
        if (depth <= 0 && cp == 0 && mate == 0) return null
        return EngineInfo(
            depth = depth,
            timeMs = timeMs,
            nodes = nodes,
            nps = nps,
            scoreCp = if (mate != 0) 0 else cp,
            scoreMate = mate,
        )
    }

    private fun readPonder(): String? {
        val buf = ByteArray(8)
        val n = PikafishNative.getPondermove(buf)
        return if (n > 0) String(buf, 0, n) else null
    }

    private fun bookMove(): Move? {
        val (fen, moves, pos) = synchronized(stateLock) {
            Triple(startFen, movesPlayed, currentPosition.copy())
        }
        val uci = openingBook?.lookup(fen, moves) ?: return null
        val move = UciCoords.parse(uci) ?: return null
        return if (pos.isLegalMove(move)) move else null
    }

    private fun waitBriefly(totalMs: Long) {
        val until = System.currentTimeMillis() + totalMs
        while (!stopRequested && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(20L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun copyAssetToCache(assetName: String, cacheName: String = assetName): String {
        val file = java.io.File(context.cacheDir, cacheName)
        if (file.exists() && file.length() > 0) return file.absolutePath
        file.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.absolutePath
    }

    private companion object {
        const val MAX_DEPTH = 64
        const val BOOK_MIN_THINK_MS = 450L
        /** 搜索途中读 g_lastInfo 的间隔. 深度推进本身就有几百毫秒, 再密没意义 */
        const val INFO_POLL_MS = 200L
        /** MultiPV 候选串上限: 每条约 16 字节, 8 条线远够 */
        const val CANDIDATES_BUF_BYTES = 512
    }
}
