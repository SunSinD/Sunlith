package io.github.sunsind.sunlith.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.time.format.FormatStyle
import java.util.Locale

class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = AgendaFactory(applicationContext)
}

private class AgendaFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var events = emptyList<CalendarEvent>()
    override fun onCreate() = Unit
    override fun onDestroy() { events = emptyList() }
    override fun onDataSetChanged() {
        events = runCatching {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return@runCatching emptyList()
            val repo = CalendarRepository(context)
            val settings = SettingsStore(context)
            val ids = if (settings.hasChosenCalendars) settings.selectedCalendarIds else repo.calendars().filter { it.visible }.map { it.id }.toSet()
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val queryStart = minOf(LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli(), LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            repo.events(queryStart, LocalDate.now().plusDays(30).atStartOfDay(zone).toInstant().toEpochMilli(), ids)
                .filter { if (it.allDay) Instant.ofEpochMilli(it.endMillis).atZone(ZoneOffset.UTC).toLocalDate() > LocalDate.now() else it.endMillis > now }
                .sortedWith(compareBy<CalendarEvent> { day(it) }.thenBy { !it.allDay }.thenBy { it.startMillis })
                .take(100)
        }.getOrElse { emptyList() }
    }
    override fun getCount(): Int = events.size
    override fun getViewAt(position: Int): RemoteViews? {
        val event = events.getOrNull(position) ?: return null
        val views = RemoteViews(context.packageName, R.layout.widget_event)
        val eventDay = day(event)
        val heading = when (eventDay) {
            LocalDate.now() -> context.getString(R.string.today)
            LocalDate.now().plusDays(1) -> context.getString(R.string.tomorrow)
            else -> eventDay.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        }
        views.setTextViewText(R.id.widget_day, heading.uppercase(Locale.getDefault()))
        views.setViewVisibility(R.id.widget_day, if (position == 0 || day(events[position - 1]) != eventDay) View.VISIBLE else View.GONE)
        val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())
        val originalDay = Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val time = if (event.allDay) context.getString(R.string.all_day) else if (originalDay < LocalDate.now()) context.getString(R.string.ongoing) else format.format(Instant.ofEpochMilli(event.startMillis))
        views.setTextViewText(R.id.widget_time, time)
        views.setTextViewText(R.id.widget_title, event.title.ifBlank { context.getString(R.string.untitled) })
        val meta = if (event.location.isNotBlank()) event.location else event.calendarName
        views.setTextViewText(R.id.widget_meta, meta)
        views.setViewVisibility(R.id.widget_meta, if (meta.isBlank()) View.GONE else View.VISIBLE)
        views.setContentDescription(R.id.widget_row, "${event.title}, $heading, $time${if (meta.isNotBlank()) ", $meta" else ""}")
        views.setOnClickFillInIntent(R.id.widget_row, Intent().putExtra("event_id", event.id).putExtra("event_begin", event.startMillis))
        return views
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = events.getOrNull(position)?.let { it.id * 31 + it.startMillis } ?: position.toLong()
    override fun hasStableIds(): Boolean = true
    private fun day(event: CalendarEvent): LocalDate = maxOf(LocalDate.now(), Instant.ofEpochMilli(event.startMillis).atZone(if (event.allDay) ZoneOffset.UTC else ZoneId.systemDefault()).toLocalDate())
}
