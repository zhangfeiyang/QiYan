package com.zhongguoxiangqi.game

/**
 * 棋盘坐标系统.
 *
 * 棋盘 10 行(0..9) × 9 列(0..8)
 * 红方在下方(行 5..9),黑方在上方(行 0..4)
 *
 * 坐标编号: square = row * 9 + col (0..89)
 */
object Square {

    const val ROWS = 10
    const val COLS = 9
    const val COUNT = 90
    const val NONE = -1

    /** 构建坐标 */
    fun make(row: Int, col: Int): Int = row * COLS + col

    /** 拆分坐标 */
    fun row(sq: Int): Int = sq / COLS
    fun col(sq: Int): Int = sq % COLS

    /** 坐标合法 */
    fun valid(sq: Int): Boolean = sq in 0 until COUNT
    fun valid(row: Int, col: Int): Boolean = row in 0 until ROWS && col in 0 until COLS

    /** 翻转(用于黑方视角) */
    fun mirror(sq: Int): Int = if (sq == NONE) NONE else (ROWS - 1 - row(sq)) * COLS + (COLS - 1 - col(sq))

    /** 标准代数坐标(如 a1 -> "a1") */
    fun toString(sq: Int): String {
        if (sq == NONE) return "-"
        val c = col(sq)
        val r = row(sq)
        return "${('a' + c)}${r}"
    }

    /** 解析代数坐标 */
    fun fromString(s: String): Int {
        if (s.length < 2) return NONE
        val c = s[0] - 'a'
        val r = s.substring(1).toIntOrNull() ?: return NONE
        if (!valid(r, c)) return NONE
        return make(r, c)
    }

    /** 中文坐标(显示用): 红方视角 "一",黑方视角 "1" */
    fun toChinese(sq: Int, isRedSide: Boolean): String {
        if (sq == NONE) return "??"
        val c = col(sq)
        val r = row(sq)
        val colStr = when {
            c <= 4 -> (c + 1).toString()  // 一二三四五
            else -> ('a' + c).toString()   // 反方用英文
        }
        val rowStr = if (isRedSide) (r + 1).toString() else (ROWS - r).toString()
        return "$colStr$rowStr"
    }

    /** 文件(A-I) -> 列(0-8) */
    fun fileToCol(file: Char): Int = when (file.lowercaseChar()) {
        'a' -> 0; 'b' -> 1; 'c' -> 2; 'd' -> 3; 'e' -> 4
        'f' -> 5; 'g' -> 6; 'h' -> 7; 'i' -> 8
        else -> -1
    }

    /** 列(0-8) -> 文件(A-I) */
    fun colToFile(col: Int): Char = ('a' + col)
}

/**
 * 棋盘预计算: 各种方向偏移
 */
object Direction {
    // 上下左右 (将/仕)
    val UP = -Square.COLS
    val DOWN = Square.COLS
    val LEFT = -1
    val RIGHT = 1

    // 马腿位置 (相对马的位置) - 8 个方向,每个方向对应一个"马腿"位置
    val HORSE_DELTAS = intArrayOf(
        -Square.COLS - 1, -Square.COLS + 1,   // 上左,上右
        Square.COLS - 1, Square.COLS + 1,      // 下左,下右
        -Square.COLS * 2 - 1, -Square.COLS * 2 + 1,
        Square.COLS * 2 - 1, Square.COLS * 2 + 1
    )
    val HORSE_LEGS = intArrayOf(
        -Square.COLS, -Square.COLS,            // 上方两个腿
        Square.COLS, Square.COLS,              // 下方两个腿
        -1, 1,                                 // 左侧两个腿
        -1, 1                                  // 右侧两个腿
    )
    val HORSE_TARGETS = intArrayOf(
        -Square.COLS * 2 - 1, -Square.COLS * 2 + 1,   // 上左,上右 (两个目标)
        Square.COLS * 2 - 1, Square.COLS * 2 + 1,     // 下左,下右
        -Square.COLS - 2, Square.COLS - 2,             // 左上,左下
        -Square.COLS + 2, Square.COLS + 2              // 右上,右下
    )

    // 象/相 4 个方向(田字),及"象眼"位置
    val ELEPHANT_DELTAS = intArrayOf(
        -Square.COLS * 2 - 2, -Square.COLS * 2 + 2,
        Square.COLS * 2 - 2, Square.COLS * 2 + 2
    )
    val ELEPHANT_EYES = intArrayOf(
        -Square.COLS - 1, -Square.COLS + 1,
        Square.COLS - 1, Square.COLS + 1
    )

    // 车/炮/兵直线 4 个方向
    val ROOK_DELTAS = intArrayOf(UP, DOWN, LEFT, RIGHT)
}