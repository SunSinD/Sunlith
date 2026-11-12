package io.github.sunsind.sunlith

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
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
import android.view.VelocityTracker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.MutatePriority
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import io.github.sunsind.sunlith.ui.focusTarget
import io.github.sunsind.sunlith.ui.Timeline
import io.github.sunsind.sunlith.ui.awaitScrollable
import io.github.sunsind.sunlith.ui.ContinuousTimeline
import io.github.sunsind.sunlith.ui.TimelineFocus
import io.github.sunsind.sunlith.ui.SunlithTheme
import io.github.sunsind.sunlith.ui.LightPalette
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    fun externallySelectedPeriodsAreMeasuredBeforeTheirFirstDraw() {
        var period by mutableLongStateOf(0)
        val draws = Collections.synchronizedList(mutableListOf<Pair<Long, List<Long>>>())
        onMain {
            requireNotNull(activity).setContent {
                val swipe = rememberPeriodSwipe(true, true) { period += it }
                PagedPeriod(swipe, period, Modifier.fillMaxSize().drawWithContent {
                    val measured = swipe.pager?.layoutInfo?.visiblePagesInfo.orEmpty().map { it.index.toLong() - 1_000_000L }
                    draws += period to measured
                    drawContent()
                }) { absolute -> Text("Selected period $absolute", Modifier.fillMaxSize()) }
            }
        }
        awaitState("The initial page must draw") { draws.any { it.first == 0L && 0L in it.second } }
        listOf(17L, -21L, 1L, 0L).forEach { target ->
            onMain { period = target }
            awaitState("The selected $target page must draw") { draws.any { it.first == target } }
        }
        assertTrue("A new selected date must never draw the previous measured page: $draws",
            draws.all { (selected, measured) -> selected in measured })
    }

    @Test
    fun externallySelectedSlidingPeriodsCancelTheirOffsetBeforeFirstDraw() {
        var period by mutableLongStateOf(0)
        var beginSlide: (() -> Unit)? = null
        val commits = AtomicInteger(0)
        val draws = Collections.synchronizedList(mutableListOf<Pair<Long, Float>>())
        onMain {
            requireNotNull(activity).setContent {
                val swipe = rememberPeriodSwipe(true, true) { commits.incrementAndGet(); period += it }
                SideEffect { beginSlide = { swipe.start(); swipe.dragBy(swipe.width * 0.35f) } }
                SlidingPeriod(swipe, period, Modifier.fillMaxSize().onSizeChanged { swipe.width = it.width }
                    .drawWithContent { draws += period to swipe.offset; drawContent() }) { absolute ->
                    Text("Selected period $absolute", Modifier.fillMaxSize())
                }
            }
        }
        awaitState("The initial sliding page must draw at rest") { draws.any { it.first == 0L && it.second == 0f } }
        listOf(17L, -21L, 1L, 0L).forEach { target ->
            val previous = period
            onMain { requireNotNull(beginSlide).invoke() }
            awaitState("The outgoing page must really draw with an unfinished slide") {
                draws.any { it.first == previous && it.second != 0f }
            }
            onMain { draws.clear(); period = target }
            awaitState("The externally selected sliding page must draw") { draws.any { it.first == target } }
            val targetDraws = synchronized(draws) { draws.filter { it.first == target } }
            assertTrue("The selected $target page must be at rest from its first draw: $targetDraws",
                targetDraws.all { it.second == 0f })
        }
        assertEquals("An external date change must not commit the interrupted gesture", 0, commits.get())
    }

    @Test
    fun aRestoredPagerCannotOverrideAFreshModelsSelectedDate() {
        val today = LocalDate.now()
        val distant = today.plusDays(9)
        val formats = Formats(english, Locale.ENGLISH)
        onMain {
            vm.changeStartView(ViewMode.Day.name)
            vm.changeDayLayout("cards")
            vm.changeViewMode(ViewMode.Day)
            vm.select(distant)
        }
        node(By.pkg(context.packageName).text(formats.dayLong(distant)))
        val outgoing = requireNotNull(activity)
        val outgoingVm = vm
        instrumentation.runOnMainSync {
            outgoing.viewModelStore.clear()
            outgoing.recreate()
        }
        awaitState("A different Activity and fresh calendar model must finish restoring.") {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull { it !== outgoing }?.let {
                    activity = it
                    vm = ViewModelProvider(it)[SunlithViewModel::class.java]
                }
            activity !== outgoing && vm !== outgoingVm && vm.loaded && !vm.loadingEvents
        }
        node(By.pkg(context.packageName).text(formats.dayLong(today)))
        onMain {
            assertEquals("Restored pager state must follow the fresh model, without committing an old page as a swipe.", today, vm.selectedDate)
            assertEquals(ViewMode.Day, vm.viewMode)
        }
    }

    @Test
    fun initialUserScrollingCannotLeaveEitherTimelineHidden() {
        val day = LocalDate.now().plusDays(2)
        val zone = ZoneId.systemDefault()
        val repository = CalendarRepository(context)
        val marker = "Visible after initial scroll interruption"
        repository.saveEvent(EventDraft(calendarId = calendarId, title = marker,
            startMillis = day.atStartOfDay(zone).toInstant().toEpochMilli(),
            endMillis = day.atTime(1, 0).atZone(zone).toInstant().toEpochMilli(),
            allDay = false, timeZone = zone.id, reminderMinutes = emptyList()))
        onMain {
            vm.changeWeekLayout("continuous")
            vm.changeViewMode(ViewMode.Week)
            vm.select(day)
            vm.reload()
        }
        awaitState("The midnight event must load before mounting either timeline.") {
            !vm.loadingEvents && vm.eventsByDay[day].orEmpty().any { it.title == marker }
        }
        val event = vm.eventsByDay.getValue(day).first { it.title == marker }
        val formats = Formats(english, Locale.ENGLISH)
        listOf(false, true).forEach { continuous ->
            val scroll = ScrollState(0)
            var hold: Job? = null
            val acquired = AtomicBoolean(false)
            try {
                onMain {
                    hold = CoroutineScope(Dispatchers.Main.immediate).launch {
                        scroll.scroll(MutatePriority.UserInput) {
                            acquired.set(true)
                            awaitCancellation()
                        }
                    }
                    assertTrue("User input must own the scroll mutation before initial positioning.", acquired.get())
                    requireNotNull(activity).setContent {
                        SunlithTheme("light", false) {
                            Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding()
                                .semantics { testTagsAsResourceId = true }) {
                                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                                    Box(Modifier.width(330.dp).height(500.dp)) {
                                        if (continuous) ContinuousTimeline(vm, formats, LocalDate.now(), true, scroll, onOpen = {})
                                        else Timeline(listOf(day, day.plusDays(1), day.plusDays(2)),
                                            { if (it == day) listOf(event) else emptyList() }, day, LocalDate.now(), formats, true, scroll,
                                            autoScroll = true, todayRequest = 0, motion = true,
                                            onSelectDay = {}, onOpen = {}, onNew = {}, onOverflow = { _, _ -> }, fallback = {})
                                    }
                                }
                            }
                        }
                    }
                }
                awaitState("The interrupted timeline must finish measurement.") {
                    scroll.maxValue in 1 until Int.MAX_VALUE && scroll.viewportSize > 0
                }
                val bounds = node(By.pkg(context.packageName).res("timeline_event_${event.id}")).visibleBounds
                assertTrue("The midnight block must be inside the viewport.", bounds.width() > 20 && bounds.height() > 20)
                var painted = false
                val deadline = SystemClock.elapsedRealtime() + 3_000
                while (!painted && SystemClock.elapsedRealtime() < deadline) {
                    val pixels = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        painted = pixels.getPixel(bounds.right - 8, bounds.centerY()) != pixels.getPixel(bounds.left - 8, bounds.centerY())
                    } finally { pixels.recycle() }
                    if (!painted) SystemClock.sleep(50)
                }
                onMain {
                    assertTrue("User input must still own the scroll mutation when painted readiness is checked.", hold?.isActive == true && scroll.isScrollInProgress)
                    assertEquals("Rejected initial positioning must preserve the user's scroll.", 0, scroll.value)
                    assertTrue("Initial positioning cancelled by user input must reveal the actual event: continuous=$continuous", painted)
                }
            } finally { onMain { hold?.cancel() } }
        }
    }

    @Test
    fun aGroupedWeekThatFitsRevealsWithoutWaitingForAScrollRange() {
        val day = LocalDate.now().plusDays(2)
        val zone = ZoneId.systemDefault()
        val event = CalendarEvent(id = 981_071L, calendarId = calendarId, title = "Compact grouped meeting",
            startMillis = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            endMillis = day.atTime(13, 0).atZone(zone).toInstant().toEpochMilli(),
            allDay = false, timeZone = zone.id, description = "", location = "")
        val scroll = ScrollState(0)
        val measuredWithoutDelay = AtomicBoolean(false)
        val formats = Formats(english, Locale.ENGLISH)
        onMain {
            requireNotNull(activity).setContent {
                SunlithTheme("light", false) {
                    Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding()
                        .semantics { testTagsAsResourceId = true }) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                            Box(Modifier.width(330.dp).height(500.dp)) {
                                Timeline(listOf(day, day.plusDays(1), day.plusDays(2)),
                                    { if (it == day) listOf(event) else emptyList() }, day, LocalDate.now(), formats, true, scroll,
                                    autoScroll = true, todayRequest = 0, motion = true,
                                    onSelectDay = {}, onOpen = {}, onNew = {}, onOverflow = { _, _ -> },
                                    groupEmptyHours = true, fallback = {})
                            }
                        }
                    }
                }
                LaunchedEffect(scroll) {
                    androidx.compose.runtime.snapshotFlow { scroll.viewportSize > 0 }.first { it }
                    assertEquals("This grouped week must fit without scrolling.", 0, scroll.maxValue)
                    kotlinx.coroutines.withTimeout(250) {
                        assertFalse(awaitScrollable(scroll))
                    }
                    measuredWithoutDelay.set(true)
                }
            }
        }
        awaitState("A measured, non-scrollable timeline must be ready immediately.") { measuredWithoutDelay.get() }
        val bounds = node(By.pkg(context.packageName).res("timeline_event_${event.id}")).visibleBounds
        val pixels = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue("The compact meeting must be painted after measurement.",
                pixels.getPixel(bounds.right - 8, bounds.centerY()) != pixels.getPixel(bounds.left - 8, bounds.centerY()))
        } finally { pixels.recycle() }
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun anUnloadedDayListShowsRetryAndRecoversAfterAProviderFailure() {
        val fail = AtomicBoolean(true)
        val resolver = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
                if (uri.pathSegments.firstOrNull() == "instances" && fail.get()) throw IllegalStateException("Synthetic calendar load failure")
                return this@CalendarRefinementTest.context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            }
            override fun getType(uri: Uri) = this@CalendarRefinementTest.context.contentResolver.getType(uri)
            override fun insert(uri: Uri, values: ContentValues?) = this@CalendarRefinementTest.context.contentResolver.insert(uri, values)
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = this@CalendarRefinementTest.context.contentResolver.delete(uri, selection, selectionArgs)
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = this@CalendarRefinementTest.context.contentResolver.update(uri, values, selection, selectionArgs)
        }.apply {
            attachInfo(this@CalendarRefinementTest.context, android.content.pm.ProviderInfo().apply {
                authority = "com.android.calendar"
                exported = false
            })
        })
        val failingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
        }
        val repository = SunlithViewModel::class.java.getDeclaredField("repo").apply { isAccessible = true }
        val original = repository.get(vm)
        val day = LocalDate.now().plusDays(400)
        try {
            repository.set(vm, CalendarRepository(failingContext))
            onMain {
                vm.changeDayLayout("cards")
                vm.changeViewMode(ViewMode.Day)
                vm.select(day)
                vm.reload()
            }
            awaitState("The selected unloaded day must report the provider failure.") {
                !vm.loadingEvents && vm.loadError != null && !vm.coversRange(day, day)
            }
            val retry = node(By.pkg(context.packageName).text(english.getString(R.string.action_retry)))
            fail.set(false)
            retry.click()
            awaitState("Retry must load the selected day and clear the error.") {
                !vm.loadingEvents && vm.loadError == null && vm.coversRange(day, day)
            }
            assertFalse(device.hasObject(By.pkg(context.packageName).text(english.getString(R.string.action_retry))))
        } finally { repository.set(vm, original) }
    }

    @Test
    fun aNewDayTimelinePositionsItsEventBeforeDrawingIntermediateHours() {
        val day = LocalDate.now().plusDays(2)
        val zone = ZoneId.systemDefault()
        val event = CalendarEvent(
            id = 981_067L, calendarId = calendarId, title = "First painted evening class",
            startMillis = day.atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
            endMillis = day.atTime(21, 0).atZone(zone).toInstant().toEpochMilli(),
            allDay = false, timeZone = zone.id, description = "", location = "", color = android.graphics.Color.BLUE,
        )
        val scroll = ScrollState(0)
        val handled = AtomicBoolean(false)
        val samples = Collections.synchronizedList(mutableListOf<Int>())
        val formats = Formats(english, Locale.ENGLISH)
        onMain {
            requireNotNull(activity).setContent {
                val focus = androidx.compose.runtime.remember { TimelineFocus(day, 1) }
                Timeline(listOf(day), { listOf(event) }, day, LocalDate.now(), formats, false, scroll,
                    autoScroll = true, todayRequest = 0, motion = true,
                    onSelectDay = {}, onOpen = {}, onNew = {}, onOverflow = { _, _ -> },
                    modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.drawWithContent {
                        samples += scroll.value
                        drawContent()
                    }, focus = focus, onFocusHandled = { handled.set(true) }, fallback = {})
            }
        }
        awaitState("The new day must position and draw its first class") { handled.get() && samples.isNotEmpty() }
        val settled = scroll.value
        assertTrue("The fixture must start at a late hour.", settled > 0)
        assertTrue("No rendered frame may traverse intermediate hours: $samples, target=$settled",
            samples.all { kotlin.math.abs(it - settled) <= 2 })
        val eventBounds = node(By.pkg(context.packageName).res("timeline_event_${event.id}")).visibleBounds
        val viewport = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
        assertTrue("The positioned event must actually be visible.",
            eventBounds.height() > 20 && eventBounds.top >= viewport.top && eventBounds.bottom <= viewport.bottom)
        val pixels = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val eventPixel = pixels.getPixel(eventBounds.right - 8, eventBounds.centerY())
            val outsidePixel = pixels.getPixel(eventBounds.left - 8, eventBounds.centerY())
            assertTrue("The focused block must actually be painted, not remain hidden by its preparation layer.", eventPixel != outsidePixel)
        } finally { pixels.recycle() }
    }

    @Test
    fun dayAndGroupedWeekZoomResizeEventsAndPreserveTheViewedMinute() {
        val day = LocalDate.now().plusDays(2)
        val zone = ZoneId.systemDefault()
        val formats = Formats(english, Locale.ENGLISH)
        val example = CalendarEvent(
            id = 981_068L, calendarId = calendarId, title = "Zoom keeps this class",
            startMillis = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            endMillis = day.atTime(13, 0).atZone(zone).toInstant().toEpochMilli(),
            allDay = false, timeZone = zone.id, description = "Original description", location = "Room Z",
        )
        val storedId = CalendarRepository(context).saveEvent(EventDraft(calendarId = calendarId, title = example.title,
            startMillis = example.startMillis, endMillis = example.endMillis, allDay = false, timeZone = zone.id,
            description = example.description, location = example.location, reminderMinutes = emptyList()))
        onMain { vm.select(day); vm.reload() }
        awaitState("The continuous zoom marker must load from the provider.") {
            !vm.loadingEvents && vm.eventsByDay[day].orEmpty().any { it.id == storedId }
        }
        val stored = vm.eventsByDay.getValue(day).first { it.id == storedId }
        listOf("day", "week", "continuous").forEach { layout ->
            val groupedWeek = layout == "week"
            val continuous = layout == "continuous"
            val event = if (continuous) stored else example
            val scroll = ScrollState(0)
            val zoom = mutableIntStateOf(100)
            val focus = mutableStateOf<TimelineFocus?>(if (continuous) null else TimelineFocus(day, 1))
            val opened = AtomicReference<CalendarEvent?>(null)
            val days = if (groupedWeek) listOf(day, day.plusDays(1), day.plusDays(2)) else listOf(day)
            // Other columns keep enough occupied hours around the grouped bands to exercise real scrolling
            // at 50%, while the measured class remains a single unchanged one-hour event.
            val earlier = event.copy(id = 981_069L, startMillis = day.plusDays(1).atTime(5, 0).atZone(zone).toInstant().toEpochMilli(),
                endMillis = day.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli())
            val later = event.copy(id = 981_070L, startMillis = day.plusDays(2).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
                endMillis = day.plusDays(2).atTime(20, 0).atZone(zone).toInstant().toEpochMilli())
            onMain {
                if (continuous) {
                    vm.changeWeekLayout("continuous")
                    vm.changeViewMode(ViewMode.Week)
                    vm.changeGroupEmptyHours(false)
                    vm.changeWeekTimelineZoom(100)
                    vm.select(day)
                }
                requireNotNull(activity).setContent {
                    SunlithTheme("light", false) {
                        Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding()
                            .semantics { testTagsAsResourceId = true }) {
                            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                                Box(Modifier.width(350.dp).height(if (continuous) 500.dp else 320.dp)) {
                                    if (continuous) ContinuousTimeline(vm, formats, LocalDate.now(), true, scroll,
                                        onOpen = { opened.set(it) })
                                    else Timeline(days, { shown -> when (shown) {
                                        day -> listOf(event)
                                        day.plusDays(1) -> listOf(earlier)
                                        else -> listOf(later)
                                    } }, day, LocalDate.now(), formats, groupedWeek, scroll,
                                        autoScroll = true, todayRequest = 0, motion = true,
                                        onSelectDay = {}, onOpen = { opened.set(it) }, onNew = {}, onOverflow = { _, _ -> },
                                        focus = focus.value, onFocusHandled = { focus.value = null },
                                        groupEmptyHours = groupedWeek, zoomPercent = zoom.intValue, fallback = {})
                                }
                            }
                        }
                    }
                }
            }
            awaitState("The class must be positioned before changing zoom.") { focus.value == null && scroll.viewportSize > 0 }
            if (continuous) {
                awaitState("Initial continuous navigation must finish before setting the viewed minute.") {
                    !vm.loadingEvents && scroll.value > 0 && vm.timelineNavigationRequest == vm.handledTimelineNavigation
                }
                onMain { CoroutineScope(Dispatchers.Main.immediate).launch { scroll.scrollTo(736) } }
                awaitState("Continuous must start at 11:30 with the 64px hour.") { scroll.value == 736 }
            }
            val selector = By.pkg(context.packageName).res("timeline_event_${event.id}")
            var base = paintedTimelineBounds(selector)
            var baselineViewport = nodeBounds(By.pkg(context.packageName).res("calendar_timeline"))
            val baselineDeadline = SystemClock.elapsedRealtime() + 5_000
            fun baselineReady() = abs(base.height() - 64) <= 2 &&
                abs(base.top - baselineViewport.top - 32) <= 2 &&
                base.top >= baselineViewport.top && base.bottom <= baselineViewport.bottom
            while (!baselineReady() && SystemClock.elapsedRealtime() < baselineDeadline) {
                SystemClock.sleep(50)
                base = paintedTimelineBounds(selector)
                baselineViewport = nodeBounds(By.pkg(context.packageName).res("calendar_timeline"))
            }
            if (!baselineReady() || InstrumentationRegistry.getArguments().getString("captureFinal") == "true") {
                val name = "zoom-baseline-$layout"
                device.takeScreenshot(File(context.filesDir, "$name.png"))
                device.dumpWindowHierarchy(File(context.filesDir, "$name.xml"))
                File(context.filesDir, "$name.json").writeText(JSONObject().put("painted", base.toString())
                    .put("viewport", baselineViewport.toString()).put("scroll", scroll.value).toString())
            }
            val description = readNode(selector) { it.contentDescription }
            assertTrue("The full title must remain accessible at every zoom.", description.contains(event.title))
            assertTrue("The baseline class must be completely inside the viewport: layout=$layout base=$base viewport=$baselineViewport", base.height() > 0 &&
                base.top > nodeBounds(By.pkg(context.packageName).res("calendar_timeline")).top &&
                base.bottom < nodeBounds(By.pkg(context.packageName).res("calendar_timeline")).bottom)
            val initialTop = nodeBounds(By.pkg(context.packageName).res("calendar_timeline")).top
            val fraction = (base.top - initialTop).toFloat() / base.height()
            assertTrue("The fixture must view the half-hour before this one-hour class: layout=$layout base=$base viewport=$baselineViewport scroll=${scroll.value}", abs(fraction - 0.5f) < 0.06f)
            listOf(50, 100, 200).forEach { percent ->
                onMain { if (continuous) vm.changeWeekTimelineZoom(percent) else zoom.intValue = percent }
                val deadline = SystemClock.elapsedRealtime() + 5_000
                var settled = false
                while (!settled && SystemClock.elapsedRealtime() < deadline) {
                    val measured = paintedTimelineBounds(selector)
                    val area = nodeBounds(By.pkg(context.packageName).res("calendar_timeline"))
                    settled = abs(measured.height() - base.height() * percent / 100f) <= 2f &&
                        abs((measured.top - area.top) - fraction * measured.height()) <= 2f
                    if (!settled) SystemClock.sleep(50)
                }
                if (!settled) {
                    val name = "zoom-unsettled-$layout-$percent"
                    device.takeScreenshot(File(context.filesDir, "$name.png"))
                    device.dumpWindowHierarchy(File(context.filesDir, "$name.xml"))
                }
                val bounds = paintedTimelineBounds(selector)
                val viewport = nodeBounds(By.pkg(context.packageName).res("calendar_timeline"))
                assertTrue("Zoom must resize the rendered one-hour block: layout=$layout zoom=$percent base=$base actual=$bounds",
                    abs(bounds.height() - base.height() * percent / 100f) <= 2f)
                assertTrue("The whole event must remain visible at $percent%.", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
                // The event starts at noon and spans one hour. Its relative distance below the top
                // therefore measures the visible minute without reusing the production axis mapping.
                assertTrue("Zoom must preserve the viewed minute: layout=$layout zoom=$percent fraction=$fraction bounds=$bounds viewport=$viewport",
                    abs((bounds.top - viewport.top) - fraction * bounds.height()) <= 2f)
                assertEquals("Zoom must preserve the full spoken title, times and room.", description, readNode(selector) { it.contentDescription })
                opened.set(null)
                readNode(selector) { it.click() }
                assertEquals("Zoom must retain the original event and its exact start/end times.", event, opened.get())
                if (InstrumentationRegistry.getArguments().getString("captureFinal") == "true") {
                    val name = "zoom-$layout-$percent"
                    assertTrue(device.takeScreenshot(File(context.filesDir, "$name.png")))
                    device.dumpWindowHierarchy(File(context.filesDir, "$name.xml"))
                }
            }
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
        listOf(false, true).forEach { motion ->
            onMain { vm.changeNavigationAnimation(motion) }
            repeat(3) {
                diagonalSwipe(forward = true)
                awaitMonth(original.plusMonths(1))
                diagonalSwipe(forward = false)
                awaitMonth(original)
            }
        }
        assertEquals(ViewMode.Month, vm.viewMode)
        assertTrue(vm.editor == null)
    }

    @Test
    fun diagonalSwipesPageWeekAndDayListsOnceWithAndWithoutMotion() {
        val formats = Formats(english, Locale.ENGLISH)
        listOf(ViewMode.Week, ViewMode.Day).forEach { mode ->
            listOf(false, true).forEach { motion ->
                onMain {
                    vm.changeWeekLayout("list")
                    vm.changeDayLayout("cards")
                    vm.changeNavigationAnimation(motion)
                    vm.changeViewMode(mode)
                    vm.select(vm.today)
                }
                awaitState("The $mode list must be loaded before swiping, motion=$motion") { vm.loaded && !vm.loadingEvents }
                val original = vm.selectedDate
                val changes = mutableListOf<LocalDate>()
                var previous = original
                val observer = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (vm.selectedDate != previous) { previous = vm.selectedDate; changes += previous }
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(observer) }
                try {
                    repeat(2) {
                        listOf(true, false).forEach { forward ->
                            val expected = if (!forward) original else if (mode == ViewMode.Week) original.plusWeeks(1) else original.plusDays(1)
                            val area = if (mode == ViewMode.Week) agendaBounds()
                                else node(By.pkg(context.packageName).res("calendar_day_cards")).visibleBounds
                            diagonalSwipe(forward, area)
                            awaitState("A diagonal $mode list swipe must land exactly on $expected, motion=$motion") { vm.selectedDate == expected }
                            assertTrue("The visible $expected header must expose its selected state.",
                                node(By.pkg(context.packageName).descStartsWith(formats.dayLong(expected) + ",")).let { it.isSelected || it.isChecked })
                            if (mode == ViewMode.Day) node(By.pkg(context.packageName).text(formats.dayLong(expected)))
                            assertEquals("The outgoing page's events must disappear after landing, mode=$mode motion=$motion",
                                expected == original, device.hasObject(By.pkg(context.packageName).textStartsWith(marker)))
                            assertEquals(mode, vm.viewMode)
                            assertTrue(vm.editor == null)
                        }
                    }
                } finally {
                    instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(observer) }
                }
                val next = if (mode == ViewMode.Week) original.plusWeeks(1) else original.plusDays(1)
                assertEquals("Each diagonal gesture must produce one visible date change, mode=$mode motion=$motion",
                    listOf(next, original, next, original), changes)
            }
        }
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
    fun nonzeroTodayMotionYieldsToVerticalTouchAndImmediateWeekPaging() {
        val today = LocalDate.now()
        val formats = Formats(english, Locale.ENGLISH)
        listOf(3, 7).forEach { days ->
            listOf(true, false).forEach { motion ->
                var period by mutableLongStateOf(0)
                var request by mutableIntStateOf(0)
                val scrolls = mutableMapOf<Long, ScrollState>()
                onMain {
                    requireNotNull(activity).setContent {
                        // Keep columns fitted here to isolate scroll ownership; real-view tests cover readable overflow.
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                            val swipe = rememberPeriodSwipe(true, motion) { period += it }
                            PagedPeriod(swipe, period, Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }) { absolute ->
                                val scroll = rememberScrollState()
                                SideEffect { scrolls[absolute] = scroll }
                                val first = today.plusDays(absolute * days)
                                Timeline((0L until days.toLong()).map(first::plusDays), { emptyList() }, today, today,
                                    formats, true, scroll, absolute == period, request, motion,
                                    onSelectDay = {}, onOpen = {}, onNew = {}, onOverflow = { _, _ -> },
                                    fallback = { Text("Unexpected timeline fallback") })
                            }
                        }
                    }
                }
                awaitState("The $days-day real timeline fixture must be scrollable") {
                    scrolls[0]?.let { it.maxValue in 64 until Int.MAX_VALUE && !it.isScrollInProgress } == true
                }
                val scroll = requireNotNull(scrolls[0])
                val target = scroll.value
                val bounds = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
                fun startSmallTodayMove() {
                    val ready = java.util.concurrent.CountDownLatch(1)
                    val requestedAt = SystemClock.uptimeMillis()
                    instrumentation.runOnMainSync {
                        val displaced = if (target >= 31) target - 31 else target + 31
                        scroll.dispatchRawDelta((displaced - scroll.value).toFloat())
                        assertEquals(31, abs(scroll.value - target))
                        request++
                        Choreographer.getInstance().postFrameCallback {
                            Choreographer.getInstance().postFrameCallback { ready.countDown() }
                        }
                    }
                    assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    assertTrue("Touch must start during the original 300 ms Today move", SystemClock.uptimeMillis() - requestedAt < 250)
                }
                startSmallTodayMove()
                val distance = minOf(320, bounds.height() / 3)
                val down = target > scroll.maxValue / 2
                val center = Point(bounds.centerX(), bounds.centerY())
                moveOnce(center, Point(center.x, center.y + if (down) distance else -distance), holdAfterMove = 350)
                val dragged = scroll.value
                assertTrue("A real vertical drag must interrupt Today in the $days-day timeline, motion=$motion", abs(dragged - target) > distance / 2)
                SystemClock.sleep(350)
                onMain { assertEquals("Today must not pull the timeline back after the finger releases", dragged, scroll.value) }
                startSmallTodayMove()
                horizontalSwipe(bounds, true)
                awaitState("A small nonzero Today move must allow the first $days-day swipe, motion=$motion") { period == 1L }
                horizontalSwipe(node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds, false)
                awaitState("The $days-day timeline must reverse immediately, motion=$motion") { period == 0L }
                val activeScroll = requireNotNull(scrolls[0])
                onMain { activeScroll.dispatchRawDelta((activeScroll.maxValue / 2 - activeScroll.value).toFloat()) }
                val beforeFling = activeScroll.value
                val gesture = flingVertically(center, Point(center.x, center.y + distance))
                val flingChecked = java.util.concurrent.CountDownLatch(1)
                var flingObserved = false
                instrumentation.runOnMainSync {
                    val releasedAt = activeScroll.value
                    val deadline = SystemClock.uptimeMillis() + 1000
                    Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            if (activeScroll.isScrollInProgress && activeScroll.value != releasedAt) {
                                flingObserved = true
                                request++
                                flingChecked.countDown()
                            } else if (SystemClock.uptimeMillis() < deadline) {
                                Choreographer.getInstance().postFrameCallback(this)
                            } else flingChecked.countDown()
                        }
                    })
                }
                assertTrue(flingChecked.await(2, java.util.concurrent.TimeUnit.SECONDS))
                if (!flingObserved) {
                    device.takeScreenshot(File(context.filesDir, "today-fling-week$days-motion$motion-failure.png"))
                    device.dumpWindowHierarchy(File(context.filesDir, "today-fling-week$days-motion$motion-failure.xml"))
                }
                assertTrue("The real vertical gesture must move after release with a fling running: days=$days motion=$motion " +
                    "before=$beforeFling after=${activeScroll.value} max=${activeScroll.maxValue} target=$target " +
                    "sameState=${activeScroll === scroll} gesture=$gesture", flingObserved)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SUNLITH_FLING=$days:$motion:$gesture\n") })
                awaitState("Today must finish after stopping the existing vertical fling") {
                    abs(activeScroll.value - target) <= 1 && !activeScroll.isScrollInProgress
                }
                SystemClock.sleep(350)
                onMain { assertTrue("The old fling must not move the timeline after Today", abs(activeScroll.value - target) <= 1) }
                if (InstrumentationRegistry.getArguments().getString("captureMinor") == "true") {
                    val prefix = InstrumentationRegistry.getArguments().getString("minorEvidencePrefix") ?: "minor-v0.6.3"
                    device.takeScreenshot(File(context.filesDir, "$prefix-touch-week$days-motion$motion.png"))
                    device.dumpWindowHierarchy(File(context.filesDir, "$prefix-touch-week$days-motion$motion.xml"))
                }
            }
        }
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
            fun area(): Rect {
                val bounds = requireNotNull(stableBounds(By.pkg(context.packageName).res("calendar_timeline")))
                return if (days == 3) bounds else timelinePagingArea(bounds)
            }
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
            "week-timeline", "week-three-timeline" -> timelinePagingArea(node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds)
            "day-timeline" -> node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
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
    fun openingADayFromFullMonthKeepsItsParentAndOffersTodayWhenAway() {
        onMain { vm.changeMonthLayout("full") }
        val original = vm.selectedDate
        val chosen = original.withDayOfMonth(if (original.dayOfMonth == 15) 16 else 15)
        node(By.pkg(context.packageName).descStartsWith(Formats(english, Locale.ENGLISH).dayLong(chosen) + ",")).click()
        awaitState("Tapping a month date must open that day with a parent.") {
            vm.viewMode == ViewMode.Day && vm.selectedDate == chosen && vm.hasDayParent
        }
        if (chosen == LocalDate.now()) {
            assertFalse(device.hasObject(By.pkg(context.packageName).desc(english.getString(R.string.action_today))))
        } else {
            node(By.pkg(context.packageName).res("calendar_today_${if (chosen < LocalDate.now()) "next" else "previous"}"))
        }
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

    private fun timelinePagingArea(timeline: Rect): Rect =
        // The divider above the hours belongs to the pager, outside the columns' sideways scroll.
        Rect(timeline.left, timeline.top - 1, timeline.right, timeline.top)

    private fun horizontalSwipe(bounds: Rect, forward: Boolean) {
        val left = bounds.left + bounds.width() / 6
        val right = bounds.right - bounds.width() / 6
        assertTrue(device.swipe(if (forward) right else left, bounds.centerY(), if (forward) left else right, bounds.centerY(), 24))
        device.waitForIdle()
    }

    private fun flingVertically(start: Point, end: Point): String {
        val downTime = SystemClock.uptimeMillis()
        val tracker = VelocityTracker.obtain()
        val times = mutableListOf<Long>()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction, 0)
            try {
                times += event.eventTime - downTime
                tracker.addMovement(event)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("The fling gesture must reach the app", instrumentation.uiAutomation.injectInputEvent(event, action == MotionEvent.ACTION_UP))
            } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        try {
            repeat(6) { step ->
                SystemClock.sleep(10)
                send(MotionEvent.ACTION_MOVE, (step + 1) / 6f)
            }
        } finally { send(MotionEvent.ACTION_UP, 1f) }
        return try {
            tracker.computeCurrentVelocity(1000)
            "eventsMs=$times velocityY=${tracker.yVelocity} elapsedMs=${SystemClock.uptimeMillis() - downTime}"
        } finally { tracker.recycle() }
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

    private fun gridBounds(): Rect {
        val selector = By.pkg(context.packageName).res("calendar_grid")
        // A layout change can still be composing on slower devices.
        device.wait(Until.hasObject(selector), 10_000)
        return requireNotNull(stableBounds(selector)) { "The full month grid must be shown" }
    }

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
        captureFailure()
        throw AssertionError(message)
    }

    private fun paintedTimelineBounds(selector: BySelector): Rect {
        val target = nodeBounds(selector)
        val viewport = nodeBounds(By.pkg(context.packageName).res("calendar_timeline"))
        val pixels = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val x = target.right - 8
            val middle = target.centerY().coerceIn(viewport.top, viewport.bottom - 1)
            val color = pixels.getPixel(x, middle)
            var top = middle
            var bottom = middle + 1
            while (top > viewport.top && pixels.getPixel(x, top - 1) == color) top--
            while (bottom < viewport.bottom && pixels.getPixel(x, bottom) == color) bottom++
            return Rect(target.left, top, target.right, bottom)
        } finally { pixels.recycle() }
    }

    private fun nodeBounds(selector: BySelector): Rect = readNode(selector) { it.visibleBounds }

    private fun <T> readNode(selector: BySelector, read: (UiObject2) -> T): T {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
                val target = device.wait(Until.findObject(selector), (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                    ?: break
                return read(target)
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                SystemClock.sleep(50)
            }
        }
        captureFailure()
        throw AssertionError("Expected stable calendar control: $selector")
    }

    private fun node(selector: BySelector): UiObject2 {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        return requireNotNull(device.wait(Until.findObject(selector), 10_000)) {
            captureFailure()
            "Expected calendar control: $selector"
        }
    }

    private fun captureFailure() {
        runCatching {
            device.takeScreenshot(File(context.filesDir, "refinement-state.png"))
            device.dumpWindowHierarchy(File(context.filesDir, "refinement-state.xml"))
        }
    }
}
