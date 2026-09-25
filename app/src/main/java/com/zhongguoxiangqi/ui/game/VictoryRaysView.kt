package com.zhongguoxiangqi.ui.game

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.zhongguoxiangqi.ui.settings.AppSettings
import kotlin.math.cos
import kotlin.math.sin

/**
 * 终局胜利万丈光芒旋转动效 (JJ象棋风格).
 * 在胜利牌匾背后持续360度平滑旋转，放射出辉煌的金光光芒。
 */
class VictoryRaysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var isVictory: Boolean = true
        set(value) {
            field = value
            updateColors()
            invalidate()
        }

    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var currentRotation = 0f
    private var animator: ValueAnimator? = null
    private val rayPath = Path()
    private val rayCount = 18

    init {
        updateColors()
    }

    private fun updateColors() {
        // Will set shader in onSizeChanged / onDraw
        shaderRadius = 0f
    }

    private var shaderRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = kotlin.math.max(w, h) * 0.7f
        shaderRadius = radius
        val cx = w / 2f
        val cy = h / 2f

        if (isVictory) {
            rayPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    0xE6FFD700.toInt(), // 辉煌金黄
                    0x99FFA000.toInt(), // 琥珀金
                    0x44FF6F00.toInt(), // 晚霞暖金
                    0x00E65100.toInt()  // 边缘完全透明淡出
                ),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            rayPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    0x8078909C.toInt(), // 沉着水墨蓝灰
                    0x40455A64.toInt(),
                    0x00263238.toInt()
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    fun start() {
        if (animator?.isRunning == true) return
        // 动画开关关闭 / 系统禁用动画: 不转, 静止画一帧 rotation=0 的光芒
        if (!AppSettings(context).animationEnabled || !ValueAnimator.areAnimatorsEnabled()) {
            currentRotation = 0f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 14000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                currentRotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f
        val radius = kotlin.math.max(w, h) * 0.75f

        canvas.save()
        canvas.rotate(currentRotation, cx, cy)

        val step = 360.0 / rayCount
        val halfAngle = step * 0.32 // 光芒扇面宽度

        for (i in 0 until rayCount) {
            val centerAngle = Math.toRadians(i * step)
            val a1 = centerAngle - Math.toRadians(halfAngle)
            val a2 = centerAngle + Math.toRadians(halfAngle)

            val x1 = (cx + radius * cos(a1)).toFloat()
            val y1 = (cy + radius * sin(a1)).toFloat()
            val x2 = (cx + radius * cos(a2)).toFloat()
            val y2 = (cy + radius * sin(a2)).toFloat()

            rayPath.reset()
            rayPath.moveTo(cx, cy)
            rayPath.lineTo(x1, y1)
            rayPath.lineTo(x2, y2)
            rayPath.close()

            canvas.drawPath(rayPath, rayPaint)
        }

        canvas.restore()
    }
}
