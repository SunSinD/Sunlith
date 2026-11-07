package io.github.sunsind.sunlith

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.LocaleList
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.rememberMotion
import io.github.sunsind.sunlith.ui.SlidingPeriod
import io.github.sunsind.sunlith.ui.periodSwipe
import androidx.compose.ui.layout.onSizeChanged
import io.github.sunsind.sunlith.ui.PagedPeriod
import io.github.sunsind.sunlith.ui.rememberPeriodSwipe
import io.github.sunsind.sunlith.ui.awaitFocusStart
import io.github.sunsind.sunlith.ui.scrollToFocus
import io.github.sunsind.sunlith.ui.focusTarget
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
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
class CalendarRefinementTest {
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
    private lateinit var vm: SunlithViewModel
    private var activity: ComponentActivity? = null
    private var oldLocales: LocaleList? = null
    private var calendarId = 0L
    private val account = "Sunlith refinement test ${UUID.randomUUID()}"
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private val marker = "Swipe fixture "

    @Before
    fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        device = UiDevice.getInstance(instrumentation)
        listOf("sunlith", "sunlith_ui").forEach { name ->
            snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).let { locales ->
                oldLocales = locales.applicationLocales
                locales.applicationLocales = LocaleList.forLanguageTags("en")
            }
        }
        calendarId = insertLocalCalendar(context, "sunlith-refinement-test", "Refinement calendar", account)
        context.getSharedPreferences("sunlith", Context.MODE_PRIVATE).edit().putBoolean("keep_selected_date", false).commit()
        SettingsStore(context).apply {
            selectedCalendarIds = setOf(calendarId)
            hasChosenCalendars = true
            startView = "Month"
            themeMode = "dark"
            materialYou = false
            remindersEnabled = false
            monthLayout = "split"
            agendaRange = "month"
            agendaDisplay = "all"
            collapseCalendarOnScroll = false
            swipeNavigation = true
            showNavigationArrows = false
            navigationAnimation = false
            dayLayout = "cards"
        }
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("ai_intro_done", true).putString("view_mode", "Month")
            .putLong("last_calendar", calendarId).commit()
        val repository = CalendarRepository(context)
        val today = LocalDate.now()
        repeat(24) { index ->
            repository.saveEvent(EventDraft(
                calendarId = calendarId, title = marker + index.toString().padStart(2, '0'),
                startMillis = CalendarDates.allDayMillis(today),
                endMillis = CalendarDates.allDayMillis(today.plusDays(1)),
                allDay = true, timeZone = "UTC", reminderMinutes = emptyList(),
            ))
        }
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as ComponentActivity
        onMain { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java] }
        node(By.pkg(context.packageName).textStartsWith(marker))
    }

    @Test
    fun switchingAnyViewDefaultsToTodayAndSelectingTheCurrentViewDoesNothing() {
        val distant = LocalDate.now().plusMonths(5).withDayOfMonth(17)
        context.getSharedPreferences("sunlith", Context.MODE_PRIVATE).edit().remove("keep_selected_date").commit()
        assertFalse("Existing installs must default to today", SettingsStore(context).keepSelectedDate)
        onMain {
            assertFalse(vm.keepSelectedDate)
            ViewMode.entries.forEach { from ->
                ViewMode.entries.filter { it != from }.forEach { to ->
                    vm.changeViewMode(from)
                    vm.select(distant)
                    vm.changeViewMode(to)
                    assertEquals("$from to $to must use today by default", vm.today, vm.selectedDate)
                }
            }
            vm.select(distant)
            val request = vm.dayFocusRequest
            vm.changeViewMode(vm.viewMode)
            assertEquals(distant, vm.selectedDate)
            assertEquals(request, vm.dayFocusRequest)
        }
    }

    @Test
    fun keepingTheDatePersistsAndExplicitDayBackAndRecreationHonorIt() {
        val chosen = LocalDate.now().plusMonths(3).withDayOfMonth(12)
        onMain {
            vm.changeKeepSelectedDate(true)
            vm.select(chosen)
            listOf(ViewMode.Week, ViewMode.Day, ViewMode.Month).forEach {
                vm.changeViewMode(it)
                assertEquals(chosen, vm.selectedDate)
            }
            assertTrue(SettingsStore(context).keepSelectedDate)
            vm.openDay(chosen.plusDays(1))
        }
        awaitState("An explicit Day open must consume its focus") { !vm.pendingDayFocus }
        val request = vm.dayFocusRequest
        val previous = requireNotNull(activity)
        instrumentation.runOnMainSync { previous.recreate() }
        awaitState("The recreated calendar must resume") {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull { it !== previous }?.let {
                    activity = it
                    vm = ViewModelProvider(it)[SunlithViewModel::class.java]
                    true
                } ?: false
        }
        onMain {
            assertTrue(vm.keepSelectedDate)
            assertEquals(chosen.plusDays(1), vm.selectedDate)
            assertEquals(request, vm.dayFocusRequest)
            assertFalse("Recreation must not replay a consumed focus", vm.pendingDayFocus)
            vm.reload()
            assertFalse(vm.pendingDayFocus)
            vm.back()
            assertEquals(ViewMode.Month, vm.viewMode)
            assertEquals(chosen, vm.selectedDate)
        }
    }

    @Test
    fun todayFocusUsesTheFirstTimedEventEvenIfItHasAlreadyEnded() {
        val day = LocalDate.now()
        val zone = ZoneId.systemDefault()
        fun event(id: Long, hour: Int) = CalendarEvent(id, calendarId, "Event $id", "", "",
            day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(hour, 30).atZone(zone).toInstant().toEpochMilli(), false, zone.id)
        val first = event(1, 0)
        assertEquals(first, focusTarget(listOf(event(2, 23), first), day, zone))
    }

    @Test
    fun adjacentMonthsReuseTheLoadedSnapshotAndRefillBeforeItsBoundary() {
        onMain { vm.changeMonthLayout("full"); vm.reload() }
        awaitState("The initial data buffer must finish loading") { !vm.loadingEvents && vm.loaded }
        val original = vm.month
        fun start(month: YearMonth) = io.github.sunsind.sunlith.ui.gridStart(month, vm.weekStartsMonday)
        assertEquals(start(original.minusMonths(2)), vm.gridLoadedFrom)
        assertEquals(start(original.plusMonths(2)).plusDays(41), vm.gridLoadedTo)
        val index = vm.eventsByDay
        val from = vm.gridLoadedFrom
        val to = vm.gridLoadedTo
        onMain {
            vm.step(1)
            assertFalse("The adjacent month and its neighbors are already covered", vm.loadingEvents)
            assertTrue("A covered navigation must retain the day index", index === vm.eventsByDay)
        }
        assertEquals(from, vm.gridLoadedFrom)
        assertEquals(to, vm.gridLoadedTo)
        onMain { vm.step(1) }
        awaitState("Refill must finish before the next neighbor is missing") {
            !vm.loadingEvents && vm.gridLoadedFrom == start(original) && vm.gridLoadedTo == start(original.plusMonths(4)).plusDays(41)
        }
        onMain { vm.step(-2) }
        awaitState("Returning across the buffer boundary must restore earlier coverage") {
            !vm.loadingEvents && vm.gridLoadedFrom == from && vm.gridLoadedTo == to
        }
        assertTrue(vm.coversRange(start(original.minusMonths(1)), start(original.plusMonths(1)).plusDays(41)))
    }

    @Test
    fun theContinuousHeaderRetainsTheLandedAndReverseCompositions() {
        var period by mutableLongStateOf(0)
        val mounts = mutableMapOf<Long, Int>()
        val disposals = mutableMapOf<Long, Int>()
        onMain {
            requireNotNull(activity).setContent {
                val swipe = rememberPeriodSwipe(true, true) { period += it }
                SlidingPeriod(swipe, period, Modifier.fillMaxSize().onSizeChanged { swipe.width = it.width }
                    .periodSwipe(swipe).semantics { testTagsAsResourceId = true }.testTag("sliding_identity_fixture")) { absolute ->
                    DisposableEffect(absolute) {
                        mounts[absolute] = mounts.getOrDefault(absolute, 0) + 1
                        onDispose { disposals[absolute] = disposals.getOrDefault(absolute, 0) + 1 }
                    }
                    Box(Modifier.fillMaxSize()) { Text("Period $absolute") }
                }
            }
        }
        val bounds = node(By.pkg(context.packageName).res("sliding_identity_fixture")).visibleBounds
        SystemClock.sleep(300)
        horizontalSwipe(bounds, true)
        awaitState("The header must settle forward") { period == 1L }
        onMain {
            assertEquals("The incoming header must retain its composition", 1, mounts[1L])
            assertEquals("The previous header must be retained for reversal", 1, mounts[0L])
            assertEquals(0, disposals.getOrDefault(0L, 0))
        }
        horizontalSwipe(bounds, false)
        awaitState("The header must settle back") { period == 0L }
        onMain { assertEquals("Reversal must reuse its original composition", 1, mounts[0L]) }
    }

    @Test
    fun theIncomingPagerCompositionSurvivesLanding() {
        var period by mutableLongStateOf(0)
        val mounts = mutableMapOf<Long, Int>()
        val disposals = mutableMapOf<Long, Int>()
        onMain {
            requireNotNull(activity).setContent {
                val swipe = rememberPeriodSwipe(true, true) { period += it }
                PagedPeriod(swipe, period, Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("pager_identity_fixture")) { absolute ->
                    DisposableEffect(absolute) {
                        mounts[absolute] = mounts.getOrDefault(absolute, 0) + 1
                        onDispose { disposals[absolute] = disposals.getOrDefault(absolute, 0) + 1 }
                    }
                    Box(Modifier.fillMaxSize()) { Text("Period $absolute") }
                }
            }
        }
        val bounds = node(By.pkg(context.packageName).res("pager_identity_fixture")).visibleBounds
        horizontalSwipe(bounds, true)
        awaitState("The incoming period must settle") { period == 1L }
        onMain {
            assertEquals("Landing must retain the incoming composition", 1, mounts[1L])
            assertEquals("Landing must not dispose the incoming composition", 0, disposals.getOrDefault(1L, 0))
        }
    }

    @Test
    fun aLongDayFocusRendersAtTopAndScrollsWithoutTeleporting() {
        val done = AtomicBoolean(false)
        val viewport = AtomicInteger(0)
        val positions = mutableListOf<Int>()
        onMain {
            requireNotNull(activity).setContent {
                val list = rememberLazyListState()
                LaunchedEffect(list) {
                    awaitFocusStart(true)
                    list.scrollToFocus(140, true)
                    done.set(true)
                }
                LaunchedEffect(list) {
                    while (!done.get()) {
                        withFrameNanos { }
                        val info = list.layoutInfo
                        info.visibleItemsInfo.firstOrNull()?.let { item ->
                            viewport.set(info.viewportEndOffset - info.viewportStartOffset)
                            positions += list.firstVisibleItemIndex * item.size + list.firstVisibleItemScrollOffset
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(180) { Text("Focus item $it", Modifier.height(64.dp)) }
                }
            }
        }
        awaitState("A long focus must finish") { done.get() }
        onMain {
            assertTrue("The top must be rendered before motion", positions.takeWhile { it == 0 }.size >= 2)
            assertTrue("The controlled fixture must actually scroll", positions.last() > viewport.get() * 3)
            assertTrue("No frame may teleport a viewport", positions.zipWithNext().all { (a, b) -> abs(b - a) < viewport.get() })
        }
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        instrumentation.waitForIdleSync()
        if (calendarId != 0L) context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
        snapshots.forEach { (name, values) ->
            restoreTestPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values)
        }
        if (Build.VERSION.SDK_INT >= 33) oldLocales?.let {
            context.getSystemService(LocaleManager::class.java).applicationLocales = it
        }
    }

    @Test
    fun systemMotionPreferenceUpdatesWithoutRecreatingTheActivity() {
        val original = device.executeShellCommand("settings get global animator_duration_scale").trim()
        val motion = AtomicBoolean(false)
        try {
            device.executeShellCommand("settings put global animator_duration_scale 1")
            onMain { requireNotNull(activity).setContent { motion.set(rememberMotion(true)) } }
            awaitState("System motion must initially be enabled") { motion.get() }
            device.executeShellCommand("settings put global animator_duration_scale 0")
            awaitState("Removing animations must update the existing composition") { !motion.get() }
            device.executeShellCommand("settings put global animator_duration_scale 1")
            awaitState("Restoring animations must update the existing composition") { motion.get() }
        } finally {
            device.executeShellCommand(if (original == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $original")
        }
    }

    @Test
    fun diagonalSwipesOverTheAgendaPageInBothDirectionsRepeatedly() {
        val original = vm.month
        repeat(3) {
            diagonalSwipe(forward = true)
            awaitMonth(original.plusMonths(1))
            diagonalSwipe(forward = false)
            awaitMonth(original)
        }
        assertEquals(ViewMode.Month, vm.viewMode)
        assertTrue(vm.editor == null)
    }

    @Test
    fun theFirstMovePastSlopCanCommitAMonthWithoutAnotherMove() {
        onMain { vm.changeMonthLayout("full") }
        val original = vm.month
        val bounds = gridBounds()
        moveOnce(
            Point(bounds.left + bounds.width() * 4 / 5, bounds.centerY()),
            Point(bounds.left + bounds.width() * 2 / 5, bounds.centerY()),
        )
        awaitMonth(original.plusMonths(1))
        assertEquals(ViewMode.Month, vm.viewMode)
        assertFalse("A swipe across date cells must not open a day.", vm.hasDayParent)
    }

    @Test
    fun curvedVerticalScrollingStaysVerticalAndACancelledSwipeKeepsItsPosition() {
        val original = vm.month
        val before = visibleRows().first()
        val bounds = agendaBounds()
        val x = bounds.left + bounds.width() / 6
        val y = bounds.bottom - bounds.height() / 5
        val vertical = minOf((48 * context.resources.displayMetrics.density).toInt(), bounds.height() / 4)
        assertTrue(device.swipe(arrayOf(
            Point(x, y),
            Point(x, y - vertical),
            Point(bounds.right - bounds.width() / 6, y - vertical - bounds.height() / 5),
        ), 24))
        device.waitForIdle()
        assertEquals("A vertical drag that bends sideways must keep the overview month.", original, vm.month)
        val moved = stableBounds(By.pkg(context.packageName).text(before.first))
        assertTrue("The gesture must actually scroll the agenda.", moved == null || moved.top < before.second.top - 10)
        val anchor = visibleRows().let { it[it.size / 2] }
        val scrolled = agendaBounds()
        moveOnce(
            Point(scrolled.centerX(), scrolled.centerY()),
            Point(scrolled.centerX() - scrolled.width() / 8, scrolled.centerY()),
            holdAfterMove = 250,
        )
        assertEquals("A short, slow sideways drag must snap back.", original, vm.month)
        val after = requireNotNull(stableBounds(By.pkg(context.packageName).text(anchor.first)))
        assertTrue("Snapping back must preserve the agenda's scroll position: ${anchor.second} / $after", abs(anchor.second.top - after.top) <= 2)
        assertEquals(ViewMode.Month, vm.viewMode)
        assertTrue(vm.editor == null)
    }

    @Test
    fun fullMonthSupportsRepeatedAnimatedSwipesWithoutOpeningDates() {
        onMain { vm.changeMonthLayout("full"); vm.changeNavigationAnimation(true) }
        val original = vm.month
        listOf(1L, 2L, 1L, 0L, -1L, 0L).forEach { offset ->
            val forward = original.plusMonths(offset).isAfter(vm.month)
            horizontalSwipe(gridBounds(), forward)
            awaitMonth(original.plusMonths(offset))
            assertEquals(ViewMode.Month, vm.viewMode)
            assertFalse(vm.hasDayParent)
            assertTrue(vm.editor == null)
        }
    }

    @Test
    fun fullMonthAcceptsRepeatedDiagonalFlingsInBothDirections() {
        onMain { vm.changeMonthLayout("full"); vm.changeNavigationAnimation(true) }
        val original = vm.month
        repeat(3) {
            diagonalSwipe(true, gridBounds())
            awaitMonth(original.plusMonths(1))
            diagonalSwipe(false, gridBounds())
            awaitMonth(original)
        }
        assertFalse(vm.hasDayParent)
        assertTrue(vm.editor == null)
    }

    @Test
    fun denseFullMonthReversalsKeepTheirDatesInRtlAndWithReducedMotion() {
        val repository = CalendarRepository(context)
        val start = LocalDate.now().withDayOfMonth(1).minusDays(7)
        repeat(76) { index ->
            val day = start.plusDays(index.toLong())
            repeat(3) { slot ->
                repository.saveEvent(EventDraft(
                    calendarId = calendarId, title = "Dense grid $index / $slot",
                    startMillis = CalendarDates.allDayMillis(day),
                    endMillis = CalendarDates.allDayMillis(day.plusDays(1)),
                    allDay = true, timeZone = "UTC", reminderMinutes = emptyList(),
                ))
            }
        }
        onMain { vm.changeMonthLayout("full"); vm.changeNavigationAnimation(true); vm.changeShowNavigationArrows(true); vm.reload() }
        awaitState("The dense grid must load real provider events") { vm.eventsByDay.values.flatten().any { it.title.startsWith("Dense grid") } }
        if (Build.VERSION.SDK_INT >= 33) {
            val previous = requireNotNull(activity)
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("ar")
            awaitState("The RTL activity must resume") {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { it !== previous }?.let {
                        activity = it
                        vm = ViewModelProvider(it)[SunlithViewModel::class.java]
                        true
                    } ?: false
            }
        }
        val rtl = context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val localized = requireNotNull(activity).resources
        val original = vm.month
        val scale = device.executeShellCommand("settings get global animator_duration_scale").trim()
        try {
            listOf(false, true).forEach { reduced ->
                device.executeShellCommand("settings put global animator_duration_scale ${if (reduced) 0 else 1}")
                SystemClock.sleep(150)
                listOf(1L, 0L, 1L, 0L).forEachIndexed { index, offset ->
                    horizontalSwipe(gridBounds(), forward = (offset == 1L) != rtl)
                    awaitMonth(original.plusMonths(offset))
                    assertEquals(ViewMode.Month, vm.viewMode)
                    assertFalse(vm.hasDayParent)
                    assertTrue(vm.editor == null)
                    SystemClock.sleep(300)
                    assertTrue(device.takeScreenshot(File(context.filesDir, "dense-reversal-${if (rtl) "rtl" else "ltr"}-$reduced-$index.png")))
                }
                val next = node(By.pkg(context.packageName).desc(localized.getString(R.string.action_next_month))).visibleBounds
                val previous = node(By.pkg(context.packageName).desc(localized.getString(R.string.action_prev_month))).visibleBounds
                listOf(next, previous, next, previous).forEach { assertTrue(device.click(it.centerX(), it.centerY())) }
                SystemClock.sleep(650)
                awaitMonth(original)
            }
        } finally {
            device.executeShellCommand(if (scale == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $scale")
        }
    }

    @Test
    fun timelineAlreadyAtAllDayFocusAllowsImmediateHorizontalPaging() {
        onMain { vm.changeNavigationAnimation(true); vm.changeDayLayout("timeline"); vm.changeViewMode(ViewMode.Day) }
        awaitState("The timeline must have loaded data") { vm.loaded && !vm.loadingEvents }
        val area = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
        assertTrue(device.takeScreenshot(File(context.filesDir, "timeline-all-day-immediate-swipe.png")))
        SystemClock.sleep(300)
        val original = vm.selectedDate
        horizontalSwipe(area, true)
        awaitState("An already-focused timeline must accept the first horizontal gesture") { vm.selectedDate == original.plusDays(1) }
        horizontalSwipe(node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds, false)
        awaitState("The timeline must allow immediate reversal") { vm.selectedDate == original }
    }

    @Test
    fun weekTimelinesAlreadyAtTodayAllowImmediateHorizontalPagingAndReversal() {
        listOf(3, 7).forEach { days ->
            onMain {
                vm.changeNavigationAnimation(true)
                vm.changeWeekLayout("timeline")
                vm.changeVisibleWeekDays(days)
                vm.changeViewMode(ViewMode.Week)
                vm.goToday()
            }
            awaitState("The $days-day timeline must load today") { vm.loaded && !vm.loadingEvents && vm.selectedDate == vm.today }
            SystemClock.sleep(650)
            val original = vm.selectedDate
            fun area() = requireNotNull(stableBounds(By.pkg(context.packageName).res("calendar_timeline")))
            val todayArea = area()
            horizontalSwipe(area(), true)
            awaitState("The $days-day timeline must page away") { vm.selectedDate == original.plusDays(days.toLong()) }
            val ready = java.util.concurrent.CountDownLatch(1)
            val requestedAt = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                vm.goToday()
                Choreographer.getInstance().postFrameCallback {
                    Choreographer.getInstance().postFrameCallback { ready.countDown() }
                }
            }
            assertTrue("Today must render without waiting for UI idleness", ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val delay = SystemClock.uptimeMillis() - requestedAt
            assertTrue("The first gesture must start inside the former 300 ms no-op animation: $delay ms", delay < 250)
            horizontalSwipe(todayArea, true)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SUNLITH_TODAY_SWIPE_DELAY_MS=$days:$delay\n") })
            awaitState("The first gesture immediately after Today must page the $days-day timeline") { vm.selectedDate == original.plusDays(days.toLong()) }
            horizontalSwipe(area(), false)
            awaitState("The $days-day timeline must immediately reverse after Today") { vm.selectedDate == original }
        }
    }

    @Test
    fun fullMonthLandingReportsRealFrameDurations() {
        val layout = InstrumentationRegistry.getArguments().getString("pagingLayout", "grid")
        onMain {
            vm.changeNavigationAnimation(true)
            when (layout) {
                "agenda", "continuous" -> { vm.changeMonthLayout("split"); vm.changeAgendaRange(if (layout == "continuous") "continuous" else "month") }
                "week-list" -> { vm.changeWeekLayout("list"); vm.changeVisibleWeekDays(7); vm.changeViewMode(ViewMode.Week) }
                "week-timeline", "week-three-timeline" -> { vm.changeWeekLayout("timeline"); vm.changeVisibleWeekDays(if (layout == "week-three-timeline") 3 else 7); vm.changeViewMode(ViewMode.Week) }
                "day-cards", "day-timeline" -> { vm.changeDayLayout(if (layout == "day-cards") "cards" else "timeline"); vm.changeViewMode(ViewMode.Day) }
                else -> vm.changeMonthLayout("full")
            }
        }
        fun bounds(): Rect = when (layout) {
            "grid" -> gridBounds()
            "agenda", "continuous" -> Rect(device.displayWidth / 12, device.displayHeight / 5, device.displayWidth * 11 / 12, device.displayHeight / 2)
            "week-list" -> Rect(device.displayWidth / 12, device.displayHeight / 6, device.displayWidth * 11 / 12, device.displayHeight / 3)
            "day-cards" -> node(By.pkg(context.packageName).res("calendar_day_cards")).visibleBounds
            "day-timeline", "week-timeline", "week-three-timeline" -> node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
            else -> error("Unknown paging layout $layout")
        }
        fun expected(original: LocalDate, offset: Long): LocalDate = when (layout) {
            "week-three-timeline" -> original.plusDays(offset * 3)
            "week-list", "week-timeline" -> original.plusWeeks(offset)
            "day-cards", "day-timeline" -> original.plusDays(offset)
            else -> YearMonth.from(original).plusMonths(offset).let { if (it == YearMonth.from(LocalDate.now())) LocalDate.now() else it.atDay(1) }
        }
        awaitState("The benchmark must start from loaded data") { vm.loaded && !vm.loadingEvents }
        val initialBounds = bounds()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SUNLITH_PAGING_INITIAL=$layout bounds=$initialBounds screen=${device.displayWidth}x${device.displayHeight}\n") })
        assertTrue(device.takeScreenshot(File(context.filesDir, "paging-initial-$layout.png")))
        SystemClock.sleep(300)
        val originalDate = vm.selectedDate
        // Warm both directions before collecting any measured frames.
        listOf(1L, 0L).forEach { offset ->
            val date = expected(originalDate, offset)
            horizontalSwipe(bounds(), date.isAfter(vm.selectedDate))
            awaitState("Warm-up $layout must settle on $date (actual ${vm.selectedDate}, mode ${vm.viewMode}, swipe ${vm.swipeNavigation})") { vm.selectedDate == date }
        }
        val generationField = SunlithViewModel::class.java.getDeclaredField("loadGeneration").apply { isAccessible = true }
        val generationBefore = generationField.getLong(vm)
        val gridDaysBefore = java.time.temporal.ChronoUnit.DAYS.between(vm.gridLoadedFrom, vm.gridLoadedTo) + 1
        val starts = mutableListOf<Long>()
        val frames = Collections.synchronizedList(mutableListOf<LongArray>())
        val commits = mutableListOf<Long>()
        val worker = HandlerThread("calendar-frame-fixture").apply { start() }
        val window = requireNotNull(activity).window
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += longArrayOf(
                metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
                metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION),
                metrics.getMetric(FrameMetrics.DRAW_DURATION),
                metrics.getMetric(FrameMetrics.SYNC_DURATION),
                metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1,
            )
        }
        var tracking = true
        var previous = vm.selectedDate
        val observer = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!tracking) return
                if (vm.selectedDate != previous) { commits += frameTimeNanos; previous = vm.selectedDate }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        onMain {
            window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper))
            Choreographer.getInstance().postFrameCallback(observer)
        }
        try {
            listOf(1L, 2L, 1L, 0L, -1L, 0L).forEach { offset ->
                val date = expected(originalDate, offset)
                val area = bounds()
                starts += System.nanoTime()
                horizontalSwipe(area, date.isAfter(vm.selectedDate))
                awaitState("Measured swipe must settle on $date") { vm.selectedDate == date }
                assertFalse(vm.hasDayParent)
            }
        } finally {
            onMain {
                tracking = false
                Choreographer.getInstance().removeFrameCallback(observer)
                window.removeOnFrameMetricsAvailableListener(listener)
            }
            worker.quitSafely()
            worker.join(2_000)
        }
        val measured = synchronized(frames) { frames.toList() }
        assertEquals("Each gesture must commit exactly once", 6, commits.size)
        assertTrue("Real rendered frames must be measured", measured.isNotEmpty())
        val landing = measured.filter { frame -> commits.any { frame[0] in (it - 50_000_000L)..(it + 250_000_000L) } }
        assertTrue("The report must include landing frames", landing.isNotEmpty())
        val durations = landing.map { it[1] }.sorted()
        val report = JSONObject().put("fixture", "24 all-day events; 2 warmup then 6 alternating swipes").put("layout", layout).put("dragStartsNs", JSONArray(starts))
            .put("reloadsDuringMeasuredSwipes", generationField.getLong(vm) - generationBefore).put("bufferDaysBefore", gridDaysBefore)
            .put("indexedDays", vm.eventsByDay.size).put("loadedEventCount", vm.events.size)
            .put("frameCount", measured.size).put("landingFrameCount", landing.size)
            .put("landingP50Ns", durations[durations.size / 2])
            .put("landingP95Ns", durations[((durations.size - 1) * 0.95).toInt()])
            .put("landingMaxNs", durations.last())
            .put("commitsNs", JSONArray(commits))
            .put("frameColumns", JSONArray(listOf("vsync", "total", "measure", "unknownDelay", "input", "animation", "draw", "sync", "command", "swap", "gpu")))
            .put("framesVsyncTotalMeasureNs", JSONArray(measured.map { JSONArray(it.toList()) }))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SUNLITH_PAGING_FRAMES=$report\n") })
    }

    @Test
    fun disabledSwipesAcrossFullMonthCellsSuppressDateTaps() {
        onMain { vm.changeMonthLayout("full"); vm.changeSwipeNavigation(false) }
        val original = vm.selectedDate
        repeat(2) { index -> horizontalSwipe(gridBounds(), forward = index == 0) }
        assertEquals(original, vm.selectedDate)
        assertEquals(ViewMode.Month, vm.viewMode)
        assertFalse(vm.hasDayParent)
        assertTrue(vm.editor == null)
    }

    @Test
    fun queuedArrowTapsStillAdvanceBothMonths() {
        onMain { vm.changeShowNavigationArrows(true); vm.changeNavigationAnimation(true) }
        listOf("split", "full").forEach { layout ->
            onMain { vm.changeMonthLayout(layout) }
            val expected = vm.month.plusMonths(2)
            val bounds = node(By.pkg(context.packageName).desc(english.getString(R.string.action_next_month))).visibleBounds
            repeat(2) { assertTrue(device.click(bounds.centerX(), bounds.centerY())) }
            awaitMonth(expected)
        }
    }

    @Test
    fun openingADayFromFullMonthKeepsItsParentAndHidesFloatingToday() {
        onMain { vm.changeMonthLayout("full") }
        val original = vm.selectedDate
        val chosen = original.withDayOfMonth(if (original.dayOfMonth == 15) 16 else 15)
        node(By.pkg(context.packageName).descStartsWith(Formats(english, Locale.ENGLISH).dayLong(chosen) + ",")).click()
        awaitState("Tapping a month date must open that day with a parent.") {
            vm.viewMode == ViewMode.Day && vm.selectedDate == chosen && vm.hasDayParent
        }
        assertFalse(device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))))
        assertFalse("Drilled-in Day uses Back to return to its calendar", device.hasObject(By.pkg(context.packageName).res("calendar_view_menu")))
        node(By.pkg(context.packageName).desc(english.getString(R.string.action_back))).click()
        awaitState("Back must restore the month and its selected date.") {
            vm.viewMode == ViewMode.Month && vm.selectedDate == original && !vm.hasDayParent
        }
        node(By.pkg(context.packageName).res("calendar_full_month"))
    }

    private fun diagonalSwipe(forward: Boolean, bounds: Rect = agendaBounds()) {
        val distance = minOf(bounds.width() * 3 / 5, bounds.height() * 4 / 5)
        val rise = distance * 5 / 8
        val startX = bounds.centerX() + if (forward) distance / 2 else -distance / 2
        val endX = bounds.centerX() + if (forward) -distance / 2 else distance / 2
        val startY = bounds.centerY() + rise / 2
        assertTrue(device.swipe(startX, startY, endX, startY - rise, 28))
        device.waitForIdle()
    }

    private fun horizontalSwipe(bounds: Rect, forward: Boolean) {
        val left = bounds.left + bounds.width() / 6
        val right = bounds.right - bounds.width() / 6
        assertTrue(device.swipe(if (forward) right else left, bounds.centerY(), if (forward) left else right, bounds.centerY(), 24))
        device.waitForIdle()
    }

    private fun moveOnce(start: Point, end: Point, holdAfterMove: Long = 120) {
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, point: Point) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x.toFloat(), point.y.toFloat(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("The test gesture must reach the app.", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, start)
        try {
            SystemClock.sleep(80)
            send(MotionEvent.ACTION_MOVE, end)
            SystemClock.sleep(holdAfterMove)
        } finally {
            send(MotionEvent.ACTION_UP, end)
        }
        device.waitForIdle()
    }

    private fun visibleRows(): List<Pair<String, Rect>> {
        val bounds = agendaBounds()
        return device.findObjects(By.pkg(context.packageName).textStartsWith(marker))
            .map { it.text to it.visibleBounds }
            .filter { (_, rect) -> rect.height() > 10 && rect.centerY() in bounds.top until bounds.bottom }
            .sortedBy { it.second.top }.also { assertTrue("The fixture must have visible agenda rows.", it.isNotEmpty()) }
    }

    private fun gridBounds(): Rect = requireNotNull(stableBounds(By.pkg(context.packageName).res("calendar_grid")))

    private fun agendaBounds(): Rect = requireNotNull(stableBounds(By.pkg(context.packageName).res("calendar_agenda")))

    private fun stableBounds(selector: BySelector): Rect? {
        repeat(4) {
            try {
                instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
                return device.findObject(selector)?.visibleBounds
            } catch (_: androidx.test.uiautomator.StaleObjectException) { device.waitForIdle() }
        }
        return node(selector).visibleBounds
    }

    private fun onMain(action: () -> Unit) {
        instrumentation.runOnMainSync(action)
        instrumentation.waitForIdleSync()
        device.waitForIdle()
    }

    private fun awaitMonth(expected: YearMonth) {
        awaitState("A horizontal gesture must settle on $expected.") { vm.month == expected }
    }

    private fun awaitState(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) {
                instrumentation.waitForIdleSync()
                device.waitForIdle()
                return
            }
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun node(selector: BySelector): UiObject2 {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        return requireNotNull(device.wait(Until.findObject(selector), 10_000)) { "Expected calendar control: $selector" }
    }
}
