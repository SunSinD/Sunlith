package io.github.sunsind.sunlith.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayDirectionTest {
    private val today = LocalDate.of(2026, 11, 7)

    @Test
    fun earlierAndLaterVisibleDatesPointTowardTodayAcrossMonthAndYearBoundaries() {
        assertEquals(TodayDirection.Down, todayDirection(today, today.minusMonths(2), today.minusDays(1)))
        assertEquals(TodayDirection.Up, todayDirection(today, today.plusDays(1), today.plusYears(1)))
    }

    @Test
    fun todayAtEitherEdgeOrInsideACompactEmptyRangeNeedsNoArrow() {
        assertNull(todayDirection(today, today, today.plusDays(6)))
        assertNull(todayDirection(today, today.minusDays(6), today))
        assertNull(todayDirection(today, today.minusWeeks(2), today.plusWeeks(2)))
        assertNull(todayDirection(today, today, today))
    }

    @Test
    fun aSparseEventsViewportBracketingTodayNeedsNoArrowEvenWithoutATodayEvent() {
        assertNull(todayDirection(today, today.minusWeeks(3), today.plusWeeks(3)))
    }
}
