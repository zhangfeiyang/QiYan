package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.UciCoords
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * 用外部 UCI 引擎当打标签的老师.
 *
 * ## 为什么需要它
 *
 * 蒸馏的上限就是老师。实测两个扩展方向都已经饱和(同 800 个开局的配对比较):
 *   数据量 2.1万 → 10万 (5倍):  -6.0 Elo, 95% 上界 +12.6
 *   容量   l1 256 → 512 (2倍):  -1.0 Elo, 95% 上界 +17.3, 且求值慢 1.64 倍
 * 两个维度同时到顶, 说明学生已经把"gen4 + 深度6搜索"能表达的东西学干净了,
 * 再多数据只是把同一个老师的分打得更准。唯一没饱和的维度是**老师本身**。
 *
 * ## 许可证
 *
 * **注意: 用 GPL 引擎(如 Pikafish)当老师, 训出的权重是其输出的衍生物。**
 * 本项目其余部分刻意与 GPL 隔离(见 [NnueNative] 与 SelfPlayGenerator 的注释),
 * 拿 GPL 引擎当**尺子**量强度不影响产物, 当**老师**则会进权重, 而且一旦训过
 * 就洗不掉, 只能整代作废。用哪种老师是发行策略问题, 由调用方显式选择。
 *
 * ## 分数约定
 *
 * UCI 的 `score cp` 是**走子方视角**, 与本项目记录格式一致, 不需要翻号。
 * `score mate N` 折算成一个大分并压到 [SCORE_CAP] —— 杀棋分若按原值进回归
 * 目标会把尺度带偏(见 train.py 里 SCORE_SCALE 那段)。
 */
class UciTeacher(
    private val exe: File,
    private val depth: Int,
    /** 传给引擎的 setoption, 如 Threads/Hash。老师是单局面查询, 单线程最省资源 */
    private val options: Map<String, String> = mapOf("Threads" to "1"),
) : AutoCloseable {

    private var proc: Process? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    /** 进程重启次数; 非零说明喂进去过它不接受的局面, 值得查 */
    var restarts = 0
        private set

    /** 因局面被引擎拒绝而放弃的次数 */
    var rejects = 0
        private set

    init { start() }

    private fun start() {
        val pb = ProcessBuilder(exe.absolutePath)
        // 工作目录设成引擎所在处: Pikafish 要在当前目录找它自己的 .nnue
        pb.directory(exe.parentFile)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val w = PrintWriter(p.outputStream, true)
        val r = BufferedReader(InputStreamReader(p.inputStream))
        w.println("uci")
        var ok = false
        while (true) {
            val line = r.readLine() ?: break
            if (line == "uciok") { ok = true; break }
        }
        check(ok) { "UCI 引擎未响应 uciok: ${exe.absolutePath}" }
        for ((k, v) in options) w.println("setoption name $k value $v")
        w.println("isready")
        while (true) { if (r.readLine() == "readyok") break }
        proc = p; writer = w; reader = r
    }

    private fun restart() {
        runCatching { close() }
        restarts++
        start()
    }

    /**
     * 查询一个局面, 返回 (最佳着法, 走子方视角的厘兵分); 引擎不接受该局面时返回 null.
     *
     * 调用方**必须**先排除半回合计数越界的局面: Pikafish 强制 60 回合规则,
     * 计数超 120 会报 CRITICAL ERROR 并**退出进程**。这里能自愈(重启), 但那
     * 意味着白扔掉一次查询, 上游判和才是正解。
     */
    fun evaluate(pos: Position): Pair<Move, Int>? {
        val w = writer ?: return null
        val r = reader ?: return null
        val p = proc ?: return null
        if (!p.isAlive) { restart(); return null }

        w.println("position fen " + pos.toFen())
        w.println("go depth $depth")

        var score: Int? = null
        while (true) {
            val line = r.readLine()
            if (line == null) {            // 进程死了 (多半是局面被拒)
                rejects++; restart(); return null
            }
            if (line.startsWith("info")) {
                parseScore(line)?.let { score = it }
                continue
            }
            if (line.startsWith("bestmove")) {
                val tok = line.split(" ").getOrNull(1) ?: return null
                val mv = UciCoords.parse(tok) ?: return null
                val sc = score ?: return null
                return mv to sc
            }
            // CRITICAL ERROR 之类: 引擎通常紧接着退出, 交给下一轮的 isAlive 处理
        }
    }

    /**
     * 从 info 行里取分数. 只认带 pv 的行 —— 不带 pv 的多是 currmove 进度汇报,
     * 那上面的分数是上一轮的残留。
     */
    private fun parseScore(line: String): Int? {
        val t = line.split(" ")
        val i = t.indexOf("score")
        if (i < 0 || i + 2 >= t.size) return null
        if (!t.contains("pv")) return null
        return when (t[i + 1]) {
            "cp" -> t[i + 2].toIntOrNull()
            // mate N: N 为正表示走子方 N 步内取胜。压到上限, 保留符号和"越快越好"
            "mate" -> t[i + 2].toIntOrNull()?.let { n ->
                val v = SCORE_CAP - kotlin.math.abs(n).coerceAtMost(MATE_PLY_CAP)
                if (n >= 0) v else -v
            }
            else -> null
        }
    }

    override fun close() {
        runCatching { writer?.println("quit") }
        runCatching { proc?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { proc?.destroyForcibly() }
        proc = null; writer = null; reader = null
    }

    companion object {
        /** 与 SelfPlayGenerator.SCORE_CLAMP 一致: 超出这个尺度会把回归目标带偏 */
        const val SCORE_CAP = 10_000
        const val MATE_PLY_CAP = 200
    }
}
