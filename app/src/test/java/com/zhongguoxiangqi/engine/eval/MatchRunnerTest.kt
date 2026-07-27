package com.zhongguoxiangqi.engine.eval

import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 评测工具自身的正确性.
 *
 * 这个工具是用来判断"新一代网络是不是真的更强"的唯一依据. 它自己算错,
 * 后面每一代自举都会跟着错, 而且错得悄无声息 —— 所以统计部分必须先自证.
 */
class MatchRunnerTest {

    // ---------- Elo 换算 ----------

    @Test
    fun `五五开时 Elo 差为零`() {
        val r = MatchRunner.Result(wins = 50, losses = 50, draws = 0)
        assertEquals(0.0, r.elo!!, 0.01)
    }

    @Test
    fun `全和也是零分差`() {
        val r = MatchRunner.Result(wins = 0, losses = 0, draws = 100)
        assertEquals(0.0, r.elo!!, 0.01)
    }

    @Test
    fun `七成五胜率约合 191 分`() {
        // Elo 公式: -400*log10(1/p - 1), p=0.75 -> 190.8
        val r = MatchRunner.Result(wins = 75, losses = 25, draws = 0)
        assertEquals(190.8, r.elo!!, 1.0)
    }

    @Test
    fun `全胜全负无法估算, 不能假装给个数字`() {
        assertNull(MatchRunner.Result(10, 0, 0).elo)
        assertNull(MatchRunner.Result(0, 10, 0).elo)
    }

    @Test
    fun `和棋按半分计入`() {
        // 40胜 20负 40和 -> 得分 60/100 = 0.6
        val r = MatchRunner.Result(wins = 40, losses = 20, draws = 40)
        assertEquals(0.6, r.score / r.games, 1e-9)
        assertTrue("应当是正的分差", r.elo!! > 0)
    }

    // ---------- 置信区间 ----------

    @Test
    fun `局数越多误差越小`() {
        val few = MatchRunner.Result(wins = 55, losses = 45, draws = 0)
        val many = MatchRunner.Result(wins = 550, losses = 450, draws = 0)
        assertTrue("1000 局的误差必须明显小于 100 局",
            many.eloMargin!! < few.eloMargin!! / 2)
    }

    @Test
    fun `一百局赢五十五局说明不了问题`() {
        // 这正是这个工具存在的意义: 55% 看着像变强了, 其实完全在噪声里
        val r = MatchRunner.Result(wins = 55, losses = 45, draws = 0)
        assertTrue("误差应当大到覆盖 0, 即无法断言更强",
            r.eloMargin!! > kotlin.math.abs(r.elo!!))
    }

    @Test
    fun `局数太少不给误差`() {
        assertNull(MatchRunner.Result(1, 0, 0).eloMargin)
    }

    // ---------- 对局流程 ----------

    /** 只会随机走的引擎 */
    private fun randomEngine(seed: Long) = MatchRunner.EngineFactory {
        val rng = Random(seed)
        MatchRunner.Engine { pos ->
            val legal = mutableListOf<Move>()
            Rules.generateAllLegal(pos, legal)
            if (legal.isEmpty()) Move.empty() else legal[rng.nextInt(legal.size)]
        }
    }

    @Test
    fun `局数等于开局对数的两倍 —— 每个开局红黑各下一次`() {
        val r = MatchRunner.play(
            randomEngine(1), randomEngine(2),
            pairs = 5, randomPlies = 6, maxPlies = 30, seed = 7
        )
        assertEquals(10, r.games)
    }

    @Test
    fun `给不出合法着法的引擎判负`() {
        val broken = MatchRunner.EngineFactory {
            MatchRunner.Engine { Move.empty() }   // 永远不出招
        }
        val r = MatchRunner.play(
            broken, randomEngine(3),
            pairs = 3, randomPlies = 4, maxPlies = 20, seed = 11
        )
        assertEquals("坏引擎应当全负", 0, r.wins)
        assertEquals(6, r.losses)
    }

    @Test
    fun `同强度对打结果应当接近五五开`() {
        // 两个随机引擎, 除了种子没有差别. 用足够多的局数压住方差.
        val r = MatchRunner.play(
            randomEngine(100), randomEngine(200),
            pairs = 60, randomPlies = 6, maxPlies = 60, seed = 999
        )
        assertNotNull(r.elo)
        assertTrue("同强度不该出现巨大分差, 实测 ${r}",
            kotlin.math.abs(r.elo!!) < r.eloMargin!! * 2)
    }

    @Test
    fun `重复局面判和, 不会无限循环`() {
        // 两个总是走同一步的引擎必然重复局面; 必须能收场而不是挂死
        val stubborn = MatchRunner.EngineFactory {
            MatchRunner.Engine { pos ->
                val legal = mutableListOf<Move>()
                Rules.generateAllLegal(pos, legal)
                if (legal.isEmpty()) Move.empty() else legal[0]
            }
        }
        val r = MatchRunner.play(
            stubborn, stubborn, pairs = 2, randomPlies = 4, maxPlies = 200, seed = 5
        )
        assertEquals(4, r.games)
    }

    @Test
    fun `开局局面对配对的两局完全一致`() {
        // 不一致就等于没有配对, 先手优势消不掉
        val a = MatchRunner.play(
            randomEngine(1), randomEngine(1),
            pairs = 4, randomPlies = 8, maxPlies = 40, seed = 4242
        )
        val b = MatchRunner.play(
            randomEngine(1), randomEngine(1),
            pairs = 4, randomPlies = 8, maxPlies = 40, seed = 4242
        )
        assertEquals("同种子必须完全可复现", a.toString(), b.toString())
    }
}
