import SwiftUI

/// 结算页: 匾额 (胜/负/和三色) + 星 + 说明卡 + 继续/再来一局/复盘/返回.
/// 登峰棋路换成闯关语义 (过关/下一关/重打), 不提积分.
struct ResultOverlay: View {
    @ObservedObject var vm: GameViewModel
    let stars: Int
    let bestStars: Int
    let onContinue: () -> Void
    let onAgain: () -> Void
    let onReview: () -> Void
    let onClose: () -> Void
    let onExit: () -> Void
    @State private var appeared = false

    private var s: GameViewModel.State { vm.state }
    private var won: Bool { s.gameResult == .win || (s.gameOverWinner != Side.none && s.gameOverWinner == s.playerSide) }
    private var lost: Bool { s.gameResult == .loss || (s.gameOverWinner != Side.none && s.gameOverWinner != s.playerSide) }
    private var campaign: Bool { s.campaignLevel > 0 }
    private var tacticName: String? { s.winningTactic.map { Names.tactic($0) } }

    var body: some View {
        ZStack {
            Color.black.opacity(0.45).ignoresSafeArea()
            VictoryRays(victory: won).opacity(appeared ? 1 : 0).allowsHitTesting(false)
            VStack(spacing: 16) {
                plaque
                    .scaleEffect(appeared ? 1 : 2.2)
                    .opacity(appeared ? 1 : 0)
                detailCard
                    .offset(y: appeared ? 0 : 40)
                    .opacity(appeared ? 1 : 0)
                buttons
            }
            .padding(.horizontal, 22)
            VStack {
                HStack {
                    Spacer()
                    Button(action: onClose) {
                        HStack(spacing: 4) {
                            Image("ic_close").renderingMode(.template).resizable().frame(width: 18, height: 18)
                            Text(L("result_watch_board")).font(.system(size: 14, weight: .semibold))
                        }
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Capsule().fill(Color.black.opacity(0.55)))
                        .overlay(Capsule().stroke(Color.white.opacity(0.7), lineWidth: 1))
                    }
                    .accessibilityLabel(L("result_close"))
                }
                Spacer()
            }
            .padding(.horizontal, 16).padding(.top, 8)
        }
        .onAppear {
            if AppSettings.shared.motionAllowed {
                withAnimation(.spring(response: 0.46, dampingFraction: 0.6)) { appeared = true }
            } else {
                appeared = true
            }
        }
    }

    // MARK: Plaque

    private var plaqueColors: (outer: [Color], ring: Color, inner: [Color], stroke: Color, text: Color) {
        if won {
            return ([hex(0xFFF8E1), hex(0xFFD54F), hex(0xFF8F00)], hex(0x3E0000), [hex(0x7F0000), hex(0xB71C1C), hex(0xD32F2F)],
                    hex(0xFFE082), hex(0xFFF8E1))
        }
        if lost {
            return ([hex(0xCFD8DC), hex(0x90A4AE), hex(0x546E7A)], hex(0x1A2228), [hex(0x263238), hex(0x37474F), hex(0x455A64)],
                    hex(0xB0BEC5), hex(0xECEFF1))
        }
        return ([hex(0xFFE082), hex(0xBCAAA4), hex(0x8D6E63)], hex(0x2D241E), [hex(0x3E2723), hex(0x4E342E), hex(0x5D4037)],
                hex(0xD7CCC8), hex(0xFFF3E0))
    }

    private func hex(_ v: UInt32) -> Color { Color(UIColor(argb: 0xFF00_0000 | v)) }

    private var banner: String {
        if campaign {
            if won { return tacticName.map { L("result_win_tactic_fmt", $0) } ?? L("campaign_win_title") }
            return tacticName.map { L("result_lose_tactic_fmt", $0) } ?? L("campaign_fail_title")
        }
        if won {
            if let t = tacticName { return L("result_win_tactic_fmt", t) }
            return s.gameOverWinner != Side.none ? L("result_win_checkmate") : L("result_win_big")
        }
        if lost { return tacticName.map { L("result_lose_tactic_fmt", $0) } ?? L("result_lose_banner") }
        return L("result_draw_banner")
    }

    private var reason: String {
        if campaign {
            if won, let t = tacticName { return L("result_reason_win_tactic_fmt", t) }
            return s.gameOverReason.isEmpty ? L(won ? "campaign_reason_clear" : "campaign_reason_fail") : s.gameOverReason
        }
        if won {
            if let t = tacticName { return L("result_reason_win_tactic_fmt", t) }
            return s.gameOverReason.isEmpty ? L("result_reason_win_default") : s.gameOverReason
        }
        if lost {
            if let t = tacticName { return L("result_reason_lose_tactic_fmt", t) }
            return s.gameOverReason.isEmpty ? L("result_reason_lose_default") : s.gameOverReason
        }
        return s.gameOverReason.isEmpty ? L("result_reason_draw_default") : s.gameOverReason
    }

    private var plaque: some View {
        let c = plaqueColors
        return VStack(spacing: 10) {
            if won {
                HStack(spacing: 10) {
                    ForEach(0..<3, id: \.self) { i in
                        Image("ic_star_gold").resizable().frame(width: i == 1 ? 34 : 28, height: i == 1 ? 34 : 28)
                            .opacity(litStars.contains(i) ? 1 : 0.28)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(campaign ? L("campaign_stars_cd", stars) : "")
            }
            Text(banner)
                .font(.system(size: 34, weight: .heavy, design: .serif))
                .foregroundStyle(c.text)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.6)
                .lineLimit(2)
            Text(reason)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(c.text)
                .padding(.horizontal, 14).padding(.vertical, 4)
                .background(Capsule().fill(Color.white.opacity(0.18)))
        }
        .padding(.vertical, 22).padding(.horizontal, 18)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 13)
                .fill(LinearGradient(colors: c.inner, startPoint: .top, endPoint: .bottom))
                .overlay(RoundedRectangle(cornerRadius: 13).stroke(c.stroke, lineWidth: 1.5))
                .padding(5)
                .background(RoundedRectangle(cornerRadius: 15).fill(c.ring).padding(3))
                .background(RoundedRectangle(cornerRadius: 18).fill(LinearGradient(colors: c.outer, startPoint: .top, endPoint: .bottom)))
        )
        .shadow(color: .black.opacity(0.35), radius: 12, y: 6)
    }

    private var litStars: Set<Int> {
        switch min(max(stars, 0), 3) {
        case 1: return [1]
        case 2: return [0, 1]
        case 3: return [0, 1, 2]
        default: return []
        }
    }

    // MARK: Detail card

    private var category: String {
        if campaign {
            let stage = CampaignStages.of(s.campaignLevel)
            return L("campaign_stage_result", s.campaignLevel, L(stage.nameKey))
        }
        return s.isPractice ? L("game_practice_label") : L("game_mode_ranked")
    }

    private var detail: String {
        if campaign {
            let hasNext = won && s.campaignLevel < DifficultySettings.maxLevel
            let body = hasNext ? L("campaign_body_unlocked") : (won ? L("campaign_body_all_clear") : L("campaign_body_retry"))
            guard won else { return body }
            let line = L("campaign_stars_line", stars, L(s.hintsUsed == 0 ? "campaign_star_hint_kept" : "campaign_star_hint_used"),
                         L(s.undosUsed == 0 ? "campaign_star_undo_kept" : "campaign_star_undo_used"))
            let record = bestStars > stars ? L("campaign_stars_record", bestStars) : ""
            return [body, line, record].filter { !$0.isEmpty }.joined(separator: "\n")
        }
        let delta = (s.ratingDelta >= 0 ? "+" : "") + String(s.ratingDelta)
        var line: String
        if s.continuesRatedGame { line = L("result_continuation_detail") }
        else if s.isPractice { line = L("result_practice_detail") }
        else if let played = s.placementPlayed {
            line = L("result_placement_progress", played, RankLadder.placementGames, RankLadder.placementGames - played)
        } else {
            line = L("game_over_rating_detail", delta, s.playerRank)
        }
        if !s.promotedRank.isEmpty {
            let head = s.placementComplete ? L("result_placement_done", s.promotedRank) : L("rank_promoted", s.promotedRank)
            line = head + "\n" + line
        }
        return line
    }

    private var detailCard: some View {
        VStack(spacing: 8) {
            Text(category).font(.system(size: 13)).foregroundStyle(Theme.textTertiary)
            Text(detail).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
            Text(L("result_replay_hint")).font(.system(size: 13)).foregroundStyle(Theme.textTertiary)
                .multilineTextAlignment(.center)
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 18).fill(Color(UIColor(argb: 0xEEFCF9F3))))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(UIColor(argb: 0xFFD7CCC8)), lineWidth: 1.5))
    }

    // MARK: Buttons

    private var buttons: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Button(action: onContinue) {
                    Label(L(s.endedByResign ? "result_resume" : "result_undo"), image: "ic_undo")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(OutlineButtonStyle(height: 50))
                .disabled(!(s.endedByResign || s.moveCount > 0))
                .frame(maxWidth: .infinity)
                Button(action: onAgain) {
                    Label(L(campaign ? (won && s.campaignLevel < DifficultySettings.maxLevel ? "campaign_next_level" : "campaign_retry")
                            : "game_play_again"), image: "ic_refresh")
                }
                .buttonStyle(GoldButtonStyle(height: 50))
                .frame(maxWidth: .infinity)
            }
            HStack(spacing: 14) {
                if !campaign {
                    Button(action: onReview) {
                        Label(L("result_review"), image: "ic_history")
                    }
                    .buttonStyle(OutlineButtonStyle(color: .white, height: 44))
                    .colorScheme(.dark)
                }
                Button(action: onExit) {
                    Text(L(campaign ? "campaign_back_list" : "action_back"))
                        .font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 16).frame(minHeight: 44)
                }
            }
        }
    }
}

/// 胜利放射光 (负局是冷色的淡光)
struct VictoryRays: View {
    let victory: Bool
    @State private var angle: Double = 0

    var body: some View {
        GeometryReader { geo in
            let c = CGPoint(x: geo.size.width / 2, y: geo.size.height * 0.36)
            Canvas { ctx, size in
                let rays = 14
                let r = max(size.width, size.height)
                for i in 0..<rays {
                    let a0 = (Double(i) / Double(rays)) * 2 * .pi
                    let a1 = a0 + .pi / Double(rays) * 0.7
                    var p = Path()
                    p.move(to: c)
                    p.addLine(to: CGPoint(x: c.x + cos(a0) * r, y: c.y + sin(a0) * r))
                    p.addLine(to: CGPoint(x: c.x + cos(a1) * r, y: c.y + sin(a1) * r))
                    p.closeSubpath()
                    ctx.fill(p, with: .color(victory ? Color(red: 1, green: 0.84, blue: 0.4).opacity(0.18) : Color.white.opacity(0.05)))
                }
            }
            .rotationEffect(.radians(angle), anchor: UnitPoint(x: 0.5, y: 0.36))
        }
        .ignoresSafeArea()
        .onAppear {
            guard AppSettings.shared.motionAllowed else { return }
            withAnimation(.linear(duration: 40).repeatForever(autoreverses: false)) { angle = 2 * .pi }
        }
    }
}
