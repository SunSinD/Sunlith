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
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.DarkPalette
import io.github.sunsind.sunlith.ui.LightPalette
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.gridStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import java.util.UUID
import java.io.File
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
        context = instrumentation.targetContext
        english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        device = UiDevice.getInstance(instrumentation)
        listOf("sunlith", "sunlith_ui").forEach { name -> snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)
            oldLocales = locales.applicationLocales
            locales.applicationLocales = LocaleList.forLanguageTags("en")
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
            button.click()
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
                node(By.pkg(context.packageName).res("calendar_today_$arrow")).click()
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
            node(By.pkg(context.packageName).res("calendar_today_$arrow")).click()
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
        val arrow = if (adjacent < current) "down" else "up"
        node(By.pkg(context.packageName).res("calendar_today_$arrow")).click()
        awaitUi { vm.month == current && vm.selectedDate == today &&
            !device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))) }
        captureMinor("grid-spillover-return")
    }

    @Test
    fun todayDirectionSurvivesBoundedRangesGridAndWeekLayoutDisposal() {
        val today = LocalDate.now()
        fun checkPeriod(name: String) {
            listOf(today.minusMonths(2) to "down", today.plusMonths(2) to "up").forEach { (shown, arrow) ->
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
        val arrow = if (anchor.isBefore(today)) "down" else "up"
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
        if (InstrumentationRegistry.getArguments().getString("captureMinor") != "true") return
        device.waitForIdle()
        SystemClock.sleep(350)
        val prefix = InstrumentationRegistry.getArguments().getString("minorEvidencePrefix") ?: "minor-v0.6.3"
        device.takeScreenshot(File(context.filesDir, "$prefix-$name.png"))
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
        assertEquals("timeline", vm.dayLayout)
        assertEquals(7, vm.visibleWeekDays)
        assertEquals("week", vm.agendaRange)
        assertTrue(vm.showNavigationArrows)
        assertFalse(vm.swipeNavigation)
        node(By.pkg(context.packageName).res("calendar_timeline"))
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
    fun presetsRenderDifferentMonthAndDayViewsWithTheSameCalendarEvents() {
        onMain { vm.applyLayoutPreset("sunlith"); vm.changeViewMode(ViewMode.Month); vm.select(date) }
        node(By.pkg(context.packageName).res("calendar_grid"))
        node(By.pkg(context.packageName).res("calendar_agenda"))
        onMain { vm.changeViewMode(ViewMode.Day); vm.select(date) }
        node(By.pkg(context.packageName).res("calendar_timeline"))

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
        node(By.pkg(context.packageName).res("calendar_timeline"))
        val event = revealTimelineEvent(firstId)
        assertTrue(event.isEnabled)
        assertFalse(device.hasObject(By.pkg(context.packageName).res("calendar_schedule")))
    }

    @Test
    fun gridDateOpensItsLateTimelineEventWithoutScrollingAndBackRestoresTheMonth() {
        val target = date.plusDays(2)
        val late = addTimed(target, "Late evening drill-down marker", 20, 21)
        onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Month); vm.select(date); vm.reload() }
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
        assertFalse(device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))))
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
    fun legacyGridCardsUpgradeOpensTheTimelineAndLaterCardsSurviveRecreation() {
        val target = date.plusDays(2)
        val late = addTimed(target, "Upgraded grid afternoon event", 16, 18)
        finish()
        context.getSharedPreferences("sunlith", Context.MODE_PRIVATE).edit()
            .remove("day_timeline_default_v1").putString("day_layout", "cards")
            .putString("month_layout", "full").putString("week_layout", "list")
            .putString("layout_preset", "custom").putString("start_view", "Month").commit()
        launch()
        assertEquals("timeline", vm.dayLayout)
        assertEquals("list", vm.weekLayout)
        assertEquals("full", vm.monthLayout)
        awaitUi { vm.events.any { it.id == late } }
        node(day(target)).click()
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target }
        node(By.pkg(context.packageName).res("calendar_timeline"))
        awaitUi { device.findObject(By.pkg(context.packageName).res("timeline_event_$late"))?.visibleBounds?.height()?.let { it > 20 } == true }
        device.pressBack()
        awaitUi { vm.viewMode == ViewMode.Month && vm.selectedDate == date }
        onMain { vm.changeDayLayout("cards"); vm.changeMonthLayout("split"); vm.changeMonthLayout("full") }
        recreateActivity()
        assertEquals("cards", vm.dayLayout)
        assertEquals("list", vm.weekLayout)
        node(day(target)).click()
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target }
        node(By.pkg(context.packageName).res("calendar_day_cards"))
        node(By.pkg(context.packageName).res("day_event_$late"))
        recreateActivity()
        assertEquals("cards", vm.dayLayout)
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
        onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Month); vm.select(date); vm.reload() }
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
        onMain { vm.changeMonthLayout("full"); vm.reload() }
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
    fun openingADateAnimatesToTheFirstEventForEveryDayLayout() {
        val target = date.plusDays(3)
        repeat(16) { i ->
            repository.saveEvent(EventDraft(calendarId = calendarId, title = "Motion all-day entry $i",
                startMillis = CalendarDates.allDayMillis(target), endMillis = CalendarDates.allDayMillis(target.plusDays(1)),
                allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
        }
        val late = addTimed(target, "Motion first timed event", 20, 21)
        val simultaneous = addTimed(target, "Motion simultaneous timed event", 20, 22)
        val capture = InstrumentationRegistry.getArguments().getString("captureMotion") == "true"
        listOf("cards", "timeline", "schedule").forEach { layout ->
            onMain {
                vm.applyLayoutPreset("planner")
                vm.changeDayLayout(layout)
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
                "schedule" -> "schedule_event_$late"
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
    }

    @Test
    fun dayFocusIsConsumedAcrossRecreationAndSwipesKeepTheChosenHour() {
        val target = date.plusDays(2)
        val late = addTimed(target, "Focused evening event", 20, 21)
        val next = addTimed(target.plusDays(1), "Next evening event", 20, 21)
        onMain { vm.applyLayoutPreset("planner"); vm.openDay(target); vm.reload() }
        awaitUi { vm.events.any { it.id == late } }
        fun visible(id: Long): Boolean = device.findObject(By.pkg(context.packageName).res("timeline_event_$id"))
            ?.visibleBounds?.height()?.let { it > 20 } == true
        awaitUi { visible(late) }
        timelineScroller().scroll(Direction.UP, 1f, 500)
        device.waitForIdle()
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
            onMain { vm.applyLayoutPreset("planner"); vm.changeDayLayout(layout); vm.changeViewMode(ViewMode.Month); vm.select(date); vm.reload() }
            awaitUi { vm.events.any { it.id == late } }
            val dateCell = node(day(target)).visibleBounds
            device.click(dateCell.centerX(), dateCell.top + (14 * context.resources.displayMetrics.density).toInt())
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
            device.pressBack()
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
        val eventSelector = By.pkg(context.packageName).res("schedule_event_$event")
        awaitUi { device.findObject(eventSelector)?.visibleBounds?.height()?.let { it > 30 } == true }
        onMain { vm.selectAndFocus(distant.plusDays(1)) }
        val nextSelector = By.pkg(context.packageName).res("schedule_event_$next")
        awaitUi { device.findObject(nextSelector)?.visibleBounds?.height()?.let { it > 30 } == true }
        assertEquals(distant.plusDays(1), vm.selectedDate)
        assertTrue("Programmatic event positioning must not page the agenda repeatedly.", !requireNotNull(vm.loadedTo).isAfter(distant.plusDays(120)))
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
            val labels = listOf(R.string.action_back, R.string.action_prev_day, R.string.action_next_day, R.string.title_settings)
            val bounds = labels.map { label ->
                generateSequence(node(By.pkg(context.packageName).desc(localized.getString(label)))) { it.parent }
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
        onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Month); vm.select(date); vm.reload() }
        awaitUi { vm.events.any { it.id == eventId } }
        node(day(target)).longClick()
        assertFalse("Long press must not show the removed preview.", device.hasObject(By.pkg(context.packageName).text("Open day")))
        assertFalse(device.hasObject(By.pkg(context.packageName).textContains("Complete title ending marker")))
        if (vm.viewMode == ViewMode.Day) device.pressBack()
        awaitUi { vm.viewMode == ViewMode.Month && vm.selectedDate == date }
        node(day(target)).click()
        awaitUi { vm.viewMode == ViewMode.Day && vm.selectedDate == target && vm.hasDayParent }
        node(By.pkg(context.packageName).res("calendar_timeline"))
        revealTimelineEvent(eventId).click()
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
        onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Day); vm.select(date) }
        revealTimelineEvent(firstId)
        repeat(8) {
            if (device.findObject(By.pkg(context.packageName).res("timeline_event_$secondId"))?.visibleBounds?.height()?.let { height -> height > 20 } != true) {
                timelineScroller().scroll(Direction.DOWN, 0.18f)
            }
        }
        val first = node(By.pkg(context.packageName).res("timeline_event_$firstId")).visibleBounds
        val second = node(By.pkg(context.packageName).res("timeline_event_$secondId")).visibleBounds
        assertTrue("Overlapping events must occupy different horizontal lanes: $first / $second", first.right <= second.left || second.right <= first.left)
        assertTrue("Events whose times overlap must overlap vertically: $first / $second", first.top < second.bottom && second.top < first.bottom)
        assertTrue("Both overlapping events need usable tap targets.", first.width() > 20 && second.width() > 20 && first.height() > 20 && second.height() > 20)
        val allDay = node(By.pkg(context.packageName).res("calendar_timeline_all_day")).visibleBounds
        val hours = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
        assertTrue("All-day events belong above the hourly scroll area.", allDay.bottom <= hours.top)
        node(By.pkg(context.packageName).descContains(allDayTitle))
        if (InstrumentationRegistry.getArguments().getString("captureFinal") == "true") {
            timelineScroller().scroll(Direction.DOWN, 0.4f)
            device.waitForIdle()
        }
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
        onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Day); vm.select(date); vm.reload() }
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
        onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Day); vm.select(date); vm.reload() }
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
        val selector = By.pkg(context.packageName).text(text)
        repeat(32) {
            refreshAccessibility()
            device.findObject(selector)?.takeIf { it.visibleBounds.height() > 20 }?.let { return it }
            node(By.pkg(context.packageName).scrollable(true)).scroll(Direction.DOWN, 0.7f)
        }
        error("The overflow chooser did not expose the hidden event: $text")
    }

    private fun day(value: LocalDate): BySelector = By.pkg(context.packageName).descStartsWith(Formats(english, Locale.ENGLISH).dayLong(value) + ",")

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
        repeat(32) {
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
                timelineScroller().scroll(Direction.DOWN, 0.18f, 500)
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

    private fun launch(locale: Locale = Locale.ENGLISH) {
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

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            refreshAccessibility()
            if (condition()) return
            SystemClock.sleep(100)
        }
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "customization-state.xml"))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "customization-state.png"))
        assertTrue("The expected customized calendar must be rendered.", condition())
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
