import SwiftUI

/// 千题库: 按杀法筛选, 点进去是红先一步杀
struct PuzzleCatalogView: View {
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = ProgressStore.shared
    @State private var filter: String?
    private let all = CatalogStore.all
    private let filters: [(String, Int)] = {
        var counts: [String: Int] = [:]
        for p in CatalogStore.all { counts[p.tactic ?? "绝杀", default: 0] += 1 }
        return counts.sorted { $0.value > $1.value }
    }()

    var body: some View {
        let solved = store.puzzleSolvedIds()
        let shown = filter.map { f in all.filter { $0.tactic == f } } ?? all
        ZStack {
            PageBackground()
            VStack(alignment: .leading, spacing: 0) {
                TopBar(title: L("home_catalog_title"), onBack: { dismiss() })
                Text(L("catalog_progress", all.filter { solved.contains($0.id) }.count, all.count))
                    .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 20).padding(.bottom, 8)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        chip(L("catalog_filter_all"), nil)
                        ForEach(filters, id: \.0) { f in chip(L("catalog_filter_count", Names.tactic(f.0), f.1), f.0) }
                    }
                    .padding(.horizontal, 16)
                }
                .padding(.bottom, 6)
                List {
                    ForEach(shown) { p in
                        Button { router.go(.puzzle(.catalog(p.id))) } label: { row(p, solved: solved.contains(p.id)) }
                            .buttonStyle(.plain)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }

    private func chip(_ label: String, _ tactic: String?) -> some View {
        let on = filter == tactic
        return Button { filter = tactic } label: {
            Text(label).font(.system(size: 14)).foregroundStyle(on ? Theme.action : Theme.textPrimary)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(Capsule().fill(on ? Theme.c("action_muted") : Theme.surfaceRaised))
                .overlay(Capsule().stroke(on ? Theme.action : Color.clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func row(_ p: Puzzle, solved: Bool) -> some View {
        HStack(spacing: 14) {
            Text("\(p.id - PuzzleCatalog.firstId + 1)").font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.action)
                .lineLimit(1).minimumScaleFactor(0.6)
                .frame(width: 40, height: 30)
                .background(Capsule().fill(Theme.c("action_muted")))
                .overlay(Capsule().stroke(Theme.action, lineWidth: 1.2))
            VStack(alignment: .leading, spacing: 2) {
                Text(Names.tactic(p.tactic)).font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.textPrimary)
                Text(L("catalog_mate_hint")).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
            }
            Spacer()
            Text(L(solved ? "catalog_solved" : "catalog_open")).font(.system(size: 13, weight: .bold))
                .foregroundStyle(solved ? Theme.affirm : Theme.textSecondary)
        }
        .frame(height: 76)
        .contentShape(Rectangle())
    }
}
