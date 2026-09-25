package com.zhongguoxiangqi.data

import java.time.LocalDate

data class DailyPracticeSnapshot(
    val puzzleId: Int,
    val completedToday: Boolean,
    val completedDays: Int,
    val currentStreak: Int,
    val bestStreak: Int,
)

/** Local calendar dates, rather than elapsed 24-hour windows, define a practice day. */
object DailyPractice {
    fun puzzleId(today: LocalDate, puzzleIds: List<Int>): Int {
        require(puzzleIds.isNotEmpty())
        val day = today.toEpochDay() - LocalDate.of(2026, 1, 1).toEpochDay()
        return puzzleIds[Math.floorMod(day, puzzleIds.size.toLong()).toInt()]
    }

    fun recordCompletion(
        today: LocalDate,
        challengeDate: LocalDate,
        solvedPuzzleId: Int,
        puzzleIds: List<Int>,
        completedDates: Set<LocalDate>,
    ): Set<LocalDate> = if (challengeDate == today && solvedPuzzleId == puzzleId(today, puzzleIds))
        completedDates + today else completedDates

    fun snapshot(today: LocalDate, puzzleIds: List<Int>, completedDates: Set<LocalDate>): DailyPracticeSnapshot {
        val dates = completedDates.filter { !it.isAfter(today) }.toSortedSet()
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (date in dates) {
            run = if (previous?.plusDays(1) == date) run + 1 else 1
            best = maxOf(best, run)
            previous = date
        }
        // Yesterday's streak remains active until the player has had today's chance.
        var cursor = if (today in dates) today else today.minusDays(1)
        var current = 0
        while (cursor in dates) {
            current++
            cursor = cursor.minusDays(1)
        }
        return DailyPracticeSnapshot(puzzleId(today, puzzleIds), today in dates, dates.size, current, best)
    }
}
