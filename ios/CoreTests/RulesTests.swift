import XCTest
@testable import QiYanCore

/// 安卓 PositionTest / MoveGeneratorTest / RulesRegressionTest / UciCoordsTest 的移植.
final class RulesTests: XCTestCase {
    private func empty() -> Position {
        let p = Position()
        p.clear()
        p.sideToMove = Side.red
        return p
    }

    private func m(_ fr: Int, _ fc: Int, _ tr: Int, _ tc: Int) -> Move {
        Move(Square.make(fr, fc), Square.make(tr, tc))
    }

    func testInitialPositionHas32Pieces() {
        XCTAssertEqual(Position().squares.filter { $0 != 0 }.count, 32)
    }

    func testInitialPositionHas44LegalMoves() {
        XCTAssertEqual(Position().legalMoves().count, 44)
    }

    func testMoveAndUndoRestores() {
        let pos = Position()
        let hash = pos.hash, fen = pos.toFen()
        let move = m(7, 1, 7, 3)
        XCTAssertTrue(pos.isLegalMove(move))
        pos.doMove(move)
        XCTAssertNotEqual(hash, pos.hash)
        pos.undoMove()
        XCTAssertEqual(hash, pos.hash)
        XCTAssertEqual(fen, pos.toFen())
    }

    func testBlackHasNoMovesOnRedsTurn() {
        let pos = Position()
        XCTAssertTrue(pos.legalMoves().allSatisfy { Piece.fromCode(pos.pieceAt($0.from)).color == Side.red })
        XCTAssertFalse(pos.isLegalMove(m(0, 0, 0, 1)))
    }

    func testRookSlides() {
        let pos = empty()
        pos.squares[Square.make(5, 4)] = Piece.rRook.code
        XCTAssertEqual(pos.legalMoves().count, 17)
    }

    func testHorseLegBlocks() {
        let pos = empty()
        pos.squares[Square.make(5, 4)] = Piece.rHorse.code
        pos.squares[Square.make(4, 4)] = Piece.rPawn.code
        XCTAssertNil(pos.legalMoves().first { $0.to == Square.make(3, 3) || $0.to == Square.make(3, 5) })
    }

    func testCannonNeedsScreen() {
        let pos = empty()
        pos.squares[Square.make(5, 0)] = Piece.rCannon.code
        pos.squares[Square.make(5, 4)] = Piece.rPawn.code
        pos.squares[Square.make(5, 8)] = Piece.bRook.code
        let moves = pos.legalMoves()
        XCTAssertNotNil(moves.first { $0.to == Square.make(5, 8) })
        for c in 1...3 { XCTAssertNotNil(moves.first { $0.to == Square.make(5, c) }) }
        for c in 5...7 { XCTAssertNil(moves.first { $0.to == Square.make(5, c) }) }

        let p2 = empty()
        p2.squares[Square.make(5, 4)] = Piece.rCannon.code
        p2.squares[Square.make(5, 0)] = Piece.bRook.code
        XCTAssertNil(p2.legalMoves().first { $0.to == Square.make(5, 0) })
    }

    func testPawnRiverRules() {
        let pos = empty()
        pos.squares[Square.make(5, 0)] = Piece.rPawn.code
        XCTAssertEqual(pos.legalMoves().map(\.to), [Square.make(4, 0)])
        let p2 = empty()
        p2.squares[Square.make(4, 4)] = Piece.rPawn.code
        XCTAssertEqual(Set(p2.legalMoves().map(\.to)), [Square.make(3, 4), Square.make(4, 3), Square.make(4, 5)])
    }

    func testPieceRules() {
        let pos = Position()
        XCTAssertFalse(pos.isLegalMove(m(9, 0, 7, 1)), "车不能走马步")
        XCTAssertTrue(pos.isLegalMove(m(9, 4, 8, 4)))
        XCTAssertFalse(pos.isLegalMove(m(9, 4, 9, 2)))
        XCTAssertTrue(pos.isLegalMove(m(9, 2, 7, 4)))
        XCTAssertFalse(pos.isLegalMove(m(9, 2, 3, 4)))
        XCTAssertFalse(pos.isLegalMove(m(9, 0, 9, 1)))
        XCTAssertFalse(pos.isLegalMove(m(9, 0, 4, 4)))
    }

    func testHashes() {
        let a = Position(); a.doMove(m(7, 1, 7, 3))
        let b = Position(); b.doMove(m(7, 1, 7, 4))
        XCTAssertNotEqual(a.hash, b.hash)
        let pos = Position()
        let moves = [m(7, 1, 7, 4), m(2, 7, 2, 4), m(9, 1, 7, 2), m(0, 7, 2, 6)]
        for mv in moves {
            XCTAssertTrue(pos.isLegalMove(mv))
            pos.doMove(mv)
            XCTAssertEqual(pos.computeHash(), pos.hash)
        }
        for _ in moves { pos.undoMove(); XCTAssertEqual(pos.computeHash(), pos.hash) }
    }

    func testFen() {
        XCTAssertEqual(Position().toFen(), Position.startFen)
        let pos = Position()
        pos.doMove(m(7, 1, 7, 4))
        let restored = Position()
        XCTAssertTrue(restored.setFromFen(pos.toFen()))
        XCTAssertEqual(restored.toFen(), pos.toFen())
        XCTAssertEqual(restored.hash, pos.computeHash())
        let before = restored.toFen()
        XCTAssertFalse(restored.setFromFen("这不是FEN"))
        XCTAssertEqual(before, restored.toFen())
    }

    func testHalfmoveClock() {
        let pos = Position()
        pos.doMove(m(7, 1, 7, 4)); XCTAssertEqual(pos.halfmoveClock, 1)
        pos.doMove(m(2, 7, 2, 4)); XCTAssertEqual(pos.halfmoveClock, 2)
        pos.doMove(m(7, 4, 2, 4)); XCTAssertEqual(pos.halfmoveClock, 0)
        pos.undoMove(); XCTAssertEqual(pos.halfmoveClock, 2)
    }

    func testChecks() {
        let pos = empty()
        pos.squares[Square.make(9, 4)] = Piece.rKing.code
        pos.squares[Square.make(0, 4)] = Piece.bKing.code
        XCTAssertTrue(Rules.isInCheck(pos, Side.red), "白脸将")
        pos.squares[Square.make(5, 4)] = Piece.rPawn.code
        XCTAssertFalse(Rules.isInCheck(pos, Side.red))

        let c = empty()
        c.squares[Square.make(9, 4)] = Piece.rKing.code
        c.squares[Square.make(0, 4)] = Piece.bKing.code
        c.squares[Square.make(7, 4)] = Piece.rPawn.code
        c.squares[Square.make(5, 4)] = Piece.bCannon.code
        XCTAssertTrue(Rules.isInCheck(c, Side.red))
        c.squares[Square.make(7, 4)] = Piece.bHorse.code
        XCTAssertTrue(Rules.isInCheck(c, Side.red))

        let h = empty()
        h.squares[Square.make(9, 4)] = Piece.rKing.code
        h.squares[Square.make(0, 4)] = Piece.bKing.code
        h.squares[Square.make(7, 3)] = Piece.bHorse.code
        h.squares[Square.make(4, 4)] = Piece.bPawn.code
        h.squares[Square.make(8, 4)] = Piece.rAdvisor.code
        XCTAssertTrue(Rules.isInCheck(h, Side.red), "帅前方的子不是马腿")
        h.squares[Square.make(8, 4)] = 0
        h.squares[Square.make(8, 3)] = Piece.rAdvisor.code
        XCTAssertFalse(Rules.isInCheck(h, Side.red))
    }

    func testCheckmatedSideLoses() {
        let pos = Position()
        XCTAssertTrue(pos.setFromFen("4k4/3R1R3/9/9/9/9/9/9/4R4/4K4 b - - 0 1"))
        XCTAssertTrue(Rules.isInCheck(pos, Side.black))
        XCTAssertEqual(Rules.gameOver(pos), .checkmateBlack)
    }

    func testChineseNotation() {
        let pos = Position()
        XCTAssertEqual(Move.chineseString(m(7, 1, 7, 4), pos), "炮八平五")
        XCTAssertTrue(Move.chineseString(m(6, 4, 5, 4), pos).contains("进"))
        pos.doMove(m(7, 1, 7, 4))
        XCTAssertEqual(Move.chineseString(m(3, 4, 4, 4), pos), "卒5进1")
        XCTAssertEqual(Move.wxfString(m(2, 7, 2, 4), pos), "C8.5")
    }

    func testUciCoords() {
        let pos = Position()
        XCTAssertEqual(pos.pieceAt(UciCoords.parse("a0a1")!.from), Piece.rRook.code)
        XCTAssertEqual(pos.pieceAt(UciCoords.parse("b0b1")!.from), Piece.rHorse.code)
        XCTAssertEqual(pos.pieceAt(UciCoords.parse("e9e8")!.from), Piece.bKing.code)
        XCTAssertEqual(UciCoords.squareToUci(Square.make(9, 0)), "a0")
        XCTAssertEqual(UciCoords.squareToUci(Square.make(0, 0)), "a9")
        XCTAssertEqual(UciCoords.squareToUci(Square.make(9, 8)), "i0")
        for from in 0..<Square.count {
            for to in 0..<Square.count where from != to {
                let back = UciCoords.parse(UciCoords.toUci(Move(from, to)))
                XCTAssertEqual(back?.from, from)
                XCTAssertEqual(back?.to, to)
            }
        }
        for bad in ["", "b0", "0000", "z0b9", "bxb9"] { XCTAssertNil(UciCoords.parse(bad)) }
    }

    /// 走法数与局面数 (起始局面 perft): 44 / 1920 / 79666
    func testPerft() {
        func perft(_ pos: Position, _ depth: Int) -> Int {
            if depth == 0 { return 1 }
            var n = 0
            for mv in pos.legalMoves() {
                pos.doMove(mv)
                n += perft(pos, depth - 1)
                pos.undoMove()
            }
            return n
        }
        let pos = Position()
        XCTAssertEqual(perft(pos, 1), 44)
        XCTAssertEqual(perft(pos, 2), 1920)
        XCTAssertEqual(perft(pos, 3), 79666)
    }
}
