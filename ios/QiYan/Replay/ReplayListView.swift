import SwiftUI

/// 复盘主页: 已保存的对局, 点开进回放
struct ReplayListView: View {
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = GameStore.shared

    var body: some View {
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("title_replay"), onBack: { dismiss() })
                if store.records.isEmpty {
                    Spacer()
                    VStack(spacing: 12) {
                        Image("ic_history").renderingMode(.template).resizable().frame(width: 64, height: 64)
                            .foregroundStyle(Theme.textTertiary)
                        Text(L("replay_empty")).font(.system(size: 15)).foregroundStyle(Theme.textSecondary)
                    }
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(store.records.prefix(100), id: \.id) { r in
                                Button { router.go(.replay(r.id)) } label: { RecordRow(record: r) }
                                    .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                    }
                }
            }
        }
    }
}

private struct RecordRow: View {
    let record: GameRecord
    private static let date: DateFormatter = {
        DateFormatter.fixed("MM-dd HH:mm")
    }()

    var body: some View {
        let r = record
        let (result, color): (String, Color) = r.result == 1 ? (L("result_win_short"), Theme.affirm)
            : r.result == -1 ? (L("result_loss_short"), Theme.danger) : (L("result_draw_short"), Theme.textSecondary)
        let delta = r.ratingDelta >= 0 ? "+\(r.ratingDelta)" : "\(r.ratingDelta)"
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Text(result).font(.system(size: 20, weight: .bold)).foregroundStyle(color)
                Text(L(r.playerSide == Side.red ? "side_red_short" : "side_black_short"))
                    .font(.system(size: 14)).foregroundStyle(Theme.textPrimary)
                Text("Lv.\(r.difficulty)").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 8).padding(.vertical, 1)
                    .background(Capsule().fill(Theme.surfaceSunken))
                Spacer()
                Text(Self.date.string(from: r.startTime)).font(.system(size: 12)).foregroundStyle(Theme.textTertiary)
            }
            HStack {
                Text(L("replay_moves_summary", r.moves.count, r.endReason)).font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary).lineLimit(1)
                Spacer()
                Text(L("replay_rank_delta", L(UserRank.fromRating(r.playerRating).nameKey), delta))
                    .font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(16)
        .card(radius: 14)
        .contentShape(Rectangle())
    }
}
