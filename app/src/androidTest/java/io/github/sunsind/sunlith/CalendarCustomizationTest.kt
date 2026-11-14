package io.github.sunsind.sunlith

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentProvider
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.provider.CalendarContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.regex.Pattern
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.DarkPalette
import io.github.sunsind.sunlith.ui.CourseColors
import io.github.sunsind.sunlith.ui.eventColors
import io.github.sunsind.sunlith.ui.LightPalette
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.ContinuousTimeline
import io.github.sunsind.sunlith.ui.Timeline
import io.github.sunsind.sunlith.ui.TimelineAxis
import io.github.sunsind.sunlith.ui.TimelineEventList
import io.github.sunsind.sunlith.ui.TimelineFocus
import io.github.sunsind.sunlith.ui.SunlithTheme
import io.github.sunsind.sunlith.ui.SunlithApp
import io.github.sunsind.sunlith.ui.gridStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import java.util.UUID
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
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
class CalendarCustomizationTest {
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
    private lateinit var vm: SunlithViewModel
    private var calendarLocale = Locale.ENGLISH
    private var activity: ComponentActivity? = null
    private var oldLocales: LocaleList? = null
    private var calendarId = 0L
    private val account = "Sunlith customization ${UUID.randomUUID()}"
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private val date = LocalDate.now().with(DayOfWeek.WEDNESDAY)
    private var firstId = 0L
    private var secondId = 0L
    private var allDayId = 0L
    private val firstTitle = "Customization first overlap"
    private val secondTitle = "Customization second overlap"
    private val allDayTitle = "Customization all-day"
    private val tomorrowTitle = "Customization next date"

    @Before
    fun setUp() {
        requireEmulator()
        val targetContext = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("timelineDisplay") == "true") {
            calendarLocale = Locale.forLanguageTag(arguments.getString("timelineLanguage") ?: "en")
            assumeTrue("Before API 33 the external display profile must set the requested system language.",
                Build.VERSION.SDK_INT >= 33 || targetContext.resources.configuration.locales[0].language == calendarLocale.language)
        }
        context = targetContext
        english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        device = UiDevice.getInstance(instrumentation)
        listOf("sunlith", "sunlith_ui").forEach { name -> snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)
            oldLocales = locales.applicationLocales
            locales.applicationLocales = LocaleList.forLanguageTags(calendarLocale.toLanguageTag())
        }
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("ai_intro_done", true).putBoolean("layout_setup_done", true).putBoolean("notify_asked", true).commit()
        repository = CalendarRepository(context)
        calendarId = insertLocalCalendar(context, "sunlith-customization-test", "Customization calendar", account)
        SettingsStore(context).apply {
            selectedCalendarIds = setOf(calendarId)
            hasChosenCalendars = true
            themeMode = "light"
            materialYou = false
            navigationAnimation = false
            collapseCalendarOnScroll = false
            startView = "Day"
            keepSelectedDate = true
            monthLayout = "split"
            weekLayout = "list"
            dayLayout = "cards"
            visibleWeekDays = 7
            rollingFiveDays = true
            weekHeaderSelection = true
            groupEmptyHours = false
            layoutPreset = "sunlith"
            agendaDisplay = "all"
            agendaRange = "continuous"
            weekStartsMonday = true
            showNavigationArrows = false
            swipeNavigation = true
        }
        firstId = addTimed(date, firstTitle, 9, 11)
        secondId = addTimed(date, secondTitle, 10, 12)
        allDayId = repository.saveEvent(EventDraft(
            calendarId = calendarId, title = allDayTitle,
            startMillis = CalendarDates.allDayMillis(date), endMillis = CalendarDates.allDayMillis(date.plusDays(1)),
            allDay = true, timeZone = "UTC", reminderMinutes = emptyList(),
        ))
        addTimed(date.plusDays(1), tomorrowTitle, 9, 10)
        launch()
        awaitUi { vm.events.any { it.id == firstId } && vm.events.any { it.id == secondId } }
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        finish()
        if (calendarId > 0) context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
        snapshots.forEach { (name, values) -> restoreTestPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
        if (Build.VERSION.SDK_INT >= 33) oldLocales?.let { context.getSystemService(LocaleManager::class.java).applicationLocales = it }
    }

    @Test
    fun todayPillFollowsVisibleAgendaDatesThroughTouchScrollingAndReturnsToToday() {
        val today = LocalDate.now()
        onMain { vm.changeViewMode(ViewMode.Month); vm.goToday() }
        node(By.pkg(context.packageName).res("calendar_agenda"))
        val selected = vm.selectedDate
        listOf(Direction.UP to "down", Direction.DOWN to "up").forEach { (scroll, arrow) ->
            onMain { vm.goToday() }
            awaitUi { !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
            scrollAgendaUntilArrow(scroll, arrow)
            assertEquals("Scrolling must preserve the selected date.", selected, vm.selectedDate)
            val button = node(By.pkg(context.packageName).res("calendar_today_$arrow"))
            assertEquals(english.getString(R.string.action_today), button.contentDescription)
            assertTrue(button.isClickable)
            assertTrue(button.visibleBounds.height() >= 48 * context.resources.displayMetrics.density)
            captureMinor("agenda-$arrow")
            val request = vm.todayRequest
            clickCurrent(By.pkg(context.packageName).res("calendar_today_$arrow"))
            awaitUi { vm.selectedDate == today && vm.todayRequest == request + 1 &&
                !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
        }
        onMain { vm.changeViewMode(ViewMode.Day) }
        assertFalse(device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))))
    }

    @Test
    fun compactAndSparseAgendasUseVisibleDatesInsteadOfTheSelectedMonth() {
        val today = LocalDate.now()
        val before = today.minusDays(28)
        val after = today.plusDays(28)
        listOf(before, after).forEach { day -> repeat(14) { addTimed(day, "Minor sparse $day $it", 8, 9) } }
        addTimed(today, "Minor today anchor", 10, 11)
        listOf("compact", "events").forEach { display ->
            onMain { vm.changeViewMode(ViewMode.Month); vm.changeAgendaDisplay(display); vm.reload(); vm.goToday() }
            awaitUi { !vm.loadingEvents }
            listOf(Direction.UP to "down", Direction.DOWN to "up").forEach { (scroll, arrow) ->
                onMain { vm.goToday() }
                scrollAgendaUntilArrow(scroll, arrow)
                assertEquals(today, vm.selectedDate)
                captureMinor("$display-$arrow")
                clickCurrent(By.pkg(context.packageName).res("calendar_today_$arrow"))
                awaitUi { !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
            }
        }
    }

    @Test
    fun eventsOnlyTodayWithoutAnEventReturnsAcrossEmptyGapsAndSingleSidedMonths() {
        val today = LocalDate.now()
        val month = java.time.YearMonth.from(today)
        val before = today.minusDays(1).takeIf { java.time.YearMonth.from(it) == month }
        val after = today.plusDays(1).takeIf { java.time.YearMonth.from(it) == month }
        val cases = listOfNotNull(before?.let { listOf(it) }, after?.let { listOf(it) },
            if (before != null && after != null) listOf(before, after) else null)
        cases.forEachIndexed { case, days ->
            context.contentResolver.delete(CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(calendarId.toString()))
            days.forEach { day -> repeat(28) { addTimed(day, "Empty today case $case item $it", 8, 9) } }
            onMain {
                vm.changeViewMode(ViewMode.Month)
                vm.changeAgendaRange("month")
                vm.changeAgendaDisplay("events")
                vm.goToday()
                vm.reload()
            }
            awaitUi { !vm.loadingEvents && vm.events.size == days.size * 28 &&
                vm.events.all { it.title.startsWith("Empty today case $case") } }
            onMain { vm.goToday() }
            awaitUi { !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
            val scroll = if (days.last().isBefore(today)) Direction.UP else Direction.DOWN
            val arrow = if (scroll == Direction.UP) "down" else "up"
            scrollAgendaUntilArrow(scroll, arrow)
            clickCurrent(By.pkg(context.packageName).res("calendar_today_$arrow"))
            awaitUi { !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
            assertEquals(today, vm.selectedDate)
            assertTrue("The events-only fixture must contain no event on today", vm.eventsByDay[today].isNullOrEmpty())
            captureMinor("empty-today-$case")
        }
    }

    @Test
    fun aVisibleSpilloverTodayKeepsTheReturnButtonForTheSelectedGridMonth() {
        val today = LocalDate.now()
        val current = java.time.YearMonth.from(today)
        val adjacent = listOf(current.minusMonths(1), current.plusMonths(1)).firstOrNull { month ->
            val start = gridStart(month, true)
            !today.isBefore(start) && !today.isAfter(start.plusDays(41))
        } ?: return
        onMain { vm.changeViewMode(ViewMode.Month); vm.changeMonthLayout("full"); vm.select(adjacent.atDay(15)) }
        node(By.pkg(context.packageName).res("calendar_full_month"))
        val arrow = if (adjacent < current) "next" else "previous"
        clickCurrent(By.pkg(context.packageName).res("calendar_today_$arrow"))
        awaitUi { vm.month == current && vm.selectedDate == today &&
            !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
        captureMinor("grid-spillover-return")
    }

    @Test
    fun todayDirectionSurvivesBoundedRangesGridAndWeekLayoutDisposal() {
        val today = LocalDate.now()
        fun checkPeriod(name: String) {
            listOf(today.minusMonths(2) to "next", today.plusMonths(2) to "previous").forEach { (shown, arrow) ->
                onMain { vm.select(shown) }
                awaitUi { vm.selectedDate == shown && device.hasObject(day(shown)) &&
                    device.hasObject(By.pkg(context.packageName).res("calendar_today_$arrow")) }
                captureMinor("period-$name-$arrow")
            }
            onMain { vm.goToday() }
            awaitUi { !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
        }
        onMain { vm.changeViewMode(ViewMode.Month) }
        listOf("month", "week", "day").forEach { range ->
            onMain { vm.changeAgendaRange(range) }
            checkPeriod(range)
        }
        onMain { vm.changeMonthLayout("full") }
        checkPeriod("grid")
        onMain { vm.changeViewMode(ViewMode.Week); vm.changeWeekLayout("list") }
        checkPeriod("week-list")
        listOf(3, 7).forEach { days ->
            onMain { vm.changeWeekLayout("timeline"); vm.changeVisibleWeekDays(days) }
            checkPeriod("week-timeline$days")
        }
        onMain { vm.changeWeekLayout("list"); vm.changeViewMode(ViewMode.Month); vm.changeMonthLayout("split") }
        node(By.pkg(context.packageName).res("calendar_agenda"))
        awaitUi { !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
    }

    @Test
    fun todayReturnsToItsDateAfterRestoringABoundedAgendaPage() {
        val today = LocalDate.now()
        val marker = "Bounded restored Today marker"
        addTimed(today, marker, 7, 8)
        prepareBoundedRestoreAgenda()
        val request = vm.todayRequest
        listOf(today.minusMonths(2), today.plusMonths(2)).forEach { shown ->
            onMain { vm.select(shown) }
            awaitUi { vm.selectedDate == shown && device.hasObject(day(shown)) }
        }
        onMain { vm.goToday() }
        awaitUi { vm.selectedDate == today && vm.todayRequest == request + 1 &&
            visibleAgendaMarker(marker) != null &&
            !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
    }

    @Test
    fun boundedAgendaPreservesOtherMonthScrollAcrossAnInterveningTodayRequest() {
        val today = LocalDate.now()
        val todayMarker = "Intervening Today marker"
        addTimed(today, todayMarker, 7, 8)
        val (anchor, prefix) = prepareBoundedRestoreAgenda(monthOffset = 1)
        val first = requireNotNull(visibleAgendaMarker(prefix)).first
        val list = node(By.pkg(context.packageName).res("calendar_agenda"))
        val bounds = list.visibleBounds
        list.setGestureMargins(bounds.width() / 5, bounds.height() / 6, bounds.width() / 5, bounds.height() / 4)
        list.scroll(Direction.DOWN, 0.8f)
        device.waitForIdle()
        awaitUi { visibleAgendaMarker(prefix)?.let { it.first != first } == true }
        val marker = requireNotNull(visibleAgendaMarker(prefix))
        val request = vm.todayRequest
        onMain { vm.step(2) }
        val away = anchor.plusMonths(2).withDayOfMonth(1)
        awaitUi { vm.selectedDate == away && device.hasObject(day(away)) }
        onMain { vm.goToday() }
        awaitUi { vm.selectedDate == today && vm.todayRequest == request + 1 &&
            visibleAgendaMarker(todayMarker) != null }
        onMain { vm.step(1) }
        val returned = today.plusMonths(1).withDayOfMonth(1)
        val tolerance = 2 * context.resources.displayMetrics.density
        awaitUi { vm.selectedDate == returned && visibleAgendaMarker(prefix)?.let {
            it.first == marker.first && kotlin.math.abs(it.second - marker.second) <= tolerance
        } == true }
        assertEquals("Returning to another month must not request Today again.", request + 1, vm.todayRequest)
    }

    @Test
    fun boundedAgendaPreservesManualScrollWhenSteppingAwayAndBackWithoutATodayRequest() {
        val (anchor, prefix) = prepareBoundedRestoreAgenda()
        val first = requireNotNull(visibleAgendaMarker(prefix)).first
        val list = node(By.pkg(context.packageName).res("calendar_agenda"))
        val bounds = list.visibleBounds
        list.setGestureMargins(bounds.width() / 5, bounds.height() / 6, bounds.width() / 5, bounds.height() / 4)
        list.scroll(Direction.DOWN, 0.8f)
        device.waitForIdle()
        awaitUi { visibleAgendaMarker(prefix)?.let { it.first != first } == true }
        val marker = requireNotNull(visibleAgendaMarker(prefix))
        val request = vm.todayRequest
        onMain { vm.step(2) }
        val away = anchor.plusMonths(2).withDayOfMonth(1)
        awaitUi { vm.selectedDate == away && device.hasObject(day(away)) }
        onMain { vm.step(-2) }
        val tolerance = 2 * context.resources.displayMetrics.density
        awaitUi { vm.selectedDate == LocalDate.now() && visibleAgendaMarker(prefix)?.let {
            it.first == marker.first && kotlin.math.abs(it.second - marker.second) <= tolerance
        } == true }
        assertEquals("Ordinary month navigation must not request Today.", request, vm.todayRequest)
    }

    private fun prepareBoundedRestoreAgenda(monthOffset: Long = 0): Pair<LocalDate, String> {
        val today = LocalDate.now()
        val anchor = today.withDayOfMonth(if (today.dayOfMonth == 1) 15 else 1).plusMonths(monthOffset)
        val prefix = "Bounded restore marker "
        repeat(20) { addTimed(anchor, "$prefix$it", 8, 9) }
        onMain {
            vm.changeViewMode(ViewMode.Month)
            vm.changeAgendaRange("month")
            vm.select(anchor)
            vm.reload()
        }
        val arrow = when {
            java.time.YearMonth.from(anchor) < java.time.YearMonth.from(today) -> "next"
            java.time.YearMonth.from(anchor) > java.time.YearMonth.from(today) -> "previous"
            anchor.isBefore(today) -> "down"
            else -> "up"
        }
        awaitUi { vm.events.count { it.title.startsWith(prefix) } == 20 &&
            visibleAgendaMarker(prefix) != null && device.hasObject(By.pkg(context.packageName).res("calendar_today_$arrow")) }
        return anchor to prefix
    }

    private fun visibleAgendaMarker(prefix: String): Pair<String, Int>? {
        val bounds = device.findObject(By.pkg(context.packageName).res("calendar_agenda"))?.visibleBounds ?: return null
        return device.findObjects(By.pkg(context.packageName).textStartsWith(prefix)).firstOrNull {
            val marker = it.visibleBounds
            marker.height() > 0 && marker.top >= bounds.top && marker.bottom <= bounds.bottom
        }?.let { it.text to (it.visibleBounds.top - bounds.top) }
    }

    @Test
    fun weekListDefaultsAndExplicitTimelinesSurviveNewActivities() {
        finish()
        context.getSharedPreferences("sunlith", Context.MODE_PRIVATE).edit()
            .remove("week_layout").remove("visible_week_days").remove("layout_preset").commit()
        launch()
        onMain { vm.changeViewMode(ViewMode.Week) }
        node(By.pkg(context.packageName).res("calendar_agenda"))
        assertEquals("list", vm.weekLayout)
        assertFalse(device.hasObject(By.pkg(context.packageName).res("calendar_timeline")))
        captureMinor("week-fresh-list")
        listOf("sunlith", "planner").forEach { preset ->
            onMain { vm.applyLayoutPreset(preset) }
            finish()
            launch()
            onMain { vm.changeViewMode(ViewMode.Week) }
            assertEquals(preset, vm.layoutPreset)
            assertEquals("list", vm.weekLayout)
            assertEquals("cards", vm.dayLayout)
            assertEquals(7, vm.visibleWeekDays)
            node(By.pkg(context.packageName).res("calendar_agenda"))
            captureMinor("week-$preset-list")
        }
        listOf(3, 7).forEach { days ->
            onMain { vm.changeWeekLayout("timeline"); vm.changeVisibleWeekDays(days) }
            finish()
            launch()
            onMain { vm.changeViewMode(ViewMode.Week) }
            assertEquals("custom", vm.layoutPreset)
            assertEquals("timeline", vm.weekLayout)
            assertEquals(days, vm.visibleWeekDays)
            node(By.pkg(context.packageName).res("calendar_timeline"))
            captureMinor("week-custom-timeline$days")
        }
    }

    @Test
    fun defaultWorkweekShowsMondayThroughFridayAndRollingCanBeEnabled() {
        val monday = date.with(DayOfWeek.MONDAY)
        val saturday = monday.plusDays(5)
        onMain {
            vm.changeViewMode(ViewMode.Week)
            vm.changeWeekLayout("timeline")
            vm.changeVisibleWeekDays(5)
            vm.setMondayFirst(false)
            vm.changeShowNavigationArrows(true)
            vm.changeRollingFiveDays(false)
            vm.select(saturday)
        }
        fun shownWorkweek(start: LocalDate) {
            awaitUi {
                device.hasObject(By.pkg(context.packageName).res("calendar_timeline_headers")) &&
                    (0L..4L).all { device.hasObject(day(start.plusDays(it))) }
            }
            assertFalse(device.hasObject(day(start.minusDays(1))))
            assertFalse(device.hasObject(day(start.plusDays(5))))
        }
        shownWorkweek(monday)
        node(By.pkg(context.packageName).desc(english.getString(R.string.action_next_week))).click()
        awaitUi { vm.selectedDate == saturday.plusWeeks(1) }
        shownWorkweek(monday.plusWeeks(1))
        node(By.pkg(context.packageName).desc(english.getString(R.string.action_prev_week))).click()
        awaitUi { vm.selectedDate == saturday }
        shownWorkweek(monday)
        onMain { vm.goToday() }
        shownWorkweek(LocalDate.now().with(DayOfWeek.MONDAY))
        awaitUi { !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
        onMain { vm.select(saturday); vm.changeRollingFiveDays(true) }
        awaitUi { (0L..4L).all { device.hasObject(day(saturday.plusDays(it))) } }
        assertFalse(device.hasObject(day(monday)))
    }

    @Test
    fun longPressViewTabsChooseLayoutsWithoutChangingAnythingOnDismiss() {
        val hostDensity = device.displayWidth / 440f
        onMain {
            vm.changeShowNavigationArrows(false)
            vm.changeNavigationAnimation(false)
            vm.changeMonthLayout("split")
            vm.changeWeekLayout("list")
            vm.changeVisibleWeekDays(7)
            vm.changeDayLayout("cards")
            requireNotNull(activity).setContent {
                CompositionLocalProvider(LocalDensity provides Density(hostDensity, 1f)) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { SunlithApp(vm) }
                }
            }
        }
        val dialog = By.pkg(context.packageName).res("calendar_layout_chooser")
        fun tab(mode: ViewMode) = By.pkg(context.packageName).res("calendar_tab_${mode.name.lowercase()}")
        fun option(value: String) = By.pkg(context.packageName).res("layout_choice_$value")
        fun layouts(): List<Any> = listOf(vm.monthLayout, vm.weekLayout, vm.dayLayout, vm.visibleWeekDays,
            vm.rollingFiveDays, vm.weekHeaderSelection, vm.layoutPreset)
        fun storedLayouts(): List<Any> = SettingsStore(context).let {
            listOf(it.monthLayout, it.weekLayout, it.dayLayout, it.visibleWeekDays,
                it.rollingFiveDays, it.weekHeaderSelection, it.layoutPreset)
        }
        fun selectedChoice(mode: ViewMode): String = when (mode) {
            ViewMode.Month -> vm.monthLayout
            ViewMode.Week -> if (vm.weekLayout == "timeline") "timeline${vm.visibleWeekDays}" else vm.weekLayout
            ViewMode.Day -> vm.dayLayout
        }
        fun choiceLabel(value: String): String = english.getString(when (value) {
            "split" -> R.string.layout_preset_agenda
            "full" -> R.string.layout_preset_grid
            "list", "cards" -> R.string.settings_week_list
            "timeline3" -> R.string.settings_week_timeline3
            "timeline5" -> R.string.settings_week_timeline5
            "timeline7" -> R.string.settings_week_timeline7
            "continuous" -> R.string.settings_week_continuous
            else -> R.string.settings_day_all_hours
        })
        fun assertChoices(choices: List<String>, selected: String) {
            node(dialog)
            val rendered = device.findObjects(By.pkg(context.packageName).res(Pattern.compile("layout_choice_.*")))
                .map { requireNotNull(it.resourceName).substringAfterLast('/') }.toSet()
            assertEquals("The dialog must expose only that view's supported layouts.", choices.map { "layout_choice_$it" }.toSet(), rendered)
            val checked = choices.filter { value ->
                val row = node(option(value))
                val selectedRow = row.isSelected || row.isChecked
                try {
                    assertTrue("Every layout must stay enabled; an unselected radio choice must expose its click action: $value selected=$selectedRow clickable=${row.isClickable}",
                        row.isEnabled && (selectedRow || row.isClickable))
                    assertTrue("The whole layout row must be a usable touch target.", row.visibleBounds.height() >= 48 * hostDensity)
                    val shown = (listOfNotNull(row.text) + row.findObjects(By.text(Pattern.compile("(?s).+"))).mapNotNull { it.text })
                        .flatMap { it.split(Regex(",\\s*|\n")) }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    assertEquals("A layout row shows only its own name, with no extra subtitle: $value",
                        setOf(choiceLabel(value)), shown)
                } catch (failure: AssertionError) {
                    runCatching {
                        val folder = requireNotNull(context.getExternalFilesDir(null))
                        device.takeScreenshot(File(folder, "layout-chooser-failure.png"))
                        device.dumpWindowHierarchy(File(folder, "layout-chooser-failure.xml"))
                    }
                    throw failure
                }
                selectedRow
            }
            assertEquals("The chooser must expose exactly its saved choice as selected.", listOf(selected), checked)
        }
        val choicesByMode = listOf(
            ViewMode.Month to listOf("split", "full"),
            ViewMode.Week to listOf("list", "timeline3", "timeline5", "timeline7", "continuous"),
            ViewMode.Day to listOf("cards", "timeline"),
        )
        choicesByMode.forEach { (mode, choices) ->
            val anchor = when (mode) { ViewMode.Month -> ViewMode.Day; ViewMode.Week -> ViewMode.Month; ViewMode.Day -> ViewMode.Week }
            onMain { vm.changeViewMode(anchor); vm.select(date) }
            val before = layouts()
            val stored = storedLayouts()
            val selected = selectedChoice(mode)
            node(tab(mode)).longClick()
            assertChoices(choices, selected)
            assertEquals("Holding an inactive tab must not first run its ordinary click.", anchor, vm.viewMode)
            assertEquals(date, vm.selectedDate)
            assertEquals(before, layouts())
            assertEquals(stored, storedLayouts())
            node(By.pkg(context.packageName).text(english.getString(R.string.action_cancel))).click()
            awaitUi { !device.hasObject(dialog) }
            assertEquals("Cancel must retain the original view.", anchor, vm.viewMode)
            assertEquals(date, vm.selectedDate)
            assertEquals(before, layouts())
            assertEquals(stored, storedLayouts())

            choices.forEach { choice ->
                val previousMode = vm.viewMode
                val previousChoice = selectedChoice(mode)
                val rolling = vm.rollingFiveDays
                node(tab(mode)).longClick()
                assertChoices(choices, previousChoice)
                assertEquals("Long press must wait for a choice before changing the active view.", previousMode, vm.viewMode)
                node(option(choice)).click()
                awaitUi { !device.hasObject(dialog) && vm.viewMode == mode && selectedChoice(mode) == choice }
                val saved = SettingsStore(context)
                when (mode) {
                    ViewMode.Month -> assertEquals(choice, saved.monthLayout)
                    ViewMode.Week -> if (choice.startsWith("timeline")) {
                        assertEquals("timeline", saved.weekLayout)
                        assertEquals(choice.removePrefix("timeline").toInt(), saved.visibleWeekDays)
                    } else assertEquals(choice, saved.weekLayout)
                    ViewMode.Day -> assertEquals(choice, saved.dayLayout)
                }
                assertEquals("Choosing a view layout must preserve the separate rolling-days option.", rolling, vm.rollingFiveDays)
                assertEquals("Changing layout must retain the chosen calendar date.", date, vm.selectedDate)
                assertTrue("The chooser must not open an editor or another screen.", vm.stack.isEmpty())
                assertEquals(null, vm.editor)
            }
            val after = layouts()
            val savedAfter = storedLayouts()
            node(tab(mode)).longClick()
            assertChoices(choices, choices.last())
            device.pressBack()
            awaitUi { !device.hasObject(dialog) }
            assertEquals(mode, vm.viewMode)
            assertEquals(after, layouts())
            assertEquals(savedAfter, storedLayouts())
        }
        val beforeTap = layouts()
        node(tab(ViewMode.Month)).click()
        awaitUi { vm.viewMode == ViewMode.Month }
        assertFalse("An ordinary tab tap must switch views without opening the layout chooser.", device.hasObject(dialog))
        assertEquals("A normal tap must not alter any layout.", beforeTap, layouts())
        assertEquals(date, vm.selectedDate)
    }

    @Test
    fun compactAndLargeTextViewMenusOfferTheSameLongPressLayoutChoices() {
        val dialog = By.pkg(context.packageName).res("calendar_layout_chooser")
        val menu = By.pkg(context.packageName).res("calendar_view_menu")
        fun row(mode: ViewMode) = By.pkg(context.packageName).res("calendar_menu_${mode.name.lowercase()}")
        fun choice(value: String) = By.pkg(context.packageName).res("layout_choice_$value")
        fun savedChoices(): List<Any> = SettingsStore(context).let {
            listOf(it.monthLayout, it.weekLayout, it.dayLayout, it.visibleWeekDays,
                it.rollingFiveDays, it.weekHeaderSelection)
        }
        val modes = listOf(ViewMode.Month, ViewMode.Week, ViewMode.Day)
        listOf(320f to 1f, 440f to 2f).forEach { (widthDp, fontScale) ->
            val hostDensity = device.displayWidth / widthDp
            onMain {
                vm.changeShowNavigationArrows(false)
                vm.changeMonthLayout("split")
                vm.changeWeekLayout("list")
                vm.changeDayLayout("cards")
                vm.changeViewMode(ViewMode.Day)
                vm.select(date)
                requireNotNull(activity).setContent {
                    CompositionLocalProvider(LocalDensity provides Density(hostDensity, fontScale)) {
                        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { SunlithApp(vm) }
                    }
                }
            }
            node(menu)
            assertFalse("This profile must exercise the compact menu, rather than wide tabs.",
                device.hasObject(By.pkg(context.packageName).res("calendar_tab_day")))
            val original = savedChoices()
            node(menu).longClick()
            node(dialog)
            assertTrue("Holding the view control must open the active Day layout's chooser.",
                node(choice("cards")).let { it.isSelected || it.isChecked })
            assertFalse("A long press must not also open the ordinary view popup.", modes.any { device.hasObject(row(it)) })
            assertEquals(ViewMode.Day, vm.viewMode)
            assertEquals(original, savedChoices())
            device.pressBack()
            awaitUi { !device.hasObject(dialog) }

            modes.forEach { mode ->
                val current = vm.viewMode
                val before = savedChoices()
                node(menu).click()
                modes.forEach { node(row(it)) }
                node(row(mode)).longClick()
                node(dialog)
                assertFalse("The ordinary popup must close before its layout chooser opens.", modes.any { device.hasObject(row(it)) })
                assertEquals("Holding an inactive popup row must not switch views first.", current, vm.viewMode)
                assertEquals(date, vm.selectedDate)
                assertEquals(before, savedChoices())
                val expected = when (mode) { ViewMode.Month -> "split"; ViewMode.Week -> "list"; ViewMode.Day -> "cards" }
                assertTrue("The saved layout must be selected in the compact chooser.",
                    node(choice(expected)).let { it.isSelected || it.isChecked })
                node(By.pkg(context.packageName).text(english.getString(R.string.action_cancel))).click()
                awaitUi { !device.hasObject(dialog) }
                assertEquals(current, vm.viewMode)
                assertEquals(before, savedChoices())

                node(menu).click()
                node(row(mode)).longClick()
                node(dialog)
                val selected = when (mode) { ViewMode.Month -> "full"; ViewMode.Week -> "timeline5"; ViewMode.Day -> "timeline" }
                node(choice(selected)).click()
                awaitUi { !device.hasObject(dialog) && vm.viewMode == mode }
                val settings = SettingsStore(context)
                when (mode) {
                    ViewMode.Month -> assertEquals("full", settings.monthLayout)
                    ViewMode.Week -> {
                        assertEquals("timeline", settings.weekLayout)
                        assertEquals(5, settings.visibleWeekDays)
                    }
                    ViewMode.Day -> assertEquals("timeline", settings.dayLayout)
                }
                assertEquals("Selecting a compact layout must preserve the focused date.", date, vm.selectedDate)
                assertTrue(vm.stack.isEmpty())
            }
            val after = savedChoices()
            node(menu).click()
            node(row(ViewMode.Month)).click()
            awaitUi { vm.viewMode == ViewMode.Month && !device.hasObject(row(ViewMode.Month)) }
            assertFalse(device.hasObject(dialog))
            assertEquals("An ordinary compact-menu tap must preserve all saved layouts.", after, savedChoices())
            assertEquals(date, vm.selectedDate)
        }
    }

    @Test
    fun schoolMessageSuggestionsOpenAnUnsavedAllDayEditorAndCanBeDiscarded() {
        val target = date.plusDays(30)
        val suggestion = io.github.sunsind.sunlith.school.SchoolNoticeSuggestion("fixture-deadline", target,
            "Synthetic school registration deadline", "Source: Synthetic administration notice. Submit the school form before the stated date.")
        fun storedIds(): Set<Long> = context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID), "${CalendarContract.Events.CALENDAR_ID}=?", arrayOf(calendarId.toString()), null)!!.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        val before = storedIds()
        val selected = vm.selectedDate
        onMain { vm.addSchoolSuggestion(suggestion) }
        awaitUi { vm.stack.lastOrNull() == Screen.Editor && vm.editor?.title == suggestion.title }
        val editor = requireNotNull(vm.editor)
        assertEquals(null, editor.id)
        assertEquals(calendarId, editor.calendarId)
        assertEquals(suggestion.detail, editor.notes)
        assertTrue(editor.allDay)
        assertEquals(target, editor.startDate)
        assertEquals(target, editor.endDate)
        assertTrue("A date extracted from a notice must not invent reminders.", editor.reminders.isEmpty())
        assertEquals(null, editor.rrule)
        assertFalse(editor.todo)
        val draft = editor.toDraft(ZoneId.systemDefault())
        assertEquals(CalendarDates.allDayMillis(target), draft.startMillis)
        assertEquals(CalendarDates.allDayMillis(target.plusDays(1)), draft.endMillis)
        assertEquals(suggestion.title, draft.title)
        assertEquals(suggestion.detail, draft.description)
        node(By.pkg(context.packageName).text(suggestion.title))
        assertEquals("Preparing a school suggestion must leave the calendar provider untouched.", before, storedIds())
        node(By.pkg(context.packageName).desc(english.getString(R.string.action_close))).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.editor_discard_title)))
        node(By.pkg(context.packageName).text(english.getString(R.string.action_discard))).click()
        awaitUi { vm.stack.isEmpty() && vm.editor == null }
        assertEquals("Discarding a suggestion must not add any event.", before, storedIds())
        assertEquals(selected, vm.selectedDate)
    }

    @Test
    fun settingsDestinationsKeepTheCalendarCoveredAndReturnToSettingsInBothMotionModes() {
        val calendarTags = listOf("calendar_period_title", "calendar_day_cards", "calendar_timeline",
            "calendar_view_menu", "calendar_tab_day", "calendar_agenda")
        fun assertCovered() {
            calendarTags.forEach { tag ->
                assertFalse("A covered calendar must not remain exposed to accessibility: $tag",
                    device.hasObject(By.pkg(context.packageName).res(tag)))
            }
        }
        listOf(false, true).forEach { motion ->
            onMain {
                vm.changeNavigationAnimation(motion)
                vm.changeDayLayout("cards")
                vm.changeViewMode(ViewMode.Day)
                vm.select(date)
                vm.open(Screen.Settings)
            }
            node(By.pkg(context.packageName).text(english.getString(R.string.settings_general)))
            assertCovered()
            listOf(Screen.Calendars to R.string.title_calendars, Screen.DeviceLink to R.string.device_link_title).forEach { (screen, title) ->
                node(By.pkg(context.packageName).text(english.getString(title))).click()
                awaitUi { vm.stack.lastOrNull() == screen }
                val until = SystemClock.uptimeMillis() + 400
                do {
                    assertCovered()
                    assertEquals("Opening a settings destination must retain its Settings parent.", listOf(Screen.Settings, screen), vm.stack.toList())
                    SystemClock.sleep(16)
                } while (SystemClock.uptimeMillis() < until)
                node(By.pkg(context.packageName).text(english.getString(title)))
                assertEquals("Settings navigation must keep the calendar's focused date.", date, vm.selectedDate)
                device.pressBack()
                awaitUi { vm.stack.lastOrNull() == Screen.Settings }
                node(By.pkg(context.packageName).text(english.getString(R.string.settings_general)))
                assertCovered()
            }
            node(By.pkg(context.packageName).text(english.getString(R.string.beta_settings_appearance))).click()
            node(By.pkg(context.packageName).text(english.getString(R.string.settings_layout).uppercase(Locale.ROOT)))
            assertCovered()
            device.pressBack()
            node(By.pkg(context.packageName).text(english.getString(R.string.settings_general)))
            assertEquals(Screen.Settings, vm.stack.lastOrNull())
            assertCovered()
            device.pressBack()
            awaitUi { vm.stack.isEmpty() && device.hasObject(By.pkg(context.packageName).res("calendar_period_title")) }
            assertEquals(ViewMode.Day, vm.viewMode)
            assertEquals(date, vm.selectedDate)
        }
    }

    @Test
    fun weekHeaderDatesRequireTheirOwnOptInAndDoNotDisableDayNavigation() {
        listOf("list", "timeline", "continuous").forEach { layout ->
            onMain {
                vm.changeViewMode(ViewMode.Week)
                vm.changeWeekLayout(layout)
                vm.changeVisibleWeekDays(3)
                vm.changeWeekHeaderSelection(false)
                vm.select(date)
            }
            val target = date.plusDays(1)
            fun header(value: LocalDate): UiObject2? = if (layout == "list") device.findObject(day(value)) else visibleTimelineHeader(value)
            awaitUi { header(date) != null && header(target)?.let { !(it.isClickable && it.isEnabled) } == true }
            val initial = requireNotNull(header(target))
            assertFalse("A Week date must not advertise an enabled click until selection is enabled: $layout",
                initial.isClickable && initial.isEnabled)
            initial.click()
            device.waitForIdle()
            assertEquals("Tapping an ordinary Week header must leave the focused date alone: $layout", date, vm.selectedDate)
            assertTrue(vm.stack.isEmpty())

            onMain { vm.changeWeekHeaderSelection(true) }
            awaitUi { header(target)?.let { it.isClickable && it.isEnabled } == true }
            requireNotNull(header(target)).click()
            awaitUi { vm.selectedDate == target && header(target)?.let { it.isSelected || it.isChecked } == true }
            assertTrue(SettingsStore(context).weekHeaderSelection)
            assertTrue("Opted-in header selection must not open an unrelated screen.", vm.stack.isEmpty())

            onMain { vm.changeWeekHeaderSelection(false) }
            awaitUi(detail = { "Week header must stop exposing an enabled click after disabling selection: $layout" }) {
                header(date)?.let { !(it.isClickable && it.isEnabled) } == true
            }
            val original = requireNotNull(header(date))
            assertFalse("Disabling Week header selection must remove its enabled click: $layout", original.isClickable && original.isEnabled)
            original.click()
            device.waitForIdle()
            assertEquals("Disabling Week header selection must take effect immediately.", target, vm.selectedDate)
        }
        onMain { vm.changeDayLayout("cards"); vm.changeViewMode(ViewMode.Day); vm.select(date) }
        awaitUi {
            device.hasObject(By.pkg(context.packageName).res("calendar_day_cards")) &&
                !device.hasObject(By.pkg(context.packageName).res("calendar_timeline_headers")) &&
                device.findObject(day(date.plusDays(1)))?.let { it.isClickable && it.isEnabled } == true
        }
        val nextDay = node(day(date.plusDays(1)))
        assertTrue("The Week-only option must preserve the Day date strip's normal navigation.", nextDay.isClickable)
        nextDay.click()
        awaitUi { vm.selectedDate == date.plusDays(1) }
        assertFalse(SettingsStore(context).weekHeaderSelection)
    }

    @Test
    fun dayLayoutsOfferDirectionalReturnToTodayOnBothSides() {
        val today = LocalDate.now()
        onMain { vm.changeViewMode(ViewMode.Day) }
        listOf("cards", "timeline").forEach { layout ->
            onMain { vm.changeDayLayout(layout) }
            listOf(-1L to "next", 1L to "previous").forEach { (offset, arrow) ->
                onMain { vm.select(today.plusDays(offset)) }
                val request = vm.todayRequest
                clickCurrent(By.pkg(context.packageName).res("calendar_today_$arrow"))
                awaitUi { vm.selectedDate == today && vm.todayRequest == request + 1 &&
                    !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
            }
        }
    }

    @Test
    fun dayScheduleTodayArrowFollowsRenderedDatesAndReleasesOwnershipOnLayoutChanges() {
        val today = LocalDate.now()
        val markers = (-14L..14L).associate { offset ->
            val day = today.plusDays(offset)
            day to addTimed(day, "Schedule viewport marker $offset", 9, 10)
        }
        fun visibleMarker(day: LocalDate): Boolean {
            val bounds = device.findObject(By.pkg(context.packageName).res("schedule_event_${markers.getValue(day)}"))?.visibleBounds
            return bounds != null && bounds.height() > 24
        }
        fun showSchedule(day: LocalDate) {
            onMain {
                vm.changeViewMode(ViewMode.Day)
                vm.changeAgendaDisplay("events")
                vm.changeDayLayout("schedule")
                vm.select(day)
            }
            node(By.pkg(context.packageName).res("calendar_schedule"))
            awaitUi { !vm.loadingEvents && !vm.pendingDayFocus && visibleMarker(day) }
        }
        val todayAction = By.pkg(context.packageName).desc(english.getString(R.string.action_today))
        showSchedule(today)
        scrollAgendaUntilArrow(Direction.DOWN, "up")
        assertEquals("Scrolling the schedule must keep its selected date.", today, vm.selectedDate)
        assertFalse("Today must have left the rendered schedule viewport.", visibleMarker(today))
        assertTrue("The schedule must have reached a later synthetic event.", markers.keys.filter { it > today }.any(::visibleMarker))
        val request = vm.todayRequest
        clickCurrent(By.pkg(context.packageName).res("calendar_today_up"))
        awaitUi { vm.selectedDate == today && vm.todayRequest == request + 1 && visibleMarker(today) && !device.hasObject(todayAction) }

        val selected = today.plusDays(10)
        listOf("cards", "timeline").forEach { layout ->
            showSchedule(selected)
            repeat(16) {
                if (!visibleMarker(today)) {
                    val list = node(By.pkg(context.packageName).res("calendar_agenda"))
                    val bounds = list.visibleBounds
                    list.setGestureMargins(bounds.width() / 5, bounds.height() / 6, bounds.width() / 5, bounds.height() / 4)
                    list.scroll(Direction.UP, 0.6f)
                    device.waitForIdle()
                }
            }
            awaitUi { visibleMarker(today) && !device.hasObject(todayAction) }
            assertEquals("Seeing Today by scrolling must not rewrite the far selected date.", selected, vm.selectedDate)
            onMain {
                assertFalse("The cached schedule must finish loading before changing its layout.", vm.loadingEvents)
                vm.changeDayLayout(layout)
                assertFalse("A loaded Day must not postpone focus for an unnecessary provider query.", vm.loadingEvents)
            }
            node(By.pkg(context.packageName).res(if (layout == "cards") "calendar_day_cards" else "calendar_timeline"))
            node(By.pkg(context.packageName).res("calendar_today_previous"))
            assertEquals(selected, vm.selectedDate)

            showSchedule(today)
            scrollAgendaUntilArrow(Direction.DOWN, "up")
            onMain { vm.changeDayLayout(layout) }
            node(By.pkg(context.packageName).res(if (layout == "cards") "calendar_day_cards" else "calendar_timeline"))
            awaitUi { vm.selectedDate == today && !device.hasObject(todayAction) }
        }
    }

    @Test
    fun daySwipesShowReturnArrowsAndMonthTodayKeepsItsDirection() {
        val today = LocalDate.now()
        onMain {
            vm.changeViewMode(ViewMode.Day)
            vm.changeDayLayout("cards")
            vm.goToday()
        }
        listOf(true, false).forEach { forward ->
            val bounds = node(By.pkg(context.packageName).res("calendar_day_cards")).visibleBounds
            val left = bounds.left + bounds.width() / 6
            val right = bounds.right - bounds.width() / 6
            val towardLeft = forward != timelineIsRtl()
            assertTrue(device.swipe(if (towardLeft) right else left, bounds.centerY(), if (towardLeft) left else right, bounds.centerY(), 24))
            val expected = today.plusDays(if (forward) 1 else -1)
            awaitUi { vm.selectedDate == expected }
            clickCurrent(By.pkg(context.packageName).res("calendar_today_${if (forward) "previous" else "next"}"))
            awaitUi { vm.selectedDate == today && !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
        }
        onMain {
            vm.changeViewMode(ViewMode.Month)
            vm.changeMonthLayout("split")
            vm.changeNavigationAnimation(true)
            vm.select(today)
        }
        val agenda = node(By.pkg(context.packageName).res("calendar_agenda")).visibleBounds
        val left = agenda.left + agenda.width() / 6
        val right = agenda.right - agenda.width() / 6
        assertTrue(device.swipe(if (timelineIsRtl()) left else right, agenda.centerY(), if (timelineIsRtl()) right else left, agenda.centerY(), 24))
        awaitUi { vm.month == java.time.YearMonth.from(today).plusMonths(1) }
        clickCurrent(By.pkg(context.packageName).res("calendar_today_previous"))
        val until = SystemClock.elapsedRealtime() + 700
        while (SystemClock.elapsedRealtime() < until) {
            refreshAccessibility()
            assertFalse("Returning to this month must not expose an intermediate vertical or opposite arrow.",
                listOf("next", "up", "down").any { device.hasObject(By.pkg(context.packageName).res("calendar_today_$it")) })
            SystemClock.sleep(16)
        }
        awaitUi { vm.selectedDate == today && !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
    }

    @Test
    fun monthLayoutHeadingIsLocalizedAndMonthChoicesPreserveTheCustomWeek() {
        listOf("en" to "MONTH LAYOUT", "fr-CA" to "DISPOSITION DU MOIS").forEach { (language, heading) ->
            if (language != "en" && Build.VERSION.SDK_INT < 33) return@forEach
            finish()
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
            }
            launch(Locale.forLanguageTag(language))
            onMain { vm.changeWeekLayout("timeline"); vm.changeVisibleWeekDays(3) }
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
            node(By.pkg(context.packageName).desc(localized.getString(R.string.title_settings))).click()
            node(By.pkg(context.packageName).text(localized.getString(R.string.beta_settings_appearance))).click()
            node(By.pkg(context.packageName).text(heading))
            listOf(R.string.layout_preset_grid to "full", R.string.layout_preset_agenda to "split").forEach { (label, layout) ->
                node(By.pkg(context.packageName).text(localized.getString(label))).click()
                awaitUi { vm.monthLayout == layout }
                assertEquals("timeline", vm.weekLayout)
                assertEquals(3, vm.visibleWeekDays)
            }
            captureMinor("heading-$language")
        }
    }

    private fun scrollAgendaUntilArrow(direction: Direction, arrow: String) {
        val selector = By.pkg(context.packageName).res("calendar_today_$arrow")
        repeat(16) {
            refreshAccessibility()
            if (device.hasObject(selector)) return
            val list = node(By.pkg(context.packageName).res("calendar_agenda"))
            val bounds = list.visibleBounds
            list.setGestureMargins(bounds.width() / 5, bounds.height() / 6, bounds.width() / 5, bounds.height() / 4)
            list.scroll(direction, 0.8f)
            device.waitForIdle()
        }
        node(selector)
    }

    private fun captureMinor(name: String) {
        val arguments = InstrumentationRegistry.getArguments()
        val timeline = arguments.getString("timelineDisplay") == "true"
        if (arguments.getString("captureMinor") != "true" && !timeline) return
        device.waitForIdle()
        SystemClock.sleep(350)
        val prefix = arguments.getString("minorEvidencePrefix") ?: if (timeline) "timeline-display" else "minor-v0.6.3"
        assertTrue(device.takeScreenshot(File(context.filesDir, "$prefix-$name.png")))
        device.dumpWindowHierarchy(File(context.filesDir, "$prefix-$name.xml"))
    }

    @Test
    fun customLayoutChangesAndPresetResetSurviveStartingANewActivity() {
        onMain {
            vm.applyLayoutPreset("planner")
            vm.changeWeekLayout("timeline")
            vm.changeVisibleWeekDays(3)
            vm.changeDayLayout("schedule")
            vm.changeAgendaRange("week")
            vm.changeShowNavigationArrows(true)
            vm.changeSwipeNavigation(false)
        }
        assertEquals("custom", vm.layoutPreset)
        finish()
        launch()
        assertEquals("custom", vm.layoutPreset)
        assertEquals("full", vm.monthLayout)
        assertEquals("timeline", vm.weekLayout)
        assertEquals("schedule", vm.dayLayout)
        assertEquals(3, vm.visibleWeekDays)
        assertEquals("week", vm.agendaRange)
        assertTrue(vm.showNavigationArrows)
        assertFalse(vm.swipeNavigation)
        onMain { vm.applyLayoutPreset("sunlith") }
        finish()
        launch()
        assertEquals("sunlith", vm.layoutPreset)
        assertEquals("split", vm.monthLayout)
        assertEquals("list", vm.weekLayout)
        assertEquals("cards", vm.dayLayout)
        assertEquals(7, vm.visibleWeekDays)
        assertEquals("week", vm.agendaRange)
        assertTrue(vm.showNavigationArrows)
        assertFalse(vm.swipeNavigation)
        node(By.pkg(context.packageName).res("calendar_day_cards"))
    }

    @Test
    fun materialYouAndNeutralActuallyChangeTheVisibleAccentInLightAndDarkModes() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        listOf("light", "dark").forEach { theme ->
            onMain { vm.setTheme(theme); vm.changeMaterialYou(false) }
            val neutral = if (theme == "dark") DarkPalette.accent.toArgb() else LightPalette.accent.toArgb()
            awaitUi { dominantFabColor() == neutral }
            onMain { vm.changeMaterialYou(true) }
            val dynamic = if (theme == "dark") dynamicDarkColorScheme(context).primary.toArgb() else dynamicLightColorScheme(context).primary.toArgb()
            assertTrue("Wallpaper and neutral accents must be different on this emulator.", dynamic != neutral)
            awaitUi { dominantFabColor() == dynamic }
            onMain { vm.changeMaterialYou(false) }
            awaitUi { dominantFabColor() == neutral }
        }
    }

    @Test
    fun personalEventColorsStayStableWhenProviderColorsChangeInBothThemes() {
        val blue = android.graphics.Color.rgb(35, 80, 200)
        val red = android.graphics.Color.rgb(200, 45, 35)
        assertEquals(1, context.contentResolver.update(localCalendarUri(calendarId, account), ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_COLOR, blue)
        }, null, null))
        assertEquals(1, context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, secondId), ContentValues().apply {
            put(CalendarContract.Events.EVENT_COLOR, red)
        }, null, null))
        onMain { vm.changeDayLayout("cards"); vm.select(date); vm.reload() }
        awaitUi { vm.events.any { it.id == firstId && it.color == blue } && vm.events.any { it.id == secondId && it.color == red } }
        listOf("light", "dark").forEach { theme ->
            onMain { vm.setTheme(theme) }
            val expectedAccent = if (theme == "dark") DarkPalette.accent.toArgb() else LightPalette.accent.toArgb()
            awaitUi { dominantFabColor() == expectedAccent }
            val firstFill = dominantRenderedColor(By.pkg(context.packageName).res("day_event_$firstId"))
            val secondFill = dominantRenderedColor(By.pkg(context.packageName).res("day_event_$secondId"))
            assertTrue("These different titles must remain visibly distinct in $theme.", firstFill != secondFill)
            val changedCalendar = if (theme == "light") android.graphics.Color.rgb(20, 170, 70) else blue
            val changedOverride = if (theme == "light") android.graphics.Color.rgb(230, 190, 30) else red
            assertEquals(1, context.contentResolver.update(localCalendarUri(calendarId, account), ContentValues().apply {
                put(CalendarContract.Calendars.CALENDAR_COLOR, changedCalendar)
            }, null, null))
            assertEquals(1, context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, secondId), ContentValues().apply {
                put(CalendarContract.Events.EVENT_COLOR, changedOverride)
            }, null, null))
            onMain { vm.reload() }
            awaitUi { vm.events.any { it.id == firstId && it.color == changedCalendar } && vm.events.any { it.id == secondId && it.color == changedOverride } }
            assertEquals("Calendar color changes must preserve the first title's displayed color in $theme.",
                firstFill, dominantRenderedColor(By.pkg(context.packageName).res("day_event_$firstId")))
            assertEquals("Provider event overrides must preserve the second title's displayed color in $theme.",
                secondFill, dominantRenderedColor(By.pkg(context.packageName).res("day_event_$secondId")))
            node(By.pkg(context.packageName).res("day_event_$secondId")).click()
            awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == secondId }
            node(By.pkg(context.packageName).textContains(secondTitle))
            device.pressBack()
            node(By.pkg(context.packageName).res("day_event_$firstId"))
        }
    }

    @Test
    fun presetsRenderDifferentMonthsAndListDaysWithTheSameCalendarEvents() {
        onMain { vm.applyLayoutPreset("sunlith"); vm.changeViewMode(ViewMode.Month); vm.select(date) }
        node(By.pkg(context.packageName).res("calendar_grid"))
        node(By.pkg(context.packageName).res("calendar_agenda"))
        onMain { vm.changeViewMode(ViewMode.Day); vm.select(date) }
        assertEquals("cards", vm.dayLayout)
        assertEquals("list", vm.weekLayout)
        node(By.pkg(context.packageName).res("calendar_day_cards"))
        node(By.pkg(context.packageName).res("day_event_$firstId"))

        onMain { vm.changeDayLayout("schedule") }
        val schedule = node(By.pkg(context.packageName).res("calendar_schedule"))
        assertTrue(schedule.visibleBounds.height() > 0)
        node(By.pkg(context.packageName).textContains(firstTitle))
        assertFalse(device.hasObject(By.pkg(context.packageName).res("calendar_timeline")))

        onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Month); vm.select(date) }
        val fullMonth = node(By.pkg(context.packageName).res("calendar_full_month"))
        assertTrue(fullMonth.visibleBounds.height() > 0)
        assertFalse(device.hasObject(By.pkg(context.packageName).res("calendar_agenda")))
        val start = gridStart(vm.month, true)
        val cells = (0L until 42L).map { node(day(start.plusDays(it))).visibleBounds }
        assertEquals("The full month must show six rows.", 6, cells.map { it.top }.distinct().size)
        assertTrue("Every row must have seven dates.", cells.groupBy { it.top }.values.all { it.size == 7 })
        onMain { vm.changeViewMode(ViewMode.Day); vm.select(date) }
        assertEquals("cards", vm.dayLayout)
        assertEquals("list", vm.weekLayout)
        node(By.pkg(context.packageName).res("calendar_day_cards"))
        val event = node(By.pkg(context.packageName).res("day_event_$firstId"))
        assertTrue(event.isEnabled)
        assertFalse(device.hasObject(By.pkg(context.packageName).res("calendar_schedule")))
        assertFalse(device.hasObject(By.pkg(context.packageName).res("calendar_timeline")))
    }

    @Test
    fun gridDateOpensItsLateTimelineEventWithoutScrollingAndBackRestoresTheMonth() {
        val target = date.plusDays(2)
        val late = addTimed(target, "Late evening drill-down marker", 20, 21)
        onMain { vm.applyLayoutPreset("planner"); vm.changeDrilldownLayout("timeline"); vm.changeViewMode(ViewMode.Month); vm.select(date); vm.reload() }
        awaitUi { vm.events.any { it.id == late } }
        val parentMonth = vm.month
        node(day(target)).click()
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target }
        val eventSelector = By.pkg(context.packageName).res("timeline_event_$late")
        awaitUi {
            val timeline = device.findObject(By.pkg(context.packageName).res("calendar_timeline"))?.visibleBounds
            val bounds = device.findObject(eventSelector)?.visibleBounds
            timeline != null && bounds != null && bounds.height() > 20 && bounds.top >= timeline.top && bounds.bottom <= timeline.bottom
        }
        assertTrue("Opening a date must bring its 20:00 event into the hourly viewport without a swipe.", node(eventSelector).visibleBounds.height() > 20)
        assertFalse("A date opened from Month must show only that day, without the seven-date strip.",
            device.hasObject(By.pkg(context.packageName).res("calendar_day_picker")))
        listOf("calendar_today_previous", "calendar_today_next").forEach { tag ->
            assertFalse("Month drilldown must not reserve a bottom return-to-Today button: $tag",
                device.hasObject(By.pkg(context.packageName).res(tag)))
        }
        device.pressBack()
        awaitUi { vm.viewMode == ViewMode.Month && vm.month == parentMonth && vm.selectedDate == date }
        node(By.pkg(context.packageName).res("calendar_full_month"))
        node(day(target)).click()
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target }
        awaitUi { device.findObject(eventSelector)?.visibleBounds?.height()?.let { it > 20 } == true }
        assertTrue("Opening the same date again must renew the event focus request.", node(eventSelector).visibleBounds.height() > 20)
        device.pressBack()
        awaitUi { vm.viewMode == ViewMode.Month && vm.selectedDate == date }
    }

    @Test
    fun monthGridDayShowsOnlyItsEventsAndItsToolbarSwitchesBetweenListAndHours() {
        val target = date.plusDays(2)
        val ownTitle = "Grid day selected event"
        val otherTitle = "Grid day neighbouring event"
        val ownId = addTimed(target, ownTitle, 11, 12)
        val otherId = addTimed(target.plusDays(1), otherTitle, 11, 12)
        onMain {
            vm.applyLayoutPreset("planner")
            vm.changeDayLayout("schedule")
            vm.changeDrilldownLayout("cards")
            vm.changeViewMode(ViewMode.Month)
            vm.select(date)
            vm.reload()
        }
        awaitUi { vm.events.any { it.id == ownId } && vm.events.any { it.id == otherId } }
        node(day(target)).click()
        awaitUi { vm.hasDayParent && vm.selectedDate == target && vm.viewMode == ViewMode.Day }
        fun assertOnlySelectedDay() {
            node(By.pkg(context.packageName).res("calendar_day_drilldown"))
            assertFalse("A Month date opens its own events without a seven-day selector.",
                device.hasObject(By.pkg(context.packageName).res("calendar_day_picker")))
            assertFalse("The neighbouring day's events do not belong in this date's panel.",
                device.hasObject(By.pkg(context.packageName).textContains(otherTitle)))
            listOf("calendar_today_previous", "calendar_today_next", "calendar_today_up", "calendar_today_down").forEach { tag ->
                assertFalse("The focused Month date does not need a floating Today arrow: $tag",
                    device.hasObject(By.pkg(context.packageName).res(tag)))
            }
            assertEquals(target, vm.selectedDate)
            assertEquals("The focused date must preserve the standalone Day layout.", "schedule", vm.dayLayout)
        }
        node(By.pkg(context.packageName).res("day_event_$ownId"))
        assertOnlySelectedDay()
        val toggle = By.pkg(context.packageName).res("calendar_day_layout_toggle")
        val switch = node(toggle)
        assertTrue("The list/hour switch must advertise a direct action.", switch.isClickable && switch.isEnabled)
        assertTrue("The icon must have a spoken action name.", !switch.contentDescription.isNullOrBlank())
        val left = switch.visibleBounds
        val right = node(By.pkg(context.packageName).res("calendar_header_actions")).visibleBounds
        assertTrue("The list/hour switch sits directly before More, without overlapping it: $left / $right",
            left.right <= right.left && right.left - left.right <= 12 * context.resources.displayMetrics.density)
        assertEquals(english.getString(R.string.settings_day_all_hours), switch.contentDescription)
        switch.click()
        node(By.pkg(context.packageName).res("calendar_timeline"))
        revealTimelineEvent(ownId)
        assertOnlySelectedDay()
        assertEquals("The action label must describe switching back to the list.",
            english.getString(R.string.settings_week_list), node(toggle).contentDescription)
        node(toggle).click()
        node(By.pkg(context.packageName).res("calendar_day_cards"))
        node(By.pkg(context.packageName).res("day_event_$ownId"))
        assertOnlySelectedDay()
        captureFinal("month-grid-focused-day-list")
        device.pressBack()
        awaitUi { vm.viewMode == ViewMode.Month && !vm.hasDayParent && vm.selectedDate == date }
        assertEquals("schedule", vm.dayLayout)
        assertFalse("The focused-day switch must leave with the focused-day panel.", device.hasObject(toggle))
        node(By.pkg(context.packageName).res("calendar_full_month"))
        onMain { vm.changeViewMode(ViewMode.Day); vm.select(target) }
        node(By.pkg(context.packageName).res("calendar_day_picker"))
        node(By.pkg(context.packageName).res("calendar_schedule"))
        assertEquals("schedule", vm.dayLayout)
        assertFalse("Opening a Month date must not remove the standalone Day view's date selector.", vm.hasDayParent)
    }

    @Test
    fun legacyPresetTimelineRestoresListAndLaterChoicesSurviveRecreation() {
        val target = date.plusDays(2)
        val late = addTimed(target, "Upgraded grid afternoon event", 16, 18)
        finish()
        context.getSharedPreferences("sunlith", Context.MODE_PRIVATE).edit()
            .remove("list_defaults_v2").putString("day_layout", "timeline")
            .putString("month_layout", "full").putString("week_layout", "list")
            .putString("layout_preset", "planner").putString("start_view", "Month").commit()
        launch()
        assertEquals("cards", vm.dayLayout)
        assertEquals("list", vm.weekLayout)
        assertEquals("full", vm.monthLayout)
        assertEquals("A fresh Month drilldown uses All hours independently of the migrated Day preference.", "timeline", vm.drilldownLayout)
        awaitUi { vm.events.any { it.id == late } }
        node(day(target)).click()
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target }
        node(By.pkg(context.packageName).res("calendar_timeline"))
        revealTimelineEvent(late)
        assertEquals("cards", vm.dayLayout)
        device.pressBack()
        awaitUi { vm.viewMode == ViewMode.Month && vm.selectedDate == date }
        onMain { vm.changeDayLayout("schedule"); vm.changeDrilldownLayout("cards"); vm.changeMonthLayout("split"); vm.changeMonthLayout("full") }
        recreateActivity()
        assertEquals("schedule", vm.dayLayout)
        assertEquals("cards", vm.drilldownLayout)
        assertEquals("list", vm.weekLayout)
        node(day(target)).click()
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target }
        node(By.pkg(context.packageName).res("calendar_day_cards"))
        node(By.pkg(context.packageName).res("day_event_$late"))
        recreateActivity()
        assertEquals("schedule", vm.dayLayout)
        assertEquals("cards", vm.drilldownLayout)
        node(By.pkg(context.packageName).res("calendar_day_cards"))
        device.pressBack()
        awaitUi { vm.viewMode == ViewMode.Month && vm.selectedDate == date }
    }

    @Test
    fun emptySingleAndCrowdedGridDatesOpenDayDirectlyIncludingTheFormerOverflowArea() {
        val empty = date.plusDays(2)
        val single = date.plusDays(3)
        val crowded = date.plusDays(4)
        addTimed(single, "Single direct-day event", 16, 18)
        val crowdedIds = (0 until 12).map { addTimed(crowded, "Crowded direct-day event $it", 9, 10) }
        onMain { vm.applyLayoutPreset("planner"); vm.changeDrilldownLayout("timeline"); vm.changeViewMode(ViewMode.Month); vm.select(date); vm.reload() }
        awaitUi { crowdedIds.all { id -> vm.events.any { it.id == id } } }
        listOf(empty, single, crowded).forEach { target ->
            val cell = node(day(target))
            assertTrue("Every date must have an accessible click action.", cell.isClickable)
            captureFinal("grid-${target}")
            val bounds = cell.visibleBounds
            device.click(bounds.centerX(), bounds.bottom - (10 * context.resources.displayMetrics.density).toInt())
            awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target && vm.hasDayParent }
            node(By.pkg(context.packageName).res("calendar_timeline"))
            captureFinal("direct-day-${target}")
            assertFalse("A date must open Day without a preview action.", device.hasObject(By.pkg(context.packageName).text("Open day")))
            if (target == crowded) assertTrue("Events hidden by the bounded month cell must remain in Day.",
                vm.eventsByDay[target].orEmpty().map { it.id }.containsAll(crowdedIds))
            if (target == empty) {
                fun assertMoves(direction: Direction, message: String) {
                    val before = timelineRailPixels()
                    val reported = timelineScroller().scroll(direction, 0.8f)
                    device.waitForIdle()
                    SystemClock.sleep(400)
                    val changed = before != timelineRailPixels()
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "EMPTY_TIMELINE_SCROLL direction=$direction reported=$reported renderedRailChanged=$changed\n")
                    })
                    assertTrue(message, changed)
                }
                assertMoves(Direction.DOWN, "Empty hours must remain scrollable.")
                assertMoves(Direction.DOWN, "Empty hours must continue beyond one viewport.")
                assertMoves(Direction.UP, "Earlier empty hours must remain reachable.")
                if (InstrumentationRegistry.getArguments().getString("captureFinal") == "true") {
                    repeat(8) { timelineScroller().scroll(Direction.UP, 0.95f) }
                    captureFinal("axis-start")
                    repeat(8) { timelineScroller().scroll(Direction.DOWN, 0.95f) }
                    captureFinal("axis-end")
                }
            }
            device.pressBack()
            awaitUi { vm.viewMode == ViewMode.Month && vm.selectedDate == date && !vm.hasDayParent }
        }
    }

    @Test
    fun simultaneousCardsShareOneGutterButKeepTheirOwnDurationAndTapTarget() {
        val target = date.plusDays(2)
        val first = addTimed(target, "Shared-start first card", 8, 9)
        val second = addTimed(target, "Shared-start second card", 8, 10)
        val later = addTimed(target, "Separate-start card", 12, 13)
        onMain { vm.changeDayLayout("cards"); vm.changeViewMode(ViewMode.Day); vm.select(target); vm.reload() }
        awaitUi { vm.events.any { it.id == later } }
        node(By.pkg(context.packageName).res("day_time_$first"))
        assertFalse("The second simultaneous card must share the first gutter label.", device.hasObject(By.pkg(context.packageName).res("day_time_$second")))
        node(By.pkg(context.packageName).res("day_time_$later"))
        val firstBounds = node(By.pkg(context.packageName).res("day_event_$first")).visibleBounds
        val secondBounds = node(By.pkg(context.packageName).res("day_event_$second")).visibleBounds
        assertEquals("Grouped cards must keep the same left alignment.", firstBounds.left, secondBounds.left)
        assertEquals(firstBounds.right, secondBounds.right)
        assertTrue("Simultaneous cards must be stacked without covering each other.", firstBounds.bottom <= secondBounds.top)
        captureFinal("same-start-cards")
        listOf(first to 9, second to 10).forEach { (id, endHour) ->
            val card = node(By.pkg(context.packageName).res("day_event_$id"))
            val end = android.text.format.DateFormat.getTimeFormat(english).format(java.util.Date(
                target.atTime(endHour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            assertTrue("Each accessible card must keep its own full end time.",
                card.text.orEmpty().contains(end) || card.findObject(By.textContains(end)) != null)
            card.click()
            awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == id }
            device.pressBack()
            awaitUi { vm.stack.isEmpty() }
        }
        recreateActivity()
        node(By.pkg(context.packageName).res("day_time_$first"))
        node(By.pkg(context.packageName).res("day_event_$second"))
        assertFalse(device.hasObject(By.pkg(context.packageName).res("day_time_$second")))
    }

    @Test
    fun equalFormattedClockTimesAtDifferentInstantsKeepSeparateGutterLabels() {
        val target = date.plusDays(2)
        val zone = ZoneId.systemDefault()
        val ids = listOf(10, 40).map { seconds ->
            repository.saveEvent(EventDraft(calendarId = calendarId, title = "Distinct instant $seconds",
                startMillis = target.atTime(8, 0, seconds).atZone(zone).toInstant().toEpochMilli(),
                endMillis = target.atTime(9, 0, seconds).atZone(zone).toInstant().toEpochMilli(),
                timeZone = zone.id, reminderMinutes = emptyList()))
        }
        onMain { vm.changeDayLayout("cards"); vm.changeViewMode(ViewMode.Day); vm.select(target); vm.reload() }
        awaitUi { ids.all { id -> vm.events.any { it.id == id } } }
        captureFinal("distinct-instant-cards")
        val labels = ids.map { node(By.pkg(context.packageName).res("day_time_$it")).text }
        assertEquals("Matching displayed times must not merge different start instants, including a repeated DST hour.", labels.first(), labels.last())
    }

    @Test
    fun allDayOnlyAndEmptyCardsKeepTheirDateHeadingAtTheTop() {
        val allDayDate = date.plusDays(2)
        repeat(12) { i ->
            repository.saveEvent(EventDraft(calendarId = calendarId, title = "Top-only all-day entry $i",
                startMillis = CalendarDates.allDayMillis(allDayDate), endMillis = CalendarDates.allDayMillis(allDayDate.plusDays(1)),
                allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
        }
        onMain { vm.changeMonthLayout("full"); vm.changeDrilldownLayout("cards"); vm.reload() }
        awaitUi { vm.events.count { it.title.startsWith("Top-only all-day entry") } == 12 }
        listOf(false, true).forEach { motion ->
            listOf(allDayDate, allDayDate.plusDays(1)).forEach { target ->
                onMain {
                    vm.changeNavigationAnimation(motion)
                    vm.changeViewMode(ViewMode.Month)
                    vm.select(target)
                }
                val cell = node(By.pkg(context.packageName).res("calendar_full_month")).findObject(day(target)).visibleBounds
                device.click(cell.centerX(), cell.top + (14 * context.resources.displayMetrics.density).toInt())
                awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target }
                node(By.pkg(context.packageName).res("calendar_day_cards"))
                SystemClock.sleep(1500)
                refreshAccessibility()
                device.takeScreenshot(File(context.filesDir, "day-cards-top-${target == allDayDate}-$motion.png"))
                val cards = node(By.pkg(context.packageName).res("calendar_day_cards")).visibleBounds
                val heading = node(By.pkg(context.packageName).text(Formats(english, Locale.ENGLISH).dayLong(target))).visibleBounds
                assertTrue("A Day with no timed events must keep its date heading fully visible (motion=$motion).",
                    heading.height() > 0 && heading.top >= cards.top && heading.bottom <= cards.bottom)
                if (target == allDayDate) node(By.pkg(context.packageName).textContains("Top-only all-day entry 0"))
                else node(By.pkg(context.packageName).text(english.getString(R.string.no_events)))
                device.pressBack()
                awaitUi { vm.viewMode == ViewMode.Month }
            }
        }
    }

    @Test
    fun openingADateStartsAtTheFirstEventWithoutTravellingThroughOtherEvents() {
        val target = date.plusDays(3)
        repeat(16) { i ->
            repository.saveEvent(EventDraft(calendarId = calendarId, title = "Motion all-day entry $i",
                startMillis = CalendarDates.allDayMillis(target), endMillis = CalendarDates.allDayMillis(target.plusDays(1)),
                allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
        }
        val late = addTimed(target, "Motion first timed event", 20, 21)
        val simultaneous = addTimed(target, "Motion simultaneous timed event", 20, 22)
        val capture = InstrumentationRegistry.getArguments().getString("captureMotion") == "true"
        listOf("cards", "timeline").forEach { layout ->
            onMain {
                vm.applyLayoutPreset("planner")
                vm.changeDrilldownLayout(layout)
                vm.changeNavigationAnimation(true)
                vm.changeViewMode(ViewMode.Month)
                vm.select(target)
                vm.reload()
            }
            awaitUi { vm.events.any { it.id == late } && vm.coversRange(target, target) }
            val cell = node(day(target)).visibleBounds
            val opened = SystemClock.elapsedRealtime()
            device.click(cell.centerX(), cell.top + (14 * context.resources.displayMetrics.density).toInt())
            if (capture) {
                listOf(0L, 90L, 180L, 300L, 480L, 720L, 1050L, 1450L, 1900L, 2400L).forEachIndexed { index, ms ->
                    val wait = opened + ms - SystemClock.elapsedRealtime()
                    if (wait > 0) SystemClock.sleep(wait)
                    val elapsed = SystemClock.elapsedRealtime() - opened
                    device.takeScreenshot(File(context.filesDir, "day-motion-$layout-${index.toString().padStart(2, '0')}.png"))
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "DAY_MOTION_FRAME layout=$layout index=$index elapsedMs=$elapsed\n")
                    })
                }
            }
            val event = By.pkg(context.packageName).res(when (layout) {
                "timeline" -> "timeline_event_$late"
                else -> "day_event_$late"
            })
            awaitUi { device.findObject(event)?.visibleBounds?.let { it.height() > 30 && it.top >= 0 && it.bottom <= device.displayHeight } == true }
            assertTrue(vm.hasDayParent)
            assertFalse(device.hasObject(By.pkg(context.packageName).res("calendar_view_menu")))
            if (layout == "cards") {
                recreateActivity()
                assertTrue("Recreation must retain the first focused card after many banners.", node(event).visibleBounds.height() > 30)
                node(By.pkg(context.packageName).res("day_event_$simultaneous"))
                assertFalse("The adjoining simultaneous card must keep sharing its time label after recreation.",
                    device.hasObject(By.pkg(context.packageName).res("day_time_$simultaneous")))
            }
            device.pressBack()
            awaitUi { vm.viewMode == ViewMode.Month }
        }
        onMain {
            vm.changeDayLayout("schedule")
            vm.changeViewMode(ViewMode.Day)
            vm.selectAndFocus(target)
        }
        node(By.pkg(context.packageName).res("calendar_day_picker"))
        val scheduleEvent = By.pkg(context.packageName).res("schedule_event_$late")
        awaitUi { device.findObject(scheduleEvent)?.visibleBounds?.let { it.height() > 30 && it.top >= 0 && it.bottom <= device.displayHeight } == true }
        assertFalse("Standalone Schedule must remain separate from the Month date panel.", vm.hasDayParent)
        assertEquals("schedule", vm.activeDayLayout)
    }

    @Test
    fun dayFocusIsConsumedAcrossRecreationAndSwipesKeepTheChosenHour() {
        val target = date.plusDays(2)
        val late = addTimed(target, "Focused evening event", 20, 21)
        val next = addTimed(target.plusDays(1), "Next evening event", 20, 21)
        onMain { vm.applyLayoutPreset("planner"); vm.changeDayLayout("timeline"); vm.openDay(target); vm.reload() }
        awaitUi { vm.events.any { it.id == late } }
        fun visible(id: Long): Boolean {
            // The focus animation replaces event nodes every frame, so wait briefly for a stable read.
            repeat(30) {
                try {
                    return device.findObject(By.pkg(context.packageName).res("timeline_event_$id"))
                        ?.visibleBounds?.height()?.let { it > 20 } == true
                } catch (_: StaleObjectException) {
                    SystemClock.sleep(100)
                }
            }
            error("Timeline event $id stayed stale")
        }
        awaitUi { visible(late) }
        // One scroll gesture covers less than a viewport, so taller Day hours can leave the event at the edge.
        repeat(3) {
            if (visible(late)) {
                timelineScroller().scroll(Direction.UP, 1f, 500)
                device.waitForIdle()
            }
        }
        awaitUi { !visible(late) }
        recreateActivity()
        node(By.pkg(context.packageName).res("calendar_timeline"))
        assertFalse("Rotation must preserve the manually chosen hour after focus is consumed.", visible(late))
        val bounds = timelineScroller().visibleBounds
        device.swipe(bounds.right - bounds.width() / 6, bounds.centerY(), bounds.left + bounds.width() / 6, bounds.centerY(), 24)
        awaitUi { vm.selectedDate == target.plusDays(1) }
        device.waitForIdle()
        assertFalse("Swiping must retain the chosen hour rather than refocusing the next event.", visible(next))
    }

    @Test
    fun customCardsAndScheduleOpenAtTheTimedEventAfterManyAllDayEntries() {
        val target = date.plusDays(2)
        repeat(12) { i ->
            repository.saveEvent(EventDraft(calendarId = calendarId, title = "Earlier all-day entry $i",
                startMillis = CalendarDates.allDayMillis(target), endMillis = CalendarDates.allDayMillis(target.plusDays(1)),
                allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
        }
        val late = addTimed(target, "Timed focus after banners", 20, 21)
        val monthStart = date.plusMonths(1).withDayOfMonth(1)
        repeat(12) { i ->
            repository.saveEvent(EventDraft(calendarId = calendarId, title = "Month-start all-day entry $i",
                startMillis = CalendarDates.allDayMillis(monthStart), endMillis = CalendarDates.allDayMillis(monthStart.plusDays(1)),
                allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
        }
        val monthStartEvent = addTimed(monthStart, "Month-start timed focus", 20, 21)
        listOf("cards", "schedule").forEach { layout ->
            onMain {
                vm.applyLayoutPreset("planner")
                vm.changeDayLayout(layout)
                vm.changeDrilldownLayout("cards")
                vm.changeViewMode(if (layout == "cards") ViewMode.Month else ViewMode.Day)
                vm.selectAndFocus(if (layout == "cards") date else target)
                vm.reload()
            }
            awaitUi { vm.events.any { it.id == late } }
            if (layout == "cards") {
                val dateCell = node(day(target)).visibleBounds
                device.click(dateCell.centerX(), dateCell.top + (14 * context.resources.displayMetrics.density).toInt())
            } else {
                node(By.pkg(context.packageName).res("calendar_day_picker"))
                assertFalse("Schedule is a standalone Day layout, not a Month drilldown layout.", vm.hasDayParent)
            }
            awaitUi { vm.viewMode == ViewMode.Day }
            val selector = By.pkg(context.packageName).res(if (layout == "cards") "day_event_$late" else "schedule_event_$late")
            awaitUi { device.findObject(selector)?.visibleBounds?.height()?.let { it > 30 } == true }
            node(selector).click()
            awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == late }
            device.pressBack()
            if (layout == "schedule") {
                onMain { vm.selectAndFocus(monthStart) }
                awaitUi { device.findObject(By.pkg(context.packageName).res("schedule_event_$monthStartEvent"))
                    ?.visibleBounds?.height()?.let { it > 30 } == true }
                device.waitForIdle()
                assertTrue("Date navigation must keep the timed event visible after the focus request is consumed.",
                    node(By.pkg(context.packageName).res("schedule_event_$monthStartEvent")).visibleBounds.height() > 30)
            }
            if (layout == "cards") device.pressBack() else onMain { vm.changeViewMode(ViewMode.Month) }
            awaitUi { vm.viewMode == ViewMode.Month }
        }
    }

    @Test
    fun scheduleWaitsForDistantDatesAndEmptyDatesAdvanceToTheNextEvent() {
        val distant = date.plusMonths(8).withDayOfMonth(15)
        repeat(18) { i ->
            repository.saveEvent(EventDraft(calendarId = calendarId, title = "Distant banner $i",
                startMillis = CalendarDates.allDayMillis(distant), endMillis = CalendarDates.allDayMillis(distant.plusDays(1)),
                allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
        }
        val event = addTimed(distant, "Distant focused event", 20, 21)
        val next = addTimed(distant.plusDays(2), "Next event after an empty date", 10, 11)
        onMain {
            vm.applyLayoutPreset("planner")
            vm.changeDayLayout("schedule")
            vm.changeAgendaDisplay("events")
            vm.changeViewMode(ViewMode.Day)
            vm.select(date)
            vm.reload()
        }
        awaitUi { vm.coversRange(date, date) }
        assertFalse("The regression must request a date outside the current loaded window.", vm.coversRange(distant, distant))
        onMain { vm.selectAndFocus(distant) }
        awaitUi { vm.coversRange(distant, distant) && vm.events.any { it.id == event } }
        fun visibleScheduleEvent(selector: BySelector): Boolean =
            !vm.loadingEvents && !vm.pendingDayFocus &&
                device.findObject(selector)?.visibleBounds?.height()?.let { it > 30 } == true
        val eventSelector = By.pkg(context.packageName).res("schedule_event_$event")
        awaitUi { visibleScheduleEvent(eventSelector) }
        onMain { vm.selectAndFocus(distant.plusDays(1)) }
        val nextSelector = By.pkg(context.packageName).res("schedule_event_$next")
        awaitUi { visibleScheduleEvent(nextSelector) }
        assertEquals(distant.plusDays(1), vm.selectedDate)
        assertTrue("Programmatic event positioning must not page the agenda repeatedly.", !requireNotNull(vm.loadedTo).isAfter(distant.plusDays(120)))
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun scheduleEntryAnchorsItsUncomposedEventAfterDelayedProviderRowsArrive() {
        val selected = date.plusDays(5)
        onMain { vm.select(selected); vm.reload() }
        awaitUi { !vm.loadingEvents && vm.coversRange(selected, selected) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolver = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
                if (uri.pathSegments.firstOrNull() == "instances") {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
                return this@CalendarCustomizationTest.context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            }
            override fun getType(uri: Uri) = this@CalendarCustomizationTest.context.contentResolver.getType(uri)
            override fun insert(uri: Uri, values: ContentValues?) = this@CalendarCustomizationTest.context.contentResolver.insert(uri, values)
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = this@CalendarCustomizationTest.context.contentResolver.delete(uri, selection, selectionArgs)
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = this@CalendarCustomizationTest.context.contentResolver.update(uri, values, selection, selectionArgs)
        }.apply {
            attachInfo(this@CalendarCustomizationTest.context, android.content.pm.ProviderInfo().apply {
                authority = "com.android.calendar"
                exported = false
            })
        })
        val delayedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
        }
        val repositoryField = SunlithViewModel::class.java.getDeclaredField("repo").apply { isAccessible = true }
        val originalRepository = repositoryField.get(vm)
        repositoryField.set(vm, CalendarRepository(delayedContext))
        try {
            val earlier = (-20L..-1L).map { addTimed(selected.plusDays(it), "Delayed schedule preceding marker $it", 9, 10) }
            val target = addTimed(selected, "Delayed schedule selected marker", 9, 10)
            onMain {
                vm.changeAgendaDisplay("events")
                vm.changeDayLayout("schedule")
            }
            assertTrue("Schedule entry must wait for the held provider results.", entered.await(5, TimeUnit.SECONDS))
            assertTrue(vm.loadingEvents)
            assertTrue(vm.pendingDayFocus)
            assertFalse("The selected marker must not be in the old provider snapshot.", vm.events.any { it.id == target })
            release.countDown()
            val selector = By.pkg(context.packageName).res("schedule_event_$target")
            awaitUi {
                !vm.loadingEvents && !vm.pendingDayFocus && earlier.all { id -> vm.events.any { it.id == id } } &&
                    device.findObject(selector)?.visibleBounds?.height()?.let { it > 30 } == true
            }
            assertEquals(selected, vm.selectedDate)
            SystemClock.sleep(150)
            assertTrue("The requested event must remain visible after focus completion.",
                device.findObject(selector)?.visibleBounds?.height()?.let { it > 30 } == true)
        } finally {
            release.countDown()
            repositoryField.set(vm, originalRepository)
        }
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun distantScheduleAnchorsEveryDisplayBeforeFocusingInBothDirectionsAndAfterRecreation() {
        val forward = date.plusMonths(9).withDayOfMonth(15)
        val backward = date.minusMonths(9).withDayOfMonth(15)
        val targets = listOf(forward, backward)
        val events = targets.associateWith { target ->
            repeat(18) { i ->
                repository.saveEvent(EventDraft(calendarId = calendarId, title = "Distant context $target $i",
                    startMillis = CalendarDates.allDayMillis(target), endMillis = CalendarDates.allDayMillis(target.plusDays(1)),
                    allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
            }
            addTimed(target, "Distant every display $target", 20, 21)
        }
        val queryGate = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>(null)
        val releases = mutableListOf<CountDownLatch>()
        val resolver = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
                if (uri.pathSegments.firstOrNull() == "instances") queryGate.get()?.let { (entered, release) ->
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
                return this@CalendarCustomizationTest.context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            }
            override fun getType(uri: Uri) = this@CalendarCustomizationTest.context.contentResolver.getType(uri)
            override fun insert(uri: Uri, values: ContentValues?) = this@CalendarCustomizationTest.context.contentResolver.insert(uri, values)
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = this@CalendarCustomizationTest.context.contentResolver.delete(uri, selection, selectionArgs)
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = this@CalendarCustomizationTest.context.contentResolver.update(uri, values, selection, selectionArgs)
        }.apply {
            attachInfo(this@CalendarCustomizationTest.context, android.content.pm.ProviderInfo().apply {
                authority = "com.android.calendar"
                exported = false
            })
        })
        val delayedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
        }
        val repositoryField = SunlithViewModel::class.java.getDeclaredField("repo").apply { isAccessible = true }
        val originalRepository = repositoryField.get(vm)
        repositoryField.set(vm, CalendarRepository(delayedContext))
        try {
            listOf("all", "compact", "events").forEach { display ->
                onMain {
                    vm.changeDayLayout("schedule")
                    vm.changeAgendaDisplay(display)
                    vm.changeViewMode(ViewMode.Day)
                    vm.select(date)
                    vm.reload()
                }
                awaitUi { !vm.loadingEvents && vm.coversRange(date, date) }
                targets.forEachIndexed { index, target ->
                    assertFalse("Each direction must require a new provider window.", vm.coversRange(target, target))
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    releases += release
                    queryGate.set(entered to release)
                    onMain { vm.selectAndFocus(target) }
                    assertTrue("The real provider query must be held before results are delivered.", entered.await(5, TimeUnit.SECONDS))
                    assertTrue(vm.loadingEvents)
                    device.waitForIdle()
                    SystemClock.sleep(200)
                    if (index == 1) {
                        val previous = requireNotNull(activity)
                        instrumentation.runOnMainSync { previous.recreate() }
                        awaitUi {
                            var restored = false
                            instrumentation.runOnMainSync {
                                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                    .filterIsInstance<MainActivity>().firstOrNull { it !== previous }?.let {
                                        activity = it
                                        vm = ViewModelProvider(it)[SunlithViewModel::class.java]
                                        restored = true
                                    }
                            }
                            restored
                        }
                    }
                    assertTrue("Recreation must still wait for the held query.", vm.loadingEvents)
                    queryGate.set(null)
                    release.countDown()
                    val selector = By.pkg(context.packageName).res("schedule_event_${events.getValue(target)}")
                    awaitUi { !vm.loadingEvents && vm.coversRange(target, target) && device.findObject(selector)?.visibleBounds?.height()?.let { it > 30 } == true }
                    assertEquals(target, vm.selectedDate)
                    awaitUi { !vm.pendingDayFocus }
                    assertFalse("A completed focus must not start loading arbitrary extra windows.", vm.extendingAgenda)
                    SystemClock.sleep(150)
                    assertTrue("The event must remain visible after focus completion for $display.", device.findObject(selector)?.visibleBounds?.height()?.let { it > 30 } == true)
                }
            }
        } finally {
            releases.forEach { it.countDown() }
            queryGate.set(null)
            repositoryField.set(vm, originalRepository)
        }
    }

    @Test
    fun toolbarKeepsEnabledArrowsAndAReadableDateWithDayBackNavigation() {
        onMain { vm.applyLayoutPreset("planner"); vm.changeShowNavigationArrows(true); vm.changeViewMode(ViewMode.Month); vm.openDay(date) }
        val languages = if (Build.VERSION.SDK_INT >= 33) listOf("en", "fr-CA", "ar") else listOf("en")
        languages.forEach { language ->
            if (Build.VERSION.SDK_INT >= 33 && language != "en") {
                val previous = requireNotNull(activity)
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
                awaitUi {
                    var restored = false
                    instrumentation.runOnMainSync {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().firstOrNull { it !== previous }?.let { current ->
                                activity = current
                                vm = ViewModelProvider(current)[SunlithViewModel::class.java]
                                restored = true
                            }
                    }
                    restored
                }
            }
            val locale = Locale.forLanguageTag(language)
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
            val labels = listOf(R.string.action_back, R.string.action_prev_day, R.string.action_next_day)
            val more = By.pkg(context.packageName).res("calendar_header_actions")
            val directSettings = By.pkg(context.packageName).desc(localized.getString(R.string.title_settings))
            awaitUi {
                device.hasObject(By.pkg(context.packageName).res("calendar_period_title")) &&
                    (device.hasObject(more) || device.hasObject(directSettings))
            }
            val compactActions = device.hasObject(more)
            val settingsAction = if (compactActions) more else directSettings
            val controls = labels.map { By.pkg(context.packageName).desc(localized.getString(it)) } + settingsAction
            val bounds = controls.map { selector ->
                generateSequence(node(selector)) { it.parent }
                    .first { it.isClickable }.visibleBounds
            }
            bounds.forEach { assertTrue("Toolbar controls must retain full touch targets inside the screen in $language: $it", it.left >= 0 && it.right <= device.displayWidth && it.width() >= 47 * context.resources.displayMetrics.density) }
            val format = Formats(localized, locale)
            val titleNode = node(By.pkg(context.packageName).res("calendar_period_title"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "toolbar-$language.xml"))
            device.takeScreenshot(File(context.filesDir, "toolbar-$language.png"))
            val titleTexts = (listOf(titleNode) + titleNode.findObjects(By.text(java.util.regex.Pattern.compile(".+")))).mapNotNull { it.text }
            val visibleDate = titleTexts.firstOrNull { it in listOf(format.dayMonth(date), format.numericDayMonth(date)) }
            assertTrue("The displayed date must retain both month and day in $language: $titleTexts", visibleDate != null)
            assertFalse("An opened date must not show the unrelated Month/Week/Day menu.",
                device.hasObject(By.pkg(context.packageName).res("calendar_view_menu")))
            val title = titleNode.visibleBounds
            assertTrue("The date must retain readable space beside enabled arrows and Back in $language: $title", title.width() >= 48 * context.resources.displayMetrics.density)
            if (language == "ar") assertTrue("Back must be on the leading edge in RTL.", bounds[0].centerX() > bounds.last().centerX())
            device.takeScreenshot(File(context.filesDir, "toolbar-$language.png"))
            node(settingsAction).click()
            if (compactActions) node(By.pkg(context.packageName).text(localized.getString(R.string.title_settings))).click()
            awaitUi { vm.stack.lastOrNull() == Screen.Settings }
            device.pressBack()
            awaitUi { vm.stack.isEmpty() && vm.hasDayParent && vm.viewMode == ViewMode.Day }
        }
    }

    @Test
    fun longPressingAGridDateNeverOpensAPreviewAndTappingStillOpensDay() {
        val target = date.plusDays(2)
        val title = listOf(
            "Electrical engineering laboratory preparation and equipment reservation",
            "Review the complete circuit diagram and bring the measurement notes",
            "Check every oscilloscope probe and compare both signal channels",
            "Prepare the digital communication demonstration with the whole team",
            "Record the expected voltage readings for each important test point",
            "Keep the final observations and the teacher instructions together",
            "Complete title ending marker",
        ).joinToString("\n")
        val eventId = addTimed(target, title, 20, 21)
        onMain { vm.applyLayoutPreset("planner"); vm.changeDrilldownLayout("cards"); vm.changeViewMode(ViewMode.Month); vm.select(date); vm.reload() }
        awaitUi { vm.events.any { it.id == eventId } }
        node(day(target)).longClick()
        assertFalse("Long press must not show the removed preview.", device.hasObject(By.pkg(context.packageName).text("Open day")))
        if (vm.viewMode == ViewMode.Day) {
            node(By.pkg(context.packageName).res("calendar_day_cards"))
            device.pressBack()
        }
        awaitUi { vm.viewMode == ViewMode.Month && vm.selectedDate == date }
        assertFalse("Returning to Month must not leave an event preview.",
            device.hasObject(By.pkg(context.packageName).textContains("Complete title ending marker")))
        node(day(target)).click()
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target && vm.hasDayParent }
        node(By.pkg(context.packageName).res("calendar_day_cards"))
        node(By.pkg(context.packageName).res("day_event_$eventId")).click()
        awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == eventId }
        assertTrue("The complete title must remain accessible from Day.", vm.events.single { it.id == eventId }.title == title)
    }

    @Test
    fun dayPickerChangesTheShownEventsAndTheNewEventDateForEveryDayLayout() {
        listOf("cards", "timeline", "schedule").forEach { layout ->
            onMain { vm.changeDayLayout(layout); vm.changeViewMode(ViewMode.Day); vm.select(date) }
            node(By.pkg(context.packageName).res("calendar_day_picker"))
            val target = date.plusDays(1)
            node(day(target)).click()
            awaitUi { vm.selectedDate == target }
            if (layout == "timeline") {
                val targetId = vm.events.single { it.title == tomorrowTitle }.id
                revealTimelineEvent(targetId)
            } else {
                node(By.pkg(context.packageName).textContains(tomorrowTitle))
            }
            node(By.pkg(context.packageName).desc(english.getString(R.string.action_new_event))).click()
            awaitUi { vm.editor?.startDate == target }
            node(By.pkg(context.packageName).desc(english.getString(R.string.action_close))).click()
            awaitUi { vm.editor == null }
        }
    }

    @Test
    fun overlappingTimelineEventsHaveSeparateTapTargetsAndAllDayStaysAboveTheHours() {
        onMain { vm.applyLayoutPreset("planner"); vm.changeDayLayout("timeline"); vm.changeViewMode(ViewMode.Day); vm.select(date) }
        // Measure both complete cards above the floating navigation and Add controls.
        positionTimelineEvent(secondId)
        val first = node(By.pkg(context.packageName).res("timeline_event_$firstId")).visibleBounds
        val second = node(By.pkg(context.packageName).res("timeline_event_$secondId")).visibleBounds
        assertTrue("Overlapping events must occupy different horizontal lanes: $first / $second", first.right <= second.left || second.right <= first.left)
        assertTrue("Events whose times overlap must overlap vertically: $first / $second", first.top < second.bottom && second.top < first.bottom)
        assertTrue("Both overlapping events need usable tap targets.", first.width() > 20 && second.width() > 20 && first.height() > 20 && second.height() > 20)
        val overlapping = listOf(firstId, secondId).map { id -> vm.events.single { it.id == id } }
        assertSeparatedRoundedCards("day-two-overlaps", overlapping, nowMarkerMayCrossCards = date == LocalDate.now()) { cards ->
            assertTrue("The 9:00 card must start above the 10:00 card: ${cards.map { it.target }}", cards[0].target.top < cards[1].target.top)
            assertTrue("Cards whose times overlap must sit side by side, not on top of each other: ${cards.map { it.bounds }}",
                cards[0].bounds.right <= cards[1].bounds.left || cards[1].bounds.right <= cards[0].bounds.left)
        }
        val allDay = node(By.pkg(context.packageName).res("calendar_timeline_all_day")).visibleBounds
        val hours = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
        assertTrue("All-day events belong above the hourly scroll area.", allDay.bottom <= hours.top)
        node(By.pkg(context.packageName).descContains(allDayTitle))
        captureFinal("timeline-two-overlaps")
        listOf(firstId, secondId).forEach { id ->
            node(By.pkg(context.packageName).res("timeline_event_$id")).click()
            awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == id }
            node(By.pkg(context.packageName).desc(english.getString(R.string.action_back))).click()
            awaitUi { vm.stack.isEmpty() }
        }
        node(By.pkg(context.packageName).descContains(allDayTitle)).click()
        awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == allDayId }
    }

    @Test
    fun continuousWeekDragsAcrossWeeksAndReversesWithoutChangingTheSelectedDate() {
        val today = LocalDate.now()
        val markers = (-14L..21L).associate { offset ->
            val day = today.plusDays(offset)
            day to addTimed(day, "Continuous column $day", 8, 9)
        }
        onMain {
            vm.changeVisibleWeekDays(3)
            vm.changeWeekLayout("continuous")
            vm.changeViewMode(ViewMode.Week)
            vm.changeSwipeNavigation(false)
            vm.goToday()
            vm.reload()
        }
        node(By.pkg(context.packageName).res("calendar_continuous_timeline"))
        awaitUi { !vm.loadingEvents && vm.timelineViewportStart == today &&
            vm.events.any { it.id == markers.getValue(today) } }
        revealTimelineEvent(markers.getValue(today))
        assertTimelineHeadersAlignWithMarkers(markers)
        val selected = vm.selectedDate
        repeat(10) { step ->
            if (requireNotNull(vm.timelineViewportStart) < today.plusDays(8)) {
                val before = requireNotNull(vm.timelineViewportStart)
                repeat(2) {
                    if (vm.timelineViewportStart == before) dragTimelineHorizontally(forward = true, header = step % 2 == 0)
                }
                awaitUi { vm.timelineViewportStart?.isAfter(before) == true && !vm.loadingEvents }
                assertEquals("A continuous drag must preserve the selected date.", selected, vm.selectedDate)
                assertTimelineHeadersAlignWithMarkers(markers)
            }
        }
        assertTrue("Repeated drags must cross a whole week without paging the selection.",
            requireNotNull(vm.timelineViewportStart) >= today.plusDays(8))
        node(By.pkg(context.packageName).res("calendar_today_previous"))
        assertFalse(device.hasObject(By.pkg(context.packageName).res("calendar_today_up")))
        repeat(24) { step ->
            if (requireNotNull(vm.timelineViewportEnd) > today.minusDays(8)) {
                val before = requireNotNull(vm.timelineViewportStart)
                repeat(2) {
                    if (vm.timelineViewportStart == before) dragTimelineHorizontally(forward = false, header = step % 2 != 0)
                }
                awaitUi { vm.timelineViewportStart?.isBefore(before) == true && !vm.loadingEvents }
                assertEquals("Reversing a continuous drag must preserve the selected date.", selected, vm.selectedDate)
                assertTimelineHeadersAlignWithMarkers(markers)
            }
        }
        assertTrue("Reversing must reach the previous week, rather than reset the strip.",
            requireNotNull(vm.timelineViewportEnd) <= today.minusDays(8))
        val request = vm.todayRequest
        clickCurrent(By.pkg(context.packageName).res("calendar_today_next"))
        awaitUi { vm.selectedDate == today && vm.todayRequest == request + 1 &&
            vm.timelineViewportStart == today && visibleTimelineHeader(today) != null &&
            !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
        assertTrue("Today must restore the selected header into the actual viewport.",
            node(day(today)).let { it.isSelected || it.isChecked })
        assertTrue("Dragging the strip must not open an event.", vm.stack.isEmpty())
        assertEquals(null, vm.editor)
    }

    @Test
    fun selectingTheSameDateReturnsAPannedContinuousViewportWithoutRequestingToday() {
        val selected = date.plusWeeks(2)
        onMain {
            vm.changeWeekLayout("continuous")
            vm.changeVisibleWeekDays(3)
            vm.changeViewMode(ViewMode.Week)
            vm.select(selected)
        }
        awaitUi { vm.timelineViewportStart == selected && visibleTimelineHeader(selected) != null }
        repeat(5) { dragTimelineHorizontally(forward = true, header = it % 2 == 0) }
        awaitUi { requireNotNull(vm.timelineViewportStart) > selected.plusDays(2) }
        assertEquals(selected, vm.selectedDate)
        assertEquals(null, visibleTimelineHeader(selected))
        val todayRequest = vm.todayRequest
        val navigation = vm.timelineNavigationRequest
        val pannedStart = requireNotNull(vm.timelineViewportStart)
        val pannedEnd = requireNotNull(vm.timelineViewportEnd)
        val anchor = (0L..vm.visibleWeekDays.toLong()).map(pannedStart::plusDays)
            .first { visibleTimelineHeader(it) != null }
        val anchorBounds = requireNotNull(visibleTimelineHeader(anchor)).visibleBounds
        val retained = vm
        recreateActivity()
        awaitUi {
            vm === retained && vm.timelineViewportStart?.let { it in pannedStart.minusDays(1)..pannedStart.plusDays(1) } == true &&
                vm.timelineViewportEnd?.let { it in pannedEnd.minusDays(1)..pannedEnd.plusDays(1) } == true && visibleTimelineHeader(anchor) != null
        }
        assertEquals("Recreation must preserve the selected date while the viewport remains panned.", selected, vm.selectedDate)
        assertEquals(null, visibleTimelineHeader(selected))
        assertTrue("Recreation must retain the rendered day anchor near its previous position.",
            kotlin.math.abs(requireNotNull(visibleTimelineHeader(anchor)).visibleBounds.centerX() - anchorBounds.centerX()) <=
                anchorBounds.width() / 3)
        assertEquals("Recreation must not request Today.", todayRequest, vm.todayRequest)
        assertEquals("Recreation must not request selected-date navigation.", navigation, vm.timelineNavigationRequest)
        onMain { vm.select(selected) }
        awaitUi { vm.timelineViewportStart == selected &&
            visibleTimelineHeader(selected)?.let { it.isSelected || it.isChecked } == true }
        assertEquals("An explicit same-date selection must not be replaced with a Today request.", todayRequest, vm.todayRequest)
        assertEquals(navigation + 1, vm.timelineNavigationRequest)
        assertEquals(selected, vm.selectedDate)
    }

    @Test
    fun completedTodaySelectionKeepsTheUsersLaterPanAfterRecreation() {
        val today = LocalDate.now()
        val from = today.plusDays(12)
        onMain {
            vm.changeNavigationAnimation(false)
            vm.changeWeekLayout("continuous")
            vm.changeVisibleWeekDays(3)
            vm.changeViewMode(ViewMode.Week)
            vm.select(from)
        }
        awaitUi { vm.timelineViewportStart == from && visibleTimelineHeader(from) != null }
        onMain { vm.changeNavigationAnimation(true); vm.goToday() }
        awaitUi { vm.timelineViewportStart == today && visibleTimelineHeader(today) != null &&
            vm.handledTimelineNavigation == vm.timelineNavigationRequest }
        repeat(4) { dragTimelineHorizontally(forward = true, header = true) }
        val panned = requireNotNull(vm.timelineViewportStart)
        assertTrue("The user's pan must leave today outside the viewport.", panned > today)
        val request = vm.timelineNavigationRequest
        recreateActivity()
        awaitUi { vm.timelineViewportStart?.let { it in panned.minusDays(1)..panned.plusDays(1) } == true &&
            visibleTimelineHeader(panned) != null }
        assertEquals(today, vm.selectedDate)
        assertEquals(request, vm.timelineNavigationRequest)
        assertEquals(request, vm.handledTimelineNavigation)
        assertEquals(null, visibleTimelineHeader(today))
        captureFinal("continuous-today-then-recreated-pan")
    }

    @Test
    fun pendingTodaySelectionSurvivesDisposalAndRecreation() {
        val today = LocalDate.now()
        val from = today.plusDays(12)
        onMain {
            vm.changeNavigationAnimation(false)
            vm.changeWeekLayout("continuous")
            vm.changeVisibleWeekDays(3)
            vm.changeViewMode(ViewMode.Week)
            vm.select(from)
        }
        awaitUi { vm.timelineViewportStart == from && visibleTimelineHeader(from) != null }
        onMain { vm.changeNavigationAnimation(true) }
        val outgoing = requireNotNull(activity)
        var request = 0
        instrumentation.runOnMainSync {
            vm.goToday()
            request = vm.timelineNavigationRequest
            assertTrue("The new request must still be pending before disposal.", request != vm.handledTimelineNavigation)
            val host = outgoing.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
                as androidx.compose.ui.platform.AbstractComposeView
            host.disposeComposition()
            assertTrue("Disposing the old composition must retain pending navigation.", request != vm.handledTimelineNavigation)
            outgoing.recreate()
        }
        awaitUi {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { it !== outgoing }?.let { activity = it }
            }
            activity !== outgoing && vm.timelineViewportStart == today && visibleTimelineHeader(today) != null &&
                vm.handledTimelineNavigation == request
        }
        assertEquals(request, vm.timelineNavigationRequest)
        assertEquals(today, vm.selectedDate)
        captureFinal("continuous-recreated-today")
    }

    @Test
    fun continuousResizeKeepsTheDateAndFractionWhenColumnsBecomeNarrower() {
        val shown = date.plusWeeks(2)
        onMain {
            vm.changeNavigationAnimation(false)
            vm.changeWeekLayout("continuous")
            vm.changeVisibleWeekDays(3)
            vm.changeViewMode(ViewMode.Week)
            vm.select(shown)
        }
        awaitUi { vm.timelineViewportStart == shown && visibleTimelineHeader(shown) != null }
        val width = mutableStateOf(660.dp)
        mountContinuousTimelineHost(width)
        onMain { vm.select(shown) }
        fun hostState(): String = "selected=${vm.selectedDate}, first=${vm.timelineViewportStart}, last=${vm.timelineViewportEnd}, " +
            "nodes=" + device.findObjects(By.pkg(context.packageName).desc(java.util.regex.Pattern.compile(".+")))
                .joinToString { "${it.contentDescription}:${it.visibleBounds}" }
        awaitUi(detail = ::hostState) { vm.timelineViewportStart == shown && visibleTimelineHeader(shown.plusDays(1)) != null &&
            vm.timelineNavigationRequest == vm.handledTimelineNavigation }
        val viewport = timelineHeaderViewport()
        val next = requireNotNull(visibleTimelineHeader(shown.plusDays(1))).visibleBounds
        val travel = (next.width() * 0.8f).toInt()
        val fromX = viewport.left + viewport.width() * 82 / 100
        dragTouch(fromX, viewport.centerY(), fromX - travel, viewport.centerY())
        try {
            awaitUi(detail = { "viewport=$viewport, next=$next, travel=$travel, ${hostState()}" }) {
                visibleTimelineHeader(shown.plusDays(1)) != null
            }
        } catch (failure: Throwable) {
            runCatching {
                device.takeScreenshot(File(context.filesDir, "continuous-resize-unready.png"))
                device.dumpWindowHierarchy(File(context.filesDir, "continuous-resize-unready.xml"))
                File(context.filesDir, "continuous-resize-unready.txt").writeText(hostState())
            }
            throw failure
        }
        val before = requireNotNull(visibleTimelineHeader(shown.plusDays(1))).visibleBounds
        val fraction = 1f - (before.left - viewport.left).toFloat() / before.width()
        assertTrue("The saved offset must exceed the narrower column width.", fraction > 0.55f && fraction < 1f)
        captureFinal("continuous-resize-before")
        onMain { width.value = 330.dp }
        awaitUi(detail = ::hostState) {
            val resized = visibleTimelineHeader(shown.plusDays(1))?.visibleBounds
            resized != null && resized.width() < before.width() &&
                kotlin.math.abs((resized.left - timelineHeaderViewport().left).toFloat() / resized.width() - (1f - fraction)) < 0.06f
        }
        assertTrue("The next date must retain space for the preceding partial column.",
            requireNotNull(visibleTimelineHeader(shown.plusDays(1))).visibleBounds.left > timelineHeaderViewport().left)
        assertEquals(shown, vm.selectedDate)
        captureFinal("continuous-resize-after")
    }

    @Test
    fun continuousTodaySelectionAndResizeShareTheSameTarget() {
        val today = LocalDate.now()
        val from = today.plusDays(12)
        onMain {
            vm.changeNavigationAnimation(false)
            vm.changeWeekLayout("continuous")
            vm.changeVisibleWeekDays(3)
            vm.changeViewMode(ViewMode.Week)
            vm.select(from)
        }
        awaitUi { vm.timelineViewportStart == from && visibleTimelineHeader(from) != null }
        val width = mutableStateOf(660.dp)
        mountContinuousTimelineHost(width)
        onMain { vm.select(from) }
        awaitUi { vm.timelineViewportStart == from && visibleTimelineHeader(from.plusDays(1)) != null &&
            vm.timelineNavigationRequest == vm.handledTimelineNavigation }
        val originalHeaderWidth = requireNotNull(visibleTimelineHeader(from.plusDays(1))).visibleBounds.width()
        onMain { vm.changeNavigationAnimation(true) }
        var request = 0
        instrumentation.runOnMainSync {
            vm.goToday()
            request = vm.timelineNavigationRequest
            assertTrue("Resize must be queued with the unhandled Today request.", vm.handledTimelineNavigation != request)
            width.value = 330.dp
        }
        awaitUi {
            vm.timelineViewportStart == today && visibleTimelineHeader(today) != null &&
                visibleTimelineHeader(today.plusDays(1))?.visibleBounds?.width()?.let { it < originalHeaderWidth } == true &&
                vm.handledTimelineNavigation == request
        }
        assertEquals(today, vm.selectedDate)
        assertEquals(request, vm.timelineNavigationRequest)
        val next = requireNotNull(visibleTimelineHeader(today.plusDays(1))).visibleBounds
        assertTrue("Today must finish at the leading edge after resizing.",
            kotlin.math.abs(next.left - timelineHeaderViewport().left - next.width()) <= 3)
        captureFinal("continuous-resize-today-after")
    }

    private fun mountContinuousTimelineHost(width: androidx.compose.runtime.State<androidx.compose.ui.unit.Dp>) {
        val formats = Formats(english, Locale.ENGLISH)
        onMain {
            requireNotNull(activity).setContent {
                SunlithTheme("light", false) {
                    Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding().semantics { testTagsAsResourceId = true }) {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            Box(Modifier.width(width.value).height(900.dp)) {
                                ContinuousTimeline(vm, formats, LocalDate.now(), vm.navigationAnimation, rememberScrollState(), onOpen = {})
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun pagedTimelineRestoresManualHourWithAFreshViewModel() {
        val today = LocalDate.now()
        val markerHour = if (LocalTime.now().hour < 12) 20 else 1
        val marker = addTimed(today, "Restored manual timeline hour", markerHour, markerHour + 1)
        onMain {
            vm.changeWeekLayout("timeline")
            vm.changeVisibleWeekDays(3)
            vm.changeStartView(ViewMode.Week.name)
            vm.changeViewMode(ViewMode.Week)
            vm.goToday()
            vm.reload()
        }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == marker } &&
            vm.pagedTimelineTodayHandled.intValue == vm.todayRequest }
        val before = revealTimelineEvent(marker).visibleBounds.top - timelineScroller().visibleBounds.top
        val outgoing = requireNotNull(activity)
        val outgoingVm = vm
        assertTrue("The saved UI must follow an actual Today request.", outgoingVm.todayRequest > 0)
        instrumentation.runOnMainSync {
            outgoing.viewModelStore.clear()
            outgoing.recreate()
        }
        awaitUi {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { it !== outgoing }?.let {
                        activity = it
                        vm = ViewModelProvider(it)[SunlithViewModel::class.java]
                    }
            }
            activity !== outgoing && vm !== outgoingVm && !vm.loadingEvents && vm.events.any { it.id == marker } &&
                device.findObject(By.pkg(context.packageName).res("timeline_event_$marker"))?.visibleBounds?.let {
                    it.height() > 20 && kotlin.math.abs(it.top - timelineScroller().visibleBounds.top - before) <=
                        3 * context.resources.displayMetrics.density
                } == true
        }
        assertEquals(0, vm.todayRequest)
        assertEquals(0, vm.pagedTimelineTodayHandled.intValue)
        captureFinal("paged-fresh-model-manual-hour")
        onMain {
            vm.changeMonthLayout("split")
            vm.changeAgendaRange("continuous")
            vm.changeViewMode(ViewMode.Month)
            vm.changeCollapseCalendarOnScroll(true)
            vm.select(today)
        }
        awaitUi { !vm.loadingEvents && device.hasObject(By.pkg(context.packageName).res("calendar_grid")) }
        val expandedTodayTop = node(By.pkg(context.packageName).res("calendar_agenda")).visibleBounds.top
        onMain { vm.select(today.plusMonths(2)) }
        node(By.pkg(context.packageName).res("calendar_today_previous"))
        val expandedFutureTop = node(By.pkg(context.packageName).res("calendar_agenda")).visibleBounds.top
        val requiredCollapse = 48 * context.resources.displayMetrics.density
        repeat(2) {
            if (node(By.pkg(context.packageName).res("calendar_agenda")).visibleBounds.top > expandedFutureTop - requiredCollapse) {
                val grid = node(By.pkg(context.packageName).res("calendar_grid")).visibleBounds
                device.swipe(grid.centerX(), grid.bottom - 12, grid.centerX(), grid.top + 12, 20)
                device.waitForIdle()
            }
        }
        awaitUi { node(By.pkg(context.packageName).res("calendar_agenda")).visibleBounds.top <= expandedFutureTop - requiredCollapse }
        captureFinal("fresh-model-collapsed-month")
        clickCurrent(By.pkg(context.packageName).res("calendar_today_previous"))
        awaitUi { vm.selectedDate == today && vm.todayRequest == 1 &&
            node(By.pkg(context.packageName).res("calendar_agenda")).visibleBounds.top >=
                expandedTodayTop - 3 * context.resources.displayMetrics.density }
        captureFinal("fresh-model-first-today-expanded-month")
    }

    @Test
    fun enteringDayFocusSupersedesAnUnfinishedWeekTodayRequest() {
        val today = LocalDate.now()
        val marker = addTimed(today, "New Day focus owns navigation", 1, 2)
        val nowHour = (LocalTime.now().hour - 1).coerceAtLeast(0)
        val nowMarker = addTimed(today, "New Today request owns navigation", nowHour, nowHour + 1)
        onMain {
            vm.changeWeekLayout("timeline")
            vm.changeDayLayout("timeline")
            vm.changeViewMode(ViewMode.Week)
            vm.changeNavigationAnimation(true)
            vm.goToday()
            assertTrue("Today must be unhandled when the newer intent arrives.",
                vm.pagedTimelineTodayHandled.intValue != vm.todayRequest)
            vm.openDay(today)
            assertTrue(vm.pendingDayFocus)
            assertEquals("The newer Day request must supersede the older Today request immediately.",
                vm.todayRequest, vm.pagedTimelineTodayHandled.intValue)
        }
        awaitUi {
            val event = device.findObject(By.pkg(context.packageName).res("timeline_event_$marker"))?.visibleBounds
            val viewport = device.findObject(By.pkg(context.packageName).res("calendar_timeline"))?.visibleBounds
            !vm.loadingEvents && !vm.pendingDayFocus && event != null && viewport != null && event.height() > 20 &&
                event.top >= viewport.top && event.top < viewport.top + viewport.height() / 2
        }
        captureFinal("paged-day-focus-supersedes-today")
        onMain {
            vm.openDay(today)
            assertTrue(vm.pendingDayFocus)
            vm.goToday()
            assertFalse("A newer Today request must also supersede an older Day focus.", vm.pendingDayFocus)
        }
        awaitUi {
            val event = device.findObject(By.pkg(context.packageName).res("timeline_event_$nowMarker"))?.visibleBounds
            val viewport = device.findObject(By.pkg(context.packageName).res("calendar_timeline"))?.visibleBounds
            vm.pagedTimelineTodayHandled.intValue == vm.todayRequest && event != null && viewport != null &&
                event.height() > 20 && event.top >= viewport.top && event.top < viewport.top + viewport.height() / 2
        }
        captureFinal("paged-today-supersedes-day-focus")
        onMain {
            vm.goToday()
            assertTrue(vm.pagedTimelineTodayHandled.intValue != vm.todayRequest)
            vm.back()
            assertEquals(ViewMode.Week, vm.viewMode)
            assertEquals("Back must also supersede an unfinished Today request.",
                vm.todayRequest, vm.pagedTimelineTodayHandled.intValue)
        }
    }

    @Test
    fun pagedTimelineTodayFinishesAfterEventsRebuildItsGroupedAxis() {
        checkPagedTimelineAxisRestart(focusEvent = false)
    }

    @Test
    fun pagedTimelineEventFocusFinishesAfterEventsRebuildItsGroupedAxis() {
        checkPagedTimelineAxisRestart(focusEvent = true)
    }

    private fun checkPagedTimelineAxisRestart(focusEvent: Boolean) {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        fun event(id: Long, day: LocalDate, hour: Int) = CalendarEvent(id, calendarId, "Axis restart class $hour", "", "",
            day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(hour + 1, 0).atZone(zone).toInstant().toEpochMilli(), false, zone.id)
        val initial = listOf(event(-101, today.plusDays(1), 8), event(-102, today, 20))
        val incoming = event(-103, today.plusDays(1), 12)
        val events = mutableStateOf(initial)
        val request = mutableIntStateOf(0)
        val handled = mutableIntStateOf(0)
        val focus = mutableStateOf<TimelineFocus?>(null)
        val armed = mutableStateOf(false)
        val rebuiltDuringMotion = mutableStateOf(false)
        val focusHandled = mutableStateOf(false)
        val scroll = ScrollState(0)
        var hourPx = 0f
        var gapPx = 0f
        var edgePx = 0f
        var start = 0
        var originalTarget = 0
        fun targetOn(axis: TimelineAxis, minute: Int): Float =
            axis.minuteToY(minute) + if (!focusEvent && minute > 0) edgePx else 0f
        val targetMinute = if (focusEvent) 19 * 60 + 30 else (LocalTime.now().hour - 1).coerceAtLeast(0) * 60
        val formats = Formats(english, Locale.ENGLISH)
        onMain {
            requireNotNull(activity).setContent {
                SunlithTheme("light", false) {
                    Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding().semantics { testTagsAsResourceId = true }) {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            val density = LocalDensity.current
                            val line = with(density) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }
                            val measurer = rememberTextMeasurer()
                            edgePx = with(density) {
                                (measurer.measure("Ag", MaterialTheme.typography.labelSmall, maxLines = 1).size.height / 2f + 3.dp.toPx()).roundToInt().toFloat()
                            }
                            hourPx = with(density) { 64.dp.toPx() }
                            gapPx = with(density) { maxOf(48.dp, line * 3f).toPx() }
                            LaunchedEffect(armed.value) {
                                if (!armed.value || focusEvent) return@LaunchedEffect
                                snapshotFlow { scroll.value }.first {
                                    kotlin.math.abs(it - start) > 5 && kotlin.math.abs(it - originalTarget) > 5
                                }
                                rebuiltDuringMotion.value = true
                                events.value = initial + incoming
                            }
                            Box(Modifier.width(350.dp).height(250.dp)) {
                                Timeline(listOf(today, today.plusDays(1)), { day ->
                                    events.value.filter { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == day }
                                }, today, today, formats, true, scroll,
                                    autoScroll = true, todayRequest = request.intValue, motion = true,
                                    onSelectDay = {}, onOpen = {}, onNew = {}, onOverflow = { _, _ -> },
                                    focus = focus.value, onFocusHandled = { focusHandled.value = true; focus.value = null },
                                    groupEmptyHours = true, handledTodayRequest = handled, fallback = {})
                            }
                        }
                    }
                }
            }
        }
        awaitUi { scroll.viewportSize > 0 && scroll.maxValue > 100 && hourPx > 0f }
        val originalAxis = TimelineAxis(hourPx, listOf(8 * 60 to 9 * 60, 20 * 60 to 21 * 60), true, gapPx)
        onMain {
            originalTarget = targetOn(originalAxis, targetMinute).roundToInt().coerceIn(0, scroll.maxValue)
            start = if (originalTarget > scroll.maxValue / 2) 0 else scroll.maxValue
            scroll.dispatchRawDelta((start - scroll.value).toFloat())
            armed.value = true
            if (focusEvent) {
                // Date focus now lands immediately; publish the changed axis with that request.
                // Today below still proves an axis rebuild during actual animated movement.
                events.value = initial + incoming
                rebuiltDuringMotion.value = true
                focus.value = TimelineFocus(today, 1)
            } else request.intValue = 1
        }
        awaitUi { rebuiltDuringMotion.value }
        val rebuiltAxis = TimelineAxis(hourPx,
            listOf(8 * 60 to 9 * 60, 12 * 60 to 13 * 60, 20 * 60 to 21 * 60), true, gapPx)
        if (focusEvent) assertTrue("The changed gap must move the focus target in pixels.",
            kotlin.math.abs(originalAxis.minuteToY(targetMinute) - rebuiltAxis.minuteToY(targetMinute)) > 20)
        awaitUi(detail = { "scroll=${scroll.value}, max=${scroll.maxValue}, handled=${handled.intValue}, focus=${focusHandled.value}" }) {
            val currentTarget = if (focusEvent) targetMinute else (LocalTime.now().hour - 1).coerceAtLeast(0) * 60
            val expected = targetOn(rebuiltAxis, currentTarget).roundToInt().coerceIn(0, scroll.maxValue)
            kotlin.math.abs(scroll.value - expected) <= 2 &&
                if (focusEvent) focusHandled.value else handled.intValue == 1
        }
        assertEquals("The incoming event must actually rebuild the grouped axis.", initial + incoming, events.value)
        captureFinal(if (focusEvent) "paged-axis-event-focus" else "paged-axis-today")
    }

    @Test
    fun adjacentFiveMinuteEventsHaveADiscoverableListWithFullTitlesAndRealTimes() {
        val shown = date.plusWeeks(2)
        val ids = seedAdjacentShortEvents(shown)
        onMain { vm.changeDayLayout("timeline"); vm.changeViewMode(ViewMode.Day); vm.select(shown); vm.reload() }
        awaitUi { !vm.loadingEvents && ids.all { id -> vm.events.any { it.id == id } } }
        positionTimelineEvent(ids.first())
        val events = ids.map { id -> vm.events.single { it.id == id } }
        val formats = Formats(calendarContext(), calendarLocale)
        val holders = cardNamingEachEvent(events, formats)
        assertEquals("Sequential short events share one full width card, never false overlap lanes",
            setOf(events.first()), holders.values.toSet())
        val em = 11 * context.resources.displayMetrics.density * context.resources.configuration.fontScale
        val group = assertSeparatedRoundedCards("day-adjacent-short", listOf(events.first())) { cards ->
            assertCountBesideTitle("day-adjacent-short", cards.single(), timelineIsRtl(),
                context.resources.displayMetrics.density, em)
        }.single()
        assertTrue("A compact group reserves one title line, not one row per event: ${group.target}",
            group.target.height() < 64 * context.resources.displayMetrics.density)
        captureFinal("day-adjacent-short-labels")
        val heading = calendarContext().resources.getQuantityString(R.plurals.event_count, events.size, events.size)
        fun listItem(event: CalendarEvent): UiObject2? = device.findObjects(By.pkg(context.packageName).descContains(event.title))
            .firstOrNull { item -> events.count { item.contentDescription.orEmpty().contains(it.title) } == 1 }
        fun verifyDetails(target: CalendarEvent) {
            awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == target.id }
            val opened = (vm.stack.last() as Screen.Details).event
            assertEquals(target.title, opened.title)
            assertEquals(target.startMillis, opened.startMillis)
            assertEquals(target.endMillis, opened.endMillis)
            onMain { vm.back() }
            awaitUi { vm.stack.isEmpty() }
        }
        events.forEach { event ->
            node(By.pkg(context.packageName).res("timeline_event_${events.first().id}")).click()
            node(By.pkg(context.packageName).text(heading))
            assertTrue("The list leaves the Day timeline underneath it", vm.stack.isEmpty())
            events.forEach { listedEvent ->
                awaitUi { listItem(listedEvent) != null }
                val description = requireNotNull(listItem(listedEvent)).contentDescription.orEmpty()
                assertTrue("The list preserves the complete title and real times: $description",
                    description.contains(listedEvent.title) && description.contains(formats.time(listedEvent.startMillis)) &&
                        description.contains(formats.time(listedEvent.endMillis)))
            }
            if (event == events.first()) captureFinal("day-adjacent-short-labels-list")
            requireNotNull(listItem(event)).click()
            verifyDetails(event)
        }
    }

    @Test
    fun continuousWeekFlingCanReverseBeforeSettlingAndTodayRestoresItsViewport() {
        onMain {
            vm.changeNavigationAnimation(true)
            vm.changeWeekLayout("continuous")
            vm.changeVisibleWeekDays(3)
            vm.changeViewMode(ViewMode.Week)
            vm.goToday()
        }
        awaitUi { vm.timelineViewportStart == vm.today && visibleTimelineHeader(vm.today) != null }
        assertTrue(vm.navigationAnimation)
        flingTimelineAndReverse()
        val request = vm.todayRequest
        onMain { vm.goToday() }
        awaitUi { vm.timelineViewportStart == vm.today && visibleTimelineHeader(vm.today) != null }
        assertEquals(request + 1, vm.todayRequest)
        assertEquals(vm.today, vm.selectedDate)
    }

    @Test
    fun optInSyntheticTimelineDisplayCheckpoints() {
        assumeTrue("Explicit synthetic timeline display opt-in required.",
            InstrumentationRegistry.getArguments().getString("timelineDisplay") == "true")
        val shown = date.plusWeeks(2)
        val french = calendarLocale.language == "fr"
        val ids = seedAdjacentShortEvents(shown)
        val longTitle = if (french) "Cours synthétique de circuits numériques et communications avec plusieurs activités pratiques"
            else "Synthetic digital circuits and communications class with several practical activities"
        val longId = addTimedMinutes(shown, longTitle, 11 * 60 + 30, 14 * 60 + 10)
        addTimed(shown.plusDays(1), if (french) "Laboratoire synthétique du lendemain" else "Synthetic following-day laboratory", 17, 18)
        onMain {
            vm.changeNavigationAnimation(true)
            vm.changeWeekLayout("continuous")
            vm.changeVisibleWeekDays(3)
            vm.changeViewMode(ViewMode.Week)
            vm.select(shown)
            vm.reload()
        }
        awaitUi { !vm.loadingEvents && vm.timelineViewportStart == shown && visibleTimelineHeader(shown) != null }
        positionTimelineEvent(longId)
        captureMinor("continuous-start")
        onMain { vm.changeGroupEmptyHours(true) }
        positionTimelineEvent(longId)
        val gapLabel = calendarContext().getString(R.string.timeline_empty_hours)
        repeat(12) {
            if (device.findObjects(By.pkg(context.packageName).text(gapLabel)).any { it.visibleBounds.height() > 10 }) return@repeat
            timelineScroller().scroll(Direction.UP, 0.65f)
            device.waitForIdle()
        }
        assertTrue("The grouped capture must show its empty-hour label.",
            device.findObjects(By.pkg(context.packageName).text(gapLabel)).any { it.visibleBounds.height() > 10 })
        captureMinor("continuous-grouped")
        flingTimelineAndReverse()
        captureMinor("continuous-reversed")
        onMain { vm.select(shown) }
        awaitUi { vm.timelineViewportStart == shown && visibleTimelineHeader(shown) != null }
        positionTimelineEvent(longId)
        captureMinor("continuous-returned")
        onMain { vm.changeGroupEmptyHours(false); vm.changeWeekLayout("timeline"); vm.changeVisibleWeekDays(5); vm.select(shown) }
        awaitUi { !vm.loadingEvents && visibleTimelineHeader(shown) != null }
        positionTimelineEvent(longId)
        if (context.resources.configuration.fontScale <= 1.15f) {
            assertTrue("All five normal-font dates must have visible header centres.",
                (0L until 5L).all { visibleTimelineHeader(shown.plusDays(it)) != null })
        }
        captureMinor("five-day-window")
        onMain { vm.changeVisibleWeekDays(7); vm.select(shown) }
        awaitUi { !vm.loadingEvents && visibleTimelineHeader(shown) != null }
        positionTimelineEvent(longId)
        captureMinor("seven-day-window")
        onMain { vm.changeDayLayout("timeline"); vm.changeViewMode(ViewMode.Day); vm.selectAndFocus(shown) }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == longId } }
        positionTimelineEvent(longId)
        captureMinor("day-long-class")
        positionTimelineEvent(ids.first())
        captureMinor("day-short-events-first")
        val shortEvents = ids.map { id -> vm.events.single { it.id == id } }
        val formats = Formats(calendarContext(), calendarLocale)
        val holders = cardNamingEachEvent(shortEvents, formats)
        assertEquals("Short sequential events use one compact group", setOf(shortEvents.first()), holders.values.toSet())
        assertPaintedTimelineTitle(node(By.pkg(context.packageName).res("timeline_event_${ids.first()}")), shortEvents.first())
        node(By.pkg(context.packageName).res("timeline_event_${ids.first()}")).click()
        val target = shortEvents.last()
        val heading = calendarContext().resources.getQuantityString(R.plurals.event_count, shortEvents.size, shortEvents.size)
        node(By.pkg(context.packageName).text(heading))
        val item = scrollDialogToText(target.title)
        assertTrue("The popup keeps the last event's real time", item.contentDescription.orEmpty().contains(
            formats.timeRange(target.startMillis, target.endMillis)))
        captureMinor("day-short-events-last")
        item.click()
        awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == target.id }
        captureMinor("short-event-details")
        onMain { vm.back(); vm.open(Screen.Settings) }
        val localized = calendarContext()
        node(By.pkg(context.packageName).text(localized.getString(R.string.beta_settings_appearance))).click()
        scrollDialogToText(localized.getString(R.string.view_week)).click()
        scrollDialogToText(localized.getString(R.string.settings_week_continuous))
        captureMinor("settings-week-options")
        node(By.pkg(context.packageName).text(localized.getString(R.string.settings_week_continuous))).click()
        scrollDialogToText(localized.getString(R.string.settings_group_empty_hours))
        captureMinor("settings-group-empty-hours")
        onMain { vm.back(); vm.changeDayLayout("cards"); vm.changeViewMode(ViewMode.Day); vm.selectAndFocus(shown) }
        awaitUi { !vm.loadingEvents && vm.coversRange(shown, shown) }
        node(By.pkg(context.packageName).res("calendar_day_cards"))
        captureMinor("day-list")
        onMain { vm.changeWeekLayout("list"); vm.changeViewMode(ViewMode.Week); vm.select(shown) }
        awaitUi { !vm.loadingEvents && vm.coversRange(shown, shown) }
        node(By.pkg(context.packageName).res("calendar_agenda"))
        captureMinor("week-list")
    }

    @Test
    fun emptyHourGroupingDefaultsOffAndPersistsInIsolatedPreferences() {
        val preferenceName = "sunlith-customization-preferences-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                this@CalendarCustomizationTest.context.getSharedPreferences(preferenceName, mode)
        }
        try {
            val settings = SettingsStore(isolated)
            assertFalse("Empty hours must stay expanded for a fresh installation.", settings.groupEmptyHours)
            settings.groupEmptyHours = true
            settings.weekLayout = "continuous"
            val restored = SettingsStore(isolated)
            assertTrue("The opt-in must survive a new settings instance.", restored.groupEmptyHours)
            assertEquals("continuous", restored.weekLayout)
            restored.groupEmptyHours = false
            assertFalse("Turning grouping off must also persist.", SettingsStore(isolated).groupEmptyHours)
        } finally {
            assertTrue(context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit())
            assertTrue(context.deleteSharedPreferences(preferenceName))
        }
    }

    @Test
    fun groupedEmptyHoursKeepALateEventInAnotherTimelineColumnReachable() {
        val targetDay = date.plusDays(2)
        val lateId = addTimed(targetDay, "Other column evening class", 18, 19)
        assertFalse(vm.groupEmptyHours)
        onMain {
            vm.changeWeekLayout("timeline")
            vm.changeVisibleWeekDays(3)
            vm.changeViewMode(ViewMode.Week)
            vm.select(date)
            vm.changeGroupEmptyHours(true)
            vm.reload()
        }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == lateId } &&
            vm.events.any { it.id == firstId } }
        assertTrue(SettingsStore(context).groupEmptyHours)
        onMain { vm.select(targetDay) }
        awaitUi { visibleTimelineHeader(targetDay)?.let { it.isSelected || it.isChecked } == true }
        val event = revealTimelineEvent(lateId)
        val header = requireNotNull(visibleTimelineHeader(targetDay)).visibleBounds
        assertTrue("The evening event must remain in its own date column after grouping.",
            kotlin.math.abs(event.visibleBounds.centerX() - header.centerX()) <= 3 * context.resources.displayMetrics.density)
        event.click()
        awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == lateId }
        val opened = (vm.stack.last() as Screen.Details).event
        assertEquals("The grouped axis must preserve the event's real start time.",
            targetDay.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), opened.startMillis)
        assertEquals("Other column evening class", opened.title)
    }

    @Test
    fun twoFingerPinchKeepsTheFocusedMinuteAndLeavesSingleFingerScrollingAvailable() {
        val density = context.resources.displayMetrics.density
        assumeTrue("This gesture regression uses a normal-font phone viewport.",
            context.resources.configuration.fontScale <= 1.15f)
        val markerId = addTimed(date, "Pinch noon marker", 12, 13)
        // Grouped Week needs content past noon so preserving its focal minute is not limited by the scroll edge.
        val lateId = addTimed(date, "Pinch late axis extent marker", 20, 21)
        onMain { vm.reload(); vm.changeNavigationAnimation(true) }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == markerId } && vm.events.any { it.id == lateId } }
        listOf("week", "continuous").forEach { layout ->
            onMain {
                vm.changeWeekTimelineZoom(100)
                vm.changeGroupEmptyHours(layout == "week")
                vm.changeVisibleWeekDays(3)
                vm.changeWeekLayout(if (layout == "continuous") "continuous" else "timeline")
                vm.changeViewMode(ViewMode.Week)
                vm.select(date)
            }
            positionTimelineEvent(markerId)
            val selector = By.pkg(context.packageName).res("timeline_event_$markerId")
            val baseline = node(selector).visibleBounds
            val viewport = timelineScroller().visibleBounds
            val focalX = baseline.centerX().toFloat()
            val focalY = baseline.centerY().toFloat()
            val available = minOf(focalY - viewport.top, viewport.bottom - focalY) - 16 * density
            val halfSpan = minOf(40 * density, available / 1.9f)
            assertTrue("The real pinch needs room for two separate fingers: $layout $viewport $baseline",
                halfSpan >= 20 * density && baseline.height() > 20)
            val focusMinute = 720f + (focalY - baseline.top) * 60f / baseline.height()
            val selected = vm.selectedDate
            fun zoom() = vm.weekTimelineZoom
            fun persistedZoom() = SettingsStore(context).let {
                it.weekTimelineZoom
            }
            fun settledBounds(expectedZoom: Int): Rect {
                awaitUi(detail = { "layout=$layout zoom=${zoom()} expected=$expectedZoom" }) {
                    val bounds = device.findObject(selector)?.visibleBounds
                    bounds != null && bounds.top > viewport.top && bounds.bottom < viewport.bottom &&
                        kotlin.math.abs(bounds.height() - baseline.height() * expectedZoom / 100f) <= 4 * density
                }
                return node(selector).visibleBounds
            }
            fun assertFocalMinute(bounds: Rect) {
                val actual = 720f + (focalY - bounds.top) * 60f / bounds.height()
                assertTrue("The same minute must stay under the two-finger centroid: $layout before=$focusMinute after=$actual bounds=$bounds",
                    kotlin.math.abs(actual - focusMinute) <= 4f)
            }
            pinchVertically(focalX, focalY, halfSpan, halfSpan * 1.8f)
            awaitUi { zoom() in 125..185 }
            val expandedZoom = zoom()
            assertFocalMinute(settledBounds(expandedZoom))
            assertEquals("Pinch must save the active view's zoom.", expandedZoom, persistedZoom())
            assertEquals("Pinch must leave the other view's zoom unchanged.", 100,
                vm.dayTimelineZoom)
            assertEquals("A two-finger gesture must not page to another date.", selected, vm.selectedDate)
            assertTrue("A pinch starting on an event must not open Details.", vm.stack.isEmpty())
            assertEquals("A pinch must not open a new-event editor.", null, vm.editor)

            pinchVertically(focalX, focalY, halfSpan * 1.5f, halfSpan)
            awaitUi { zoom() in 75 until expandedZoom - 15 }
            val reducedZoom = zoom()
            val reduced = settledBounds(reducedZoom)
            assertFocalMinute(reduced)
            assertEquals(reducedZoom, persistedZoom())
            assertEquals(selected, vm.selectedDate)
            assertTrue(vm.stack.isEmpty())
            assertEquals(null, vm.editor)

            val gutterX = if (timelineIsRtl()) viewport.right - (12 * density).toInt() else viewport.left + (12 * density).toInt()
            val fromY = viewport.top + viewport.height() * 3 / 5
            val toY = fromY - minOf(viewport.height() / 5, (80 * density).toInt())
            dragTouch(gutterX, fromY, gutterX, toY)
            awaitUi {
                device.findObject(selector)?.visibleBounds?.let { kotlin.math.abs(it.centerY() - reduced.centerY()) > 12 * density } == true
            }
            assertEquals("Single-finger scrolling after a pinch must not change zoom.", reducedZoom, zoom())
            assertEquals("A vertical scroll must not page the timeline.", selected, vm.selectedDate)
            assertTrue(vm.stack.isEmpty())
        }
    }

    @Test
    fun immediateSingleFingerDragCancelsThePinchLimitSpringWithoutMovingBack() {
        val density = context.resources.displayMetrics.density
        assumeTrue("This regression needs a normal-font viewport and enabled system animation.",
            context.resources.configuration.fontScale <= 1.15f &&
                android.provider.Settings.Global.getFloat(context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
        val markerId = addTimed(date, "Pinch limit interruption noon marker", 12, 13)
        onMain { vm.reload(); vm.changeNavigationAnimation(true) }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == markerId } }
        listOf("week", "continuous").forEach { layout ->
            listOf(200, 50).forEach { limit ->
                onMain {
                    vm.changeWeekTimelineZoom(100)
                    vm.changeGroupEmptyHours(false)
                    vm.changeVisibleWeekDays(3)
                    vm.changeWeekLayout(if (layout == "continuous") "continuous" else "timeline")
                    vm.changeViewMode(ViewMode.Week)
                    vm.select(date)
                }
                positionTimelineEvent(markerId)
                val selector = By.pkg(context.packageName).res("timeline_event_$markerId")
                val before = node(selector).visibleBounds
                val viewport = timelineScroller().visibleBounds
                val focalX = before.centerX().toFloat()
                val focalY = before.centerY().toFloat()
                val available = minOf(focalY - viewport.top, viewport.bottom - focalY) - 16 * density
                val largeSpan = minOf(88 * density, available)
                val smallSpan = 24 * density
                assertTrue("Both limit gestures must have room to pass the saved range.", largeSpan > smallSpan * 3)
                val gutterX = if (timelineIsRtl()) viewport.right - (12 * density).toInt() else viewport.left + (12 * density).toInt()
                val dragDistance = (64 * density).toInt()
                val dragFrom = before.centerY()
                val dragTo = dragFrom - dragDistance
                val selected = vm.selectedDate
                var dragStartedAfterRelease = Long.MAX_VALUE
                pinchVertically(focalX, focalY,
                    if (limit == 200) smallSpan else largeSpan,
                    if (limit == 200) largeSpan else smallSpan,
                    afterRelease = { releasedAt ->
                        // Let the release reach one frame, then interrupt its still-running spring without an idle wait.
                        SystemClock.sleep(16)
                        dragStartedAfterRelease = SystemClock.uptimeMillis() - releasedAt
                        dragTouch(gutterX, dragFrom, gutterX, dragTo, steps = 8, stepDelayMs = 8, holdMillis = 300)
                    })
                assertTrue("The next finger must interrupt the spring, rather than wait for it to settle.", dragStartedAfterRelease < 100)
                fun zoom() = vm.weekTimelineZoom
                awaitUi { zoom() == limit }
                val after = node(selector).visibleBounds
                val moved = before.centerY() - after.centerY()
                val expectedHeight = before.height() * limit / 100f
                assertTrue("A limit spring must stop fighting the next scroll: $layout limit=$limit movement=$moved expected=$dragDistance before=$before after=$after",
                    kotlin.math.abs(moved - dragDistance) <= 16 * density)
                // Dense Week targets stay within their painted lanes instead of covering adjacent events.
                val expectedTargetHeight = expectedHeight
                val paintedHeight = paintedTimelineBlockHeight(selector)
                val correctHeight = kotlin.math.abs(after.height() - expectedTargetHeight) <= 4 * density &&
                    kotlin.math.abs(paintedHeight - expectedHeight) <= 4 * density
                if (!correctHeight) {
                    assertTrue(device.takeScreenshot(File(context.filesDir, "pinch-limit-height-$layout-$limit.png")))
                    device.dumpWindowHierarchy(File(context.filesDir, "pinch-limit-height-$layout-$limit.xml"))
                }
                assertTrue("The interrupted pinch must render the saved limit, without leaving live scale behind: " +
                    "$layout limit=$limit saved=${zoom()} before=$before after=$after expectedHeight=$expectedHeight " +
                    "expectedTargetHeight=$expectedTargetHeight paintedHeight=$paintedHeight " +
                    "density=$density viewport=$viewport releaseDelay=$dragStartedAfterRelease",
                    correctHeight)
                val saved = SettingsStore(context)
                assertEquals(limit, saved.weekTimelineZoom)
                SystemClock.sleep(450)
                refreshAccessibility()
                val later = node(selector).visibleBounds
                assertTrue("An old zoom animation must not pull the viewport back after the drag.",
                    kotlin.math.abs(later.centerY() - after.centerY()) <= 3 * density)
                assertTrue(kotlin.math.abs(later.height() - after.height()) <= 2 * density)
                assertEquals("The interruption must preserve the focused date.", selected, vm.selectedDate)
                assertTrue("Pinch and its following drag must not open Details.", vm.stack.isEmpty())
                assertEquals(null, vm.editor)
            }
        }
    }

    @Test
    fun cancelledPinchesSaveTheirVisibleScaleAndReplacingAFingerDoesNotJumpZoom() {
        val density = context.resources.displayMetrics.density
        assumeTrue("These pointer regressions use a normal-font phone viewport.", context.resources.configuration.fontScale <= 1.15f)
        val markerId = addTimed(date, "Interrupted pinch noon marker", 12, 13)
        onMain { vm.reload(); vm.changeNavigationAnimation(true) }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == markerId } }
        listOf("week", "continuous").forEach { layout ->
            fun zoom() = vm.weekTimelineZoom
            fun savedZoom() = SettingsStore(context).let { it.weekTimelineZoom }
            listOf(false, true).forEach { replacement ->
                onMain {
                    vm.changeWeekTimelineZoom(100)
                    vm.changeGroupEmptyHours(false)
                    vm.changeVisibleWeekDays(3)
                    vm.changeWeekLayout(if (layout == "continuous") "continuous" else "timeline")
                    vm.changeViewMode(ViewMode.Week)
                    vm.select(date)
                }
                positionTimelineEvent(markerId)
                val selector = By.pkg(context.packageName).res("timeline_event_$markerId")
                val before = node(selector).visibleBounds
                val viewport = timelineScroller().visibleBounds
                val x = before.centerX().toFloat()
                val y = before.centerY().toFloat()
                assertTrue("The replacement finger must stay inside the actual timeline.",
                    y - 90 * density > viewport.top && y + 90 * density < viewport.bottom)
                if (replacement) pinchReplacingSecondFinger(x, y, 32 * density)
                else pinchVertically(x, y, 32 * density, 48 * density, cancel = true)
                val expectedZoom = if (replacement) 130..165 else 110..135
                awaitUi { zoom() in expectedZoom }
                val committed = zoom()
                assertEquals("An interrupted gesture must persist its final visible zoom.", committed, savedZoom())
                awaitUi {
                    device.findObject(selector)?.visibleBounds?.let {
                        kotlin.math.abs(it.height() - before.height() * committed / 100f) <= 4 * density
                    } == true
                }
                val after = node(selector).visibleBounds
                captureFinal("pinch-$layout-${if (replacement) "finger-replacement" else "cancelled"}")
                assertTrue("Changing fingers or cancelling must not remap the viewport to an unrelated time: $layout replacement=$replacement before=$before after=$after",
                    kotlin.math.abs(after.centerY() - before.centerY()) <= 12 * density)
                SystemClock.sleep(450)
                refreshAccessibility()
                val later = node(selector).visibleBounds
                assertTrue("No cancelled pointer handler may keep changing the hour scale.",
                    kotlin.math.abs(later.height() - after.height()) <= 2 * density)
                val gutterX = if (timelineIsRtl()) viewport.right - (12 * density).toInt() else viewport.left + (12 * density).toInt()
                dragTouch(gutterX, viewport.centerY(), gutterX, viewport.centerY() - (64 * density).toInt())
                awaitUi { device.findObject(selector)?.visibleBounds?.let { it.centerY() < later.centerY() - 16 * density } == true }
                assertEquals("The next single-finger drag must leave the saved zoom alone.", committed, zoom())
                assertEquals(date, vm.selectedDate)
                assertTrue(vm.stack.isEmpty())
                assertEquals(null, vm.editor)
            }
        }
    }

    @Test
    fun todayRequestStopsDrivingTheTimelineWhenAPinchTakesOver() {
        val density = context.resources.displayMetrics.density
        val targetHour = (LocalTime.now().hour - 1).coerceAtLeast(0)
        assumeTrue("This check exercises real timeline motion at normal text size.",
            context.resources.configuration.fontScale <= 1.15f &&
                android.provider.Settings.Global.getFloat(context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f && targetHour in 2..17)
        val today = LocalDate.now()
        val markerHour = if (targetHour >= 10) 4 else 17
        val markerId = addTimed(today, "Today interrupted by pinch distant marker", markerHour, markerHour + 1)
        val todayId = addTimed(today, "Today expected hour marker", targetHour, targetHour + 1)
        onMain { vm.reload(); vm.changeNavigationAnimation(true) }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == markerId } && vm.events.any { it.id == todayId } }
        listOf("week", "continuous").forEach { layout ->
            onMain {
                vm.changeWeekTimelineZoom(100)
                vm.changeGroupEmptyHours(false)
                vm.changeVisibleWeekDays(3)
                vm.changeWeekLayout(if (layout == "continuous") "continuous" else "timeline")
                vm.changeViewMode(ViewMode.Week)
                vm.select(today)
            }
            positionTimelineEvent(markerId)
            val selector = By.pkg(context.packageName).res("timeline_event_$markerId")
            val todaySelector = By.pkg(context.packageName).res("timeline_event_$todayId")
            val manual = node(selector).visibleBounds
            val viewport = timelineScroller().visibleBounds
            val manualTopMinute = markerHour * 60f - (manual.top - viewport.top) * 60f / manual.height()
            assertTrue("This fixture must start far enough away that Today has real work to do.",
                kotlin.math.abs(manualTopMinute - targetHour * 60f) >= 120f)
            onMain { vm.goToday() }
            awaitUi {
                device.findObject(todaySelector)?.visibleBounds?.let {
                    kotlin.math.abs(it.top - timelineScroller().visibleBounds.top) <= 5 * density && it.height() > 20
                } == true
            }
            positionTimelineEvent(markerId)
            val before = node(selector).visibleBounds
            val focalY = before.centerY().toFloat()
            val focusMinute = markerHour * 60f + (focalY - before.top) * 60f / before.height()
            val request = vm.todayRequest
            if (layout == "continuous") {
                // Continuous Today is immediate. Request it after both fingers arrive, before scale movement,
                // so this checks a pending jump yielding to touch rather than a jump that already finished.
                pinchVertically(before.centerX().toFloat(), focalY, 32 * density, 48 * density,
                    onPointersDown = { instrumentation.runOnMainSync { vm.goToday() } })
            } else {
                // Deliberately do not wait for idle: the two fingers interrupt the running Today animation.
                instrumentation.runOnMainSync { vm.goToday() }
                pinchVertically(before.centerX().toFloat(), focalY, 32 * density, 48 * density)
            }
            fun zoom() = vm.weekTimelineZoom
            awaitUi { zoom() in 110..135 }
            val after = node(selector).visibleBounds
            captureFinal("today-then-pinch-$layout")
            val actualMinute = markerHour * 60f + (focalY - after.top) * 60f / after.height()
            assertTrue("Today must yield to the minute held by the pinch: $layout before=$focusMinute after=$actualMinute bounds=$after",
                kotlin.math.abs(actualMinute - focusMinute) <= 5f)
            SystemClock.sleep(450)
            refreshAccessibility()
            val later = node(selector).visibleBounds
            assertTrue("The interrupted Today animation must not resume after pinch release.",
                kotlin.math.abs(later.centerY() - after.centerY()) <= 3 * density)
            assertEquals(request + 1, vm.todayRequest)
            assertEquals(today, vm.selectedDate)
            assertTrue(vm.stack.isEmpty())
            assertEquals(null, vm.editor)
        }
    }

    @Test
    fun todayImmediatelyAfterPinchReleaseWinsOverTheRunningZoomSpring() {
        val density = context.resources.displayMetrics.density
        val targetHour = (LocalTime.now().hour - 1).coerceAtLeast(0)
        assumeTrue("This check needs a normal-font viewport, an unclamped Today hour, and enabled animation.",
            context.resources.configuration.fontScale <= 1.15f && targetHour in 2..17 &&
                android.provider.Settings.Global.getFloat(context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
        val today = LocalDate.now()
        val distantHour = if (targetHour >= 10) 4 else 17
        val markerId = addTimed(today, "Pinch spring distant hour marker", distantHour, distantHour + 1)
        val todayId = addTimed(today, "Pinch spring Today target marker", targetHour, targetHour + 1)
        onMain { vm.reload() }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == markerId } && vm.events.any { it.id == todayId } }
        listOf("week", "continuous").forEach { layout ->
            showGestureTimeline(layout, today)
            positionTimelineEvent(markerId)
            val selector = By.pkg(context.packageName).res("timeline_event_$markerId")
            val target = By.pkg(context.packageName).res("timeline_event_$todayId")
            val before = node(selector).visibleBounds
            val viewport = timelineScroller().visibleBounds
            val available = minOf(before.centerY() - viewport.top, viewport.bottom - before.centerY()) - 16 * density
            val largeSpan = minOf(88 * density, available)
            assertTrue("The released pinch must cross the upper limit to start its spring.", largeSpan > 72 * density)
            val request = vm.todayRequest
            pinchVertically(before.centerX().toFloat(), before.centerY().toFloat(), 24 * density, largeSpan,
                afterRelease = { instrumentation.runOnMainSync { vm.goToday() } })
            fun zoom() = vm.weekTimelineZoom
            awaitUi {
                zoom() == 200 && device.findObject(target)?.visibleBounds?.let {
                    kotlin.math.abs(it.top - timelineScroller().visibleBounds.top) <= 5 * density && it.height() > 20
                } == true
            }
            val after = node(target).visibleBounds
            captureFinal("pinch-release-then-today-$layout")
            SystemClock.sleep(450)
            refreshAccessibility()
            val later = node(target).visibleBounds
            assertTrue("The old spring must not reclaim the earlier focal hour after Today: $layout after=$after later=$later",
                kotlin.math.abs(later.top - after.top) <= 3 * density)
            assertTrue("The final visible hour must remain the actual now-minus-one-hour target.",
                kotlin.math.abs(later.top - timelineScroller().visibleBounds.top) <= 5 * density)
            assertEquals(request + 1, vm.todayRequest)
            assertEquals(today, vm.selectedDate)
            assertTrue(vm.stack.isEmpty())
            assertEquals(null, vm.editor)
        }
    }

    @Test
    fun restartingPointerInputDuringAnActivePinchCommitsTheShownZoomAndLeavesGesturesUsable() {
        val density = context.resources.displayMetrics.density
        assumeTrue("This pointer restart check needs normal text size and enabled system animation.",
            context.resources.configuration.fontScale <= 1.15f &&
                android.provider.Settings.Global.getFloat(context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
        val markerId = addTimed(date, "Pointer restart noon marker", 12, 13)
        onMain { vm.reload() }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == markerId } }
        listOf("week", "continuous").forEach { layout ->
            showGestureTimeline(layout, date)
            positionTimelineEvent(markerId)
            val selector = By.pkg(context.packageName).res("timeline_event_$markerId")
            val before = node(selector).visibleBounds
            fun zoom() = vm.weekTimelineZoom
            pinchVertically(before.centerX().toFloat(), before.centerY().toFloat(), 32 * density, 48 * density,
                whilePointersDown = {
                    assertEquals("The saved preference must wait until the live gesture ends or its handler restarts.", 100, zoom())
                    instrumentation.runOnMainSync { vm.changeNavigationAnimation(false) }
                    awaitUi { zoom() in 110..135 }
                })
            val committed = zoom()
            assertEquals(committed, SettingsStore(context).let { it.weekTimelineZoom })
            awaitUi { device.findObject(selector)?.visibleBounds?.let {
                kotlin.math.abs(it.height() - before.height() * committed / 100f) <= 4 * density
            } == true }
            val after = node(selector).visibleBounds
            assertTrue("Restarting pointer input must keep the visible focal time.",
                kotlin.math.abs(after.centerY() - before.centerY()) <= 8 * density)
            pinchVertically(after.centerX().toFloat(), after.centerY().toFloat(), 48 * density, 24 * density)
            awaitUi { zoom() in 60..100 }
            val next = node(selector).visibleBounds
            val viewport = timelineScroller().visibleBounds
            val gutterX = if (timelineIsRtl()) viewport.right - (12 * density).toInt() else viewport.left + (12 * density).toInt()
            dragTouch(gutterX, viewport.centerY(), gutterX, viewport.centerY() - (64 * density).toInt())
            awaitUi { device.findObject(selector)?.visibleBounds?.let { it.centerY() < next.centerY() - 16 * density } == true }
            assertEquals(date, vm.selectedDate)
            assertTrue(vm.stack.isEmpty())
            assertEquals(null, vm.editor)
            captureFinal("active-pointer-input-restart-$layout")
        }
    }

    @Test
    fun aNewWeekPinchTakesOverBeforeThePreviousLimitSpringFinishes() {
        val density = context.resources.displayMetrics.density
        assumeTrue("This check exercises the running spring at normal text size.",
            context.resources.configuration.fontScale <= 1.15f &&
                android.provider.Settings.Global.getFloat(context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
        val markerId = addTimed(date, "Second pinch takes over marker", 12, 13)
        onMain { vm.reload() }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == markerId } }
        listOf("week", "continuous").forEach { layout ->
            showGestureTimeline(layout, date)
            positionTimelineEvent(markerId)
            val selector = By.pkg(context.packageName).res("timeline_event_$markerId")
            val before = node(selector).visibleBounds
            val area = timelineScroller().visibleBounds
            val available = minOf(before.centerY() - area.top, area.bottom - before.centerY()) - 16 * density
            val largeSpan = minOf(88 * density, available)
            assertTrue(largeSpan > 72 * density)
            pinchVertically(before.centerX().toFloat(), before.centerY().toFloat(), 24 * density, largeSpan,
                afterRelease = { releasedAt ->
                    assertEquals("The first pinch must have saved its upper limit", 200, vm.weekTimelineZoom)
                    assertTrue("The second pinch must start before the first spring finishes", SystemClock.uptimeMillis() - releasedAt < 100)
                    pinchVertically(before.centerX().toFloat(), before.centerY().toFloat(), 48 * density, 24 * density)
                })
            awaitUi { vm.weekTimelineZoom in 115..155 }
            val settled = node(selector).visibleBounds
            assertTrue("The next pinch must preserve the focal time", kotlin.math.abs(settled.centerY() - before.centerY()) <= 12 * density)
            val saved = vm.weekTimelineZoom
            assertEquals(saved, SettingsStore(context).weekTimelineZoom)
            SystemClock.sleep(450)
            val later = node(selector).visibleBounds
            assertEquals(saved, vm.weekTimelineZoom)
            assertTrue("The old spring must not reclaim the scale", kotlin.math.abs(later.height() - settled.height()) <= 2 * density)
            assertTrue(vm.stack.isEmpty())
            assertEquals(null, vm.editor)
            captureFinal("second-week-pinch-during-spring-$layout")
        }
    }

    @Test
    fun dayAndMonthDoNotReactToPinchZoom() {
        val density = context.resources.displayMetrics.density
        val markerId = addTimed(date, "Day fixed scale marker", 12, 13)
        onMain { vm.reload() }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == markerId } }
        showGestureTimeline("day", date)
        positionTimelineEvent(markerId)
        val selector = By.pkg(context.packageName).res("timeline_event_$markerId")
        val before = node(selector).visibleBounds
        val daySelected = vm.selectedDate
        onMain { vm.changeSwipeNavigation(true) }
        pinchVertically(before.centerX().toFloat(), before.centerY().toFloat(), 32 * density, 80 * density)
        val after = node(selector).visibleBounds
        assertTrue("Day event height must stay fixed after a deliberate pinch", kotlin.math.abs(after.height() - before.height()) <= 2 * density)
        assertTrue("An unsupported Day pinch must not scroll the event", kotlin.math.abs(after.top - before.top) <= 2 * density)
        assertEquals(daySelected, vm.selectedDate)
        assertTrue(vm.stack.isEmpty())
        assertEquals(null, vm.editor)
        assertEquals(100, vm.weekTimelineZoom)
        assertEquals(100, vm.dayTimelineZoom)
        assertEquals(ViewMode.Day, vm.viewMode)
        captureFinal("day-ignores-two-finger-gestures")
        onMain { vm.changeMonthLayout("full"); vm.changeViewMode(ViewMode.Month) }
        val month = node(By.pkg(context.packageName).res("calendar_full_month")).visibleBounds
        val selected = vm.selectedDate
        pinchVertically(month.centerX().toFloat(), month.centerY().toFloat(), 24 * density, 60 * density)
        assertEquals(ViewMode.Month, vm.viewMode)
        assertEquals(selected, vm.selectedDate)
        assertEquals(100, vm.weekTimelineZoom)
        assertTrue(vm.stack.isEmpty())
        assertEquals(null, vm.editor)
        captureFinal("month-ignores-two-finger-gestures")
        node(day(selected)).click()
        awaitUi { vm.viewMode == ViewMode.Day }
        assertEquals("Ordinary date taps must still open that day", selected, vm.selectedDate)
    }

    @Test
    fun weekPinchIgnoresSmallSpanChangesBeforeADeliberateGesture() {
        val density = context.resources.displayMetrics.density
        val markerId = addTimed(date, "Deliberate week zoom marker", 12, 13)
        onMain { vm.reload() }
        awaitUi { !vm.loadingEvents && vm.events.any { it.id == markerId } }
        listOf("week", "continuous").forEach { layout ->
            showGestureTimeline(layout, date)
            positionTimelineEvent(markerId)
            val selector = By.pkg(context.packageName).res("timeline_event_$markerId")
            val before = node(selector).visibleBounds
            pinchVertically(before.centerX().toFloat(), before.centerY().toFloat(), 40 * density, 43 * density)
            assertEquals("Small two-finger movements must not save a new zoom", 100, vm.weekTimelineZoom)
            assertEquals(100, SettingsStore(context).weekTimelineZoom)
            val unchanged = node(selector).visibleBounds
            assertTrue("Small movements must not change the live scale either", kotlin.math.abs(unchanged.height() - before.height()) <= 2 * density)
            assertTrue(vm.stack.isEmpty())
            assertEquals(null, vm.editor)
            pinchVertically(unchanged.centerX().toFloat(), unchanged.centerY().toFloat(), 32 * density, 64 * density)
            awaitUi { vm.weekTimelineZoom in 135..175 }
            assertEquals(vm.weekTimelineZoom, SettingsStore(context).weekTimelineZoom)
            captureFinal("deliberate-week-pinch-$layout")
        }
    }

    @Test
    fun shortDayEventsShareOneCompactCardWithAVisibleCountAtEveryTextSizeAndDirection() {
        val zone = ZoneId.systemDefault()
        val shown = date.plusWeeks(2)
        fun timed(id: Long, title: String, from: Int, to: Int, room: String = "") = CalendarEvent(id, calendarId, title, "", room,
            shown.atStartOfDay(zone).plusMinutes(from.toLong()).toInstant().toEpochMilli(),
            shown.atStartOfDay(zone).plusMinutes(to.toLong()).toInstant().toEpochMilli(), false, zone.id)
        // The user's screenshot: 1 minute events at 18:00, 18:15 and 18:20, then an ordinary class at 19:00.
        val trio = listOf(timed(-601, "Short title 1", 18 * 60, 18 * 60 + 1),
            timed(-602, "Short title 2", 18 * 60 + 15, 18 * 60 + 16, "B-1234"),
            timed(-603, "Short title 3", 18 * 60 + 20, 18 * 60 + 21))
        val evening = timed(-604, "Evening class", 19 * 60, 20 * 60)
        val events = trio + evening
        val formats = Formats(english, Locale.ENGLISH)
        val fittedDensity = device.displayWidth / 390f
        data class Case(val density: Float, val fontScale: Float, val rtl: Boolean)
        listOf(Case(fittedDensity, 1f, false), Case(fittedDensity, 1.15f, false), Case(fittedDensity, 2f, false),
            Case(2.625f, 1.15f, false), Case(2.625f, 2f, false), Case(fittedDensity, 1.15f, true)).forEach { case ->
            val screenDensity = case.density
            val focus = mutableStateOf<TimelineFocus?>(TimelineFocus(shown, 1))
            val scroll = ScrollState(0)
            val opened = AtomicReference<CalendarEvent?>(null)
            val listed = AtomicReference<List<CalendarEvent>?>(null)
            val popup = mutableStateOf(emptyList<CalendarEvent>())
            onMain {
                requireNotNull(activity).setContent {
                    SunlithTheme("light", false) {
                        CompositionLocalProvider(LocalDensity provides Density(screenDensity, case.fontScale),
                            LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding()
                                .semantics { testTagsAsResourceId = true }) {
                                Timeline(listOf(shown), { events }, shown, date, formats, false, scroll,
                                    autoScroll = true, todayRequest = 0, motion = false, onSelectDay = {}, onOpen = { opened.set(it) }, onNew = {},
                                    onOverflow = { _, list -> listed.set(list); popup.value = list },
                                    focus = focus.value, onFocusHandled = { focus.value = null }, fallback = {})
                            }
                            if (popup.value.isNotEmpty()) TimelineEventList(popup.value, formats, onOpen = { opened.set(it) }) { popup.value = emptyList() }
                        }
                    }
                }
            }
            awaitUi { focus.value == null && scroll.viewportSize > 0 }
            val group = node(By.pkg(context.packageName).res("timeline_event_${trio[0].id}"))
            val spoken = group.contentDescription.orEmpty()
            trio.forEach { event ->
                assertTrue("The group card names ${event.title} with its real start and end: $spoken", spoken.contains(event.title) &&
                    spoken.contains(formats.time(event.startMillis)) && spoken.contains(formats.time(event.endMillis)))
            }
            assertTrue("The group card names the room of a grouped event: $spoken", spoken.contains("B-1234"))
            assertFalse("The 19:00 class is not hidden in the group: $spoken", spoken.contains(evening.title))
            trio.drop(1).forEach { event ->
                assertEquals("${event.title} is shown by the one compact card, not by a row or lane of its own", null,
                    device.findObject(By.pkg(context.packageName).res("timeline_event_${event.id}")))
            }
            assertPaintedTimelineTitle(group, trio[0])
            val em = 11 * screenDensity * case.fontScale
            val name = "short-day-group-${(case.fontScale * 100).roundToInt()}-${(screenDensity * 1000).roundToInt()}" +
                if (case.rtl) "-rtl" else ""
            val cards = assertSeparatedRoundedCards(name, listOf(trio[0], evening), screenDensity) { cards ->
                // Layout bounds, since the focus ring on the first event insets its sampled fill.
                val (card, later) = cards.map { it.target }
                val hourPx = 64 * screenDensity
                assertEquals("The class starts one real hour below the 18:00 group: $card / $later",
                    hourPx, (later.top - card.top).toFloat(), 3f)
                assertTrue("The group is one title line tall, never one row per event: $card / $later",
                    card.height() < later.height() && card.bottom <= later.top)
                assertEquals("The group and the class share one full width, never a staircase: $card / $later",
                    card.width().toFloat(), later.width().toFloat(), 2f)
                assertCountBesideTitle(name, cards[0], case.rtl, screenDensity, em)
                assertReadableTitle(cards[1], em)
            }
            captureFinal(name)
            listed.set(null)
            opened.set(null)
            assertTrue(device.click(cards[0].bounds.centerX(), cards[0].bounds.centerY()))
            awaitUi { opened.get() == null && listed.get() == trio }
            fun listItem(event: CalendarEvent): UiObject2? = device.findObjects(By.pkg(context.packageName).descContains(event.title))
                .firstOrNull { item -> trio.count { item.contentDescription.orEmpty().contains(it.title) } == 1 }
            trio.forEach { event ->
                awaitUi { listItem(event) != null }
                val description = requireNotNull(listItem(event)).contentDescription.orEmpty()
                assertTrue("The list shows ${event.title} with its real start and end: $description",
                    description.contains(formats.time(event.startMillis)) && description.contains(formats.time(event.endMillis)))
                if (event.location.isNotBlank()) assertTrue("The list keeps the room: $description", description.contains(event.location))
            }
            captureFinal("$name-list")
            requireNotNull(listItem(trio[1])).click()
            awaitUi { opened.get() == trio[1] && popup.value.isEmpty() }
            opened.set(null)
            assertTrue(device.click(cards[1].bounds.centerX(), cards[1].bounds.centerY()))
            awaitUi { opened.get() == evening }
        }
    }

    @Test
    fun aLongClassBesideShortEventsKeepsItsCardAndOnlyTheShortLaneShares() {
        val zone = ZoneId.systemDefault()
        val shown = date.plusWeeks(2)
        fun timed(id: Long, title: String, from: Int, to: Int) = CalendarEvent(id, calendarId, title, "", "",
            shown.atStartOfDay(zone).plusMinutes(from.toLong()).toInstant().toEpochMilli(),
            shown.atStartOfDay(zone).plusMinutes(to.toLong()).toInstant().toEpochMilli(), false, zone.id)
        // A 9:00 to 10:00 class overlaps 9:00 to 9:10 and 9:15 to 9:16 events; three events at 15:00 really overlap.
        val long = timed(-621, "Long class", 9 * 60, 10 * 60)
        val first = timed(-622, "Quick check", 9 * 60, 9 * 60 + 10)
        val second = timed(-623, "Second check", 9 * 60 + 15, 9 * 60 + 16)
        val together = (1..3).map { timed(-630L - it, "Same minute $it", 15 * 60, 15 * 60 + 1) }
        val events = listOf(long, first, second) + together
        val formats = Formats(english, Locale.ENGLISH)
        val screenDensity = device.displayWidth / 390f
        listOf(1f, 2f).forEach { fontScale ->
            val focus = mutableStateOf<TimelineFocus?>(TimelineFocus(shown, 1))
            val scroll = ScrollState(0)
            val opened = AtomicReference<CalendarEvent?>(null)
            val listed = AtomicReference<List<CalendarEvent>?>(null)
            onMain {
                requireNotNull(activity).setContent {
                    SunlithTheme("light", false) {
                        CompositionLocalProvider(LocalDensity provides Density(screenDensity, fontScale)) {
                            Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding()
                                .semantics { testTagsAsResourceId = true }) {
                                Timeline(listOf(shown), { events }, shown, date, formats, false, scroll,
                                    autoScroll = true, todayRequest = 0, motion = false, onSelectDay = {}, onOpen = { opened.set(it) }, onNew = {},
                                    onOverflow = { _, list -> listed.set(list) },
                                    focus = focus.value, onFocusHandled = { focus.value = null }, fallback = {})
                            }
                        }
                    }
                }
            }
            awaitUi { focus.value == null && scroll.viewportSize > 0 }
            assertEquals("The 9:15 event shares the short lane card", null,
                device.findObject(By.pkg(context.packageName).res("timeline_event_${second.id}")))
            val spoken = node(By.pkg(context.packageName).res("timeline_event_${first.id}")).contentDescription.orEmpty()
            listOf(first, second).forEach { event ->
                assertTrue("The short lane card names ${event.title} with its real time: $spoken",
                    spoken.contains(event.title) && spoken.contains(formats.timeRange(event.startMillis, event.endMillis)))
            }
            assertFalse("The long class keeps its own card: $spoken", spoken.contains(long.title))
            val em = 11 * screenDensity * fontScale
            val name = "day-long-beside-short-${(fontScale * 100).roundToInt()}"
            val cards = assertSeparatedRoundedCards(name, listOf(long, first), screenDensity) { cards ->
                val (classCard, shortCard) = cards.map { it.target }
                val minutePx = 64 * screenDensity / 60f
                assertEquals("The long class keeps its real hour: $classCard", 60 * minutePx - 2 * screenDensity, classCard.height().toFloat(), 3f)
                assertTrue("The class and the short lane start on the same line: $classCard / $shortCard",
                    kotlin.math.abs(classCard.top - shortCard.top) <= 1)
                assertTrue("Two real lanes side by side: $classCard / $shortCard",
                    classCard.right <= shortCard.left || shortCard.right <= classCard.left)
                assertTrue("The short lane card stays inside the class's hour: $classCard / $shortCard", shortCard.bottom <= classCard.bottom + 1)
                assertReadableTitle(cards[0], em)
                assertCountBesideTitle(name, cards[1], false, screenDensity, em)
            }
            captureFinal(name)
            opened.set(null)
            assertTrue(device.click(cards[0].bounds.centerX(), cards[0].bounds.centerY()))
            awaitUi { opened.get() == long && listed.get() == null }
            assertTrue(device.click(cards[1].bounds.centerX(), cards[1].bounds.centerY()))
            awaitUi { listed.get() == listOf(first, second) }
            positionTimelineEvent(together[0].id)
            val lanes = assertSeparatedRoundedCards("$name-same-minute", together, screenDensity) { lanes ->
                val targets = lanes.map { it.target }.sortedBy { it.left }
                assertTrue("Events at the same minute share one top line: $targets", targets.maxOf { it.top } - targets.minOf { it.top } <= 1)
                targets.zipWithNext().forEach { (a, b) -> assertTrue("They sit side by side in three real lanes: $targets", a.right <= b.left) }
                lanes.forEach { assertReadableTitle(it, em) }
            }
            captureFinal("$name-same-minute")
            assertEquals(3, lanes.size)
        }
    }

    @Test
    fun ordinaryDayKeepsFullWidthCardsAtRealTimesAndFiveSimultaneousEventsSideBySide() {
        val zone = ZoneId.systemDefault()
        val shown = date.plusWeeks(2)
        fun timed(id: Long, title: String, from: Int, to: Int) = CalendarEvent(id, calendarId, title, "", "",
            shown.atStartOfDay(zone).plusMinutes(from.toLong()).toInstant().toEpochMilli(),
            shown.atStartOfDay(zone).plusMinutes(to.toLong()).toInstant().toEpochMilli(), false, zone.id)
        // A normal day: a class, a back to back break, a study hour, five meetings at the exact same time, then lunch.
        val sparse = listOf(
            timed(-901, "Morning class", 9 * 60, 10 * 60),
            timed(-902, "Coffee break", 10 * 60, 10 * 60 + 30),
            timed(-903, "Study session", 11 * 60, 12 * 60),
            timed(-904, "Lunch", 14 * 60, 14 * 60 + 30),
        )
        val meetings = (1..5).map { timed(-910L - it, "Meeting $it", 12 * 60 + 30, 13 * 60 + 30) }
        val everything = sparse + meetings
        val formats = Formats(english, Locale.ENGLISH)
        val screenDensity = device.displayWidth / 390f
        listOf(1f, 1.15f).forEach { fontScale ->
            val opened = AtomicReference<CalendarEvent?>(null)
            val listed = AtomicReference<List<CalendarEvent>?>(null)
            val focus = mutableStateOf<TimelineFocus?>(TimelineFocus(shown, 1))
            val scroll = ScrollState(0)
            onMain {
                requireNotNull(activity).setContent {
                    SunlithTheme("light", false) {
                        CompositionLocalProvider(LocalDensity provides Density(screenDensity, fontScale)) {
                            Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding()
                                .semantics { testTagsAsResourceId = true }) {
                                Timeline(listOf(shown), { everything }, shown, date, formats, false, scroll,
                                    autoScroll = true, todayRequest = 0, motion = false, onSelectDay = {}, onOpen = { opened.set(it) }, onNew = {},
                                    onOverflow = { _, list -> listed.set(list) },
                                    focus = focus.value, onFocusHandled = { focus.value = null }, fallback = {})
                            }
                        }
                    }
                }
            }
            awaitUi { focus.value == null && scroll.viewportSize > 0 }
            val holders = cardNamingEachEvent(everything, formats)
            everything.forEach { assertEquals("${it.title} keeps its own card on a phone Day", it, holders.getValue(it)) }
            val name = "day-normal-five-simultaneous-${(fontScale * 100).roundToInt()}"
            val cards = assertSeparatedRoundedCards(name, everything, screenDensity) { cards ->
                // Layout bounds, since the focus ring on the first event insets its sampled fill.
                val target = cards.associate { it.event.id to it.target }
                val minutePx = 64 * screenDensity / 60f
                val inset = 2 * screenDensity
                fun start(e: CalendarEvent) = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone).let { it.hour * 60 + it.minute }
                val origin = target.getValue(sparse[0].id).top - 1 * screenDensity
                everything.forEach { e ->
                    val t = target.getValue(e.id)
                    val minutes = ((e.endMillis - e.startMillis) / 60_000L).toInt()
                    assertEquals("${e.title} must start at its real time on the normal hour scale: $t",
                        origin + (start(e) - 9 * 60) * minutePx, t.top.toFloat(), 3f)
                    assertEquals("${e.title} must be exactly as tall as its real duration: $t",
                        minutes * minutePx - inset, t.height().toFloat(), 3f)
                }
                val full = sparse.map { target.getValue(it.id) }
                assertTrue("Ordinary events must share the same full width: $full",
                    full.maxOf { it.left } - full.minOf { it.left } <= 1 && full.maxOf { it.right } - full.minOf { it.right } <= 1)
                val lanes = meetings.map { target.getValue(it.id) }.sortedBy { it.left }
                assertTrue("Simultaneous meetings must start and end on the same lines: $lanes",
                    lanes.maxOf { it.top } - lanes.minOf { it.top } <= 1 && lanes.maxOf { it.bottom } - lanes.minOf { it.bottom } <= 1)
                lanes.zipWithNext().forEach { (a, b) -> assertTrue("Meetings must sit side by side, not on top of each other: $lanes", a.right <= b.left) }
                assertTrue("The meetings must share the same full width as ordinary events: $lanes / $full",
                    lanes.first().left - full.first().left <= 3 * screenDensity && full.first().right - lanes.last().right <= 3 * screenDensity)
                full.forEach { assertTrue("An ordinary event must not be narrowed into a lane: $it / $lanes", it.width() > 4 * lanes.first().width()) }
                cards.forEach { assertReadableTitle(it, 11 * screenDensity * fontScale) }
                cards.forEach { card ->
                    val before = card.inkLeft - card.bounds.left
                    val after = card.bounds.right - card.inkRight
                    if (card.event in meetings) assertTrue("A narrow meeting lane centers its wrapped title: before=$before after=$after ${card.bounds}",
                        kotlin.math.abs(before - after) <= maxOf(4 * screenDensity, card.bounds.width() * 0.12f))
                    else assertTrue("An ordinary full width card keeps start aligned text: before=$before after=$after ${card.bounds}",
                        before * 3 < after)
                }
            }
            captureFinal(name)
            assertCardsOpenTheirEvents(cards, holders, opened, listed)
        }
    }

    @Test
    fun sevenDayWeekShowsSimultaneousEventsAsReadableCardsOrOneCompleteGroup() {
        val zone = ZoneId.systemDefault()
        val shown = date.with(DayOfWeek.MONDAY)
        val days = (0L..6L).map(shown::plusDays)
        val events = (0..2).map { index ->
            CalendarEvent(-701L - index, calendarId, "Separate event ${index + 1}", "", "",
                shown.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                shown.atTime(13, 0).atZone(zone).toInstant().toEpochMilli(), false, zone.id)
        }
        val formats = Formats(english, Locale.ENGLISH)
        val screenDensity = device.displayWidth / 390f
        val opened = AtomicReference<CalendarEvent?>(null)
        val listed = AtomicReference<List<CalendarEvent>?>(null)
        val focus = mutableStateOf<TimelineFocus?>(TimelineFocus(shown, 1))
        val scroll = ScrollState(0)
        onMain {
            requireNotNull(activity).setContent {
                SunlithTheme("light", false) {
                    CompositionLocalProvider(LocalDensity provides Density(screenDensity, 1.15f)) {
                        Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding()
                            .semantics { testTagsAsResourceId = true }) {
                            Timeline(days, { if (it == shown) events else emptyList() }, shown, shown.minusWeeks(4), formats, true, scroll,
                                autoScroll = true, todayRequest = 0, motion = false, onSelectDay = {}, onOpen = { opened.set(it) }, onNew = {},
                                onOverflow = { _, list -> listed.set(list) },
                                focus = focus.value, onFocusHandled = { focus.value = null }, fallback = {})
                        }
                    }
                }
            }
        }
        awaitUi { focus.value == null && scroll.viewportSize > 0 }
        val viewport = timelineHeaderViewport()
        days.forEach { assertTrue("Every date must fit above its events", visibleTimelineHeader(it) != null) }
        val holders = cardNamingEachEvent(events, formats)
        val visible = holders.values.distinct()
        visible.forEach { event ->
            val bounds = node(By.pkg(context.packageName).res("timeline_event_${event.id}")).visibleBounds
            assertTrue("Each card must stay inside the week: $bounds", bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
        val cards = assertSeparatedRoundedCards("seven-days-three-simultaneous", visible, screenDensity) { cards ->
            assertTrue("Simultaneous events must start on the same line: ${cards.map { it.target }}",
                cards.maxOf { it.target.top } - cards.minOf { it.target.top } <= 1)
            cards.forEach { assertReadableTitle(it, 11 * screenDensity * 1.15f) }
        }
        captureFinal("seven-days-three-simultaneous-events")
        assertCardsOpenTheirEvents(cards, holders, opened, listed)
    }

    @Test
    fun narrowWeekGroupListsEveryRealTitleAndTimeAndOpensEachEvent() {
        val shown = date.plusDays(2)
        val titles = (1..3).map { "Customization simultaneous laboratory $it" }
        val ids = titles.map { addTimedMinutes(shown, it, 12 * 60 + 15, 13 * 60 + 45) }
        onMain {
            vm.changeWeekLayout("timeline")
            vm.changeVisibleWeekDays(7)
            vm.setMondayFirst(true)
            vm.changeViewMode(ViewMode.Week)
            vm.select(shown)
            vm.reload()
        }
        awaitUi { !vm.loadingEvents && ids.all { id -> vm.events.any { it.id == id } } }
        val events = ids.map { id -> vm.events.single { it.id == id } }
        val formats = Formats(calendarContext(), calendarLocale)
        val em = 11 * context.resources.displayMetrics.density * context.resources.configuration.fontScale
        val today = shown == LocalDate.now()
        fun verifyDetails(target: CalendarEvent) {
            awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == target.id }
            val details = (vm.stack.last() as Screen.Details).event
            assertEquals(target.title, details.title)
            assertEquals(target.startMillis, details.startMillis)
            assertEquals(target.endMillis, details.endMillis)
            onMain { vm.back() }
            awaitUi { vm.stack.isEmpty() }
        }
        positionTimelineEvent(ids.first())
        val present = ids.filter { id -> device.findObject(By.pkg(context.packageName).res("timeline_event_$id"))?.visibleBounds?.isEmpty == false }
        if (present.size == ids.size) {
            // A wide week has room: each event keeps its own readable card and opens directly.
            val cards = assertSeparatedRoundedCards("week-simultaneous-direct", events, nowMarkerMayCrossCards = today) { cards ->
                cards.forEach { assertReadableTitle(it, em) }
            }
            captureFinal("week-simultaneous-direct")
            cards.forEach { card ->
                assertTrue(device.click(card.bounds.centerX(), card.bounds.centerY()))
                verifyDetails(card.event)
            }
            return
        }
        assertEquals("A narrow week shows one group card that starts with the first event, never slivers", listOf(ids.first()), present)
        val spoken = node(By.pkg(context.packageName).res("timeline_event_${ids.first()}")).contentDescription.orEmpty()
        events.forEach { event ->
            assertTrue("The group card must name ${event.title} with its real time: $spoken",
                spoken.contains(event.title) && spoken.contains(formats.timeRange(event.startMillis, event.endMillis)))
        }
        val group = assertSeparatedRoundedCards("week-simultaneous-group", listOf(events.first()), nowMarkerMayCrossCards = today) { cards ->
            assertReadableTitle(cards.single(), em)
        }.single()
        captureFinal("week-simultaneous-group")
        val heading = calendarContext().resources.getQuantityString(R.plurals.event_count, events.size, events.size)
        fun listItem(event: CalendarEvent): UiObject2? = device.findObjects(By.pkg(context.packageName).descContains(event.title))
            .firstOrNull { item -> titles.count { item.contentDescription.orEmpty().contains(it) } == 1 }
        events.forEach { event ->
            assertTrue(device.click(group.bounds.centerX(), group.bounds.centerY()))
            node(By.pkg(context.packageName).text(heading))
            assertTrue("Opening the group must leave the week underneath the list.", vm.stack.isEmpty())
            events.forEach { listedEvent ->
                awaitUi { listItem(listedEvent) != null }
                val description = requireNotNull(listItem(listedEvent)).contentDescription.orEmpty()
                assertTrue("The list must show ${listedEvent.title} with its real start and end: $description",
                    description.contains(formats.time(listedEvent.startMillis)) && description.contains(formats.time(listedEvent.endMillis)))
            }
            if (event == events.first()) captureFinal("week-simultaneous-group-list")
            requireNotNull(listItem(event)).click()
            verifyDetails(event)
        }
    }

    @Test
    fun sevenDayWeekKeepsWholeWordsReadableSeparateCardsAndRealTimes() {
        val zone = ZoneId.systemDefault()
        val monday = date.with(DayOfWeek.MONDAY)
        val days = (0L..6L).map(monday::plusDays)
        fun timed(id: Long, day: LocalDate, title: String, from: Int, to: Int) = CalendarEvent(id, calendarId, title, "", "",
            day.atStartOfDay(zone).plusMinutes(from.toLong()).toInstant().toEpochMilli(),
            day.atStartOfDay(zone).plusMinutes(to.toLong()).toInstant().toEpochMilli(), false, zone.id)
        // The user's crowded week: a class every day, then a marker and three simultaneous labs on Friday.
        // Monday's single long word and Tuesday's short word have the same time, so only word chopping can make
        // Monday's card hold more lines of text.
        val longWord = timed(-801, days[0], "Electromagnetism", 9 * 60, 11 * 60)
        val shortWord = timed(-802, days[1], "Elgin", 9 * 60, 11 * 60)
        val classes = (2..6).map { timed(-801L - it, days[it], "Digital circuits $it", 9 * 60, 11 * 60) }
        val marker = timed(-810, days[4], "Synthetic noon position marker", 11 * 60 + 30, 12 * 60)
        val labs = (1..3).map { timed(-810L - it, days[4], "Synthetic overlapping circuits laboratory $it", 12 * 60 + 15, 13 * 60 + 45) }
        val everything = listOf(longWord, shortWord) + classes + marker + labs
        val byDay = everything.groupBy { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
        val screenDensity = device.displayWidth / 390f
        val formats = Formats(english, Locale.ENGLISH)
        listOf(1f, 1.15f).forEach { fontScale ->
            val opened = AtomicReference<CalendarEvent?>(null)
            val listed = AtomicReference<List<CalendarEvent>?>(null)
            val focus = mutableStateOf<TimelineFocus?>(TimelineFocus(monday, 1))
            val scroll = ScrollState(0)
            onMain {
                requireNotNull(activity).setContent {
                    SunlithTheme("light", false) {
                        CompositionLocalProvider(LocalDensity provides Density(screenDensity, fontScale)) {
                            Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding()
                                .semantics { testTagsAsResourceId = true }) {
                                // Today is outside this week, so the current-time dot never sits on a measured card.
                                Timeline(days, { byDay[it].orEmpty() }, monday, monday.minusWeeks(4), formats, true, scroll,
                                    autoScroll = true, todayRequest = 0, motion = false, onSelectDay = {}, onOpen = { opened.set(it) }, onNew = {},
                                    onOverflow = { _, list -> listed.set(list) },
                                    focus = focus.value, onFocusHandled = { focus.value = null }, fallback = {})
                            }
                        }
                    }
                }
            }
            awaitUi { focus.value == null && scroll.viewportSize > 0 }
            days.forEach { assertTrue("Every date must fit above its events", visibleTimelineHeader(it) != null) }
            // Every event keeps its full title and real time, on its own card or on the one group card for the labs.
            val holders = cardNamingEachEvent(everything, formats)
            (listOf(longWord, shortWord) + classes + marker).forEach {
                assertEquals("${it.title} has room for its own card", it, holders.getValue(it))
            }
            val em = 11 * screenDensity * fontScale
            val name = "seven-day-words-${(fontScale * 100).roundToInt()}"
            val cards = assertSeparatedRoundedCards(name, holders.values.distinct(), screenDensity) { cards ->
                val card = cards.associateBy { it.event.id }
                val morning = (listOf(longWord, shortWord) + classes).map { card.getValue(it.id).target }
                assertTrue("Every 9:00 class must start on the same line in every column: $morning",
                    morning.maxOf { it.top } - morning.minOf { it.top } <= 1)
                assertTrue("Every 9 to 11 class must end on the same line: $morning",
                    morning.maxOf { it.bottom } - morning.minOf { it.bottom } <= 1)
                val fridayClass = card.getValue(classes[2].id).target
                val noon = card.getValue(marker.id).target
                val labCards = labs.map { holders.getValue(it) }.distinct().map { card.getValue(it.id).target }
                assertTrue("The 11:30 marker must sit between the 11:00 end and the 12:15 labs: $fridayClass / $noon / $labCards",
                    fridayClass.bottom <= noon.top && noon.bottom <= labCards.minOf { it.top })
                assertTrue("Simultaneous labs must start on the same line: $labCards",
                    labCards.maxOf { it.top } - labCards.minOf { it.top } <= 1)
                val long = card.getValue(longWord.id)
                val short = card.getValue(shortWord.id)
                assertTrue("Both comparison cards must paint text: ${long.inkHeight} / ${short.inkHeight}", long.inkHeight > 0 && short.inkHeight > 0)
                assertTrue("A long word must not be chopped onto extra lines: long=${long.inkHeight}px short=${short.inkHeight}px em=$em",
                    long.inkHeight <= short.inkHeight + 0.6f * em)
                cards.forEach { assertReadableTitle(it, em) }
            }
            captureFinal(name)
            assertCardsOpenTheirEvents(cards, holders, opened, listed)
        }
    }

    @Test
    fun normalSevenDayTimelineFitsTheWholeWeekWithoutAnInnerHorizontalScroll() {
        val density = context.resources.displayMetrics.density
        assumeTrue("This check exercises a fitted normal-font phone week.",
            context.resources.configuration.fontScale <= 1.15f && device.displayWidth / density >= 280)
        val monday = date.with(DayOfWeek.MONDAY)
        val expected = (0L..6L).map(monday::plusDays)
        onMain {
            vm.changeWeekLayout("timeline")
            vm.changeVisibleWeekDays(7)
            vm.changeViewMode(ViewMode.Week)
            vm.setMondayFirst(true)
            vm.changeSwipeNavigation(false)
            // Exercise the product default: opted-in clicks expand semantics to a 48dp touch target.
            vm.changeWeekHeaderSelection(false)
            vm.select(date)
        }
        fun fittedHeaders() {
            awaitUi { expected.all { visibleTimelineHeader(it) != null } }
            val viewport = timelineHeaderViewport()
            val bounds = expected.map { value ->
                val header = requireNotNull(visibleTimelineHeader(value))
                assertFalse("The default fitted week must retain read-only date headers.", header.isClickable)
                header.visibleBounds
            }
            bounds.forEachIndexed { index, rect ->
                assertTrue("${expected[index]} must be completely inside the seven-day viewport: $rect / $viewport",
                    rect.left >= viewport.left - 1 && rect.right <= viewport.right + 1 &&
                        viewport.contains(rect.centerX(), rect.centerY()))
                assertTrue("A fitted day must keep readable date space.", rect.width() >= 24 * density)
            }
            assertEquals("Seven dates must occupy seven distinct horizontal columns.", 7, bounds.map { it.centerX() }.toSet().size)
            assertFalse(device.hasObject(day(monday.minusDays(1))))
            assertFalse(device.hasObject(day(monday.plusDays(7))))
        }
        fittedHeaders()
        val viewport = timelineHeaderViewport()
        listOf(true, false).forEach { forward ->
            val left = viewport.left + viewport.width() / 5
            val right = viewport.right - viewport.width() / 5
            assertTrue(device.swipe(if (forward) right else left, viewport.centerY(),
                if (forward) left else right, viewport.centerY(), 30))
            device.waitForIdle()
            refreshAccessibility()
            assertEquals("A fitted week has no inner date strip to drift sideways.", date, vm.selectedDate)
            fittedHeaders()
            assertTrue("A header drag must not open another screen.", vm.stack.isEmpty())
        }
    }

    @Test
    fun normalFiveDayTimelineKeepsEveryExpectedHeaderCentreInsideItsViewport() {
        val density = context.resources.displayMetrics.density
        assumeTrue("This check exercises the fitted normal-width timeline.",
            context.resources.configuration.fontScale <= 1.15f && device.displayWidth / density >= 280)
        val start = date.with(DayOfWeek.FRIDAY)
        val expected = (0L until 5L).map(start::plusDays)
        onMain {
            vm.changeWeekLayout("timeline")
            vm.changeVisibleWeekDays(5)
            vm.changeViewMode(ViewMode.Week)
            vm.select(start)
        }
        awaitUi { !vm.loadingEvents && expected.all { visibleTimelineHeader(it) != null } }
        val viewport = timelineHeaderViewport()
        expected.forEach { day ->
            val header = requireNotNull(visibleTimelineHeader(day))
            assertTrue("$day must have a real visible header centre, rather than an offscreen semantics node.",
                viewport.contains(header.visibleCenter.x, header.visibleCenter.y))
            assertTrue("Every fitted date needs a usable width.", header.visibleBounds.width() >= 32 * density)
        }
        assertFalse(device.hasObject(day(start.minusDays(1))))
        assertFalse(device.hasObject(day(start.plusDays(5))))
        node(day(start.plusDays(1))).click()
        awaitUi { vm.selectedDate == start.plusDays(1) && expected.all { visibleTimelineHeader(it) != null } }
        assertEquals(5, vm.visibleWeekDays)
    }

    @Test
    fun threeDayAndSevenDayWeekTimelinesChangeTheNumberOfSelectableDates() {
        val zone = ZoneId.systemDefault()
        val shortId = repository.saveEvent(EventDraft(calendarId = calendarId, title = "Short event ending at 10:30",
            startMillis = date.plusDays(2).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
            endMillis = date.plusDays(2).atTime(10, 30).atZone(zone).toInstant().toEpochMilli(),
            timeZone = zone.id, reminderMinutes = emptyList()))
        onMain { vm.applyLayoutPreset("planner"); vm.changeWeekLayout("timeline"); vm.changeViewMode(ViewMode.Week); vm.select(date); vm.reload() }
        node(By.pkg(context.packageName).res("calendar_timeline"))
        val dates = date.minusDays(2).let { monday -> (0L until 7L).map { monday.plusDays(it) } }
        onMain { vm.changeSwipeNavigation(false) }
        val minimumHeaderWidth = (24 * context.resources.displayMetrics.density).toInt()
        fun visibleHeader(value: LocalDate): UiObject2? = device.findObject(day(value))?.takeIf {
            it.visibleBounds.width() >= minimumHeaderWidth
        }
        fun dragHeaders(forward: Boolean) {
            refreshAccessibility()
            val headerY = requireNotNull(dates.firstNotNullOfOrNull(::visibleHeader)).visibleBounds.centerY()
            val bounds = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
            val left = bounds.left + bounds.width() / 4
            val right = bounds.right - bounds.width() / 8
            assertTrue(device.swipe(if (forward) right else left, headerY, if (forward) left else right, headerY, 30))
            device.waitForIdle()
            refreshAccessibility()
            assertEquals("Scrolling the readable columns must retain the selected date.", date, vm.selectedDate)
            assertFalse("A column scroll must not expose a date before this week.", visibleHeader(dates.first().minusDays(1)) != null)
            assertFalse("A column scroll must not expose a date after this week.", visibleHeader(dates.last().plusDays(1)) != null)
        }
        val reached = mutableSetOf<LocalDate>()
        fun observeHeaders() { dates.filterTo(reached) { visibleHeader(it) != null } }
        awaitUi { !vm.loadingEvents && visibleHeader(date) != null }
        observeHeaders()
        listOf(true, false).forEach { forward ->
            repeat(4) {
                if (reached.size < dates.size) { dragHeaders(forward); observeHeaders() }
            }
        }
        assertEquals("All seven dates must remain reachable in the same readable week.", dates.toSet(), reached)
        val eventDate = date.plusDays(2)
        repeat(4) { if (visibleHeader(eventDate) == null) dragHeaders(true) }
        requireNotNull(visibleHeader(eventDate)).click()
        awaitUi { vm.selectedDate == eventDate && visibleHeader(eventDate)?.let { it.isSelected || it.isChecked } == true }
        revealTimelineEvent(shortId)
        device.takeScreenshot(File(context.filesDir, "week-seven-days.png"))
        onMain { vm.changeSwipeNavigation(true); vm.changeVisibleWeekDays(3) }
        node(By.pkg(context.packageName).res("calendar_timeline"))
        awaitUi { dates.count { device.hasObject(day(it)) } == 3 }
        assertEquals("The three-day timeline must show only three selectable dates.", 3, dates.count { device.hasObject(day(it)) })
        assertFalse(device.hasObject(day(eventDate.minusDays(1))))
        assertFalse(device.hasObject(day(eventDate.plusDays(3))))
        assertEquals("custom", vm.layoutPreset)
    }

    @Test
    fun overnightEventStaysInTheHourlyTimelineOnBothDates() {
        val zone = ZoneId.systemDefault()
        val overnightId = repository.saveEvent(EventDraft(
            calendarId = calendarId, title = "Customization overnight shift",
            startMillis = date.atTime(22, 0).atZone(zone).toInstant().toEpochMilli(),
            endMillis = date.plusDays(1).atTime(1, 0).atZone(zone).toInstant().toEpochMilli(),
            timeZone = zone.id, reminderMinutes = emptyList(),
        ))
        onMain { vm.applyLayoutPreset("planner"); vm.changeDayLayout("timeline"); vm.changeViewMode(ViewMode.Day); vm.select(date); vm.reload() }
        awaitUi { vm.events.any { it.id == overnightId } }
        listOf(date, date.plusDays(1)).forEach { shownDate ->
            onMain { vm.select(shownDate) }
            awaitUi { device.hasObject(day(shownDate).checked(true)) }
            revealTimelineEvent(overnightId)
            val selector = By.pkg(context.packageName).res("timeline_event_$overnightId")
            val hours = node(By.pkg(context.packageName).res("calendar_timeline"))
            val timedEvent = hours.findObject(selector)
            assertTrue("An overnight event shorter than 24 hours must occupy the hourly area on $shownDate.", timedEvent != null)
            val banner = device.findObject(By.pkg(context.packageName).res("calendar_timeline_all_day"))
            assertFalse("Crossing midnight must not turn a timed event into an all-day banner.", banner?.findObject(selector) != null)
            val bounds = requireNotNull(timedEvent).visibleBounds
            val inset = (20 * context.resources.displayMetrics.density).toInt()
            device.click(bounds.left + minOf(bounds.width() / 2, inset), bounds.top + minOf(bounds.height() / 2, inset))
            awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == overnightId }
            assertFalse((vm.stack.last() as Screen.Details).event.allDay)
            node(By.pkg(context.packageName).desc(english.getString(R.string.action_back))).click()
            awaitUi { vm.stack.isEmpty() }
        }
    }

    @Test
    fun threeDayWindowKeepsTheSelectedMiddleDateAndVisibleDatesAfterActivityRecreation() {
        val start = date.plusWeeks(1)
        val shownDates = (0L until 3L).map(start::plusDays)
        onMain { vm.applyLayoutPreset("planner"); vm.changeWeekLayout("timeline"); vm.changeViewMode(ViewMode.Week); vm.changeVisibleWeekDays(3); vm.select(start) }
        awaitUi { shownDates.all { device.hasObject(day(it)) } && !device.hasObject(day(start.minusDays(1))) && !device.hasObject(day(start.plusDays(3))) }
        val middle = start.plusDays(1)
        node(day(middle)).click()
        awaitUi { vm.selectedDate == middle && shownDates.all { device.hasObject(day(it)) } && device.findObject(day(middle))?.let { it.isSelected || it.isChecked } == true }
        assertTrue("The middle date must expose its selected state.", node(day(middle)).let { it.isSelected || it.isChecked })
        recreateActivity()
        node(By.pkg(context.packageName).res("calendar_timeline"))
        awaitUi { shownDates.all { device.hasObject(day(it)) } && !device.hasObject(day(start.minusDays(1))) && !device.hasObject(day(start.plusDays(3))) && device.findObject(day(middle))?.let { it.isSelected || it.isChecked } == true }
        assertEquals("Recreation must keep the selected middle date.", middle, vm.selectedDate)
        assertEquals(ViewMode.Week, vm.viewMode)
        assertEquals(3, vm.visibleWeekDays)
        assertTrue("The restored middle date must remain selected.", node(day(middle)).let { it.isSelected || it.isChecked })
    }

    @Test
    fun threeDayTimelineKeepsItsFirstDateLoadedWhenSelectionCrossesTheMonthBoundary() {
        listOf(
            true to LocalDate.of(2027, 1, 30),
            false to LocalDate.of(2026, 10, 30),
        ).forEach { (mondayFirst, start) ->
            val shownDates = (0L until 3L).map(start::plusDays)
            val marker = addTimed(start, "Boundary first date", 9, 10)
            onMain {
                vm.applyLayoutPreset("planner")
                vm.changeWeekLayout("timeline")
                vm.changeViewMode(ViewMode.Week)
                vm.changeVisibleWeekDays(3)
                vm.setMondayFirst(mondayFirst)
                vm.select(start)
                vm.reload()
            }
            awaitUi { shownDates.all { device.hasObject(day(it)) } && vm.events.any { it.id == marker } }
            node(day(shownDates.last())).click()
            val nextMonth = java.time.YearMonth.from(shownDates.last())
            val nextGrid = gridStart(nextMonth, mondayFirst)
            val bufferMonth = java.time.YearMonth.from(start)
            val bufferedFrom = gridStart(bufferMonth.minusMonths(2), mondayFirst)
            val bufferedTo = gridStart(bufferMonth.plusMonths(2), mondayFirst).plusDays(41)
            awaitUi {
                vm.selectedDate == shownDates.last() && vm.gridLoadedFrom == bufferedFrom && vm.gridLoadedTo == bufferedTo &&
                    vm.coversRange(start, shownDates.last()) &&
                    device.hasObject(By.pkg(context.packageName).res("calendar_timeline")) &&
                    shownDates.all { device.hasObject(day(it)) }
            }
            assertTrue("The first date's event must survive a reload for the selected month.", vm.events.any { it.id == marker })
            revealTimelineEvent(marker)

            onMain { vm.changeWeekLayout("list"); vm.select(start); vm.reload() }
            awaitUi { vm.coversRange(start, shownDates.last()) }
            onMain { vm.select(shownDates.last()) }
            awaitUi { vm.gridLoadedFrom == bufferedFrom && vm.gridLoadedTo == bufferedTo && vm.coversRange(nextGrid, nextGrid.plusDays(41)) }
            onMain { vm.changeWeekLayout("timeline") }
            awaitUi {
                vm.coversRange(start, shownDates.last()) &&
                    device.hasObject(By.pkg(context.packageName).res("calendar_timeline")) &&
                    shownDates.all { device.hasObject(day(it)) }
            }

            onMain { vm.changeVisibleWeekDays(7); vm.reload() }
            awaitUi { vm.loadedFrom == nextGrid }
            onMain { vm.changeVisibleWeekDays(3) }
            awaitUi {
                vm.coversRange(start, shownDates.last()) &&
                    device.hasObject(By.pkg(context.packageName).res("calendar_timeline")) &&
                    shownDates.all { device.hasObject(day(it)) }
            }
            assertTrue("Layout changes must retain the first date's event.", vm.events.any { it.id == marker })
        }
    }

    @Test
    fun denseDayOverlapChooserCanOpenTheLastHiddenEvent() {
        val titles = (0 until 64).map { "Customization dense overlap ${it.toString().padStart(2, '0')}" }
        val ids = titles.map { addTimed(date, it, 15, 17) }
        onMain { vm.applyLayoutPreset("planner"); vm.changeDayLayout("timeline"); vm.changeViewMode(ViewMode.Day); vm.select(date); vm.reload() }
        awaitUi { ids.all { id -> vm.events.any { it.id == id } } }
        revealTimelineEvent(ids.first())
        val hours = node(By.pkg(context.packageName).res("calendar_timeline"))
        val visibleIds = ids.filter { hours.findObject(By.pkg(context.packageName).res("timeline_event_$it")) != null }
        val hiddenCount = ids.size - visibleIds.size
        assertTrue("Dense overlaps must offer a chooser when every event cannot fit.", hiddenCount > 0)
        assertFalse("The last event must be one of the events hidden by overflow.", ids.last() in visibleIds)
        val label = english.resources.getQuantityString(R.plurals.event_count, hiddenCount, hiddenCount)
        val overflow = node(By.pkg(context.packageName).desc(label)).visibleBounds
        val inset = (8 * context.resources.displayMetrics.density).toInt()
        device.click(overflow.centerX(), overflow.top + minOf(overflow.height() / 2, inset))
        node(By.pkg(context.packageName).text(label))
        assertTrue("Opening overflow must leave the Day view underneath the chooser.", vm.stack.isEmpty())
        captureFinal("timeline-overlap-dialog")
        scrollDialogToText(titles.last()).click()
        awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == ids.last() }
        node(By.pkg(context.packageName).textContains(titles.last()))
    }

    private fun timelineRailPixels(): Long {
        val bounds = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var hash = 1L
            var y = bounds.top + 8
            while (y < bounds.bottom - 8) {
                var x = bounds.left + 2
                while (x < bounds.left + bounds.width() / 7) {
                    hash = hash * 31 + bitmap.getPixel(x, y)
                    x += 2
                }
                y += 2
            }
            return hash
        } finally { bitmap.recycle() }
    }

    private fun captureFinal(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureFinal") != "true") return
        device.waitForIdle()
        SystemClock.sleep(250)
        assertTrue(device.takeScreenshot(File(context.filesDir, "final-$name.png")))
        device.dumpWindowHierarchy(File(context.filesDir, "final-$name.xml"))
    }

    private fun recreateActivity() {
        val previous = requireNotNull(activity)
        instrumentation.runOnMainSync { previous.recreate() }
        awaitUi {
            var restored = false
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { it !== previous }?.let { current ->
                        activity = current
                        vm = ViewModelProvider(current)[SunlithViewModel::class.java]
                        restored = true
                    }
            }
            restored
        }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        refreshAccessibility()
    }

    private fun scrollDialogToText(text: String): UiObject2 {
        val selectors = listOf(By.pkg(context.packageName).text(text), By.pkg(context.packageName).descStartsWith("$text, "))
        var direction = Direction.DOWN
        repeat(32) {
            refreshAccessibility()
            selectors.firstNotNullOfOrNull { selector ->
                device.findObject(selector)?.takeIf { it.visibleBounds.height() > 20 }
            }?.let { return it }
            // UiObject2.scroll can report the end of a Compose dialog early, so detect the end by visible movement.
            val before = visibleDialogContentPositions()
            node(By.pkg(context.packageName).scrollable(true)).scroll(direction, 0.7f)
            device.waitForIdle()
            refreshAccessibility()
            if (visibleDialogContentPositions() == before) {
                direction = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
            }
        }
        captureFinal("dialog-scroll-failure")
        error("The scrollable screen did not expose the requested text: $text")
    }

    private fun visibleDialogContentPositions(): List<String> =
        (device.findObjects(By.pkg(context.packageName).text(Pattern.compile(".+"))) +
            device.findObjects(By.pkg(context.packageName).desc(Pattern.compile(".+")))).mapNotNull { item ->
            try { "${item.text}|${item.contentDescription}@${item.visibleBounds}" } catch (_: StaleObjectException) { null }
        }.distinct().sorted()

    private fun seedAdjacentShortEvents(day: LocalDate): List<Long> {
        val titles = if (calendarLocale.language == "fr") listOf(
            "Première activité synthétique courte avec un titre complet à vérifier",
            "Deuxième activité synthétique courte avec un autre titre complet à vérifier",
            "Troisième activité synthétique courte à ouvrir avec son heure réelle",
        ) else listOf(
            "Synthetic first short activity with a complete title to verify",
            "Synthetic second short activity with another complete title to verify",
            "Synthetic third short activity to open with its actual scheduled time",
        )
        return titles.mapIndexed { index, title ->
            val start = 9 * 60 + index * 5
            addTimedMinutes(day, title, start, start + 5)
        }
    }

    private fun calendarContext(): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(calendarLocale) })

    private fun day(value: LocalDate): BySelector = By.pkg(context.packageName).descStartsWith(Formats(calendarContext(), calendarLocale).dayLong(value) + ",")

    private fun timelineHeaderViewport(): Rect = node(By.pkg(context.packageName).res("calendar_timeline_headers")).visibleBounds

    private fun visibleTimelineHeader(value: LocalDate): UiObject2? {
        return try {
            val viewport = device.findObject(By.pkg(context.packageName).res("calendar_timeline_headers"))?.visibleBounds ?: return null
            device.findObject(day(value))?.takeIf { header ->
                header.visibleBounds.width() > 20 && viewport.contains(header.visibleCenter.x, header.visibleCenter.y)
            }
        } catch (_: StaleObjectException) { null }
    }

    private fun assertTimelineHeadersAlignWithMarkers(markers: Map<LocalDate, Long>) {
        val viewport = timelineHeaderViewport()
        val minimum = 48 * context.resources.displayMetrics.density
        val tolerance = 3 * context.resources.displayMetrics.density
        val first = requireNotNull(vm.timelineViewportStart)
        val last = requireNotNull(vm.timelineViewportEnd)
        var checked = 0
        markers.filterKeys { it >= first && it <= last }.forEach { (day, id) ->
            val header = visibleTimelineHeader(day)?.visibleBounds ?: return@forEach
            if (header.width() < minimum || header.left < viewport.left || header.right > viewport.right) return@forEach
            val event = node(By.pkg(context.packageName).res("timeline_event_$id")).visibleBounds
            assertTrue("The real $day marker must stay visible in the hourly body.", event.height() > 20)
            assertTrue("The $day header and hourly event must move together: $header / $event",
                kotlin.math.abs(event.centerX() - header.centerX()) <= tolerance)
            checked++
        }
        assertTrue("At least one fully visible date must prove header/body alignment.", checked > 0)
    }

    private fun dragTimelineHorizontally(forward: Boolean, header: Boolean) {
        val viewport = timelineHeaderViewport()
        val body = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
        val left = viewport.left + viewport.width() * 18 / 100
        val right = viewport.left + viewport.width() * 82 / 100
        val y = if (header) viewport.centerY() else body.top + body.height() / 3
        val towardLeft = forward != timelineIsRtl()
        val from = if (towardLeft) right else left
        val to = if (towardLeft) left else right
        dragTouch(from, y, to, y)
    }

    private fun timelineIsRtl(): Boolean = requireNotNull(activity).window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun dragTouch(fromX: Int, fromY: Int, toX: Int, toY: Int,
        steps: Int = 24, stepDelayMs: Long = 12, holdMillis: Long = 150) {
        val started = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, fromX.toFloat(), fromY.toFloat())
        try {
            for (step in 1..steps) {
                SystemClock.sleep(stepDelayMs)
                send(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / steps.toFloat(), fromY + (toY - fromY) * step / steps.toFloat())
            }
            SystemClock.sleep(holdMillis)
        } finally { send(MotionEvent.ACTION_UP, toX.toFloat(), toY.toFloat()) }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        refreshAccessibility()
    }

    private fun flingTimelineAndReverse() {
        val viewport = timelineHeaderViewport()
        val left = viewport.left + viewport.width() * 18 / 100
        val right = viewport.left + viewport.width() * 82 / 100
        val from = if (timelineIsRtl()) left else right
        val to = if (timelineIsRtl()) right else left
        val selected = vm.selectedDate
        val initial = requireNotNull(vm.timelineViewportStart)
        assertTrue("Fling checks must exercise enabled navigation motion.", vm.navigationAnimation)
        assertTrue(device.swipe(from, viewport.centerY(), to, viewport.centerY(), 8))
        SystemClock.sleep(80)
        val forward = requireNotNull(vm.timelineViewportStart)
        assertTrue(device.swipe(to, viewport.centerY(), from, viewport.centerY(), 8))
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        assertTrue("The first fling must move the actual date viewport forward before reversal: $initial / $forward.", forward > initial)
        assertTrue("One fling must keep date progress bounded.", forward <= initial.plusDays(14))
        awaitUi { requireNotNull(vm.timelineViewportStart) < forward }
        val reversed = requireNotNull(vm.timelineViewportStart)
        assertTrue("The reversed viewport must stay near the dates touched by the gestures.", reversed >= initial.minusDays(14))
        assertTrue("A rendered header must corroborate the reported reversed viewport.",
            (0L..vm.visibleWeekDays.toLong()).any { visibleTimelineHeader(reversed.plusDays(it)) != null })
        assertEquals("Flinging must preserve the selected date.", selected, vm.selectedDate)
        assertTrue("Flinging must not open an event.", vm.stack.isEmpty())
        assertEquals(null, vm.editor)
    }

    private fun assertPaintedTimelineTitle(block: UiObject2, event: CalendarEvent, maxInkHeight: Int = Int.MAX_VALUE): Rect {
        val bounds = block.visibleBounds
        val density = context.resources.displayMetrics.density
        val verticalInset = (4 * density).roundToInt().coerceAtLeast(4)
        val horizontalInset = minOf((6 * density).roundToInt().coerceAtLeast(10),
            (bounds.width() / 4).coerceAtLeast(1))
        var image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        // Accessibility can publish bounds before the corresponding frame is visible. Require the fixture's
        // actual event fill across its interior; a saturated wallpaper or another screen must not pass readiness.
        val paintDeadline = SystemClock.elapsedRealtime() + 3_000
        val background = eventColors(event, LightPalette, CourseColors.Empty).container.toArgb()
        fun contrast(pixel: Int): Int {
            return kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(background)) +
                kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(background)) +
                kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(background))
        }
        fun eventFillPainted(): Boolean {
            var samples = 0
            var fill = 0
            for (y in bounds.top + verticalInset until bounds.bottom - verticalInset step 4) {
                for (x in bounds.left + horizontalInset until bounds.right - horizontalInset step 4) {
                    samples++
                    if (contrast(image.getPixel(x, y)) <= 12) fill++
                }
            }
            return samples > 0 && fill * 2 >= samples
        }
        while (!eventFillPainted() && SystemClock.elapsedRealtime() < paintDeadline) {
            image.recycle()
            SystemClock.sleep(32)
            image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        }
        try {
            val painted = eventFillPainted()
            var ink = 0
            var firstInk = bounds.bottom
            var lastInk = bounds.top
            for (y in bounds.top + verticalInset until bounds.bottom - verticalInset) {
                for (x in bounds.left + horizontalInset until bounds.right - horizontalInset) {
                    val pixel = image.getPixel(x, y)
                    if (contrast(pixel) > 100) {
                        ink++
                        firstInk = minOf(firstInk, y)
                        lastInk = maxOf(lastInk, y)
                    }
                }
            }
            val titleInk = Rect(bounds.left + horizontalInset, firstInk, bounds.right - horizontalInset, lastInk + 1)
            if (!painted || ink < 8 || titleInk.height() > maxInkHeight) {
                File(context.filesDir, "short-title-ink-failure.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                device.dumpWindowHierarchy(File(context.filesDir, "short-title-ink-failure.xml"))
            }
            assertTrue("The expected event fill must be painted before checking its title: bounds=$bounds", painted)
            assertTrue("A timeline event must paint readable title ink inside its block: bounds=$bounds, ink=$ink", ink >= 8)
            assertTrue("Narrow simultaneous Week titles must stay on one line instead of stacking letters: $titleInk",
                titleInk.height() <= maxInkHeight)
            return titleInk
        } finally { image.recycle() }
    }

    /** One event's painted card, found from its own fill colour, with the title ink measured inside it. */
    private class PaintedCard(val event: CalendarEvent, val target: Rect, val bounds: Rect,
        val inkTop: Int, val inkBottom: Int, val firstLineWidth: Int, val inkLeft: Int, val inkRight: Int) {
        val inkHeight get() = if (inkBottom < inkTop) 0 else inkBottom - inkTop + 1
    }

    /**
     * Checks what a person actually sees for [events]: every card is its own shape with a clear gap to every other
     * card, its corners are rounded, its outline is a plain rectangle rather than a stepped polygon, and its own tap
     * target covers its painted centre. Cards are traced from their expected fill, so a focus ring, the grid or a
     * neighbour of another colour cannot pass for a card. On any failure the screenshot used for the assertion and
     * the window hierarchy are saved as `cards-failure-[name]`, including failures raised by [also].
     */
    private fun assertSeparatedRoundedCards(name: String, events: List<CalendarEvent>,
        density: Float = context.resources.displayMetrics.density, nowMarkerMayCrossCards: Boolean = false,
        also: (List<PaintedCard>) -> Unit = {}): List<PaintedCard> {
        val viewport = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
        val targets = events.map { node(By.pkg(context.packageName).res("timeline_event_${it.id}")).visibleBounds }
        val fills = events.map { eventColors(it, LightPalette, CourseColors.Empty).container.toArgb() }
        fun difference(pixel: Int, fill: Int) =
            kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(fill)) +
                kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(fill)) +
                kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(fill))
        var image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        fun painted(): Boolean = targets.indices.all { index ->
            val target = Rect(targets[index])
            if (!target.intersect(viewport) || !target.intersect(0, 0, image.width, image.height)) return@all false
            var samples = 0
            var fill = 0
            for (y in target.top until target.bottom step 3) for (x in target.left until target.right step 3) {
                samples++
                if (difference(image.getPixel(x, y), fills[index]) <= 12) fill++
            }
            samples > 0 && fill * 5 >= samples
        }
        // Accessibility can publish bounds before their frame is on screen; wait for the real fills, not a fixed time.
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (!painted() && SystemClock.elapsedRealtime() < deadline) {
            image.recycle()
            instrumentation.waitForIdleSync()
            image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        }
        val notes = mutableListOf<String>()
        try {
            assertTrue("Every card must be painted in its own colour inside its tap target: $targets", painted())
            val margin = (8 * density).roundToInt()
            val region = Rect(targets.first()).apply { targets.forEach { union(it) }; inset(-margin, -margin) }
            check(region.intersect(viewport) && region.intersect(0, 0, image.width, image.height))
            val width = region.width()
            val height = region.height()
            val labels = IntArray(width * height)
            val queue = IntArray(width * height)
            fun index(x: Int, y: Int) = (y - region.top) * width + (x - region.left)
            fun ink(x: Int, y: Int, card: Int) = labels[index(x, y)] == 0 && difference(image.getPixel(x, y), fills[card]) > 100
            val edge = (2 * density).roundToInt()
            fun clipped(x: Int, y: Int) = x - viewport.left < edge || viewport.right - 1 - x < edge ||
                y - viewport.top < edge || viewport.bottom - 1 - y < edge
            val cornerRadius = 16 * density
            fun cornerAllowance(distance: Int): Int {
                if (distance >= cornerRadius) return 0
                val dy = cornerRadius - distance - 0.5f
                return kotlin.math.ceil(cornerRadius - kotlin.math.sqrt((cornerRadius * cornerRadius - dy * dy).coerceAtLeast(0f))).toInt()
            }
            val cards = events.mapIndexed { card, event ->
                val label = card + 1
                val target = Rect(targets[card]).apply { check(intersect(region)) { "${event.title} is outside the measured area" } }
                // A letter's enclosed counter has the same fill as the card. Use the largest connected area,
                // not a centre seed that could land inside an "o" at larger text sizes.
                val visited = BooleanArray(labels.size)
                var pixels = IntArray(0)
                var claimedBy = 0
                for (y in target.top until target.bottom) for (x in target.left until target.right) {
                    if (difference(image.getPixel(x, y), fills[card]) > 12) continue
                    val candidate = index(x, y)
                    val owner = labels[candidate]
                    if (owner != 0) {
                        if (claimedBy == 0) claimedBy = owner
                        continue
                    }
                    if (visited[candidate]) continue
                    var head = 0
                    var tail = 1
                    visited[candidate] = true
                    queue[0] = candidate
                    while (head < tail) {
                        val current = queue[head++]
                        val cx = region.left + current % width
                        val cy = region.top + current / width
                        for (step in 0 until 4) {
                            val nx = cx + if (step == 0) -1 else if (step == 1) 1 else 0
                            val ny = cy + if (step == 2) -1 else if (step == 3) 1 else 0
                            if (nx < region.left || nx >= region.right || ny < region.top || ny >= region.bottom) continue
                            val next = index(nx, ny)
                            if (!visited[next] && labels[next] == 0 && difference(image.getPixel(nx, ny), fills[card]) <= 12) {
                                visited[next] = true
                                queue[tail++] = next
                            }
                        }
                    }
                    if (tail > pixels.size) pixels = queue.copyOf(tail)
                }
                assertTrue("${event.title} is joined to ${if (claimedBy > 0) events[claimedBy - 1].title else "nothing"}: " +
                    "the two cards are painted as one shape", pixels.isNotEmpty())
                var left = Int.MAX_VALUE
                var top = Int.MAX_VALUE
                var right = Int.MIN_VALUE
                var bottom = Int.MIN_VALUE
                for (current in pixels) {
                    labels[current] = label
                    val x = region.left + current % width
                    val y = region.top + current / width
                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                }
                val bounds = Rect(left, top, right + 1, bottom + 1)
                notes += "${event.title}: target=${targets[card]} card=$bounds"
                assertTrue("${event.title} must be a real card, not a sliver: $bounds",
                    bounds.width() >= 4 * density && bounds.height() >= 3 * density)
                assertTrue("${event.title}'s own tap target must cover its painted centre: target=${targets[card]} card=$bounds",
                    targets[card].contains(bounds.centerX(), bounds.centerY()))
                listOf(bounds.left to bounds.top, bounds.right - 1 to bounds.top, bounds.left to bounds.bottom - 1,
                    bounds.right - 1 to bounds.bottom - 1).forEach { (x, y) ->
                    if (!clipped(x, y) && bounds.height() >= 6 * density) {
                        assertTrue("${event.title} must have rounded corners, not a square corner at ($x, $y): $bounds",
                            labels[index(x, y)] != label)
                    }
                }
                val rowLeft = IntArray(bounds.height())
                val rowRight = IntArray(bounds.height())
                var inkTop = Int.MAX_VALUE
                var inkBottom = Int.MIN_VALUE
                var inkLeft = Int.MAX_VALUE
                var inkRight = Int.MIN_VALUE
                // Only on today's date may the small current-time dot cover a card's start edge for a few rows.
                val dotRows = if (nowMarkerMayCrossCards) (8 * density).roundToInt() else 0
                val brokenRows = mutableListOf<Int>()
                for (y in bounds.top until bounds.bottom) {
                    var first = Int.MAX_VALUE
                    var last = Int.MIN_VALUE
                    for (x in bounds.left until bounds.right) if (labels[index(x, y)] == label) { first = minOf(first, x); last = maxOf(last, x) }
                    rowLeft[y - bounds.top] = first
                    rowRight[y - bounds.top] = last
                    val allowance = 2 + cornerAllowance(minOf(y - bounds.top, bounds.bottom - 1 - y))
                    val whole = first - bounds.left <= allowance && bounds.right - 1 - last <= allowance
                    if (!whole) brokenRows += y
                    assertTrue("${event.title} must be one plain rounded rectangle, not a stepped or cut shape; row $y spans $first..$last of $bounds",
                        whole || (brokenRows.size <= dotRows && y - brokenRows.first() < dotRows && bounds.right - 1 - last <= allowance))
                    for (x in first + 1 until last) if (ink(x, y, card)) {
                        inkTop = minOf(inkTop, y); inkBottom = maxOf(inkBottom, y); inkLeft = minOf(inkLeft, x); inkRight = maxOf(inkRight, x + 1)
                    }
                }
                // The first painted text line is the title's first line; a blank row ends it.
                var lineLeft = Int.MAX_VALUE
                var lineRight = Int.MIN_VALUE
                var line = inkTop
                while (line <= inkBottom) {
                    var any = false
                    for (x in rowLeft[line - bounds.top] + 1 until rowRight[line - bounds.top]) {
                        if (ink(x, line, card)) { any = true; lineLeft = minOf(lineLeft, x); lineRight = maxOf(lineRight, x) }
                    }
                    if (!any) break
                    line++
                }
                PaintedCard(event, targets[card], bounds, inkTop, inkBottom, if (lineRight >= lineLeft) lineRight - lineLeft + 1 else 0,
                    inkLeft, inkRight)
            }
            // A clear gap of at least 1.5dp must separate every pair of cards, whatever their colours.
            val reach = kotlin.math.ceil(1.5f * density).toInt() - 1
            cards.forEachIndexed { card, painted ->
                val label = card + 1
                val bounds = painted.bounds
                for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                    if (labels[index(x, y)] != label) continue
                    val inside = x > region.left && x < region.right - 1 && y > region.top && y < region.bottom - 1 &&
                        labels[index(x - 1, y)] == label && labels[index(x + 1, y)] == label &&
                        labels[index(x, y - 1)] == label && labels[index(x, y + 1)] == label
                    if (inside) continue
                    for (dy in -reach..reach) for (dx in -reach..reach) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < region.left || nx >= region.right || ny < region.top || ny >= region.bottom) continue
                        val other = labels[index(nx, ny)]
                        if (other != 0 && other != label) {
                            throw AssertionError("${painted.event.title} and ${events[other - 1].title} must keep a visible gap; " +
                                "they touch near ($x, $y): ${painted.bounds} / ${cards[other - 1].bounds}")
                        }
                    }
                }
            }
            also(cards)
            return cards
        } catch (failure: Throwable) {
            runCatching {
                File(context.filesDir, "cards-failure-$name.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                device.dumpWindowHierarchy(File(context.filesDir, "cards-failure-$name.xml"))
                File(context.filesDir, "cards-failure-$name.txt").writeText((notes + failure.toString()).joinToString("\n"))
            }
            throw failure
        } finally { image.recycle() }
    }

    /** Every visible card shows a readable start of its title: never a bare colour strip, one letter or a column of letters. */
    private fun assertReadableTitle(card: PaintedCard, emPixels: Float) {
        assertTrue("${card.event.title} must show a readable title, not a strip or one letter: firstLine=${card.firstLineWidth}px " +
            "em=$emPixels card=${card.bounds}", card.firstLineWidth >= 1.5f * emPixels)
    }

    /**
     * A compact group card shows a readable title and, on the same line at its end edge, a separate count of the other
     * events. Found from painted ink, so a blank card, a colour strip or a count with no title cannot pass.
     */
    private fun assertCountBesideTitle(name: String, card: PaintedCard, rtl: Boolean, density: Float, emPixels: Float) {
        val fill = eventColors(card.event, LightPalette, CourseColors.Empty).container.toArgb()
        val bounds = card.bounds
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            fun ink(x: Int, y: Int): Boolean {
                val pixel = image.getPixel(x, y)
                return kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(fill)) +
                    kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(fill)) +
                    kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(fill)) > 100
            }
            val edge = (3 * density).roundToInt()
            val rows = bounds.top + edge until bounds.bottom - edge
            // Columns from the end edge inward: the right edge, or the left edge in right to left text.
            val columns = (bounds.left + edge until bounds.right - edge).let { if (rtl) it.toList() else it.reversed() }
            val inkRows = columns.map { x -> rows.filter { y -> ink(x, y) } }
            val countStart = inkRows.indexOfFirst { it.isNotEmpty() }
            assertTrue("$name: the group card must paint something", countStart >= 0)
            assertTrue("$name: the count must sit at the card's end edge: offset=$countStart width=${bounds.width()}",
                countStart <= bounds.width() * 0.3f)
            val gap = (3 * density).roundToInt()
            var index = countStart
            var blank = 0
            while (index < inkRows.size && blank < gap) {
                if (inkRows[index].isEmpty()) blank++ else blank = 0
                index++
            }
            assertTrue("$name: a clear gap must separate the count from the title", blank >= gap)
            val countColumns = countStart until index - gap
            val titleColumns = (index until inkRows.size).filter { inkRows[it].isNotEmpty() }
            assertTrue("$name: the title must be painted beside the count", titleColumns.isNotEmpty())
            val titleWidth = titleColumns.last() - titleColumns.first() + 1
            assertTrue("$name: the title beside the count must stay readable: ${titleWidth}px em=$emPixels",
                titleWidth >= 1.5f * emPixels)
            assertTrue("$name: the count is a short label, not a second title: ${countColumns.count()}px",
                countColumns.count() in 1..(4 * emPixels).roundToInt())
            val countRows = countColumns.flatMap { inkRows[it] }
            val titleRows = titleColumns.flatMap { inkRows[it] }
            assertTrue("$name: the count must share the title's first line", countRows.min() <= titleRows.max() && titleRows.min() <= countRows.max())
        } catch (failure: Throwable) {
            runCatching {
                File(context.filesDir, "count-failure-$name.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            throw failure
        } finally { image.recycle() }
    }

    /**
     * Each event is named, with its real time range, by exactly one visible card: its own card, or one group card that
     * stands for a narrow overlap. Returns that card's event for every event.
     */
    private fun cardNamingEachEvent(events: List<CalendarEvent>, formats: Formats): Map<CalendarEvent, CalendarEvent> {
        val present = events.filter { event ->
            device.findObject(By.pkg(context.packageName).res("timeline_event_${event.id}"))?.visibleBounds?.isEmpty == false
        }
        assertTrue("At least one event card must be visible", present.isNotEmpty())
        val spoken = present.associateWith { node(By.pkg(context.packageName).res("timeline_event_${it.id}")).contentDescription.orEmpty() }
        return events.associateWith { event ->
            val time = formats.timeRange(event.startMillis, event.endMillis)
            val holders = present.filter { holder -> spoken.getValue(holder).let { it.contains(event.title) && it.contains(time) } }
            assertEquals("${event.title} must be named with its real time $time by exactly one visible card: $spoken", 1, holders.size)
            if (event in present) assertEquals("An event with its own card is named by that card", event, holders.single())
            holders.single()
        }
    }

    /** A card for one event opens it directly; a group card opens the list of exactly its own real events. */
    private fun assertCardsOpenTheirEvents(cards: List<PaintedCard>, holders: Map<CalendarEvent, CalendarEvent>,
        opened: AtomicReference<CalendarEvent?>, listed: AtomicReference<List<CalendarEvent>?>) {
        cards.forEach { card ->
            val members = holders.filterValues { it == card.event }.keys
            opened.set(null)
            listed.set(null)
            assertTrue(device.click(card.bounds.centerX(), card.bounds.centerY()))
            if (members.size == 1) awaitUi { opened.get() == card.event && listed.get() == null }
            else awaitUi { opened.get() == null && listed.get()?.let { it.size == members.size && it.toSet() == members } == true }
        }
    }

    private fun showGestureTimeline(layout: String, day: LocalDate) {
        onMain {
            vm.changeNavigationAnimation(true)
            vm.changeDayTimelineZoom(100)
            vm.changeWeekTimelineZoom(100)
            vm.changeGroupEmptyHours(false)
            vm.changeVisibleWeekDays(3)
            if (layout == "day") {
                vm.changeDayLayout("timeline")
                vm.changeViewMode(ViewMode.Day)
            } else {
                vm.changeWeekLayout(if (layout == "continuous") "continuous" else "timeline")
                vm.changeViewMode(ViewMode.Week)
            }
            vm.select(day)
        }
    }

    private fun pinchVertically(x: Float, y: Float, fromHalfSpan: Float, toHalfSpan: Float,
        afterRelease: ((Long) -> Unit)? = null, cancel: Boolean = false, whilePointersDown: (() -> Unit)? = null,
        onPointersDown: (() -> Unit)? = null) {
        val downTime = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        var secondFingerDown = false
        var span = fromHalfSpan
        fun send(action: Int, count: Int) {
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y + if (index == 0) -span else span
                pressure = 1f
                size = 1f
            } }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count,
                properties.copyOf(count), coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertTrue("Both pinch pointers must reach the app.", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 1)
        try {
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            secondFingerDown = true
            onPointersDown?.invoke()
            repeat(24) { step ->
                SystemClock.sleep(16)
                span = fromHalfSpan + (toHalfSpan - fromHalfSpan) * (step + 1) / 24f
                send(MotionEvent.ACTION_MOVE, 2)
            }
            SystemClock.sleep(50)
            whilePointersDown?.invoke()
        } finally {
            if (cancel) send(MotionEvent.ACTION_CANCEL, if (secondFingerDown) 2 else 1)
            else {
                if (secondFingerDown) send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
                send(MotionEvent.ACTION_UP, 1)
            }
        }
        afterRelease?.invoke(SystemClock.uptimeMillis())
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        refreshAccessibility()
    }

    private fun pinchReplacingSecondFinger(x: Float, y: Float, halfSpan: Float) {
        val downTime = SystemClock.uptimeMillis()
        val positions = floatArrayOf(y - halfSpan, y + halfSpan, y + halfSpan * 2.25f)
        fun send(action: Int, ids: List<Int>) {
            val properties = ids.map { pointerId -> MotionEvent.PointerProperties().apply {
                id = pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = ids.map { pointerId -> MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = positions[pointerId]
                pressure = 1f
                size = 1f
            } }.toTypedArray()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, ids.size, properties,
                coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        var active = listOf(0)
        send(MotionEvent.ACTION_DOWN, active)
        try {
            active = listOf(0, 1)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), active)
            repeat(12) { step ->
                SystemClock.sleep(16)
                val span = halfSpan * (1f + 0.5f * (step + 1) / 12f)
                positions[0] = y - span
                positions[1] = y + span
                send(MotionEvent.ACTION_MOVE, active)
            }
            active = listOf(0, 1, 2)
            send(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), active)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), active)
            active = listOf(0, 2)
            send(MotionEvent.ACTION_MOVE, active)
            val midpoint = (positions[0] + positions[2]) / 2f
            val span = (positions[2] - positions[0]) / 2f
            repeat(12) { step ->
                SystemClock.sleep(16)
                val scale = 1f + 0.2f * (step + 1) / 12f
                positions[0] = midpoint - span * scale
                positions[2] = midpoint + span * scale
                send(MotionEvent.ACTION_MOVE, active)
            }
            SystemClock.sleep(50)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), active)
            active = listOf(0)
            send(MotionEvent.ACTION_UP, active)
            active = emptyList()
        } finally { if (active.isNotEmpty()) send(MotionEvent.ACTION_CANCEL, active) }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        refreshAccessibility()
    }

    private fun positionTimelineEvent(id: Long) {
        revealTimelineEvent(id)
        val selector = By.pkg(context.packageName).res("timeline_event_$id")
        for (attempt in 0 until 4) {
            val body = timelineScroller().visibleBounds
            val event = node(selector).visibleBounds
            val delta = (body.centerY() - event.centerY()).coerceIn(-body.height() / 3, body.height() / 3)
            if (kotlin.math.abs(delta) <= 12 * context.resources.displayMetrics.density) break
            val inset = (16 * context.resources.displayMetrics.density).toInt()
            val x = if (timelineIsRtl()) body.right - inset else body.left + inset
            dragTouch(x, body.centerY(), x, body.centerY() + delta)
        }
        val body = timelineScroller().visibleBounds
        val event = node(selector).visibleBounds
        assertTrue("The captured event must have its visible centre inside the hourly viewport.",
            event.height() > 20 && body.contains(event.centerX(), event.centerY()))
    }

    private fun revealTimelineEvent(id: Long): UiObject2 {
        val selector = By.pkg(context.packageName).res("timeline_event_$id")
        SystemClock.sleep(400)
        for (attempt in 0 until 24) {
            timelineScroller()
            refreshAccessibility()
            fun findTimeline(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (root.viewIdResourceName == "calendar_timeline") return root
                for (index in 0 until root.childCount) {
                    root.getChild(index)?.let { child -> findTimeline(child)?.let { return it } }
                }
                return null
            }
            val timeline = instrumentation.uiAutomation.rootInActiveWindow?.let(::findTimeline)
            requireNotNull(timeline) { "The hourly area must expose its scroll actions." }
            val moved = timeline.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(400)
            device.waitForIdle()
            if (!moved) break
        }
        repeat(96) {
            refreshAccessibility()
            val found = device.findObject(selector)
            val bounds = try { found?.visibleBounds } catch (_: StaleObjectException) { null }
            val inset = (20 * context.resources.displayMetrics.density).toInt()
            val safeBottom = timelineScroller().visibleBounds.bottom - (96 * context.resources.displayMetrics.density).toInt()
            if (bounds != null && bounds.height() > 20 && bounds.top + minOf(bounds.height() / 2, inset) < safeBottom) {
                SystemClock.sleep(400)
                refreshAccessibility()
                val settled = device.findObject(selector)
                val stable = try { settled?.visibleBounds == bounds } catch (_: StaleObjectException) { false }
                if (stable) return requireNotNull(settled)
            } else {
                val body = timelineScroller().visibleBounds
                val safeTop = body.top + inset
                val usableHeight = safeBottom - safeTop
                require(usableHeight > 48 * context.resources.displayMetrics.density) {
                    "The hourly viewport must leave room for a safe vertical gesture."
                }
                val x = if (timelineIsRtl()) body.right - inset else body.left + inset
                val distance = usableHeight * 55 / 100
                dragTouch(x, safeTop + usableHeight * 3 / 4, x, safeTop + usableHeight * 3 / 4 - distance)
                SystemClock.sleep(400)
            }
        }
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "timeline-failure.xml"))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "timeline-failure.png"))
        error("The hourly timeline did not expose event $id.")
    }

    private fun timelineScroller(): UiObject2 = node(By.pkg(context.packageName).res("calendar_timeline")).apply {
        val bounds = visibleBounds
        setGestureMargins(bounds.width() / 10, bounds.height() / 5, bounds.width() / 10, bounds.height() / 4)
    }

    private fun dominantFabColor(): Int = dominantRenderedColor(By.pkg(context.packageName).desc(english.getString(R.string.action_new_event)))

    private fun paintedTimelineBlockHeight(selector: BySelector): Int {
        val bounds = node(selector).visibleBounds
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try {
            val area = Rect(bounds)
            assertTrue("The event must intersect the screenshot.", area.intersect(0, 0, screenshot.width, screenshot.height))
            val colors = mutableMapOf<Int, Int>()
            for (y in area.top until area.bottom step 2) for (x in area.left until area.right step 2) {
                val color = screenshot.getPixel(x, y)
                colors[color] = (colors[color] ?: 0) + 1
            }
            val fill = requireNotNull(colors.maxByOrNull { it.value }).key
            var current = 0
            var longest = 0
            for (y in area.top until area.bottom) {
                val filled = (area.left until area.right step 2).count { screenshot.getPixel(it, y) == fill }
                current = if (filled * 2 >= area.width() / 3) current + 1 else 0
                longest = maxOf(longest, current)
            }
            longest
        } finally { screenshot.recycle() }
    }

    private fun dominantRenderedColor(selector: BySelector): Int {
        val bounds = node(selector).visibleBounds
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try {
            val area = Rect(bounds)
            assertTrue("The rendered control must intersect the screenshot.", area.intersect(0, 0, screenshot.width, screenshot.height))
            val colors = mutableMapOf<Int, Int>()
            for (y in area.top until area.bottom step 2) {
                for (x in area.left until area.right step 2) {
                    val color = screenshot.getPixel(x, y)
                    colors[color] = (colors[color] ?: 0) + 1
                }
            }
            requireNotNull(colors.maxByOrNull { it.value }).key
        } finally { screenshot.recycle() }
    }

    private fun addTimed(day: LocalDate, title: String, startHour: Int, endHour: Int): Long {
        val zone = ZoneId.systemDefault()
        return repository.saveEvent(EventDraft(
            calendarId = calendarId, title = title,
            startMillis = day.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli(),
            endMillis = day.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli(),
            timeZone = zone.id, reminderMinutes = emptyList(),
        ))
    }

    private fun addTimedMinutes(day: LocalDate, title: String, startMinutes: Int, endMinutes: Int): Long {
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone)
        return repository.saveEvent(EventDraft(
            calendarId = calendarId, title = title,
            startMillis = start.plusMinutes(startMinutes.toLong()).toInstant().toEpochMilli(),
            endMillis = start.plusMinutes(endMinutes.toLong()).toInstant().toEpochMilli(),
            timeZone = zone.id, reminderMinutes = emptyList(),
        ))
    }

    private fun launch(locale: Locale = calendarLocale) {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        onMain { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java]; vm.select(date) }
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
        node(By.pkg(context.packageName).desc(localized.getString(R.string.action_new_event)))
    }

    private fun finish() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().forEach(Activity::finish)
            activity?.finish()
        }
        instrumentation.waitForIdleSync()
        activity = null
    }

    private fun onMain(action: () -> Unit) {
        instrumentation.runOnMainSync(action)
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        refreshAccessibility()
    }

    private fun awaitUi(detail: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        fun ready(): Boolean = try { condition() } catch (_: StaleObjectException) { false }
        while (SystemClock.elapsedRealtime() < deadline) {
            refreshAccessibility()
            if (ready()) return
            SystemClock.sleep(100)
        }
        device.dumpWindowHierarchy(File(context.filesDir, "customization-state.xml"))
        device.takeScreenshot(File(context.filesDir, "customization-state.png"))
        assertTrue("The expected customized calendar must be rendered. ${detail()}", ready())
    }

    private fun clickCurrent(selector: BySelector) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var bounds: Rect? = null
        while (bounds == null && SystemClock.elapsedRealtime() < deadline) {
            refreshAccessibility()
            bounds = try {
                device.findObject(selector)?.takeIf { it.isClickable && it.isEnabled }?.visibleBounds?.takeUnless { it.isEmpty }
            } catch (_: StaleObjectException) { null }
            if (bounds == null) SystemClock.sleep(16)
        }
        val target = requireNotNull(bounds) { "Missing current calendar control: $selector" }
        assertTrue("The current calendar control must receive one tap.", device.click(target.centerX(), target.centerY()))
    }

    private fun node(selector: BySelector): UiObject2 {
        refreshAccessibility()
        val found = device.wait(Until.findObject(selector), 10_000)
        if (found == null) {
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "customization-missing.xml"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "customization-missing.png"))
        }
        return requireNotNull(found) { "Missing calendar control: $selector" }
    }

    private fun refreshAccessibility() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
    }
}
