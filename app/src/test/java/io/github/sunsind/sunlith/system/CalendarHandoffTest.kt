package io.github.sunsind.sunlith.system

import io.github.sunsind.sunlith.ui.EditorState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalendarHandoffTest {
    private val zone = ZoneId.of("America/Toronto")

    private fun base() = EditorState(null, 42L, false,
        startDate = LocalDate.of(2026, 11, 5), startTime = LocalTime.of(9, 0),
        endDate = LocalDate.of(2026, 11, 5), endTime = LocalTime.of(10, 0),
        timeZone = zone.id, rrule = null, originalRrule = null, reminders = listOf(15))

    private fun event(begin: String, end: String, allDay: Boolean = false) = IncomingCalendarEvent(
        "Dentist", "Bring insurance card", "Clinic", Instant.parse(begin).toEpochMilli(), Instant.parse(end).toEpochMilli(), allDay)

    @Test
    fun timedHandoffKeepsCalendarRemindersAndExactOvernightInstants() {
        val incoming = event("2026-11-06T04:30:15.123Z", "2026-11-06T06:30:45.456Z")
        val editor = incoming.editor(base(), zone)
        assertEquals(LocalDate.of(2026, 11, 5), editor.startDate)
        assertEquals(LocalDate.of(2026, 11, 6), editor.endDate)
        assertEquals(42L, editor.calendarId)
        assertEquals(listOf(15), editor.reminders)
        val saved = editor.copy(title = "Dentist checkup").toDraft(zone)
        assertEquals(incoming.begin, saved.startMillis)
        assertEquals(incoming.end, saved.endMillis)
    }

    @Test
    fun repeatedFallBackHourKeepsTheIncomingOffsetWhenOnlyTitleChanges() {
        val incoming = event("2026-11-01T06:30:00Z", "2026-11-01T07:30:00Z")
        val editor = incoming.editor(base(), zone)
        assertEquals(LocalTime.of(1, 30), editor.startTime)
        val saved = editor.copy(title = "Changed").toDraft(zone)
        assertEquals(incoming.begin, saved.startMillis)
        assertEquals(incoming.end, saved.endMillis)
    }

    @Test
    fun allDayBoundsStayUtcAndEditorEndBecomesInclusiveInEveryZone() {
        val incoming = event("2026-05-31T00:00:00Z", "2026-06-02T00:00:00Z", true)
        for (name in listOf("America/Vancouver", "Pacific/Kiritimati")) {
            val localZone = ZoneId.of(name)
            val editor = incoming.editor(base(), localZone)
            assertEquals(LocalDate.of(2026, 5, 31), editor.startDate)
            assertEquals(LocalDate.of(2026, 6, 1), editor.endDate)
            val saved = editor.toDraft(localZone)
            assertEquals(incoming.begin, saved.startMillis)
            assertEquals(incoming.end, saved.endMillis)
            assertEquals("UTC", saved.timeZone)
        }
    }

    @Test
    fun invalidOrLocalMidnightAllDayBoundsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            event("2026-05-31T04:00:00Z", "2026-06-01T04:00:00Z", true).validate(zone)
        }
        assertThrows(IllegalArgumentException::class.java) {
            event("2026-05-31T00:00:00Z", "2026-05-31T00:00:00Z").validate(zone)
        }
        assertThrows(IllegalArgumentException::class.java) {
            event("2026-06-01T00:00:00Z", "2026-05-31T00:00:00Z").validate(zone)
        }
    }

    @Test
    fun extremeDatesAndOversizeFieldsCannotReachTheEditor() {
        assertThrows(IllegalArgumentException::class.java) {
            IncomingCalendarEvent("", "", "", Long.MIN_VALUE, Long.MAX_VALUE, false).validate(zone)
        }
        assertThrows(IllegalArgumentException::class.java) {
            event("2026-05-31T00:00:00Z", "2026-06-01T00:00:00Z").copy(title = "x".repeat(1001)).validate(zone)
        }
    }
}
