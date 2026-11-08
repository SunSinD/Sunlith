package io.github.sunsind.sunlith.school

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.sunsind.sunlith.MainActivity
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.insertLocalCalendar
import io.github.sunsind.sunlith.localCalendarUri
import io.github.sunsind.sunlith.requireEmulator
import io.github.sunsind.sunlith.restoreTestPreferences
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.cert.Certificate
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
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
class SchoolNotificationsFixtureTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var base: Context
    private lateinit var context: Context
    private lateinit var notifications: NotificationManager
    private lateinit var notifier: SchoolNotifications
    private lateinit var directory: File
    private lateinit var fixtureId: String
    private var calendar = 0L
    private var unrelatedPostTime = -1L
    private var ownsNotifications = false
    private var channelsBefore = emptyMap<String, NotificationChannel>()
    private var groupsBefore = emptyMap<String, NotificationChannelGroup>()
    private val preferenceNames = Collections.synchronizedSet(mutableSetOf<String>())
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private val pendingIntents = mutableSetOf<PendingIntent>()
    private val scheduler = MemoryJobScheduler()
    private var manager: SchoolScheduleManager? = null
    private var activity: MainActivity? = null
    private lateinit var vm: SunlithViewModel
    private val zone = ZoneId.of("America/Toronto")
    private val today = LocalDate.now(zone)
    private val day = today.plusDays(2)

    @Before fun fixture() {
        requireEmulator()
        base = instrumentation.targetContext
        notifications = base.getSystemService(NotificationManager::class.java)
        assumeTrue("School notification tags must be unused", schoolNotifications().isEmpty())
        assertTrue("The fixture explicitly grants notification permission", Build.VERSION.SDK_INT < 33 ||
            base.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assertTrue("App notifications must be enabled on the fixture emulator", notifications.areNotificationsEnabled())
        channelsBefore = notifications.notificationChannels.associateBy { it.id }
        groupsBefore = notifications.notificationChannelGroups.associateBy { it.id }
        listOf(CHANGES, PROBLEMS).forEach { id ->
            channelsBefore[id]?.let {
                assumeTrue("Existing school channels must have restorable importance", it.importance in
                    NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_DEFAULT)
                assumeTrue("Existing school channels must already belong to the school group", it.group == GROUP)
            }
        }
        groupsBefore[GROUP]?.let { assumeTrue("Existing school group must allow delivery", !it.isBlocked) }
        ownsNotifications = true
        fixtureId = "school-notifications-fixture-${UUID.randomUUID()}"
        directory = File(base.noBackupFilesDir, fixtureId).apply { check(mkdirs()) }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$fixtureId-$name"
                preferenceNames += isolated
                return base.getSharedPreferences(isolated, mode)
            }
            override fun getSystemService(name: String): Any? =
                if (name == Context.JOB_SCHEDULER_SERVICE) scheduler else super.getSystemService(name)
        }
        check(context.getSystemService(JobScheduler::class.java) === scheduler)
        calendar = insertLocalCalendar(context, fixtureId, "Synthetic school notification calendar", fixtureId)
        notifier = SchoolNotifications(context)
        notifications.createNotificationChannel(NotificationChannel(fixtureId, "Synthetic unrelated notification", NotificationManager.IMPORTANCE_LOW))
        notifications.notify(fixtureId, 91, Notification.Builder(base, fixtureId)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Synthetic unrelated notification").build())
        await("Unrelated notification must reach Android") { unrelated() != null }
        unrelatedPostTime = requireNotNull(unrelated()).postTime
    }

    @After fun cleanup() {
        try {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            instrumentation.waitForIdleSync()
            manager?.let { current -> runBlocking { withTimeout(TIMEOUT) { current.onBackground(); current.disconnect(false) } } }
        } finally {
            if (ownsNotifications) {
                notifications.cancel("school-changes", 0)
                notifications.cancel("school-connection", 0)
                if (::fixtureId.isInitialized) notifications.cancel(fixtureId, 91)
                await("Fixture school notifications must finish cancellation") { schoolNotifications().isEmpty() }
                pendingIntents.forEach { it.cancel() }
                if (::fixtureId.isInitialized) notifications.deleteNotificationChannel(fixtureId)
                groupsBefore[GROUP]?.let { notifications.createNotificationChannelGroup(it) }
                listOf(CHANGES, PROBLEMS).forEach { id ->
                    channelsBefore[id]?.let { notifications.createNotificationChannel(it) }
                        ?: notifications.deleteNotificationChannel(id)
                }
                if (GROUP !in groupsBefore) notifications.deleteNotificationChannelGroup(GROUP)
            }
            if (calendar != 0L) context.contentResolver.delete(localCalendarUri(calendar, fixtureId), null, null)
            if (::base.isInitialized) {
                snapshots.forEach { (name, values) -> restoreTestPreferences(base.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
                preferenceNames.forEach { base.deleteSharedPreferences(it) }
            }
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun everyScheduleCategoryFiltersRealPostedEventsAndLabels() {
        scheduleCategories().forEach { (alert, label) ->
            val selected = item("selected", title = "Selected synthetic event")
            val other = item("other", date = day.minusDays(1), title = "Filtered synthetic event")
            val (before, after) = when (alert) {
                SchoolAlert.TEACHER_ABSENCES -> listOf(selected) to listOf(selected.copy(teacherAbsent = true))
                SchoolAlert.CLASS_TIMES -> listOf(selected) to listOf(selected.copy(startMillis = selected.startMillis + 60_000))
                SchoolAlert.ROOMS -> listOf(selected) to listOf(selected.copy(rooms = listOf("B200"), teachers = listOf("Synthetic Substitute")))
                SchoolAlert.TEACHERS -> listOf(selected) to listOf(selected.copy(teachers = listOf("Synthetic Substitute")))
                SchoolAlert.ADDED_CLASSES -> emptyList<SchoolItem>() to listOf(selected)
                SchoolAlert.REMOVED_CLASSES -> listOf(selected) to emptyList()
                SchoolAlert.ASSESSMENTS -> emptyList<SchoolItem>() to listOf(selected.copy(kind = "07"))
                SchoolAlert.SCHOOL_DAYS -> emptyList<SchoolItem>() to listOf(selected.copy(kind = "08"))
                else -> error("Not a schedule category")
            }
            val unrelatedChange = if (alert == SchoolAlert.ROOMS) other.copy(teachers = listOf("Filtered Teacher"))
                else other.copy(rooms = listOf("Filtered room"))
            notifier.changes(record(setOf(alert)), SchoolDiff.changes(before + other, after + unrelatedChange, today))

            val posted = posted("school-changes")
            val notification = posted.notification
            assertEquals(CHANGES, notification.channelId)
            assertEquals(Notification.CATEGORY_EVENT, notification.category)
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertEquals(base.resources.getQuantityString(R.plurals.school_sync_changes_count, 1, 1), text(notification))
            val detail = bigText(notification)
            assertEquals("$alert must label only the enabled category", base.getString(label), detail.lineSequence().first())
            assertTrue("$alert must identify the affected date and event", detail.contains("$day: ${selected.title}"))
            assertFalse("Disabled events must not leak into notification details", detail.contains(other.title))
            assertFalse(detail.contains("Filtered room"))
            assertNoCredentials(notification)
            assertUnrelatedPreserved()
            clearSchool()
        }
    }

    @Test fun masterOffAndEmptyOrUnrelatedCategoriesDoNotPost() {
        val before = item("room")
        val changes = SchoolDiff.changes(listOf(before), listOf(before.copy(rooms = listOf("B200"))), today)
        listOf(
            record().copy(notifications = SchoolNotificationPreferences(enabled = false)),
            record(emptySet()),
            record(setOf(SchoolAlert.TEACHERS)),
        ).forEach { current ->
            notifier.changes(current, changes)
            notifier.failure(current, SchoolError.NETWORK)
            assertSchoolQuiet()
        }
        notifier.changes(record(setOf(SchoolAlert.ROOMS)), changes)
        posted("school-changes")
        assertUnrelatedPreserved()
    }

    @Test fun pastAdditionsRemovalsAndAbsenceChangesStaySilentButTodayPosts() {
        val old = item("past-change", today.minusDays(1))
        val removed = item("past-removal", today.minusDays(2))
        val added = item("past-addition", today.minusDays(3))
        notifier.changes(record(), SchoolDiff.changes(
            listOf(old, removed), listOf(old.copy(teacherAbsent = true, notes = listOf("Synthetic absence notice")), added), today,
        ))
        assertSchoolQuiet()

        val current = item("today", today)
        notifier.changes(record(), SchoolDiff.changes(listOf(current), listOf(current.copy(teacherAbsent = true)), today))
        assertTrue(bigText(posted("school-changes").notification).contains("$today: ${current.title}"))
        assertUnrelatedPreserved()
    }

    @Test fun changedAbsenceNoticePostsTheAffectedClassAndPreservesItsSavedDetail() = fixtureTest {
        val portal = PortalFixture()
        val current = connect(portal)
        current.setNotifications(SchoolNotificationPreferences(enabled = true, categories = setOf(SchoolAlert.TEACHER_ABSENCES)))
        portal.absent = true
        portal.notice = "Synthetic original notice"
        current.refresh()
        posted("school-changes")
        clearSchool()
        portal.notice = "Synthetic replacement notice"

        current.refresh()

        val notification = posted("school-changes").notification
        assertTrue(bigText(notification).contains(base.getString(R.string.school_alert_teacher_absences)))
        assertTrue(bigText(notification).contains("$day: Synthetic school class"))
        assertTrue(bigText(notification).contains(portal.notice))
        assertFalse(bigText(notification).contains("Synthetic original notice"))
        assertEquals(base.resources.getQuantityString(R.plurals.school_sync_changes_count, 1, 1), text(notification))
        assertNull(current.state.value.error)
        val saved = CalendarRepository(context).getEvent(SchoolStore(context).load()!!.ledger.single().rowId)!!
        assertEquals(base.getString(R.string.school_sync_absent_title, "Synthetic school class"), saved.title)
        assertTrue(saved.description.contains(base.getString(R.string.school_sync_teacher_absent)))
        assertTrue(saved.description.contains(portal.notice))
        assertFalse(saved.description.contains("Synthetic original notice"))
        assertNoCredentials(notification)
        assertUnrelatedPreserved()
    }

    @Test fun connectionErrorsUseTheProblemChannelAndSafeActionableText() {
        SchoolError.entries.forEach { error ->
            notifier.failure(record(setOf(SchoolAlert.CONNECTION_PROBLEMS)), error)
            val notification = posted("school-connection").notification
            assertEquals(PROBLEMS, notification.channelId)
            assertEquals(Notification.CATEGORY_ERROR, notification.category)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            val message = if (error in setOf(SchoolError.SIGN_IN, SchoolError.MFA_REQUIRED))
                R.string.school_sync_sign_in_notification else R.string.school_sync_connection_notification
            assertEquals(base.getString(message), text(notification))
            assertNoCredentials(notification)
            assertUnrelatedPreserved()
            clearSchool()
        }
    }

    @Test fun successfulRefreshClearsOnlyTheFailureAndARepeatFailureDoesNotRepost() = fixtureTest {
        val portal = PortalFixture()
        val current = connect(portal)
        current.setNotificationsEnabled(true)
        portal.room = "B200"
        current.refresh()
        val change = posted("school-changes")
        portal.offline = true
        current.refresh()
        assertEquals(SchoolError.NETWORK, current.state.value.error)
        val failure = posted("school-connection")
        SystemClock.sleep(25)
        current.refresh()
        assertEquals(SchoolError.NETWORK, current.state.value.error)
        assertEquals("An unchanged failure must not alert again", failure.postTime, posted("school-connection").postTime)

        portal.offline = false
        current.refresh()

        assertNull(current.state.value.error)
        assertNull(SchoolStore(context).load()!!.lastFailure)
        await("A successful refresh must clear the failure") { schoolNotifications().none { it.tag == "school-connection" } }
        assertEquals("Recovery must retain the existing change notice", change.postTime, posted("school-changes").postTime)
        assertUnrelatedPreserved()
    }

    @Test fun signInFailureAfterNetworkFailureUpdatesTheActionableNotification() = fixtureTest {
        val portal = PortalFixture()
        val current = connect(portal)
        current.setNotificationsEnabled(true)
        portal.offline = true
        current.refresh()
        assertEquals(base.getString(R.string.school_sync_connection_notification), text(posted("school-connection").notification))
        portal.offline = false
        portal.signInFailure = true
        current.refresh()
        assertEquals(SchoolError.SIGN_IN, current.state.value.error)
        await("An actionable sign-in error must replace the earlier network error") {
            schoolNotifications().singleOrNull { it.tag == "school-connection" }?.notification?.let { text(it) } == base.getString(R.string.school_sync_sign_in_notification)
        }
        assertUnrelatedPreserved()
    }

    @Test fun interruptedDisconnectRecoveryClearsOnlySchoolNotifications() = fixtureTest {
        val portal = PortalFixture()
        val current = connect(portal)
        current.setNotificationsEnabled(true)
        portal.room = "B200"
        current.refresh()
        posted("school-changes")
        portal.offline = true
        current.refresh()
        posted("school-connection")
        SchoolStore(context).save(SchoolStore(context).load()!!.copy(disconnecting = "release"))
        val recovered = SchoolScheduleManager(context) { address, language -> SchoolPortalClient(address, language, portal::open) }
        manager = recovered
        recovered.refresh()
        assertFalse(recovered.state.value.connected)
        assertNull(SchoolStore(context).load())
        await("Recovered disconnect must clear school notifications") { schoolNotifications().isEmpty() }
        assertUnrelatedPreserved()
    }

    @Test fun masterDisableAndDisconnectClearBothSchoolTagsOnly() = fixtureTest {
        val portal = PortalFixture()
        val current = connect(portal)
        current.setNotificationsEnabled(true)
        portal.room = "B200"
        current.refresh()
        posted("school-changes")
        portal.offline = true
        current.refresh()
        posted("school-connection")

        current.setNotificationsEnabled(false)

        assertFalse(current.state.value.notifications.enabled)
        assertFalse(SchoolStore(context).load()!!.notifications.enabled)
        await("Disabling alerts must clear both school tags") { schoolNotifications().isEmpty() }
        assertSchoolQuiet()
        assertUnrelatedPreserved()
        portal.offline = false
        current.refresh()
        current.setNotificationsEnabled(true)
        portal.room = "C300"
        current.refresh()
        posted("school-changes")
        portal.offline = true
        current.refresh()
        posted("school-connection")

        current.disconnect(false)

        assertFalse(current.state.value.connected)
        assertNull(SchoolStore(context).load())
        await("Disconnecting must clear both school tags") { schoolNotifications().isEmpty() }
        assertSchoolQuiet()
        assertUnrelatedPreserved()
    }

    @Test fun heldRemovalNotifiesOnceSurvivesRestartAndRequiresExplicitApply() = fixtureTest {
        val portal = PortalFixture()
        var current = connect(portal)
        current.setNotifications(SchoolNotificationPreferences(true, setOf(SchoolAlert.REMOVED_CLASSES)))
        val before = SchoolStore(context).load()!!
        portal.empty = true
        current.refresh()
        assertEquals(SchoolChangeSummary(0, 0, 1), current.state.value.pendingChanges)
        assertEquals(before.ledger, SchoolStore(context).load()!!.ledger)
        val review = posted("school-review")
        assertEquals(base.getString(R.string.school_sync_review_notification), text(review.notification))
        current.refresh()
        assertEquals("An unchanged held update must not repost", review.postTime, posted("school-review").postTime)
        notifier.clearReview()
        await("The dismissed review must clear") { schoolNotifications().none { it.tag == "school-review" } }
        current.onBackground()
        current = SchoolScheduleManager(context) { address, language -> SchoolPortalClient(address, language, portal::open) }
        manager = current
        current.refresh()
        assertEquals(SchoolChangeSummary(0, 0, 1), current.state.value.pendingChanges)
        assertTrue("Restarting must not replay a dismissed review", schoolNotifications().none { it.tag == "school-review" })
        assertNotNull(CalendarRepository(context).getEvent(before.ledger.single().rowId))
        current.applyPendingChanges()
        assertNull(current.state.value.error)
        assertNull(current.state.value.pendingChanges)
        assertEquals(0, current.state.value.eventCount)
        assertNull(CalendarRepository(context).getEvent(before.ledger.single().rowId))
        assertTrue(schoolNotifications().none { it.tag == "school-review" })
        assertUnrelatedPreserved()
    }

    @Test fun reviewRespectsCategoriesAndOpensSettingsIndependentlyOfFailureAndEventActions() {
        launchNavigation()
        val source = item("held-removal")
        val changes = SchoolDiff.changes(listOf(source), emptyList(), today)
        notifier.review(record(setOf(SchoolAlert.ROOMS)), changes)
        assertSchoolQuiet()
        notifier.review(record(setOf(SchoolAlert.REMOVED_CLASSES)), changes)
        val review = posted("school-review").notification.contentIntent
        notifier.changes(record(), SchoolDiff.changes(emptyList(), listOf(source), today))
        val change = posted("school-changes").notification.contentIntent
        notifier.failure(record(), SchoolError.NETWORK)
        val failure = posted("school-connection").notification.contentIntent
        assertNotEquals(review, change)
        assertNotEquals(review, failure)
        assertSchoolIntent(send(review), "review", MainActivity.EXTRA_SCHOOL_SETTINGS, true)
        awaitMain("Review notifications must open School schedule") {
            vm.stack.toList() == listOf(Screen.Settings, Screen.Calendars, Screen.SchoolSchedule)
        }
        clearSchool()
        assertSchoolQuiet()
        assertUnrelatedPreserved()
    }

    @Test fun changePendingIntentOpensEarliestEnabledDateAndUpdatesAnExistingToken() {
        launchNavigation()
        val later = item("later", day.plusDays(3))
        val earlier = item("earlier", day)
        val filtered = item("filtered", today)
        notifier.changes(record(setOf(SchoolAlert.ADDED_CLASSES)), SchoolDiff.changes(
            listOf(filtered), listOf(later, earlier, filtered.copy(rooms = listOf("B200"))), today,
        ))
        val pending = posted("school-changes").notification.contentIntent
        val delivered = send(pending, Intent().putExtra(MainActivity.EXTRA_SCHOOL_DATE, today.plusDays(50).toString())
            .putExtra(MainActivity.EXTRA_SCHOOL_SETTINGS, true).putExtra("password", "Synthetic injected credential"))

        assertSchoolIntent(delivered, "changes", MainActivity.EXTRA_SCHOOL_DATE, day.toString())
        awaitMain("Change notification must open the earliest enabled event") {
            vm.selectedDate == day && vm.viewMode == ViewMode.Day && vm.stack.isEmpty()
        }
        onMain { assertFalse(requireNotNull(activity).intent.hasExtra(MainActivity.EXTRA_SCHOOL_DATE)) }

        val replacement = item("replacement", day.plusDays(6))
        notifier.changes(record(), SchoolDiff.changes(emptyList(), listOf(replacement), today))
        val updated = posted("school-changes").notification.contentIntent
        assertEquals("Replacement notices must update the existing PendingIntent", pending, updated)
        val replacementIntent = send(pending)
        assertSchoolIntent(replacementIntent, "changes", MainActivity.EXTRA_SCHOOL_DATE, replacement.date.toString())
        awaitMain("A previously obtained token must open the new notification date") { vm.selectedDate == replacement.date }
        onMain { requireNotNull(activity).finish() }
        instrumentation.waitForIdleSync()
        activity = instrumentation.startActivitySync(Intent(replacementIntent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        onMain { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java] }
        awaitMain("The delivered school intent must also route through onCreate") {
            vm.loaded && vm.selectedDate == replacement.date && vm.viewMode == ViewMode.Day && vm.stack.isEmpty()
        }
        assertUnrelatedPreserved()
    }

    @Test fun connectionPendingIntentOpensSchoolSettingsAndKeepsChangeDateSeparate() {
        launchNavigation()
        val selected = item("selected")
        notifier.changes(record(), SchoolDiff.changes(emptyList(), listOf(selected), today))
        val change = posted("school-changes").notification.contentIntent
        notifier.failure(record(), SchoolError.SIGN_IN)
        val connection = posted("school-connection").notification.contentIntent
        assertNotEquals("Connection and event notifications need independent actions", change, connection)

        assertSchoolIntent(send(connection), "connection", MainActivity.EXTRA_SCHOOL_SETTINGS, true)
        awaitMain("Connection problems must open School schedule settings") {
            vm.stack.toList() == listOf(Screen.Settings, Screen.Calendars, Screen.SchoolSchedule)
        }
        onMain { assertFalse(requireNotNull(activity).intent.hasExtra(MainActivity.EXTRA_SCHOOL_SETTINGS)) }
        assertSchoolIntent(send(change), "changes", MainActivity.EXTRA_SCHOOL_DATE, selected.date.toString())
        awaitMain("Opening settings must preserve the event notification's date") {
            vm.selectedDate == selected.date && vm.viewMode == ViewMode.Day && vm.stack.isEmpty()
        }
        assertUnrelatedPreserved()
    }

    @Test fun schoolNotificationWaitsForOnboardingAndDoesNotDiscardADirtyDraft() {
        launchNavigation(onboardingDone = false)
        notifier.changes(record(), SchoolDiff.changes(emptyList(), listOf(item("queued")), today))
        send(posted("school-changes").notification.contentIntent)
        onMain {
            vm.deliverPendingLink()
            assertTrue(vm.stack.isEmpty())
            assertNotEquals(day, vm.selectedDate)
            vm.finishAiIntro()
            vm.deliverPendingLink()
            assertFalse(vm.layoutSetupDone)
            assertNotEquals(day, vm.selectedDate)
            vm.finishLayoutSetup()
            vm.deliverPendingLink()
        }
        awaitMain("The queued school date must be delivered after setup") { vm.selectedDate == day && vm.viewMode == ViewMode.Day }
        onMain {
            vm.newEvent(day)
            vm.updateEditor { it.copy(title = "Synthetic unsaved draft") }
            assertTrue(vm.editorDirty)
        }
        notifier.failure(record(), SchoolError.NETWORK)
        send(posted("school-connection").notification.contentIntent)
        onMain {
            assertEquals("Synthetic unsaved draft", vm.editor?.title)
            assertTrue(vm.editorDirty)
            assertEquals(Screen.Editor, vm.stack.last())
            assertFalse(vm.stack.contains(Screen.SchoolSchedule))
        }
    }

    private fun scheduleCategories() = listOf(
        SchoolAlert.TEACHER_ABSENCES to R.string.school_alert_teacher_absences,
        SchoolAlert.CLASS_TIMES to R.string.school_alert_class_times,
        SchoolAlert.ROOMS to R.string.school_alert_rooms,
        SchoolAlert.TEACHERS to R.string.school_alert_teachers,
        SchoolAlert.ADDED_CLASSES to R.string.school_alert_added,
        SchoolAlert.REMOVED_CLASSES to R.string.school_alert_removed,
        SchoolAlert.ASSESSMENTS to R.string.school_alert_assessments,
        SchoolAlert.SCHOOL_DAYS to R.string.school_alert_school_days,
    )

    private fun item(id: String, date: LocalDate = day, title: String = "Synthetic school class") = SchoolItem(
        id = id, classKey = "synthetic-class-$id", title = title, course = "123ABC45", group = "000123",
        classKind = "2", kind = "01", date = date,
        startMillis = date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(),
        endMillis = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
        allDay = false, zone = zone.id, teachers = listOf("Synthetic Teacher"), rooms = listOf("A100"),
        notes = emptyList(), teacherAbsent = false,
    )

    private fun record(categories: Set<SchoolAlert> = SchoolAlert.entries.toSet()) = SchoolRecord(
        portal = PORTAL, username = USERNAME, password = PASSWORD, zone = zone.id, calendarId = calendar,
        account = "synthetic-account", connection = fixtureId, token = TOKEN, expiresAt = Long.MAX_VALUE,
        notifications = SchoolNotificationPreferences(enabled = true, categories = categories),
    )

    private fun schoolNotifications() = notifications.activeNotifications.filter { it.tag in setOf("school-changes", "school-connection", "school-review") }
    private fun unrelated() = notifications.activeNotifications.singleOrNull { it.tag == fixtureId && it.id == 91 }
    private fun text(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun bigText(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()

    private fun posted(tag: String): StatusBarNotification {
        await("Android must post $tag") { schoolNotifications().any { it.tag == tag } }
        return schoolNotifications().single { it.tag == tag }.also { posted ->
            assertEquals(0, posted.id)
            assertEquals(base.packageName, posted.packageName)
            assertNotNull(posted.notification.contentIntent)
            pendingIntents += posted.notification.contentIntent
            if (Build.VERSION.SDK_INT >= 31) assertTrue(posted.notification.contentIntent.isImmutable)
        }
    }

    private fun clearSchool() {
        notifier.clearAll()
        await("Only school notifications should be cleared") { schoolNotifications().isEmpty() }
    }

    private fun assertSchoolQuiet() {
        val until = SystemClock.elapsedRealtime() + 500
        do {
            assertTrue("No school notification should be delivered", schoolNotifications().isEmpty())
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < until)
        assertUnrelatedPreserved()
    }

    private fun assertUnrelatedPreserved() {
        assertNotNull("School operations must preserve unrelated notifications", unrelated())
        assertEquals("School operations must not replace unrelated notifications", unrelatedPostTime, unrelated()!!.postTime)
        channelsBefore.filterKeys { it !in setOf(CHANGES, PROBLEMS) }.forEach { (id, original) ->
            assertEquals("School delivery must preserve unrelated channel $id", original, notifications.getNotificationChannel(id))
        }
    }

    private fun assertNoCredentials(notification: Notification) {
        val visible = listOf(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(), text(notification), bigText(notification)).joinToString("\n")
        listOf(PORTAL, USERNAME, PASSWORD, TOKEN, "synthetic-account").forEach {
            assertFalse("Notification text must not contain synthetic credentials", visible.contains(it))
        }
    }

    private fun fixtureTest(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun connect(portal: PortalFixture): SchoolScheduleManager {
        val current = SchoolScheduleManager(context) { address, language -> SchoolPortalClient(address, language, portal::open) }
        manager = current
        current.connect(PORTAL, USERNAME, PASSWORD, zone.id, calendar)
        assertNull(current.state.value.error)
        assertTrue(current.state.value.connected)
        assertTrue(SchoolStore(context).load()!!.initialized)
        assertSchoolQuiet()
        return current
    }

    private fun launchNavigation(onboardingDone: Boolean = true) {
        listOf("school-connection-v1.bin", "school-connection-v1.bin.bak", "school-connection-v1.bin.new").forEach {
            assumeTrue("Navigation fixture requires an emulator without a real school connection", !File(base.noBackupFilesDir, it).exists())
        }
        listOf("sunlith", "sunlith_ui").forEach { name -> snapshots[name] = base.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        SettingsStore(base).apply {
            selectedCalendarIds = setOf(calendar)
            hasChosenCalendars = true
            remindersEnabled = false
            startView = "Month"
        }
        check(base.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("ai_intro_done", onboardingDone).putBoolean("layout_setup_done", onboardingDone)
            .putBoolean("notify_asked", true).putBoolean("computer_setup_requested", false).putLong("last_calendar", calendar).commit())
        activity = instrumentation.startActivitySync(Intent(base, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        onMain { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java] }
        awaitMain("Synthetic navigation calendar must load") { vm.loaded && !vm.loadingEvents }
        onMain { assertNull(vm.loadError) }
    }

    private fun send(pending: PendingIntent, fillIn: Intent? = null): Intent {
        val received = AtomicReference<Intent>()
        val finished = CountDownLatch(1)
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        pending.send(requireNotNull(activity), 0, fillIn, { _, intent, _, _, _ ->
            received.set(Intent(intent))
            finished.countDown()
        }, Handler(Looper.getMainLooper()), null, options.toBundle())
        assertTrue("Android must deliver the actual PendingIntent", finished.await(TIMEOUT, TimeUnit.MILLISECONDS))
        instrumentation.waitForIdleSync()
        return requireNotNull(received.get())
    }

    private fun assertSchoolIntent(intent: Intent, kind: String, key: String, value: Any) {
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(base.packageName, intent.component?.packageName)
        assertEquals("sunlith://school/$kind", intent.dataString)
        assertNull(intent.action)
        assertNull(intent.clipData)
        assertNull(intent.selector)
        assertEquals(setOf(key), intent.extras?.keySet())
        when (value) {
            is String -> assertEquals(value, intent.getStringExtra(key))
            is Boolean -> assertEquals(value, intent.getBooleanExtra(key, false))
            else -> error("Unexpected school link extra")
        }
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        val payload = intent.toUri(Intent.URI_INTENT_SCHEME)
        listOf(PORTAL, USERNAME, PASSWORD, TOKEN, "synthetic-account", "Synthetic injected credential").forEach {
            assertFalse("School PendingIntents must never carry credentials", payload.contains(it))
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun awaitMain(message: String, condition: () -> Boolean) = await(message) {
        var result = false
        onMain { result = condition() }
        result
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue(message, condition())
    }

    private inner class PortalFixture {
        @Volatile var room = "A100"
        @Volatile var offline = false
        @Volatile var signInFailure = false
        @Volatile var absent = false
        @Volatile var notice = ""
        @Volatile var empty = false

        fun open(uri: URI): HttpsURLConnection {
            if (offline) throw IOException("Synthetic offline response")
            if (signInFailure) return FakeConnection(uri, "{}", 401)
            val body = when {
                uri.path.endsWith("token") -> JSONObject().put("access_token", TOKEN).put("expires_in", 2592000).toString()
                uri.path.endsWith("infoMFA") -> "{\"IndMFA\":false,\"ServicesNonDisponibles\":false}"
                uri.path.endsWith("accesPermis") -> "{\"Acces\":true}"
                uri.path.endsWith("informations") -> "{\"CleUnique\":\"synthetic-student-id\",\"PeriodeEtudesHoraire\":\"FIXTURE\",\"ServicesAffiches\":[\"AGENDA\",\"CANCELED_SESSIONS\"]}"
                uri.path.endsWith("periodesEtudes") -> JSONArray().put(JSONObject().put("Code", "FIXTURE").put("Description", "Synthetic term")
                    .put("DateDebut", SchoolSource.wireDate(today.minusDays(7))).put("DateFin", SchoolSource.wireDate(today.plusDays(14)))).toString()
                uri.path.endsWith("agenda") && empty -> "[]"
                uri.path.endsWith("agenda") -> JSONArray().put(JSONObject()
                    .put("CleUnique", "synthetic-event").put("CleClasse", "synthetic-class").put("Titre", "Synthetic school class")
                    .put("Cours", "123ABC45").put("Groupe", "000123").put("TypeClasse", "2").put("Type", "01")
                    .put("Date", SchoolSource.wireDate(day)).put("JourneeComplete", false).put("HeureDebut", "0800").put("HeureFin", "1000")
                    .put("IndAbsence", absent).put("Commentaire", notice)
                    .put("Enseignants", JSONArray().put(JSONObject().put("Nom", "Synthetic Teacher")))
                    .put("Locaux", JSONArray().put(JSONObject().put("Nom", room)))).toString()
                uri.path.endsWith("coursAnnules") -> "[]"
                else -> error("Unexpected synthetic endpoint ${uri.path}")
            }
            return FakeConnection(uri, body)
        }
    }

    private class FakeConnection(uri: URI, private val body: String, private val status: Int = 200) : HttpsURLConnection(uri.toURL()) {
        override fun getResponseCode() = status
        override fun getContentType() = "application/json"
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
        const val TIMEOUT = 10_000L
        const val CHANGES = "school_schedule_changes"
        const val PROBLEMS = "school_connection_problems"
        const val GROUP = "school"
        const val PORTAL = "https://example.invalid/colnet/"
        const val USERNAME = "synthetic-notification-user"
        const val PASSWORD = "synthetic-notification-password"
        const val TOKEN = "synthetic-notification-token"
    }
}
