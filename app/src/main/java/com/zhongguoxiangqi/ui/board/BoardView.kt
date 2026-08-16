package com.zhongguoxiangqi.ui.board

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Square
import kotlin.math.max
import kotlin.math.min

/**
 * 中国象棋棋盘自定义 View.
 *
 * 特性:
 * - 经典木纹棋盘背景
 * - 棋子: 红/黑圆形木纹棋子 + 中文楷体字符
 * - 选格高亮、可走步高亮、最后走法高亮、提示高亮
 * - 触摸事件: 点击选格 → 拖动 → 落子
 * - 走子动画(平滑过渡)
 * - 翻转棋盘(支持黑方视角)
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 从资源解析颜色, 替代原来满屏的 parseColor("#XXXXXX").
     *
     * 之前棋盘的三十来个颜色是写死在 Kotlin 里的字符串字面量, colors.xml
     * 里那套 board_* / piece_* / highlight_* 则从没被解析过 —— 两套值各写
     * 各的, 改了资源棋盘纹丝不动. 走资源之后才能随明暗主题自动翻转.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun themeColor(@ColorRes id: Int): Int =
        ContextCompat.getColor(context, id)

    // ====== 配置 ======
    private var flipped = false
    private var showCoordinates = true
    private var showWinRateBar = true
    private var animationEnabled = true

    // ====== 数据 ======
    private var position: Position = Position()
    private var selectedSquare: Int = -1
    private var legalMovesFromSelected: List<Move> = emptyList()
    private var lastMove: Move? = null
    private var hintMove: Move? = null
    private var hintMoveSecondary: List<Move> = emptyList()
    private var checkedKingSq: Int = -1  // 被将军的将/帅位置

    // ====== 回调 ======
    var onMoveListener: ((Move) -> Unit)? = null
    var onSelectedListener: ((Int, List<Move>) -> Unit)? = null
    var onLongPressListener: ((Int) -> Unit)? = null

    /**
     * 编辑模式: 点击只上报格子, 不做选子/走子那一套.
     *
     * 拍照识别出来的局面往往还没法合法走子(可能缺将、可能多子), 用走子逻辑
     * 去处理点击只会什么都选不中.
     */
    var editMode = false

    /** 编辑模式下的点击回调 */
    var onCellTapListener: ((Int) -> Unit)? = null

    /** 需要用户重点核对的格子(识别置信度低 / 校验有问题) */
    private var suspectSquares: Set<Int> = emptySet()

    /**
     * 提示走法的目标格 —— 在那一格要画"棋子落在那里"的虚影.
     * 落子完成 / 玩家自行走出新一步时, 都要清掉.
     */
    private var hintGhostSquare: Int = -1

    /** 提示走法要预览的棋子代码(从 from 格取, 提前记下来) */
    private var hintGhostPiece: Int = Piece.NONE.code

    /**
     * 威胁光晕: 哪个格子上的子被对方下一步瞄上了.
     * 命名按"我在看谁的子"来, 不按"谁走子" —— 红方子被威胁就标红, 黑方子被威胁就标绿,
     * 颜色来自棋子本身, 跟当前走子方无关.
     */
    private var redUnderThreat: Set<Int> = emptySet()
    private var blackUnderThreat: Set<Int> = emptySet()

    // ====== 绘制资源 ======
    private val pieceRedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boardLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.board_line)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val boardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palaceLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.board_line)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val highlightSelectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_select)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val highlightMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_move)
    }
    /** 可走点的外圈光晕: 单独一支笔, 避免改到 highlightMovePaint 的 alpha */
    private val highlightMoveHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_move_halo)
    }
    private val highlightCapturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_capture)
    }
    private val highlightLastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_last)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val highlightHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_hint)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val highlightHintSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_hint_soft)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    /** 识别置信度低 / 校验不通过的格子 */
    private val suspectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = themeColor(R.color.warning)
    }

    /** 红方子被威胁: 我方子被瞄, 红色光晕警示 */
    private val dangerRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_capture)
    }
    /** 黑方子被威胁: 对方子被瞄, 绿色光晕(可吃) */
    private val dangerGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_move)
    }

    /** 提示走法的箭头: 深绿, 粗笔 */
    private val hintArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_arrow)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    /** 提示走法箭头头部的小三角 */
    private val hintArrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_arrow)
        style = Paint.Style.FILL
    }
    /** 提示走法虚影: 棋子位图叠一层 alpha */
    private val hintGhostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 90
    }

    private val checkMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.hl_check)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    /** 选中环: 不透明, 因为它要压在棋子上, 半透明会和棋子颜色混脏 */
    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.sel_ring)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val selectionGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.sel_ring)
        style = Paint.Style.STROKE
    }

    /** 选中环的呼吸相位 0..1, 由 [selectionAnimator] 驱动 */
    private var selectionPulse = 0f
    private var selectionAnimator: ValueAnimator? = null

    // ====== 吃子特效 ======

    /**
     * 一颗碎片.
     *
     * 位置/速度都用"格宽的倍数"存, 不存像素 —— 棋盘尺寸会随屏幕变, 存像素
     * 就得在 onSizeChanged 里重算所有粒子.
     */
    private class Shard(
        var x: Float, var y: Float,
        val vx: Float, val vy: Float,
        val spin: Float,
        val size: Float,
        val color: Int,
    )

    private var fxSquare = -1
    private var fxPiece = Piece.NONE.code
    private var fxProgress = 0f
    private var fxAnimator: ValueAnimator? = null
    private val fxShards = ArrayList<Shard>(28)
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fxPath = Path()

    /** 提示箭头的三角头, 复用避免 onDraw 内分配 */
    private val arrowHeadPath = Path()

    /**
     * 播放吃子特效.
     *
     * 每个兵种一套视觉, 不是同一个爆炸换颜色 —— 吃车和吃兵在棋上的分量差得远,
     * 特效也应该说出这个差别. 具体见 [drawCaptureFx].
     */
    fun playCaptureEffect(square: Int, capturedPiece: Int) {
        if (square < 0 || square >= Square.COUNT) return
        fxAnimator?.cancel()
        fxSquare = square
        fxPiece = capturedPiece
        fxProgress = 0f
        seedShards(capturedPiece)

        fxAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = captureFxDuration(capturedPiece)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fxProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    fxSquare = -1
                    fxShards.clear()
                    invalidate()
                }
            })
            start()
        }
    }

    /** 将/帅被吃是终局, 值得演久一点; 兵卒频繁被吃, 拖长了会烦 */
    private fun captureFxDuration(piece: Int): Long = when (Piece.fromCode(piece)) {
        Piece.R_KING, Piece.B_KING -> 1100L
        Piece.R_ROOK, Piece.B_ROOK, Piece.R_CANNON, Piece.B_CANNON -> 780L
        Piece.R_PAWN, Piece.B_PAWN -> 420L
        else -> 620L
    }

    private fun seedShards(pieceCode: Int) {
        fxShards.clear()
        val piece = Piece.fromCode(pieceCode)
        val warm = if (piece.isRed) themeColor(R.color.piece_red) else themeColor(R.color.piece_black)
        val spark = themeColor(R.color.fx_spark)
        val rnd = java.util.Random(pieceCode.toLong() * 31 + fxSquare)

        val count = when (piece) {
            Piece.R_PAWN, Piece.B_PAWN -> 8
            Piece.R_KING, Piece.B_KING -> 26
            Piece.R_ROOK, Piece.B_ROOK -> 18
            else -> 14
        }
        repeat(count) { i ->
            val ang = (i.toFloat() / count) * (Math.PI * 2).toFloat() +
                rnd.nextFloat() * 0.4f
            val speed = 0.5f + rnd.nextFloat() * 0.9f
            fxShards += Shard(
                x = 0f, y = 0f,
                vx = kotlin.math.cos(ang) * speed,
                vy = kotlin.math.sin(ang) * speed,
                spin = (rnd.nextFloat() - 0.5f) * 720f,
                size = 0.06f + rnd.nextFloat() * 0.10f,
                color = if (i % 3 == 0) spark else warm,
            )
        }
    }
    private val coordinatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.board_coord)
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val riverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.board_palace)
        textSize = 56f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    // ====== 动画 ======
    private var animatingMove: Move? = null
    private var animationProgress = 0f
    private var animationAnimator: ValueAnimator? = null
    private var animatingFromCenter = PointF()
    private var animatingToCenter = PointF()
    private var animatingPiece: Int = 0

    // ====== 尺寸(在 onSizeChanged 中计算) ======
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardRight = 0f
    private var boardBottom = 0f
    private var cellWidth = 0f
    private var cellHeight = 0f
    private var pieceRadius = 0f
    private var pieceBitmapCache: Pair<Bitmap, Bitmap>? = null  // 红/黑棋子位图

    // ====== 长按检测 ======
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f

    // 棋子绘制用的常驻画笔(onDraw 中不再分配对象)
    private val pieceInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pieceWoodLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.piece_grain)
        strokeWidth = 0.8f
        style = Paint.Style.STROKE
    }
    private val boardWoodPaint = Paint().apply {
        color = themeColor(R.color.board_grain)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val boardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.board_border)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val boardRect = RectF()

    private val redPieceColor = themeColor(R.color.piece_red)
    private val blackPieceColor = themeColor(R.color.piece_black)
    private val pieceTextColor = themeColor(R.color.piece_text)

    /** 棋子上印汉字还是拉丁字母. 见 [pieceLabel] */
    enum class PieceSet { HANZI, LATIN }

    /**
     * 当前棋子字符集. 改动会重排字号并立刻重绘 ——
     * 拉丁字母比汉字窄, 沿用汉字的字号会在圆面里显得空一圈。
     */
    var pieceSet: PieceSet = PieceSet.HANZI
        set(value) {
            if (field == value) return
            field = value
            applyPieceTextSize()
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        pieceShadowPaint.color = themeColor(R.color.piece_shadow)
        pieceBorderPaint.style = Paint.Style.FILL
        // 棋子文字必须居中对齐,否则字会整体偏到棋子右侧
        pieceRedPaint.textAlign = Paint.Align.CENTER
        pieceBlackPaint.textAlign = Paint.Align.CENTER
        // 尝试加载内嵌字体
        val tf = try {
            Typeface.createFromAsset(context.assets, "fonts/chinese_piece.ttf")
        } catch (_: Exception) {
            // 字体加载失败,使用系统字体
            Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        pieceRedPaint.typeface = tf
        pieceBlackPaint.typeface = tf

        // 字符集在这里自己读, 不由各个 Activity 分别设置: 全 App 有 5 处
        // 用到 BoardView (对局/复盘/支招/摆子/识局), 靠调用方记得设置就等于
        // 迟早会漏掉一两处, 出现"对局界面是字母、复盘界面是汉字"这种分裂。
        pieceSet = com.zhongguoxiangqi.ui.common.Notation.pieceSetFor(context)
    }

    // ====== 公共 API ======

    /**
     * 同步棋盘.
     *
     * @param position **走子之后**的局面
     * @param animateMove 若非 null,把这一步画成从起点滑到终点的动画.
     *        只在确实只走了一步时传(悔棋/开新局/跳步都应传 null).
     */
    fun setPosition(position: Position, animateMove: Move? = null) {
        // 局面没变就什么都不做. 状态流一步棋会发射多次(比如紧接着的
        // isThinking=true), 每次都重刷会打断正在播的走子动画, 也会把玩家
        // 刚选中的棋子清掉.
        if (animateMove == null && position.hash == this.position.hash) return

        this.position = position.copy()
        this.lastMove = null
        this.hintMove = null
        this.hintMoveSecondary = emptyList()
        this.selectedSquare = -1
        this.legalMovesFromSelected = emptyList()
        syncSelectionAnimator()
        this.checkedKingSq = -1

        if (animateMove != null && animationEnabled && cellWidth > 0f) {
            startMoveAnimation(animateMove)
        } else {
            cancelMoveAnimation()
        }
        invalidate()
    }

    /**
     * 让走子的棋子从起点滑到终点.
     * 传进来的 [position] 已是走完之后的状态,所以动画棋子取自终点格.
     */
    private fun startMoveAnimation(move: Move) {
        val piece = position.pieceAt(move.to)
        if (piece == Piece.NONE.code) {
            cancelMoveAnimation()
            return
        }
        animationAnimator?.cancel()
        animatingMove = move
        animatingPiece = piece
        animatingFromCenter = squareToCenter(move.from)
        animatingToCenter = squareToCenter(move.to)
        animationProgress = 0f

        animationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                animatingMove = null
                animationProgress = 0f
                invalidate()
            }
            start()
        }
    }

    private fun cancelMoveAnimation() {
        animationAnimator?.cancel()
        animationAnimator = null
        animatingMove = null
        animationProgress = 0f
    }

    fun setLastMove(move: Move?) {
        this.lastMove = move
        invalidate()
    }

    fun setHint(primaryMove: Move?, secondary: List<Move> = emptyList()) {
        // 提前记下 from 格上的棋子代码, 用来在目标格渲染"这枚子走到这里"的虚影.
        // 走子方走完后 from 格已经空了, 那时再读就拿不到.
        if (primaryMove != null) {
            hintGhostPiece = position.pieceAt(primaryMove.from)
            hintGhostSquare = primaryMove.to
        } else {
            hintGhostSquare = -1
            hintGhostPiece = Piece.NONE.code
        }
        this.hintMove = primaryMove
        this.hintMoveSecondary = secondary
        invalidate()
    }

    fun clearHint() {
        this.hintMove = null
        this.hintMoveSecondary = emptyList()
        hintGhostSquare = -1
        hintGhostPiece = Piece.NONE.code
        invalidate()
    }

    fun setFlipped(flipped: Boolean) {
        this.flipped = flipped
        invalidate()
    }

    fun isFlipped() = flipped

    fun setShowCoordinates(show: Boolean) {
        this.showCoordinates = show
        invalidate()
    }

    fun setShowWinRateBar(show: Boolean) {
        this.showWinRateBar = show
        invalidate()
    }

    fun setAnimationEnabled(enabled: Boolean) {
        this.animationEnabled = enabled
    }

    fun setCheckedKing(sq: Int) {
        this.checkedKingSq = sq
        invalidate()
    }

    /** 清除选格 */
    fun clearSelection() {
        this.selectedSquare = -1
        this.legalMovesFromSelected = emptyList()
        syncSelectionAnimator()
        invalidate()
    }

    /**
     * 直接指定高亮哪一格, 传 -1 取消.
     *
     * 编辑模式用: 摆子时"选中"不走合法着法那一套 (局面可能还缺将、多子,
     * 根本算不出合法着法), 只需要把用户点的那格标出来.
     */
    fun setSelectedSquare(sq: Int) {
        this.selectedSquare = sq
        this.legalMovesFromSelected = emptyList()
        syncSelectionAnimator()
        invalidate()
    }

    /**
     * 选中时才让呼吸动画跑起来.
     *
     * 一个无限循环的 ValueAnimator 会一直触发 invalidate, 没选中棋子时就是
     * 纯耗电 —— 对局界面常年停在那儿, 这点开销不能白花.
     */
    private fun syncSelectionAnimator() {
        val shouldRun = selectedSquare != -1
        if (shouldRun && selectionAnimator == null) {
            selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    selectionPulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!shouldRun) {
            selectionAnimator?.cancel()
            selectionAnimator = null
            selectionPulse = 0f
        }
    }

    // ====== 触摸事件 ======

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val sq = pixelToSquare(event.x, event.y)
                if (sq != -1) {
                    val runnable = Runnable { onLongPressListener?.invoke(sq) }
                    longPressRunnable = runnable
                    handler.postDelayed(runnable, LONG_PRESS_MS)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > LONG_PRESS_SLOP_SQ) cancelPendingLongPress()
            }
            MotionEvent.ACTION_UP -> {
                cancelPendingLongPress()
                performClick()
                handleTap(pixelToSquare(event.x, event.y))
                return true
            }
            MotionEvent.ACTION_CANCEL -> cancelPendingLongPress()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 取消待触发的长按. 按下点若在棋盘外, runnable 为 null —— 不能无条件解引用 */
    private fun cancelPendingLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    override fun onDetachedFromWindow() {
        selectionAnimator?.cancel()
        selectionAnimator = null
        fxAnimator?.cancel()
        fxAnimator = null
        super.onDetachedFromWindow()
        cancelPendingLongPress()
        animationAnimator?.cancel()
        animationAnimator = null
    }

    /** 标出可疑格子, 传空集合即清除 */
    fun setSuspectSquares(squares: Collection<Int>) {
        suspectSquares = squares.toSet()
        invalidate()
    }

    /**
     * 视觉通告: 哪些格子上的子正在被对方下一步瞄上.
     *
     * 这里两个集合都按"格子上**子**的颜色"分类, 不按"我方/敌方"分类:
     * 颜色 = 警示颜色, 红方子被瞄上 -> 红光, 黑方子被瞄上 -> 绿光.
     * 这样翻转棋盘时颜色和棋子本身仍对得上.
     */
    fun setThreats(redSquares: Collection<Int>, blackSquares: Collection<Int>) {
        redUnderThreat = redSquares.toSet()
        blackUnderThreat = blackSquares.toSet()
        invalidate()
    }

    private fun handleTap(sq: Int) {
        if (editMode) {
            if (sq != -1) onCellTapListener?.invoke(sq)
            return
        }
        if (sq == -1) {
            // 点击空白处
            clearSelection()
            return
        }
        val piece = position.pieceAt(sq)

        if (selectedSquare == -1) {
            // 没有选格,尝试选本方棋子
            if (piece != Piece.NONE.code && Piece.fromCode(piece).color == position.sideToMove) {
                selectedSquare = sq
                syncSelectionAnimator()
                val moves = mutableListOf<Move>()
                com.zhongguoxiangqi.game.Rules.generateAllLegal(position, moves)
                legalMovesFromSelected = moves.filter { it.from == sq }
                onSelectedListener?.invoke(sq, legalMovesFromSelected)
                invalidate()
            }
        } else {
            // 已选格
            if (sq == selectedSquare) {
                clearSelection()
                return
            }
            // 是本方另一棋子?切换选格
            if (piece != Piece.NONE.code && Piece.fromCode(piece).color == position.sideToMove) {
                selectedSquare = sq
                syncSelectionAnimator()
                val moves = mutableListOf<Move>()
                com.zhongguoxiangqi.game.Rules.generateAllLegal(position, moves)
                legalMovesFromSelected = moves.filter { it.from == sq }
                onSelectedListener?.invoke(sq, legalMovesFromSelected)
                invalidate()
                return
            }
            // 尝试走子
            val move = legalMovesFromSelected.firstOrNull { it.to == sq }
            if (move != null) {
                onMoveListener?.invoke(move)
                clearSelection()
                return
            }
            // 无效点击,清除选格
            clearSelection()
        }
    }

    // ====== 坐标转换 ======

    /**
     * 像素坐标 -> 棋盘格(-1 表示无效).
     *
     * 棋子画在网格交点上, 所以要取"最近的交点"(四舍五入); 用取整会让
     * 命中区整体偏移半格. 允许半格以内的误差, 超出则视为点在棋盘外.
     */
    private fun pixelToSquare(x: Float, y: Float): Int {
        if (cellWidth <= 0f || cellHeight <= 0f) return -1
        val fc = (x - boardLeft) / cellWidth
        val fr = (y - boardTop) / cellHeight
        val col = Math.round(fc)
        val row = Math.round(fr)
        if (col !in 0 until Square.COLS || row !in 0 until Square.ROWS) return -1
        // 命中容差: 距交点超过 0.5 格判为无效点击
        if (kotlin.math.abs(fc - col) > 0.5f || kotlin.math.abs(fr - row) > 0.5f) return -1
        // 翻转仅在这里做一次 —— 像素坐标不再另行翻转, 否则两次翻转相互抵消
        return if (flipped) {
            Square.make(Square.ROWS - 1 - row, Square.COLS - 1 - col)
        } else {
            Square.make(row, col)
        }
    }

    /** 棋盘格 -> 中心像素坐标 */
    private fun squareToCenter(sq: Int): PointF {
        val row = Square.row(sq)
        val col = Square.col(sq)
        val (r, c) = if (flipped) (Square.ROWS - 1 - row) to (Square.COLS - 1 - col) else row to col
        val cx = boardLeft + c * cellWidth
        val cy = boardTop + r * cellHeight
        return PointF(cx, cy)
    }

    /** 棋盘格 -> 角点(左上) */
    private fun squareToTopLeft(sq: Int): PointF {
        val row = Square.row(sq)
        val col = Square.col(sq)
        val (r, c) = if (flipped) (Square.ROWS - 1 - row) to (Square.COLS - 1 - col) else row to col
        return PointF(boardLeft + c * cellWidth - pieceRadius, boardTop + r * cellHeight - pieceRadius)
    }

    // ====== 尺寸 ======

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateDimensions()
    }

    private fun calculateDimensions() {
        if (width <= 0 || height <= 0) return

        // 网格是 8 格宽 × 9 格高(交点 9×10). 棋子画在最外圈交点上,
        // 会向外凸出一个半径,所以四周必须额外留出 MARGIN_CELLS 格,
        // 否则边线上的车/兵会被裁掉.
        val cell = min(
            width / (Square.COLS - 1 + MARGIN_CELLS),
            height / (Square.ROWS - 1 + MARGIN_CELLS)
        )
        // 正方形格子,棋盘看起来才正
        cellWidth = cell
        cellHeight = cell

        val bw = cell * (Square.COLS - 1)
        val bh = cell * (Square.ROWS - 1)
        boardLeft = (width - bw) / 2f
        boardTop = (height - bh) / 2f
        boardRight = boardLeft + bw
        boardBottom = boardTop + bh
        pieceRadius = cell * 0.42f

        coordinatePaint.textSize = pieceRadius * 0.65f
        riverTextPaint.textSize = pieceRadius * 1.4f
        applyPieceTextSize()

        rebuildShaders()
    }

    /**
     * 渐变只依赖尺寸,在尺寸变化时构建一次即可.
     * 棋子渐变以棋子中心为原点,绘制时靠 canvas 平移定位.
     */
    private fun rebuildShaders() {
        if (pieceRadius <= 0f) return

        // 木纹要一直铺到坐标标签外面 —— 边框收在 pieceRadius*1.2(0.5 格)时,
        // 标签只能画在木纹之外的页面底色上, 看着像掉出棋盘.
        val wood = cellWidth * WOOD_MARGIN_CELLS
        boardRect.set(
            boardLeft - wood, boardTop - wood,
            boardRight + wood, boardBottom + wood
        )
        boardBgPaint.shader = LinearGradient(
            boardRect.left, boardRect.top, boardRect.right, boardRect.bottom,
            intArrayOf(
                themeColor(R.color.board_grad_1),
                themeColor(R.color.board_grad_2),
                themeColor(R.color.board_grad_3),
                themeColor(R.color.board_grad_4)
            ),
            floatArrayOf(0f, 0.4f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )

        pieceInnerPaint.shader = RadialGradient(
            -pieceRadius * 0.3f, -pieceRadius * 0.3f, pieceRadius * 0.7f,
            intArrayOf(
                themeColor(R.color.piece_grad_1),
                themeColor(R.color.piece_grad_2),
                themeColor(R.color.piece_grad_3)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    // ====== 绘制 ======

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBoard(canvas)
        drawGrid(canvas)
        drawPalace(canvas)
        drawRiver(canvas)
        if (showCoordinates) drawCoordinates(canvas)
        drawThreats(canvas)            // 威胁光晕: 画在棋子下, 看起来像棋子在"发光"
        drawHighlights(canvas)
        drawPieces(canvas)
        drawSelectionRing(canvas)      // 选中环: 必须压在棋子之上, 见下方说明
        if (animatingMove != null) drawAnimatingPiece(canvas)
        drawHintOverlay(canvas)        // 虚影 + 箭头: 画在棋子之上, 永远可见
        drawCaptureFx(canvas)          // 吃子特效压在最上层
    }

    /**
     * 吃子特效. 七个兵种七套视觉:
     *
     *   车  十字冲击波 —— 车走直线, 冲击波也沿横竖两条线扫出去
     *   马  螺旋轨迹   —— 对应马走"日"字的折返感
     *   炮  爆炸       —— 双层冲击环 + 四散火星, 最响的一个
     *   兵卒 小碎片     —— 最轻, 一闪而过, 免得频繁吃兵时满屏乱飞
     *   士仕 菱形内爆   —— 士守在九宫里, 用向内收的菱形
     *   象相 双环扩散   —— 象走田字, 两个同心方环
     *   将帅 金光爆发   —— 终局, 光柱 + 全盘泛光
     *
     * 所有几何量都按 [pieceRadius] 缩放, 换屏幕尺寸不会走样.
     */
    private fun drawCaptureFx(canvas: Canvas) {
        if (fxSquare == -1) return
        val c = squareToCenter(fxSquare)
        val t = fxProgress
        val fade = ((1f - t) * 255).toInt().coerceIn(0, 255)
        val piece = Piece.fromCode(fxPiece)
        val main = if (piece.isRed) themeColor(R.color.piece_red) else themeColor(R.color.piece_black)
        val spark = themeColor(R.color.fx_spark)

        when (piece) {
            Piece.R_ROOK, Piece.B_ROOK -> {
                // 十字冲击波: 横竖两道亮条向外扫
                fxPaint.style = Paint.Style.STROKE
                fxPaint.strokeCap = Paint.Cap.ROUND
                fxPaint.color = spark
                fxPaint.alpha = fade
                fxPaint.strokeWidth = pieceRadius * 0.42f * (1f - t)
                val reach = pieceRadius * (1f + 5f * t)
                canvas.drawLine(c.x - reach, c.y, c.x + reach, c.y, fxPaint)
                canvas.drawLine(c.x, c.y - reach, c.x, c.y + reach, fxPaint)
                fxPaint.color = main
                fxPaint.alpha = (fade * 0.7f).toInt()
                fxPaint.strokeWidth = pieceRadius * 0.16f * (1f - t)
                canvas.drawCircle(c.x, c.y, pieceRadius * (1f + 2.4f * t), fxPaint)
            }

            Piece.R_HORSE, Piece.B_HORSE -> {
                // 螺旋: 碎片绕着中心转出去
                fxPaint.style = Paint.Style.FILL
                fxShards.forEachIndexed { i, s ->
                    val ang = kotlin.math.atan2(s.vy, s.vx) + t * 3.2f
                    val r = pieceRadius * (0.3f + 2.6f * t) * (0.6f + s.size * 3f)
                    val px = c.x + kotlin.math.cos(ang) * r
                    val py = c.y + kotlin.math.sin(ang) * r
                    fxPaint.color = if (i % 2 == 0) spark else main
                    fxPaint.alpha = fade
                    canvas.drawCircle(px, py, pieceRadius * s.size * (1f - t * 0.6f), fxPaint)
                }
            }

            Piece.R_CANNON, Piece.B_CANNON -> {
                // 爆炸: 内外两层冲击环 + 四散火星
                fxPaint.style = Paint.Style.STROKE
                fxPaint.color = spark
                fxPaint.alpha = fade
                fxPaint.strokeWidth = pieceRadius * 0.30f * (1f - t)
                canvas.drawCircle(c.x, c.y, pieceRadius * (0.6f + 3.4f * t), fxPaint)
                fxPaint.strokeWidth = pieceRadius * 0.16f * (1f - t)
                fxPaint.alpha = (fade * 0.6f).toInt()
                canvas.drawCircle(c.x, c.y, pieceRadius * (0.4f + 2.0f * t), fxPaint)
                fxPaint.style = Paint.Style.FILL
                drawShards(canvas, c.x, c.y, t, fade, speedScale = 3.4f)
            }

            Piece.R_PAWN, Piece.B_PAWN -> {
                // 最轻的一档: 只有几粒碎片
                fxPaint.style = Paint.Style.FILL
                drawShards(canvas, c.x, c.y, t, fade, speedScale = 1.8f)
            }

            Piece.R_ADVISOR, Piece.B_ADVISOR -> {
                // 菱形内爆: 从外向内收
                fxPaint.style = Paint.Style.STROKE
                fxPaint.strokeWidth = pieceRadius * 0.20f
                fxPaint.color = spark
                fxPaint.alpha = fade
                val r = pieceRadius * (2.2f - 1.8f * t)
                fxPath.reset()
                fxPath.moveTo(c.x, c.y - r)
                fxPath.lineTo(c.x + r, c.y)
                fxPath.lineTo(c.x, c.y + r)
                fxPath.lineTo(c.x - r, c.y)
                fxPath.close()
                canvas.drawPath(fxPath, fxPaint)
            }

            Piece.R_ELEPHANT, Piece.B_ELEPHANT -> {
                // 双方环扩散, 对应"田"字
                fxPaint.style = Paint.Style.STROKE
                fxPaint.strokeWidth = pieceRadius * 0.18f * (1f - t)
                for (k in 0..1) {
                    val phase = (t + k * 0.35f).coerceAtMost(1f)
                    fxPaint.color = if (k == 0) spark else main
                    fxPaint.alpha = ((1f - phase) * 255).toInt().coerceIn(0, 255)
                    val half = pieceRadius * (0.7f + 2.6f * phase)
                    canvas.drawRect(c.x - half, c.y - half, c.x + half, c.y + half, fxPaint)
                }
            }

            Piece.R_KING, Piece.B_KING -> {
                // 终局: 全盘泛光 + 放射光柱 + 大量碎片
                fxPaint.style = Paint.Style.FILL
                fxPaint.color = spark
                fxPaint.alpha = (fade * 0.22f).toInt()
                canvas.drawRect(boardRect, fxPaint)

                fxPaint.style = Paint.Style.STROKE
                fxPaint.strokeCap = Paint.Cap.ROUND
                fxPaint.color = spark
                fxPaint.alpha = fade
                val rays = 12
                val inner = pieceRadius * (0.5f + 1.2f * t)
                val outer = pieceRadius * (1.2f + 6f * t)
                fxPaint.strokeWidth = pieceRadius * 0.14f * (1f - t)
                for (i in 0 until rays) {
                    val a = (i.toFloat() / rays) * (Math.PI * 2).toFloat() + t * 0.6f
                    val ca = kotlin.math.cos(a)
                    val sa = kotlin.math.sin(a)
                    canvas.drawLine(
                        c.x + ca * inner, c.y + sa * inner,
                        c.x + ca * outer, c.y + sa * outer, fxPaint
                    )
                }
                fxPaint.style = Paint.Style.FILL
                drawShards(canvas, c.x, c.y, t, fade, speedScale = 4.0f)
            }

            else -> {
                fxPaint.style = Paint.Style.FILL
                drawShards(canvas, c.x, c.y, t, fade, speedScale = 2.4f)
            }
        }
    }

    /** 碎片四散: 带一点重力, 让它们往下掉而不是平着飞 */
    private fun drawShards(
        canvas: Canvas, cx: Float, cy: Float,
        t: Float, fade: Int, speedScale: Float,
    ) {
        val gravity = 1.6f * t * t
        fxShards.forEach { s ->
            val px = cx + s.vx * pieceRadius * speedScale * t
            val py = cy + s.vy * pieceRadius * speedScale * t + pieceRadius * gravity
            fxPaint.color = s.color
            fxPaint.alpha = fade
            val save = canvas.save()
            canvas.rotate(s.spin * t, px, py)
            val half = pieceRadius * s.size * (1f - t * 0.5f)
            canvas.drawRect(px - half, py - half, px + half, py + half, fxPaint)
            canvas.restoreToCount(save)
        }
    }

    /**
     * 选中棋子的标识.
     *
     * 以前这个环是在 [drawHighlights] 里画的, 也就是**在棋子之前** —— 画完
     * 立刻被棋子盖住, 用户点了子看不出有没有点中. 挪到 drawPieces 之后, 并且
     * 半径比棋子大一圈, 保证露在外面.
     *
     * 做成一圈会呼吸的亮环 + 外层柔光: 静态描边在木色棋盘上仍然容易被看漏,
     * 动起来才抓得住眼睛.
     */
    private fun drawSelectionRing(canvas: Canvas) {
        if (selectedSquare == -1) return
        val center = squareToCenter(selectedSquare)
        val cx = center.x
        val cy = center.y
        val phase = selectionPulse                      // 0..1 往复
        val ringR = pieceRadius * (1.06f + 0.10f * phase)

        // 外层柔光, 让环在木色底上"浮"出来
        selectionGlowPaint.alpha = (70 * (1f - phase * 0.5f)).toInt().coerceIn(0, 255)
        selectionGlowPaint.strokeWidth = pieceRadius * 0.30f
        canvas.drawCircle(cx, cy, ringR, selectionGlowPaint)

        // 实环
        selectionRingPaint.strokeWidth = pieceRadius * 0.13f
        canvas.drawCircle(cx, cy, ringR, selectionRingPaint)

        // 四角小标记: 即使用户对颜色不敏感, 这四个角也能说明"这一个被选中了"
        val corner = pieceRadius * 1.34f
        val len = pieceRadius * 0.34f
        selectionRingPaint.strokeWidth = pieceRadius * 0.11f
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                val x = cx + sx * corner
                val y = cy + sy * corner
                canvas.drawLine(x, y, x - sx * len, y, selectionRingPaint)
                canvas.drawLine(x, y, x, y - sy * len, selectionRingPaint)
            }
        }
    }

    private fun drawBoard(canvas: Canvas) {
        // 木纹背景
        canvas.drawRoundRect(boardRect, 12f, 12f, boardBgPaint)

        // 木纹细节 (细线)
        for (i in 0..30) {
            val y = boardRect.top + (boardRect.height() / 30f) * i
            val wave = kotlin.math.sin(i * 0.7f) * 4f
            canvas.drawLine(boardRect.left + wave, y, boardRect.right - wave, y, boardWoodPaint)
        }

        // 边框
        canvas.drawRoundRect(boardRect, 12f, 12f, boardBorderPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        // 横线 (10 条)
        for (i in 0 until Square.ROWS) {
            val y = boardTop + i * cellHeight
            if (i == 0 || i == Square.ROWS - 1) {
                canvas.drawLine(boardLeft, y, boardRight, y, boardLinePaint)
            } else {
                // 楚河汉界处的横线不断开
                canvas.drawLine(boardLeft, y, boardRight, y, boardLinePaint)
            }
        }

        // 纵线 (9 条)
        for (i in 0 until Square.COLS) {
            val x = boardLeft + i * cellWidth
            // 边线连续
            if (i == 0 || i == Square.COLS - 1) {
                canvas.drawLine(x, boardTop, x, boardBottom, boardLinePaint)
            } else {
                // 中间断开(楚河汉界处)
                canvas.drawLine(x, boardTop, x, boardTop + 4 * cellHeight, boardLinePaint)
                canvas.drawLine(x, boardTop + 5 * cellHeight, x, boardBottom, boardLinePaint)
            }
        }
    }

    private fun drawPalace(canvas: Canvas) {
        // 九宫格: 上下两个 X
        // 上方: row 0..2, col 3..5
        drawPalaceX(canvas, boardTop, boardLeft + 3 * cellWidth, boardLeft + 5 * cellWidth, 2 * cellHeight)
        // 下方: row 7..9, col 3..5
        drawPalaceX(canvas, boardTop + 7 * cellHeight, boardLeft + 3 * cellWidth, boardLeft + 5 * cellWidth, 2 * cellHeight)
    }

    private fun drawPalaceX(canvas: Canvas, topY: Float, leftX: Float, rightX: Float, h: Float) {
        canvas.drawLine(leftX, topY, rightX, topY + h, palaceLinePaint)
        canvas.drawLine(rightX, topY, leftX, topY + h, palaceLinePaint)
    }

    private fun drawRiver(canvas: Canvas) {
        val midY1 = boardTop + 4 * cellHeight
        val midY2 = boardTop + 5 * cellHeight
        val centerY = (midY1 + midY2) / 2
        // 河界题字随界面语言走: 英文界面上一行汉字读不出来, 也就传达不了
        // "这里是楚汉的分界"这层意思, 那正是它存在的全部理由.
        // 字号也要跟着降 —— 英文那句比四个汉字长得多, 沿用汉字字号会顶到
        // 两侧的棋子上.
        val text = context.getString(R.string.board_river_text)
        val isLatin = text.none { it.code in 0x4E00..0x9FFF }
        riverTextPaint.textSize = pieceRadius * if (isLatin) 0.72f else 1.4f
        canvas.drawText(text, (boardLeft + boardRight) / 2, centerY + riverTextPaint.textSize / 3, riverTextPaint)
    }

    private fun drawCoordinates(canvas: Canvas) {
        // 上下/左右的列号和行号.
        // 列号用阿拉伯数字, 每一方都从**自己的右手边**数起 —— 真棋盘就是这么标的,
        // 所以上下两排互为镜像. 关键是这一号数跟着**棋盘上的那一列**走, 不跟着
        // 屏幕位置走: setFlipped 是把棋盘转 180°(行列都反), 转完红的"一路"从屏幕
        // 右边跑到了左边, 号数必须跟着过去, 否则翻一次棋盘"炮二平五"就指错列了.
        // 行号从屏幕底部数起: 底行 = 1.
        coordinatePaint.textSize = pieceRadius * 0.62f
        val fm = coordinatePaint.fontMetrics
        // 基线偏移: 把文字的视觉中心落在给定的 y 上
        val baseline = -(fm.ascent + fm.descent) / 2f
        // 标签放进"棋子外沿 → 木纹边框"这条空带的正中.
        // 以前是按 pieceRadius 的倍数硬编码落点(0.23~0.57 格), 正好压在最外圈
        // 棋子(半径 0.42 格)身上 —— 上下两排列号被车马象整个盖住, 行号"10"也有
        // 一半钻到车底下, 等于没标. 小程序那边(59bf6b4)已经按同样思路修过.
        val bandCenter = (pieceRadius + cellWidth * WOOD_MARGIN_CELLS) / 2f

        coordinatePaint.textAlign = Paint.Align.CENTER
        val yTop = boardTop - bandCenter + baseline
        val yBottom = boardBottom + bandCenter + baseline
        for (c in 0 until Square.COLS) {
            val x = boardLeft + c * cellWidth
            canvas.drawText(fileLabel(c, flipped, top = true), x, yTop, coordinatePaint)
            canvas.drawText(fileLabel(c, flipped, top = false), x, yBottom, coordinatePaint)
        }

        // 行号贴着棋子外沿往外排(左侧右对齐, 右侧左对齐)而不是"整体居中":
        // "10" 是两位数, 居中放会有一位压回棋子上, 对齐方式才能保证不管几位数
        // 都从棋子边上起步.
        val gap = pieceRadius * 0.12f
        for (r in 0 until Square.ROWS) {
            val y = boardTop + r * cellHeight + baseline
            val rowNum = (Square.ROWS - r).toString()
            coordinatePaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(rowNum, boardLeft - pieceRadius - gap, y, coordinatePaint)
            coordinatePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(rowNum, boardRight + pieceRadius + gap, y, coordinatePaint)
        }
        coordinatePaint.textAlign = Paint.Align.CENTER
    }

    /**
     * 威胁光晕: 在被对方瞄上的棋子下画半透明光晕.
     * - 红方子被瞄 -> 红色光晕(我方子受威胁, 警示)
     * - 黑方子被瞄 -> 绿色光晕(对方子被瞄, 提示可吃)
     * 画在棋子之下, 看起来像棋子在发光, 不会盖住棋子上的字.
     */
    private fun drawThreats(canvas: Canvas) {
        for (sq in redUnderThreat) {
            drawThreatGlow(canvas, sq, dangerRedPaint)
        }
        for (sq in blackUnderThreat) {
            drawThreatGlow(canvas, sq, dangerGreenPaint)
        }
    }

    private fun drawThreatGlow(canvas: Canvas, sq: Int, paint: Paint) {
        val center = squareToCenter(sq)
        canvas.drawCircle(center.x, center.y, pieceRadius * 1.05f, paint)
    }

    /**
     * 提示走法预览: 起点已经在原位, 终点画"这枚子落到这里"的虚影,
     * 起点到终点画一段绿色箭头.
     * 必须在 drawPieces 之后调用, 箭头与虚影都要压过真实棋子.
     */
    private fun drawHintOverlay(canvas: Canvas) {
        val mv = hintMove ?: return
        // 虚影: 目标格上的棋子(从 setHint 时记下)
        if (hintGhostSquare >= 0 && hintGhostPiece != Piece.NONE.code) {
            val center = squareToCenter(hintGhostSquare)
            val save = canvas.saveLayerAlpha(null, hintGhostPaint.alpha)
            drawPieceAt(canvas, center.x, center.y, hintGhostPiece)
            canvas.restoreToCount(save)
        }
        // 箭头: 从起点指向终点
        val fromCenter = squareToCenter(mv.from)
        val toCenter = squareToCenter(mv.to)
        drawArrow(canvas, fromCenter, toCenter, pieceRadius)
    }

    /** 画一段带三角形头部的箭头, 起点/终点坐标来自 UI 空间 */
    private fun drawArrow(canvas: Canvas, from: PointF, to: PointF, pieceRadius: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        // 箭头在两端各留出一段, 不要画到棋子内部, 也不要画过终点.
        // 线宽/头长都按 pieceRadius 走: 原来杆子写死 8f 像素, 高分屏上细得
        // 像根发丝, 三角头也就跟着显不出来.
        hintArrowPaint.strokeWidth = pieceRadius * 0.17f
        val headLen = pieceRadius * 0.72f
        val tailGap = pieceRadius * 0.4f
        val ux = dx / len
        val uy = dy / len
        val startX = from.x + ux * tailGap
        val startY = from.y + uy * tailGap
        val endX = to.x - ux * headLen
        val endY = to.y - uy * headLen
        if (endX < startX && kotlin.math.abs(dx) < 0.001f) return
        canvas.drawLine(startX, startY, endX, endY, hintArrowPaint)
        // 三角头部, 顶点朝 to
        val angle = kotlin.math.atan2(dy, dx)
        val headAngle = Math.toRadians(28.0).toFloat()
        val p1x = to.x - headLen * kotlin.math.cos(angle - headAngle)
        val p1y = to.y - headLen * kotlin.math.sin(angle - headAngle)
        val p2x = to.x - headLen * kotlin.math.cos(angle + headAngle)
        val p2y = to.y - headLen * kotlin.math.sin(angle + headAngle)
        // 复用成员 Path: 这个方法是从 onDraw 调的, 提示箭头在时每帧都会走一遍.
        // 每帧 new 一个 Path 就是每帧一次分配, 动画期间会持续制造 GC 压力.
        arrowHeadPath.reset()
        arrowHeadPath.moveTo(to.x, to.y)
        arrowHeadPath.lineTo(p1x, p1y)
        arrowHeadPath.lineTo(p2x, p2y)
        arrowHeadPath.close()
        canvas.drawPath(arrowHeadPath, hintArrowHeadPaint)
    }

    private fun drawHighlights(canvas: Canvas) {
        // 最后走法高亮
        lastMove?.let { m ->
            drawSquareHighlight(canvas, m.from, highlightLastMovePaint, true)
            drawSquareHighlight(canvas, m.to, highlightLastMovePaint, true)
        }

        // 选格高亮
        if (selectedSquare != -1) {
            drawSquareHighlight(canvas, selectedSquare, highlightSelectPaint, true)
        }

        // 可走步高亮
        for (m in legalMovesFromSelected) {
            val targetPiece = position.pieceAt(m.to)
            if (targetPiece != Piece.NONE.code) {
                drawCaptureMarker(canvas, m.to)
            } else {
                drawMoveMarker(canvas, m.to)
            }
        }

        // 提示高亮: 起点上已经有真实棋子, 终点上会有虚影棋子, 所以两边都不画环
        // (环会被棋子/虚影盖住, 还不如不画). 真正的提示效果由 hintOverlay 负责.
        for (m in hintMoveSecondary) {
            drawSquareHighlight(canvas, m.from, highlightHintSecondaryPaint, true)
            drawSquareHighlight(canvas, m.to, highlightHintSecondaryPaint, true)
        }

        // 将军高亮
        if (checkedKingSq != -1) {
            drawSquareHighlight(canvas, checkedKingSq, checkMarkerPaint, true)
        }

        // 识别存疑的格子 —— 画在最上层, 让视线直接落到最可能错的地方
        for (sq in suspectSquares) {
            drawSquareHighlight(canvas, sq, suspectPaint, true)
        }
    }

    private fun drawSquareHighlight(canvas: Canvas, sq: Int, paint: Paint, asStroke: Boolean) {
        val center = squareToCenter(sq)
        canvas.drawCircle(center.x, center.y, pieceRadius * 0.95f, paint)
    }

    private fun drawMoveMarker(canvas: Canvas, sq: Int) {
        val center = squareToCenter(sq)
        val radius = pieceRadius * 0.18f
        canvas.drawCircle(center.x, center.y, radius * 1.8f, highlightMoveHaloPaint)
        canvas.drawCircle(center.x, center.y, radius, highlightMovePaint)
    }

    private fun drawCaptureMarker(canvas: Canvas, sq: Int) {
        val center = squareToCenter(sq)
        val radius = pieceRadius * 0.95f
        // 四角标记
        val corners = floatArrayOf(0.4f, 0.6f)
        for (frac in corners) {
            for (sign in intArrayOf(-1, 1)) {
                val off = radius * frac * sign
                canvas.drawCircle(center.x + off, center.y + off, radius * 0.12f, highlightCapturePaint)
            }
        }
    }

    private fun drawPieces(canvas: Canvas) {
        // 动画中的棋子由 drawAnimatingPiece 画在途中,终点格先空着
        val skipSquare = animatingMove?.to ?: -1
        for (sq in 0 until Square.COUNT) {
            if (sq == skipSquare) continue
            val pieceCode = position.pieceAt(sq)
            if (pieceCode == Piece.NONE.code) continue
            drawPiece(canvas, sq, pieceCode)
        }
    }

    private fun drawAnimatingPiece(canvas: Canvas) {
        if (animatingMove == null) return
        val cx = animatingFromCenter.x + (animatingToCenter.x - animatingFromCenter.x) * animationProgress
        val cy = animatingFromCenter.y + (animatingToCenter.y - animatingFromCenter.y) * animationProgress
        drawPieceAt(canvas, cx, cy, animatingPiece)
    }

    private fun drawPiece(canvas: Canvas, sq: Int, pieceCode: Int) {
        val center = squareToCenter(sq)
        drawPieceAt(canvas, center.x, center.y, pieceCode)
    }

    /**
     * 画一枚棋子: 实心色底 + 白字.
     *
     * 旧版是"米白木纹面 + 彩色字": 红子 #B4302A 的字压在 #F5E9CE 的面上只有
     * 3.2:1 的对比度, 低于正文线, 而棋子上的字恰恰是全 App 最需要一眼读准的
     * 东西; 更糟的是红黑两方**整枚子长得一样**, 只有中间那个字的颜色不同,
     * 扫一眼盘面根本分不出敌我。
     *
     * 现在整枚子按方着色, 字统一用白色 —— 红子 5.0:1、黑子 15.6:1, 并且
     * "这是哪一方的子"在余光里就能判断, 不必逐个去读字。
     *
     * 分层(由下到上): 投影 → 描边环 → 实心面 → 高光 → 内刻环 → 字.
     * 内刻环替代了旧版那几条木纹横线: 横线画在实心底上像划痕, 而一圈内环
     * 正是实物棋子被车出来的那道边, 也顺便把字框住。
     */
    private fun drawPieceAt(canvas: Canvas, cx: Float, cy: Float, pieceCode: Int) {
        val piece = Piece.fromCode(pieceCode)
        if (piece == Piece.NONE) return
        val isRed = piece.isRed
        val radius = pieceRadius

        // 以棋子中心为原点绘制,渐变着色器得以复用
        val save = canvas.save()
        canvas.translate(cx, cy)

        // 阴影
        canvas.drawCircle(3f, 4f, radius * 1.05f, pieceShadowPaint)

        // 描边环: 比面色更深一档, 让子的轮廓在木色上咬得住
        pieceBorderPaint.color =
            if (isRed) themeColor(R.color.piece_red_edge) else themeColor(R.color.piece_black_edge)
        canvas.drawCircle(0f, 0f, radius, pieceBorderPaint)

        // 实心面
        pieceFacePaint.color = if (isRed) redPieceColor else blackPieceColor
        canvas.drawCircle(0f, 0f, radius * 0.93f, pieceFacePaint)

        // 高光: 左上偏亮的一层白色渐变, 做出圆润感(shader 已带 alpha)
        canvas.drawCircle(0f, 0f, radius * 0.93f, pieceInnerPaint)

        // 内刻环
        pieceWoodLinePaint.strokeWidth = radius * 0.05f
        canvas.drawCircle(0f, 0f, radius * 0.78f, pieceWoodLinePaint)

        // 字符. 红黑共用白字, 所以两支画笔的颜色是一样的 ——
        // 仍然分开两支是因为字体大小要按字符集各自调(拉丁字母比汉字窄).
        val charPaint = if (isRed) pieceRedPaint else pieceBlackPaint
        charPaint.color = pieceTextColor
        val text = pieceLabel(piece, isRed)
        val fm = charPaint.fontMetrics
        canvas.drawText(text, 0f, -(fm.ascent + fm.descent) / 2, charPaint)

        canvas.restoreToCount(save)
    }

    /**
     * 按当前字符集定棋子的字号.
     *
     * 汉字是方的, 1.3 倍半径正好填满内刻环; 拉丁字母是窄的, 同样字号只占掉
     * 中间一条, 圆面上下各空一圈, 看起来像字掉进了井里 —— 所以放大到 1.55 倍
     * 并让它按字母的实际高度居中。
     */
    private fun applyPieceTextSize() {
        val scale = if (pieceSet == PieceSet.LATIN) 1.55f else 1.3f
        pieceRedPaint.textSize = pieceRadius * scale
        pieceBlackPaint.textSize = pieceRadius * scale
    }

    /**
     * 棋子上印什么.
     *
     * 汉字是象棋的本来面目, 但对读不了汉字的人来说, 七个兵种在盘上就是七团
     * 一样的墨 —— 界面翻成英文并不能让他认出哪个是"炮". 所以提供拉丁字母
     * 一档, 用的是 FEN 里那套标准缩写 (K/A/B/N/R/C/P), 与英文记谱同源,
     * 记谱里读到 "C2.5" 时能直接对上盘面那枚 C。
     *
     * 字母两方都用大写: 旧的 FEN 约定靠大小写区分红黑, 而这里颜色已经由
     * 整枚子的底色说清楚了, 再用小写只会让黑方的字看起来矮一截。
     */
    private fun pieceLabel(piece: Piece, isRed: Boolean): String = when (pieceSet) {
        PieceSet.LATIN -> piece.fen.uppercaseChar().toString()
        else -> if (isRed) piece.redChar else piece.blackChar
    }

    // ====== 工具 ======

    companion object {
        /** 棋盘四周为棋子半径和坐标文字预留的空间, 单位是"格" */
        // 上下底标签字号比之前大了一档(0.55 -> 0.65 个 pieceRadius),
        // 还要给行号两边让出位置, 1.35 已经压到字会被棋子盖住. 加到 1.7.
        private const val MARGIN_CELLS = 1.7f
        /**
         * 木纹边框相对最外圈交点再往外铺多少格.
         *
         * 必须 < MARGIN_CELLS/2 (= 0.85), 否则边框描边会被 View 边缘切掉;
         * 又必须 > 棋子半径 0.42, 坐标标签才有地方落. 0.78 两头都留了余量.
         */
        private const val WOOD_MARGIN_CELLS = 0.78f

        /**
         * 棋盘上下两排该标的列号.
         *
         * @param screenCol 屏幕从左往右第几列 (0..8)
         * @param flipped 棋盘是否已转 180°(黑在下)
         * @param top true=上边那排, false=下边那排
         *
         * 每一方从**自己的右手边**数起: 红的"一路"永远是棋盘 col 8, 黑的"1路"
         * 永远是 col 0 —— 认的是棋盘上的列, 不是屏幕上的位置. 翻面后红那一路
         * 从屏幕右边跑到左边, 号数要跟着走, 否则"炮二平五"会指到错的列.
         */
        internal fun fileLabel(screenCol: Int, flipped: Boolean, top: Boolean): String {
            val physCol = if (flipped) Square.COLS - 1 - screenCol else screenCol
            val redSideOnTop = flipped
            return if (top == redSideOnTop) ('9' - physCol).toString()
            else ('1' + physCol).toString()
        }
        /** 走子动画时长 */
        private const val MOVE_ANIMATION_MS = 220L
        private const val LONG_PRESS_MS = 600L
        /** 超过这个位移(像素平方)就认为是拖动,取消长按 */
        private const val LONG_PRESS_SLOP_SQ = 100f
    }

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }
}
