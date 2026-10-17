import SwiftUI

/// 残局破解: 红先, 在限定步数内将死黑方; 引擎执黑最佳防守. 每日一题/残局研习/千题库/历史战役共用.
struct PuzzleView: View {
    let launch: PuzzleLaunch
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = PuzzleViewModel()
    @StateObject private var toaster = Toaster()
    @State private var showClue = false
    @State private var confirmRetry = false
    @State private var result: PuzzleResult?

    var body: some View {
        ZStack {
            PageBackground()
            VStack(alignment: .leading, spacing: 0) {
                TopBar(title: L(vm.dailyDay != nil ? "daily_puzzle_title" : "puzzle_title"), onBack: { dismiss() })
                Text(vm.title).font(.system(size: 18, weight: .bold)).foregroundStyle(Theme.textPrimary)
                    .lineLimit(1).minimumScaleFactor(0.7)
                    .padding(.horizontal, 16)
                Text(vm.meta).font(.system(size: 12)).foregroundStyle(Theme.textSecondary).lineLimit(2)
                    .padding(.horizontal, 16).padding(.top, 2)
                if vm.deviated && !vm.finished {
                    Text(L("puzzle_deviated")).font(.system(size: 12)).foregroundStyle(Theme.danger)
                        .padding(.horizontal, 16).padding(.top, 8)
                }
                ZStack(alignment: .bottomLeading) {
                    BoardView(handle: vm.board, onMove: { m in
                        if !vm.playerMove(m) { toaster.show(L("game_illegal_move")) }
                    })
                    .padding(8)
                    if vm.thinking {
                        Pill(text: L("status_thinking"), color: Theme.warning).padding(12)
                    }
                }
                .frame(maxHeight: .infinity)
                HStack(spacing: 0) {
                    ToolButton("ic_hint", L("puzzle_clue")) { vm.clueUsed = true; showClue = true }
                    ToolButton("ic_undo", L("puzzle_undo"), enabled: vm.canUndo) {
                        if vm.undo() { Feedback.shared.undo() }
                        else { toaster.show(L(vm.undosLeft <= 0 ? "puzzle_undo_exhausted" : "puzzle_undo_unavailable")) }
                    }
                    ToolButton("ic_history", L("puzzle_retry")) {
                        if vm.finished { vm.reset() } else { confirmRetry = true }
                    }
                }
                .padding(.horizontal, 8).padding(.top, 8).padding(.bottom, 12)
            }
        }
        .toast(toaster)
        .onAppear {
            vm.onToast = { key in
                if key == "puzzle_deviated" { Feedback.shared.puzzleWrong() }
                toaster.show(L(key))
            }
            vm.onFinished = { won, tactic in
                if won { Feedback.shared.puzzleSolved() } else { Feedback.shared.puzzleFailed() }
                result = vm.makeResult(won: won, tactic: tactic)
            }
            vm.start(launch)
        }
        .onDisappear { vm.stop() }
        .alert(L("puzzle_clue"), isPresented: $showClue) {
            Button(L("dialog_ok"), role: .cancel) {}
        } message: { Text(vm.hint) }
        .alert(L("puzzle_retry"), isPresented: $confirmRetry) {
            Button(L("dialog_yes")) { vm.reset() }
            Button(L("dialog_no"), role: .cancel) {}
        } message: { Text(L("puzzle_retry_confirm", vm.attempts)) }
        .alert(result?.title ?? "", isPresented: Binding(get: { result != nil }, set: { if !$0 { result = nil } }),
               presenting: result) { r in
            switch r.primary {
            case .more: Button(L("daily_puzzle_more")) { router.replace(.puzzles) }
            case .next(let id): Button(L("puzzle_next_puzzle")) { vm.loadNext(id) }
            case .retry: Button(L("puzzle_retry")) { vm.reset() }
            }
            Button(L("action_back"), role: .cancel) { dismiss() }
        } message: { r in Text(r.message) }
    }
}

struct PuzzleResult {
    enum Primary { case more, next(Int), retry }
    let title: String
    let message: String
    let primary: Primary
}

@MainActor
final class PuzzleViewModel: ObservableObject {
    static let undoLimit = 3
    private static let defendMs = 900, checkMs = 600

    @Published private(set) var puzzle: Puzzle?
    @Published private(set) var attempts = 0
    @Published private(set) var playerMoves = 0
    @Published private(set) var undosLeft = undoLimit
    @Published private(set) var thinking = false
    @Published private(set) var engineReady = false
    @Published private(set) var finished = false
    @Published private(set) var deviated = false
    @Published private(set) var dailyDay: Int?
    var clueUsed = false
    var onToast: ((String) -> Void)?
    var onFinished: ((Bool, String?) -> Void)?

    let board = BoardHandle()
    private let position = Position()
    private var history: [Move] = []
    private var initialFen = ""
    private var battleId = 0
    private var titleOverride: String?
    private var hintOverride: String?
    private let store = ProgressStore.shared
    private var started = false

    var canUndo: Bool { engineReady && !thinking && !finished && playerMoves > 0 && undosLeft > 0 }

    var title: String {
        guard let p = puzzle else { return "" }
        let name: String
        if let t = titleOverride { name = t }
        else if p.id >= PuzzleCatalog.firstId { name = L("catalog_item_title", p.id - PuzzleCatalog.firstId + 1, Names.tactic(p.tactic)) }
        else { name = L("puzzle_stage_name", p.id, L(p.nameKey)) }
        guard let day = dailyDay else { return name }
        let f = DateFormatter()
        f.dateStyle = .medium
        return L("daily_puzzle_date", f.string(from: DailyPractice.date(epochDay: day)), name)
    }

    var meta: String {
        guard let p = puzzle else { return "" }
        return L("puzzle_meta_format", L("puzzle_red_to_move", p.maxMoves), playerMoves, p.maxMoves, undosLeft, attempts)
    }

    var hint: String { hintOverride ?? puzzle.map { L($0.hintKey) } ?? "" }

    func start(_ launch: PuzzleLaunch) {
        guard !started else { return }
        started = true
        PikafishEngine.shared.load { [weak self] ok in
            self?.engineReady = ok
            if !ok { self?.onToast?("puzzle_engine_error") }
        }
        switch launch {
        case .daily:
            let today = DailyPractice.epochDay(Date())
            load(Puzzles.byId(store.dailyPuzzleId(today: today)) ?? Puzzles.all[0], daily: today)
        case .puzzle(let id):
            load(Puzzles.byId(id) ?? Puzzles.all[0])
        case .catalog(let id):
            load(CatalogStore.byId(id) ?? Puzzles.all[0])
        case .battle(let id):
            if let b = HistoryCampaign.battle(id), let p = HistoryCampaign.resolve(b, CatalogStore.all) {
                battleId = id
                titleOverride = L(b.nameKey)
                hintOverride = L(b.briefKey)
                load(p)
            } else {
                load(Puzzles.all[0])
            }
        }
    }

    private func load(_ p: Puzzle, daily: Int? = nil) {
        dailyDay = daily
        puzzle = p
        initialFen = p.fen
        resetBoard()
    }

    func loadNext(_ id: Int) {
        battleId = 0
        clueUsed = false
        titleOverride = nil
        hintOverride = nil
        load(id >= PuzzleCatalog.firstId ? (CatalogStore.byId(id) ?? Puzzles.all[0]) : (Puzzles.byId(id) ?? Puzzles.all[0]))
    }

    func reset() { resetBoard() }

    private func resetBoard() {
        guard let p = puzzle else { return }
        stop()
        position.setFromFen(initialFen)
        history = []
        playerMoves = 0
        undosLeft = Self.undoLimit
        finished = false
        deviated = false
        attempts = store.incrementPuzzleAttempts(p.id)
        board.show(position, lastMove: nil)
    }

    func stop() {
        purpose = .none
        PikafishEngine.shared.stop()
        thinking = false
    }

    // MARK: Moves

    @discardableResult
    func playerMove(_ m: Move) -> Bool {
        guard let p = puzzle, !finished, !thinking, position.sideToMove == Side.red, position.isLegalMove(m) else { return false }
        // 一步将死不必等引擎加载完才落子
        if !engineReady && !matesAtOnce(m) { return false }
        stop()
        let capture = position.pieceAt(m.to) != 0
        position.doMove(m)
        history.append(m)
        playerMoves += 1
        deviated = false
        if capture { Feedback.shared.capture() } else { Feedback.shared.move(byPlayer: true) }
        board.show(position, animate: m, lastMove: m)
        let over = Rules.gameOver(position)
        if over == .checkmateBlack || over == .stalemate { finish(won: true); return true }
        if over != .none || playerMoves >= p.maxMoves { finish(won: false); return true }
        defend()
        return true
    }

    private func matesAtOnce(_ m: Move) -> Bool {
        position.doMove(m)
        let over = Rules.gameOver(position)
        position.undoMove()
        return over == .checkmateBlack || over == .stalemate
    }

    /// 悔掉"玩家+黑方"一整回合
    func undo() -> Bool {
        guard !finished, !thinking, undosLeft > 0, !history.isEmpty else { return false }
        stop()
        position.undoMove()
        history.removeLast()
        if position.sideToMove != Side.red, !history.isEmpty {
            position.undoMove()
            history.removeLast()
        }
        undosLeft -= 1
        playerMoves = (history.count + 1) / 2
        deviated = false
        board.show(position, lastMove: history.last)
        return true
    }

    // MARK: Engine

    private enum Purpose { case none, defense, check }
    private var purpose = Purpose.none

    private func defend() {
        purpose = .defense
        thinking = true
        let params = SearchParams(movetime: Self.defendMs, threads: 1, hashMb: 16, multiPv: 1, strictMovetime: true)
        PikafishEngine.shared.search(fen: initialFen, moves: history.map(UciCoords.toUci), params: params) { [weak self] r in
            guard let self, self.purpose == .defense, !self.finished else { return }
            self.thinking = false
            guard let uci = r.bestMove, let m = UciCoords.parse(uci), self.position.isLegalMove(m) else {
                self.stop()
                self.onToast?("puzzle_engine_error")
                return
            }
            let capture = self.position.pieceAt(m.to) != 0
            self.position.doMove(m)
            self.history.append(m)
            if capture { Feedback.shared.capture() } else { Feedback.shared.move(byPlayer: false) }
            self.board.show(self.position, animate: m, lastMove: m)
            let over = Rules.gameOver(self.position)
            if over == .checkmateRed || over == .stalemate { self.finish(won: false); return }
            if Rules.isInCheck(self.position, Side.red) { Feedback.shared.check() }
            self.checkMateStillOn()
        }
    }

    /// 黑方防守后, 从红方视角快速校验杀机是否还在
    private func checkMateStillOn() {
        purpose = .check
        let params = SearchParams(movetime: Self.checkMs, threads: 1, hashMb: 16, multiPv: 1, strictMovetime: true)
        PikafishEngine.shared.search(fen: initialFen, moves: history.map(UciCoords.toUci), params: params) { [weak self] r in
            guard let self, self.purpose == .check else { return }
            self.purpose = .none
            if (r.info?.scoreMate ?? 0) <= 0 && !self.finished {
                self.deviated = true
                self.onToast?("puzzle_deviated")
            }
        }
    }

    private func finish(won: Bool) {
        guard let p = puzzle, !finished else { return }
        stop()
        finished = true
        if won {
            store.markPuzzleSolved(p.id)
            if let day = dailyDay { store.markDailyCompleted(p.id, challengeDay: day) }
            if battleId != 0 {
                let stars = CampaignStars.award(won: true, hintsUsed: clueUsed ? 1 : 0, undosUsed: Self.undoLimit - undosLeft)
                _ = store.recordChronicleStars(battleId, stars)
            }
        }
        let tactic = won ? (Tactics.detect(position, Side.red) ?? p.tactic) : p.tactic
        onFinished?(won, tactic)
    }

    func makeResult(won: Bool, tactic: String?) -> PuzzleResult {
        let p = puzzle!
        let next = p.id >= PuzzleCatalog.firstId ? CatalogStore.next(p.id) : Puzzles.next(p.id)
        let practice = store.dailyPractice()
        let tacticLabel = won && !(tactic ?? "").isEmpty ? Names.tactic(tactic) : nil
        let tacticLine = tacticLabel.map { L("puzzle_solved_tactic_body", $0) }
        let today = DailyPractice.epochDay(Date())
        let message: String
        if won && dailyDay == today {
            message = [tacticLine, L("daily_puzzle_success", practice.completedDays, practice.currentStreak)]
                .compactMap { $0 }.joined(separator: "\n")
        } else if won && dailyDay != nil {
            message = L("daily_puzzle_expired")
        } else if let t = tacticLine {
            message = t
        } else {
            message = L(won ? "puzzle_solved_body" : "puzzle_fail_moves")
        }
        let title: String
        if let t = tacticLabel { title = L("puzzle_solved_tactic_title", t) }
        else { title = L(won ? "puzzle_solved_title" : "puzzle_fail_title") + " " + L("campaign_level_format", p.id) }
        let primary: PuzzleResult.Primary = won && dailyDay != nil ? .more : (won && next != nil ? .next(next!.id) : .retry)
        return PuzzleResult(title: title, message: message, primary: primary)
    }
}
