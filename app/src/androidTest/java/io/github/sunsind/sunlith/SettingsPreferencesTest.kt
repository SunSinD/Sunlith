package io.github.sunsind.sunlith

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sunsind.sunlith.system.SettingsStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPreferencesTest {
    private lateinit var context: Context
    private lateinit var isolated: Context
    private val files = mutableSetOf<String>()

    @Before
    fun setUp() {
        requireEmulator()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "sunlith-settings-test-${UUID.randomUUID()}-"
        isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val file = prefix + name
                files += file
                return context.getSharedPreferences(file, mode)
            }
        }
    }

    @After
    fun tearDown() {
        if (::context.isInitialized) files.forEach(context::deleteSharedPreferences)
    }

    @Test
    fun freshPreferencesFollowSystemAndShowAnOpaqueSevenDayWidget() {
        val settings = SettingsStore(isolated)
        assertEquals("system", settings.themeMode)
        assertEquals(100, settings.widgetOpacity)
        assertEquals(7, settings.widgetDays)
        assertFalse(settings.widgetShowCalendarName)
        assertTrue(settings.widgetShowEmptyDays)
        assertEquals("all", settings.agendaDisplay)
        assertEquals("Month", settings.startView)
        assertFalse(settings.collapseCalendarOnScroll)
        assertTrue(settings.navigationAnimation)
        assertTrue(settings.materialYou)
        assertFalse(settings.showNavigationArrows)
        assertTrue(settings.swipeNavigation)
        assertEquals("continuous", settings.agendaRange)
        assertEquals("split", settings.monthLayout)
        assertEquals("list", settings.weekLayout)
        assertEquals("timeline", settings.dayLayout)
        assertEquals(7, settings.visibleWeekDays)
        assertEquals("sunlith", settings.layoutPreset)
    }

    @Test
    fun legacyCardsUpgradeOnceWithoutResettingIndependentLayoutsOrOtherSettings() {
        val prefs = isolated.getSharedPreferences("sunlith", Context.MODE_PRIVATE)
        listOf("sunlith", "planner", "custom").forEach { preset ->
            prefs.edit().clear()
                .putString("day_layout", "cards")
                .putString("month_layout", "full")
                .putString("week_layout", "list")
                .putString("layout_preset", preset)
                .putInt("visible_week_days", 3)
                .putBoolean("keep_selected_date", true)
                .putBoolean("navigation_animation", false)
                .putString("theme", "dark")
                .commit()
            val previous = prefs.all.filterKeys { it != "day_layout" }
            assertEquals("timeline", SettingsStore(isolated).dayLayout)
            assertTrue(prefs.getBoolean("day_timeline_default_v1", false))
            assertEquals(previous, prefs.all.filterKeys { it !in setOf("day_layout", "day_timeline_default_v1") })
            assertEquals("timeline", SettingsStore(isolated).dayLayout)
        }
    }

    @Test
    fun upgradePreservesExistingTimelineAndScheduleChoices() {
        val prefs = isolated.getSharedPreferences("sunlith", Context.MODE_PRIVATE)
        listOf("timeline", "schedule").forEach { layout ->
            prefs.edit().clear().putString("day_layout", layout).putString("layout_preset", "custom").commit()
            assertEquals(layout, SettingsStore(isolated).dayLayout)
            assertEquals(layout, SettingsStore(isolated).dayLayout)
            assertEquals("custom", SettingsStore(isolated).layoutPreset)
        }
    }

    @Test
    fun explicitDayChoicesAfterUpgradeSurviveRepeatedStoreRecreation() {
        val prefs = isolated.getSharedPreferences("sunlith", Context.MODE_PRIVATE)
        prefs.edit().putString("day_layout", "cards").commit()
        val upgraded = SettingsStore(isolated)
        assertEquals("timeline", upgraded.dayLayout)
        listOf("cards", "schedule", "timeline", "cards").forEach { layout ->
            SettingsStore(isolated).dayLayout = layout
            repeat(3) { assertEquals(layout, SettingsStore(isolated).dayLayout) }
        }
    }

    @Test
    fun inheritedAndCustomWeekTimelinesSurviveStoreRecreationWithoutMigration() {
        val prefs = isolated.getSharedPreferences("sunlith", Context.MODE_PRIVATE)
        listOf("planner", "custom", "sunlith").forEach { preset ->
            listOf(3, 7).forEach { days ->
                prefs.edit().clear().putBoolean("day_timeline_default_v1", true)
                    .putString("month_layout", "full").putString("week_layout", "timeline")
                    .putString("day_layout", "timeline").putInt("visible_week_days", days)
                    .putString("layout_preset", preset).commit()
                val before = prefs.all.toMap()
                repeat(3) {
                    val restored = SettingsStore(isolated)
                    assertEquals("timeline", restored.weekLayout)
                    assertEquals(days, restored.visibleWeekDays)
                    assertEquals(preset, restored.layoutPreset)
                    assertEquals(before, prefs.all)
                }
            }
        }
    }

    @Test
    fun widgetControlsKeepValidRangesAndFivePercentOpacitySteps() {
        val settings = SettingsStore(isolated)
        listOf(-20 to 0, 2 to 0, 3 to 5, 97 to 95, 98 to 100, 120 to 100).forEach { (requested, expected) ->
            settings.widgetOpacity = requested
            assertEquals(expected, SettingsStore(isolated).widgetOpacity)
        }
        listOf(0 to 1, 1 to 1, 7 to 7, 31 to 31, 99 to 31).forEach { (requested, expected) ->
            settings.widgetDays = requested
            assertEquals(expected, SettingsStore(isolated).widgetDays)
        }
    }

    @Test
    fun calendarAndWidgetChoicesSurviveRecreatingTheSettingsStore() {
        SettingsStore(isolated).apply {
            themeMode = "light"
            widgetDays = 14
            widgetOpacity = 65
            widgetShowCalendarName = true
            widgetShowEmptyDays = false
            agendaDisplay = "compact"
            startView = "last"
            collapseCalendarOnScroll = true
            navigationAnimation = false
            materialYou = false
            showNavigationArrows = true
            swipeNavigation = false
            agendaRange = "week"
            monthLayout = "full"
            weekLayout = "timeline"
            dayLayout = "schedule"
            visibleWeekDays = 3
            layoutPreset = "custom"
        }
        val restored = SettingsStore(isolated)
        assertEquals("light", restored.themeMode)
        assertEquals(14, restored.widgetDays)
        assertEquals(65, restored.widgetOpacity)
        assertTrue(restored.widgetShowCalendarName)
        assertFalse(restored.widgetShowEmptyDays)
        assertEquals("compact", restored.agendaDisplay)
        assertEquals("last", restored.startView)
        assertTrue(restored.collapseCalendarOnScroll)
        assertFalse(restored.navigationAnimation)
        assertFalse(restored.materialYou)
        assertTrue(restored.showNavigationArrows)
        assertFalse(restored.swipeNavigation)
        assertEquals("week", restored.agendaRange)
        assertEquals("full", restored.monthLayout)
        assertEquals("timeline", restored.weekLayout)
        assertEquals("schedule", restored.dayLayout)
        assertEquals(3, restored.visibleWeekDays)
        assertEquals("custom", restored.layoutPreset)
    }

    @Test
    fun unreadableLayoutChoicesFallBackWithoutChangingOtherPreferences() {
        isolated.getSharedPreferences("sunlith", Context.MODE_PRIVATE).edit()
            .putString("agenda_range", "future-version")
            .putString("month_layout", "future-version")
            .putString("week_layout", "future-version")
            .putString("day_layout", "future-version")
            .putString("layout_preset", "future-version")
            .putInt("visible_week_days", 5)
            .putString("theme", "dark")
            .putInt("widget_days", 14)
            .commit()
        val settings = SettingsStore(isolated)
        assertEquals("continuous", settings.agendaRange)
        assertEquals("split", settings.monthLayout)
        assertEquals("list", settings.weekLayout)
        assertEquals("timeline", settings.dayLayout)
        assertEquals("sunlith", settings.layoutPreset)
        assertEquals(7, settings.visibleWeekDays)
        assertEquals("dark", settings.themeMode)
        assertEquals(14, settings.widgetDays)
    }

    @Test
    fun retiredSchedulePresetKeepsItsExistingLayoutsAsCustom() {
        isolated.getSharedPreferences("sunlith", Context.MODE_PRIVATE).edit()
            .putString("layout_preset", "schedule")
            .putString("month_layout", "split")
            .putString("week_layout", "list")
            .putString("day_layout", "schedule")
            .putInt("visible_week_days", 3)
            .commit()
        val restored = SettingsStore(isolated)
        assertEquals("custom", restored.layoutPreset)
        assertEquals("split", restored.monthLayout)
        assertEquals("list", restored.weekLayout)
        assertEquals("schedule", restored.dayLayout)
        assertEquals(3, restored.visibleWeekDays)
    }

    @Test
    fun rejectedLayoutChoicesPreserveTheLastValidSelection() {
        val settings = SettingsStore(isolated).apply {
            agendaRange = "day"
            monthLayout = "full"
            weekLayout = "timeline"
            dayLayout = "schedule"
            visibleWeekDays = 3
            layoutPreset = "planner"
        }
        assertThrows(IllegalArgumentException::class.java) { settings.agendaRange = "invalid" }
        assertThrows(IllegalArgumentException::class.java) { settings.monthLayout = "invalid" }
        assertThrows(IllegalArgumentException::class.java) { settings.weekLayout = "invalid" }
        assertThrows(IllegalArgumentException::class.java) { settings.dayLayout = "invalid" }
        assertThrows(IllegalArgumentException::class.java) { settings.visibleWeekDays = 5 }
        assertThrows(IllegalArgumentException::class.java) { settings.layoutPreset = "invalid" }
        val restored = SettingsStore(isolated)
        assertEquals("day", restored.agendaRange)
        assertEquals("full", restored.monthLayout)
        assertEquals("timeline", restored.weekLayout)
        assertEquals("schedule", restored.dayLayout)
        assertEquals(3, restored.visibleWeekDays)
        assertEquals("planner", restored.layoutPreset)
    }
}
