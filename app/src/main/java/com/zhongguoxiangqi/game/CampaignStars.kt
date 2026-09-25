package com.zhongguoxiangqi.game

/**
 * 闯关三星.
 *
 * 赢下即有一星. 整局不用提示再加一星, 不用悔棋再加一星.
 * 已经记下的星数只升不降, 所以重打低星不会抹掉更好的成绩.
 * 旧存档只有"通关"没有星数时, 按一星展示, 避免把老玩家的关卡打回零星.
 */
object CampaignStars {
    const val MAX = 3

    fun award(won: Boolean, hintsUsed: Int, undosUsed: Int): Int {
        if (!won) return 0
        var stars = 1
        if (hintsUsed <= 0) stars++
        if (undosUsed <= 0) stars++
        return stars
    }

    fun merge(previous: Int, earned: Int): Int =
        maxOf(previous.coerceIn(0, MAX), earned.coerceIn(0, MAX))

    /** [stored] 为 null 表示还没写过星数. */
    fun display(stored: Int?, passed: Boolean): Int = when {
        stored != null -> stored.coerceIn(0, MAX)
        passed -> 1
        else -> 0
    }
}
