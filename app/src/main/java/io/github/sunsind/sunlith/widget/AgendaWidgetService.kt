package io.github.sunsind.sunlith.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.system.SettingsStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = AgendaFactory(applicationContext)
}

private sealed interface WidgetRow {
    data class Entry(val day: LocalDate, val event: CalendarEvent?, val firstOfDay: Boolean) : WidgetRow
    data class Message(val text: Int) : WidgetRow
}

private data class WidgetSnapshot(
    val rows: List<WidgetRow> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val locale: Locale = Locale.getDefault(),
    val showCalendarName: Boolean = false,
)

private class AgendaFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    @Volatile private var snapshot = WidgetSnapshot()

    override fun onCreate() = Unit
    override fun onDestroy() { snapshot = WidgetSnapshot() }

    override fun onDataSetChanged() {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val settings = SettingsStore(context)
        val base = WidgetSnapshot(today = today, zone = zone,
            locale = context.resources.configuration.locales[0], showCalendarName = settings.widgetShowCalendarName)
        snapshot = try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                base.copy(rows = listOf(WidgetRow.Message(R.string.widget_calendar_access)))
            } else {
                val repo = CalendarRepository(context)
                val calendars = repo.calendars()
                val ids = if (settings.hasChosenCalendars) settings.selectedCalendarIds.intersect(calendars.map { it.id }.toSet())
                    else calendars.filter { it.visible }.map { it.id }.toSet()
                if (ids.isEmpty()) {
                    base.copy(rows = listOf(WidgetRow.Message(R.string.widget_choose_calendars)))
                } else {
                    val end = today.plusDays(settings.widgetDays.coerceIn(1, 31).toLong())
                    val startMillis = minOf(today.atStartOfDay(zone).toInstant().toEpochMilli(), today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    val endMillis = maxOf(end.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    val index = mutableMapOf<LocalDate, MutableList<CalendarEvent>>()
                    repo.events(startMillis, endMillis, ids).forEach { event ->
                        val eventZone = if (event.allDay) ZoneOffset.UTC else zone
                        val first = Instant.ofEpochMilli(event.startMillis).atZone(eventZone).toLocalDate()
                        val last = Instant.ofEpochMilli(if (event.endMillis > event.startMillis) event.endMillis - 1 else event.startMillis)
                            .atZone(eventZone).toLocalDate()
                        var day = maxOf(first, today)
                        val until = minOf(last, end.minusDays(1))
                        while (!day.isAfter(until)) {
                            index.getOrPut(day) { mutableListOf() }.add(event)
                            day = day.plusDays(1)
                        }
                    }
                    val rows = buildList {
                        var day = today
                        while (day < end) {
                            val events = index[day].orEmpty().sortedWith(compareBy<CalendarEvent> { !it.allDay }.thenBy { it.startMillis }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                            if (events.isEmpty() && settings.widgetShowEmptyDays) add(WidgetRow.Entry(day, null, true))
                            events.forEachIndexed { index, event -> add(WidgetRow.Entry(day, event, index == 0)) }
                            day = day.plusDays(1)
                        }
                    }
                    base.copy(rows = rows)
                }
            }
        } catch (_: SecurityException) {
            base.copy(rows = listOf(WidgetRow.Message(R.string.widget_calendar_access)))
        } catch (_: Exception) {
            base.copy(rows = listOf(WidgetRow.Message(R.string.widget_calendar_unavailable)))
        }
    }

    override fun getCount(): Int = snapshot.rows.size

    override fun getViewAt(position: Int): RemoteViews? {
        val state = snapshot
        val row = state.rows.getOrNull(position) ?: return null
        val views = RemoteViews(context.packageName, R.layout.widget_event)
        if (row is WidgetRow.Message) {
            views.setViewVisibility(R.id.widget_divider, View.GONE)
            views.setViewVisibility(R.id.widget_day, View.GONE)
            views.setViewVisibility(R.id.widget_time, View.GONE)
            views.setViewVisibility(R.id.widget_meta, View.GONE)
            views.setTextViewText(R.id.widget_title, context.getString(row.text))
            views.setTextColor(R.id.widget_title, Color.rgb(242, 242, 242))
            views.setContentDescription(R.id.widget_row, context.getString(row.text))
            views.setOnClickFillInIntent(R.id.widget_row, Intent())
            return views
        }
        row as WidgetRow.Entry
        val heading = dayHeading(row.day, state)
        views.setTextViewText(R.id.widget_day, heading)
        views.setViewVisibility(R.id.widget_day, if (row.firstOfDay) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_divider, if (row.firstOfDay && position > 0) View.VISIBLE else View.GONE)
        val event = row.event
        if (event == null) {
            views.setViewVisibility(R.id.widget_time, View.GONE)
            views.setViewVisibility(R.id.widget_meta, View.GONE)
            views.setTextViewText(R.id.widget_title, context.getString(R.string.no_events))
            views.setTextColor(R.id.widget_title, Color.rgb(163, 163, 163))
            views.setContentDescription(R.id.widget_row, context.getString(R.string.widget_empty_day_action, heading))
            views.setOnClickFillInIntent(R.id.widget_row, Intent().putExtra("create_event", true).putExtra("create_date", row.day.toString()))
            return views
        }
        val eventStart = Instant.ofEpochMilli(event.startMillis)
        val time = when {
            event.allDay -> context.getString(R.string.all_day)
            eventStart.atZone(state.zone).toLocalDate() < row.day -> context.getString(R.string.ongoing)
            else -> android.text.format.DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(state.zone) }.format(Date.from(eventStart))
        }
        val title = event.title.ifBlank { context.getString(R.string.untitled) }
        val meta = buildList {
            if (event.location.isNotBlank()) add(event.location)
            if (state.showCalendarName && event.calendarName.isNotBlank()) add(event.calendarName)
        }.joinToString(", ")
        views.setViewVisibility(R.id.widget_time, View.VISIBLE)
        views.setTextViewText(R.id.widget_time, time)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextColor(R.id.widget_title, Color.rgb(242, 242, 242))
        views.setTextViewText(R.id.widget_meta, meta)
        views.setViewVisibility(R.id.widget_meta, if (meta.isBlank()) View.GONE else View.VISIBLE)
        views.setContentDescription(R.id.widget_row, "$title, $heading, $time${if (meta.isNotBlank()) ", $meta" else ""}")
        views.setOnClickFillInIntent(R.id.widget_row, Intent().putExtra("event_id", event.id).putExtra("event_begin", event.startMillis))
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false

    private fun dayHeading(day: LocalDate, state: WidgetSnapshot): String {
        val relative = when (day) {
            state.today -> context.getString(R.string.today)
            state.today.plusDays(1) -> context.getString(R.string.tomorrow)
            else -> null
        }
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(state.locale, if (relative == null) "EEEMMMd" else "MMMd")
        val date = day.format(DateTimeFormatter.ofPattern(pattern, state.locale))
        return if (relative == null) date else context.getString(R.string.widget_relative_date, relative, date)
    }
}
