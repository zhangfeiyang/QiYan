package com.zhongguoxiangqi.game

import org.junit.Test
import kotlin.random.Random

/**
 * 金标准生成器(非断言测试): 造一批随机局面, 把每个局面里所有吃子着法的 SEE
 * 值打印出来, 供 C++ 移植版(perft_test.cpp 旁边的 see_test.cpp)读回去逐条
 * 比对。用法同 [PerftGoldenGenerator]。
 */
class SeeGoldenGenerator {

    private fun positions(n: Int, seed: Int): List<Position> {
        val rng = Random(seed)
        val out = mutableListOf<Position>()
        val legal = mutableListOf<Move>()
        while (out.size < n) {
            val p = Position().also { it.setupStartPosition() }
            var dead = false
            repeat(4 + rng.nextInt(70)) {
                legal.clear(); Rules.generateAllLegal(p, legal)
                if (legal.isEmpty()) { dead = true; return@repeat }
                p.doMove(legal[rng.nextInt(legal.size)])
            }
            if (dead) continue
            out.add(p.copy())
        }
        return out
    }

    @Test
    fun printGolden() {
        val poss = positions(300, 20260801)
        val caps = mutableListOf<Move>()
        var total = 0
        for (p in poss) {
            caps.clear()
            MoveGenerator.generateCaptures(p, caps)
            if (caps.isEmpty()) continue
            println("[SEE] fen=${p.toFen()}")
            for (m in caps) {
                val s = See.evaluate(p, m)
                println("[SEE] move ${m.from} ${m.to} see=$s")
                total++
            }
        }
        println("[SEE] total=$total")
    }
}
