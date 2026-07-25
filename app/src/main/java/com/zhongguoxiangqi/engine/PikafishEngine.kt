package com.zhongguoxiangqi.engine

/**
 * Pikafish 引擎包装.
 *
 * 通过 UCI 协议与 Pikafish 子进程通信.
 * Pikafish 是基于 Stockfish 的中国象棋引擎,NNUE 神经网络驱动,
 * 强度世界冠军级别,轻松击败最顶级的人类棋手.
 *
 * 部署方式:
 * 1. 在 assets/engine/ 下放置 pikafish-{arch} 可执行文件
 *    - pikafish-arm64-v8a
 *    - pikafish-armeabi-v7a
 *    - pikafish-x86_64
 * 2. 放置 NNUE 网络文件 pikafish.nnue (约 40MB)
 * 3. 首次启动时将可执行文件复制到 filesDir 并 chmod 755
 */
class PikafishEngine(
    private val binaryPath: String,
    private val nnuePath: String
) : ChessEngine {

    @Volatile private var ready = false
    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private var readerThread: Thread? = null
    @Volatile private var listener: EngineListener? = null
    @Volatile private var threads = 2
    @Volatile private var hashMb = 64

    override fun isReady() = ready

    override fun name() = "Pikafish NNUE"

    override fun maxStrength() = 10

    override fun start(): Boolean {
        return try {
            shutdown()

            val pb = ProcessBuilder(binaryPath)
            pb.redirectErrorStream(false)
            val env = pb.environment()
            // NNUE 网络文件路径
            env["PIKAFISH_NETWORK"] = nnuePath

            val p = pb.start()
            process = p

            writer = p.outputStream.bufferedWriter()
            startReader(p.inputStream, false)
            startReader(p.errorStream, true)

            // UCI 握手
            send("uci")
            waitFor("uciok", 10_000)
            send("setoption name UCI_AnalyseMode value true")
            send("setoption name Threads value $threads")
            send("setoption name Hash value $hashMb")
            send("setoption name EvalFile value $nnuePath")
            send("isready")
            waitFor("readyok", 15_000)

            ready = true
            listener?.onReady()
            true
        } catch (e: Exception) {
            android.util.Log.e("Pikafish", "Start failed: ${e.message}", e)
            listener?.onError("引擎启动失败: ${e.message}")
            false
        }
    }

    override fun shutdown() {
        try {
            send("quit")
            process?.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) { }
        try { process?.destroyForcibly() } catch (_: Exception) { }
        process = null
        writer = null
        readerThread?.interrupt()
        readerThread = null
        ready = false
    }

    override fun setPosition(fen: String, moves: List<String>) {
        val sb = StringBuilder("position fen $fen")
        if (moves.isNotEmpty()) {
            sb.append(" moves")
            for (m in moves) sb.append(" $m")
        }
        send(sb.toString())
    }

    override fun startSearch(params: SearchParams) {
        if (params.threads != threads) {
            threads = params.threads
            send("setoption name Threads value $threads")
        }
        if (params.hashMb != hashMb) {
            hashMb = params.hashMb
            send("setoption name Hash value $hashMb")
        }
        if (params.multiPv > 1) {
            send("setoption name MultiPV value ${params.multiPv}")
        } else {
            send("setoption name MultiPV value 1")
        }

        val cmd = StringBuilder("go")
        when {
            params.infinite -> cmd.append(" infinite")
            params.movetime > 0 -> cmd.append(" movetime ${params.movetime}")
            params.depth > 0 -> cmd.append(" depth ${params.depth}")
            params.nodes > 0 -> cmd.append(" nodes ${params.nodes}")
            else -> cmd.append(" movetime 3000")  // 默认 3 秒
        }
        send(cmd.toString())
    }

    override fun stopSearch() {
        send("stop")
    }

    override fun setListener(listener: EngineListener?) {
        this.listener = listener
    }

    private fun send(command: String) {
        try {
            synchronized(writer!!) {
                writer!!.write(command)
                writer!!.newLine()
                writer!!.flush()
            }
        } catch (e: Exception) {
            android.util.Log.w("Pikafish", "send failed: $command, ${e.message}")
        }
    }

    private fun waitFor(token: String, timeoutMs: Long): Boolean {
        // 简化实现: 简单的轮询 (引擎握手耗时短暂,可接受)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (lineBuffer.any { it.contains(token) }) return true
            try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    // 行缓冲(用于 waitFor)
    private val lineBuffer = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun startReader(stream: java.io.InputStream, isErr: Boolean) {
        val thread = Thread({
            val br = stream.bufferedReader()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val line = br.readLine() ?: break
                    if (isErr) {
                        android.util.Log.d("Pikafish-err", line)
                        continue
                    }
                    lineBuffer.offer(line)
                    handleLine(line)
                }
            } catch (_: java.io.IOException) { }
        }, "Pikafish-${if (isErr) "err" else "out"}-reader")
        thread.isDaemon = true
        thread.start()
        if (!isErr) readerThread = thread
    }

    /** 解析 UCI 输出 */
    private fun handleLine(line: String) {
        val l = listener ?: return
        try {
            when {
                line.startsWith("info ") -> {
                    val info = parseInfo(line)
                    if (info != null) l.onInfo(info)
                }
                line.startsWith("bestmove ") -> {
                    val parts = line.split(" ")
                    if (parts.size >= 2) {
                        val best = parts[1]
                        val ponder = if (parts.size >= 4 && parts[2] == "ponder") parts[3] else null
                        l.onBestMove(best, ponder)
                    }
                }
                line.startsWith("uciok") -> { /* handled by waitFor */ }
                line.startsWith("readyok") -> { /* handled by waitFor */ }
            }
        } catch (e: Exception) {
            android.util.Log.w("Pikafish", "Parse line failed: $line", e)
        }
    }

    private fun parseInfo(line: String): EngineInfo? {
        // info depth 18 seldepth 26 time 1234 nodes 567890 nps 459876 hashfull 234
        //   score cp 23 lowerbound ... upperbound ...
        //   score mate 3
        //   pv e7e5 g1f3 ...
        //   multipv 1 ...
        val tokens = line.split(" ").drop(1)
        var depth = 0; var seldepth = 0
        var timeMs = 0L; var nodes = 0L; var nps = 0L
        var scoreCp = 0; var scoreMate = 0
        var pv = mutableListOf<String>()
        var pvIndex = 0
        var hashfull = 0

        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> { depth = tokens.getOrNull(++i)?.toIntOrNull() ?: 0 }
                "seldepth" -> { seldepth = tokens.getOrNull(++i)?.toIntOrNull() ?: 0 }
                "time" -> { timeMs = tokens.getOrNull(++i)?.toLongOrNull() ?: 0L }
                "nodes" -> { nodes = tokens.getOrNull(++i)?.toLongOrNull() ?: 0L }
                "nps" -> { nps = tokens.getOrNull(++i)?.toLongOrNull() ?: 0L }
                "hashfull" -> { hashfull = tokens.getOrNull(++i)?.toIntOrNull() ?: 0 }
                "multipv" -> { pvIndex = tokens.getOrNull(++i)?.toIntOrNull() ?: 1 }
                "score" -> {
                    i++
                    when (tokens.getOrNull(i)) {
                        "cp" -> scoreCp = tokens.getOrNull(++i)?.toIntOrNull() ?: 0
                        "mate" -> scoreMate = tokens.getOrNull(++i)?.toIntOrNull() ?: 0
                    }
                }
                "pv" -> {
                    i++
                    pv.clear()
                    while (i < tokens.size && !isInfoToken(tokens[i])) {
                        pv.add(tokens[i])
                        i++
                    }
                    continue  // 跳过下面的 i++
                }
            }
            i++
        }
        return EngineInfo(depth, seldepth, timeMs, nodes, nps, scoreCp, scoreMate, pv, pvIndex, hashfull)
    }

    private fun isInfoToken(t: String): Boolean =
        t in setOf("depth", "seldepth", "time", "nodes", "nps", "hashfull",
            "multipv", "score", "pv", "string", "currmove", "currmovenumber",
            "tbhits", "sbhits", "cpuload")

    companion object {
        /**
         * 检查 Pikafish 二进制是否已部署到内部存储.
         * 如果没有,从 assets 复制并赋予执行权限.
         */
        fun ensureInstalled(
            context: android.content.Context,
            assetDir: String = "engine"
        ): Pair<String, String>? {
            val abi = currentAbi()
            val binName = "pikafish-$abi"
            val nnueName = "pikafish.nnue"

            val engineDir = java.io.File(context.filesDir, "engine").apply { mkdirs() }
            val binFile = java.io.File(engineDir, binName)
            val nnueFile = java.io.File(engineDir, nnueName)

            try {
                // 复制 Pikafish 二进制
                if (!binFile.exists() || binFile.length() == 0L) {
                    copyAsset(context, "$assetDir/$binName", binFile)
                    binFile.setExecutable(true)
                    binFile.setReadable(true)
                }
                // 复制 NNUE 文件
                if (!nnueFile.exists() || nnueFile.length() == 0L) {
                    copyAsset(context, "$assetDir/$nnueName", nnueFile)
                }
            } catch (e: Exception) {
                android.util.Log.e("Pikafish", "Install failed", e)
                return null
            }
            if (!binFile.exists() || !nnueFile.exists()) return null
            return Pair(binFile.absolutePath, nnueFile.absolutePath)
        }

        private fun copyAsset(context: android.content.Context, assetPath: String, dst: java.io.File) {
            context.assets.open(assetPath).use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        private fun currentAbi(): String {
            val abis = android.os.Build.SUPPORTED_ABIS
            for (abi in abis) {
                if (abi == "arm64-v8a") return "arm64-v8a"
                if (abi == "armeabi-v7a") return "armeabi-v7a"
                if (abi == "x86_64") return "x86_64"
                if (abi == "x86") return "x86_64"  // 32 位 x86 用 64 位兼容
            }
            return "arm64-v8a"  // 默认
        }
    }
}