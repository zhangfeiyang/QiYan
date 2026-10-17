import SwiftUI

/// How a game was started (free play, a campaign stage, or a recognized position).
struct GameConfig: Hashable {
    var difficulty: Int
    var playerSide: Int
    var style: PlayStyle = .balanced
    var campaignLevel = 0
    var startFen: String? = nil
}

/// 对局页 (port of GameActivity without the cloud AI assistant).
struct GameView: View {
    let config: GameConfig
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var router: Router
    @StateObject private var vm = GameViewModel()
    @StateObject private var toaster = Toaster()
    @StateObject private var voice = VoiceInput()
    @ObservedObject private var settings = AppSettings.shared
    @State private var started = false
    @State private var coachSelected: String?
    @State private var showResult = false
    @State private var resultDismissed = false
    @State private var showPicker = false
    @State private var confirmResign = false
    @State private var confirmUndo = false
    @State private var confirmSwap = false
    @State private var lastOpening: String?
    @State private var resultStars = 0
    @State private var resultBestStars = 0

    private var state: GameViewModel.State { vm.state }
    private var myTurn: Bool { state.sideToMove == state.playerSide && !state.isGameOver }

    var body: some View {
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("title_game"),
                       subtitle: state.openingName.map { L("opening_chip", Names.opening($0)) },
                       onBack: { dismiss() }) {
                    Button { startVoice() } label: {
                        Image("ic_mic").renderingMode(.template).resizable().frame(width: 24, height: 24)
                            .foregroundStyle(voice.listening ? Theme.action : Theme.textPrimary)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L("action_voice"))
                }
                opponentRow
                if showCoach {
                    Text(coachText)
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16).padding(.vertical, 11)
                        .background(RoundedRectangle(cornerRadius: 22).fill(Theme.surfaceRaised.opacity(0.92)))
                        .padding(.horizontal, 12).padding(.top, 4)
                }
                if state.difficulty >= 3 && settings.showWinRate {
                    WinRateBar(redWinRate: vm.redWinRate)
                        .frame(height: 28)
                        .padding(.horizontal, 16).padding(.top, 8)
                }
                ZStack {
                    BoardView(handle: vm.board, flipped: boardFlipped, inputEnabled: !vm.enginePreparing,
                              onMove: { move in
                                  vm.playerMove(move)
                              },
                              onSelect: { sq, moves in
                                  guard state.difficulty <= 2 else { return }
                                  let piece = Piece.fromCode(vm.position.pieceAt(sq))
                                  coachSelected = moves.isEmpty ? L("game_coach_blocked")
                                      : L("game_coach_selected", Names.pieceRule(piece))
                              })
                    if vm.enginePreparing {
                        Theme.c("loading_scrim")
                        VStack(spacing: 10) {
                            ProgressView()
                            Text(L("loading_engine")).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                        }
                    }
                }
                .frame(maxHeight: .infinity)
                playerRow
                toolbar
            }
            if showResult {
                ResultOverlay(vm: vm, stars: resultStars, bestStars: resultBestStars,
                              onContinue: continueGame, onAgain: playAgain, onReview: openReview,
                              onClose: { resultDismissed = true; showResult = false }, onExit: { dismiss() })
                    .transition(.opacity)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .toast(toaster)
        .sheet(isPresented: $showPicker) {
            DifficultySheet(level: state.difficulty, style: state.style) { level, style in
                vm.setDifficulty(level)
                vm.setStyle(style)
                persistSetup()
            }
            .presentationDetents([.large])
        }
        .alert(L("dialog_resign_title"), isPresented: $confirmResign) {
            Button(L("dialog_yes"), role: .destructive) { vm.resign() }
            Button(L("dialog_no"), role: .cancel) {}
        } message: { Text(L("dialog_resign_message")) }
        .alert(L("dialog_undo_title"), isPresented: $confirmUndo) {
            Button(L("dialog_yes")) { if vm.undo() { Feedback.shared.undo() } else { toaster.show(L("game_undo_failed")) } }
            Button(L("dialog_no"), role: .cancel) {}
        } message: { Text(L("game_undo_no_penalty")) }
        .alert(L("game_swap_rated_title"), isPresented: $confirmSwap) {
            Button(L("game_swap_rated_confirm")) { swapSides() }
            Button(L("dialog_cancel"), role: .cancel) {}
        } message: { Text(L("game_swap_rated_message")) }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            Bgm.shared.resume()
            guard !started else { return }
            started = true
            Feedback.shared.preload()
            vm.onEvent = { [toaster] e in GameView.handle(e, toaster: toaster) }
            vm.startNewGame(difficulty: config.difficulty, playerSide: config.playerSide, startFen: config.startFen,
                            style: config.style, campaignLevel: config.campaignLevel)
            Feedback.shared.gameStart()
            persistSetup()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            voice.cancel()
        }
        .onChange(of: state.isGameOver) { _, over in
            if over { presentResult() } else { showResult = false; resultDismissed = false }
        }
        .onChange(of: state.openingName) { _, name in
            guard let name, name != lastOpening else { return }
            lastOpening = name
            vm.board.view?.showTag(vm.moveHistory.last?.to ?? 4, Names.opening(name), color: .token("action"))
        }
        .onChange(of: state.moveCount) { _, _ in coachSelected = nil }
        .onChange(of: vm.hint?.move) { _, m in
            if m == nil { vm.board.view?.setHint(nil) }
        }
        .onChange(of: voice.recognized) { _, text in
            if let text { onVoice(text) }
        }
    }

    private var boardFlipped: Bool { (state.playerSide == Side.black) != settings.boardFlipped }

    private var showCoach: Bool { (state.campaignLevel > 0 || state.difficulty <= 2) && !state.isGameOver }

    private var coachText: String {
        if let s = coachSelected { return s }
        if state.campaignLevel > 0 && !(state.isCheck && myTurn) { return L(CampaignStages.of(state.campaignLevel).briefKey) }
        if state.isCheck && myTurn { return L("game_coach_check") }
        if state.isThinking && !myTurn { return L("game_coach_thinking") }
        if state.moveCount == 0 && state.isPractice { return L("game_coach_practice") }
        if state.moveCount <= 2 { return L("game_coach_start") }
        return L("game_coach_opening")
    }

    // MARK: Rows

    private func dot(_ red: Bool) -> some View {
        Circle().fill(red ? Theme.c("side_red") : Theme.c("side_black")).frame(width: 14, height: 14)
    }

    private var opponentRow: some View {
        HStack(spacing: 8) {
            dot(state.playerSide != Side.red).opacity(myTurn ? 0.45 : 1)
            Text(L(state.playerSide == Side.red ? "game_opponent_black" : "game_opponent_red"))
                .font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.textPrimary)
                .opacity(myTurn ? 0.45 : 1).lineLimit(1).minimumScaleFactor(0.75).layoutPriority(-1)
            Button { if state.campaignLevel == 0 { showPicker = true } } label: {
                if state.campaignLevel > 0 {
                    Pill(text: L("campaign_level_chip", L(CampaignStages.of(state.campaignLevel).nameKey)), color: Theme.action,
                         fill: Theme.c("action_muted"), stroke: Theme.action)
                } else {
                    Pill(text: Names.level(state.difficulty).name, color: Theme.action, fill: Theme.c("action_muted"),
                         stroke: Theme.action, trailingChevron: true)
                }
            }
            .buttonStyle(.plain)
            Button { if state.campaignLevel == 0 { showPicker = true } } label: { Pill(text: L(state.style.nameKey)) }
                .buttonStyle(.plain)
            Spacer(minLength: 0)
            thinkChip
        }
        .padding(.horizontal, 16)
        .frame(height: 50)
    }

    @ViewBuilder private var thinkChip: some View {
        if state.isThinking {
            Pill(text: L("status_thinking"), color: Theme.warning)
        } else if let h = vm.hint, !state.isGameOver {
            Pill(text: L("game_hint_chip", h.notation), color: Theme.affirm).fixedSize()
        } else {
            Pill(text: " ").hidden()
        }
    }

    private var playerRow: some View {
        HStack(spacing: 10) {
            dot(state.playerSide == Side.red).opacity(myTurn ? 1 : 0.45)
            Text(L(state.playerSide == Side.red ? "game_player_red" : "game_player_black"))
                .font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.textPrimary)
                .opacity(myTurn ? 1 : 0.45).lineLimit(1)
            if let o = state.openingName {
                Text(Names.opening(o)).font(.system(size: 11, weight: .semibold)).foregroundStyle(Theme.action)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Capsule().fill(Theme.c("action_muted")))
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            Text(state.isPractice && state.campaignLevel == 0 ? L("game_practice_label") : state.playerRank)
                .font(.system(size: 13)).foregroundStyle(Theme.textSecondary).lineLimit(1)
                .opacity(myTurn ? 1 : 0.45)
            Text(L("game_move_count", state.moveCount / 2 + 1))
                .font(.system(size: 13)).foregroundStyle(Theme.textTertiary)
                .opacity(myTurn ? 1 : 0.45)
        }
        .padding(.horizontal, 16)
        .frame(height: 48)
    }

    private func toolButton(_ icon: String, _ title: String, tint: Color = Theme.textPrimary, enabled: Bool = true,
                            _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(icon).renderingMode(.template).resizable().frame(width: 26, height: 26)
                Text(title).font(.system(size: 13, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
            }
            .foregroundStyle(enabled ? tint : Theme.textTertiary.opacity(0.6))
            .frame(maxWidth: .infinity, minHeight: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private var toolbar: some View {
        let ready = !vm.enginePreparing
        return HStack(spacing: 0) {
            toolButton("ic_hint", L("action_hint"), enabled: myTurn && !state.isThinking && ready) { vm.requestHint() }
            toolButton("ic_undo", L("action_undo"), enabled: !state.isThinking && state.moveCount > 0) {
                if state.isPractice {
                    let ok = vm.undo()
                    if ok { Feedback.shared.undo() }
                    toaster.show(L(ok ? "game_practice_undone" : "game_undo_failed"))
                } else {
                    confirmUndo = true
                }
            }
            toolButton("ic_flip", L("action_flip"), enabled: !state.isGameOver && ready) {
                if state.campaignLevel > 0 { toaster.show(L("game_swap_campaign_locked")) }
                else if vm.swapMakesPractice { confirmSwap = true }
                else { swapSides() }
            }
            toolButton("ic_flag", L("action_resign"), tint: Theme.danger, enabled: !state.isGameOver && ready) {
                confirmResign = true
            }
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }

    // MARK: Actions

    private func swapSides() {
        guard vm.swapSides() else { return }
        persistSetup()
        toaster.show(L(state.playerSide == Side.black ? "game_switched_to_black" : "game_switched_to_red"))
    }

    private func persistSetup() {
        guard config.campaignLevel == 0, state.campaignLevel == 0 else { return }
        settings.lastDifficulty = state.difficulty
        settings.lastSide = state.playerSide
        settings.lastStyle = state.style.id
    }

    private static func handle(_ e: GameViewModel.Event, toaster: Toaster) {
        switch e {
        case .toast(let s): toaster.show(s)
        case .check: Feedback.shared.check()
        case .tacticCheck(let t): Feedback.shared.tactic(t)
        case .move(let byPlayer): Feedback.shared.move(byPlayer: byPlayer)
        case .capture: Feedback.shared.capture()
        case .checkmate, .threats, .gameOver: break
        }
    }

    private func presentResult() {
        guard !resultDismissed else { return }
        let won = state.gameResult == .win
        if state.campaignLevel > 0 {
            let earned = CampaignStars.award(won: won, hintsUsed: state.hintsUsed, undosUsed: state.undosUsed)
            resultStars = earned
            if won {
                ProgressStore.shared.markCampaignPassed(state.campaignLevel)
                resultBestStars = ProgressStore.shared.recordCampaignStars(state.campaignLevel, earned)
                if state.winningTactic != nil { Feedback.shared.victory(tactic: state.winningTactic) } else { Feedback.shared.campaignCleared() }
            } else {
                Feedback.shared.defeat()
            }
        } else {
            resultStars = won ? CampaignStars.max : 0
            switch state.gameResult {
            case .win?: Feedback.shared.victory(tactic: state.winningTactic)
            case .loss?: Feedback.shared.defeat()
            default: Feedback.shared.draw()
            }
        }
        withAnimation(.easeOut(duration: 0.22)) { showResult = true }
    }

    private func continueGame() {
        let resign = state.endedByResign
        if vm.continueAfterGameOver() {
            showResult = false
            resultDismissed = false
            if !resign { Feedback.shared.undo() }
        } else {
            toaster.show(L("game_undo_failed"))
        }
    }

    private func playAgain() {
        showResult = false
        resultDismissed = false
        let won = state.gameResult == .win
        var level = state.campaignLevel
        if level > 0 && won && level < DifficultySettings.maxLevel { level += 1 }
        vm.startNewGame(difficulty: level > 0 ? level : state.difficulty, playerSide: state.playerSide,
                        startFen: config.startFen, style: state.style, campaignLevel: level)
        Feedback.shared.gameStart()
    }

    private func openReview() {
        guard let id = vm.savedGameId else { toaster.show(L("result_review_unavailable")); return }
        router.path.append(Route.replay(id))
    }

    private func startVoice() {
        voice.start { message in toaster.show(message) }
    }

    private func onVoice(_ text: String) {
        let moves = VoiceCommands.parse(vm.position, text)
        if moves.isEmpty { toaster.show(L("voice_no_match", text)); return }
        if moves.count > 1 { toaster.show(L("voice_ambiguous")); return }
        let notation = Notation.format(moves[0], vm.position)
        if vm.playerMove(moves[0]) {
            toaster.show(L("voice_played", notation))
        } else if state.isGameOver {
            toaster.show(L("voice_game_over"))
        } else if state.isThinking {
            toaster.show(L("voice_wait_engine"))
        } else if state.sideToMove != state.playerSide {
            toaster.show(L("voice_not_your_turn"))
        } else {
            toaster.show(L("game_illegal_move"))
        }
    }
}

/// 实时胜率条: 红在左, 黑在右, 颜色浓度随优势增强
struct WinRateBar: View {
    var redWinRate: Double

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let red = min(max(redWinRate / 100, 0), 1)
            let redW = w * red
            ZStack(alignment: .leading) {
                Rectangle().fill(Theme.surfaceSunken)
                Rectangle()
                    .fill(LinearGradient(colors: [blend("winrate_red_soft", "winrate_red_strong", abs(red - 0.5) * 2),
                                                  Theme.c("winrate_red_strong")], startPoint: .leading, endPoint: .trailing))
                    .frame(width: redW)
                Rectangle()
                    .fill(LinearGradient(colors: [Theme.c("winrate_black_strong"),
                                                  blend("winrate_black_soft", "winrate_black_strong", abs(0.5 - red) * 2)],
                                         startPoint: .leading, endPoint: .trailing))
                    .frame(width: w - redW)
                    .offset(x: redW)
                Rectangle().fill(Theme.textPrimary).frame(width: 1.5).offset(x: redW - 0.75)
                Text(String(format: "%.1f%%", redWinRate)).font(Theme.serif(min(h * 0.55, 14)))
                    .foregroundStyle(Theme.c("winrate_text_on_red"))
                    .position(x: redW / 2, y: h / 2)
                Text(String(format: "%.1f%%", 100 - redWinRate)).font(Theme.serif(min(h * 0.55, 14)))
                    .foregroundStyle(Theme.c("winrate_text_on_black"))
                    .position(x: redW + (w - redW) / 2, y: h / 2)
            }
            .overlay(Rectangle().stroke(Theme.borderSubtle, lineWidth: 1))
            .animation(.easeOut(duration: 0.3), value: redWinRate)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(format: "%.1f%% / %.1f%%", redWinRate, 100 - redWinRate))
    }

    private func blend(_ a: String, _ b: String, _ t: Double) -> Color {
        let ca = UIColor.token(a), cb = UIColor.token(b)
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        ca.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        cb.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        let k = CGFloat(min(max(t, 0), 1))
        return Color(red: r1 + (r2 - r1) * k, green: g1 + (g2 - g1) * k, blue: b1 + (b2 - b1) * k, opacity: a1 + (a2 - a1) * k)
    }
}
