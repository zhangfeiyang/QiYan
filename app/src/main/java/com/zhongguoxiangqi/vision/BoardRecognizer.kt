package com.zhongguoxiangqi.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * 从照片里识别中国象棋局面.
 *
 * 两阶段, 模型来自 https://github.com/TheOne1006/chinese-chess-recognition (MIT):
 *
 * 1. **棋盘四角关键点** (RTMPose, 256x256) —— 找出 A0/A8/J0/J8 四个角点.
 *    输出是 SimCC 形式: x 和 y 各 512 个 bin, 各自取 argmax 即为坐标.
 * 2. **棋子分类** (SwinV2-tiny) —— 先按四角做透视校正拉成正视图, 再一次性
 *    输出 90 个点位 x 16 类的概率.
 *
 * 先校正再分类是能识别"斜着拍"的关键: 分类模型只需要面对正视图.
 *
 * 输出的 10x9 排布与 App 内部坐标完全一致 (row 0 = 黑方底线), 不需要翻转.
 */
class BoardRecognizer private constructor(
    private val env: OrtEnvironment,
    private val poseSession: OrtSession,
    private val layoutSession: OrtSession
) : Closeable {

    /**
     * 识别一张图片.
     *
     * 耗时约 0.5~2 秒(取决于机型), **必须在后台线程调用**.
     */
    fun recognize(source: Bitmap): RecognizedBoard {
        val (corners, scores) = detectCorners(source)
        val warped = warpToBoard(source, corners)
        val (layout, confidence) = classify(warped)
        return RecognizedBoard(layout, confidence, corners, scores, warped)
    }

    /**
     * 用外部给定的四角重新识别 —— 用户手动拖动角点校正后走这条路,
     * 跳过关键点模型, 直接透视校正 + 分类.
     */
    fun recognizeWithCorners(source: Bitmap, corners: FloatArray): RecognizedBoard {
        require(corners.size == 8) { "corners 必须是 4 个点共 8 个浮点数" }
        val warped = warpToBoard(source, corners)
        val (layout, confidence) = classify(warped)
        return RecognizedBoard(layout, confidence, corners, FloatArray(4) { 1f }, warped)
    }

    // ====== 阶段 1: 四角关键点 ======

    /** @return corners = [A0x,A0y, A8x,A8y, J0x,J0y, J8x,J8y] (原图坐标), scores 4 个 */
    private fun detectCorners(source: Bitmap): Pair<FloatArray, FloatArray> {
        // bbox 取整张图. MMPose 的做法: 以图片中心为心取一个边长
        // max(w,h)*padding 的**正方形**区域, 缩放进 256x256.
        val cx = source.width / 2f
        val cy = source.height / 2f
        val side = maxOf(source.width, source.height) * POSE_PADDING
        val k = POSE_SIZE / side

        val input = Bitmap.createBitmap(POSE_SIZE, POSE_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(input).apply {
            // cv2.warpAffine 默认用 0 填充边界, 这里也要填黑, 否则模型看到的
            // 边缘和训练时不一致.
            drawColor(Color.BLACK)
            val m = Matrix().apply {
                setScale(k, k)
                postTranslate(POSE_SIZE / 2f - cx * k, POSE_SIZE / 2f - cy * k)
            }
            drawBitmap(source, m, filterPaint)
        }

        val out = runModel(poseSession, input, POSE_SIZE, POSE_SIZE, outputCount = 2)
        input.recycle()

        @Suppress("UNCHECKED_CAST")
        val simccX = out[0] as Array<Array<FloatArray>>  // (1, 4, 512)
        @Suppress("UNCHECKED_CAST")
        val simccY = out[1] as Array<Array<FloatArray>>

        val n = simccX[0].size
        val corners = FloatArray(n * 2)
        val scores = FloatArray(n)
        for (i in 0 until n) {
            val (xi, xv) = argmax(simccX[0][i])
            val (yi, yv) = argmax(simccY[0][i])
            // bin 索引 -> 归一化 0..1 (bin 数 = 输入边长 x 2) -> 256 空间 -> 原图
            val x256 = xi.toFloat() / SIMCC_BINS * POSE_SIZE
            val y256 = yi.toFloat() / SIMCC_BINS * POSE_SIZE
            corners[i * 2] = (x256 - POSE_SIZE / 2f) / k + cx
            corners[i * 2 + 1] = (y256 - POSE_SIZE / 2f) / k + cy
            scores[i] = xv * yv
        }
        return corners to scores
    }

    // ====== 阶段 2: 透视校正 + 分类 ======

    /**
     * 按四角把棋盘拉成正视图.
     *
     * 参考实现是"变换到 450x500(四周留白 50) 再中心裁剪成 400x450", 裁剪只是
     * 平移, 所以这里直接一步变换到 400x450, 角点落在留白 25 处, 结果等价.
     */
    private fun warpToBoard(source: Bitmap, corners: FloatArray): Bitmap {
        val dst = floatArrayOf(
            WARP_PAD, WARP_PAD,                          // A0 左上
            WARP_W - WARP_PAD, WARP_PAD,                 // A8 右上
            WARP_PAD, WARP_H - WARP_PAD,                 // J0 左下
            WARP_W - WARP_PAD, WARP_H - WARP_PAD         // J8 右下
        )
        val m = Matrix()
        // 4 个点对 => 完整透视变换
        m.setPolyToPoly(corners, 0, dst, 0, 4)

        val out = Bitmap.createBitmap(WARP_W.toInt(), WARP_H.toInt(), Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(source, m, filterPaint)
        }
        return out
    }

    /** @return layout 10x9 字符, confidence 10x9 概率 */
    private fun classify(warped: Bitmap): Pair<Array<CharArray>, Array<FloatArray>> {
        // 400x450 -> 280x315 (等比 0.7), 与参考实现的 resize 一致
        val input = Bitmap.createBitmap(CLS_W, CLS_H, Bitmap.Config.ARGB_8888)
        Canvas(input).drawBitmap(
            warped,
            android.graphics.Rect(0, 0, warped.width, warped.height),
            android.graphics.Rect(0, 0, CLS_W, CLS_H),
            filterPaint
        )

        val out = runModel(layoutSession, input, CLS_W, CLS_H, outputCount = 1)
        input.recycle()

        @Suppress("UNCHECKED_CAST")
        val probs = out[0] as Array<Array<FloatArray>>  // (1, 90, 16), 已经过 softmax

        val layout = Array(Rows) { CharArray(Cols) }
        val confidence = Array(Rows) { FloatArray(Cols) }
        for (i in 0 until Rows * Cols) {
            val (idx, p) = argmax(probs[0][i])
            layout[i / Cols][i % Cols] = CLASS_CHARS[idx]
            confidence[i / Cols][i % Cols] = p
        }
        return layout to confidence
    }

    // ====== 通用推理 ======

    /** 把 bitmap 归一化成 NCHW float 送进模型, 返回各输出的值 */
    private fun runModel(
        session: OrtSession,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        outputCount: Int
    ): List<Any> {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val buf = FloatBuffer.allocate(3 * width * height)
        val plane = width * height
        val arr = buf.array()
        for (i in 0 until plane) {
            val p = pixels[i]
            arr[i] = ((p shr 16 and 0xFF) - MEAN_R) / STD_R
            arr[plane + i] = ((p shr 8 and 0xFF) - MEAN_G) / STD_G
            arr[plane * 2 + i] = ((p and 0xFF) - MEAN_B) / STD_B
        }

        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        OnnxTensor.createTensor(env, buf, shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                return (0 until outputCount).map { result[it].value }
            }
        }
    }

    override fun close() {
        runCatching { poseSession.close() }
        runCatching { layoutSession.close() }
    }

    companion object {
        const val Rows = 10
        const val Cols = 9

        /** 分类的 16 个类别, 顺序必须与模型训练时一致 */
        const val CLASS_CHARS = ".xKABNRCPkabnrcp"

        /** 不在棋盘范围内 —— 透视校正没对准时会大量出现 */
        const val CHAR_OFF_BOARD = 'x'
        const val CHAR_EMPTY = '.'

        private const val POSE_SIZE = 256
        private const val POSE_PADDING = 1.25f
        private const val SIMCC_BINS = 512f

        private const val WARP_W = 400f
        private const val WARP_H = 450f
        private const val WARP_PAD = 25f

        private const val CLS_W = 280
        private const val CLS_H = 315

        // ImageNet 归一化, 两个模型共用
        private const val MEAN_R = 123.675f
        private const val MEAN_G = 116.28f
        private const val MEAN_B = 103.53f
        private const val STD_R = 58.395f
        private const val STD_G = 57.12f
        private const val STD_B = 57.375f

        private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        private fun argmax(v: FloatArray): Pair<Int, Float> {
            var bi = 0
            var bv = v[0]
            for (i in 1 until v.size) if (v[i] > bv) { bv = v[i]; bi = i }
            return bi to bv
        }

        /**
         * 加载模型. 失败返回 null(调用方据此隐藏识别入口), **在后台线程调用**.
         */
        fun create(context: Context): BoardRecognizer? = try {
            val pose = VisionModelInstaller.ensure(context, "board_pose.onnx")
            val layout = VisionModelInstaller.ensure(context, "board_layout.onnx")
            if (pose == null || layout == null) {
                null
            } else {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }
                BoardRecognizer(env, env.createSession(pose.absolutePath, opts),
                    env.createSession(layout.absolutePath, opts))
            }
        } catch (e: Throwable) {
            android.util.Log.e("BoardRecognizer", "模型加载失败", e)
            null
        }
    }
}

/** 一次识别的结果 */
data class RecognizedBoard(
    /** 10x9 字符棋盘, 取值见 [BoardRecognizer.CLASS_CHARS]; row 0 = 黑方底线 */
    val layout: Array<CharArray>,
    /** 与 layout 同形状的置信度(概率), 用来给 UI 标出可疑点位 */
    val confidence: Array<FloatArray>,
    /** 四角在原图中的坐标 [A0x,A0y, A8x,A8y, J0x,J0y, J8x,J8y] */
    val corners: FloatArray,
    /** 四角各自的关键点得分, 偏低说明棋盘没框准 */
    val cornerScores: FloatArray,
    /** 透视校正后的正视图, 供界面预览 */
    val warped: Bitmap
) {
    /**
     * 原照片里**红方是不是在上方**.
     *
     * 分类模型自己会把局面摆正 —— 把整张图旋转 180° 再识别, 出来的 10x9
     * 完全一样, 永远是 row 0 = 黑方底线. 这对数据是好事(局面永远合法),
     * 但照片本来的上下关系就丢了: 公园里对坐着下棋, 从红方那侧拍过去
     * 就是红在上, 硬摆成黑在上会让人对不上自己拍的那张图.
     *
     * 好在四角坐标是**原图**坐标, 上下关系还能还原出来:
     * A 行(row 0, 黑方底线)如果在图里比 J 行(row 9, 红方底线)更靠下,
     * 说明拍的时候红方在上.
     *
     * 这只影响显示 —— 局面数据始终保持 row 0 = 黑方底线的规范形式.
     */
    val redOnTop: Boolean
        get() {
            val blackRankY = (corners[1] + corners[3]) / 2f   // A0.y, A8.y
            val redRankY = (corners[5] + corners[7]) / 2f     // J0.y, J8.y
            return blackRankY > redRankY
        }

    /** 落在棋盘外的点位数量; >0 说明四角没对准 */
    val offBoardCount: Int
        get() = layout.sumOf { row -> row.count { it == BoardRecognizer.CHAR_OFF_BOARD } }

    /** 最低的单点置信度 */
    val minConfidence: Float
        get() = confidence.minOf { it.min() }

    /** 四角关键点的平均得分 */
    val meanCornerScore: Float
        get() = if (cornerScores.isEmpty()) 0f else cornerScores.average().toFloat()

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 把 assets 里的 .onnx 释放到 filesDir.
 *
 * 直接从 assets 读成 ByteArray 再建 session 会在建表时出现两份 31MB 拷贝,
 * 落到文件上让 ORT 自己 mmap 更省内存. 用 .sha256 边车文件判断新鲜度,
 * 与 NNUE 的安装逻辑保持一致.
 */
internal object VisionModelInstaller {

    fun ensure(context: Context, assetName: String): File? {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val dst = File(dir, assetName)
        val sidecar = File(dir, "$assetName.sha256")

        val want = runCatching {
            context.assets.open("models/$assetName.sha256")
                .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        val have = if (sidecar.exists()) sidecar.readText().trim().takeIf { it.isNotEmpty() } else null

        if (want != null && want == have && dst.exists() && dst.length() > 0L) return dst

        if (dst.exists()) dst.delete()
        return try {
            context.assets.open("models/$assetName").use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
            if (want != null) sidecar.writeText(want + "\n")
            dst
        } catch (e: Exception) {
            android.util.Log.e("BoardRecognizer", "释放模型 $assetName 失败", e)
            null
        }
    }
}
