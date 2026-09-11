package com.zhongguoxiangqi.game

import com.zhongguoxiangqi.R

/**
 * 残局破解谜题库.
 *
 * 每道谜题: 红方先行, 目标是在 [maxMoves] 步(玩家自己走的步数)内将死黑方。
 * 全部谜题都经过 PikaFish 引擎实测存在强制杀 (PuzzleVerificationTest 常驻校验),
 * [maxMoves] 取引擎 PV 的实际杀棋步数 —— 不是拍脑袋写的。
 *
 * 由易到难: 一至三关 = 一步杀, 四五关 = 两步杀, 第六关 = 三步杀, 第七关 = 四步杀。
 *
 * FEN 约定与 [Position.setFromFen] 一致: 第一段从黑方底线(row 0)开始,
 * 大写红方 / 小写黑方, "w" = 红先。
 */
data class Puzzle(
    val id: Int,
    val nameRes: Int,
    /** 玩家(红方)须在多少步内完成将死 */
    val maxMoves: Int,
    val fen: String,
    /** 一句解题思路提示 */
    val hintRes: Int,
)

object Puzzles {

    /** 谜题按难度从易到难排列, id 从 1 开始连续 */
    val all: List<Puzzle> = listOf(
        // 一步杀: 黑马被帅拴死(动则露将), 平帅困毙
        Puzzle(1, R.string.puzzle_1_name, 1,
            "3aka3/4n4/9/9/2C6/9/9/9/9/3K5 w - - 0 1", R.string.puzzle_1_hint),
        // 一步杀: 重炮, 前炮作架后炮将
        Puzzle(2, R.string.puzzle_2_name, 1,
            "3aka3/9/4C4/9/9/2C6/9/9/9/3K5 w - - 0 1", R.string.puzzle_2_hint),
        // 一步杀: 车双将, 后车扫底
        Puzzle(3, R.string.puzzle_3_name, 1,
            "R2ak4/8R/6N2/9/9/9/9/9/9/3K5 w - - 0 1", R.string.puzzle_3_hint),
        // 两步杀: 车沉底叫将(象拦), 换个方向再杀
        Puzzle(4, R.string.puzzle_4_name, 2,
            "4ka3/R7R/4b4/9/9/9/9/9/9/3K5 w - - 0 1", R.string.puzzle_4_hint),
        // 两步杀: 一车占中肋, 另一车辗转叫将
        Puzzle(5, R.string.puzzle_5_name, 2,
            "3aka3/9/9/9/9/9/R8/9/2R6/3K5 w - - 0 1", R.string.puzzle_5_hint),
        // 三步杀: 车炮兵对双士, 兵换门后再杀
        Puzzle(6, R.string.puzzle_6_name, 3,
            "3aka3/9/9/9/9/P8/9/4C4/R8/3K5 w - - 0 1", R.string.puzzle_6_hint),
        // 四步杀: 马炮辗转对双士
        Puzzle(7, R.string.puzzle_7_name, 4,
            "3aka3/9/9/9/4N4/9/9/2C6/9/3K5 w - - 0 1", R.string.puzzle_7_hint),
    )

    fun byId(id: Int): Puzzle? = all.firstOrNull { it.id == id }

    /** 下一道谜题; 已是最后一道返回 null */
    fun next(id: Int): Puzzle? = all.firstOrNull { it.id == id + 1 }
}
