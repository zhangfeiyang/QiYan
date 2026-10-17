import Foundation

/// 走子方. 与安卓 `Color` 同值: 红 = 1, 黑 = 0, 无主 = -1 (不能与黑方的 0 混用).
enum Side {
    static let none = -1
    static let red = 1
    static let black = 0

    static func opposite(_ side: Int) -> Int { 1 - side }
}

/// 中国象棋棋子. 编码与安卓一致: 1..7 红方 帅仕相马车炮兵, 9..15 黑方.
/// `type = code & 7` 即兵种 1..7.
enum Piece: Int, CaseIterable {
    case none = 0
    case rKing = 1, rAdvisor, rElephant, rHorse, rRook, rCannon, rPawn
    case bKing = 9, bAdvisor, bElephant, bHorse, bRook, bCannon, bPawn

    var code: Int { rawValue }
    var isRed: Bool { (1...7).contains(rawValue) }
    var isBlack: Bool { (9...15).contains(rawValue) }
    var color: Int { isRed ? Side.red : (isBlack ? Side.black : Side.none) }
    /// 兵种: 1=帅 2=仕 3=相 4=马 5=车 6=炮 7=兵
    var type: Int { rawValue == 0 ? 0 : rawValue & 7 }

    /// 标准 FEN 字母: 大写红, 小写黑 (K/A/B/N/R/C/P)
    var fen: Character {
        let letters: [Character] = [" ", "K", "A", "B", "N", "R", "C", "P"]
        let c = letters[type]
        return isBlack ? Character(c.lowercased()) : c
    }

    var chinese: String { isRed ? redChar : blackChar }
    /// 红方字形(帅仕相马车炮兵)
    var redChar: String { Piece.redChars[type] }
    /// 黑方字形(将士象马车炮卒)
    var blackChar: String { Piece.blackChars[type] }

    private static let redChars = ["", "帅", "仕", "相", "马", "车", "炮", "兵"]
    private static let blackChars = ["", "将", "士", "象", "马", "车", "炮", "卒"]
    private static let byCode: [Piece] = (0..<16).map { Piece(rawValue: $0) ?? .none }

    static func fromCode(_ code: Int) -> Piece { (0..<16).contains(code) ? byCode[code] : .none }

    static func fromFen(_ c: Character) -> Piece {
        guard let i = "KABNRCP".firstIndex(of: Character(c.uppercased())) else { return .none }
        let type = "KABNRCP".distance(from: "KABNRCP".startIndex, to: i) + 1
        return Piece(rawValue: c.isUppercase ? type : type + 8) ?? .none
    }

    /// 某一方、某兵种的棋子
    static func of(type: Int, color: Int) -> Piece {
        Piece(rawValue: color == Side.red ? type : type + 8) ?? .none
    }
}
