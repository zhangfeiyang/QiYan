package com.zhongguoxiangqi.game

import org.junit.Assert.*
import org.junit.Test

/**
 * 走法生成器测试.
 */
class MoveGeneratorTest {

    @Test
    fun `initial position has exactly 44 legal moves`() {
        // 中国象棋起始局面有 44 种合法走法(红方先手)
        val pos = Position()
        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)
        assertEquals("起始局面应有 44 种合法走法", 44, moves.size)
    }

    @Test
    fun `rook generates all sliding moves`() {
        // 构造测试: 单车在空棋盘(无其它子)
        val pos = Position()
        for (i in 0 until Square.COUNT) {
            pos.squares[i] = Piece.NONE.code
        }
        // 车放在 (5, 4) (中间位置)
        pos.squares[Square.make(5, 4)] = Piece.R_ROOK.code
        pos.sideToMove = Color.RED

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)

        // 上: 5 步 (0..4,4); 下: 4 步 (6..9,4); 左: 4 步 (5,0..3); 右: 4 步 (5,5..8)
        // 总共 17 步(没有自己的棋子挡住)
        assertEquals(17, moves.size)
    }

    @Test
    fun `horse leg blocking works`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) {
            pos.squares[i] = Piece.NONE.code
        }
        // 马在 (5, 4),马腿在 (4, 4) (马向上走的腿)
        pos.squares[Square.make(5, 4)] = Piece.R_HORSE.code
        pos.squares[Square.make(4, 4)] = Piece.R_PAWN.code  // 自己的兵挡住上方腿
        pos.sideToMove = Color.RED

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)

        // 应该不能往上走(马腿被挡)
        val moveUp = moves.firstOrNull { it.to == Square.make(3, 3) || it.to == Square.make(3, 5) }
        assertNull("马腿被挡时不能往上走", moveUp)
    }

    @Test
    fun `cannon jumps over screen to capture`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) {
            pos.squares[i] = Piece.NONE.code
        }
        // 红炮 (5, 0),炮架 (5, 4) 红兵,目标 (5, 8) 黑车
        pos.squares[Square.make(5, 0)] = Piece.R_CANNON.code
        pos.squares[Square.make(5, 4)] = Piece.R_PAWN.code  // 炮架
        pos.squares[Square.make(5, 8)] = Piece.B_ROOK.code  // 目标
        pos.sideToMove = Color.RED

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)

        // 应该能吃 (5, 8) 的黑车(隔一个炮架)
        val capture = moves.firstOrNull { it.to == Square.make(5, 8) }
        assertNotNull("炮应该能隔着炮架吃子", capture)

        // 应该能在 (5,1), (5,2), (5,3) 移动(无子)
        for (c in 1..3) {
            val m = moves.firstOrNull { it.to == Square.make(5, c) }
            assertNotNull("炮应该能移到 (5, $c)", m)
        }

        // 跨过炮架后,(5,5)(5,6)(5,7) 不能直接吃(炮架之后还有空格)
        for (c in 5..7) {
            val m = moves.firstOrNull { it.to == Square.make(5, c) }
            assertNull("炮跨过炮架后空格不能移动, ($c)", m)
        }
    }

    @Test
    fun `cannon cannot capture without screen`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) {
            pos.squares[i] = Piece.NONE.code
        }
        // 红炮 (5, 4), 直接看到 (5, 0) 黑车 (无炮架)
        pos.squares[Square.make(5, 4)] = Piece.R_CANNON.code
        pos.squares[Square.make(5, 0)] = Piece.B_ROOK.code
        pos.sideToMove = Color.RED

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)

        // 不应该能吃 (5, 0) 的黑车 (无炮架)
        val capture = moves.firstOrNull { it.to == Square.make(5, 0) }
        assertNull("无炮架时炮不能吃子", capture)

        // 但可以移动到 (5, 1), (5, 2), (5, 3)
        assertNotNull(moves.firstOrNull { it.to == Square.make(5, 1) })
        assertNotNull(moves.firstOrNull { it.to == Square.make(5, 2) })
        assertNotNull(moves.firstOrNull { it.to == Square.make(5, 3) })
    }

    @Test
    fun `pawn cannot cross river`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) {
            pos.squares[i] = Piece.NONE.code
        }
        // 红兵在 (5, 0) (还没过河, 红方河 = row <= 4 是过河)
        // 红方过河条件: row <= 4
        // 所以 (5, 0) 还没过河,只能前进到 (4, 0)
        pos.squares[Square.make(5, 0)] = Piece.R_PAWN.code
        pos.sideToMove = Color.RED

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)

        // 应该只能向前走一步到 (4, 0)
        assertEquals(1, moves.size)
        assertEquals(Square.make(4, 0), moves[0].to)
    }

    @Test
    fun `pawn can move sideways after crossing river`() {
        val pos = Position()
        for (i in 0 until Square.COUNT) {
            pos.squares[i] = Piece.NONE.code
        }
        // 红兵已过河,在 (4, 4)
        pos.squares[Square.make(4, 4)] = Piece.R_PAWN.code
        pos.sideToMove = Color.RED

        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)

        // 应该可以前、左、右 (3 种)
        assertEquals(3, moves.size)
        assertTrue(moves.any { it.to == Square.make(3, 4) })  // 前进
        assertTrue(moves.any { it.to == Square.make(4, 3) })  // 左
        assertTrue(moves.any { it.to == Square.make(4, 5) })  // 右
    }
}