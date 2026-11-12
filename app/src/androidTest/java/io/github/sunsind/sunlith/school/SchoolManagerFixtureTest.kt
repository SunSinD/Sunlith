package io.github.sunsind.sunlith.school

import android.Manifest
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EditBlockReason
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.requireEmulator
import io.github.sunsind.sunlith.system.AppRefresh
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.Certificate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchoolManagerFixtureTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
    )

    private lateinit var base: Context
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var account: String
    private lateinit var preferencePrefix: String
    private lateinit var portal: PortalFixture
    private lateinit var manager: SchoolScheduleManager
    private val preferenceNames = Collections.synchronizedSet(mutableSetOf<String>())
    private val scheduler = MemoryJobScheduler()
    private val gates = mutableListOf<NetworkGate>()
    private var calendar = -1L
    private val zone = ZoneId.of("America/Toronto")
    private val today = LocalDate.now(zone)
    private val day = today.plusDays(1)
    private val termStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val termEnd = termStart.plusDays(20)

    @Before fun fixture() {
        requireEmulator()
        base = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("School notification tags must be unused by the emulator fixture", schoolNotifications().isEmpty())
        val fixtureId = "school-manager-fixture-${UUID.randomUUID()}"
        directory = File(base.noBackupFilesDir, fixtureId).apply { check(mkdirs()) }
        preferencePrefix = fixtureId
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$preferencePrefix-$name"
                preferenceNames += isolatedName
                return base.getSharedPreferences(isolatedName, mode)
            }
            override fun getSystemService(name: String): Any? =
                if (name == Context.JOB_SCHEDULER_SERVICE) scheduler else super.getSystemService(name)
        }
        check(context.getSystemService(JobScheduler::class.java) === scheduler)
        account = fixtureId
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, account)
            put(Calendars.CALENDAR_DISPLAY_NAME, "School manager synthetic fixture")
            put(Calendars.OWNER_ACCOUNT, account)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, zone.id)
            put(Calendars.ALLOWED_REMINDERS, "1")
            put(Calendars.MAX_REMINDERS, 5)
        }
        calendar = ContentUris.parseId(context.contentResolver.insert(calendarUri(), values)!!)
        check(context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().putLong("last_calendar", calendar).commit())
        portal = PortalFixture()
    }

    @After fun cleanup() {
        gates.forEach { it.release() }
        try {
            if (::manager.isInitialized) runBlocking {
                withTimeout(TIMEOUT) {
                    manager.onBackground()
                    manager.disconnect(false)
                    awaitCalendarRefresh()
                    val deadline = android.os.SystemClock.elapsedRealtime() + TIMEOUT
                    while (schoolNotifications().isNotEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
                        android.os.SystemClock.sleep(25)
                    }
                    assertTrue("Fixture school notifications must finish cancellation", schoolNotifications().isEmpty())
                }
            }
        } finally {
            if (calendar >= 0) context.contentResolver.delete(ContentUris.withAppendedId(calendarUri(), calendar), null, null)
            if (::base.isInitialized) preferenceNames.forEach { base.deleteSharedPreferences(it) }
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun firstSyncWritesExistingDestinationAndPreservesPersonalRows() = fixtureTest {
        val personal = savePersonal()
        val before = providerBytes(personal)
        startManager()

        connect()

        val record = durableRecord()
        assertTrue(record.initialized)
        assertEquals(calendar, record.calendarId)
        assertEquals(1, manager.state.value.eventCount)
        assertNull(manager.state.value.pendingChanges)
        assertNotNull(manager.state.value.lastSuccessMillis)
        assertFalse(manager.state.value.notifications.enabled)
        assertArrayEquals(before, providerBytes(personal))
        assertEquals(setOf(personal, record.ledger.single().rowId), repository().storedEvents(setOf(calendar)).map { it.draft.id }.toSet())
        assertTrue(schoolNotifications().isEmpty())
        awaitCalendarRefresh()
        assertEquals(1, scheduler.getAllPendingJobs().count { it.service.className == SchoolSyncJobService::class.java.name })
        assertTrue(scheduler.getAllPendingJobs().any { it.id == 4031 })
        assertTrue(scheduler.getAllPendingJobs().any { it.id == 4033 })
        assertTrue(portal.calls.any { it.path.endsWith("agenda") && it.query == "dateDebut=${wire(termStart)}&dateFin=${wire(termEnd)}&cleUniqueEleve=synthetic-student-id" })
        assertEquals(SchoolTerm("FIXTURE", "Synthetic active term", termStart, termEnd).mondays().map { "DateDebut=${wire(it)}" }, portal.calls.filter { it.path.endsWith("coursAnnules") }.map { it.query })
    }

    @Test fun firstSyncWithEnabledAlertsIsSilent() = fixtureTest {
        assumeTrue("An existing notification grant is needed to observe first-sync silence", NotificationManagerCompat.from(context).areNotificationsEnabled())
        assumeTrue("The fixture never changes notification permission", Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        SchoolStore(context).save(uninitializedRecord().copy(notifications = SchoolNotificationPreferences(enabled = true)))
        startManager()

        connect()

        assertTrue(durableRecord().initialized)
        assertTrue(manager.state.value.notifications.enabled)
        assertEquals(1, manager.state.value.eventCount)
        assertTrue(schoolNotifications().isEmpty())
    }

    @Test fun exactLegacyClassRemainsUnchangedUntilManagerAppliesPendingLink() = fixtureTest {
        val legacy = savePersonal(description = "123ABC45-000123", exactTime = true)
        val personal = savePersonal()
        val legacyBefore = providerBytes(legacy)
        val personalBefore = providerBytes(personal)
        val uid = repository().revision(legacy)!![Events.UID_2445]
        startManager()

        connect()

        assertEquals(SchoolChangeSummary(0, 0, 0, 1), manager.state.value.pendingChanges)
        assertEquals(0, manager.state.value.eventCount)
        assertNull(manager.state.value.lastSuccessMillis)
        assertFalse(durableRecord().initialized)
        assertArrayEquals(legacyBefore, providerBytes(legacy))
        assertNull(repository().editBlockReason(legacy))

        manager.applyPendingChanges()

        assertNull(manager.state.value.pendingChanges)
        assertTrue(durableRecord().initialized)
        assertEquals(legacy, durableRecord().ledger.single().rowId)
        assertEquals(uid, repository().revision(legacy)!![Events.UID_2445])
        assertEquals(EditBlockReason.SCHOOL_MANAGED, repository().editBlockReason(legacy))
        assertArrayEquals(personalBefore, providerBytes(personal))
        assertEquals(2, repository().storedEvents(setOf(calendar)).size)
    }

    @Test fun refreshUpdatesClassDetailsAndRemovesOnlyOwnedFutureClass() = fixtureTest {
        portal.agenda = JSONArray().put(agendaJson()).put(agendaJson("agenda-2", day.plusDays(1))).toString()
        val personal = savePersonal()
        val personalBefore = providerBytes(personal)
        startManager()
        connect()
        val owned = durableRecord().ledger.associateBy { it.item.id }
        val changed = agendaJson().put("HeureDebut", "0930").put("HeureFin", "1130").put("IndAbsence", true)
            .put("Locaux", names("B200")).put("Enseignants", names("Synthetic Substitute"))
        portal.agenda = JSONArray().put(changed).put(agendaJson("agenda-2", day.plusDays(1))).toString()

        manager.refresh()

        assertNull(manager.state.value.error)
        val updated = repository().getEvent(owned.getValue("agenda-1").rowId)!!
        assertEquals("B200", updated.location)
        assertEquals(at(day, 9, 30), updated.startMillis)
        assertEquals(at(day, 11, 30), updated.endMillis)
        assertEquals(context.getString(R.string.school_sync_absent_title, "Synthetic full class title"), updated.title)
        assertTrue(updated.description.contains("Synthetic Substitute"))
        assertTrue(updated.description.contains(context.getString(R.string.school_sync_teacher_absent)))
        assertTrue(durableRecord().baseline.single { it.id == "agenda-1" }.teacherAbsent)
        assertArrayEquals(personalBefore, providerBytes(personal))

        portal.agenda = JSONArray().put(changed).toString()
        manager.refresh()

        assertNull(manager.state.value.error)
        assertNull(manager.state.value.pendingChanges)
        assertNull(repository().getEvent(owned.getValue("agenda-2").rowId))
        assertEquals(owned.getValue("agenda-1").rowId, durableRecord().ledger.single().rowId)
        assertEquals(1, manager.state.value.eventCount)
        assertArrayEquals(personalBefore, providerBytes(personal))
        assertEquals(2, repository().storedEvents(setOf(calendar)).size)
    }

    @Test fun invalidSourceAndFailedLoginPreserveBaselineAndAllowRetry() = fixtureTest {
        portal.agenda = JSONArray().put(agendaJson()).put(agendaJson("agenda-2", day.plusDays(1))).toString()
        val personal = savePersonal()
        val personalBefore = providerBytes(personal)
        startManager()
        connect()
        val first = durableRecord()
        val firstRows = first.ledger.associate { it.rowId to providerBytes(it.rowId) }
        val changed = agendaJson().put("Locaux", names("C300"))
        portal.agenda = JSONArray().put(changed).put(agendaJson("agenda-2", day.plusDays(1)).apply { remove("IndAbsence") }).toString()

        manager.refresh()

        assertPreservedAfterFailure(first, firstRows, SchoolError.INVALID_DATA)
        portal.agenda = JSONArray().put(changed).put(agendaJson("agenda-2", day.plusDays(1))).toString()
        manager.refresh()
        assertNull(manager.state.value.error)
        assertNull(durableRecord().lastFailure)
        assertEquals("C300", repository().getEvent(first.ledger.first().rowId)!!.location)

        val recovered = durableRecord()
        val recoveredRows = recovered.ledger.associate { it.rowId to providerBytes(it.rowId) }
        portal.rejectSession = true
        portal.rejectLogin = true
        val loginAttempts = portal.calls.count { it.path.endsWith("token") }
        manager.refresh()

        assertPreservedAfterFailure(recovered, recoveredRows, SchoolError.SIGN_IN)
        assertEquals(loginAttempts + 1, portal.calls.count { it.path.endsWith("token") })
        portal.rejectSession = false
        portal.rejectLogin = false
        portal.agenda = JSONArray().put(changed.put("Locaux", names("D400"))).put(agendaJson("agenda-2", day.plusDays(1))).toString()
        manager.refresh()

        assertNull(manager.state.value.error)
        assertNull(durableRecord().lastFailure)
        assertEquals("D400", repository().getEvent(recovered.ledger.first().rowId)!!.location)
        assertArrayEquals(personalBefore, providerBytes(personal))
    }

    @Test fun htmlUnauthorizedSessionRefreshesLoginAndUpdatesSavedClasses() = fixtureTest {
        var rejectNextSession = false
        manager = SchoolScheduleManager(context) { address, language ->
            SchoolPortalClient(address, language) { uri ->
                if (rejectNextSession && uri.path.endsWith("infoMFA")) {
                    rejectNextSession = false
                    FakeConnection(uri, "<html>Unauthorized</html>", 401, null, "text/html")
                } else portal.open(uri)
            }
        }
        val personal = savePersonal()
        val personalBefore = providerBytes(personal)
        connect()
        val rowId = durableRecord().ledger.single().rowId
        val loginAttempts = portal.calls.count { it.path.endsWith("token") }
        portal.agenda = JSONArray().put(agendaJson().put("Locaux", names("E500"))).toString()
        rejectNextSession = true

        manager.refresh()

        assertFalse(rejectNextSession)
        assertNull(manager.state.value.error)
        assertFalse(manager.state.value.busy)
        assertEquals(loginAttempts + 1, portal.calls.count { it.path.endsWith("token") })
        assertEquals(rowId, durableRecord().ledger.single().rowId)
        assertEquals("E500", repository().getEvent(rowId)!!.location)
        assertArrayEquals(personalBefore, providerBytes(personal))
    }

    @Test fun allPhoneRoutesFailWithoutChangingSavedClassesAndTheNextRefreshCanRecover() = fixtureTest {
        var failRoutes = false
        val attempts = mutableListOf<String>()
        manager = SchoolScheduleManager(context) { address, language ->
            SchoolPortalClient.routed(address, language, listOf(
                { uri ->
                    if (failRoutes) { attempts += "direct"; throw UnknownHostException("Synthetic direct DNS failure") }
                    portal.open(uri)
                },
                { _ -> attempts += "default"; throw SocketException("Synthetic fallback transport failure") },
            ))
        }
        val personal = savePersonal()
        val personalBefore = providerBytes(personal)
        connect()
        val before = durableRecord()
        val rows = before.ledger.associate { it.rowId to providerBytes(it.rowId) }
        failRoutes = true
        manager.refresh()
        assertEquals(listOf("direct", "default"), attempts)
        assertPreservedAfterFailure(before, rows, SchoolError.NETWORK)
        assertArrayEquals(personalBefore, providerBytes(personal))
        failRoutes = false
        manager.refresh()
        assertNull(manager.state.value.error)
        assertFalse(manager.state.value.busy)
        assertEquals(before.baseline, durableRecord().baseline)
        assertArrayEquals(personalBefore, providerBytes(personal))
    }

    @Test fun notificationSettersPreserveErrorAndConcurrentCategoryChanges() = fixtureTest {
        startManager()
        connect()
        portal.agenda = "[{}]"
        manager.refresh()
        assertEquals(SchoolError.INVALID_DATA, manager.state.value.error)
        val success = manager.state.value.lastSuccessMillis
        val preferences = SchoolNotificationPreferences(enabled = true, categories = SchoolAlert.entries.toSet() - SchoolAlert.CONNECTION_PROBLEMS)

        manager.setNotifications(preferences)
        assertEquals(preferences, manager.state.value.notifications)
        assertEquals(SchoolError.INVALID_DATA, manager.state.value.error)
        manager.setNotificationsEnabled(false)
        assertFalse(manager.state.value.notifications.enabled)
        assertEquals(SchoolError.INVALID_DATA, manager.state.value.error)
        coroutineScope {
            val start = CompletableDeferred<Unit>()
            val updates = listOf(
                async(Dispatchers.Default) { start.await(); manager.setCategory(SchoolAlert.ROOMS, false) },
                async(Dispatchers.Default) { start.await(); manager.setCategory(SchoolAlert.TEACHERS, false) },
                async(Dispatchers.Default) { start.await(); manager.setNotificationsEnabled(true) },
            )
            start.complete(Unit)
            withTimeout(TIMEOUT) { updates.awaitAll() }
        }

        val expected = preferences.copy(categories = preferences.categories - SchoolAlert.ROOMS - SchoolAlert.TEACHERS)
        assertEquals(expected, manager.state.value.notifications)
        assertEquals(expected, durableRecord().notifications)
        assertEquals(SchoolError.INVALID_DATA, manager.state.value.error)
        assertEquals(success, manager.state.value.lastSuccessMillis)
        assertEquals(SchoolError.INVALID_DATA.name, durableRecord().lastFailure)
    }

    @Test fun connectSurvivesCallerCancellationDuringLogin() = fixtureTest {
        val personal = savePersonal()
        val before = providerBytes(personal)
        startManager()

        cancelledCallerAt("token", { connect() }) { it.connected && it.eventCount == 1 }

        assertTrue(durableRecord().initialized)
        assertNull(manager.state.value.error)
        assertArrayEquals(before, providerBytes(personal))
    }

    @Test fun confirmedApplySurvivesCallerCancellationDuringRevalidation() = fixtureTest {
        val legacy = savePersonal(description = "123ABC45-000123", exactTime = true)
        val uid = repository().revision(legacy)!![Events.UID_2445]
        startManager()
        connect()
        assertEquals(1, manager.state.value.pendingChanges!!.linked)

        cancelledCallerAt("agenda", { manager.applyPendingChanges() }) { it.pendingChanges == null && it.eventCount == 1 }

        assertEquals(legacy, durableRecord().ledger.single().rowId)
        assertEquals(uid, repository().revision(legacy)!![Events.UID_2445])
        assertEquals(EditBlockReason.SCHOOL_MANAGED, repository().editBlockReason(legacy))
        assertEquals(1, repository().storedEvents(setOf(calendar)).size)
    }

    @Test fun manualRefreshSurvivesCallerCancellationDuringFetch() = fixtureTest {
        startManager()
        connect()
        val row = durableRecord().ledger.single().rowId
        portal.agenda = JSONArray().put(agendaJson().put("Locaux", names("E500"))).toString()

        cancelledCallerAt("agenda", { manager.refresh() }) { it.connected }

        assertNull(manager.state.value.error)
        assertEquals("E500", repository().getEvent(row)!!.location)
        assertEquals(listOf("E500"), durableRecord().baseline.single().rooms)
    }

    @Test fun jobRefreshRemainsCancellableAndNextManualRefreshSucceeds() = fixtureTest {
        startManager()
        connect()
        val before = durableRecord()
        val row = before.ledger.single().rowId
        val rowBefore = providerBytes(row)
        portal.agenda = JSONArray().put(agendaJson().put("Locaux", names("F600"))).toString()
        val gate = gateNext("agenda")
        coroutineScope {
            val caller = launch(Dispatchers.IO) { manager.refreshForJob() }
            try {
                gate.awaitEntered()
                caller.cancel()
                gate.release()
                withTimeout(TIMEOUT) { caller.join() }
                assertTrue(caller.isCancelled)
            } finally {
                gate.release()
                withTimeout(TIMEOUT) { caller.cancelAndJoin() }
            }
        }

        assertEquals(before.baseline, durableRecord().baseline)
        assertEquals(before.lastSuccess, durableRecord().lastSuccess)
        assertArrayEquals(rowBefore, providerBytes(row))
        assertNull(manager.state.value.error)
        assertFalse(manager.state.value.busy)
        manager.refresh()
        assertEquals("F600", repository().getEvent(row)!!.location)
        assertNull(manager.state.value.error)
    }

    @Test fun disconnectKeepSurvivesCancelledCallerAndRetainsCalendarChoices() = fixtureTest {
        val personal = savePersonal()
        val personalBefore = providerBytes(personal)
        startManager()
        manager.onOpen()
        awaitState { it.calendarId == calendar && it.availableCalendars.any { choice -> choice.id == calendar } }
        connect()
        val choices = manager.state.value.availableCalendars
        val owned = durableRecord().ledger.single()
        val schoolBefore = repository().getEvent(owned.rowId)!!
        val gate = gateNext("agenda")
        coroutineScope {
            val refresh = launch(Dispatchers.IO) { manager.refreshForJob() }
            try {
                gate.awaitEntered()
                val caller = launch(start = CoroutineStart.UNDISPATCHED) { manager.disconnect(false) }
                withTimeout(TIMEOUT) { caller.cancelAndJoin() }
                gate.release()
                awaitState { !it.connected && !it.busy }
                withTimeout(TIMEOUT) { refresh.join() }
                assertTrue(caller.isCancelled)
            } finally {
                gate.release()
                withTimeout(TIMEOUT) { refresh.cancelAndJoin() }
            }
        }

        assertNull(SchoolStore(context).load())
        assertEquals(schoolBefore, repository().getEvent(owned.rowId))
        assertTrue(repository().revision(owned.rowId)!![Events.CUSTOM_APP_URI]!!.startsWith("sunlith-school://released/"))
        assertEquals(context.packageName, repository().revision(owned.rowId)!![Events.CUSTOM_APP_PACKAGE])
        assertEquals(owned.uid, repository().revision(owned.rowId)!![Events.UID_2445])
        assertNull(repository().editBlockReason(owned.rowId))
        assertArrayEquals(personalBefore, providerBytes(personal))
        assertEquals(choices, manager.state.value.availableCalendars)
        assertEquals(calendar, manager.state.value.calendarId)
        assertEquals(0, manager.state.value.eventCount)
        assertNull(manager.state.value.pendingChanges)
        assertEquals(calendar, context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).getLong("last_calendar", -1))
        assertTrue(scheduler.getAllPendingJobs().none { it.service.className == SchoolSyncJobService::class.java.name })
        assertTrue(SchoolCalendarWriter(context).calendars().any { it.id == calendar })
    }

    @Test fun syncedMetadataAndOutsideEditsDoNotBlockManagerRefresh() = fixtureTest {
        startManager(); connect()
        val id = durableRecord().ledger.single().rowId
        val syncUri = ContentUris.withAppendedId(Events.CONTENT_URI, id).buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        context.contentResolver.update(syncUri, ContentValues().apply {
            put(Events._SYNC_ID, "synthetic-upload-id"); put(Events.HAS_ATTENDEE_DATA, 1)
            put(Events.TITLE, "Outside edit")
        }, null, null)
        portal.agenda = JSONArray().put(agendaJson().put("Titre", "Changed official title")).toString()
        manager.refresh()
        assertNull(manager.state.value.error)
        assertEquals("Changed official title", repository().getEvent(id)!!.title)
        manager.disconnect(true)
        assertFalse(manager.state.value.connected)
        assertNull(SchoolStore(context).load())
        assertNull(repository().getEvent(id))
    }
    @Test fun disconnectRemovePreservesOutsideEditAndFinishesDurably() = fixtureTest {
        startManager(); connect()
        val id = durableRecord().ledger.single().rowId
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply {
            put(Events.TITLE, "Preserve outside edit")
        }, null, null)
        manager.disconnect(true)
        assertNull(manager.state.value.error)
        assertFalse(manager.state.value.connected)
        assertNull(SchoolStore(context).load())
        assertEquals("Preserve outside edit", repository().getEvent(id)!!.title)
        assertNull(repository().editBlockReason(id))
    }
    @Test fun interruptedCommitWithLaterOutsideEditRecoversBeforeRefresh() = fixtureTest {
        val current = uninitializedRecord()
        val writer = SchoolCalendarWriter(context)
        val source = SchoolSource.agenda(JSONArray().put(agendaJson()), SchoolTerm("FIXTURE", "Synthetic active term", termStart, termEnd), zone)
        val plan = writer.plan(current, SchoolFeed(current.account, SchoolTerm("FIXTURE", "Synthetic active term", termStart, termEnd), source), today)
        SchoolStore(context).save(current.copy(journal = plan))
        val owned = writer.commit(current, plan).single()
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, owned.rowId), ContentValues().apply {
            put(Events.TITLE, "Edit between provider commit and journal recovery")
        }, null, null)
        startManager()
        manager.refresh()
        assertNull(manager.state.value.error)
        assertNull(durableRecord().journal)
        assertEquals(owned.rowId, durableRecord().ledger.single().rowId)
        assertEquals("Synthetic full class title", repository().getEvent(owned.rowId)!!.title)
        assertEquals(1, repository().storedEvents(setOf(calendar)).size)
    }
    @Test fun initiallyEmptyTermFinishesWithoutAnEmptyReviewCard() = fixtureTest {
        portal.agenda = "[]"
        startManager(); connect()
        assertTrue(durableRecord().initialized)
        assertNull(manager.state.value.pendingChanges)
        assertEquals(0, manager.state.value.eventCount)
        assertNotNull(manager.state.value.lastSuccessMillis)
    }

    @Test fun changedCalendarRejectsRemovalAndStillAllowsDisconnectKeepingEvents() = fixtureTest {
        val personal = savePersonal()
        startManager(); connect()
        val owned = durableRecord().ledger.single()
        val schoolBefore = providerBytes(owned.rowId)
        val personalBefore = providerBytes(personal)
        context.contentResolver.update(calendarUri(), ContentValues().apply {
            put(Calendars.NAME, "$account-renamed")
        }, "${Calendars._ID}=?", arrayOf(calendar.toString()))

        manager.disconnect(true)

        assertTrue(manager.state.value.connected)
        assertEquals(SchoolError.CALENDAR_CHANGED, manager.state.value.error)
        assertEquals("remove", durableRecord().disconnecting)
        assertArrayEquals(schoolBefore, providerBytes(owned.rowId))
        assertArrayEquals(personalBefore, providerBytes(personal))
        startManager()
        awaitState { it.error == SchoolError.CALENDAR_CHANGED }
        assertTrue(manager.state.value.connected)
        manager.disconnect(false)
        assertFalse(manager.state.value.connected)
        assertNull(SchoolStore(context).load())
        assertArrayEquals(schoolBefore, providerBytes(owned.rowId))
        assertArrayEquals(personalBefore, providerBytes(personal))
        assertNull(repository().editBlockReason(owned.rowId))
    }

    @Test fun emptyAgendaAfterPastClassesKeepsHistoryWithoutAnEmptyReviewCard() = fixtureTest {
        portal.termFrom = minOf(termStart, today.minusDays(1))
        portal.agenda = JSONArray().put(agendaJson(date = today.minusDays(1))).toString()
        startManager(); connect()
        val owned = durableRecord().ledger.single()
        val before = providerBytes(owned.rowId)
        portal.agenda = "[]"

        manager.refresh()

        assertNull(manager.state.value.error)
        assertNull(manager.state.value.pendingChanges)
        assertTrue(durableRecord().baseline.isEmpty())
        assertEquals(owned.rowId, durableRecord().ledger.single().rowId)
        assertArrayEquals(before, providerBytes(owned.rowId))
        assertNotNull(manager.state.value.lastSuccessMillis)
    }

    @Test fun successfulHeldReviewClearsFailureEvenWhenTheReviewFingerprintIsUnchanged() = fixtureTest {
        assumeTrue("An existing notification grant is needed", NotificationManagerCompat.from(context).areNotificationsEnabled())
        portal.agenda = JSONArray((0 until 6).map { agendaJson("class-$it", day.plusDays(it.toLong())) }).toString()
        startManager(); connect()
        manager.setNotificationsEnabled(true)
        suspend fun awaitNotice(tag: String, present: Boolean) = withTimeout(TIMEOUT) {
            while (schoolNotifications().any { it.tag == tag } != present) kotlinx.coroutines.delay(25)
        }
        var fingerprint: String? = null
        repeat(2) {
            portal.agenda = "[{}]"
            manager.refresh()
            assertEquals(SchoolError.INVALID_DATA.name, durableRecord().lastFailure)
            awaitNotice("school-connection", true)
            portal.agenda = "[]"
            manager.refresh()
            assertNull(manager.state.value.error)
            assertNull(durableRecord().lastFailure)
            assertEquals(6, manager.state.value.pendingChanges!!.removed)
            assertEquals(6, repository().storedEvents(setOf(calendar)).size)
            if (fingerprint == null) fingerprint = durableRecord().pendingReview
            assertNotNull(fingerprint)
            assertEquals(fingerprint, durableRecord().pendingReview)
            awaitNotice("school-connection", false)
            awaitNotice("school-review", true)
        }
        portal.agenda = "[{}]"
        manager.refresh()
        awaitNotice("school-connection", true)
        assertEquals(SchoolError.INVALID_DATA.name, durableRecord().lastFailure)
    }

    @Test fun disconnectKeepRemainsAvailableWhenAJournalCannotRecover() = fixtureTest {
        startManager(); connect()
        val current = durableRecord()
        val owned = current.ledger.single()
        val source = current.baseline.single().copy(title = "Pending school update")
        val writer = SchoolCalendarWriter(context)
        val plan = writer.plan(current, SchoolFeed(current.account, SchoolTerm("FIXTURE", "Synthetic active term", termStart, termEnd), listOf(source)), today)
        SchoolStore(context).save(current.copy(journal = plan, disconnecting = "remove"))
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, owned.rowId), ContentValues().apply {
            put(Events.UID_2445, "replacement-personal-event"); put(Events.TITLE, "Personal replacement")
        }, null, null)
        manager = SchoolScheduleManager(context) { address, language -> SchoolPortalClient(address, language, portal::open) }
        manager.disconnect(false)
        assertFalse(manager.state.value.connected)
        assertNull(SchoolStore(context).load())
        assertEquals("Personal replacement", repository().getEvent(owned.rowId)!!.title)
        assertNull(repository().editBlockReason(owned.rowId))
    }

    @Test fun reconnectAfterKeepingAnImportedClassRequiresReviewAndPreservesItsOriginalUid() = fixtureTest {
        val legacy = savePersonal(description = "123ABC45-000123", exactTime = true)
        val originalUid = repository().revision(legacy)!![Events.UID_2445]
        startManager(); connect(); manager.applyPendingChanges()
        assertEquals(legacy, durableRecord().ledger.single().rowId)
        manager.disconnect(false)
        assertNull(repository().editBlockReason(legacy))
        repository().saveEvent(repository().getEvent(legacy)!!.copy(title = "My disconnected class edit"))
        connect()
        assertEquals(SchoolChangeSummary(0, 0, 0, 1), manager.state.value.pendingChanges)
        assertEquals("My disconnected class edit", repository().getEvent(legacy)!!.title)
        manager.applyPendingChanges()
        assertNull(manager.state.value.error)
        assertEquals(legacy, durableRecord().ledger.single().rowId)
        assertEquals(originalUid, repository().revision(legacy)!![Events.UID_2445])
        assertEquals(1, repository().storedEvents(setOf(calendar)).size)
        assertEquals(EditBlockReason.SCHOOL_MANAGED, repository().editBlockReason(legacy))
    }

    @Test fun newTermLoadsHistoricalAbsencesOnceAndDoesNotNotifyForTheWholeTerm() = fixtureTest {
        assumeTrue("An existing notification grant is needed", NotificationManagerCompat.from(context).areNotificationsEnabled())
        startManager(); connect()
        manager.setNotificationsEnabled(true)
        val previousKey = durableRecord().termKey
        val historical = termStart.minusWeeks(2)
        portal.termCode = "NEXT"
        portal.termFrom = termStart.minusWeeks(3)
        portal.agenda = JSONArray().put(agendaJson("historical-next", historical))
            .put(agendaJson().put("Titre", "Next term class title")).toString()
        portal.absences = mapOf("DateDebut=${wire(historical)}" to JSONArray().put(
            agendaJson("historical-next", historical).put("CleUnique", "dated-absence-next").put("Message", "Synthetic historical absence"),
        ).toString())
        portal.calls.clear()

        manager.refresh()

        assertNull(manager.state.value.error)
        assertNotEquals(previousKey, durableRecord().termKey)
        assertEquals("Synthetic NEXT term", manager.state.value.termName)
        assertTrue(durableRecord().baseline.single { it.date == historical }.teacherAbsent)
        val expected = SchoolTerm("NEXT", "Synthetic NEXT term", portal.termFrom, portal.termTo).mondays()
        assertEquals(expected.map { "DateDebut=${wire(it)}" }, portal.calls.filter { it.path.endsWith("coursAnnules") }.map { it.query })
        assertTrue(schoolNotifications().isEmpty())
        portal.calls.clear()
        manager.refresh()
        assertNull(manager.state.value.error)
        assertTrue(durableRecord().baseline.single { it.date == historical }.teacherAbsent)
        assertEquals(expected.filter { !it.isBefore(termStart) }.map { "DateDebut=${wire(it)}" }, portal.calls.filter { it.path.endsWith("coursAnnules") }.map { it.query })
    }

    @Test fun emptyNewTermInitializesOnceAndItsLaterFirstClassCanNotify() = fixtureTest {
        assumeTrue("An existing notification grant is needed", NotificationManagerCompat.from(context).areNotificationsEnabled())
        portal.agenda = "[]"
        startManager(); connect()
        manager.setNotificationsEnabled(true)
        val key = durableRecord().termKey
        assertTrue(key.isNotBlank())
        portal.calls.clear()
        manager.refresh()
        assertEquals(key, durableRecord().termKey)
        assertEquals(3, portal.calls.count { it.path.endsWith("coursAnnules") })
        portal.agenda = JSONArray().put(agendaJson()).toString()
        manager.refresh()
        assertNull(manager.state.value.error)
        assertEquals(1, manager.state.value.eventCount)
        assertTrue(schoolNotifications().any { it.tag == "school-changes" })
    }

    @Test fun aNewTermReleasesOldHistoryWithoutDeletingOrRewritingIt() = fixtureTest {
        val historical = today.minusDays(30)
        portal.termCode = "OLD"
        portal.termFrom = today.minusDays(40)
        portal.termTo = today.minusDays(20)
        portal.agenda = JSONArray().put(agendaJson("old-class", historical)).toString()
        startManager(); connect()
        val owned = durableRecord().ledger.single()
        val draft = repository().getEvent(owned.rowId)!!
        portal.termCode = "NEXT"
        portal.termFrom = termStart
        portal.termTo = termEnd
        portal.agenda = JSONArray().put(agendaJson("next-class")).toString()
        manager.refresh()
        assertNull(manager.state.value.error)
        assertNull(manager.state.value.pendingChanges)
        assertEquals(1, durableRecord().ledger.size)
        assertNotEquals(owned.rowId, durableRecord().ledger.single().rowId)
        assertEquals(draft, repository().getEvent(owned.rowId))
        assertEquals(owned.uid, repository().revision(owned.rowId)!![Events.UID_2445])
        assertNull(repository().editBlockReason(owned.rowId))
        assertEquals(2, repository().storedEvents(setOf(calendar)).size)
    }

    @Test fun startupRecoveryFailureKeepsConnectionFieldsAndAllowsDisconnect() = fixtureTest {
        val current = uninitializedRecord()
        val writer = SchoolCalendarWriter(context)
        val term = SchoolTerm("FIXTURE", "Synthetic active term", termStart, termEnd)
        val plan = writer.plan(current, SchoolFeed(current.account, term, SchoolSource.agenda(JSONArray().put(agendaJson()), term, zone)), today)
        val owned = writer.commit(current, plan).single()
        SchoolStore(context).save(current.copy(journal = plan))
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, owned.rowId), ContentValues().apply {
            put(Events.UID_2445, "outside-replacement-uid")
        }, null, null)
        startManager()
        awaitState { it.error != null }
        assertTrue(manager.state.value.connected)
        assertEquals(current.portal, manager.state.value.portalAddress)
        assertEquals(current.username, manager.state.value.accountName)
        assertEquals(calendar, manager.state.value.calendarId)
        manager.disconnect(false)
        assertFalse(manager.state.value.connected)
    }

    @Test fun privateLivePortalSmokeCheck() = runBlocking {
        assumeTrue("Live verification is explicitly opt-in", InstrumentationRegistry.getArguments().getString("schoolLive") == "true")
        val input = File(base.noBackupFilesDir, "school-live-input.json")
        val request = try { JSONObject(input.readText()) } finally { input.delete() }
        val personal = savePersonal()
        val personalBefore = providerBytes(personal)
        manager = SchoolScheduleManager(context)
        withTimeout(180_000) {
            manager.connect(request.getString("portal"), request.getString("username"), request.getString("password"), zone.id, calendar)
        }
        assertNull(manager.state.value.error)
        assertTrue(manager.state.value.connected)
        assertNull(manager.state.value.pendingChanges)
        val first = durableRecord()
        assertTrue(first.initialized)
        assertTrue(first.ledger.isNotEmpty())
        val classes = first.ledger.filter { it.item.kind == "01" }
        assertTrue(classes.isNotEmpty())
        assertTrue(classes.all { it.item.course.isNotBlank() && it.item.group.isNotBlank() && it.item.teachers.isNotEmpty() && it.item.title.isNotBlank() })
        if (request.has("absenceDate")) assertTrue(first.ledger.any { it.item.teacherAbsent && it.item.date.toString() == request.getString("absenceDate") })
        assertTrue(first.ledger.any { it.item.kind == "07" && it.item.allDay })
        assertTrue(first.ledger.any { it.item.kind == "08" })
        assertArrayEquals(personalBefore, providerBytes(personal))
        withTimeout(180_000) { manager.refresh() }
        assertNull(manager.state.value.error)
        val second = durableRecord()
        assertEquals(first.ledger.map { it.rowId }.toSet(), second.ledger.map { it.rowId }.toSet())
        assertEquals(second.ledger.size + 1, repository().storedEvents(setOf(calendar)).size)
        assertArrayEquals(personalBefore, providerBytes(personal))
        assertTrue(schoolNotifications().isEmpty())
        val result = android.os.Bundle().apply {
            putInt("schoolLiveTotal", second.ledger.size)
            putInt("schoolLiveClasses", second.ledger.count { it.item.kind == "01" })
            putInt("schoolLiveAbsences", second.ledger.count { it.item.teacherAbsent })
            putBoolean("schoolLivePersonalUnchanged", true)
            putBoolean("schoolLiveStableRows", true)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result)
    }

    private fun fixtureTest(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(30_000) { block() } }
    }

    private fun startManager() {
        manager = SchoolScheduleManager(context) { address, language ->
            SchoolPortalClient(address, language, portal::open)
        }
    }

    private suspend fun connect() = withTimeout(TIMEOUT) {
        manager.connect(PORTAL, "synthetic-user", "synthetic-password", zone.id, calendar)
        assertNull(manager.state.value.error)
        assertTrue(manager.state.value.connected)
        assertFalse(manager.state.value.busy)
    }

    private fun uninitializedRecord() = SchoolRecord(
        SchoolPortalClient.normalize(PORTAL).toString(), "synthetic-user", "synthetic-password", zone.id, calendar,
        schoolHash("${SchoolPortalClient.normalize(PORTAL)}\nsynthetic-student-id"), UUID.randomUUID().toString(),
        "synthetic-token-only", Long.MAX_VALUE, calendarIdentity = SchoolCalendarWriter(context).calendarIdentity(calendar),
    )

    private fun durableRecord() = checkNotNull(SchoolStore(context).load())
    private fun awaitCalendarRefresh() { AppRefresh.executor.submit {}.get(TIMEOUT, TimeUnit.MILLISECONDS) }
    private fun repository() = CalendarRepository(context)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun wire(date: LocalDate) = SchoolSource.wireDate(date)
    private fun names(value: String) = JSONArray().put(JSONObject().put("Nom", value))
    private fun agendaJson(id: String = "agenda-1", date: LocalDate = day) = JSONObject()
        .put("CleUnique", id).put("CleClasse", "synthetic-class-$id").put("Titre", "Synthetic full class title")
        .put("Cours", "123ABC45").put("Groupe", "000123").put("TypeClasse", "2").put("Type", "01")
        .put("Date", wire(date)).put("JourneeComplete", false).put("HeureDebut", "0800").put("HeureFin", "1000")
        .put("IndAbsence", false).put("Enseignants", names("Synthetic Teacher")).put("Locaux", names("A100"))

    private fun savePersonal(description: String = "Synthetic personal notes", exactTime: Boolean = false) = repository().saveEvent(EventDraft(
        calendarId = calendar, title = "Synthetic personal event", description = description,
        startMillis = at(day, 8) + if (exactTime) 0 else 1000,
        endMillis = at(day, 10) + if (exactTime) 0 else 1000,
        timeZone = zone.id, reminderMinutes = if (exactTime) emptyList() else listOf(10),
    ))

    private fun calendarUri() = Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private fun schoolNotifications() = base.getSystemService(NotificationManager::class.java).activeNotifications
        .filter { it.tag in setOf("school-changes", "school-connection", "school-review") }

    private fun providerBytes(id: Long): ByteArray {
        fun rows(uri: Uri, selection: String? = null): List<List<Pair<String, String?>>> =
            checkNotNull(context.contentResolver.query(uri, null, selection, if (selection == null) null else arrayOf(id.toString()), "${Events._ID} ASC")).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.columnNames.sorted().map { name ->
                        val index = cursor.getColumnIndexOrThrow(name)
                        name to when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
                            else -> cursor.getString(index)
                        }
                    })
                }
            }
        val event = rows(ContentUris.withAppendedId(Events.CONTENT_URI, id))
        check(event.size == 1)
        return listOf(event, rows(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?"), rows(Attendees.CONTENT_URI, "${Attendees.EVENT_ID}=?"))
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun assertPreservedAfterFailure(before: SchoolRecord, rows: Map<Long, ByteArray>, error: SchoolError) {
        val after = durableRecord()
        assertEquals(error, manager.state.value.error)
        assertTrue(manager.state.value.connected)
        assertFalse(manager.state.value.busy)
        assertEquals(before.baseline, after.baseline)
        assertEquals(before.ledger, after.ledger)
        assertEquals(before.lastSuccess, after.lastSuccess)
        assertEquals(before.lastSuccess, manager.state.value.lastSuccessMillis)
        assertEquals(error.name, after.lastFailure)
        rows.forEach { (id, bytes) -> assertArrayEquals(bytes, providerBytes(id)) }
    }

    private suspend fun awaitState(condition: (SchoolScheduleState) -> Boolean) =
        withTimeout(TIMEOUT) { manager.state.first(condition) }

    private fun gateNext(endpoint: String) = NetworkGate(endpoint).also {
        gates += it
        check(portal.nextGate.compareAndSet(null, it))
    }

    private suspend fun cancelledCallerAt(endpoint: String, action: suspend () -> Unit, completed: (SchoolScheduleState) -> Boolean) = coroutineScope {
        val gate = gateNext(endpoint)
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { action() }
        try {
            gate.awaitEntered()
            withTimeout(TIMEOUT) { caller.cancelAndJoin() }
            assertTrue(caller.isCancelled)
            assertTrue(manager.state.value.busy)
            gate.release()
            awaitState { !it.busy && completed(it) }
        } finally {
            gate.release()
            withTimeout(TIMEOUT) { caller.cancelAndJoin() }
        }
    }

    private inner class PortalFixture {
        @Volatile var agenda = JSONArray().put(agendaJson()).toString()
        @Volatile var termCode = "FIXTURE"
        @Volatile var termFrom = termStart
        @Volatile var termTo = termEnd
        @Volatile var absences: Map<String, String> = emptyMap()
        @Volatile var rejectSession = false
        @Volatile var rejectLogin = false
        val calls = Collections.synchronizedList(mutableListOf<URI>())
        val nextGate = AtomicReference<NetworkGate?>()

        fun open(uri: URI): HttpsURLConnection {
            calls += uri
            val status = when {
                uri.path.endsWith("token") && rejectLogin -> 400
                uri.path.endsWith("infoMFA") && rejectSession -> 401
                else -> 200
            }
            val body = when {
                uri.path.endsWith("token") -> "{\"access_token\":\"synthetic-token-only\",\"expires_in\":2592000}"
                uri.path.endsWith("infoMFA") -> "{\"IndMFA\":false,\"ServicesNonDisponibles\":false}"
                uri.path.endsWith("accesPermis") -> "{\"Acces\":true}"
                uri.path.endsWith("informations") -> JSONObject().put("CleUnique", "synthetic-student-id").put("PeriodeEtudesHoraire", termCode)
                    .put("ServicesAffiches", JSONArray().put("AGENDA").put("CANCELED_SESSIONS")).toString()
                uri.path.endsWith("periodesEtudes") -> JSONArray().put(JSONObject().put("Code", termCode).put("Description", if (termCode == "FIXTURE") "Synthetic active term" else "Synthetic $termCode term")
                    .put("DateDebut", wire(termFrom)).put("DateFin", wire(termTo))).toString()
                uri.path.endsWith("agenda") -> agenda
                uri.path.endsWith("coursAnnules") -> absences[uri.query] ?: "[]"
                else -> error("Unexpected synthetic endpoint: ${uri.path}")
            }
            val waiting = nextGate.get()
            val gate = if (waiting != null && uri.path.endsWith(waiting.endpoint) && nextGate.compareAndSet(waiting, null)) waiting else null
            return FakeConnection(uri, body, status, gate)
        }
    }

    private class NetworkGate(val endpoint: String) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun block() {
            entered.countDown()
            if (!released.await(TIMEOUT, TimeUnit.MILLISECONDS)) throw IOException("Synthetic network gate timed out")
        }
        suspend fun awaitEntered() = withContext(Dispatchers.IO) {
            assertTrue("Manager did not reach the synthetic $endpoint gate", entered.await(TIMEOUT, TimeUnit.MILLISECONDS))
        }
        fun release() { released.countDown() }
    }

    private class FakeConnection(uri: URI, private val body: String, private val status: Int, private val gate: NetworkGate?,
        private val mime: String = "application/json") : HttpsURLConnection(uri.toURL()) {
        override fun getResponseCode(): Int { gate?.block(); return status }
        override fun getContentType() = mime
        override fun getContentLengthLong() = body.toByteArray(Charsets.UTF_8).size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "SYNTHETIC"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private class MemoryJobScheduler : JobScheduler() {
        private val jobs = mutableMapOf<Int, JobInfo>()
        @Synchronized override fun schedule(job: JobInfo): Int { jobs[job.id] = job; return RESULT_SUCCESS }
        override fun enqueue(job: JobInfo, work: JobWorkItem) = schedule(job)
        @Synchronized override fun cancel(jobId: Int) { jobs.remove(jobId) }
        @Synchronized override fun cancelAll() { jobs.clear() }
        @Synchronized override fun getAllPendingJobs(): List<JobInfo> = jobs.values.toList()
        @Synchronized override fun getPendingJob(jobId: Int): JobInfo? = jobs[jobId]
    }

    private companion object {
        const val PORTAL = "https://example.invalid/colnet/"
        const val TIMEOUT = 10_000L
    }
}
