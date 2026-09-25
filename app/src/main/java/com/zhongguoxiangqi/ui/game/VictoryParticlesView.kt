package com.zhongguoxiangqi.ui.game

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * 终局胜利彩带星光礼花飞舞特效 (JJ象棋风格).
 * 绝杀获胜瞬间，大量金色星光与绚烂彩带从中央向四周喷薄爆发，璀璨夺目。
 */
class VictoryParticlesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val color: Int,
        val size: Float,
        var rotation: Float,
        val vRot: Float,
        val isStar: Boolean,
        val lifeTime: Float, // seconds
    ) {
        var age: Float = 0f
    }

    private val particles = ArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val starPath = Path()
    private val random = Random()
    private var animator: ValueAnimator? = null

    private val victoryColors = intArrayOf(
        0xFFFFD700.toInt(), // 黄金
        0xFFFFA000.toInt(), // 琥珀
        0xFFFF5722.toInt(), // 亮朱红
        0xFFE91E63.toInt(), // 喜庆红
        0xFFFFEB3B.toInt(), // 亮金星
        0xFF4CAF50.toInt(), // 碧玉
        0xFF00E5FF.toInt(), // 青翠
        0xFFFFFFFF.toInt()  // 闪耀白
    )

    fun burst(isVictory: Boolean = true) {
        particles.clear()
        val cx = (if (width > 0) width else 1080) / 2f
        val cy = (if (height > 0) height else 1920) / 2f - 40f
        spawn(if (isVictory) 55 else 20, isVictory, cx, cy)
        restartAnimator()
    }

    /**
     * 追加一波礼花 (不清空第一波): 胜利双波演出用 —— 第一波在匾额砸落时喷出,
     * 第二波在星星就位后 (约 550ms) 再补一把, 视觉上"燃两次"。
     * 视图已脱离窗口时静默忽略, 避免宿主 postDelayed 的残回调驱动僵尸动画。
     */
    fun extraBurst(count: Int) {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return
        spawn(count, isVictory = true, width / 2f, height / 2f - 40f)
        restartAnimator()
    }

    private fun spawn(count: Int, isVictory: Boolean, cx: Float, cy: Float) {
        for (i in 0 until count) {
            val angle = random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = 250f + random.nextFloat() * 850f
            val vx = cos(angle) * speed
            val vy = sin(angle) * speed - 150f // 稍向上喷薄
            val color = if (isVictory) {
                victoryColors[random.nextInt(victoryColors.size)]
            } else {
                0xFF90A4AE.toInt()
            }
            val isStar = random.nextBoolean()
            val size = if (isStar) (10f + random.nextFloat() * 14f) else (8f + random.nextFloat() * 12f)
            val lifeTime = 1.4f + random.nextFloat() * 1.0f

            particles.add(
                Particle(
                    x = cx,
                    y = cy,
                    vx = vx,
                    vy = vy,
                    color = color,
                    size = size,
                    rotation = random.nextFloat() * 360f,
                    vRot = (random.nextFloat() - 0.5f) * 720f,
                    isStar = isStar,
                    lifeTime = lifeTime
                )
            )
        }
    }

    private fun restartAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 2.2f).apply {
            duration = 2200L
            interpolator = DecelerateInterpolator(1.2f)
            var lastTime = 0f
            addUpdateListener { va ->
                val currTime = va.animatedFraction * 2.2f
                val dt = (currTime - lastTime).coerceIn(0f, 0.1f)
                lastTime = currTime
                updatePhysics(dt)
                invalidate()
            }
            start()
        }
    }

    private fun updatePhysics(dt: Float) {
        val gravity = 480f
        val drag = 0.94f
        for (p in particles) {
            p.age += dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += gravity * dt
            p.vx *= drag
            p.vy *= drag
            p.rotation += p.vRot * dt
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (particles.isEmpty()) return

        for (p in particles) {
            if (p.age >= p.lifeTime) continue
            val progress = p.age / p.lifeTime
            val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue

            paint.color = p.color
            paint.alpha = alpha

            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rotation)

            if (p.isStar) {
                drawStar(canvas, p.size)
            } else {
                // 彩纸薄片，带 3D 翻转缩放感
                val flip = cos(p.rotation * 0.05).toFloat()
                val rect = RectF(-p.size, -p.size * 0.4f * flip, p.size, p.size * 0.4f * flip)
                canvas.drawRoundRect(rect, 2f, 2f, paint)
            }

            canvas.restore()
        }
    }

    private fun drawStar(canvas: Canvas, r: Float) {
        starPath.reset()
        val innerR = r * 0.45f
        for (i in 0 until 5) {
            val outerAngle = Math.toRadians((i * 72 - 18).toDouble())
            val innerAngle = Math.toRadians((i * 72 + 18).toDouble())
            val ox = (r * cos(outerAngle)).toFloat()
            val oy = (r * sin(outerAngle)).toFloat()
            val ix = (innerR * cos(innerAngle)).toFloat()
            val iy = (innerR * sin(innerAngle)).toFloat()
            if (i == 0) starPath.moveTo(ox, oy) else starPath.lineTo(ox, oy)
            starPath.lineTo(ix, iy)
        }
        starPath.close()
        canvas.drawPath(starPath, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}
