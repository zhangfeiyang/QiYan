import SwiftUI
import UIKit

/// 对局回放: 胜率走势图拖动寻步, 六级走法评估, 开局名, 试下变着, PGN/FEN 导出与战报分享.
/// (安卓版的"AI 分析"入口在 iOS 版不提供)
struct ReplayViewerView: View {
    let gameId: Int64
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = ReplayViewModel()
    @StateObject private var toaster = Toaster()
    @State private var share: ShareItem?
    private static let date: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f
    }()

    var body: some View {
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("replay_viewer_title"), onBack: back) {
                    HStack(spacing: 0) {
                        Button { shareReport() } label: {
                            Image("ic_share").renderingMode(.template).resizable().frame(width: 22, height: 22)
                                .foregroundStyle(Theme.textPrimary).frame(width: 44, height: 44)
                        }
                        .accessibilityLabel(L("replay_share_report"))
                        Menu {
                            Button(L("replay_export_pgn")) { exportPgn() }
                            Button(L("replay_copy_fen")) { copy(vm.position.toFen()) }
                        } label: {
                            Image(systemName: "ellipsis").rotationEffect(.degrees(90)).font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(Theme.textPrimary).frame(width: 44, height: 44)
                        }
                    }
                    .disabled(vm.record == nil)
                }
                if let r = vm.record { header(r) }
                BoardView(handle: vm.board, inputEnabled: vm.variation, onMove: { vm.variationMove($0) })
                    .frame(maxHeight: .infinity)
                analysisCard
                details
                controls
            }
        }
        .toast(toaster)
        .onAppear {
            vm.onToast = { toaster.show($0) }
            vm.load(gameId)
            if vm.record == nil { toaster.show(L("replay_record_missing")); dismiss() }
        }
        .onDisappear { vm.stopAutoPlay() }
        .sheet(item: $share) { ShareSheet(items: [$0.text]) }
    }

    private func back() {
        if vm.variation { vm.exitVariation() } else { dismiss() }
    }

    private func header(_ r: GameRecord) -> some View {
        let result = r.result == 1 ? L("result_win_short") : r.result == -1 ? L("result_loss_short") : L("result_draw_short")
        return HStack {
            Text("\(Self.date.string(from: r.startTime))  \(result)").font(.system(size: 15, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
            Spacer()
            Text(L("replay_player_info", r.difficulty, L(UserRank.fromRating(r.playerRating).nameKey), r.moves.count))
                .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
        }
        .padding(.horizontal, 16)
        .frame(height: 40)
        .background(Theme.surfaceRaised.opacity(0.6))
    }

    private var analysisCard: some View {
        VStack(spacing: 4) {
            if let a = vm.analysis {
                HStack {
                    ForEach([("💎", a.brilliantCount), ("🟢", a.bestCount), ("🟡", a.inaccuracyCount),
                             ("🟠", a.mistakeCount), ("🔴", a.blunderCount)], id: \.0) { s in
                        Text("\(s.0) \(s.1)").font(.system(size: 11)).foregroundStyle(Theme.textSecondary)
                            .frame(maxWidth: .infinity)
                    }
                }
                AdvantageGraph(winRates: a.winRates, evaluations: a.evaluations, step: vm.step) { vm.goto($0) }
                    .frame(height: 64)
            } else {
                ProgressView().frame(height: 84)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Theme.surfaceRaised.opacity(0.75))
    }

    @ViewBuilder private var details: some View {
        HStack(spacing: 8) {
            if vm.variation {
                Text(vm.variationText).font(.system(size: 12)).foregroundStyle(Theme.textPrimary).lineLimit(2)
                Spacer(minLength: 4)
                Button(L("replay_variation_undo")) { vm.undoVariation() }
                    .buttonStyle(OutlineButtonStyle(height: 32)).disabled(vm.variationCount == 0)
                    .opacity(vm.variationCount == 0 ? 0.5 : 1)
                Button(L("replay_variation_exit")) { vm.exitVariation() }.buttonStyle(OutlineButtonStyle(height: 32))
            } else {
                Text(vm.moveLine).font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.textPrimary).lineLimit(1)
                    .fixedSize()
                if let q = vm.quality {
                    Text("\(q.symbol) \(L(q.labelKey))").font(.system(size: 11, weight: .bold)).foregroundStyle(Color(hex: q.color))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .overlay(Capsule().stroke(Color(hex: q.color).opacity(0.6), lineWidth: 1))
                        .fixedSize()
                }
                if let o = vm.opening {
                    Text(L("opening_chip", Names.opening(o))).font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                        .lineLimit(1).layoutPriority(-1)
                }
                Spacer(minLength: 4)
                Text(vm.winRateText).font(.system(size: 12)).foregroundStyle(Theme.textSecondary).fixedSize()
                Button { vm.enterVariation() } label: {
                    HStack(spacing: 4) {
                        Image("ic_edit").renderingMode(.template).resizable().frame(width: 13, height: 13)
                        Text(L("replay_try_variation")).font(.system(size: 12, weight: .bold))
                    }
                }
                .buttonStyle(OutlineButtonStyle(height: 30))
                .fixedSize()
                .accessibilityLabel(L("replay_try_variation"))
            }
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 56)
        .background(Theme.surfaceRaised.opacity(0.4))
    }

    private var controls: some View {
        let n = vm.moveCount
        let on = !vm.variation
        return HStack(spacing: 0) {
            control("ic_first", L("replay_first"), enabled: on && vm.step > 0) { vm.goto(0) }
            control("ic_prev", L("replay_prev"), enabled: on && vm.step > 0) { vm.goto(vm.step - 1) }
            control("ic_play", L(vm.autoPlaying ? "replay_pause" : "replay_play"), tint: Theme.action, enabled: on) {
                vm.toggleAutoPlay()
            }
            control("ic_next", L("replay_next"), enabled: on && vm.step < n) { vm.goto(vm.step + 1) }
            control("ic_last", L("replay_last"), enabled: on && vm.step < n) { vm.goto(n) }
        }
        .opacity(vm.variation ? 0.35 : 1)
        .padding(.horizontal, 8).padding(.vertical, 6)
        .background(Theme.surfaceRaised.opacity(0.75).ignoresSafeArea(edges: .bottom))
    }

    private func control(_ icon: String, _ title: String, tint: Color = Theme.textPrimary, enabled: Bool,
                         _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(icon).renderingMode(.template).resizable().frame(width: 22, height: 22)
                Text(title).font(.system(size: 13, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
            }
            .foregroundStyle(enabled ? tint : Theme.textTertiary.opacity(0.6))
            .frame(maxWidth: .infinity, minHeight: 58)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    // MARK: Export

    private func shareReport() {
        guard let r = vm.record else { return }
        share = ShareItem(text: PgnExporter.shareText(r, moves: vm.chineseMoves, opening: vm.lastOpening))
    }

    private func exportPgn() {
        guard let r = vm.record else { return }
        let pgn = PgnExporter.pgn(r, moves: vm.chineseMoves, opening: vm.lastOpening)
        UIPasteboard.general.string = pgn
        share = ShareItem(text: pgn)
    }

    private func copy(_ text: String) {
        UIPasteboard.general.string = text
        toaster.show(L("replay_copied_to_clipboard"))
    }
}

struct ShareItem: Identifiable {
    let id = UUID()
    let text: String
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

@MainActor
final class ReplayViewModel: ObservableObject {
    @Published private(set) var record: GameRecord?
    @Published private(set) var analysis: ReplayAnalysisResult?
    @Published private(set) var step = 0
    @Published private(set) var autoPlaying = false
    @Published private(set) var variation = false
    @Published private(set) var variationCount = 0
    @Published private(set) var variationText = ""
    let board = BoardHandle()
    let position = Position()
    var onToast: ((String) -> Void)?

    private var moves: [String] = []
    private var applied: [Move] = []
    private var variationMoves: [Move] = []
    private var timer: Timer?

    var moveCount: Int { moves.count }
    var chineseMoves: [String] { analysis?.evaluations.map(\.chineseNotation) ?? [] }
    var lastOpening: String? { analysis?.evaluations.last { $0.openingName != nil }?.openingName }

    private var currentEval: MoveEvaluation? { step > 0 ? analysis?.evaluations[safe: step - 1] : nil }
    var quality: MoveQuality? { currentEval?.quality }
    var opening: String? { currentEval?.openingName.flatMap { $0.isEmpty ? nil : $0 } }

    var moveLine: String {
        if step == 0 { return L("replay_start_position") }
        guard currentEval != nil, let m = applied.last else { return L("replay_move_number", step) }
        // 记谱按界面语言重算; 评估里存的是中文记谱, 只给导出用
        position.undoMove()
        let text = Notation.format(m, position)
        position.doMove(m)
        return L("replay_move_line", step, text)
    }

    var winRateText: String {
        if step == 0 { return L("replay_red_winrate", Int((analysis?.winRates.first ?? 50).rounded())) }
        guard let e = currentEval else { return "" }
        return L("replay_red_winrate", Int(e.redWinRate.rounded()))
    }

    func load(_ id: Int64) {
        guard record == nil, let r = GameStore.shared.record(id) else { return }
        record = r
        moves = r.moves
        resetToStart()
        refreshBoard(animate: false)
        let fen = r.initialFen, list = r.moves
        Task.detached(priority: .userInitiated) {
            let a = ReplayEvaluator.analyze(initialFen: fen, uciMoves: list)
            await MainActor.run { self.analysis = a }
        }
    }

    private func resetToStart() {
        let fen = record?.initialFen ?? ""
        if fen.isEmpty || !position.setFromFen(fen) { position.setupStartPosition() }
        step = 0
        applied = []
    }

    func goto(_ target: Int) {
        if variation { exitVariation() }
        let t = min(max(target, 0), moves.count)
        guard t != step else { return }
        let before = step
        while step > t {
            position.undoMove()
            applied.removeLast()
            step -= 1
        }
        while step < t {
            guard let m = UciCoords.parse(moves[step]), position.isLegalMove(m) else {
                // 棋谱损坏: 停在能走到的最后一步
                onToast?(L("replay_move_undo_failed", step + 1))
                stopAutoPlay()
                break
            }
            position.doMove(m)
            applied.append(m)
            step += 1
        }
        refreshBoard(animate: step == before + 1)
        if step == before + 1 { Feedback.shared.move(byPlayer: false) }
    }

    private func refreshBoard(animate: Bool) {
        board.show(position, animate: animate ? applied.last : nil, lastMove: applied.last)
    }

    // MARK: Auto play

    func toggleAutoPlay() {
        if autoPlaying { stopAutoPlay(); return }
        guard step < moves.count else { return }
        autoPlaying = true
        goto(step + 1)
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.autoPlaying else { return }
                if self.step >= self.moves.count { self.stopAutoPlay(); return }
                self.goto(self.step + 1)
            }
        }
    }

    func stopAutoPlay() {
        timer?.invalidate()
        timer = nil
        autoPlaying = false
    }

    // MARK: Variation (试下)

    func enterVariation() {
        guard !variation else { return }
        stopAutoPlay()
        variation = true
        variationMoves = []
        updateVariation(nil)
    }

    func variationMove(_ m: Move) {
        guard variation else { return }
        guard position.isLegalMove(m) else { onToast?(L("game_illegal_move")); return }
        let capture = position.pieceAt(m.to) != 0
        let notation = Notation.format(m, position)
        position.doMove(m)
        variationMoves.append(m)
        if capture { Feedback.shared.capture() } else { Feedback.shared.move(byPlayer: true) }
        board.show(position, animate: m, lastMove: m)
        updateVariation(notation)
    }

    func undoVariation() {
        guard variation, !variationMoves.isEmpty else { return }
        position.undoMove()
        variationMoves.removeLast()
        board.show(position, lastMove: variationMoves.last ?? applied.last)
        updateVariation(variationMoves.isEmpty ? nil : L("replay_variation_step", variationMoves.count))
    }

    func exitVariation() {
        guard variation else { return }
        variation = false
        while !variationMoves.isEmpty {
            position.undoMove()
            variationMoves.removeLast()
        }
        variationCount = 0
        refreshBoard(animate: false)
    }

    private func updateVariation(_ last: String?) {
        variationCount = variationMoves.count
        let rate = Int(ReplayEvaluator.evaluatePosition(position).redWinRate.rounded())
        variationText = last.map { L("replay_variation_status_move", variationCount, $0, rate) }
            ?? L("replay_variation_status", variationCount, rate)
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

/// 棋谱导出 (标准 PGN 与分享战报), 与安卓 PgnExporter 同格式
enum PgnExporter {
    private static func fmt(_ f: String) -> DateFormatter {
        let d = DateFormatter()
        d.dateFormat = f
        return d
    }

    private static func result(_ r: GameRecord) -> String {
        switch r.result {
        case 1: return r.playerSide == Side.red ? "1-0" : "0-1"
        case -1: return r.playerSide == Side.red ? "0-1" : "1-0"
        default: return "1/2-1/2"
        }
    }

    static func pgn(_ r: GameRecord, moves: [String], opening: String?) -> String {
        let res = result(r)
        let engine = "引擎 (Lv.\(r.difficulty))"
        var s = "[Game \"Chinese Chess\"]\n[Event \"棋眼中国象棋对弈\"]\n"
        s += "[Date \"\(fmt("yyyy.MM.dd").string(from: r.startTime))\"]\n"
        s += "[Red \"\(r.playerSide == Side.red ? "玩家" : engine)\"]\n"
        s += "[Black \"\(r.playerSide == Side.black ? "玩家" : engine)\"]\n"
        s += "[Result \"\(res)\"]\n"
        if let o = opening, !o.isEmpty { s += "[Opening \"\(o)\"]\n" }
        if !r.initialFen.isEmpty { s += "[FEN \"\(r.initialFen)\"]\n" }
        s += "\n"
        var turn = 1
        for (i, m) in moves.enumerated() {
            if i % 2 == 0 { s += "\(turn). " }
            s += m + "  "
            if i % 2 == 1 {
                turn += 1
                if turn % 4 == 1 { s += "\n" }
            }
        }
        return s + res + "\n"
    }

    static func shareText(_ r: GameRecord, moves: [String], opening: String?) -> String {
        let desc = r.result == 1 ? "大获全胜 🏆" : r.result == -1 ? "遗憾惜败 ⚔️" : "握手言和 🤝"
        let op = opening.map { "【\($0)】" } ?? "常规开局"
        var s = "♟️【棋眼中国象棋 · 对局战报】\n"
        s += "时间: \(fmt("yyyy-MM-dd HH:mm").string(from: r.startTime))\n"
        s += "模式: 引擎 Lv.\(r.difficulty) (Elo \(r.engineElo))\n"
        s += "战况: \(desc)  \(op)\n"
        s += "回合: 共 \(moves.count) 步\n-------------------\n"
        var turn = 1
        for i in 0..<min(moves.count, 8) {
            if i % 2 == 0 { s += "\(turn). " }
            s += moves[i] + " "
            if i % 2 == 1 { turn += 1; s += "\n" }
        }
        if moves.count > 8 { s += "... 更多对局进程请载入完整棋谱\n" }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
