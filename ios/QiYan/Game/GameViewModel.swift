import Foundation
import SwiftUI

/// One game against the engine (port of the Android GameViewModel): board, engine, rating,
/// live win rate, hints, undo, resign, continuation after the result, campaign stars.
@MainActor
final class GameViewModel: ObservableObject {
    struct HintState {
        let move: Move
        let notation: String
    }

    struct State {
        var difficulty = 1
        /// 陪练档/拍照局不计分; 中途调高也不会变成计分局
        var isPractice = true
        var style: PlayStyle = .balanced
        /// > 0 = 登峰棋路关卡
        var campaignLevel = 0
        var playerSide = Side.red
        var sideToMove = Side.red
        var isThinking = false
        var isCheck = false
        var isGameOver = false
        var gameOverReason = ""
        var gameOverWinner = Side.none
        var gameResult: GameResult?
        var moveCount = 0
        var playerRating = 1500
        var ratingDelta = 0
        var playerRank = ""
        var winningTactic: String?
        var openingName: String?
        var hintsUsed = 0
        var undosUsed = 0
        var promotedRank = ""
        var placementComplete = false
        var placementPlayed: Int?
        var endedByResign = false
        var isContinuation = false
        var continuesRatedGame = false
    }

    enum Event {
        case toast(String)
        case threats(red: [Int], black: [Int])
        case check
        case tacticCheck(String)
        case checkmate(String?)
        case move(byPlayer: Bool)
        case capture(piece: Piece, square: Int, attacker: Piece, attackerSquare: Int, byPlayer: Bool)
        case gameOver
    }

    @Published private(set) var state = State()
    @Published private(set) var redWinRate: Double = 50
    @Published private(set) var hint: HintState?
    @Published private(set) var enginePreparing = false
    /// 一次性事件 (音效/提示), 由视图接收
    var onEvent: ((Event) -> Void)?

    let position = Position()
    private(set) var moveHistory: [Move] = []
    private(set) var initialFen = Position.startFen
    let board = BoardHandle()
    private var renderedMoveCount = 0

    private let engine = GameEngine()
    private let profile = UserProfile.shared
    private var startedFromFen = false
    private var gameStart = Date()
    private var hasStarted = false
    private var resultRecorded = false
    private var settledRatingDelta = 0
    private var settledRated = false
    private var startRating = 0
    private var lowestLevelFaced = 1
    private var savedRecordId: Int64 = 0

    private enum Purpose { case none, engineMove, hint, analysis }
    private var purpose = Purpose.none
    private var searchSide = Side.red

    private func emit(_ e: Event) {
        handleBoardEvent(e)
        onEvent?(e)
    }

    // MARK: Start

    /// startFen 非空时从这个局面开局 (拍照识别摆好的残局); 解析失败退回标准开局
    func startNewGame(difficulty: Int, playerSide: Int, startFen: String? = nil, style: PlayStyle = .balanced,
                      campaignLevel: Int = 0) {
        stopEngine()
        moveHistory.removeAll()
        let loaded = startFen.map { position.setFromFen($0) } ?? false
        if !loaded { position.setupStartPosition() }
        initialFen = position.toFen()
        startedFromFen = startFen != nil
        gameStart = Date()
        hasStarted = true
        resultRecorded = false
        settledRatingDelta = 0
        settledRated = false
        savedRecordId = 0
        startRating = profile.displayedRating
        redWinRate = 50
        hint = nil
        let level = DifficultySettings.normalize(difficulty)
        engine.level = level
        engine.style = style
        lowestLevelFaced = level
        let practice = level == 1 || startedFromFen
        state = State(difficulty: level, isPractice: practice, style: style, campaignLevel: campaignLevel,
                      playerSide: playerSide, sideToMove: position.sideToMove, playerRating: profile.displayedRating,
                      playerRank: rankLabel(countsTowardPlacement: campaignLevel == 0 && !practice))
        renderedMoveCount = 0
        board.view?.setThreats(red: [], black: [])
        syncBoard()
        enginePreparing = true
        engine.prepare { [weak self] ok in
            guard let self else { return }
            self.enginePreparing = false
            if !ok { self.emit(.toast(L("game_engine_start_failed"))); return }
            if self.position.sideToMove != self.state.playerSide { self.makeEngineMove() }
        }
    }

    // MARK: Player actions

    @discardableResult
    func playerMove(_ move: Move) -> Bool {
        guard !enginePreparing, !state.isThinking, !state.isGameOver,
              position.sideToMove == state.playerSide, position.isLegalMove(move) else { return false }
        stopEngine()
        hint = nil
        let wasInCheck = Rules.isInCheck(position, state.playerSide)
        let captured = Piece.fromCode(position.pieceAt(move.to))
        let attacker = Piece.fromCode(position.pieceAt(move.from))
        position.doMove(move)
        moveHistory.append(move)
        if wasInCheck && Rules.isInCheck(position, state.playerSide) {
            position.undoMove()
            moveHistory.removeLast()
            emit(.toast(L("must_answer_check")))
            return false
        }
        afterMove(captured: captured, square: move.to, attacker: attacker, attackerSquare: move.from)
        if !state.isGameOver { makeEngineMove() }
        return true
    }

    @discardableResult
    func undo() -> Bool {
        guard !enginePreparing, !state.isThinking, !moveHistory.isEmpty else { return false }
        let toUndo = position.sideToMove == state.playerSide ? 2 : 1
        guard toUndo <= moveHistory.count else { return false }
        stopEngine()
        for _ in 0..<toUndo {
            position.undoMove()
            moveHistory.removeLast()
        }
        state.sideToMove = position.sideToMove
        state.isCheck = Rules.isInCheck(position, position.sideToMove)
        state.isGameOver = false
        state.gameOverReason = ""
        state.gameOverWinner = Side.none
        state.gameResult = nil
        state.winningTactic = nil
        state.endedByResign = false
        state.moveCount = moveHistory.count
        state.openingName = moveHistory.isEmpty ? nil : OpeningDetector.detect(moveHistory.map(UciCoords.toUci))
        state.undosUsed += 1
        redWinRate = 50
        hint = nil
        syncBoard()
        emitThreats()
        return true
    }

    /// 结算页上的「继续」: 认输时原样接着下; 其它终局退回轮到玩家的那一手. 续下部分不计分.
    @discardableResult
    func continueAfterGameOver() -> Bool {
        guard state.isGameOver, !enginePreparing else { return false }
        let resumed: Bool
        if state.endedByResign {
            state.isGameOver = false
            state.gameOverReason = ""
            state.gameOverWinner = Side.none
            state.gameResult = nil
            state.winningTactic = nil
            state.endedByResign = false
            resumed = true
        } else {
            resumed = undo()
        }
        guard resumed else { return false }
        state.isPractice = true
        state.isContinuation = true
        state.continuesRatedGame = settledRated
        state.ratingDelta = 0
        state.promotedRank = ""
        if position.sideToMove != state.playerSide { makeEngineMove() }
        return true
    }

    func resign() {
        guard !enginePreparing, !state.isGameOver else { return }
        stopEngine()
        endGame(.loss, reason: L("status_resign"), resigned: true)
    }

    func requestHint() {
        guard !enginePreparing, !state.isGameOver, !state.isThinking, position.sideToMove == state.playerSide else { return }
        stopEngine()
        searchSide = position.sideToMove
        purpose = .hint
        state.isThinking = true
        state.hintsUsed += 1
        hint = nil
        engine.search(initialFen: initialFen, moves: moveHistory, params: .hint, onInfo: { [weak self] in self?.onInfo($0) }) {
            [weak self] best, _ in self?.onBestMove(best, candidates: [])
        }
    }

    private func startAnalysis() {
        guard !enginePreparing, !state.isGameOver, !state.isThinking else { return }
        stopEngine()
        searchSide = position.sideToMove
        purpose = .analysis
        engine.search(initialFen: initialFen, moves: moveHistory, params: .analysis,
                      onInfo: { [weak self] in self?.onInfo($0) }) { [weak self] _, _ in
            if self?.purpose == .analysis { self?.purpose = .none }
        }
    }

    /// 对局中途改档, 立即生效; 积分按本局出现过的最低档结算
    func setDifficulty(_ level: Int) {
        let l = DifficultySettings.normalize(level)
        guard l != state.difficulty, !state.isGameOver else { return }
        stopEngine()
        engine.level = l
        let notStarted = moveHistory.isEmpty && !state.isContinuation
        lowestLevelFaced = notStarted ? l : min(lowestLevelFaced, l)
        let practice = notStarted ? (l == 1 || startedFromFen) : (state.isPractice || l == 1)
        state.difficulty = l
        state.isPractice = practice
        state.playerRank = rankLabel(countsTowardPlacement: state.campaignLevel == 0 && !practice)
        engine.prepare { [weak self] _ in
            guard let self, !self.enginePreparing, self.position.sideToMove != self.state.playerSide else { return }
            self.makeEngineMove()
        }
    }

    func setStyle(_ style: PlayStyle) {
        guard style != state.style, !state.isGameOver else { return }
        stopEngine()
        engine.style = style
        state.style = style
        if !enginePreparing && position.sideToMove != state.playerSide { makeEngineMove() }
    }

    /// 计分局下过棋之后换边会转为练习
    var swapMakesPractice: Bool { !state.isPractice && state.campaignLevel == 0 && !moveHistory.isEmpty }

    @discardableResult
    func swapSides() -> Bool {
        guard !enginePreparing, !state.isGameOver, state.campaignLevel == 0 else { return false }
        let becomesPractice = swapMakesPractice
        stopEngine()
        hint = nil
        state.playerSide = Side.opposite(state.playerSide)
        state.isThinking = false
        state.isPractice = state.isPractice || becomesPractice
        if position.sideToMove != state.playerSide { makeEngineMove() }
        return true
    }

    // MARK: Engine

    private func makeEngineMove() {
        guard !enginePreparing, !state.isGameOver, position.sideToMove != state.playerSide else { return }
        stopEngine()
        searchSide = position.sideToMove
        purpose = .engineMove
        state.isThinking = true
        engine.search(initialFen: initialFen, moves: moveHistory, params: SearchParams(multiPv: engine.style.multiPv),
                      onInfo: { [weak self] in self?.onInfo($0) }) { [weak self] best, candidates in
            self?.onBestMove(best, candidates: candidates)
        }
    }

    private func onInfo(_ info: EngineInfo) {
        guard purpose != .none else { return }
        let red = searchSide == Side.red
        redWinRate = WinRateAnalyzer.smoothWinRate(red ? info.scoreCp : -info.scoreCp, mate: red ? info.scoreMate : -info.scoreMate)
    }

    private func onBestMove(_ best: String?, candidates: [PlayStyle.Candidate]) {
        let p = purpose
        guard p == .engineMove || p == .hint, !state.isGameOver else { return }
        guard let best, best != UciCoords.nullMove, best.count >= 4 else {
            stopEngine()
            emit(.toast(L("game_search_no_move")))
            return
        }
        if p == .hint {
            purpose = .none
            state.isThinking = false
            if let m = UciCoords.parse(best), position.isLegalMove(m) {
                hint = HintState(move: m, notation: Notation.format(m, position))
                board.view?.setHint(m)
            }
            return
        }
        guard position.sideToMove != state.playerSide else { return }
        guard let parsed = UciCoords.parse(best), position.isLegalMove(parsed) else {
            emit(.toast(L("engine_illegal_move", best)))
            state.isThinking = false
            return
        }
        purpose = .none
        let move = engine.style == .balanced ? parsed : styledMove(parsed, candidates)
        let captured = Piece.fromCode(position.pieceAt(move.to))
        let attacker = Piece.fromCode(position.pieceAt(move.from))
        position.doMove(move)
        moveHistory.append(move)
        afterMove(captured: captured, square: move.to, attacker: attacker, attackerSquare: move.from)
    }

    /// 风格挑选: 与最佳分差足够小的候选里按棋路挑一步
    private func styledMove(_ best: Move, _ raw: [PlayStyle.Candidate]) -> Move {
        guard raw.count > 1 else { return best }
        var entries: [(PlayStyle.Candidate, Move, PlayStyle.Features)] = []
        for c in raw {
            guard let m = UciCoords.parse(c.moveUci), position.isLegalMove(m) else { continue }
            let capture = position.pieceAt(m.to) != 0
            let gain = capture ? See.evaluate(position, m) : 0
            var check = false
            if position.doMove(m) {
                check = Rules.isInCheck(position, position.sideToMove)
                position.undoMove()
            }
            entries.append((c, m, PlayStyle.Features(isCapture: capture, captureGain: gain, givesCheck: check)))
        }
        guard !entries.isEmpty else { return best }
        let picked = engine.style.pick(entries.map(\.0)) { c in entries.first { $0.0 == c }!.2 }
        return entries.first { $0.0 == picked }?.1 ?? best
    }

    private func stopEngine() {
        purpose = .none
        engine.stop()
        state.isThinking = false
    }

    // MARK: After a move

    private func afterMove(captured: Piece, square: Int, attacker: Piece, attackerSquare: Int) {
        let inCheck = Rules.isInCheck(position, position.sideToMove)
        let over = Rules.gameOver(position)
        let reason: String
        switch over {
        case .checkmateRed: reason = L("game_over_checkmate_red")
        case .checkmateBlack: reason = L("game_over_checkmate_black")
        case .stalemate: reason = L("status_stalemate")
        case .repetition: reason = L("game_over_repetition")
        case .fiftyMoves: reason = L("game_over_fifty_moves")
        default: reason = ""
        }
        let winner = over == .checkmateRed ? Side.black : (over == .checkmateBlack ? Side.red : Side.none)
        let tactic = winner != Side.none ? Tactics.detect(position, winner) : nil
        let byPlayer = Side.opposite(position.sideToMove) == state.playerSide

        syncBoard()
        emitThreats()
        if over == .checkmateRed || over == .checkmateBlack {
            emit(.checkmate(tactic))
        } else if inCheck {
            if let t = Tactics.detect(position, Side.opposite(position.sideToMove)) { emit(.tacticCheck(t)) } else { emit(.check) }
        } else if captured != .none {
            emit(.capture(piece: captured, square: square, attacker: attacker, attackerSquare: attackerSquare, byPlayer: byPlayer))
        } else {
            emit(.move(byPlayer: byPlayer))
        }

        let ucis = moveHistory.map(UciCoords.toUci)
        let opening = (moveHistory.count <= 16 ? OpeningDetector.detect(ucis) : nil) ?? state.openingName
        if over != .none {
            let result: GameResult = winner == Side.none ? .draw : (winner == state.playerSide ? .win : .loss)
            state.openingName = opening
            endGame(result, reason: reason, winner: winner, tactic: tactic)
        } else {
            state.sideToMove = position.sideToMove
            state.isCheck = inCheck
            state.isGameOver = false
            state.winningTactic = nil
            state.openingName = opening
            state.gameResult = nil
            state.moveCount = moveHistory.count
            state.isThinking = false
            if position.sideToMove == state.playerSide && state.difficulty >= 3 && moveHistory.count > 5 { startAnalysis() }
        }
    }

    private func emitThreats() {
        let threats = Threats.findThreats(position)
        let red = threats.filter { Piece.fromCode(position.pieceAt($0.target)).isRed }.map(\.target)
        let black = threats.filter { !Piece.fromCode(position.pieceAt($0.target)).isRed }.map(\.target)
        board.view?.setThreats(red: red, black: black)
    }

    /// 只有恰好新增一步才播走子动画; 悔棋/开新局直接刷新
    func syncBoard() {
        let advanced = moveHistory.count == renderedMoveCount + 1
        board.view?.setRippleByPlayer(position.sideToMove != state.playerSide)
        board.setPosition(position, animate: advanced ? moveHistory.last : nil)
        let inCheck = Rules.isInCheck(position, position.sideToMove)
        board.view?.setCheckedKing(inCheck ? Rules.findKing(position, position.sideToMove) : -1)
        board.view?.setLastMove(moveHistory.last)
        if let h = hint { board.view?.setHint(h.move) }
        renderedMoveCount = moveHistory.count
    }

    private func handleBoardEvent(_ e: Event) {
        let kingSq = Rules.findKing(position, position.sideToMove)
        switch e {
        case .check, .tacticCheck:
            board.view?.showTag(kingSq, L("board_tag_check"), color: .token("warning"))
        case .checkmate(let tactic):
            board.view?.showTag(kingSq, tactic.map { L("result_win_tactic_fmt", Names.tactic($0)) } ?? L("board_tag_mate"),
                                color: .token("danger"))
        case let .capture(piece, square, attacker, attackerSquare, _):
            board.view?.playCapture(square: square, captured: piece.code, attacker: attacker.code,
                                    attackerSquare: attackerSquare, label: L("captured_piece_format", Names.piece(piece)))
        default:
            break
        }
    }

    // MARK: End of game

    private func endGame(_ result: GameResult, reason: String, winner: Int = Side.none, tactic: String? = nil, resigned: Bool = false) {
        guard !state.isGameOver else { return }
        stopEngine()
        let first = !resultRecorded
        resultRecorded = true
        let rated = first && state.campaignLevel == 0 && !state.isPractice
        let gamesBefore = profile.totalGames
        if rated {
            profile.recordResult(result, opponentRating: DifficultySettings.nominalElo(lowestLevelFaced))
            settledRatingDelta = profile.displayedRating - startRating
            settledRated = true
        }
        let gamesAfter = profile.totalGames
        let earned = rated ? RankLadder.earnedSeal(ratingBefore: startRating, ratingAfter: profile.displayedRating,
                                                   gamesBefore: gamesBefore, gamesAfter: gamesAfter) : nil
        state.sideToMove = position.sideToMove
        state.isCheck = Rules.isInCheck(position, position.sideToMove)
        state.isGameOver = true
        state.gameOverReason = reason
        state.gameOverWinner = winner
        state.winningTactic = tactic
        state.gameResult = result
        state.endedByResign = resigned
        state.moveCount = moveHistory.count
        state.isThinking = false
        state.playerRating = profile.displayedRating
        state.ratingDelta = rated ? settledRatingDelta : 0
        state.playerRank = rankLabel(countsTowardPlacement: false)
        state.promotedRank = earned.map { L($0.nameKey) } ?? ""
        state.placementComplete = rated && RankLadder.completesPlacement(before: gamesBefore, after: gamesAfter)
        state.placementPlayed = rated && profile.isInPlacement ? gamesAfter : nil

        if state.campaignLevel > 0 { emit(.gameOver); return }
        let record = GameRecord(id: savedRecordId, startTime: gameStart, endTime: Date(), playerSide: state.playerSide,
                                difficulty: state.difficulty, engineElo: engine.nominalElo,
                                result: result.rawValue, ratingDelta: settledRatingDelta, playerRating: profile.displayedRating,
                                moves: moveHistory.map(UciCoords.toUci), initialFen: initialFen,
                                endReason: settledRated ? reason : L("game_practice_record_reason", reason))
        savedRecordId = GameStore.shared.save(record)
        emit(.gameOver)
    }

    var savedGameId: Int64? { savedRecordId > 0 ? savedRecordId : nil }

    private func rankLabel(countsTowardPlacement: Bool) -> String {
        if !profile.isInPlacement { return L(profile.rank.nameKey) }
        if countsTowardPlacement { return L("rank_placement_chip", profile.totalGames + 1, RankLadder.placementGames) }
        return L("rank_placement_name")
    }

    var engineElo: Int { engine.nominalElo }

    func shutdown() { stopEngine() }
}
