import Foundation

/// 搜索参数 (与安卓 SearchParams 同义)
struct SearchParams: Equatable {
    var depth = 0
    var movetime = 0          // ms, 0 = 不限
    var nodes = 0
    var infinite = false
    var threads = 1
    var hashMb = 16
    var multiPv = 1
    /// movetime 是硬上限, 不按难度抬高 (提示/分析/实时支招)
    var strictMovetime = false

    /// 提示: 延迟是硬约束
    static let hint = SearchParams(movetime: 800, threads: 1, hashMb: 16, multiPv: 1, strictMovetime: true)
    /// 后台胜率分析: 浅搜
    static let analysis = SearchParams(depth: 6, threads: 1, hashMb: 16, multiPv: 1, strictMovetime: true)

    /// 按难度定对局时限/线程/哈希 (前两档的降难度由陪练引擎实现)
    static func adapt(_ base: SearchParams, level: Int) -> SearchParams {
        if base.strictMovetime { return base }
        let l = DifficultySettings.normalize(level)
        let time = [350, 550, 1200, 3200, 6000, 7500, 9000, 12000][l - 1]
        let threads = l >= 4 ? 4 : (l == 3 ? 2 : 1)
        let hash = [16, 16, 64, 128, 256, 256, 256, 256][l - 1]
        var p = base
        p.movetime = max(base.movetime, time)
        p.threads = max(base.threads, threads)
        p.hashMb = max(base.hashMb, hash)
        return p
    }

    /// 难度为练习档且不是严格搜索时走陪练引擎
    static func usesStrongEngine(level: Int, params: SearchParams) -> Bool {
        params.strictMovetime || !DifficultySettings.isTraining(level)
    }
}

/// 引擎一次搜索的信息. 分数是走子方视角; scoreMate>0 表示走子方 N 步杀.
struct EngineInfo: Equatable {
    var depth = 0
    var timeMs = 0
    var nodes = 0
    var nps = 0
    var scoreCp = 0
    var scoreMate = 0
    var pv: [String] = []
}

/// 棋手风格: 难度管想多久, 风格管想好后挑哪步 (只在候选分差 ≤40cp 时起作用).
enum PlayStyle: Int, CaseIterable, Identifiable {
    case balanced = 0, aggressive, solid, swarm

    var id: Int { rawValue }
    var multiPv: Int { [1, 4, 4, 5][rawValue] }
    var nameKey: String { ["style_balanced", "style_aggressive", "style_solid", "style_swarm"][rawValue] }
    var descKey: String { nameKey + "_desc" }

    static func fromId(_ id: Int) -> PlayStyle { PlayStyle(rawValue: id) ?? .balanced }

    static let selectionMarginCp = 40
    private static let mateStrength = 1_000_000
    private static let captureTasteUnit = 90

    struct Candidate: Equatable {
        let moveUci: String
        let scoreCp: Int
        let scoreMate: Int
    }

    struct Features {
        var isCapture = false
        var captureGain = 0
        var givesCheck = false
    }

    static func strength(_ c: Candidate) -> Int {
        if c.scoreMate > 0 { return mateStrength - c.scoreMate }
        if c.scoreMate < 0 { return -mateStrength - c.scoreMate }
        return c.scoreCp
    }

    /// 候选为空返回 nil, 调用方退回引擎 bestmove
    func pick<G: RandomNumberGenerator>(_ candidates: [Candidate], using rng: inout G,
                                        featuresOf: (Candidate) -> Features) -> Candidate? {
        guard let best = candidates.first else { return nil }
        if self == .balanced || candidates.count == 1 { return best }
        if best.scoreMate > 0 { return best }
        let bestStrength = PlayStyle.strength(best)
        let playable = candidates.filter { bestStrength - PlayStyle.strength($0) <= PlayStyle.selectionMarginCp }
        if playable.count <= 1 { return best }

        switch self {
        case .aggressive:
            func key(_ c: Candidate) -> [Int] {
                let f = featuresOf(c)
                return [max(f.captureGain, 0) / PlayStyle.captureTasteUnit, f.givesCheck ? 1 : 0,
                        f.isCapture ? 1 : 0, PlayStyle.strength(c)]
            }
            return playable.max { key($0).lexicographicallyPrecedes(key($1)) } ?? best
        case .solid:
            var safe = playable.filter { featuresOf($0).captureGain >= 0 }
            if safe.isEmpty { safe = [best] }
            func key(_ c: Candidate) -> [Int] {
                let f = featuresOf(c)
                return [PlayStyle.strength(c), f.isCapture ? 0 : 1, f.givesCheck ? 0 : 1]
            }
            return safe.max { key($0).lexicographicallyPrecedes(key($1)) } ?? best
        case .swarm:
            if let mate = playable.filter({ $0.scoreMate > 0 }).min(by: { $0.scoreMate < $1.scoreMate }) { return mate }
            return playable.randomElement(using: &rng)
        case .balanced:
            return best
        }
    }

    func pick(_ candidates: [Candidate], featuresOf: (Candidate) -> Features) -> Candidate? {
        var rng = SystemRandomNumberGenerator()
        return pick(candidates, using: &rng, featuresOf: featuresOf)
    }
}

/// 前两档陪练: 一档以随机走子为主, 二档只看一步吃子与眼前威胁.
enum BeginnerEngine {
    static func pieceValue(_ piece: Int) -> Int {
        switch Piece.fromCode(piece).type {
        case 1: return 10000
        case 5: return 900
        case 4, 6: return 400
        case 2, 3: return 200
        case 7: return 100
        default: return 0
        }
    }

    /// 不改动传入局面
    static func chooseMove<G: RandomNumberGenerator>(_ position: Position, level: Int, style: PlayStyle = .balanced,
                                                     using rng: inout G) -> Move? {
        let pos = position.copy()
        let legal = pos.legalMoves()
        guard !legal.isEmpty else { return nil }
        let styleShift = style == .swarm ? 10 : (style == .aggressive ? -10 : 0)
        let randomChance = (level <= 1 ? 85 : 25) + styleShift
        if Int.random(in: 0..<100, using: &rng) < randomChance { return legal.randomElement(using: &rng) }

        let scored: [(Move, Int)] = legal.map { move in
            var score = pieceValue(pos.pieceAt(move.to))
            if level >= 2 || style == .solid {
                pos.doMove(move)
                var captures: [Move] = []
                MoveGenerator.generateCaptures(pos, &captures)
                score -= captures.map { pieceValue(pos.pieceAt($0.to)) }.max() ?? 0
                pos.undoMove()
            }
            return (move, score)
        }
        let best = scored.map(\.1).max() ?? 0
        return scored.filter { $0.1 == best }.randomElement(using: &rng)?.0
    }
}

/// 开局库: 已走着法恰是某变例前缀时给出下一步, 出谱即不用.
struct OpeningBook {
    struct Line { let name: String; let moves: [String] }
    static let maxBookPlies = 12

    let book: [String: [Line]]

    init(book: [String: [Line]]) { self.book = book }

    init(json data: Data) {
        var out: [String: [Line]] = [:]
        if let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for (key, value) in root {
                guard let entry = value as? [String: Any], let lines = entry["lines"] as? [[String: Any]] else { continue }
                let parsed = lines.compactMap { obj -> Line? in
                    guard let moves = obj["moves"] as? [String], !moves.isEmpty else { return nil }
                    return Line(name: obj["name"] as? String ?? "", moves: moves)
                }
                if !parsed.isEmpty { out[key] = parsed }
            }
        }
        book = out
    }

    func lookup(startFen: String, movesPlayed: [String]) -> String? {
        guard movesPlayed.count < OpeningBook.maxBookPlies else { return nil }
        let key = String(startFen.split(separator: " ").first ?? "")
        guard let lines = book[key] else { return nil }
        let candidates = lines.filter { line in
            line.moves.count > movesPlayed.count &&
                movesPlayed.indices.allSatisfy { line.moves[$0].lowercased() == movesPlayed[$0].lowercased() }
        }
        return candidates.randomElement()?.moves[movesPlayed.count]
    }
}

/// 引擎评估 -> 胜率
enum WinRateAnalyzer {
    /// Pikafish 内部单位约 2 倍厘兵
    private static let internalToCp = 0.5
    private static let scaling = 400.0

    static func redWinRate(_ score: Int, mate: Int = 0) -> Double {
        if mate != 0 { return mate > 0 ? 99.9 : 0.1 }
        let cp = Double(score) * internalToCp
        return 100.0 / (1.0 + pow(10.0, -cp / scaling))
    }

    /// tanh 平滑版: 小分差下不剧烈摆动 (界面胜率条用这个)
    static func smoothWinRate(_ score: Int, mate: Int = 0) -> Double {
        if mate != 0 { return mate > 0 ? 100 : 0 }
        let cp = Double(score) * internalToCp
        return tanh(cp / (scaling * 1.8)) * 50 + 50
    }

    static func sideToMoveWinRate(_ scoreCp: Int, sideToMove: Int, mate: Int = 0) -> Double {
        let red = redWinRate(scoreCp, mate: mate)
        return sideToMove == Side.red ? red : 100 - red
    }

    static func winRateToEloDiff(_ winRate: Double) -> Double {
        let p = min(max(winRate / 100, 0.001), 0.999)
        return -400 * log10(1 / p - 1)
    }
}
