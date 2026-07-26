package com.zhongguoxiangqi.game

/**
 * UCI 坐标 ↔ 内部坐标的唯一转换入口.
 *
 * 两套坐标的行方向是**相反**的, 这是唯一容易搞错的地方:
 *
 * - UCI (Pikafish `types.h`): 列 a..i = file 0..8; rank 0 = **红方**底线,
 *   rank 9 = 黑方底线. FEN 第一段对应 rank 9, 逐段递减到 rank 0.
 * - App (`Square`): row 0 = **黑方**底线, row 9 = 红方底线; `toFen()` 也是
 *   从 row 0 开始输出, 所以 row 0 与 UCI 的 rank 9 对齐.
 *
 * 于是 `rank = 9 - row`, 反向同式.
 *
 * 以前 EngineManager / GameViewModel / ReplayViewerActivity / SimpleEngine
 * 各自抄了一份这段换算, 其中一份改错方向就会让引擎的着法在本地判为非法
 * ("引擎给出非法着法: b0b9"). 只留这一份, 并由单元测试锁住方向.
 */
object UciCoords {

    /** 空着 / 无效着法的 UCI 表示 */
    const val NULL_MOVE = "0000"

    private const val LAST_RANK = Square.ROWS - 1  // 9

    /** 内部 row → UCI rank, 以及反向(自反, 同一个式子) */
    fun flipRow(row: Int): Int = LAST_RANK - row

    /** 走法 → UCI 字符串, 如 "b0b9" */
    fun toUci(move: Move): String {
        if (move.from == move.to) return NULL_MOVE
        return squareToUci(move.from) + squareToUci(move.to)
    }

    /** 单格坐标 → UCI, 如 "b0" */
    fun squareToUci(sq: Int): String =
        "${('a' + Square.col(sq))}${flipRow(Square.row(sq))}"

    /**
     * UCI 字符串 → 走法. 格式非法或坐标越界时返回 null,
     * 调用方仍需用 `Position.isLegalMove` 判断该着法在当前局面下是否合法.
     */
    fun parse(uci: String): Move? {
        if (uci.length < 4 || uci == NULL_MOVE) return null
        val fromCol = uci[0] - 'a'
        val toCol = uci[2] - 'a'
        if (!uci[1].isDigit() || !uci[3].isDigit()) return null
        val fromRow = flipRow(uci[1] - '0')
        val toRow = flipRow(uci[3] - '0')
        if (!Square.valid(fromRow, fromCol) || !Square.valid(toRow, toCol)) return null
        return Move.make(Square.make(fromRow, fromCol), Square.make(toRow, toCol))
    }
}
