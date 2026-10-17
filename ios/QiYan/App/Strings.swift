import Foundation

/// The language the interface resolved to (system or per-app language from iOS Settings).
enum AppLocale {
    static var identifier: String { Bundle.main.preferredLocalizations.first ?? "en" }
    static var isChinese: Bool { identifier.hasPrefix("zh") }
    static var locale: Locale { Locale(identifier: identifier) }
}

/// Localised string by Android resource key (tools/make_strings.py keeps the names).
func L(_ key: String) -> String { NSLocalizedString(key, comment: "") }

/// Localised format string; Android `%1$s` was converted to `%1$@`, `%d` to `%ld`.
func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), locale: AppLocale.locale, arguments: args)
}

enum Names {
    static func tactic(_ key: String?) -> String {
        let k = key ?? "绝杀"
        guard let res = TacticNames.keys[k] else { return k }
        return L(res)
    }

    static func opening(_ key: String?) -> String {
        guard let k = key, !k.isEmpty else { return "" }
        guard let res = OpeningNames.keys[k] else { return k }
        return L(res)
    }

    /// 棋子名 (吃X 标签)
    static func piece(_ p: Piece) -> String {
        L(["", "piece_king", "piece_advisor", "piece_elephant", "piece_horse", "piece_rook", "piece_cannon",
           "piece_pawn"][max(p.type, 1)])
    }

    static func pieceRule(_ p: Piece) -> String {
        L(["", "game_rule_king", "game_rule_advisor", "game_rule_elephant", "game_rule_horse", "game_rule_rook",
           "game_rule_cannon", "game_rule_pawn"][max(p.type, 1)])
    }

    /// 难度档的名字/标签/说明 (与安卓 DifficultyLevel 一致)
    static func level(_ id: Int) -> (name: String, tag: String, desc: String) {
        let keys: [(String, String, String)] = [
            ("difficulty_gentle", "difficulty_gentle_tag", "difficulty_gentle_desc"),
            ("difficulty_starter", "difficulty_starter_tag", "difficulty_starter_desc"),
            ("difficulty_club", "difficulty_club_tag", "difficulty_club_desc"),
            ("difficulty_expert", "difficulty_expert_tag", "difficulty_expert_desc"),
            ("difficulty_master_new", "difficulty_master_new_tag", "difficulty_master_new_desc"),
            ("difficulty_6", "difficulty_6_tag", "difficulty_6_desc"),
            ("difficulty_7", "difficulty_7_tag", "difficulty_7_desc"),
            ("difficulty_8", "difficulty_8_tag", "difficulty_8_desc"),
        ]
        let k = keys[DifficultySettings.normalize(id) - 1]
        return (L(k.0), L(k.1), L(k.2))
    }
}

/// 记谱与棋子字符集: 默认跟随界面语言, 设置里可改.
enum Notation {
    enum Style { case chinese, wxf }

    static var style: Style {
        switch AppSettings.shared.notationMode {
        case 1: return .chinese
        case 2: return .wxf
        default: return AppLocale.isChinese ? .chinese : .wxf
        }
    }

    static var latinPieces: Bool {
        switch AppSettings.shared.pieceSetMode {
        case 1: return false
        case 2: return true
        default: return !AppLocale.isChinese
        }
    }

    /// position 必须是走这一步之前的局面
    static func format(_ move: Move, _ position: Position) -> String {
        style == .chinese ? Move.chineseString(move, position) : Move.wxfString(move, position)
    }

    static func pieceLabel(_ piece: Piece) -> String {
        latinPieces ? String(piece.fen).uppercased() : piece.chinese
    }
}
