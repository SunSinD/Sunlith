package io.github.sunsind.sunlith.data

import android.content.Context
import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VAlarm
import biweekly.component.VEvent
import biweekly.io.TimezoneAssignment
import biweekly.parameter.Related
import biweekly.property.DateEnd
import biweekly.property.DateStart
import biweekly.property.RawProperty
import biweekly.property.RecurrenceId
import biweekly.property.RecurrenceRule
import biweekly.property.Trigger
import biweekly.util.DateTimeComponents
import biweekly.util.ICalDate
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.TimeZone
import java.util.UUID

data class ImportPreview(
    val total: Int,
    val importable: Int,
    val skipped: Int,
    val messages: List<String> = emptyList(),
)

data class ImportReport(
    val imported: Int,
    val duplicates: Int,
    val skipped: Int,
    val messages: List<String> = emptyList(),
)

class IcsTransfer(context: Context, private val repository: CalendarRepository) {
    var exportWarnings: List<String> = emptyList()
        private set

    fun inspect(input: InputStream): ImportPreview {
        val parsed = IcsCodec.parse(input)
        return ImportPreview(parsed.total, parsed.events.size, parsed.total - parsed.events.size, parsed.messages)
    }

    fun import(input: InputStream, calendarId: Long): ImportReport {
        repository.writableCalendar(calendarId)
        val parsed = IcsCodec.parse(input)
        val existing = repository.storedEvents(setOf(calendarId))
        val uids = existing.mapTo(mutableSetOf()) { it.uid }
        val signatures = existing.filter { it.invalidReason == null }.mapTo(mutableSetOf()) { IcsCodec.signature(it.draft) }
        val policy = repository.reminderPolicy(calendarId)
        var imported = 0
        var duplicates = 0
        var skipped = parsed.total - parsed.events.size
        val messages = parsed.messages.toMutableList()
        existing.filter { it.invalidReason != null }.take(IcsCodec.MAX_MESSAGES - messages.size).forEach {
            messages += "${it.draft.title.ifBlank { "Event ${it.draft.id}" }}: existing malformed provider data was excluded from detail-based duplicate matching."
        }
        for (item in parsed.events) {
            val acceptedReminders = policy.accepted(item.draft.reminderMinutes)
            val draft = item.draft.copy(calendarId = calendarId, reminderMinutes = acceptedReminders)
            val signature = IcsCodec.signature(draft)
            if (item.uid in uids || !item.hasSourceUid && signature in signatures) {
                duplicates++
                continue
            }
            try {
                if (acceptedReminders != item.draft.reminderMinutes.distinct().sorted() && messages.size < IcsCodec.MAX_MESSAGES) {
                    messages += "${draft.title}: reminders were trimmed to this calendar's supported count and time limits; the event was kept."
                }
                repository.saveImported(draft, item.uid)
                imported++
                uids += item.uid
                signatures += signature
            } catch (failure: SecurityException) {
                throw failure
            } catch (failure: Exception) {
                skipped++
                if (messages.size < IcsCodec.MAX_MESSAGES) messages += "${draft.title}: ${failure.message ?: "could not save this event"}"
            }
        }
        return ImportReport(imported, duplicates, skipped, messages)
    }

    fun export(output: OutputStream, calendarIds: Set<Long>): Int {
        exportWarnings = emptyList()
        require(calendarIds.isNotEmpty()) { "Choose at least one calendar to export." }
        val accessible = repository.calendars().mapTo(mutableSetOf()) { it.id }
        require(calendarIds.all { it in accessible }) { "One of the selected calendars is no longer available." }
        val records = repository.storedEvents(calendarIds)
        require(records.size <= IcsCodec.MAX_EVENTS) { "Export at most ${IcsCodec.MAX_EVENTS} events at a time." }
        val byId = records.associateBy { it.draft.id }
        val bySyncId = records.filter { it.syncId != null }.associateBy { it.syncId }
        fun groupUid(record: StoredEvent): String = record.originalId?.let { byId[it]?.uid }
            ?: record.originalSyncId?.let { bySyncId[it]?.uid } ?: record.uid
        val invalidGroups = records.filter { it.invalidReason != null }.mapTo(mutableSetOf(), ::groupUid)
        val valid = records.filter { groupUid(it) !in invalidGroups }
        val skipped = records.filter { groupUid(it) in invalidGroups }
        if (skipped.isNotEmpty()) exportWarnings = listOf("${skipped.size} events were skipped because their provider data or recurring series could not be exported safely.") +
            skipped.take(IcsCodec.MAX_MESSAGES - 1).map { "${it.draft.title.ifBlank { "Event ${it.draft.id}" }}: ${it.invalidReason ?: "belongs to a recurring series with malformed provider data"}" }
        val calendar = IcsCodec.buildCalendar(valid)
        Biweekly.write(calendar).go(output)
        output.flush()
        return valid.size
    }
}

internal data class ParsedImport(val total: Int, val events: List<ImportEvent>, val messages: List<String>)
internal data class ImportEvent(val uid: String, val draft: EventDraft, val hasSourceUid: Boolean = true)

internal object IcsCodec {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_EVENTS = 2000
    const val MAX_MESSAGES = 30

    fun parse(input: InputStream): ParsedImport {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(bytes.size() + read <= MAX_BYTES) { "This calendar file is too large. The limit is 5 MB." }
            bytes.write(buffer, 0, read)
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            .removePrefix("\uFEFF")
        require(Regex("(?im)^BEGIN:VCALENDAR\\s*$").containsMatchIn(text)) { "Choose a valid .ics calendar file." }
        require(Regex("(?im)^END:VCALENDAR\\s*$").containsMatchIn(text)) { "This calendar file is incomplete." }
        val announcedEvents = Regex("(?im)^BEGIN:VEVENT\\s*$").findAll(text).count()
        require(announcedEvents <= MAX_EVENTS) { "Import at most $MAX_EVENTS events at a time." }
        val calendars = Biweekly.parse(text).defaultTimezone(TimeZone.getDefault()).all()
        require(calendars.isNotEmpty()) { "No calendar was found in this file." }
        val total = calendars.sumOf { it.events.size }
        require(total <= MAX_EVENTS && total == announcedEvents) { "This calendar contains malformed event data." }
        val messages = mutableListOf<String>()
        val result = mutableListOf<ImportEvent>()
        fun message(value: String) { if (messages.size < MAX_MESSAGES) messages += value }
        for (calendar in calendars) {
            if (calendar.method?.value.equals("CANCEL", ignoreCase = true)) {
                message("Cancellation files are not imported as new events.")
                continue
            }
            val exceptionUids = calendar.events.filter { it.recurrenceId != null }.mapNotNull { it.uid?.value }.toSet()
            if (calendar.todos.isNotEmpty() || calendar.journals.isNotEmpty()) message("Tasks and journals are not imported.")
            for (event in calendar.events) {
                val label = normalizeText(event.summary?.value).takeIf(String::isNotBlank) ?: "Untitled event"
                try {
                    require(event.recurrenceId == null && event.uid?.value !in exceptionUids && event.exceptionDates.isEmpty()
                        && event.recurrenceDates.isEmpty() && event.exceptionRules.isEmpty()) {
                        "repeating series with exceptions are not imported; use your calendar account to keep them intact"
                    }
                    require(!event.status?.value.equals("CANCELLED", true) && !event.status?.value.equals("CANCELED", true)) {
                        "canceled event was skipped"
                    }
                    val startProperty = event.dateStart ?: error("missing start date")
                    val startValue = startProperty.value ?: error("missing start date")
                    val allDay = !startValue.hasTime()
                    val start = if (allDay) dateMillis(startValue) else startValue.time
                    val recurrence = event.recurrenceRule?.let(::recurrenceText)
                    require(event.experimentalProperties.none { it.name.equals("RRULE", true) }) { "invalid recurrence rule" }
                    val tz = if (allDay) "UTC" else {
                        val rawId = startProperty.parameters.timezoneId?.removePrefix("/")
                            ?: calendar.timezoneInfo.getTimezone(startProperty)?.timeZone?.id
                            ?: if (startValue.rawComponents?.isUtc == true) "UTC" else TimeZone.getDefault().id
                        runCatching { ZoneId.of(rawId).id }.getOrElse { error("unsupported timezone '$rawId'; event was skipped to keep its time intact") }
                    }
                    val nominalDuration = event.duration?.value
                    if (!allDay && recurrence != null && nominalDuration != null &&
                        ((nominalDuration.days ?: 0) > 0 || (nominalDuration.weeks ?: 0) > 0)) {
                        require(ZoneId.of(tz).rules.nextTransition(Instant.ofEpochMilli(start)) == null) {
                            "timed repeating events with day/week durations in a timezone with clock changes cannot be imported safely"
                        }
                    }
                    require(event.dateEnd == null || event.dateEnd.value.hasTime() == startValue.hasTime()) {
                        "start and end dates use different date types"
                    }
                    val end = event.dateEnd?.value?.let { if (allDay) dateMillis(it) else it.time }
                        ?: event.duration?.value?.let { duration ->
                            require(!allDay || !duration.hasTime()) { "all-day event has a timed duration" }
                            CalendarDates.durationEndMillis(start, duration.toString(), allDay, tz)
                        } ?: (start + if (allDay) 86_400_000 else 0)
                    val reminders = event.alarms.mapNotNull { alarm ->
                        if (!alarm.action?.value.equals("DISPLAY", true)) return@mapNotNull null
                        val trigger = alarm.trigger ?: return@mapNotNull null
                        val relative = trigger.duration ?: return@mapNotNull null
                        if (trigger.related != null && trigger.related != Related.START) return@mapNotNull null
                        val offset = relative.toMillis()
                        if (offset > 0 || offset % 60_000L != 0L) return@mapNotNull null
                        (-offset / 60_000L).takeIf { it in 0..525_600L }?.toInt()
                    }.distinct().sorted()
                    if (reminders.size != event.alarms.size) message("$label: some advanced alarms were omitted.")
                    if (event.attendees.isNotEmpty() || event.organizer != null) message("$label: imported as a personal copy; guests are not invited.")
                    if (!allDay && calendar.timezoneInfo.isFloating(startProperty)) message("$label: floating time uses ${TimeZone.getDefault().id}.")
                    val draft = EventDraft(calendarId = 0, title = label, description = normalizeText(event.description?.value),
                        location = normalizeText(event.location?.value), startMillis = start, endMillis = end, allDay = allDay,
                        timeZone = tz, rrule = recurrence, reminderMinutes = reminders)
                    CalendarDates.validate(draft)
                    val sourceUid = event.uid?.value?.takeIf(String::isNotBlank)
                    result += ImportEvent(sourceUid ?: "${UUID.randomUUID()}@sunlith-import", draft, sourceUid != null)
                } catch (failure: Exception) {
                    message("$label: ${failure.message ?: "unsupported event"}.")
                }
            }
        }
        return ParsedImport(total, result, messages)
    }

    fun recurrence(rule: String): RecurrenceRule {
        require(rule.length <= 2048 && '\r' !in rule && '\n' !in rule) { "Invalid recurrence rule." }
        val parsed = Biweekly.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:rule@sunlith\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20260101T000000Z\r\nRRULE:$rule\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n").first()
        val recurrence = parsed.events.single().recurrenceRule ?: error("Invalid recurrence rule.")
        require(recurrence.value?.frequency != null) { "Invalid recurrence frequency." }
        require(recurrence.value.count == null || recurrence.value.count > 0) { "A repeat count must be positive." }
        require(recurrence.value.interval == null || recurrence.value.interval > 0) { "A repeat interval must be positive." }
        require(recurrence.value.byMonth.all { it in 1..12 } && recurrence.value.byMonthDay.all { it in -31..31 && it != 0 }
            && recurrence.value.byHour.all { it in 0..23 } && recurrence.value.byMinute.all { it in 0..59 }
            && recurrence.value.bySecond.all { it in 0..60 } && recurrence.value.byYearDay.all { it in -366..366 && it != 0 }
            && recurrence.value.byWeekNo.all { it in -53..53 && it != 0 } && recurrence.value.bySetPos.all { it in -366..366 && it != 0 }) {
            "The recurrence rule contains an invalid date or time."
        }
        return recurrence
    }

    private fun recurrenceText(rule: RecurrenceRule): String {
        val calendar = ICalendar()
        val event = VEvent()
        event.recurrenceRule = rule
        calendar.addEvent(event)
        val text = Biweekly.write(calendar).go().replace(Regex("\r?\n[ \t]"), "")
        return Regex("(?m)^RRULE:(.*)\\r?$").find(text)?.groupValues?.get(1)?.trim()
            ?: error("The recurrence rule could not be read.")
    }

    fun signature(draft: EventDraft): String = listOf(normalizeText(draft.title).trim(), draft.startMillis, draft.endMillis,
        draft.allDay, normalizeText(draft.description), normalizeText(draft.location), draft.rrule.orEmpty()).joinToString("\u0000")

    private fun normalizeText(value: String?): String = value.orEmpty().replace("\r\n", "\n").replace('\r', '\n')

    private fun dateMillis(value: ICalDate): Long {
        val raw = value.rawComponents
        val date = if (raw != null) LocalDate.of(raw.year, raw.month, raw.date)
        else Instant.ofEpochMilli(value.time).atZone(ZoneId.systemDefault()).toLocalDate()
        return CalendarDates.allDayMillis(date)
    }

    private fun dateOnly(millis: Long): ICalDate {
        val date = CalendarDates.allDayDate(millis)
        return ICalDate(DateTimeComponents(date.year, date.monthValue, date.dayOfMonth), false)
    }

    fun buildCalendar(records: List<StoredEvent>): ICalendar {
        val calendar = ICalendar()
        calendar.setProductId("-//SunSinD//Sunlith Calendar//EN")
        val byId = records.associateBy { it.draft.id }
        val bySyncId = records.filter { it.syncId != null }.associateBy { it.syncId }
        for (stored in records) {
            val draft = stored.draft
            val event = VEvent()
            val parent = stored.originalId?.let(byId::get) ?: stored.originalSyncId?.let(bySyncId::get)
            require(stored.originalInstanceTime == null || parent != null) { "A recurring exception is missing its source series. Export it from your calendar service." }
            event.setUid(parent?.uid ?: stored.uid)
            event.setSummary(draft.title)
            event.setDescription(draft.description)
            event.setLocation(draft.location)
            event.setDateTimeStamp(Date())
            val startProperty = if (draft.allDay) DateStart(dateOnly(draft.startMillis)) else DateStart(Date(draft.startMillis), true)
            val endProperty = if (draft.allDay) DateEnd(dateOnly(draft.endMillis)) else DateEnd(Date(draft.endMillis), true)
            event.dateStart = startProperty
            if (!draft.rrule.isNullOrBlank() && stored.originalDuration != null) {
                event.setDuration(biweekly.util.Duration.parse(CalendarDates.duration(draft.startMillis, draft.endMillis, draft.allDay)))
            } else event.dateEnd = endProperty
            if (!draft.allDay) {
                val timezone = TimeZone.getTimeZone(ZoneId.of(draft.timeZone))
                val assignment = TimezoneAssignment(timezone, draft.timeZone)
                calendar.timezoneInfo.setTimezone(startProperty, assignment)
                calendar.timezoneInfo.setTimezone(endProperty, assignment)
            }
            draft.rrule?.takeIf(String::isNotBlank)?.let { event.recurrenceRule = recurrence(it) }
            addDateList(event, "EXDATE", stored.exdate)
            addDateList(event, "RDATE", stored.rdate)
            stored.exrule?.let { event.addProperty(RawProperty("EXRULE", it)) }
            stored.originalInstanceTime?.let { original ->
                val recurrenceId = if (stored.originalAllDay) RecurrenceId(dateOnly(original)) else RecurrenceId(Date(original), true)
                event.recurrenceId = recurrenceId
                if (!stored.originalAllDay) calendar.timezoneInfo.setTimezone(recurrenceId, TimezoneAssignment(TimeZone.getTimeZone(draft.timeZone), draft.timeZone))
            }
            if (stored.canceled) event.addProperty(RawProperty("STATUS", "CANCELLED"))
            draft.reminderMinutes.distinct().forEach { minutes ->
                val duration = biweekly.util.Duration.builder().minutes(minutes).prior(true).build()
                event.addAlarm(VAlarm.display(Trigger(duration, Related.START), draft.title.ifBlank { "Event reminder" }))
            }
            calendar.addEvent(event)
        }
        return calendar
    }

    private fun addDateList(event: VEvent, name: String, raw: String?) {
        if (raw.isNullOrBlank()) return
        require('\r' !in raw && '\n' !in raw) { "This calendar contains unsupported multi-line recurrence dates." }
        val separator = raw.indexOf(':')
        if (separator >= 0 && raw.substring(0, separator).startsWith("TZID=")) {
            val prop = RawProperty(name, raw.substring(separator + 1))
            prop.parameters.timezoneId = raw.substring(5, separator)
            event.addProperty(prop)
        } else {
            require(';' !in raw && ':' !in raw) { "This calendar contains unsupported recurrence date formatting." }
            event.addProperty(RawProperty(name, raw))
        }
    }
}
