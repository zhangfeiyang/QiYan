package com.zhongguoxiangqi.ui.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.zhongguoxiangqi.R
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 盖在相机预览上的 AR 图层: 画出棋盘轮廓和推荐着法的箭头.
 *
 * 坐标链路是这个功能里最容易错的一环, 这里说清楚:
 *
 *   棋盘格 (col 0..8, row 0..9)
 *     └─ setPolyToPoly(单位棋盘 → 识别出的四角) ──► 分析图像坐标
 *          └─ 居中裁剪缩放 ────────────────────► View 坐标
 *
 * 第一段用透视变换是必须的: 手机是斜着对着棋盘拍的, 棋盘在画面里是个梯形,
 * 用线性插值算出来的格点会整体偏移, 箭头就指不准格子.
 */
class BoardOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private fun themeColor(@ColorRes id: Int) = ContextCompat.getColor(context, id)

    /** 识别出的四角, 分析图像坐标: [A0x,A0y, A8x,A8y, J0x,J0y, J8x,J8y] */
    private var corners: FloatArray? = null

    /** 分析图像的尺寸, 用来算居中裁剪 */
    private var srcW = 0
    private var srcH = 0

    /** 要画的着法, 棋盘坐标 (fromCol,fromRow,toCol,toRow); null = 不画 */
    private var move: IntArray? = null

    /** 箭头生长进度 0..1 */
    private var grow = 1f

    private val quadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = themeColor(R.color.action)
        alpha = 150
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = themeColor(R.color.hl_arrow)
    }
    private val arrowEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        // 深色描边: 棋盘木色深浅不一, 纯绿箭头压在浅木色上会糊
        color = 0xCC000000.toInt()
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor(R.color.hl_arrow)
    }
    /** 三角的深色底, 与杆子的描边同理: 压在木色上要有轮廓 */
    private val headEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC000000.toInt()
    }
    private val fromPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = themeColor(R.color.hl_arrow)
    }

    private val boardToSrc = Matrix()
    private val pt = FloatArray(2)
    private val path = Path()

    // ---- 预分配的复用缓冲 ----
    // onDraw 跟着相机每秒跑好几次, 里面每一次 floatArrayOf / listOf 都是
    // 一次分配. 全部提到成员上, 画一帧不再产生任何垃圾.
    private val unitBoard = floatArrayOf(0f, 0f, 8f, 0f, 0f, 9f, 8f, 9f)
    /** 棋盘四角的绘制顺序 (col,row) 两两一组 */
    private val quadOrder = floatArrayOf(0f, 0f, 8f, 0f, 8f, 9f, 0f, 9f)
    private val pFrom = FloatArray(2)
    private val pTo = FloatArray(2)
    private val pTmp = FloatArray(2)

    /** 清空: 棋盘丢了/局面无效时不要留着上一次的箭头误导人 */
    fun clear() {
        corners = null
        move = null
        invalidate()
    }

    fun update(corners: FloatArray?, srcW: Int, srcH: Int, move: IntArray?, grow: Float = 1f) {
        this.corners = corners
        this.srcW = srcW
        this.srcH = srcH
        this.move = move
        this.grow = grow
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = corners ?: return
        if (srcW <= 0 || srcH <= 0 || c.size < 8) return

        // 单位棋盘 -> 四角. 顺序必须和 RecognizedBoard.corners 一致.
        boardToSrc.setPolyToPoly(unitBoard, 0, c, 0, 4)

        // 棋盘四条边, 让用户知道"我认出来的是这一块"
        path.reset()
        for (i in 0 until 4) {
            boardPointInto(quadOrder[i * 2], quadOrder[i * 2 + 1], pTmp)
            if (i == 0) path.moveTo(pTmp[0], pTmp[1]) else path.lineTo(pTmp[0], pTmp[1])
        }
        path.close()
        canvas.drawPath(path, quadPaint)

        val mv = move ?: return
        boardPointInto(mv[0].toFloat(), mv[1].toFloat(), pFrom)
        boardPointInto(mv[2].toFloat(), mv[3].toFloat(), pTo)
        // 箭头按 grow 生长, 让"算出新着法"这件事有个可见的动作
        val toX = pFrom[0] + (pTo[0] - pFrom[0]) * grow
        val toY = pFrom[1] + (pTo[1] - pFrom[1]) * grow

        // 线宽跟着棋盘在画面里的大小走, 远近都合适
        boardPointInto(0f, 0f, pTmp)
        val originX = pTmp[0]
        val originY = pTmp[1]
        boardPointInto(1f, 0f, pTmp)
        val cell = hypot(pTmp[0] - originX, pTmp[1] - originY).coerceAtLeast(8f)

        fromPaint.strokeWidth = cell * 0.10f
        canvas.drawCircle(pFrom[0], pFrom[1], cell * 0.42f, fromPaint)

        val ang = atan2(toY - pFrom[1], toX - pFrom[0])
        val cosA = kotlin.math.cos(ang)
        val sinA = kotlin.math.sin(ang)

        // 箭头三角: 必须明显比杆子宽, 否则看起来就是根线中间鼓了一下.
        val shaftW = cell * 0.15f
        val headLen = cell * 0.62f
        val headW = cell * 0.30f

        // 杆子在三角底边处就收住, 不要一路画到尖端 —— 否则圆头会从三角
        // 两侧漏出来, 边缘变成毛的.
        val baseX = toX - cosA * headLen
        val baseY = toY - sinA * headLen

        arrowEdgePaint.strokeWidth = shaftW * 1.7f
        arrowPaint.strokeWidth = shaftW
        canvas.drawLine(pFrom[0], pFrom[1], baseX, baseY, arrowEdgePaint)

        buildHead(baseX, baseY, toX, toY, cosA, sinA, headW * 1.35f)
        canvas.drawPath(path, headEdgePaint)

        canvas.drawLine(pFrom[0], pFrom[1], baseX, baseY, arrowPaint)
        buildHead(baseX, baseY, toX, toY, cosA, sinA, headW)
        canvas.drawPath(path, headPaint)
    }

    /** 以 (baseX,baseY) 为底边中点、(tipX,tipY) 为尖端, 半宽 halfW 的三角 */
    private fun buildHead(
        baseX: Float, baseY: Float, tipX: Float, tipY: Float,
        cosA: Float, sinA: Float, halfW: Float,
    ) {
        // 底边方向是行进方向转 90°
        val nx = -sinA * halfW
        val ny = cosA * halfW
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(baseX + nx, baseY + ny)
        path.lineTo(baseX - nx, baseY - ny)
        path.close()
    }

    /** 棋盘格 -> View 坐标, 结果写进 [out], 不分配 */
    private fun boardPointInto(col: Float, row: Float, out: FloatArray) {
        pt[0] = col; pt[1] = row
        boardToSrc.mapPoints(pt)
        LiveGeometry.srcToViewInto(pt[0], pt[1], srcW, srcH, width, height, out)
    }

}
