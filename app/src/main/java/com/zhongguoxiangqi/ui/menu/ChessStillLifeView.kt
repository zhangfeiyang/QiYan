package com.zhongguoxiangqi.ui.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** A quiet, locally drawn still life. Decorative only; no animation loop or bitmap allocation. */
class ChessStillLifeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterface = runCatching {
        Typeface.createFromAsset(context.assets, "fonts/chinese_piece.ttf")
    }.getOrElse { Typeface.create("serif", Typeface.BOLD) }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 220f, height / 220f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .65f
        paint.color = Color.argb(32, 223, 196, 139)
        for (i in 0..8) {
            canvas.drawLine(35f + i * 30, 20f, -40f + i * 30, 220f, paint)
            canvas.drawLine(0f, 30f + i * 28, 240f, 100f + i * 28, paint)
        }
        paint.color = Color.argb(28, 223, 196, 139)
        canvas.drawCircle(170f, 91f, 86f, paint)
        canvas.drawCircle(170f, 91f, 93f, paint)
        piece(canvas, 163f, 60f, 33f, "卒", false)
        piece(canvas, 103f, 123f, 44f, "馬", false)
        piece(canvas, 170f, 168f, 48f, "帥", true)
        canvas.restore()
    }

    private fun piece(canvas: Canvas, x: Float, y: Float, r: Float, glyph: String, red: Boolean) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(x + 3, y + 12, r * 1.32f,
            intArrayOf(0x66060F0D, 0x00060F0D), floatArrayOf(.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x + 3, y + 12, r * 1.32f, paint)
        paint.shader = null
        paint.color = Color.rgb(157, 117, 65)
        canvas.drawOval(x-r, y-r+7, x+r, y+r+7, paint)
        paint.shader = LinearGradient(x-r, y-r, x+r, y+r,
            intArrayOf(0xFFFFF4D8.toInt(), 0xFFE4CAA0.toInt(), 0xFFB78F56.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.color = if (red) Color.rgb(160, 56, 38) else Color.rgb(49, 68, 56)
        canvas.drawCircle(x, y, r - 5, paint)
        paint.strokeWidth = .7f
        canvas.drawCircle(x, y, r - 8, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = letterface
        paint.textSize = r * 1.13f
        canvas.drawText(glyph, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
