package io.github.sunsind.sunlith

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.Choreographer
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.SchoolAbsenceCoverage
import io.github.sunsind.sunlith.school.SchoolCalendarWriter
import io.github.sunsind.sunlith.school.SchoolFeed
import io.github.sunsind.sunlith.school.SchoolItem
import io.github.sunsind.sunlith.school.SchoolRecord
import io.github.sunsind.sunlith.school.SchoolStore
import io.github.sunsind.sunlith.school.SchoolTerm
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithApp
import io.github.sunsind.sunlith.ui.SunlithTheme
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.courseColors
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarReadabilityUiTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val base get() = instrumentation.targetContext
    private val language get() = arguments.getString("readabilityLanguage") ?: "en"
    private val rtl get() = arguments.getString("readabilityRtl") == "true"
    private val zone = ZoneId.systemDefault()
    private val friday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).plusWeeks(2)
    private val distant get() = friday.plusMonths(8)
    private val prefix = "readability-${UUID.randomUUID()}"
    private val preferenceNames = mutableSetOf<String>()
    private lateinit var app: Application
    private lateinit var directory: File
    private lateinit var device: UiDevice
    private lateinit var settings: SettingsStore
    private lateinit var screenContext: Context
    private var activity: ComponentActivity? = null
    private lateinit var vm: SunlithViewModel
    private val formats get() = Formats(screenContext, Locale.forLanguageTag(language))
    private var calendarId = 0L
    private val account get() = prefix
    private val gymTitle get() = if (language == "fr") "Musculation fonctionnelle avancée" else "Advanced functional strength training"
    private val courseTitles get() = if (language == "fr") listOf("Électronique analogique", "Circuits numériques", "Mathématiques appliquées", "Éthique et société", "Littérature québécoise")
        else listOf("Analog electronics", "Digital circuits", "Applied mathematics", "Ethics and society", "Quebec literature")

    @Before
    fun setUp() {
        requireEmulator()
        device = UiDevice.getInstance(instrumentation)
        directory = File(base.noBackupFilesDir, prefix).apply { check(mkdirs()) }
        app = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$prefix-$name"
                synchronized(preferenceNames) { preferenceNames += isolated }
                return base.getSharedPreferences(isolated, mode)
            }
        }
        screenContext = app.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
        calendarId = insertLocalCalendar(app, prefix, "Readability synthetic calendar", account)
        settings = SettingsStore(app).apply {
            selectedCalendarIds = setOf(calendarId)
            hasChosenCalendars = true
            themeMode = "light"
            materialYou = false
            remindersEnabled = false
            navigationAnimation = false
            showNavigationArrows = true
            swipeNavigation = true
            keepSelectedDate = true
            startView = "Week"
            weekStartsMonday = true
            weekLayout = "list"
            visibleWeekDays = 7
            dayLayout = "timeline"
            agendaRange = "month"
            agendaDisplay = "all"
            collapseCalendarOnScroll = false
        }
        check(app.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("ai_intro_done", true).putBoolean("layout_setup_done", true)
            .putBoolean("notify_asked", true).putLong("last_calendar", calendarId).commit())
        seedSchoolRows()
        launch()
        await("The isolated synthetic calendar must be the only selected calendar") {
            vm.loaded && !vm.loadingEvents && vm.loadError == null && vm.activeIds == setOf(calendarId)
        }
    }

    @After
    fun tearDown() {
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        instrumentation.waitForIdleSync()
        if (calendarId != 0L) {
            assertEquals(1, base.contentResolver.delete(localCalendarUri(calendarId, account), null, null))
            assertTrue(CalendarRepository(app).storedEvents(setOf(calendarId)).isEmpty())
        }
        synchronized(preferenceNames) { preferenceNames.forEach(base::deleteSharedPreferences) }
        if (::directory.isInitialized) check(directory.deleteRecursively())
    }

    @Test
    fun compactMonthKeepsRenderedChipsAtFractionalDensity() = checked {
        onMain { vm.changeMonthLayout("full"); vm.changeViewMode(ViewMode.Month); vm.select(friday) }
        loaded(friday)
        installContent(compactMonth = true)
        idle()
        node(By.res("calendar_full_month"))
        assertRenderedMonthCourseFill()
    }

    @Test
    fun themedTextKeepsChronologicalRangesAndContentDirectionInRtlLayout() = checked {
        val ready = CountDownLatch(1)
        val samples = mapOf(
            "range" to "13:30 - 16:10",
            "date" to "27 nov.",
            "en" to "Check the school notice.",
            "fr" to "Mis à jour par votre portail scolaire.",
            "ar" to "مرحبا",
        )
        var measured = emptyList<Triple<String, String, TextLayoutResult>>()
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SunlithTheme("light", materialYou = false) {
                        val typography = MaterialTheme.typography
                        val styles = listOf(
                            "displayLarge" to typography.displayLarge, "displayMedium" to typography.displayMedium,
                            "displaySmall" to typography.displaySmall, "headlineLarge" to typography.headlineLarge,
                            "headlineMedium" to typography.headlineMedium, "headlineSmall" to typography.headlineSmall,
                            "titleLarge" to typography.titleLarge, "titleMedium" to typography.titleMedium,
                            "titleSmall" to typography.titleSmall, "bodyLarge" to typography.bodyLarge,
                            "bodyMedium" to typography.bodyMedium, "bodySmall" to typography.bodySmall,
                            "labelLarge" to typography.labelLarge, "labelMedium" to typography.labelMedium,
                            "labelSmall" to typography.labelSmall,
                        )
                        val measurer = rememberTextMeasurer()
                        val layouts = styles.flatMap { (name, style) -> samples.map { (sample, value) ->
                            Triple(name, sample, measurer.measure(value, style, softWrap = false,
                                constraints = Constraints(maxWidth = 10_000)))
                        } }
                        SideEffect { measured = layouts; ready.countDown() }
                    }
                }
            }
        }
        assertTrue("The actual theme text must be measured", ready.await(5, TimeUnit.SECONDS))
        assertEquals(75, measured.size)
        measured.forEach { (style, sample, layout) ->
            val value = samples.getValue(sample)
            fun x(index: Int) = layout.getBoundingBox(index).center.x
            assertEquals("$style $sample paragraph direction",
                if (sample == "ar") ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr,
                layout.getParagraphDirection(0))
            when (sample) {
                "range" -> assertTrue("$style must draw the start time before the end time", x(0) < x(value.indexOf("16:10")))
                "date" -> assertTrue("$style must keep the day before the month and its final period", x(0) < x(3) && x(3) < x(value.lastIndex))
                "ar" -> assertTrue("$style must preserve Arabic glyph order", x(0) > x(value.lastIndex))
                else -> assertTrue("$style $sample must keep the final period after the sentence", x(0) < x(value.lastIndex))
            }
        }
    }

    @Test
    fun fiveDaySettingPagesAcrossWeekendAndSurvivesRecreationAndToday() = checked {
        node(By.desc(text(R.string.title_settings))).click()
        node(By.text(text(R.string.beta_settings_appearance))).click()
        scrollTo(By.text(text(R.string.view_week))).click()
        node(By.res("settings_choice_timeline5")).click()
        await("The setting must switch both the layout and the saved day count") { vm.weekLayout == "timeline" && vm.visibleWeekDays == 5 }
        assertEquals("timeline", settings.weekLayout)
        assertEquals(5, settings.visibleWeekDays)
        device.pressBack()
        device.pressBack()
        timeline(friday, 5)
        assertWindowReachable(friday, 5)
        node(By.desc(text(R.string.calendar_next_five_days))).click()
        await("The next arrow must move exactly five days") { vm.selectedDate == friday.plusDays(5) }
        checkedHeader(friday.plusDays(5))
        node(By.desc(text(R.string.calendar_prev_five_days))).click()
        await("The previous arrow must return to the original five-day start") { vm.selectedDate == friday }
        checkedHeader(friday)
        dragHeader(forward = true, distanceFraction = 0.63f)
        await("A fitted timeline swipe must advance exactly five days") { vm.selectedDate == friday.plusDays(5) }
        checkedHeader(friday.plusDays(5))
        dragHeader(forward = false, distanceFraction = 0.63f)
        await("The reverse timeline swipe must return exactly five days") { vm.selectedDate == friday }
        checkedHeader(friday)
        reachableHeader(friday.plusDays(1)).click()
        await("A Saturday inside the window must stay selectable") { vm.selectedDate == friday.plusDays(1) }
        checkedHeader(friday.plusDays(1))
        assertWindowReachable(friday, 5)
        recreate()
        assertEquals(friday.plusDays(1), vm.selectedDate)
        assertEquals(5, vm.visibleWeekDays)
        assertWindowReachable(friday, 5)
        node(By.desc(text(R.string.action_today))).click()
        await("Today must restore today's actual date") { vm.selectedDate == vm.today }
        val todayHeader = checkedHeader(vm.today)
        assertTrue("Today must be the checked date header", todayHeader.isCheckable && todayHeader.isChecked)
        for (offset in 1L..4L) {
            val other = reachableHeader(vm.today.plusDays(offset))
            assertTrue("Every other date must remain unchecked", other.isCheckable && !other.isChecked)
        }
        assertEquals(5, settings.visibleWeekDays)
    }

    @Test
    fun readableHorizontalOverflowRemainsScrollableWithPeriodSwipesDisabled() = checked { exerciseHorizontalOverflow(false) }

    @Test
    fun readableHorizontalOverflowConsumesItsOwnScrollBeforePeriodSwipes() = checked { exerciseHorizontalOverflow(true) }

    @Test
    fun disabledSwipesOnFittedThreeAndFiveDayTimelinesNeverPageOrTap() = checked {
        onMain { vm.changeSwipeNavigation(false) }
        for (count in listOf(3, 5)) {
            timeline(friday, count)
            assertTrue("This regression must exercise a fitted $count-day viewport", readableHeader(friday) != null && readableHeader(friday.plusDays(count - 1L)) != null)
            val timeline = node(By.res("calendar_timeline")).visibleBounds
            val before = vm.selectedDate
            val y = timeline.top + timeline.height() / 3
            val x1 = device.displayWidth * 4 / 5
            val x2 = device.displayWidth / 4
            assertTrue(device.swipe(x1, y, x2, y, 45))
            idle()
            assertEquals("Disabled $count-day swipes must keep the same period", before, vm.selectedDate)
            assertTrue("A horizontal drag must not turn into an event click or a new-event tap", vm.stack.isEmpty())
            assertEquals(null, vm.editor)
            assertEquals(count, vm.visibleWeekDays)
            dragHeader(forward = true)
            assertEquals("Dragging a fitted date header must not select the release target", before, vm.selectedDate)
            assertTrue(vm.stack.isEmpty())
        }
    }

    @Test
    fun groupedClassShowsItsWholeSpanEveryRoomAndOnlyThePartialAbsence() = checked {
        showDay(friday)
        val event = gym()
        assertEquals(3, event.school!!.periods.size)
        assertEquals(SchoolAbsenceCoverage.PARTIAL, event.school!!.absenceCoverage)
        assertEquals(at(friday, 13 * 60 + 30), event.startMillis)
        assertEquals(at(friday, 16 * 60 + 10), event.endMillis)
        assertEquals(1, vm.eventsByDay[friday].orEmpty().count { it.school?.course == "109-GYM" })
        val ordinary = vm.eventsByDay[friday].orEmpty().single { it.school?.course == "243-C0" }
        val ordinaryHeight = unclippedHeight(ordinary)
        val blockHeight = unclippedHeight(event)
        val block = eventNode(event)
        assertTrue("The 160-minute group must occupy more than twice a 50-minute block", blockHeight > ordinaryHeight * 2)
        assertTrue(block.contentDescription.contains(gymTitle))
        assertTrue(block.contentDescription.contains(formats.timeRange(event.startMillis, event.endMillis)))
        assertTrue(block.contentDescription.contains(text(R.string.calendar_teacher_absent_range,
            formats.timeRange(at(friday, 13 * 60 + 30), at(friday, 14 * 60 + 20)))))
        listOf("MON-B2410", "MON-B2422", "MON-DOUCHE").forEach { assertTrue(block.contentDescription.contains(it)) }
        block.click()
        assertDetails(event)
    }

    @Test
    fun secondPeriodNotificationAndDateFocusOpenTheWholeGroupedClass() = checked {
        showDay(friday)
        val event = gym()
        val second = event.school!!.periods[1]
        onMain { vm.handleLink(second.eventId, second.startMillis, false) }
        await("A notification for the second provider period must open event details") { vm.stack.lastOrNull() is Screen.Details }
        val opened = (vm.stack.last() as Screen.Details).event
        assertEquals(event.id, opened.id)
        assertEquals(event.startMillis, opened.startMillis)
        assertEquals(event.endMillis, opened.endMillis)
        assertEquals(3, opened.school!!.periods.size)
        assertDetails(opened)
        device.pressBack()
        onMain { vm.select(distant); vm.handleSchoolLink(friday) }
        loaded(friday)
        await("The requested date focus must be consumed by the real timeline") { !vm.pendingDayFocus }
        val first = vm.eventsByDay[friday].orEmpty().filterNot { it.allDay }.minBy { it.startMillis }
        val focused = node(By.res("timeline_event_${first.id}"))
        assertTrue("Date focus must bring that day's first class into view without a test scroll", focused.visibleBounds.height() > 20)
        assertTrue(focused.contentDescription.contains(formats.timeRange(first.startMillis, first.endMillis)))
        assertEquals(friday, vm.selectedDate)
    }

    @Test
    fun renderedCourseColorsStayDistinctWhenLoadedCoursesChangeAndAfterRestart() = checked {
        showDay(friday)
        val original = vm.courseColorSlots.toMap()
        val keys = (0..4).map { "243-C$it" } + "109-GYM" + "340-ABSENT"
        assertTrue(original.keys.containsAll(keys))
        assertEquals(keys.size, keys.map { original.getValue(it) }.toSet().size)
        val sampled = (0..4).associate { index ->
            val event = vm.eventsByDay[friday].orEmpty().single { it.school?.course == "243-C$index" }
            event.school!!.course to assertRenderedFill(event, original.getValue(event.school!!.course))
        }
        onMain { vm.selectAndFocus(distant) }
        loaded(distant)
        await("A distant provider window must load the new course") { "NEW-DISTANT" in vm.courseColorSlots }
        assertFalse("The fixture must actually change the loaded course set", vm.events.any { it.school?.course == "109-GYM" })
        original.forEach { (key, slot) -> assertEquals(slot, vm.courseColorSlots[key]) }
        val activeCourses = vm.events.mapNotNull { it.school?.course }.distinct()
        assertEquals(setOf("243-C0", "NEW-DISTANT"), activeCourses.toSet())
        assertEquals("Courses loaded together must keep distinct colors", activeCourses.size, activeCourses.map { vm.courseColorSlots.getValue(it) }.toSet().size)
        assertEquals(vm.courseColorSlots, settings.courseColorSlots)
        val previous = vm
        close()
        launch()
        assertNotEquals("A full restart must create a new view model", previous, vm)
        showDay(friday)
        original.forEach { (key, slot) -> assertEquals(slot, vm.courseColorSlots[key]) }
        sampled.forEach { (course, fill) ->
            val event = vm.eventsByDay[friday].orEmpty().single { it.school?.course == course }
            assertEquals(fill, assertRenderedFill(event, original.getValue(course)))
        }
    }

    @Test
    fun optInSyntheticReadabilityDisplayCheckpoints() = checked {
        assumeTrue("Explicit synthetic readability screenshot opt-in required", arguments.getString("readabilityCapture") != null)
        timeline(friday, 5)
        capture("five-day-start")
        assertWindowReachable(friday, 5)
        onMain { vm.select(friday.plusDays(1)) }
        checkedHeader(friday.plusDays(1))
        onMain { vm.select(friday) }
        checkedHeader(friday)
        eventNode(gym())
        capture("five-day-grouped-class")
        timeline(friday.with(DayOfWeek.MONDAY), 7)
        capture("seven-day-start")
        assertWindowReachable(friday.with(DayOfWeek.MONDAY), 7)
        onMain { vm.select(friday.with(DayOfWeek.SUNDAY)) }
        checkedHeader(friday.with(DayOfWeek.SUNDAY))
        await("The final day column must be fully visible") {
            readFresh { device.findObject(By.descStartsWith(formats.dayLong(friday.with(DayOfWeek.SUNDAY))))?.visibleBounds?.width() }
                ?.let { it >= 79 * base.resources.displayMetrics.density } == true
        }
        onMain { vm.select(friday) }
        checkedHeader(friday)
        eventNode(gym())
        capture("seven-day-grouped-class")
        onMain { vm.changeSwipeNavigation(false) }
        assertWindowReachable(friday.with(DayOfWeek.MONDAY), 7)
        capture("seven-day-swipe-off")
        showDay(friday)
        capture("day-course-colors")
        val event = gym()
        eventNode(event)
        capture("grouped-partial-absence")
        eventNode(event).click()
        capture("grouped-details-top")
        assertDetails(event)
        capture("grouped-details-periods-absence")
        device.pressBack()
        onMain { vm.changeDayLayout("cards") }
        loaded(friday)
        node(By.res("calendar_day_cards"))
        await("Cards must finish positioning the selected day") { !vm.pendingDayFocus }
        scrollTo(By.res("day_event_${event.id}"))
        capture("day-cards-rooms")
        onMain { vm.changeDayLayout("schedule") }
        loaded(friday)
        node(By.res("calendar_schedule"))
        await("Schedule must finish positioning the selected day") { !vm.pendingDayFocus }
        scrollTo(By.res("schedule_event_${event.id}"))
        capture("day-schedule")
        onMain { vm.changeDayLayout("timeline"); vm.selectAndFocus(friday) }
        loaded(friday)
        val absent = vm.eventsByDay[friday].orEmpty().single { it.school?.course == "340-ABSENT" }
        eventNode(absent)
        capture("fully-absent-outline")
        onMain { vm.openSearch(); vm.updateSearch(gymTitle) }
        await("Search must return the combined class once") { !vm.searchLoading && vm.searchResults.size == 1 && vm.searchResults.single().school?.periods?.size == 3 }
        onMain {
            val host = requireNotNull(activity)
            host.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
        }
        scrollTo(By.text(gymTitle))
        capture("search-grouped-class")
        onMain { vm.back(); vm.changeMonthLayout("full"); vm.changeViewMode(ViewMode.Month); vm.select(friday) }
        loaded(friday)
        node(By.res("calendar_full_month"))
        capture("full-month-course-colors")
        assertRenderedMonthCourseFill()
        settings.themeMode = "dark"
        close()
        launch()
        showDay(friday)
        eventNode(gym())
        capture("dark-grouped-partial-absence")
        onMain { vm.open(Screen.Settings) }
        node(By.text(text(R.string.beta_settings_appearance))).click()
        scrollTo(By.text(text(R.string.view_week)))
        capture("settings-week-choices")
    }

    private fun exerciseHorizontalOverflow(swipeEnabled: Boolean) {
        val monday = friday.with(DayOfWeek.MONDAY)
        onMain { vm.changeSwipeNavigation(swipeEnabled) }
        timeline(monday, 7)
        val first = header(monday).visibleBounds
        assertEquals("Seven readable columns must overflow the phone viewport", null, readableHeader(monday.plusDays(6)))
        val selected = vm.selectedDate
        dragHeader(forward = true)
        assertEquals("Scrolling a child viewport must keep the selected period", selected, vm.selectedDate)
        val moved = readFresh { device.findObject(By.descStartsWith(formats.dayLong(monday)))?.visibleBounds }
        assertTrue("The timeline must actually move horizontally", moved == null || moved != first)
        assertWindowReachable(monday, 7)
        assertEquals(selected, vm.selectedDate)
        if (!swipeEnabled) {
            repeat(2) { dragHeader(forward = true) }
            assertEquals("Disabled period swipes must stay disabled at the child scroll edge", selected, vm.selectedDate)
        } else {
            reachableHeader(monday.plusDays(6))
            dragHeader(forward = true, distanceFraction = 0.63f)
            await("A deliberate drag beyond the child scroll edge must move exactly one week") { vm.selectedDate == selected.plusWeeks(1) }
            checkedHeader(selected.plusWeeks(1))
        }
        assertEquals(swipeEnabled, settings.swipeNavigation)
    }

    private fun assertDetails(event: CalendarEvent) {
        node(By.text(gymTitle))
        scrollTo(By.text(formats.timeRange(event.startMillis, event.endMillis)))
        scrollTo(By.text(text(R.string.details_school_periods)))
        event.school!!.periods.forEach { period ->
            scrollTo(By.textContains(formats.timeRange(period.startMillis, period.endMillis)))
            scrollTo(By.textContains(period.location))
        }
        scrollTo(By.text(text(R.string.details_teacher_absence)))
        scrollTo(By.textContains(formats.timeRange(at(friday, 13 * 60 + 30), at(friday, 14 * 60 + 20))))
        scrollTo(By.textContains("Synthetic first-period absence only"))
        assertFalse("A partial absence must not become a full-class absence label", device.hasObject(By.text(text(R.string.calendar_teacher_absent))))
    }

    private fun assertRenderedFill(event: CalendarEvent, slot: Int): Int {
        val expected = courseColors(slot, dark = false).container.toArgb()
        assertRenderedFill(eventNode(event).visibleBounds, expected,
            "Rendered course ${event.school?.course} must use its saved fill, not a generic calendar color")
        return expected
    }

    private fun assertRenderedMonthCourseFill() {
        val day = (-4L..2L).map { friday.plusDays(it) }.first { it != friday && it.month == friday.month && it.year == friday.year }
        val event = vm.eventsByDay.getValue(day).single()
        val slot = vm.courseColorSlots.getValue(requireNotNull(event.school).courseKey)
        assertRenderedFill(node(By.res("calendar_full_month")).visibleBounds,
            courseColors(slot, dark = false).container.toArgb(),
            "The full month must visibly draw a school course chip at the current font size")
    }

    private fun assertRenderedFill(bounds: Rect, expected: Int, message: String) {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var count = 0
            val clipped = Rect(bounds)
            check(clipped.intersect(0, 0, screenshot.width, screenshot.height)) { "The rendered event must intersect the screenshot" }
            for (y in clipped.top until clipped.bottom) for (x in clipped.left until clipped.right) {
                if (screenshot.getPixel(x, y) == expected) count++
            }
            assertTrue(message, count > 100)
        } finally { screenshot.recycle() }
    }

    private fun timeline(date: LocalDate, count: Int) {
        onMain { vm.changeVisibleWeekDays(count); vm.changeWeekLayout("timeline"); vm.changeViewMode(ViewMode.Week); vm.select(date) }
        loaded(date)
        node(By.res("calendar_timeline"))
        checkedHeader(date)
    }

    private fun showDay(date: LocalDate) {
        onMain { vm.changeViewMode(ViewMode.Day); vm.changeDayLayout("timeline"); vm.selectAndFocus(date) }
        loaded(date)
        node(By.res("calendar_timeline"))
    }

    private fun gym() = vm.eventsByDay[friday].orEmpty().single { it.school?.course == "109-GYM" }
    private fun loaded(date: LocalDate) = await("Synthetic events must load for $date") { vm.loaded && !vm.loadingEvents && vm.loadError == null && vm.coversRange(date, date) }
    private fun header(date: LocalDate) = node(By.descStartsWith(formats.dayLong(date)))
    private fun checkedHeader(date: LocalDate): UiObject2 = stableNode(By.descStartsWith(formats.dayLong(date))) {
        it.isCheckable && it.isChecked
    }
    private fun readableHeader(date: LocalDate, viewport: Rect = headerViewport()): UiObject2? = readFresh {
        device.findObject(By.descStartsWith(formats.dayLong(date)))
            ?.takeIf { clippedHeaderBounds(it, viewport).width() >= 32 * base.resources.displayMetrics.density }
    }

    private fun clippedHeaderBounds(header: UiObject2, viewport: Rect): Rect = Rect(header.visibleBounds).apply {
        if (!intersect(viewport)) setEmpty()
    }

    private fun headerViewport(): Rect = readFresh {
        // Descendant accessibility bounds can extend into the fixed hour gutter. Use the scroll container itself.
        device.findObject(By.clazz("android.widget.HorizontalScrollView").hasDescendant(By.checkable(true)))?.visibleBounds
            ?: run {
                val timeline = requireNotNull(device.findObject(By.res("calendar_timeline"))).visibleBounds
                val headers = device.findObjects(By.descContains(", ")).filter {
                    it.isCheckable && it.visibleBounds.bottom <= timeline.top
                }.map { it.visibleBounds }
                check(headers.isNotEmpty()) { "The fitted timeline must expose its date headers" }
                Rect(headers.minOf { it.left }, headers.minOf { it.top }, headers.maxOf { it.right }, headers.maxOf { it.bottom })
            }
    }

    // At 200% text a week spans several screens. Keep each drag short enough to avoid paging.
    private val headerScrollAttempts get() = if (base.resources.configuration.fontScale > 1.3f) 96 else 24

    private fun reachableHeader(date: LocalDate): UiObject2 {
        val selected = vm.selectedDate
        val minimum = 48 * base.resources.displayMetrics.density
        val selector = By.descStartsWith(formats.dayLong(date))
        val viewport = headerViewport()
        fun stableVisible(waitMillis: Long): UiObject2? {
            val deadline = SystemClock.elapsedRealtime() + waitMillis
            var previous: Rect? = null
            var samples = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    val found = device.findObject(selector)
                    val bounds = found?.let { clippedHeaderBounds(it, viewport) }
                    if (found != null && bounds != null && bounds.width() >= minimum && bounds.contains(found.visibleCenter.x, found.visibleCenter.y)) {
                        samples = if (bounds == previous) samples + 1 else 1
                        previous = Rect(bounds)
                        if (samples >= 3) return found
                    } else { samples = 0; previous = null }
                } catch (_: StaleObjectException) { samples = 0; previous = null }
                SystemClock.sleep(60)
            }
            return null
        }
        stableVisible(2_000)?.let { return it }
        val observed = readFresh { device.findObjects(By.clazz("android.widget.Button")).map {
            "${it.contentDescription} ${it.visibleBounds} checked=${it.isChecked}"
        } }
        Log.i("ReadabilityFixture", "Missing header date=$date prefix=${formats.dayLong(date)} minWidth=$minimum density=${base.resources.displayMetrics.density} selected=$selected headers=$observed")
        // A fitted viewport has every header already; absence is a failure, never a reason to change pages.
        if (vm.visibleWeekDays <= 5 && base.resources.configuration.fontScale <= 1.3f && device.displayWidth / base.resources.displayMetrics.density >= 360) {
            return stableNode(selector) { clippedHeaderBounds(it, viewport).width() >= minimum }
        }
        for (forward in listOf(false, true)) repeat(headerScrollAttempts) {
            dragHeader(forward)
            assertEquals("Finding an in-window day must not change the calendar period", selected, vm.selectedDate)
            stableVisible(600)?.let { return it }
        }
        error("The in-window date must remain reachable: $date; prefix=${formats.dayLong(date)} minimumWidth=$minimum headers=$observed")
    }

    private fun assertWindowReachable(start: LocalDate, count: Int) {
        val expected = (0L until count.toLong()).map { start.plusDays(it) }
        val reached = mutableSetOf<LocalDate>()
        val viewport = headerViewport()
        reachableHeader(start)
        fun observe() {
            expected.forEach { date ->
                if (readableHeader(date, viewport) != null) reached += date
            }
        }
        observe()
        repeat(headerScrollAttempts) {
            if (reached.size < count) { dragHeader(forward = true); observe() }
        }
        repeat(headerScrollAttempts) {
            if (reached.size < count) { dragHeader(forward = false); observe() }
        }
        assertEquals("Every date including Saturday and Sunday must remain reachable in the same window", expected.toSet(), reached)
        assertEquals(null, readableHeader(start.minusDays(1), viewport))
        assertEquals(null, readableHeader(start.plusDays(count.toLong()), viewport))
    }

    private fun dragHeader(forward: Boolean, distanceFraction: Float = 0.08f) {
        val timeline = node(By.res("calendar_timeline")).visibleBounds
        val viewport = headerViewport()
        assertTrue("The real date viewport must sit above the hourly timeline", viewport.bottom <= timeline.top)
        val y = viewport.centerY()
        val inset = (8 * base.resources.displayMetrics.density).toInt()
        val start = viewport.left.coerceAtLeast(0) + inset
        val end = viewport.right.coerceAtMost(device.displayWidth) - inset
        val right = minOf((device.displayWidth * 0.88).toInt(), end)
        val left = maxOf(start, (right - device.displayWidth * distanceFraction).toInt())
        assertTrue("The drag must stay inside the scrollable dates, clear of the fixed hour gutter", right > left)
        val toLeft = forward != rtl
        val from = if (toLeft) right else left
        val to = if (toLeft) left else right
        assertTrue(device.swipe(from, y, to, y, 45))
        idle()
    }

    private fun eventNode(event: CalendarEvent): UiObject2 = scrollTo(By.res("timeline_event_${event.id}"))

    // Visible bounds are clipped at the viewport edge, so nudge the event fully into view before measuring it.
    private fun unclippedHeight(event: CalendarEvent): Int {
        eventNode(event)
        val step = device.displayHeight / 10
        repeat(8) {
            val area = node(By.res("calendar_timeline")).visibleBounds
            val bounds = readFresh { requireNotNull(device.findObject(By.res("timeline_event_${event.id}"))).visibleBounds }
            val clippedTop = bounds.top <= area.top + 2
            val clippedBottom = bounds.bottom >= area.bottom - 2
            if (!clippedTop && !clippedBottom) return bounds.height()
            check(!(clippedTop && clippedBottom)) { "The event is taller than the timeline viewport" }
            device.swipe(area.centerX(), area.centerY(), area.centerX(), area.centerY() + if (clippedTop) step else -step, 40)
            idle()
        }
        error("The whole timeline event could not be shown")
    }
    private fun scrollTo(selector: BySelector): UiObject2 {
        fun viewport(): Rect? = readFresh {
            (device.findObject(By.res("calendar_timeline"))
                ?: device.findObject(By.res("calendar_agenda"))
                ?: device.findObject(By.res("calendar_day_cards"))
                ?: device.findObjects(By.scrollable(true))
                    .filter { it.className != "android.widget.HorizontalScrollView" }
                    .maxByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() }
            )?.visibleBounds
        }
        fun visible() = readFresh {
            val area = viewport() ?: Rect(0, 0, device.displayWidth, device.displayHeight)
            device.findObject(selector)?.takeIf {
                val bounds = Rect(it.visibleBounds)
                bounds.intersect(area) && bounds.height() > 20 &&
                    area.contains(it.visibleCenter.x, it.visibleCenter.y) &&
                    bounds.centerY() in device.displayHeight / 8..device.displayHeight * 7 / 8
            }
        }
        fun move(down: Boolean) {
            val timeline = readFresh { device.findObject(By.res("calendar_timeline"))?.visibleBounds }
            if (timeline != null) {
                // The fixed hour rail stays clear of floating buttons and the sideways day columns.
                val inset = (16 * base.resources.displayMetrics.density).toInt()
                val x = if (rtl) timeline.right - inset else timeline.left + inset
                val upper = timeline.top + timeline.height() * 28 / 100
                val lower = timeline.top + timeline.height() * 72 / 100
                dragVertically(x, if (down) lower else upper, if (down) upper else lower)
            } else {
                val area = requireNotNull(viewport()) { "Required vertical scroll viewport is missing: $selector" }
                val x = area.left + area.width() / 3
                val upper = area.top + area.height() * 28 / 100
                val lower = area.top + area.height() * 72 / 100
                dragVertically(x, if (down) lower else upper, if (down) upper else lower)
            }
            idle()
        }
        visible()?.let { return it }
        repeat(12) {
            move(down = true)
            visible()?.let { return it }
        }
        repeat(24) {
            move(down = false)
            visible()?.let { return it }
        }
        error("Required synthetic UI content was not reachable: $selector")
    }

    private fun dragVertically(x: Int, from: Int, to: Int) {
        val started = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x.toFloat(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, from.toFloat())
        try {
            for (step in 1..24) {
                SystemClock.sleep(12)
                send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 24f)
            }
            SystemClock.sleep(150)
        } finally { send(MotionEvent.ACTION_UP, to.toFloat()) }
    }

    private fun launch() {
        activity = instrumentation.startActivitySync(Intent().setComponent(ComponentName(base.packageName, ComponentActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        installContent()
        idle()
    }

    private fun installContent(compactMonth: Boolean = false) {
        val current = requireNotNull(activity)
        val config = Configuration(current.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            if (rtl) setLayoutDirection(Locale.forLanguageTag("ar"))
        }
        val localized = object : ContextWrapper(app.createConfigurationContext(config)) {
            override fun getApplicationContext(): Context = app
        }
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(current, ViewModelProvider.AndroidViewModelFactory(app))[SunlithViewModel::class.java]
            current.setContent {
                CompositionLocalProvider(
                    LocalActivity provides current,
                    LocalActivityResultRegistryOwner provides current,
                    LocalContext provides localized,
                    LocalConfiguration provides config,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    if (compactMonth) {
                        CompositionLocalProvider(LocalDensity provides Density(2.625f, 1f)) {
                            Box(Modifier.fillMaxWidth().height(300.dp)) { SunlithApp(vm) }
                        }
                    } else SunlithApp(vm)
                }
            }
        }
    }

    private fun close() {
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        instrumentation.waitForIdleSync()
        activity = null
    }

    private fun recreate() {
        val previous = requireNotNull(activity)
        onMain { previous.recreate() }
        await("The fixture activity must actually be recreated") {
            var found: ComponentActivity? = null
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull { it !== previous && it.javaClass == ComponentActivity::class.java }
            }
            found?.let { activity = it } != null
        }
        installContent()
        idle()
    }

    private fun capture(state: String) {
        idle()
        val rendered = CountDownLatch(1)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback { Choreographer.getInstance().postFrameCallback { rendered.countDown() } }
        }
        assertTrue("The captured state must have rendered two frames", rendered.await(3, TimeUnit.SECONDS))
        SystemClock.sleep(200)
        val variant = requireNotNull(arguments.getString("readabilityCapture"))
        val folder = requireNotNull(base.getExternalFilesDir(null))
        val name = "readability-$variant-$state"
        assertTrue(device.takeScreenshot(File(folder, "$name.png")))
        device.dumpWindowHierarchy(File(folder, "$name.xml"))
        File(folder, "$name.json").writeText(JSONObject().put("state", state).put("language", language).put("rtl", rtl)
            .put("selectedDate", vm.selectedDate).put("visibleDays", vm.visibleWeekDays).put("swipeNavigation", vm.swipeNavigation)
            .put("fontScale", base.resources.configuration.fontScale).put("widthPixels", device.displayWidth)
            .put("heightPixels", device.displayHeight).put("syntheticCalendarId", calendarId)
            .put("courseSlots", JSONObject(vm.courseColorSlots)).toString(2))
    }

    private fun checked(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            if (failure !is org.junit.AssumptionViolatedException) runCatching {
                val folder = requireNotNull(base.getExternalFilesDir(null))
                val stem = "readability-failure-${System.currentTimeMillis()}"
                device.takeScreenshot(File(folder, "$stem.png"))
                device.dumpWindowHierarchy(File(folder, "$stem.xml"))
                File(folder, "$stem.txt").writeText(failure.stackTraceToString())
            }
            throw failure
        }
    }

    private fun text(id: Int, vararg args: Any) = screenContext.getString(id, *args)
    private fun node(selector: BySelector): UiObject2 = stableNode(selector)
    private fun stableNode(selector: BySelector, matches: (UiObject2) -> Boolean = { true }): UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var previous: Rect? = null
        var samples = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                val candidate = device.findObject(selector)
                val bounds = candidate?.visibleBounds
                if (candidate != null && bounds != null && !bounds.isEmpty && matches(candidate)) {
                    samples = if (bounds == previous) samples + 1 else 1
                    previous = Rect(bounds)
                    if (samples >= 3) return candidate
                } else { samples = 0; previous = null }
            } catch (_: StaleObjectException) { samples = 0; previous = null }
            SystemClock.sleep(60)
        }
        error("Required stable readability control was not found: $selector")
    }
    private fun <T> readFresh(read: () -> T): T {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (true) {
            try { return read() } catch (stale: StaleObjectException) {
                if (SystemClock.elapsedRealtime() >= deadline) throw stale
                instrumentation.waitForIdleSync()
                SystemClock.sleep(60)
            }
        }
    }
    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync(block); idle() }
    private fun idle() { instrumentation.waitForIdleSync(); device.waitForIdle() }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
        idle()
    }
    private fun at(day: LocalDate, minutes: Int) = day.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()

    private fun seedSchoolRows() {
        val items = buildList {
            add(SchoolItem("registration", "", if (language == "fr") "Date limite d'inscription" else "Registration deadline",
                "", "", "", "notice", friday, CalendarDates.allDayMillis(friday), CalendarDates.allDayMillis(friday.plusDays(1)),
                true, "UTC", emptyList(), emptyList(), emptyList(), false))
            courseTitles.forEachIndexed { index, title -> add(item("course-$index", "243-C$index", title, friday, (8 + index) * 60, (8 + index) * 60 + 50, "MON-C${100 + index}")) }
            for (offset in -4L..2L) if (offset != 0L) {
                val index = Math.floorMod(offset.toInt(), courseTitles.size)
                add(item("weekday-$offset", "243-C$index", courseTitles[index], friday.plusDays(offset), 9 * 60, 9 * 60 + 50, "MON-W${100 + index}"))
            }
            add(item("gym-1", "109-GYM", gymTitle, friday, 13 * 60 + 30, 14 * 60 + 20, "MON-B2410", absent = true))
            add(item("gym-2", "109-GYM", gymTitle, friday, 14 * 60 + 25, 15 * 60 + 15, "MON-B2422"))
            add(item("gym-3", "109-GYM", gymTitle, friday, 15 * 60 + 20, 16 * 60 + 10, "MON-DOUCHE"))
            add(item("absent", "340-ABSENT", if (language == "fr") "Philosophie et réflexion critique" else "Philosophy and critical thinking", friday, 17 * 60, 17 * 60 + 50, "MON-A300", absent = true))
            add(item("distant", "NEW-DISTANT", "Synthetic distant course", distant, 10 * 60, 11 * 60, "MON-D400"))
            add(item("distant-existing", "243-C0", courseTitles[0], distant, 12 * 60, 12 * 60 + 50, "MON-D410"))
        }
        val writer = SchoolCalendarWriter(app)
        val record = SchoolRecord("https://synthetic.invalid/ColNet/", "synthetic-readability", "synthetic-unused-password", zone.id,
            calendarId, account, prefix, "synthetic-unused-token", Long.MAX_VALUE, calendarIdentity = writer.calendarIdentity(calendarId))
        val term = SchoolTerm("READABILITY", "Synthetic readability term", friday.minusDays(20), distant.plusDays(20))
        val plan = writer.plan(record, SchoolFeed(account, term, items), term.start)
        val owned = writer.commit(record, plan)
        SchoolStore(app).save(record.copy(ledger = owned, baseline = items, initialized = true))
        assertEquals(items.size, CalendarRepository(app).storedEvents(setOf(calendarId)).size)
    }

    private fun item(id: String, course: String, title: String, day: LocalDate, start: Int, end: Int, room: String, absent: Boolean = false) =
        SchoolItem(id, "$course-$day", title, course, "00001", "T", "01", day, at(day, start), at(day, end), false, zone.id,
            listOf("Synthetic teacher"), listOf(room), if (id == "gym-1") listOf("Synthetic first-period absence only") else emptyList(), absent)
}
