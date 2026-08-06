package com.zhongguoxiangqi.game

import kotlin.math.abs

/**
 * 常见战术杀法的局面检测(给语音播报用).
 *
 * 目前实现用户点名的两个:
 * - 马后炮: 炮隔着己方的马照将, 炮-马-将三点一线
 * - 铁门栓: 车照将, 同时己方兵(或帅/将)卡在将的侧翼出口, 把将"栓"住
 *
 * 检测是"近似"的: 只判断杀伤结构是否成立, 不验证该结构是否无解 ——
 * 播报只是锦上添花, 宁可宽松也不漏报.
 */
object Tactics {

    /**
     * 走完一手后检测战术名.
     * @param pos 走子完成后的局面
     * @param mover 刚走子的一方(正在将军的一方)
     * @return 战术名, 检测不到返回 null
     */
    fun detect(pos: Position, mover: Int): String? {
        val kingSide = Color.opposite(mover)
        if (!Rules.isInCheck(pos, kingSide)) return null
        val kingSq = findKing(pos, kingSide)
        if (kingSq < 0) return null
        if (isHorseBehindCannon(pos, mover, kingSq)) return "马后炮"
        if (isIronDoorBolt(pos, mover, kingSq)) return "铁门栓"
        return null
    }

    private fun findKing(pos: Position, color: Int): Int {
        val code = if (color == Color.RED) Piece.R_KING.code else Piece.B_KING.code
        for (sq in 0 until Square.COUNT) {
            if (pos.squares[sq] == code) return sq
        }
        return -1
    }

    /** 同线两格之间的棋子(不含两端). 不在同一行/列时返回空. */
    private fun between(pos: Position, a: Int, b: Int): List<Int> {
        val ar = Square.row(a)
        val ac = Square.col(a)
        val br = Square.row(b)
        val bc = Square.col(b)
        if (ar != br && ac != bc) return emptyList()
        val dr = (br - ar).coerceIn(-1, 1)
        val dc = (bc - ac).coerceIn(-1, 1)
        val out = ArrayList<Int>(8)
        var r = ar + dr
        var c = ac + dc
        while (r != br || c != bc) {
            val sq = Square.make(r, c)
            if (pos.squares[sq] != Piece.NONE.code) out.add(sq)
            r += dr
            c += dc
        }
        return out
    }

    /**
     * 马后炮: 炮与将之间恰好隔一个子, 且这个"炮架"是己方的马.
     * 方向无所谓 —— 炮把马当炮架照将, 马在炮前, 就是马后炮结构.
     */
    private fun isHorseBehindCannon(pos: Position, mover: Int, kingSq: Int): Boolean {
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != mover || pc.type != 6) continue
            val inBetween = between(pos, sq, kingSq)
            if (inBetween.size != 1) continue
            val screen = Piece.fromCode(pos.squares[inBetween[0]])
            if (screen.type == 4 && screen.color == mover) return true
        }
        return false
    }

    /**
     * 铁门栓: 车在同线照将, 同时己方兵(或帅/将)占据将的侧翼出口
     * (同排相邻列 或 同列相邻排), 将的退路被"栓"住.
     */
    private fun isIronDoorBolt(pos: Position, mover: Int, kingSq: Int): Boolean {
        val kr = Square.row(kingSq)
        val kc = Square.col(kingSq)
        var rookCheck = false
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != mover || pc.type != 5) continue
            if (Square.row(sq) == kr || Square.col(sq) == kc) {
                if (between(pos, sq, kingSq).isEmpty()) {
                    rookCheck = true
                    break
                }
            }
        }
        if (!rookCheck) return false
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != mover || (pc.type != 7 && pc.type != 1)) continue
            val r = Square.row(sq)
            val c = Square.col(sq)
            if (r == kr && abs(c - kc) == 1) return true
            if (c == kc && abs(r - kr) == 1) return true
        }
        return false
    }
}
