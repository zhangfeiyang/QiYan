package com.zhongguoxiangqi.ui.replay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.zhongguoxiangqi.R
import kotlin.math.roundToInt

/**
 * 局势胜率折线走势图控件 (Advantage Graph).
 *
 * 借鉴 Chess.com / Lichess 的复盘图谱：
 * 1. 50% 均势基准中线 (虚线)
 * 2. 贝塞尔圆角平滑曲线 (红优向上，黑优向下)
 * 3. 上部红方优势渐变填充，下部黑方优势渐变填充
 * 4. 关键转折点标记 (💎妙着、🔴漏着、🟠失误)
 * 5. 当前手竖线光标与高亮指示圆点
 * 6. 支持手指触摸、横向滑动实时寻步与拖动复盘
 */
class AdvantageGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var winRates: FloatArray? = null
    private var evaluations: List<MoveEvaluation>? = null
    private var currentStep: Int = 0

    var onStepSelectedListener: ((Int) -> Unit)? = null

    // 画笔
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55888888
        strokeWidth = dpToPx(1f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(4f), dpToPx(3f)), 0f)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC62828.toInt()
        strokeWidth = dpToPx(2.2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(dpToPx(4f))
    }

    private val redFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val blackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val cursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD4AF37.toInt() // 雅金
        strokeWidth = dpToPx(1.8f)
        style = Paint.Style.STROKE
    }

    private val cursorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD4AF37.toInt()
        style = Paint.Style.FILL
    }

    private val cursorDotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dpToPx(1.2f)
        style = Paint.Style.STROKE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88888888.toInt()
        textSize = dpToPx(9.5f)
        isFakeBoldText = true
    }

    // 路径与缓存
    private val curvePath = Path()
    private val baselinePath = Path()
    private val redFillPath = Path()
    private val blackFillPath = Path()

    fun setData(winRates: FloatArray, evaluations: List<MoveEvaluation>, initialStep: Int = 0) {
        this.winRates = winRates
        this.evaluations = evaluations
        this.currentStep = initialStep.coerceIn(0, winRates.size - 1)
        invalidate()
    }

    fun setCurrentStep(step: Int) {
        val maxStep = (winRates?.size ?: 1) - 1
        val coerced = step.coerceIn(0, maxStep)
        if (currentStep != coerced) {
            currentStep = coerced
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padTop = paddingTop.toFloat() + dpToPx(6f)
        val padBottom = h - paddingBottom.toFloat() - dpToPx(6f)
        val midY = (padTop + padBottom) / 2f

        redFillPaint.shader = LinearGradient(
            0f, padTop, 0f, midY,
            0x45D32F2F.toInt(), 0x05D32F2F,
            Shader.TileMode.CLAMP
        )
        blackFillPaint.shader = LinearGradient(
            0f, midY, 0f, padBottom,
            0x051976D2, 0x401976D2.toInt(),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = winRates ?: return
        if (data.size <= 1) return

        val padLeft = paddingLeft.toFloat() + dpToPx(8f)
        val padRight = width - paddingRight.toFloat() - dpToPx(8f)
        val padTop = paddingTop.toFloat() + dpToPx(6f)
        val padBottom = height - paddingBottom.toFloat() - dpToPx(6f)

        val drawW = padRight - padLeft
        val drawH = padBottom - padTop
        if (drawW <= 0 || drawH <= 0) return

        val totalSteps = data.size - 1
        val baselineY = padTop + drawH * 0.5f

        // 1. 绘制 50% 均势基准线
        baselinePath.reset()
        baselinePath.moveTo(padLeft, baselineY)
        baselinePath.lineTo(padRight, baselineY)
        canvas.drawPath(baselinePath, baselinePaint)

        // 标注 "红优" / "黑优"
        canvas.drawText(context.getString(R.string.replay_red_better), padLeft, padTop + dpToPx(8f), labelPaint)
        canvas.drawText(context.getString(R.string.replay_black_better), padLeft, padBottom - dpToPx(2f), labelPaint)

        // 2. 构建曲线路径
        curvePath.reset()
        val stepX = FloatArray(data.size)
        val stepY = FloatArray(data.size)

        for (i in data.indices) {
            val x = padLeft + (i.toFloat() / totalSteps) * drawW
            val wr = data[i].coerceIn(0f, 100f)
            // 胜率 100% 在顶端 (padTop)，0% 在底端 (padBottom)
            val y = padTop + (1f - (wr / 100f)) * drawH
            stepX[i] = x
            stepY[i] = y

            if (i == 0) {
                curvePath.moveTo(x, y)
            } else {
                curvePath.lineTo(x, y)
            }
        }

        // 3. 填充上方(红优)与下方(黑优)渐变
        // 红优填充: 曲线封闭到底部基准线，并用画布剪裁限制在 baselineY 以上
        redFillPath.reset()
        redFillPath.set(curvePath)
        redFillPath.lineTo(padRight, baselineY)
        redFillPath.lineTo(padLeft, baselineY)
        redFillPath.close()

        canvas.save()
        canvas.clipRect(padLeft, padTop, padRight, baselineY)
        canvas.drawPath(redFillPath, redFillPaint)
        canvas.restore()

        // 黑优填充: 曲线封闭到底部基准线，并用画布剪裁限制在 baselineY 以下
        blackFillPath.reset()
        blackFillPath.set(curvePath)
        blackFillPath.lineTo(padRight, baselineY)
        blackFillPath.lineTo(padLeft, baselineY)
        blackFillPath.close()

        canvas.save()
        canvas.clipRect(padLeft, baselineY, padRight, padBottom)
        canvas.drawPath(blackFillPath, blackFillPaint)
        canvas.restore()

        // 4. 绘制折线
        canvas.drawPath(curvePath, curvePaint)

        // 5. 绘制关键步标记点 (妙着 💎、漏着 🔴、失误 🟠)
        val evals = evaluations
        if (evals != null) {
            for (ev in evals) {
                val idx = ev.stepIndex
                if (idx in 1 until data.size) {
                    val px = stepX[idx]
                    val py = stepY[idx]

                    when (ev.quality) {
                        MoveQuality.BRILLIANT -> {
                            // 妙着: 青蓝色外环 + 白色实心
                            markerPaint.color = 0xFF00B0FF.toInt()
                            canvas.drawCircle(px, py, dpToPx(4.5f), markerPaint)
                            canvas.drawCircle(px, py, dpToPx(4.5f), markerStrokePaint)
                        }
                        MoveQuality.BLUNDER -> {
                            // 漏着: 大红圆点 + 白边
                            markerPaint.color = 0xFFE53935.toInt()
                            canvas.drawCircle(px, py, dpToPx(4.2f), markerPaint)
                            canvas.drawCircle(px, py, dpToPx(4.2f), markerStrokePaint)
                        }
                        MoveQuality.MISTAKE -> {
                            // 失误: 橙色圆点
                            markerPaint.color = 0xFFFF9800.toInt()
                            canvas.drawCircle(px, py, dpToPx(3.2f), markerPaint)
                        }
                        else -> { /* 其他常规步不绘点，避免画面杂乱 */ }
                    }
                }
            }
        }

        // 6. 绘制当前步光标与高亮指示圆点
        val cursorIndex = currentStep.coerceIn(0, totalSteps)
        val cx = stepX[cursorIndex]
        val cy = stepY[cursorIndex]

        // 垂直光标虚线/细线
        canvas.drawLine(cx, padTop, cx, padBottom, cursorLinePaint)

        // 指示圆点
        canvas.drawCircle(cx, cy, dpToPx(5.5f), cursorDotPaint)
        canvas.drawCircle(cx, cy, dpToPx(2.5f), cursorDotInnerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val data = winRates ?: return super.onTouchEvent(event)
        if (data.size <= 1) return super.onTouchEvent(event)

        val padLeft = paddingLeft.toFloat() + dpToPx(8f)
        val padRight = width - paddingRight.toFloat() - dpToPx(8f)
        val drawW = padRight - padLeft
        if (drawW <= 0) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val fraction = ((event.x - padLeft) / drawW).coerceIn(0f, 1f)
                val totalSteps = data.size - 1
                val targetStep = (fraction * totalSteps).roundToInt().coerceIn(0, totalSteps)
                if (targetStep != currentStep) {
                    currentStep = targetStep
                    invalidate()
                    onStepSelectedListener?.invoke(targetStep)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
