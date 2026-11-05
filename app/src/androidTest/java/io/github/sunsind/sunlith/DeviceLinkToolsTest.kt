package io.github.sunsind.sunlith

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.device.DeviceLink
import io.github.sunsind.sunlith.device.DeviceLinkChange
import io.github.sunsind.sunlith.device.DeviceLinkChangeAction
import io.github.sunsind.sunlith.device.DeviceLinkChangeField
import io.github.sunsind.sunlith.device.DeviceLinkError
import io.github.sunsind.sunlith.device.DeviceLinkHistory
import io.github.sunsind.sunlith.device.DeviceLinkJson
import io.github.sunsind.sunlith.device.DeviceLinkStore
import io.github.sunsind.sunlith.device.DeviceLinkTools
import java.io.File
import java.time.LocalDate
import java.util.TimeZone
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceLinkToolsTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private lateinit var context: Context
    private lateinit var repository: CalendarRepository
    private lateinit var tools: DeviceLinkTools
    private val owned = mutableListOf<Pair<Long, String>>()
    private var allowedId = 0L
    private var readOnlyId = 0L
    private var otherId = 0L
    private val operationNamespace = "test-${UUID.randomUUID()}-"
    private val allowed get() = setOf(allowedId, readOnlyId)

    @Before
    fun setUp() {
        requireEmulator()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = CalendarRepository(context)
        tools = DeviceLinkTools(context, repository)
        allowedId = calendar(Calendars.CAL_ACCESS_OWNER)
        readOnlyId = calendar(Calendars.CAL_ACCESS_READ)
        otherId = calendar(Calendars.CAL_ACCESS_OWNER)
        DeviceLink.clearHistory(context)
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        owned.reversed().forEach { (id, account) -> context.contentResolver.delete(localCalendarUri(id, account), null, null) }
        DeviceLinkStore.clear(context)
        DeviceLink.clearHistory(context)
    }

    @Test
    fun listCalendarsReturnsOnlyTheAllowlist() {
        val result = call("list_calendars", mapOf("limit" to 100))
        val calendars = list(result["calendars"]).map { it as Map<*, *> }
        assertEquals(setOf(allowedId.toString(), readOnlyId.toString()), calendars.map { it["id"] }.toSet())
        assertEquals(false, calendars.single { it["id"] == readOnlyId.toString() }["writable"])
        assertEquals(allowedId.toString(), result["defaultCalendarId"])
        assertNull(result["nextPageToken"])
        val first = call("list_calendars", mapOf("limit" to 1))
        assertEquals("1", first["nextPageToken"])
        assertEquals(1, list(call("list_calendars", mapOf("limit" to 1, "pageToken" to "1"))["calendars"]).size)
    }

    @Test
    fun createReadUpdateDeleteWithEtags() {
        val created = create("lab-crud-0001", "Lab report")
        val id = created["id"] as String
        assertEquals(false, created["reused"])
        assertEquals(mapOf("dateTime" to "2026-07-01T10:00:00-04:00", "timeZone" to "America/Toronto"), created["start"])
        assertEquals(listOf(10, 30), created["reminderMinutes"])
        assertEquals("event", created["scope"])
        assertNull(created["editBlockReason"])
        val read = call("get_event", mapOf("calendarId" to allowedId.toString(), "eventId" to id))
        assertEquals(created["etag"], read["etag"])
        val updated = call("update_event", mapOf("calendarId" to allowedId.toString(), "eventId" to id, "etag" to read["etag"],
            "title" to "Lab report v2", "reminderMinutes" to emptyList<Int>()))
        assertEquals("Lab report v2", updated["title"])
        assertEquals(emptyList<Int>(), updated["reminderMinutes"])
        assertEquals("Lab", read["location"])
        assertEquals("Lab", updated["location"])
        assertNotEquals(read["etag"], updated["etag"])
        expect("revision_mismatch") { call("update_event", mapOf("calendarId" to allowedId.toString(), "eventId" to id, "etag" to read["etag"], "title" to "Stale")) }
        expect("revision_mismatch") { call("delete_event", mapOf("calendarId" to allowedId.toString(), "eventId" to id, "etag" to read["etag"], "confirm" to true)) }
        expect("bad_request") { call("delete_event", mapOf("calendarId" to allowedId.toString(), "eventId" to id, "etag" to updated["etag"])) }
        assertEquals(true, call("delete_event", mapOf("calendarId" to allowedId.toString(), "eventId" to id, "etag" to updated["etag"], "confirm" to true))["deleted"])
        expect("not_found") { call("get_event", mapOf("calendarId" to allowedId.toString(), "eventId" to id)) }
        expect("not_found") { call("delete_event", mapOf("calendarId" to allowedId.toString(), "eventId" to id, "etag" to updated["etag"], "confirm" to true)) }
    }

    @Test
    fun externalChangesInvalidateTheEtag() {
        val created = create("lab-external-01", "External")
        val id = (created["id"] as String).toLong()
        val stored = repository.getEvent(id)!!
        repository.saveEvent(stored.copy(title = "Changed in the app"))
        expect("revision_mismatch") {
            call("update_event", mapOf("calendarId" to allowedId.toString(), "eventId" to id.toString(), "etag" to created["etag"], "title" to "Overwrite"))
        }
        assertEquals("Changed in the app", repository.getEvent(id)!!.title)
    }

    @Test
    fun createRetriesAreIdempotentAndConflictsAreDetected() {
        val first = create("retry-op-0001", "Study session")
        val again = create("retry-op-0001", "Study session")
        assertEquals(first["id"], again["id"])
        assertEquals(true, again["reused"])
        assertEquals(1, repository.storedEvents(setOf(allowedId)).count { it.draft.title == "Study session" })
        expect("operation_conflict") { create("retry-op-0001", "Different title") }
        val sameOpOtherCalendar = runCatching { create("retry-op-0001", "Study session", calendarId = readOnlyId) }.exceptionOrNull() as DeviceLinkError
        assertEquals("calendar_not_allowed", sameOpOtherCalendar.code)
        call("delete_event", mapOf("calendarId" to allowedId.toString(), "eventId" to first["id"], "etag" to first["etag"], "confirm" to true))
        expect("operation_conflict") { create("retry-op-0001", "Study session") }
        assertEquals(0, repository.storedEvents(setOf(allowedId)).count { it.draft.title == "Study session" })
    }

    @Test
    fun operationHashKeepsTextFieldsSeparateEvenWithControlCharacters() {
        val base = mapOf<String, Any?>(
            "calendarId" to allowedId.toString(), "operationId" to "structured-hash-001",
            "title" to "Alpha\u0000Beta", "description" to "Gamma",
            "start" to timed("2026-07-01T10:00:00-04:00"),
            "end" to timed("2026-07-01T11:00:00-04:00"),
        )
        val first = call("create_event", base)
        assertEquals(first["id"], call("create_event", base)["id"])
        expect("operation_conflict") {
            call("create_event", base + mapOf("title" to "Alpha", "description" to "Beta\u0000Gamma"))
        }
    }

    @Test
    fun allowlistAndActualEventCalendarAreBothChecked() {
        val foreign = repository.saveEvent(EventDraft(calendarId = otherId, title = "Private", startMillis = 1_780_000_000_000, endMillis = 1_780_003_600_000,
            timeZone = "UTC", reminderMinutes = emptyList()))
        expect("calendar_not_allowed") { call("get_event", mapOf("calendarId" to otherId.toString(), "eventId" to foreign.toString())) }
        expect("not_found") { call("get_event", mapOf("calendarId" to allowedId.toString(), "eventId" to foreign.toString())) }
        expect("calendar_not_allowed") { call("list_events", range(otherId)) }
        expect("calendar_not_allowed") { create("readonly-op-01", "Nope", calendarId = readOnlyId) }
        expect("calendar_not_allowed") { create("other-op-0001", "Nope", calendarId = otherId) }
        expect("calendar_not_allowed") { call("import_calendar", mapOf("calendarId" to readOnlyId.toString(), "ics" to ics("ro-1"), "confirm" to true)) }
        assertTrue(list(call("list_events", range(allowedId))["events"]).none { (it as Map<*, *>)["title"] == "Private" })
    }

    @Test
    fun offsetsMustMatchTheZoneAndAllDayDatesStayExclusive() {
        expect("bad_request") { create("dst-gap-0001", "Gap", start = timed("2026-03-08T02:30:00-05:00"), end = timed("2026-03-08T03:30:00-04:00")) }
        expect("bad_request") { create("dst-off-0001", "Offset", start = timed("2026-07-01T10:00:00-05:00"), end = timed("2026-07-01T11:00:00-04:00")) }
        val ambiguous = create("dst-back-001", "Fall back", start = timed("2026-11-01T01:30:00-05:00"), end = timed("2026-11-01T02:30:00-05:00"))
        assertEquals("2026-11-01T01:30:00-05:00", (ambiguous["start"] as Map<*, *>)["dateTime"])
        val allDay = create("all-day-0001", "Field trip", start = mapOf("date" to "2026-03-08"), end = mapOf("date" to "2026-03-10"))
        assertEquals(mapOf("date" to "2026-03-08"), allDay["start"])
        assertEquals(mapOf("date" to "2026-03-10"), allDay["end"])
        val stored = repository.getEvent((allDay["id"] as String).toLong())!!
        assertEquals(CalendarDates.allDayMillis(LocalDate.of(2026, 3, 8)), stored.startMillis)
        assertTrue(stored.allDay)
        expect("bad_request") { create("all-day-0002", "Empty", start = mapOf("date" to "2026-03-08"), end = mapOf("date" to "2026-03-08")) }
        expect("bad_request") { create("mixed-00001", "Mixed", start = mapOf("date" to "2026-03-08"), end = timed("2026-03-08T10:00:00-05:00")) }
        val listed = list(call("list_events", mapOf("calendarId" to allowedId.toString(), "timeMin" to "2026-03-07T00:00:00Z",
            "timeMax" to "2026-03-12T00:00:00Z"))["events"]).map { it as Map<*, *> }
        assertEquals(mapOf("date" to "2026-03-10"), listed.single { it["title"] == "Field trip" }["end"])
    }

    @Test
    fun repeatingSeriesNeedSeriesScopeAndListAsMasters() {
        val created = create("series-op-001", "Lab", start = timed("2026-01-05T09:00:00-05:00"), end = timed("2026-01-05T11:00:00-05:00"),
            recurrence = listOf("RRULE:FREQ=WEEKLY;COUNT=4"))
        assertEquals("series", created["scope"])
        assertEquals(listOf("RRULE:FREQ=WEEKLY;COUNT=4"), created["recurrence"])
        val events = list(call("list_events", mapOf("calendarId" to allowedId.toString(), "timeMin" to "2026-01-01T00:00:00-05:00",
            "timeMax" to "2026-02-01T00:00:00-05:00", "query" to "lab"))["events"]).map { it as Map<*, *> }
        assertEquals(4, events.size)
        assertTrue(events.all { it["id"] == created["id"] && it["scope"] == "series" && it["occurrenceStart"] == it["start"] })
        assertEquals(null, events[0].get("description"))
        val page = call("list_events", mapOf("calendarId" to allowedId.toString(), "timeMin" to "2026-01-01T00:00:00-05:00",
            "timeMax" to "2026-02-01T00:00:00-05:00", "limit" to 3))
        assertEquals("3", page["nextPageToken"])
        val base = mapOf("calendarId" to allowedId.toString(), "eventId" to created["id"], "etag" to created["etag"])
        expect("bad_request") { call("update_event", base + ("title" to "Lab moved")) }
        expect("bad_request") { call("update_event", base + ("start" to timed("2026-01-05T10:00:00-05:00"))) }
        val updated = call("update_event", base + mapOf("seriesScope" to "series", "start" to timed("2026-01-05T10:00:00-05:00"),
            "end" to timed("2026-01-05T12:00:00-05:00")))
        assertEquals(mapOf("dateTime" to "2026-01-05T10:00:00-05:00", "timeZone" to "America/Toronto"), updated["start"])
        val summer = list(call("list_events", mapOf("calendarId" to allowedId.toString(), "timeMin" to "2026-01-01T00:00:00-05:00",
            "timeMax" to "2026-02-01T00:00:00-05:00"))["events"]).map { it as Map<*, *> }
        assertTrue(summer.all { ((it["start"] as Map<*, *>)["dateTime"] as String).endsWith("T10:00:00-05:00") })
        val deleteBase = mapOf("calendarId" to allowedId.toString(), "eventId" to created["id"], "etag" to updated["etag"], "confirm" to true)
        expect("bad_request") { call("delete_event", deleteBase) }
        assertEquals(true, call("delete_event", deleteBase + ("seriesScope" to "series"))["deleted"])
    }

    @Test
    fun guestsBlockChanges() {
        val created = create("guest-op-0001", "Meeting")
        val values = ContentValues().apply {
            put(Attendees.EVENT_ID, (created["id"] as String).toLong())
            put(Attendees.ATTENDEE_EMAIL, "guest@example.com")
            put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
            put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
            put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_INVITED)
        }
        context.contentResolver.insert(Attendees.CONTENT_URI, values)
        val read = call("get_event", mapOf("calendarId" to allowedId.toString(), "eventId" to created["id"]))
        assertEquals("guests", read["editBlockReason"])
        assertNotEquals(created["etag"], read["etag"])
        expect("blocked") { call("update_event", mapOf("calendarId" to allowedId.toString(), "eventId" to created["id"], "etag" to read["etag"], "title" to "x")) }
        expect("blocked") { call("delete_event", mapOf("calendarId" to allowedId.toString(), "eventId" to created["id"], "etag" to read["etag"], "confirm" to true)) }
    }

    @Test
    fun importReportsRealCountsAndKeepsUids() {
        val first = call("import_calendar", mapOf("calendarId" to allowedId.toString(), "ics" to ics("import-uid-1"), "confirm" to true))
        assertEquals(1, first["imported"])
        assertEquals(0, first["duplicates"])
        assertEquals(false, first["atomic"])
        val second = call("import_calendar", mapOf("calendarId" to allowedId.toString(), "ics" to ics("import-uid-1"), "confirm" to true))
        assertEquals(0, second["imported"])
        assertEquals(1, second["duplicates"])
        assertTrue(repository.storedEvents(setOf(allowedId)).any { it.uid == "import-uid-1" && it.draft.reminderMinutes == listOf(15) })
        expect("bad_request") { call("import_calendar", mapOf("calendarId" to allowedId.toString(), "ics" to "not a calendar", "confirm" to true)) }
        expect("bad_request") { call("import_calendar", mapOf("calendarId" to allowedId.toString(), "ics" to ics("import-uid-2"))) }
        expect("too_large") { call("import_calendar", mapOf("calendarId" to allowedId.toString(), "ics" to "é".repeat(140_000), "confirm" to true)) }
    }

    @Test
    fun currentTimeDefaultsToThePhoneZone() {
        val now = call("get_current_time", emptyMap())
        assertEquals(TimeZone.getDefault().toZoneId().id, now["timeZone"])
        val utc = call("get_current_time", mapOf("timeZone" to "UTC"))
        assertTrue((utc["localIso"] as String).endsWith("Z"))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(utc["utc"] as String))
        expect("bad_request") { call("get_current_time", mapOf("timeZone" to "Nowhere/City")) }
    }

    @Test
    fun pairingConfigIsExactAndStoresOnlyAHash() {
        assertThrows(IllegalArgumentException::class.java) { DeviceLink.createPairing(context, emptySet(), "127.0.0.1") }
        assertThrows(IllegalArgumentException::class.java) { DeviceLink.createPairing(context, setOf(allowedId), "8.8.8.8") }
        assertThrows(IllegalArgumentException::class.java) { DeviceLink.createPairing(context, setOf(Long.MAX_VALUE), "127.0.0.1") }
        val config = DeviceLinkJson.parse(DeviceLink.createPairing(context, setOf(allowedId, readOnlyId), "127.0.0.1").toByteArray()) as Map<*, *>
        assertEquals(listOf("version", "baseUrl", "certificateSha256", "token"), config.keys.toList())
        assertEquals(1L, config["version"])
        assertEquals("https://127.0.0.1:39393", config["baseUrl"])
        assertTrue(Regex("[0-9a-f]{64}").matches(config["certificateSha256"] as String))
        val token = config["token"] as String
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(token))
        assertTrue(DeviceLink.isPaired(context))
        assertEquals(setOf(allowedId, readOnlyId), DeviceLink.pairedCalendarIds(context))
        assertEquals(setOf(allowedId, readOnlyId), DeviceLinkStore.authorize(context, token)?.calendarIds)
        File(context.noBackupFilesDir, "device-link").walkTopDown().filter { it.isFile }.forEach { assertFalse(it.readText().contains(token)) }
        val rotated = DeviceLinkJson.parse(DeviceLink.createPairing(context, setOf(allowedId), "127.0.0.1").toByteArray()) as Map<*, *>
        assertNull(DeviceLinkStore.authorize(context, token))
        assertEquals(config["certificateSha256"], rotated["certificateSha256"])
        DeviceLink.revoke(context)
        assertFalse(DeviceLink.isPaired(context))
        assertNull(DeviceLinkStore.authorize(context, rotated["token"] as String))
    }

    @Test
    fun historyRecordsOnlyRealChangesWithoutPrivateDetails() {
        val before = DeviceLink.activity.value
        val createArgs = mapOf("calendarId" to allowedId.toString(), "operationId" to "history-op-01",
            "title" to "History lab", "description" to "private-description-text", "location" to "Room A-1",
            "start" to timed("2026-07-01T10:00:00-04:00"), "end" to timed("2026-07-01T11:00:00-04:00"))
        val created = call("create_event", createArgs)
        val id = created["id"] as String
        assertTrue(DeviceLink.activity.value > before)
        assertEquals(true, call("create_event", createArgs)["reused"])
        assertEquals(1, DeviceLink.history(context).size)

        val base = mapOf("calendarId" to allowedId.toString(), "eventId" to id)
        expect("revision_mismatch") { call("update_event", base + mapOf("etag" to "r1-stale", "title" to "Never saved")) }
        expect("bad_request") { call("update_event", base + mapOf("etag" to created["etag"], "reminderMinutes" to listOf(1, 2, 3, 4, 5, 6))) }
        call("update_event", base + mapOf("etag" to created["etag"], "title" to "History lab", "location" to "Room A-1"))
        assertEquals(1, DeviceLink.history(context).size)

        val etag = call("get_event", base)["etag"]
        val updated = call("update_event", base + mapOf("etag" to etag, "title" to "History lab moved", "location" to "Room B-2",
            "start" to timed("2026-07-01T12:00:00-04:00"), "end" to timed("2026-07-01T13:00:00-04:00")))
        expect("revision_mismatch") { call("delete_event", base + mapOf("etag" to etag, "confirm" to true)) }
        call("delete_event", base + mapOf("etag" to updated["etag"], "confirm" to true))
        expect("not_found") { call("delete_event", base + mapOf("etag" to updated["etag"], "confirm" to true)) }

        call("import_calendar", mapOf("calendarId" to allowedId.toString(), "ics" to ics("history-uid-1"), "confirm" to true))
        call("import_calendar", mapOf("calendarId" to allowedId.toString(), "ics" to ics("history-uid-1"), "confirm" to true))
        expect("bad_request") { call("import_calendar", mapOf("calendarId" to allowedId.toString(), "ics" to "not a calendar", "confirm" to true)) }
        expect("calendar_not_allowed") { call("import_calendar", mapOf("calendarId" to readOnlyId.toString(), "ics" to ics("history-uid-2"), "confirm" to true)) }

        val history = DeviceLink.history(context)
        assertEquals(listOf(DeviceLinkChangeAction.IMPORT, DeviceLinkChangeAction.DELETE, DeviceLinkChangeAction.UPDATE, DeviceLinkChangeAction.CREATE),
            history.map { it.action })
        assertTrue(history.zipWithNext().all { (newer, older) -> newer.atMillis >= older.atMillis })
        assertTrue(history.all { it.calendarId == allowedId })
        val (imported, deleted, edit, create) = history
        assertEquals(DeviceLinkChange(DeviceLinkChangeAction.IMPORT, imported.atMillis, allowedId, imported = 1, duplicates = 0, skipped = 0), imported)
        assertEquals(id.toLong(), deleted.eventId)
        assertEquals("History lab moved", deleted.title)
        assertEquals(listOf(DeviceLinkChangeField.TITLE, DeviceLinkChangeField.LOCATION, DeviceLinkChangeField.TIME), edit.fields)
        assertEquals("History lab moved", edit.title)
        assertEquals(id.toLong(), create.eventId)
        assertEquals("History lab", create.title)
        assertTrue(create.fields.isEmpty())

        val saved = File(context.noBackupFilesDir, "device-link/history.json").readText()
        listOf("private-description-text", "Room A-1", "Room B-2", "operationId", "history-op-01", "BEGIN:VCALENDAR", "etag").forEach {
            assertFalse(it, saved.contains(it))
        }
        DeviceLinkHistory.dropCache()
        assertEquals(history, DeviceLink.history(context))

        DeviceLinkStore.pair(context, setOf(allowedId))
        DeviceLink.revoke(context)
        assertEquals(history, DeviceLink.history(context))
    }

    @Test
    fun historyIsBoundedClearableAndSurvivesABadFile() {
        repeat(DeviceLinkHistory.MAX_ENTRIES + 5) {
            DeviceLinkHistory.record(context, DeviceLinkChange(DeviceLinkChangeAction.CREATE, it.toLong(), allowedId, it + 1L, "x".repeat(300)))
        }
        val history = DeviceLink.history(context)
        assertEquals(DeviceLinkHistory.MAX_ENTRIES, history.size)
        assertEquals((DeviceLinkHistory.MAX_ENTRIES + 4).toLong(), history.first().atMillis)
        assertEquals(5L, history.last().atMillis)
        assertEquals(DeviceLinkHistory.MAX_TITLE, history.first().title?.length)
        DeviceLinkHistory.dropCache()
        assertEquals(history, DeviceLink.history(context))

        val high = Char(0xD83D)
        val low = Char(0xDE00)
        val emoji = "$high$low"
        assertEquals("a".repeat(199), DeviceLinkHistory.cleanTitle("a".repeat(199) + emoji))
        assertEquals("ab$emoji", DeviceLinkHistory.cleanTitle("a${low}b$emoji$high"))
        DeviceLinkHistory.record(context, DeviceLinkChange(DeviceLinkChangeAction.DELETE, 1_000, allowedId, 1, "Split $high"))
        DeviceLinkHistory.dropCache()
        assertEquals("Split ", DeviceLink.history(context).first().title)

        DeviceLink.clearHistory(context)
        assertTrue(DeviceLink.history(context).isEmpty())
        DeviceLinkHistory.dropCache()
        assertTrue(DeviceLink.history(context).isEmpty())

        File(context.noBackupFilesDir, "device-link/history.json").apply { parentFile?.mkdirs() }.writeText("{not json")
        DeviceLinkHistory.dropCache()
        assertTrue(DeviceLink.history(context).isEmpty())
        DeviceLinkHistory.record(context, DeviceLinkChange(DeviceLinkChangeAction.DELETE, 7, allowedId, 9, "Kept"))
        DeviceLinkHistory.dropCache()
        assertEquals(listOf(DeviceLinkChange(DeviceLinkChangeAction.DELETE, 7, allowedId, 9, "Kept")), DeviceLink.history(context))
    }

    @Test
    fun strictArgumentsAreRejected() {
        expect("bad_request") { call("get_event", mapOf("calendarId" to allowedId.toString(), "eventId" to "01")) }
        expect("bad_request") { call("get_event", mapOf("calendarId" to allowedId, "eventId" to "1")) }
        expect("bad_request") { call("get_event", mapOf("calendarId" to allowedId.toString(), "eventId" to "1", "extra" to true)) }
        expect("bad_request") { call("create_event", mapOf("calendarId" to allowedId.toString(), "title" to "x", "start" to timed("2026-07-01T10:00:00-04:00"), "end" to timed("2026-07-01T11:00:00-04:00"))) }
        expect("bad_request") { create("bad op id!", "x") }
        expect("bad_request") { call("list_events", range(allowedId) + ("eventTypes" to listOf("focusTime"))) }
        expect("bad_request") { call("list_events", mapOf("calendarId" to allowedId.toString(), "timeMin" to "2026-01-01T00:00:00Z", "timeMax" to "2027-01-03T00:00:00Z")) }
        expect("bad_request") { call("update_event", mapOf("calendarId" to allowedId.toString(), "eventId" to "1", "etag" to "x")) }
        expect("bad_request") { call("update_event", mapOf("calendarId" to allowedId.toString(), "eventId" to "1", "etag" to "x", "start" to timed("2026-07-01T10:00:00-04:00"))) }
    }

    private fun call(tool: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val operationId = arguments["operationId"] as? String
        val isolated = if (operationId == null) arguments else arguments + ("operationId" to operationNamespace + operationId)
        return tools.call(tool, isolated, allowed)
    }

    private fun create(
        operationId: String,
        title: String,
        calendarId: Long = allowedId,
        start: Map<String, String> = timed("2026-07-01T10:00:00-04:00"),
        end: Map<String, String> = timed("2026-07-01T11:00:00-04:00"),
        recurrence: List<String>? = null,
    ): Map<String, Any?> = call("create_event", buildMap<String, Any?> {
        put("calendarId", calendarId.toString())
        put("operationId", operationId)
        put("title", title)
        put("location", "Lab")
        put("start", start)
        put("end", end)
        put("reminderMinutes", listOf(30, 10))
        recurrence?.let { put("recurrence", it) }
    })

    private fun timed(value: String) = mapOf("dateTime" to value, "timeZone" to "America/Toronto")

    private fun range(calendarId: Long) = mapOf("calendarId" to calendarId.toString(), "timeMin" to "2026-01-01T00:00:00Z", "timeMax" to "2026-12-31T00:00:00Z")

    private fun ics(uid: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\nBEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\n" +
        "DTSTART:20260410T140000Z\r\nDTEND:20260410T150000Z\r\nSUMMARY:Imported lab\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\n" +
        "TRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    private fun list(value: Any?): List<*> = value as List<*>

    private fun expect(code: String, block: () -> Unit) {
        val error = assertThrows(DeviceLinkError::class.java) { block() }
        assertEquals(code, error.code)
    }

    private fun calendar(access: Int): Long {
        val account = "Sunlith link test ${UUID.randomUUID()}"
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "sunlith-link-${UUID.randomUUID()}")
            put(Calendars.CALENDAR_DISPLAY_NAME, "Sunlith link test")
            put(Calendars.OWNER_ACCOUNT, account)
            put(Calendars.CALENDAR_ACCESS_LEVEL, access)
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
        val id = context.contentResolver.insert(uri, values)?.let(ContentUris::parseId) ?: error("Could not create the test calendar.")
        owned += id to account
        return id
    }
}
