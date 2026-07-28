package com.zhongguoxiangqi.engine.eval

import com.zhongguoxiangqi.engine.SimpleEngine
import com.zhongguoxiangqi.engine.nnue.NnueEvaluator
import com.zhongguoxiangqi.engine.nnue.NnueNetwork
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.UciCoords
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlin.random.Random

/**
 * 自研引擎 vs Pikafish (开源最强基线) 对战跑分.
 *
 * 前提: 已在 third_party/Pikafish/src/ 下 make build 出 pikafish 可执行文件.
 *
 * 用法:
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*PikafishMatchRunner*" \
 *     -Dpika.exe=third_party/Pikafish/src/pikafish \
 *     -Dpika.match=1 -Dpika.games=40 -Dpika.depth=3 -Dpika.threads=4 \
 *     -Dpika.net=/home/zhangfy/nnue-nets/gen2.nnue
 * ```
 */
class PikafishMatchRunner {

    @Test
    fun run() {
        if (System.getProperty("pika.match") == null) {
            println("[pika-match] 未设置 -Dpika.match=1, 跳过")
            return
        }
        val exePath = System.getProperty("pika.exe", "third_party/Pikafish/src/pikafish")
        val exe = File(exePath)
        if (!exe.canExecute()) {
            println("[pika-match] pikafish 不可执行: $exePath, 跳过")
            return
        }

        val games = System.getProperty("pika.games", "40").toInt()
        val depth = System.getProperty("pika.depth", "3").toInt()
        val threads = System.getProperty("pika.threads", "4").toInt()
        val netPath = System.getProperty("pika.net")
        val ourNet = netPath?.let { FileInputStream(it).use { f -> NnueNetwork.load(f) } }

        println("[pika-match] 自研${ourNet?.let { "NNUE(l1=${it.l1})" } ?: "手写评估"}  " +
            "vs Pikafish  深度=$depth  $games 局  threads=$threads")

        val rng = Random(20260729L)
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val t0 = System.currentTimeMillis()
        val done = java.util.concurrent.atomic.AtomicInteger()

        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val perThread = maxOf(1, games / threads)

        for (t in 0 until threads) {
            pool.submit {
                val pika = startPikafish(exe)
                val engine = SimpleEngine()
                engine.start()
                engine.setNnue(ourNet?.let { NnueEvaluator(it) })
                val pos = Position()
                val legal = mutableListOf<Move>()
                var g = 0

                try {
                    repeat(perThread) {
                        val seed = rng.nextLong()
                        val localRng = Random(seed)
                        pos.setupStartPosition()
                        val ourColor = if (localRng.nextBoolean()) Color.RED else Color.BLACK
                        var result = "?"

                        for (ply in 0 until 200) {
                            legal.clear()
                            Rules.generateAllLegal(pos, legal)
                            if (legal.isEmpty()) {
                                // 无子可走 → 被将死(输) 或 困毙(和)
                                result = if (Rules.isInCheck(pos, pos.sideToMove)) {
                                    if (pos.sideToMove == ourColor) "L" else "W"
                                } else "D"
                                break
                            }

                            val ourTurn = pos.sideToMove == ourColor
                            val move: Move

                            if (ply < 4) {
                                // 开局随机4步
                                move = legal[localRng.nextInt(legal.size)]
                            } else if (ourTurn) {
                                engine.setPositionDirect(pos)
                                move = engine.searchWithScore(depth).first
                                if (move.data == 0) { result = "L"; break }
                                // 校验着法合法性
                                if (legal.none { it.data == move.data }) { result = "L"; break }
                            } else {
                                pika.send("position fen ${pos.toFen()}")
                                pika.send("go depth $depth")
                                val resp = pika.readUntil { it.startsWith("bestmove") }
                                val parts = resp.split(" ")
                                val uci = if (parts.size >= 2) parts[1] else ""
                                val parsed = UciCoords.parse(uci)
                                if (parsed == null) { result = "W"; break }
                                // 校验着法合法性
                                if (legal.none { it.data == parsed.data }) { result = "W"; break }
                                move = parsed
                            }

                            pos.doMove(move)
                        }
                        if (result == "?") result = "D" // 200步走满 = 和
                        results.add(result)
                        g++
                        val n = done.incrementAndGet()
                        if (n % 5 == 0 || n == games) {
                            val w = results.count { it == "W" }
                            val l = results.count { it == "L" }
                            val d = results.count { it == "D" }
                            val secs = (System.currentTimeMillis() - t0) / 1000.0
                            val pct = "%.1f".format((w + d * 0.5) / n * 100)
                            println("[pika-match] $n/$games  " +
                                "${w}W ${l}L ${d}D  score=$pct%  " +
                                "${"%.0f".format(secs)}s")
                        }
                    }
                } finally {
                    engine.shutdown()
                    pika.close()
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.DAYS)

        val w = results.count { it == "W" }
        val l = results.count { it == "L" }
        val d = results.count { it == "D" }
        val score = (w + d * 0.5) / games
        val elo = if (score in 0.001..0.999) -400.0 * Math.log10(1.0 / score - 1.0) else 0.0
        val margin = if (w + l > 0)
            1.96 * 400.0 * Math.sqrt((w + l).toDouble()) / (2.0 * games) else 999.0
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        println("[pika-match] 结果: ${w}W ${l}L ${d}D  score=${"%.1f".format(score * 100)}%  " +
            "Elo=${"%.0f".format(elo)} ± ${"%.0f".format(margin)}  耗时=${"%.0f".format(secs)}s")
        if (elo > 0) println("[pika-match] 自研 > Pikafish 约 ${"%.0f".format(elo)} Elo")
        else if (elo < 0) println("[pika-match] Pikafish > 自研 约 ${"%.0f".format(-elo)} Elo")
        else println("[pika-match] 势均力敌")
    }

    // ---- Pikafish UCI 子进程 ----

    private data class PikaProc(
        val proc: Process,
        val writer: PrintWriter,
        val reader: BufferedReader,
    ) {
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

    private fun startPikafish(exe: File): PikaProc {
        val pb = ProcessBuilder(exe.absolutePath)
        pb.directory(exe.parentFile)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val writer = PrintWriter(proc.outputStream, true)
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        writer.println("uci"); writer.flush()
        var ok = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line == "uciok") { ok = true; break }
        }
        check(ok) { "Pikafish 未响应 uciok" }
        writer.println("isready"); writer.flush()
        while (true) {
            if (reader.readLine() == "readyok") break
        }
        return PikaProc(proc, writer, reader)
    }
}
