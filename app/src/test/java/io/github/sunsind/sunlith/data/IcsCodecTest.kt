package io.github.sunsind.sunlith.data

import biweekly.Biweekly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class IcsCodecTest {
    @Test
    fun unknownTimezoneIsReportedInsteadOfSilentlyChangingTheTime() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:zone@test\r\nDTSTART;TZID=Not_A_Zone:20261105T170000\r\nDTEND;TZID=Not_A_Zone:20261105T180000\r\nSUMMARY:Dentist\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val result = IcsCodec.parse(text.byteInputStream())
        assertTrue(result.events.isEmpty())
        assertTrue(result.messages.any { it.contains("timezone") })
    }

    @Test
    fun allDayDatesRoundTripAcrossDeviceTimezones() {
        val originalTimezone = TimeZone.getDefault()
        try {
            for (zone in listOf("America/Toronto", "Pacific/Honolulu", "Pacific/Auckland")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val date = LocalDate.of(2026, 11, 5)
                val draft = EventDraft(calendarId = 1, title = "Dentist", startMillis = CalendarDates.allDayMillis(date),
                    endMillis = CalendarDates.allDayMillis(date.plusDays(2)), allDay = true, timeZone = "UTC")
                val exported = Biweekly.write(IcsCodec.buildCalendar(listOf(StoredEvent(draft, "all-day@test")))).go()
                assertTrue(exported.contains("DTSTART;VALUE=DATE:20261105"))
                assertTrue(exported.contains("DTEND;VALUE=DATE:20261107"))
                val imported = IcsCodec.parse(exported.byteInputStream()).events.single().draft
                assertEquals(draft.startMillis, imported.startMillis)
                assertEquals(draft.endMillis, imported.endMillis)
                assertTrue(imported.allDay)
            }
        } finally {
            TimeZone.setDefault(originalTimezone)
        }
    }

    @Test
    fun recurringTimezoneAndEscapedTextSurviveExportImport() {
        val start = ZonedDateTime.of(2026, 10, 28, 17, 0, 0, 0, ZoneId.of("America/Toronto")).toInstant().toEpochMilli()
        val draft = EventDraft(calendarId = 1, title = "Dentist, check; follow-up",
            description = "First line\nSecond line with \\ and café", location = "Laval, Québec",
            startMillis = start, endMillis = start + 3_600_000, timeZone = "America/Toronto",
            rrule = "FREQ=WEEKLY;BYDAY=WE;COUNT=4", reminderMinutes = listOf(0, 15, 60))
        val text = Biweekly.write(IcsCodec.buildCalendar(listOf(StoredEvent(draft, "weekly@test")))).go()
        val imported = IcsCodec.parse(text.byteInputStream()).events.single()
        assertEquals("weekly@test", imported.uid)
        assertEquals(draft.title, imported.draft.title)
        assertEquals(draft.description, imported.draft.description)
        assertEquals(draft.location, imported.draft.location)
        assertEquals(draft.startMillis, imported.draft.startMillis)
        assertEquals(draft.timeZone, imported.draft.timeZone)
        assertEquals(draft.reminderMinutes, imported.draft.reminderMinutes)
        assertTrue(imported.draft.rrule!!.contains("BYDAY=WE"))
        val calendar = Biweekly.parse(text).first()
        val occurrences = calendar.events.single().getDateIterator(TimeZone.getTimeZone("America/Toronto"))
        val first = occurrences.next().toInstant().atZone(ZoneId.of("America/Toronto"))
        val second = occurrences.next().toInstant().atZone(ZoneId.of("America/Toronto"))
        assertEquals(17, first.hour)
        assertEquals(17, second.hour)
        assertFalse(first.offset == second.offset)
    }

    @Test
    fun recurrenceExceptionsSkipTheEntireSeries() {
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:series@test
            DTSTART:20261105T170000Z
            DTEND:20261105T180000Z
            SUMMARY:Weekly
            RRULE:FREQ=WEEKLY
            END:VEVENT
            BEGIN:VEVENT
            UID:series@test
            RECURRENCE-ID:20261112T170000Z
            DTSTART:20261112T190000Z
            DTEND:20261112T200000Z
            SUMMARY:Moved occurrence
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val imported = IcsCodec.parse(text.byteInputStream())
        assertEquals(2, imported.total)
        assertTrue(imported.events.isEmpty())
        assertTrue(imported.messages.any { it.contains("exceptions") })
    }

    @Test
    fun foldedUnicodeAndDefaultAllDayDurationImportCorrectly() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:fold@test\r\n" +
            "DTSTART;VALUE=DATE:20261105\r\nSUMMARY:Électro\r\n nique\\, lab\r\n" +
            "DESCRIPTION:First\\nSecond\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val draft = IcsCodec.parse(text.byteInputStream()).events.single().draft
        assertEquals("Électronique, lab", draft.title)
        assertEquals("First\nSecond", draft.description)
        assertEquals(86_400_000L, draft.endMillis - draft.startMillis)
        assertTrue(draft.reminderMinutes.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun largeInputIsRejectedBeforeParsing() {
        IcsCodec.parse(ByteArrayInputStream(ByteArray(IcsCodec.MAX_BYTES + 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteCalendarIsRejected() {
        IcsCodec.parse("BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\n".byteInputStream())
    }

    @Test
    fun canceledEventsAndUnsupportedAlarmTypesAreNotImportedAsActiveReminders() {
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:cancel@test
            DTSTART:20261105T170000Z
            SUMMARY:Canceled
            STATUS:CANCELLED
            END:VEVENT
            BEGIN:VEVENT
            UID:alarm@test
            DTSTART:20261106T170000Z
            SUMMARY:Valid
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=END:-PT10M
            DESCRIPTION:Unsupported end-relative reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val imported = IcsCodec.parse(text.byteInputStream())
        assertEquals(2, imported.total)
        assertEquals(1, imported.events.size)
        assertTrue(imported.events.single().draft.reminderMinutes.isEmpty())
        assertTrue(imported.messages.any { it.contains("advanced alarms") })
    }

    @Test
    fun recurringProviderDurationUsesWholeAllDayDatesAcrossDst() {
        val start = CalendarDates.allDayMillis(LocalDate.of(2026, 10, 31))
        val end = CalendarDates.allDayMillis(LocalDate.of(2026, 11, 3))
        assertEquals("P3D", CalendarDates.duration(start, end, true))
        assertEquals("PT3600S", CalendarDates.duration(0, 3_600_000, false))
        assertEquals(Instant.parse("2026-10-31T00:00:00Z").toEpochMilli(), start)
        assertEquals(end, CalendarDates.durationEndMillis(start, "P3D", true, "America/Toronto"))
        val timedStart = ZonedDateTime.of(2026, 10, 31, 17, 0, 0, 0, ZoneId.of("America/Toronto")).toInstant().toEpochMilli()
        assertEquals(25 * 3_600_000L, CalendarDates.durationEndMillis(timedStart, "P1D", false, "America/Toronto") - timedStart)
        assertEquals(24 * 3_600_000L, CalendarDates.providerDurationEndMillis(timedStart, "P1D") - timedStart)
        assertEquals(7 * 24 * 3_600_000L, CalendarDates.providerDurationEndMillis(timedStart, "P1W") - timedStart)
    }

    @Test
    fun importedRemindersRespectCalendarCountMethodsAndGoogleTimeLimit() {
        val google = ReminderPolicy(maximumCount = 2, alertsAllowed = true, maximumMinutes = 40_320)
        assertEquals(listOf(15, 60), google.accepted(listOf(525_600, 40_320, 60, 15, 60)))
        assertEquals(listOf(40_320), google.accepted(listOf(40_320, 40_321)))
        assertTrue(ReminderPolicy(5, false, 525_600).accepted(listOf(15)).isEmpty())
        assertTrue(ReminderPolicy(0, true, 525_600).accepted(listOf(15)).isEmpty())
    }

    @Test
    fun exportingTimedProviderDurationPreservesElapsedTimeAcrossDst() {
        val start = ZonedDateTime.of(2026, 10, 31, 17, 0, 0, 0, ZoneId.of("America/Toronto")).toInstant().toEpochMilli()
        val draft = EventDraft(calendarId = 1, title = "Daily", startMillis = start,
            endMillis = CalendarDates.providerDurationEndMillis(start, "P1D"), timeZone = "America/Toronto", rrule = "FREQ=WEEKLY")
        val text = Biweekly.write(IcsCodec.buildCalendar(listOf(StoredEvent(draft, "duration@test", originalDuration = "P1D")))).go()
        assertTrue(text.contains("DURATION:PT86400S"))
        assertFalse(text.contains("DTEND"))
        val imported = IcsCodec.parse(text.byteInputStream()).events.single().draft
        assertEquals(draft.startMillis, imported.startMillis)
        assertEquals(draft.endMillis, imported.endMillis)
        assertEquals(24 * 3_600_000L, imported.endMillis - imported.startMillis)
    }

    @Test
    fun nominalDayDurationOnTimedRecurringDstSeriesIsReportedAndSkipped() {
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:nominal@test
            DTSTART;TZID=America/Toronto:20261031T170000
            DURATION:P1D
            RRULE:FREQ=WEEKLY
            SUMMARY:Nominal day
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val rejected = IcsCodec.parse(text.byteInputStream())
        assertEquals(1, rejected.total)
        assertTrue(rejected.events.isEmpty())
        assertTrue(rejected.messages.any { it.contains("day/week durations") })
        val fixedZone = IcsCodec.parse(text.replace("America/Toronto", "UTC").byteInputStream())
        assertEquals(1, fixedZone.events.size)
        assertEquals(24 * 3_600_000L, fixedZone.events.single().draft.endMillis - fixedZone.events.single().draft.startMillis)
        val nonrecurring = IcsCodec.parse(text.replace("RRULE:FREQ=WEEKLY", "").byteInputStream())
        assertEquals(1, nonrecurring.events.size)
        assertEquals(25 * 3_600_000L, nonrecurring.events.single().draft.endMillis - nonrecurring.events.single().draft.startMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedProviderDurationIsRejectedInsteadOfBecomingZeroLength() {
        CalendarDates.providerDurationEndMillis(0, "P1")
    }
}
