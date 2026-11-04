package io.github.sunsind.sunlith

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.system.CalendarHandoff
import io.github.sunsind.sunlith.system.readCalendarHandoff
import io.github.sunsind.sunlith.ui.SunlithViewModel
import java.util.UUID
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarHandoffUiTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private var activity: ComponentActivity? = null
    private lateinit var vm: SunlithViewModel
    private var calendarId = 0L
    private val account = "Sunlith handoff test ${UUID.randomUUID()}"
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private val fixture = Uri.parse("content://io.github.sunsind.sunlith.test.calendarfiles/fixture.ics")

    @Before
    fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        device = UiDevice.getInstance(instrumentation)
        listOf("sunlith", "sunlith_ui").forEach { name -> snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        calendarId = insertLocalCalendar(context, "sunlith-handoff-test", "Handoff calendar", account)
        SettingsStore(context).apply {
            selectedCalendarIds = setOf(calendarId)
            hasChosenCalendars = true
            themeMode = "dark"
            startView = "Month"
        }
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().putBoolean("ai_intro_done", true).putBoolean("notify_asked", true).putLong("last_calendar", calendarId).commit()
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        finish()
        if (calendarId != 0L) context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
        snapshots.forEach { (name, values) -> restoreTestPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
    }

    @Test
    fun standardInsertUrisAndEventMimeTypesResolveToSunlithAndOpenOnlyADraft() {
        listOf(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI), Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.dir/event"), Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.item/event")).forEach { incoming ->
            val matches = context.packageManager.queryIntentActivities(incoming, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("Sunlith must appear in Android's calendar event chooser.", matches.any { it.activityInfo.packageName == context.packageName })
        }
        val begin = System.currentTimeMillis() + 86_400_123L
        val end = begin + 3_600_456L
        launch(insert(begin, end))
        await { vm.editor?.title == "Incoming appointment" }
        val draft = requireNotNull(vm.editor).toDraft(java.time.ZoneId.systemDefault())
        assertEquals(begin, draft.startMillis)
        assertEquals(end, draft.endMillis)
        assertEquals("Incoming location", draft.location)
        assertEquals("Incoming notes", draft.description)
        assertEquals("An incoming intent must not save an event automatically.", 0, savedEvents())
        expectText(R.string.editor_new)
    }

    @Test
    fun viewSendAndClipDataCalendarFilesOpenImportPreviewWithoutWritingEvents() {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(fixture, "text/calendar"),
            Intent(Intent.ACTION_VIEW, fixture),
            Intent(Intent.ACTION_SEND).setType("text/calendar").putExtra(Intent.EXTRA_STREAM, fixture),
            Intent(Intent.ACTION_SEND).setType("text/calendar").apply { clipData = ClipData.newRawUri("Synthetic calendar", fixture) },
        )
        intents.forEach { incoming ->
            finish()
            launch(incoming.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            await { vm.importUi?.preview != null || vm.importUi?.error != null }
            assertNull("The synthetic content URI must be readable by the import preview.", vm.importUi?.error)
            assertEquals(1, vm.importUi?.preview?.importable)
            assertEquals(0, savedEvents())
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text(context.getString(R.string.import_destination))), 10_000))
            assertTrue(device.hasObject(By.pkg(context.packageName).text("Handoff calendar")))
        }
    }

    @Test
    fun unsafeFileSchemesAndInvalidIncomingEventTimesDoNotOpenAnEditorOrImport() {
        val invalid = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/private.ics"), "text/calendar"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.invalid/calendar.ics"), "text/calendar"),
            Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI),
            insert(2_000, 1_000),
            insert(System.currentTimeMillis(), System.currentTimeMillis() + 3_600_000).putExtra(Intent.EXTRA_EMAIL, arrayOf("fixture@example.invalid")),
            insert(System.currentTimeMillis(), System.currentTimeMillis() + 3_600_000).putExtra(CalendarContract.Events.RRULE, "FREQ=WEEKLY"),
        )
        invalid.forEach { incoming ->
            assertTrue(readCalendarHandoff(incoming) is CalendarHandoff.Rejected)
            if (incoming.data?.scheme == "file") return@forEach
            finish()
            launch(incoming)
            await { vm.loaded }
            device.waitForIdle()
            assertNull(vm.editor)
            assertNull(vm.importUi)
            assertEquals(0, savedEvents())
        }
    }

    @Test
    fun anInsertWaitsForOnboardingAndDoesNotReplaceAnExistingDraft() {
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().putBoolean("ai_intro_done", false).commit()
        val now = System.currentTimeMillis()
        launch(insert(now + 86_400_000, now + 90_000_000))
        expectText(R.string.ai_intro_title)
        assertNull(vm.editor)
        val previous = requireNotNull(activity)
        instrumentation.runOnMainSync { previous.recreate() }
        await {
            var recreated = false
            instrumentation.runOnMainSync {
                val current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                if (current != null && current !== previous) {
                    activity = current
                    vm = ViewModelProvider(current)[SunlithViewModel::class.java]
                    recreated = true
                }
            }
            recreated
        }
        requireNotNull(device.findObject(By.pkg(context.packageName).text(context.getString(R.string.action_without_ai)))).click()
        await { vm.editor?.title == "Incoming appointment" }
        instrumentation.runOnMainSync { vm.updateEditor { it.copy(title = "Draft I am editing") } }
        val newer = insert(now + 172_800_000, now + 176_400_000).putExtra(CalendarContract.Events.TITLE, "Queued appointment")
        context.startActivity(Intent(newer).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text(context.getString(R.string.handoff_waiting))), 10_000))
        assertEquals("Draft I am editing", vm.editor?.title)
        assertEquals(0, savedEvents())
        instrumentation.runOnMainSync { vm.closeEditor() }
        await { vm.editor?.title == "Queued appointment" }
        assertEquals(0, savedEvents())
    }

    private fun insert(begin: Long, end: Long): Intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        .putExtra(CalendarContract.Events.TITLE, "Incoming appointment")
        .putExtra(CalendarContract.Events.EVENT_LOCATION, "Incoming location")
        .putExtra(CalendarContract.Events.DESCRIPTION, "Incoming notes")

    private fun launch(intent: Intent) {
        activity = instrumentation.startActivitySync(Intent(intent).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        instrumentation.runOnMainSync { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java] }
    }

    private fun finish() {
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        activity = null
    }

    private fun savedEvents(): Int = context.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), "${CalendarContract.Events.CALENDAR_ID}=?", arrayOf(calendarId.toString()), null)!!.use { it.count }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) { device.waitForIdle(); return }
            SystemClock.sleep(100)
        }
        captureFailure()
        assertTrue("Incoming state: intro=${vm.aiIntroDone}, permission=${vm.hasPermission}, notifications=${vm.needsNotificationStep}, loaded=${vm.loaded}, editor=${vm.editor?.title}, import=${vm.importUi}", condition())
    }

    private fun expectText(id: Int) {
        if (device.wait(Until.hasObject(By.pkg(context.packageName).text(context.getString(id))), 10_000)) return
        captureFailure()
        error("Missing incoming UI '${context.getString(id)}'; intro=${vm.aiIntroDone}, loaded=${vm.loaded}, editor=${vm.editor?.title}, import=${vm.importUi}")
    }

    private fun captureFailure() {
        device.takeScreenshot(File(context.getExternalFilesDir(null), "handoff-failure.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "handoff-failure.xml"))
    }
}
