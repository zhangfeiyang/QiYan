import Foundation

/// Zobrist 哈希键 (置换/重复检测). 固定种子, 每次启动相同.
enum Zobrist {
    private(set) static var pieceSquare: [[UInt64]] = table(seed: 0xCAFE_BABE)
    private(set) static var side: UInt64 = 0x9E37_79B9_7F4A_7C15

    /// 测试用: 换一组固定种子
    static func reseed(_ seed: UInt64) {
        pieceSquare = table(seed: seed)
    }

    private static func table(seed: UInt64) -> [[UInt64]] {
        var state = seed
        func next() -> UInt64 {  // SplitMix64
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
        var t = [[UInt64]](repeating: [UInt64](repeating: 0, count: Square.count), count: 16)
        for p in 1...15 { for sq in 0..<Square.count { t[p][sq] = next() } }
        return t
    }
}

/// 棋盘局面: 90 格、走子方、哈希、悔棋栈、重复检测历史. 与安卓 `Position` 同语义.
final class Position {
    struct UndoInfo {
        let from: Int, to: Int, moved: Int, captured: Int, prevHalfmoveClock: Int
    }

    var squares = [Int](repeating: 0, count: Square.count)
    var sideToMove = Side.red
    var hash: UInt64 = 0
    /// 距上次吃子的半回合数(自然限着)
    private(set) var halfmoveClock = 0
    private(set) var fullmoveNumber = 1
    private var moveStack: [UndoInfo] = []
    private(set) var historyHashes: [UInt64] = []
    private var nullMoveDepth = 0

    init() { setupStartPosition() }

    static let startFen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"

    func setupStartPosition() {
        _ = setFromFen(Position.startFen)
    }

    func computeHash() -> UInt64 {
        var h: UInt64 = 0
        for sq in 0..<Square.count where squares[sq] != 0 {
            h ^= Zobrist.pieceSquare[squares[sq]][sq]
        }
        if sideToMove == Side.black { h ^= Zobrist.side }
        return h
    }

    @inline(__always) func pieceAt(_ sq: Int) -> Int { squares[sq] }
    func pieceAt(_ row: Int, _ col: Int) -> Int { squares[Square.make(row, col)] }

    /// 空着: 只交出行棋权, 不进历史栈. 必须与 `undoNullMove` 配对.
    func doNullMove() {
        hash ^= Zobrist.side
        sideToMove = Side.opposite(sideToMove)
        nullMoveDepth += 1
    }

    func undoNullMove() {
        if nullMoveDepth > 0 { nullMoveDepth -= 1 }
        sideToMove = Side.opposite(sideToMove)
        hash ^= Zobrist.side
    }

    /// 执行走法 (只检查起点有本方子; 合法性用 isLegalMove 判)
    @discardableResult
    func doMove(_ move: Move) -> Bool {
        let from = move.from, to = move.to
        guard Square.valid(from), Square.valid(to) else { return false }
        let piece = squares[from]
        guard piece != 0, Piece.fromCode(piece).color == sideToMove else { return false }
        let captured = squares[to]

        hash ^= Zobrist.pieceSquare[piece][from]
        hash ^= Zobrist.pieceSquare[piece][to]
        if captured != 0 { hash ^= Zobrist.pieceSquare[captured][to] }
        hash ^= Zobrist.side

        squares[from] = 0
        squares[to] = piece
        moveStack.append(UndoInfo(from: from, to: to, moved: piece, captured: captured, prevHalfmoveClock: halfmoveClock))
        if sideToMove == Side.black { fullmoveNumber += 1 }
        sideToMove = Side.opposite(sideToMove)
        halfmoveClock = captured != 0 ? 0 : halfmoveClock + 1
        historyHashes.append(hash)
        return true
    }

    @discardableResult
    func undoMove() -> UndoInfo? {
        guard let info = moveStack.popLast() else { return nil }
        historyHashes.removeLast()
        sideToMove = Side.opposite(sideToMove)
        if sideToMove == Side.black { fullmoveNumber -= 1 }
        halfmoveClock = info.prevHalfmoveClock
        squares[info.to] = info.captured
        squares[info.from] = info.moved
        hash ^= Zobrist.side
        hash ^= Zobrist.pieceSquare[info.moved][info.from]
        hash ^= Zobrist.pieceSquare[info.moved][info.to]
        if info.captured != 0 { hash ^= Zobrist.pieceSquare[info.captured][info.to] }
        return info
    }

    /// 合法 = 符合行棋规则 + 走完不被将
    func isLegalMove(_ move: Move) -> Bool {
        Rules.isPseudoLegal(self, move) && leavesKingSafe(move)
    }

    /// 前提: move 已是伪合法
    func leavesKingSafe(_ move: Move) -> Bool {
        let mover = sideToMove
        guard doMove(move) else { return false }
        let safe = !Rules.isInCheck(self, mover)
        undoMove()
        return safe
    }

    func copy() -> Position {
        let p = Position()
        p.squares = squares
        p.sideToMove = sideToMove
        p.hash = hash
        p.halfmoveClock = halfmoveClock
        p.fullmoveNumber = fullmoveNumber
        p.moveStack = moveStack
        p.historyHashes = historyHashes
        p.nullMoveDepth = nullMoveDepth
        return p
    }

    func legalMoves() -> [Move] {
        var moves: [Move] = []
        MoveGenerator.generateAll(self, &moves)
        return moves
    }

    /// 三次重复
    func isThreefoldRepetition() -> Bool {
        guard historyHashes.count >= 9 else { return false }
        let current = hash
        return historyHashes.reduce(0) { $0 + ($1 == current ? 1 : 0) } >= 3
    }

    /// 自然限着: 连续 120 个半回合无吃子判和
    func isFiftyMoveDraw() -> Bool { halfmoveClock >= 120 }

    func isSearchRuleDraw() -> Bool { nullMoveDepth == 0 && (isFiftyMoveDraw() || isThreefoldRepetition()) }

    /// 标准象棋 FEN, 第一段从 row 0 (黑方底线) 开始
    func toFen() -> String {
        var s = ""
        for r in 0..<Square.rows {
            var empty = 0
            for c in 0..<Square.cols {
                let p = squares[Square.make(r, c)]
                if p == 0 { empty += 1; continue }
                if empty > 0 { s += String(empty); empty = 0 }
                s.append(Piece.fromCode(p).fen)
            }
            if empty > 0 { s += String(empty) }
            if r < Square.rows - 1 { s += "/" }
        }
        s += sideToMove == Side.red ? " w" : " b"
        s += " - - \(halfmoveClock) \(fullmoveNumber)"
        return s
    }

    /// 载入 FEN; 解析失败返回 false 且不改动局面
    @discardableResult
    func setFromFen(_ fen: String) -> Bool {
        let parts = fen.trimmingCharacters(in: .whitespaces).split(separator: " ", omittingEmptySubsequences: true)
        guard let board = parts.first else { return false }
        let rows = board.split(separator: "/", omittingEmptySubsequences: false)
        guard rows.count == Square.rows else { return false }
        var parsed = [Int](repeating: 0, count: Square.count)
        for (r, row) in rows.enumerated() {
            var c = 0
            for ch in row {
                if let d = ch.wholeNumberValue, ch.isASCII {
                    c += d
                } else {
                    let piece = Piece.fromFen(ch)
                    guard piece != .none, c < Square.cols else { return false }
                    parsed[Square.make(r, c)] = piece.code
                    c += 1
                }
            }
            guard c == Square.cols else { return false }
        }
        squares = parsed
        sideToMove = parts.count > 1 && parts[1] == "b" ? Side.black : Side.red
        halfmoveClock = parts.count > 4 ? Int(parts[4]) ?? 0 : 0
        fullmoveNumber = parts.count > 5 ? Int(parts[5]) ?? 1 : 1
        hash = computeHash()
        historyHashes = [hash]
        moveStack.removeAll()
        nullMoveDepth = 0
        return true
    }

    /// 清空棋盘 (测试与摆棋用), 不更新历史
    func clear() {
        squares = [Int](repeating: 0, count: Square.count)
    }
}
