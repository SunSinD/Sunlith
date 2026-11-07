package io.github.sunsind.sunlith

import android.Manifest
import android.app.Instrumentation
import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.device.DeviceLink
import io.github.sunsind.sunlith.device.DeviceLinkStore
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithViewModel
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComputerConnectionUiTest {
    @get:Rule
    val permissions: GrantPermissionRule = computerConnectionPermissions()

    private lateinit var fixture: ComputerConnectionHarness

    @Before
    fun setUp() {
        fixture = ComputerConnectionHarness()
        fixture.prepare()
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun connectionShowsItsDetailsAndSuccessfulChangeHistory(): Unit = with(fixture) {
        DeviceLink.createPairing(context, setOf(linkedCalendarId), "127.0.0.1")
        val tools = io.github.sunsind.sunlith.device.DeviceLinkTools(context)
        val today = java.time.LocalDate.now()
        val title = "Connection history ${UUID.randomUUID().toString().take(8)}"
        tools.call("create_event", mapOf("calendarId" to linkedCalendarId.toString(), "operationId" to "history-${UUID.randomUUID()}",
            "title" to title, "start" to mapOf("date" to today.toString()), "end" to mapOf("date" to today.plusDays(1).toString())), setOf(linkedCalendarId))
        launch()
        instrumentation.runOnMainSync { vm.open(Screen.DeviceLink) }
        findText(R.string.device_link_details).click()
        findText(R.string.device_link_paired_since)
        findText(R.string.device_link_last_request)
        findText(R.string.device_link_no_contact)
        device.pressBack()
        findText(R.string.device_link_history).click()
        requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).text(context.getString(R.string.device_link_history_added, title))), 10_000))
        findText(R.string.device_link_history_clear).click()
        findText(R.string.device_link_history_clear_body)
        findText(R.string.action_cancel).click()
        assertTrue("Cancelling must preserve the change record.", DeviceLink.history(context).any { it.title == title })
        assertTrue("Viewing history must preserve pairing.", DeviceLink.isPaired(context))
    }

    @Test
    fun explicitShareConsentPairsAndCancellingTheChooserKeepsAccessAndDisplayChoices(): Unit = with(fixture) {
        launch()
        instrumentation.runOnMainSync { vm.open(Screen.Settings) }
        findText(R.string.device_link_title).click()
        findText(R.string.device_link_consent)
        assertFalse(DeviceLink.isPaired(context))
        assertFalse(DeviceLink.state.value.running)
        assertEquals(setOf(displayCalendarId), settings.selectedCalendarIds)
        requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).text("Computer test shared")), 5_000)).click()
        assertEquals("Choosing access must not change the app display filter.", setOf(displayCalendarId), settings.selectedCalendarIds)
        assertTrue("The emulator must expose a usable private address.", DeviceLink.addresses(context).isNotEmpty())
        val chooser = java.util.concurrent.atomic.AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) chooser.set(intent)
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            findText(R.string.connection_setup_connect_computer).click()
            await("Connect computer must offer Android's share chooser") { chooser.get() != null }
            val send = if (Build.VERSION.SDK_INT >= 33) requireNotNull(chooser.get()).getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") requireNotNull(chooser.get()).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            requireNotNull(send)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/json", send.type)
            val uri = if (Build.VERSION.SDK_INT >= 33) send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            requireNotNull(uri)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
                requireNotNull(cursor)
                assertTrue(cursor.moveToFirst())
                assertEquals("sunlith-pairing.json", cursor.getString(0))
            }
            assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue("Consent creates a pairing before the chooser opens.", DeviceLink.isPaired(context))
            await("The Android chooser must be visible") { device.currentPackageName != context.packageName }
            device.pressBack()
            findText(R.string.connection_setup_share_again)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertTrue("Cancelling sharing keeps the pairing available for Share again.", DeviceLink.isPaired(context))
        assertEquals(setOf(linkedCalendarId), DeviceLink.pairedCalendarIds(context))
        assertFalse(DeviceLink.state.value.running)
        assertFalse(hasConnectionNotification())
        assertEquals(setOf(displayCalendarId), settings.selectedCalendarIds)
        assertEquals(setOf(displayCalendarId), vm.activeIds)
        val cached = requireNotNull(DeviceLink.cachedPairingShare(context))
        val previous = requireNotNull(activity)
        instrumentation.runOnMainSync { previous.recreate() }
        await("Pairing screen did not recreate") {
            var restored = false
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull { it !== previous }?.let {
                    activity = it
                    vm = ViewModelProvider(it)[SunlithViewModel::class.java]
                    restored = true
                }
            }
            restored
        }
        findText(R.string.connection_setup_share_again)
        assertTrue("Recreation must reuse the current private pairing file.", cached == DeviceLink.cachedPairingShare(context))
        findText(R.string.connection_setup_open_calendar).click()
        await("Open calendar must exit setup") { vm.stack.isEmpty() }
        assertTrue(DeviceLink.isPaired(context))
        assertEquals(setOf(displayCalendarId), vm.activeIds)
    }

    @Test
    fun cachedSharesRejectStaleAndUntrustedFilesAcrossReplacementAndRevocation(): Unit = with(fixture) {
        val directory = File(context.cacheDir, "pairing-share").apply { mkdirs() }
        val firstConfig = DeviceLink.createPairing(context, setOf(linkedCalendarId), "127.0.0.1")
        val first = File(directory, "first-${UUID.randomUUID()}.json").apply { writeText(firstConfig) }
        assertTrue(DeviceLink.cachedPairingShare(context) == first)
        DeviceLinkStore.dropCache()
        assertTrue("A saved share must still be usable after the pairing store reloads.", DeviceLink.cachedPairingShare(context) == first)
        val replacement = DeviceLink.createPairing(context, setOf(displayCalendarId), "127.0.0.1")
        assertFalse("Replacing access must delete old share files.", first.exists())
        assertNull(DeviceLink.cachedPairingShare(context))
        File(directory, "stale.json").writeText(firstConfig)
        File(directory, "broken.json").writeText("{")
        File(directory, "wrong-type.txt").writeText(replacement)
        val outside = File(context.cacheDir, "outside-pairing-${UUID.randomUUID()}.json").apply { writeText(replacement) }
        try {
            assertNull("Only current JSON credentials inside the private share directory may be offered.", DeviceLink.cachedPairingShare(context))
            val current = File(directory, "current-${UUID.randomUUID()}.json").apply { writeText(replacement) }
            assertTrue(DeviceLink.cachedPairingShare(context) == current)
            DeviceLink.revoke(context)
            assertFalse(current.exists())
            assertTrue(directory.listFiles().orEmpty().none { it.isFile })
            assertNull(DeviceLink.cachedPairingShare(context))
            current.writeText(replacement)
            DeviceLinkStore.dropCache()
            assertNull("A restored file cannot revive revoked access.", DeviceLink.cachedPairingShare(context))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun connectionSurvivesRecreationAndUiCanStopRestartAndUnpair(): Unit = with(fixture) {
        DeviceLink.createPairing(context, setOf(linkedCalendarId), "127.0.0.1")
        launch()
        instrumentation.runOnMainSync { vm.open(Screen.DeviceLink) }
        findText(R.string.device_link_start).click()
        await("Connection did not start with a foreground notification") { DeviceLink.state.value.running && hasConnectionNotification() }
        assertNull(DeviceLink.state.value.error)
        findText(R.string.device_link_on)
        assertEquals(setOf(linkedCalendarId), DeviceLink.pairedCalendarIds(context))
        assertEquals("Pairing must not replace the calendars displayed in the app.", setOf(displayCalendarId), settings.selectedCalendarIds)
        assertEquals(setOf(displayCalendarId), vm.activeIds)
        repeat(3) { attempt ->
            instrumentation.runOnMainSync {
                DeviceLink.stop(requireNotNull(activity))
                DeviceLink.start(requireNotNull(activity))
            }
            await("Immediate restart ${attempt + 1} did not restore the connection and notification") {
                DeviceLink.state.value.running && hasConnectionNotification()
            }
            assertNull("Immediate restart must release and rebind the listening port.", DeviceLink.state.value.error)
        }
        findText(R.string.device_link_on)
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text("Computer test shared")), 5_000))
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "computer-connection-scopes.png")))

        val previous = requireNotNull(activity)
        instrumentation.runOnMainSync { previous.recreate() }
        await("Activity did not recreate") {
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
        findText(R.string.device_link_stop).click()
        await("Stopping must remove the foreground notification") { !DeviceLink.state.value.running && !hasConnectionNotification() }
        assertTrue("Stopping keeps the pairing for next time.", DeviceLink.isPaired(context))
        findText(R.string.device_link_start).click()
        await("Saved pairing did not restart") { DeviceLink.state.value.running && hasConnectionNotification() }
        findText(R.string.device_link_unpair).click()
        findText(R.string.device_link_unpair_body)
        val buttons = device.findObjects(By.pkg(context.packageName).text(context.getString(R.string.device_link_unpair)))
        assertTrue("The unpair confirmation needs an explicit action.", buttons.isNotEmpty())
        buttons.maxBy { it.visibleBounds.centerY() }.click()
        await("Unpairing must stop access and remove the notification") { !DeviceLink.isPaired(context) && !DeviceLink.state.value.running && !hasConnectionNotification() }
        assertTrue(DeviceLink.pairedCalendarIds(context).isEmpty())
        assertEquals(setOf(displayCalendarId), settings.selectedCalendarIds)
        findText(R.string.device_link_consent)
    }
}

internal fun computerConnectionPermissions(): GrantPermissionRule = GrantPermissionRule.grant(*buildList {
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray())

internal class ComputerConnectionHarness {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val settings = SettingsStore(context)
    var activity: ComponentActivity? = null
    lateinit var vm: SunlithViewModel
    var displayCalendarId = 0L
        private set
    var linkedCalendarId = 0L
        private set
    private val account = "Sunlith computer test ${UUID.randomUUID()}"
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private var oldLocales: LocaleList? = null
    private var ownsPairing = false

    fun prepare() {
        requireEmulator()
        assumeFalse("An existing computer pairing must not be overwritten by tests.", DeviceLink.isPaired(context) || DeviceLink.state.value.running)
        ownsPairing = true
        listOf("sunlith", "sunlith_ui").forEach { name -> snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)
            oldLocales = locales.applicationLocales
            locales.applicationLocales = LocaleList.forLanguageTags("en")
        }
        displayCalendarId = insertLocalCalendar(context, "computer-display-${UUID.randomUUID()}", "Computer test display", account)
        linkedCalendarId = insertLocalCalendar(context, "computer-shared-${UUID.randomUUID()}", "Computer test shared", account)
        settings.selectedCalendarIds = setOf(displayCalendarId)
        settings.hasChosenCalendars = true
        settings.themeMode = "dark"
        settings.startView = "Month"
        settings.navigationAnimation = false
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().putBoolean("ai_intro_done", true).putBoolean("notify_asked", true).putLong("last_calendar", displayCalendarId).commit()
    }

    fun launch() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        instrumentation.runOnMainSync { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java] }
        await("Calendar screen did not load") { vm.loaded }
    }

    fun hasConnectionNotification(): Boolean = context.getSystemService(NotificationManager::class.java).activeNotifications.any {
        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == context.getString(R.string.device_link_notification_title) &&
            it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
    }

    fun findText(id: Int): UiObject2 {
        val selector = By.pkg(context.packageName).text(context.getString(id))
        device.wait(Until.findObject(selector), 3_000)?.let { return it }
        repeat(8) {
            device.findObject(By.pkg(context.packageName).scrollable(true))?.scroll(Direction.UP, 0.8f)
            device.wait(Until.findObject(selector), 200)?.let { return it }
        }
        repeat(8) {
            device.findObject(By.pkg(context.packageName).scrollable(true))?.scroll(Direction.DOWN, 0.65f)
            device.wait(Until.findObject(selector), 500)?.let { return it }
        }
        error("Expected connection UI: ${context.getString(id)}")
    }

    fun await(message: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }

    fun close() {
        if (!ownsPairing) return
        try {
            DeviceLink.stop(context)
            DeviceLink.revoke(context)
            await("Test connection did not stop") { !DeviceLink.state.value.running && !hasConnectionNotification() }
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            listOf(displayCalendarId, linkedCalendarId).filter { it != 0L }.forEach { context.contentResolver.delete(localCalendarUri(it, account), null, null) }
            snapshots.forEach { (name, values) -> restoreTestPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
            if (Build.VERSION.SDK_INT >= 33) oldLocales?.let { context.getSystemService(LocaleManager::class.java).applicationLocales = it }
        }
    }
}
