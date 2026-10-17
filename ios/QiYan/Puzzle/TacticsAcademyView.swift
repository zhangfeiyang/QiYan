import SwiftUI

/// 杀法宝典: 11 式经典绝杀, 口诀 + 大师原声 + 破局实战, 解开对应残局即点亮"已精通"
struct TacticsAcademyView: View {
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = ProgressStore.shared

    var body: some View {
        let solved = store.puzzleSolvedIds()
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("home_tactics_title"), onBack: { dismiss() })
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(L("tactic_academy_banner_count", TacticsAcademy.topics.count))
                                .font(.system(size: 22, weight: .bold, design: .serif)).foregroundStyle(Theme.textPrimary)
                            Text(L("tactic_academy_sub")).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                        }
                        .padding(.horizontal, 4).padding(.top, 8).padding(.bottom, 4)
                        ForEach(TacticsAcademy.topics) { t in
                            TopicCard(topic: t, mastered: solved.contains(t.puzzleId)) {
                                router.go(.puzzle(.puzzle(t.puzzleId)))
                            }
                        }
                    }
                    .padding(.horizontal, 16).padding(.bottom, 24)
                }
            }
        }
    }
}

private struct TopicCard: View {
    let topic: TacticTopic
    let mastered: Bool
    let challenge: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Text(Notation.latinPieces ? topic.keyPiece.latin : topic.keyPiece.hanzi)
                    .font(Font(PieceFont.font(22))).foregroundStyle(Theme.c("piece_red"))
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Theme.c("piece_face")))
                    .overlay(Circle().stroke(Theme.c("piece_red"), lineWidth: 1.5).padding(4))
                    .overlay(Circle().stroke(Theme.borderStrong, lineWidth: 1))
                Text(Names.tactic(topic.name)).font(.system(size: 20, weight: .semibold)).foregroundStyle(Theme.textPrimary)
                Spacer()
                Text(L(mastered ? "tactic_mastered" : "tactic_learning")).font(.system(size: 12, weight: .bold))
                    .foregroundStyle(mastered ? Theme.action : Theme.textSecondary)
                    .padding(.horizontal, 12).padding(.vertical, 5)
                    .background(Capsule().fill(mastered ? Theme.c("action_muted") : Theme.surfaceSunken))
                    .overlay(Capsule().stroke(mastered ? Theme.action : Color.clear, lineWidth: 1))
            }
            Text(L(topic.rhymeKey)).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.action)
            Text(L(topic.descriptionKey)).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 12) {
                Spacer()
                Button { Feedback.shared.masterVoice(topic.voice) } label: {
                    Label(L("tactic_voice"), systemImage: "play.fill").font(.system(size: 14, weight: .bold))
                }
                .buttonStyle(OutlineButtonStyle(height: 38))
                Button(action: challenge) {
                    HStack(spacing: 6) {
                        Text(L("tactic_challenge"))
                        Image("ic_last").renderingMode(.template).resizable().frame(width: 14, height: 14)
                    }
                    .font(.system(size: 14, weight: .bold))
                    .padding(.horizontal, 18)
                }
                .buttonStyle(GoldButtonStyle(height: 44))
                .fixedSize()
            }
        }
        .padding(18)
        .card(radius: 18)
    }
}
