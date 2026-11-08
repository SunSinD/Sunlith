package io.github.sunsind.sunlith.device

import android.Manifest
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.provider.CalendarContract.Events
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.DeviceCalendar
import io.github.sunsind.sunlith.data.EditBlockReason
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.data.IcsCodec
import io.github.sunsind.sunlith.data.IcsTransfer
import io.github.sunsind.sunlith.data.StoredEvent
import io.github.sunsind.sunlith.device.DeviceLinkStore.hex
import io.github.sunsind.sunlith.system.AppRefresh
import io.github.sunsind.sunlith.system.SettingsStore
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Base64
import java.util.Locale

/**
 * Tool handlers for the direct phone link. Inputs mirror integrations/mcp/src/device-validation.js.
 * Every calendar read or write checks the pairing allowlist and the event's actual provider calendar.
 */
internal class DeviceLinkTools(context: Context, private val repository: CalendarRepository = CalendarRepository(context)) {
    private val app = context.applicationContext

    fun call(tool: String, arguments: Map<String, Any?>, allowed: Set<Long>): Map<String, Any?> = try {
        when (tool) {
            "get_current_time" -> currentTime(Args(arguments, setOf("timeZone")))
            "list_calendars" -> read { listCalendars(Args(arguments, setOf("limit", "pageToken")), allowed) }
            "list_events" -> read { listEvents(Args(arguments, LIST_EVENTS), allowed) }
            "get_event" -> read { getEvent(Args(arguments, setOf("calendarId", "eventId")), allowed) }
            "create_event" -> write { createEvent(Args(arguments, CREATE), allowed) }
            "update_event" -> write { updateEvent(Args(arguments, UPDATE), allowed) }
            "delete_event" -> write { deleteEvent(Args(arguments, setOf("calendarId", "eventId", "etag", "seriesScope", "confirm")), allowed) }
            "import_calendar" -> write { importCalendar(Args(arguments, setOf("calendarId", "ics", "confirm")), allowed) }
            else -> throw DeviceLinkError.badRequest("Unknown tool.")
        }
    } catch (error: DeviceLinkError) {
        throw error
    } catch (_: SecurityException) {
        throw DeviceLinkError.permissionMissing()
    } catch (_: Exception) {
        throw DeviceLinkError.internal()
    }

    private fun <T> read(block: () -> T): T {
        if (!granted(Manifest.permission.READ_CALENDAR)) throw DeviceLinkError.permissionMissing()
        return block()
    }

    private fun <T> write(block: () -> T): T {
        if (!granted(Manifest.permission.READ_CALENDAR) || !granted(Manifest.permission.WRITE_CALENDAR)) throw DeviceLinkError.permissionMissing()
        return block()
    }

    private fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun currentTime(args: Args): Map<String, Any?> {
        val zone = args.zone("timeZone") ?: ZoneId.systemDefault()
        val now = Instant.now()
        val local = now.atZone(zone)
        val offset = local.offset.let { if (it.totalSeconds == 0) "Z" else it.id }
        return linkedMapOf(
            "timeZone" to zone.id,
            "localIso" to local.format(LOCAL_MILLIS) + offset,
            "local" to local.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu 'at' HH:mm:ss zzz", Locale.ENGLISH)),
            "weekday" to local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            "utc" to now.atOffset(ZoneOffset.UTC).format(LOCAL_MILLIS) + "Z",
        )
    }

    private fun listCalendars(args: Args, allowed: Set<Long>): Map<String, Any?> {
        val limit = args.int("limit", 1, 100) ?: 50
        val offset = args.pageToken()
        val calendars = repository.calendars().filter { it.id in allowed }
        val settings = SettingsStore(app)
        val shown = if (settings.hasChosenCalendars) settings.selectedCalendarIds else calendars.filter { it.visible }.mapTo(mutableSetOf()) { it.id }
        val page = calendars.drop(offset).take(limit)
        val default = calendars.firstOrNull { it.writable && it.id in shown } ?: calendars.firstOrNull { it.writable }
        return linkedMapOf(
            "calendars" to page.map {
                linkedMapOf("id" to it.id.toString(), "name" to it.name, "account" to it.account, "writable" to it.writable,
                    "selected" to (it.id in shown), "local" to it.local)
            },
            "nextPageToken" to nextPage(offset, limit, calendars.size),
            "defaultCalendarId" to default?.id?.toString(),
        )
    }

    private fun listEvents(args: Args, allowed: Set<Long>): Map<String, Any?> {
        val calendarId = args.id("calendarId")
        val timeMin = args.instant("timeMin")
        val timeMax = args.instant("timeMax")
        val query = args.string("query", 0, 300)
        val limit = args.int("limit", 1, 100) ?: 50
        val offset = args.pageToken()
        args.list("eventTypes", 1, 1)?.let { if (it.single() != "default") throw DeviceLinkError.badRequest("Only default eventTypes are supported.") }
        val range = timeMax.toEpochMilli() - timeMin.toEpochMilli()
        if (range <= 0 || range > 366L * DAY) throw DeviceLinkError.badRequest("The time range must be positive and no longer than 366 days.")
        allowedCalendar(calendarId, allowed)
        val zone = ZoneId.systemDefault()
        fun startOf(millis: Long, allDay: Boolean) = if (allDay) CalendarDates.allDayDate(millis).atStartOfDay(zone).toInstant().toEpochMilli() else millis
        val matches = repository.events(timeMin.toEpochMilli() - 2 * DAY, timeMax.toEpochMilli() + 2 * DAY, setOf(calendarId))
            .filter { it.calendarId == calendarId }
            .filter { startOf(it.endMillis, it.allDay) > timeMin.toEpochMilli() && startOf(it.startMillis, it.allDay) < timeMax.toEpochMilli() }
            .filter { event -> query == null || listOf(event.title, event.location, event.description).any { it.contains(query, ignoreCase = true) } }
            .sortedWith(compareBy<CalendarEvent>({ startOf(it.startMillis, it.allDay) }, { startOf(it.endMillis, it.allDay) }, { it.title.lowercase(Locale.ROOT) }))
        val page = matches.drop(offset).take(limit)
        val links = repository.seriesLinks(calendarId, page.map { it.id })
        return linkedMapOf(
            "events" to page.map { event ->
                val link = links[event.id]
                val rule = link?.rrule ?: event.rrule.takeIf { link == null }
                linkedMapOf<String, Any?>(
                    "id" to (link?.masterId ?: event.id).toString(),
                    "calendarId" to event.calendarId.toString(),
                    "title" to event.title.take(1000),
                    "location" to event.location.take(1000),
                    "start" to time(event.startMillis, event.allDay, event.timeZone),
                    "end" to time(event.endMillis, event.allDay, event.timeZone),
                ).apply {
                    if (rule.isNullOrBlank()) put("scope", "event")
                    else {
                        put("recurrence", listOf("RRULE:$rule"))
                        put("scope", "series")
                        val original = link?.originalInstanceTime
                        put("occurrenceStart", if (link != null && original != null) time(original, link.originalAllDay, event.timeZone)
                            else time(event.startMillis, event.allDay, event.timeZone))
                    }
                }
            },
            "nextPageToken" to nextPage(offset, limit, matches.size),
            "timeZone" to zone.id,
        )
    }

    private fun getEvent(args: Args, allowed: Set<Long>): Map<String, Any?> {
        val calendarId = args.id("calendarId")
        val eventId = args.id("eventId")
        allowedCalendar(calendarId, allowed)
        val master = repository.seriesLinks(calendarId, listOf(eventId))[eventId]?.masterId ?: throw DeviceLinkError.notFound()
        return snapshot(master, calendarId).json()
    }

    private fun createEvent(args: Args, allowed: Set<Long>): Map<String, Any?> {
        val calendarId = args.id("calendarId")
        val operationId = args.required("operationId", 8, 128)
        if (!OPERATION_ID.matches(operationId)) throw DeviceLinkError.badRequest("operationId may use only letters, digits, dot, underscore and dash.")
        val title = args.title() ?: throw DeviceLinkError.badRequest("title is required.")
        val description = args.string("description", 0, 5000).orEmpty()
        val location = args.string("location", 0, 1000).orEmpty()
        val start = args.eventTime("start") ?: throw DeviceLinkError.badRequest("start is required.")
        val end = args.eventTime("end") ?: throw DeviceLinkError.badRequest("end is required.")
        val rule = args.recurrence()
        val reminders = args.reminders() ?: emptyList()
        val calendar = allowedCalendar(calendarId, allowed)
        if (!calendar.writable) throw DeviceLinkError.calendarNotAllowed("This calendar is read-only on the phone.")
        val timing = timing(start, end, rule != null)
        val draft = EventDraft(calendarId = calendarId, title = title, description = description, location = location,
            startMillis = timing.start, endMillis = timing.end, allDay = timing.allDay, timeZone = timing.zone, rrule = rule,
            reminderMinutes = reminders.distinct().sorted())
        validate(draft)
        checkReminders(calendarId, draft.reminderMinutes)
        val key = digest("$calendarId\n$operationId").take(32)
        val payload = DeviceLinkStore.sha256(DeviceLinkJson.write(linkedMapOf(
            "calendarId" to calendarId, "title" to title, "description" to description, "location" to location,
            "allDay" to timing.allDay, "start" to timing.start, "end" to timing.end,
            "zone" to if (timing.allDay) "" else timing.zone, "recurrence" to rule.orEmpty(),
            "reminders" to draft.reminderMinutes,
        ))).hex().take(32)
        val prefix = "sunlith-op-$key-"
        val uid = "$prefix$payload@sunlith"
        val (id, reused) = synchronized(WRITES) {
            val matches = repository.eventsWithUidPrefix(calendarId, prefix)
            val live = matches.firstOrNull { !it.deleted }
            val journal = DeviceLinkStore.operation(app, key)
            when {
                live != null && live.uid == uid -> live.id to true
                live != null -> throw DeviceLinkError.operationConflict(CONFLICT_DETAILS)
                matches.isNotEmpty() -> throw DeviceLinkError.operationConflict(CONFLICT_DELETED)
                journal != null && journal.payloadHash != payload -> throw DeviceLinkError.operationConflict(CONFLICT_DETAILS)
                journal != null -> {
                    val stored = repository.storedEvent(journal.eventId)
                    if (stored == null || stored.draft.calendarId != calendarId) throw DeviceLinkError.operationConflict(CONFLICT_DELETED)
                    journal.eventId to true
                }
                else -> {
                    val created = try { repository.saveImported(draft, uid) } catch (_: IllegalArgumentException) { throw rejected() }
                    runCatching { DeviceLinkStore.recordOperation(app, OperationRecord(key, payload, created)) }
                    remember(DeviceLinkChange(DeviceLinkChangeAction.CREATE, System.currentTimeMillis(), calendarId, created, title))
                    created to false
                }
            }
        }
        if (!reused) AppRefresh.refresh(app)
        return snapshot(id, calendarId).json() + ("reused" to reused)
    }

    private fun updateEvent(args: Args, allowed: Set<Long>): Map<String, Any?> {
        val calendarId = args.id("calendarId")
        val eventId = args.id("eventId")
        val etag = args.etag()
        val series = args.seriesScope()
        val title = args.title()
        val description = args.string("description", 0, 5000)
        val location = args.string("location", 0, 1000)
        val start = args.eventTime("start")
        val end = args.eventTime("end")
        if ((start == null) != (end == null)) throw DeviceLinkError.badRequest("Provide both start and end when changing event time.")
        val recurrenceGiven = args.has("recurrence")
        val rule = args.recurrence()
        val reminders = args.reminders()
        if (title == null && description == null && location == null && start == null && !recurrenceGiven && reminders == null) {
            throw DeviceLinkError.badRequest("Provide at least one field to update.")
        }
        allowedCalendar(calendarId, allowed)
        synchronized(WRITES) {
            val current = snapshot(eventId, calendarId)
            current.checkMutable(etag, series)
            val stored = current.stored.draft
            var draft = stored.copy(
                title = title ?: stored.title,
                description = description ?: stored.description,
                location = location ?: stored.location,
                rrule = if (recurrenceGiven) rule else stored.rrule?.takeIf(String::isNotBlank),
                reminderMinutes = reminders?.distinct()?.sorted() ?: stored.reminderMinutes,
            )
            if (start != null && end != null) {
                val timing = timing(start, end, draft.rrule != null)
                draft = draft.copy(startMillis = timing.start, endMillis = timing.end, allDay = timing.allDay, timeZone = timing.zone)
            }
            validate(draft)
            if (reminders != null) checkReminders(calendarId, draft.reminderMinutes)
            try {
                repository.updateIfUnchanged(draft, current.revision)
            } catch (_: OperationApplicationException) {
                throw DeviceLinkError.revisionMismatch()
            } catch (_: IllegalArgumentException) {
                throw rejected()
            } catch (_: IllegalStateException) {
                if (repository.storedEvent(eventId) == null) throw DeviceLinkError.notFound()
                throw DeviceLinkError.internal()
            }
            val changed = changedFields(stored, draft)
            if (changed.isNotEmpty()) {
                remember(DeviceLinkChange(DeviceLinkChangeAction.UPDATE, System.currentTimeMillis(), calendarId, eventId, draft.title, changed))
            }
        }
        AppRefresh.refresh(app)
        return snapshot(eventId, calendarId).json()
    }

    private fun deleteEvent(args: Args, allowed: Set<Long>): Map<String, Any?> {
        val calendarId = args.id("calendarId")
        val eventId = args.id("eventId")
        val etag = args.etag()
        val series = args.seriesScope()
        args.confirm()
        allowedCalendar(calendarId, allowed)
        synchronized(WRITES) {
            val current = snapshot(eventId, calendarId)
            current.checkMutable(etag, series)
            try {
                repository.deleteIfUnchanged(eventId, current.revision)
            } catch (_: OperationApplicationException) {
                throw DeviceLinkError.revisionMismatch()
            } catch (_: IllegalArgumentException) {
                throw rejected()
            }
            remember(DeviceLinkChange(DeviceLinkChangeAction.DELETE, System.currentTimeMillis(), calendarId, eventId, current.stored.draft.title))
        }
        AppRefresh.refresh(app)
        return linkedMapOf("deleted" to true, "eventId" to eventId.toString())
    }

    private fun importCalendar(args: Args, allowed: Set<Long>): Map<String, Any?> {
        val calendarId = args.id("calendarId")
        val ics = args.required("ics", 1, MAX_ICS)
        args.confirm()
        val bytes = ics.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_ICS) throw DeviceLinkError.tooLarge("The calendar file is larger than 256 KiB.")
        val calendar = allowedCalendar(calendarId, allowed)
        if (!calendar.writable) throw DeviceLinkError.calendarNotAllowed("This calendar is read-only on the phone.")
        val report = synchronized(WRITES) {
            val result = try {
                IcsTransfer(app, repository).import(ByteArrayInputStream(bytes), calendarId)
            } catch (_: IllegalArgumentException) {
                throw DeviceLinkError.badRequest("The calendar file could not be read. Use a complete .ics file with at most 2000 events.")
            } finally {
                AppRefresh.refresh(app)
            }
            if (result.imported > 0) {
                remember(DeviceLinkChange(DeviceLinkChangeAction.IMPORT, System.currentTimeMillis(), calendarId,
                    imported = result.imported, duplicates = result.duplicates, skipped = result.skipped))
            }
            result
        }
        return linkedMapOf("imported" to report.imported, "duplicates" to report.duplicates, "skipped" to report.skipped,
            "messages" to report.messages.map { it.take(500) }, "atomic" to false)
    }

    private inner class Snapshot(val stored: StoredEvent, val revision: Map<String, String?>, val block: EditBlockReason?) {
        val etag: String = run {
            val text = buildString {
                revision.toSortedMap().forEach { (key, value) -> append(key).append('=').append(value ?: "\u0001").append('\u0000') }
                append(stored.draft.reminderMinutes.sorted().joinToString(",")).append('\u0000').append(block?.name.orEmpty())
            }
            "r1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(DeviceLinkStore.sha256(text.toByteArray(Charsets.UTF_8)))
        }

        fun checkMutable(expectedEtag: String, series: Boolean) {
            when (block) {
                null -> Unit
                EditBlockReason.MISSING -> throw DeviceLinkError.notFound()
                EditBlockReason.READ_ONLY -> throw DeviceLinkError.calendarNotAllowed("This calendar is read-only on the phone.")
                EditBlockReason.GUESTS -> throw DeviceLinkError.blocked("Events with guests must be changed in the calendar app so invitations stay correct.", "guests")
                EditBlockReason.RECURRENCE_EXCEPTION -> throw DeviceLinkError.blocked("This repeating series has changed or separate occurrences. Change it in the calendar app.", "recurrence_exception")
                EditBlockReason.INVALID_PROVIDER_DATA -> throw DeviceLinkError.blocked("This event has calendar data Sunlith cannot safely change.", "invalid_provider_data")
                EditBlockReason.SCHOOL_MANAGED -> throw DeviceLinkError.blocked("This event is updated by the school portal. Disconnect the school schedule before editing it.", "school_managed")
            }
            if (expectedEtag != etag) throw DeviceLinkError.revisionMismatch()
            if (!stored.draft.rrule.isNullOrBlank() && !series) {
                throw DeviceLinkError(400, "bad_request", "This is a repeating event. Confirm the whole series with the user, then send seriesScope:\"series\".", "series_scope_required")
            }
        }

        fun json(): Map<String, Any?> {
            val draft = stored.draft
            val rule = draft.rrule?.takeIf(String::isNotBlank)
            return linkedMapOf(
                "id" to draft.id.toString(),
                "calendarId" to draft.calendarId.toString(),
                "etag" to etag,
                "title" to draft.title,
                "description" to draft.description,
                "location" to draft.location,
                "start" to time(draft.startMillis, draft.allDay, draft.timeZone),
                "end" to time(draft.endMillis, draft.allDay, draft.timeZone),
                "recurrence" to listOfNotNull(rule?.let { "RRULE:$it" }),
                "reminderMinutes" to draft.reminderMinutes.distinct().sorted(),
                "scope" to if (rule == null) "event" else "series",
                "editBlockReason" to block?.name?.lowercase(Locale.ROOT),
            )
        }
    }

    private fun snapshot(id: Long, calendarId: Long): Snapshot {
        repeat(3) {
            val before = repository.revision(id)?.takeIf { it[Events.CALENDAR_ID] == calendarId.toString() }
                ?: throw DeviceLinkError.notFound()
            val stored = repository.storedEvent(id)?.takeIf { it.draft.calendarId == calendarId } ?: throw DeviceLinkError.notFound()
            val block = repository.editBlockReason(id)
            val after = repository.revision(id) ?: throw DeviceLinkError.notFound()
            if (before == after) return Snapshot(stored, after, block)
        }
        throw DeviceLinkError.revisionMismatch()
    }

    /** Called after the provider write succeeded, inside WRITES so the list keeps provider order. A history failure never fails the request. */
    private fun remember(change: DeviceLinkChange) {
        try { DeviceLinkHistory.record(app, change) } catch (_: Exception) { }
    }

    private fun changedFields(before: EventDraft, after: EventDraft): List<DeviceLinkChangeField> = buildList {
        if (before.title.trim() != after.title.trim()) add(DeviceLinkChangeField.TITLE)
        if (before.description != after.description) add(DeviceLinkChangeField.DESCRIPTION)
        if (before.location != after.location) add(DeviceLinkChangeField.LOCATION)
        if (!CalendarDates.timingUnchanged(before, after)) add(DeviceLinkChangeField.TIME)
        if (before.rrule?.takeIf(String::isNotBlank) != after.rrule?.takeIf(String::isNotBlank)) add(DeviceLinkChangeField.RECURRENCE)
        if (before.reminderMinutes.distinct().sorted() != after.reminderMinutes.distinct().sorted()) add(DeviceLinkChangeField.REMINDERS)
    }

    private fun allowedCalendar(id: Long, allowed: Set<Long>): DeviceCalendar {
        if (id !in allowed) throw DeviceLinkError.calendarNotAllowed()
        return repository.calendars().firstOrNull { it.id == id } ?: throw DeviceLinkError.notFound("This calendar is no longer available on the phone.")
    }

    private fun checkReminders(calendarId: Long, minutes: List<Int>) {
        if (minutes.isEmpty()) return
        val policy = repository.reminderPolicy(calendarId)
        if (!policy.alertsAllowed) throw DeviceLinkError.badRequest("This calendar does not support on-device reminders.")
        if (minutes.size > policy.maximumCount) throw DeviceLinkError.badRequest("This calendar allows at most ${policy.maximumCount} reminders per event.")
        if (minutes.any { it > policy.maximumMinutes }) throw DeviceLinkError.badRequest("This calendar supports reminders up to ${policy.maximumMinutes} minutes before an event.")
    }

    private fun validate(draft: EventDraft) {
        try { CalendarDates.validate(draft) } catch (_: Exception) { throw rejected() }
    }

    private fun rejected() = DeviceLinkError.badRequest("The phone calendar rejected these event details. Check times, recurrence and reminder limits.")

    private data class Timing(val start: Long, val end: Long, val allDay: Boolean, val zone: String)

    private fun timing(start: EventTime, end: EventTime, recurring: Boolean): Timing = when {
        start is EventTime.AllDay && end is EventTime.AllDay -> {
            if (!end.date.isAfter(start.date)) throw DeviceLinkError.badRequest("End must be after start; all-day end dates are exclusive.")
            Timing(CalendarDates.allDayMillis(start.date), CalendarDates.allDayMillis(end.date), true, "UTC")
        }
        start is EventTime.Timed && end is EventTime.Timed -> {
            if (!end.instant.isAfter(start.instant)) throw DeviceLinkError.badRequest("End must be after start.")
            if (recurring && start.zone != end.zone) throw DeviceLinkError.badRequest("Recurring start and end must use the same IANA timezone.")
            Timing(start.instant.toEpochMilli(), end.instant.toEpochMilli(), false, start.zone.id)
        }
        else -> throw DeviceLinkError.badRequest("Start and end must both be all-day dates or timed values.")
    }

    private fun time(millis: Long, allDay: Boolean, timeZone: String): Map<String, String> {
        if (allDay) return mapOf("date" to CalendarDates.allDayDate(millis).toString())
        val zone = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneOffset.UTC)
        return linkedMapOf("dateTime" to Instant.ofEpochMilli(millis).atZone(zone).format(DATE_TIME), "timeZone" to zone.id)
    }

    private fun nextPage(offset: Int, limit: Int, total: Int): String? = (offset.toLong() + limit).takeIf { it < total }?.toString()

    private fun digest(text: String) = DeviceLinkStore.sha256(text.toByteArray(Charsets.UTF_8)).hex()

    companion object {
        private val WRITES = Any()
        private const val DAY = 24L * 60 * 60 * 1000
        private const val MAX_ICS = 256 * 1024
        private val OPERATION_ID = Regex("[A-Za-z0-9._-]{8,128}")
        private val DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        private val LOCAL_MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)
        private val LIST_EVENTS = setOf("calendarId", "timeMin", "timeMax", "query", "pageToken", "limit", "eventTypes")
        private val CREATE = setOf("calendarId", "operationId", "title", "description", "location", "start", "end", "recurrence", "reminderMinutes")
        private val UPDATE = setOf("calendarId", "eventId", "etag", "seriesScope", "title", "description", "location", "start", "end", "recurrence", "reminderMinutes")
        private const val CONFLICT_DETAILS = "This operationId was already used with different event details. Read the event before editing it."
        private const val CONFLICT_DELETED = "This operationId belongs to an event that was deleted. Use a new operationId only if the user wants to create it again."
    }
}

internal sealed interface EventTime {
    data class Timed(val instant: Instant, val zone: ZoneId) : EventTime
    data class AllDay(val date: LocalDate) : EventTime
}

/** Strict argument reader: unknown keys, nulls and wrong JSON types are rejected with field-level messages that never echo values. */
internal class Args(private val values: Map<String, Any?>, allowedKeys: Set<String>) {
    init {
        if (!allowedKeys.containsAll(values.keys)) throw DeviceLinkError.badRequest("Unknown field: ${values.keys.first { it !in allowedKeys }.take(40).filter { it.isLetterOrDigit() }}.")
        values.forEach { (key, value) -> if (value == null) throw invalid(key) }
    }

    fun has(key: String) = values.containsKey(key)

    fun string(key: String, min: Int, max: Int): String? {
        val value = values[key] ?: return null
        if (value !is String || value.length < min || value.length > max) throw invalid(key)
        return value
    }

    fun int(key: String, min: Int, max: Int): Int? {
        val value = values[key] ?: return null
        val number = when (value) { is Long -> value; is Int -> value.toLong(); else -> throw invalid(key) }
        if (number < min || number > max) throw invalid(key)
        return number.toInt()
    }

    fun list(key: String, min: Int, max: Int): List<Any?>? {
        val value = values[key] ?: return null
        if (value !is List<*> || value.size < min || value.size > max) throw invalid(key)
        return value
    }

    fun required(key: String, min: Int, max: Int): String = string(key, min, max) ?: throw DeviceLinkError.badRequest("$key is required.")

    fun id(key: String): Long {
        val value = required(key, 1, 19)
        if (!ID.matches(value)) throw DeviceLinkError.badRequest("$key must be the exact positive decimal ID returned by Sunlith.")
        return value.toLongOrNull() ?: throw invalid(key)
    }

    fun pageToken(): Int {
        val value = string("pageToken", 1, 16) ?: return 0
        if (!PAGE.matches(value)) throw invalid("pageToken")
        val number = value.toLong()
        if (number > MAX_SAFE_INTEGER) throw invalid("pageToken")
        return number.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun instant(key: String): Instant = offsetDateTime(key, required(key, 1, 40)).toInstant()

    fun zone(key: String): ZoneId? = string(key, 1, 100)?.let { zoneId(key, it) }

    fun title(): String? {
        val value = values["title"] ?: return null
        if (value !is String) throw invalid("title")
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 200) throw invalid("title")
        return trimmed
    }

    fun etag(): String {
        val value = required("etag", 1, 512)
        if ('\r' in value || '\n' in value) throw invalid("etag")
        return value
    }

    fun seriesScope(): Boolean {
        val value = string("seriesScope", 1, 16) ?: return false
        if (value != "series") throw invalid("seriesScope")
        return true
    }

    fun confirm() {
        if (values["confirm"] != true) throw DeviceLinkError.badRequest("confirm:true is required after the user asks for this change.")
    }

    fun recurrence(): String? {
        val list = list("recurrence", 0, 1) ?: return null
        val rule = list.firstOrNull() ?: return null
        if (rule !is String || rule.length > 500 || !RRULE.matches(rule)) {
            throw DeviceLinkError.badRequest("Only one RRULE with DAILY, WEEKLY, MONTHLY, or YEARLY frequency is supported.")
        }
        val value = rule.removePrefix("RRULE:")
        try { IcsCodec.recurrence(value) } catch (_: Exception) { throw DeviceLinkError.badRequest("Invalid recurrence rule.") }
        return value
    }

    fun reminders(): List<Int>? = list("reminderMinutes", 0, 5)?.map { value ->
        val number = when (value) { is Long -> value; is Int -> value.toLong(); else -> throw invalid("reminderMinutes") }
        if (number !in 0..40_320) throw invalid("reminderMinutes")
        number.toInt()
    }

    fun eventTime(key: String): EventTime? {
        val value = values[key] ?: return null
        if (value !is Map<*, *>) throw invalid(key)
        return when (value.keys) {
            setOf("date") -> {
                val text = value["date"] as? String ?: throw invalid(key)
                if (!DATE.matches(text)) throw DeviceLinkError.badRequest("Use a real date in YYYY-MM-DD format for $key.")
                EventTime.AllDay(try { LocalDate.parse(text) } catch (_: Exception) { throw DeviceLinkError.badRequest("Use a real date in YYYY-MM-DD format for $key.") })
            }
            setOf("dateTime", "timeZone") -> {
                val text = value["dateTime"] as? String ?: throw invalid(key)
                val zoneText = value["timeZone"] as? String ?: throw invalid(key)
                if (text.length > 40 || zoneText.isEmpty() || zoneText.length > 100) throw invalid(key)
                val dateTime = offsetDateTime(key, text)
                val zone = zoneId(key, zoneText)
                if (zone.rules.getOffset(dateTime.toInstant()) != dateTime.offset) {
                    throw DeviceLinkError.badRequest("The UTC offset in $key must be valid for this IANA timezone at this local time. Check DST gaps and offsets.")
                }
                EventTime.Timed(dateTime.toInstant(), zone)
            }
            else -> throw DeviceLinkError.badRequest("$key must be {date} or {dateTime,timeZone}.")
        }
    }

    private fun offsetDateTime(key: String, value: String): OffsetDateTime {
        if (!DATE_TIME.matches(value)) throw DeviceLinkError.badRequest("Use RFC3339 with seconds and a timezone offset for $key.")
        return try { OffsetDateTime.parse(value) } catch (_: Exception) { throw DeviceLinkError.badRequest("Use a valid date and time for $key.") }
    }

    private fun zoneId(key: String, value: String): ZoneId {
        val canonical = ZONES[value.lowercase(Locale.ROOT)] ?: throw DeviceLinkError.badRequest("Use an IANA timezone, such as America/Toronto, for $key.")
        return ZoneId.of(canonical)
    }

    private fun invalid(key: String) = DeviceLinkError.badRequest("Invalid $key.")

    private companion object {
        val ID = Regex("[1-9][0-9]{0,18}")
        val PAGE = Regex("0|[1-9][0-9]*")
        val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        val DATE_TIME = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})")
        val RRULE = Regex("RRULE:FREQ=(?:DAILY|WEEKLY|MONTHLY|YEARLY)(?:;[A-Z]+=[A-Z0-9,+\\-]+)*")
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        val ZONES: Map<String, String> by lazy { ZoneId.getAvailableZoneIds().associateBy { it.lowercase(Locale.ROOT) } }
    }
}
