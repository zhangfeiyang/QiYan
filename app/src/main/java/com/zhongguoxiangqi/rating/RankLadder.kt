package com.zhongguoxiangqi.rating

/**
 * 段位阶梯上的进度与晋升.
 *
 * 分数跨过下一枚印的门槛才算晋升. 同一枚印里的涨分只推动进度, 不重复授印.
 */
object RankLadder {
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
