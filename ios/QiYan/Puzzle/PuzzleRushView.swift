import SwiftUI

/// 残局速拼: 3 分钟, 3 颗心, 连击, 得分越高题越难; 结束弹结算卡
struct PuzzleRushView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = PuzzleRushViewModel()
    @State private var confirmLeave = false
    @State private var flash: Color?
    @State private var popup: String?
    @State private var popupScale: CGFloat = 1
    @State private var share: ShareItem?

    var body: some View {
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("puzzle_rush_title"), close: true, onBack: leave)
                hud
                HStack {
                    if let p = vm.puzzle {
                        Text(L("puzzle_rush_prompt_short", L(p.nameKey))).font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary).lineLimit(1)
                    }
                    Spacer()
                    Text(L("puzzle_rush_best_badge", vm.highScore)).font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Theme.warning)
                }
                .padding(.horizontal, 16).frame(height: 34)
                ZStack {
                    BoardView(handle: vm.board, inputEnabled: vm.started && !vm.finished, onMove: { vm.playerMove($0) })
                        .padding(8)
                        .opacity(vm.started ? 1 : 0.35)
                    if let flash { flash.allowsHitTesting(false) }
                    if let popup {
                        Text(popup).font(.system(size: 32, weight: .heavy)).foregroundStyle(Theme.affirm)
                            .scaleEffect(popupScale)
                            .shadow(color: .white, radius: 6)
                            .allowsHitTesting(false)
                    }
                    if !vm.started { intro }
                }
                .frame(maxHeight: .infinity)
                Button(L("puzzle_rush_pass")) { vm.skip() }
                    .buttonStyle(OutlineButtonStyle(height: 46))
                    .disabled(!vm.started || vm.finished)
                    .opacity(!vm.started || vm.finished ? 0.5 : 1)
                    .padding(.vertical, 12)
            }
            if vm.finished { resultCard.transition(.opacity) }
        }
        .onAppear { vm.onEvent = handle }
        .onDisappear { vm.stopAll() }
        .alert(L("puzzle_rush_title"), isPresented: $confirmLeave) {
            Button(L("puzzle_rush_leave_yes"), role: .destructive) { vm.stopAll(); dismiss() }
            Button(L("dialog_cancel"), role: .cancel) {}
        } message: { Text(L("puzzle_rush_leave")) }
        .sheet(item: $share) { ShareSheet(items: [$0.text]) }
    }

    private func leave() {
        if vm.finished || !vm.started { vm.stopAll(); dismiss() } else { confirmLeave = true }
    }

    private var hud: some View {
        HStack {
            Text(L("puzzle_rush_score_line", vm.score)).font(.system(size: 22, weight: .bold, design: .serif))
                .foregroundStyle(Theme.action)
            if vm.combo >= 2 {
                Text(L("puzzle_rush_combo", vm.combo)).font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.warning)
            }
            Spacer()
            Text(String(format: "%02d:%02d", vm.secondsLeft / 60, vm.secondsLeft % 60))
                .font(.system(size: 22, weight: .bold).monospacedDigit())
                .foregroundStyle(vm.secondsLeft <= 30 ? Color(hex: 0xFFE53935) : Theme.textPrimary)
                .padding(.horizontal, 20).padding(.vertical, 8)
                .background(Capsule().fill(Theme.surfaceRaised))
            Spacer()
            Text((1...3).map { $0 <= vm.lives ? "❤️" : "🖤" }.joined()).font(.system(size: 20))
        }
        .padding(.horizontal, 16).frame(height: 64)
        .background(Theme.surfaceRaised.opacity(0.7))
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L("puzzle_rush_intro_title")).font(.system(size: 24, design: .serif)).foregroundStyle(Theme.textPrimary)
            Text(L("puzzle_rush_intro_rules")).font(.system(size: 15)).foregroundStyle(Theme.textSecondary).lineSpacing(6)
            Text(vm.highScore > 0 ? L("puzzle_rush_intro_best", vm.highScore) : L("puzzle_rush_intro_first"))
                .font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.warning)
            Button { vm.start() } label: { Label(L("puzzle_rush_start"), image: "ic_play") }
                .buttonStyle(GoldButtonStyle(height: 60))
        }
        .padding(28)
        .background(RoundedRectangle(cornerRadius: 18).fill(Theme.surfaceRaised))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.borderSubtle, lineWidth: 1))
        .shadow(color: .black.opacity(0.1), radius: 10, y: 4)
        .padding(.horizontal, 28)
    }

    private var resultCard: some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(spacing: 10) {
                Text(L("puzzle_rush_result_title")).font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.textPrimary)
                Text(vm.finishReason).font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text("\(vm.score)").font(.system(size: 52, weight: .heavy, design: .serif)).foregroundStyle(Theme.action)
                    Text(L("puzzle_rush_score_unit")).font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.textPrimary)
                }
                Text(L("puzzle_rush_honor_line", vm.honorTitle)).font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.warning)
                Text(vm.isNewRecord ? L("puzzle_rush_record_line", vm.score) : L("puzzle_rush_previous_best", vm.highScore))
                    .font(.system(size: 13)).foregroundStyle(vm.isNewRecord ? Theme.action : Theme.textSecondary)
                Button(L("puzzle_rush_play_again")) { vm.start() }.buttonStyle(GoldButtonStyle()).padding(.top, 8)
                HStack(spacing: 10) {
                    Button(L("puzzle_rush_share")) {
                        share = ShareItem(text: L("puzzle_rush_share_body", L("puzzle_rush_report_title"), vm.score, vm.maxCombo,
                                                  L("puzzle_rush_honor_line", vm.honorTitle)))
                    }
                    .buttonStyle(OutlineButtonStyle(fullWidth: true))
                    Button(L("action_back")) { dismiss() }.buttonStyle(OutlineButtonStyle(fullWidth: true))
                }
            }
            .padding(24)
            .background(RoundedRectangle(cornerRadius: 20).fill(Theme.surfaceRaised))
            .padding(.horizontal, 32)
        }
    }

    private func handle(_ e: PuzzleRushViewModel.Event) {
        switch e {
        case .solved(let combo, let tactic):
            if let t = tactic {
                Feedback.shared.victory(tactic: t)
                popup = L("puzzle_rush_mate_line", Names.tactic(t)) + (combo >= 2 ? " " + L("puzzle_rush_combo", combo) : "")
            } else {
                Feedback.shared.checkmate()
                popup = combo >= 2 ? L("puzzle_rush_combo", combo) : L("puzzle_rush_solved_flash")
            }
            flash = Color(hex: 0x444CAF50)
            popupScale = 0.6
            withAnimation(.spring(response: 0.3, dampingFraction: 0.5)) { popupScale = 1 }
        case .strike:
            Feedback.shared.defeat()
            flash = Color(hex: 0x55E53935)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
            withAnimation(.easeOut(duration: 0.2)) { flash = nil; popup = nil }
        }
    }
}

@MainActor
final class PuzzleRushViewModel: ObservableObject {
    enum Event {
        case solved(combo: Int, tactic: String?)
        case strike
    }
    static let duration = 180

    @Published private(set) var puzzle: Puzzle?
    @Published private(set) var score = 0
    @Published private(set) var combo = 0
    @Published private(set) var maxCombo = 0
    @Published private(set) var lives = 3
    @Published private(set) var secondsLeft = duration
    @Published private(set) var started = false
    @Published private(set) var finished = false
    @Published private(set) var finishReason = ""
    @Published private(set) var isNewRecord = false
    @Published private(set) var highScore = ProgressStore.shared.puzzleRushHighScore
    @Published private(set) var honorTitle = ""
    @Published private(set) var thinking = false
    var onEvent: ((Event) -> Void)?

    let board = BoardHandle()
    private let position = Position()
    private var history: [Move] = []
    private var initialFen = ""
    private var playerMoves = 0
    private var used = Set<Int>()
    private var timer: Timer?
    private var deadline = Date()
    /// 换题/跳过即作废还在路上的引擎应手
    private var generation = 0

    init() {
        PikafishEngine.shared.load { _ in }
        let p = Position()
        p.setupStartPosition()
        board.show(p, lastMove: nil)
    }

    func start() {
        timer?.invalidate()
        used = []
        score = 0; combo = 0; maxCombo = 0; lives = 3
        secondsLeft = Self.duration
        finished = false; isNewRecord = false
        highScore = ProgressStore.shared.puzzleRushHighScore
        started = true
        loadNext()
        deadline = Date().addingTimeInterval(Double(Self.duration))
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func tick() {
        guard started, !finished else { return }
        secondsLeft = max(Int(ceil(deadline.timeIntervalSinceNow)), 0)
        if secondsLeft == 0 { finish(L("puzzle_rush_time_up")) }
    }

    func stopAll() {
        timer?.invalidate()
        timer = nil
        generation += 1
        PikafishEngine.shared.stop()
    }

    func skip() {
        guard started, !finished else { return }
        strike()
    }

    func playerMove(_ m: Move) {
        guard let p = puzzle, started, !finished, !thinking, position.sideToMove == Side.red, position.isLegalMove(m) else { return }
        let capture = position.pieceAt(m.to) != 0
        position.doMove(m)
        history.append(m)
        playerMoves += 1
        if capture { Feedback.shared.capture() } else { Feedback.shared.move(byPlayer: true) }
        board.show(position, animate: m, lastMove: m)
        let over = Rules.gameOver(position)
        if over == .checkmateBlack || over == .stalemate { solved(); return }
        if playerMoves >= p.maxMoves { strike(); return }
        defend()
    }

    private func solved() {
        generation += 1
        score += 1
        combo += 1
        maxCombo = max(maxCombo, combo)
        onEvent?(.solved(combo: combo, tactic: Tactics.detect(position, Side.red)))
        let g = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.38) { [weak self] in
            guard let self, g == self.generation, !self.finished else { return }
            self.loadNext()
        }
    }

    private func strike() {
        generation += 1
        PikafishEngine.shared.stop()
        thinking = false
        lives -= 1
        combo = 0
        onEvent?(.strike)
        if lives <= 0 { finish(L("puzzle_rush_lives_out")); return }
        let g = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
            guard let self, g == self.generation, !self.finished else { return }
            self.loadNext()
        }
    }

    /// 得分 0-4: 卷一; 5-9: 卷一二; 10+: 卷二以后
    private func loadNext() {
        let pool = score < 5 ? Puzzles.all.filter { $0.chapter == 1 }
            : score < 10 ? Puzzles.all.filter { $0.chapter <= 2 } : Puzzles.all.filter { $0.chapter >= 2 }
        var available = pool.filter { !used.contains($0.id) }
        if available.isEmpty { used = []; available = pool }
        let p = available.randomElement()!
        used.insert(p.id)
        generation += 1
        initialFen = p.fen
        position.setFromFen(p.fen)
        history = []
        playerMoves = 0
        thinking = false
        puzzle = p
        board.show(position, lastMove: nil)
    }

    private func defend() {
        thinking = true
        generation += 1
        let g = generation
        let params = SearchParams(movetime: 100, threads: 1, hashMb: 16, multiPv: 1, strictMovetime: true)
        PikafishEngine.shared.search(fen: initialFen, moves: history.map(UciCoords.toUci), params: params) { [weak self] r in
            guard let self, g == self.generation, !self.finished else { return }
            guard let uci = r.bestMove, let m = UciCoords.parse(uci), self.position.isLegalMove(m) else {
                // 引擎无解或应手异常: 判玩家解出
                self.thinking = false
                self.solved()
                return
            }
            self.position.doMove(m)
            self.history.append(m)
            Feedback.shared.move(byPlayer: false)
            self.board.show(self.position, animate: m, lastMove: m)
            let over = Rules.gameOver(self.position)
            if over == .checkmateRed || over == .stalemate { self.strike() } else { self.thinking = false }
        }
    }

    private func finish(_ reason: String) {
        guard !finished else { return }
        stopAll()
        isNewRecord = ProgressStore.shared.recordPuzzleRushScore(score)
        highScore = max(highScore, score)
        honorTitle = L(score < 3 ? "puzzle_rush_honor_1" : score < 6 ? "puzzle_rush_honor_2"
                       : score < 10 ? "puzzle_rush_honor_3" : score < 15 ? "puzzle_rush_honor_4" : "puzzle_rush_honor_5")
        finishReason = reason
        thinking = false
        withAnimation { finished = true }
    }
}
