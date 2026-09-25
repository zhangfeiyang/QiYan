package com.zhongguoxiangqi.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DailyPracticeTest {
    private val puzzles = listOf(1, 2, 3, 4, 5, 6, 7)
    private val today = LocalDate.of(2026, 9, 18)

    @Test fun dailyPuzzleIsStableAndRotatesThroughTheWholeLibrary() {
        val todayPuzzle = DailyPractice.puzzleId(today, puzzles)
        assertEquals(todayPuzzle, DailyPractice.puzzleId(today, puzzles))
        val week = (0L..6L).map { DailyPractice.puzzleId(today.plusDays(it), puzzles) }
        assertEquals(puzzles.toSet(), week.toSet())
        assertEquals(todayPuzzle, DailyPractice.puzzleId(today.plusDays(7), puzzles))
    }

    @Test fun datesBeforeScheduleEpochStillSelectAnExistingPuzzle() {
        assertTrue(DailyPractice.puzzleId(LocalDate.of(2020, 1, 1), puzzles) in puzzles)
    }

    @Test fun emptyHistoryNeverInventsProgress() {
        val result = DailyPractice.snapshot(today, puzzles, emptySet())
        assertFalse(result.completedToday)
        assertEquals(0, result.completedDays)
        assertEquals(0, result.currentStreak)
        assertEquals(0, result.bestStreak)
    }

    @Test fun streakRemainsAvailableUntilTheEndOfToday() {
        val yesterday = today.minusDays(1)
        val result = DailyPractice.snapshot(today, puzzles, setOf(yesterday, yesterday.minusDays(1)))
        assertFalse(result.completedToday)
        assertEquals(2, result.currentStreak)
        assertEquals(2, result.completedDays)
    }

    @Test fun oneMissingDayResetsCurrentStreakButKeepsBestAndTotal() {
        val dates = (2L..5L).map { today.minusDays(it) }.toSet() + today
        val result = DailyPractice.snapshot(today, puzzles, dates)
        assertTrue(result.completedToday)
        assertEquals(1, result.currentStreak)
        assertEquals(4, result.bestStreak)
        assertEquals(5, result.completedDays)
    }

    @Test fun repeatedCompletionsCannotAddDays() {
        val puzzleId = DailyPractice.puzzleId(today, puzzles)
        val initial = setOf(today.minusDays(1))
        val once = DailyPractice.recordCompletion(today, today, puzzleId, puzzles, initial)
        val twice = DailyPractice.recordCompletion(today, today, puzzleId, puzzles, once)
        assertEquals(once, twice)
        val result = DailyPractice.snapshot(today, puzzles, twice)
        assertEquals(2, result.completedDays)
        assertEquals(2, result.currentStreak)
    }

    @Test fun challengeFinishedAfterMidnightCannotEarnTheNewDay() {
        val yesterday = today.minusDays(1)
        val oldPuzzle = DailyPractice.puzzleId(yesterday, puzzles)
        val result = DailyPractice.recordCompletion(today, yesterday, oldPuzzle, puzzles, emptySet())
        assertTrue(result.isEmpty())
    }

    @Test fun anotherPuzzleCannotEarnDailyCredit() {
        val wrongPuzzle = puzzles.first { it != DailyPractice.puzzleId(today, puzzles) }
        val result = DailyPractice.recordCompletion(today, today, wrongPuzzle, puzzles, emptySet())
        assertTrue(result.isEmpty())
    }

    @Test fun calendarStreakCrossesLeapDayAndIgnoresFutureClockEntries() {
        val date = LocalDate.of(2024, 3, 1)
        val result = DailyPractice.snapshot(date, puzzles, setOf(
            LocalDate.of(2024, 2, 28), LocalDate.of(2024, 2, 29), date, date.plusDays(1),
        ))
        assertEquals(3, result.currentStreak)
        assertEquals(3, result.completedDays)
        assertEquals(3, result.bestStreak)
    }
}
