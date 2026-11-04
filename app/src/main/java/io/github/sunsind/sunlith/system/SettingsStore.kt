package io.github.sunsind.sunlith.system

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sunlith", Context.MODE_PRIVATE)

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

    var startView: String
        get() = prefs.getString("start_view", "Month")?.takeIf { it in setOf("Month", "Week", "Day", "last") } ?: "Month"
        set(value) { require(value in setOf("Month", "Week", "Day", "last")); prefs.edit().putString("start_view", value).apply() }

    var swipeNavigation: Boolean
        get() = prefs.getBoolean("swipe_navigation", true)
        set(value) { prefs.edit().putBoolean("swipe_navigation", value).apply() }

    var navigationAnimation: Boolean
        get() = prefs.getBoolean("navigation_animation", true)
        set(value) { prefs.edit().putBoolean("navigation_animation", value).apply() }
}
