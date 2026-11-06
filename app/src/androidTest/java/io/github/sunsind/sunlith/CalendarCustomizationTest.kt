package io.github.sunsind.sunlith

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
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
    fun customLayoutChangesAndPresetResetSurviveStartingANewActivity() {
        onMain {
            vm.applyLayoutPreset("planner")
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
        node(By.pkg(context.packageName).res("day_event_$firstId"))
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
    fun presetsRenderDifferentMonthAndDayViewsWithTheSameCalendarEvents() {
        onMain { vm.applyLayoutPreset("sunlith"); vm.changeViewMode(ViewMode.Month); vm.select(date) }
        node(By.pkg(context.packageName).res("calendar_grid"))
        node(By.pkg(context.packageName).res("calendar_agenda"))
        onMain { vm.changeViewMode(ViewMode.Day); vm.select(date) }
        node(By.pkg(context.packageName).res("day_event_$firstId"))

        onMain { vm.applyLayoutPreset("schedule") }
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
        onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Week); vm.select(date) }
        node(By.pkg(context.packageName).res("calendar_timeline"))
        val dates = date.minusDays(2).let { monday -> (0L until 7L).map { monday.plusDays(it) } }
        assertEquals(7, dates.count { device.hasObject(day(it)) })
        onMain { vm.changeVisibleWeekDays(3) }
        node(By.pkg(context.packageName).res("calendar_timeline"))
        awaitUi { dates.count { device.hasObject(day(it)) } == 3 }
        assertEquals("The three-day timeline must show only three selectable dates.", 3, dates.count { device.hasObject(day(it)) })
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
        onMain { vm.applyLayoutPreset("planner"); vm.changeViewMode(ViewMode.Week); vm.changeVisibleWeekDays(3); vm.select(start) }
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
        scrollDialogToText(titles.last()).click()
        awaitUi { (vm.stack.lastOrNull() as? Screen.Details)?.event?.id == ids.last() }
        node(By.pkg(context.packageName).textContains(titles.last()))
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
            if (!timeline.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break
            device.waitForIdle()
        }
        repeat(32) {
            refreshAccessibility()
            val found = device.findObject(selector)?.takeIf { it.visibleBounds.height() > 20 }
            val inset = (20 * context.resources.displayMetrics.density).toInt()
            val safeBottom = timelineScroller().visibleBounds.bottom - (96 * context.resources.displayMetrics.density).toInt()
            if (found != null && found.visibleBounds.top + minOf(found.visibleBounds.height() / 2, inset) < safeBottom) {
                val bounds = found.visibleBounds
                SystemClock.sleep(400)
                refreshAccessibility()
                device.findObject(selector)?.takeIf { it.visibleBounds == bounds }?.let { return it }
            } else {
                timelineScroller().scroll(Direction.DOWN, 0.18f, 500)
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

    private fun dominantFabColor(): Int {
        val bounds = node(By.pkg(context.packageName).desc(english.getString(R.string.action_new_event))).visibleBounds
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try {
            val area = Rect(bounds).apply { intersect(0, 0, screenshot.width, screenshot.height) }
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

    private fun launch() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        onMain { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java]; vm.select(date) }
        node(By.pkg(context.packageName).desc(english.getString(R.string.action_new_event)))
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
