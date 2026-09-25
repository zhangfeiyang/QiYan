package com.zhongguoxiangqi.ui.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.zhongguoxiangqi.R
import androidx.core.content.ContextCompat

/**
 * 十枚段位印, 自上而下从初学排到棋王.
 *
 * 当前印加一圈金边. 还没到的印收成石色, 名字仍可读, 让阶梯看得见终点.
 */
class RankHallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var currentId = 1
    private var names: List<String> = emptyList()
    private var ranges: List<String> = emptyList()
    private var colors: IntArray = IntArray(0)

    private val kai: Typeface = try {
        Typeface.createFromAsset(context.assets, "fonts/chinese_piece.ttf")
    } catch (_: Exception) {
        Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val sealPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = kai
        isFakeBoldText = true
    }
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = kai
        isFakeBoldText = true
    }

    fun setLadder(currentId: Int, names: List<String>, ranges: List<String>, colors: IntArray) {
        this.currentId = currentId
        this.names = names
        this.ranges = ranges
        this.colors = colors
        contentDescription = names.getOrNull(currentId - 1)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(320f).toInt(), widthMeasureSpec)
        val rows = names.size.coerceAtLeast(1)
        val h = dp(8f) + rows * dp(ROW_DP)
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = names.size
        if (n == 0) return
        val sealR = dp(22f)
        val left = dp(20f)
        val textLeft = left + sealR * 2f + dp(16f)
        for (i in 0 until n) {
            val cy = dp(8f) + i * dp(ROW_DP) + dp(ROW_DP) / 2f
            val id = i + 1
            val reached = id <= currentId
            val current = id == currentId
            val ink = if (reached) colors.getOrElse(i) { theme(R.color.text_primary) }
                else theme(R.color.text_tertiary)
            sealPaint.color = if (reached) theme(R.color.piece_face) else theme(R.color.surface_raised)
            canvas.drawCircle(left + sealR, cy, sealR, sealPaint)
            ringPaint.color = if (current) theme(R.color.rank_gold) else ink
            ringPaint.strokeWidth = if (current) dp(2.6f) else dp(1.6f)
            canvas.drawCircle(left + sealR, cy, sealR - ringPaint.strokeWidth / 2f, ringPaint)
            if (current) {
                ringPaint.strokeWidth = dp(1f)
                canvas.drawCircle(left + sealR, cy, sealR + dp(4f), ringPaint)
            }
            numeralPaint.color = ink
            numeralPaint.textSize = dp(16f)
            val numeral = id.toString()
            canvas.drawText(
                numeral,
                left + sealR,
                cy - (numeralPaint.fontMetrics.ascent + numeralPaint.fontMetrics.descent) / 2f,
                numeralPaint,
            )
            namePaint.color = if (reached) theme(R.color.text_primary) else theme(R.color.text_tertiary)
            namePaint.textSize = dp(16f)
            canvas.drawText(names[i], textLeft, cy - dp(4f), namePaint)
            metaPaint.color = theme(R.color.text_secondary)
            metaPaint.textSize = dp(12f)
            val status = when {
                current -> context.getString(R.string.rank_hall_current)
                reached -> context.getString(R.string.rank_hall_reached)
                else -> context.getString(R.string.rank_hall_locked)
            }
            val range = ranges.getOrElse(i) { "" }
            canvas.drawText("$range  ·  $status", textLeft, cy + dp(16f), metaPaint)
        }
    }

    private fun theme(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object {
        const val ROW_DP = 76f
    }
}
