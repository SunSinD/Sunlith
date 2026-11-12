package io.github.sunsind.sunlith.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodoMarkerTest {
    private val zone = ZoneId.of("America/Toronto")
    private fun event(start: Long = 1000L, allDay: Boolean = false, rule: String? = null) =
        CalendarEvent(7, 3, "Synthetic task", TodoMarker.TOKEN, "", start, start + 900_000L, allDay, zone.id, rule)

    @Test fun tagRoundTripPreservesNotesAndIsIdempotent() {
        listOf("", "Notes", "Notes\n", "Notes  \n\n", "Première étape\r\n").forEach { notes ->
            val tagged = TodoMarker.set(notes, true)
            assertTrue(TodoMarker.has(tagged))
            assertEquals(tagged, TodoMarker.set(tagged, true))
            assertEquals(notes, TodoMarker.set(tagged, false))
            assertEquals(notes, TodoMarker.strip(notes))
        }
    }

    @Test fun googleHtmlAndCaseChangesKeepTheTagButEmbeddedWordsDoNot() {
        listOf("<p>[sunlith:todo]</p>", "Notes<br>[SUNLITH:TODO]<br>", "<span>[sunlith:todo]</span>").forEach { text ->
            assertTrue(TodoMarker.has(text))
            assertFalse(TodoMarker.has(TodoMarker.strip(text)))
        }
        listOf("prefix[sunlith:todo]", "[sunlith:todo]suffix", "[sunlith:todo-extra]").forEach {
            assertFalse(TodoMarker.has(it))
            assertEquals(it, TodoMarker.strip(it))
        }
    }

    @Test fun standaloneTasksStayDoneAfterTimeEditsButSeparateCalendarsDoNotCollide() {
        val first = event()
        assertEquals(TodoIdentity.key(first), TodoIdentity.key(first.copy(startMillis = 9000L)))
        assertNotEquals(TodoIdentity.key(first), TodoIdentity.key(first.copy(calendarId = 4)))
    }

    @Test fun repeatingOccurrenceAndItsMovedExceptionShareCompletion() {
        val occurrence = event(rule = "FREQ=DAILY")
        val movedException = occurrence.copy(id = 8, rrule = null, startMillis = 9000L)
        assertEquals(TodoIdentity.key(occurrence), TodoIdentity.key(movedException, 7, true, 1000L))
        assertNotEquals(TodoIdentity.key(occurrence), TodoIdentity.key(occurrence.copy(startMillis = 2000L)))
    }

    @Test fun allDayTasksActivateAtLocalMidnightAcrossDst() {
        listOf(LocalDate.of(2030, 3, 10), LocalDate.of(2030, 11, 3)).forEach { date ->
            val task = event(CalendarDates.allDayMillis(date), true, "FREQ=DAILY")
            assertEquals(date.atStartOfDay(zone).toInstant().toEpochMilli(), TodoIdentity.availableAt(task, zone))
            assertTrue(TodoIdentity.key(task).endsWith(":$date"))
            assertNotEquals(task.startMillis, TodoIdentity.availableAt(task, zone))
        }
        val timed = event(Instant.parse("2030-11-03T05:30:00Z").toEpochMilli())
        assertEquals(timed.startMillis, TodoIdentity.availableAt(timed, zone))
    }

    @Test fun movingDailySeriesKeepsOccurrenceCompletionAcrossDstAndEndBoundEdits() {
        val first = LocalDate.of(2030, 3, 9)
        val master = draft(first, 9 * 60, "FREQ=DAILY;COUNT=4")
        val moved = draft(first, 9 * 60 + 30, "COUNT=12;FREQ=DAILY")
        val before = occurrence(master, first.plusDays(2), 9 * 60)
        val after = occurrence(moved, first.plusDays(2), 9 * 60 + 30)
        assertEquals(recurringKey(before, master), recurringKey(after, moved))
        assertTrue(recurringKey(before, master).endsWith(":2"))
        assertNotEquals(recurringKey(before, master), recurringKey(occurrence(master, first.plusDays(3), 9 * 60), master))
        val untilBefore = master.copy(rrule = "FREQ=DAILY;UNTIL=20300401T130000Z")
        val untilAfter = moved.copy(rrule = "FREQ=DAILY;UNTIL=20300401T133000Z")
        assertEquals(recurringKey(before, untilBefore), recurringKey(after, untilAfter))
    }

    @Test fun movingAllDaySeriesOneDateKeepsCompletion() {
        val first = LocalDate.of(2030, 6, 1)
        val master = draft(first, 0, "FREQ=DAILY", allDay = true)
        val moved = draft(first.plusDays(1), 0, "FREQ=DAILY", allDay = true)
        assertEquals(recurringKey(occurrence(master, first.plusDays(4), 0), master),
            recurringKey(occurrence(moved, first.plusDays(5), 0), moved))
    }

    @Test fun weeklyDefaultsAndWeekdaySelectorsKeepOrdinalIdentityAfterMoves() {
        val monday = LocalDate.of(2030, 11, 4)
        val weekly = draft(monday, 9 * 60, "FREQ=WEEKLY;INTERVAL=2")
        val shifted = draft(monday.plusDays(1), 10 * 60, "INTERVAL=2;FREQ=WEEKLY")
        assertEquals(recurringKey(occurrence(weekly, monday.plusWeeks(4), 9 * 60), weekly),
            recurringKey(occurrence(shifted, monday.plusWeeks(4).plusDays(1), 10 * 60), shifted))
        val weekdays = draft(monday, 9 * 60, "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        val weekdaysMoved = draft(monday.plusWeeks(1), 9 * 60 + 30, "BYDAY=FR,TH,WE,TU,MO;FREQ=WEEKLY")
        val before = recurringKey(occurrence(weekdays, monday.plusDays(8), 9 * 60), weekdays)
        val after = recurringKey(occurrence(weekdaysMoved, monday.plusDays(15), 9 * 60 + 30), weekdaysMoved)
        assertEquals(before, after)
        assertTrue(before.endsWith(":6"))
    }

    @Test fun monthlyAndYearlyOrdinalsCountSkippedDatesInsteadOfRawPeriods() {
        val monthly = draft(LocalDate.of(2030, 1, 31), 9 * 60, "FREQ=MONTHLY")
        val monthlyMoved = draft(LocalDate.of(2030, 2, 1), 9 * 60, "FREQ=MONTHLY")
        assertEquals(recurringKey(occurrence(monthly, LocalDate.of(2030, 3, 31), 9 * 60), monthly),
            recurringKey(occurrence(monthlyMoved, LocalDate.of(2030, 3, 1), 9 * 60), monthlyMoved))
        val yearly = draft(LocalDate.of(2024, 2, 29), 0, "FREQ=YEARLY", allDay = true)
        val yearlyMoved = draft(LocalDate.of(2024, 2, 28), 0, "FREQ=YEARLY", allDay = true)
        assertEquals(recurringKey(occurrence(yearly, LocalDate.of(2028, 2, 29), 0), yearly),
            recurringKey(occurrence(yearlyMoved, LocalDate.of(2025, 2, 28), 0), yearlyMoved))
    }

    @Test fun exceptionUsesTheOriginalOccurrenceOrdinalAndComplexRulesKeepFallback() {
        val first = LocalDate.of(2030, 3, 9)
        val master = draft(first, 9 * 60, "FREQ=DAILY")
        val original = occurrence(master, first.plusDays(2), 9 * 60)
        val exception = original.copy(id = 9, rrule = null, startMillis = original.startMillis + 900_000L)
        assertEquals(recurringKey(original, master), TodoIdentity.key(exception, 7, true, original.startMillis, false,
            TodoRecurrenceIdentity.from(master)))
        assertNull(TodoRecurrenceIdentity.from(master.copy(rrule = "FREQ=MONTHLY;BYDAY=MO;BYSETPOS=-1")))
        assertNull(TodoRecurrenceIdentity.from(master, extraDates = true))
        assertNotEquals(recurringKey(original, master), recurringKey(original, master.copy(rrule = "FREQ=WEEKLY")))
    }

    private fun draft(date: LocalDate, minute: Int, rule: String, allDay: Boolean = false): EventDraft {
        val start = if (allDay) CalendarDates.allDayMillis(date) else date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        return EventDraft(id = 7, calendarId = 3, title = "Synthetic series", description = TodoMarker.TOKEN,
            startMillis = start, endMillis = start + if (allDay) 86_400_000L else 900_000L,
            allDay = allDay, timeZone = if (allDay) "UTC" else zone.id, rrule = rule)
    }

    private fun occurrence(master: EventDraft, date: LocalDate, minute: Int): CalendarEvent {
        val start = if (master.allDay) CalendarDates.allDayMillis(date) else date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        return CalendarEvent(7, 3, master.title, master.description, "", start, start + 900_000L, master.allDay, master.timeZone, master.rrule)
    }

    private fun recurringKey(event: CalendarEvent, master: EventDraft): String = TodoIdentity.key(event, recurrence = requireNotNull(TodoRecurrenceIdentity.from(master)))
}
