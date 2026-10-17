import Foundation

/// 一道残局题. 红先, 玩家须在 maxMoves 步(自己走的步数)内将死黑方.
/// 标题/提示是字符串键 (与安卓 strings.xml 同名), 千题库与战役关用 override.
struct Puzzle: Identifiable, Hashable {
    let id: Int
    let nameKey: String
    let maxMoves: Int
    let fen: String
    let hintKey: String
    let chapter: Int
    let tactic: String?
}

/// 残局研习: 六卷 48 关, 每卷 8 关. 均经 PikaFish 复核存在强制杀.
enum Puzzles {
    static let chapterCount = 6
    static let perChapter = 8

    private static let table: [(Int, String, String)] = [
        // 卷一: 一步必杀
        (1, "3aka3/4n4/9/9/2C6/9/9/9/9/3K5 w - - 0 1", "闷宫"),
        (1, "3aka3/9/4C4/9/9/2C6/9/9/9/3K5 w - - 0 1", "重炮"),
        (1, "R2ak4/8R/6N2/9/9/9/9/9/9/3K5 w - - 0 1", "双车错"),
        (1, "4k4/4a4/9/9/9/9/9/9/R8/4K4 w - - 0 1", "白脸将"),
        (1, "3aka3/9/9/4C4/9/9/9/9/R8/3K5 w - - 0 1", "铁门栓"),
        (1, "3ak4/4a4/9/9/9/9/9/9/R1C6/3K5 w - - 0 1", "天地炮"),
        (1, "3ak4/4a4/2N6/9/9/9/9/9/R8/4K4 w - - 0 1", "卧槽马"),
        (1, "4k4/9/4N4/9/9/9/9/C8/9/5K3 w - - 0 1", "马后炮"),
        // 卷二: 两步杀
        (2, "4ka3/R7R/4b4/9/9/9/9/9/9/3K5 w - - 0 1", "双车错"),
        (2, "3aka3/9/9/9/9/9/R8/9/2R6/3K5 w - - 0 1", "双车错"),
        (2, "4k4/4a4/2N6/9/9/9/9/9/R8/3K5 w - - 0 1", "卧槽马"),
        (2, "3aka3/9/9/9/9/9/4N4/4R4/9/3K5 w - - 0 1", "拔簧"),
        (2, "3aka3/9/9/9/9/4N4/9/2C6/R8/3K5 w - - 0 1", "挂角马"),
        (2, "3aka3/9/9/9/9/4P4/9/2C6/R8/3K5 w - - 0 1", "铁门栓"),
        (2, "3aka3/9/9/9/9/9/2R6/9/R8/3K5 w - - 0 1", "双车错"),
        (2, "3aka3/9/9/9/9/9/9/2C6/2R6/3K5 w - - 0 1", "重炮"),
        // 卷三: 两至三步
        (3, "3ak4/4a4/9/9/9/2N6/9/9/R8/3K5 w - - 0 1", "卧槽马"),
        (3, "3ak4/4a4/9/2N6/9/9/9/9/R8/3K5 w - - 0 1", "钓鱼马"),
        (3, "3ak4/4a4/4N4/9/9/9/9/9/R8/3K5 w - - 0 1", "挂角马"),
        (3, "3ak4/4a4/9/9/9/9/9/R7R/9/3K5 w - - 0 1", "双车错"),
        (2, "4k4/4a4/6N2/9/9/9/9/9/R8/3K5 w - - 0 1", "钓鱼马"),
        (2, "3aka3/9/9/9/9/9/4C4/9/R1C6/3K5 w - - 0 1", "天地炮"),
        (3, "3aka3/9/9/9/9/4N4/9/9/R1C6/3K5 w - - 0 1", "马后炮"),
        (3, "4k4/9/9/9/9/9/9/R8/1R7/3K5 w - - 0 1", "双车错"),
        // 卷四: 三步名局
        (3, "3aka3/9/9/9/9/9/9/R8/1R7/3K5 w - - 0 1", "双车错"),
        (3, "3ak4/4a4/9/9/9/9/9/R8/1R7/3K5 w - - 0 1", "双车错"),
        (3, "3ak4/4a4/9/9/9/9/9/9/R7R/3K5 w - - 0 1", "大刀剜心"),
        (3, "3ak4/4a4/4N4/9/9/9/9/9/4R4/4K4 w - - 0 1", "挂角马"),
        (3, "3ak4/9/9/9/9/9/9/R8/1R7/3K5 w - - 0 1", "双车错"),
        (3, "4ka3/9/9/9/4N4/9/9/9/R7R/3K5 w - - 0 1", "双车错"),
        (3, "3ak4/4a4/9/9/9/9/9/9/3R5/3K5 w - - 0 1", "白脸将"),
        (3, "3ak4/4a4/9/9/9/2N6/9/9/4R4/3K5 w - - 0 1", "卧槽马"),
        // 卷五: 三至五步
        (4, "3ak4/4a4/9/2N6/9/9/9/9/4R4/3K5 w - - 0 1", "钓鱼马"),
        (4, "3aka3/9/4P4/9/9/9/9/9/R8/3K5 w - - 0 1", "铁门栓"),
        (4, "4k4/4a4/4N4/9/9/9/9/9/R8/4K4 w - - 0 1", "挂角马"),
        (4, "3aka3/9/9/9/9/9/R8/2R6/4N4/3K5 w - - 0 1", "双车错"),
        (4, "3aka3/9/9/9/4P4/9/9/4R4/9/3K5 w - - 0 1", "铁门栓"),
        (4, "3aka3/9/9/9/9/2N6/9/9/R8/3K5 w - - 0 1", "卧槽马"),
        (5, "3aka3/9/9/9/4P4/9/9/9/R7R/3K5 w - - 0 1", "双车错"),
        (3, "4ka3/9/9/9/9/9/9/4C4/R7R/3K5 w - - 0 1", "天地炮"),
        // 卷六: 古谱名局
        (6, "3aka3/9/9/9/P8/9/9/4C4/R8/3K5 w - - 0 1", "铁门栓"),
        (8, "3aka3/9/9/9/9/9/4N4/9/R8/3K5 w - - 0 1", "挂角马"),
        (8, "3aka3/9/9/9/4N4/9/9/9/R8/3K5 w - - 0 1", "卧槽马"),
        (6, "3ak4/9/9/9/9/9/9/9/R8/3K5 w - - 0 1", "白脸将"),
        (2, "4ka3/R7R/9/9/9/9/9/9/9/3K5 w - - 0 1", "双车错"),
        (3, "4ka3/9/2N6/9/9/9/9/9/R8/3K5 w - - 0 1", "卧槽马"),
        (3, "4ka3/9/9/9/9/9/9/R8/5R3/3K5 w - - 0 1", "双车错"),
        (2, "3ak4/4a4/9/9/9/9/2C6/3C5/9/5K3 w - - 0 1", "重炮"),
    ]

    static let all: [Puzzle] = table.enumerated().map { i, row in
        let id = i + 1
        return Puzzle(id: id, nameKey: "puzzle_\(id)_name", maxMoves: row.0, fen: row.1,
                      hintKey: "puzzle_\(id)_hint", chapter: (id - 1) / 8 + 1, tactic: row.2)
    }

    static func byId(_ id: Int) -> Puzzle? { all.first { $0.id == id } }
    static func byChapter(_ chapter: Int) -> [Puzzle] { all.filter { $0.chapter == chapter } }
    static func next(_ id: Int) -> Puzzle? { all.first { $0.id == id + 1 } }
}

/// 千题库: assets/puzzles/catalog.txt, 每行 "id \t 杀法 \t FEN", 全是红先一步杀.
enum PuzzleCatalog {
    static let firstId = 1001

    static func parse(_ text: String) -> [Puzzle] {
        text.split(whereSeparator: \.isNewline).compactMap { raw in
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty else { return nil }
            let parts = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 3, let id = Int(parts[0]) else { return nil }
            return Puzzle(id: id, nameKey: "catalog_mate_title", maxMoves: 1, fen: parts[2],
                          hintKey: "catalog_mate_hint", chapter: 0, tactic: parts[1])
        }
    }
}

/// 题名在卡片上只写题眼: "一步杀·闷宫" -> "闷宫"
enum PuzzleTitles {
    static func theme(_ name: String) -> String {
        guard let cut = name.firstIndex(of: "·") else { return name }
        let rest = name[name.index(after: cut)...].trimmingCharacters(in: .whitespaces)
        return rest.isEmpty ? name : rest
    }
}

/// 杀法宝典的主子徽记
enum KeyPiece {
    case cannon, rook, horse, king
    var hanzi: String { ["炮", "车", "马", "帅"][index] }
    var latin: String { ["C", "R", "N", "K"][index] }
    private var index: Int {
        switch self { case .cannon: return 0; case .rook: return 1; case .horse: return 2; case .king: return 3 }
    }
}

struct TacticTopic: Identifiable {
    let id: String
    let name: String
    let keyPiece: KeyPiece
    let rhymeKey: String
    let descriptionKey: String
    let puzzleId: Int
    /// 大师原声音频文件名 (不含扩展名)
    let voice: String
}

/// 杀法宝典: 11 式
enum TacticsAcademy {
    static let topics: [TacticTopic] = [
        TacticTopic(id: "mahoupao", name: "马后炮", keyPiece: .cannon, rhymeKey: "tactic_rhyme_mahoupao",
                    descriptionKey: "tactic_desc_mahoupao", puzzleId: 8, voice: "sfx_win_mahoupao"),
        TacticTopic(id: "tiemenshuan", name: "铁门栓", keyPiece: .rook, rhymeKey: "tactic_rhyme_bolt",
                    descriptionKey: "tactic_desc_bolt", puzzleId: 5, voice: "sfx_win_tiemenshuan"),
        TacticTopic(id: "zhongpao", name: "重炮", keyPiece: .cannon, rhymeKey: "tactic_rhyme_double_cannon",
                    descriptionKey: "tactic_desc_double_cannon", puzzleId: 2, voice: "sfx_win_zhongpao"),
        TacticTopic(id: "shuangche", name: "双车错", keyPiece: .rook, rhymeKey: "tactic_rhyme_double_rook",
                    descriptionKey: "tactic_desc_double_rook", puzzleId: 9, voice: "sfx_win_shuangche"),
        TacticTopic(id: "wochaoma", name: "卧槽马", keyPiece: .horse, rhymeKey: "tactic_rhyme_pocket",
                    descriptionKey: "tactic_desc_pocket", puzzleId: 7, voice: "sfx_win_wochaoma"),
        TacticTopic(id: "guajiaoma", name: "挂角马", keyPiece: .horse, rhymeKey: "tactic_rhyme_corner",
                    descriptionKey: "tactic_desc_corner", puzzleId: 19, voice: "sfx_win_guajiaoma"),
        TacticTopic(id: "bailianjiang", name: "白脸将", keyPiece: .king, rhymeKey: "tactic_rhyme_face",
                    descriptionKey: "tactic_desc_face", puzzleId: 4, voice: "sfx_win_bailianjiang"),
        TacticTopic(id: "tiandipao", name: "天地炮", keyPiece: .cannon, rhymeKey: "tactic_rhyme_heaven",
                    descriptionKey: "tactic_desc_heaven", puzzleId: 22, voice: "sfx_win_tiandipao"),
        TacticTopic(id: "diaoyuma", name: "钓鱼马", keyPiece: .horse, rhymeKey: "tactic_rhyme_fishing",
                    descriptionKey: "tactic_desc_fishing", puzzleId: 21, voice: "sfx_win_diaoyuma"),
        TacticTopic(id: "dadaowanxin", name: "大刀剜心", keyPiece: .rook, rhymeKey: "tactic_rhyme_heart",
                    descriptionKey: "tactic_desc_heart", puzzleId: 27, voice: "sfx_win_dadaowanxin"),
        TacticTopic(id: "mengong", name: "闷宫", keyPiece: .cannon, rhymeKey: "tactic_rhyme_smothered",
                    descriptionKey: "tactic_desc_smothered", puzzleId: 1, voice: "sfx_win_mengong"),
    ]
}

/// 杀法/开局中文键 -> 界面字符串键
enum TacticNames {
    static let keys: [String: String] = [
        "马后炮": "tactic_label_mahoupao", "铁门栓": "tactic_label_bolt", "重炮": "tactic_label_double_cannon",
        "双车错": "tactic_label_double_rook", "卧槽马": "tactic_label_pocket", "挂角马": "tactic_label_corner",
        "钓鱼马": "tactic_label_fishing", "白脸将": "tactic_label_face", "天地炮": "tactic_label_heaven",
        "大刀剜心": "tactic_label_heart", "闷宫": "tactic_label_smothered", "拔簧": "tactic_label_spring",
        "绝杀": "tactic_label_mate",
    ]
}

enum OpeningNames {
    static let keys: [String: String] = [
        "中炮对屏风马": "opening_central_vs_screen", "顺手炮": "opening_same_cannon",
        "列手炮": "opening_opposite_cannon", "中炮对反宫马": "opening_central_vs_reversed",
        "中炮对单提马": "opening_central_vs_single", "中炮对三步虎": "opening_central_vs_tiger",
        "仙人指路对卒底炮": "opening_pawn_vs_cannon", "仙人指路互进卒": "opening_mutual_pawn",
        "仙人指路对中炮": "opening_pawn_vs_central", "仙人指路对进马": "opening_pawn_vs_horse",
        "仙人指路对飞相": "opening_pawn_vs_elephant", "飞相局对左中炮": "opening_elephant_vs_left",
        "飞相局对右中炮": "opening_elephant_vs_right", "飞相局对进马": "opening_elephant_vs_horse",
        "飞相局对进卒": "opening_elephant_vs_pawn", "飞相局对士角炮": "opening_elephant_vs_corner",
        "飞相局对过宫炮": "opening_elephant_vs_crossing", "起马局对挺卒": "opening_horse_vs_pawn",
        "起马局对中炮": "opening_horse_vs_central", "起马局对进马": "opening_horse_vs_horse",
        "当头炮": "opening_central_cannon", "仙人指路": "opening_pawn", "飞相局": "opening_elephant",
        "起马局": "opening_horse", "过宫炮": "opening_crossing_cannon", "士角炮": "opening_corner_cannon",
        "金钩炮": "opening_hook_cannon", "铁滑车": "opening_iron_roller", "边马局": "opening_edge_horse",
        "边兵局": "opening_edge_pawn",
    ]
}
