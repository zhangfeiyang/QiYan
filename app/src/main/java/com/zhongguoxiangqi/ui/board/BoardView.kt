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

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // 尝试加载内嵌字体
        try {
            val tf = Typeface.createFromAsset(context.assets, "fonts/chinese_piece.ttf")
            pieceRedPaint.typeface = tf
            pieceBlackPaint.typeface = tf
        } catch (_: Exception) {
            // 字体加载失败,使用系统字体
            val tf = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            pieceRedPaint.typeface = tf
            pieceBlackPaint.typeface = tf
        }
    }

    // ====== 公共 API ======

    fun setPosition(position: Position) {
        this.position = position.copy()
        this.lastMove = null
        this.hintMove = null
        this.hintMoveSecondary = emptyList()
        this.selectedSquare = -1
        this.legalMovesFromSelected = emptyList()
        this.checkedKingSq = -1
        invalidate()
    }

    /** 应用走法(带动画) */
    fun applyMoveWithAnimation(move: Move, captured: Int = -1) {
        if (!animationEnabled) {
            // 不使用动画时,直接更新
            position.doMove(move)
            this.lastMove = move
            invalidate()
            return
        }

        animationAnimator?.cancel()
        animatingMove = move
        animatingPiece = position.pieceAt(move.from)
        animatingFromCenter = squareToCenter(move.from)
        animatingToCenter = squareToCenter(move.to)

        // 暂时把原位置的棋子移走
        position.doMove(move)
        this.lastMove = move

        animationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
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

    fun setLastMove(move: Move?) {
        this.lastMove = move
        invalidate()
    }

    fun setHint(primaryMove: Move?, secondary: List<Move> = emptyList()) {
        this.hintMove = primaryMove
        this.hintMoveSecondary = secondary
        invalidate()
    }

    fun clearHint() {
        this.hintMove = null
        this.hintMoveSecondary = emptyList()
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
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val sq = pixelToSquare(event.x, event.y)
                if (sq != -1) {
                    longPressRunnable = Runnable {
                        onLongPressListener?.invoke(sq)
                    }
                    handler.postDelayed(longPressRunnable!!, 600L)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > 100) {
                    handler.removeCallbacks(longPressRunnable!!)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable!!)
                val sq = pixelToSquare(event.x, event.y)
                handleTap(sq)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable!!)
            }
        }
        return true
    }

    private fun handleTap(sq: Int) {
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

    /** 像素坐标 -> 棋盘格(-1 表示无效) */
    private fun pixelToSquare(x: Float, y: Float): Int {
        val (boardX, boardY) = pixelToBoard(x, y)
        val col = ((boardX - boardLeft) / cellWidth).toInt()
        val row = ((boardY - boardTop) / cellHeight).toInt()
        if (col !in 0 until Square.COLS || row !in 0 until Square.ROWS) return -1
        // 翻转时,行列需要翻转
        return if (flipped) {
            Square.make(Square.ROWS - 1 - row, Square.COLS - 1 - col)
        } else {
            Square.make(row, col)
        }
    }

    private fun pixelToBoard(x: Float, y: Float): Pair<Float, Float> {
        // 翻转后,像素坐标系也需要翻转
        return if (flipped) {
            Pair(width - x, height - y)
        } else {
            Pair(x, y)
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
        val padding = 32f
        val availableW = width - padding * 2
        val availableH = height - padding * 2
        // 棋盘比例 9:10
        val boardRatio = Square.COLS.toFloat() / Square.ROWS  // 0.9
        val viewRatio = availableW / availableH
        val (bw, bh) = if (viewRatio > boardRatio) {
            val bh2 = availableH
            val bw2 = bh2 * boardRatio
            Pair(bw2, bh2)
        } else {
            val bw2 = availableW
            val bh2 = bw2 / boardRatio
            Pair(bw2, bh2)
        }
        val left = (width - bw) / 2
        val top = (height - bh) / 2 - 20
        boardLeft = left
        boardTop = top
        boardRight = left + bw
        boardBottom = top + bh
        cellWidth = bw / (Square.COLS - 1)
        cellHeight = bh / (Square.ROWS - 1)
        pieceRadius = min(cellWidth, cellHeight) * 0.42f

        coordinatePaint.textSize = pieceRadius * 0.55f
        riverTextPaint.textSize = pieceRadius * 1.4f
        pieceRedPaint.textSize = pieceRadius * 1.3f
        pieceBlackPaint.textSize = pieceRadius * 1.3f
    }

    // ====== 绘制 ======

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBoard(canvas)
        drawGrid(canvas)
        drawPalace(canvas)
        drawRiver(canvas)
        if (showCoordinates) drawCoordinates(canvas)
        drawHighlights(canvas)
        drawPieces(canvas)
        if (animatingMove != null) drawAnimatingPiece(canvas)
    }

    private fun drawBoard(canvas: Canvas) {
        // 木纹背景
        val rect = RectF(boardLeft - pieceRadius * 1.2f, boardTop - pieceRadius * 1.2f,
            boardRight + pieceRadius * 1.2f, boardBottom + pieceRadius * 1.2f)

        val gradient = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(
                AndroidColor.parseColor("#DEB887"),
                AndroidColor.parseColor("#D2A56C"),
                AndroidColor.parseColor("#C8A06A"),
                AndroidColor.parseColor("#B88A52")
            ),
            floatArrayOf(0f, 0.4f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        boardBgPaint.shader = gradient
        canvas.drawRoundRect(rect, 12f, 12f, boardBgPaint)

        // 木纹细节 (细线)
        val woodPaint = Paint().apply {
            color = AndroidColor.parseColor("#80A0826D")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        for (i in 0..30) {
            val y = rect.top + (rect.height() / 30f) * i
            val wave = kotlin.math.sin(i * 0.7f) * 4f
            canvas.drawLine(rect.left + wave, y, rect.right - wave, y, woodPaint)
        }

        // 边框
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#5D2F0E")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRoundRect(rect, 12f, 12f, borderPaint)
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

        // 提示高亮
        hintMove?.let { m ->
            drawSquareHighlight(canvas, m.from, highlightHintPaint, true)
            drawSquareHighlight(canvas, m.to, highlightHintPaint, true)
        }
        for (m in hintMoveSecondary) {
            drawSquareHighlight(canvas, m.from, highlightHintSecondaryPaint, true)
            drawSquareHighlight(canvas, m.to, highlightHintSecondaryPaint, true)
        }

        // 将军高亮
        if (checkedKingSq != -1) {
            drawSquareHighlight(canvas, checkedKingSq, checkMarkerPaint, true)
        }
    }

    private fun drawSquareHighlight(canvas: Canvas, sq: Int, paint: Paint, asStroke: Boolean) {
        val center = squareToCenter(sq)
        canvas.drawCircle(center.x, center.y, pieceRadius * 0.95f, paint)
    }

    private fun drawMoveMarker(canvas: Canvas, sq: Int) {
        val center = squareToCenter(sq)
        val radius = pieceRadius * 0.18f
        canvas.drawCircle(center.x, center.y, radius, highlightMovePaint)
        canvas.drawCircle(center.x, center.y, radius * 1.8f, highlightMovePaint.also {
            it.alpha = 80
        })
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
        for (sq in 0 until Square.COUNT) {
            val pieceCode = position.pieceAt(sq)
            if (pieceCode == Piece.NONE.code) continue
            // 跳过正在动画中的棋子(避免重复绘制)
            if (animatingMove != null && (sq == animatingMove!!.from || (sq == animatingMove!!.to && animationProgress < 1f))) continue

            val piece = Piece.fromCode(pieceCode)
            drawPiece(canvas, sq, pieceCode)
        }
    }

    private fun drawAnimatingPiece(canvas: Canvas) {
        val am = animatingMove ?: return
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
        val isRed = piece.isRed
        val radius = pieceRadius

        // 阴影
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        shadowPaint.color = AndroidColor.parseColor("#40000000")
        canvas.drawCircle(cx + 3, cy + 4, radius * 1.05f, shadowPaint)

        // 棋子外圈
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isRed) AndroidColor.parseColor("#7B0A1A") else AndroidColor.parseColor("#1A1A1A")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // 棋子内圈(木纹底色)
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - radius * 0.3f, cy - radius * 0.3f, radius * 0.7f,
                intArrayOf(
                    AndroidColor.parseColor("#FFF8DC"),
                    AndroidColor.parseColor("#F5DEB3"),
                    AndroidColor.parseColor("#DEB887")
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius * 0.92f, innerPaint)

        // 木纹细节
        val woodLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#30A0826D")
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
        }
        for (i in -2..2) {
            val y = cy + i * radius * 0.25f
            canvas.drawLine(cx - radius, y, cx + radius, y, woodLinePaint)
        }

        // 字符
        val charPaint = if (isRed) pieceRedPaint else pieceBlackPaint
        charPaint.color = if (isRed) AndroidColor.parseColor("#C8102E") else AndroidColor.parseColor("#1A1A1A")
        val text = if (isRed) piece.redChar else piece.blackChar
        val fm = charPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2
        canvas.drawText(text, cx, textY, charPaint)
    }

    // ====== 工具 ======

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }
}