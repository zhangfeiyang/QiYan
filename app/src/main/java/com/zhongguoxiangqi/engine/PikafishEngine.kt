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
    }
}
