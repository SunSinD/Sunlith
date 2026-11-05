package io.github.sunsind.sunlith

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.LocaleList
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.gridStart
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarExperienceTest {
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
    private lateinit var settings: SettingsStore
    private lateinit var repository: CalendarRepository
    private lateinit var vm: SunlithViewModel
    private var activity: ComponentActivity? = null
    private var oldLocales: LocaleList? = null
    private var calendarId = 0L
    private val account = "Sunlith experience test ${UUID.randomUUID()}"
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private val marker = "Experience today marker"

    @Before
    fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        device = UiDevice.getInstance(instrumentation)
        listOf("sunlith", "sunlith_ui").forEach { name -> snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).let { locales ->
                oldLocales = locales.applicationLocales
                locales.applicationLocales = LocaleList.forLanguageTags("en")
            }
        }
        repository = CalendarRepository(context)
        settings = SettingsStore(context)
        calendarId = insertLocalCalendar(context, "sunlith-experience-test", "Experience calendar", account)
        settings.selectedCalendarIds = setOf(calendarId)
        settings.hasChosenCalendars = true
        settings.themeMode = "dark"
        settings.startView = "Month"
        settings.agendaDisplay = "all"
        settings.weekStartsMonday = true
        settings.navigationAnimation = false
        settings.collapseCalendarOnScroll = false
        settings.widgetDays = 7
        settings.widgetOpacity = 100
        settings.widgetShowEmptyDays = true
        settings.widgetShowCalendarName = false
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().putBoolean("ai_intro_done", true).putString("view_mode", "Month").commit()
        addEvent(LocalDate.now(), marker)
        launch()
        node(By.pkg(context.packageName).text(marker))
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        finish()
        context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
        snapshots.forEach { (name, values) -> restoreTestPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
        if (Build.VERSION.SDK_INT >= 33) oldLocales?.let { context.getSystemService(LocaleManager::class.java).applicationLocales = it }
    }

    @Test
    fun monthGridKeepsSixRowsAndTheSameHeightAcrossFebruaryDecemberAndNovember() {
        val heights = mutableListOf<Int>()
        listOf(YearMonth.of(2027, 2), YearMonth.of(2026, 12), YearMonth.of(2026, 11)).forEach { month ->
            onMain { vm.select(month.atDay(1)) }
            val start = gridStart(month, true)
            node(day(start.plusDays(41)))
            device.waitForIdle()
            val cells = (0L until 42L).map { offset ->
                val selector = day(start.plusDays(offset))
                var bounds: android.graphics.Rect? = null
                repeat(3) {
                    if (bounds == null) try { bounds = node(selector).visibleBounds } catch (_: StaleObjectException) { device.waitForIdle() }
                }
                requireNotNull(bounds) { "A date cell must settle after the month changes: $selector" }
            }
            assertEquals("Every month must expose six complete calendar rows: $cells", 6, cells.map { it.top }.distinct().size)
            assertTrue("Each calendar row must keep seven date cells.", cells.groupBy { it.top }.values.all { it.size == 7 })
            heights += cells.maxOf { it.bottom } - cells.minOf { it.top }
        }
        assertTrue("Changing months must not move the agenda by changing grid height: $heights", heights.maxOrNull()!! - heights.minOrNull()!! <= 2)
    }

    @Test
    fun dayEventCardsShareTheSameBoundsForDifferentTimeLengths() {
        val today = LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        val ids = listOf(9 to 50, 11 to 40).mapIndexed { index, (hour, minute) ->
            val start = today.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            repository.saveEvent(EventDraft(calendarId = calendarId, title = "Aligned time $index", location = "Room 1", startMillis = start,
                endMillis = start + 60 * 60 * 1000, timeZone = zone.id, reminderMinutes = emptyList()))
        }
        onMain { vm.changeViewMode(ViewMode.Day); vm.reload() }
        val cards = ids.map { node(By.pkg(context.packageName).res("day_event_$it")).visibleBounds }
        assertEquals("Time labels must not move the left edge of event cards.", cards[0].left, cards[1].left)
        assertEquals(cards[0].right, cards[1].right)
        assertEquals("Equivalent event content must have equal height.", cards[0].height(), cards[1].height())
    }

    @Test
    fun selectedDayNumberStaysHorizontallyCentered() {
        val selected = LocalDate.of(2027, 2, 18)
        onMain { vm.select(selected) }
        val cell = node(day(selected))
        assertTrue("The date must be marked selected for accessibility.", cell.isChecked || cell.isSelected)
        device.waitForIdle()
        val bounds = cell.visibleBounds
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val pixels = buildList<Pair<Int, Int>> {
                for (x in bounds.left until bounds.right) {
                    for (y in bounds.top until bounds.bottom) {
                        val pixel = image.getPixel(x, y)
                        if (Color.red(pixel) > 185 && Color.green(pixel) > 185 && Color.blue(pixel) > 185) add(x to y)
                    }
                }
            }
            assertTrue("The selected date number must be visible.", pixels.isNotEmpty())
            val left = pixels.minOf { it.first }
            val right = pixels.maxOf { it.first }
            val top = pixels.minOf { it.second }
            val bottom = pixels.maxOf { it.second }
            val circleX = (left + right) / 2f
            val circleY = (top + bottom) / 2f
            assertTrue("The selected circle must be centered in its tappable day cell.", abs(circleX - bounds.exactCenterX()) <= 4 * context.resources.displayMetrics.density)
            val glyph = buildList<Pair<Int, Int>> {
                for (x in (left + (right - left) * 0.2f).toInt()..(right - (right - left) * 0.2f).toInt()) {
                    for (y in (top + (bottom - top) * 0.2f).toInt()..(bottom - (bottom - top) * 0.2f).toInt()) {
                        val pixel = image.getPixel(x, y)
                        if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) add(x to y)
                    }
                }
            }
            assertTrue("The selected number must be visible inside its circle.", glyph.isNotEmpty())
            assertTrue("The date number must be horizontally centered inside its circle.", abs((glyph.minOf { it.first } + glyph.maxOf { it.first }) / 2f - circleX) <= 3 * context.resources.displayMetrics.density)
            assertTrue("The date number must be vertically centered inside its circle.", abs((glyph.minOf { it.second } + glyph.maxOf { it.second }) / 2f - circleY) <= 3 * context.resources.displayMetrics.density)
        } finally { image.recycle() }
    }

    @Test
    fun explicitStartViewsAndLastUsedViewSurviveRelaunch() {
        onMain { vm.changeViewMode(ViewMode.Day) }
        settings.startView = "Month"
        relaunch()
        assertEquals(ViewMode.Month, vm.viewMode)
        settings.startView = "Week"
        relaunch()
        assertEquals(ViewMode.Week, vm.viewMode)
        settings.startView = "Day"
        relaunch()
        assertEquals(ViewMode.Day, vm.viewMode)
        settings.startView = "last"
        onMain { vm.changeViewMode(ViewMode.Week) }
        relaunch()
        assertEquals(ViewMode.Week, vm.viewMode)
    }

    @Test
    fun groupedSettingsExposeRelevantControlsAndPersistRealUiChanges() {
        node(By.pkg(context.packageName).desc(english.getString(R.string.action_more))).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.title_settings))).click()
        val groups = listOf(R.string.beta_settings_appearance, R.string.beta_settings_calendar, R.string.beta_settings_widget, R.string.beta_settings_reminders, R.string.beta_settings_transfer, R.string.beta_settings_about)
        groups.forEach { node(By.pkg(context.packageName).text(english.getString(it))) }
        assertFalse(device.hasObject(By.pkg(context.packageName).text(english.getString(R.string.settings_import))))
        assertFalse(device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.settings_widget_opacity))))
        node(By.pkg(context.packageName).text(english.getString(R.string.beta_settings_calendar))).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.settings_choose_calendars))).click()
        node(By.pkg(context.packageName).text("Experience calendar"))
        back()
        node(By.pkg(context.packageName).text(english.getString(R.string.settings_choose_calendars)))
        assertFalse("Returning from Calendars must preserve the Calendar settings subpage.", device.hasObject(By.pkg(context.packageName).text(english.getString(R.string.beta_settings_widget))))
        scrollToText(english.getString(R.string.view_week)).click()
        awaitUi { settings.startView == "Week" }
        assertEquals("Week", settings.startView)
        scrollToText(english.getString(R.string.beta_event_days)).click()
        awaitUi { settings.agendaDisplay == "events" }
        assertEquals("events", settings.agendaDisplay)
        scrollToText(english.getString(R.string.beta_collapse_calendar)).click()
        awaitUi { settings.collapseCalendarOnScroll }
        assertTrue("The Calendar settings switch must enable collapsing.", settings.collapseCalendarOnScroll)
        scrollToText(english.getString(R.string.beta_collapse_calendar)).click()
        awaitUi { !settings.collapseCalendarOnScroll }
        assertFalse("The Calendar settings switch must restore a fixed header.", settings.collapseCalendarOnScroll)
        back()
        node(By.pkg(context.packageName).text(english.getString(R.string.beta_settings_widget))).click()
        setProgress(english.getString(R.string.beta_widget_days), 14f)
        assertEquals(14, settings.widgetDays)
        setProgress(english.getString(R.string.settings_widget_opacity), 65f)
        assertEquals(65, settings.widgetOpacity)
        scrollToText(english.getString(R.string.beta_widget_empty_days)).click()
        assertFalse(settings.widgetShowEmptyDays)
        scrollToText(english.getString(R.string.beta_widget_calendar_name)).click()
        assertTrue(settings.widgetShowCalendarName)
        back()
        node(By.pkg(context.packageName).text(english.getString(R.string.beta_settings_transfer))).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.settings_import)))
        node(By.pkg(context.packageName).text(english.getString(R.string.settings_export)))
        back()
        node(By.pkg(context.packageName).text(english.getString(R.string.beta_settings_reminders))).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.settings_reminders_toggle)))
        back()
        node(By.pkg(context.packageName).text(english.getString(R.string.beta_settings_about))).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.about_source)))
    }

    @Test
    fun monthAgendaModesKeepSeparateEmptyDaysGroupedRangesAndEventsDistinct() {
        val selected = LocalDate.of(2027, 2, 10)
        val later = "Experience later marker"
        addEvent(selected, "Experience selected marker")
        addEvent(selected.plusDays(6), later)
        onMain { vm.select(selected); vm.reload() }
        awaitUi { device.findObjects(By.pkg(context.packageName).text(english.getString(R.string.no_events))).size >= 2 }
        assertEquals("all", vm.agendaDisplay)
        assertTrue("The default agenda must show separate empty days.", device.findObjects(By.pkg(context.packageName).text(english.getString(R.string.no_events))).size >= 2)
        onMain { vm.changeAgendaDisplay("compact") }
        node(By.pkg(context.packageName).textStartsWith("  " + english.getString(R.string.no_events)))
        node(By.pkg(context.packageName).text(later))
        onMain { vm.changeAgendaDisplay("events") }
        node(By.pkg(context.packageName).text(later))
        awaitUi { !device.hasObject(By.pkg(context.packageName).textContains(english.getString(R.string.no_events))) }
        assertFalse("Event-only mode must omit empty-day rows and empty ranges.", device.hasObject(By.pkg(context.packageName).textContains(english.getString(R.string.no_events))))
    }

    @Test
    fun weekAlwaysShowsAllSevenDaysForEveryMonthAgendaPreference() {
        val monday = LocalDate.of(2027, 2, 8)
        addEvent(monday, "Experience Monday marker")
        addEvent(monday.plusDays(6), "Experience Sunday marker")
        onMain { vm.select(monday); vm.changeViewMode(ViewMode.Week); vm.reload() }
        awaitUi { vm.coversRange(monday, monday.plusDays(6)) && vm.events.any { it.title == "Experience Sunday marker" } }
        listOf("all", "compact", "events").forEach { display ->
            onMain { vm.changeAgendaDisplay(display) }
            val texts = allAgendaTexts()
            assertTrue("Week must retain all seven day rows with the $display Month preference: $texts", (8..14).all { it.toString() in texts })
            assertTrue("Week must retain empty days with the $display Month preference.", english.getString(R.string.no_events) in texts)
            assertTrue("Week must include its first day's event: $texts", texts.any { it.contains("Experience Monday marker") })
            assertTrue("Week must include its last day's event: $texts", texts.any { it.contains("Experience Sunday marker") })
        }
    }

    @Test
    fun agendasIncludeBothBoundariesAndNeverReachOutsideTheirDisplayedPeriod() {
        val month = YearMonth.of(2027, 2)
        val monday = LocalDate.of(2027, 2, 8)
        val fixtures = mapOf(
            month.atDay(1).minusDays(1) to "Before month",
            month.atDay(1) to "First month day",
            month.atEndOfMonth() to "Last month day",
            month.atEndOfMonth().plusDays(1) to "After month",
            monday.minusDays(1) to "Before week",
            monday to "First week day",
            monday.plusDays(6) to "Last week day",
            monday.plusDays(7) to "After week",
        )
        fixtures.forEach { (date, title) -> addEvent(date, title) }
        onMain { vm.select(month.atDay(1)); vm.reload() }
        awaitUi { vm.coversRange(month.atDay(1), month.atEndOfMonth()) && vm.events.any { it.title == "Last month day" } }
        val monthTexts = allAgendaTexts()
        assertTrue("Month agenda must include its first and last days: $monthTexts", setOf("First month day", "Last month day").all { title -> monthTexts.any { it.contains(title) } })
        assertFalse("Month agenda must exclude events immediately outside the month.", setOf("Before month", "After month").any { title -> monthTexts.any { it.contains(title) } })
        onMain { vm.select(monday); vm.changeViewMode(ViewMode.Week); vm.reload() }
        awaitUi { vm.coversRange(monday, monday.plusDays(6)) && vm.events.any { it.title == "Last week day" } }
        val weekTexts = allAgendaTexts()
        assertTrue("Week agenda must include Monday and Sunday: $weekTexts", setOf("First week day", "Last week day").all { title -> weekTexts.any { it.contains(title) } })
        assertFalse("Week agenda must exclude events immediately outside the week.", setOf("Before week", "After week").any { title -> weekTexts.any { it.contains(title) } })
        assertEquals("Week view must preserve the selected date.", monday, vm.selectedDate)
    }

    @Test
    fun dayTabReturnsToTodayFromAnotherDateAndWhenAlreadyInDayView() {
        onMain { vm.select(LocalDate.of(2027, 2, 10)) }
        node(By.pkg(context.packageName).text(english.getString(R.string.view_day))).click()
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == LocalDate.now() }
        node(By.pkg(context.packageName).text(marker))
        onMain { vm.step(1) }
        assertEquals(LocalDate.now().plusDays(1), vm.selectedDate)
        node(By.pkg(context.packageName).text(english.getString(R.string.view_day))).click()
        awaitUi { vm.selectedDate == LocalDate.now() }
        node(By.pkg(context.packageName).text(marker))
    }

    @Test
    fun calendarStaysVisibleByDefaultAndCollapseToggleRestoresIt() {
        val first = gridStart(vm.month, true)
        val original = node(day(first)).visibleBounds
        agenda().scroll(Direction.DOWN, 0.9f)
        assertEquals("The calendar must remain still while scrolling by default.", original, node(day(first)).visibleBounds)
        onMain { vm.changeCollapseCalendarOnScroll(true) }
        assertTrue(settings.collapseCalendarOnScroll)
        agenda().scroll(Direction.DOWN, 0.9f)
        awaitUi { device.findObject(day(first))?.visibleBounds?.let { it.height() == 0 || it.top < original.top } != false }
        onMain { vm.changeCollapseCalendarOnScroll(false) }
        awaitUi { device.findObject(day(first))?.visibleBounds == original }
        assertFalse(settings.collapseCalendarOnScroll)
    }

    @Test
    fun toolbarArrowsMoveEachViewByItsPeriodAndReturn() {
        val today = LocalDate.now()
        listOf(
            Triple(ViewMode.Month, R.string.action_next_month, R.string.action_prev_month),
            Triple(ViewMode.Week, R.string.action_next_week, R.string.action_prev_week),
            Triple(ViewMode.Day, R.string.action_next_day, R.string.action_prev_day),
        ).forEach { (mode, next, previous) ->
            onMain { vm.select(today); vm.changeViewMode(mode) }
            node(By.pkg(context.packageName).desc(english.getString(next))).click()
            val expected = when (mode) {
                ViewMode.Month -> YearMonth.from(today).plusMonths(1).atDay(1)
                ViewMode.Week -> today.plusWeeks(1)
                ViewMode.Day -> today.plusDays(1)
            }
            awaitUi { vm.selectedDate == expected }
            assertEquals("The next arrow must keep the active view.", mode, vm.viewMode)
            if (mode != ViewMode.Day) node(day(expected))
            node(By.pkg(context.packageName).desc(english.getString(previous))).click()
            awaitUi { vm.selectedDate == today }
            assertEquals("The previous arrow must return to the original period.", YearMonth.from(today), vm.month)
            assertTrue("Period arrows must not open an editor.", vm.editor == null)
        }
    }

    @Test
    fun horizontalSwipesDoNotChangeDates() {
        val oldScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        fun setScale(scale: Float) {
            instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale $scale").let { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        }
        try {
            setScale(0f)
            settings.navigationAnimation = true
            relaunch()
            node(By.pkg(context.packageName).text(marker))
            val original = vm.selectedDate
            fun swipe(forward: Boolean) {
                val y = agenda().visibleBounds.centerY()
                val left = device.displayWidth / 6
                val right = device.displayWidth * 5 / 6
                device.swipe(if (forward) right else left, y, if (forward) left else right, y, 20)
                device.waitForIdle()
                assertTrue("A horizontal drag must not open an event editor.", vm.editor == null)
                assertEquals("A horizontal drag must preserve the selected date.", original, vm.selectedDate)
            }
            swipe(true)
            swipe(false)
            device.waitForIdle()
            assertEquals("Horizontal swipes must not change the date.", original, vm.selectedDate)
            assertEquals(ViewMode.Month, vm.viewMode)
        } finally { setScale(oldScale) }
    }

    @Test
    fun landscapeAtTwoHundredPercentLetsUsersReachAndSelectTheSixthMonthRow() {
        val oldScale = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
        fun setScale(scale: Float) {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $scale").let { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        }
        val start = gridStart(vm.month, true)
        val target = start.plusDays(38)
        try {
            setScale(2f)
            device.setOrientationLeft()
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            awaitUi { device.displayWidth > device.displayHeight && context.resources.configuration.fontScale >= 1.99f }
            device.waitForIdle()
            val headerBounds = node(By.pkg(context.packageName).res("calendar_grid")).visibleBounds
            val agendaBounds = agenda().visibleBounds
            assertTrue("Calendar and agenda must occupy separate areas.", headerBounds.bottom <= agendaBounds.top)
            assertTrue("The agenda must keep visible space below the calendar.", agendaBounds.height() > 30 * context.resources.displayMetrics.density)
            fun visibleLower(): UiObject2? {
                refreshAccessibility()
                return device.findObject(day(target))?.takeIf { it.visibleBounds.height() >= 20 && it.visibleBounds.centerY() in (headerBounds.top + 10)..(headerBounds.bottom - 10) }
            }
            var lower = visibleLower()
            repeat(20) {
                if (lower == null || lower!!.visibleBounds.height() < 20) {
                    device.swipe(headerBounds.centerX(), headerBounds.bottom - 10, headerBounds.centerX(), headerBounds.top + 10, 18)
                    device.waitForIdle()
                    lower = visibleLower()
                }
            }
            if (lower == null) captureFailure()
            assertTrue("The sixth calendar row must be reachable by dragging the month header at 200% font size in landscape.", lower != null)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "beta-landscape.png"))
            requireNotNull(lower).click()
            awaitUi { vm.selectedDate == target }
            node(By.pkg(context.packageName).desc(english.getString(R.string.action_new_event))).click()
            awaitUi { vm.editor?.startDate == target }
            node(By.pkg(context.packageName).desc(english.getString(R.string.action_close))).click()
            node(By.pkg(context.packageName).desc(english.getString(R.string.action_today))).click()
            awaitUi {
                vm.selectedDate == LocalDate.now() && device.findObject(day(LocalDate.now()))?.let {
                    (it.isChecked || it.isSelected) && it.visibleBounds.height() >= 20 && it.visibleBounds.centerY() in (headerBounds.top + 10)..(headerBounds.bottom - 10)
                } == true
            }
        } finally {
            setScale(oldScale)
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            device.setOrientationNatural()
            device.unfreezeRotation()
            awaitUi { device.displayHeight > device.displayWidth && abs(context.resources.configuration.fontScale - oldScale) < 0.01f }
            device.waitForIdle()
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ComponentActivity>().firstOrNull()?.let { activity = it }
            }
        }
    }

    @Test
    fun todayProvidesVisibleFeedbackEvenWhenTodayIsAlreadySelected() {
        device.waitForIdle()
        val current = node(By.pkg(context.packageName).text(marker))
        val pointX = (62 * context.resources.displayMetrics.density).toInt()
        val pointY = current.visibleBounds.centerY()
        val before = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val previous = vm.todayRequest
        val button = node(By.pkg(context.packageName).desc(english.getString(R.string.action_today))).visibleBounds
        device.click(button.centerX(), button.centerY())
        var highlighted = false
        var after = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val deadline = SystemClock.elapsedRealtime() + 950
        while (!highlighted && SystemClock.elapsedRealtime() < deadline) {
            highlighted = Color.red(after.getPixel(pointX, pointY)) > Color.red(before.getPixel(pointX, pointY)) + 8
            if (!highlighted) {
                after.recycle()
                SystemClock.sleep(20)
                after = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            }
        }
        try {
            if (!highlighted) {
                File(context.getExternalFilesDir(null), "today-before.png").outputStream().use { before.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                File(context.getExternalFilesDir(null), "today-after.png").outputStream().use { after.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue("Today must visibly highlight its agenda row at ($pointX, $pointY): ${Color.red(before.getPixel(pointX, pointY))} -> ${Color.red(after.getPixel(pointX, pointY))}", highlighted)
        } finally { before.recycle(); after.recycle() }
        assertEquals(previous + 1, vm.todayRequest)
        assertEquals(LocalDate.now(), vm.selectedDate)
        onMain { vm.select(LocalDate.now().plusDays(40)) }
        node(By.pkg(context.packageName).desc(english.getString(R.string.action_today))).click()
        node(day(LocalDate.now()))
        assertEquals(LocalDate.now(), vm.selectedDate)
        awaitUi { device.findObject(day(LocalDate.now()))?.let { it.isChecked || it.isSelected } == true }
        node(By.pkg(context.packageName).text(marker))
    }

    private fun addEvent(date: LocalDate, title: String) {
        repository.saveEvent(EventDraft(calendarId = calendarId, title = title, startMillis = CalendarDates.allDayMillis(date), endMillis = CalendarDates.allDayMillis(date.plusDays(1)), allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
    }

    private fun day(date: LocalDate): BySelector = By.pkg(context.packageName).descStartsWith(Formats(english, Locale.ENGLISH).dayLong(date) + ",")

    private fun agenda(): UiObject2 = node(By.pkg(context.packageName).res("calendar_agenda"))

    private fun allAgendaTexts(): Set<String> {
        repeat(8) { agenda().scroll(Direction.UP, 1f) }
        val texts = mutableSetOf<String>()
        fun collect(): String {
            val textNodes = agenda().findObjects(By.text(Pattern.compile("(?s).+")))
            val describedNodes = agenda().findObjects(By.desc(Pattern.compile("(?s).+")))
            texts += textNodes.mapNotNull { it.text }.flatMap { it.lines() }
            texts += describedNodes.mapNotNull { it.contentDescription }
            return (textNodes + describedNodes).joinToString("|") { "${it.text}/${it.contentDescription}/${it.visibleBounds}" }
        }
        var previous = collect()
        repeat(40) {
            agenda().scroll(Direction.DOWN, 0.8f)
            device.waitForIdle()
            val current = collect()
            if (current == previous) return texts
            previous = current
        }
        error("The bounded agenda must reach its end within forty scrolls.")
    }

    private fun onMain(action: () -> Unit) {
        instrumentation.runOnMainSync(action)
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        refreshAccessibility()
    }

    private fun launch() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        onMain { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java] }
        node(By.pkg(context.packageName).desc(context.getString(R.string.action_new_event)))
        device.waitForIdle()
    }

    private fun finish() {
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        activity = null
    }

    private fun relaunch() {
        finish()
        launch()
    }

    private fun back() {
        node(By.pkg(context.packageName).desc(english.getString(R.string.action_back))).click()
        device.waitForIdle()
    }

    private fun scrollToText(text: String): UiObject2 {
        repeat(12) {
            device.wait(Until.findObject(By.pkg(context.packageName).text(text)), 300)?.let { return it }
            node(By.pkg(context.packageName).scrollable(true)).scroll(Direction.DOWN, 0.65f)
        }
        error("Expected settings control was not reachable: $text")
    }

    private fun setProgress(label: String, value: Float) {
        node(By.pkg(context.packageName).desc(label))
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val labelled = requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow)) { "Accessible slider missing: $label" }
        fun progressNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { progressNode(it) }?.let { return it }
            return null
        }
        val control = progressNode(labelled) ?: labelled.parent?.let(::progressNode) ?: run {
            captureFailure()
            fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
                node ?: return ""
                return " ".repeat(depth) + "${node.className} text=${node.text} desc=${node.contentDescription} range=${node.rangeInfo} actions=${node.actionList}\n" + (0 until node.childCount).joinToString("") { describe(node.getChild(it), depth + 1) }
            }
            File(context.getExternalFilesDir(null), "slider-actions.txt").writeText(describe(instrumentation.uiAutomation.rootInActiveWindow))
            error("The slider must expose a progress action: $label")
        }
        assertTrue("Slider must support setting progress through accessibility: $label", control.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
        device.waitForIdle()
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            refreshAccessibility()
            if (condition()) return
            SystemClock.sleep(100)
        }
        captureFailure()
        assertTrue("The expected rendered calendar state must appear.", condition())
    }

    private fun captureFailure() {
        device.takeScreenshot(File(context.getExternalFilesDir(null), "experience-failure.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "experience-failure.xml"))
    }

    private fun node(selector: BySelector): UiObject2 {
        refreshAccessibility()
        device.wait(Until.findObject(selector), 15_000)?.let { return it }
        captureFailure()
        error("Expected calendar UI was not shown: $selector")
    }

    private fun refreshAccessibility() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
    }
}

internal fun restoreTestPreferences(prefs: SharedPreferences, values: Map<String, *>) {
    val editor = prefs.edit()
    (prefs.all.keys - values.keys).forEach(editor::remove)
    values.forEach { (key, value) ->
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
    editor.commit()
}
