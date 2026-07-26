package com.zhongguoxiangqi.game

/**
 * 攻防威胁识别 —— 找出"对方下回合可能吃掉的格子".
 *
 * 走完每一步都重新算: 它的目的是给玩家一个**视觉**提示, 不是引擎分析, 所以
 * 算法选最简单清晰的: 直接遍历对方所有合法着法, 看哪个目标格上有我方子.
 * 一格可能被多枚对方子同时盯着, 记录所有攻击者, 渲染时只画光晕不画箭头,
 * 不会因为多对一就出错.
 *
 * **不**做交换/被保护的子过滤 —— 那些判断要么需要更深的搜索, 要么把"送车
 * 无保护"和"送车但有根"这类值得警告的情况都滤掉. 玩家看到红光时, 自己
 * 判断"有没有根", 比算法猜更准.
 */
object Threats {

    /**
     * @param pos 当前局面(走子方 = [Position.sideToMove])
     * @return 所有「走子方下一步可以吃掉的对方子」, 含攻击者来源
     */
    fun findThreats(pos: Position): List<Threat> {
        val out = mutableListOf<Threat>()
        val moves = mutableListOf<Move>()
        Rules.generateAllLegal(pos, moves)
        val stm = pos.sideToMove
        for (m in moves) {
            val victim = pos.pieceAt(m.to)
            if (victim == Piece.NONE.code) continue
            // 走子方吃对方子 —— 攻击者颜色 = stm, 受害方 = 对方
            val victimColor = Piece.fromCode(victim).color
            if (victimColor == Color.opposite(stm)) {
                out += Threat(target = m.to, attacker = m.from, attackerColor = stm)
            }
        }
        return out
    }
}

/**
 * 一条威胁: 走子方从 [attacker] 可以吃掉 [target] 上的对方子.
 *
 * [targetSquare] 上的子是**对手**的颜色(不是 [attackerColor]).
 * 渲染时光晕颜色由「被吃子颜色」决定: 红子被威胁画红, 黑子被威胁画绿.
 */
data class Threat(
    val target: Int,
    val attacker: Int,
    val attackerColor: Int
)