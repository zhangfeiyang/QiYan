import SwiftUI

/// 新对局: 挑对手 + 棋路 + 执子. 首页"开始对弈"直接开上一盘的设定, 想换对手才进这一页.
struct NewGameView: View {
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var settings = AppSettings.shared
    @State private var level = AppSettings.shared.lastDifficulty
    @State private var style = PlayStyle.fromId(AppSettings.shared.lastStyle)
    @State private var side = AppSettings.shared.sidePref

    var body: some View {
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("new_game_title"), onBack: { dismiss() })
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(L("new_game_guidance")).font(.system(size: 25, design: .serif)).foregroundStyle(Theme.textPrimary)
                            .padding(.top, 12)
                        Text(L("new_game_support")).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                            .lineSpacing(3).padding(.top, 10).padding(.bottom, 20)
                        VStack(spacing: 8) {
                            ForEach(1...DifficultySettings.maxLevel, id: \.self) { l in
                                OpponentRow(level: l, selected: l == level)
                                    .onTapGesture { level = l }
                            }
                        }
                        if level <= 2 {
                            Text(L("new_game_style_training")).font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                                .padding(.top, 12)
                        }
                        Text(L("new_game_style")).font(.system(size: 13)).foregroundStyle(Theme.textTertiary)
                            .padding(.top, 20).padding(.bottom, 8)
                        Segmented(items: PlayStyle.allCases.map { ($0.id, L($0.nameKey)) }, selected: style.id) {
                            style = PlayStyle.fromId($0)
                        }
                        Text(L(style.descKey)).font(.system(size: 14)).foregroundStyle(Theme.textSecondary).padding(.top, 8)
                        Text(L("new_game_side")).font(.system(size: 13)).foregroundStyle(Theme.textTertiary)
                            .padding(.top, 20).padding(.bottom, 8)
                        Segmented(items: [(Side.red, L("side_red")), (AppSettings.randomSide, L("side_random")),
                                          (Side.black, L("side_black"))], selected: side) {
                            side = $0
                            settings.sidePref = $0
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
                VStack {
                    Button(action: start) {
                        Label(L("menu_play"), image: "ic_play")
                    }
                    .buttonStyle(GoldButtonStyle())
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .background(Theme.surfaceRaised.ignoresSafeArea(edges: .bottom))
            }
        }
    }

    private func start() {
        // 随机在开局这一刻掷: 选"随机"的意思是每局重掷
        let actual = side == AppSettings.randomSide ? (Bool.random() ? Side.red : Side.black) : side
        router.replace(.game(GameConfig(difficulty: level, playerSide: actual, style: style)))
    }
}

/// 新对局页的一档对手: 头像 + 名字 + 说明, 选中的一格描红
private struct OpponentRow: View {
    let level: Int
    let selected: Bool

    var body: some View {
        let n = Names.level(level)
        HStack(spacing: 14) {
            OpponentAvatar(level: level, size: 48)
            VStack(alignment: .leading, spacing: 4) {
                Text(n.name).font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.textPrimary)
                Text(n.desc).font(.system(size: 13)).foregroundStyle(Theme.textSecondary).lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Text("✓").font(.system(size: 18)).foregroundStyle(Theme.action).opacity(selected ? 1 : 0)
                .frame(width: 24)
        }
        .padding(14)
        .frame(minHeight: 88)
        .background(RoundedRectangle(cornerRadius: 12).fill(selected ? Theme.c("action_muted") : Color.clear))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? Theme.action : Color.clear, lineWidth: 1.5))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// 一排等宽的单选按钮 (MaterialButtonToggleGroup); 放不下时竖排
struct Segmented<ID: Hashable>: View {
    let items: [(ID, String)]
    let selected: ID
    let onPick: (ID) -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 0) { cells(vertical: false) }
            VStack(spacing: 0) { cells(vertical: true) }
        }
        .background(RoundedRectangle(cornerRadius: 10).fill(Theme.surfaceRaised))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.borderStrong, lineWidth: 1))
    }

    @ViewBuilder private func cells(vertical: Bool) -> some View {
        ForEach(Array(items.enumerated()), id: \.offset) { i, item in
            let on = item.0 == selected
            Button { onPick(item.0) } label: {
                Text(item.1).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    .foregroundStyle(on ? Theme.action : Theme.textPrimary)
                    .padding(.horizontal, 8)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .fixedSize(horizontal: !vertical, vertical: false)
                    .frame(maxWidth: .infinity)
                    .background(on ? Theme.c("action_muted") : Color.clear)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(on ? .isSelected : [])
            if i < items.count - 1 {
                if vertical { Rectangle().fill(Theme.borderStrong).frame(height: 1) }
                else { Rectangle().fill(Theme.borderStrong).frame(width: 1, height: 46) }
            }
        }
    }
}
