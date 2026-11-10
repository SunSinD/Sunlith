package io.github.sunsind.sunlith.system

import android.content.Context
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sunlith", Context.MODE_PRIVATE)

    var courseColorSlots: Map<String, Int>
        get() = runCatching {
            val saved = JSONObject(prefs.getString("course_color_slots", "{}").orEmpty())
            saved.keys().asSequence().associateWith { saved.getInt(it) }.filterValues { it in 0..9 }
        }.getOrDefault(emptyMap())
        set(value) { prefs.edit().putString("course_color_slots", JSONObject(value).toString()).apply() }

    init {
        if (!prefs.getBoolean("list_defaults_v2", false)) {
            val migration = prefs.edit().putBoolean("list_defaults_v2", true)
            // Restore the preset default while retaining explicitly customized layouts.
            if (prefs.getString("layout_preset", "sunlith") in setOf("sunlith", "planner") &&
                prefs.getString("day_layout", "cards") == "timeline") migration.putString("day_layout", "cards")
            migration.apply()
        }
    }

    var selectedCalendarIds: Set<Long>
        get() = prefs.getStringSet("calendars", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()
        set(value) { prefs.edit().putStringSet("calendars", value.map(Long::toString).toSet()).apply() }

    var hasChosenCalendars: Boolean
        get() = prefs.getBoolean("chosen", false)
        set(value) { prefs.edit().putBoolean("chosen", value).apply() }

    var remindersEnabled: Boolean
        get() = prefs.getBoolean("reminders", true)
        set(value) { prefs.edit().putBoolean("reminders", value).apply() }

    var themeMode: String
        get() = prefs.getString("theme", "system")?.takeIf { it in setOf("system", "dark", "light") } ?: "system"
        set(value) { require(value in setOf("system", "dark", "light")); prefs.edit().putString("theme", value).apply() }

    var weekStartsMonday: Boolean
        get() = prefs.getBoolean("monday", true)
        set(value) { prefs.edit().putBoolean("monday", value).apply() }

    var widgetOpacity: Int
        get() = ((prefs.getInt("widget_opacity", 100).coerceIn(0, 100) + 2) / 5) * 5
        set(value) { prefs.edit().putInt("widget_opacity", ((value.coerceIn(0, 100) + 2) / 5) * 5).apply() }

    var widgetDays: Int
        get() = prefs.getInt("widget_days", 7).coerceIn(1, 31)
        set(value) { prefs.edit().putInt("widget_days", value.coerceIn(1, 31)).apply() }

    var widgetShowCalendarName: Boolean
        get() = prefs.getBoolean("widget_calendar_name", false)
        set(value) { prefs.edit().putBoolean("widget_calendar_name", value).apply() }

    var widgetShowEmptyDays: Boolean
        get() = prefs.getBoolean("widget_empty_days", true)
        set(value) { prefs.edit().putBoolean("widget_empty_days", value).apply() }

    var agendaDisplay: String
        get() = prefs.getString("agenda_display", "all")?.takeIf { it in setOf("all", "compact", "events") } ?: "all"
        set(value) { require(value in setOf("all", "compact", "events")); prefs.edit().putString("agenda_display", value).apply() }

    var collapseCalendarOnScroll: Boolean
        get() = prefs.getBoolean("collapse_calendar_on_scroll", false)
        set(value) { prefs.edit().putBoolean("collapse_calendar_on_scroll", value).apply() }

    var startView: String
        get() = prefs.getString("start_view", "Month")?.takeIf { it in setOf("Month", "Week", "Day", "last") } ?: "Month"
        set(value) { require(value in setOf("Month", "Week", "Day", "last")); prefs.edit().putString("start_view", value).apply() }

    var navigationAnimation: Boolean
        get() = prefs.getBoolean("navigation_animation", true)
        set(value) { prefs.edit().putBoolean("navigation_animation", value).apply() }

    var materialYou: Boolean
        get() = prefs.getBoolean("material_you", true)
        set(value) { prefs.edit().putBoolean("material_you", value).apply() }

    var showNavigationArrows: Boolean
        get() = prefs.getBoolean("navigation_arrows", false)
        set(value) { prefs.edit().putBoolean("navigation_arrows", value).apply() }

    var swipeNavigation: Boolean
        get() = prefs.getBoolean("swipe_navigation", true)
        set(value) { prefs.edit().putBoolean("swipe_navigation", value).apply() }

    var keepSelectedDate: Boolean
        get() = prefs.getBoolean("keep_selected_date", false)
        set(value) { prefs.edit().putBoolean("keep_selected_date", value).apply() }

    var agendaRange: String
        get() = choice("agenda_range", "continuous", setOf("continuous", "month", "week", "day"))
        set(value) { saveChoice("agenda_range", value, setOf("continuous", "month", "week", "day")) }

    var monthLayout: String
        get() = choice("month_layout", "split", setOf("split", "full"))
        set(value) { saveChoice("month_layout", value, setOf("split", "full")) }

    var weekLayout: String
        get() = choice("week_layout", "list", setOf("list", "timeline", "continuous"))
        set(value) { saveChoice("week_layout", value, setOf("list", "timeline", "continuous")) }

    var groupEmptyHours: Boolean
        get() = prefs.getBoolean("group_empty_hours", false)
        set(value) { prefs.edit().putBoolean("group_empty_hours", value).apply() }

    var dayLayout: String
        get() = choice("day_layout", "cards", setOf("cards", "timeline", "schedule"))
        set(value) { saveChoice("day_layout", value, setOf("cards", "timeline", "schedule")) }

    var dayTimelineZoom: Int
        get() = prefs.getInt("day_timeline_zoom", 100).coerceIn(50, 200)
        set(value) { prefs.edit().putInt("day_timeline_zoom", value.coerceIn(50, 200)).apply() }

    var weekTimelineZoom: Int
        get() = prefs.getInt("week_timeline_zoom", 100).coerceIn(50, 200)
        set(value) { prefs.edit().putInt("week_timeline_zoom", value.coerceIn(50, 200)).apply() }

    var visibleWeekDays: Int
        get() = prefs.getInt("visible_week_days", 7).takeIf { it in setOf(3, 5, 7) } ?: 7
        set(value) { require(value in setOf(3, 5, 7)); prefs.edit().putInt("visible_week_days", value).apply() }

    var layoutPreset: String
        get() = choice("layout_preset", "sunlith", setOf("sunlith", "schedule", "planner", "custom")).let { if (it == "schedule") "custom" else it }
        set(value) { saveChoice("layout_preset", value, setOf("sunlith", "schedule", "planner", "custom")) }

    private fun choice(key: String, fallback: String, values: Set<String>): String =
        prefs.getString(key, fallback)?.takeIf { it in values } ?: fallback

    private fun saveChoice(key: String, value: String, values: Set<String>) {
        require(value in values)
        prefs.edit().putString(key, value).apply()
    }
}
