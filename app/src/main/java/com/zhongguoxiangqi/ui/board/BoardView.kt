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
        color = AndroidColor.parseColor("#3B2412")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val boardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palaceLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#3B2412")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val highlightSelectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#80FFD700")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val highlightMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#8032CD32")
    }
    /** 可走点的外圈光晕: 单独一支笔, 避免改到 highlightMovePaint 的 alpha */
    private val highlightMoveHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#5032CD32")
    }
    private val highlightCapturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#80DC143C")
    }
    private val highlightLastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#80FF8C00")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val highlightHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#804169E1")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val highlightHintSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#609090EE")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    /** 识别置信度低 / 校验不通过的格子 */
    private val suspectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = AndroidColor.parseColor("#FFB300")
    }

    /** 红方子被威胁: 我方子被瞄, 红色光晕警示 */
    private val dangerRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#80DC143C")
    }
    /** 黑方子被威胁: 对方子被瞄, 绿色光晕(可吃) */
    private val dangerGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#8032CD32")
    }

    /** 提示走法的箭头: 深绿, 粗笔 */
    private val hintArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FF2E7D32")
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    /** 提示走法箭头头部的小三角 */
    private val hintArrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FF2E7D32")
        style = Paint.Style.FILL
    }
    /** 提示走法虚影: 棋子位图叠一层 alpha */
    private val hintGhostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 90
    }

    private val checkMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFFF0000")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val coordinatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#6B4423")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val riverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#7B3F00")
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
    private val pieceWoodLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#30A0826D")
        strokeWidth = 0.8f
        style = Paint.Style.STROKE
    }
    private val boardWoodPaint = Paint().apply {
        color = AndroidColor.parseColor("#80A0826D")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val boardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#5D2F0E")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val boardRect = RectF()

    private val redPieceColor = AndroidColor.parseColor("#C8102E")
    private val blackPieceColor = AndroidColor.parseColor("#1A1A1A")

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        pieceShadowPaint.color = AndroidColor.parseColor("#40000000")
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
        invalidate()
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

        coordinatePaint.textSize = pieceRadius * 0.55f
        riverTextPaint.textSize = pieceRadius * 1.4f
        pieceRedPaint.textSize = pieceRadius * 1.3f
        pieceBlackPaint.textSize = pieceRadius * 1.3f

        rebuildShaders()
    }

    /**
     * 渐变只依赖尺寸,在尺寸变化时构建一次即可.
     * 棋子渐变以棋子中心为原点,绘制时靠 canvas 平移定位.
     */
    private fun rebuildShaders() {
        if (pieceRadius <= 0f) return

        boardRect.set(
            boardLeft - pieceRadius * 1.2f, boardTop - pieceRadius * 1.2f,
            boardRight + pieceRadius * 1.2f, boardBottom + pieceRadius * 1.2f
        )
        boardBgPaint.shader = LinearGradient(
            boardRect.left, boardRect.top, boardRect.right, boardRect.bottom,
            intArrayOf(
                AndroidColor.parseColor("#DEB887"),
                AndroidColor.parseColor("#D2A56C"),
                AndroidColor.parseColor("#C8A06A"),
                AndroidColor.parseColor("#B88A52")
            ),
            floatArrayOf(0f, 0.4f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )

        pieceInnerPaint.shader = RadialGradient(
            -pieceRadius * 0.3f, -pieceRadius * 0.3f, pieceRadius * 0.7f,
            intArrayOf(
                AndroidColor.parseColor("#FFF8DC"),
                AndroidColor.parseColor("#F5DEB3"),
                AndroidColor.parseColor("#DEB887")
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
        if (animatingMove != null) drawAnimatingPiece(canvas)
        drawHintOverlay(canvas)        // 虚影 + 箭头: 画在棋子之上, 永远可见
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
        // 文字背景留白
        val text = "楚 河          汉 界"
        canvas.drawText(text, (boardLeft + boardRight) / 2, centerY + riverTextPaint.textSize / 3, riverTextPaint)
    }

    private fun drawCoordinates(canvas: Canvas) {
        // 上下/左右的列号和行号
        for (c in 0 until Square.COLS) {
            val x = boardLeft + c * cellWidth
            val yTop = boardTop - pieceRadius * 0.4f
            val yBottom = boardBottom + pieceRadius * 0.6f
            val colChar = ('a' + (if (flipped) Square.COLS - 1 - c else c))
            canvas.drawText(colChar.toString(), x, yTop, coordinatePaint)
            canvas.drawText(colChar.toString(), x, yBottom, coordinatePaint)
        }
        for (r in 0 until Square.ROWS) {
            val y = boardTop + r * cellHeight
            val xLeft = boardLeft - pieceRadius * 0.5f
            val xRight = boardRight + pieceRadius * 0.5f
            val rowNum = (if (flipped) Square.ROWS - r else r + 1).toString()
            canvas.drawText(rowNum, xLeft, y + coordinatePaint.textSize / 3, coordinatePaint)
            canvas.drawText(rowNum, xRight, y + coordinatePaint.textSize / 3, coordinatePaint)
        }
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
        // 箭头在两端各留出一段, 不要画到棋子内部, 也不要画过终点
        val headLen = pieceRadius * 0.55f
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
        val path = android.graphics.Path().apply {
            moveTo(to.x, to.y); lineTo(p1x, p1y); lineTo(p2x, p2y); close()
        }
        canvas.drawPath(path, hintArrowHeadPaint)
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

        // 棋子外圈
        pieceBorderPaint.color = if (isRed) AndroidColor.parseColor("#7B0A1A") else blackPieceColor
        canvas.drawCircle(0f, 0f, radius, pieceBorderPaint)

        // 棋子内圈(木纹底色)
        canvas.drawCircle(0f, 0f, radius * 0.92f, pieceInnerPaint)

        // 木纹细节
        for (i in -2..2) {
            val y = i * radius * 0.25f
            canvas.drawLine(-radius, y, radius, y, pieceWoodLinePaint)
        }

        // 字符
        val charPaint = if (isRed) pieceRedPaint else pieceBlackPaint
        charPaint.color = if (isRed) redPieceColor else blackPieceColor
        val text = if (isRed) piece.redChar else piece.blackChar
        val fm = charPaint.fontMetrics
        canvas.drawText(text, 0f, -(fm.ascent + fm.descent) / 2, charPaint)

        canvas.restoreToCount(save)
    }

    // ====== 工具 ======

    companion object {
        /** 棋盘四周为棋子半径和坐标文字预留的空间, 单位是"格" */
        private const val MARGIN_CELLS = 1.35f
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