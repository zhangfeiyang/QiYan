package com.zhongguoxiangqi.game

/**
 * 静态交换评估 (Static Exchange Evaluation).
 *
 * 回答"在某一格连续兑子, 双方都挑最便宜的子来吃, 最终净得多少子力"。
 * 搜索用它来跳过明显亏损的吃子(车吃有保护的兵)和改进吃子排序。
 *
 * ## 象棋为什么不能照抄国象的做法
 *
 * 国象的 SEE 有个标准技巧: 一次性算出所有攻击者, 每吃掉一个就增量地把"被它
 * 挡住的远程子"补进来(x-ray)。象棋不行, 因为**炮必须隔恰好一个子才能吃**:
 *
 *   - 移除一个子可能解除车的阻挡  -> 多一个攻击者 (和国象一样)
 *   - 移除一个子可能拆掉炮架      -> **少**一个攻击者
 *   - 移除一个子可能把"隔两子的炮"变成"隔一子" -> **凭空多**一个攻击者
 *
 * 三个方向都可能, 增量更新会算错。所以这里每吃一步都**重新枚举**攻击者。
 * 代价是慢一些, 但兑子序列通常只有两三个来回, 而算错的代价是剪掉好棋。
 *
 * ## 正确性怎么保证
 *
 * [attackersTo] 是从目标格反向探出去的独立实现, 与 [MoveGenerator] 是两套
 * 代码。SeeTest 在随机局面上交叉验证两者: 对每一格, "反向探出来的攻击者集合"
 * 必须与"走法生成器里所有落点为该格的吃子着法的起点集合"完全相同。规则写错
 * 一条(蹩马腿、塞象眼、炮架、兵过河)都会被这条测试抓住。
 */
object See {

    /** 与 Move.mvvLva 用的是同一套, 两处不一致会让排序和裁剪各说各话 */
    fun value(pieceCode: Int): Int = when (Piece.fromCode(pieceCode).type) {
        1 -> KING_VALUE
        2, 3 -> 200      // 仕 / 相
        4 -> 400         // 马
        5 -> 900         // 车
        6 -> 450         // 炮
        7 -> 100         // 兵
        else -> 0
    }

    /**
     * 走 [move] 这个吃子之后, 在落点连续兑完的净子力收益(走子方视角, 厘兵).
     *
     * 返回 >= 0 表示这个吃子不亏。非吃子着法返回 0。
     */
    fun evaluate(pos: Position, move: Move): Int {
        val to = move.to
        val victim = pos.squares[to]
        if (victim == Piece.NONE.code) return 0
        val attacker = pos.squares[move.from]
        if (attacker == Piece.NONE.code) return 0

        // 在一份拷贝上模拟, 不动真实局面
        val board = pos.squares.copyOf()
        var side = Color.opposite(Piece.fromCode(attacker).color)

        // gain[d] = "站在第 d 层的一方, 若继续吃下去能拿到的净收益"
        val gain = IntArray(MAX_SWAPS)
        gain[0] = value(victim)
        board[to] = attacker
        board[move.from] = Piece.NONE.code

        var d = 1
        var onSquare = attacker          // 当前占着落点的子, 下一步会被吃
        while (d < MAX_SWAPS) {
            val from = leastValuableAttacker(board, to, side)
            if (from < 0) break
            gain[d] = value(onSquare) - gain[d - 1]
            // 剪枝: 就算后面全按最好的走, 这一层也不可能翻盘
            if (maxOf(-gain[d - 1], gain[d]) < 0) break
            onSquare = board[from]
            board[to] = onSquare
            board[from] = Piece.NONE.code
            side = Color.opposite(side)
            d++
        }
        // 从最深处回推: 每一层都可以选择"不再吃"
        while (d > 1) {
            d--
            gain[d - 1] = -maxOf(-gain[d - 1], gain[d])
        }
        return gain[0]
    }

    /**
     * [color] 方能吃到 [to] 的子里最便宜的那个, 返回它的起点; 没有则 -1.
     *
     * 从目标格反向探: 沿各方向找过去, 看那里的子能不能反过来打到这一格。
     * 这样只看常数个方向, 不用扫全盘。
     */
    fun leastValuableAttacker(board: IntArray, to: Int, color: Int): Int {
        var best = -1
        var bestVal = Int.MAX_VALUE
        forEachAttacker(board, to, color) { from ->
            val v = value(board[from])
            if (v < bestVal) { bestVal = v; best = from }
        }
        return best
    }

    /** 枚举 [color] 方所有能吃到 [to] 的子的起点 */
    inline fun forEachAttacker(board: IntArray, to: Int, color: Int, visit: (Int) -> Unit) {
        val tr = Square.row(to)
        val tc = Square.col(to)

        // ---- 车 / 炮 / 将(飞将) : 四条直线 ----
        for (dir in LINE_DIRS) {
            var step = 1
            var screen = 0          // 沿途遇到的子数
            while (true) {
                val r = tr + dir[0] * step
                val c = tc + dir[1] * step
                if (r !in 0 until Square.ROWS || c !in 0 until Square.COLS) break
                val sq = Square.make(r, c)
                val p = board[sq]
                if (p != Piece.NONE.code) {
                    val pc = Piece.fromCode(p)
                    if (pc.color == color) {
                        when {
                            // 车: 中间无子
                            screen == 0 && pc.type == 5 -> visit(sq)
                            // 炮: 中间恰好一个子
                            screen == 1 && pc.type == 6 -> visit(sq)
                            // 将: 贴着的一格(且在九宫内), 或飞将(同列中间无子)
                            screen == 0 && pc.type == 1 &&
                                (step == 1 && inPalace(r, c, color) ||
                                    dir[1] == 0 && board[to] != Piece.NONE.code &&
                                    Piece.fromCode(board[to]).type == 1) -> visit(sq)
                        }
                    }
                    screen++
                    if (screen > 1) break     // 隔两个子之后什么都打不到了
                }
                step++
            }
        }

        // ---- 马: 从目标格反推八个起点, 蹩腿看的是**起点侧**那一格 ----
        for (d in HORSE_FROM) {
            val r = tr + d[0]
            val c = tc + d[1]
            if (r !in 0 until Square.ROWS || c !in 0 until Square.COLS) continue
            val sq = Square.make(r, c)
            val p = board[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != color || pc.type != 4) continue
            // 马腿在起点朝目标方向的第一格
            val legR = r + d[2]
            val legC = c + d[3]
            if (board[Square.make(legR, legC)] == Piece.NONE.code) visit(sq)
        }

        // ---- 相/象: 田字, 塞象眼, 且不能过河 ----
        for (d in ELEPHANT_DIRS) {
            val r = tr + d[0]
            val c = tc + d[1]
            if (r !in 0 until Square.ROWS || c !in 0 until Square.COLS) continue
            val sq = Square.make(r, c)
            val p = board[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != color || pc.type != 3) continue
            if (crossedRiver(tr, color)) continue          // 象不能过河
            if (board[Square.make(r + d[0] / -2, c + d[1] / -2)] != Piece.NONE.code) continue
            visit(sq)
        }

        // ---- 仕/士: 九宫斜一格 ----
        for (d in ADVISOR_DIRS) {
            val r = tr + d[0]
            val c = tc + d[1]
            if (r !in 0 until Square.ROWS || c !in 0 until Square.COLS) continue
            val sq = Square.make(r, c)
            val p = board[sq]
            if (p == Piece.NONE.code) continue
            val pc = Piece.fromCode(p)
            if (pc.color != color || pc.type != 2) continue
            if (!inPalace(tr, tc, color) || !inPalace(r, c, color)) continue
            visit(sq)
        }

        // ---- 兵/卒: 从目标格反推, 前进方向相反; 横吃要它已过河 ----
        run {
            val back = if (color == Color.RED) 1 else -1   // 兵往上走, 所以起点在下方
            val r = tr + back
            if (r in 0 until Square.ROWS) {
                val sq = Square.make(r, tc)
                val p = board[sq]
                if (p != Piece.NONE.code) {
                    val pc = Piece.fromCode(p)
                    if (pc.color == color && pc.type == 7) visit(sq)
                }
            }
            for (dc in intArrayOf(-1, 1)) {
                val c = tc + dc
                if (c !in 0 until Square.COLS) continue
                val sq = Square.make(tr, c)
                val p = board[sq]
                if (p == Piece.NONE.code) continue
                val pc = Piece.fromCode(p)
                if (pc.color != color || pc.type != 7) continue
                if (crossedRiver(tr, color)) visit(sq)      // 过河才能横吃
            }
        }
    }

    /** 该格对 [color] 而言是否已过河 */
    fun crossedRiver(row: Int, color: Int): Boolean =
        if (color == Color.RED) row <= 4 else row >= 5

    fun inPalace(row: Int, col: Int, color: Int): Boolean =
        col in 3..5 && if (color == Color.RED) row in 7..9 else row in 0..2

    /** 兑子序列最多算这么多层. 真实局面很少超过六七层, 给足余量 */
    const val MAX_SWAPS = 32
    const val KING_VALUE = 10000

    val LINE_DIRS = arrayOf(
        intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1)
    )

    /**
     * 马: 前两项是"从目标格看, 攻击者在哪", 后两项是"从攻击者走向目标时的马腿偏移".
     * 马腿永远在**起点**朝目标方向的第一格 —— 写成从目标格反推时最容易搞反。
     */
    val HORSE_FROM = arrayOf(
        intArrayOf(-2, -1, 1, 0), intArrayOf(-2, 1, 1, 0),
        intArrayOf(2, -1, -1, 0), intArrayOf(2, 1, -1, 0),
        intArrayOf(-1, -2, 0, 1), intArrayOf(1, -2, 0, 1),
        intArrayOf(-1, 2, 0, -1), intArrayOf(1, 2, 0, -1)
    )

    val ELEPHANT_DIRS = arrayOf(
        intArrayOf(-2, -2), intArrayOf(-2, 2), intArrayOf(2, -2), intArrayOf(2, 2)
    )

    val ADVISOR_DIRS = arrayOf(
        intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1)
    )
}
