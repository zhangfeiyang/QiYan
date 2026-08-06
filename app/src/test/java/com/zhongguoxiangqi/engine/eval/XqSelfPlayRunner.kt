package com.zhongguoxiangqi.engine.eval

import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.UciCoords
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 自研 C++ 引擎(app/src/main/cpp/uci_engine.cpp) 自己跟自己对战: 新参数
 * (奇异延伸 + 激进 LMR) vs 旧参数(无奇异延伸 + 保守 LMR), 验证"节点数降低"
 * 是不是真的换来了棋力提升, 而不是拿更粗糙的搜索去赌。
 *
 * 结构照抄 [PikafishMatchRunner]: 确定性开局集 + 配对比较(每个开局双方各
 * 执一次红黑) + 误差按开局对算, 理由见那边的类注释, 不重复。区别只是两边
 * 都是 UCI 子进程(同一个可执行文件, 不同启动参数), 不是内置引擎 vs 外部
 * 引擎。
 *
 * 用法:
 * ./gradlew :app:testDebugUnitTest --tests "*XqSelfPlayRunner*" \
 *     -Dxq.match=1 -Dxq.exe=/tmp/xq_uci -Dxq.net=/home/zhangfy/nnue-nets/net328m.nnue \
 *     -Dxq.pairs=100 -Dxq.depth=6 --offline -i
 */
class XqSelfPlayRunner {

    @Test
    fun run() {
        if (System.getProperty("xq.match") == null) {
            println("[xq-selfplay] 未设置 -Dxq.match=1, 跳过"); return
        }
        val exePath = System.getProperty("xq.exe") ?: run {
            println("[xq-selfplay] 未设置 -Dxq.exe, 跳过"); return
        }
        val netPath = System.getProperty("xq.net") ?: run {
            println("[xq-selfplay] 未设置 -Dxq.net, 跳过"); return
        }
        val exe = File(exePath)
        if (!exe.canExecute()) {
            println("[xq-selfplay] 不可执行: $exePath, 跳过"); return
        }

        val pairs = System.getProperty("xq.pairs", "100").toInt()
        val depth = System.getProperty("xq.depth", "6").toInt()
        val movetimeMs = System.getProperty("xq.movetime", "0").toInt()
        val openingPlies = System.getProperty("xq.openingPlies", "6").toInt()
        val maxPlies = System.getProperty("xq.maxPlies", "200").toInt()
        val seed = System.getProperty("xq.seed", "20260801").toLong()

        // 当前在测: 在已锁定的 se=1 + lmrdiv=1.2 之上, 加 LMP + futility 两个
        // 新剪枝。基准是同一组参数但两个新剪枝关掉 —— 只变这一个因素, 别的
        // 全部对齐, 否则测出来的差值归因不到具体改动上。
        // 本轮只隔离 pawn correction history：其余参数逐项相同。
        // “新”打开 correction，“旧”关闭；正 Elo 才允许晋升默认值。
        val defaultNewArgs = listOf("--se=1", "--lmrdiv=1.2",
            "--lmp=1", "--futility=1", "--cont=1", "--dlmr=1", "--rootpvs=1",
            "--iir=1", "--ch=1", "--corr=1", "--ttsamegen=0", "--ttshift=20")
        val defaultOldArgs = listOf("--se=1", "--lmrdiv=1.2",
            "--lmp=1", "--futility=1", "--cont=1", "--dlmr=1", "--rootpvs=1",
            "--iir=1", "--ch=1", "--corr=0", "--ttsamegen=0", "--ttshift=20")
        fun argsProperty(name: String, fallback: List<String>): List<String> =
            System.getProperty(name)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
                ?: fallback
        val newArgs = argsProperty("xq.newArgs", defaultNewArgs)
        val oldArgs = argsProperty("xq.oldArgs", defaultOldArgs)
        val newLabel = System.getProperty("xq.newLabel", "新(corr=1)")
        val oldSelfLabel = System.getProperty("xq.oldLabel", "旧(corr=0)")

        // -Dxq.oppExe 指向另一个 UCI 可执行文件时, 对手换成它(典型用途: 拿
        // 我们的 C++ 引擎直接跟 Pikafish 打)。Pikafish 不吃我们的 --xxx 参数,
        // 也自己找它的权重文件, 所以对手一律"裸启动 + setoption Threads 1"。
        val oppExePath = System.getProperty("xq.oppExe")
        val oppExe = oppExePath?.let { File(it) }
        if (oppExe != null && !oppExe.canExecute()) {
            println("[xq-selfplay] 对手不可执行: $oppExePath, 跳过"); return
        }
        val newCmd = listOf(exe.absolutePath, netPath) + newArgs
        val oldCmd = if (oppExe != null) listOf(oppExe.absolutePath)
                     else listOf(exe.absolutePath, netPath) + oldArgs
        val oldLabel = if (oppExe != null) "Pikafish(${oppExe.name})" else oldSelfLabel

        val modeDesc = if (movetimeMs > 0) "限时=${movetimeMs}ms/步" else "深度=$depth"
        println("[xq-selfplay] $newLabel vs $oldLabel  $modeDesc  " +
            "$pairs 个开局 x 2 = ${pairs * 2} 盘  seed=$seed")

        val openings = buildOpenings(pairs, openingPlies, seed)
        println("[xq-selfplay] 开局集 ${openings.size} 个")

        val results = DoubleArray(openings.size * 2) { Double.NaN }
        val t0 = System.currentTimeMillis()

        val total = openings.size * 2
        var newEngine = startEngine(newCmd)
        var oldEngine = startEngine(oldCmd)
        try {
            for (g in 0 until total) {
                if (!newEngine.isAlive()) { newEngine.close(); newEngine = startEngine(newCmd) }
                if (!oldEngine.isAlive()) { oldEngine.close(); oldEngine = startEngine(oldCmd) }
                val opening = openings[g / 2]
                // 偶数盘"新参数"执红, 奇数盘"新参数"执黑 —— 配对抵消先手优势
                val newColor = if (g % 2 == 0) Color.RED else Color.BLACK
                results[g] = playGame(
                    newEngine, oldEngine, opening, newColor, depth, movetimeMs, maxPlies,
                )

                // 逐盘落地进度。一场 40 开局对的限时对战要跑近一小时, 中途一行
                // 不打的话, 既看不出还要多久, 更要命的是被超时/Ctrl-C 打断时
                // 全部结果一起蒸发 —— 这个 runner 以前就吃过这个亏。每盘打一行,
                // 打断了至少还能从最后一行读到当时的战绩。
                val done = g + 1
                val elapsed = System.currentTimeMillis() - t0
                val etaSec = if (done > 0) (elapsed.toDouble() / done * (total - done) / 1000.0) else 0.0
                val tag = when {
                    results[g].isNaN() -> "作废"
                    results[g] >= 0.99 -> "新胜"
                    results[g] <= 0.01 -> "新负"
                    else -> "和 "
                }
                emit("[xq-selfplay] 进度 $done/$total  本盘=$tag  ${runningLine(results, done)}" +
                    "  已用 ${"%.0f".format(elapsed / 1000.0)}s  预计剩余 ${"%.0f".format(etaSec)}s")
            }
        } finally {
            newEngine.close()
            oldEngine.close()
        }

        report(results, openings.size, System.currentTimeMillis() - t0)
    }

    /**
     * 进度输出。同时写 stdout 和 -Dxq.progressFile 指定的文件。
     *
     * 只写 stdout 是不够的: Gradle 会把测试进程的标准输出整体捕获, 跑的过程中
     * 一个字节都不往外放, 只在测试结束后统一塞进 JUnit XML —— 所以在 Kotlin
     * 里怎么 flush()、命令行怎么 stdbuf 都没用, 中途照样看不到进度。想在跑的
     * 过程中看, 唯一可靠的办法是绕开 Gradle 的捕获, 自己往文件里追加。
     */
    private val progressFile: java.io.File? =
        System.getProperty("xq.progressFile")?.let { java.io.File(it) }

    private fun emit(line: String) {
        println(line)
        System.out.flush()
        progressFile?.let {
            try {
                it.appendText(line + "\n")   // 每行即开即关, 进程被杀也不会丢
            } catch (e: Exception) {
                println("[xq-selfplay] !! 进度文件写入失败: ${e.message}")
            }
        }
    }

    /** 已完成盘数下的即时战绩 + 完整开局对的即时 Elo, 供逐盘进度行使用 */
    private fun runningLine(results: DoubleArray, done: Int): String {
        var w = 0; var l = 0; var d = 0
        for (i in 0 until done) when {
            results[i].isNaN() -> {}
            results[i] >= 0.99 -> w++
            results[i] <= 0.01 -> l++
            else -> d++
        }
        // Elo 只按"红黑都下完"的完整开局对算, 半对会被先手优势带偏
        val donePairs = (0 until done / 2).filter {
            !results[it * 2].isNaN() && !results[it * 2 + 1].isNaN()
        }
        val eloStr = if (donePairs.isEmpty()) "-" else {
            val s = donePairs.sumOf { results[it * 2] + results[it * 2 + 1] } / donePairs.size / 2.0
            // 全胜/全负时 Elo 公式发散, 截到 ±1200; 注意要带**方向**, 早期
            // 只下完一对时很容易撞上这个分支, 写成无方向的"±1200"会让人读反
            when {
                s <= 0.0005 -> "-1200"
                s >= 0.9995 -> "+1200"
                else -> "%+.0f".format(-400.0 * log10(1.0 / s - 1.0))
            }
        }
        return "累计 ${w}W ${l}L ${d}D  即时Elo≈$eloStr"
    }

    private fun buildOpenings(want: Int, plies: Int, seed: Long): List<Position> {
        val out = ArrayList<Position>(want)
        val legal = mutableListOf<Move>()
        var i = 0
        while (out.size < want && i < want * 10) {
            val rng = Random(seed + i)
            i++
            val pos = Position().also { it.setupStartPosition() }
            var ok = true
            repeat(plies) {
                legal.clear()
                Rules.generateAllLegal(pos, legal)
                if (legal.isEmpty()) { ok = false; return@repeat }
                pos.doMove(legal[rng.nextInt(legal.size)])
            }
            if (!ok) continue
            legal.clear()
            Rules.generateAllLegal(pos, legal)
            if (legal.isEmpty()) continue
            out.add(pos.copy())
        }
        return out
    }

    private val reasons = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun note(r: String) {
        reasons.computeIfAbsent(r) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }

    /** @return "新参数"一方得分: 1.0 胜 / 0.5 和 / 0.0 负 */
    private fun playGame(
        newEngine: UciProc, oldEngine: UciProc, opening: Position, newColor: Int,
        depth: Int, movetimeMs: Int, maxPlies: Int,
    ): Double {
        val pos = opening.copy()
        val legal = mutableListOf<Move>()
        for (ply in 0 until maxPlies) {
            legal.clear()
            Rules.generateAllLegal(pos, legal)
            if (legal.isEmpty()) {
                return if (Rules.isInCheck(pos, pos.sideToMove)) {
                    if (pos.sideToMove == newColor) { note("新参数被将死"); 0.0 }
                    else { note("旧参数被将死"); 1.0 }
                } else { note("困毙判和"); 0.5 }
            }
            if (pos.isFiftyMoveDraw()) { note("60回合判和"); return 0.5 }
            if (pos.isThreefoldRepetition()) { note("三次重复判和"); return 0.5 }

            val engine = if (pos.sideToMove == newColor) newEngine else oldEngine
            val fen = pos.toFen()
            engine.send("position fen $fen")
            engine.send(if (movetimeMs > 0) "go movetime $movetimeMs" else "go depth $depth")
            val resp = engine.readUntil { it.startsWith("bestmove") }
            val parts = resp.split(" ")
            val parsed = if (parts.size >= 2) UciCoords.parse(parts[1]) else null
            if (parsed == null || legal.none { it.data == parsed.data }) {
                note("!! 引擎故障/非法着法(作废): $resp")
                return Double.NaN
            }
            pos.doMove(parsed)
        }
        note("$maxPlies 步和"); return 0.5
    }

    private fun report(results: DoubleArray, nPairs: Int, ms: Long) {
        var w = 0; var l = 0; var d = 0
        for (r in results) when {
            r.isNaN() -> {}
            r >= 0.99 -> w++
            r <= 0.01 -> l++
            else -> d++
        }
        val valid = (0 until nPairs).filter {
            !results[it * 2].isNaN() && !results[it * 2 + 1].isNaN()
        }
        if (valid.size < nPairs) {
            println("[xq-selfplay] !! ${nPairs - valid.size}/$nPairs 个开局对作废, 已剔除")
        }
        if (valid.isEmpty()) { println("[xq-selfplay] !! 无有效对局"); return }
        val pairScores = DoubleArray(valid.size) { results[valid[it] * 2] + results[valid[it] * 2 + 1] }
        val mean = pairScores.average()
        val score = mean / 2.0
        val n = pairScores.size
        val sd = if (n > 1) sqrt(pairScores.sumOf { (it - mean) * (it - mean) } / (n - 1)) else 0.0
        val se = sd / sqrt(n.toDouble()) / 2.0

        fun elo(s: Double): Double =
            if (s <= 0.0005) -1200.0 else if (s >= 0.9995) 1200.0 else -400.0 * log10(1.0 / s - 1.0)

        val lo = elo((score - 1.96 * se).coerceIn(0.0, 1.0))
        val hi = elo((score + 1.96 * se).coerceIn(0.0, 1.0))
        val e = elo(score)
        emit("[xq-selfplay] 战绩(新参数视角) ${w}W ${l}L ${d}D  得分率 ${"%.1f".format(score * 100)}%")
        emit("[xq-selfplay] Elo ${"%.0f".format(e)}  95%% 区间 [${"%.0f".format(lo)}, ${"%.0f".format(hi)}]" +
            "  (按 $n 个有效开局对算)")
        emit("[xq-selfplay] 耗时 ${"%.0f".format(ms / 1000.0)}s")
        println("[xq-selfplay] 终局原因:")
        reasons.entries.sortedByDescending { it.value.get() }.take(8).forEach {
            println("[xq-selfplay]    ${it.key}  x${it.value.get()}")
        }
    }

    // ---- UCI 子进程 ----
    private data class UciProc(val proc: Process, val writer: PrintWriter, val reader: BufferedReader) {
        fun isAlive(): Boolean = proc.isAlive
        fun send(cmd: String) { writer.println(cmd); writer.flush() }
        fun readUntil(pred: (String) -> Boolean): String {
            while (true) {
                val line = reader.readLine() ?: return ""
                if (pred(line)) return line
            }
        }
        fun close() {
            try { send("quit") } catch (_: Exception) {}
            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            proc.destroyForcibly()
        }
    }

    private fun startEngine(cmd: List<String>): UciProc {
        val pb = ProcessBuilder(cmd)
        // 工作目录设成可执行文件所在目录: Pikafish 是按相对路径找自己的权重的
        pb.directory(File(cmd[0]).parentFile)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val writer = PrintWriter(proc.outputStream, true)
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        writer.println("uci"); writer.flush()
        while (true) {
            val line = reader.readLine() ?: break
            if (line == "uciok") break
        }
        // 单线程: 多线程搜索依赖线程调度, 会让整场比赛不可复现。我们自己的
        // 引擎忽略这条, Pikafish 需要它。
        writer.println("setoption name Threads value 1"); writer.flush()
        writer.println("isready"); writer.flush()
        while (true) {
            if (reader.readLine() == "readyok") break
        }
        return UciProc(proc, writer, reader)
    }
}
