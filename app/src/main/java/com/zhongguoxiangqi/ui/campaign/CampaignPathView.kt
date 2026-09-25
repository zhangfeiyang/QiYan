package com.zhongguoxiangqi.ui.campaign

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.zhongguoxiangqi.R
import kotlin.math.sqrt

/**
 * 闯关地图: 蜿蜒虚线路径串起关卡节点的自绘 View.
 *
 * 参考中国风棋类 App 的关卡地图: 一条沙土色的虚线山路从第 1 关蜿蜒到
 * 第 5 关, 山顶放一只通关宝箱. 旧版是 3 列网格的关卡卡, 排列整齐得
 * 像张表格 —— 闯关的心理是"沿路往上打", 不是"从列表里挑一个", 路径
 * 本身就是进度感.
 *
 * 节点按状态分三种 (与盘上棋子同一套视觉语汇):
 *   已通关 —— 象牙面圆印 + 朱砂「胜」字 (复用棋子的画法)
 *   当前关 —— 朱红旗帜 + 白「战」字, 全图唯一的旗
 *   未解锁 —— 灰石碑, 只刻关号
 * 宝箱是山顶的终点装饰, 不可点.
 *
 * 状态(unlocked/passed)与几何(x/y)分开存: 通关回来刷新状态时 View 尺寸
 * 多半没变, onSizeChanged 不会再走 —— 几何依赖尺寸, 状态只管重绘.
 *
 * 背景的山水画在 bg_campaign (屏幕层), 这里只画"路"和"驿站" ——
 * 滚动的是路, 山水是固定的天.
 */
class CampaignPathView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onLevelClickListener: ((Int) -> Unit)? = null

    private var totalLevels = 0
    private var unlockedMax = 0
    private var passedSet: Set<Int> = emptySet()
    private var starMap: Map<Int, Int> = emptyMap()
    private var stageNames: List<String> = emptyList()

    /** 节点几何: [x0,y0, x1,y1, ...], 由尺寸决定, onSizeChanged 里重算 */
    private var positions = FloatArray(0)
    private var chest = RectF()

    // ====== 画笔(全部常驻, onDraw 不分配) ======
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = themeColor(R.color.campaign_path)
        pathEffect = DashPathEffect(
            floatArrayOf(dp(PATH_DASH_ON_DP), dp(PATH_DASH_OFF_DP)), 0f
        )
    }
    private val flagPolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = themeColor(R.color.board_palace)
    }
    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flagEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = themeColor(R.color.piece_red_edge)
    }
    private val stonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stoneEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = themeColor(R.color.border_strong)
    }
    /** 胜印与棋子同构: 象牙面 + 朱砂环 */
    private val sealFacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sealRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sealShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val starFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starEmptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** 宝箱的金漆: 渐变依赖尺寸, 在 onSizeChanged 构建 */
    private val chestBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chestLidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chestBandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chestEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = themeColor(R.color.gold_stroke)
        strokeWidth = 2f
    }

    /** 虚线路径的几何, onSizeChanged 重建 */
    private val pathShape = Path()

    /** 棋子/地图共用的楷体 (OFL, 见 assets/fonts/) */
    private val kaiTypeface: Typeface = try {
        Typeface.createFromAsset(context.assets, "fonts/chinese_piece.ttf")
    } catch (_: Exception) {
        Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    // ====== 公共 API ======

    /**
     * 设置地图数据. 尺寸依赖关卡数, 所以要 requestLayout;
     * 关卡数不变时 onSizeChanged 不会再来, 但几何只依赖尺寸, 仍然有效.
     *
     * @param unlocked 已解锁的最高关 (<= 该关都可打)
     * @param passed 已通关的关卡集合
     * @param stars 每关已获得的星数, 0 表示还没通关
     */
    fun setData(
        totalLevels: Int,
        unlocked: Int,
        passed: Set<Int>,
        stars: Map<Int, Int> = emptyMap(),
        names: List<String> = emptyList(),
    ) {
        this.totalLevels = totalLevels
        this.unlockedMax = unlocked
        this.passedSet = passed
        this.starMap = stars
        this.stageNames = names
        if (positions.size != totalLevels * 2) positions = FloatArray(totalLevels * 2)
        requestLayout()
        invalidate()
    }

    /** 某关节点中心的 y (View 坐标), 供打开页面时自动滚到当前关 */
    fun yOffsetOf(level: Int): Float {
        val idx = (level - 1).coerceIn(0, (totalLevels - 1).coerceAtLeast(0))
        return positions.getOrElse(idx * 2 + 1) { 0f }
    }

    /** 当前关(第一个已解锁未通关的关), 全图唯一旗子的落点 */
    private fun currentLevel(): Int =
        (1..totalLevels).firstOrNull { it <= unlockedMax && it !in passedSet } ?: -1

    // ====== 尺寸 ======

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(320f).toInt(), widthMeasureSpec)
        val n = totalLevels.coerceAtLeast(1)
        val h = dp(CHEST_ZONE_DP) + dp(TOP_PAD_DP) + (n - 1) * dp(ROW_SPACING_DP) +
            dp(BOTTOM_PAD_DP)
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || totalLevels <= 0) return
        layoutNodes(w.toFloat(), h.toFloat())
        rebuildPathShape()
        rebuildChestShader()
    }

    /**
     * 关卡沿一条正弦摆动的纵轴自下而上排布.
     *
     * 摆幅接近半屏宽, 相位取得让相邻两关左右交错、隔两关又荡回来 ——
     * 参考地图的"蛇形"就是这种不均匀的摆, 严格左右交替反而像地铁线路图.
     */
    private fun layoutNodes(w: Float, h: Float) {
        val swing = w / 2f - dp(NODE_EDGE_PAD_DP)
        for (level in 1..totalLevels) {
            val i = level - 1
            val x = w / 2f + swing * Math.sin(i * SWING_PHASE + SWING_OFFSET).toFloat()
            val y = h - dp(BOTTOM_PAD_DP) - i * dp(ROW_SPACING_DP)
            positions[i * 2] = x
            positions[i * 2 + 1] = y
        }
        // 宝箱悬在山顶关的正上方
        val topX = positions[(totalLevels - 1) * 2]
        val topY = positions[(totalLevels - 1) * 2 + 1]
        val cw = dp(CHEST_W_DP)
        val ch = dp(CHEST_H_DP)
        chest = RectF(topX - cw / 2, topY - dp(CHEST_GAP_DP) - ch,
            topX + cw / 2, topY - dp(CHEST_GAP_DP))
    }

    /** 相邻两关之间用竖直切线的三次贝塞尔相连 —— 正是山路那种"S"弯 */
    private fun rebuildPathShape() {
        pathShape.reset()
        if (totalLevels <= 0 || positions.isEmpty()) return
        pathShape.moveTo(positions[0], positions[1])
        for (i in 1 until totalLevels) {
            val px = positions[(i - 1) * 2]
            val py = positions[(i - 1) * 2 + 1]
            val cx = positions[i * 2]
            val cy = positions[i * 2 + 1]
            val midY = (py + cy) / 2f
            pathShape.cubicTo(px, midY, cx, midY, cx, cy)
        }
        // 最后一段通向宝箱
        pathShape.cubicTo(
            positions[(totalLevels - 1) * 2], chest.centerY(),
            chest.centerX(), chest.centerY(), chest.centerX(), chest.bottom
        )
    }

    private fun rebuildChestShader() {
        if (chest.isEmpty) return
        chestLidPaint.shader = LinearGradient(
            0f, chest.top, 0f, chest.centerY(),
            themeColor(R.color.gold_grad_1), themeColor(R.color.gold_grad_2),
            Shader.TileMode.CLAMP
        )
        chestBodyPaint.shader = LinearGradient(
            0f, chest.centerY(), 0f, chest.bottom,
            themeColor(R.color.gold_grad_2), themeColor(R.color.gold_grad_3),
            Shader.TileMode.CLAMP
        )
    }

    // ====== 绘制 ======

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totalLevels <= 0 || positions.isEmpty()) return
        pathPaint.strokeWidth = dp(PATH_WIDTH_DP)
        canvas.drawPath(pathShape, pathPaint)
        drawChest(canvas)
        val current = currentLevel()
        for (level in 1..totalLevels) {
            drawNode(canvas, level, isCurrent = level == current)
        }
    }

    private fun nodeX(level: Int) = positions[(level - 1) * 2]
    private fun nodeY(level: Int) = positions[(level - 1) * 2 + 1]

    private fun drawNode(canvas: Canvas, level: Int, isCurrent: Boolean) {
        val x = nodeX(level)
        val y = nodeY(level)
        val label = stageNames.getOrElse(level - 1) {
            context.getString(R.string.campaign_level_format, level)
        }
        when {
            isCurrent -> {
                drawFlag(canvas, x, y, FLAG_CHAR)
                drawLabel(canvas, x, y, label, R.color.action, bold = true)
            }
            level in passedSet -> {
                drawSeal(canvas, x, y, PASS_CHAR)
                drawStarRow(canvas, x, y + dp(24f), starMap[level] ?: 0)
                drawLabel(canvas, x, y, label, R.color.text_secondary, bold = false, extra = dp(10f))
            }
            else -> {
                drawStone(canvas, x, y, level)
                drawLabel(canvas, x, y, label, R.color.text_tertiary, bold = false, dim = true)
            }
        }
    }

    /** 当前关: 朱红旗帜, 参考地图里"打到这儿了"的那面战旗 */
    private fun drawFlag(canvas: Canvas, x: Float, y: Float, char: String) {
        val poleTop = y - dp(FLAG_POLE_DP)
        flagPolePaint.strokeWidth = dp(3f)
        canvas.drawLine(x, y + dp(8f), x, poleTop, flagPolePaint)
        // 旗面: 挂在杆右侧, 外沿一道微波, "旗子在风里"
        val bw = dp(FLAG_W_DP)
        val bh = dp(FLAG_H_DP)
        val flag = Path()
        flag.moveTo(x, poleTop)
        flag.lineTo(x + bw, poleTop + dp(2.5f))
        flag.quadTo(x + bw + dp(4f), poleTop + bh / 2f, x + bw, poleTop + bh - dp(2.5f))
        flag.lineTo(x, poleTop + bh)
        flag.close()
        flagPaint.color = themeColor(R.color.action)
        canvas.drawPath(flag, flagPaint)
        flagEdgePaint.strokeWidth = dp(1f)
        canvas.drawPath(flag, flagEdgePaint)

        textPaint.typeface = kaiTypeface
        textPaint.isFakeBoldText = true
        textPaint.color = themeColor(R.color.piece_text)
        textPaint.textSize = dp(FLAG_TEXT_DP)
        canvas.drawText(char, x + bw / 2f + dp(1.5f),
            poleTop + bh / 2f - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f,
            textPaint)
    }

    /** 已通关: 象牙面圆印 + 朱砂「胜」, 与盘上的棋子同一套画法 */
    private fun drawSeal(canvas: Canvas, x: Float, y: Float, char: String) {
        val r = dp(SEAL_R_DP)
        sealShadowPaint.color = themeColor(R.color.piece_shadow)
        canvas.drawCircle(x + dp(1.5f), y + dp(2f), r, sealShadowPaint)
        sealFacePaint.color = themeColor(R.color.piece_face)
        canvas.drawCircle(x, y, r, sealFacePaint)
        sealRimPaint.color = themeColor(R.color.piece_red)
        sealRimPaint.strokeWidth = dp(2.2f)
        canvas.drawCircle(x, y, r - sealRimPaint.strokeWidth / 2f, sealRimPaint)

        textPaint.typeface = kaiTypeface
        textPaint.isFakeBoldText = true
        textPaint.color = themeColor(R.color.piece_red)
        textPaint.textSize = dp(SEAL_TEXT_DP)
        canvas.drawText(char, x,
            y - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f,
            textPaint)
    }

    /** 未解锁: 一块灰石碑, 只刻关号 —— 山路上还没立起来的那块碑 */
    private fun drawStone(canvas: Canvas, x: Float, y: Float, level: Int) {
        val r = dp(STONE_R_DP)
        // 整体压暗一档, 让石碑往画里退
        stonePaint.alpha = 210
        stonePaint.color = themeColor(R.color.surface_raised)
        canvas.drawCircle(x, y, r, stonePaint)
        stoneEdgePaint.alpha = 185
        stoneEdgePaint.strokeWidth = dp(1.5f)
        canvas.drawCircle(x, y, r - stoneEdgePaint.strokeWidth / 2f, stoneEdgePaint)
        textPaint.typeface = kaiTypeface
        textPaint.isFakeBoldText = false
        textPaint.color = themeColor(R.color.text_tertiary)
        textPaint.alpha = 200
        textPaint.textSize = dp(STONE_TEXT_DP)
        canvas.drawText(level.toString(), x,
            y - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f,
            textPaint)
        stonePaint.alpha = 255
        stoneEdgePaint.alpha = 255
        textPaint.alpha = 255
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float, text: String,
                          @ColorRes colorRes: Int, bold: Boolean, dim: Boolean = false,
                          extra: Float = 0f) {
        textPaint.typeface = kaiTypeface
        textPaint.isFakeBoldText = bold
        textPaint.color = themeColor(colorRes)
        textPaint.alpha = if (dim) 204 else 255
        textPaint.textSize = dp(LABEL_TEXT_DP)
        canvas.drawText(text, x, y + dp(LABEL_GAP_DP) + extra, textPaint)
        textPaint.alpha = 255
    }

    /** 胜印底下的三颗星: 实心是拿到的, 空心是还没拿到的. */
    private fun drawStarRow(canvas: Canvas, x: Float, y: Float, count: Int) {
        val shown = count.coerceIn(0, 3)
        if (shown == 0) return
        val r = dp(2.4f)
        val gap = dp(8f)
        val start = x - gap
        val gold = themeColor(R.color.rank_gold)
        starFillPaint.color = gold
        starEmptyPaint.color = gold
        starEmptyPaint.strokeWidth = dp(1f)
        for (i in 0 until 3) {
            val cx = start + i * gap
            if (i < shown) canvas.drawCircle(cx, y, r, starFillPaint)
            else canvas.drawCircle(cx, y, r, starEmptyPaint)
        }
    }

    /** 山顶的通关宝箱: 终点装饰, 金漆与鎏金按钮同源 */
    private fun drawChest(canvas: Canvas) {
        if (chest.isEmpty) return
        val lidH = chest.height() * 0.42f
        val lid = RectF(chest.left, chest.top, chest.right, chest.top + lidH)
        val body = RectF(chest.left, chest.top + lidH, chest.right, chest.bottom)
        val rr = dp(7f)
        canvas.drawRoundRect(lid, rr, rr, chestLidPaint)
        canvas.drawRoundRect(body, rr, rr, chestBodyPaint)
        // 中缝金箍 + 锁扣
        chestBandPaint.color = themeColor(R.color.gold_pressed)
        canvas.drawRect(chest.centerX() - dp(3.5f), chest.top,
            chest.centerX() + dp(3.5f), chest.bottom, chestBandPaint)
        canvas.drawCircle(chest.centerX(), chest.top + lidH, dp(4.5f), chestBandPaint)
        canvas.drawRoundRect(lid, rr, rr, chestEdgePaint)
        canvas.drawRoundRect(body, rr, rr, chestEdgePaint)

        textPaint.typeface = kaiTypeface
        textPaint.isFakeBoldText = true
        // 不能用 gold_text: 那是"金底按钮上的字"色, 夜间是深褐 #4A1D0A,
        // 画在深色页面上等于隐身. warning 是日夜各自调过对比度的琥珀金.
        textPaint.color = themeColor(R.color.warning)
        textPaint.textSize = dp(GOAL_TEXT_DP)
        val label = context.getString(R.string.campaign_goal)
        canvas.drawText(label, chest.centerX(), chest.bottom + dp(16f) -
            (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f, textPaint)
    }

    // ====== 触摸 ======

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            // DOWN 必须声明消费: 本 View 默认不可点击, super 对 DOWN 返回
            // false, 父 ScrollView 就接管整串事件, UP 再也到不了这里 ——
            // 表现就是"点关卡没反应"。
            android.view.MotionEvent.ACTION_DOWN -> return true
            android.view.MotionEvent.ACTION_UP -> {
                performClick()
                val level = hitLevel(event.x, event.y)
                if (level != -1 && level <= unlockedMax) {
                    onLevelClickListener?.invoke(level)
                    return true
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitLevel(x: Float, y: Float): Int {
        val hitR = dp(HIT_R_DP)
        var best = -1
        var bestD = Float.MAX_VALUE
        for (level in 1..totalLevels) {
            val dx = x - nodeX(level)
            val dy = y - nodeY(level)
            val d = sqrt(dx * dx + dy * dy)
            if (d < hitR && d < bestD) {
                best = level
                bestD = d
            }
        }
        return best
    }

    // ====== 工具 ======

    @Suppress("NOTHING_TO_INLINE")
    private inline fun themeColor(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val FLAG_CHAR = "战"
        private const val PASS_CHAR = "胜"

        /** 两关之间的垂直间距(dp) —— 路要"蜿蜒", 不能挤成楼梯 */
        private const val ROW_SPACING_DP = 104f
        private const val TOP_PAD_DP = 8f
        private const val BOTTOM_PAD_DP = 72f
        /** 山顶宝箱区的高度 */
        private const val CHEST_ZONE_DP = 96f
        private const val CHEST_GAP_DP = 40f
        private const val CHEST_W_DP = 46f
        private const val CHEST_H_DP = 32f
        private const val GOAL_TEXT_DP = 14f

        private const val FLAG_POLE_DP = 30f
        private const val FLAG_W_DP = 36f
        private const val FLAG_H_DP = 24f
        private const val FLAG_TEXT_DP = 14f
        private const val SEAL_R_DP = 17f
        private const val SEAL_TEXT_DP = 17f
        private const val STONE_R_DP = 15f
        private const val STONE_TEXT_DP = 15f
        private const val LABEL_TEXT_DP = 12f
        private const val LABEL_GAP_DP = 30f
        private const val NODE_EDGE_PAD_DP = 64f
        private const val HIT_R_DP = 34f

        private const val PATH_WIDTH_DP = 3.5f
        private const val PATH_DASH_ON_DP = 9f
        private const val PATH_DASH_OFF_DP = 7f

        /** 关卡沿纵轴摆动的节奏: 每关转 0.85rad, 起手向左 */
        private const val SWING_PHASE = 0.85
        private const val SWING_OFFSET = 2.4
    }
}
