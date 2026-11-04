package io.github.sunsind.sunlith

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.data.IcsTransfer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarRepositoryTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private lateinit var context: Context
    private lateinit var repository: CalendarRepository
    private val ownedCalendars = mutableListOf<Pair<Long, String>>()
    private var calendarId = 0L
    private val zone = ZoneId.of("America/Toronto")

    @Before
    fun setUp() {
        requireEmulator()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = CalendarRepository(context)
        calendarId = createTestCalendar()
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        ownedCalendars.reversed().forEach { (id, account) ->
            context.contentResolver.delete(localCalendarUri(id, account), null, null)
        }
    }

    @Test
    fun localCalendarCreationIsIdempotentAndWritable() {
        val existing = repository.calendars().firstOrNull { it.local && it.account == "Sunlith" && it.writable }
        val first = repository.createLocalCalendar()
        if (existing == null) ownedCalendars += first to "Sunlith"
        assertEquals(first, repository.createLocalCalendar())
        val created = repository.calendars().single { it.id == first }
        assertTrue(created.local)
        assertTrue(created.writable)
        assertTrue(created.visible)
    }

    @Test
    fun timedEventCanBeCreatedReadEditedAndDeleted() {
        val start = LocalDate.of(2027, 2, 12).atTime(17, 0).atZone(zone).toInstant().toEpochMilli()
        val draft = EventDraft(
            calendarId = calendarId,
            title = "Dentist",
            description = "Apporter la carte\nArriver 10 minutes avant.",
            location = "Laval",
            startMillis = start,
            endMillis = start + 3_600_000,
            timeZone = zone.id,
            reminderMinutes = listOf(15, 60),
        )
        val id = repository.saveEvent(draft)
        val saved = repository.getEvent(id)
        assertNotNull(saved)
        assertEquals(draft.copy(id = id), saved)
        val listed = repository.events(start - 1, start + 7_200_000, setOf(calendarId))
        assertEquals(listOf(id), listed.map { it.id })
        assertEquals("Dentist", listed.single().title)

        repository.saveEvent(saved!!.copy(title = "Dentist rescheduled", startMillis = start + 3_600_000, endMillis = start + 7_200_000, reminderMinutes = listOf(30)))
        val edited = repository.getEvent(id)!!
        assertEquals("Dentist rescheduled", edited.title)
        assertEquals(start + 3_600_000, edited.startMillis)
        assertEquals(listOf(30), edited.reminderMinutes)
        repository.deleteEvent(id)
        assertNull(repository.getEvent(id))
        assertTrue(repository.events(start - 1, start + 10_800_000, setOf(calendarId)).isEmpty())
    }

    @Test
    fun allDayEventKeepsExclusiveUtcDatesAcrossDstBoundary() {
        val day = LocalDate.of(2026, 11, 1)
        val start = CalendarDates.allDayMillis(day)
        val end = CalendarDates.allDayMillis(day.plusDays(2))
        val id = repository.saveEvent(EventDraft(
            calendarId = calendarId,
            title = "Lab report due",
            startMillis = start,
            endMillis = end,
            allDay = true,
            timeZone = "UTC",
            reminderMinutes = emptyList(),
        ))
        val actual = repository.getEvent(id)!!
        assertTrue(actual.allDay)
        assertEquals("UTC", actual.timeZone)
        assertEquals(start, actual.startMillis)
        assertEquals(end, actual.endMillis)
        assertEquals(day.plusDays(2), CalendarDates.allDayDate(actual.endMillis))
        val instance = repository.events(start - 86_400_000, end + 86_400_000, setOf(calendarId)).single()
        assertEquals(start, instance.startMillis)
        assertEquals(end, instance.endMillis)
    }

    @Test
    fun weeklyInstancesKeepWallClockTimeAcrossDstAndSeriesEdits() {
        val day = LocalDate.of(2026, 10, 28)
        val start = day.atTime(17, 0).atZone(zone).toInstant().toEpochMilli()
        val id = repository.saveEvent(EventDraft(
            calendarId = calendarId,
            title = "Weekly lab",
            startMillis = start,
            endMillis = start + 3_600_000,
            timeZone = zone.id,
            rrule = "FREQ=WEEKLY;COUNT=3;BYDAY=WE",
            reminderMinutes = emptyList(),
        ))
        val rangeEnd = day.plusWeeks(4).atStartOfDay(zone).toInstant().toEpochMilli()
        val instances = repository.events(start - 1, rangeEnd, setOf(calendarId))
        assertEquals(3, instances.size)
        instances.forEachIndexed { index, instance ->
            val local = Instant.ofEpochMilli(instance.startMillis).atZone(zone)
            assertEquals(day.plusWeeks(index.toLong()), local.toLocalDate())
            assertEquals(17, local.hour)
            assertEquals(3_600_000L, instance.endMillis - instance.startMillis)
        }
        val master = repository.getEvent(id)!!
        repository.saveEvent(master.copy(title = "Updated weekly lab", startMillis = start - 3_600_000, endMillis = start))
        val updated = repository.events(start - 7_200_000, rangeEnd, setOf(calendarId))
        assertEquals(3, updated.size)
        assertTrue(updated.all { it.title == "Updated weekly lab" })
        assertTrue(updated.all { Instant.ofEpochMilli(it.startMillis).atZone(zone).hour == 16 })
        repository.deleteEvent(id)
        assertTrue(repository.events(start - 7_200_000, rangeEnd, setOf(calendarId)).isEmpty())
    }

    @Test
    fun invalidEventDoesNotWriteAnything() {
        val start = Instant.parse("2027-02-12T17:00:00Z").toEpochMilli()
        val draft = EventDraft(calendarId = calendarId, title = "", startMillis = start, endMillis = start + 3_600_000)
        assertThrows(IllegalArgumentException::class.java) { repository.saveEvent(draft) }
        assertThrows(IllegalArgumentException::class.java) { repository.saveEvent(draft.copy(title = "Invalid range", endMillis = start - 1)) }
        assertTrue(repository.events(start - 1, start + 7_200_000, setOf(calendarId)).isEmpty())
    }

    @Test
    fun icsRoundTripPreservesTimedAllDayAndWeeklyEventsAndSkipsDuplicates() {
        val day = LocalDate.of(2027, 2, 12)
        val timed = day.atTime(17, 0).atZone(zone).toInstant().toEpochMilli()
        repository.saveEvent(EventDraft(calendarId = calendarId, title = "Dentist, check-up", description = "Line one\nLine two; accented: é", location = "Laval", startMillis = timed, endMillis = timed + 3_600_000, timeZone = zone.id, reminderMinutes = listOf(30)))
        repository.saveEvent(EventDraft(calendarId = calendarId, title = "Assignment due", startMillis = CalendarDates.allDayMillis(day.plusDays(1)), endMillis = CalendarDates.allDayMillis(day.plusDays(2)), allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
        repository.saveEvent(EventDraft(calendarId = calendarId, title = "Weekly lab", startMillis = timed - 28_800_000, endMillis = timed - 18_000_000, timeZone = zone.id, rrule = "FREQ=WEEKLY;COUNT=3", reminderMinutes = listOf(15)))
        val bytes = ByteArrayOutputStream().also { output ->
            assertEquals(3, IcsTransfer(context, repository).export(output, setOf(calendarId)))
        }.toByteArray()
        assertTrue(bytes.toString(Charsets.UTF_8).contains("BEGIN:VCALENDAR"))

        val destination = createTestCalendar()
        val transfer = IcsTransfer(context, repository)
        val preview = transfer.inspect(ByteArrayInputStream(bytes))
        assertEquals(3, preview.total)
        assertEquals(3, preview.importable)
        val imported = transfer.import(ByteArrayInputStream(bytes), destination)
        assertEquals(3, imported.imported)
        assertEquals(0, imported.duplicates)
        assertEquals(0, imported.skipped)
        val end = day.plusWeeks(4).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val actual = repository.events(timed - 86_400_000, end, setOf(destination))
        assertEquals(5, actual.size)
        assertEquals(setOf("Dentist, check-up", "Assignment due", "Weekly lab"), actual.map { it.title }.toSet())
        val dentist = repository.getEvent(actual.single { it.title == "Dentist, check-up" }.id)!!
        assertEquals("Line one\nLine two; accented: é", dentist.description)
        assertEquals(timed, dentist.startMillis)
        assertEquals(listOf(30), dentist.reminderMinutes)
        val allDay = actual.single { it.title == "Assignment due" }
        assertTrue(allDay.allDay)
        assertEquals(CalendarDates.allDayMillis(day.plusDays(1)), allDay.startMillis)
        assertEquals(CalendarDates.allDayMillis(day.plusDays(2)), allDay.endMillis)
        val again = transfer.import(ByteArrayInputStream(bytes), destination)
        assertEquals(0, again.imported)
        assertEquals(3, again.duplicates)
        assertEquals(5, repository.events(timed - 86_400_000, end, setOf(destination)).size)
    }

    @Test
    fun distinctUidsRemainDistinctAndUidlessFilesStillDeduplicate() {
        val body = "DTSTART:20270212T170000Z\r\nDTEND:20270212T180000Z\r\nSUMMARY:Same details\r\n"
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:first@test\r\n${body}END:VEVENT\r\nBEGIN:VEVENT\r\nUID:second@test\r\n${body}END:VEVENT\r\nEND:VCALENDAR\r\n"
        val transfer = IcsTransfer(context, repository)
        assertEquals(2, transfer.import(text.byteInputStream(), calendarId).imported)
        assertEquals(2, transfer.import(text.byteInputStream(), calendarId).duplicates)
        val withoutUid = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n${body.replace("Same details", "No UID")}END:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals(1, transfer.import(withoutUid.byteInputStream(), calendarId).imported)
        assertEquals(1, transfer.import(withoutUid.byteInputStream(), calendarId).duplicates)
    }

    @Test
    fun exceptionBearingSeriesCannotBeDeletedThroughTheSimpleEditor() {
        val start = Instant.parse("2027-02-12T17:00:00Z").toEpochMilli()
        val id = repository.saveEvent(EventDraft(calendarId = calendarId, title = "Keep exceptions", startMillis = start,
            endMillis = start + 3_600_000L, rrule = "FREQ=WEEKLY;COUNT=3", reminderMinutes = emptyList()))
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply {
            put(Events.EXDATE, "20270219T170000Z")
        }, null, null)
        assertThrows(IllegalArgumentException::class.java) { repository.deleteEvent(id) }
        assertNotNull(repository.getEvent(id))
    }

    @Test
    fun unsupportedRecurrenceExceptionsAreSkippedWithoutFlatteningSeries() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sunlith//Instrumentation//EN
            BEGIN:VEVENT
            UID:unsafe-exception@example.test
            DTSTART:20270212T170000Z
            DTEND:20270212T180000Z
            SUMMARY:Do not flatten this series
            RRULE:FREQ=WEEKLY;COUNT=3
            EXDATE:20270219T170000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n").toByteArray()
        val transfer = IcsTransfer(context, repository)
        val preview = transfer.inspect(ByteArrayInputStream(ics))
        assertEquals(0, preview.importable)
        assertTrue(preview.skipped > 0)
        val result = transfer.import(ByteArrayInputStream(ics), calendarId)
        assertEquals(0, result.imported)
        assertTrue(result.skipped > 0)
        val start = Instant.parse("2027-02-01T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2027-04-01T00:00:00Z").toEpochMilli()
        assertTrue(repository.events(start, end, setOf(calendarId)).isEmpty())
    }

    private fun createTestCalendar(): Long {
        val account = "Sunlith instrumentation ${UUID.randomUUID()}"
        val id = insertLocalCalendar(context, "sunlith-test-${UUID.randomUUID()}", "Sunlith test", account)
        ownedCalendars += id to account
        return id
    }
}

internal fun requireEmulator() {
    check(Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk_gphone")) {
        "These provider tests are restricted to emulators. They must not run against personal calendar data."
    }
}

internal fun insertLocalCalendar(context: Context, name: String, displayName: String, account: String): Long {
    val values = ContentValues().apply {
        put(Calendars.ACCOUNT_NAME, account)
        put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        put(Calendars.NAME, name)
        put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
        put(Calendars.OWNER_ACCOUNT, account)
        put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
        put(Calendars.CALENDAR_COLOR, 0xFF9CA3AF.toInt())
        put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        put(Calendars.VISIBLE, 1)
        put(Calendars.SYNC_EVENTS, 1)
        put(Calendars.ALLOWED_REMINDERS, CalendarContract.Reminders.METHOD_ALERT.toString())
        put(Calendars.MAX_REMINDERS, 5)
    }
    val uri = Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()
    return context.contentResolver.insert(uri, values)?.let(ContentUris::parseId)
        ?: error("The emulator calendar provider could not create the isolated test calendar.")
}

internal fun localCalendarUri(id: Long, account: String) = ContentUris.withAppendedId(Calendars.CONTENT_URI, id).buildUpon()
    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
    .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
    .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
    .build()
