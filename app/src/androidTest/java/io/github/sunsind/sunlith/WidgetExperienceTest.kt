package io.github.sunsind.sunlith

import android.Manifest
import android.app.LocaleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.widget.AgendaWidgetReceiver
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetExperienceTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private lateinit var settings: SettingsStore
    private lateinit var repository: CalendarRepository
    private lateinit var host: AppWidgetHost
    private lateinit var hostView: AppWidgetHostView
    private lateinit var overlay: FrameLayout
    private lateinit var list: ListView
    private var activity: ComponentActivity? = null
    private var oldLocales: LocaleList? = null
    private var widgetId = 0
    private var calendarId = 0L
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private val account = "Sunlith widget test ${UUID.randomUUID()}"
    private val providerName = "Hidden widget provider"
    private val today get() = LocalDate.now()

    @Before
    fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        device = UiDevice.getInstance(instrumentation)
        listOf("sunlith", "sunlith_ui").forEach { name -> snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).let {
            oldLocales = it.applicationLocales
            it.applicationLocales = LocaleList.forLanguageTags("en")
        }
        settings = SettingsStore(context)
        repository = CalendarRepository(context)
        calendarId = insertLocalCalendar(context, "sunlith-widget-experience", providerName, account)
        settings.selectedCalendarIds = setOf(calendarId)
        settings.hasChosenCalendars = true
        settings.widgetDays = 7
        settings.widgetShowEmptyDays = true
        settings.widgetShowCalendarName = false
        settings.widgetOpacity = 100
        settings.themeMode = "dark"
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().putBoolean("ai_intro_done", true).commit()
        listOf(0 to "First day event", 3 to "Fourth day event", 6 to "Seventh day event", 7 to "Outside week event", 12 to "Expanded window event").forEach { (offset, title) ->
            addAllDay(today.plusDays(offset.toLong()), title)
        }
        shell("appwidget grantbind --package ${context.packageName} --user 0")
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        val manager = AppWidgetManager.getInstance(context)
        instrumentation.runOnMainSync {
            host = AppWidgetHost(context, 20261105)
            host.startListening()
            widgetId = host.allocateAppWidgetId()
        }
        assertTrue(manager.bindAppWidgetIdIfAllowed(widgetId, ComponentName(context, AgendaWidgetReceiver::class.java)))
        manager.updateAppWidgetOptions(widgetId, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 340)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 340)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 440)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 440)
        })
        instrumentation.runOnMainSync {
            val current = requireNotNull(activity)
            val density = context.resources.displayMetrics.density
            overlay = FrameLayout(current).apply { setBackgroundColor(Color.rgb(8, 8, 8)) }
            hostView = host.createView(current, widgetId, manager.getAppWidgetInfo(widgetId))
            overlay.addView(hostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (440 * density).toInt(), Gravity.CENTER).apply {
                setMargins((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            })
            current.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            AgendaWidgetReceiver.updateAll(context)
        }
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "widget_title").text("First day event")), 15_000))
        instrumentation.runOnMainSync { list = hostView.findViewById(R.id.widget_events) }
        awaitRows(7)
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        instrumentation.runOnMainSync {
            if (::host.isInitialized) {
                if (widgetId != 0) host.deleteAppWidgetId(widgetId)
                host.stopListening()
            }
            activity?.finish()
        }
        shell("appwidget revokebind --package ${context.packageName} --user 0")
        if (calendarId != 0L) context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
        snapshots.forEach { (name, values) -> restoreTestPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
        if (Build.VERSION.SDK_INT >= 33) oldLocales?.let { context.getSystemService(LocaleManager::class.java).applicationLocales = it }
    }

    @Test
    fun noonAndLongTimeLabelsFitOneLineAtNarrowWidthsAndLargeText() {
        listOf(1f, 2f).forEach { scale ->
            val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = scale }
            val scaled = context.createConfigurationContext(config)
            listOf(180, 340).forEach { width ->
                listOf("12:00 p.m." to 1, "11:40 a.m." to 1, "All day" to 2, "Toute la journée" to 2, "En cours" to 2).forEach { (label, maxLines) ->
                    instrumentation.runOnMainSync {
                        val views = RemoteViews(context.packageName, R.layout.widget_event).apply {
                            setTextViewText(R.id.widget_time, label)
                            setInt(R.id.widget_time, "setMaxLines", maxLines)
                            setTextViewText(R.id.widget_title, "Sample event")
                        }
                        val root = views.apply(scaled, FrameLayout(scaled))
                        root.measure(View.MeasureSpec.makeMeasureSpec((width * scaled.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                        val time = root.findViewById<TextView>(R.id.widget_time)
                        assertTrue("Time label must fit at ${width}dp and ${scale}x text.", time.lineCount in 1..maxLines)
                        assertEquals(label.length, time.layout.getLineEnd(time.lineCount - 1))
                        repeat(time.lineCount) { line ->
                            assertEquals(0, time.layout.getEllipsisCount(line))
                            val visible = time.text.subSequence(time.layout.getLineStart(line), time.layout.getLineVisibleEnd(line)).toString()
                            val textWidth = time.paint.measureText(visible)
                            val available = time.width - time.totalPaddingLeft - time.totalPaddingRight
                            assertTrue("The complete label must fit: $label, ${width}dp, ${scale}x, line $line '$visible', $textWidth / $available pixels", textWidth <= available + 1)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun sevenDayWidgetShowsOrderedEmptyDaysDividersAndNoProviderNames() {
        val headings = mutableListOf<String>()
        for (position in 0 until 7) {
            val expected = when (position) { 0 -> "First day event"; 3 -> "Fourth day event"; 6 -> "Seventh day event"; else -> context.getString(R.string.no_events) }
            val row = readRow(position, expected)
            headings += row.heading
            assertTrue("Every day heading must include its date.", row.heading.contains(today.plusDays(position.toLong()).dayOfMonth.toString()))
            assertEquals("A divider must separate each later day.", position > 0, row.divider)
            assertFalse("Provider names must be hidden by default.", row.metaVisible)
        }
        assertEquals(7, headings.distinct().size)
        assertTrue(headings.first().startsWith(context.getString(R.string.today)))
        assertTrue(headings[1].startsWith(context.getString(R.string.tomorrow)))
        var alpha = -1
        instrumentation.runOnMainSync { alpha = hostView.findViewById<ImageView>(R.id.widget_background).imageAlpha }
        assertEquals("The default widget background must be fully opaque.", 255, alpha)
    }

    @Test
    fun dayWindowEmptyRowsAndCalendarNamesCanBeConfigured() {
        settings.widgetDays = 3
        refresh(3)
        readRow(2, context.getString(R.string.no_events))
        settings.widgetDays = 14
        refresh(14)
        readRow(7, "Outside week event")
        readRow(12, "Expanded window event")
        settings.widgetShowEmptyDays = false
        refresh(5)
        readRow(4, "Expanded window event")
        settings.widgetShowCalendarName = true
        refresh(5)
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "widget_meta").text(providerName)), 10_000))
        val named = readRow(0, "First day event")
        assertTrue(named.metaVisible)
        assertEquals(providerName, named.meta)
    }

    @Test
    fun tappingAnEmptyDayCreatesAnEventOnThatDay() {
        val empty = readRow(1, context.getString(R.string.no_events))
        requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "widget_row").desc(empty.description)), 10_000)).click()
        device.waitForIdle()
        instrumentation.runOnMainSync { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text(context.getString(R.string.editor_new))), 10_000))
        var date: LocalDate? = null
        instrumentation.runOnMainSync { date = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java].editor?.startDate }
        assertEquals("An empty widget day must retain its date when opening the editor.", today.plusDays(1), date)
    }

    @Test
    fun multiDayEventsAppearOnEachCoveredDayWithExclusiveEndDates() {
        addAllDay(today.plusDays(1), "All-day span marker", 2)
        val zone = ZoneId.systemDefault()
        repository.saveEvent(EventDraft(calendarId = calendarId, title = "Ongoing span marker", startMillis = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), endMillis = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(), timeZone = zone.id, reminderMinutes = emptyList()))
        refresh(9)
        val rows = (0 until 9).map { readRow(it) }
        val allDay = rows.filter { it.title == "All-day span marker" }
        val ongoing = rows.filter { it.title == "Ongoing span marker" }
        assertEquals(2, allDay.size)
        assertTrue(allDay.all { it.time == context.getString(R.string.all_day) })
        assertEquals(2, ongoing.size)
        assertTrue(ongoing.all { it.time == context.getString(R.string.ongoing) })
        assertEquals(1, rows.count { it.title == "Fourth day event" })
    }

    private fun addAllDay(day: LocalDate, title: String, days: Long = 1) {
        repository.saveEvent(EventDraft(calendarId = calendarId, title = title, startMillis = CalendarDates.allDayMillis(day), endMillis = CalendarDates.allDayMillis(day.plusDays(days)), allDay = true, timeZone = "UTC", reminderMinutes = emptyList()))
    }

    private fun refresh(count: Int) {
        instrumentation.runOnMainSync { AgendaWidgetReceiver.updateAll(context) }
        awaitRows(count)
    }

    private fun awaitRows(expected: Int) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var actual = -1
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { actual = list.adapter?.count ?: -1 }
            if (actual == expected) { device.waitForIdle(); return }
            SystemClock.sleep(100)
        }
        assertEquals("The real collection service must load the expected widget rows.", expected, actual)
    }

    private data class Row(val heading: String, val title: String, val time: String, val meta: String, val metaVisible: Boolean, val divider: Boolean, val description: String)

    private fun readRow(position: Int, expected: String? = null): Row {
        instrumentation.runOnMainSync { list.setSelection(position) }
        device.waitForIdle()
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var result: Row? = null
            instrumentation.runOnMainSync {
                val item = list.getChildAt(position - list.firstVisiblePosition)
                val title = item?.findViewById<TextView>(R.id.widget_title)
                if (title != null && (expected == null || title.text.toString() == expected)) {
                    val root = requireNotNull(item.findViewById<View>(R.id.widget_row))
                    val meta = item.findViewById<TextView>(R.id.widget_meta)
                    result = Row(item.findViewById<TextView>(R.id.widget_day).text.toString(), title.text.toString(), item.findViewById<TextView>(R.id.widget_time).text.toString(), meta.text.toString(), meta.visibility == View.VISIBLE, item.findViewById<View>(R.id.widget_divider).visibility == View.VISIBLE, root.contentDescription.toString())
                }
            }
            result?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected actual widget row $position ($expected) was not rendered.")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).let { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
    }
}
