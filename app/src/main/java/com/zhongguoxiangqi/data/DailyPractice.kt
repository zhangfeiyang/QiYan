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
    /**
     * 新手的前几次每日一题只从入门题 (一两步杀) 里出.
     *
     * 全库按日期轮换时, 装好 App 的第一天完全可能轮到一道六步杀 —— 连走子都
     * 还在熟悉的人做不出来, 连续打卡还没开始就断了. 做满这几天再进全库轮换.
     */
    const val STARTER_DAYS = 7

    fun puzzleId(today: LocalDate, puzzleIds: List<Int>): Int {
        require(puzzleIds.isNotEmpty())
        val day = today.toEpochDay() - LocalDate.of(2026, 1, 1).toEpochDay()
        return puzzleIds[Math.floorMod(day, puzzleIds.size.toLong()).toInt()]
    }

    /**
     * 今天的题. 只按"今天以前"完成过几天来决定用哪个题池 —— 一天之内这个数不会变,
     * 早上打开的题晚上做完仍然算数, 做完也不会把今天的题换掉.
     */
    fun puzzleIdFor(
        today: LocalDate,
        puzzleIds: List<Int>,
        starterIds: List<Int>,
        completedDates: Set<LocalDate>,
    ): Int {
        val completedBefore = completedDates.count { it.isBefore(today) }
        return if (completedBefore < STARTER_DAYS && starterIds.isNotEmpty()) puzzleId(today, starterIds)
            else puzzleId(today, puzzleIds)
    }

    fun recordCompletion(
        today: LocalDate,
        challengeDate: LocalDate,
        solvedPuzzleId: Int,
        puzzleIds: List<Int>,
        completedDates: Set<LocalDate>,
        starterIds: List<Int> = emptyList(),
    ): Set<LocalDate> = if (challengeDate == today &&
        solvedPuzzleId == puzzleIdFor(today, puzzleIds, starterIds, completedDates))
        completedDates + today else completedDates

    fun snapshot(
        today: LocalDate,
        puzzleIds: List<Int>,
        completedDates: Set<LocalDate>,
        starterIds: List<Int> = emptyList(),
    ): DailyPracticeSnapshot {
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
        return DailyPracticeSnapshot(
            puzzleIdFor(today, puzzleIds, starterIds, dates), today in dates, dates.size, current, best,
        )
    }
}
