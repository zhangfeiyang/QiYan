package com.zhongguoxiangqi.game

import kotlin.random.Random

/**
 * 生成红先一步将死的题面.
 *
 * 每道题都经 [MateSearch] 确认. 杀法名取走完杀招后的局面, 认不出就记“绝杀”.
 * 随机采样之外, 用几组固定骨架把常见杀法补够, 避免题库全是同一种车杀.
 */
object CatalogGenerator {

    data class Entry(val fen: String, val tactic: String)

    fun generate(limit: Int, seed: Long = 20260922L): List<Entry> {
        val rng = Random(seed)
        val named = LinkedHashMap<String, Entry>(limit)
        val other = LinkedHashMap<String, Entry>(limit)
        val pos = Position()
        var spins = 0
        val cap = limit * 800
        var relaxed = false
        while (named.size + other.size < limit && spins < cap) {
            spins++
            if (!relaxed && spins > cap / 3 && named.size + other.size < limit) relaxed = true
            val fen = (if (!relaxed && spins % 4 != 0) skeleton(rng) else randomPosition(rng)) ?: continue
            if (!pos.setFromFen(fen)) continue
            val board = fen.substringBefore(' ')
            if (board in named || board in other) continue
            val move = MateSearch.matingMove(pos) ?: continue
            pos.doMove(move)
            val tactic = Tactics.detect(pos, Color.RED) ?: "绝杀"
            pos.undoMove()
            val entry = Entry(pos.toFen().substringBefore(' ') + " w - - 0 1", tactic)
            if (tactic == "绝杀") {
                if (relaxed || other.size < limit / 4) other[board] = entry
            } else {
                named[board] = entry
            }
        }
        return (named.values + other.values).take(limit)
    }

    private fun randomPosition(rng: Random): String? {
        val cells = Array(10) { IntArray(9) }
        val bk = BLACK_PALACE[rng.nextInt(BLACK_PALACE.size)]
        val rk = RED_PALACE[rng.nextInt(RED_PALACE.size)]
        cells[bk.first][bk.second] = Piece.B_KING.code
        cells[rk.first][rk.second] = Piece.R_KING.code
        repeat(rng.nextInt(3)) {
            val spot = BLACK_PALACE[rng.nextInt(BLACK_PALACE.size)]
            if (cells[spot.first][spot.second] == 0) cells[spot.first][spot.second] = Piece.B_ADVISOR.code
        }
        if (rng.nextBoolean()) {
            val spot = BLACK_ELEPHANTS[rng.nextInt(BLACK_ELEPHANTS.size)]
            if (cells[spot.first][spot.second] == 0) cells[spot.first][spot.second] = Piece.B_ELEPHANT.code
        }
        val bag = intArrayOf(
            Piece.R_ROOK.code, Piece.R_ROOK.code,
            Piece.R_CANNON.code, Piece.R_CANNON.code,
            Piece.R_HORSE.code, Piece.R_HORSE.code,
            Piece.R_PAWN.code, Piece.R_PAWN.code, Piece.R_PAWN.code,
        )
        val counts = HashMap<Int, Int>()
        repeat(rng.nextInt(2, 5)) {
            val code = bag[rng.nextInt(bag.size)]
            if ((counts[code] ?: 0) >= if (code == Piece.R_PAWN.code) 5 else 2) return@repeat
            val row = if (code == Piece.R_PAWN.code) rng.nextInt(0, 7) else rng.nextInt(0, 10)
            val col = rng.nextInt(0, 9)
            if (cells[row][col] != 0) return@repeat
            cells[row][col] = code
            counts[code] = (counts[code] ?: 0) + 1
        }
        return toFen(cells)
    }

    /**
     * 底线车杀的骨架: 将在中路底线, 退路被兵看住, 车从下一线提上来照将.
     * 列和红帅位置会变, 所以不是同一道题。
     */
    private fun skeleton(rng: Random): String = when (rng.nextInt(6)) {
        0 -> rookLift(rng)
        1 -> faceToFace(rng)
        2 -> smothered(rng)
        3 -> doubleCannon(rng)
        4 -> horseCannon(rng)
        else -> heartRook(rng)
    }

    /** 车从底线下一格提上来, 兵看住将的退路. */
    private fun rookLift(rng: Random): String {
        val cells = blank()
        val kingCol = 3 + rng.nextInt(3)
        cells[0][kingCol] = Piece.B_KING.code
        val rookCol = if (rng.nextBoolean()) 0 else 8
        cells[1][rookCol] = Piece.R_ROOK.code
        cells[2][kingCol] = Piece.R_PAWN.code
        val redCol = redColAwayFrom(kingCol, rng)
        cells[8][redCol] = Piece.R_KING.code
        cells[9][redCol] = Piece.R_ADVISOR.code
        return finish(cells, rng)
    }

    /** 中路只隔一门炮, 炮让开后帅将照面. 将的退路用自己的士和兵看死. */
    private fun faceToFace(rng: Random): String {
        val cells = blank()
        cells[0][4] = Piece.B_KING.code
        cells[0][3] = Piece.B_ADVISOR.code
        cells[0][5] = Piece.B_ADVISOR.code
        cells[1][3] = Piece.R_PAWN.code
        val cannonRow = 3 + rng.nextInt(4)
        cells[cannonRow][4] = Piece.R_CANNON.code
        cells[9][4] = Piece.R_KING.code
        cells[8][3] = Piece.R_ADVISOR.code
        return finish(cells, rng)
    }

    /** 双士堵在底线, 中路有一个炮架, 另一门炮横到中路. */
    private fun smothered(rng: Random): String {
        val cells = blank()
        cells[0][4] = Piece.B_KING.code
        cells[0][3] = Piece.B_ADVISOR.code
        cells[0][5] = Piece.B_ADVISOR.code
        cells[1][4] = if (rng.nextBoolean()) Piece.B_HORSE.code else Piece.B_CANNON.code
        val cannonCol = if (rng.nextBoolean()) 1 else 7
        cells[3][cannonCol] = Piece.R_CANNON.code
        cells[9][3] = Piece.R_KING.code
        return finish(cells, rng)
    }

    /** 一门炮已在中路当架, 另一门炮横过来叠上. */
    private fun doubleCannon(rng: Random): String {
        val cells = blank()
        cells[0][4] = Piece.B_KING.code
        cells[0][3] = Piece.B_ADVISOR.code
        cells[2][4] = Piece.R_CANNON.code
        val col = if (rng.nextBoolean()) 1 else 6
        cells[5][col] = Piece.R_CANNON.code
        cells[9][3] = Piece.R_KING.code
        cells[8][5] = Piece.R_ADVISOR.code
        return finish(cells, rng)
    }

    /** 马在中路当炮架, 炮从侧翼横到马后. */
    private fun horseCannon(rng: Random): String {
        val cells = blank()
        cells[0][4] = Piece.B_KING.code
        cells[0][3] = Piece.B_ADVISOR.code
        cells[0][5] = Piece.B_ADVISOR.code
        cells[2][4] = Piece.R_HORSE.code
        val col = if (rng.nextBoolean()) 2 else 6
        cells[4][col] = Piece.R_CANNON.code
        cells[9][3] = Piece.R_KING.code
        return finish(cells, rng)
    }

    /** 车贴着九宫心的横线, 一步杀进中心. */
    private fun heartRook(rng: Random): String {
        val cells = blank()
        cells[0][4] = Piece.B_KING.code
        cells[0][3] = Piece.B_ADVISOR.code
        cells[0][5] = Piece.B_ADVISOR.code
        val rookCol = if (rng.nextBoolean()) 0 else 8
        cells[1][rookCol] = Piece.R_ROOK.code
        cells[9][3] = Piece.R_KING.code
        cells[7][5] = Piece.R_ADVISOR.code
        return finish(cells, rng)
    }

    /** 在红方半场加一两子, 不改杀法骨架; 加完若破坏杀棋, 采样时会被丢掉. */
    private fun finish(cells: Array<IntArray>, rng: Random): String {
        repeat(rng.nextInt(0, 3)) {
            val row = 6 + rng.nextInt(4)
            val col = rng.nextInt(9)
            if (cells[row][col] != 0) return@repeat
            cells[row][col] = if (rng.nextBoolean()) Piece.R_PAWN.code else Piece.R_HORSE.code
        }
        return toFen(cells)
    }

    private fun blank() = Array(10) { IntArray(9) }

    private fun redColAwayFrom(kingCol: Int, rng: Random): Int {
        val choices = intArrayOf(3, 4, 5).filter { it != kingCol }
        return choices[rng.nextInt(choices.size)]
    }

    private fun toFen(cells: Array<IntArray>): String {
        val sb = StringBuilder()
        for (r in 0 until 10) {
            if (r > 0) sb.append('/')
            var empty = 0
            for (c in 0 until 9) {
                val code = cells[r][c]
                if (code == 0) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(Piece.fromCode(code).fen)
                }
            }
            if (empty > 0) sb.append(empty)
        }
        sb.append(" w - - 0 1")
        return sb.toString()
    }

    private val BLACK_PALACE = listOf(
        0 to 3, 0 to 4, 0 to 5,
        1 to 3, 1 to 4, 1 to 5,
        2 to 3, 2 to 4, 2 to 5,
    )
    private val RED_PALACE = listOf(
        7 to 3, 7 to 4, 7 to 5,
        8 to 3, 8 to 4, 8 to 5,
        9 to 3, 9 to 4, 9 to 5,
    )
    private val BLACK_ELEPHANTS = listOf(
        0 to 2, 0 to 6, 2 to 0, 2 to 4, 2 to 8, 4 to 2, 4 to 6,
    )
}
