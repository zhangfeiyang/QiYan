import Foundation

/// 走法生成 (走子规则的唯一来源; `Rules.isPseudoLegal` 也走这里).
enum MoveGenerator {
    private static let orthogonal = [(-1, 0), (1, 0), (0, -1), (0, 1)]
    private static let diagonal = [(-1, -1), (-1, 1), (1, -1), (1, 1)]
    /// (dr, dc, 马腿 dr, 马腿 dc)
    private static let horseDirs = [(-2, -1, -1, 0), (-2, 1, -1, 0), (2, -1, 1, 0), (2, 1, 1, 0),
                                    (-1, -2, 0, -1), (1, -2, 0, -1), (-1, 2, 0, 1), (1, 2, 0, 1)]
    private static let elephantDirs = [(-2, -2), (-2, 2), (2, -2), (2, 2)]

    /// 全部合法吃子 (静止搜索/陪练用)
    static func generateCaptures(_ pos: Position, _ moves: inout [Move]) {
        let start = moves.count
        for sq in 0..<Square.count {
            let p = pos.squares[sq]
            if p == 0 || Piece.fromCode(p).color != pos.sideToMove { continue }
            generateForSquare(pos, sq, &moves, capturesOnly: true)
        }
        filterLegal(pos, &moves, from: start)
    }

    /// 全部合法走法
    static func generateAll(_ pos: Position, _ moves: inout [Move]) {
        let start = moves.count
        generatePseudo(pos, &moves)
        filterLegal(pos, &moves, from: start)
    }

    static func generatePseudo(_ pos: Position, _ moves: inout [Move], capturesOnly: Bool = false) {
        for sq in 0..<Square.count {
            let p = pos.squares[sq]
            if p == 0 || Piece.fromCode(p).color != pos.sideToMove { continue }
            generateForSquare(pos, sq, &moves, capturesOnly: capturesOnly)
        }
    }

    private static func filterLegal(_ pos: Position, _ moves: inout [Move], from start: Int) {
        var kept = Array(moves[..<start])
        for m in moves[start...] where pos.leavesKingSafe(m) { kept.append(m) }
        moves = kept
    }

    /// 某格棋子的全部伪合法走法
    static func generateForSquare(_ pos: Position, _ sq: Int, _ moves: inout [Move], capturesOnly: Bool = false) {
        let p = pos.squares[sq]
        guard p != 0 else { return }
        let piece = Piece.fromCode(p)
        let color = piece.color
        let r = Square.row(sq), c = Square.col(sq)

        func add(_ toR: Int, _ toC: Int) {
            let to = Square.make(toR, toC)
            let tgt = pos.squares[to]
            if tgt != 0 && Piece.fromCode(tgt).color == color { return }
            if capturesOnly && tgt == 0 { return }
            moves.append(Move(sq, to))
        }
        func inPalace(_ toR: Int, _ toC: Int) -> Bool {
            toC >= 3 && toC <= 5 && (color == Side.red ? (toR >= 7 && toR <= 9) : (toR >= 0 && toR <= 2))
        }

        switch piece.type {
        case 1:  // 帅/将
            for (dr, dc) in orthogonal {
                let toR = r + dr, toC = c + dc
                if Square.valid(toR, toC) && inPalace(toR, toC) { add(toR, toC) }
            }
        case 2:  // 仕/士
            for (dr, dc) in diagonal {
                let toR = r + dr, toC = c + dc
                if Square.valid(toR, toC) && inPalace(toR, toC) { add(toR, toC) }
            }
        case 3:  // 相/象: 不过河, 塞象眼
            if color == Side.red && r <= 4 { return }
            if color == Side.black && r >= 5 { return }
            for (dr, dc) in elephantDirs {
                let toR = r + dr, toC = c + dc
                guard Square.valid(toR, toC) else { continue }
                if color == Side.red && toR <= 4 { continue }
                if color == Side.black && toR >= 5 { continue }
                if pos.pieceAt(r + dr / 2, c + dc / 2) != 0 { continue }
                add(toR, toC)
            }
        case 4:  // 马: 蹩马腿
            for (dr, dc, lr, lc) in horseDirs {
                let toR = r + dr, toC = c + dc
                guard Square.valid(toR, toC) else { continue }
                if pos.squares[Square.make(r + lr, c + lc)] != 0 { continue }
                add(toR, toC)
            }
        case 5:  // 车
            for (dr, dc) in orthogonal {
                var step = 1
                while true {
                    let toR = r + dr * step, toC = c + dc * step
                    guard Square.valid(toR, toC) else { break }
                    let tgt = pos.squares[Square.make(toR, toC)]
                    if tgt == 0 {
                        if !capturesOnly { moves.append(Move(sq, Square.make(toR, toC))) }
                    } else {
                        if Piece.fromCode(tgt).color != color { moves.append(Move(sq, Square.make(toR, toC))) }
                        break
                    }
                    step += 1
                }
            }
        case 6:  // 炮: 走同车, 吃隔一子
            for (dr, dc) in orthogonal {
                var step = 1
                var jumped = false
                while true {
                    let toR = r + dr * step, toC = c + dc * step
                    guard Square.valid(toR, toC) else { break }
                    let tgt = pos.squares[Square.make(toR, toC)]
                    if !jumped {
                        if tgt == 0 {
                            if !capturesOnly { moves.append(Move(sq, Square.make(toR, toC))) }
                        } else {
                            jumped = true
                        }
                    } else if tgt != 0 {
                        if Piece.fromCode(tgt).color != color { moves.append(Move(sq, Square.make(toR, toC))) }
                        break
                    }
                    step += 1
                }
            }
        case 7:  // 兵/卒: 未过河只能前进
            let forward = color == Side.red ? -1 : 1
            let toR = r + forward
            if toR >= 0 && toR < Square.rows { add(toR, c) }
            let crossed = color == Side.red ? r <= 4 : r >= 5
            if crossed {
                for dc in [-1, 1] where c + dc >= 0 && c + dc < Square.cols { add(r, c + dc) }
            }
        default:
            break
        }
    }
}

/// 规则判定: 将军、将死、困毙、终局.
enum Rules {
    enum GameOver {
        /// CHECKMATE_RED 读作"红方被将死"(黑胜)
        case none, checkmateRed, checkmateBlack, stalemate, repetition, fiftyMoves, resign
    }

    static func findKing(_ pos: Position, _ color: Int) -> Int {
        let code = color == Side.red ? Piece.rKing.code : Piece.bKing.code
        for sq in 0..<Square.count where pos.squares[sq] == code { return sq }
        return -1
    }

    /// 某一方是否被将军
    static func isInCheck(_ pos: Position, _ color: Int) -> Bool {
        let kingSq = findKing(pos, color)
        guard kingSq >= 0 else { return false }
        let opp = Side.opposite(color)
        if isFlyingGeneral(pos, kingSq) { return true }
        let kr = Square.row(kingSq), kc = Square.col(kingSq)

        // 车/炮沿四条直线
        for (dr, dc) in [(-1, 0), (1, 0), (0, -1), (0, 1)] {
            var step = 1
            var screened = false
            while true {
                let r = kr + dr * step, c = kc + dc * step
                guard Square.valid(r, c) else { break }
                step += 1
                let p = pos.squares[Square.make(r, c)]
                if p == 0 { continue }
                let pt = Piece.fromCode(p)
                if !screened {
                    if pt.color == opp && pt.type == 5 { return true }
                    screened = true
                } else {
                    if pt.color == opp && pt.type == 6 { return true }
                    break
                }
            }
        }

        // 马: 马腿紧挨着马 (从将反推是对角格)
        for (dr, dc) in [(-2, -1), (-2, 1), (2, -1), (2, 1), (-1, -2), (1, -2), (-1, 2), (1, 2)] {
            let r = kr + dr, c = kc + dc
            guard Square.valid(r, c) else { continue }
            let legR = kr + (dr > 0 ? 1 : -1), legC = kc + (dc > 0 ? 1 : -1)
            if pos.squares[Square.make(legR, legC)] != 0 { continue }
            let p = Piece.fromCode(pos.squares[Square.make(r, c)])
            if p.color == opp && p.type == 4 { return true }
        }

        // 兵/卒: 正前方, 或过河后横向
        let pawnRow = color == Side.red ? kr - 1 : kr + 1
        if pawnRow >= 0 && pawnRow < Square.rows {
            let p = Piece.fromCode(pos.squares[Square.make(pawnRow, kc)])
            if p.color == opp && p.type == 7 { return true }
        }
        for dc in [-1, 1] {
            let c = kc + dc
            guard c >= 0 && c < Square.cols else { continue }
            let p = Piece.fromCode(pos.squares[Square.make(kr, c)])
            if p.color == opp && p.type == 7 {
                let crossed = opp == Side.red ? kr <= 4 : kr >= 5
                if crossed { return true }
            }
        }
        return false
    }

    /// 对脸将(飞行将军)
    static func isFlyingGeneral(_ pos: Position, _ kingSq: Int) -> Bool {
        let col = Square.col(kingSq)
        let color = Piece.fromCode(pos.squares[kingSq]).color
        let oppKing = color == Side.red ? Piece.bKing.code : Piece.rKing.code
        for r in 0..<Square.rows {
            let sq = Square.make(r, col)
            if sq == kingSq || pos.squares[sq] != oppKing { continue }
            let lo = min(r, Square.row(kingSq)), hi = max(r, Square.row(kingSq))
            for rr in (lo + 1)..<max(lo + 1, hi) where pos.squares[Square.make(rr, col)] != 0 { return false }
            return true
        }
        return false
    }

    /// 是否符合该子的行棋规则 (可能送将)
    static func isPseudoLegal(_ pos: Position, _ move: Move) -> Bool {
        let from = move.from, to = move.to
        guard from != to, Square.valid(from), Square.valid(to) else { return false }
        let piece = pos.squares[from]
        guard piece != 0, Piece.fromCode(piece).color == pos.sideToMove else { return false }
        var candidates: [Move] = []
        MoveGenerator.generateForSquare(pos, from, &candidates)
        return candidates.contains { $0.to == to }
    }

    static func generateAllLegal(_ pos: Position, _ moves: inout [Move]) {
        MoveGenerator.generateAll(pos, &moves)
    }

    static func isCheckmate(_ pos: Position) -> Bool {
        isInCheck(pos, pos.sideToMove) && pos.legalMoves().isEmpty
    }

    static func isStalemate(_ pos: Position) -> Bool {
        !isInCheck(pos, pos.sideToMove) && pos.legalMoves().isEmpty
    }

    /// 走不动的一方输: 轮到红走却被将死 = checkmateRed (黑胜)
    static func gameOver(_ pos: Position) -> GameOver {
        if pos.legalMoves().isEmpty {
            if isInCheck(pos, pos.sideToMove) {
                return pos.sideToMove == Side.red ? .checkmateRed : .checkmateBlack
            }
            return .stalemate
        }
        if pos.isThreefoldRepetition() { return .repetition }
        if pos.isFiftyMoveDraw() { return .fiftyMoves }
        return .none
    }
}
