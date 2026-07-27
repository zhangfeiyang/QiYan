package com.zhongguoxiangqi.engine

import org.junit.Test

/** 量一下真实吞吐, 用来估算生成 N 万条训练数据要多久 */
class EngineSpeedTest {
    @Test
    fun `节点每秒`() {
        val fen = "r1ba1abnr/4k4/1cn3c2/p1p1p1p1p/9/2P6/P3P1P1P/1C2C1N2/9/RNBAKAB1R w - - 0 1"
        for (d in intArrayOf(6, 7)) {
            val e = SimpleEngine(); e.start(); e.setPosition(fen, emptyList())
            val t0 = System.currentTimeMillis()
            val n = e.benchmark(d)
            val ms = System.currentTimeMillis() - t0
            e.shutdown()
            println("[speed] depth=$d nodes=$n ms=$ms nps=${if (ms>0) n*1000/ms else 0}")
        }
    }
}
