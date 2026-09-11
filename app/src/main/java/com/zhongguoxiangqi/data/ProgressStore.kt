package com.zhongguoxiangqi.data

import android.content.Context

/**
 * 闯关进度存储(对弈闯关 + 残局破解 共用).
 *
 * 与战绩积分刻意分开存: 闯关的胜利不计入 Elo/战绩, 这里只记"第几关解锁了、
 * 哪些残局破解过、各试了几次"。
 */
class ProgressStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ====== 对弈闯关 ======

    /** 已解锁的最高关卡(1 起); 通关第 n 关后解锁 n+1 */
    var campaignUnlocked: Int
        get() = prefs.getInt(KEY_CAMPAIGN_UNLOCKED, 1)
        private set(value) = prefs.edit().putInt(KEY_CAMPAIGN_UNLOCKED, value).apply()

    /** 已通关的关卡集合 */
    fun campaignPassedLevels(): Set<Int> =
        prefs.getStringSet(KEY_CAMPAIGN_PASSED, emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: emptySet()

    /** 通关第 [level] 关: 打通关卡标记并解锁下一关 */
    fun markCampaignPassed(level: Int) {
        val passed = campaignPassedLevels() + level
        prefs.edit()
            .putStringSet(KEY_CAMPAIGN_PASSED, passed.map { it.toString() }.toSet())
            .putInt(KEY_CAMPAIGN_UNLOCKED, maxOf(campaignUnlocked, level + 1))
            .apply()
    }

    // ====== 残局破解 ======

    /** 已破解的残局 id 集合 */
    fun puzzleSolvedIds(): Set<Int> =
        prefs.getStringSet(KEY_PUZZLE_SOLVED, emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: emptySet()

    fun markPuzzleSolved(id: Int) {
        val solved = puzzleSolvedIds() + id
        prefs.edit().putStringSet(KEY_PUZZLE_SOLVED, solved.map { it.toString() }.toSet()).apply()
    }

    /** 某道残局的累计尝试次数 */
    fun puzzleAttempts(id: Int): Int = prefs.getInt("$KEY_PUZZLE_ATTEMPTS$id", 0)

    /** 尝试次数 +1, 返回新值 */
    fun incrementPuzzleAttempts(id: Int): Int {
        val v = puzzleAttempts(id) + 1
        prefs.edit().putInt("$KEY_PUZZLE_ATTEMPTS$id", v).apply()
        return v
    }

    private companion object {
        const val PREFS = "progress"
        const val KEY_CAMPAIGN_UNLOCKED = "campaign_unlocked"
        const val KEY_CAMPAIGN_PASSED = "campaign_passed"
        const val KEY_PUZZLE_SOLVED = "puzzle_solved"
        const val KEY_PUZZLE_ATTEMPTS = "puzzle_attempts_"
    }
}
