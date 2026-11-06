package io.github.sunsind.sunlith

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
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
import io.github.sunsind.sunlith.system.SettingsStore
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiOnboardingTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var english: Context
    private lateinit var device: UiDevice
    private var activity: Activity? = null
    private var oldLocales: LocaleList? = null
    private val snapshots = mutableMapOf<String, Map<String, *>>()

    @Before
    fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        device = UiDevice.getInstance(instrumentation)
        listOf("sunlith", "sunlith_ui").forEach { name -> snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("ai_intro_done", false).putBoolean("layout_setup_done", false).commit()
        SettingsStore(context).navigationAnimation = false
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)
            oldLocales = locales.applicationLocales
            locales.applicationLocales = LocaleList.forLanguageTags("en")
        }
        launch()
        node(R.string.ai_intro_title)
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        finish()
        snapshots.forEach { (name, values) -> restoreTestPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
        if (Build.VERSION.SDK_INT >= 33) oldLocales?.let { context.getSystemService(LocaleManager::class.java).applicationLocales = it }
    }

    @Test
    fun firstLaunchHasAnAccessibleSkipAndKeepsTheDefaultLayout() {
        assertTrue(node(R.string.action_set_up_ai).isEnabled)
        val skip = node(R.string.action_without_ai)
        assertTrue(skip.isEnabled)
        assertTrue(skip.visibleBounds.width() > 0 && skip.visibleBounds.height() > 0)
        skip.click()
        node(R.string.layout_setup_title)
        assertTrue("The default must expose its selected state to accessibility.", selected(preset(R.string.layout_preset_sunlith)))
        val proceed = node(R.string.layout_continue)
        assertTrue(proceed.isEnabled)
        assertTrue(proceed.visibleBounds.height() > 0)
        proceed.click()
        awaitCalendar()
        val settings = SettingsStore(context)
        assertEquals("sunlith", settings.layoutPreset)
        assertEquals("split", settings.monthLayout)
        assertEquals("list", settings.weekLayout)
        assertEquals("cards", settings.dayLayout)
        assertTrue(uiPrefs().getBoolean("layout_setup_done", false))
        assertFalse(uiPrefs().getBoolean("computer_setup_requested", false))
        finish()
        launch()
        awaitCalendar()
    }

    @Test
    fun everyPresetIsSelectableAndPlannerChoiceSurvivesRecreationBeforeConfirmation() {
        node(R.string.action_without_ai).click()
        node(R.string.layout_setup_title)
        listOf(R.string.layout_preset_schedule, R.string.layout_preset_sunlith, R.string.layout_preset_planner).forEach { id ->
            scrollToPreset(id).click()
            device.waitForIdle()
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
            assertTrue("Selected layout must expose its selected state.", selected(preset(id)))
        }
        assertFalse(uiPrefs().getBoolean("layout_setup_done", false))
        recreate()
        node(R.string.layout_setup_title)
        assertTrue("The unconfirmed selection must survive activity recreation.", selected(scrollToPreset(R.string.layout_preset_planner)))
        assertFalse(uiPrefs().getBoolean("layout_setup_done", false))
        node(R.string.layout_continue).click()
        awaitCalendar()
        val settings = SettingsStore(context)
        assertEquals("planner", settings.layoutPreset)
        assertEquals("full", settings.monthLayout)
        assertEquals("timeline", settings.weekLayout)
        assertEquals("timeline", settings.dayLayout)
    }

    @Test
    fun pendingComputerSetupSurvivesTheChooserWithoutCreatingAPairing() {
        assumeFalse("This flow requires an unpaired test emulator.", DeviceLink.isPaired(context))
        node(R.string.action_set_up_ai).click()
        node(R.string.layout_setup_title)
        assertTrue(uiPrefs().getBoolean("computer_setup_requested", false))
        assertFalse(device.hasObject(By.pkg(context.packageName).text(english.getString(R.string.device_link_consent))))
        recreate()
        node(R.string.layout_setup_title)
        assertTrue(uiPrefs().getBoolean("computer_setup_requested", false))
        scrollToPreset(R.string.layout_preset_schedule).click()
        node(R.string.layout_continue).click()
        node(R.string.device_link_title)
        node(R.string.device_link_consent)
        assertFalse(DeviceLink.isPaired(context))
        assertEquals("schedule", SettingsStore(context).layoutPreset)
        assertFalse(uiPrefs().getBoolean("computer_setup_requested", false))
        assertFalse(device.hasObject(By.pkg(context.packageName).text("Codex")))
        device.pressBack()
        awaitCalendar()
    }

    @Test
    fun existingUsersSkipTheChooserAndComputerAccessHasNoAssistantGuidePicker() {
        finish()
        uiPrefs().edit().putBoolean("ai_intro_done", true).remove("layout_setup_done").commit()
        launch()
        awaitCalendar()
        assertFalse(device.hasObject(By.pkg(context.packageName).text(english.getString(R.string.layout_setup_title))))
        requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).desc(english.getString(R.string.title_settings))), 10_000)).click()
        node(R.string.title_settings)
        node(R.string.title_calendars)
        scrollToText(english.getString(R.string.device_link_title), startsWith = true).click()
        node(R.string.device_link_title)
        listOf("Codex", "Claude", "Cursor", "Grok", "OpenCode", "Antigravity", "Muse Code", "Pi").forEach {
            assertFalse("Computer access must not offer an assistant guide: $it", device.hasObject(By.pkg(context.packageName).text(it)))
        }
    }

    private fun uiPrefs() = context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE)

    private fun launch() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun finish() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().forEach(Activity::finish)
            activity?.finish()
        }
        instrumentation.waitForIdleSync()
        activity = null
    }

    private fun recreate() {
        val old = requireNotNull(activity)
        instrumentation.runOnMainSync { old.recreate() }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull { it !== old }?.let { activity = it }
            }
            if (activity !== old) {
                device.waitForIdle()
                return
            }
            SystemClock.sleep(100)
        }
        error("The activity did not finish recreating.")
    }

    private fun selected(node: UiObject2): Boolean =
        generateSequence(node) { it.parent }.take(5).any { it.isSelected || it.isChecked }

    private fun preset(id: Int): UiObject2 = requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).textStartsWith(english.getString(id))), 10_000))

    private fun scrollToPreset(id: Int): UiObject2 = scrollToText(english.getString(id), startsWith = true)

    private fun node(id: Int): UiObject2 = requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).textStartsWith(english.getString(id))), 15_000)) {
        "Expected onboarding control was not shown: ${english.getString(id)}"
    }

    private fun awaitCalendar() {
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_new_event))), 15_000))
        assertFalse(device.hasObject(By.pkg(context.packageName).text(english.getString(R.string.layout_setup_title))))
    }

    private fun scrollToText(text: String, startsWith: Boolean = false): UiObject2 {
        val selector = if (startsWith) By.pkg(context.packageName).textStartsWith(text) else By.pkg(context.packageName).text(text)
        repeat(10) {
            device.wait(Until.findObject(selector), 300)?.let { return it }
            requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).scrollable(true)), 5_000)).scroll(Direction.DOWN, 0.65f)
        }
        error("Expected setup option was not reachable: $text")
    }
}
