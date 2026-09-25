package com.zhongguoxiangqi.data

import android.content.Context
import com.zhongguoxiangqi.engine.DifficultySettings
import com.zhongguoxiangqi.game.CampaignStars
import com.zhongguoxiangqi.game.Puzzles
import java.time.LocalDate

/**
 * 闯关进度存储(对弈闯关 + 残局破解 共用).
 *
 * 与战绩积分刻意分开存: 闯关的胜利不计入 Elo/战绩, 这里只记"第几关解锁了、
 * 哪些残局破解过、各试了几次"。
 */
class ProgressStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (prefs.getInt(KEY_CAMPAIGN_VERSION, 0) < DifficultySettings.STORAGE_VERSION) {
            val migrated = CampaignProgressMigration.migrate(
                prefs.getInt(KEY_CAMPAIGN_UNLOCKED, 1), campaignPassedLevels(),
            )
            prefs.edit()
                .putStringSet(KEY_CAMPAIGN_PASSED, migrated.passed.map { it.toString() }.toSet())
                .putInt(KEY_CAMPAIGN_UNLOCKED, migrated.unlocked)
                .putInt(KEY_CAMPAIGN_VERSION, DifficultySettings.STORAGE_VERSION).apply()
        }
    }

    // ====== 对弈闯关 ======

    /** 已解锁的最高关卡(1 起); 通关第 n 关后解锁 n+1 */
    var campaignUnlocked: Int
        get() = prefs.getInt(KEY_CAMPAIGN_UNLOCKED, 1).coerceIn(1, DifficultySettings.MAX_LEVEL)
        private set(value) = prefs.edit().putInt(KEY_CAMPAIGN_UNLOCKED, value).apply()

    /** 已通关的关卡集合 */
    fun campaignPassedLevels(): Set<Int> =
        prefs.getStringSet(KEY_CAMPAIGN_PASSED, emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: emptySet()

    /** 通关第 [level] 关: 打通关卡标记并解锁下一关 */
    fun markCampaignPassed(level: Int) {
        if (level !in 1..DifficultySettings.MAX_LEVEL) return
        val passed = campaignPassedLevels() + level
        prefs.edit()
            .putStringSet(KEY_CAMPAIGN_PASSED, passed.map { it.toString() }.toSet())
            .putInt(KEY_CAMPAIGN_UNLOCKED, maxOf(campaignUnlocked, level + 1).coerceAtMost(DifficultySettings.MAX_LEVEL))
            .apply()
    }

    /**
     * 某一关目前展示的星数.
     * 没写过星、但已经通关的旧档按 1 星算.
     */
    fun campaignStars(level: Int): Int {
        if (level !in 1..DifficultySettings.MAX_LEVEL) return 0
        return CampaignStars.display(readCampaignStarMap()[level], level in campaignPassedLevels())
    }

    fun campaignStarTotal(): Int =
        (1..DifficultySettings.MAX_LEVEL).sumOf { campaignStars(it) }

    /** 记下本关星数, 只升不降, 返回这一关的最高星. */
    fun recordCampaignStars(level: Int, stars: Int): Int {
        if (level !in 1..DifficultySettings.MAX_LEVEL) return 0
        val best = CampaignStars.merge(campaignStars(level), stars)
        if (best <= 0) return 0
        val map = readCampaignStarMap().toMutableMap()
        map[level] = best
        prefs.edit()
            .putStringSet(KEY_CAMPAIGN_STARS, map.map { "${it.key}:${it.value}" }.toSet())
            .apply()
        return best
    }

    fun chronicleStars(battleId: Int): Int =
        CampaignStars.display(readChronicleStarMap()[battleId], passed = false)

    fun recordChronicleStars(battleId: Int, stars: Int): Int {
        if (battleId !in 1..64) return 0
        val best = CampaignStars.merge(chronicleStars(battleId), stars)
        if (best <= 0) return 0
        val map = readChronicleStarMap().toMutableMap()
        map[battleId] = best
        prefs.edit()
            .putStringSet(KEY_CHRONICLE_STARS, map.map { "${it.key}:${it.value}" }.toSet())
            .apply()
        return best
    }

    private fun readChronicleStarMap(): Map<Int, Int> {
        val raw = prefs.getStringSet(KEY_CHRONICLE_STARS, emptySet()) ?: emptySet()
        val out = HashMap<Int, Int>(raw.size)
        for (entry in raw) {
            val parts = entry.split(':')
            if (parts.size != 2) continue
            val id = parts[0].toIntOrNull() ?: continue
            val stars = parts[1].toIntOrNull() ?: continue
            out[id] = stars
        }
        return out
    }

    private fun readCampaignStarMap(): Map<Int, Int> {
        val raw = prefs.getStringSet(KEY_CAMPAIGN_STARS, emptySet()) ?: emptySet()
        val out = HashMap<Int, Int>(raw.size)
        for (entry in raw) {
            val parts = entry.split(':')
            if (parts.size != 2) continue
            val level = parts[0].toIntOrNull() ?: continue
            val stars = parts[1].toIntOrNull() ?: continue
            if (level in 1..DifficultySettings.MAX_LEVEL) out[level] = stars
        }
        return out
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

    // ====== 每日一题 ======

    fun dailyPuzzleId(today: LocalDate = LocalDate.now()): Int =
        DailyPractice.puzzleId(today, Puzzles.all.map { it.id })

    fun dailyPractice(today: LocalDate = LocalDate.now()): DailyPracticeSnapshot =
        DailyPractice.snapshot(today, Puzzles.all.map { it.id }, dailyCompletedDates())

    /** Only the scheduled puzzle, finished on its own local date, can earn a day. */
    fun markDailyPuzzleCompleted(id: Int, challengeDate: LocalDate, today: LocalDate = LocalDate.now()): Boolean {
        val completed = dailyCompletedDates()
        val updated = DailyPractice.recordCompletion(today, challengeDate, id, Puzzles.all.map { it.id }, completed)
        if (updated == completed) return false
        prefs.edit().putStringSet(KEY_DAILY_COMPLETED, updated.map { it.toString() }.toSet()).apply()
        return true
    }

    private fun dailyCompletedDates(): Set<LocalDate> =
        prefs.getStringSet(KEY_DAILY_COMPLETED, emptySet()).orEmpty()
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()

    // ====== 残局速拼 (Puzzle Rush) ======

    var puzzleRushHighScore: Int
        get() = prefs.getInt(KEY_PUZZLE_RUSH_HIGH_SCORE, 0)
        private set(value) = prefs.edit().putInt(KEY_PUZZLE_RUSH_HIGH_SCORE, value).apply()

    /** 记录速拼得分, 若打破纪录返回 true 并更新最高分 */
    fun recordPuzzleRushScore(score: Int): Boolean {
        if (score > puzzleRushHighScore) {
            puzzleRushHighScore = score
            return true
        }
        return false
    }

    private companion object {
        const val PREFS = "progress"
        const val KEY_CAMPAIGN_UNLOCKED = "campaign_unlocked"
        const val KEY_CAMPAIGN_PASSED = "campaign_passed"
        const val KEY_CAMPAIGN_STARS = "campaign_stars"
        const val KEY_CHRONICLE_STARS = "chronicle_stars"
        const val KEY_CAMPAIGN_VERSION = "campaign_schema_version"
        const val KEY_PUZZLE_SOLVED = "puzzle_solved"
        const val KEY_PUZZLE_ATTEMPTS = "puzzle_attempts_"
        const val KEY_DAILY_COMPLETED = "daily_completed_dates"
        const val KEY_PUZZLE_RUSH_HIGH_SCORE = "puzzle_rush_high_score"
    }
}
