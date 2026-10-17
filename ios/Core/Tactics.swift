import Foundation

/// 经典杀法局面检测 (将军/绝杀时的专属播报). 返回中文键, 界面经 `TacticNames` 本地化.
enum Tactics {
    /// pos: 走子完成后的局面; mover: 刚走子(正在将军)的一方
    static func detect(_ pos: Position, _ mover: Int) -> String? {
        let kingSide = Side.opposite(mover)
        guard Rules.isInCheck(pos, kingSide) else { return nil }
        let king = Rules.findKing(pos, kingSide)
        guard king >= 0 else { return nil }
        if isHorseBehindCannon(pos, mover, king) { return "马后炮" }
        if isIronDoorBolt(pos, mover, king) { return "铁门栓" }
        if isDoubleCannon(pos, mover, king) { return "重炮" }
        if isDoubleRooks(pos, mover, king) { return "双车错" }
        if isWoCaoHorse(pos, mover, king) { return "卧槽马" }
        if isCornerHorse(pos, mover, king) { return "挂角马" }
        if isHeartCarvingRook(pos, mover, king) { return "大刀剜心" }
        if isFaceToFaceKings(pos, mover, king) { return "白脸将" }
        if isHeavenEarthCannons(pos, mover, king) { return "天地炮" }
        if isSmotheredPalace(pos, mover, king) { return "闷宫" }
        if isFishingHorse(pos, mover, king) { return "钓鱼马" }
        return nil
    }

    /// 同线两格之间的棋子 (不含两端); 不在同一行/列返回空
    static func between(_ pos: Position, _ a: Int, _ b: Int) -> [Int] {
        let ar = Square.row(a), ac = Square.col(a), br = Square.row(b), bc = Square.col(b)
        guard ar == br || ac == bc else { return [] }
        let dr = max(-1, min(1, br - ar)), dc = max(-1, min(1, bc - ac))
        var out: [Int] = []
        var r = ar + dr, c = ac + dc
        while r != br || c != bc {
            let sq = Square.make(r, c)
            if pos.squares[sq] != 0 { out.append(sq) }
            r += dr
            c += dc
        }
        return out
    }

    private static func pieces(_ pos: Position, _ color: Int, _ type: Int) -> [Int] {
        (0..<Square.count).filter {
            let p = Piece.fromCode(pos.squares[$0])
            return p.color == color && p.type == type
        }
    }

    private static func knightApart(_ a: Int, _ b: Int) -> Bool {
        let dr = abs(Square.row(a) - Square.row(b)), dc = abs(Square.col(a) - Square.col(b))
        return (dr == 1 && dc == 2) || (dr == 2 && dc == 1)
    }

    private static func isHorseBehindCannon(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        pieces(pos, mover, 6).contains { sq in
            let mid = between(pos, sq, king)
            guard mid.count == 1 else { return false }
            let screen = Piece.fromCode(pos.squares[mid[0]])
            return screen.type == 4 && screen.color == mover
        }
    }

    private static func isIronDoorBolt(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let kr = Square.row(king), kc = Square.col(king)
        let rookCheck = pieces(pos, mover, 5).contains {
            (Square.row($0) == kr || Square.col($0) == kc) && between(pos, $0, king).isEmpty
        }
        guard rookCheck else { return false }
        for sq in 0..<Square.count {
            let pc = Piece.fromCode(pos.squares[sq])
            guard pc.color == mover, pc.type == 7 || pc.type == 6 else { continue }
            let r = Square.row(sq), c = Square.col(sq)
            if r == kr && abs(c - kc) == 1 { return true }
            if c == kc && abs(r - kr) == 1 { return true }
        }
        return false
    }

    private static func isDoubleCannon(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let kr = Square.row(king), kc = Square.col(king)
        let cannons = pieces(pos, mover, 6).filter { Square.row($0) == kr || Square.col($0) == kc }
        guard cannons.count >= 2 else { return false }
        let c1 = cannons[0], c2 = cannons[1]
        guard Square.row(c1) == Square.row(c2) || Square.col(c1) == Square.col(c2) else { return false }
        guard between(pos, c1, c2).isEmpty else { return false }
        let d1 = abs(Square.row(c1) - kr) + abs(Square.col(c1) - kc)
        let d2 = abs(Square.row(c2) - kr) + abs(Square.col(c2) - kc)
        let front = d1 < d2 ? c1 : c2, back = d1 < d2 ? c2 : c1
        let backLine = between(pos, back, king)
        if backLine.count == 1 && backLine[0] == front { return true }
        let frontLine = between(pos, front, king)
        let gap = abs(Square.row(back) - Square.row(front)) + abs(Square.col(back) - Square.col(front))
        return frontLine.count == 1 && gap == 1
    }

    private static func isDoubleRooks(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let rooks = pieces(pos, mover, 5)
        guard rooks.count >= 2 else { return false }
        let r1 = rooks[0], r2 = rooks[1]
        let adjacent = abs(Square.row(r1) - Square.row(r2)) == 1 || abs(Square.col(r1) - Square.col(r2)) == 1
        guard adjacent else { return false }
        let kr = Square.row(king), kc = Square.col(king)
        func checks(_ r: Int) -> Bool {
            (Square.row(r) == kr || Square.col(r) == kc) && between(pos, r, king).isEmpty
        }
        return checks(r1) || checks(r2)
    }

    private static func isWoCaoHorse(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let oppBlack = Square.row(king) < 5
        let rows = oppBlack ? [1, 2] : [7, 8]
        return pieces(pos, mover, 4).contains {
            rows.contains(Square.row($0)) && [1, 2, 6, 7].contains(Square.col($0)) && knightApart($0, king)
        }
    }

    private static func isCornerHorse(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let row = Square.row(king) < 5 ? 2 : 7
        return pieces(pos, mover, 4).contains {
            Square.row($0) == row && [3, 5].contains(Square.col($0)) && knightApart($0, king)
        }
    }

    private static func isFaceToFaceKings(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let mine = Rules.findKing(pos, mover)
        guard mine >= 0, Square.col(mine) == Square.col(king) else { return false }
        let mid = between(pos, mine, king)
        if mid.isEmpty { return true }
        if mid.count == 1 {
            let p = Piece.fromCode(pos.squares[mid[0]])
            if p.color == mover {
                if p.type == 5 { return true }
                if p.type == 7 && abs(Square.row(mid[0]) - Square.row(king)) == 1 { return true }
            }
        }
        return false
    }

    private static func isHeavenEarthCannons(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let bottom = Square.row(king) < 5 ? 0 : 9
        let cannons = pieces(pos, mover, 6)
        guard cannons.count >= 2 else { return false }
        let hasBottom = cannons.contains { Square.row($0) == bottom }
        let hasCenter = cannons.contains { Square.col($0) == 4 }
        guard hasBottom && hasCenter else { return false }
        return cannons.contains { Square.row($0) == bottom && Square.col($0) != 4 }
            || cannons.contains { Square.col($0) == 4 && Square.row($0) != bottom }
    }

    private static func isHeartCarvingRook(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let heartRow = Square.row(king) < 5 ? 1 : 8
        let pc = Piece.fromCode(pos.squares[Square.make(heartRow, 4)])
        guard pc.color == mover, pc.type == 5 else { return false }
        return abs(heartRow - Square.row(king)) + abs(4 - Square.col(king)) == 1
    }

    private static func isSmotheredPalace(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let oppBlack = Square.row(king) < 5
        let base = oppBlack ? 0 : 9
        guard Square.row(king) == base, Square.col(king) == 4 else { return false }
        let advisor = oppBlack ? Piece.bAdvisor.code : Piece.rAdvisor.code
        guard pos.squares[Square.make(base, 3)] == advisor, pos.squares[Square.make(base, 5)] == advisor else { return false }
        for r in 0..<Square.rows {
            let sq = Square.make(r, 4)
            let pc = Piece.fromCode(pos.squares[sq])
            if pc.color == mover && pc.type == 6 && between(pos, sq, king).count == 1 { return true }
        }
        return false
    }

    private static func isFishingHorse(_ pos: Position, _ mover: Int, _ king: Int) -> Bool {
        let row = Square.row(king) < 5 ? 2 : 7
        return pieces(pos, mover, 4).contains {
            Square.row($0) == row && [2, 6].contains(Square.col($0))
                && abs(Square.row($0) - Square.row(king)) <= 2 && abs(Square.col($0) - Square.col(king)) <= 3
        }
    }
}

/// 经典开局定式检测 (按 UCI 着法前缀取最长匹配). 返回中文键.
enum OpeningDetector {
    private static let lines: [(String, [String])] = [
        ("中炮对屏风马", ["h2e2", "h9g7", "h0g2", "i9h9", "i0h0"]),
        ("中炮对屏风马", ["h2e2", "h9g7", "h0g2", "i9h9"]),
        ("中炮对屏风马", ["h2e2", "h9g7"]),
        ("中炮对屏风马", ["b2e2", "b9c7", "b0c2", "a9b9", "a0b0"]),
        ("中炮对屏风马", ["b2e2", "b9c7", "b0c2", "a9b9"]),
        ("中炮对屏风马", ["b2e2", "b9c7"]),
        ("顺手炮", ["h2e2", "h7e7", "h0g2", "b9c7"]),
        ("顺手炮", ["h2e2", "h7e7"]),
        ("顺手炮", ["b2e2", "b7e7"]),
        ("列手炮", ["h2e2", "b7e7"]),
        ("列手炮", ["b2e2", "h7e7"]),
        ("中炮对反宫马", ["h2e2", "b9c7", "h0g2", "h7f7"]),
        ("中炮对反宫马", ["h2e2", "h9g7", "b0c2", "b7d7"]),
        ("中炮对单提马", ["h2e2", "b9c7", "h0g2", "h9i7"]),
        ("中炮对单提马", ["h2e2", "h9g7", "h0g2", "b9a7"]),
        ("中炮对三步虎", ["h2e2", "i9h9", "h0g2", "h9g7"]),
        ("仙人指路对卒底炮", ["c3c4", "b7c7"]),
        ("仙人指路对卒底炮", ["c3c4", "h7g7"]),
        ("仙人指路对卒底炮", ["g3g4", "h7g7"]),
        ("仙人指路对卒底炮", ["g3g4", "b7c7"]),
        ("仙人指路互进卒", ["c3c4", "c6c5"]),
        ("仙人指路互进卒", ["g3g4", "g6g5"]),
        ("仙人指路对中炮", ["c3c4", "h7e7"]),
        ("仙人指路对中炮", ["c3c4", "b7e7"]),
        ("仙人指路对进马", ["c3c4", "h9g7"]),
        ("仙人指路对进马", ["g3g4", "b9c7"]),
        ("仙人指路对飞相", ["c3c4", "g9e7"]),
        ("飞相局对左中炮", ["g0e2", "h7e7"]),
        ("飞相局对右中炮", ["c0e2", "b7e7"]),
        ("飞相局对进马", ["g0e2", "h9g7"]),
        ("飞相局对进马", ["c0e2", "b9c7"]),
        ("飞相局对进卒", ["g0e2", "g6g5"]),
        ("飞相局对进卒", ["c0e2", "c6c5"]),
        ("飞相局对士角炮", ["g0e2", "h7f7"]),
        ("飞相局对过宫炮", ["g0e2", "h7d7"]),
        ("起马局对挺卒", ["h0g2", "c6c5"]),
        ("起马局对挺卒", ["b0c2", "g6g5"]),
        ("起马局对中炮", ["h0g2", "h7e7"]),
        ("起马局对中炮", ["b0c2", "b7e7"]),
        ("起马局对进马", ["h0g2", "h9g7"]),
        ("起马局对进马", ["b0c2", "b9c7"]),
        ("当头炮", ["h2e2"]), ("当头炮", ["b2e2"]),
        ("仙人指路", ["c3c4"]), ("仙人指路", ["g3g4"]),
        ("飞相局", ["g0e2"]), ("飞相局", ["c0e2"]),
        ("起马局", ["h0g2"]), ("起马局", ["b0c2"]),
        ("过宫炮", ["h2d2"]), ("过宫炮", ["b2f2"]),
        ("士角炮", ["h2f2"]), ("士角炮", ["b2d2"]),
        ("金钩炮", ["h2g2"]), ("金钩炮", ["b2c2"]),
        ("铁滑车", ["i0i1"]), ("铁滑车", ["a0a1"]),
        ("边马局", ["h0i2"]), ("边马局", ["b0a2"]),
        ("边兵局", ["a3a4"]), ("边兵局", ["i3i4"]),
    ]

    static func detect(_ uciMoves: [String]) -> String? {
        guard !uciMoves.isEmpty else { return nil }
        var best: String?
        var maxLen = 0
        for (name, prefix) in lines where prefix.count <= uciMoves.count && prefix.count > maxLen {
            if Array(uciMoves.prefix(prefix.count)) == prefix {
                best = name
                maxLen = prefix.count
            }
        }
        return best
    }
}
