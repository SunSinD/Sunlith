package io.github.sunsind.sunlith.system

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.ui.EditorState
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

sealed interface CalendarHandoff {
    data class CalendarFile(val uri: Uri) : CalendarHandoff
    data class Event(val fields: IncomingCalendarEvent) : CalendarHandoff
    data class Rejected(val message: Int) : CalendarHandoff
}

data class IncomingCalendarEvent(
    val title: String,
    val notes: String,
    val location: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
) {
    fun validate(zone: ZoneId): IncomingCalendarEvent {
        require(title.length <= 1000 && notes.length <= 100_000 && location.length <= 10_000)
        require(end > begin)
        val eventZone = if (allDay) ZoneOffset.UTC else zone
        val start = Instant.ofEpochMilli(begin).atZone(eventZone)
        val finish = Instant.ofEpochMilli(end).atZone(eventZone)
        val lastDate = if (allDay) finish.toLocalDate().minusDays(1) else finish.toLocalDate()
        require(start.year in 1900..2100 && lastDate.year in 1900..2100)
        if (allDay) require(start.toLocalTime() == LocalTime.MIDNIGHT && finish.toLocalTime() == LocalTime.MIDNIGHT)
        return this
    }

    fun editor(base: EditorState, zone: ZoneId): EditorState {
        validate(zone)
        val draft = EventDraft(calendarId = base.calendarId ?: 0L, title = title, description = notes,
            location = location, startMillis = begin, endMillis = end, allDay = allDay,
            timeZone = if (allDay) "UTC" else zone.id, reminderMinutes = base.reminders)
        return EditorState.from(draft, zone).copy(calendarId = base.calendarId, lockedCalendar = false)
    }
}

private val calendarMimeTypes = setOf("text/calendar", "text/x-vcalendar", "application/ics")

fun readCalendarHandoff(intent: Intent, resolvedType: String? = intent.type): CalendarHandoff? = try {
    when (intent.action) {
        Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
            if (resolvedType?.substringBefore(';')?.trim()?.lowercase() !in calendarMimeTypes) null
            else {
                val uri = if (intent.action == Intent.ACTION_VIEW) intent.data else {
                    val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
                    stream ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
                }
                if (uri?.scheme == "content" && !uri.authority.isNullOrBlank()) CalendarHandoff.CalendarFile(uri)
                else CalendarHandoff.Rejected(R.string.handoff_invalid_file)
            }
        }
        Intent.ACTION_INSERT -> {
            val target = intent.data
            val eventUri = target == CalendarContract.Events.CONTENT_URI
            val eventMime = intent.type in setOf("vnd.android.cursor.item/event", "vnd.android.cursor.dir/event") && target == null
            if (!eventUri && !eventMime) CalendarHandoff.Rejected(R.string.handoff_invalid_event)
            else if (intent.hasExtra(CalendarContract.Events.RRULE) ||
                intent.hasExtra(CalendarContract.Events.RDATE) ||
                intent.hasExtra(CalendarContract.Events.EXRULE) ||
                intent.hasExtra(CalendarContract.Events.EXDATE) ||
                intent.hasExtra(Intent.EXTRA_EMAIL) ||
                intent.getBooleanExtra(CalendarContract.Events.HAS_ATTENDEE_DATA, false) ||
                intent.hasExtra("attendees")) CalendarHandoff.Rejected(R.string.handoff_personal_events_only)
            else if (!intent.hasExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME) || !intent.hasExtra(CalendarContract.EXTRA_EVENT_END_TIME))
                CalendarHandoff.Rejected(R.string.handoff_invalid_time)
            else CalendarHandoff.Event(IncomingCalendarEvent(
                title = intent.getStringExtra(CalendarContract.Events.TITLE).orEmpty(),
                notes = intent.getStringExtra(CalendarContract.Events.DESCRIPTION).orEmpty(),
                location = intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION).orEmpty(),
                begin = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, Long.MIN_VALUE),
                end = intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, Long.MIN_VALUE),
                allDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false),
            ).validate(ZoneId.systemDefault()))
        }
        else -> null
    }
} catch (_: Exception) {
    CalendarHandoff.Rejected(R.string.handoff_invalid_event)
}

fun CalendarHandoff.save(): Bundle = Bundle().also { out ->
    when (this) {
        is CalendarHandoff.CalendarFile -> { out.putString("kind", "ics"); out.putString("uri", uri.toString()) }
        is CalendarHandoff.Event -> {
            out.putString("kind", "event")
            out.putString("title", fields.title); out.putString("notes", fields.notes); out.putString("location", fields.location)
            out.putLong("begin", fields.begin); out.putLong("end", fields.end); out.putBoolean("all_day", fields.allDay)
        }
        is CalendarHandoff.Rejected -> Unit
    }
}

fun restoreCalendarHandoff(saved: Bundle?): CalendarHandoff? = runCatching {
    when (saved?.getString("kind")) {
        "ics" -> saved.getString("uri")?.let(Uri::parse)?.takeIf { it.scheme == "content" && !it.authority.isNullOrBlank() }?.let { CalendarHandoff.CalendarFile(it) }
        "event" -> CalendarHandoff.Event(IncomingCalendarEvent(saved.getString("title").orEmpty(), saved.getString("notes").orEmpty(),
            saved.getString("location").orEmpty(), saved.getLong("begin"), saved.getLong("end"), saved.getBoolean("all_day")).validate(ZoneId.systemDefault()))
        else -> null
    }
}.getOrNull()
