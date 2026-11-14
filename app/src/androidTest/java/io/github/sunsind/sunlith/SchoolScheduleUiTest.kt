package io.github.sunsind.sunlith

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.school.SchoolAlert
import io.github.sunsind.sunlith.school.SchoolCalendarChoice
import io.github.sunsind.sunlith.school.SchoolChangeSummary
import io.github.sunsind.sunlith.school.SchoolError
import io.github.sunsind.sunlith.school.SchoolNotificationPreferences
import io.github.sunsind.sunlith.school.SchoolScheduleController
import io.github.sunsind.sunlith.school.SchoolScheduleState
import io.github.sunsind.sunlith.ui.SchoolScheduleScreen
import io.github.sunsind.sunlith.ui.SunlithTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchoolScheduleUiTest {
    @get:Rule
    val permissions = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33 && InstrumentationRegistry.getArguments().getString("schoolPermissionRecovery") != "true") add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var device: UiDevice
    private var activity: ComponentActivity? = null
    private lateinit var controller: FixtureController
    private val captureArguments get() = InstrumentationRegistry.getArguments()
    private val screenContext get() = captureArguments.getString("schoolLanguage")?.let { language ->
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    } ?: context

    @Before
    fun setUp() {
        requireEmulator()
        device = UiDevice.getInstance(instrumentation)
        controller = FixtureController()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
    }

    @After
    fun tearDown() {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
    }

    @Test
    fun choosingSchoolSuppliesItsAddressAndTimeZoneWithoutPrefillingAnAccount() {
        show(connected = false, timeZone = "Asia/Tokyo")
        find(R.string.school_choose_institution)
        try {
            val connect = generateSequence(find(R.string.school_connect)) { it.parent }
                .take(8).firstOrNull { it.isClickable }
            assertTrue("Connect must expose a clickable control", connect != null)
            assertFalse("Connect must stay disabled until a school and credentials are entered", requireNotNull(connect).isEnabled)
        } catch (failure: Throwable) {
            runCatching {
                val folder = requireNotNull(context.getExternalFilesDir(null))
                device.takeScreenshot(File(folder, "school-connect-state-failure.png"))
                device.dumpWindowHierarchy(File(folder, "school-connect-state-failure.xml"))
            }
            throw failure
        }
        assertEquals(2, device.findObjects(By.clazz("android.widget.EditText")).size)
        find(R.string.school_choose_institution).click()
        find(R.string.school_montmorency).click()
        idle()
        val inputs = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(2, inputs.size)
        assertTrue(inputs.all { it.text.isNullOrBlank() || it.text in setOf(
            text(R.string.school_username), text(R.string.school_password)) })
        assertFalse(device.hasObject(By.text(text(R.string.school_portal))))
        inputs[0].text = "fixture-student"
        inputs[1].text = "fixture-password"
        find(R.string.school_connect).click()
        assertTrue(device.wait(Until.gone(By.clazz("android.widget.EditText")), 5_000))
        assertEquals(listOf("https://enligne.cmontmorency.qc.ca/ColNet/", "fixture-student", "fixture-password", "America/Toronto", "71"), controller.connection)
        assertTrue(controller.state.value.connected)
        assertEquals(71L, controller.state.value.calendarId)
        assertFalse(device.hasObject(By.text("fixture-password")))
    }

    @Test
    fun manualColNetSetupClearsThePresetAndUsesTheEnteredAddress() {
        show(connected = false)
        find(R.string.school_choose_institution).click()
        find(R.string.school_montmorency).click()
        find(R.string.school_montmorency).click()
        find(R.string.school_other_colnet).click()
        scrollTo(R.string.school_portal)
        val inputs = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(3, inputs.size)
        assertTrue(inputs[0].text.isNullOrBlank() || inputs[0].text == text(R.string.school_portal))
        inputs[0].text = "https://portal.example/ColNet/login.asp?NoRegr=2"
        inputs[1].text = "fixture-student"
        inputs[2].text = "fixture-password"
        scrollTo(R.string.school_connect).click()
        idle()
        assertEquals(listOf("https://portal.example/ColNet/login.asp?NoRegr=2", "fixture-student", "fixture-password", java.time.ZoneId.systemDefault().id, "71"), controller.connection)
    }

    @Test
    fun knownSchoolReauthenticationPreservesTheExactSavedConnection() {
        show()
        val original = "https://enligne.cmontmorency.qc.ca/colnet/mobile/"
        controller.state.value = controller.state.value.copy(portalAddress = original, timeZone = "America/Vancouver")
        idle()
        scrollTo(R.string.school_more_options).click()
        scrollTo(R.string.school_change_connection).click()
        idle()
        find(R.string.school_montmorency)
        val inputs = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(1, inputs.size)
        find(R.string.school_username)
        node(By.text("fixture-student"))
        inputs[0].text = "replacement-fixture-password"
        scrollTo(R.string.school_connect).click()
        idle()
        assertEquals(listOf(original, "fixture-student", "replacement-fixture-password", "America/Vancouver", "71"), controller.connection)
    }

    @Test
    fun signingInAgainNeverSubstitutesAnUnavailableSavedCalendar() {
        show()
        controller.state.value = controller.state.value.copy(
            availableCalendars = listOf(SchoolCalendarChoice(72, "Another calendar")),
            calendarsLoaded = true,
        )
        idle()
        scrollTo(R.string.school_more_options).click()
        scrollTo(R.string.school_change_connection).click()
        idle()
        scrollTo(R.string.school_error_calendar_permission)
        val inputs = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(1, inputs.size)
        inputs[0].text = "replacement-fixture-password"
        scrollTo(R.string.school_connect).click()
        idle()
        assertEquals("71", requireNotNull(controller.connection).last())
    }

    @Test
    fun notificationMasterAndEveryCategoryUpdateIndependently() {
        show()
        assertFalse(controller.state.value.notifications.enabled)
        assertFalse(device.hasObject(By.text(text(R.string.school_alert_class_times))))
        scrollTo(R.string.school_notifications).click()
        idle()
        assertTrue(controller.state.value.notifications.enabled)
        val categories = listOf(
            SchoolAlert.CLASS_TIMES to R.string.school_alert_class_times,
            SchoolAlert.ROOMS to R.string.school_alert_rooms,
            SchoolAlert.TEACHERS to R.string.school_alert_teachers,
            SchoolAlert.ADDED_CLASSES to R.string.school_alert_added,
            SchoolAlert.REMOVED_CLASSES to R.string.school_alert_removed,
            SchoolAlert.TEACHER_ABSENCES to R.string.school_alert_teacher_absences,
            SchoolAlert.ASSESSMENTS to R.string.school_alert_assessments,
            SchoolAlert.SCHOOL_DAYS to R.string.school_alert_school_days,
            SchoolAlert.CONNECTION_PROBLEMS to R.string.school_alert_connection,
            SchoolAlert.ADMINISTRATION_MESSAGES to R.string.school_alert_administration,
        )
        categories.forEach { (category, label) ->
            val target = scrollTo(label)
            val bounds = target.visibleBounds
            target.click()
            idle()
            try {
                assertEquals("Tapped $category at $bounds; callbacks=${controller.categoryChanges}",
                    SchoolAlert.entries.toSet() - category, controller.state.value.notifications.categories)
            } catch (failure: AssertionError) {
                val folder = requireNotNull(context.getExternalFilesDir(null))
                device.takeScreenshot(File(folder, "school-category-$category-failure.png"))
                device.dumpWindowHierarchy(File(folder, "school-category-$category-failure.xml"))
                throw failure
            }
            scrollTo(label).click()
            idle()
            assertEquals(SchoolAlert.entries.toSet(), controller.state.value.notifications.categories)
        }
        scrollTo(R.string.school_notifications).click()
        idle()
        assertFalse(controller.state.value.notifications.enabled)
        assertFalse(device.hasObject(By.text(text(R.string.school_alert_class_times))))
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun deniedSchoolNotificationsOpenAndroidSettingsAndRecoverOnReturn() {
        assumeTrue("Run separately after pm revoke with -e schoolPermissionRecovery true",
            InstrumentationRegistry.getArguments().getString("schoolPermissionRecovery") == "true")
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        show()
        scrollTo(R.string.school_notifications).click()
        node(By.res("com.android.permissioncontroller", "permission_deny_button")).click()
        idle()
        assertFalse(controller.state.value.notifications.enabled)
        scrollTo(R.string.school_notifications_blocked)
        assertFalse(device.hasObject(By.text(text(R.string.school_alert_class_times))))
        val folder = requireNotNull(context.getExternalFilesDir(null))
        assertTrue(device.takeScreenshot(File(folder, "school-permission-denied.png")))
        device.dumpWindowHierarchy(File(folder, "school-permission-denied.xml"))
        scrollTo(R.string.school_open_android_settings).click()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5_000))
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        device.pressBack()
        idle()
        scrollTo(R.string.school_notifications).click()
        idle()
        assertTrue(controller.state.value.notifications.enabled)
        scrollTo(R.string.school_alert_class_times)
        assertFalse(device.hasObject(By.text(text(R.string.school_notifications_blocked))))
        assertTrue(device.takeScreenshot(File(folder, "school-permission-recovered.png")))
        device.dumpWindowHierarchy(File(folder, "school-permission-recovered.xml"))
    }

    @Test
    fun pendingLinkDoesNotApplyUntilTappedAndDisconnectKeepsEventsByDefault() {
        show(pending = SchoolChangeSummary(2, 1, 0, 3))
        assertEquals(0, controller.applied)
        find(R.string.school_link_title)
        find(R.string.school_apply).click()
        idle()
        assertEquals(1, controller.applied)
        assertEquals(null, controller.state.value.pendingChanges)
        scrollTo(R.string.school_more_options).click()
        scrollTo(R.string.school_disconnect).click()
        find(R.string.school_disconnect_title)
        find(R.string.school_disconnect).click()
        idle()
        assertEquals(false, controller.removal)
        assertFalse(controller.state.value.connected)
        find(R.string.school_connect)
    }

    @Test
    fun removalIsAnExplicitDisconnectChoiceAndDiscardDoesNotApply() {
        show(pending = SchoolChangeSummary(0, 0, 12))
        find(R.string.school_not_now).click()
        idle()
        assertEquals(1, controller.discarded)
        assertEquals(0, controller.applied)
        scrollTo(R.string.school_more_options).click()
        scrollTo(R.string.school_disconnect).click()
        val remove = context.resources.getQuantityString(R.plurals.school_disconnect_remove, 4, 4)
        node(By.text(remove)).click()
        find(R.string.school_disconnect_remove_confirm).click()
        idle()
        assertEquals(true, controller.removal)
    }

    @Test
    fun signingInAgainPreservesTheExistingPortalAccountCalendarAndZone() {
        show()
        scrollTo(R.string.school_more_options).click()
        scrollTo(R.string.school_change_connection).click()
        idle()
        val inputs = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(1, inputs.size)
        node(By.text("https://portal.example/ColNet/"))
        node(By.text("fixture-student"))
        inputs[0].text = "replacement-fixture-password"
        scrollTo(R.string.school_connect).click()
        idle()
        assertEquals(listOf("https://portal.example/ColNet/", "fixture-student", "replacement-fixture-password", "America/Toronto", "71"), controller.connection)
    }

    @Test
    fun notificationChoicesPreserveTheSignInErrorAndItsRecoveryAction() {
        show()
        controller.state.value = controller.state.value.copy(error = SchoolError.SIGN_IN)
        idle()
        find(R.string.school_sign_in_again)
        scrollTo(R.string.school_notifications).click()
        idle()
        scrollTo(R.string.school_alert_class_times).click()
        idle()
        assertEquals(SchoolError.SIGN_IN, controller.state.value.error)
        assertFalse(controller.state.value.busy)
        scrollTo(R.string.school_sign_in_again)
        val error = text(R.string.school_error_sign_in)
        node(By.text(error))
    }

    @Test
    fun ambiguousLegacyClassesShowAnActionableErrorOnce() {
        show()
        controller.state.value = controller.state.value.copy(error = SchoolError.LEGACY_COLLISION)
        idle()
        val message = text(R.string.school_error_existing_classes)
        node(By.text(message))
        assertEquals(1, device.findObjects(By.text(message)).size)
    }

    @Test
    fun optInSyntheticSchoolDisplayCheckpoints() {
        assumeTrue(captureArguments.getString("schoolCapture") != null)
        show(connected = false)
        if (captureArguments.getString("schoolRtl") == "true") show(connected = false)
        capture("disconnected-top")
        scrollTo(R.string.school_choose_institution).click()
        find(R.string.school_montmorency)
        find(R.string.school_other_colnet)
        capture("school-picker")
        find(R.string.school_montmorency).click()
        idle()
        assertFalse(device.hasObject(By.text(text(R.string.school_portal))))
        capture("montmorency-setup")
        scrollTo(R.string.school_montmorency).click()
        find(R.string.school_other_colnet).click()
        scrollTo(R.string.school_portal)
        capture("manual-setup")
        scrollTo(R.string.school_portal_help)
        capture("manual-help")
        scrollTo(R.string.school_connect)
        capture("disconnected-connect")
        show()
        capture("connected")
        scrollTo(R.string.school_notifications).click()
        idle()
        assertTrue(controller.state.value.notifications.enabled)
        scrollTo(R.string.school_alert_class_times)
        capture("notifications-top")
        scrollTo(R.string.school_alert_connection)
        capture("notifications-bottom")
        show(pending = SchoolChangeSummary(2, 1, 0, 3))
        scrollTo(R.string.school_link_title)
        capture("pending-link-top")
        scrollTo(R.string.school_apply)
        capture("pending-link-actions")
        assertEquals(0, controller.applied)
        show()
        controller.state.value = controller.state.value.copy(error = SchoolError.LEGACY_COLLISION)
        idle()
        node(By.text(text(R.string.school_error_existing_classes)))
        capture("error")
        controller.state.value = controller.state.value.copy(error = SchoolError.STORAGE)
        idle()
        scrollTo(R.string.school_sync_now)
        capture("storage-recovery")
        controller.state.value = controller.state.value.copy(error = SchoolError.SIGN_IN)
        idle()
        scrollTo(R.string.school_sign_in_again)
        capture("sign-in-again-error")
        scrollTo(R.string.school_sign_in_again).click()
        idle()
        scrollTo(R.string.school_portal_short)
        val inputs = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(1, inputs.size)
        node(By.text("https://portal.example/ColNet/"))
        capture("sign-in-again-form")
        scrollTo(R.string.school_connect)
        capture("sign-in-again-connect")
        assertEquals(0, controller.applied)
        assertEquals(null, controller.connection)
    }

    private fun capture(state: String) {
        val variant = captureArguments.getString("schoolCapture") ?: return
        require(variant.matches(Regex("[a-zA-Z0-9-]+")))
        idle()
        val rendered = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { rendered.countDown() }
            }
        }
        assertTrue(rendered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val folder = requireNotNull(context.getExternalFilesDir(null))
        assertTrue(device.takeScreenshot(File(folder, "school-$variant-$state.png")))
        device.dumpWindowHierarchy(File(folder, "school-$variant-$state.xml"))
    }

    private fun show(connected: Boolean = true, pending: SchoolChangeSummary? = null, timeZone: String = "America/Toronto") {
        if (captureArguments.getString("schoolCapture") != null) {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
            instrumentation.runOnMainSync {
                val resources = requireNotNull(activity).resources
                val configuration = Configuration(resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(captureArguments.getString("schoolLanguage") ?: "en"))
                    if (captureArguments.getString("schoolRtl") == "true") setLayoutDirection(Locale("ar"))
                }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(configuration, resources.displayMetrics)
            }
        }
        controller.state.value = SchoolScheduleState(
            connected = connected, timeZone = timeZone, calendarId = 71,
            portalAddress = if (connected) "https://portal.example/ColNet/" else "",
            availableCalendars = listOf(SchoolCalendarChoice(71, "Main calendar")),
            termName = "Autumn term", accountName = if (connected) "fixture-student" else "",
            lastSuccessMillis = if (connected) System.currentTimeMillis() else null,
            eventCount = 4, pendingChanges = pending,
        )
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent {
                SunlithTheme("dark", false) {
                    SchoolScheduleScreen(controller, onBack = {}, onChooseCalendars = {})
                }
            }
        }
        idle()
        find(R.string.school_title)
    }

    private fun text(id: Int) = screenContext.getString(id)
    private fun find(id: Int) = node(By.text(text(id)))
    private fun node(selector: BySelector): UiObject2 = requireNotNull(device.wait(Until.findObject(selector), 5_000)) { "Missing school control: $selector" }
    private fun idle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
    }
    private fun scrollTo(id: Int): UiObject2 {
        val selector = By.text(text(id))
        fun located(): Pair<UiObject2, android.graphics.Rect>? {
            repeat(5) {
                try {
                    val found = device.findObject(selector) ?: return null
                    return found to found.visibleBounds
                } catch (_: StaleObjectException) { idle() }
            }
            val folder = requireNotNull(context.getExternalFilesDir(null))
            device.takeScreenshot(File(folder, "school-stale-control-failure.png"))
            device.dumpWindowHierarchy(File(folder, "school-stale-control-failure.xml"))
            error("The school control did not settle after replacement: ${text(id)}")
        }
        fun visible() = located()?.takeIf { (_, bounds) ->
            bounds.height() > 12 && bounds.centerY() in device.displayHeight / 7..device.displayHeight * 6 / 7
        }?.first
        fun nudgeIfClipped(): Boolean {
            val bounds = located()?.second ?: return false
            val middle = device.displayHeight / 2
            val step = device.displayHeight / 6
            dragScroll(middle, middle + if (bounds.centerY() < middle) step else -step)
            return true
        }
        visible()?.let { return it }
        repeat(12) {
            if (!nudgeIfClipped()) dragScroll(device.displayHeight * 4 / 5, device.displayHeight / 3)
            visible()?.let { return it }
        }
        repeat(24) {
            if (!nudgeIfClipped()) dragScroll(device.displayHeight / 3, device.displayHeight * 4 / 5)
            visible()?.let { return it }
        }
        return node(selector)
    }
    private fun dragScroll(from: Int, to: Int) {
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, device.displayWidth / 2f, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, from.toFloat())
        for (step in 1..12) {
            SystemClock.sleep(16)
            inject(MotionEvent.ACTION_MOVE, from + (to - from) * step / 12f)
        }
        SystemClock.sleep(250)
        inject(MotionEvent.ACTION_UP, to.toFloat())
        idle()
    }

    private class FixtureController : SchoolScheduleController {
        override val state = MutableStateFlow(SchoolScheduleState())
        var connection: List<String>? = null
        var applied = 0
        var discarded = 0
        var removal: Boolean? = null
        val categoryChanges = mutableListOf<Pair<SchoolAlert, Boolean>>()
        override suspend fun connect(portalAddress: String, username: String, password: String, timeZone: String, calendarId: Long?) {
            connection = listOf(portalAddress, username, password, timeZone, calendarId.toString())
            state.value = state.value.copy(connected = true, portalAddress = portalAddress, accountName = username)
        }
        override suspend fun refresh() { }
        override suspend fun setNotifications(preferences: SchoolNotificationPreferences) {
            state.value = state.value.copy(notifications = preferences)
        }
        override suspend fun setCategory(alert: SchoolAlert, enabled: Boolean) {
            categoryChanges += alert to enabled
            super<SchoolScheduleController>.setCategory(alert, enabled)
        }
        override suspend fun applyPendingChanges() { applied++; state.value = state.value.copy(pendingChanges = null) }
        override suspend fun discardPendingChanges() { discarded++; state.value = state.value.copy(pendingChanges = null) }
        override suspend fun disconnect(removeSchoolEvents: Boolean) {
            removal = removeSchoolEvents
            state.value = state.value.copy(connected = false)
        }
    }
}
