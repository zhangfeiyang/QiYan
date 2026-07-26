package com.zhongguoxiangqi.game

/**
 * 中国象棋棋子定义.
 *
 * 使用 XQRS (Xiangqi Representation System) 编码:
 * - 1..7: 红方  车马象士将炮兵
 * - 9..15: 黑方  车马象士将炮兵
 *
 * 这样设计保证: piece >> 1 表示棋子类型 (1..7)
 * piece & 1 表示颜色 (1=红, 0=黑),与位运算一致
 */
enum class Piece(
    val code: Int,
    val chinese: String,
    val redChar: String,
    val blackChar: String,
    /** 标准象棋 FEN 字母: 大写=红, 小写=黑 (K/A/B/N/R/C/P) */
    val fen: Char
) {
    NONE(0, "空", "", "", ' '),
    R_KING(1, "帅", "帅", "将", 'K'),
    R_ADVISOR(2, "仕", "仕", "士", 'A'),
    R_ELEPHANT(3, "相", "相", "象", 'B'),
    R_HORSE(4, "马", "马", "马", 'N'),
    R_ROOK(5, "车", "车", "车", 'R'),
    R_CANNON(6, "炮", "炮", "炮", 'C'),
    R_PAWN(7, "兵", "兵", "卒", 'P'),
    B_KING(9, "将", "帅", "将", 'k'),
    B_ADVISOR(10, "士", "仕", "士", 'a'),
    B_ELEPHANT(11, "象", "相", "象", 'b'),
    B_HORSE(12, "马", "马", "马", 'n'),
    B_ROOK(13, "车", "车", "车", 'r'),
    B_CANNON(14, "炮", "炮", "炮", 'c'),
    B_PAWN(15, "卒", "兵", "卒", 'p');

    val isRed: Boolean get() = code in 1..7
    val isBlack: Boolean get() = code in 9..15
    val color: Int get() = if (isRed) Color.RED else if (isBlack) Color.BLACK else Color.NONE

    /** 棋子类型(去除颜色): 1=帅,2=仕,3=相,4=马,5=车,6=炮,7=兵 */
    val type: Int get() = if (code == 0) 0 else (code and 0x07)

    companion object {
        /** code -> Piece 的直查表, 避免每次线性扫描 values() */
        private val BY_CODE: Array<Piece> = Array(16) { c -> values().firstOrNull { it.code == c } ?: NONE }
        private val BY_FEN: Map<Char, Piece> = values().filter { it != NONE }.associateBy { it.fen }

        fun fromCode(code: Int): Piece = if (code in 0..15) BY_CODE[code] else NONE

        /** FEN 字母 -> 棋子, 无法识别返回 NONE */
        fun fromFen(c: Char): Piece = BY_FEN[c] ?: NONE

        /** 棋子字符(红方显示红字,黑方显示黑字) */
        fun displayChar(pieceCode: Int, isRed: Boolean): String {
            val p = fromCode(pieceCode)
            return if (isRed) p.redChar else p.blackChar
        }
    }
}

object Color {
    /** 无主(空格 / 无胜方). 必须与 BLACK 区分 —— BLACK 是 0. */
    const val NONE = -1
    const val RED = 1
    const val BLACK = 0

    fun opposite(color: Int): Int = 1 - color
    fun name(color: Int): String = when (color) {
        RED -> "红"
        BLACK -> "黑"
        else -> "无"
    }
}