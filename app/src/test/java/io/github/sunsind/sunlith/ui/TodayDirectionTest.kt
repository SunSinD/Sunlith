package io.github.sunsind.sunlith.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayDirectionTest {
    @Test
    fun touchingAnInterruptedTodayReturnRestoresTheLatestSuppressedDirection() {
        val viewport = AgendaViewport(awaitingToday = true)
        val owner = Any()
        viewport.report(owner, TodayDirection.Down)
        assertNull(viewport.direction)
        viewport.userInteraction()
        assertEquals(TodayDirection.Down, viewport.direction)
        viewport.release(owner)
        assertNull(viewport.direction)
    }
    private val today = LocalDate.of(2026, 11, 7)

    @Test
    fun fiveDayWorkweekAlwaysAnchorsMondayIncludingWeekendSelections() {
        val monday = LocalDate.of(2026, 11, 2)
        val expected = (0L..4L).map { monday.plusDays(it) }
        for (offset in 0L..6L) for (mondayFirst in listOf(false, true)) {
            assertEquals(expected, weekTimelineDates(monday.plusDays(offset), 5, mondayFirst, true))
        }
        assertEquals(expected.map { it.plusWeeks(1) }, weekTimelineDates(monday.plusDays(12), 5, false, true))
        assertEquals(expected.map { it.minusWeeks(1) }, weekTimelineDates(monday.minusDays(1), 5, false, true))
    }

    @Test
    fun workweekToggleLeavesRollingFiveDayAndOtherCountsUnchanged() {
        assertEquals((0L..4L).map { today.plusDays(it) }, weekTimelineDates(today, 5, false, false))
        assertEquals((0L..2L).map { today.plusDays(it) }, weekTimelineDates(today, 3, true, true))
        assertEquals(weekTimelineDates(today, 7, false, false), weekTimelineDates(today, 7, false, true))
        val monday = weekTimelineDates(today, 5, false, true).first()
        assertNull(horizontalTodayDirection(today, monday, monday.plusDays(6)))
        assertEquals(TodayDirection.Next, horizontalTodayDirection(today, monday.minusWeeks(1), monday.minusDays(1)))
    }

    @Test
    fun returningTodayIgnoresTransitionalDirectionsUntilTheTargetIsVisible() {
        val owner = Any()
        val viewport = AgendaViewport(awaitingToday = true)
        listOf(TodayDirection.Up, TodayDirection.Previous, TodayDirection.Down).forEach {
            viewport.report(owner, it)
            assertFalse(viewport.reporting)
            assertNull(viewport.direction)
        }
        viewport.report(owner, null)
        assertTrue(viewport.reporting)
        assertNull(viewport.direction)
        viewport.report(owner, TodayDirection.Down)
        assertEquals(TodayDirection.Down, viewport.direction)
    }

    @Test
    fun deliberateInteractionRestoresDirectionReportingAfterAnInterruptedReturn() {
        val owner = Any()
        val viewport = AgendaViewport(awaitingToday = true)
        viewport.userInteraction()
        viewport.report(owner, TodayDirection.Up)
        assertEquals(TodayDirection.Up, viewport.direction)
        viewport.release(Any())
        assertTrue(viewport.reporting)
        viewport.release(owner)
        assertFalse(viewport.reporting)
    }

    @Test
    fun earlierAndLaterVisibleDatesPointTowardTodayAcrossMonthAndYearBoundaries() {
        assertEquals(TodayDirection.Down, todayDirection(today, today.minusMonths(2), today.minusDays(1)))
        assertEquals(TodayDirection.Up, todayDirection(today, today.plusDays(1), today.plusYears(1)))
    }

    @Test
    fun horizontalPeriodsPointBackToToday() {
        assertEquals(TodayDirection.Next, horizontalTodayDirection(today, today.minusMonths(2), today.minusDays(1)))
        assertEquals(TodayDirection.Previous, horizontalTodayDirection(today, today.plusDays(1), today.plusYears(1)))
        assertNull(horizontalTodayDirection(today, today.minusDays(2), today.plusDays(2)))
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
