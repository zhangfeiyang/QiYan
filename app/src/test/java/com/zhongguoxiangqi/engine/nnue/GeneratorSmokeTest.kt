package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 生成器冒烟测试.
 *
 * 特征编码错一位, 症状是"能跑但棋力莫名其妙地差", 排查起来极痛苦 ——
 * 必须在挂机跑几天之前就把格式钉死.
 */
class GeneratorSmokeTest {

    @Test
    fun `特征下标不越界`() {
        val pos = Position().also { it.setupStartPosition() }
        val buf = IntArray(NnueFeatures.MAX_PIECES * 2)
        val n = NnueFeatures.extract(pos, buf)
        assertEquals("开局应有 32 个子", 32, n)
        for (i in 0 until n) {
            val idx = buf[i * 2]
            val sq = buf[i * 2 + 1]
            assertTrue("own 越界", NnueFeatures.ownFeature(idx, sq) in 0 until NnueFeatures.FEATURES)
            assertTrue("opp 越界", NnueFeatures.oppFeature(idx, sq) in 0 until NnueFeatures.FEATURES)
        }
    }

    @Test
    fun `翻转两次回到原点`() {
        for (sq in 0 until NnueFeatures.SQUARES) {
            assertEquals(sq, NnueFeatures.flipSquare(NnueFeatures.flipSquare(sq)))
        }
        for (i in 0 until NnueFeatures.PIECE_KINDS) {
            assertEquals(i, NnueFeatures.flipPieceIndex(NnueFeatures.flipPieceIndex(i)))
        }
    }

    @Test
    fun `红帅与黑将在各自视角下映射到同一下标`() {
        // 这正是"共享 FeatureTransformer + 双视角"要的性质: 网络学的是
        // "我方/敌方"而不是"红方/黑方". 不成立就说明翻转逻辑写反了.
        val redKingSq = Square.make(9, 4)
        val blackKingSq = NnueFeatures.flipSquare(redKingSq)
        assertEquals(
            NnueFeatures.ownFeature(NnueFeatures.pieceIndex(Piece.R_KING.code), redKingSq),
            NnueFeatures.oppFeature(NnueFeatures.pieceIndex(Piece.B_KING.code), blackKingSq)
        )
    }

    @Test
    fun `生成一小批数据并逐字段校验`() {
        val out = File.createTempFile("nnue_smoke", ".bin").apply { delete() }
        val t0 = System.currentTimeMillis()
        val count = SelfPlayGenerator(searchDepth = 4, maxPlies = 40, seed = 42L)
            .generate(games = 2, out = out)
        val ms = System.currentTimeMillis() - t0
        println("[gen] 2 局 depth=4 -> $count 条样本, 耗时 ${ms}ms, " +
            "折合 ${if (ms > 0) count * 1000 / ms else 0} 条/秒/线程")

        assertTrue("应当产出样本", count > 0)
        assertEquals("文件大小必须是记录长度的整数倍",
            0, (out.length() % SelfPlayGenerator.RECORD_BYTES).toInt())
        assertEquals(count, out.length() / SelfPlayGenerator.RECORD_BYTES)

        val bb = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        for (r in 0 until count.toInt()) {
            bb.position(r * SelfPlayGenerator.RECORD_BYTES)
            val n = bb.get().toInt() and 0xFF
            val stm = bb.get().toInt()
            val score = bb.getShort().toInt()
            assertTrue("子数不合理: $n", n in 2..32)
            assertTrue("走子方标志越界: $stm", stm == 0 || stm == 1)
            assertTrue("分数超出截断: $score",
                score in -SelfPlayGenerator.SCORE_CLAMP..SelfPlayGenerator.SCORE_CLAMP)
            for (i in 0 until n) {
                val idx = bb.get().toInt() and 0xFF
                val sq = bb.get().toInt() and 0xFF
                assertTrue("棋子索引越界: $idx", idx in 0 until NnueFeatures.PIECE_KINDS)
                assertTrue("格子越界: $sq", sq in 0 until NnueFeatures.SQUARES)
            }
        }
        out.delete()
    }
}
