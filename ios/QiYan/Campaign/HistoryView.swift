import SwiftUI

/// 历史战役: 五个篇章, 每战用千题库里一道已验证的一步杀收束
struct HistoryView: View {
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = ProgressStore.shared

    var body: some View {
        ZStack {
            PageBackground()
            VStack(alignment: .leading, spacing: 0) {
                TopBar(title: L("home_history_title"), onBack: { dismiss() })
                Text(L("history_intro")).font(.system(size: 14)).foregroundStyle(Theme.textSecondary).lineSpacing(4)
                    .padding(.horizontal, 20).padding(.bottom, 4)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(HistoryCampaign.chapters) { chapter in
                            Text(L(chapter.titleKey)).font(.system(size: 22)).foregroundStyle(Theme.textPrimary)
                                .padding(.horizontal, 20).padding(.top, 22).padding(.bottom, 6)
                            ForEach(chapter.battles) { battle in battleRow(battle) }
                        }
                    }
                    .padding(.bottom, 24)
                }
            }
        }
    }

    private func battleRow(_ b: HistoryBattle) -> some View {
        let available = HistoryCampaign.resolve(b, CatalogStore.all) != nil
        let stars = min(max(store.chronicleStars(b.id), 0), 3)
        return Button { router.go(.puzzle(.battle(b.id))) } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(L(b.nameKey)).font(.system(size: 18)).foregroundStyle(Theme.textPrimary)
                    Text(String(repeating: "★", count: stars) + String(repeating: "☆", count: 3 - stars))
                        .font(.system(size: 17)).foregroundStyle(stars > 0 ? Theme.c("rank_gold") : Theme.textPrimary)
                }
                Text(L(b.briefKey)).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!available)
    }
}
