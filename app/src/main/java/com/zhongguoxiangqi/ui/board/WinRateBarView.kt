package com.zhongguoxiangqi.ui.board

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.zhongguoxiangqi.analysis.WinRateSnapshot

/**
 * 实时胜率条.
 *
 * 显示红/黑双方胜率的水平条形图.
 * 红色在左,黑色在右,中间分隔.
 * 颜色饱和度随优势程度增强.
 */
class WinRateBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var snapshot: WinRateSnapshot = WinRateSnapshot(redWinRate = 50.0)
    private var animationProgress = 1f
    private var previousSnapshot: WinRateSnapshot = snapshot

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAEBD7")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5D2F0E")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B2412")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B2412")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private var animator: ValueAnimator? = null

    fun update(snapshot: WinRateSnapshot, animate: Boolean = true) {
        if (snapshot.redWinRate == this.snapshot.redWinRate) {
            // 数值没变,只更新附带信息,不重启动画
            this.snapshot = snapshot
            return
        }
        // 从当前显示到的位置接着动,避免连续更新时来回跳
        previousSnapshot = this.snapshot.copy(redWinRate = displayedRedWinRate())
        this.snapshot = snapshot

        animator?.cancel()
        if (!animate) {
            animationProgress = 1f
            invalidate()
            return
        }
        animationProgress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 当前实际画在屏幕上的红方胜率 */
    private fun displayedRedWinRate(): Double =
        previousSnapshot.redWinRate +
            (snapshot.redWinRate - previousSnapshot.redWinRate) * animationProgress

    fun reset() {
        animator?.cancel()
        animator = null
        snapshot = WinRateSnapshot(redWinRate = 50.0)
        previousSnapshot = snapshot
        animationProgress = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()

        // 背景框
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRect(rect, bgPaint)
        canvas.drawRect(rect, borderPaint)

        // 计算红方宽度(动画插值)
        val redPercent = (previousSnapshot.redWinRate +
                (snapshot.redWinRate - previousSnapshot.redWinRate) * animationProgress).toFloat()
        val redRatio = (redPercent / 100f).coerceIn(0f, 1f)
        val redWidth = w * redRatio

        // 红方渐变
        if (redWidth > 0) {
            val redPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val intensity = (kotlin.math.abs(redPercent - 50f) / 50f).coerceIn(0f, 1f)
            val baseColor = blendColors(
                Color.parseColor("#F08080"),
                Color.parseColor("#C8102E"),
                intensity
            )
            redPaint.shader = LinearGradient(
                0f, 0f, redWidth, 0f,
                intArrayOf(baseColor, Color.parseColor("#C8102E")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, redWidth, h, redPaint)
        }

        // 黑方渐变
        if (redWidth < w) {
            val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val intensity = (kotlin.math.abs(100f - redPercent - 50f) / 50f).coerceIn(0f, 1f)
            val baseColor = blendColors(
                Color.parseColor("#606060"),
                Color.parseColor("#1A1A1A"),
                intensity
            )
            blackPaint.shader = LinearGradient(
                redWidth, 0f, w, 0f,
                intArrayOf(Color.parseColor("#1A1A1A"), baseColor),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(redWidth, 0f, w, h, blackPaint)
        }

        // 中线
        canvas.drawLine(redWidth, 0f, redWidth, h, dividerPaint)

        // 文本. 小数点后 1 位, 两边按 0.1 互补 (63.4 ↔ 36.6, 100.0 ↔ 0.0).
        // 颜色已经区分了红黑, 不再需要「红」「黑」汉字标注.
        textPaint.textSize = h * 0.55f
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        val redText = String.format(java.util.Locale.US, "%.1f%%", redPercent.coerceIn(0f, 100f))
        val blackText = String.format(java.util.Locale.US, "%.1f%%",
            (100f - redPercent).coerceIn(0f, 100f))
        canvas.drawText(redText, redWidth * 0.5f, h * 0.65f, textPaint)
        canvas.drawText(blackText, redWidth + (w - redWidth) * 0.5f, h * 0.65f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val inv = 1f - r
        val a = (Color.alpha(color1) * inv + Color.alpha(color2) * r).toInt()
        val cr = (Color.red(color1) * inv + Color.red(color2) * r).toInt()
        val cg = (Color.green(color1) * inv + Color.green(color2) * r).toInt()
        val cb = (Color.blue(color1) * inv + Color.blue(color2) * r).toInt()
        return Color.argb(a, cr, cg, cb)
    }
}