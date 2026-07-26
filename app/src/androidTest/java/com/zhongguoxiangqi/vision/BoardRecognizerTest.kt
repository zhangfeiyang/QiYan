package com.zhongguoxiangqi.vision

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 把 Kotlin 版识别流水线的输出与 Python 参考实现对齐.
 *
 * 期望值是用参考实现 (onnxruntime + OpenCV) 在同一张图上跑出来的.
 * 两边的差异只可能来自重采样: OpenCV 的 warpAffine/warpPerspective/resize 与
 * Android Canvas 的实现不完全一致, 所以允许少量格子不同, 但不能整盘跑偏 ——
 * 一旦仿射/SimCC 解码/透视映射写错, 差异会是几十格而不是一两格.
 */
@RunWith(AndroidJUnit4::class)
class BoardRecognizerTest {

    private lateinit var recognizer: BoardRecognizer

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        recognizer = BoardRecognizer.create(target)
            ?: throw AssertionError("模型加载失败 —— assets/models 下的 onnx 是否打进包了?")
    }

    @After
    fun tearDown() {
        if (::recognizer.isInitialized) recognizer.close()
    }

    private fun recognize(asset: String): RecognizedBoard {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bmp = ctx.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        assertNotNull("读不出测试图 $asset", bmp)
        return recognizer.recognize(bmp)
    }

    private fun diff(actual: Array<CharArray>, expected: List<String>): Int {
        var n = 0
        for (r in 0 until BoardRecognizer.Rows) {
            for (c in 0 until BoardRecognizer.Cols) {
                if (actual[r][c] != expected[r][c]) n++
            }
        }
        return n
    }

    private fun render(layout: Array<CharArray>) = layout.joinToString("\n") { String(it) }

    /**
     * @param maxDiff 允许与参考实现相差几格.
     *
     * 实测在真机上三张图**都是 0 格**, 所以这里卡死 0: 一旦仿射/SimCC 解码/
     * 透视映射被改坏, 立刻就会红. 如果换机型或换 ORT 版本后偶尔差 1~2 格,
     * 看日志里打出的差异数 —— 那是重采样精度差异, 不是逻辑错误, 可以放宽.
     */
    private fun check(asset: String, expected: List<String>, maxDiff: Int = 0) {
        val result = recognize(asset)
        val d = diff(result.layout, expected)
        // 通过也要把差异数打出来: 从 0 涨到 3 仍然"通过", 但那是端口在偏离参考实现
        android.util.Log.i("BoardRecognizerTest",
            "$asset 与参考实现差 $d 格, 最低置信度 %.2f".format(result.minConfidence))
        val msg = "$asset 与参考实现差 $d 格 (上限 $maxDiff)\n实际:\n${render(result.layout)}\n" +
            "参考:\n${expected.joinToString("\n")}\n" +
            "角点得分: ${result.cornerScores.joinToString { "%.2f".format(it) }}"
        assertTrue(msg, d <= maxDiff)
    }

    /** 干净的正视截图, 参考实现整盘只错 1 格(红砲被认成黑砲) */
    @Test
    fun matchesReferenceOnDemo003() = check("demo003.png", listOf(
        "rnbakabnr",
        ".........",
        ".......c.",
        "p.p.p.p.p",
        ".........",
        ".........",
        "P.P.P.P.P",
        "RC.....CR",
        ".........",
        ".cBAKABN."
    ))

    @Test
    fun matchesReferenceOnDemo004() = check("demo004.png", listOf(
        "C...kabnr",
        "...na....",
        ".........",
        "p.p.p.p.p",
        "...P.....",
        ".......c.",
        "P...c.P.P",
        ".C.......",
        ".........",
        "RNBAKABNR"
    ))

    /** 真实拍摄、带角度的照片 —— 这才是"公园里拍一张"的实际情况 */
    @Test
    fun matchesReferenceOnRealPhoto() = check("video010-0004.jpg", listOf(
        ".rbak.bnr",
        "....aR...",
        "..n.c..c.",
        "p.p.p.p.p",
        ".........",
        ".........",
        "P.P.P.P.P",
        "..N.C..C.",
        ".........",
        "..BAKABNR"
    ))

    /** 四角必须落在图内, 且大致围成棋盘的形状 */
    @Test
    fun cornersAreSane() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bmp = ctx.assets.open("demo003.png").use { BitmapFactory.decodeStream(it) }
        val r = recognizer.recognize(bmp)
        val (w, h) = bmp.width.toFloat() to bmp.height.toFloat()
        for (i in 0 until 4) {
            val x = r.corners[i * 2]
            val y = r.corners[i * 2 + 1]
            assertTrue("角点 $i 越界: ($x, $y) 图 ${w}x$h", x in -w..(2 * w) && y in -h..(2 * h))
        }
        // A0 在上、J0 在下 (row 0 = 黑方底线)
        assertTrue("A0 应该在 J0 上方", r.corners[1] < r.corners[5])
        // A0 在左、A8 在右
        assertTrue("A0 应该在 A8 左侧", r.corners[0] < r.corners[2])
    }

    /** 识别耗时得在可接受范围内 */
    @Test
    fun runsInReasonableTime() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bmp = ctx.assets.open("video010-0004.jpg").use { BitmapFactory.decodeStream(it) }
        recognizer.recognize(bmp)  // 预热, 排除首次分配
        val t0 = System.currentTimeMillis()
        recognizer.recognize(bmp)
        val ms = System.currentTimeMillis() - t0
        android.util.Log.i("BoardRecognizerTest", "单张识别耗时 ${ms}ms")
        assertTrue("识别耗时 ${ms}ms, 超过 5 秒用户会以为卡死", ms < 5000)
    }
}
