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
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.rememberMotion
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
        val expected = vm.month.plusMonths(2)
        val bounds = node(By.pkg(context.packageName).desc(english.getString(R.string.action_next_month))).visibleBounds
        repeat(2) { assertTrue(device.click(bounds.centerX(), bounds.centerY())) }
        awaitMonth(expected)
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
        node(By.pkg(context.packageName).desc(english.getString(R.string.action_back))).click()
        awaitState("Back must restore the month and its selected date.") {
            vm.viewMode == ViewMode.Month && vm.selectedDate == original && !vm.hasDayParent
        }
        node(By.pkg(context.packageName).res("calendar_full_month"))
    }

    private fun diagonalSwipe(forward: Boolean) {
        val bounds = agendaBounds()
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
