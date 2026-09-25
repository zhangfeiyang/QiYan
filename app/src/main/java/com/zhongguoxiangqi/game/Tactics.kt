package com.zhongguoxiangqi.game

import kotlin.math.abs

/**
 * 常见经典象棋战术杀法局面检测(用于终局与将军的专属播报与称号展示).
 *
 * 支持检测的经典绝杀杀法:
 * - 马后炮: 炮隔己方马照将, 炮-马-将三点一线
 * - 铁门栓: 车直线照将, 兵或帅封锁咽喉
 * - 重炮 (双炮): 双炮连环同线照将 (后炮借前炮为架直接照将)
 * - 双车错: 双车在相邻通道交错配合压迫将门
 * - 卧槽马: 马在底象卧槽位扼杀将门
 * - 挂角马: 马在士角位策应照将
 * - 白脸将: 帅与将同线照面威慑, 借帅力强杀
 * - 天地炮: 一炮沉底, 一炮镇中路
 * - 大刀剜心: 车直插九宫中心腹地强杀
 * - 闷宫: 对方双士自阻退路, 炮直点宫顶
 */
object Tactics {

    /**
     * 检测战术名.
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
        if (isDoubleCannon(pos, mover, kingSq)) return "重炮"
        if (isDoubleRooks(pos, mover, kingSq)) return "双车错"
        if (isWoCaoHorse(pos, mover, kingSq)) return "卧槽马"
        if (isCornerHorse(pos, mover, kingSq)) return "挂角马"
        if (isHeartCarvingRook(pos, mover, kingSq)) return "大刀剜心"
        if (isFaceToFaceKings(pos, mover, kingSq)) return "白脸将"
        if (isHeavenEarthCannons(pos, mover, kingSq)) return "天地炮"
        if (isSmotheredPalace(pos, mover, kingSq)) return "闷宫"
        if (isFishingHorse(pos, mover, kingSq)) return "钓鱼马"

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
    fun between(pos: Position, a: Int, b: Int): List<Int> {
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
     * 1. 马后炮: 炮与将之间恰好隔一个子, 且这个"炮架"是己方的马.
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
     * 2. 铁门栓: 车在同线照将, 同时己方兵(或帅/将)占据将的侧翼出口, 将的退路被"栓"住.
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
            if (pc.color != mover || (pc.type != 7 && pc.type != 6)) continue
            val r = Square.row(sq)
            val c = Square.col(sq)
            if (r == kr && abs(c - kc) == 1) return true
            if (c == kc && abs(r - kr) == 1) return true
        }
        return false
    }

    /**
     * 3. 重炮 (双炮): 己方两门炮在同一线, 且与对方将在同一直线上.
     * 后炮借前炮为炮架直接照将, 或前炮照将后炮紧随其后.
     */
    private fun isDoubleCannon(pos: Position, mover: Int, kingSq: Int): Boolean {
        val kr = Square.row(kingSq)
        val kc = Square.col(kingSq)
        val cannons = mutableListOf<Int>()
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color == mover && pc.type == 6) {
                if (Square.row(sq) == kr || Square.col(sq) == kc) {
                    cannons.add(sq)
                }
            }
        }
        if (cannons.size < 2) return false
        val c1 = cannons[0]
        val c2 = cannons[1]
        // 必须两炮在同一线
        if (Square.row(c1) != Square.row(c2) && Square.col(c1) != Square.col(c2)) return false
        // 两炮之间无杂子
        if (between(pos, c1, c2).isNotEmpty()) return false

        // 确定哪个离将近
        val dist1 = abs(Square.row(c1) - kr) + abs(Square.col(c1) - kc)
        val dist2 = abs(Square.row(c2) - kr) + abs(Square.col(c2) - kc)
        val front = if (dist1 < dist2) c1 else c2
        val back = if (dist1 < dist2) c2 else c1

        // 典型重炮: 后炮隔着前炮照将 (back 到 kingSq 之间恰好只有 front 一个子)
        val betweenBackAndKing = between(pos, back, kingSq)
        if (betweenBackAndKing.size == 1 && betweenBackAndKing[0] == front) return true

        // 或者前炮照将, 后炮与前炮紧贴 (距离为 1 格)
        val betweenFrontAndKing = between(pos, front, kingSq)
        if (betweenFrontAndKing.size == 1 && abs(Square.row(back) - Square.row(front)) + abs(Square.col(back) - Square.col(front)) == 1) {
            return true
        }

        return false
    }

    /**
     * 4. 双车错: 己方两车在相邻行或相邻列, 一车照将, 另一车卡死相邻通道.
     */
    private fun isDoubleRooks(pos: Position, mover: Int, kingSq: Int): Boolean {
        val kr = Square.row(kingSq)
        val kc = Square.col(kingSq)
        val rooks = mutableListOf<Int>()
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color == mover && pc.type == 5) rooks.add(sq)
        }
        if (rooks.size < 2) return false
        val r1 = rooks[0]
        val r2 = rooks[1]
        val r1Row = Square.row(r1)
        val r1Col = Square.col(r1)
        val r2Row = Square.row(r2)
        val r2Col = Square.col(r2)

        // 必须在相邻行或相邻列
        val adjacent = abs(r1Row - r2Row) == 1 || abs(r1Col - r2Col) == 1
        if (!adjacent) return false

        // 至少一车正在直线照将
        val r1Check = (r1Row == kr || r1Col == kc) && between(pos, r1, kingSq).isEmpty()
        val r2Check = (r2Row == kr || r2Col == kc) && between(pos, r2, kingSq).isEmpty()
        return r1Check || r2Check
    }

    /**
     * 5. 卧槽马: 马在对方底象位置 (3路/7路或2路/8路底象线) 控将或照将.
     */
    private fun isWoCaoHorse(pos: Position, mover: Int, kingSq: Int): Boolean {
        val isOpponentBlack = Square.row(kingSq) < 5
        // 卧槽位置: 黑方第1或2行, 红方第7或8行
        val validRows = if (isOpponentBlack) listOf(1, 2) else listOf(7, 8)
        val validCols = listOf(1, 2, 6, 7)

        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != mover || pc.type != 4) continue
            val r = Square.row(sq)
            val c = Square.col(sq)
            if (r in validRows && c in validCols) {
                // 马在卧槽位且距离将在日字步内
                val dr = abs(r - Square.row(kingSq))
                val dc = abs(c - Square.col(kingSq))
                if ((dr == 1 && dc == 2) || (dr == 2 && dc == 1)) return true
            }
        }
        return false
    }

    /**
     * 6. 挂角马: 马在对方九宫上角 (士角: 黑方row 2, col 3/5; 红方row 7, col 3/5).
     */
    private fun isCornerHorse(pos: Position, mover: Int, kingSq: Int): Boolean {
        val isOpponentBlack = Square.row(kingSq) < 5
        val targetRow = if (isOpponentBlack) 2 else 7
        val targetCols = listOf(3, 5)

        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != mover || pc.type != 4) continue
            val r = Square.row(sq)
            val c = Square.col(sq)
            if (r == targetRow && c in targetCols) {
                val dr = abs(r - Square.row(kingSq))
                val dc = abs(c - Square.col(kingSq))
                if ((dr == 1 && dc == 2) || (dr == 2 && dc == 1)) return true
            }
        }
        return false
    }

    /**
     * 7. 白脸将: 双方帅在同一列无任何子阻隔 (照面威慑助攻).
     */
    private fun isFaceToFaceKings(pos: Position, mover: Int, kingSq: Int): Boolean {
        val myKingSq = findKing(pos, mover)
        if (myKingSq < 0) return false
        if (Square.col(myKingSq) == Square.col(kingSq)) {
            val piecesBetween = between(pos, myKingSq, kingSq)
            if (piecesBetween.isEmpty()) return true
            // 帅/将同列为己方子力(车或兵)在背后撑腰助攻
            if (piecesBetween.size == 1) {
                val sq = piecesBetween[0]
                val p = Piece.fromCode(pos.squares[sq])
                if (p.color == mover) {
                    if (p.type == 5) return true
                    if (p.type == 7 && abs(Square.row(sq) - Square.row(kingSq)) == 1) return true
                }
            }
        }
        return false
    }

    /**
     * 8. 天地炮: 己方一炮在敌方底线, 另一炮在中路.
     */
    private fun isHeavenEarthCannons(pos: Position, mover: Int, kingSq: Int): Boolean {
        val isOpponentBlack = Square.row(kingSq) < 5
        val bottomRow = if (isOpponentBlack) 0 else 9
        val cannons = mutableListOf<Int>()
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color == mover && pc.type == 6) {
                cannons.add(sq)
            }
        }
        if (cannons.size < 2) return false
        // 至少有两门不同的炮分别处于底线和中路
        var hasBottom = false
        var hasCenter = false
        for (c in cannons) {
            if (Square.row(c) == bottomRow) hasBottom = true
            if (Square.col(c) == 4) hasCenter = true
        }
        if (!hasBottom || !hasCenter) return false
        // 确认不是同一门炮同时满足(例如底线中路)
        return cannons.any { Square.row(it) == bottomRow && Square.col(it) != 4 } ||
                cannons.any { Square.col(it) == 4 && Square.row(it) != bottomRow }
    }

    /**
     * 9. 大刀剜心: 己方车攻入对方九宫中心 (黑方 row 1 col 4, 红方 row 8 col 4).
     */
    private fun isHeartCarvingRook(pos: Position, mover: Int, kingSq: Int): Boolean {
        val isOpponentBlack = Square.row(kingSq) < 5
        val heartRow = if (isOpponentBlack) 1 else 8
        val heartSq = Square.make(heartRow, 4)
        val p = pos.squares[heartSq]
        if (p == Piece.NONE.code) return false
        val pc = Piece.fromCode(p)
        if (pc.color == mover && pc.type == 5) {
            // 车在九宫心直接贴身照将
            val dr = abs(heartRow - Square.row(kingSq))
            val dc = abs(4 - Square.col(kingSq))
            return (dr + dc) == 1
        }
        return false
    }

    /**
     * 10. 闷宫: 对方将位于底线中路, 且双士都在底线原位将退路堵死, 己方炮正照将.
     */
    private fun isSmotheredPalace(pos: Position, mover: Int, kingSq: Int): Boolean {
        val isOpponentBlack = Square.row(kingSq) < 5
        val baseRow = if (isOpponentBlack) 0 else 9
        if (Square.row(kingSq) != baseRow || Square.col(kingSq) != 4) return false

        // 检查双士是否在原位 (0,3) 和 (0,5)
        val oppAdvisorCode = if (isOpponentBlack) Piece.B_ADVISOR.code else Piece.R_ADVISOR.code
        val a1 = pos.squares[Square.make(baseRow, 3)]
        val a2 = pos.squares[Square.make(baseRow, 5)]
        if (a1 != oppAdvisorCode || a2 != oppAdvisorCode) return false

        // 己方炮在中路照将
        for (r in 0 until Square.ROWS) {
            val sq = Square.make(r, 4)
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color == mover && pc.type == 6) {
                if (between(pos, sq, kingSq).size == 1) return true
            }
        }
        return false
    }

    /**
     * 11. 钓鱼马: 己方马在对方 3/7 路线三分点 (黑方 row 2, col 2/6; 红方 row 7, col 2/6) 控制将门，配合其他子照将。
     */
    private fun isFishingHorse(pos: Position, mover: Int, kingSq: Int): Boolean {
        val isOpponentBlack = Square.row(kingSq) < 5
        val targetRow = if (isOpponentBlack) 2 else 7
        val targetCols = listOf(2, 6)
        for (sq in 0 until Square.COUNT) {
            val p = pos.squares[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != mover || pc.type != 4) continue
            val r = Square.row(sq)
            val c = Square.col(sq)
            if (r == targetRow && c in targetCols) {
                val kr = Square.row(kingSq)
                val kc = Square.col(kingSq)
                if (abs(r - kr) <= 2 && abs(c - kc) <= 3) return true
            }
        }
        return false
    }
}
