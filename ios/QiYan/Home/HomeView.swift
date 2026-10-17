import SwiftUI

/// 首页 (port of MainMenuActivity): 主行动 + 当前对手 + 两张 AI 招牌卡 + 每日一题 +
/// 闯关/残局/题库/速拼/杀法 + 复盘 + 排位战绩.
struct HomeView: View {
    @EnvironmentObject private var router: Router
    @ObservedObject private var settings = AppSettings.shared
    @ObservedObject private var profile = UserProfile.shared
    @ObservedObject private var progress = ProgressStore.shared
    @State private var catalogCount = 1000
    @State private var appeared = false
    @State private var showAbout = false

    var body: some View {
        ZStack {
            HomeBackground()
            VStack(spacing: 0) {
                header
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        hero.entrance(appeared, 0)
                        opponentCard.entrance(appeared, 1)
                        featureRow.entrance(appeared, 2)
                        dailyCard.entrance(appeared, 3)
                        Text(L("home_journey")).font(Theme.serif(21)).foregroundStyle(Theme.textPrimary).padding(.top, 8)
                        campaignCard
                        VStack(spacing: 0) {
                            row("ic_flag", L("home_history_title"), historySub) { router.go(.history) }
                            row("ic_hint", L("home_puzzle_title"), L("home_puzzle_desc")) { router.go(.puzzles) }
                            row("ic_hint", L("home_catalog_title"), catalogSub) { router.go(.catalog) }
                            row("ic_flag", L("home_rush_title"), L("home_rush_sub", progress.puzzleRushHighScore)) { router.go(.rush) }
                            row("ic_star_gold", L("home_tactics_title"), tacticsSub, template: false) { router.go(.tactics) }
                        }
                        Text(L("home_tools")).font(.system(size: 14)).foregroundStyle(Theme.textSecondary).padding(.top, 4)
                        row("ic_history", L("menu_replay"), L("home_replay_desc")) { router.go(.replays) }
                        profileCard
                        footer
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 40)
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            profile.reload()
            Bgm.shared.resume()
            catalogCount = CatalogStore.all.count
            if !appeared {
                if settings.motionAllowed { withAnimation(.easeOut(duration: 0.42)) { appeared = true } } else { appeared = true }
            }
        }
        .alert(L("menu_about"), isPresented: $showAbout) {
            Button(L("dialog_ok")) {}
        } message: { Text(AboutText.full) }
    }


    // MARK: Header

    private var header: some View {
        HStack(spacing: 12) {
            Text(L("brand_seal_char")).font(.system(size: 18, weight: .bold)).foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(RoundedRectangle(cornerRadius: 6).fill(Theme.action))
            Text(L("app_name")).font(Theme.serif(24)).foregroundStyle(Theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 20)
        .frame(height: 56)
    }

    // MARK: Hero

    private var level: Int { settings.lastDifficulty }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 14) {
                    Text(L("home_eyebrow")).font(.system(size: 11, weight: .medium)).foregroundStyle(Theme.c("lounge_soft_gold"))
                        .tracking(2)
                    Text(L("home_headline")).font(.system(size: 30, weight: .regular, design: .serif))
                        .foregroundStyle(Theme.c("lounge_paper")).lineSpacing(6)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                ChessStillLife().frame(width: 120, height: 130)
            }
            Text(level <= 2 ? L("home_intro") : Names.level(level).desc)
                .font(.system(size: 13)).foregroundStyle(Theme.c("lounge_muted"))
                .padding(.top, 14)
            Button { startGame() } label: {
                Label(L(level == 1 ? "home_start_practice" : "home_start_again"), systemImage: "play.fill")
            }
            .buttonStyle(GoldButtonStyle(height: 54))
            .padding(.top, 18)
            .accessibilityIdentifier("btn_play")
        }
        .padding(22)
        .background(
            RoundedRectangle(cornerRadius: 24)
                .fill(LinearGradient(colors: [Theme.c("lounge_ink"), Theme.c("lounge_ink_deep")], startPoint: .top, endPoint: .bottom))
        )
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Theme.c("lounge_ink_deep"), lineWidth: 1))
    }

    private func startGame() {
        router.go(.game(GameConfig(difficulty: settings.lastDifficulty, playerSide: settings.lastSide,
                                   style: PlayStyle.fromId(settings.lastStyle))))
    }

    private var opponentCard: some View {
        Button { router.go(.newGame) } label: {
            HStack(spacing: 14) {
                OpponentAvatar(level: level, size: 58)
                VStack(alignment: .leading, spacing: 3) {
                    Text(Names.level(level).name).font(.system(size: 17, weight: .bold)).foregroundStyle(Theme.textPrimary)
                    Text(level <= 2 ? Names.level(level).tag : L(PlayStyle.fromId(settings.lastStyle).nameKey))
                        .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                    Text(L("home_change_opponent")).font(.system(size: 13)).foregroundStyle(Theme.c("lounge_link"))
                }
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(Names.level(level).name), \(L("home_change_opponent"))")
    }

    private var featureRow: some View {
        HStack(alignment: .top, spacing: 12) {
            featureCard("ic_live", L("live_entry"), L("home_live_desc")) { router.go(.live) }
            featureCard("ic_camera", L("menu_recognize"), L("home_recognize_desc")) { router.go(.recognize) }
        }
    }

    private func featureCard(_ icon: String, _ title: String, _ desc: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("home_feature_badge")).font(.system(size: 10, weight: .bold)).foregroundStyle(Theme.c("lounge_ink"))
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Capsule().fill(Theme.c("lounge_soft_gold")))
                Image(icon).renderingMode(.template).resizable().frame(width: 28, height: 28)
                    .foregroundStyle(Theme.c("lounge_soft_gold"))
                Text(title).font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.c("lounge_paper"))
                Text(desc).font(.system(size: 12)).foregroundStyle(Theme.c("lounge_muted"))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, minHeight: 150, alignment: .topLeading)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 18).fill(Theme.c("lounge_ink")))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.c("lounge_ink_deep"), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: Daily

    private var dailyCard: some View {
        let daily = progress.dailyPractice()
        return Button { router.go(.puzzle(.daily)) } label: {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("home_daily_title")).font(Theme.serif(22)).foregroundStyle(Theme.textPrimary)
                Text(L("home_daily_desc")).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                Text(L("home_daily_goal")).font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.top, 6)
                ProgressView(value: Double(min(daily.currentStreak, 7)), total: 7)
                    .tint(Theme.action)
                Text(daily.completedDays == 0 ? L("home_streak_start") : L("home_streak", daily.currentStreak, daily.completedDays))
                    .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                Text(L(daily.completedToday ? "home_daily_done" : "home_daily_ready"))
                    .font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.c("lounge_link")).padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(22)
            .background(RoundedRectangle(cornerRadius: 22).fill(Theme.c("lounge_daily")))
            .overlay(RoundedRectangle(cornerRadius: 22).stroke(Theme.borderSubtle, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: Campaign and rows

    private var campaignCard: some View {
        let passed = progress.campaignPassedLevels()
        let next = (1...DifficultySettings.maxLevel).first { $0 <= progress.campaignUnlocked && !passed.contains($0) } ?? DifficultySettings.maxLevel
        return Button { router.go(.campaign) } label: {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("home_campaign_title")).font(Theme.serif(20)).foregroundStyle(Theme.textPrimary)
                Text(L(CampaignStages.of(next).briefKey)).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                Text(L("home_campaign_stars", progress.campaignStarTotal(), DifficultySettings.maxLevel * CampaignStars.max,
                       passed.filter { $0 <= DifficultySettings.maxLevel }.count))
                    .font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.c("lounge_link"))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(22)
            .card()
        }
        .buttonStyle(.plain)
    }

    private var historySub: String {
        let stars = HistoryCampaign.battles.reduce(0) { $0 + progress.chronicleStars($1.id) }
        return L("home_history_sub", stars, HistoryCampaign.battles.count * CampaignStars.max)
    }

    private var catalogSub: String {
        let solved = progress.puzzleSolvedIds().filter { $0 >= PuzzleCatalog.firstId }.count
        return L("home_catalog_sub", solved, catalogCount)
    }

    private var tacticsSub: String {
        let solved = progress.puzzleSolvedIds()
        return L("home_tactics_sub", TacticsAcademy.topics.filter { solved.contains($0.puzzleId) }.count, TacticsAcademy.topics.count)
    }

    private func row(_ icon: String, _ title: String, _ sub: String, template: Bool = true,
                     _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Group {
                    if template {
                        Image(icon).renderingMode(.template).resizable().foregroundStyle(Theme.action)
                    } else {
                        Image(icon).resizable()
                    }
                }
                .frame(width: 26, height: 26)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.system(size: 17, weight: .bold)).foregroundStyle(Theme.textPrimary)
                    Text(sub).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Image("ic_back").renderingMode(.template).resizable().frame(width: 20, height: 20)
                    .rotationEffect(.degrees(180)).foregroundStyle(Theme.textTertiary)
            }
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Profile

    private var profileCard: some View {
        Button { router.go(.rankHall) } label: {
            VStack(alignment: .leading, spacing: 12) {
                Text(L("home_profile_title")).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                HStack {
                    Text(profile.nickname).font(Theme.serif(20)).foregroundStyle(Theme.textPrimary)
                    Spacer()
                    if profile.isInPlacement {
                        Text(L("rank_placement_name")).font(.system(size: 15)).foregroundStyle(Theme.textSecondary)
                    } else {
                        Text(L(profile.rank.nameKey)).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.c(profile.rank.colorKey))
                    }
                }
                HStack(alignment: .top, spacing: 12) {
                    Text(profile.isInPlacement ? "?" : String(profile.rank.id))
                        .font(.system(size: 18, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(RoundedRectangle(cornerRadius: 6).fill(Theme.action))
                    VStack(alignment: .leading, spacing: 6) {
                        Text(progressLine + "\n" + L("home_open_rank_hall"))
                            .font(.system(size: 13)).foregroundStyle(Theme.action)
                        ProgressView(value: meter).tint(Theme.action)
                    }
                }
                HStack {
                    Text(L("stat_total_games_format", profile.totalGames))
                    Spacer()
                    Text(L("stat_wins_format", profile.wins))
                    Spacer()
                    Text(L("stat_losses_format", profile.losses))
                    Spacer()
                    Text(L("stat_draws_format", profile.draws))
                }
                .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                if profile.totalGames > 0 {
                    GeometryReader { geo in
                        let total = CGFloat(max(profile.wins + profile.losses + profile.draws, 1))
                        HStack(spacing: 0) {
                            Rectangle().fill(Theme.affirm).frame(width: geo.size.width * CGFloat(profile.wins) / total)
                            Rectangle().fill(Theme.danger).frame(width: geo.size.width * CGFloat(profile.losses) / total)
                            Rectangle().fill(Theme.textTertiary).frame(width: geo.size.width * CGFloat(profile.draws) / total)
                        }
                        .clipShape(Capsule())
                    }
                    .frame(height: 6)
                }
            }
            .padding(.vertical, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(L("home_open_rank_hall"))
    }

    private var progressLine: String {
        if profile.isInPlacement {
            let played = profile.totalGames
            return L("rank_placement_progress", played, RankLadder.placementGames, RankLadder.placementGames - played)
        }
        let ladder = RankLadder.progress(profile.displayedRating)
        guard let next = ladder.next else { return L("rank_progress_top") }
        return L("rank_progress_next", L(next.nameKey), ladder.pointsToNext)
    }

    private var meter: Double {
        profile.isInPlacement ? Double(profile.totalGames) / Double(RankLadder.placementGames)
            : RankLadder.progress(profile.displayedRating).fraction
    }

    private var footer: some View {
        HStack {
            Button(L("menu_settings")) { router.go(.settings) }.frame(maxWidth: .infinity)
            Button(L("language_dialog_title")) {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .frame(maxWidth: .infinity)
            Button(L("menu_about")) { showAbout = true }.frame(maxWidth: .infinity)
        }
        .font(.system(size: 16, weight: .semibold))
        .foregroundStyle(Theme.textPrimary)
        .padding(.vertical, 8)
    }
}

private extension View {
    /// 首页入场: 依次淡入上浮
    func entrance(_ shown: Bool, _ index: Int) -> some View {
        opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 14)
            .animation(.easeOut(duration: 0.42).delay(Double(index) * 0.07), value: shown)
    }
}

/// 首页右上的静物: 三枚立体棋子 (马、卒、帥) 斜摆在淡格子上
struct ChessStillLife: View {
    var body: some View {
        Canvas { ctx, size in
            var grid = Path()
            for i in 0...4 {
                let x = size.width * 0.15 + CGFloat(i) * size.width * 0.2
                grid.move(to: CGPoint(x: x, y: size.height * 0.05)); grid.addLine(to: CGPoint(x: x, y: size.height * 0.95))
                let y = size.height * 0.05 + CGFloat(i) * size.height * 0.225
                grid.move(to: CGPoint(x: size.width * 0.15, y: y)); grid.addLine(to: CGPoint(x: size.width * 0.95, y: y))
            }
            ctx.stroke(grid, with: .color(Theme.c("lounge_soft_gold").opacity(0.18)), lineWidth: 0.8)
        }
        .overlay {
            ZStack {
                piece("卒", red: false, size: 44).offset(x: 24, y: -34)
                piece("馬", red: false, size: 54).offset(x: -10, y: 4)
                piece("帥", red: true, size: 60).offset(x: 30, y: 26)
            }
        }
        .accessibilityHidden(true)
    }

    private func piece(_ ch: String, red: Bool, size: CGFloat) -> some View {
        ZStack {
            Circle().fill(Color(red: 0.75, green: 0.62, blue: 0.42)).offset(y: size * 0.08)
            Circle().fill(RadialGradient(colors: [Color(red: 1, green: 0.97, blue: 0.88), Color(red: 0.93, green: 0.85, blue: 0.68)],
                                         center: UnitPoint(x: 0.35, y: 0.3), startRadius: 0, endRadius: size * 0.6))
            Circle().stroke(red ? Theme.c("piece_red") : Theme.c("piece_black"), lineWidth: size * 0.04).padding(size * 0.1)
            Text(ch).font(Font(PieceFont.font(size * 0.5))).foregroundStyle(red ? Theme.c("piece_red") : Theme.c("piece_black"))
        }
        .frame(width: size, height: size)
        .shadow(color: .black.opacity(0.35), radius: 4, y: 3)
    }
}
