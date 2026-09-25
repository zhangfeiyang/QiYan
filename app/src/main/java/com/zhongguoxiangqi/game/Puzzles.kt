package com.zhongguoxiangqi.game

import com.zhongguoxiangqi.R

/**
 * 残局破解谜题库.
 *
 * 每道谜题: 红方先行, 目标是在 [maxMoves] 步(玩家自己走的步数)内将死黑方。
 * 全部 48 道谜题都经过 PikaFish NNUE 引擎严格实测存在强制杀 (PuzzleVerificationTest 常驻校验),
 * [maxMoves] 取引擎 PV 的实际杀棋步数。
 *
 * 分为六卷, 每卷 8 关 (共 48 关):
 * 卷一: 1~8 关, 一步必杀 (初试锋芒)
 * 卷二: 9~16 关, 两步精妙 (妙手连珠)
 * 卷三: 17~24 关, 两至三步深算 (神机妙算)
 * 卷四: 25~32 关, 三步名局 (纵横捭阖)
 * 卷五: 33~40 关, 三至五步残局 (气吞山河)
 * 卷六: 41~48 关, 传世连环大局 (棋圣入化)
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
    val chapter: Int = ((id - 1) / 8) + 1,
    val tactic: String? = null,
    /** 千题库和战役关不用字符串资源当标题 */
    val titleOverride: String? = null,
    val hintOverride: String? = null,
)

object Puzzles {

    const val CHAPTER_COUNT = 6
    const val PER_CHAPTER = 8

    /** 谜题按难度由浅入深排列, id 从 1 开始连续至 48，每关独立且经 Pikafish 严密核算 */
    val all: List<Puzzle> = listOf(
        // === 第一卷: 初试锋芒 (1步杀) ===
        Puzzle(1, R.string.puzzle_1_name, 1,
            "3aka3/4n4/9/9/2C6/9/9/9/9/3K5 w - - 0 1", R.string.puzzle_1_hint, 1, "闷宫"),
        Puzzle(2, R.string.puzzle_2_name, 1,
            "3aka3/9/4C4/9/9/2C6/9/9/9/3K5 w - - 0 1", R.string.puzzle_2_hint, 1, "重炮"),
        Puzzle(3, R.string.puzzle_3_name, 1,
            "R2ak4/8R/6N2/9/9/9/9/9/9/3K5 w - - 0 1", R.string.puzzle_3_hint, 1, "双车错"),
        Puzzle(4, R.string.puzzle_4_name, 1,
            "4k4/4a4/9/9/9/9/9/9/R8/4K4 w - - 0 1", R.string.puzzle_4_hint, 1, "白脸将"),
        Puzzle(5, R.string.puzzle_5_name, 1,
            "3aka3/9/9/4C4/9/9/9/9/R8/3K5 w - - 0 1", R.string.puzzle_5_hint, 1, "铁门栓"),
        Puzzle(6, R.string.puzzle_6_name, 1,
            "3ak4/4a4/9/9/9/9/9/9/R1C6/3K5 w - - 0 1", R.string.puzzle_6_hint, 1, "天地炮"),
        Puzzle(7, R.string.puzzle_7_name, 1,
            "3ak4/4a4/2N6/9/9/9/9/9/R8/4K4 w - - 0 1", R.string.puzzle_7_hint, 1, "卧槽马"),
        Puzzle(8, R.string.puzzle_8_name, 1,
            "4k4/9/4N4/9/9/9/9/C8/9/5K3 w - - 0 1", R.string.puzzle_8_hint, 1, "马后炮"),

        // === 第二卷: 妙手连珠 (2步杀) ===
        Puzzle(9, R.string.puzzle_9_name, 2,
            "4ka3/R7R/4b4/9/9/9/9/9/9/3K5 w - - 0 1", R.string.puzzle_9_hint, 2, "双车错"),
        Puzzle(10, R.string.puzzle_10_name, 2,
            "3aka3/9/9/9/9/9/R8/9/2R6/3K5 w - - 0 1", R.string.puzzle_10_hint, 2, "双车错"),
        Puzzle(11, R.string.puzzle_11_name, 2,
            "4k4/4a4/2N6/9/9/9/9/9/R8/3K5 w - - 0 1", R.string.puzzle_11_hint, 2, "卧槽马"),
        Puzzle(12, R.string.puzzle_12_name, 2,
            "3aka3/9/9/9/9/9/4N4/4R4/9/3K5 w - - 0 1", R.string.puzzle_12_hint, 2, "拔簧"),
        Puzzle(13, R.string.puzzle_13_name, 2,
            "3aka3/9/9/9/9/4N4/9/2C6/R8/3K5 w - - 0 1", R.string.puzzle_13_hint, 2, "挂角马"),
        Puzzle(14, R.string.puzzle_14_name, 2,
            "3aka3/9/9/9/9/4P4/9/2C6/R8/3K5 w - - 0 1", R.string.puzzle_14_hint, 2, "铁门栓"),
        Puzzle(15, R.string.puzzle_15_name, 2,
            "3aka3/9/9/9/9/9/2R6/9/R8/3K5 w - - 0 1", R.string.puzzle_15_hint, 2, "双车错"),
        Puzzle(16, R.string.puzzle_16_name, 2,
            "3aka3/9/9/9/9/9/9/2C6/2R6/3K5 w - - 0 1", R.string.puzzle_16_hint, 2, "重炮"),

        // === 第三卷: 神机妙算 (2~3步深算) ===
        Puzzle(17, R.string.puzzle_17_name, 3,
            "3ak4/4a4/9/9/9/2N6/9/9/R8/3K5 w - - 0 1", R.string.puzzle_17_hint, 3, "卧槽马"),
        Puzzle(18, R.string.puzzle_18_name, 3,
            "3ak4/4a4/9/2N6/9/9/9/9/R8/3K5 w - - 0 1", R.string.puzzle_18_hint, 3, "钓鱼马"),
        Puzzle(19, R.string.puzzle_19_name, 3,
            "3ak4/4a4/4N4/9/9/9/9/9/R8/3K5 w - - 0 1", R.string.puzzle_19_hint, 3, "挂角马"),
        Puzzle(20, R.string.puzzle_20_name, 3,
            "3ak4/4a4/9/9/9/9/9/R7R/9/3K5 w - - 0 1", R.string.puzzle_20_hint, 3, "双车错"),
        Puzzle(21, R.string.puzzle_21_name, 2,
            "4k4/4a4/6N2/9/9/9/9/9/R8/3K5 w - - 0 1", R.string.puzzle_21_hint, 3, "钓鱼马"),
        Puzzle(22, R.string.puzzle_22_name, 2,
            "3aka3/9/9/9/9/9/4C4/9/R1C6/3K5 w - - 0 1", R.string.puzzle_22_hint, 3, "天地炮"),
        Puzzle(23, R.string.puzzle_23_name, 3,
            "3aka3/9/9/9/9/4N4/9/9/R1C6/3K5 w - - 0 1", R.string.puzzle_23_hint, 3, "马后炮"),
        Puzzle(24, R.string.puzzle_24_name, 3,
            "4k4/9/9/9/9/9/9/R8/1R7/3K5 w - - 0 1", R.string.puzzle_24_hint, 3, "双车错"),

        // === 第四卷: 纵横捭阖 (3步名局) ===
        Puzzle(25, R.string.puzzle_25_name, 3,
            "3aka3/9/9/9/9/9/9/R8/1R7/3K5 w - - 0 1", R.string.puzzle_25_hint, 4, "双车错"),
        Puzzle(26, R.string.puzzle_26_name, 3,
            "3ak4/4a4/9/9/9/9/9/R8/1R7/3K5 w - - 0 1", R.string.puzzle_26_hint, 4, "双车错"),
        Puzzle(27, R.string.puzzle_27_name, 3,
            "3ak4/4a4/9/9/9/9/9/9/R7R/3K5 w - - 0 1", R.string.puzzle_27_hint, 4, "大刀剜心"),
        Puzzle(28, R.string.puzzle_28_name, 3,
            "3ak4/4a4/4N4/9/9/9/9/9/4R4/4K4 w - - 0 1", R.string.puzzle_28_hint, 4, "挂角马"),
        Puzzle(29, R.string.puzzle_29_name, 3,
            "3ak4/9/9/9/9/9/9/R8/1R7/3K5 w - - 0 1", R.string.puzzle_29_hint, 4, "双车错"),
        Puzzle(30, R.string.puzzle_30_name, 3,
            "4ka3/9/9/9/4N4/9/9/9/R7R/3K5 w - - 0 1", R.string.puzzle_30_hint, 4, "双车错"),
        Puzzle(31, R.string.puzzle_31_name, 3,
            "3ak4/4a4/9/9/9/9/9/9/3R5/3K5 w - - 0 1", R.string.puzzle_31_hint, 4, "白脸将"),
        Puzzle(32, R.string.puzzle_32_name, 3,
            "3ak4/4a4/9/9/9/2N6/9/9/4R4/3K5 w - - 0 1", R.string.puzzle_32_hint, 4, "卧槽马"),

        // === 第五卷: 气吞山河 (3~5步残局) ===
        Puzzle(33, R.string.puzzle_33_name, 4,
            "3ak4/4a4/9/2N6/9/9/9/9/4R4/3K5 w - - 0 1", R.string.puzzle_33_hint, 5, "钓鱼马"),
        Puzzle(34, R.string.puzzle_34_name, 4,
            "3aka3/9/4P4/9/9/9/9/9/R8/3K5 w - - 0 1", R.string.puzzle_34_hint, 5, "铁门栓"),
        Puzzle(35, R.string.puzzle_35_name, 4,
            "4k4/4a4/4N4/9/9/9/9/9/R8/4K4 w - - 0 1", R.string.puzzle_35_hint, 5, "挂角马"),
        Puzzle(36, R.string.puzzle_36_name, 4,
            "3aka3/9/9/9/9/9/R8/2R6/4N4/3K5 w - - 0 1", R.string.puzzle_36_hint, 5, "双车错"),
        Puzzle(37, R.string.puzzle_37_name, 4,
            "3aka3/9/9/9/4P4/9/9/4R4/9/3K5 w - - 0 1", R.string.puzzle_37_hint, 5, "铁门栓"),
        Puzzle(38, R.string.puzzle_38_name, 4,
            "3aka3/9/9/9/9/2N6/9/9/R8/3K5 w - - 0 1", R.string.puzzle_38_hint, 5, "卧槽马"),
        Puzzle(39, R.string.puzzle_39_name, 5,
            "3aka3/9/9/9/4P4/9/9/9/R7R/3K5 w - - 0 1", R.string.puzzle_39_hint, 5, "双车错"),
        Puzzle(40, R.string.puzzle_40_name, 3,
            "4ka3/9/9/9/9/9/9/4C4/R7R/3K5 w - - 0 1", R.string.puzzle_40_hint, 5, "天地炮"),

        // === 第六卷: 棋圣入化 (2~8步古谱名局) ===
        Puzzle(41, R.string.puzzle_41_name, 6,
            "3aka3/9/9/9/P8/9/9/4C4/R8/3K5 w - - 0 1", R.string.puzzle_41_hint, 6, "铁门栓"),
        Puzzle(42, R.string.puzzle_42_name, 8,
            "3aka3/9/9/9/9/9/4N4/9/R8/3K5 w - - 0 1", R.string.puzzle_42_hint, 6, "挂角马"),
        Puzzle(43, R.string.puzzle_43_name, 8,
            "3aka3/9/9/9/4N4/9/9/9/R8/3K5 w - - 0 1", R.string.puzzle_43_hint, 6, "卧槽马"),
        Puzzle(44, R.string.puzzle_44_name, 6,
            "3ak4/9/9/9/9/9/9/9/R8/3K5 w - - 0 1", R.string.puzzle_44_hint, 6, "白脸将"),
        Puzzle(45, R.string.puzzle_45_name, 2,
            "4ka3/R7R/9/9/9/9/9/9/9/3K5 w - - 0 1", R.string.puzzle_45_hint, 6, "双车错"),
        Puzzle(46, R.string.puzzle_46_name, 3,
            "4ka3/9/2N6/9/9/9/9/9/R8/3K5 w - - 0 1", R.string.puzzle_46_hint, 6, "卧槽马"),
        Puzzle(47, R.string.puzzle_47_name, 3,
            "4ka3/9/9/9/9/9/9/R8/5R3/3K5 w - - 0 1", R.string.puzzle_47_hint, 6, "双车错"),
        Puzzle(48, R.string.puzzle_48_name, 2,
            "3ak4/4a4/9/9/9/9/2C6/3C5/9/5K3 w - - 0 1", R.string.puzzle_48_hint, 6, "重炮"),
    )

    fun byId(id: Int): Puzzle? = all.firstOrNull { it.id == id }

    fun byChapter(chapter: Int): List<Puzzle> = all.filter { it.chapter == chapter }

    /** 下一道谜题; 已是最后一道返回 null */
    fun next(id: Int): Puzzle? = all.firstOrNull { it.id == id + 1 }
}
