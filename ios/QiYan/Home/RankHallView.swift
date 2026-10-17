import SwiftUI

/// 段位印鉴: 十枚印从初学排到棋王, 当前印标金边. 只有排位对局推动分数.
struct RankHallView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var profile = UserProfile.shared

    var body: some View {
        let progress = RankLadder.progress(profile.displayedRating)
        // 定级中一枚印都还没拿到: 全部显示为"未至"
        let currentId = profile.isInPlacement ? 0 : progress.current.id
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("rank_hall_title"), onBack: { dismiss() })
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(L("rank_hall_intro")).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                            .lineSpacing(3).padding(.top, 12)
                        Text(summary(progress)).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.textPrimary)
                            .padding(.top, 10).padding(.bottom, 8)
                        ForEach(UserRank.ranks, id: \.id) { rank in
                            SealRow(rank: rank, currentId: currentId)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
            }
        }
    }

    private func summary(_ p: RankLadder.Progress) -> String {
        if profile.isInPlacement { return L("rank_hall_placement", profile.totalGames, RankLadder.placementGames) }
        guard let next = p.next else { return L("rank_progress_top") }
        return L("rank_progress_next", L(next.nameKey), p.pointsToNext)
    }
}

private struct SealRow: View {
    let rank: UserRank
    let currentId: Int

    var body: some View {
        let reached = rank.id <= currentId
        let current = rank.id == currentId
        let ink = reached ? Theme.c(rank.colorKey) : Theme.textTertiary
        let range = rank.id == UserRank.ranks.last?.id ? L("rank_range_top", rank.minRating)
            : L("rank_range", rank.minRating, rank.maxRating)
        let status = L(current ? "rank_hall_current" : (reached ? "rank_hall_reached" : "rank_hall_locked"))
        HStack(spacing: 16) {
            ZStack {
                Circle().fill(reached ? Theme.c("piece_face") : Theme.surfaceRaised)
                Circle().strokeBorder(current ? Theme.c("rank_gold") : ink, lineWidth: current ? 2.6 : 1.6)
                if current { Circle().stroke(Theme.c("rank_gold"), lineWidth: 1).frame(width: 52, height: 52) }
                Text("\(rank.id)").font(Font(PieceFont.font(16))).bold().foregroundStyle(ink)
            }
            .frame(width: 44, height: 44)
            VStack(alignment: .leading, spacing: 6) {
                Text(L(rank.nameKey)).font(Font(PieceFont.font(16))).bold()
                    .foregroundStyle(reached ? Theme.textPrimary : Theme.textTertiary)
                Text("\(range)  ·  \(status)").font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .frame(height: 76)
        .accessibilityElement(children: .combine)
    }
}
