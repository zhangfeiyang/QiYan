import XCTest
@testable import QiYanCore

/// 战术、SEE、威胁、开局、语音、题库、闯关、段位、风格、识别校验的测试 (移植自安卓单元测试).
final class FeatureTests: XCTestCase {
    private func board(_ pieces: [(Int, Int, Piece)], side: Int = Side.black) -> Position {
        let p = Position()
        p.clear()
        for (r, c, piece) in pieces { p.squares[Square.make(r, c)] = piece.code }
        p.sideToMove = side
        return p
    }

    /// 固定种子的随机数, 便于复现
    struct SeededRNG: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    private func randomPositions(_ n: Int, seed: UInt64) -> [Position] {
        var rng = SeededRNG(state: seed)
        var out: [Position] = []
        while out.count < n {
            let p = Position()
            var dead = false
            for _ in 0..<(4 + Int.random(in: 0..<70, using: &rng)) {
                let legal = p.legalMoves()
                if legal.isEmpty { dead = true; break }
                p.doMove(legal.randomElement(using: &rng)!)
            }
            if !dead { out.append(p.copy()) }
        }
        return out
    }

    // MARK: 战术

    func testTactics() {
        let king = [(0, 4, Piece.bKing), (9, 3, Piece.rKing)]
        XCTAssertEqual(Tactics.detect(board(king + [(3, 4, .rHorse), (4, 4, .rCannon)]), Side.red), "马后炮")
        XCTAssertEqual(Tactics.detect(board(king + [(2, 4, .rRook), (0, 3, .rPawn)]), Side.red), "铁门栓")
        XCTAssertEqual(Tactics.detect(board(king + [(2, 4, .rCannon), (4, 4, .rCannon)]), Side.red), "重炮")
        XCTAssertEqual(Tactics.detect(board(king + [(0, 0, .rRook), (1, 8, .rRook)]), Side.red), "双车错")
        XCTAssertEqual(Tactics.detect(board(king + [(1, 2, .rHorse)]), Side.red), "卧槽马")
        XCTAssertEqual(Tactics.detect(board(king + [(2, 3, .rHorse)]), Side.red), "挂角马")
        XCTAssertEqual(Tactics.detect(board(king + [(1, 4, .rRook)]), Side.red), "大刀剜心")
        XCTAssertEqual(Tactics.detect(board([(0, 4, .bKing), (9, 4, .rKing)]), Side.red), "白脸将")
        XCTAssertEqual(Tactics.detect(board(king + [(0, 0, .rCannon), (0, 2, .bPawn), (5, 4, .rCannon)]), Side.red), "天地炮")
        XCTAssertEqual(Tactics.detect(board(king + [(0, 3, .bAdvisor), (0, 5, .bAdvisor), (2, 4, .bPawn), (4, 4, .rCannon)]), Side.red), "闷宫")
        XCTAssertNil(Tactics.detect(board(king + [(2, 4, .rRook)]), Side.red))
        XCTAssertNil(Tactics.detect(board(king + [(5, 0, .rRook)]), Side.red))
    }

    // MARK: SEE

    func testSeeAttackersMatchMoveGenerator() {
        let positions = randomPositions(80, seed: 20260731)
        var checked = 0
        for pos in positions {
            for color in [Side.red, Side.black] {
                let saved = pos.sideToMove
                pos.sideToMove = color
                var byTarget: [Int: Set<Int>] = [:]
                for from in 0..<Square.count {
                    let p = pos.squares[from]
                    guard p != 0, Piece.fromCode(p).color == color else { continue }
                    for to in 0..<Square.count {
                        let v = pos.squares[to]
                        guard v != 0, Piece.fromCode(v).color != color else { continue }
                        if Rules.isPseudoLegal(pos, Move(from, to)) { byTarget[to, default: []].insert(from) }
                    }
                }
                pos.sideToMove = saved
                for sq in 0..<Square.count {
                    let occ = pos.squares[sq]
                    guard occ != 0, Piece.fromCode(occ).color != color else { continue }
                    var got = Set<Int>()
                    See.forEachAttacker(pos.squares, sq, color) { got.insert($0) }
                    XCTAssertEqual(got, byTarget[sq] ?? [], "FEN \(pos.toFen()) sq \(sq)")
                    checked += 1
                }
            }
        }
        XCTAssertGreaterThan(checked, positions.count * 15)
    }

    func testSeeSigns() {
        var neg = 0, nonNeg = 0
        for p in randomPositions(300, seed: 777) {
            var caps: [Move] = []
            MoveGenerator.generateCaptures(p, &caps)
            for m in caps { if See.evaluate(p, m) < 0 { neg += 1 } else { nonNeg += 1 } }
        }
        XCTAssertGreaterThan(neg, 50)
        XCTAssertGreaterThan(nonNeg, 50)
    }

    // MARK: 威胁

    func testThreats() {
        let start = Threats.findThreats(Position())
        XCTAssertTrue(start.contains { $0.target == Square.make(0, 1) && $0.attacker == Square.make(7, 1) })
        let hanging = board([(5, 4, .rRook), (0, 4, .bKing), (0, 3, .bAdvisor), (0, 5, .bAdvisor), (5, 0, .bRook)])
        let t = Threats.findThreats(hanging)
        XCTAssertTrue(t.contains { $0.target == Square.make(5, 4) && $0.attacker == Square.make(5, 0) })
        XCTAssertEqual(t.first?.attackerColor, Side.black)
        XCTAssertTrue(Threats.findThreats(board([], side: Side.red)).isEmpty)
    }

    // MARK: 开局

    func testOpenings() {
        XCTAssertEqual(OpeningDetector.detect(["h2e2"]), "当头炮")
        XCTAssertEqual(OpeningDetector.detect(["h2e2", "h9g7"]), "中炮对屏风马")
        XCTAssertEqual(OpeningDetector.detect(["h2e2", "h9g7", "h0g2", "i9h9", "i0h0"]), "中炮对屏风马")
        XCTAssertEqual(OpeningDetector.detect(["h2e2", "h7e7"]), "顺手炮")
        XCTAssertEqual(OpeningDetector.detect(["h2e2", "b7e7"]), "列手炮")
        XCTAssertEqual(OpeningDetector.detect(["c3c4", "h7g7"]), "仙人指路对卒底炮")
        XCTAssertEqual(OpeningDetector.detect(["g0e2", "h7e7"]), "飞相局对左中炮")
        XCTAssertEqual(OpeningDetector.detect(["h0g2", "c6c5"]), "起马局对挺卒")
        XCTAssertNil(OpeningDetector.detect([]))
    }

    // MARK: 语音

    func testVoiceCommands() {
        let rook = board([(9, 3, .rKing), (0, 4, .bKing), (9, 0, .rRook)], side: Side.red)
        let moves = VoiceCommands.parse(rook, "车九进四")
        XCTAssertEqual(moves.count, 1)
        XCTAssertEqual(moves.first?.to, Square.make(5, 0))
        XCTAssertFalse(VoiceCommands.parse(rook, "车九进四吃马").isEmpty)
        XCTAssertFalse(VoiceCommands.parse(rook, "车九进四将军").isEmpty)
        XCTAssertTrue(VoiceCommands.parse(rook, "随便说点什么").isEmpty)
        XCTAssertTrue(VoiceCommands.parse(rook, "将军").isEmpty)

        let black = board([(0, 4, .bKing), (9, 3, .rKing), (0, 8, .bRook)])
        XCTAssertEqual(VoiceCommands.parse(black, "车9进4").first?.to, Square.make(4, 8))

        let two = board([(9, 3, .rKing), (0, 4, .bKing), (5, 0, .rRook), (9, 0, .rRook)], side: Side.red)
        XCTAssertEqual(VoiceCommands.parse(two, "前车进一").first?.from, Square.make(5, 0))
        XCTAssertEqual(VoiceCommands.parse(two, "后车进一").first?.from, Square.make(9, 0))
        XCTAssertEqual(VoiceCommands.normalize("车９进４"), "车9进4")
        XCTAssertEqual(VoiceCommands.normalize("俥九進四"), "车九进四")
    }

    // MARK: 题库

    func testCatalogIsOneThousandMateInOnes() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "catalog", withExtension: "txt", subdirectory: "Fixtures"))
        let puzzles = PuzzleCatalog.parse(try String(contentsOf: url, encoding: .utf8))
        XCTAssertEqual(puzzles.count, 1000)
        XCTAssertEqual(puzzles.map(\.id), Array(1001...2000))
        let pos = Position()
        for p in puzzles {
            XCTAssertTrue(pos.setFromFen(p.fen))
            XCTAssertEqual(pos.sideToMove, Side.red)
            XCTAssertTrue(MateSearch.isMateInOne(pos), p.fen)
        }
        for battle in HistoryCampaign.battles {
            XCTAssertEqual(HistoryCampaign.resolve(battle, puzzles)?.tactic, battle.tactic)
        }
        XCTAssertEqual(HistoryCampaign.battles.count, 20)
        XCTAssertEqual(HistoryCampaign.chapters.count, 5)
    }

    func testPuzzleTable() {
        XCTAssertEqual(Puzzles.all.count, 48)
        XCTAssertEqual(Puzzles.byChapter(6).count, 8)
        let pos = Position()
        for p in Puzzles.all { XCTAssertTrue(pos.setFromFen(p.fen), "\(p.id)") }
        for t in TacticsAcademy.topics { XCTAssertNotNil(Puzzles.byId(t.puzzleId)) }
        XCTAssertEqual(PuzzleTitles.theme("一步杀·闷宫"), "闷宫")
        XCTAssertEqual(PuzzleTitles.theme("Mate in 1 · Smothered"), "Smothered")
    }

    // MARK: 闯关与段位

    func testCampaignStars() {
        XCTAssertEqual(CampaignStars.award(won: true, hintsUsed: 0, undosUsed: 0), 3)
        XCTAssertEqual(CampaignStars.award(won: true, hintsUsed: 1, undosUsed: 0), 2)
        XCTAssertEqual(CampaignStars.award(won: true, hintsUsed: 1, undosUsed: 1), 1)
        XCTAssertEqual(CampaignStars.award(won: false, hintsUsed: 0, undosUsed: 0), 0)
        XCTAssertEqual(CampaignStars.merge(previous: 3, earned: 1), 3)
        XCTAssertEqual(CampaignStars.display(stored: nil, passed: true), 1)
        XCTAssertEqual(CampaignStars.display(stored: 2, passed: true), 2)
    }

    func testRankLadder() {
        XCTAssertEqual(RankLadder.promoted(before: 1399, after: 1400)?.id, 4)
        XCTAssertNil(RankLadder.promoted(before: 1400, after: 1490))
        let at = RankLadder.progress(1500)
        XCTAssertEqual(at.current.id, 4)
        XCTAssertEqual(at.next?.id, 5)
        XCTAssertEqual(at.pointsToNext, 100)
        XCTAssertEqual(at.fraction, 0.5, accuracy: 0.001)
        XCTAssertNil(RankLadder.progress(2600).next)
        XCTAssertNil(RankLadder.earnedSeal(ratingBefore: 1399, ratingAfter: 1600, gamesBefore: 0, gamesAfter: 1))
        XCTAssertEqual(RankLadder.earnedSeal(ratingBefore: 1450, ratingAfter: 1305, gamesBefore: 2, gamesAfter: 3)?.id, 3)
        XCTAssertEqual(RankLadder.earnedSeal(ratingBefore: 1590, ratingAfter: 1605, gamesBefore: 5, gamesAfter: 6)?.id, 5)
    }

    func testGlickoMovesTowardResult() {
        var r = Glicko2.Rating()
        Glicko2.update(&r, [Glicko2.Match(opponentRating: 1500, opponentRd: 200, score: 1)])
        XCTAssertGreaterThan(r.rating, 1500)
        XCTAssertLessThan(r.rd, 350)
        var l = Glicko2.Rating()
        Glicko2.update(&l, [Glicko2.Match(opponentRating: 1500, opponentRd: 200, score: 0)])
        XCTAssertLessThan(l.rating, 1500)
    }

    func testDailyPractice() {
        let puzzles = [1, 2, 3, 4, 5, 6, 7]
        let today = 20714  // 2026-09-18
        let week = (0..<7).map { DailyPractice.puzzleId(today: today + $0, puzzleIds: puzzles) }
        XCTAssertEqual(Set(week), Set(puzzles))
        XCTAssertTrue(puzzles.contains(DailyPractice.puzzleId(today: 18262, puzzleIds: puzzles)))
        let s = DailyPractice.snapshot(today: today, puzzleIds: puzzles, completed: [today - 1, today - 2])
        XCTAssertFalse(s.completedToday)
        XCTAssertEqual(s.currentStreak, 2)
        let gap = Set((2...5).map { today - $0 }).union([today])
        let g = DailyPractice.snapshot(today: today, puzzleIds: puzzles, completed: gap)
        XCTAssertEqual(g.currentStreak, 1)
        XCTAssertEqual(g.bestStreak, 4)
        XCTAssertEqual(g.completedDays, 5)
        let pid = DailyPractice.puzzleId(today: today, puzzleIds: puzzles)
        let once = DailyPractice.recordCompletion(today: today, challengeDay: today, solvedPuzzleId: pid,
                                                  puzzleIds: puzzles, completed: [today - 1])
        XCTAssertEqual(once, [today - 1, today])
        XCTAssertTrue(DailyPractice.recordCompletion(today: today, challengeDay: today - 1, solvedPuzzleId: pid,
                                                     puzzleIds: puzzles, completed: []).isEmpty)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        let date = cal.date(from: DateComponents(year: 2026, month: 9, day: 18, hour: 23))!
        XCTAssertEqual(DailyPractice.epochDay(date, calendar: cal), today)
    }

    // MARK: 风格与陪练

    func testPlayStyle() {
        func c(_ u: String, _ cp: Int, _ mate: Int = 0) -> PlayStyle.Candidate { .init(moveUci: u, scoreCp: cp, scoreMate: mate) }
        XCTAssertNil(PlayStyle.aggressive.pick([]) { _ in .init() })
        XCTAssertEqual(PlayStyle.balanced.pick([c("h2e2", 60), c("b2e2", 55)]) { _ in .init(isCapture: true, captureGain: 900, givesCheck: true) }?.moveUci, "h2e2")
        XCTAssertEqual(PlayStyle.aggressive.pick([c("h2e2", 80), c("c3d5", -20)]) { $0.moveUci == "c3d5" ? .init(isCapture: true, captureGain: 900, givesCheck: true) : .init() }?.moveUci, "h2e2")
        XCTAssertEqual(PlayStyle.aggressive.pick([c("h2e2", 60), c("c3e2", 50)]) { $0.moveUci == "c3e2" ? .init(isCapture: true, captureGain: 900, givesCheck: true) : .init() }?.moveUci, "c3e2")
        XCTAssertEqual(PlayStyle.solid.pick([c("h2e2", 30), c("b0c2", 25)]) { $0.moveUci == "b0c2" ? .init(isCapture: true, captureGain: -800, givesCheck: true) : .init() }?.moveUci, "h2e2")
        XCTAssertEqual(PlayStyle.solid.pick([c("a0a1", 40), c("a0b2", 40)]) { $0.moveUci == "a0b2" ? .init(isCapture: true, captureGain: 400) : .init() }?.moveUci, "a0a1")
        for _ in 0..<20 {
            let p = PlayStyle.swarm.pick([c("h2e2", 60), c("b2e2", 55), c("c3d5", 45)]) { _ in .init() }
            XCTAssertTrue(["h2e2", "b2e2", "c3d5"].contains(p!.moveUci))
        }
    }

    func testBeginnerEngineStaysLegalAndMissesCaptures() {
        for level in 1...2 {
            for seed in 0..<4 {
                var rng = SeededRNG(state: UInt64(seed))
                let pos = Position()
                for _ in 0..<80 {
                    let fen = pos.toFen(), hash = pos.hash
                    guard let mv = BeginnerEngine.chooseMove(pos, level: level, using: &rng) else { break }
                    XCTAssertEqual(fen, pos.toFen())
                    XCTAssertEqual(hash, pos.hash)
                    XCTAssertTrue(pos.isLegalMove(mv))
                    pos.doMove(mv)
                }
            }
        }
        let pos = Position()
        pos.setFromFen("4k4/9/9/9/4p4/9/9/r8/9/R3K4 w - - 0 1")
        var captures = [0, 0]
        for level in 1...2 {
            var rng = SeededRNG(state: 72)
            for _ in 0..<400 where pos.pieceAt(BeginnerEngine.chooseMove(pos, level: level, using: &rng)!.to) != 0 {
                captures[level - 1] += 1
            }
        }
        XCTAssertLessThan(captures[0], 140)
        XCTAssertGreaterThan(captures[1], 260)
    }

    func testSearchParamsAdapt() {
        let p = SearchParams.adapt(SearchParams(multiPv: 4), level: 5)
        XCTAssertEqual(p.movetime, 6000)
        XCTAssertEqual(p.threads, 4)
        XCTAssertEqual(p.hashMb, 256)
        XCTAssertEqual(p.multiPv, 4)
        XCTAssertEqual(SearchParams.adapt(.hint, level: 8), .hint)
        XCTAssertTrue(SearchParams.usesStrongEngine(level: 1, params: .hint))
        XCTAssertFalse(SearchParams.usesStrongEngine(level: 2, params: SearchParams()))
    }

    // MARK: 识别校验

    private func layout(_ rows: [String]) -> [[Character]] { rows.map(Array.init) }

    private let startRows = ["rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
                             ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABNR"]

    func testBoardLayout() {
        let start = layout(startRows)
        XCTAssertEqual(BoardLayout.toFen(start, sideToMove: Side.red), Position().toFen())
        XCTAssertTrue(BoardLayout.validate(start, sideToMove: Side.red).isEmpty)
        XCTAssertEqual(BoardLayout.fromPosition(BoardLayout.toPosition(start, sideToMove: Side.red)!), start)

        var off = startRows; off[8] = "........x"
        let offIssues = BoardLayout.validate(layout(off), sideToMove: Side.red)
        XCTAssertTrue(offIssues.contains { $0.kind == .offBoard(count: 1) })
        XCTAssertFalse(offIssues.contains(where: \.blocking))

        var noKing = startRows; noKing[0] = "rnba.abnr"
        XCTAssertTrue(BoardLayout.validate(layout(noKing), sideToMove: Side.red).contains { $0.blocking && $0.message(chinese: true).contains("将") })
        var rooks = startRows; rooks[8] = "RRR......"
        XCTAssertTrue(BoardLayout.validate(layout(rooks), sideToMove: Side.red).contains { $0.blocking && $0.message(chinese: true).contains("车") })
        var king = startRows; king[9] = "RNBAKABNK"
        XCTAssertTrue(BoardLayout.validate(layout(king), sideToMove: Side.red).contains { $0.blocking && $0.message(chinese: true).contains("九宫") })
        var ele = startRows; ele[4] = "B........"
        XCTAssertTrue(BoardLayout.validate(layout(ele), sideToMove: Side.red).contains { $0.blocking && $0.message(chinese: true).contains("过河") })

        var check = layout(["....k....", ".........", ".........", ".........", ".........",
                            ".........", ".........", ".........", ".........", "....K...."])
        check[5][4] = "R"
        XCTAssertTrue(BoardLayout.validate(check, sideToMove: Side.red).contains { $0.blocking && $0.message(chinese: true).contains("将军") })

        var conf = [[Float]](repeating: [Float](repeating: 0.99, count: 9), count: 10)
        conf[3][4] = 0.4; conf[7][1] = 0.1
        XCTAssertEqual(Set(BoardLayout.lowConfidenceCells(conf)), [.init(row: 3, col: 4), .init(row: 7, col: 1)])
    }

    func testStabilityGate() {
        let gate = StabilityGate(needed: 2)
        XCTAssertFalse(gate.offer("a"))
        XCTAssertTrue(gate.offer("a"))
        XCTAssertFalse(gate.offer("a"))
        XCTAssertFalse(gate.offer(nil))
        XCTAssertEqual(gate.accepted, "a")
        XCTAssertFalse(gate.offer("b"))
        XCTAssertTrue(gate.offer("b"))
    }

    func testReplayEvaluator() {
        let result = ReplayEvaluator.analyze(initialFen: "", uciMoves: ["h2e2", "h9g7", "h0g2"])
        XCTAssertEqual(result.evaluations.count, 3)
        XCTAssertEqual(result.winRates.count, 4)
        XCTAssertEqual(result.evaluations[0].chineseNotation, "炮二平五")
        XCTAssertEqual(result.evaluations[0].quality, .book)
        let mate = Position()
        mate.setFromFen("4k4/3R1R3/9/9/9/9/9/9/4R4/4K4 b - - 0 1")
        XCTAssertEqual(ReplayEvaluator.staticEval(mate), 9999)
    }

    func testHomographyAndPoseFrame() {
        let src: [Double] = [112, 80, 905, 130, 60, 1010, 980, 960]
        let h = Homography(from: src, to: VisionMath.warpTargets)!
        for i in 0..<4 {
            let (x, y) = h.apply(src[i * 2], src[i * 2 + 1])
            XCTAssertEqual(x, VisionMath.warpTargets[i * 2], accuracy: 1e-6)
            XCTAssertEqual(y, VisionMath.warpTargets[i * 2 + 1], accuracy: 1e-6)
        }
        let back = h.inverse!.apply(25, 425)
        XCTAssertEqual(back.0, 60, accuracy: 1e-6)
        XCTAssertEqual(back.1, 1010, accuracy: 1e-6)

        let f = VisionMath.PoseFrame(width: 1920, height: 1080)
        let (ix, iy) = f.toInput(960, 540)
        XCTAssertEqual(ix, 128, accuracy: 1e-9)
        XCTAssertEqual(iy, 128, accuracy: 1e-9)
        let (sx, sy) = f.toSource(f.toInput(100, 200).0, f.toInput(100, 200).1)
        XCTAssertEqual(sx, 100, accuracy: 1e-9)
        XCTAssertEqual(sy, 200, accuracy: 1e-9)

        // SimCC: bin 256 of 512 -> centre of the 256 input -> image centre
        var x = [Float](repeating: 0, count: 4 * 512), y = x
        for i in 0..<4 { x[i * 512 + 256] = 0.9; y[i * 512 + 256] = 0.5 }
        let (corners, scores) = VisionMath.decodeCorners(simccX: x, simccY: y, frame: f)
        XCTAssertEqual(corners[0], 960, accuracy: 1e-6)
        XCTAssertEqual(corners[1], 540, accuracy: 1e-6)
        XCTAssertEqual(scores[3], 0.45, accuracy: 1e-6)
    }

    func testDecodeLayout() {
        var probs = [Float](repeating: 0, count: 90 * 16)
        for i in 0..<90 { probs[i * 16] = 0.9 }
        probs[4 * 16 + 9] = 0.95     // a0 row, col 4: 'k'
        probs[85 * 16 + 2] = 0.97    // row 9, col 4: 'K'
        let (layout, conf) = VisionMath.decodeLayout(probs)
        XCTAssertEqual(layout[0][4], "k")
        XCTAssertEqual(layout[9][4], "K")
        XCTAssertEqual(layout[5][5], ".")
        XCTAssertEqual(conf[9][4], 0.97, accuracy: 1e-6)
    }
}

