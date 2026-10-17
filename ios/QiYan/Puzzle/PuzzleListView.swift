import SwiftUI

/// 残局研习: 六卷左右翻页, 每卷 8 关卡片; 顶部总进度与残局速拼/杀法宝典入口
struct PuzzleListView: View {
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = ProgressStore.shared
    @State private var page = 0

    var body: some View {
        let solved = store.puzzleSolvedIds()
        let total = Puzzles.all.count
        let done = Puzzles.all.filter { solved.contains($0.id) }.count
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("home_puzzle_title"), onBack: { dismiss() })
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(L("puzzle_collection_progress", done, total)).font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Theme.textPrimary)
                        Spacer()
                        Text("\(total > 0 ? done * 100 / total : 0)%").font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Theme.action)
                    }
                    ProgressBar(fraction: total > 0 ? Double(done) / Double(total) : 0)
                }
                .padding(.horizontal, 20).padding(.vertical, 14)
                .card(radius: 14)
                .padding(.horizontal, 16)
                HStack(spacing: 10) {
                    banner(L("puzzle_rush_title"), L("puzzle_rush_banner", store.puzzleRushHighScore), Theme.warning) {
                        router.go(.rush)
                    }
                    let mastered = TacticsAcademy.topics.filter { solved.contains($0.puzzleId) }.count
                    banner(L("tactics_banner_title"), L("tactics_banner_progress", mastered, TacticsAcademy.topics.count),
                           Theme.action) { router.go(.tactics) }
                }
                .padding(.horizontal, 16).padding(.top, 10)
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("puzzle_chapter_\(page + 1)")).font(.system(size: 19, weight: .bold)).foregroundStyle(Theme.textPrimary)
                    Text(L("puzzle_chapter_sub_\(page + 1)")).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 8)
                TabView(selection: $page) {
                    ForEach(0..<Puzzles.chapterCount, id: \.self) { i in
                        ScrollView {
                            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                                ForEach(Puzzles.byChapter(i + 1)) { p in
                                    Button { router.go(.puzzle(.puzzle(p.id))) } label: {
                                        PuzzleCard(puzzle: p, solved: solved.contains(p.id), attempts: store.puzzleAttempts(p.id))
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 16).padding(.bottom, 8)
                        }
                        .tag(i)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                HStack {
                    pageButton(L("page_previous"), icon: "ic_first", leading: true, enabled: page > 0) { page -= 1 }
                    Spacer()
                    Text(L("puzzle_page", page + 1, Puzzles.chapterCount)).font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                    Spacer()
                    pageButton(L("page_next"), icon: "ic_last", leading: false, enabled: page < Puzzles.chapterCount - 1) { page += 1 }
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
            }
        }
    }

    private func banner(_ title: String, _ sub: String, _ color: Color, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(color)
                Text(sub).font(.system(size: 12)).foregroundStyle(Theme.textSecondary).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.vertical, 12)
            .card(radius: 14)
        }
        .buttonStyle(.plain)
    }

    private func pageButton(_ title: String, icon: String, leading: Bool, enabled: Bool,
                            _ action: @escaping () -> Void) -> some View {
        Button { withAnimation { action() } } label: {
            HStack(spacing: 6) {
                if leading { Image(icon).renderingMode(.template).resizable().frame(width: 14, height: 14) }
                Text(title).font(.system(size: 13, weight: .bold))
                if !leading { Image(icon).renderingMode(.template).resizable().frame(width: 14, height: 14) }
            }
            .foregroundStyle(enabled ? Theme.textPrimary : Theme.textTertiary)
            .padding(.horizontal, 16).frame(height: 38)
            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.surfaceRaised))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.borderStrong, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// 细进度条
struct ProgressBar: View {
    let fraction: Double
    var color: Color = Theme.action
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.surfaceSunken)
                Capsule().fill(color).frame(width: g.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 6)
    }
}

/// 一关卡片: 关号 + 状态/已破解印 + 题眼 + 目标
struct PuzzleCard: View {
    let puzzle: Puzzle
    let solved: Bool
    let attempts: Int
    var title: String? = nil
    var number: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(number ?? L("campaign_level_format", puzzle.id)).font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Theme.action)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(Capsule().fill(Theme.c("action_muted")))
                    .overlay(Capsule().stroke(Theme.action, lineWidth: 1.2))
                Spacer(minLength: 4)
                if solved {
                    Text(L("puzzle_solved")).font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.action)
                        .padding(.horizontal, 5).padding(.vertical, 2)
                        .background(RoundedRectangle(cornerRadius: 4).fill(Theme.action.opacity(0.08)))
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(Theme.action, lineWidth: 1.2))
                        .rotationEffect(.degrees(-6))
                } else {
                    Text(attempts > 0 ? L("puzzle_attempts_format", attempts) : L("puzzle_unsolved"))
                        .font(.system(size: 12)).foregroundStyle(Theme.textTertiary).lineLimit(1)
                }
            }
            Spacer(minLength: 10)
            Text(title ?? PuzzleTitles.theme(L(puzzle.nameKey))).font(.system(size: 17, weight: .bold))
                .foregroundStyle(Theme.textPrimary).lineLimit(1).minimumScaleFactor(0.7)
            Spacer(minLength: 10)
            Text(L("puzzle_red_to_move", puzzle.maxMoves)).font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                .lineLimit(1).minimumScaleFactor(0.8)
        }
        .padding(14)
        .frame(height: 148)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceRaised))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(solved ? Theme.affirm : Theme.borderStrong.opacity(0.6),
                                                           lineWidth: solved ? 1.5 : 1))
        .contentShape(Rectangle())
    }
}
