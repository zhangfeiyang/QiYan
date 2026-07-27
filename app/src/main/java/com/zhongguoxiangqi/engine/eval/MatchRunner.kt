package com.zhongguoxiangqi.engine.eval

import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 引擎对引擎评测.
 *
 * 自举最容易出的事故是某一代训崩了却没发现, 还拿它去生成下一代数据, 一路
 * 错下去. loss 下降**不代表**棋力提升 —— 网络可能只是更会拟合噪声. 唯一
 * 可靠的判据是实战胜率.
 *
 * 三个让结果可信的设计:
 *
 * 1. **每个开局下两局, 红黑对调**. 象棋先手优势明显(约 +50~80 分), 不配对
 *    就是在测"谁运气好抽到更多先手".
 * 2. **随机开局并去重**. 都从标准开局出发, 两个确定性引擎会一路走出同一盘
 *    棋, 打一千局等于打一局.
 * 3. **给出置信区间**. 100 局赢 55 局说明不了任何问题 —— 那完全在随机波动
 *    范围内. 不带误差的胜率是误导.
 */
object MatchRunner {

    /** 一方引擎的构造方式. 用工厂而不是实例: 每局都要全新引擎, 免得置换表跨局污染 */
    fun interface EngineFactory {
        fun create(): Engine
    }

    /** 评测只需要"给局面返回一步棋"这一个能力 */
    fun interface Engine {
        fun bestMove(pos: Position): Move
    }

    data class Result(
        val wins: Int,      // A 胜
        val losses: Int,    // A 负
        val draws: Int,
    ) {
        val games get() = wins + losses + draws
        val score get() = wins + draws * 0.5

        /**
         * A 相对 B 的 Elo 差. 全胜/全负时返回 null —— 那时 Elo 公式发散,
         * 只能说"至少高出很多", 硬给个数字是假精确.
         */
        val elo: Double?
            get() {
                if (games == 0) return null
                val p = score / games
                if (p <= 0.0 || p >= 1.0) return null
                return -400.0 * ln(1.0 / p - 1.0) / ln(10.0)
            }

        /**
         * Elo 的 95% 置信半宽.
         *
         * 这是这个工具最有价值的输出: 没有它, "赢了 55%" 到底是真的强还是
         * 只是运气, 完全没法判断.
         */
        val eloMargin: Double?
            get() {
                if (games < 2) return null
                val p = score / games
                if (p <= 0.0 || p >= 1.0) return null
                // 把和棋按 0.5 计入的方差近似
                val variance = (wins * (1 - p) * (1 - p) +
                    draws * (0.5 - p) * (0.5 - p) +
                    losses * p * p) / games
                val se = sqrt(variance / games)
                // 胜率 -> Elo 在 p 处的导数, 用来把胜率的误差换算成 Elo 的误差
                val dEloDp = 400.0 / (ln(10.0) * p * (1 - p))
                return 1.96 * se * dEloDp
            }

        override fun toString(): String {
            val e = elo
            val m = eloMargin
            val eloText = when {
                e == null && score == 0.0 -> "Elo 差: A 完败 (无法估算)"
                e == null -> "Elo 差: A 全胜 (无法估算)"
                m == null -> "Elo 差: ${"%+.0f".format(e)}"
                else -> "Elo 差: ${"%+.0f".format(e)} ± ${"%.0f".format(m)}"
            }
            val pct = if (games > 0) score / games * 100 else 0.0
            return "$games 局: A ${wins}胜 ${losses}负 ${draws}和  " +
                "得分率 ${"%.1f".format(pct)}%  $eloText"
        }
    }

    /**
     * 跑一场比赛.
     *
     * @param pairs 开局对数. 实际局数是它的两倍(每个开局红黑各下一次)
     * @param randomPlies 开局随机步数, 用来撒开局面
     * @param maxPlies 单局上限, 超过判和 —— 两个引擎互相耗死时必须有出口
     */
    fun play(
        a: EngineFactory,
        b: EngineFactory,
        pairs: Int,
        randomPlies: Int = 8,
        maxPlies: Int = 200,
        seed: Long = 12345L,
        onProgress: (Int, Result) -> Unit = { _, _ -> },
    ): Result {
        val rng = Random(seed)
        var wins = 0; var losses = 0; var draws = 0

        for (p in 0 until pairs) {
            // 同一个开局用同一个种子生成, 保证配对的两局起点完全一致
            val opening = randomOpening(rng.nextLong(), randomPlies, maxPlies)
            if (opening == null) continue

            // 第一局 A 执先手, 第二局对调
            for (swap in intArrayOf(0, 1)) {
                val outcome = playOne(
                    if (swap == 0) a.create() else b.create(),
                    if (swap == 0) b.create() else a.create(),
                    opening, maxPlies
                )
                // outcome: +1 先手方胜, -1 后手方胜, 0 和
                val aScore = if (swap == 0) outcome else -outcome
                when {
                    aScore > 0 -> wins++
                    aScore < 0 -> losses++
                    else -> draws++
                }
            }
            onProgress(p + 1, Result(wins, losses, draws))
        }
        return Result(wins, losses, draws)
    }

    /** 随机走若干步得到一个开局局面; 走不下去就返回 null */
    private fun randomOpening(seed: Long, plies: Int, maxPlies: Int): Position? {
        val rng = Random(seed)
        val pos = Position().also { it.setupStartPosition() }
        val legal = mutableListOf<Move>()
        repeat(plies) {
            legal.clear()
            Rules.generateAllLegal(pos, legal)
            if (legal.isEmpty()) return null
            pos.doMove(legal[rng.nextInt(legal.size)])
        }
        // 开局阶段就分出胜负的局面不能用来比棋力
        legal.clear()
        Rules.generateAllLegal(pos, legal)
        return if (legal.isEmpty()) null else pos
    }

    /** @return +1 先手胜, -1 后手胜, 0 和 */
    private fun playOne(first: Engine, second: Engine, opening: Position, maxPlies: Int): Int {
        val pos = opening.copy()
        val legal = mutableListOf<Move>()
        val seen = HashMap<Long, Int>()

        for (ply in 0 until maxPlies) {
            legal.clear()
            Rules.generateAllLegal(pos, legal)
            if (legal.isEmpty()) {
                // 无着可走: 被将死是负, 困毙在象棋里也判负
                val loserIsFirst = (ply % 2 == 0)
                return if (loserIsFirst) -1 else 1
            }
            // 同一局面出现三次判和, 否则两个引擎会在长将/长捉里无限循环
            val cnt = seen.merge(pos.hash, 1, Int::plus)!!
            if (cnt >= 3) return 0

            val engine = if (ply % 2 == 0) first else second
            val mv = engine.bestMove(pos)
            // 引擎给不出合法着法, 判它负 —— 这是 bug 而不是棋理问题, 必须暴露
            if (mv.data == 0 || legal.none { it.data == mv.data }) {
                return if (ply % 2 == 0) -1 else 1
            }
            pos.doMove(mv)
        }
        return 0   // 到步数上限判和
    }
}
