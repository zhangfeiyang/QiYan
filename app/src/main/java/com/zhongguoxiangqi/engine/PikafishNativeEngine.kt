package com.zhongguoxiangqi.engine

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通过 JNI 把 Pikafish 当成 .so 加载, 引擎在同进程内.
 *
 * 跟子进程路线的差别:
 * - 没有 Process / IO 流
 * - 搜索在工作线程里跑, waitForSearch() 阻塞等待完成后 read bestmove
 * - 不需要 chmod +x / SELinux exec 权限
 *
 * init 流程: 拷贝 NNUE 到 filesDir → 调 initGlobal → 引擎就绪.
 */
class PikafishNativeEngine(
    private val nnueFile: File,
    private val listener: EngineListener?
) : ChessEngine {

    private val nnuePath: String = nnueFile.absolutePath
    @Volatile private var ready = false
    private var searchThread: Thread? = null

    /**
     * 搜索代号. 每次开新搜索/停搜索/换局面都 +1.
     *
     * 全局只有一个 Pikafish 实例, 后台分析(infinite)和对局搜索共用它.
     * 停掉一个搜索时, 引擎会立刻吐出它当时的 bestmove —— 那是**上一个局面**
     * 的结果. 如果不加区分地回调出去, 调用方会把"给玩家算的着法"当成
     * "引擎自己要走的着法"用, 于是报"引擎给出非法着法".
     * 线程只在自己的代号仍是最新时才回调.
     */
    private val generation = AtomicInteger(0)

    override fun isReady(): Boolean = ready
    override fun name() = "Pikafish NNUE"
    override fun maxStrength() = 10

    override fun start(): Boolean {
        if (ready) return true
        return try {
            val rc = PikafishNative.initGlobal(nnuePath)
            if (rc == 0) {
                ready = true
                listener?.onReady()
                true
            } else {
                android.util.Log.e(TAG, "init failed: rc=$rc")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "init threw", e)
            false
        }
    }

    override fun shutdown() {
        ready = false
        generation.incrementAndGet()
        try { PikafishNative.shutdown() } catch (_: Exception) {}
        searchThread?.interrupt()
        searchThread = null
    }

    override fun setPosition(fen: String, moves: List<String>) {
        if (!ready) return
        // 换局面前必须先收掉在跑的搜索: 原生 Engine 不允许在搜索途中改根局面,
        // 而且旧搜索的结果对新局面已经没有意义.
        abortRunningSearch()
        // 清置换表 + 历史, 否则上局中局的局面评估会污染新局.
        PikafishNative.searchClear()
        PikafishNative.setposition(fen, moves.toTypedArray(), moves.size)
    }

    /** 停掉并回收当前搜索线程, 同时作废它的结果 */
    private fun abortRunningSearch() {
        generation.incrementAndGet()
        val prev = searchThread ?: return
        searchThread = null
        if (!prev.isAlive) return
        try { PikafishNative.stop() } catch (_: Exception) {}
        try {
            prev.join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun startSearch(params: SearchParams) {
        if (!ready) return

        // 上一轮搜索还没收干净时不安全
        abortRunningSearch()
        val myGen = generation.get()

        // 应用当前参数
        PikafishNative.setoption("Threads", params.threads.coerceAtLeast(1).toString())
        PikafishNative.setoption("Hash", params.hashMb.coerceAtLeast(16).toString())
        if (params.multiPv >= 1) {
            PikafishNative.setoption("MultiPV", params.multiPv.toString())
        }

        val listenerSnap = listener
        val thread = Thread({
            try {
                PikafishNative.go(
                    params.movetime,
                    params.depth,
                    if (params.infinite) 1 else 0
                )

                // Poll the engine's latest evaluation for real-time win-rate
                // updates. getLastInfo reads from a mutex-protected global
                // that the on_update_full callback writes to, so this is
                // thread-safe and non-blocking.
                val infoBuf = IntArray(6)
                var lastDepth = 0
                while (PikafishNative.isSearching() != 0) {
                    PikafishNative.getLastInfo(infoBuf)
                    val depth = infoBuf[0]
                    // 浅层搜索 (depth < 10) 的评估值波动极大, 跳过.
                    // Pikafish 在 1 秒内就能到 depth 18+, 不丢信息.
                    if (generation.get() != myGen) return@Thread
                    if (depth > lastDepth && depth >= MIN_REPORT_DEPTH) {
                        lastDepth = depth
                        val scoreCp   = infoBuf[1]
                        val scoreMate = infoBuf[2]
                        val timeMs    = infoBuf[3].toLong()
                        val nodes     = infoBuf[4].toLong()
                        val nps       = infoBuf[5].toLong()
                        listenerSnap?.onInfo(EngineInfo(
                            depth     = depth,
                            scoreCp   = scoreCp,
                            scoreMate = scoreMate,
                            timeMs    = timeMs,
                            nodes     = nodes,
                            nps       = nps
                        ))
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                }

                PikafishNative.waitForSearch()
                if (listenerSnap == null) return@Thread
                // 这一轮已被取消/被新搜索顶替: 它的 bestmove 属于旧局面, 丢弃.
                if (generation.get() != myGen) return@Thread
                val buf = ByteArray(32)
                val n = PikafishNative.getBestmove(buf)
                if (n > 0) {
                    val uci = String(buf, 0, n)
                    val pondBuf = ByteArray(32)
                    val pn = PikafishNative.getPondermove(pondBuf)
                    val ponder = if (pn > 0) String(pondBuf, 0, pn) else null
                    listenerSnap.onBestMove(uci, ponder)
                }
            } catch (e: InterruptedException) {
                // thread interrupted during sleep — normal during shutdown
            } catch (e: Exception) {
                if (generation.get() == myGen) {
                    listenerSnap?.onError(e.message ?: "native engine error")
                }
            }
        }, "PikafishNative-search")
        thread.isDaemon = true
        searchThread = thread
        thread.start()
    }

    override fun stopSearch() {
        if (!ready) return
        // 作废当前搜索: stop() 会让引擎立刻吐一个 bestmove, 那个结果必须丢弃
        generation.incrementAndGet()
        try { PikafishNative.stop() } catch (_: Exception) {}
    }

    override fun setListener(listener: EngineListener?) {}

    companion object {
        private const val TAG = "PikafishNativeEngine"
        private const val JOIN_TIMEOUT_MS = 2000L
        private const val POLL_INTERVAL_MS = 150L  // ~6-7 Hz, responsive enough for UI
        private const val MIN_REPORT_DEPTH = 8   // suppress noisy shallow-depth evals
    }
}

/**
 * 把 NNUE 文件从 assets 拷到 filesDir 并返回绝对路径.
 *
 * 用 assets 同目录的 .nnue.sha256 做新鲜度检查: 跟 filesDir 里的 sidecar
 * 不一致就重新拷贝. 首次安装或 APK 升级时 sidecar 缺失, 强制重拷.
 */
internal object PikafishNativeInstaller {

    fun ensureNnueInstalled(context: Context): File? {
        val dir = File(context.filesDir, "engine").apply { mkdirs() }
        val dst = File(dir, "pikafish.nnue")
        val sidecarDst = File(dir, "pikafish.nnue.sha256")

        val wantSha = try {
            context.assets.open("engine/pikafish.nnue.sha256").use {
                it.readBytes().toString(Charsets.UTF_8).trim()
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }

        val haveSha = if (sidecarDst.exists()) {
            sidecarDst.readText().trim().takeIf { it.isNotEmpty() }
        } else null

        if (wantSha != null && wantSha == haveSha && dst.exists() && dst.length() > 0L) {
            return dst
        }

        // 需要重新拷贝
        if (dst.exists()) dst.delete()
        return try {
            context.assets.open("engine/pikafish.nnue").use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (wantSha != null) {
                sidecarDst.writeText(wantSha + "\n")
            }
            dst
        } catch (e: Exception) {
            android.util.Log.e("PikafishNative", "nnue install failed", e)
            null
        }
    }
}