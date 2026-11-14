package io.github.sunsind.sunlith

import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.SunlithTheme
import io.github.sunsind.sunlith.ui.Timeline
import io.github.sunsind.sunlith.ui.TimelineEventList
import io.github.sunsind.sunlith.ui.TimelineFocus
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.6.10 timeline polish: the event chooser rows, Day's midnight edges and footer, and a focused day that is already
 * in place on its first laid out frame. Synthetic events only, drawn in a plain test activity.
 */
@RunWith(AndroidJUnit4::class)
class CalendarTimelinePolishTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val density get() = context.resources.displayMetrics.density
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.now().plusDays(3)
    private lateinit var device: UiDevice
    private var activity: ComponentActivity? = null

    @Before
    fun setUp() {
        requireEmulator()
        device = UiDevice.getInstance(instrumentation)
        activity = instrumentation.startActivitySync(Intent(context, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
    }

    @After
    fun tearDown() {
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        instrumentation.waitForIdleSync()
        activity = null
    }

    private fun at(minute: Int) = day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun event(id: Long, title: String, from: Int, to: Int, room: String = "") =
        CalendarEvent(id, 1, title, "", room, at(from), at(to), false, zone.id)

    private fun show(content: @Composable () -> Unit) {
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent {
                SunlithTheme("light", false) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { content() }
                }
            }
        }
        instrumentation.waitForIdleSync()
    }

    @Composable
    private fun DayTimeline(events: List<CalendarEvent>, scroll: ScrollState, focus: TimelineFocus? = null, onFocusHandled: () -> Unit = {}) {
        Timeline(listOf(day), { events }, day, LocalDate.now(), Formats(context, Locale.ENGLISH), false, scroll,
            autoScroll = true, todayRequest = 0, motion = false, onSelectDay = null, onOpen = {}, onNew = {},
            onOverflow = { _, _ -> }, focus = focus, onFocusHandled = { onFocusHandled() }, fallback = {})
    }

    private fun refreshAccessibility() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
    }

    private fun node(selector: BySelector): UiObject2 {
        refreshAccessibility()
        return requireNotNull(device.wait(Until.findObject(selector), 10_000)) { "Missing: $selector" }
    }

    private fun tag(name: String): BySelector = By.pkg(context.packageName).res(name)

    private fun awaitUi(detail: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        fun ready() = try { condition() } catch (_: StaleObjectException) { false }
        while (SystemClock.elapsedRealtime() < deadline && !ready()) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50)
        }
        assertTrue(detail, ready())
    }

    @Test
    fun chooserRowsAreOutlinedButtonsWithCloseRightBelowThem() {
        val events = listOf(event(-1, "Remise Projet Voyage", 8 * 60, 10 * 60 + 45),
            event(-2, "Voyageur responsable", 8 * 60, 10 * 60 + 40, "MON-C3609"))
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val shown = mutableStateOf(true)
        show {
            if (shown.value) TimelineEventList(events, Formats(context, Locale.ENGLISH),
                onOpen = { calls += "open ${it.id}" }, onClose = { calls += "close"; shown.value = false })
        }
        val list = node(tag("timeline_event_list"))
        val rows = events.map { node(tag("timeline_event_choice_${it.id}")) }
        rows.forEachIndexed { i, row ->
            assertTrue("Each row is a button: ${row.contentDescription}", row.isClickable)
            assertTrue("Each row has a full touch target: ${row.visibleBounds}", row.visibleBounds.height() >= (48 * density).toInt())
            assertTrue("The row names the event: ${row.contentDescription}", row.contentDescription.orEmpty().contains(events[i].title))
        }
        assertTrue("The room stays in the list", rows[1].contentDescription.orEmpty().contains("MON-C3609"))
        val close = node(By.pkg(context.packageName).text(context.getString(R.string.action_close)))
        val gap = close.visibleBounds.top - rows.last().visibleBounds.bottom
        assertTrue("Close sits right below the last row, not after a blank band: $gap px", gap <= (24 * density).toInt())
        assertTrue("The dialog ends right after Close: ${list.visibleBounds} / ${close.visibleBounds}",
            list.visibleBounds.bottom - close.visibleBounds.bottom <= (24 * density).toInt())
        rows[1].click()
        awaitUi("The row must open its event") { calls.size >= 2 }
        assertEquals("A row opens its event before it asks to close, so the caller can keep the list until the event is drawn",
            listOf("open -2", "close"), calls.toList())
    }

    @Test
    fun aLongChooserScrollsInsideTheScreenAndKeepsCloseVisible() {
        val events = (1L..30L).map { event(-it, "Synthetic class $it", 8 * 60, 9 * 60, "B-${1000 + it}") }
        show { TimelineEventList(events, Formats(context, Locale.ENGLISH), onOpen = {}, onClose = {}) }
        val list = node(tag("timeline_event_list"))
        val close = node(By.pkg(context.packageName).text(context.getString(R.string.action_close)))
        assertTrue("The dialog stays on screen: ${list.visibleBounds}", list.visibleBounds.bottom <= device.displayHeight)
        assertTrue("Close stays inside the dialog: ${close.visibleBounds}", list.visibleBounds.contains(close.visibleBounds))
    }

    @Test
    fun midnightSitsALittleBelowTheTopOfTheDayGrid() {
        val first = event(-11, "Synthetic midnight class", 0, 60)
        val scroll = ScrollState(0)
        show { DayTimeline(listOf(first), scroll) }
        awaitUi("The Day grid must be laid out") { scroll.viewportSize > 0 }
        val body = node(tag("calendar_timeline")).visibleBounds
        val card = node(tag("timeline_event_${first.id}")).visibleBounds
        val space = card.top - body.top
        assertTrue("The midnight card is not flush with the all-day edge: $space px", space >= (4 * density).toInt())
        assertTrue("The midnight space stays small: $space px", space <= (32 * density).toInt())
    }

    @Test
    fun anEmptyEveningEndsWithoutTheOldTallFooter() {
        val scroll = ScrollState(0)
        show { DayTimeline(listOf(event(-21, "Synthetic morning class", 9 * 60, 10 * 60)), scroll) }
        awaitUi("The Day grid must be laid out") { scroll.viewportSize > 0 && scroll.maxValue != Int.MAX_VALUE }
        val extra = scroll.maxValue + scroll.viewportSize - 24 * 64 * density
        assertTrue("The grid keeps room for its midnight labels: $extra px", extra >= 4 * density)
        assertTrue("No tall blank band after midnight: $extra px", extra <= 64 * density)
    }

    @Test
    fun aFinalMinuteCardStillScrollsAboveTheFloatingControls() {
        val late = CalendarEvent(-31, 1, "Synthetic final minute", "", "", at(23 * 60 + 59), at(24 * 60), false, zone.id)
        val scroll = ScrollState(0)
        show { DayTimeline(listOf(late), scroll) }
        awaitUi("The Day grid must be laid out") { scroll.viewportSize > 0 && scroll.maxValue != Int.MAX_VALUE }
        instrumentation.runOnMainSync { scroll.dispatchRawDelta(scroll.maxValue.toFloat()) }
        instrumentation.waitForIdleSync()
        val body = node(tag("calendar_timeline")).visibleBounds
        val card = node(tag("timeline_event_${late.id}")).visibleBounds
        assertTrue("The last card clears the Add button and Today pill: $card / $body",
            card.bottom <= body.bottom - (104 * density).toInt() + 2)
    }

    @Test
    fun aFocusedDayIsAlreadyInPlaceOnItsFirstLaidOutFrame() {
        val evening = event(-41, "Synthetic evening class", 18 * 60, 19 * 60)
        val scroll = ScrollState(0)
        val focus = mutableStateOf<TimelineFocus?>(TimelineFocus(day, 1))
        val samples = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        show {
            LaunchedEffect(Unit) { while (true) withFrameNanos { samples += scroll.viewportSize to scroll.value } }
            DayTimeline(listOf(evening), scroll, focus.value) { focus.value = null }
        }
        awaitUi("The focus request must complete") { focus.value == null }
        val settled = scroll.value
        assertTrue("The focus moved the grid to the evening", settled > 0)
        val first = samples.toList().first { it.first > 0 }
        assertEquals("The first laid out frame already shows the focus target, never midnight first", settled.toFloat(), first.second.toFloat(), 2f)
    }

    @Test
    fun anArrivingPageTakesTheLatestSharedMinuteBeforePublishingItsPosition() {
        val scroll = ScrollState(0)
        val minute = mutableFloatStateOf(300f)
        val active = mutableStateOf(false)
        val samples = Collections.synchronizedList(mutableListOf<Int>())
        val days = listOf(day, day.plusDays(1), day.plusDays(2))
        show {
            Box(Modifier.fillMaxSize().drawWithContent {
                drawContent()
                if (active.value) samples += scroll.value
            }) {
                Timeline(days, { emptyList() }, day, LocalDate.now(), Formats(context, Locale.ENGLISH), true, scroll,
                    autoScroll = active.value, todayRequest = 0, motion = false, onSelectDay = null, onOpen = {}, onNew = {},
                    onOverflow = { _, _ -> }, sharedMinute = minute, fallback = {})
            }
        }
        awaitUi("The neighbour must first follow the shared hour") { scroll.value > 0 }
        val expected = 600f / 60f * 64f * density
        assertTrue("The shared target must be reachable on this viewport", expected <= scroll.maxValue)
        instrumentation.runOnMainSync {
            // The outgoing page moves in the same composition that commits the new page.
            minute.floatValue = 600f
            active.value = true
        }
        awaitUi("The arriving page must draw") { samples.isNotEmpty() }
        assertEquals("The new publisher must not replace the latest minute with its previous frame's offset",
            600f, minute.floatValue, 1f)
        assertEquals("Its first drawn active frame must already have the latest shared position",
            expected, samples.first().toFloat(), 2f)
    }
}
