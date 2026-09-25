package com.zhongguoxiangqi.rating

/**
 * 段位阶梯上的进度与晋升.
 *
 * 分数跨过下一枚印的门槛才算晋升. 同一枚印里的涨分只推动进度, 不重复授印.
 */
object RankLadder {
    /**
     * 定级盘数.
     *
     * 新档案的评分偏差 (RD) 是 350, 头几盘一盘就能涨落近两百分: 第一盘计分局
     * 输给「进阶」, 业余三段直接掉到业余初段; 赢一盘又立刻"晋升". 一个还没
     * 下过计分局的人, 首页却已经挂着业余三段, 然后在第一盘被"降两段" ——
     * 段位印的分量就这么被开局的噪声冲掉了. 所以前几盘只定分不授印, 下满之后
     * 一次揭晓第一枚印.
     */
    const val PLACEMENT_GAMES = 3

    fun inPlacement(ratedGames: Int): Boolean = ratedGames < PLACEMENT_GAMES

    /** 这一盘下完正好定级结束 —— 结算页要揭晓第一枚印 */
    fun completesPlacement(ratedGamesBefore: Int, ratedGamesAfter: Int): Boolean =
        inPlacement(ratedGamesBefore) && !inPlacement(ratedGamesAfter)

    /**
     * 一盘计分局前后, 结算页该宣布的新印; 没有就是 null.
     *
     * 定级期间不谈晋升; 定级结束的那一盘无论分数怎么走都揭晓当前的印.
     */
    fun earnedSeal(ratingBefore: Int, ratingAfter: Int, ratedGamesBefore: Int, ratedGamesAfter: Int): UserRank? = when {
        completesPlacement(ratedGamesBefore, ratedGamesAfter) -> UserRank.fromRating(ratingAfter)
        inPlacement(ratedGamesAfter) -> null
        else -> promoted(ratingBefore, ratingAfter)
    }

    data class Progress(
        val current: UserRank,
        val next: UserRank?,
        val fraction: Float,
        val pointsToNext: Int,
    )

    fun promoted(before: Int, after: Int): UserRank? {
        val from = UserRank.fromRating(before)
        val to = UserRank.fromRating(after)
        return if (to.id > from.id) to else null
    }

    fun progress(rating: Int): Progress {
        val safe = rating.coerceAtLeast(0)
        val current = UserRank.fromRating(safe)
        val next = UserRank.RANKS.firstOrNull { it.id == current.id + 1 }
            ?: return Progress(current, null, 1f, 0)
        val span = (next.minRating - current.minRating).coerceAtLeast(1)
        val fraction = ((safe - current.minRating).toFloat() / span).coerceIn(0f, 1f)
        return Progress(current, next, fraction, (next.minRating - safe).coerceAtLeast(0))
    }
}
