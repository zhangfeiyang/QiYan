import SwiftUI

/// 八档对手的棋子头像: 兵马炮车将将帥帥, 每档一种刻环与字色
struct OpponentAvatar: View {
    let level: Int
    var size: CGFloat = 56

    private static let ladder = ["兵", "马", "炮", "车", "将", "将", "帥", "帥"]
    private static let latin = ["P", "N", "C", "R", "K", "K", "K", "K"]

    static func tier(_ level: Int) -> Color { Theme.c("avatar_tier_\(DifficultySettings.normalize(level))") }

    var body: some View {
        let i = DifficultySettings.normalize(level) - 1
        let tier = OpponentAvatar.tier(level)
        ZStack {
            Circle().fill(Theme.c("piece_face"))
            Circle().stroke(tier, lineWidth: max(size * 0.05, 2)).padding(size * 0.08)
            Text(Notation.latinPieces ? OpponentAvatar.latin[i] : OpponentAvatar.ladder[i])
                .font(.system(size: size * 0.54, weight: .bold))
                .foregroundStyle(tier)
        }
        .frame(width: size, height: size)
        .shadow(color: .black.opacity(0.12), radius: 2, y: 1)
        .accessibilityHidden(true)
    }
}

/// 一档对手的说明行 (新对局页与对局中改档共用)
struct LevelRow: View {
    let level: Int
    let selected: Bool

    var body: some View {
        let n = Names.level(level)
        HStack(alignment: .center, spacing: 14) {
            OpponentAvatar(level: level, size: 48)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(n.name).font(.system(size: 17, weight: .bold)).foregroundStyle(Theme.textPrimary)
                    if n.tag != n.name {
                        Text(n.tag).font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                            .padding(.horizontal, 8).padding(.vertical, 2)
                            .background(Capsule().fill(Theme.surfaceSunken))
                    }
                }
                Text(n.desc).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            if selected {
                Image(systemName: "checkmark").font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.action)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(selected ? Theme.c("action_muted") : Theme.surfaceRaised))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? Theme.action : Theme.borderSubtle, lineWidth: selected ? 1.5 : 1))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// 棋路选择: 均衡/攻杀/稳健/灵动
struct StylePicker: View {
    @Binding var style: PlayStyle

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("style_title")).font(.system(size: 13)).foregroundStyle(Theme.textTertiary)
            HStack(spacing: 0) {
                ForEach(PlayStyle.allCases) { s in
                    Button { style = s } label: {
                        Text(L(s.nameKey)).font(.system(size: 14, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                            .foregroundStyle(style == s ? Theme.action : Theme.textPrimary)
                            .frame(maxWidth: .infinity, minHeight: 40)
                            .background(style == s ? Theme.c("action_muted") : Color.clear)
                    }
                    .buttonStyle(.plain)
                    if s != PlayStyle.allCases.last { Rectangle().fill(Theme.borderStrong).frame(width: 1, height: 40) }
                }
            }
            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.surfaceRaised))
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.borderStrong, lineWidth: 1))
            Text(L(style.descKey)).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
        }
    }
}

/// 对局中改档/改棋路
struct DifficultySheet: View {
    @State var level: Int
    @State var style: PlayStyle
    let onConfirm: (Int, PlayStyle) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    StylePicker(style: $style).padding(.bottom, 6)
                    ForEach(1...DifficultySettings.maxLevel, id: \.self) { l in
                        LevelRow(level: l, selected: l == level).onTapGesture { level = l }
                    }
                }
                .padding(16)
            }
            .background(PageBackground())
            .navigationTitle(L("menu_difficulty"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("dialog_cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("dialog_confirm")) { onConfirm(level, style); dismiss() }.foregroundStyle(Theme.action)
                }
            }
        }
    }
}
