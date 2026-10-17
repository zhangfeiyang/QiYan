import Foundation

/// 棋盘坐标: 10 行 × 9 列, square = row * 9 + col.
/// row 0 = 黑方底线, row 9 = 红方底线 (与安卓、FEN 一致).
enum Square {
    static let rows = 10
    static let cols = 9
    static let count = 90
    static let none = -1

    @inline(__always) static func make(_ row: Int, _ col: Int) -> Int { row * cols + col }
    @inline(__always) static func row(_ sq: Int) -> Int { sq / cols }
    @inline(__always) static func col(_ sq: Int) -> Int { sq % cols }

    static func valid(_ sq: Int) -> Bool { sq >= 0 && sq < count }
    @inline(__always) static func valid(_ row: Int, _ col: Int) -> Bool {
        row >= 0 && row < rows && col >= 0 && col < cols
    }

    /// 旋转 180° (黑方视角)
    static func mirror(_ sq: Int) -> Int {
        sq == none ? none : (rows - 1 - row(sq)) * cols + (cols - 1 - col(sq))
    }
}
