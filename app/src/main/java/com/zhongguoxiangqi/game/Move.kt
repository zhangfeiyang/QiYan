package com.zhongguoxiangqi.game

/**
 * 走法编码.
 *
 * 16 位整数:
 * - bits 0..6  (7 bits): from square (0..89)
 * - bits 7..13 (7 bits): to square (0..89)
 * - bits 14..15 (2 bits): special flags (见 MoveType)
 *
 * 起点终点在 0..89 范围,7 bits 足够(89 < 128)
 */
data class Move(
    val data: Int,
    var score: Int = 0
) {

    constructor(from: Int, to: Int, type: Int = MoveType.NORMAL) :
        this(((type and 0x3) shl 14) or ((to and 0x7F) shl 7) or (from and 0x7F))

    val from: Int get() = data and 0x7F
    val to: Int get() = (data shr 7) and 0x7F
    val type: Int get() = (data shr 14) and 0x3

    fun isLegal(): Boolean = from != to || type != MoveType.NORMAL

    /** 走法是否导致对方将军(预计算) */
    fun givesCheck(): Boolean = (data and (1 shl 16)) != 0
    fun setCheckFlag(): Move = Move(data or (1 shl 16))

    /** MVV-LVA 评分(用于排序) */
    fun mvvLva(victim: Int, attacker: Int): Int {
        val victimValue = pieceValue(victim)
        val attackerValue = pieceValue(attacker)
        return (victimValue shl 3) - attackerValue
    }

    companion object {
        const val NONE = -1

        /** 构造一个空走法 */
        fun empty(): Move = Move(0)

        fun make(from: Int, to: Int, type: Int = MoveType.NORMAL): Move =
            Move(((type and 0x3) shl 14) or ((to and 0x7F) shl 7) or (from and 0x7F))

        /** 汉字数字, 索引即数值 */
        private val CHINESE_DIGITS = arrayOf("〇", "一", "二", "三", "四", "五", "六", "七", "八", "九")

        /**
         * 中文着法记谱, 如 "炮二平五"、"马8进7"、"前兵进一".
         *
         * 记谱约定:
         * - 红方用汉字数字, 列号自红方右侧(col 8)起数; 黑方用阿拉伯数字, 列号自黑方右侧(col 0)起数.
         * - 进 = 朝对方前进(红方 row 减小, 黑方 row 增大), 退 = 后撤, 平 = 横移.
         * - 车/炮/兵/将 进退记步数; 马/相/仕 走斜线, 进退记目标列.
         * - 同一列有多个同种棋子时改用 前/中/后 前缀替代起始列.
         */
        fun toChineseString(move: Move, board: Position): String {
            val code = board.pieceAt(move.from)
            if (code == Piece.NONE.code) return ""
            val piece = Piece.fromCode(code)
            val isRed = piece.isRed
            val name = if (isRed) piece.redChar else piece.blackChar

            val fr = Square.row(move.from)
            val fc = Square.col(move.from)
            val tr = Square.row(move.to)
            val tc = Square.col(move.to)

            // 同列同种棋子, 按"从前到后"排序
            val sameFile = (0 until Square.ROWS)
                .filter { board.pieceAt(Square.make(it, fc)) == code }
                .let { if (isRed) it.sorted() else it.sortedDescending() }
            val index = sameFile.indexOf(fr)
            val prefix = when {
                sameFile.size < 2 || index < 0 -> ""
                index == 0 -> "前"
                index == sameFile.size - 1 -> "后"
                sameFile.size == 3 -> "中"
                else -> numeral(index + 1, isRed)
            }

            val action = when {
                tr == fr -> "平"
                (tr < fr) == isRed -> "进"
                else -> "退"
            }

            // 马/相/仕 走斜线, 进退也要记目标列; 其余记纵向步数
            val diagonalMover = piece.type == 2 || piece.type == 3 || piece.type == 4
            val target = if (action == "平" || diagonalMover) {
                fileNumber(tc, isRed)
            } else {
                numeral(kotlin.math.abs(tr - fr), isRed)
            }

            val origin = if (prefix.isEmpty()) fileNumber(fc, isRed) else ""
            return "$prefix$name$origin$action$target"
        }

        /**
         * WXF 记谱, 如 "C2.5"、"H8+7"、"+P+1".
         *
         * 中文记谱对读不了汉字的人是完全不可用的 —— "炮二平五"里没有一个字符
         * 他能对上盘面。WXF (World Xiangqi Federation) 是国际通行的拉丁写法,
         * 和中文记谱一一对应, 只是把三样东西换成了 ASCII:
         *   兵种  用 FEN 那套字母 K/A/B/N/R/C/P (与拉丁棋子字符集同源)
         *   列号  双方都用阿拉伯数字, 各自从自己的右侧数起(与中文记谱同规则)
         *   动作  平 = '.'  进 = '+'  退 = '-'
         * 前/中/后 的消歧前缀写作 '+' / '' / '-' 放在兵种字母**之前**,
         * 并且此时省略起始列 —— 与中文记谱"前炮进二"省略列号是同一回事。
         *
         * 刻意与 [toChineseString] 共用同一套判定(同列同种子的排序、进退方向、
         * 斜行棋子记目标列), 两种写法描述的必须是同一步棋。
         */
        fun toWxfString(move: Move, board: Position): String {
            val code = board.pieceAt(move.from)
            if (code == Piece.NONE.code) return ""
            val piece = Piece.fromCode(code)
            val isRed = piece.isRed
            val name = piece.fen.uppercaseChar()

            val fr = Square.row(move.from)
            val fc = Square.col(move.from)
            val tr = Square.row(move.to)
            val tc = Square.col(move.to)

            val sameFile = (0 until Square.ROWS)
                .filter { board.pieceAt(Square.make(it, fc)) == code }
                .let { if (isRed) it.sorted() else it.sortedDescending() }
            val index = sameFile.indexOf(fr)
            // WXF 只有 前(+)/后(-) 两个前缀; 同列三枚以上时中间那些没有标准
            // 写法, 退回用序数, 至少不会把两步不同的棋写成同一个字符串.
            val prefix = when {
                sameFile.size < 2 || index < 0 -> ""
                index == 0 -> "+"
                index == sameFile.size - 1 -> "-"
                else -> (index + 1).toString()
            }

            val action = when {
                tr == fr -> "."
                (tr < fr) == isRed -> "+"
                else -> "-"
            }

            val diagonalMover = piece.type == 2 || piece.type == 3 || piece.type == 4
            val target = if (action == "." || diagonalMover) {
                wxfFile(tc, isRed)
            } else {
                kotlin.math.abs(tr - fr).toString()
            }

            val origin = if (prefix.isEmpty()) wxfFile(fc, isRed) else ""
            return "$prefix$name$origin$action$target"
        }

        /** WXF 列号: 双方都用阿拉伯数字, 各自从本方右侧数起 */
        private fun wxfFile(col: Int, isRed: Boolean): String =
            if (isRed) (Square.COLS - col).toString() else (col + 1).toString()

        /** 列号: 红方自右向左数(col 8 = 一), 黑方自右向左数(col 0 = 1) */
        private fun fileNumber(col: Int, isRed: Boolean): String =
            if (isRed) numeral(Square.COLS - col, true) else numeral(col + 1, false)

        private fun numeral(n: Int, isRed: Boolean): String =
            if (isRed && n in CHINESE_DIGITS.indices) CHINESE_DIGITS[n] else n.toString()

        // 各棋子大致价值(用于排序)
        private fun pieceValue(piece: Int): Int = when (Piece.fromCode(piece).type) {
            1 -> 10000  // 将/帅
            2 -> 200    // 仕
            3 -> 200    // 相
            4 -> 400    // 马
            5 -> 900    // 车
            6 -> 450    // 炮
            7 -> 100    // 兵
            else -> 0
        }
    }
}

object MoveType {
    const val NORMAL = 0
    const val EN_PASSANT = 1  // 暂未使用,留作扩展
}