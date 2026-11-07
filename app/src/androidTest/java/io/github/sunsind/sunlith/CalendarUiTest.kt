package io.github.sunsind.sunlith

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.SettingsStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarUiTest {
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
    private lateinit var repository: CalendarRepository
    private lateinit var settings: SettingsStore
    private var activity: Activity? = null
    private var calendarId = 0L
    private val account = "Sunlith UI test ${UUID.randomUUID()}"
    private var oldLocales: LocaleList? = null
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private val marker = "UI fixture marker"

    @Before
    fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        device = UiDevice.getInstance(instrumentation)
        listOf("sunlith", "sunlith_ui").forEach { name -> snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        repository = CalendarRepository(context)
        settings = SettingsStore(context)
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)
            oldLocales = locales.applicationLocales
            locales.applicationLocales = LocaleList.forLanguageTags("en")
        }
        calendarId = insertLocalCalendar(context, "sunlith-ui-test", "Sunlith UI test", account)
        settings.selectedCalendarIds = setOf(calendarId)
        settings.hasChosenCalendars = true
        settings.materialYou = false
        settings.remindersEnabled = false
        settings.agendaRange = "month"
        settings.monthLayout = "split"
        settings.themeMode = "dark"
        settings.startView = "Month"
        settings.agendaDisplay = "all"
        settings.collapseCalendarOnScroll = false
        settings.navigationAnimation = false
        val prefs = context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE)
        prefs.edit().putString("view_mode", "Month").putLong("last_calendar", calendarId).putBoolean("ai_intro_done", true).commit()
        val today = LocalDate.now()
        repository.saveEvent(EventDraft(calendarId = calendarId, title = marker, startMillis = CalendarDates.allDayMillis(today), endMillis = CalendarDates.allDayMillis(today.plusDays(1)), allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
        launch()
        node(By.text(marker))
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
        snapshots.forEach { (name, values) -> restoreTestPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
        if (Build.VERSION.SDK_INT >= 33) oldLocales?.let { context.getSystemService(LocaleManager::class.java).applicationLocales = it }
    }

    @Test
    fun createSearchEditAndDeleteAnEventThroughTheUi() {
        val title = "UI dentist test ${UUID.randomUUID().toString().take(8)}"
        node(By.desc(text(R.string.action_new_event))).click()
        node(By.text(text(R.string.editor_new)))
        firstEditorField().text = title
        node(By.text(text(R.string.action_save))).click()
        assertTrue(device.wait(Until.gone(By.text(text(R.string.editor_new))), 15_000))
        device.waitForIdle()
        node(By.desc(text(R.string.action_search)))
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val saved = repository.events(start, end, setOf(calendarId)).single { it.title == title }
        assertNotNull(repository.getEvent(saved.id))

        device.waitForIdle()
        node(By.desc(text(R.string.action_search))).click()
        firstEditorField().text = "UI dentist test"
        node(By.text(title)).click()
        node(By.desc(text(R.string.action_edit))).click()
        node(By.text(text(R.string.editor_edit)))
        val changed = "$title moved"
        firstEditorField().text = changed
        node(By.text(text(R.string.action_save))).click()
        assertTrue(device.wait(Until.gone(By.text(text(R.string.editor_edit))), 15_000))
        node(By.text(changed)).click()
        node(By.pkg(context.packageName).desc(text(R.string.action_edit)))
        assertEquals(changed, repository.getEvent(saved.id)!!.title)
        device.waitForIdle()
        node(By.pkg(context.packageName).desc(text(R.string.action_delete))).click()
        node(By.text(text(R.string.delete_event_title)))
        node(By.text(text(R.string.action_delete))).click()
        node(By.text(text(R.string.search_none)))
        assertNull(repository.getEvent(saved.id))
    }

    @Test
    fun appearanceSettingChangesRenderedUiAndPersists() {
        node(By.desc(text(R.string.title_settings))).click()
        node(By.text(text(R.string.beta_settings_appearance))).click()
        scrollToSetting(text(R.string.settings_theme)).click()
        node(By.pkg(context.packageName).res("settings_choice_light")).click()
        device.waitForIdle()
        assertEquals("light", settings.themeMode)
        assertBackground(dark = false)
        scrollToSetting(text(R.string.settings_theme)).click()
        node(By.pkg(context.packageName).res("settings_choice_dark")).click()
        device.waitForIdle()
        assertEquals("dark", settings.themeMode)
        assertBackground(dark = true)
        scrollToSetting(text(R.string.settings_theme)).click()
        node(By.pkg(context.packageName).res("settings_choice_system")).click()
        device.waitForIdle()
        assertEquals("system", settings.themeMode)
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        assertBackground(dark = systemDark)
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun frenchAppLocaleShowsTranslatedCalendarAndEditor() {
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("fr-CA")
        val french = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.CANADA_FRENCH) })
        node(By.desc(french.getString(R.string.action_new_event))).click()
        node(By.text(french.getString(R.string.editor_new)))
        node(By.text(french.getString(R.string.action_save)))
        node(By.desc(french.getString(R.string.action_close))).click()
        node(By.desc(french.getString(R.string.title_settings))).click()
        node(By.text(french.getString(R.string.beta_settings_appearance))).click()
        scrollToSetting(french.getString(R.string.settings_theme)).click()
        node(By.pkg(context.packageName).text(french.getString(R.string.theme_system)))
        node(By.pkg(context.packageName).text(french.getString(R.string.theme_dark)))
        device.pressBack()
        SystemClock.sleep(300)
        device.takeScreenshot(File(context.filesDir, "settings-fr-appearance-colors.png"))
        device.pressBack()
        node(By.pkg(context.packageName).text(french.getString(R.string.beta_settings_about)))
        SystemClock.sleep(300)
        device.takeScreenshot(File(context.filesDir, "settings-fr-root.png"))
        listOf(Triple(R.string.settings_general, "general", R.string.settings_keep_date),
            Triple(R.string.beta_settings_transfer, "transfer", R.string.settings_import),
            Triple(R.string.beta_settings_widget, "widget", R.string.settings_widget_shared),
            Triple(R.string.beta_settings_about, "about", R.string.about_source)).forEach { (label, name, content) ->
            node(By.pkg(context.packageName).text(french.getString(label))).click()
            node(By.pkg(context.packageName).text(french.getString(content)))
            SystemClock.sleep(300)
            device.takeScreenshot(File(context.filesDir, "settings-fr-$name.png"))
            device.pressBack()
            node(By.pkg(context.packageName).text(french.getString(R.string.beta_settings_about)))
            SystemClock.sleep(300)
        }
    }

    @Test
    fun landscapeWithLargeFontKeepsTheAgendaReachable() {
        val oldScale = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
        fun setScale(scale: Float) {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $scale")
                .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        }
        try {
            setScale(2f)
            device.setOrientationLeft()
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while ((device.displayWidth <= device.displayHeight || context.resources.configuration.fontScale < 1.99f) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("The fixture must actually use landscape and 200% font size.", device.displayWidth > device.displayHeight && context.resources.configuration.fontScale >= 1.99f)
            device.waitForIdle()
            var visible = device.wait(Until.hasObject(By.pkg(context.packageName).text(marker)), 5_000)
            repeat(3) {
                if (!visible) {
                    node(By.pkg(context.packageName).res("calendar_agenda")).scroll(Direction.UP, 0.8f)
                    visible = device.wait(Until.hasObject(By.pkg(context.packageName).text(marker)), 500)
                }
            }
            repeat(8) {
                if (!visible) {
                    val agenda = requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).res("calendar_agenda")), 5_000)) {
                        "The agenda must keep its own scrollable area in landscape with large text."
                    }
                    assertTrue("The agenda must have visible height.", agenda.visibleBounds.height() > 30 * context.resources.displayMetrics.density)
                    agenda.scroll(Direction.DOWN, 0.8f)
                    visible = device.wait(Until.hasObject(By.pkg(context.packageName).text(marker)), 1_000)
                }
            }
            if (!visible) {
                device.takeScreenshot(File(context.filesDir, "large-text-failure.png"))
                device.dumpWindowHierarchy(File(context.filesDir, "large-text-failure.xml"))
            }
            assertTrue("The selected day's event must remain reachable at 200% font size in landscape.", visible)
        } finally {
            setScale(oldScale)
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            device.setOrientationNatural()
            device.unfreezeRotation()
            device.waitForIdle()
        }
    }

    private fun scrollToSetting(label: String): UiObject2 {
        val selector = By.pkg(context.packageName).text(label)
        val iconDescription = context.getString(R.string.launcher_icon_choice, label)
        repeat(12) {
            device.waitForIdle()
            device.findObjects(selector).forEach { labelNode ->
                val control = generateSequence(labelNode) { it.parent }.firstOrNull { it.isClickable || it.isCheckable }
                if (control != null && control.contentDescription != iconDescription &&
                    control.findObject(By.desc(iconDescription)) == null &&
                    control.visibleBounds.height() >= 48 * context.resources.displayMetrics.density) return control
            }
            node(By.pkg(context.packageName).scrollable(true)).scroll(Direction.DOWN, 0.55f)
        }
        error("Expected appearance setting: $label")
    }

    private fun launch() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
    }

    private fun text(id: Int): String = english.getString(id)

    private fun node(selector: BySelector): UiObject2 {
        val found = device.wait(Until.findObject(selector), 15_000)
        if (found != null) return found
        val directory = context.getExternalFilesDir(null)
        device.takeScreenshot(File(directory, "ui-failure.png"))
        device.dumpWindowHierarchy(File(directory, "ui-failure.xml"))
        error("Expected UI element was not shown: $selector")
    }

    private fun firstEditorField(): UiObject2 = node(By.pkg(context.packageName).clazz("android.widget.EditText"))

    private fun assertBackground(dark: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var last = -1
        while (SystemClock.elapsedRealtime() < deadline) {
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { last = Color.red(screenshot.getPixel(screenshot.width - 5, screenshot.height / 2)) }
            finally { screenshot.recycle() }
            if (if (dark) last < 60 else last > 220) return
            SystemClock.sleep(100)
        }
        device.takeScreenshot(File(context.getExternalFilesDir(null), "theme-failure.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "theme-failure.xml"))
        assertTrue("Expected ${if (dark) "dark" else "light"} rendered background; sampled red=$last", if (dark) last < 60 else last > 220)
    }
}
