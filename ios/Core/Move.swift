import Foundation

/// 走法: bits 0..6 起点, 7..13 终点 (0..89). 与安卓 `Move.data` 同编码.
struct Move: Hashable, CustomStringConvertible {
    let data: Int

    init(data: Int) { self.data = data }
    init(_ from: Int, _ to: Int) { data = ((to & 0x7F) << 7) | (from & 0x7F) }

    static func make(_ from: Int, _ to: Int) -> Move { Move(from, to) }

    var from: Int { data & 0x7F }
    var to: Int { (data >> 7) & 0x7F }

    var description: String { UciCoords.toUci(self) }

    private static let chineseDigits = ["〇", "一", "二", "三", "四", "五", "六", "七", "八", "九"]

    /// 中文着法记谱, 如 "炮二平五"、"马8进7"、"前兵进一". [board] 必须是走这一步之前的局面.
    ///
    /// 红方用汉字数字, 列号自红方右侧(col 8)起数; 黑方用阿拉伯数字, 自黑方右侧(col 0)起数.
    /// 车/炮/兵/将 进退记步数; 马/相/仕 走斜线, 进退记目标列. 同列多个同种子用 前/中/后 代替起始列.
    static func chineseString(_ move: Move, _ board: Position) -> String {
        let code = board.pieceAt(move.from)
        guard code != Piece.none.code else { return "" }
        let piece = Piece.fromCode(code)
        let isRed = piece.isRed
        let name = isRed ? piece.redChar : piece.blackChar
        let fr = Square.row(move.from), fc = Square.col(move.from)
        let tr = Square.row(move.to), tc = Square.col(move.to)

        var sameFile = (0..<Square.rows).filter { board.pieceAt(Square.make($0, fc)) == code }
        sameFile = isRed ? sameFile.sorted() : sameFile.sorted(by: >)
        let index = sameFile.firstIndex(of: fr) ?? -1
        let prefix: String
        if sameFile.count < 2 || index < 0 { prefix = "" }
        else if index == 0 { prefix = "前" }
        else if index == sameFile.count - 1 { prefix = "后" }
        else if sameFile.count == 3 { prefix = "中" }
        else { prefix = numeral(index + 1, isRed) }

        let action: String
        if tr == fr { action = "平" } else if (tr < fr) == isRed { action = "进" } else { action = "退" }

        let diagonalMover = piece.type == 2 || piece.type == 3 || piece.type == 4
        let target = (action == "平" || diagonalMover) ? fileNumber(tc, isRed) : numeral(abs(tr - fr), isRed)
        let origin = prefix.isEmpty ? fileNumber(fc, isRed) : ""
        return prefix + name + origin + action + target
    }

    /// WXF 记谱, 如 "C2.5"、"H8+7"、"+P+1". 与中文记谱共用同一套判定.
    static func wxfString(_ move: Move, _ board: Position) -> String {
        let code = board.pieceAt(move.from)
        guard code != Piece.none.code else { return "" }
        let piece = Piece.fromCode(code)
        let isRed = piece.isRed
        let name = String(piece.fen).uppercased()
        let fr = Square.row(move.from), fc = Square.col(move.from)
        let tr = Square.row(move.to), tc = Square.col(move.to)

        var sameFile = (0..<Square.rows).filter { board.pieceAt(Square.make($0, fc)) == code }
        sameFile = isRed ? sameFile.sorted() : sameFile.sorted(by: >)
        let index = sameFile.firstIndex(of: fr) ?? -1
        let prefix: String
        if sameFile.count < 2 || index < 0 { prefix = "" }
        else if index == 0 { prefix = "+" }
        else if index == sameFile.count - 1 { prefix = "-" }
        else { prefix = String(index + 1) }

        let action: String
        if tr == fr { action = "." } else if (tr < fr) == isRed { action = "+" } else { action = "-" }

        let diagonalMover = piece.type == 2 || piece.type == 3 || piece.type == 4
        let target = (action == "." || diagonalMover) ? wxfFile(tc, isRed) : String(abs(tr - fr))
        let origin = prefix.isEmpty ? wxfFile(fc, isRed) : ""
        return prefix + name + origin + action + target
    }

    private static func wxfFile(_ col: Int, _ isRed: Bool) -> String {
        isRed ? String(Square.cols - col) : String(col + 1)
    }

    private static func fileNumber(_ col: Int, _ isRed: Bool) -> String {
        isRed ? numeral(Square.cols - col, true) : numeral(col + 1, false)
    }

    private static func numeral(_ n: Int, _ isRed: Bool) -> String {
        isRed && n >= 0 && n < chineseDigits.count ? chineseDigits[n] : String(n)
    }
}
