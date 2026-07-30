package com.zhongguoxiangqi.game

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * SEE 的攻击者枚举必须与走法生成器**完全一致**.
 *
 * [See.forEachAttacker] 是从目标格反向探出去的独立实现, 和 [MoveGenerator]
 * 是两套代码。规则写错一条(蹩马腿的方向、塞象眼、炮架数、兵过河、九宫边界)
 * 都会让 SEE 算出错误的兑子结果, 进而剪掉好棋 —— 而那种错误不会崩, 只会让
 * 棋力莫名其妙地差。
 *
 * 所以这里逐格交叉验证: 对每一格, "反向探出来的攻击者集合"必须等于"走法
 * 生成器里所有落点为该格的吃子着法的起点集合"。
 */
class SeeTest {

    /** 造一批真实对局里会出现的局面 */
    private fun positions(n: Int, seed: Int): List<Position> {
        val rng = Random(seed)
        val out = mutableListOf<Position>()
        val legal = mutableListOf<Move>()
        while (out.size < n) {
            val p = Position().also { it.setupStartPosition() }
            var dead = false
            repeat(4 + rng.nextInt(70)) {
                legal.clear(); Rules.generateAllLegal(p, legal)
                if (legal.isEmpty()) { dead = true; return@repeat }
                p.doMove(legal[rng.nextInt(legal.size)])
            }
            if (dead) continue
            out.add(p.copy())
        }
        return out
    }

    @Test
    fun `攻击者枚举与走法生成器逐格一致`() {
        val poss = positions(120, 20260731)
        var checked = 0
        for (pos in poss) {
            for (color in intArrayOf(Color.RED, Color.BLACK)) {
                // 比较对象必须是**伪合法**集合, 不能用 generateAll。
                //
                // SEE 按定义不做合法性检查 —— "这个子能不能吃到那一格"是几何
                // 问题, "吃完会不会自曝将"是搜索的事。generateAll 过滤掉送将
                // 着法, 拿它当基准会把 SEE 正确枚举出的攻击者判成多余的。
                // (踩过一次: 黑卒吃红兵是伪合法的, 但走完之后它成了红炮的
                //  炮架, 黑将当场被照, 于是 generateAll 里没有这一手。)
                val saved = pos.sideToMove
                pos.sideToMove = color
                val byTarget = HashMap<Int, MutableSet<Int>>()
                for (from in 0 until Square.COUNT) {
                    val p = pos.squares[from]
                    if (p == Piece.NONE.code || Piece.fromCode(p).color != color) continue
                    for (to in 0 until Square.COUNT) {
                        val victim = pos.squares[to]
                        if (victim == Piece.NONE.code) continue
                        if (Piece.fromCode(victim).color == color) continue
                        if (Rules.isPseudoLegal(pos, Move.make(from, to))) {
                            byTarget.getOrPut(to) { mutableSetOf() }.add(from)
                        }
                    }
                }
                pos.sideToMove = saved

                for (sq in 0 until Square.COUNT) {
                    val expect = byTarget[sq] ?: emptySet<Int>()
                    val got = mutableSetOf<Int>()
                    // 只在该格有敌方子时比对 —— 空格上"能走到"不等于"能吃到",
                    // 而 SEE 只关心吃子
                    val occ = pos.squares[sq]
                    if (occ == Piece.NONE.code) continue
                    if (Piece.fromCode(occ).color == color) continue
                    See.forEachAttacker(pos.squares, sq, color) { got.add(it) }
                    assertEquals(
                        "格 $sq (${Square.row(sq)},${Square.col(sq)}) 上 " +
                            (if (color == Color.RED) "红" else "黑") + "方攻击者不一致\n" +
                            "FEN: ${pos.toFen()}\n" +
                            "走法生成器: ${expect.sorted()}\n" +
                            "SEE 反向探:  ${got.sorted()}",
                        expect, got
                    )
                    checked++
                }
            }
        }
        println("[see] 逐格交叉验证通过: ${poss.size} 个局面, $checked 个被攻击格")
        // 门槛按局面数定: 每个局面平均能贡献二三十个被攻击格。
        // 调局面数时别忘了这行 —— 上次减到 120 却留着 5000 的门槛, 实质断言
        // 全过了却报"覆盖不够"。
        assert(checked > poss.size * 15) { "只比对了 $checked 格, 覆盖不够" }
    }

    @Test
    fun `明显亏损的吃子 SEE 为负`() {
        // 真实局面里"亏损吃子"和"不亏吃子"都该有相当数量 ——
        // 全正或全负都说明实现坏了
        val poss = positions(400, 777)
        var neg = 0
        var pos_ = 0
        val caps = mutableListOf<Move>()
        for (p in poss) {
            caps.clear()
            MoveGenerator.generateCaptures(p, caps)
            for (m in caps) {
                val s = See.evaluate(p, m)
                if (s < 0) neg++ else pos_++
            }
        }
        println("[see] ${poss.size} 个局面的吃子: SEE<0 有 $neg 个, SEE>=0 有 $pos_ 个")
        // 真实局面里两类都该有相当数量 —— 全正或全负说明实现坏了
        assert(neg > 100) { "亏损吃子只有 $neg 个, SEE 可能恒非负" }
        assert(pos_ > 100) { "不亏吃子只有 $pos_ 个, SEE 可能恒负" }
    }

    @Test
    fun `白吃无保护的子 SEE 等于该子价值`() {
        val poss = positions(200, 5150)
        val caps = mutableListOf<Move>()
        var n = 0
        for (p in poss) {
            caps.clear()
            MoveGenerator.generateCaptures(p, caps)
            for (m in caps) {
                val victim = p.squares[m.to]
                // 落点无人保护 => 吃完不会被反吃 => SEE 恰好等于被吃子的价值
                val board = p.squares.copyOf()
                board[m.to] = board[m.from]
                board[m.from] = Piece.NONE.code
                val defColor = Piece.fromCode(victim).color
                var defended = false
                See.forEachAttacker(board, m.to, defColor) { defended = true }
                if (defended) continue
                assertEquals(
                    "白吃 ${m.from}->${m.to} 应当恰好得 ${See.value(victim)}\nFEN: ${p.toFen()}",
                    See.value(victim), See.evaluate(p, m)
                )
                n++
            }
        }
        println("[see] 白吃场景验证 $n 例")
        assert(n > 200) { "只验到 $n 例, 覆盖不够" }
    }
}
