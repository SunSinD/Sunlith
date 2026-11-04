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
        get() = prefs.getString("theme", "dark") ?: "dark"
        set(value) { prefs.edit().putString("theme", value).apply() }

    var weekStartsMonday: Boolean
        get() = prefs.getBoolean("monday", true)
        set(value) { prefs.edit().putBoolean("monday", value).apply() }

    var widgetOpacity: Int
        get() = prefs.getInt("widget_opacity", 92).coerceIn(0, 100)
        set(value) { prefs.edit().putInt("widget_opacity", value.coerceIn(0, 100)).apply() }
}
