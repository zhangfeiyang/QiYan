import Foundation

enum MoveQuality: CaseIterable {
    case brilliant, best, inaccuracy, mistake, blunder, book

    var labelKey: String {
        switch self {
        case .brilliant: return "move_quality_brilliant"
        case .best: return "move_quality_best"
        case .inaccuracy: return "move_quality_inaccuracy"
        case .mistake: return "move_quality_mistake"
        case .blunder: return "move_quality_blunder"
        case .book: return "move_quality_book"
        }
    }

    var symbol: String {
        switch self {
        case .brilliant: return "💎"
        case .best: return "🟢"
        case .inaccuracy: return "🟡"
        case .mistake: return "🟠"
        case .blunder: return "🔴"
        case .book: return "📖"
        }
    }

    /// ARGB
    var color: UInt32 {
        switch self {
        case .brilliant: return 0xFF00B0FF
        case .best: return 0xFF4CAF50
        case .inaccuracy: return 0xFFFFC107
        case .mistake: return 0xFFFF9800
        case .blunder: return 0xFFE53935
        case .book: return 0xFF9C27B0
        }
    }
}

struct MoveEvaluation {
    let stepIndex: Int
    let uci: String
    let chineseNotation: String
    let redWinRate: Double
    let scoreCp: Int
    let quality: MoveQuality
    let deltaWinRate: Double
    let openingName: String?
}

struct ReplayAnalysisResult {
    let evaluations: [MoveEvaluation]
    /// 第 i 个是走完 i 步后的红方胜率 (0 = 起始局面)
    let winRates: [Double]
    let brilliantCount: Int
    let bestCount: Int
    let inaccuracyCount: Int
    let mistakeCount: Int
    let blunderCount: Int
}

/// 快速全盘复盘: 静态估值 -> 胜率 -> 着法质量 (与安卓 ReplayEvaluator 一致)
enum ReplayEvaluator {
    static func analyze(initialFen: String, uciMoves: [String]) -> ReplayAnalysisResult {
        let pos = Position()
        if !initialFen.isEmpty { pos.setFromFen(initialFen) }
        var winRates = [WinRateAnalyzer.smoothWinRate(staticEval(pos))]
        var evaluations: [MoveEvaluation] = []
        var brilliant = 0, best = 0, inaccuracy = 0, mistake = 0, blunder = 0

        for (i, uci) in uciMoves.enumerated() {
            let move = UciCoords.parse(uci)
            let mover = pos.sideToMove
            var notation = uci
            if let m = move, pos.isLegalMove(m) {
                notation = Move.chineseString(m, pos)
                pos.doMove(m)
            }
            let score = staticEval(pos)
            let redWr = WinRateAnalyzer.smoothWinRate(score)
            let prev = mover == Side.red ? winRates[i] : 100 - winRates[i]
            let curr = mover == Side.red ? redWr : 100 - redWr
            winRates.append(redWr)
            let delta = curr - prev
            let opening = i < 16 ? OpeningDetector.detect(Array(uciMoves.prefix(i + 1))) : nil

            let quality: MoveQuality
            if Rules.isCheckmate(pos) || delta >= 12 { quality = .brilliant; brilliant += 1 }
            else if delta <= -18 { quality = .blunder; blunder += 1 }
            else if delta <= -8 { quality = .mistake; mistake += 1 }
            else if delta <= -3 { quality = .inaccuracy; inaccuracy += 1 }
            else if opening != nil && i < 6 { quality = .book; best += 1 }
            else { quality = .best; best += 1 }

            evaluations.append(MoveEvaluation(stepIndex: i + 1, uci: uci, chineseNotation: notation, redWinRate: redWr,
                                              scoreCp: score, quality: quality, deltaWinRate: delta, openingName: opening))
        }
        return ReplayAnalysisResult(evaluations: evaluations, winRates: winRates, brilliantCount: brilliant,
                                    bestCount: best, inaccuracyCount: inaccuracy, mistakeCount: mistake,
                                    blunderCount: blunder)
    }

    static func evaluatePosition(_ pos: Position) -> (score: Int, redWinRate: Double) {
        let s = staticEval(pos)
        return (s, WinRateAnalyzer.smoothWinRate(s))
    }

    /// 静态估值 (红方视角)
    static func staticEval(_ pos: Position) -> Int {
        if Rules.isCheckmate(pos) { return pos.sideToMove == Side.red ? -9999 : 9999 }
        var red = 0, black = 0
        for sq in 0..<Square.count {
            let code = pos.squares[sq]
            guard code != 0 else { continue }
            let p = Piece.fromCode(code)
            let r = Square.row(sq), c = Square.col(sq)
            var v: Int
            switch p.type {
            case 1: v = 10000
            case 5: v = 900
            case 6:
                v = 450
                if c == 4 { v += 35 }
                if p.isRed && r <= 2 { v += 20 }
                if !p.isRed && r >= 7 { v += 20 }
            case 4:
                v = 400
                if p.isRed && r <= 4 { v += 25 }
                if !p.isRed && r >= 5 { v += 25 }
            case 2, 3: v = 200
            case 7:
                v = 100
                if p.isRed { if r <= 4 { v += 80 }; v += (9 - r) * 10 }
                else { if r >= 5 { v += 80 }; v += r * 10 }
            default: v = 0
            }
            if p.isRed { red += v } else { black += v }
        }
        var diff = red - black
        if Rules.isInCheck(pos, Side.red) { diff -= 140 }
        if Rules.isInCheck(pos, Side.black) { diff += 140 }
        return diff
    }
}
