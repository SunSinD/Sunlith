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
import android.content.res.Configuration
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.projectSchoolEvents
import io.github.sunsind.sunlith.requireEmulator
import io.github.sunsind.sunlith.system.AppRefresh
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.net.URLDecoder
import java.security.cert.Certificate
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic transport only. Neither the mobile server nor an authenticated classic portal is contacted. */
@RunWith(AndroidJUnit4::class)
class SchoolMessagesManagerFixtureTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
    )

    private lateinit var base: Context
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var fixtureId: String
    private val managers = mutableListOf<SchoolMessagesManager>()
    private val gates = mutableListOf<Gate>()
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val zone = ZoneId.of("America/Toronto")
    private val today = LocalDate.now(zone)
    private var calendar = -1L
    private var schedule: SchoolScheduleManager? = null
    private var elapsed = 0L

    @Before fun fixture() {
        requireEmulator()
        base = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Fixture must not clear existing school notifications", base.getSystemService(NotificationManager::class.java)
            .activeNotifications.none { it.tag in setOf("school-changes", "school-connection", "school-review") })
        fixtureId = "school-messages-fixture-${UUID.randomUUID()}"
        directory = File(base.noBackupFilesDir, fixtureId).apply { check(mkdirs()) }
        val scheduler = MemoryJobScheduler()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$fixtureId-$name"
                preferences += isolated
                return base.getSharedPreferences(isolated, mode)
            }
            override fun getSystemService(name: String): Any? =
                if (name == Context.JOB_SCHEDULER_SERVICE) scheduler else super.getSystemService(name)
        }
    }

    @After fun cleanup() {
        gates.forEach { it.release() }
        managers.forEach { it.clear() }
        try {
            schedule?.let { manager -> runBlocking { withTimeout(TIMEOUT) { manager.onBackground(); manager.disconnect(false) } } }
            AppRefresh.executor.submit {}.get(TIMEOUT, TimeUnit.MILLISECONDS)
        } finally {
            if (calendar >= 0) context.contentResolver.delete(ContentUris.withAppendedId(calendarUri(), calendar), null, null)
            if (::base.isInitialized) synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                base.deleteSharedPreferences(name)
            }
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun classicLoginPreservesHiddenFieldsCookiesAndReadOnlyMethods() = runBlocking {
        val portal = ClassicFixture()
        val result = portal.reader().readAdministration("synthetic+student", "synthetic&password", emptyMap(), today)

        assertEquals(SchoolAdminOutcome.OK, result.outcome)
        assertEquals(1, result.rows.size)
        assertTrue(result.details.values.single().body.contains("18h"))
        val post = portal.calls.single { it.requestMethod == "POST" }
        val fields = post.output.toString("UTF-8").split('&').associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        assertEquals("nonce&guard", fields["csrf"])
        assertEquals("synthetic+student", fields[SchoolClassicPortal.USER])
        assertEquals("synthetic&password", fields[SchoolClassicPortal.PASSWORD])
        assertNotNull(fields["${SchoolClassicPortal.SUBMIT}.x"])
        assertTrue(post.getRequestProperty("Cookie").contains("ASPSESSIONID=before"))
        assertTrue(portal.calls.filter { it.url.path.endsWith("accueil.asp") || it.url.path.endsWith("affmessdir.asp") }
            .all { it.getRequestProperty("Cookie").contains("ASPSESSIONID=after") })
        assertTrue(portal.calls.filter { it !== post }.all { it.requestMethod == "GET" })
        assertTrue(portal.calls.all { !it.instanceFollowRedirects })
        assertTrue(portal.calls.all { it.hostnameVerifier === HttpsURLConnection.getDefaultHostnameVerifier() })
        assertTrue(portal.calls.all { it.sslSocketFactory === HttpsURLConnection.getDefaultSSLSocketFactory() })
    }

    @Test fun classicPhoneLoginLayoutSignsInWithTheVerifiedSubmitFields() = runBlocking {
        // Col.NET gives Android user agents a login form without the image button (anonymized live shape, 2026-11-14).
        val portal = ClassicFixture().apply { phoneLayout = true }
        val result = portal.reader().readAdministration("student", "password", emptyMap(), today)
        assertEquals(SchoolAdminOutcome.OK, result.outcome)
        assertEquals(1, result.rows.size)
        val post = portal.calls.single { it.requestMethod == "POST" }
        val fields = post.output.toString("UTF-8").split('&').associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        assertEquals("12", fields["${SchoolClassicPortal.SUBMIT}.x"])
        assertEquals("8", fields["${SchoolClassicPortal.SUBMIT}.y"])
        assertTrue(fields.containsKey(SchoolClassicPortal.POST_BACK))
        assertFalse(fields.containsKey("txtLibWebBidon"))
    }

    @Test fun portalLinksKeepARealEmptyListApartFromFailures() = runBlocking {
        fun client(info: String) = SchoolPortalClient(BASE.toString(), "fr") { uri -> Connection(uri, info) }
        val none = emptyList<Pair<String, String>>()
        assertEquals(none, client("""{"Liens":[]}""").portalLinks("synthetic-token-only"))
        assertEquals(none, client("""{}""").portalLinks("synthetic-token-only"))
        assertEquals(none, client("""{"Liens":null}""").portalLinks("synthetic-token-only"))
        assertEquals(listOf("SYN" to "Synthetic link"), client("""{"Liens":[{"Code":"SYN","Description":"Synthetic link"},{"Code":"","Description":"Blank code"},7]}""")
            .portalLinks("synthetic-token-only"))
        val broken = try { client("""{"Liens":"broken"}""").portalLinks("synthetic-token-only"); null } catch (e: SchoolFailure) { e.reason }
        assertEquals(SchoolError.INVALID_DATA, broken)

        fun linksManager(info: String) = SchoolMessagesManager(context,
            clientFactory = { address, language -> SchoolPortalClient(address, language) { uri -> Connection(uri, info) } },
            classicFactory = { ClassicFixture().reader() }, onNoticesChanged = {}, onNewMessages = { _, _ -> }, elapsedRealtime = { elapsed },
        ).also { managers += it }
        assertEquals(SchoolPortalLinks.Failed(SchoolAdminStatus.NOT_LOADED), linksManager("""{"Liens":[]}""").loadPortalLinks())
        SchoolStore(context).save(record())
        val empty = linksManager("""{"Liens":[]}""")
        assertEquals(SchoolPortalLinks.Loaded(emptyList()), empty.loadPortalLinks())
        assertEquals(false, empty.state.value.portalLinksAvailable)
        assertEquals(SchoolPortalLinks.Failed(SchoolAdminStatus.UNRECOGNIZED), linksManager("""{"Liens":"broken"}""").loadPortalLinks())
        val listed = linksManager("""{"Liens":[{"Code":"SYN","Description":"Synthetic link"}]}""")
        assertEquals(SchoolPortalLinks.Loaded(listOf(SchoolPortalLink("SYN", "Synthetic link"))), listed.loadPortalLinks())
        assertEquals(true, listed.state.value.portalLinksAvailable)
    }

    @Test fun classicRefusesCrossOriginRedirectsAndDetailLinks() = runBlocking {
        val redirected = ClassicFixture().apply { redirect = "https://other.example.invalid/colnet/accueil.asp" }
        assertEquals(SchoolAdminOutcome.UNRECOGNIZED,
            redirected.reader().readAdministration("student", "password", emptyMap(), today).outcome)
        assertTrue(redirected.calls.all { it.url.host == HOST })

        val linked = ClassicFixture().apply { detailLink = "https://other.example.invalid/colnet/affmessdir.asp?NoMess=1" }
        val result = linked.reader().readAdministration("student", "password", emptyMap(), today)
        assertEquals(SchoolAdminOutcome.OK, result.outcome)
        assertNull(result.rows.single().link)
        assertTrue(result.details.isEmpty())
        assertTrue(linked.calls.none { it.url.path.endsWith("affmessdir.asp") })
    }

    @Test fun classicFallsBackOnceWithoutChangingTheHttpsContract() = runBlocking {
        val portal = ClassicFixture()
        val directAttempts = AtomicInteger()
        val reader = portal.reader {
            directAttempts.incrementAndGet()
            throw UnknownHostException("Synthetic direct DNS failure")
        }
        val result = reader.readAdministration("student", "password", emptyMap(), today)
        assertEquals(SchoolAdminOutcome.OK, result.outcome)
        assertEquals("The fallback remains selected for this portal session", 1, directAttempts.get())
        assertTrue(portal.calls.all { it.url.protocol == "https" && it.url.host == HOST })
        assertTrue(portal.calls.all { it.hostnameVerifier === HttpsURLConnection.getDefaultHostnameVerifier() })
        assertTrue(portal.calls.all { it.sslSocketFactory === HttpsURLConnection.getDefaultSSLSocketFactory() })
    }

    @Test fun classicRefreshesEditedBodiesAndAcceptsRecognizedEmptyList() = runBlocking {
        val portal = ClassicFixture()
        val first = portal.reader().readAdministration("student", "password", emptyMap(), today)
        val firstId = first.rows.single().id
        portal.body = "Les cours sont levés le ${frenchDate()} jusqu'à 12h."
        val second = portal.reader().readAdministration("student", "password", first.details, today)
        assertEquals(firstId, second.rows.single().id)
        assertTrue(second.details.getValue(firstId).body.contains("12h"))
        assertEquals(2, portal.calls.count { it.url.path.endsWith("affmessdir.asp") })

        portal.empty = true
        val empty = portal.reader().readAdministration("student", "password", second.details, today)
        assertEquals(SchoolAdminOutcome.OK, empty.outcome)
        assertTrue(empty.rows.isEmpty())
        assertTrue(empty.details.isEmpty())
    }

    @Test fun failedDetailRefreshKeepsOnlyMatchingListedCachedBodiesAndReportsStaleness() = runBlocking {
        val portal = ClassicFixture()
        val first = portal.reader().readAdministration("student", "password", emptyMap(), today)
        for (malformed in listOf(false, true)) {
            portal.failDetail = !malformed
            portal.malformedDetail = malformed
            val stale = portal.reader().readAdministration("student", "password", first.details, today)
            assertEquals(SchoolAdminOutcome.OK, stale.outcome)
            assertEquals(first.details, stale.details)
            assertTrue(stale.detailsStale)
        }
        val mismatched = first.details.mapValues { (_, detail) -> detail.copy(date = today.minusDays(1)) }
        val rejected = portal.reader().readAdministration("student", "password", mismatched, today)
        assertTrue("A cache entry with a different date must never stand in for the listed message", rejected.details.isEmpty())
        assertTrue(rejected.detailsStale)
        portal.empty = true
        val removed = portal.reader().readAdministration("student", "password", first.details, today)
        assertTrue(removed.details.isEmpty())
        assertFalse(removed.detailsStale)
    }

    @Test fun encryptedCacheRestoresOnlyForTheSameAccountAndConnection() = runBlocking {
        val original = record()
        SchoolStore(context).save(original)
        val manager = newManager(MobileFixture(), ClassicFixture())
        manager.refresh()
        val cached = checkNotNull(SchoolMessagesStore(context).load())
        assertEquals(original.account, cached.account)
        val file = File(directory, "school-messages-v1.bin")
        assertTrue(file.isFile)
        val ciphertext = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(ciphertext.contains(cached.admin.single().subject))
        assertFalse(ciphertext.contains(original.password))

        val restored = newManager(MobileFixture(), ClassicFixture())
        await { restored.state.value.connected }
        assertEquals(cached.admin, restored.state.value.admin)

        for (differentAccount in listOf(false, true)) {
            SchoolMessagesStore(context).save(cached)
            val changed = original.copy(account = if (differentAccount) "another-account" else original.account,
                connection = if (differentAccount) original.connection else UUID.randomUUID().toString())
            SchoolStore(context).save(changed)
            val isolated = newManager(MobileFixture(), ClassicFixture())
            await { isolated.state.value.connected }
            assertTrue(isolated.state.value.admin.isEmpty())
            assertNull(SchoolMessagesStore(context).load())
        }
    }

    @Test fun staleAdministrationBodyRemainsVisibleWithAnError() = runBlocking {
        SchoolStore(context).save(record())
        val classic = ClassicFixture()
        val manager = newManager(MobileFixture(), classic)
        manager.refresh()
        val before = manager.state.value.admin.single()
        classic.failDetail = true
        manager.refresh()
        assertEquals(before, manager.state.value.admin.single())
        assertEquals(SchoolAdminStatus.NETWORK, manager.state.value.adminStatus)
        assertEquals(before, checkNotNull(SchoolMessagesStore(context).load()).admin.single())
    }

    @Test fun disconnectDuringAResponseCannotRestoreMessagesOrCache() = runBlocking {
        SchoolStore(context).save(record())
        val gate = Gate().also { gates += it }
        val mobile = MobileFixture()
        val classic = ClassicFixture().apply { detailGate = gate }
        val manager = newManager(mobile, classic)
        val refreshing = async(Dispatchers.Default) { runCatching { manager.refresh() }.exceptionOrNull() }
        gate.awaitEntered()
        SchoolStore(context).clear()
        manager.clear()
        gate.release()
        val error = withTimeout(TIMEOUT) { refreshing.await() }
        assertTrue(error == null || error is CancellationException)
        assertFalse(manager.state.value.connected)
        assertFalse(manager.state.value.busy)
        assertTrue(manager.state.value.admin.isEmpty())
        assertNull(SchoolMessagesStore(context).load())
        assertTrue("Administration refresh must not request the mobile mailbox", mobile.calls.isEmpty())
    }

    @Test fun changedConnectionDuringAResponseRejectsTheOldCommit() = runBlocking {
        val original = record()
        SchoolStore(context).save(original)
        val gate = Gate().also { gates += it }
        val manager = newManager(MobileFixture(), ClassicFixture().apply { detailGate = gate })
        val refreshing = async(Dispatchers.Default) { manager.refresh() }
        gate.awaitEntered()
        SchoolStore(context).save(original.copy(connection = UUID.randomUUID().toString()))
        gate.release()
        withTimeout(TIMEOUT) { refreshing.await() }
        assertNull(SchoolMessagesStore(context).load())
        assertTrue(manager.state.value.admin.isEmpty())
    }

    @Test fun noticeWarningConfirmationMissingSourceAndCorrectionReachCalendarWithoutAgendaRequests() = runBlocking {
        createCalendar()
        val term = SchoolTerm("SYNTHETIC", "Synthetic term", today.minusDays(7), today.plusDays(30))
        val writer = SchoolCalendarWriter(context)
        val start = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val course = SchoolItem("synthetic-course", "synthetic-class", "Synthetic class", "243SYN", "0001", "1", "01", today,
            start, start + 3_600_000, false, zone.id, emptyList(), emptyList(), emptyList(), false)
        val seed = record(calendar).copy(initialized = true, term = term.name, termKey = term.key, termSource = term,
            calendarIdentity = writer.calendarIdentity(calendar), lastSuccess = 1234L)
        val plan = writer.plan(seed, SchoolFeed(seed.account, term, listOf(course)), today)
        val owned = writer.commitSteps(seed, plan) { SchoolStore(context).save(it) }
        SchoolStore(context).save(seed.copy(ledger = owned, baseline = listOf(course)))
        val classUri = ContentUris.withAppendedId(Events.CONTENT_URI, owned.single().rowId)
        val editedTitle = "Synthetic local class edit preserved during notice refresh"
        assertEquals(1, context.contentResolver.update(classUri, ContentValues().apply { put(Events.TITLE, editedTitle) }, null, null))
        fun assertClassUntouched() {
            context.contentResolver.query(classUri, arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(editedTitle, cursor.getString(0))
                assertEquals(start, cursor.getLong(1))
                assertEquals(start + 3_600_000, cursor.getLong(2))
                assertFalse(cursor.moveToNext())
            }
        }
        val mobile = MobileFixture()
        val classic = ClassicFixture().apply {
            subject = "Avis de boycott le ${frenchDate()}"
            body = "Le Collège pourrait décréter une levée des cours le ${frenchDate()} vers 7h15 si une ligne de piquetage est constatée."
        }
        val reader = newManager(mobile, classic)
        val requests = AtomicInteger()
        val calendarManager = SchoolScheduleManager(context, notices = reader, clientFactory = { _, _ ->
            requests.incrementAndGet(); error("Notice-only updates must not fetch the agenda")
        }).also { schedule = it }
        reader.refresh()
        calendarManager.syncCachedNotices()
        val warned = checkNotNull(SchoolStore(context).load())
        val firstNotice = warned.ledger.single { it.item.kind == SchoolNoticeInterpreter.NOTICE_KIND }
        assertTrue(firstNotice.item.title.contains("possible", true))
        assertEquals(owned.single().rowId, warned.ledger.single { it.item.id == course.id }.rowId)
        assertEquals(1234L, warned.lastSuccess)
        assertClassUntouched()

        classic.subject = "Levée des cours le ${frenchDate()}"
        classic.body = "Les cours sont levés le ${frenchDate()} jusqu'à 18h."
        reader.refresh()
        calendarManager.syncCachedNotices()
        val confirmed = checkNotNull(SchoolStore(context).load())
        val confirmedNotice = confirmed.ledger.single { it.item.kind == SchoolNoticeInterpreter.NOTICE_KIND }
        assertEquals(firstNotice.rowId, confirmedNotice.rowId)
        assertEquals(firstNotice.uid, confirmedNotice.uid)
        assertTrue(confirmedNotice.item.title.contains("18"))
        assertFalse(confirmedNotice.item.title.contains("possible", true))
        assertEquals(1234L, confirmed.lastSuccess)
        assertClassUntouched()

        classic.empty = true
        reader.refresh()
        calendarManager.syncCachedNotices()
        val missing = checkNotNull(SchoolStore(context).load())
        assertEquals("A missing portal row is not a correction.", confirmed.baseline, missing.baseline)
        assertEquals(confirmed.ledger.map { it.item.id to it.rowId }, missing.ledger.map { it.item.id to it.rowId })
        assertClassUntouched()

        classic.empty = false
        classic.subject = "Annulation de la levée des cours le ${frenchDate()}"
        classic.body = "La levée des cours du ${frenchDate()} est annulée. Les cours sont maintenus."
        reader.refresh()
        calendarManager.syncCachedNotices()
        val corrected = checkNotNull(SchoolStore(context).load())
        assertEquals(listOf(course), corrected.baseline.filter { it.kind != SchoolNoticeInterpreter.NOTICE_KIND })
        val correctedNotice = corrected.ledger.single { it.item.kind == SchoolNoticeInterpreter.NOTICE_KIND }
        assertEquals(firstNotice.rowId, correctedNotice.rowId)
        assertEquals(firstNotice.uid, correctedNotice.uid)
        assertTrue(correctedNotice.item.title.contains("mis à jour"))
        val preservedClass = corrected.ledger.single { it.item.id == course.id }
        assertEquals(owned.single().rowId, preservedClass.rowId)
        assertEquals(owned.single().uid, preservedClass.uid)
        assertEquals(1234L, corrected.lastSuccess)
        assertEquals(0, requests.get())
        assertNull(calendarManager.state.value.error)
        assertNull(calendarManager.state.value.pendingChanges)
        assertClassUntouched()
    }

    @Test fun confirmedLiftingMarksUneditedClassesInPlaceAndWithdrawalRestoresThem() = runBlocking {
        createCalendar()
        val term = SchoolTerm("LIFT", "Synthetic lifting term", today.minusDays(7), today.plusDays(30))
        val writer = SchoolCalendarWriter(context)
        val start = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val evening = today.atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
        val course = SchoolItem("lift-course", "lift-class", "Synthetic class", "243SYN", "0001", "1", "01", today,
            start, start + 3_600_000, false, zone.id, emptyList(), emptyList(), emptyList(), false)
        val late = course.copy(id = "lift-evening", classKey = "lift-evening-class", title = "Synthetic evening class", startMillis = evening, endMillis = evening + 3_600_000)
        val seed = record(calendar).copy(initialized = true, term = term.name, termKey = term.key, termSource = term,
            calendarIdentity = writer.calendarIdentity(calendar), lastSuccess = 4321L)
        val owned = writer.commitSteps(seed, writer.plan(seed, SchoolFeed(seed.account, term, listOf(course, late)), today)) { SchoolStore(context).save(it) }
        SchoolStore(context).save(seed.copy(ledger = owned, baseline = listOf(course, late)))
        val rows = owned.associate { it.item.id to (it.rowId to it.uid) }
        fun row(id: String): Pair<String, Long> = context.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, rows.getValue(id).first),
            arrayOf(Events.TITLE, Events.DTSTART), null, null, null)!!.use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) to cursor.getLong(1) }
        val classic = ClassicFixture()
        val reader = newManager(MobileFixture(), classic)
        val calendarManager = SchoolScheduleManager(context, notices = reader, clientFactory = { _, _ ->
            error("A notice refresh must not fetch the agenda")
        }).also { schedule = it }
        reader.refresh()
        assertTrue(reader.state.value.collegeNotices.single().classesMarked)
        calendarManager.syncCachedNotices()
        val lifted = checkNotNull(SchoolStore(context).load())
        assertEquals(context.getString(R.string.school_sync_lifted_title, course.title) to start, row(course.id))
        assertEquals("a class starting after the end time stays as it was", late.title to evening, row(late.id))
        assertEquals("$today@18:00", lifted.ledger.single { it.item.id == course.id }.item.collegeLift)
        assertEquals(rows, lifted.ledger.filter { it.item.kind != SchoolNoticeInterpreter.NOTICE_KIND }.associate { it.item.id to (it.rowId to it.uid) })
        val stored = lifted.baseline.single { it.id == course.id }
        assertEquals(stored, SchoolItem.read(stored.json()))
        assertFalse(course.json().has("collegeLift"))
        val repository = CalendarRepository(context)
        fun shownClass() = repository.events(start, start + 3_600_000, setOf(calendar), school = true).single { it.id == rows.getValue(course.id).first }
        assertEquals(SchoolClassLift(today, java.time.LocalTime.of(18, 0), today), shownClass().school?.lift)

        classic.subject = "Annulation de la levée des cours le ${frenchDate()}"
        classic.body = "La levée des cours du ${frenchDate()} est annulée. Les cours sont maintenus."
        reader.refresh()
        assertEquals(SchoolNoticeStatus.UPDATED, reader.state.value.collegeNotices.single().status)
        calendarManager.syncCachedNotices()
        val restored = checkNotNull(SchoolStore(context).load())
        assertEquals(course.title to start, row(course.id))
        assertNull(shownClass().school?.lift)
        assertEquals(listOf(course, late), restored.baseline.filter { it.kind != SchoolNoticeInterpreter.NOTICE_KIND }.sortedBy { it.startMillis })
        assertEquals(rows, restored.ledger.filter { it.item.kind != SchoolNoticeInterpreter.NOTICE_KIND }.associate { it.item.id to (it.rowId to it.uid) })
        assertEquals(4321L, restored.lastSuccess)
        assertNull(calendarManager.state.value.error)
    }

    @Test fun liftingAcrossALanguageChangePreservesPreviouslyObservedLocalEdits() = runBlocking {
        createCalendar()
        fun inLanguage(language: String): Context {
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
            return object : ContextWrapper(context) { override fun getResources() = localized.resources }
        }
        val french = SchoolCalendarWriter(inLanguage("fr"))
        val englishContext = inLanguage("en")
        val english = SchoolCalendarWriter(englishContext)
        val term = SchoolTerm("LOCAL-LIFT", "Synthetic local edit term", today.minusDays(7), today.plusDays(30))
        val start = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val untouched = SchoolItem("plain-class", "plain", "Synthetic plain class", "243SYN", "0001", "1", "01", today,
            start, start + 3_600_000, false, zone.id, emptyList(), emptyList(), emptyList(), false)
        val edited = untouched.copy(id = "edited-class", classKey = "edited", title = "Synthetic original title")
        val changedZone = untouched.copy(id = "zone-class", classKey = "zone", title = "Synthetic changed time zone")
        val classes = listOf(untouched, edited, changedZone)
        val seed = record(calendar).copy(initialized = true, term = term.name, termKey = term.key, termSource = term,
            calendarIdentity = french.calendarIdentity(calendar), lastSuccess = 7890L)
        val initial = french.commitSteps(seed, french.plan(seed, SchoolFeed(seed.account, term, classes), today)) { SchoolStore(context).save(it) }
        var current = seed.copy(ledger = initial, baseline = classes)
        SchoolStore(context).save(current)
        val ids = initial.associate { it.item.id to it.rowId }
        val localTitle = "My preserved class title"
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, ids.getValue(edited.id)), ContentValues().apply { put(Events.TITLE, localTitle) }, null, null)
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, ids.getValue(changedZone.id)), ContentValues().apply { put(Events.EVENT_TIMEZONE, "UTC") }, null, null)
        val warning = SchoolAdminNotice("w", today, "Avis de boycott le ${frenchDate()}", "Direction générale",
            body = "Le Collège pourrait décréter une levée des cours le ${frenchDate()} si une ligne de piquetage est constatée.")
        val warnedFeed = SchoolFeed(seed.account, term, classes + SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(warning)), false))
        val adopted = french.commitSteps(current, french.plan(current, warnedFeed, today, noticeOnly = true)) { SchoolStore(context).save(it) }
        current = current.copy(ledger = adopted, baseline = warnedFeed.items)
        SchoolStore(context).save(current)
        assertEquals(localTitle, current.ledger.single { it.item.id == edited.id }.revision[Events.TITLE])
        assertEquals(edited.title, current.ledger.single { it.item.id == edited.id }.writtenRevision!![Events.TITLE])
        initial.forEach { owned ->
            assertEquals(owned, SchoolOwned.read(owned.json()))
            val legacy = owned.json().apply { remove("writtenRevision") }
            assertNull("Old ledgers must not infer last-written content from an observed revision.", SchoolOwned.read(legacy).writtenRevision)
        }
        val confirmed = warning.copy(id = "c", subject = "Levée des cours le ${frenchDate()}", body = "Les cours sont levés le ${frenchDate()} jusqu'à 18 h.")
        val marked = SchoolNoticeInterpreter.applyLifts(classes, listOf(SchoolClassLift(today, java.time.LocalTime.of(18, 0), today)), classes, today.minusDays(14))
        val feed = SchoolFeed(seed.account, term, marked + SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(confirmed)), false))
        val legacy = current.copy(ledger = current.ledger.map { it.copy(writtenRevision = null) })
        val legacyPlan = french.plan(legacy, feed, today, noticeOnly = true)
        assertEquals(localTitle, legacyPlan.after.single { it.item.id == edited.id }.revision[Events.TITLE])
        assertEquals("", legacyPlan.after.single { it.item.id == edited.id }.item.collegeLift)
        assertEquals("UTC", legacyPlan.after.single { it.item.id == changedZone.id }.revision[Events.EVENT_TIMEZONE])
        val after = english.commitSteps(current, english.plan(current, feed, today, noticeOnly = true)) { SchoolStore(context).save(it) }
        current = current.copy(ledger = after, baseline = feed.items)
        SchoolStore(context).save(current)
        val shown = CalendarRepository(context).events(start, start + 3_600_000, setOf(calendar), school = true).associateBy { it.id }
        assertEquals(englishContext.getString(R.string.school_sync_lifted_title, untouched.title), shown.getValue(ids.getValue(untouched.id)).title)
        assertNotNull(shown.getValue(ids.getValue(untouched.id)).school?.lift)
        assertEquals(localTitle, shown.getValue(ids.getValue(edited.id)).title)
        assertNull("An adopted local title edit must not regain school metadata.", shown.getValue(ids.getValue(edited.id)).school)
        assertNull("A local time zone edit must not be overwritten or annotated.", shown.getValue(ids.getValue(changedZone.id)).school)
        assertEquals("UTC", shown.getValue(ids.getValue(changedZone.id)).timeZone)
        assertEquals(classes.associate { it.id to ids.getValue(it.id) }, after.filter { it.item.kind == "01" }.associate { it.item.id to it.rowId })
    }

    @Test fun legacyLocalTitlesStayVisibleAfterNoticeRefreshAndConfirmedLifting() = runBlocking {
        createCalendar()
        val writer = SchoolCalendarWriter(context)
        val term = SchoolTerm("LEGACY-TITLE", "Synthetic legacy term", today.minusDays(7), today.plusDays(30))
        val start = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val first = SchoolItem("legacy-first", "legacy-class", "Synthetic legacy class", "243SYN", "0001", "1", "01", today,
            start, start + 3_600_000, false, zone.id, emptyList(), emptyList(), emptyList(), false)
        val second = first.copy(id = "legacy-second", startMillis = start + 3_600_000, endMillis = start + 7_200_000)
        val classes = listOf(first, second)
        val seed = record(calendar).copy(initialized = true, term = term.name, termKey = term.key, termSource = term,
            calendarIdentity = writer.calendarIdentity(calendar), lastSuccess = 7654L)
        val imported = writer.commitSteps(seed, writer.plan(seed, SchoolFeed(seed.account, term, classes), today)) { SchoolStore(context).save(it) }
        var current = seed.copy(ledger = imported.map { it.copy(writtenRevision = null) }, baseline = classes)
        SchoolStore(context).save(current)
        val ids = imported.associate { it.item.id to it.rowId }
        fun shown() = CalendarRepository(context).events(start, start + 7_200_000, setOf(calendar), school = true)
            .filter { it.id in ids.values }.sortedBy { it.startMillis }
        assertTrue("Untouched legacy periods still carry verified metadata.", shown().all { it.school != null })
        assertEquals(1, projectSchoolEvents(shown(), zone).size)

        val localTitle = "My preserved legacy title"
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, ids.getValue(first.id)),
            ContentValues().apply { put(Events.TITLE, localTitle) }, null, null)
        val warning = SchoolAdminNotice("legacy-warning", today, "Avis de boycott le ${frenchDate()}", "Direction",
            body = "Le Collège pourrait décréter une levée des cours le ${frenchDate()} si une ligne de piquetage est constatée.")
        val warnedFeed = SchoolFeed(seed.account, term, classes + SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(warning)), false))
        val adopted = writer.commitSteps(current, writer.plan(current, warnedFeed, today, noticeOnly = true)) { SchoolStore(context).save(it) }
        current = current.copy(ledger = adopted, baseline = warnedFeed.items)
        SchoolStore(context).save(current)
        assertNull(current.ledger.single { it.item.id == first.id }.writtenRevision)
        assertEquals(localTitle, current.ledger.single { it.item.id == first.id }.revision[Events.TITLE])
        fun assertLocalTitle() {
            val rows = shown()
            assertEquals(localTitle, rows.first().title)
            assertNull("Observed legacy edits must not become imported metadata.", rows.first().school)
            assertNotNull(rows.last().school)
            val displayed = projectSchoolEvents(rows, zone)
            assertEquals(2, displayed.size)
            assertEquals(localTitle, displayed.first().title)
            assertEquals(ids.getValue(first.id), displayed.first().id)
            assertEquals(start, displayed.first().startMillis)
            assertEquals(start + 3_600_000, displayed.first().endMillis)
        }
        assertLocalTitle()

        val marked = SchoolNoticeInterpreter.applyLifts(classes,
            listOf(SchoolClassLift(today, java.time.LocalTime.of(18, 0), today)), classes, today.minusDays(14))
        val confirmedFeed = SchoolFeed(seed.account, term, marked)
        val after = writer.commitSteps(current, writer.plan(current, confirmedFeed, today, noticeOnly = true)) { SchoolStore(context).save(it) }
        current = current.copy(ledger = after, baseline = confirmedFeed.items)
        SchoolStore(context).save(current)
        assertLocalTitle()
        assertNotNull("The untouched legacy period still receives the confirmed lifting.", shown().last().school?.lift)
        assertEquals(imported.map { it.rowId to it.uid }, after.filter { it.item.kind == SchoolNoticeInterpreter.CLASS_KIND }.map { it.rowId to it.uid })
    }

    @Test fun unreadReplacementKeepsTheAppliedNoticeAndClassMarksUntilAReadCorrection() = runBlocking {
        createCalendar()
        val term = SchoolTerm("UNREAD-LIFT", "Synthetic unread notice term", today.minusDays(7), today.plusDays(30))
        val writer = SchoolCalendarWriter(context)
        val start = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val course = SchoolItem("unread-course", "unread-class", "Synthetic cancelled class", "243SYN", "0001", "1", "01", today,
            start, start + 3_600_000, false, zone.id, emptyList(), emptyList(), emptyList(), false, collegeLift = "$today@18:00")
        val unmarked = course.copy(id = "new-course", classKey = "new-class", title = "Synthetic unmarked class", collegeLift = "")
        val classic = ClassicFixture()
        val full = SchoolAdminNotice("confirmed", today, classic.subject, "Direction générale", body = classic.body, detailAvailable = true)
        val notice = SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(full)), false).single()
        val baseline = listOf(notice, course, unmarked)
        val seed = record(calendar).copy(initialized = true, term = term.name, termKey = term.key, termSource = term,
            calendarIdentity = writer.calendarIdentity(calendar), lastSuccess = 6789L)
        val owned = writer.commitSteps(seed, writer.plan(seed, SchoolFeed(seed.account, term, baseline), today)) { SchoolStore(context).save(it) }
        SchoolStore(context).save(seed.copy(ledger = owned, baseline = baseline))
        val ids = owned.associate { it.item.id to it.rowId }
        classic.failDetail = true
        val reader = newManager(MobileFixture(), classic)
        val calendarManager = SchoolScheduleManager(context, notices = reader, clientFactory = { _, _ ->
            error("A title-only replacement must not fetch the agenda")
        }).also { schedule = it }
        reader.refresh()
        assertFalse(reader.state.value.collegeNotices.single().detailRead)
        val before = checkNotNull(SchoolStore(context).load())
        assertTrue(reader.classLifts(before, term)!!.single().preserveOnly)
        assertEquals(listOf(notice), reader.items(before, term, false))
        calendarManager.syncCachedNotices()
        val kept = checkNotNull(SchoolStore(context).load())
        assertEquals(notice, kept.baseline.single { it.kind == SchoolNoticeInterpreter.NOTICE_KIND })
        assertEquals(course.collegeLift, kept.baseline.single { it.id == course.id }.collegeLift)
        assertEquals("", kept.baseline.single { it.id == unmarked.id }.collegeLift)
        assertEquals(ids, kept.ledger.associate { it.item.id to it.rowId })

        classic.failDetail = false
        classic.subject = "Annulation de la levée des cours le ${frenchDate()}"
        classic.body = "La levée des cours du ${frenchDate()} est annulée. Les cours sont maintenus."
        reader.refresh()
        calendarManager.syncCachedNotices()
        val restored = checkNotNull(SchoolStore(context).load())
        assertTrue(restored.baseline.filter { it.kind == "01" }.all { it.collegeLift.isEmpty() })
        assertEquals(ids, restored.ledger.associate { it.item.id to it.rowId })
        assertEquals(6789L, restored.lastSuccess)
        assertNull(calendarManager.state.value.error)
    }

    @Test fun aDisappearingNoticeKeepsExistingMarksAndItsCalendarItemUntilAReadCorrection() = runBlocking {
        createCalendar()
        val term = SchoolTerm("MISSING-LIFT", "Synthetic missing notice term", today.minusDays(7), today.plusDays(30))
        val writer = SchoolCalendarWriter(context)
        val start = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val course = SchoolItem("missing-course", "missing-class", "Synthetic cancelled class", "243SYN", "0001", "1", "01", today,
            start, start + 3_600_000, false, zone.id, emptyList(), emptyList(), emptyList(), false, collegeLift = "$today@18:00")
        val unmarked = course.copy(id = "new-course", classKey = "new-class", title = "Synthetic unmarked class", collegeLift = "")
        val classic = ClassicFixture()
        val full = SchoolAdminNotice("confirmed", today, classic.subject, "Direction générale", body = classic.body, detailAvailable = true)
        val notice = SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(full)), false).single()
        val baseline = listOf(notice, course, unmarked)
        val seed = record(calendar).copy(initialized = true, term = term.name, termKey = term.key, termSource = term,
            calendarIdentity = writer.calendarIdentity(calendar), lastSuccess = 7890L)
        val owned = writer.commitSteps(seed, writer.plan(seed, SchoolFeed(seed.account, term, baseline), today)) { SchoolStore(context).save(it) }
        SchoolStore(context).save(seed.copy(ledger = owned, baseline = baseline))
        val ids = owned.associate { it.item.id to it.rowId }
        val reader = newManager(MobileFixture(), classic)
        reader.refresh()
        classic.empty = true
        reader.refresh()
        assertEquals(SchoolAdminStatus.OK, reader.state.value.adminStatus)
        assertTrue(reader.state.value.admin.isEmpty())
        val before = checkNotNull(SchoolStore(context).load())
        assertTrue(reader.classLifts(before, term)!!.single().preserveOnly)
        assertEquals(listOf(notice), reader.items(before, term, false))
        val withoutNotice = before.copy(baseline = listOf(course, unmarked))
        assertTrue("A class mark alone also survives a missing source", reader.classLifts(withoutNotice, term)!!.single().preserveOnly)
        val restarted = newManager(MobileFixture(), classic)
        restarted.refresh()
        assertTrue(restarted.classLifts(before, term)!!.single().preserveOnly)
        assertEquals(listOf(notice), restarted.items(before, term, false))
        val calendarManager = SchoolScheduleManager(context, notices = restarted, clientFactory = { _, _ ->
            error("A missing administration source must not fetch the agenda")
        }).also { schedule = it }
        calendarManager.syncCachedNotices()
        val kept = checkNotNull(SchoolStore(context).load())
        assertEquals(notice, kept.baseline.single { it.kind == SchoolNoticeInterpreter.NOTICE_KIND })
        assertEquals(course.collegeLift, kept.baseline.single { it.id == course.id }.collegeLift)
        assertEquals("", kept.baseline.single { it.id == unmarked.id }.collegeLift)
        assertEquals(ids, kept.ledger.associate { it.item.id to it.rowId })

        classic.empty = false
        classic.subject = "Cours finalement maintenus le ${frenchDate()}"
        classic.body = "Les cours sont finalement maintenus, sauf les cours du soir."
        restarted.refresh()
        val correction = restarted.state.value.collegeNotices.single()
        assertEquals(SchoolNoticeStatus.UPDATED, correction.status)
        assertFalse(correction.classesMarked)
        assertTrue(restarted.classLifts(kept, term)!!.isEmpty())
        calendarManager.syncCachedNotices()
        val restored = checkNotNull(SchoolStore(context).load())
        assertTrue(restored.baseline.filter { it.kind == "01" }.all { it.collegeLift.isEmpty() })
        val updatedNotice = restored.baseline.single { it.kind == SchoolNoticeInterpreter.NOTICE_KIND }
        assertEquals(notice.id, updatedNotice.id)
        assertNotEquals(notice.title, updatedNotice.title)
        assertTrue(updatedNotice.notes.any { it == classic.body })
        assertEquals(ids, restored.ledger.associate { it.item.id to it.rowId })
        assertEquals(7890L, restored.lastSuccess)
        assertNull(calendarManager.state.value.error)
    }

    @Test fun correctionPostedTodayUpdatesYesterdaysNoticeWithoutRewritingPastClasses() = runBlocking {
        createCalendar()
        val yesterday = today.minusDays(1)
        val term = SchoolTerm("PAST-NOTICE", "Synthetic correction term", yesterday.minusDays(7), today.plusDays(30))
        val writer = SchoolCalendarWriter(context)
        val start = yesterday.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val course = SchoolItem("past-course", "past-class", "Synthetic past class", "243SYN", "0001", "1", "01", yesterday,
            start, start + 3_600_000, false, zone.id, emptyList(), emptyList(), emptyList(), false)
        val oldMessage = SchoolAdminNotice("past-confirmation", yesterday, "Levée des cours le ${frenchDate(yesterday)}", "Direction générale",
            body = "Les cours sont levés le ${frenchDate(yesterday)} jusqu'à 18h.")
        val oldNotice = SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(oldMessage)), true).single()
        val seed = record(calendar).copy(initialized = true, term = term.name, termKey = term.key, termSource = term,
            calendarIdentity = writer.calendarIdentity(calendar), lastSuccess = 5678L)
        val baseline = listOf(course, oldNotice)
        val plan = writer.plan(seed, SchoolFeed(seed.account, term, baseline), today)
        val owned = writer.commitSteps(seed, plan) { SchoolStore(context).save(it) }
        SchoolStore(context).save(seed.copy(ledger = owned, baseline = baseline))
        val initialNotice = owned.single { it.item.kind == SchoolNoticeInterpreter.NOTICE_KIND }
        val initialCourse = owned.single { it.item.id == course.id }
        val classUri = ContentUris.withAppendedId(Events.CONTENT_URI, initialCourse.rowId)
        val editedTitle = "Synthetic edited past class"
        assertEquals(1, context.contentResolver.update(classUri, ContentValues().apply { put(Events.TITLE, editedTitle) }, null, null))
        val classic = ClassicFixture().apply {
            subject = "Cours maintenus le ${frenchDate(yesterday)}"
            body = "Les cours ne sont pas annulés le ${frenchDate(yesterday)}. Les cours sont maintenus."
        }
        val reader = newManager(MobileFixture(), classic)
        val calendarManager = SchoolScheduleManager(context, notices = reader, clientFactory = { _, _ ->
            error("A correction to yesterday's notice must not fetch the agenda")
        }).also { schedule = it }
        reader.refresh()
        val correction = reader.state.value.collegeNotices.single()
        assertEquals(yesterday, correction.targetDate)
        assertEquals(today, correction.sourceDate)
        assertEquals(SchoolNoticeStatus.UPDATED, correction.status)
        calendarManager.syncCachedNotices()
        val updated = checkNotNull(SchoolStore(context).load())
        val notice = updated.ledger.single { it.item.kind == SchoolNoticeInterpreter.NOTICE_KIND }
        assertEquals(initialNotice.rowId, notice.rowId)
        assertEquals(initialNotice.uid, notice.uid)
        assertEquals(initialNotice.item.id, notice.item.id)
        assertNotEquals(initialNotice.item.title, notice.item.title)
        assertTrue(notice.item.notes.any { it.contains(classic.subject) })
        assertTrue(notice.item.notes.any { it.contains("ne sont pas annulés") })
        context.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, notice.rowId), arrayOf(Events.TITLE, Events.DESCRIPTION), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(notice.item.title, cursor.getString(0))
            assertTrue(cursor.getString(1).contains(classic.subject))
        }
        context.contentResolver.query(classUri, arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(editedTitle, cursor.getString(0))
            assertEquals(start, cursor.getLong(1))
            assertEquals(start + 3_600_000, cursor.getLong(2))
        }
        assertEquals(initialCourse.rowId, updated.ledger.single { it.item.id == course.id }.rowId)
        assertEquals(initialCourse.uid, updated.ledger.single { it.item.id == course.id }.uid)
        assertEquals(notice.item, updated.baseline.single { it.kind == SchoolNoticeInterpreter.NOTICE_KIND })
        assertEquals(5678L, updated.lastSuccess)
        assertNull(calendarManager.state.value.error)
    }

    @Test fun administrationRefreshNeverFetchesPersonalMailAndAlertsOnlyNewOrEditedFrenchMessages() = runBlocking {
        SchoolStore(context).save(record())
        val mobile = MobileFixture()
        val classic = ClassicFixture()
        val received = mutableListOf<SchoolAdminNotice>()
        val manager = newManager(mobile, classic) { _, messages -> received += messages }
        manager.refresh()
        assertTrue("The initial history must not flood notifications", received.isEmpty())
        assertEquals(classic.subject, manager.state.value.admin.single().subject)
        assertEquals(classic.body, manager.state.value.admin.single().body)
        classic.subject = "Inscription aux ateliers de français"
        classic.body = "Bonjour, les inscriptions sont ouvertes. Conservez ce texte tel quel."
        elapsed += 300_000L
        manager.refreshForJob()
        assertEquals(listOf(classic.subject), received.map { it.subject })
        assertEquals(classic.body, received.single().body)
        manager.refresh()
        assertEquals("An unchanged message must not alert twice", 1, received.size)
        classic.body += " La date limite a été modifiée."
        manager.refresh()
        assertEquals(2, received.size)
        assertEquals(classic.body, received.last().body)
        val restarted = newManager(mobile, classic) { _, messages -> received += messages }
        restarted.refresh()
        assertEquals("Restarting must preserve the alert baseline", 2, received.size)
        assertTrue(mobile.calls.isEmpty())
    }

    @Test fun emptyListAndRestartKeepAlertHistoryAndStillAlertForCorrections() = runBlocking {
        SchoolStore(context).save(record())
        val classic = ClassicFixture()
        val received = mutableListOf<SchoolAdminNotice>()
        val manager = newManager(MobileFixture(), classic) { _, messages -> received += messages }
        manager.refresh()
        val first = manager.state.value.admin.single()
        assertTrue(received.isEmpty())
        // Model an older installed cache with no separate history, then an empty portal response.
        SchoolMessagesStore(context).save(checkNotNull(SchoolMessagesStore(context).load()).copy(seen = emptyMap()))
        val upgraded = newManager(MobileFixture(), classic) { _, messages -> received += messages }
        classic.empty = true
        upgraded.refresh()
        assertTrue(upgraded.state.value.admin.isEmpty())
        assertTrue(checkNotNull(SchoolMessagesStore(context).load()).seen.containsKey(first.id))
        val restarted = newManager(MobileFixture(), classic) { _, messages -> received += messages }
        classic.empty = false
        restarted.refresh()
        assertTrue("An empty response and a restart must not make old messages new", received.isEmpty())
        classic.body += " Correction de l'horaire."
        elapsed += 300_000L
        restarted.refreshForJob()
        assertEquals(listOf(classic.body), received.map { it.body })
    }

    @Test fun firstBodyFetchDoesNotRepeatTheTitleAlert() = runBlocking {
        SchoolStore(context).save(record())
        val classic = ClassicFixture()
        val received = mutableListOf<SchoolAdminNotice>()
        val manager = newManager(MobileFixture(), classic) { _, messages -> received += messages }
        manager.refresh()
        classic.subject = "Nouvelle information du Collège"
        classic.body = "La bibliothèque vous présente ses nouvelles ressources."
        classic.failDetail = true
        manager.refresh()
        assertEquals(1, received.size)
        assertNull(received.single().body)
        classic.failDetail = false
        manager.refresh()
        assertEquals("Fetching the body of an already announced title must stay silent", 1, received.size)
        classic.body += " Correction."
        manager.refresh()
        assertEquals(2, received.size)
    }

    @Test fun firstBodyCategoryDoesNotRepeatAnAlreadyConfirmedTitle() {
        val title = SchoolAdminNotice("same-notice", today, "Levée des cours le ${frenchDate()} jusqu'à 18h", "")
        val previous = SchoolMessagesCache("account", "connection", admin = listOf(title), adminAt = 1).remember()
        val detailed = previous.copy(admin = listOf(title.copy(category = "Direction générale", body = "Consultez les informations du Collège.")))
        assertTrue("A category arriving with the body must not repeat an unchanged confirmed notice", detailed.arrivals(previous).isEmpty())
    }

    @Test fun firstBodyAlertsWhenItRevealsOrConfirmsAClassLifting() = runBlocking {
        SchoolStore(context).save(record())
        val classic = ClassicFixture()
        val received = mutableListOf<SchoolAdminNotice>()
        val manager = newManager(MobileFixture(), classic) { _, messages -> received += messages }
        manager.refresh()
        listOf("Nouvelle information du Collège", "Grève possible le ${frenchDate()}").forEach { subject ->
            classic.subject = subject
            classic.failDetail = true
            manager.refresh()
            assertNull(received.last().body)
            val before = received.size
            classic.failDetail = false
            manager.refresh()
            assertEquals("A first body confirming a class lifting must alert", before + 1, received.size)
            assertEquals(classic.body, received.last().body)
            assertEquals(SchoolNoticeStatus.CONFIRMED, manager.state.value.collegeNotices.single().status)
            manager.refresh()
            assertEquals("The same confirmation must not repeat", before + 1, received.size)
        }
    }

    @Test fun automaticSignInFailuresPauseUntilManualRetry() = runBlocking {
        SchoolStore(context).save(record())
        val classic = ClassicFixture().apply { rejectLogin = true }
        val manager = newManager(MobileFixture(), classic)
        manager.refreshForJob()
        assertEquals(SchoolAdminStatus.SIGN_IN, manager.state.value.adminStatus)
        val before = classic.calls.size
        elapsed += 600_000L
        manager.refreshForJob()
        assertEquals("Automatic checks must not keep retrying rejected credentials", before, classic.calls.size)
        classic.rejectLogin = false
        manager.refresh()
        assertEquals(SchoolAdminStatus.OK, manager.state.value.adminStatus)
        elapsed += 300_000L
        manager.refreshForJob()
        assertTrue(classic.calls.size > before)
    }

    @Test fun administrationAlertCategoryMigratesOnceAndKeepsAnExplicitDisabledChoice() {
        val old = record().json().apply {
            remove("administrationCategory")
            put("categories", JSONArray(listOf(SchoolAlert.ROOMS.name)))
        }
        val migrated = SchoolRecord.read(old)
        assertEquals(setOf(SchoolAlert.ROOMS, SchoolAlert.ADMINISTRATION_MESSAGES), migrated.notifications.categories)
        assertFalse(migrated.notifications.enabled)
        val disabled = migrated.copy(notifications = migrated.notifications.copy(categories = setOf(SchoolAlert.ROOMS)))
        assertEquals(disabled, SchoolRecord.read(disabled.json()))
    }

    @Test fun backgroundRefreshWaitsUntilAdministrationMessagesHaveBeenSaved() = runBlocking {
        SchoolStore(context).save(record())
        val gate = Gate().also { gates += it }
        val manager = newManager(MobileFixture(), ClassicFixture().apply { detailGate = gate })
        val refreshing = async(Dispatchers.Default) { manager.refresh() }
        gate.awaitEntered()
        val task = async(Dispatchers.Default) { manager.refreshForJob() }
        delay(50)
        assertFalse("The background job must remain active during the administration response", task.isCompleted)
        gate.release()
        withTimeout(TIMEOUT) { refreshing.await() }
        withTimeout(TIMEOUT) { task.await() }
        assertEquals(SchoolAdminStatus.OK, manager.state.value.adminStatus)
        assertEquals(manager.state.value.admin, checkNotNull(SchoolMessagesStore(context).load()).admin)
    }

    private fun newManager(mobile: MobileFixture, classic: ClassicFixture,
        onNewMessages: (SchoolRecord, List<SchoolAdminNotice>) -> Unit = { _, _ -> },
    ) = SchoolMessagesManager(context,
        clientFactory = { address, language -> SchoolPortalClient(address, language, mobile::open) },
        classicFactory = { classic.reader() }, onNoticesChanged = {}, onNewMessages = onNewMessages, elapsedRealtime = { elapsed },
    ).also { managers += it }

    private fun record(calendarId: Long = -1) = SchoolRecord(BASE.toString(), "synthetic-student", "synthetic-password", zone.id, calendarId,
        schoolHash("$BASE\nsynthetic-student-key"), UUID.randomUUID().toString(), "synthetic-token-only", Long.MAX_VALUE)

    private suspend fun await(predicate: () -> Boolean) = withTimeout(TIMEOUT) { while (!predicate()) delay(10) }

    private fun frenchDate(date: LocalDate = today) = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]} ${date.year}"

    private fun createCalendar() {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, fixtureId); put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, fixtureId); put(Calendars.CALENDAR_DISPLAY_NAME, "School messages synthetic fixture")
            put(Calendars.OWNER_ACCOUNT, fixtureId); put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.VISIBLE, 1); put(Calendars.SYNC_EVENTS, 1); put(Calendars.CALENDAR_TIME_ZONE, zone.id)
        }
        calendar = ContentUris.parseId(checkNotNull(context.contentResolver.insert(calendarUri(), values)))
    }

    private fun calendarUri() = Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, fixtureId)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private inner class ClassicFixture {
        var redirect = "accueil.asp"
        var detailLink = "affmessdir.asp?NoMess=1"
        var subject = "Levée des cours le ${frenchDate()}"
        var body = "Les cours sont levés le ${frenchDate()} jusqu'à 18h."
        var empty = false
        var rejectLogin = false
        var failDetail = false
        var malformedDetail = false
        var detailGate: Gate? = null
        var phoneLayout = false
        val calls = Collections.synchronizedList(mutableListOf<Connection>())
        private val desktopLogin = """<form method="post" action="login.asp"><input type="hidden" name="csrf" value="nonce&amp;guard"><input name="txtCodeUsager"><input type="password" name="txtMotDePasse"><input type="image" name="btnConnecter"></form>"""
        private val phoneLogin = """<form method=post  name="frmLogin" action=/colnet/login.asp><input type=text name='txtLibWebBidon' style='display:none;'><input id=txtCodeUsager type=text name=txtCodeUsager size=17 maxlength=15 value="" ><input id=txtMotDePasse type=password name=txtMotDePasse size=32 maxlength=50 value="" ><a href="javascript:DoPostBack('TOKEN','?TOKEN')">Connexion</a><input type=hidden name=_PostBackInfo value=""><input type=hidden name=_PostBackPar value=""><input type=hidden name=_NoSeqWeb value=""></form>"""
        private val login get() = if (phoneLayout) phoneLogin else desktopLogin
        fun reader(direct: ((URI) -> HttpsURLConnection)? = null) = SchoolClassicPortal(BASE,
            if (direct == null) listOf(::open) else listOf(direct, ::open))
        private fun open(uri: URI): HttpsURLConnection {
            check(uri.host == HOST) { "Synthetic classic transport must never leave its origin" }
            val loginRequest = uri.path.endsWith("login.asp")
            val isPost = loginRequest && calls.count { it.url.path.endsWith("login.asp") } % 2 == 1
            val html = when {
                loginRequest -> login
                uri.path.endsWith("accueil.asp") -> "<frameset><frame src='dashboard.asp'></frameset>"
                uri.path.endsWith("dashboard.asp") -> "<table><tr><td>Date</td><td>Objet</td><td>De</td></tr>" +
                    (if (empty) "" else "<tr><td>$today</td><td><a href='$detailLink'>$subject</a></td><td>Direction générale</td></tr>") + "</table>"
                uri.path.endsWith("affmessdir.asp") -> if (malformedDetail) "<p>Synthetic unrecognized detail</p>" else
                    "<table><tr><td>Date</td><td>$today</td></tr><tr><td>Objet</td><td>$subject</td></tr>" +
                        "<tr><td>Source du message</td><td>Direction générale</td></tr><tr><td>Message</td><td>$body</td></tr></table>"
                else -> error("Unexpected classic fixture path: ${uri.path}")
            }
            val headers = if (isPost && !rejectLogin) mapOf("Location" to listOf(redirect), "Set-Cookie" to listOf("ASPSESSIONID=after; Path=/colnet/; Secure; HttpOnly"))
                else if (loginRequest) mapOf("Set-Cookie" to listOf("ASPSESSIONID=before; Path=/colnet/; Secure; HttpOnly")) else emptyMap()
            val status = when { isPost && !rejectLogin -> 302; failDetail && uri.path.endsWith("affmessdir.asp") -> 503; else -> 200 }
            return Connection(uri, html, status, "text/html; charset=UTF-8", headers,
                beforeResponse = if (uri.path.endsWith("affmessdir.asp")) ({ detailGate?.block(); Unit }) else null).also { calls += it }
        }
    }

    private inner class MobileFixture {
        val calls = Collections.synchronizedList(mutableListOf<URI>())
        fun open(uri: URI): HttpsURLConnection {
            calls += uri
            error("Administration refresh must not request any mobile mailbox route: ${uri.path}")
        }
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun block() { entered.countDown(); if (!released.await(TIMEOUT, TimeUnit.MILLISECONDS)) throw IOException("Synthetic response gate timed out") }
        fun awaitEntered() { assertTrue("The manager must reach the response gate", entered.await(TIMEOUT, TimeUnit.MILLISECONDS)) }
        fun release() { released.countDown() }
    }

    private class Connection(uri: URI, private val body: String, private val status: Int = 200,
        private val mime: String = "application/json", private val headers: Map<String, List<String>> = emptyMap(),
        private val beforeResponse: (() -> Unit)? = null,
    ) : HttpsURLConnection(uri.toURL()) {
        val output = ByteArrayOutputStream()
        override fun getResponseCode(): Int { beforeResponse?.invoke(); return status }
        override fun getHeaderFields(): Map<String, List<String>> = headers
        override fun getHeaderField(name: String?): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        override fun getContentType() = mime
        override fun getContentLengthLong() = body.toByteArray(Charsets.UTF_8).size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = output
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
        const val HOST = "school-messages.example.invalid"
        val BASE = URI("https://$HOST/colnet/mobile/")
        const val TIMEOUT = 15_000L
        val MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
    }
}
