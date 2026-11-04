package io.github.sunsind.sunlith

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
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
    private var oldCalendars = emptySet<Long>()
    private var oldChosen = false
    private var oldTheme = "dark"
    private var oldLocales: LocaleList? = null
    private var oldView: String? = null
    private var oldLastCalendar: Long? = null
    private var oldAiIntro: Boolean? = null
    private val marker = "UI fixture marker"

    @Before
    fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        device = UiDevice.getInstance(instrumentation)
        repository = CalendarRepository(context)
        settings = SettingsStore(context)
        oldCalendars = settings.selectedCalendarIds
        oldChosen = settings.hasChosenCalendars
        oldTheme = settings.themeMode
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)
            oldLocales = locales.applicationLocales
            locales.applicationLocales = LocaleList.forLanguageTags("en")
        }
        calendarId = insertLocalCalendar(context, "sunlith-ui-test", "Sunlith UI test", account)
        settings.selectedCalendarIds = setOf(calendarId)
        settings.hasChosenCalendars = true
        settings.themeMode = "dark"
        val prefs = context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE)
        oldView = prefs.getString("view_mode", null)
        oldLastCalendar = if (prefs.contains("last_calendar")) prefs.getLong("last_calendar", -1) else null
        oldAiIntro = if (prefs.contains("ai_intro_done")) prefs.getBoolean("ai_intro_done", false) else null
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
        settings.selectedCalendarIds = oldCalendars
        settings.hasChosenCalendars = oldChosen
        settings.themeMode = oldTheme
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().apply {
            if (oldView == null) remove("view_mode") else putString("view_mode", oldView)
            if (oldLastCalendar == null) remove("last_calendar") else putLong("last_calendar", oldLastCalendar!!)
            if (oldAiIntro == null) remove("ai_intro_done") else putBoolean("ai_intro_done", oldAiIntro!!)
        }.commit()
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
        node(By.desc(text(R.string.action_more))).click()
        node(By.text(text(R.string.title_settings))).click()
        node(By.text(text(R.string.theme_light))).click()
        device.waitForIdle()
        assertEquals("light", settings.themeMode)
        val light = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { assertTrue("Light appearance must render a light background.", Color.red(light.getPixel(light.width - 5, light.height / 2)) > 220) }
        finally { light.recycle() }
        node(By.text(text(R.string.theme_dark))).click()
        device.waitForIdle()
        assertEquals("dark", settings.themeMode)
        val dark = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { assertTrue("Dark appearance must render a dark background.", Color.red(dark.getPixel(dark.width - 5, dark.height / 2)) < 60) }
        finally { dark.recycle() }
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
        node(By.desc(french.getString(R.string.action_more))).click()
        node(By.text(french.getString(R.string.title_settings))).click()
        node(By.text(french.getString(R.string.theme_dark)))
        node(By.text(french.getString(R.string.theme_light)))
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
            device.waitForIdle()
            var visible = device.hasObject(By.pkg(context.packageName).text(marker))
            repeat(8) {
                if (!visible) {
                    val agenda = requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).scrollable(true)), 5_000)) {
                        "The month and agenda must remain scrollable in landscape with large text."
                    }
                    agenda.scroll(Direction.DOWN, 0.8f)
                    visible = device.wait(Until.hasObject(By.pkg(context.packageName).text(marker)), 1_000)
                }
            }
            assertTrue("The selected day's event must remain reachable at 200% font size in landscape.", visible)
        } finally {
            setScale(oldScale)
            device.setOrientationNatural()
            device.unfreezeRotation()
            device.waitForIdle()
        }
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
}
