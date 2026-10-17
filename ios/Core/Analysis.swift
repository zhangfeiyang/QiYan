import Foundation

/// UCI 坐标 ↔ 内部坐标. UCI rank 0 = 红方底线 = 内部 row 9, 所以 rank = 9 - row.
enum UciCoords {
    static let nullMove = "0000"

    static func flipRow(_ row: Int) -> Int { Square.rows - 1 - row }

    static func toUci(_ move: Move) -> String {
        move.from == move.to ? nullMove : squareToUci(move.from) + squareToUci(move.to)
    }

    static func squareToUci(_ sq: Int) -> String {
        let file = Character(UnicodeScalar(UInt8(97 + Square.col(sq))))
        return "\(file)\(flipRow(Square.row(sq)))"
    }

    /// 格式非法或越界返回 nil; 是否合法仍需 `Position.isLegalMove`
    static func parse(_ uci: String) -> Move? {
        let chars = Array(uci)
        guard chars.count >= 4, uci != nullMove,
              let fa = chars[0].asciiValue, let ta = chars[2].asciiValue,
              let fr = chars[1].wholeNumberValue, chars[1].isASCII,
              let tr = chars[3].wholeNumberValue, chars[3].isASCII else { return nil }
        let fromCol = Int(fa) - 97, toCol = Int(ta) - 97
        let fromRow = flipRow(fr), toRow = flipRow(tr)
        guard Square.valid(fromRow, fromCol), Square.valid(toRow, toCol) else { return nil }
        return Move(Square.make(fromRow, fromCol), Square.make(toRow, toCol))
    }
}

/// 一条威胁: 走子方从 attacker 可以吃掉 target 上的对方子.
struct Threat: Equatable {
    let target: Int
    let attacker: Int
    let attackerColor: Int
}

/// 攻防威胁: 走子方下一步能吃到的对方子 (视觉提示用, 不做交换判断).
enum Threats {
    static func findThreats(_ pos: Position) -> [Threat] {
        let stm = pos.sideToMove
        return pos.legalMoves().compactMap { m in
            let victim = pos.pieceAt(m.to)
            guard victim != 0, Piece.fromCode(victim).color == Side.opposite(stm) else { return nil }
            return Threat(target: m.to, attacker: m.from, attackerColor: stm)
        }
    }
}

/// 浅层杀棋判断: 红方一步将死 (校验千题库).
enum MateSearch {
    static func matingMove(_ pos: Position) -> Move? {
        if Rules.isInCheck(pos, Side.red) || Rules.isInCheck(pos, Side.black) { return nil }
        for move in pos.legalMoves() {
            if Piece.fromCode(pos.pieceAt(move.to)).type == 1 { continue }
            guard pos.doMove(move) else { continue }
            let mate = pos.sideToMove == Side.black && Rules.isInCheck(pos, Side.black) && pos.legalMoves().isEmpty
            pos.undoMove()
            if mate { return move }
        }
        return nil
    }

    static func isMateInOne(_ pos: Position) -> Bool { matingMove(pos) != nil }
}

/// 静态交换评估. 每吃一步都重新枚举攻击者 (炮架会随兑子出现/消失).
enum See {
    static let kingValue = 10000
    static let maxSwaps = 32

    static func value(_ pieceCode: Int) -> Int {
        switch Piece.fromCode(pieceCode).type {
        case 1: return kingValue
        case 2, 3: return 200
        case 4: return 400
        case 5: return 900
        case 6: return 450
        case 7: return 100
        default: return 0
        }
    }

    /// 走这一步吃子后在落点连续兑完的净收益 (走子方视角, 厘兵). 非吃子返回 0.
    static func evaluate(_ pos: Position, _ move: Move) -> Int {
        let to = move.to
        let victim = pos.squares[to]
        guard victim != 0 else { return 0 }
        let attacker = pos.squares[move.from]
        guard attacker != 0 else { return 0 }
        var board = pos.squares
        var side = Side.opposite(Piece.fromCode(attacker).color)
        var gain = [Int](repeating: 0, count: maxSwaps)
        gain[0] = value(victim)
        board[to] = attacker
        board[move.from] = 0
        var d = 1
        var onSquare = attacker
        while d < maxSwaps {
            let from = leastValuableAttacker(board, to, side)
            if from < 0 { break }
            gain[d] = value(onSquare) - gain[d - 1]
            if max(-gain[d - 1], gain[d]) < 0 { break }
            onSquare = board[from]
            board[to] = onSquare
            board[from] = 0
            side = Side.opposite(side)
            d += 1
        }
        while d > 1 {
            d -= 1
            gain[d - 1] = -max(-gain[d - 1], gain[d])
        }
        return gain[0]
    }

    static func leastValuableAttacker(_ board: [Int], _ to: Int, _ color: Int) -> Int {
        var best = -1
        var bestVal = Int.max
        forEachAttacker(board, to, color) { from in
            let v = value(board[from])
            if v < bestVal { bestVal = v; best = from }
        }
        return best
    }

    static func crossedRiver(_ row: Int, _ color: Int) -> Bool { color == Side.red ? row <= 4 : row >= 5 }

    static func inPalace(_ row: Int, _ col: Int, _ color: Int) -> Bool {
        col >= 3 && col <= 5 && (color == Side.red ? (row >= 7 && row <= 9) : (row >= 0 && row <= 2))
    }

    private static let lineDirs = [(-1, 0), (1, 0), (0, -1), (0, 1)]
    /// (攻击者相对目标的偏移, 从攻击者走向目标时的马腿偏移)
    private static let horseFrom = [(-2, -1, 1, 0), (-2, 1, 1, 0), (2, -1, -1, 0), (2, 1, -1, 0),
                                    (-1, -2, 0, 1), (1, -2, 0, 1), (-1, 2, 0, -1), (1, 2, 0, -1)]
    private static let elephantDirs = [(-2, -2), (-2, 2), (2, -2), (2, 2)]
    private static let advisorDirs = [(-1, -1), (-1, 1), (1, -1), (1, 1)]

    /// 枚举 color 方所有能吃到 to 的子 (几何意义, 不查送将)
    static func forEachAttacker(_ board: [Int], _ to: Int, _ color: Int, _ visit: (Int) -> Void) {
        let tr = Square.row(to), tc = Square.col(to)

        for (dr, dc) in lineDirs {
            var step = 1
            var screen = 0
            while true {
                let r = tr + dr * step, c = tc + dc * step
                guard Square.valid(r, c) else { break }
                let sq = Square.make(r, c)
                let p = board[sq]
                if p != 0 {
                    let pc = Piece.fromCode(p)
                    if pc.color == color {
                        if screen == 0 && pc.type == 5 { visit(sq) }
                        else if screen == 1 && pc.type == 6 { visit(sq) }
                        else if screen == 0 && pc.type == 1 && step == 1 && inPalace(tr, tc, color) { visit(sq) }
                    }
                    screen += 1
                    if screen > 1 { break }
                }
                step += 1
            }
        }

        for (dr, dc, lr, lc) in horseFrom {
            let r = tr + dr, c = tc + dc
            guard Square.valid(r, c) else { continue }
            let sq = Square.make(r, c)
            let pc = Piece.fromCode(board[sq])
            guard pc.color == color, pc.type == 4 else { continue }
            if board[Square.make(r + lr, c + lc)] == 0 { visit(sq) }
        }

        for (dr, dc) in elephantDirs {
            let r = tr + dr, c = tc + dc
            guard Square.valid(r, c) else { continue }
            let sq = Square.make(r, c)
            let pc = Piece.fromCode(board[sq])
            guard pc.color == color, pc.type == 3 else { continue }
            if crossedRiver(tr, color) { continue }
            if board[Square.make(r + dr / -2, c + dc / -2)] != 0 { continue }
            visit(sq)
        }

        for (dr, dc) in advisorDirs {
            let r = tr + dr, c = tc + dc
            guard Square.valid(r, c) else { continue }
            let sq = Square.make(r, c)
            let pc = Piece.fromCode(board[sq])
            guard pc.color == color, pc.type == 2 else { continue }
            guard inPalace(tr, tc, color), inPalace(r, c, color) else { continue }
            visit(sq)
        }

        let back = color == Side.red ? 1 : -1
        let r = tr + back
        if r >= 0 && r < Square.rows {
            let sq = Square.make(r, tc)
            let pc = Piece.fromCode(board[sq])
            if pc.color == color && pc.type == 7 { visit(sq) }
        }
        for dc in [-1, 1] {
            let c = tc + dc
            guard c >= 0 && c < Square.cols else { continue }
            let sq = Square.make(tr, c)
            let pc = Piece.fromCode(board[sq])
            if pc.color == color && pc.type == 7 && crossedRiver(tr, color) { visit(sq) }
        }
    }
}
