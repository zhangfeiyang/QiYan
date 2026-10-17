import Foundation

/// 难度契约 (与安卓 DifficultySettings 一致): 8 档, 默认第 1 档, 前两档是练习.
enum DifficultySettings {
    static let maxLevel = 8
    static let defaultLevel = 1
    static let storageVersion = 2

    static func normalize(_ level: Int) -> Int { min(max(level, 1), maxLevel) }
    static func migrateLegacyLevel(_ old: Int) -> Int { (min(max(old, 1), 15) + 2) / 3 }
    static func isTraining(_ level: Int) -> Bool { normalize(level) <= 2 }

    /// 相对积分换算的估计值, 未经外部标定
    static func nominalElo(_ level: Int) -> Int {
        [350, 800, 1450, 2000, 2500, 2600, 2700, 2800][normalize(level) - 1]
    }
}

/// 登峰棋路的八关: 关卡号即对手强度
struct CampaignStage {
    let level: Int
    var nameKey: String { "stage_\(level)_name" }
    var briefKey: String { "stage_\(level)_brief" }
}

enum CampaignStages {
    static let all: [CampaignStage] = (1...DifficultySettings.maxLevel).map { CampaignStage(level: $0) }
    static func of(_ level: Int) -> CampaignStage { all[DifficultySettings.normalize(level) - 1] }
}

/// 闯关三星: 赢一星, 不用提示一星, 不悔棋一星. 记下的星只升不降.
enum CampaignStars {
    static let max = 3

    static func award(won: Bool, hintsUsed: Int, undosUsed: Int) -> Int {
        guard won else { return 0 }
        return 1 + (hintsUsed <= 0 ? 1 : 0) + (undosUsed <= 0 ? 1 : 0)
    }

    static func merge(previous: Int, earned: Int) -> Int {
        Swift.max(clamp(previous), clamp(earned))
    }

    /// stored == nil 表示还没写过星数; 旧档通关按一星展示
    static func display(stored: Int?, passed: Bool) -> Int {
        if let s = stored { return clamp(s) }
        return passed ? 1 : 0
    }

    private static func clamp(_ v: Int) -> Int { Swift.min(Swift.max(v, 0), max) }
}

/// 旧版 15 档闯关进度迁移: 连通三关才算新的一关
enum CampaignProgressMigration {
    static func migrate(oldUnlocked: Int, oldPassed: Set<Int>) -> (unlocked: Int, passed: Set<Int>) {
        let passed = Set((1...DifficultySettings.maxLevel).filter { level in
            ((level - 1) * 3 + 1...level * 3).allSatisfy { oldPassed.contains($0) }
        })
        return (DifficultySettings.migrateLegacyLevel(oldUnlocked), passed)
    }
}

struct HistoryBattle: Identifiable {
    let id: Int
    let chapterId: Int
    let tactic: String
    /// 该杀法在千题库中的序号, 从 0 起
    let occurrence: Int
    var nameKey: String { "battle_\(id)_name" }
    var briefKey: String { "battle_\(id)_brief" }
}

struct HistoryChapter: Identifiable {
    let id: Int
    let battles: [HistoryBattle]
    var titleKey: String { "history_ch_\(id)" }
}

/// 历史战役: 五章二十战, 每战用千题库里一道一步杀收束
enum HistoryCampaign {
    private static let plan: [[(String, Int)]] = [
        [("铁门栓", 0), ("双车错", 0), ("马后炮", 0), ("卧槽马", 0)],
        [("重炮", 0), ("钓鱼马", 0), ("双车错", 1), ("卧槽马", 1)],
        [("白脸将", 0), ("重炮", 1), ("马后炮", 1), ("双车错", 2)],
        [("铁门栓", 1), ("重炮", 2), ("钓鱼马", 1), ("卧槽马", 2)],
        [("白脸将", 1), ("挂角马", 0), ("马后炮", 2), ("天地炮", 0)],
    ]

    static let chapters: [HistoryChapter] = plan.enumerated().map { ci, rows in
        HistoryChapter(id: ci + 1, battles: rows.enumerated().map { bi, row in
            HistoryBattle(id: ci * 4 + bi + 1, chapterId: ci + 1, tactic: row.0, occurrence: row.1)
        })
    }

    static let battles: [HistoryBattle] = chapters.flatMap(\.battles)

    static func battle(_ id: Int) -> HistoryBattle? { battles.first { $0.id == id } }

    static func resolve(_ battle: HistoryBattle, _ catalog: [Puzzle]) -> Puzzle? {
        let matches = catalog.filter { $0.tactic == battle.tactic }
        return battle.occurrence < matches.count ? matches[battle.occurrence] : nil
    }
}

/// 中文着法语音解析: 复用记谱作标准答案, 对全部合法着法逐一比对.
enum VoiceCommands {
    static func parse(_ position: Position, _ raw: String) -> [Move] {
        guard let spoken = normalize(raw) else { return [] }
        return position.legalMoves().filter { normalize(Move.chineseString($0, position)) == spoken }
    }

    static func normalize(_ raw: String) -> String? {
        var t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        t = t.replacingOccurrences(of: "[\\s　]+", with: "", options: .regularExpression)
        let fullWidth: [Character: Character] = ["１": "1", "２": "2", "３": "3", "４": "4", "５": "5",
                                                 "６": "6", "７": "7", "８": "8", "９": "9", "０": "0",
                                                 "傌": "马", "砲": "炮", "俥": "车", "帥": "帅", "將": "将", "進": "进"]
        t = String(t.map { fullWidth[$0] ?? $0 })
        t = t.replacingOccurrences(of: "吃[将士象相仕帅马炮车兵卒]?$", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "(将军|绝杀|将死|将)$", with: "", options: .regularExpression)
        return t.isEmpty ? nil : t
    }
}
