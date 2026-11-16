package io.github.sunsind.sunlith.system

import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderNotificationsFormatterTest {
    private val zone = ZoneId.of("America/Toronto")

    @Test
    fun futureIntervalsUseAStableHumanUnit() {
        val start = 1_800_000L
        val event = event(start, start + 3_600_000L)

        assertEquals(ReminderTimingState.FUTURE, reminderTiming(event, 1_740_000L, zone).state)
        assertEquals(60_000L, reminderTiming(event, 1_740_000L, zone).amount.toMillis())
        assertEquals(30_000L, reminderTiming(event, 1_770_000L, zone).amount.toMillis())
        val daysLater = event(start + 2 * 86_400_000L, start + 2 * 86_400_000L + 3_600_000L)
        assertEquals(2 * 86_400_000L, reminderTiming(daysLater, start, zone).amount.toMillis())
    }

    @Test
    fun ongoingAndPastOccurrencesKeepTheirOccurrenceStart() {
        val event = event(10_000L, 130_000L)

        assertEquals(ReminderTimingState.STARTING, reminderTiming(event, 10_001L, zone).state)
        val ongoing = reminderTiming(event, 75_000L, zone)
        assertEquals(ReminderTimingState.ONGOING, ongoing.state)
        assertEquals(65_000L, ongoing.amount.toMillis())
        assertEquals(10_000L, ongoing.startMillis)

        val past = reminderTiming(event, 140_000L, zone)
        assertEquals(ReminderTimingState.PAST, past.state)
        assertEquals(130_000L, past.amount.toMillis())
    }

    @Test
    fun instantEventsArePastAfterTheirStartAndShortEventsAfterTheirEnd() {
        val instant = event(10_000L, 10_000L)
        assertEquals(ReminderTimingState.FUTURE, reminderTiming(instant, 9_999L, zone).state)
        assertEquals(ReminderTimingState.STARTING, reminderTiming(instant, 10_000L, zone).state)
        assertEquals(ReminderTimingState.PAST, reminderTiming(instant, 10_001L, zone).state)
        assertEquals(ReminderTimingState.PAST, reminderTiming(event(10_000L, 20_000L), 20_000L, zone).state)
    }

    @Test
    fun allDayStartUsesItsUtcCalendarDateAtLocalMidnightAcrossDst() {
        val date = LocalDate.of(2026, 11, 1)
        val event = CalendarEvent(
            1L, 1L, "All day", "", "", CalendarDates.allDayMillis(date),
            CalendarDates.allDayMillis(date.plusDays(1)), true, "UTC",
        )
        val timing = reminderTiming(event, date.atStartOfDay(zone).toInstant().toEpochMilli(), zone)

        assertEquals(ReminderTimingState.STARTING, timing.state)
        assertEquals(date.atStartOfDay(zone).toInstant().toEpochMilli(), timing.startMillis)
        assertTrue(timing.startMillis != event.startMillis)
        assertEquals(ReminderTimingState.ONGOING, reminderTiming(event, timing.startMillis + 24 * 3_600_000L + 1_800_000L, zone).state)
        assertEquals(ReminderTimingState.PAST, reminderTiming(event, timing.startMillis + 25 * 3_600_000L, zone).state)
    }

    @Test
    fun countdownRoundsAtHumanUnitBoundariesDespiteAlarmLatency() {
        mapOf(
            59_999L to ReminderDistance(ReminderDistanceUnit.LESS_THAN_MINUTE, 0),
            60_000L to ReminderDistance(ReminderDistanceUnit.MINUTES, 1),
            15 * 60_000L - 1 to ReminderDistance(ReminderDistanceUnit.MINUTES, 15),
            59 * 60_000L + 30_000 to ReminderDistance(ReminderDistanceUnit.HOURS, 1),
            23 * 3_600_000L + 30 * 60_000 to ReminderDistance(ReminderDistanceUnit.DAYS, 1),
            2 * 86_400_000L - 1 to ReminderDistance(ReminderDistanceUnit.DAYS, 2),
        ).forEach { (millis, expected) -> assertEquals("$millis ms", expected, reminderDistance(Duration.ofMillis(millis))) }
    }

    private fun event(start: Long, end: Long) = CalendarEvent(1L, 1L, "Event", "", "", start, end, false, ZoneOffset.UTC.id)
}
