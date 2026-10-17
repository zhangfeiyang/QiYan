import Foundation

/// 识别结果 (10×9 字符棋盘, 类别见 `classChars`) 与局面之间的转换和校验.
/// 行序与内部坐标、FEN 一致: row 0 = 黑方底线.
enum BoardLayout {
    static let rows = 10
    static let cols = 9
    /// 分类模型的 16 个类别, 顺序与训练一致
    static let classChars: [Character] = Array(".xKABNRCPkabnrcp")
    static let offBoard: Character = "x"
    static let empty: Character = "."
    /// 低于这个概率让用户重点核对
    static let lowConfidence: Float = 0.75
    /// 四角关键点平均分低于它基本没框住棋盘
    static let lowCornerScore: Float = 0.18

    struct Cell: Hashable { let row: Int; let col: Int }

    enum IssueKind: Equatable {
        case tooMany(piece: Character, count: Int, max: Int)
        case kingCount(red: Bool, count: Int)
        case outsidePalace(piece: Character)
        case elephantCrossed(red: Bool)
        case pawnImpossible(red: Bool)
        case offBoard(count: Int)
        case unparsable
        case opponentInCheck(redInCheck: Bool)
    }

    struct Issue: Equatable {
        let kind: IssueKind
        /// true = 必须改正才能求招
        let blocking: Bool
        let cells: [Cell]

        /// 中文与安卓一致; 其余语言用英文
        func message(chinese: Bool) -> String {
            switch kind {
            case let .tooMany(p, count, max):
                return chinese ? "\(sideName(p, true))\(BoardLayout.pieceName(p))有 \(count) 个, 最多 \(max) 个"
                    : "\(count) \(sideName(p, false)) \(englishName(p))s found; at most \(max)"
            case let .kingCount(red, count):
                if chinese {
                    return red ? "红方应有且仅有 1 个帅, 识别到 \(count) 个" : "黑方应有且仅有 1 个将, 识别到 \(count) 个"
                }
                return "\(red ? "Red" : "Black") needs exactly one king; found \(count)"
            case let .outsidePalace(p):
                return chinese ? "\(BoardLayout.pieceName(p)) 必须在九宫内" : "The \(englishName(p)) must stay inside the palace"
            case let .elephantCrossed(red):
                return chinese ? (red ? "红相不能过河" : "黑象不能过河")
                    : "\(red ? "Red" : "Black") elephants cannot cross the river"
            case let .pawnImpossible(red):
                return chinese ? (red ? "红兵不可能在这个位置" : "黑卒不可能在这个位置")
                    : "A \(red ? "red" : "black") pawn cannot stand there"
            case let .offBoard(count):
                return chinese ? "有 \(count) 个点位落在棋盘外, 棋盘四角没对准, 建议重拍或手动调整边框"
                    : "\(count) points fall outside the board. The corners are off: retake the photo or adjust the frame"
            case .unparsable:
                return chinese ? "局面无法解析, 请检查棋子摆放" : "This position cannot be read. Check the pieces"
            case let .opponentInCheck(redInCheck):
                return chinese
                    ? "\(redInCheck ? "红方" : "黑方") 正在被将军, 但轮到对方走棋 —— 局面不合法, 请检查是否认错了棋子或走子方"
                    : "\(redInCheck ? "Red" : "Black") is in check but it is the other side's move. Check the pieces or who moves"
            }
        }

        private func sideName(_ p: Character, _ chinese: Bool) -> String {
            p.isUppercase ? (chinese ? "红" : "red") : (chinese ? "黑" : "black")
        }

        private func englishName(_ p: Character) -> String {
            ["k": "king", "a": "advisor", "b": "elephant", "n": "horse", "r": "chariot", "c": "cannon", "p": "pawn"][
                Character(p.lowercased())] ?? "piece"
        }
    }

    private static let maxCount: [(Character, Int)] = [("K", 1), ("A", 2), ("B", 2), ("N", 2), ("R", 2), ("C", 2), ("P", 5)]

    static func pieceName(_ c: Character) -> String {
        ["K": "帅", "A": "仕", "B": "相", "N": "马", "R": "车", "C": "炮", "P": "兵",
         "k": "将", "a": "士", "b": "象", "n": "马", "r": "车", "c": "炮", "p": "卒"][c] ?? "?"
    }

    static func emptyLayout() -> [[Character]] {
        [[Character]](repeating: [Character](repeating: empty, count: cols), count: rows)
    }

    /// 'x' 一律当空点
    static func toFen(_ layout: [[Character]], sideToMove: Int) -> String {
        var s = ""
        for r in 0..<rows {
            var n = 0
            for c in 0..<cols {
                let ch = layout[r][c]
                if ch == empty || ch == offBoard { n += 1; continue }
                if n > 0 { s += String(n); n = 0 }
                s.append(ch)
            }
            if n > 0 { s += String(n) }
            if r < rows - 1 { s += "/" }
        }
        return s + (sideToMove == Side.red ? " w" : " b") + " - - 0 1"
    }

    static func toPosition(_ layout: [[Character]], sideToMove: Int) -> Position? {
        let pos = Position()
        return pos.setFromFen(toFen(layout, sideToMove: sideToMove)) ? pos : nil
    }

    static func fromPosition(_ pos: Position) -> [[Character]] {
        (0..<rows).map { r in
            (0..<cols).map { c in
                let code = pos.pieceAt(r, c)
                return code == 0 ? empty : Piece.fromCode(code).fen
            }
        }
    }

    /// 校验识别出的局面; sideToMove 由用户指定 (照片给不出)
    static func validate(_ layout: [[Character]], sideToMove: Int) -> [Issue] {
        var issues: [Issue] = []
        var counts: [Character: [Cell]] = [:]
        var off: [Cell] = []
        for r in 0..<rows {
            for c in 0..<cols {
                let ch = layout[r][c]
                if ch == offBoard { off.append(Cell(row: r, col: c)); continue }
                if ch != empty { counts[ch, default: []].append(Cell(row: r, col: c)) }
            }
        }
        for (upper, max) in maxCount {
            for ch in [upper, Character(upper.lowercased())] {
                if let cells = counts[ch], cells.count > max {
                    issues.append(Issue(kind: .tooMany(piece: ch, count: cells.count, max: max), blocking: true, cells: cells))
                }
            }
        }
        let redKings = counts["K"] ?? [], blackKings = counts["k"] ?? []
        if redKings.count != 1 { issues.append(Issue(kind: .kingCount(red: true, count: redKings.count), blocking: true, cells: redKings)) }
        if blackKings.count != 1 { issues.append(Issue(kind: .kingCount(red: false, count: blackKings.count), blocking: true, cells: blackKings)) }

        for (ch, red) in [("K", true), ("A", true), ("k", false), ("a", false)] as [(Character, Bool)] {
            let bad = (counts[ch] ?? []).filter {
                !(red ? (7...9).contains($0.row) : (0...2).contains($0.row)) || !(3...5).contains($0.col)
            }
            if !bad.isEmpty { issues.append(Issue(kind: .outsidePalace(piece: ch), blocking: true, cells: bad)) }
        }
        let redEle = (counts["B"] ?? []).filter { $0.row < 5 }
        if !redEle.isEmpty { issues.append(Issue(kind: .elephantCrossed(red: true), blocking: true, cells: redEle)) }
        let blackEle = (counts["b"] ?? []).filter { $0.row > 4 }
        if !blackEle.isEmpty { issues.append(Issue(kind: .elephantCrossed(red: false), blocking: true, cells: blackEle)) }
        let redPawn = (counts["P"] ?? []).filter { $0.row > 6 }
        if !redPawn.isEmpty { issues.append(Issue(kind: .pawnImpossible(red: true), blocking: true, cells: redPawn)) }
        let blackPawn = (counts["p"] ?? []).filter { $0.row < 3 }
        if !blackPawn.isEmpty { issues.append(Issue(kind: .pawnImpossible(red: false), blocking: true, cells: blackPawn)) }
        if !off.isEmpty { issues.append(Issue(kind: .offBoard(count: off.count), blocking: false, cells: off)) }

        if issues.contains(where: \.blocking) { return issues }
        guard let pos = toPosition(layout, sideToMove: sideToMove) else {
            return issues + [Issue(kind: .unparsable, blocking: true, cells: [])]
        }
        let opponent = Side.opposite(sideToMove)
        if Rules.isInCheck(pos, opponent) {
            issues.append(Issue(kind: .opponentInCheck(redInCheck: opponent == Side.red), blocking: true, cells: []))
        }
        return issues
    }

    static func lowConfidenceCells(_ confidence: [[Float]], threshold: Float = lowConfidence) -> [Cell] {
        var out: [Cell] = []
        for (r, row) in confidence.enumerated() {
            for (c, p) in row.enumerated() where p < threshold { out.append(Cell(row: r, col: c)) }
        }
        return out
    }
}

/// 实时支招: 连续 N 帧认出同一局面才接受
final class StabilityGate {
    private let needed: Int
    private var candidate: String?
    private var count = 0
    private(set) var accepted: String?

    init(needed: Int = 2) { self.needed = needed }

    /// 返回 true 表示这是新的稳定局面, 该重算着法
    func offer(_ fen: String?) -> Bool {
        guard let fen else {
            candidate = nil
            count = 0
            return false
        }
        if fen == candidate { count += 1 } else { candidate = fen; count = 1 }
        if count < needed || fen == accepted { return false }
        accepted = fen
        return true
    }

    func invalidate() {
        accepted = nil
        candidate = nil
        count = 0
    }
}

/// aspect-fill 映射: 分析图像坐标 -> 视图坐标 (与预览层同一算法)
enum LiveGeometry {
    static func fillScale(srcW: Double, srcH: Double, viewW: Double, viewH: Double) -> Double {
        guard srcW > 0, srcH > 0 else { return 1 }
        return max(viewW / srcW, viewH / srcH)
    }

    static func srcToView(x: Double, y: Double, srcW: Double, srcH: Double, viewW: Double, viewH: Double) -> (Double, Double) {
        let s = fillScale(srcW: srcW, srcH: srcH, viewW: viewW, viewH: viewH)
        return (x * s + (viewW - srcW * s) / 2, y * s + (viewH - srcH * s) / 2)
    }
}
