import Foundation

/// The one process-wide PikaFish runtime (GPL-3). Every native call runs on one serial
/// queue, so loading, searching and teardown from different screens never overlap.
final class PikafishEngine {
    static let shared = PikafishEngine()

    private let queue = DispatchQueue(label: "qiyan.pikafish", qos: .userInitiated)
    private var loaded = false
    private var generation = 0
    private let lock = NSLock()

    struct Result {
        let bestMove: String?
        let info: EngineInfo?
        let candidates: [PlayStyle.Candidate]
    }

    var nnuePath: String? { Bundle.main.path(forResource: "pikafish", ofType: "nnue") }

    /// 读 50 MB 权重, 只做一次. 在引擎队列上同步执行, 回调在主线程.
    func load(_ completion: @escaping (Bool) -> Void) {
        queue.async {
            let ok = self.loadSync()
            DispatchQueue.main.async { completion(ok) }
        }
    }

    private func loadSync() -> Bool {
        if loaded { return true }
        guard let path = nnuePath else { return false }
        loaded = pf_init(path) == 0
        return loaded
    }

    private func nextGeneration() -> Int {
        lock.lock(); defer { lock.unlock() }
        generation += 1
        return generation
    }

    private func current(_ g: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return generation == g
    }

    /// 停掉正在进行的搜索并作废它的回调
    func stop() {
        _ = nextGeneration()
        pf_stop()
    }

    /// 异步搜索. onInfo 在深度推进时回调 (主线程); completion 只在这次搜索没被 stop 时回调.
    func search(fen: String, moves: [String], params: SearchParams, book: OpeningBook? = nil,
                onInfo: ((EngineInfo) -> Void)? = nil, completion: @escaping (Result) -> Void) {
        let g = nextGeneration()
        pf_stop()
        queue.async {
            guard self.current(g) else { return }
            guard self.loadSync() else {
                DispatchQueue.main.async { if self.current(g) { completion(Result(bestMove: nil, info: nil, candidates: [])) } }
                return
            }
            // 开局库: 对局搜索才用, 分析需要带分数的结果
            if !params.strictMovetime, let book, let uci = book.lookup(startFen: fen, movesPlayed: moves) {
                let until = Date().addingTimeInterval(Double(min(450, params.movetime > 0 ? params.movetime : 450)) / 1000)
                while Date() < until && self.current(g) { Thread.sleep(forTimeInterval: 0.02) }
                DispatchQueue.main.async { if self.current(g) { completion(Result(bestMove: uci, info: nil, candidates: [])) } }
                return
            }
            pf_set_option("Threads", String(min(max(params.threads, 1), 8)))
            pf_set_option("Hash", String(max(params.hashMb, 1)))
            pf_set_option("MultiPV", String(min(max(params.multiPv, 1), 8)))
            let ok: Bool = {
                if moves.isEmpty { return pf_set_position(fen, nil, 0) == 0 }
                let cstrings: [UnsafePointer<CChar>?] = moves.map { UnsafePointer(strdup($0)) }
                defer { cstrings.forEach { free(UnsafeMutablePointer(mutating: $0)) } }
                return cstrings.withUnsafeBufferPointer { pf_set_position(fen, $0.baseAddress, Int32(moves.count)) == 0 }
            }()
            guard ok, self.current(g) else {
                DispatchQueue.main.async { if self.current(g) { completion(Result(bestMove: nil, info: nil, candidates: [])) } }
                return
            }
            pf_go(params.infinite ? 0 : Int64(max(params.movetime, 0)), Int32(min(params.depth, 64)), params.infinite ? 1 : 0)
            var reported = 0
            while pf_is_searching() != 0 {
                Thread.sleep(forTimeInterval: 0.2)
                guard self.current(g) else { continue }
                if let info = self.lastInfo(), info.depth > reported {
                    reported = info.depth
                    DispatchQueue.main.async { if self.current(g) { onInfo?(info) } }
                }
            }
            pf_wait_for_search()
            guard self.current(g) else { return }
            var buf = [CChar](repeating: 0, count: 16)
            let n = pf_bestmove(&buf, 15)
            let best = n > 0 ? String(cString: buf) : nil
            let info = self.lastInfo()
            let candidates = self.multiPv()
            DispatchQueue.main.async {
                guard self.current(g) else { return }
                if let info { onInfo?(info) }
                completion(Result(bestMove: best, info: info, candidates: candidates))
            }
        }
    }

    private func lastInfo() -> EngineInfo? {
        var out = [Int64](repeating: 0, count: 6)
        pf_last_info(&out)
        if out[0] <= 0 && out[1] == 0 && out[2] == 0 { return nil }
        return EngineInfo(depth: Int(out[0]), timeMs: Int(out[3]), nodes: Int(out[4]), nps: Int(out[5]),
                          scoreCp: out[2] != 0 ? 0 : Int(out[1]), scoreMate: Int(out[2]))
    }

    private func multiPv() -> [PlayStyle.Candidate] {
        var buf = [CChar](repeating: 0, count: 512)
        let n = pf_multipv(&buf, 511)
        guard n > 0 else { return [] }
        return String(cString: buf).split(separator: ";").compactMap { part in
            let seg = part.split(separator: ":")
            guard seg.count >= 3 else { return nil }
            return PlayStyle.Candidate(moveUci: String(seg[0]), scoreCp: Int(seg[1]) ?? 0, scoreMate: Int(seg[2]) ?? 0)
        }
    }
}

/// 对局引擎: 前两档走陪练 (随机/一步吃子), 其余档位与提示/分析走 PikaFish.
/// 与安卓 EngineManager + DifficultyEngine 同义.
final class GameEngine {
    var level = DifficultySettings.defaultLevel {
        didSet { level = DifficultySettings.normalize(level) }
    }
    var style: PlayStyle = .balanced
    private var practiceWork: DispatchWorkItem?
    private static let book: OpeningBook = {
        guard let url = Bundle.main.url(forResource: "opening_book", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return OpeningBook(book: [:]) }
        return OpeningBook(json: data)
    }()

    var engineName: String {
        DifficultySettings.isTraining(level) ? (level <= 1 ? "轻松陪练" : "入门棋友") : "PikaFish（GPL-3）"
    }

    var nominalElo: Int { DifficultySettings.nominalElo(level) }

    /// 非练习档提前把权重读进来, 免得第一步才卡几秒
    func prepare(_ completion: @escaping (Bool) -> Void) {
        if DifficultySettings.isTraining(level) { completion(true); return }
        PikafishEngine.shared.load(completion)
    }

    /// 用户设置的线程/哈希上限最后套用
    private func limited(_ p: SearchParams) -> SearchParams {
        var out = SearchParams.adapt(p, level: level)
        out.threads = min(out.threads, AppSettings.shared.threads)
        out.hashMb = min(out.hashMb, AppSettings.shared.hashMb)
        return out
    }

    /// 起始 FEN + 着法序列 (position fen X moves ... 的语义)
    func search(initialFen: String, moves: [Move], params: SearchParams,
                onInfo: ((EngineInfo) -> Void)? = nil,
                completion: @escaping (_ bestUci: String?, _ candidates: [PlayStyle.Candidate]) -> Void) {
        stop()
        let effective = limited(params)
        if !SearchParams.usesStrongEngine(level: level, params: effective) {
            let pos = Position()
            pos.setFromFen(initialFen)
            for m in moves { pos.doMove(m) }
            let level = self.level, style = self.style
            var work: DispatchWorkItem!
            work = DispatchWorkItem {
                var rng = SystemRandomNumberGenerator()
                let move = BeginnerEngine.chooseMove(pos, level: min(level, 2), style: style, using: &rng)
                Thread.sleep(forTimeInterval: Double(min(max(effective.movetime, 0), 650)) / 1000)
                DispatchQueue.main.async {
                    guard !work.isCancelled else { return }
                    completion(move.map(UciCoords.toUci) ?? UciCoords.nullMove, [])
                }
            }
            practiceWork = work
            DispatchQueue.global(qos: .userInitiated).async(execute: work)
            return
        }
        PikafishEngine.shared.search(fen: initialFen, moves: moves.map(UciCoords.toUci), params: effective,
                                     book: GameEngine.book, onInfo: onInfo) { result in
            completion(result.bestMove, result.candidates)
        }
    }

    func stop() {
        practiceWork?.cancel()
        practiceWork = nil
        PikafishEngine.shared.stop()
    }
}
