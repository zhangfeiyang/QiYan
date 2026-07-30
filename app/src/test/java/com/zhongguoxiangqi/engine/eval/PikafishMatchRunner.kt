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
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 自研引擎 vs Pikafish 对战跑分.
 *
 * ## 为什么要重写
 *
 * 原来的版本**同一配置连跑三次会给出 -70 / -338 / -301**, 而它自报的置信
 * 区间是 ±60 —— 真实方差被低估了约四倍, 拿它做的任何比较都是在读噪声。
 * 根因是所有线程共享一个 `Random` 实例并发取种子: `kotlin.random.Random`
 * 不是线程安全的, 每次运行拿到的开局集都不一样, 40 局的样本量下开局运气
 * 直接主导结果。
 *
 * 现在的设计:
 *
 * 1. **开局集确定性生成**. 单线程按 `Random(seed + i)` 造第 i 个开局, 与
 *    线程调度无关。
 * 2. **配对比较**. 每个开局下**两盘**, 红黑各一次。先手优势因此抵消, 而且
 *    不同网络面对的是**完全相同**的局面 —— 方差主要来自网络差异而不是开局
 *    运气, 这是能用几百盘分辨几十 Elo 的关键。
 * 3. **线程按下标切分**. 第 g 盘固定由第 `g % threads` 个线程跑, 结果与
 *    调度无关。加上双方引擎在固定深度下都是确定性的, 整场比赛可复现 ——
 *    同样参数跑两次结果必须逐字节相同。
 * 4. **误差按开局对算**, 不按盘算。两盘同开局的结果高度相关, 当成独立样本
 *    会把置信区间算窄一半。
 *
 * 用法:
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*PikafishMatchRunner*" \
 *     -Dpika.match=1 -Dpika.exe=/abs/path/to/pikafish \
 *     -Dpika.pairs=200 -Dpika.depth=3 -Dpika.threads=4 \
 *     -Dpika.net=/home/zhangfy/nnue-nets/gen4.nnue
 * ```
 * 注意 `pika.exe` 要用**绝对路径**: 测试 JVM 的工作目录是 `app/`。
 */
class PikafishMatchRunner {

    @Test
    fun run() {
        if (System.getProperty("pika.match") == null) {
            println("[pika-match] 未设置 -Dpika.match=1, 跳过")
            return
        }
        val exePath = System.getProperty("pika.exe") ?: run {
            println("[pika-match] 未设置 -Dpika.exe, 跳过"); return
        }
        val exe = File(exePath)
        if (!exe.canExecute()) {
            println("[pika-match] pikafish 不可执行: $exePath (注意要用绝对路径), 跳过")
            return
        }

        val pairs = System.getProperty("pika.pairs", "200").toInt()
        val depth = System.getProperty("pika.depth", "3").toInt()
        val threads = System.getProperty("pika.threads", "4").toInt()
        val openingPlies = System.getProperty("pika.openingPlies", "6").toInt()
        val seed = System.getProperty("pika.seed", "20260730").toLong()
        val netPath = System.getProperty("pika.net")
        val ourNet = netPath?.let { FileInputStream(it).use { f -> NnueNetwork.load(f) } }

        val label = if (ourNet != null) "NNUE(l1=" + ourNet.l1 + ")" else "手写评估"
        println("[pika-match] 自研 $label vs Pikafish  深度=$depth  " +
            "$pairs 个开局 x 2 = ${pairs * 2} 盘  threads=$threads  seed=$seed")

        // ---- 开局集: 单线程确定性生成, 与调度无关 ----
        val openings = buildOpenings(pairs, openingPlies, seed)
        println("[pika-match] 开局集 ${openings.size} 个 (每个随机 $openingPlies 步, 已排除无着可走的)")

        val total = openings.size * 2
        // results[g] = 第 g 盘的得分(1.0 胜 / 0.5 和 / 0.0 负), 下标固定
        val results = DoubleArray(total) { -1.0 }
        val t0 = System.currentTimeMillis()

        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        for (t in 0 until threads) {
            pool.submit {
                var pika = startPikafish(exe)
                val engine = SimpleEngine()
                engine.start()
                engine.setNnue(ourNet?.let { NnueEvaluator(it) })
                // 用来验证剪枝改动: 固定深度对战只惩罚它的精度代价、不奖励
                // 它的速度收益, 所以这里 Elo 不掉就等于真实场景净赚。
                if (System.getProperty("pika.nullmove") == "0") engine.useNullMove = false
                // 限定节点数而不是深度: 直接衡量"每节点的棋力", 剪枝省下的
                // 节点会换成更深的搜索被真实奖励, 而结果仍然确定性可复现。
                System.getProperty("pika.nodes")?.let { engine.nodeLimit = it.toLong() }
                try {
                    var g = t
                    while (g < total) {
                        // 上一盘可能把它打死了; 死了就换个新的, 否则后面全废
                        if (!pika.isAlive()) {
                            note("Pikafish 已退出, 重启")
                            pika.close(); pika = startPikafish(exe)
                        }
                        // 第 g 盘: 开局 g/2, 偶数盘我们执红, 奇数盘执黑
                        val opening = openings[g / 2]
                        val ourColor = if (g % 2 == 0) Color.RED else Color.BLACK
                        results[g] = playGame(engine, pika, opening, ourColor, depth)
                        g += threads
                    }
                } finally {
                    engine.shutdown()
                    pika.close()
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.DAYS)

        // 导出每个开局对的得分, 供配对分析.
        // 两个网络下的是同一批开局且全程确定性, 所以可以逐对相减 —— 开局
        // 难易这个最大的方差源被完全消掉, 比拿两个独立区间比重叠强得多。
        System.getProperty("pika.dump")?.let { path ->
            File(path).printWriter().use { pw ->
                for (i in 0 until openings.size) {
                    pw.println(i.toString() + "\t" + results[i * 2] + "\t" + results[i * 2 + 1])
                }
            }
            println("[pika-match] 每对得分已写入 " + path)
        }
        report(results, openings.size, System.currentTimeMillis() - t0)
    }

    /** 每个开局随机走 [plies] 步; 走不下去的丢掉, 保证每个开局都还能继续对局 */
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

    /** 终局原因计数, 用来确认胜负是真下出来的而不是判定 bug */
    private val reasons = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun note(r: String) {
        reasons.computeIfAbsent(r) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }

    /** @return 我方得分: 1.0 胜 / 0.5 和 / 0.0 负 */
    private fun playGame(
        engine: SimpleEngine, pika: PikaProc, opening: Position, ourColor: Int, depth: Int
    ): Double {
        val pos = opening.copy()
        val legal = mutableListOf<Move>()
        for (ply in 0 until 200) {
            legal.clear()
            Rules.generateAllLegal(pos, legal)
            if (legal.isEmpty()) {
                // 无着可走: 被将死则输, 困毙判和
                return if (Rules.isInCheck(pos, pos.sideToMove)) {
                    if (pos.sideToMove == ourColor) { note("我方被将死"); 0.0 }
                    else { note("对方被将死"); 1.0 }
                } else { note("困毙判和"); 0.5 }
            }
            // 必须判和. 不判的话棋局一路走到 200 步, 半回合计数爬过 120,
            // 而 Pikafish 强制 60 回合规则, 超限会报 CRITICAL ERROR 并**退出**。
            // 每线程只启动一个 Pikafish, 一旦被打死, 该线程后续每一盘都在第 0
            // 步"获胜" —— 一个坏局面污染整条线程, 这是之前 47/80 判定错误的来源。
            if (pos.isFiftyMoveDraw()) { note("60 回合判和"); return 0.5 }
            if (pos.isThreefoldRepetition()) { note("三次重复判和"); return 0.5 }

            val move: Move
            if (pos.sideToMove == ourColor) {
                engine.setPositionDirect(pos)
                // 有节点预算时把名义深度放开, 让预算成为真正的约束
                move = engine.searchWithScore(if (engine.nodeLimit > 0) 32 else depth).first
                if (move.data == 0) { note("我方无着法(bug)"); return 0.0 }
                if (legal.none { it.data == move.data }) { note("我方非法着法(bug)"); return 0.0 }
            } else {
                val fen = pos.toFen()
                pika.send("position fen " + fen)
                pika.send("go depth $depth")
                val resp = pika.readUntil { it.startsWith("bestmove") }
                val parts = resp.split(" ")
                val parsed = if (parts.size >= 2) UciCoords.parse(parts[1]) else null
                // 对手故障**不能**算我方胜: 那样是把工具的毛病记成棋力, 而且
                // 偏置有方向性 —— 越倾向和棋的网络越容易把对手拖进 Rule60。
                // 返回 NaN 表示该局作废, 统计时整对剔除。
                if (parsed == null) {
                    note("!! Pikafish 无回应(作废) ply=" + ply + " [" + pika.drain() + "]")
                    println("[pika-debug] ply=" + ply + " 无回应, FEN: " + fen)
                    return Double.NaN
                }
                if (legal.none { it.data == parsed.data }) {
                    note("!! Pikafish 着法被判非法(作废):" + parts[1])
                    println("[pika-debug] 非法着法 " + parts[1] + " FEN: " + fen)
                    return Double.NaN
                }
                move = parsed
            }
            pos.doMove(move)
        }
        note("200 步和"); return 0.5
    }

    /**
     * 误差按**开局对**算, 不按盘算.
     *
     * 同一开局的两盘结果高度相关(局面本身的好坏是共同因素), 当成两个独立样本
     * 会把标准误算小 √2 倍。这里直接对每对的合计得分(0..2)求样本标准差 ——
     * 不需要假设分布, 配对本身已经把开局运气这个最大的方差源消掉了。
     */
    private fun report(results: DoubleArray, nPairs: Int, ms: Long) {
        var w = 0; var l = 0; var d = 0
        for (r in results) when {
            r.isNaN() -> {}
            r >= 0.99 -> w++
            r <= 0.01 -> l++
            else -> d++
        }
        // 任意一盘作废, 整个开局对剔除 —— 配对比较必须两盘都在
        val valid = (0 until nPairs).filter {
            !results[it * 2].isNaN() && !results[it * 2 + 1].isNaN()
        }
        if (valid.size < nPairs) {
            println("[pika-match] !! " + (nPairs - valid.size) + "/" + nPairs +
                " 个开局对因对手故障作废, 已剔除")
        }
        if (valid.isEmpty()) { println("[pika-match] !! 无有效对局, 结果无意义"); return }
        val pairScores = DoubleArray(valid.size) { results[valid[it] * 2] + results[valid[it] * 2 + 1] }
        val mean = pairScores.average()                 // 0..2
        val score = mean / 2.0                          // 0..1
        val n = pairScores.size
        val sd = if (n > 1) sqrt(pairScores.sumOf { (it - mean) * (it - mean) } / (n - 1)) else 0.0
        val se = sd / sqrt(n.toDouble()) / 2.0     // 得分率的标准误

        fun elo(s: Double): Double =
            if (s <= 0.0005) -1200.0 else if (s >= 0.9995) 1200.0
            else -400.0 * log10(1.0 / s - 1.0)

        val lo = elo((score - 1.96 * se).coerceIn(0.0, 1.0))
        val hi = elo((score + 1.96 * se).coerceIn(0.0, 1.0))
        val e = elo(score)
        println("[pika-match] 战绩 ${w}W ${l}L ${d}D  得分率 " + "%.1f".format(score * 100) + "%")
        println("[pika-match] Elo " + "%.0f".format(e) +
            "  95%% 区间 [" + "%.0f".format(lo) + ", " + "%.0f".format(hi) + "]" +
            "  (按 $n 个有效开局对算, 半宽 " + "%.0f".format((hi - lo) / 2) + ")")
        println("[pika-match] 耗时 " + "%.0f".format(ms / 1000.0) + "s")
        println("[pika-match] 终局原因:")
        reasons.entries.sortedByDescending { it.value.get() }.take(8).forEach {
            println("[pika-match]    " + it.key + "  x" + it.value.get())
        }
        println("[pika-match] 复现性: 固定开局 + 固定深度 + 按下标分线程, " +
            "同参数重跑结果应当完全一致")
    }

    // ---- Pikafish UCI 子进程 ----

    private data class PikaProc(
        val proc: Process,
        val writer: PrintWriter,
        val reader: BufferedReader,
    ) {
        fun isAlive(): Boolean = proc.isAlive
        fun send(cmd: String) { writer.println(cmd); writer.flush() }
        fun readUntil(pred: (String) -> Boolean): String {
            while (true) {
                val line = reader.readLine() ?: return ""
                if (pred(line)) return line
            }
        }
        /** 进程死掉时把还能读到的输出捞出来, 否则只看到一个空字符串 */
        fun drain(): String {
            val sb = StringBuilder()
            try {
                while (reader.ready()) { sb.append(reader.readLine() ?: break).append(" | ") }
            } catch (_: Exception) {}
            try { if (!proc.isAlive) sb.append("<进程已退出, exit=" + proc.exitValue() + ">") }
            catch (_: Exception) {}
            return sb.toString().take(300)
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
        // 单线程: 多线程搜索的结果依赖线程调度, 那会让整场比赛不可复现
        writer.println("setoption name Threads value 1"); writer.flush()
        writer.println("isready"); writer.flush()
        while (true) {
            if (reader.readLine() == "readyok") break
        }
        return PikaProc(proc, writer, reader)
    }
}
