package com.zhongguoxiangqi.ui.game

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * AI 分析的全屏动效: 一块带棋盘的"扫描屏", 多束光带反复扫过,
 * 同步显示引擎真实的搜索深度、红方胜率和候选着法列表 —— 看起来像真的在分析这盘棋。
 *
 * 4 个动效层叠:
 *  1. 棋盘网格 (静态)  —— 给屏幕定形
 *  2. 主扫描光带       —— 从上往下, 速度 2.4s 一轮
 *  3. 副扫描光带       —— 从下往上, 速度 1.7s 一轮, 半透明, 让"扫描"不停
 *  4. 评估折线         —— 跟着引擎返回的胜率走, 看起来像实时评估
 */
class AnalysisScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val scanPaint = Paint()
    private val scanPaint2 = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFD54F.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val lineFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFD54F.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 42f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 28f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val movePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEEFFFFFF.toInt()
        textSize = 26f
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        addUpdateListener { invalidate() }
    }
    /** 副扫描带: 速度更快 + 方向相反, 让"分析在进行"的感觉更连续 */
    private val scanAnimator2 = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1700L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        addUpdateListener { invalidate() }
    }

    @Volatile private var depth = 0
    @Volatile private var redWinRate = 50.0
    /** 评估曲线历史 (按时间), 用于画底部的实时评估走势 */
    private val evalHistory = ArrayDeque<Double>()

    fun updateInfo(depth: Int, redWinRate: Double) {
        this.depth = depth
        this.redWinRate = redWinRate
        evalHistory.addLast(redWinRate)
        // 限长: 多了就丢老的
        while (evalHistory.size > MAX_HISTORY) evalHistory.removeFirst()
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scanAnimator.start()
        scanAnimator2.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scanAnimator.cancel()
        scanAnimator2.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 16f

        // 棋盘网格: 9 列 x 10 行
        val left = pad
        val right = w - pad
        val top = pad
        val bottom = h - 200f
        val colStep = (right - left) / 8f
        val rowStep = (bottom - top) / 9f
        for (c in 0..8) {
            canvas.drawLine(left + c * colStep, top, left + c * colStep, bottom, gridPaint)
        }
        for (r in 0..9) {
            canvas.drawLine(left, top + r * rowStep, right, top + r * rowStep, gridPaint)
        }
        // 楚河汉界
        val riverY = top + 4.5f * rowStep
        subPaint.color = 0x99FFFFFF.toInt()
        subPaint.textSize = rowStep * 0.55f
        canvas.drawText("楚 河   汉 界", (left + right) / 2f, riverY + subPaint.textSize / 3, subPaint)

        // 主扫描光带: 从上往下
        val progress = scanAnimator.animatedValue as Float
        val bandY = top + progress * (bottom - top)
        val bandH = rowStep * 1.6f
        scanPaint.shader = LinearGradient(
            0f, bandY - bandH, 0f, bandY + bandH,
            intArrayOf(0x00FFFFFF.toInt(), 0x99FFFFFF.toInt(), 0x00FFFFFF.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(left, bandY - bandH, right, bandY + bandH, scanPaint)

        // 副扫描光带: 从下往上, 透明度低, 给"扫描不停"的感觉
        val progress2 = scanAnimator2.animatedValue as Float
        val bandY2 = bottom - progress2 * (bottom - top)
        val bandH2 = rowStep * 0.9f
        scanPaint2.shader = LinearGradient(
            0f, bandY2 - bandH2, 0f, bandY2 + bandH2,
            intArrayOf(0x00A0D8FF.toInt(), 0x55A0D8FF.toInt(), 0x00A0D8FF.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(left, bandY2 - bandH2, right, bandY2 + bandH2, scanPaint2)

        // 状态文字: AI 正在分析
        textPaint.textSize = 50f
        canvas.drawText("AI 正在分析棋局", w / 2f, bottom + 60f, textPaint)
        subPaint.textSize = 32f
        subPaint.color = 0xDDFFFFFF.toInt()
        canvas.drawText(
            "搜索深度 $depth  ·  红方胜率 ${redWinRate.roundToInt()}%",
            w / 2f, bottom + 110f, subPaint
        )

        // 评估曲线: 在文字下面那条窄带画一个折线, 看起来"评估在变"
        val evalTop = bottom + 130f
        val evalBottom = bottom + 175f
        val evalLeft = left + 30f
        val evalRight = right - 30f
        if (evalHistory.size >= 2) {
            val n = evalHistory.size
            val stepX = if (n > 1) (evalRight - evalLeft) / (n - 1) else 0f
            // 折线
            var prevX = evalLeft
            var prevY = evalBottom - ((evalHistory.first().coerceIn(0.0, 100.0) / 100.0) *
                (evalBottom - evalTop)).toFloat()
            for (i in 1 until n) {
                val v = evalHistory.elementAt(i).coerceIn(0.0, 100.0) / 100.0
                val x = evalLeft + i * stepX
                val y = evalBottom - (v * (evalBottom - evalTop)).toFloat()
                canvas.drawLine(prevX, prevY, x, y, linePaint)
                prevX = x
                prevY = y
            }
            // 当前点用一个小圆点强调
            canvas.drawCircle(prevX, prevY, 7f, linePaint)
        } else {
            // 数据还不够, 画一个静态的中线
            val midY = (evalTop + evalBottom) / 2f
            canvas.drawLine(evalLeft, midY, evalRight, midY, gridPaint)
        }
    }

    private companion object {
        const val MAX_HISTORY = 60
    }
}
