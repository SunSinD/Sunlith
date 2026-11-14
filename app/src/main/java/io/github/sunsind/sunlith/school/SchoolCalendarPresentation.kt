package io.github.sunsind.sunlith.school

import android.content.Context
import android.provider.CalendarContract.Events
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.SchoolEventAbsence
import io.github.sunsind.sunlith.data.SchoolEventInfo
import io.github.sunsind.sunlith.data.SchoolEventPeriod

internal object SchoolCalendarPresentation {
    fun annotate(context: Context, events: List<CalendarEvent>): List<CalendarEvent> {
        if (events.isEmpty()) return events
        val record = try { SchoolStore(context).load() } catch (_: SchoolFailure) { null } ?: return events
        val entries = (record.ledger + record.journal?.after.orEmpty()).filter { it.rowId >= 0 }.groupBy { it.rowId }
        val ids = events.filter { it.calendarId == record.calendarId && it.id in entries }.map { it.id }.distinct()
        if (ids.isEmpty()) return events
        val writer = SchoolCalendarWriter(context)
        val rows = ids.chunked(300).flatMap { chunk ->
            context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID, *SchoolCalendarWriter.COLUMNS),
                "${Events.CALENDAR_ID}=? AND ${Events._ID} IN (${chunk.joinToString(",") { "?" }}) AND ${Events.DELETED}=0",
                (listOf(record.calendarId.toString()) + chunk.map(Long::toString)).toTypedArray(), null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((arrayOf(Events._ID, *SchoolCalendarWriter.COLUMNS)).associateWith { name ->
                        cursor.getColumnIndex(name).let { if (it < 0 || cursor.isNull(it)) null else cursor.getString(it) }
                    })
                }
            }.orEmpty()
        }.associateBy { it[Events._ID]?.toLongOrNull() }
        return events.map { event ->
            val row = rows[event.id]
            val owned = entries[event.id]?.firstOrNull { entry ->
                val written = writer.writtenValues(record.calendarId, entry)
                row != null && event.calendarId == record.calendarId &&
                    SchoolCalendarWriter.identity(entry, record.calendarId, row, context.packageName) &&
                    CONTENT_COLUMNS.all { written[it] == row[it] } &&
                    listOf(Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME).all { row[it].isNullOrBlank() } &&
                    row[Events.HAS_ATTENDEE_DATA] != "1" &&
                    event.title == row[Events.TITLE].orEmpty() && event.description == row[Events.DESCRIPTION].orEmpty() &&
                    event.location == row[Events.EVENT_LOCATION].orEmpty() &&
                    entry.item.startMillis == event.startMillis && entry.item.endMillis == event.endMillis &&
                    entry.item.allDay == event.allDay
            }
            if (owned == null) event else event.copy(school = owned.item.let { item ->
                SchoolEventInfo(item.course, item.group, item.classKey, item.classKind, item.kind, item.title,
                    listOf(SchoolEventPeriod(event.id, event.startMillis, event.endMillis, event.location, event.description)),
                    if (item.teacherAbsent || item.kind == "absence") listOf(SchoolEventAbsence(
                        item.startMillis, item.endMillis, item.allDay, item.notes)) else emptyList(),
                    item.collegeLift.takeIf { item.kind == SchoolNoticeInterpreter.CLASS_KIND && it.isNotEmpty() }
                        ?.let { SchoolClassLift.parse(it) }?.let { (noticeDate, until) -> SchoolClassLift(item.date, until, noticeDate) })
            })
        }
    }

    private val CONTENT_COLUMNS = listOf(Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
        Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE, Events.RDATE,
        Events.EXRULE, Events.EXDATE, Events.DURATION, Events.STATUS)
}
