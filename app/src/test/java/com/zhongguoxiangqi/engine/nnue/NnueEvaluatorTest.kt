package com.zhongguoxiangqi.engine.nnue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端上量化推理与训练侧参考实现的一致性.
 *
 * 这是整个 NNUE 流水线唯一的跨语言交接面. 它错了不会崩溃, 只会让棋力
 * 莫名其妙地差 —— 网络照样输出数字, 引擎照样下棋. 所以这里比对的是
 * **完全相等**, 不是"接近": 整数推理是确定性的, 差一分就说明哪里对不上,
 * 没有"误差范围"可言.
 *
 * 素材由 nnue/make_golden.py 生成 (真实自对弈局面 + Python int_infer 的输出).
 */
class NnueEvaluatorTest {

    private fun res(path: String) =
        javaClass.classLoader!!.getResourceAsStream(path)
            ?: fail("缺少测试素材 $path, 用 nnue/make_golden.py 生成").let {
                error("unreachable")
            }

    private fun loadNet() = NnueNetwork.load(res("nnue/test.nnue"))

    // ---------- 与 Python 参考实现逐条比对 ----------

    @Test
    fun `每条黄金向量都要完全相等`() {
        val net = loadNet()
        val ev = NnueEvaluator(net)

        val lines = res("nnue/golden.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("黄金向量太少, 覆盖不了什么", lines.size >= 100)

        val scores = mutableSetOf<Int>()
        var checked = 0
        for ((lineNo, line) in lines.withIndex()) {
            val parts = line.split("|")
            assertEquals("第 ${lineNo + 1} 行格式不对: $line", 3, parts.size)

            val blackToMove = parts[0].trim() == "1"
            val pairs = parts[1].trim().split(" ").filter { it.isNotEmpty() }
            val expected = parts[2].trim().toInt()

            val pieces = IntArray(NnueFeatures.MAX_PIECES * 2)
            for ((i, p) in pairs.withIndex()) {
                val (idx, sq) = p.split(",")
                pieces[i * 2] = idx.toInt()
                pieces[i * 2 + 1] = sq.toInt()
            }

            val got = ev.evaluatePieces(pieces, pairs.size, blackToMove)
            assertEquals(
                "第 ${lineNo + 1} 条与 Python int_infer 不一致 " +
                    "(${pairs.size} 子, ${if (blackToMove) "黑" else "红"}走)",
                expected, got
            )
            scores.add(expected)
            checked++
        }

        // 素材本身必须有区分度: 如果所有期望值都一样, 上面的断言对着一个
        // 恒返回同一个数的实现也会全过, 等于什么都没测.
        assertTrue(
            "黄金向量区分度不足 (只有 ${scores.size} 种取值), " +
                "得用输出有跨度的网络重新生成",
            scores.size > checked / 2
        )
    }

    @Test
    fun `同一实例反复求值结果稳定`() {
        // 内部缓冲是复用的, 上一次的残留没清干净就会表现为"第二次算得不一样"
        val ev = NnueEvaluator(loadNet())
        val a = IntArray(64).also { it[0] = 0; it[1] = 4; it[2] = 7; it[3] = 85 }
        val b = IntArray(64).also {
            it[0] = 0; it[1] = 4; it[2] = 7; it[3] = 85
            it[4] = 4; it[5] = 0; it[6] = 11; it[7] = 80
        }
        val first = ev.evaluatePieces(a, 2, false)
        ev.evaluatePieces(b, 4, true)
        assertEquals("同样输入两次求值必须相同", first, ev.evaluatePieces(a, 2, false))
    }

    // ---------- 加载器的防呆 ----------

    @Test
    fun `magic 不对要拒绝加载`() {
        val bad = ByteArray(64)
        try {
            NnueNetwork.load(ByteArrayInputStream(bad))
            fail("应当拒绝")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("不是 NNUE"))
        }
    }

    @Test
    fun `结构对不上要拒绝加载, 而不是读出一堆噪声`() {
        // 这正是最危险的情形: 尺寸错位之后网络照样能算出数字, 只是全是垃圾
        val head = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        head.put("XQNN".toByteArray())
        head.putInt(NnueNetwork.FORMAT_VERSION)   // 版本要跟着格式走, 写死会先被版本检查拦下
        head.putInt(999)      // FEATURES 故意写错
        head.putInt(256)
        head.putInt(32)
        head.putInt(32)
        head.putInt(NnueNetwork.QA)
        head.putInt(NnueNetwork.QW)
        try {
            NnueNetwork.load(ByteArrayInputStream(head.array()))
            fail("应当拒绝")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("FEATURES"))
        }
    }

    @Test
    fun `层宽离谱要拒绝加载, 而不是照着分配内存`() {
        // 这个数字直接决定要分配多大的数组. 不拦的话, 一个损坏的文件能让
        // App 在加载网络时 OOM 崩掉.
        val head = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        head.put("XQNN".toByteArray())
        head.putInt(NnueNetwork.FORMAT_VERSION)   // 同上
        head.putInt(NnueFeatures.FEATURES)
        head.putInt(1 shl 20)   // L1 离谱
        head.putInt(32)
        head.putInt(32)
        head.putInt(NnueNetwork.QA)
        head.putInt(NnueNetwork.QW)
        try {
            NnueNetwork.load(ByteArrayInputStream(head.array()))
            fail("应当拒绝")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("L1"))
        }
    }

    @Test
    fun `文件被截断要拒绝加载`() {
        val full = res("nnue/test.nnue").readBytes()
        try {
            NnueNetwork.load(ByteArrayInputStream(full.copyOf(full.size / 2)))
            fail("应当拒绝")
        } catch (e: IOException) {
            // readFully 读不满会抛 EOFException, 也是 IOException
        }
    }

    @Test
    fun `尾部多出数据要拒绝加载`() {
        val full = res("nnue/test.nnue").readBytes()
        try {
            NnueNetwork.load(ByteArrayInputStream(full + ByteArray(8)))
            fail("应当拒绝")
        } catch (e: IOException) {
            assertTrue("实际信息: ${e.message}", e.message!!.contains("长度不符"))
        }
    }
}
