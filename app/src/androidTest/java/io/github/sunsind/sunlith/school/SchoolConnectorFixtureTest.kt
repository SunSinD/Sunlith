package io.github.sunsind.sunlith.school

import android.Manifest
import android.content.ContentUris
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EditBlockReason
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.data.SchoolAbsenceCoverage
import io.github.sunsind.sunlith.data.projectSchoolEvents
import io.github.sunsind.sunlith.requireEmulator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.cert.Certificate
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchoolConnectorFixtureTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private lateinit var context: Context
    private lateinit var directory: File
    private var calendar = -1L
    private lateinit var account: String
    private val day = LocalDate.of(2030, 10, 7)
    private val zone = ZoneId.of("America/Toronto")
    private val term = SchoolTerm("TEST", "Synthetic term", day, day.plusDays(13))

    @Before fun fixture() {
        requireEmulator()
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.noBackupFilesDir, "school-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        account = "school-fixture-${UUID.randomUUID()}"
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account); put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, account); put(Calendars.CALENDAR_DISPLAY_NAME, "School synthetic fixture")
            put(Calendars.OWNER_ACCOUNT, account); put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.VISIBLE, 1); put(Calendars.SYNC_EVENTS, 1); put(Calendars.CALENDAR_TIME_ZONE, "UTC")
            put(Calendars.ALLOWED_REMINDERS, "1"); put(Calendars.MAX_REMINDERS, 5)
        }
        calendar = ContentUris.parseId(context.contentResolver.insert(calendarUri(), values)!!)
    }
    @After fun cleanup() {
        if (calendar >= 0) context.contentResolver.delete(ContentUris.withAppendedId(calendarUri(), calendar), null, null)
        directory.deleteRecursively()
    }
    private fun calendarUri() = Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account).appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
    private fun source(id: String = "agenda-1", date: LocalDate = day) = SchoolItem(id, "synthetic-class-key", "Full synthetic class title", "123ABC45", "000123", "2", "01", date,
        date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), false,
        zone.id, listOf("Synthetic Teacher"), listOf("A100"), emptyList(), false)
    private fun record() = SchoolRecord("https://example.invalid/colnet/mobile/", "synthetic-user", "synthetic-password", zone.id, calendar,
        "synthetic-account-hash", UUID.randomUUID().toString(), "synthetic-token", Long.MAX_VALUE, calendarIdentity = SchoolCalendarWriter(context).calendarIdentity(calendar))
    private fun feed(items: List<SchoolItem>) = SchoolFeed("synthetic-account-hash", term, items)

    @Test fun presentationGroupsPreservedPastRowsWithoutChangingProviderEvents() {
        val first = source().copy(teacherAbsent = true)
        val second = source("agenda-2").copy(startMillis = first.endMillis + 5 * 60_000,
            endMillis = first.endMillis + 55 * 60_000, rooms = listOf("B200"))
        val writer = SchoolCalendarWriter(context)
        val initial = record()
        val ledger = writer.commit(initial, writer.plan(initial, feed(listOf(first, second)), day))
        val connected = initial.copy(ledger = ledger, initialized = true, baseline = listOf(first, second))
        SchoolStore(context).save(connected)
        val personalId = savePersonal(first)
        val repo = CalendarRepository(context)
        val raw = repo.events(first.startMillis - 60_000, second.endMillis + 60_000, setOf(calendar), school = true)
        assertTrue(repo.events(first.startMillis - 60_000, second.endMillis + 60_000, setOf(calendar)).all { it.school == null })
        assertEquals(3, raw.size)
        assertNull(raw.single { it.id == personalId }.school)
        assertEquals(2, raw.count { it.school != null })
        assertEquals(first.endMillis, repo.getEvent(ledger.first().rowId)!!.endMillis)
        val projected = projectSchoolEvents(raw, zone)
        assertEquals(2, projected.size)
        val course = projected.single { it.school != null }
        assertEquals(first.startMillis, course.startMillis)
        assertEquals(second.endMillis, course.endMillis)
        assertEquals(SchoolAbsenceCoverage.PARTIAL, course.school!!.absenceCoverage)
        assertEquals(2, course.school.periods.size)
        assertEquals("A100; B200", course.location)
        assertEquals(first.endMillis, repo.getEvent(ledger.first().rowId)!!.endMillis)
        val pastPlan = writer.plan(connected, feed(listOf(first.copy(teacherAbsent = false), second)), day.plusDays(1))
        assertEquals(ledger, pastPlan.after)
        assertEquals(raw, repo.events(first.startMillis - 60_000, second.endMillis + 60_000, setOf(calendar), school = true))
    }

    @Test fun presentationMetadataRequiresUnchangedOwnedContentAndIdentity() {
        val writer = SchoolCalendarWriter(context)
        val initial = record()
        val item = source()
        val ledger = writer.commit(initial, writer.plan(initial, feed(listOf(item)), day))
        SchoolStore(context).save(initial.copy(ledger = ledger, initialized = true, baseline = listOf(item)))
        val repo = CalendarRepository(context)
        fun shown() = repo.events(item.startMillis - 60_000, item.endMillis + 60_000, setOf(calendar), school = true).single()
        assertNotNull(shown().school)
        val row = ContentUris.withAppendedId(Events.CONTENT_URI, ledger.single().rowId)
        context.contentResolver.update(row, ContentValues().apply { put(Events.TITLE, "Edited synthetic title") }, null, null)
        assertNull(shown().school)
        context.contentResolver.update(row, ContentValues().apply {
            put(Events.TITLE, ledger.single().revision[Events.TITLE])
            put(Events.UID_2445, "foreign-synthetic-uid")
        }, null, null)
        assertNull(shown().school)
    }
    private fun savePersonal(item: SchoolItem = source(), description: String = "Personal notes") = CalendarRepository(context).saveEvent(EventDraft(
        calendarId = calendar, title = "Personal event", description = description, startMillis = item.startMillis, endMillis = item.endMillis, timeZone = zone.id, reminderMinutes = emptyList()))
    private fun markReleased(id: Long, item: SchoolItem = source(), sourceAccount: String = "synthetic-account-hash") {
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply {
            put(Events.CUSTOM_APP_PACKAGE, context.packageName)
            put(Events.CUSTOM_APP_URI, "sunlith-school://released/$sourceAccount/${schoolHash(item.id)}")
        }, null, null)
    }

    @Test fun ownershipBlocksOnlySchoolRowsAndDisconnectReleasesMarkers() {
        val writer = SchoolCalendarWriter(context); var current = record(); val source = source()
        val personal = savePersonal(source.copy(startMillis = source.startMillis + 1000, endMillis = source.endMillis + 1000))
        val personalRevision = CalendarRepository(context).revision(personal)
        val plan = writer.plan(current, feed(listOf(source)), day)
        SchoolStore(context).save(current.copy(journal = plan))
        current = current.copy(ledger = writer.commit(current, plan), baseline = listOf(source), initialized = true)
        SchoolStore(context).save(current)
        val repository = CalendarRepository(context); val school = current.ledger.single().rowId
        assertEquals(personalRevision, repository.revision(personal))
        assertEquals(EditBlockReason.SCHOOL_MANAGED, repository.editBlockReason(school))
        assertNull(repository.editBlockReason(personal))
        assertThrows(IllegalArgumentException::class.java) { repository.saveEvent(repository.getEvent(school)!!.copy(title = "Unsafe edit")) }
        assertThrows(IllegalArgumentException::class.java) { repository.deleteEvent(school) }
        assertThrows(IllegalArgumentException::class.java) { repository.updateIfUnchanged(repository.getEvent(school)!!.copy(title = "Unsafe import edit"), repository.revision(school)!!) }
        assertThrows(IllegalArgumentException::class.java) { repository.deleteIfUnchanged(school, repository.revision(school)!!) }
        val stable = writer.plan(current, feed(listOf(source)), day)
        assertEquals(SchoolChangeSummary(0, 0, 0), writer.summary(current, stable))
        assertEquals(current.ledger, writer.commit(current, stable))
        writer.disconnect(current, false)
        writer.disconnect(current, false)
        SchoolStore(context).clear()
        assertNull(repository.editBlockReason(school))
        assertEquals(personalRevision, repository.revision(personal))
        repository.saveEvent(repository.getEvent(personal)!!.copy(title = "Still editable"))
    }
    @Test fun exactLegacyLinkIsReviewedAndPreservesUid() {
        val writer = SchoolCalendarWriter(context); val current = record(); val item = source()
        val imported = savePersonal(item, "${item.course}-${item.group}")
        val uid = CalendarRepository(context).revision(imported)!![Events.UID_2445]
        val plan = writer.plan(current, feed(listOf(item)), day)
        assertEquals(SchoolChangeSummary(0, 0, 0, 1), writer.summary(current, plan))
        assertNull(CalendarRepository(context).revision(imported)!![Events.CUSTOM_APP_URI])
        val ledger = writer.commit(current, plan)
        assertEquals(imported, ledger.single().rowId)
        assertEquals(uid, ledger.single().uid)
        assertEquals(uid, CalendarRepository(context).revision(imported)!![Events.UID_2445])
    }
    @Test fun releasedProvenanceCanBeReviewedAndStaleReviewIsRecoverablyAborted() {
        val writer = SchoolCalendarWriter(context); val current = record(); val item = source()
        val released = savePersonal(item)
        markReleased(released, item)
        val repository = CalendarRepository(context)
        val uid = repository.revision(released)!![Events.UID_2445]
        val items = listOf(item, source("new-with-release", day.plusDays(1)))
        val plan = writer.plan(current, feed(items), day)
        assertEquals(1, plan.linked.size)
        repository.saveEvent(repository.getEvent(released)!!.copy(title = "Outside edit after release review"))
        assertThrows(Exception::class.java) { writer.commit(current, plan) }
        assertEquals(1, repository.storedEvents(setOf(calendar)).size)
        assertTrue(writer.baselineUnchanged(current.copy(journal = plan)))
        assertNull(writer.verify(current, plan, allowContentChanges = true))
        val retry = writer.plan(current, feed(items), day)
        val linked = writer.commit(current, retry).single { it.item.id == item.id }
        assertEquals(released, linked.rowId)
        assertEquals(uid, linked.uid)
    }
    @Test fun staleLegacyRevisionAbortsWholeBatch() {
        val writer = SchoolCalendarWriter(context); val current = record(); val item = source()
        val imported = savePersonal(item, "${item.course}-${item.group}")
        val plan = writer.plan(current, feed(listOf(item, source("new-source-id").copy(startMillis = item.startMillis + 86_400_000, endMillis = item.endMillis + 86_400_000, date = day.plusDays(1)))), day)
        val repository = CalendarRepository(context)
        repository.saveEvent(repository.getEvent(imported)!!.copy(title = "Changed after review"))
        assertThrows(Exception::class.java) { writer.commit(current, plan) }
        assertEquals("Changed after review", repository.getEvent(imported)!!.title)
        assertNull(repository.revision(imported)!![Events.CUSTOM_APP_URI])
        assertEquals(1, repository.storedEvents(setOf(calendar)).size)
    }
    @Test fun ambiguousLegacyMatchesAreNotAdopted() {
        val item = source(); savePersonal(item, "${item.course}-${item.group}"); savePersonal(item, "${item.course}-${item.group}")
        val failure = assertThrows(SchoolFailure::class.java) { SchoolCalendarWriter(context).plan(record(), feed(listOf(item)), day) }
        assertEquals(SchoolError.LEGACY_COLLISION, failure.reason)
    }
    @Test fun historyAndPersonalRowsSurviveFutureRemovalAndInterruptedCommitCanBeRecovered() {
        val writer = SchoolCalendarWriter(context); var current = record()
        val past = source("past", day); val future = source("future", day.plusDays(1))
        val personal = savePersonal(future.copy(startMillis = future.startMillis + 1000, endMillis = future.endMillis + 1000))
        val first = writer.plan(current, feed(listOf(past, future)), day)
        current = current.copy(ledger = writer.commit(current, first), baseline = listOf(past, future), initialized = true)
        val next = writer.plan(current, feed(emptyList()), day.plusDays(1))
        SchoolStore(context).save(current.copy(journal = next))
        writer.commit(current, next)
        val durable = SchoolStore(context).load()!!
        assertNotNull(writer.verify(durable, durable.journal!!))
        assertEquals(1, writer.verify(durable, durable.journal!!)!!.size)
        assertNotNull(CalendarRepository(context).getEvent(personal))
        assertNotNull(CalendarRepository(context).getEvent(current.ledger.first { it.item.id == "past" }.rowId))
    }
    @Test fun recycledIdOrForeignMetadataNeverMatchesOwnership() {
        val writer = SchoolCalendarWriter(context); val current = record(); val plan = writer.plan(current, feed(listOf(source())), day)
        val owned = writer.commit(current, plan).single()
        assertTrue(SchoolCalendarWriter.identity(owned, calendar, owned.revision, context.packageName))
        assertFalse(SchoolCalendarWriter.identity(owned, calendar, owned.revision + (Events.UID_2445 to "different-personal-uid"), context.packageName))
        assertFalse(SchoolCalendarWriter.identity(owned, calendar, owned.revision + (Events.CALENDAR_ID to "999999"), context.packageName))
        assertFalse(SchoolCalendarWriter.identity(owned, calendar, owned.revision + (Events.CUSTOM_APP_URI to "foreign://marker"), context.packageName))
    }
    @Test fun fullTermLegacyLinkFitsOneAtomicProviderBatch() {
        val items = (0 until 179).map { index ->
            val date = day.plusDays((index / 13).toLong())
            val start = date.atTime(8, 0).plusMinutes((index % 13) * 30L).atZone(zone).toInstant().toEpochMilli()
            source("synthetic-$index", date).copy(classKey = "class-$index", startMillis = start, endMillis = start + 20 * 60_000L)
        }
        val operations = ArrayList(items.mapIndexed { index, item ->
            ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(ContentValues().apply {
                put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "Synthetic imported class $index")
                put(Events.DESCRIPTION, "${item.course}-${item.group}"); put(Events.UID_2445, "synthetic-import-$index")
                put(Events.DTSTART, item.startMillis); put(Events.DTEND, item.endMillis); put(Events.ALL_DAY, 0)
                put(Events.EVENT_TIMEZONE, zone.id); put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ALARM, 0)
            }).build()
        })
        context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val writer = SchoolCalendarWriter(context); val current = record(); val plan = writer.plan(current, feed(items), day)
        assertEquals(179, writer.summary(current, plan).linked)
        assertEquals(179, writer.commit(current, plan).size)
        assertEquals(179, CalendarRepository(context).storedEvents(setOf(calendar)).size)
    }
    @Test fun removeSchoolEventsNeverDeletesCalendarOrPersonalRows() {
        val item = source(); val personal = savePersonal(item.copy(startMillis = item.startMillis + 1000, endMillis = item.endMillis + 1000))
        val writer = SchoolCalendarWriter(context); var current = record()
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(listOf(item)), day)))
        writer.disconnect(current, true)
        assertNotNull(CalendarRepository(context).getEvent(personal))
        assertNull(CalendarRepository(context).getEvent(current.ledger.single().rowId))
        assertTrue(writer.calendars().any { it.id == calendar })
    }
    @Test fun staleLedgerNeverDeletesReplacementWithDifferentUid() {
        val writer = SchoolCalendarWriter(context); var current = record()
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(listOf(source())), day)))
        val id = current.ledger.single().rowId
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply {
            put(Events.UID_2445, "replacement-personal-uid"); put(Events.TITLE, "Replacement personal row")
        }, null, null)
        writer.disconnect(current, true)
        assertEquals("Replacement personal row", CalendarRepository(context).getEvent(id)!!.title)
    }
    @Test fun syncAdapterMetadataDoesNotBlockRefreshOrOverwriteReminders() {
        val writer = SchoolCalendarWriter(context); var current = record(); val item = source()
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(listOf(item)), day)), initialized = true)
        val id = current.ledger.single().rowId
        val plan = writer.plan(current, feed(listOf(item.copy(title = "Updated by school"))), day)
        context.contentResolver.update(syncEventUri(id), ContentValues().apply {
            put(Events._SYNC_ID, "uploaded-by-synthetic-adapter"); put(Events.HAS_ATTENDEE_DATA, 1)
        }, null, null)
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id); put(CalendarContract.Reminders.MINUTES, 10)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        current = current.copy(ledger = writer.commit(current, plan))
        assertEquals("Updated by school", CalendarRepository(context).getEvent(id)!!.title)
        assertEquals("1", CalendarRepository(context).revision(id)!![Events.HAS_ALARM])
        val unchanged = writer.plan(current, feed(listOf(item.copy(title = "Updated by school"))), day)
        assertEquals(SchoolChangeSummary(0, 0, 0), writer.summary(current, unchanged))
        writer.disconnect(current, true)
        assertNull(CalendarRepository(context).getEvent(id))
    }
    @Test fun outsideContentEditIsCorrectedOnlyForFutureOwnedEvents() {
        val writer = SchoolCalendarWriter(context); var current = record()
        val past = source("past", day.minusDays(1)); val future = source("future")
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(listOf(past, future)), day)), initialized = true)
        current.ledger.forEach { owned -> context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, owned.rowId),
            ContentValues().apply { put(Events.TITLE, "Edited in another app") }, null, null) }
        val plan = writer.plan(current, feed(listOf(past, future)), day)
        val ledger = writer.commit(current, plan)
        assertEquals("Edited in another app", CalendarRepository(context).getEvent(ledger.first { it.item.id == "past" }.rowId)!!.title)
        assertEquals(future.title, CalendarRepository(context).getEvent(ledger.first { it.item.id == "future" }.rowId)!!.title)
    }
    @Test fun disconnectRemoveReleasesOutsideEditedEventAndRemovesUnchangedSchoolEvent() {
        val writer = SchoolCalendarWriter(context); var current = record()
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(listOf(source("edited"), source("unchanged", day.plusDays(1)))), day)))
        val edited = current.ledger.first { it.item.id == "edited" }.rowId
        val unchanged = current.ledger.first { it.item.id == "unchanged" }.rowId
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, edited), ContentValues().apply { put(Events.TITLE, "Keep my outside edit") }, null, null)
        writer.disconnect(current, true)
        assertEquals("Keep my outside edit", CalendarRepository(context).getEvent(edited)!!.title)
        assertTrue(CalendarRepository(context).revision(edited)!![Events.CUSTOM_APP_URI]!!.startsWith("sunlith-school://released/"))
        assertNull(CalendarRepository(context).getEvent(unchanged))
        writer.disconnect(current, true)
    }
    @Test fun journalRecoveryRecognizesOwnedRowsAfterOutsideEditButRejectsLostIdentity() {
        val writer = SchoolCalendarWriter(context); val current = record()
        val plan = writer.plan(current, feed(listOf(source())), day)
        val owned = writer.commit(current, plan).single()
        context.contentResolver.update(syncEventUri(owned.rowId), ContentValues().apply { put(Events._SYNC_ID, "uploaded"); put(Events.TITLE, "Edited after commit") }, null, null)
        assertNull(writer.verify(current, plan))
        assertFalse(writer.baselineUnchanged(current.copy(journal = plan)))
        assertEquals("Edited after commit", writer.verify(current, plan, allowContentChanges = true)!!.single().revision[Events.TITLE])
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, owned.rowId), ContentValues().apply { put(Events.UID_2445, "replacement-personal-uid") }, null, null)
        assertNull(writer.verify(current, plan, allowContentChanges = true))
        val replacement = CalendarRepository(context).revision(owned.rowId)
        val next = writer.plan(current.copy(ledger = listOf(owned), initialized = true), feed(listOf(source())), day)
        assertEquals(SchoolChangeSummary(1, 0, 0), writer.summary(current, next))
        val recreated = writer.commit(current, next).single()
        assertNotEquals(owned.marker, recreated.marker)
        assertNotEquals(owned.rowId, recreated.rowId)
        assertEquals(replacement, CalendarRepository(context).revision(owned.rowId))
    }
    @Test fun outsideContentEditAfterPlanningStillAbortsTheWholeBatch() {
        val writer = SchoolCalendarWriter(context); var current = record(); val item = source()
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(listOf(item)), day)))
        val id = current.ledger.single().rowId
        val plan = writer.plan(current, feed(listOf(item.copy(title = "School update"), source("new", day.plusDays(1)))), day)
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.TITLE, "Changed after planning") }, null, null)
        assertThrows(Exception::class.java) { writer.commit(current, plan) }
        assertEquals("Changed after planning", CalendarRepository(context).getEvent(id)!!.title)
        assertEquals(1, CalendarRepository(context).storedEvents(setOf(calendar)).size)
        assertTrue(writer.baselineUnchanged(current.copy(journal = plan)))
    }
    @Test fun twoFullTermsPruneRepeatablyAndDisconnectWithinActualParcelBounds() {
        val batches = mutableListOf<ArrayList<ContentProviderOperation>>()
        val writer = SchoolCalendarWriter(context) { operations ->
            assertTrue(SchoolCalendarWriter.batchBytes(operations) <= 800_000)
            batches += ArrayList(operations)
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        }
        fun items(prefix: String, start: LocalDate) = (0 until 300).map { index ->
            val item = source("$prefix-$index", start.plusDays((index / 25).toLong()))
            item.copy(startMillis = item.startMillis + index % 25 * 60_000L, endMillis = item.endMillis + index % 25 * 60_000L)
        }
        val first = items("first", day)
        var current = record()
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(first), day)), initialized = true)
        assertEquals(1, batches.size)
        assertEquals(302, batches.single().size)
        val revised = first.mapIndexed { index, item -> if (index == 0) item.copy(title = "One changed school item") else item }
        val update = writer.plan(current, feed(revised), day)
        val untouched = current.ledger.last().rowId
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, untouched), ContentValues().apply { put(Events.TITLE, "Outside edit to an untouched row") }, null, null)
        current = current.copy(ledger = writer.commit(current, update))
        assertEquals(3, batches.last().size)
        assertEquals("Outside edit to an untouched row", CalendarRepository(context).getEvent(untouched)!!.title)
        val historicalId = current.ledger.first().rowId
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, historicalId); put(CalendarContract.Reminders.MINUTES, 17)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        val repository = CalendarRepository(context)
        val historyBefore = current.ledger.associate { it.rowId to repository.revision(it.rowId)!! }
        val secondStart = day.plusDays(180)
        val secondTerm = SchoolTerm("SECOND", "Synthetic next term", secondStart, secondStart.plusDays(13))
        var committedChunks = 0
        val interrupted = SchoolCalendarWriter(context) { operations ->
            assertTrue(SchoolCalendarWriter.batchBytes(operations) <= 800_000)
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
            if (++committedChunks == 1) throw IllegalStateException("Synthetic crash after a committed history chunk")
        }
        assertThrows(IllegalStateException::class.java) { interrupted.pruneHistory(current, secondTerm, secondStart) }
        assertEquals(1, committedChunks)
        current = current.copy(ledger = writer.pruneHistory(current, secondTerm, secondStart))
        assertTrue(current.ledger.isEmpty())
        assertTrue(writer.pruneHistory(current, secondTerm, secondStart).isEmpty())
        historyBefore.forEach { (id, revision) ->
            assertEquals(revision - Events.CUSTOM_APP_URI, repository.revision(id)!! - Events.CUSTOM_APP_URI)
            assertTrue(repository.revision(id)!![Events.CUSTOM_APP_URI]!!.startsWith("sunlith-school://released/"))
        }
        assertEquals(listOf(17), repository.getEvent(historicalId)!!.reminderMinutes)
        val second = SchoolFeed(current.account, secondTerm, items("second", secondStart))
        current = current.copy(ledger = writer.commit(current, writer.plan(current, second, secondStart)))
        assertEquals(300, current.ledger.size)
        val ownedBeforeDisconnect = current.ledger.map { it.rowId }
        val beforeDisconnect = batches.size
        writer.disconnect(current, true)
        assertEquals(3, batches.size - beforeDisconnect)
        assertTrue(batches.drop(beforeDisconnect).all { it.size <= 201 })
        writer.disconnect(current, true)
        ownedBeforeDisconnect.forEach { assertNull(repository.getEvent(it)) }
        assertEquals(300, repository.storedEvents(setOf(calendar)).size)
    }
    @Test fun pruneOnlyRetiresPastOutsideTermAndDoesNotTouchMissingOrReplacedRows() {
        val writer = SchoolCalendarWriter(context); var current = record()
        val items = listOf(source("old", day.minusDays(2)), source("missing", day.minusDays(3)), source("replaced", day.minusDays(4)),
            source("active-history", day.plusDays(1)).copy(teacherAbsent = true), source("future-other-term", day.plusDays(40)))
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(items), day.minusDays(5))), initialized = true)
        val missing = current.ledger.single { it.item.id == "missing" }.rowId
        context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, missing), null, null)
        val replaced = current.ledger.single { it.item.id == "replaced" }.rowId
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, replaced), ContentValues().apply { put(Events.UID_2445, "synthetic-replacement") }, null, null)
        val repository = CalendarRepository(context)
        val replacementBefore = repository.revision(replaced)
        val active = current.ledger.single { it.item.id == "active-history" }.rowId
        val activeBefore = repository.revision(active)
        val remaining = writer.pruneHistory(current, term, day.plusDays(2))
        assertEquals(setOf("active-history", "future-other-term"), remaining.map { it.item.id }.toSet())
        assertEquals(replacementBefore, repository.revision(replaced))
        assertEquals(activeBefore, repository.revision(active))
        assertEquals(remaining, writer.pruneHistory(current, term, day.plusDays(2)))
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int = android.content.pm.PackageManager.PERMISSION_DENIED
        }
        assertEquals(remaining, SchoolCalendarWriter(denied).pruneHistory(current.copy(ledger = remaining), term, day.plusDays(2)))
    }
    @Test fun missingFutureIsRecreatedWhileMissingPastStaysMissing() {
        val writer = SchoolCalendarWriter(context); var current = record()
        val past = source("past", day.minusDays(1)); val future = source("future", day.plusDays(1))
        val replacedPast = source("replaced-past", day.minusDays(1))
        val items = listOf(past, future, replacedPast)
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(items), day.minusDays(2))), baseline = items, initialized = true)
        val replacement = current.ledger.single { it.item.id == replacedPast.id }.rowId
        current.ledger.filter { it.rowId != replacement }.forEach { context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, it.rowId), null, null) }
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, replacement), ContentValues().apply { put(Events.UID_2445, "synthetic-past-replacement") }, null, null)
        val replacementBefore = CalendarRepository(context).revision(replacement)
        val plan = writer.plan(current, feed(items), day)
        assertEquals(SchoolChangeSummary(1, 0, 0), writer.summary(current, plan))
        current = current.copy(ledger = writer.commit(current, plan), baseline = plan.baseline)
        assertEquals("future", current.ledger.single().item.id)
        val second = writer.plan(current, feed(items), day)
        assertEquals(SchoolChangeSummary(0, 0, 0), writer.summary(current, second))
        assertEquals(current.ledger, writer.commit(current, second))
        val newlySeenHistory = source("new-term-history", day.minusDays(2))
        val newTerm = SchoolTerm("NEW", "Synthetic newly loaded term", day.minusDays(3), day.plusDays(13))
        val newHistory = writer.plan(current, SchoolFeed(current.account, newTerm, items + newlySeenHistory), day)
        assertEquals(SchoolChangeSummary(1, 0, 0), writer.summary(current, newHistory))
        assertEquals(setOf("future", "new-term-history"), writer.commit(current, newHistory).map { it.item.id }.toSet())
        assertEquals(replacementBefore, CalendarRepository(context).revision(replacement))
    }
    @Test fun orphanReconnectRequiresExactAccountItemPackageAndExplicitLinkPlan() {
        val writer = SchoolCalendarWriter(context); val first = record(); val item = source()
        val orphan = writer.commit(first, writer.plan(first, feed(listOf(item)), day)).single()
        val repository = CalendarRepository(context)
        val before = repository.revision(orphan.rowId)
        val permissionBlocked = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int = if (permission == Manifest.permission.WRITE_CALENDAR) android.content.pm.PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        SchoolCalendarWriter(permissionBlocked).disconnect(first.copy(ledger = listOf(orphan)), false)
        writer.disconnect(first.copy(ledger = listOf(orphan), calendarIdentity = emptyMap()), false)
        assertEquals(before, repository.revision(orphan.rowId))
        val reconnect = record()
        val plan = writer.plan(reconnect, feed(listOf(item)), day)
        assertEquals(SchoolChangeSummary(0, 0, 0, 1), writer.summary(reconnect, plan))
        assertEquals(before, repository.revision(orphan.rowId))
        assertEquals(plan.linked, writer.plan(reconnect, feed(listOf(item)), day).linked)
        val wrongAccount = reconnect.copy(account = "another-synthetic-account")
        assertEquals(1, writer.summary(wrongAccount, writer.plan(wrongAccount, SchoolFeed(wrongAccount.account, term, listOf(item)), day)).added)
        assertEquals(1, writer.summary(reconnect, writer.plan(reconnect, feed(listOf(item.copy(id = "different-source-id"))), day)).added)
        val foreign = savePersonal(item)
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, foreign), ContentValues().apply {
            put(Events.CUSTOM_APP_PACKAGE, "example.synthetic.foreign")
            put(Events.CUSTOM_APP_URI, orphan.marker)
        }, null, null)
        val foreignBefore = repository.revision(foreign)
        val linked = writer.commit(reconnect, plan).single()
        assertEquals(orphan.rowId, linked.rowId)
        assertEquals(orphan.uid, linked.uid)
        assertNotEquals(orphan.marker, linked.marker)
        assertEquals(foreignBefore, repository.revision(foreign))
    }
    @Test fun largeDisconnectCanResumeAfterACommittedChunkWithoutOneOversizedBatch() {
        val writer = SchoolCalendarWriter(context); val current = record()
        val all = (0 until 2).flatMap { termIndex ->
            val items = (0 until 300).map { source("large-$termIndex-$it", day.plusDays((it % 14).toLong())) }
            writer.commit(current, writer.plan(current, feed(items), day))
        }
        val large = current.copy(ledger = all, initialized = true)
        var applied = 0
        val interrupted = SchoolCalendarWriter(context) { operations ->
            assertTrue(SchoolCalendarWriter.batchBytes(operations) <= 800_000)
            assertTrue(operations.size <= 201)
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
            if (++applied == 1) throw IllegalStateException("Synthetic disconnect crash")
        }
        assertThrows(IllegalStateException::class.java) { interrupted.disconnect(large, true) }
        assertEquals(500, CalendarRepository(context).storedEvents(setOf(calendar)).size)
        val sizes = mutableListOf<Int>()
        val resumed = SchoolCalendarWriter(context) { operations ->
            sizes += SchoolCalendarWriter.batchBytes(operations)
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        }
        resumed.disconnect(large, true)
        resumed.disconnect(large, true)
        assertEquals(5, sizes.size)
        assertTrue(sizes.all { it <= 800_000 })
        assertTrue(CalendarRepository(context).storedEvents(setOf(calendar)).isEmpty())
    }
    @Test fun recurringOrAttendeeOrphanCannotBeLinked() {
        val writer = SchoolCalendarWriter(context); val current = record(); val item = source()
        val orphan = writer.commit(current, writer.plan(current, feed(listOf(item)), day)).single()
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, orphan.rowId), ContentValues().apply { put(Events.HAS_ATTENDEE_DATA, 1) }, null, null)
        assertEquals(SchoolError.LEGACY_COLLISION, assertThrows(SchoolFailure::class.java) { writer.plan(record(), feed(listOf(item)), day) }.reason)
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, orphan.rowId), ContentValues().apply {
            put(Events.HAS_ATTENDEE_DATA, 0); put(Events.RRULE, "FREQ=DAILY"); put(Events.DURATION, "P3600S"); putNull(Events.DTEND)
        }, null, null)
        assertEquals(SchoolError.LEGACY_COLLISION, assertThrows(SchoolFailure::class.java) { writer.plan(record(), feed(listOf(item)), day) }.reason)
    }
    @Test fun atomicAbortWithRemovalAndOutsideEditCanRetryButPartialEffectsCannot() {
        val writer = SchoolCalendarWriter(context); var current = record(); val item = source("update"); val removed = source("remove", day.plusDays(1))
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(listOf(item, removed)), day)), initialized = true)
        val plan = writer.plan(current, feed(listOf(item.copy(title = "School changed title"))), day)
        val updatedId = current.ledger.single { it.item.id == item.id }.rowId
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, updatedId), ContentValues().apply { put(Events.TITLE, "Outside race") }, null, null)
        assertThrows(Exception::class.java) { writer.commit(current, plan) }
        assertTrue(writer.baselineUnchanged(current.copy(journal = plan)))
        assertNull(writer.verify(current, plan))
        val retry = writer.plan(current, feed(listOf(item.copy(title = "School changed title"))), day)
        assertEquals(1, writer.commit(current, retry).size)
        assertFalse(writer.baselineUnchanged(current.copy(journal = retry)))
        assertNotNull(writer.verify(current, retry))
        val next = writer.plan(current.copy(ledger = writer.verify(current, retry)!!), feed(listOf(item.copy(title = "Next school title"), source("insert", day.plusDays(2)))), day)
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, updatedId), ContentValues().apply { put(Events.TITLE, "Next school title") }, null, null)
        assertFalse(writer.baselineUnchanged(current.copy(journal = next)))
    }
    @Test fun insertedMarkerCollisionAbortsAndAmbiguousPureUpdateIsNotDeclaredUnapplied() {
        val writer = SchoolCalendarWriter(context); var current = record(); val item = source()
        val insert = writer.plan(current, feed(listOf(item)), day)
        val collision = savePersonal(item)
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, collision), ContentValues().apply {
            put(Events.CUSTOM_APP_PACKAGE, context.packageName); put(Events.CUSTOM_APP_URI, insert.after.single().marker)
        }, null, null)
        val collisionBefore = CalendarRepository(context).revision(collision)
        assertThrows(Exception::class.java) { writer.commit(current, insert) }
        assertEquals(collisionBefore, CalendarRepository(context).revision(collision))
        assertFalse(writer.baselineUnchanged(current.copy(journal = insert)))
        val fresh = writer.plan(current, feed(listOf(item)), day)
        current = current.copy(ledger = writer.commit(current, fresh), initialized = true)
        val update = writer.plan(current, feed(listOf(item.copy(title = "School changed"))), day)
        assertTrue(writer.baselineUnchanged(current.copy(journal = update)))
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, current.ledger.single().rowId), ContentValues().apply { put(Events.TITLE, "Outside changed" ) }, null, null)
        assertThrows(Exception::class.java) { writer.commit(current, update) }
        assertFalse(writer.baselineUnchanged(current.copy(journal = update)))
        assertNull(writer.verify(current, update, allowContentChanges = true))
    }
    @Test fun partialInsertWithoutPlannedUpdateIsNeverRecoveredAsACompletedOrAbortedBatch() {
        val writer = SchoolCalendarWriter(context); var current = record(); val item = source()
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(listOf(item)), day)), initialized = true)
        val plan = writer.plan(current, feed(listOf(item.copy(title = "Planned update"), source("planned-insert", day.plusDays(1)))), day)
        val brokenAtomicProvider = SchoolCalendarWriter(context) { operations ->
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(operations.filter { it.isAssertQuery || it.isInsert }))
        }
        assertThrows(SchoolFailure::class.java) { brokenAtomicProvider.commit(current, plan) }
        assertFalse(writer.baselineUnchanged(current.copy(journal = plan)))
        assertNull(writer.verify(current, plan))
        assertNull(writer.verify(current, plan, allowContentChanges = true))
        assertEquals(2, CalendarRepository(context).storedEvents(setOf(calendar)).size)
        writer.disconnect(current.copy(journal = plan), true)
        assertTrue(CalendarRepository(context).storedEvents(setOf(calendar)).isEmpty())
    }
    @Test fun disconnectUsesCommittedJournalRevisionAndPreservesOutsideChangesAndReplacements() {
        val writer = SchoolCalendarWriter(context); var current = record()
        val changed = source("changed"); val edited = source("edited", day.plusDays(1)); val removed = source("removed", day.plusDays(2))
        current = current.copy(ledger = writer.commit(current, writer.plan(current, feed(listOf(changed, edited, removed)), day)), initialized = true)
        val removal = current.ledger.single { it.item.id == "removed" }.rowId
        val plan = writer.plan(current, feed(listOf(changed.copy(title = "Committed school update"), edited.copy(title = "Committed then outside edited"), source("inserted", day.plusDays(3)))), day)
        val committed = writer.commit(current, plan)
        val outside = committed.single { it.item.id == "edited" }.rowId
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, outside), ContentValues().apply { put(Events.TITLE, "Preserve this outside edit") }, null, null)
        val personal = savePersonal(source("unrelated", day.plusDays(4)))
        val personalBefore = CalendarRepository(context).revision(personal)
        writer.disconnect(current.copy(journal = plan), true)
        val repository = CalendarRepository(context)
        committed.filter { it.rowId != outside }.forEach { assertNull(repository.getEvent(it.rowId)) }
        assertNull(repository.getEvent(removal))
        assertEquals("Preserve this outside edit", repository.getEvent(outside)!!.title)
        assertTrue(repository.revision(outside)!![Events.CUSTOM_APP_URI]!!.startsWith("sunlith-school://released/"))
        assertEquals(personalBefore, repository.revision(personal))
        writer.disconnect(current.copy(journal = plan), true)
    }
    private fun syncEventUri(id: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, id).buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
    @Test fun allDayDeadlineKeepsFullTitleAndUsesUtcExclusiveEnd() {
        val rows = JSONArray().put(agendaJson().put("Type", "07").put("JourneeComplete", true).put("HeureDebut", "").put("HeureFin", "").put("Titre", "Exam 1 (23:59)"))
        val parsed = SchoolSource.agenda(rows, term, zone).single()
        assertEquals("Exam 1 (23:59)", parsed.title)
        assertEquals("UTC", parsed.zone)
        assertEquals(86_400_000L, parsed.endMillis - parsed.startMillis)
        assertEquals(0L, parsed.startMillis % 86_400_000)
    }
    @Test fun missingFieldsDuplicateIdsAndOutOfWeekAbsencesRejectEntireFeed() {
        val missing = agendaJson().apply { remove("JourneeComplete") }
        assertThrows(SchoolFailure::class.java) { SchoolSource.agenda(JSONArray().put(missing), term, zone) }
        assertThrows(SchoolFailure::class.java) { SchoolSource.agenda(JSONArray().put(agendaJson()).put(agendaJson()), term, zone) }
        assertThrows(SchoolFailure::class.java) { SchoolSource.absences(JSONArray().put(agendaJson().put("Date", "20301015")), day, zone) }
    }
    @Test fun inclusiveNextMondayAbsenceIsAcceptedAndMergedOnlyOnceAcrossQueries() {
        val boundary = agendaJson().put("Date", "20301014")
        val agenda = SchoolSource.agenda(JSONArray().put(boundary), term, zone)
        val notice = JSONObject(boundary.toString()).put("CleUnique", "synthetic-boundary-absence").put("Message", "Synthetic dated absence")
        val first = SchoolSource.absences(JSONArray().put(notice), day, zone)
        val second = SchoolSource.absences(JSONArray().put(notice), day.plusWeeks(1), zone)
        val merged = SchoolSource.merge(agenda, first + second, term)
        assertEquals(1, merged.size)
        assertEquals(day.plusWeeks(1), merged.single().date)
        assertTrue(merged.single().teacherAbsent)
        assertEquals(1, merged.single().notes.count { it == "Synthetic dated absence" })
    }
    @Test fun syntheticNetworkUsesAllWeekQueriesAndSafeHeaders() = runBlocking {
        val calls = mutableListOf<Pair<URI, FakeConnection>>()
        val client = SchoolPortalClient("https://example.invalid/colnet/login.asp", "fr") { uri ->
            val body = when {
                uri.path.endsWith("token") -> "{\"access_token\":\"synthetic-token-only\",\"expires_in\":2592000}"
                uri.path.endsWith("infoMFA") -> "{\"IndMFA\":false,\"ServicesNonDisponibles\":false}"
                uri.path.endsWith("accesPermis") -> "{\"Acces\":true}"
                uri.path.endsWith("informations") -> "{\"CleUnique\":\"synthetic-user-id\",\"PeriodeEtudesHoraire\":\"TEST\",\"ServicesAffiches\":[\"AGENDA\",\"CANCELED_SESSIONS\"]}"
                uri.path.endsWith("periodesEtudes") -> "[{\"Code\":\"TEST\",\"Description\":\"Synthetic term\",\"DateDebut\":\"20301007\",\"DateFin\":\"20301020\"}]"
                uri.path.endsWith("agenda") -> JSONArray().put(agendaJson()).toString()
                uri.query == "DateDebut=20301007" -> JSONArray().put(agendaJson().put("CleUnique", "absence-other-id").put("Message", "Synthetic absence notice")).toString()
                else -> "[]"
            }
            FakeConnection(uri, body).also { calls += uri to it }
        }
        val login = client.login("synthetic+user", "synthetic&password")
        val result = client.fetch(login.token, zone)
        assertTrue(result.items.single().teacherAbsent)
        assertEquals(listOf("DateDebut=20301007", "DateDebut=20301014"), calls.filter { it.first.path.endsWith("coursAnnules") }.map { it.first.query })
        assertTrue(calls.all { !it.second.instanceFollowRedirects && it.second.getRequestProperty("X-Coba-Mobile-Version") == "4.6.3" })
        assertTrue(calls.first().second.output.toString("UTF-8").contains("synthetic%26password"))
        assertNull(calls.first().second.getRequestProperty("Authorization"))
        assertTrue(calls.drop(1).all { it.second.getRequestProperty("Authorization") == "Bearer synthetic-token-only" })
    }
    @Test fun incrementalRefreshPreservesPastAbsenceWithoutQueryingHistoricalWeeksAgain() = runBlocking {
        var weekQueries = 0
        val client = SchoolPortalClient("https://example.invalid/colnet/", "en") { uri ->
            val old = agendaJson().put("Date", "20011001")
            val body = when {
                uri.path.endsWith("infoMFA") -> "{\"IndMFA\":false}"
                uri.path.endsWith("accesPermis") -> "{\"Acces\":true}"
                uri.path.endsWith("informations") -> "{\"CleUnique\":\"synthetic-user-id\",\"PeriodeEtudesHoraire\":\"TEST\",\"ServicesAffiches\":[\"AGENDA\",\"CANCELED_SESSIONS\"]}"
                uri.path.endsWith("periodesEtudes") -> "[{\"Code\":\"TEST\",\"Description\":\"Synthetic historical term\",\"DateDebut\":\"20011001\",\"DateFin\":\"20011014\"}]"
                uri.path.endsWith("agenda") -> JSONArray().put(old).toString()
                uri.path.endsWith("coursAnnules") -> { weekQueries++; if (uri.query == "DateDebut=20011001") JSONArray().put(old.put("CleUnique", "synthetic-absence-id").put("Message", "Historical absence notice")).toString() else "[]" }
                else -> error("Unexpected synthetic endpoint")
            }
            FakeConnection(uri, body)
        }
        val initial = client.fetch("synthetic-token", zone)
        assertTrue(initial.items.single().teacherAbsent)
        assertEquals(2, weekQueries)
        val next = client.fetch("synthetic-token", zone, initial.items, true, initial.term.key)
        assertTrue(next.items.single().teacherAbsent)
        assertEquals(initial.items.single().notes, next.items.single().notes)
        assertEquals(2, weekQueries)
    }
    @Test fun storedCredentialsAreEncryptedAndTamperingIsRejected() {
        val store = SchoolStore(context); store.save(record())
        val file = File(directory, "school-connection-v1.bin")
        val encrypted = file.readBytes()
        assertFalse(String(encrypted, Charsets.UTF_8).contains("synthetic-password"))
        assertEquals("synthetic-password", store.load()!!.password)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        file.writeBytes(encrypted)
        assertEquals(SchoolError.STORAGE, assertThrows(SchoolFailure::class.java) { store.load() }.reason)
    }
    @Test fun redirectsHtmlMfaAndServiceUnavailableCannotProduceSchedule() = runBlocking {
        suspend fun failure(body: String, status: Int = 200, mime: String = "application/json", login: Boolean = false): SchoolError {
            val client = SchoolPortalClient("https://example.invalid/colnet/", "en") { FakeConnection(it, body, status, mime) }
            return try { if (login) client.login("synthetic", "synthetic") else client.fetch("synthetic-token", zone); fail("Must reject invalid source"); SchoolError.STORAGE }
            catch (e: SchoolFailure) { e.reason }
        }
        assertEquals(SchoolError.NETWORK, failure("{}", 302, login = true))
        assertEquals(SchoolError.INVALID_DATA, failure("<html>Login</html>", mime = "text/html", login = true))
        assertEquals(SchoolError.MFA_REQUIRED, failure("{\"IndMFA\":true}"))
        assertEquals(SchoolError.UNAVAILABLE, failure("{\"Code\":\"ServicesNonDisponibles\"}"))
        assertEquals(SchoolError.UNAVAILABLE, failure("{\"ServicesNonDisponibles\":true}"))
    }
    private fun agendaJson() = JSONObject().put("CleUnique", "agenda-1").put("CleClasse", "synthetic-class-key").put("Titre", "Full synthetic class title")
        .put("Cours", "123ABC45").put("Groupe", "000123").put("TypeClasse", "2").put("Type", "01").put("Date", "20301007")
        .put("JourneeComplete", false).put("HeureDebut", "0800").put("HeureFin", "1000").put("IndAbsence", false)
        .put("Enseignants", JSONArray().put(JSONObject().put("Nom", "Synthetic Teacher"))).put("Locaux", JSONArray().put(JSONObject().put("Nom", "A100")))

    private class FakeConnection(uri: URI, private val body: String, private val status: Int = 200, private val mime: String = "application/json") : HttpsURLConnection(uri.toURL()) {
        val output = ByteArrayOutputStream()
        override fun getResponseCode() = status
        override fun getContentType() = mime
        override fun getContentLengthLong() = body.toByteArray().size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = output
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "SYNTHETIC"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
