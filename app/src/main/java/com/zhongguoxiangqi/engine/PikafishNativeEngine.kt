package com.zhongguoxiangqi.engine

import android.content.Context
import java.io.File

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
        try { PikafishNative.shutdown() } catch (_: Exception) {}
        searchThread?.interrupt()
        searchThread = null
    }

    override fun setPosition(fen: String, moves: List<String>) {
        if (!ready) return
        // 清置换表 + 历史, 否则上局中局的局面评估会污染新局.
        PikafishNative.searchClear()
        PikafishNative.setposition(fen, moves.toTypedArray(), moves.size)
    }

    override fun startSearch(params: SearchParams) {
        if (!ready) return

        // 上一轮搜索还没收干净时不安全
        searchThread?.let { prev ->
            if (prev.isAlive) {
                try { PikafishNative.stop() } catch (_: Exception) {}
                try { prev.join(JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {}
            }
        }

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
                PikafishNative.waitForSearch()
                if (listenerSnap == null) return@Thread
                val buf = ByteArray(32)
                val n = PikafishNative.getBestmove(buf)
                if (n > 0) {
                    val uci = String(buf, 0, n)
                    val pondBuf = ByteArray(32)
                    val pn = PikafishNative.getPondermove(pondBuf)
                    val ponder = if (pn > 0) String(pondBuf, 0, pn) else null
                    listenerSnap.onBestMove(uci, ponder)
                }
            } catch (e: Exception) {
                listenerSnap?.onError(e.message ?: "native engine error")
            }
        }, "PikafishNative-search")
        thread.isDaemon = true
        searchThread = thread
        thread.start()
    }

    override fun stopSearch() {
        if (!ready) return
        try { PikafishNative.stop() } catch (_: Exception) {}
    }

    override fun setListener(listener: EngineListener?) {}

    companion object {
        private const val TAG = "PikafishNativeEngine"
        private const val JOIN_TIMEOUT_MS = 2000L
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