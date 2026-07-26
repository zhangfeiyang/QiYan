package com.zhongguoxiangqi.rating

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户档案(评分、战绩、设置).
 *
 * 持久化使用 SharedPreferences(轻量数据).
 * 大数据(对局记录)存 Room 数据库.
 */
class UserProfile(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)

    /** 玩家昵称 */
    var nickname: String
        get() = prefs.getString(KEY_NICKNAME, "棋友") ?: "棋友"
        set(value) { prefs.edit().putString(KEY_NICKNAME, value).apply() }

    /** 当前 Glicko-2 评分 */
    var rating: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_RATING, java.lang.Double.doubleToLongBits(1500.0)))
        set(value) { prefs.edit().putLong(KEY_RATING, java.lang.Double.doubleToRawLongBits(value)).apply() }

    /** 当前评分偏差(RD) */
    var rd: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_RD, java.lang.Double.doubleToLongBits(350.0)))
        set(value) { prefs.edit().putLong(KEY_RD, java.lang.Double.doubleToRawLongBits(value)).apply() }

    /** 评分波动率 */
    var sigma: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_SIGMA, java.lang.Double.doubleToLongBits(0.06)))
        set(value) { prefs.edit().putLong(KEY_SIGMA, java.lang.Double.doubleToRawLongBits(value)).apply() }

    /** 总对局 */
    var totalGames: Int
        get() = prefs.getInt(KEY_TOTAL_GAMES, 0)
        set(value) { prefs.edit().putInt(KEY_TOTAL_GAMES, value).apply() }

    /** 胜场 */
    var wins: Int
        get() = prefs.getInt(KEY_WINS, 0)
        set(value) { prefs.edit().putInt(KEY_WINS, value).apply() }

    /** 负场 */
    var losses: Int
        get() = prefs.getInt(KEY_LOSSES, 0)
        set(value) { prefs.edit().putInt(KEY_LOSSES, value).apply() }

    /** 和棋 */
    var draws: Int
        get() = prefs.getInt(KEY_DRAWS, 0)
        set(value) { prefs.edit().putInt(KEY_DRAWS, value).apply() }

    /** 是否首次启动 */
    var firstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) { prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply() }

    /** 已选默认难度 */
    var defaultDifficulty: Int
        get() = prefs.getInt(KEY_DEFAULT_DIFFICULTY, 5)
        set(value) { prefs.edit().putInt(KEY_DEFAULT_DIFFICULTY, value).apply() }

    /** 执子偏好 0=红 1=黑 2=随机 */
    var preferredSide: Int
        get() = prefs.getInt(KEY_PREFERRED_SIDE, 0)
        set(value) { prefs.edit().putInt(KEY_PREFERRED_SIDE, value).apply() }

    /** Glicko-2 评分对象 */
    fun glicko(): Glicko2.Rating = Glicko2.Rating(rating, rd, sigma)

    /** 保存 Glicko-2 评分 */
    fun saveGlicko(g: Glicko2.Rating) {
        rating = g.rating
        rd = g.rd
        sigma = g.sigma
    }

    /** 胜率 */
    val winRate: Double
        get() = if (totalGames == 0) 0.0 else wins.toDouble() / totalGames * 100.0

    /** 段位等级 */
    fun rank(): UserRank = UserRank.fromRating(displayedRating)

    /** 显示评分 */
    val displayedRating: Int get() = rating.toInt().coerceAtLeast(0)

    /** 记录对局结果 */
    fun recordResult(result: GameResult, opponentRating: Int = 1500) {
        val glicko = glicko()
        val match = Glicko2.Match(
            opponentRating = opponentRating.toDouble(),
            opponentRd = 200.0,
            score = result.score
        )
        Glicko2.update(glicko, listOf(match))
        saveGlicko(glicko)

        totalGames++
        when (result) {
            GameResult.WIN -> wins++
            GameResult.LOSS -> losses++
            GameResult.DRAW -> draws++
        }
    }

    companion object {
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_RATING = "rating"
        private const val KEY_RD = "rd"
        private const val KEY_SIGMA = "sigma"
        private const val KEY_TOTAL_GAMES = "total_games"
        private const val KEY_WINS = "wins"
        private const val KEY_LOSSES = "losses"
        private const val KEY_DRAWS = "draws"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_DEFAULT_DIFFICULTY = "default_difficulty"
        private const val KEY_PREFERRED_SIDE = "preferred_side"
    }
}

enum class GameResult(val score: Double, val chinese: String) {
    WIN(1.0, "胜"),
    LOSS(0.0, "负"),
    DRAW(0.5, "和");
}

/**
 * 用户段位(根据评分).
 */
data class UserRank(
    val id: Int,
    val nameRes: Int,
    val colorRes: Int,
    val minRating: Int,
    val maxRating: Int,
    val chineseTitle: String
) {
    companion object {
        val RANKS = listOf(
            UserRank(1, com.zhongguoxiangqi.R.string.rank_1, com.zhongguoxiangqi.R.color.rank_bronze, 0, 999, "初学者"),
            UserRank(2, com.zhongguoxiangqi.R.string.rank_2, com.zhongguoxiangqi.R.color.rank_bronze, 1000, 1199, "入门棋友"),
            UserRank(3, com.zhongguoxiangqi.R.string.rank_3, com.zhongguoxiangqi.R.color.rank_silver, 1200, 1399, "业余初段"),
            UserRank(4, com.zhongguoxiangqi.R.string.rank_4, com.zhongguoxiangqi.R.color.rank_silver, 1400, 1599, "业余三段"),
            UserRank(5, com.zhongguoxiangqi.R.string.rank_5, com.zhongguoxiangqi.R.color.rank_gold, 1600, 1799, "业余五段"),
            UserRank(6, com.zhongguoxiangqi.R.string.rank_6, com.zhongguoxiangqi.R.color.rank_gold, 1800, 1999, "业余七段"),
            UserRank(7, com.zhongguoxiangqi.R.string.rank_7, com.zhongguoxiangqi.R.color.rank_platinum, 2000, 2199, "地方大师"),
            UserRank(8, com.zhongguoxiangqi.R.string.rank_8, com.zhongguoxiangqi.R.color.rank_diamond, 2200, 2399, "国家大师"),
            UserRank(9, com.zhongguoxiangqi.R.string.rank_9, com.zhongguoxiangqi.R.color.rank_master, 2400, 2599, "特级大师"),
            UserRank(10, com.zhongguoxiangqi.R.string.rank_10, com.zhongguoxiangqi.R.color.rank_grandmaster, 2600, 9999, "棋王")
        )

        fun fromRating(rating: Int): UserRank = RANKS.firstOrNull { rating in it.minRating..it.maxRating } ?: RANKS.first()
    }
}