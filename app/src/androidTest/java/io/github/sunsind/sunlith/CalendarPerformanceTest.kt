package io.github.sunsind.sunlith

import android.Manifest
import android.app.Activity
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.activity.ComponentActivity
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
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.gridStart
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarPerformanceTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private lateinit var vm: SunlithViewModel
    private var activity: ComponentActivity? = null
    private var calendarId = 0L
    private val account = "Sunlith performance ${UUID.randomUUID()}"
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private val eventIds = mutableMapOf<String, Long>()
    private val descriptionIds = mutableSetOf<Long>()
    private val today = LocalDate.now()

    @Before
    fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        device = UiDevice.getInstance(instrumentation)
        finish()
        listOf("sunlith", "sunlith_ui").forEach { name ->
            snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("ai_intro_done", true).putBoolean("layout_setup_done", true)
            .putBoolean("notify_asked", true).commit()
        calendarId = insertLocalCalendar(context, "sunlith-performance-test", "Performance calendar", account)
        SettingsStore(context).apply {
            selectedCalendarIds = setOf(calendarId)
            hasChosenCalendars = true
            remindersEnabled = false
            startView = "Month"
            monthLayout = "split"
            agendaRange = "continuous"
            agendaDisplay = "all"
            collapseCalendarOnScroll = false
            navigationAnimation = false
            materialYou = false
            weekStartsMonday = true
        }
        seedEvents()
        assertEquals(1460, eventIds.size)
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        try {
            finish()
        } finally {
            try {
                if (calendarId > 0) context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
            } finally {
                snapshots.forEach { (name, values) ->
                    restoreTestPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values)
                }
            }
        }
    }

    @Test
    fun denseCalendarLoadsPagesJumpsAndSearchesDescriptions() {
        measure("initial_month_agenda") {
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as ComponentActivity
            onMain { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java] }
            awaitReady { vm.loaded && vm.coversRange(today.minusDays(60), today.plusDays(120)) }
            node(By.pkg(context.packageName).res("calendar_agenda"))
            node(By.pkg(context.packageName).text(title(90, 0)))
            assertTrue(vm.events.any { it.id == eventIds.getValue(title(90, 0)) })
        }

        val end = requireNotNull(vm.loadedTo)
        measure("page_forward") {
            onMain { vm.extendAgenda(true) }
            awaitReady { !vm.extendingAgenda && vm.loadedTo == end.plusDays(90) }
            assertTrue(vm.events.any { it.id == eventIds.getValue(title(265, 0)) })
            node(By.pkg(context.packageName).text(title(90, 0)))
        }

        val start = requireNotNull(vm.loadedFrom)
        measure("page_backward") {
            onMain { vm.extendAgenda(false) }
            awaitReady { !vm.extendingAgenda && vm.loadedFrom == start.minusDays(90) }
            assertTrue(vm.events.any { it.id == eventIds.getValue(title(5, 0)) })
            assertEquals(today, vm.selectedDate)
            val grid = gridStart(vm.month, true)
            assertTrue(vm.coversRange(grid, grid.plusDays(41)))
            node(By.pkg(context.packageName).text(title(90, 0)))
        }

        val distant = today.plusDays(270)
        measure("select_distant_month") {
            onMain { vm.select(distant) }
            awaitReady { vm.coversRange(distant.minusDays(60), distant.plusDays(120)) }
            node(By.pkg(context.packageName).text(title(360, 0)))
            assertEquals(distant, vm.selectedDate)
            assertTrue(vm.events.any { it.id == eventIds.getValue(title(360, 0)) })
        }

        measure("cold_description_search") {
            onMain { vm.openSearch() }
            node(By.pkg(context.packageName).clazz("android.widget.EditText")).text = "descriptionneedle"
            awaitReady { !vm.searchLoading && vm.searchResults.map { it.id }.toSet() == descriptionIds }
            assertTrue(vm.searchResults.all { "descriptionneedle" !in it.title && "descriptionneedle" in it.description })
            val upcoming = vm.searchResults.filter { it.startMillis >= today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            node(By.pkg(context.packageName).text(upcoming.minBy { it.startMillis }.title))
        }

        measure("warm_title_search") {
            node(By.pkg(context.packageName).clazz("android.widget.EditText")).text = "Performance day 250"
            val expected = (0 until 4).map { eventIds.getValue(title(250, it)) }.toSet()
            awaitReady { !vm.searchLoading && vm.searchResults.map { it.id }.toSet() == expected }
            node(By.pkg(context.packageName).text(title(250, 0)))
        }
    }

    private fun seedEvents() {
        val zone = ZoneId.systemDefault()
        val description = "Synthetic course notes with preparation details and room information. ".repeat(16)
        val fixtures = (0 until 365).flatMap { day -> (0 until 4).map { slot -> day to slot } }
        fixtures.chunked(100).forEach { chunk ->
            val operations = ArrayList(chunk.map { (day, slot) ->
                val start = today.minusDays(90).plusDays(day.toLong()).atTime(9 + slot * 3, 0)
                    .atZone(zone).toInstant().toEpochMilli()
                ContentProviderOperation.newInsert(Events.CONTENT_URI)
                    .withValue(Events.CALENDAR_ID, calendarId)
                    .withValue(Events.TITLE, title(day, slot))
                    .withValue(Events.DESCRIPTION, description + if (day % 37 == 0 && slot == 0) "descriptionneedle" else "ordinary details")
                    .withValue(Events.DTSTART, start)
                    .withValue(Events.DTEND, start + 3_600_000)
                    .withValue(Events.EVENT_TIMEZONE, zone.id)
                    .withValue(Events.ALL_DAY, 0)
                    .withValue(Events.HAS_ALARM, 0)
                    .withValue(Events.STATUS, Events.STATUS_CONFIRMED)
                    .build()
            })
            val inserted = context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
            chunk.forEachIndexed { index, (day, slot) ->
                val id = ContentUris.parseId(requireNotNull(inserted[index].uri))
                eventIds[title(day, slot)] = id
                if (day % 37 == 0 && slot == 0) descriptionIds += id
            }
        }
    }

    private fun title(day: Int, slot: Int) = "Performance day $day event $slot"

    private fun measure(stage: String, action: () -> Unit) {
        val started = SystemClock.elapsedRealtime()
        val handler = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val samples = AtomicLong()
        val maxLateness = AtomicLong()
        val heartbeat = object : Runnable {
            var dueAt = SystemClock.uptimeMillis()
            override fun run() {
                if (!active.get()) return
                val now = SystemClock.uptimeMillis()
                maxLateness.updateAndGet { maxOf(it, (now - dueAt).coerceAtLeast(0)) }
                samples.incrementAndGet()
                dueAt = now + 25
                handler.postAtTime(this, dueAt)
            }
        }
        handler.post(heartbeat)
        try {
            action()
        } finally {
            active.set(false)
            handler.removeCallbacks(heartbeat)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("sunlith_calendar_performance", JSONObject().apply {
                put("stage", stage)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                put("seeded_events", eventIds.size)
                put("loaded_events", vm.events.size)
                put("search_matches", vm.searchResults.size)
                put("main_heartbeat_samples", samples.get())
                put("main_heartbeat_max_lateness_ms", maxLateness.get())
            }.toString())
        })
    }

    private fun awaitReady(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        var ready = false
        onMain { ready = condition() }
        assertTrue("Dense-calendar operation did not finish; provider error: ${vm.loadError}", ready)
    }

    private fun node(selector: BySelector): UiObject2 {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        val found = device.wait(Until.findObject(selector), 15_000)
        if (found == null) {
            device.takeScreenshot(java.io.File(context.filesDir, "dense-calendar-failure.png"))
            device.dumpWindowHierarchy(java.io.File(context.filesDir, "dense-calendar-failure.xml"))
        }
        return requireNotNull(found) { "Missing dense-calendar UI: $selector" }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun finish() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().forEach(Activity::finish)
            activity?.finish()
        }
        instrumentation.waitForIdleSync()
        activity = null
    }
}
