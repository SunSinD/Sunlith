package io.github.sunsind.sunlith

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
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
import io.github.sunsind.sunlith.system.LauncherIcons
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
        settings.materialYou = false
        settings.agendaRange = "month"
        settings.monthLayout = "split"
        settings.weekLayout = "list"
        settings.dayLayout = "cards"
        settings.showNavigationArrows = false
        settings.swipeNavigation = true
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
        node(By.pkg(context.packageName).desc(english.getString(R.string.title_settings))).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.title_calendars)))
        node(By.pkg(context.packageName).text(english.getString(R.string.beta_settings_appearance)))
        assertFalse(device.hasObject(By.pkg(context.packageName).text(english.getString(R.string.settings_import))))
        assertFalse(device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.settings_widget_opacity))))
        node(By.pkg(context.packageName).text(english.getString(R.string.title_calendars))).click()
        node(By.pkg(context.packageName).text("Experience calendar"))
        back()
        node(By.pkg(context.packageName).text(english.getString(R.string.settings_general)))
        scrollToText(english.getString(R.string.settings_general)).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.beta_start_view).uppercase(Locale.ENGLISH)))
        node(By.pkg(context.packageName).text(english.getString(R.string.view_week))).click()
        awaitUi { settings.startView == "Week" }
        scrollToText(english.getString(R.string.beta_event_days)).click()
        awaitUi { settings.agendaDisplay == "events" }
        scrollToText(english.getString(R.string.beta_collapse_calendar)).click()
        awaitUi { settings.collapseCalendarOnScroll }
        scrollToText(english.getString(R.string.beta_collapse_calendar)).click()
        awaitUi { !settings.collapseCalendarOnScroll }
        scrollToText(english.getString(R.string.settings_reminders_toggle))
        scrollToText(english.getString(R.string.settings_import))
        scrollToText(english.getString(R.string.settings_export))
        back()
        scrollToText(english.getString(R.string.beta_settings_widget)).click()
        setProgress(english.getString(R.string.beta_widget_days), 14f)
        assertEquals(14, settings.widgetDays)
        setProgress(english.getString(R.string.settings_widget_opacity), 65f)
        assertEquals(65, settings.widgetOpacity)
        scrollToText(english.getString(R.string.beta_widget_empty_days)).click()
        assertFalse(settings.widgetShowEmptyDays)
        scrollToText(english.getString(R.string.beta_widget_calendar_name)).click()
        assertTrue(settings.widgetShowCalendarName)
        back()
        scrollToText(english.getString(R.string.about_source))
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
        selectView(ViewMode.Day)
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == LocalDate.now() }
        node(By.pkg(context.packageName).text(marker))
        onMain { vm.step(1) }
        assertEquals(LocalDate.now().plusDays(1), vm.selectedDate)
        selectView(ViewMode.Day)
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
    fun toolbarArrowsAreOptionalAndMoveEachViewByItsPeriodAndReturn() {
        assertFalse(device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_next_month))))
        onMain { vm.changeShowNavigationArrows(true) }
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
    fun horizontalSwipesNavigateAndCanBeDisabledWithoutOpeningAnEditor() {
        fun swipe(forward: Boolean) {
            device.waitForIdle()
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
            val bounds = stableBounds(By.pkg(context.packageName).res("calendar_grid"))
            val left = bounds.left + bounds.width() / 6
            val right = bounds.right - bounds.width() / 6
            device.swipe(if (forward) right else left, bounds.centerY(), if (forward) left else right, bounds.centerY(), 25)
            device.waitForIdle()
            assertTrue("A horizontal swipe must not open an event editor.", vm.editor == null)
        }
        ViewMode.entries.forEach { mode ->
            onMain { vm.changeViewMode(mode); vm.select(LocalDate.now()); vm.changeNavigationAnimation(mode != ViewMode.Week) }
            val original = vm.selectedDate
            val next = when (mode) {
                ViewMode.Month -> YearMonth.from(original).plusMonths(1).atDay(1)
                ViewMode.Week -> original.plusWeeks(1)
                ViewMode.Day -> original.plusDays(1)
            }
            swipe(true)
            awaitUi { vm.selectedDate == next }
            swipe(false)
            awaitUi { vm.selectedDate == original }
        }
        onMain { vm.changeViewMode(ViewMode.Month) }
        onMain { vm.changeSwipeNavigation(false) }
        val disabledDate = vm.selectedDate
        swipe(true)
        assertEquals("Disabling swipe keeps the date fixed.", disabledDate, vm.selectedDate)
        assertEquals(ViewMode.Month, vm.viewMode)
    }

    @Test
    fun curvedVerticalAgendaScrollDoesNotChangeTheCalendarPeriod() {
        val original = vm.selectedDate
        val bounds = agenda().visibleBounds
        val x = bounds.left + bounds.width() / 4
        val bottom = bounds.bottom - bounds.height() / 5
        val top = bounds.top + bounds.height() / 5
        device.swipe(arrayOf(Point(x, bottom), Point(x + 20, (bottom + top) / 2), Point(x + 60, top)), 25)
        device.waitForIdle()
        assertEquals(original, vm.selectedDate)
        assertEquals(ViewMode.Month, vm.viewMode)
        assertTrue(vm.editor == null)
    }

    @Test
    fun twoQuickAnimatedArrowTapsAdvanceTwoMonths() {
        onMain { vm.changeShowNavigationArrows(true); vm.changeNavigationAnimation(true) }
        val expected = vm.month.plusMonths(2)
        val bounds = node(By.pkg(context.packageName).desc(english.getString(R.string.action_next_month))).visibleBounds
        repeat(2) { device.click(bounds.centerX(), bounds.centerY()) }
        awaitUi { vm.month == expected }
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
    fun floatingTodayAppearsOnlyWhenAwayAndReturnsToTheCurrentDay() {
        val today = By.pkg(context.packageName).desc(english.getString(R.string.action_today))
        assertFalse("Today stays out of the toolbar when the current day is visible.", device.hasObject(today))
        onMain { vm.changeAgendaRange("continuous") }
        awaitUi { vm.coversRange(LocalDate.now().minusDays(60), LocalDate.now().plusDays(120)) }
        agenda().scroll(Direction.DOWN, 0.9f)
        node(today)
        val previous = vm.todayRequest
        node(today).click()
        awaitUi { vm.todayRequest == previous + 1 && vm.selectedDate == LocalDate.now() }
        node(By.pkg(context.packageName).text(marker))
        awaitUi { !device.hasObject(today) }
        onMain { vm.select(LocalDate.now().plusDays(40)) }
        node(today).click()
        node(By.pkg(context.packageName).text(marker))
        awaitUi { vm.selectedDate == LocalDate.now() && !device.hasObject(today) }
    }

    @Test
    fun dayLayoutsNeverShowTheFloatingTodayActionEvenAwayFromToday() {
        val today = By.pkg(context.packageName).desc(english.getString(R.string.action_today))
        listOf("cards", "timeline", "schedule").forEach { layout ->
            onMain { vm.changeDayLayout(layout); vm.changeViewMode(ViewMode.Day); vm.select(LocalDate.now().plusDays(40)) }
            node(By.pkg(context.packageName).res("calendar_day_picker"))
            assertFalse("Day $layout must use the Day view control to return to today.", device.hasObject(today))
            selectView(ViewMode.Day)
            awaitUi { vm.selectedDate == LocalDate.now() }
            assertFalse(device.hasObject(today))
        }
    }

    @Test
    fun appearanceIconTilesRequireConfirmationAndPersistAcrossRecreation() {
        val original = LauncherIcons.saved(context)
        try {
            node(By.pkg(context.packageName).desc(english.getString(R.string.title_settings))).click()
            node(By.pkg(context.packageName).text(english.getString(R.string.beta_settings_appearance))).click()
            val target = if (original == LauncherIcons.Style.Light) LauncherIcons.Style.Dark else LauncherIcons.Style.Light
            val selector = By.pkg(context.packageName).desc(english.getString(R.string.launcher_icon_choice, english.getString(target.label)))
            repeat(8) {
                if (!device.hasObject(selector)) node(By.pkg(context.packageName).scrollable(true)).scroll(Direction.DOWN, 0.5f, 500)
            }
            assertFalse("Only Light and Dark are offered.", device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.launcher_icon_choice, english.getString(R.string.launcher_icon_neutral)))))
            node(selector).click()
            node(By.pkg(context.packageName).text(english.getString(R.string.launcher_icon_notice_title)))
            assertEquals("Opening the notice must not change the icon.", original, LauncherIcons.saved(context))
            node(By.pkg(context.packageName).text(english.getString(R.string.action_cancel))).click()
            assertEquals(original, LauncherIcons.saved(context))
            node(selector).click()
            val previous = requireNotNull(activity)
            node(By.pkg(context.packageName).text(english.getString(R.string.action_ok))).click()
            awaitUi {
                var restored = false
                instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ComponentActivity>().firstOrNull { it !== previous }?.let {
                            activity = it
                            vm = ViewModelProvider(it)[SunlithViewModel::class.java]
                            restored = true
                        }
                }
                restored && LauncherIcons.saved(context) == target && LauncherIcons.enabled(context) == listOf(target)
            }
            node(selector)
            assertEquals(target, LauncherIcons.saved(context))
        } finally {
            assertTrue(LauncherIcons.select(context, original))
        }
    }

    private fun selectView(mode: ViewMode) {
        refreshAccessibility()
        val menu = device.findObject(By.pkg(context.packageName).res("calendar_view_menu"))
        menu?.click()
        if (menu != null) device.waitForIdle()
        val label = when (mode) {
            ViewMode.Month -> R.string.view_month
            ViewMode.Week -> R.string.view_week
            ViewMode.Day -> R.string.view_day
        }
        val selector = By.pkg(context.packageName).text(english.getString(label))
        node(selector)
        if (menu == null) node(selector).click() else device.findObjects(selector).maxBy { it.visibleBounds.centerY() }.click()
        awaitUi { vm.viewMode == mode }
    }

    @Test
    fun currentDateRefreshMovesTodayWithoutReplacingAnExplicitlySelectedDate() {
        val original = LocalDate.now()
        onMain { vm.changeViewMode(ViewMode.Day); vm.refreshCurrentDate(original.plusDays(1)) }
        awaitUi { vm.today == original.plusDays(1) && vm.selectedDate == vm.today }
        node(day(vm.today))
        val chosen = original.plusDays(8)
        onMain { vm.select(chosen); vm.refreshCurrentDate(original.plusDays(2)) }
        awaitUi { vm.today == original.plusDays(2) }
        assertEquals("A new day must preserve a date chosen explicitly.", chosen, vm.selectedDate)
        onMain { vm.refreshCurrentDate(original) }
    }

    @Test
    fun fullMonthKeepsTodayHiddenWhenAllRowsFitAfterSelectingALaterDate() {
        val today = LocalDate.of(2026, 11, 6)
        onMain {
            vm.applyLayoutPreset("planner")
            vm.changeViewMode(ViewMode.Month)
            vm.refreshCurrentDate(today)
            vm.select(today.withDayOfMonth(27))
        }
        node(By.pkg(context.packageName).res("calendar_full_month"))
        node(day(today))
        node(day(today.withDayOfMonth(27)))
        device.waitForIdle()
        assertFalse("Today is already visible in the fitting full-month grid.", device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))))
    }

    @Test
    fun fullMonthTodayReturnsToItsRowWhenLargeTextRequiresScrolling() {
        val oldScale = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
        fun setScale(scale: Float) {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $scale")
                .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        }
        try {
            setScale(2f)
            device.setOrientationLeft()
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            awaitUi { device.displayWidth > device.displayHeight && context.resources.configuration.fontScale >= 1.99f }
            onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Month); vm.goToday() }
            val today = LocalDate.now()
            val todayAction = By.pkg(context.packageName).desc(english.getString(R.string.action_today))
            val grid = node(By.pkg(context.packageName).res("calendar_grid"))
            val bounds = grid.visibleBounds
            val top = bounds.top + bounds.height() / 5
            val bottom = bounds.bottom - bounds.height() / 5
            val early = java.time.temporal.ChronoUnit.DAYS.between(gridStart(vm.month, vm.weekStartsMonday), today) < 21
            repeat(5) {
                device.swipe(bounds.centerX(), if (early) bottom else top, bounds.centerX(), if (early) top else bottom, 30)
            }
            node(todayAction).click()
            awaitUi {
                device.findObject(day(today))?.visibleBounds?.let { it.height() > 20 && it.centerY() in bounds.top..bounds.bottom } == true &&
                    !device.hasObject(todayAction)
            }
            assertEquals(ViewMode.Month, vm.viewMode)
            assertEquals(today, vm.selectedDate)
        } finally {
            setScale(oldScale)
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            device.setOrientationNatural()
            device.unfreezeRotation()
            awaitUi { device.displayHeight > device.displayWidth && abs(context.resources.configuration.fontScale - oldScale) < 0.01f }
        }
    }

    @Test
    fun continuousAgendaScrollsAcrossAMonthBoundary() {
        val month = YearMonth.from(LocalDate.now())
        val last = month.atEndOfMonth()
        addEvent(last, "Month boundary marker")
        addEvent(last.plusDays(1), "Next month marker")
        onMain { vm.changeAgendaRange("continuous"); vm.select(last); vm.reload() }
        awaitUi { vm.events.any { it.title == "Next month marker" } }
        node(By.pkg(context.packageName).text("Month boundary marker"))
        agenda().scroll(Direction.DOWN, 0.18f, 500)
        node(By.pkg(context.packageName).text("Next month marker"))
        assertEquals("Scrolling the agenda keeps the selected overview month stable.", month, vm.month)
        node(By.pkg(context.packageName).res("calendar_grid"))
    }

    @Test
    fun loadingEarlierAgendaDatesKeepsTheVisibleEventInPlace() {
        onMain { vm.changeAgendaRange("continuous") }
        awaitUi { vm.coversRange(LocalDate.now().minusDays(60), LocalDate.now().plusDays(120)) }
        val before = node(By.pkg(context.packageName).text(marker)).visibleBounds
        val oldStart = requireNotNull(vm.loadedFrom)
        onMain { vm.extendAgenda(false) }
        awaitUi { vm.loadedFrom?.isBefore(oldStart) == true }
        val after = node(By.pkg(context.packageName).text(marker)).visibleBounds
        assertTrue("Loading earlier dates must preserve the visible row: $before / $after", abs(after.top - before.top) <= 2)
        assertEquals(LocalDate.now(), vm.selectedDate)
    }

    @Test
    fun continuousAgendaPagesInBothDirectionsAndKeepsTheGridLoaded() {
        settings.agendaRange = "continuous"
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var paging: SunlithViewModel
        val original = LocalDate.now()
        val future = original.plusDays(175)
        addEvent(future, "Far future marker")
        try {
            onMain {
                paging = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as android.app.Application))[SunlithViewModel::class.java]
            }
            awaitUi { paging.coversRange(original.minusDays(60), original.plusDays(120)) }
            val oldEnd = requireNotNull(paging.loadedTo)
            onMain { paging.extendAgenda(true) }
            awaitUi { paging.loadedTo?.isAfter(oldEnd) == true && paging.events.any { it.title == "Far future marker" } }
            val oldStart = requireNotNull(paging.loadedFrom)
            onMain { paging.extendAgenda(false) }
            awaitUi { paging.loadedFrom?.isBefore(oldStart) == true }
            assertEquals("Paging must not move the selected calendar date.", original, paging.selectedDate)
            repeat(7) {
                val end = requireNotNull(paging.loadedTo)
                onMain { paging.extendAgenda(true) }
                awaitUi { paging.loadedTo?.isAfter(end) == true }
            }
            assertTrue("The agenda cache remains bounded during long scrolling.", java.time.temporal.ChronoUnit.DAYS.between(paging.loadedFrom, paging.loadedTo) < 730)
            assertTrue("Grid data stays loaded after the agenda moves far away.", paging.coversRange(gridStart(paging.month, true), gridStart(paging.month, true).plusDays(41)))
        } finally { onMain { store.clear() } }
    }

    private fun addEvent(date: LocalDate, title: String) {
        repository.saveEvent(EventDraft(calendarId = calendarId, title = title, startMillis = CalendarDates.allDayMillis(date), endMillis = CalendarDates.allDayMillis(date.plusDays(1)), allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
    }

    private fun day(date: LocalDate): BySelector = By.pkg(context.packageName).descStartsWith(Formats(english, Locale.ENGLISH).dayLong(date) + ",")

    private fun agenda(): UiObject2 = node(By.pkg(context.packageName).res("calendar_agenda")).apply {
        val bounds = visibleBounds
        setGestureMargins(bounds.width() / 20, bounds.height() / 20, bounds.width() * 3 / 5, bounds.height() / 20)
    }

    private fun allAgendaTexts(): Set<String> {
        for (attempt in 0 until 8) {
            if (!agenda().scroll(Direction.UP, 1f)) break
        }
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
        val selector = By.pkg(context.packageName).text(text)
        device.wait(Until.findObject(selector), 300)?.let { return it }
        repeat(12) {
            node(By.pkg(context.packageName).scrollable(true)).scroll(Direction.UP, 0.8f)
            device.wait(Until.findObject(selector), 200)?.let { return it }
        }
        repeat(18) {
            node(By.pkg(context.packageName).scrollable(true)).scroll(Direction.DOWN, 0.65f)
            device.wait(Until.findObject(selector), 300)?.let { return it }
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

    private fun stableBounds(selector: BySelector): Rect {
        repeat(5) {
            try { return node(selector).visibleBounds }
            catch (_: androidx.test.uiautomator.StaleObjectException) { device.waitForIdle() }
        }
        error("The calendar viewport did not settle: $selector")
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
