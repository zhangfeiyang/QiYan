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

        /** 中文着法(红方视角) */
        fun toChineseString(move: Move, side: Int, board: Position): String {
            if (move.data == 0) return ""
            val p = board.pieceAt(move.from)
            val pieceName = when (Piece.fromCode(p).type) {
                1 -> if (side == Color.RED) "帅" else "将"
                2 -> if (side == Color.RED) "仕" else "士"
                3 -> if (side == Color.RED) "相" else "象"
                4 -> "马"; 5 -> "车"; 6 -> "炮"
                7 -> if (side == Color.RED) "兵" else "卒"
                else -> "?"
            }

            // 同列有两个同种棋子时需加前缀
            val fr = Square.row(move.from)
            val tr = Square.row(move.to)
            val fc = Square.col(move.from)
            val tc = Square.col(move.to)
            val sameColCount = board.countPiecesInCol(fc, p, side)
            val prefix = if (sameColCount >= 2) {
                // 用前后区分: 两个同列同种,前用"前",后用"后"; 三个以上用"中"
                val minRow = if (side == Color.RED) fr else 9 - fr
                when {
                    sameColCount == 2 -> if (minRow <= 4) "后" else "前"
                    else -> "中"  // 简化处理
                }
            } else ""

            // 移动方向: 进退平
            val action = when {
                fc == tc -> if (tr > fr) "进" else "退"  // 同列直走
                else -> "平"  // 移列
            }

            // 终点记法: 进退时用行差(数字),平时用列号
            val toText = if (action == "平") {
                "${tc + 1}"
            } else {
                if (side == Color.RED) "${tr + 1}" else "${9 - tr}"
            }
            return "$prefix$pieceName$action$toText"
        }

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