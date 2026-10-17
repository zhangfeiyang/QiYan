import Foundation
import SwiftUI

/// App settings (keys and defaults match the Android SettingsActivity / AppSettings).
final class AppSettings: ObservableObject {
    static let shared = AppSettings()
    private let d = UserDefaults.standard

    private func bool(_ key: String, _ def: Bool) -> Bool { d.object(forKey: key) == nil ? def : d.bool(forKey: key) }
    private func int(_ key: String, _ def: Int) -> Int { d.object(forKey: key) == nil ? def : d.integer(forKey: key) }
    private func set(_ key: String, _ value: Any) { objectWillChange.send(); d.set(value, forKey: key) }

    var sound: Bool { get { bool("sound", true) } set { set("sound", newValue) } }
    var vibrate: Bool { get { bool("vibrate", true) } set { set("vibrate", newValue) } }
    var showCoordinates: Bool { get { bool("show_coords", true) } set { set("show_coords", newValue) } }
    var showWinRate: Bool { get { bool("show_winrate", true) } set { set("show_winrate", newValue) } }
    var animation: Bool { get { bool("animation", true) } set { set("animation", newValue) } }
    var boardFlipped: Bool { get { bool("flipped", false) } set { set("flipped", newValue) } }
    /// 吃子厮杀特效; 关掉后棋盘仍弹"吃X"文字标签
    var captureFx: Bool { get { bool("capture_fx", true) } set { set("capture_fx", newValue) } }
    var bgm: Bool { get { bool("bgm", true) } set { set("bgm", newValue) } }
    var bgmVolume: Int { get { min(max(int("bgm_volume", 30), 0), 100) } set { set("bgm_volume", newValue) } }
    /// 0=跟随系统 1=深色 2=浅色 (默认浅色: 配色按浅色暖木设计)
    var themeMode: Int { get { int("theme", 2) } set { set("theme", newValue) } }
    /// 0=跟随界面语言 1=汉字 2=拉丁字母
    var pieceSetMode: Int { get { int("piece_set", 0) } set { set("piece_set", newValue) } }
    /// 0=跟随界面语言 1=中文记谱 2=WXF
    var notationMode: Int { get { int("notation", 0) } set { set("notation", newValue) } }
    var boardSkin: Int { get { int("board_skin", 0) } set { set("board_skin", newValue) } }
    /// 引擎上限: 线程/置换表
    var threads: Int {
        get { min(max(int("threads", min(max(ProcessInfo.processInfo.activeProcessorCount / 2, 2), 4)), 1), 8) }
        set { set("threads", newValue) }
    }
    var hashMb: Int {
        get {
            let gb = Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824
            return min(max(int("hash_mb", gb >= 6 ? 256 : (gb >= 3 ? 128 : 64)), 16), 1024)
        }
        set { set("hash_mb", newValue) }
    }

    var motionAllowed: Bool { animation && !UIAccessibility.isReduceMotionEnabled }

    var colorScheme: ColorScheme? {
        switch themeMode {
        case 1: return .dark
        case 2: return .light
        default: return nil
        }
    }

    /// 上次对局设定 (首页"直接开局"与对手卡用)
    var lastDifficulty: Int {
        get { DifficultySettings.normalize(int("last_difficulty", DifficultySettings.defaultLevel)) }
        set { set("last_difficulty", DifficultySettings.normalize(newValue)) }
    }
    var lastSide: Int { get { int("last_side", Side.red) } set { set("last_side", newValue) } }
    var lastStyle: Int { get { int("last_style", PlayStyle.balanced.id) } set { set("last_style", newValue) } }
    /// 新对局页的执子偏好 (可能是"随机"), 与上一局实际执的那方分开存
    var sidePref: Int { get { int("side_pref", Side.red) } set { set("side_pref", newValue) } }
    static let randomSide = 2

    func clearAll() {
        objectWillChange.send()
        if let id = Bundle.main.bundleIdentifier { d.removePersistentDomain(forName: id) }
    }
}

/// 战绩与 Glicko-2 评分 (UserDefaults, 与安卓 user_profile 同义)
final class UserProfile: ObservableObject {
    static let shared = UserProfile()
    private let d = UserDefaults.standard

    var nickname: String {
        get { d.string(forKey: "profile.nickname") ?? L("default_nickname") }
        set { objectWillChange.send(); d.set(newValue, forKey: "profile.nickname") }
    }
    var glicko: Glicko2.Rating {
        get {
            Glicko2.Rating(rating: d.object(forKey: "profile.rating") as? Double ?? 1500,
                           rd: d.object(forKey: "profile.rd") as? Double ?? 350,
                           sigma: d.object(forKey: "profile.sigma") as? Double ?? 0.06)
        }
        set {
            objectWillChange.send()
            d.set(newValue.rating, forKey: "profile.rating")
            d.set(newValue.rd, forKey: "profile.rd")
            d.set(newValue.sigma, forKey: "profile.sigma")
        }
    }
    /// 计分局总数 (定级只数计分局)
    var totalGames: Int { d.integer(forKey: "profile.total") }
    var wins: Int { d.integer(forKey: "profile.wins") }
    var losses: Int { d.integer(forKey: "profile.losses") }
    var draws: Int { d.integer(forKey: "profile.draws") }

    var displayedRating: Int { glicko.displayedRating }
    var rank: UserRank { UserRank.fromRating(displayedRating) }
    var isInPlacement: Bool { RankLadder.inPlacement(totalGames) }

    func recordResult(_ result: GameResult, opponentRating: Int) {
        var g = glicko
        Glicko2.update(&g, [Glicko2.Match(opponentRating: Double(opponentRating), opponentRd: 200, score: result.score)])
        glicko = g
        d.set(totalGames + 1, forKey: "profile.total")
        switch result {
        case .win: d.set(wins + 1, forKey: "profile.wins")
        case .loss: d.set(losses + 1, forKey: "profile.losses")
        case .draw: d.set(draws + 1, forKey: "profile.draws")
        }
    }

    func reload() { objectWillChange.send() }
}

/// 闯关/残局/每日一题/速拼的进度 (与安卓 ProgressStore 同义)
final class ProgressStore: ObservableObject {
    static let shared = ProgressStore()
    private let d = UserDefaults.standard

    private func ints(_ key: String) -> Set<Int> { Set(d.array(forKey: key) as? [Int] ?? []) }
    private func setInts(_ key: String, _ v: Set<Int>) { objectWillChange.send(); d.set(Array(v).sorted(), forKey: key) }
    private func map(_ key: String) -> [Int: Int] {
        (d.dictionary(forKey: key) as? [String: Int] ?? [:]).reduce(into: [:]) { if let k = Int($1.key) { $0[k] = $1.value } }
    }
    private func setMap(_ key: String, _ m: [Int: Int]) {
        objectWillChange.send()
        d.set(Dictionary(uniqueKeysWithValues: m.map { (String($0.key), $0.value) }), forKey: key)
    }

    // 对弈闯关
    var campaignUnlocked: Int { min(max(d.object(forKey: "progress.campaign_unlocked") as? Int ?? 1, 1), DifficultySettings.maxLevel) }
    func campaignPassedLevels() -> Set<Int> { ints("progress.campaign_passed") }

    func markCampaignPassed(_ level: Int) {
        guard (1...DifficultySettings.maxLevel).contains(level) else { return }
        setInts("progress.campaign_passed", campaignPassedLevels().union([level]))
        d.set(min(max(campaignUnlocked, level + 1), DifficultySettings.maxLevel), forKey: "progress.campaign_unlocked")
    }

    func campaignStars(_ level: Int) -> Int {
        CampaignStars.display(stored: map("progress.campaign_stars")[level], passed: campaignPassedLevels().contains(level))
    }

    func campaignStarTotal() -> Int { (1...DifficultySettings.maxLevel).reduce(0) { $0 + campaignStars($1) } }

    @discardableResult
    func recordCampaignStars(_ level: Int, _ stars: Int) -> Int {
        let best = CampaignStars.merge(previous: campaignStars(level), earned: stars)
        guard best > 0 else { return 0 }
        var m = map("progress.campaign_stars")
        m[level] = best
        setMap("progress.campaign_stars", m)
        return best
    }

    // 历史战役
    func chronicleStars(_ battleId: Int) -> Int { CampaignStars.display(stored: map("progress.chronicle_stars")[battleId], passed: false) }

    @discardableResult
    func recordChronicleStars(_ battleId: Int, _ stars: Int) -> Int {
        let best = CampaignStars.merge(previous: chronicleStars(battleId), earned: stars)
        guard best > 0 else { return 0 }
        var m = map("progress.chronicle_stars")
        m[battleId] = best
        setMap("progress.chronicle_stars", m)
        return best
    }

    // 残局
    func puzzleSolvedIds() -> Set<Int> { ints("progress.puzzle_solved") }
    func markPuzzleSolved(_ id: Int) { setInts("progress.puzzle_solved", puzzleSolvedIds().union([id])) }
    func puzzleAttempts(_ id: Int) -> Int { d.integer(forKey: "progress.puzzle_attempts_\(id)") }

    @discardableResult
    func incrementPuzzleAttempts(_ id: Int) -> Int {
        let v = puzzleAttempts(id) + 1
        d.set(v, forKey: "progress.puzzle_attempts_\(id)")
        return v
    }

    // 每日一题
    private var dailyStarterIds: [Int] { Puzzles.all.filter { $0.maxMoves <= 2 }.map(\.id) }
    private var dailyCompleted: Set<Int> { ints("progress.daily_completed") }

    func dailyPractice(today: Int = DailyPractice.epochDay(Date())) -> DailyPracticeSnapshot {
        DailyPractice.snapshot(today: today, puzzleIds: Puzzles.all.map(\.id), completed: dailyCompleted, starterIds: dailyStarterIds)
    }

    func dailyPuzzleId(today: Int = DailyPractice.epochDay(Date())) -> Int {
        DailyPractice.puzzleIdFor(today: today, puzzleIds: Puzzles.all.map(\.id), starterIds: dailyStarterIds, completed: dailyCompleted)
    }

    @discardableResult
    func markDailyCompleted(_ id: Int, challengeDay: Int, today: Int = DailyPractice.epochDay(Date())) -> Bool {
        let before = dailyCompleted
        let after = DailyPractice.recordCompletion(today: today, challengeDay: challengeDay, solvedPuzzleId: id,
                                                   puzzleIds: Puzzles.all.map(\.id), completed: before, starterIds: dailyStarterIds)
        guard after != before else { return false }
        setInts("progress.daily_completed", after)
        return true
    }

    // 残局速拼
    var puzzleRushHighScore: Int { d.integer(forKey: "progress.rush_high") }

    @discardableResult
    func recordPuzzleRushScore(_ score: Int) -> Bool {
        guard score > puzzleRushHighScore else { return false }
        objectWillChange.send()
        d.set(score, forKey: "progress.rush_high")
        return true
    }
}

/// 一盘对局的记录 (复盘列表/回放)
struct GameRecord: Codable, Identifiable, Hashable {
    var id: Int64 = 0
    var startTime: Date
    var endTime: Date
    var playerSide: Int
    var difficulty: Int
    var engineElo: Int
    /// 1 胜 0 和 -1 负
    var result: Int
    var ratingDelta: Int
    var playerRating: Int
    var moves: [String]
    var initialFen: String
    var endReason: String
}

/// 对局库: Application Support/games.json
final class GameStore: ObservableObject {
    static let shared = GameStore()
    @Published private(set) var records: [GameRecord] = []
    private let url: URL
    private let queue = DispatchQueue(label: "qiyan.gamestore")

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("games.json")
        if let data = try? Data(contentsOf: url),
           let list = try? JSONDecoder().decode([GameRecord].self, from: data) {
            records = list.sorted { $0.startTime > $1.startTime }
        }
    }

    /// 插入或覆盖 (id=0 时分配新 id), 返回 id
    @discardableResult
    func save(_ record: GameRecord) -> Int64 {
        var r = record
        if r.id == 0 { r.id = (records.map(\.id).max() ?? 0) + 1 }
        if let i = records.firstIndex(where: { $0.id == r.id }) { records[i] = r } else { records.insert(r, at: 0) }
        records.sort { $0.startTime > $1.startTime }
        persist()
        return r.id
    }

    func record(_ id: Int64) -> GameRecord? { records.first { $0.id == id } }

    func delete(_ id: Int64) {
        records.removeAll { $0.id == id }
        persist()
    }

    func clear() {
        records = []
        persist()
    }

    private func persist() {
        let snapshot = records
        let url = self.url
        queue.async {
            if let data = try? JSONEncoder().encode(snapshot) { try? data.write(to: url, options: .atomic) }
        }
    }
}
