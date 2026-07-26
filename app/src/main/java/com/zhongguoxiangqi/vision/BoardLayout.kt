package com.zhongguoxiangqi.vision

import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.Square

/**
 * 识别结果(10x9 字符棋盘) 与 App 局面之间的转换和校验.
 *
 * 识别模型输出的行序与 App 内部坐标、标准 FEN 三者一致 —— 都是
 * row 0 = 黑方底线, row 9 = 红方底线 —— 所以不需要任何翻转.
 *
 * **校验是必须的**: 模型整盘全对的概率约 83%, 也就是每 6 张图就有 1 张
 * 至少错一个子. 而错一个子, 引擎给的建议就是完全错误的. 把明显不合法的
 * 局面拦在送进引擎之前, 用户才有机会去改.
 */
object BoardLayout {

    /** 一条校验结果 */
    data class Issue(
        val message: String,
        /** true = 必须改正才能求招; false = 只是提醒 */
        val blocking: Boolean,
        /** 相关点位(可能为空), 用来在界面上高亮 */
        val cells: List<Cell> = emptyList()
    )

    data class Cell(val row: Int, val col: Int)

    /** 各兵种在一方的数量上限 */
    private val MAX_COUNT = mapOf(
        'K' to 1, 'A' to 2, 'B' to 2, 'N' to 2, 'R' to 2, 'C' to 2, 'P' to 5
    )

    private val PIECE_NAME = mapOf(
        'K' to "帅", 'A' to "仕", 'B' to "相", 'N' to "马", 'R' to "车", 'C' to "炮", 'P' to "兵",
        'k' to "将", 'a' to "士", 'b' to "象", 'n' to "马", 'r' to "车", 'c' to "炮", 'p' to "卒"
    )

    /** 生成一个空棋盘(全部为空点) */
    fun emptyLayout(): Array<CharArray> =
        Array(BoardRecognizer.Rows) { CharArray(BoardRecognizer.Cols) { BoardRecognizer.CHAR_EMPTY } }

    /**
     * 转成标准 FEN.
     *
     * 'x'(棋盘外) 一律当空点处理 —— 它不是棋子, 留着只会让 FEN 非法.
     */
    fun toFen(layout: Array<CharArray>, sideToMove: Int): String {
        val sb = StringBuilder()
        for (r in 0 until BoardRecognizer.Rows) {
            var empty = 0
            for (c in 0 until BoardRecognizer.Cols) {
                val ch = layout[r][c]
                if (ch == BoardRecognizer.CHAR_EMPTY || ch == BoardRecognizer.CHAR_OFF_BOARD) {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < BoardRecognizer.Rows - 1) sb.append('/')
        }
        sb.append(if (sideToMove == Color.RED) " w" else " b")
        sb.append(" - - 0 1")
        return sb.toString()
    }

    /** 转成 [Position]; FEN 不被解析器接受时返回 null */
    fun toPosition(layout: Array<CharArray>, sideToMove: Int): Position? {
        val pos = Position()
        return if (pos.setFromFen(toFen(layout, sideToMove))) pos else null
    }

    /** 从 [Position] 反向生成排布, 供用户手工编辑后再校验 */
    fun fromPosition(pos: Position): Array<CharArray> {
        val layout = emptyLayout()
        for (r in 0 until BoardRecognizer.Rows) {
            for (c in 0 until BoardRecognizer.Cols) {
                val code = pos.pieceAt(Square.make(r, c))
                layout[r][c] = if (code == Piece.NONE.code) BoardRecognizer.CHAR_EMPTY
                               else Piece.fromCode(code).fen
            }
        }
        return layout
    }

    /**
     * 校验识别出来的局面.
     *
     * @param sideToMove 轮到谁走 —— 图片本身给不出这个信息, 必须由用户指定
     * @return 问题列表, 空表示可以直接送引擎
     */
    fun validate(layout: Array<CharArray>, sideToMove: Int): List<Issue> {
        val issues = mutableListOf<Issue>()

        // --- 数量与位置 ---
        val counts = mutableMapOf<Char, MutableList<Cell>>()
        for (r in 0 until BoardRecognizer.Rows) {
            for (c in 0 until BoardRecognizer.Cols) {
                val ch = layout[r][c]
                if (ch != BoardRecognizer.CHAR_EMPTY && ch != BoardRecognizer.CHAR_OFF_BOARD) {
                    counts.getOrPut(ch) { mutableListOf() }.add(Cell(r, c))
                }
            }
        }

        for ((upper, max) in MAX_COUNT) {
            for (ch in listOf(upper, upper.lowercaseChar())) {
                val cells = counts[ch] ?: continue
                if (cells.size > max) {
                    val side = if (ch.isUpperCase()) "红" else "黑"
                    issues += Issue(
                        "$side${PIECE_NAME[ch]}有 ${cells.size} 个, 最多 $max 个",
                        blocking = true, cells = cells
                    )
                }
            }
        }

        // --- 将帅 ---
        val redKings = counts['K'].orEmpty()
        val blackKings = counts['k'].orEmpty()
        if (redKings.size != 1) {
            issues += Issue("红方应有且仅有 1 个帅, 识别到 ${redKings.size} 个",
                blocking = true, cells = redKings)
        }
        if (blackKings.size != 1) {
            issues += Issue("黑方应有且仅有 1 个将, 识别到 ${blackKings.size} 个",
                blocking = true, cells = blackKings)
        }

        // 将帅、士必须在九宫内, 否则引擎会拒绝这个局面
        checkPalace(counts['K'], redPalace = true, "帅", issues)
        checkPalace(counts['A'], redPalace = true, "仕", issues)
        checkPalace(counts['k'], redPalace = false, "将", issues)
        checkPalace(counts['a'], redPalace = false, "士", issues)

        // 相/象不能过河
        counts['B'].orEmpty().filter { it.row < 5 }.let {
            if (it.isNotEmpty()) issues += Issue("红相不能过河", blocking = true, cells = it)
        }
        counts['b'].orEmpty().filter { it.row > 4 }.let {
            if (it.isNotEmpty()) issues += Issue("黑象不能过河", blocking = true, cells = it)
        }

        // 兵/卒不可能出现在自己底线后方两行
        counts['P'].orEmpty().filter { it.row > 6 }.let {
            if (it.isNotEmpty()) issues += Issue("红兵不可能在这个位置", blocking = true, cells = it)
        }
        counts['p'].orEmpty().filter { it.row < 3 }.let {
            if (it.isNotEmpty()) issues += Issue("黑卒不可能在这个位置", blocking = true, cells = it)
        }

        // --- 棋盘外 ---
        val offBoard = mutableListOf<Cell>()
        for (r in 0 until BoardRecognizer.Rows) {
            for (c in 0 until BoardRecognizer.Cols) {
                if (layout[r][c] == BoardRecognizer.CHAR_OFF_BOARD) offBoard += Cell(r, c)
            }
        }
        if (offBoard.isNotEmpty()) {
            issues += Issue(
                "有 ${offBoard.size} 个点位落在棋盘外, 棋盘四角没对准, 建议重拍或手动调整边框",
                blocking = false, cells = offBoard
            )
        }

        // 上面任何一条不过, 局面根本立不起来, 后面的规则判断没有意义
        if (issues.any { it.blocking }) return issues

        // --- 规则层面 ---
        val pos = toPosition(layout, sideToMove)
        if (pos == null) {
            issues += Issue("局面无法解析, 请检查棋子摆放", blocking = true)
            return issues
        }

        // 轮到我走, 却是对方正在被将 —— 说明上一步是不合法的, 局面立不住
        val opponent = Color.opposite(sideToMove)
        if (Rules.isInCheck(pos, opponent)) {
            val who = if (opponent == Color.RED) "红方" else "黑方"
            issues += Issue(
                "$who 正在被将军, 但轮到对方走棋 —— 局面不合法, 请检查是否认错了棋子或走子方",
                blocking = true
            )
        }

        return issues
    }

    private fun checkPalace(
        cells: List<Cell>?,
        redPalace: Boolean,
        name: String,
        issues: MutableList<Issue>
    ) {
        val rows = if (redPalace) 7..9 else 0..2
        val bad = cells.orEmpty().filter { it.row !in rows || it.col !in 3..5 }
        if (bad.isNotEmpty()) {
            issues += Issue("$name 必须在九宫内", blocking = true, cells = bad)
        }
    }

    /**
     * 列出置信度低于阈值的点位, 供界面高亮.
     *
     * 模型输出已经过 softmax, 所以这就是真实概率.
     */
    fun lowConfidenceCells(
        confidence: Array<FloatArray>,
        threshold: Float = LOW_CONFIDENCE
    ): List<Cell> {
        val out = mutableListOf<Cell>()
        for (r in confidence.indices) {
            for (c in confidence[r].indices) {
                if (confidence[r][c] < threshold) out += Cell(r, c)
            }
        }
        return out
    }

    /** 低于这个概率就让用户重点核对 */
    const val LOW_CONFIDENCE = 0.75f

    /** 四角关键点平均分低于这个值, 基本可以认为没框住棋盘 */
    const val LOW_CORNER_SCORE = 0.18f
}
