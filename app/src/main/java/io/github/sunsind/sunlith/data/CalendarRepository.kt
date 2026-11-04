package io.github.sunsind.sunlith.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import io.github.sunsind.sunlith.R
import java.util.TimeZone
import java.util.UUID

class CalendarRepository(context: Context) {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    fun calendars(): List<DeviceCalendar> {
        checkRead()
        return resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR,
                Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE, Calendars.ACCOUNT_TYPE),
            null, null, "${Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DeviceCalendar(
                    cursor.long(Calendars._ID), cursor.string(Calendars.CALENDAR_DISPLAY_NAME).ifBlank { "Calendar" },
                    cursor.string(Calendars.ACCOUNT_NAME), cursor.int(Calendars.CALENDAR_COLOR),
                    cursor.int(Calendars.CALENDAR_ACCESS_LEVEL) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    cursor.int(Calendars.VISIBLE) != 0,
                    cursor.string(Calendars.ACCOUNT_TYPE) == CalendarContract.ACCOUNT_TYPE_LOCAL,
                ))
            }
        } ?: emptyList()
    }

    fun events(startMillis: Long, endMillis: Long, calendarIds: Set<Long>): List<CalendarEvent> {
        checkRead()
        require(endMillis > startMillis) { "Choose a valid date range." }
        require(endMillis - startMillis <= 366L * 24 * 60 * 60 * 1000 * 3) { "Load at most three years at a time." }
        if (calendarIds.isEmpty()) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startMillis)
            ContentUris.appendId(it, endMillis)
        }.build()
        val ids = calendarIds.toList()
        return resolver.query(uri,
            arrayOf(Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.DESCRIPTION,
                Instances.EVENT_LOCATION, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
                Instances.EVENT_TIMEZONE, Instances.RRULE, Instances.DISPLAY_COLOR, Instances.CALENDAR_DISPLAY_NAME),
            "${Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }}) AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ?)",
            (ids.map(Long::toString) + Events.STATUS_CANCELED.toString()).toTypedArray(),
            "${Instances.BEGIN} ASC, ${Instances.END} ASC, ${Instances.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(CalendarEvent(
                    cursor.long(Instances.EVENT_ID), cursor.long(Instances.CALENDAR_ID),
                    cursor.string(Instances.TITLE), cursor.string(Instances.DESCRIPTION),
                    cursor.string(Instances.EVENT_LOCATION), cursor.long(Instances.BEGIN), cursor.long(Instances.END),
                    cursor.int(Instances.ALL_DAY) != 0, cursor.string(Instances.EVENT_TIMEZONE).ifBlank { TimeZone.getDefault().id },
                    cursor.optional(Instances.RRULE), cursor.int(Instances.DISPLAY_COLOR), cursor.string(Instances.CALENDAR_DISPLAY_NAME),
                ))
            }
        } ?: emptyList()
    }

    fun getEvent(id: Long): EventDraft? {
        checkRead()
        return readStored(id)?.draft
    }

    fun editBlockReason(id: Long): EditBlockReason? {
        checkRead()
        val stored = readStored(id) ?: return EditBlockReason.MISSING
        if (calendars().none { it.id == stored.draft.calendarId && it.writable }) return EditBlockReason.READ_ONLY
        if (stored.invalidReason != null) return EditBlockReason.INVALID_PROVIDER_DATA
        if (hasAttendees(id)) return EditBlockReason.GUESTS
        if (stored.originalId != null || stored.originalSyncId != null || stored.originalInstanceTime != null || !stored.exdate.isNullOrBlank() ||
            !stored.rdate.isNullOrBlank() || !stored.exrule.isNullOrBlank() || hasExceptions(id)) return EditBlockReason.RECURRENCE_EXCEPTION
        return null
    }

    fun saveEvent(draft: EventDraft): Long = save(draft, null)

    internal fun saveImported(draft: EventDraft, uid: String): Long = save(draft, uid)

    private fun save(draft: EventDraft, importedUid: String?): Long {
        checkWrite()
        CalendarDates.validate(draft)
        writableCalendar(draft.calendarId)
        val original = draft.id?.let { readStored(it) ?: error("This event no longer exists.") }
        if (original != null) {
            require(original.invalidReason == null) { original.invalidReason.orEmpty() }
            writableCalendar(original.draft.calendarId)
            require(original.originalId == null && original.originalSyncId == null && original.originalInstanceTime == null) {
                "Editing a single occurrence is not supported yet. Open the series in your existing calendar app."
            }
            require(original.draft.calendarId == draft.calendarId) { "Moving an existing event between calendars is not supported yet." }
            require(!hasAttendees(draft.id!!)) {
                "Edit meetings with guests in your existing calendar app so you can review invitation updates."
            }
            require(original.exdate.isNullOrBlank() && original.rdate.isNullOrBlank() && original.exrule.isNullOrBlank() && !hasExceptions(draft.id!!)) {
                "This series has exceptions. Edit it in your existing calendar app to preserve them."
            }
        }
        val minutes = draft.reminderMinutes.distinct().sorted()
        val remindersChanged = original == null || original.draft.reminderMinutes.distinct().sorted() != minutes
        val preservedReminders = if (remindersChanged) draft.id?.let(::preservedReminderCount) ?: 0 else 0
        val policy = if (remindersChanged) reminderPolicy(draft.calendarId) else null
        if (remindersChanged) {
            val limits = requireNotNull(policy)
            require(minutes.size + preservedReminders <= limits.maximumCount) { "This calendar allows at most ${limits.maximumCount} reminders per event." }
            require(minutes.isEmpty() || limits.alertsAllowed) { "This calendar does not support on-device reminders." }
            require(minutes.all { it <= limits.maximumMinutes }) { "This calendar supports reminders up to ${limits.maximumMinutes} minutes before an event." }
        }
        val rule = draft.rrule?.takeIf(String::isNotBlank)
        val timingUnchanged = original != null && CalendarDates.timingUnchanged(original.draft, draft)
        val recurrenceTypeUnchanged = (original?.draft?.rrule.isNullOrBlank() == rule.isNullOrBlank())
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, draft.calendarId)
            put(Events.TITLE, draft.title.trim())
            put(Events.DESCRIPTION, draft.description)
            put(Events.EVENT_LOCATION, draft.location)
            if (remindersChanged) put(Events.HAS_ALARM, if (minutes.isNotEmpty() || preservedReminders > 0) 1 else 0)
            if (!timingUnchanged || !recurrenceTypeUnchanged) {
                put(Events.DTSTART, draft.startMillis)
                put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else draft.timeZone)
                if (rule == null) {
                    put(Events.DTEND, draft.endMillis)
                    putNull(Events.DURATION)
                } else {
                    putNull(Events.DTEND)
                    put(Events.DURATION, if (timingUnchanged) original?.originalDuration
                        ?: CalendarDates.duration(draft.startMillis, draft.endMillis, draft.allDay)
                    else CalendarDates.duration(draft.startMillis, draft.endMillis, draft.allDay))
                }
            }
            if (rule == null) putNull(Events.RRULE) else put(Events.RRULE, rule)
            if (original == null) {
                put(Events.STATUS, Events.STATUS_CONFIRMED)
                put(Events.UID_2445, importedUid?.takeIf(String::isNotBlank) ?: "${UUID.randomUUID()}@sunlith")
            }
        }
        val operations = arrayListOf<ContentProviderOperation>()
        if (draft.id == null) {
            operations += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values).build()
        } else {
            operations += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Events.CONTENT_URI, draft.id))
                .withValues(values).withExpectedCount(1).build()
            if (remindersChanged) operations += ContentProviderOperation.newDelete(Reminders.CONTENT_URI)
                    .withSelection("${Reminders.EVENT_ID}=? AND ${Reminders.MINUTES}>=0 AND (${Reminders.METHOD}=? OR ${Reminders.METHOD}=?)",
                        arrayOf(draft.id.toString(), Reminders.METHOD_ALERT.toString(), Reminders.METHOD_DEFAULT.toString())).build()
        }
        if (remindersChanged) minutes.forEach { minute ->
            val builder = ContentProviderOperation.newInsert(Reminders.CONTENT_URI)
                .withValue(Reminders.MINUTES, minute).withValue(Reminders.METHOD, policy?.writeMethod ?: Reminders.METHOD_ALERT)
            if (draft.id == null) builder.withValueBackReference(Reminders.EVENT_ID, 0)
            else builder.withValue(Reminders.EVENT_ID, draft.id)
            operations += builder.build()
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        return draft.id ?: results.first().uri?.let(ContentUris::parseId) ?: error("The calendar did not save the event.")
    }

    fun deleteEvent(id: Long) {
        checkWrite()
        val stored = readStored(id) ?: return
        writableCalendar(stored.draft.calendarId)
        require(stored.originalId == null && stored.originalSyncId == null && stored.originalInstanceTime == null) {
            "Delete this occurrence in your existing calendar app to preserve the repeating series."
        }
        require(!hasAttendees(id)) { "Delete meetings with guests in your existing calendar app so you can review cancellation messages." }
        require(stored.exdate.isNullOrBlank() && stored.rdate.isNullOrBlank() && stored.exrule.isNullOrBlank() && !hasExceptions(id)) {
            "This series has exceptions. Delete it in your existing calendar app to preserve them."
        }
        val count = resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
        check(count == 1) { "The calendar could not delete this event." }
    }

    fun createLocalCalendar(): Long {
        checkWrite()
        calendars().firstOrNull { it.local && it.account == LOCAL_ACCOUNT && it.writable }?.let { return it.id }
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "sunlith-local")
            put(Calendars.CALENDAR_DISPLAY_NAME, context.getString(R.string.local_calendar_name))
            put(Calendars.CALENDAR_COLOR, 0xFF737373.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
            put(Calendars.MAX_REMINDERS, 5)
        }
        return resolver.insert(uri, values)?.let(ContentUris::parseId) ?: error("Could not create an offline calendar.")
    }

    internal fun writableCalendar(id: Long): DeviceCalendar {
        val calendar = calendars().firstOrNull { it.id == id } ?: error("This calendar is no longer available.")
        require(calendar.writable) { "This calendar is read-only." }
        return calendar
    }

    internal fun storedEvents(calendarIds: Set<Long>): List<StoredEvent> {
        checkRead()
        if (calendarIds.isEmpty()) return emptyList()
        val records = resolver.query(Events.CONTENT_URI, STORED_PROJECTION,
            "${Events.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }}) AND ${Events.DELETED}=0",
            calendarIds.map(Long::toString).toTypedArray(), "${Events.DTSTART} ASC")?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    require(size < 50_000) { "This calendar is too large for this operation. Export it from your calendar service." }
                    add(readStored(cursor, false))
                }
            }
        } ?: emptyList()
        val reminderMap = mutableMapOf<Long, MutableList<Int>>()
        records.mapNotNull { it.draft.id }.chunked(300).forEach { ids ->
            resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
                "${Reminders.EVENT_ID} IN (${ids.joinToString(",") { "?" }})",
                ids.map(Long::toString).toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val method = cursor.int(Reminders.METHOD)
                    val minutes = cursor.int(Reminders.MINUTES)
                    if ((method == Reminders.METHOD_ALERT || method == Reminders.METHOD_DEFAULT) && minutes >= 0) {
                        reminderMap.getOrPut(cursor.long(Reminders.EVENT_ID)) { mutableListOf() }.add(minutes)
                    }
                }
            }
        }
        return records.map { it.copy(draft = it.draft.copy(reminderMinutes = reminderMap[it.draft.id].orEmpty())) }
    }

    private fun readStored(id: Long): StoredEvent? = resolver.query(
        ContentUris.withAppendedId(Events.CONTENT_URI, id), STORED_PROJECTION, "${Events.DELETED}=0", null, null,
    )?.use { if (it.moveToFirst()) readStored(it) else null }

    private fun readStored(cursor: Cursor, includeReminders: Boolean = true): StoredEvent {
        val id = cursor.long(Events._ID)
        val start = cursor.long(Events.DTSTART)
        val allDay = cursor.int(Events.ALL_DAY) != 0
        val timezone = if (allDay) "UTC" else cursor.string(Events.EVENT_TIMEZONE).ifBlank { TimeZone.getDefault().id }
        val duration = cursor.optional(Events.DURATION)
        var invalidReason: String? = null
        val durationEnd = duration?.let {
            try { CalendarDates.providerDurationEndMillis(start, it) }
            catch (_: Exception) {
                invalidReason = "Event $id has an invalid calendar duration. Open it in your existing calendar app."
                null
            }
        }
        val end = cursor.optionalLong(Events.DTEND) ?: durationEnd ?: (start + if (allDay) 86_400_000 else 0)
        return StoredEvent(
            EventDraft(id, cursor.long(Events.CALENDAR_ID), cursor.string(Events.TITLE), cursor.string(Events.DESCRIPTION),
                cursor.string(Events.EVENT_LOCATION), start, end, allDay,
                timezone,
                cursor.optional(Events.RRULE), if (includeReminders) reminders(id) else emptyList()),
            cursor.optional(Events.UID_2445) ?: "event-$id@sunlith.local",
            cursor.optional(Events.EXDATE), cursor.optional(Events.RDATE), cursor.optional(Events.EXRULE),
            cursor.optionalLong(Events.ORIGINAL_ID), cursor.optionalLong(Events.ORIGINAL_INSTANCE_TIME),
            cursor.int(Events.ORIGINAL_ALL_DAY) != 0, cursor.int(Events.STATUS) == Events.STATUS_CANCELED,
            cursor.optional(Events._SYNC_ID), cursor.optional(Events.ORIGINAL_SYNC_ID),
            duration, invalidReason,
        )
    }

    private fun reminders(eventId: Long): List<Int> = resolver.query(Reminders.CONTENT_URI,
        arrayOf(Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null,
    )?.use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val method = cursor.int(Reminders.METHOD)
            if (method == Reminders.METHOD_ALERT || method == Reminders.METHOD_DEFAULT) {
                cursor.int(Reminders.MINUTES).takeIf { it >= 0 }?.let(::add)
            }
        }
    } } ?: emptyList()

    internal fun reminderPolicy(calendarId: Long): ReminderPolicy = resolver.query(
        ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), arrayOf(Calendars.MAX_REMINDERS, Calendars.ALLOWED_REMINDERS, Calendars.ACCOUNT_TYPE),
        null, null, null,
    )?.use {
        if (!it.moveToFirst()) return@use ReminderPolicy(0, false, 0)
        val max = it.int(Calendars.MAX_REMINDERS).let { count -> if (count < 0) 20 else count }
        val allowed = it.string(Calendars.ALLOWED_REMINDERS).split(',').map(String::trim)
        val google = it.string(Calendars.ACCOUNT_TYPE) == "com.google"
        ReminderPolicy(max, allowed.isEmpty() || allowed == listOf("") || Reminders.METHOD_ALERT.toString() in allowed || Reminders.METHOD_DEFAULT.toString() in allowed,
            if (google) 40_320 else 525_600,
            if (Reminders.METHOD_ALERT.toString() in allowed || allowed == listOf("")) Reminders.METHOD_ALERT else Reminders.METHOD_DEFAULT)
    } ?: ReminderPolicy(0, false, 0)

    private fun preservedReminderCount(id: Long): Int = resolver.query(Reminders.CONTENT_URI,
        arrayOf(Reminders._ID), "${Reminders.EVENT_ID}=? AND (${Reminders.MINUTES}<0 OR (${Reminders.METHOD}!=? AND ${Reminders.METHOD}!=?))",
        arrayOf(id.toString(), Reminders.METHOD_ALERT.toString(), Reminders.METHOD_DEFAULT.toString()), null,
    )?.use { it.count } ?: 0

    private fun hasAttendees(id: Long): Boolean = resolver.query(Attendees.CONTENT_URI, arrayOf(Attendees._ID),
        "${Attendees.EVENT_ID}=?", arrayOf(id.toString()), null)?.use { it.moveToFirst() } ?: false

    private fun hasExceptions(id: Long): Boolean {
        val syncId = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events._SYNC_ID),
            null, null, null)?.use { if (it.moveToFirst()) it.optional(Events._SYNC_ID) else null }
        val selection = if (syncId == null) "${Events.ORIGINAL_ID}=? AND ${Events.DELETED}=0"
        else "(${Events.ORIGINAL_ID}=? OR ${Events.ORIGINAL_SYNC_ID}=?) AND ${Events.DELETED}=0"
        val args = if (syncId == null) arrayOf(id.toString()) else arrayOf(id.toString(), syncId)
        return resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), selection, args, null)?.use { it.moveToFirst() } ?: false
    }

    private fun checkRead() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Calendar access must run off the main thread." }
        check(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            "Allow calendar access to show your events."
        }
    }

    private fun checkWrite() {
        checkRead()
        check(context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            "Allow calendar access to save events."
        }
    }

    companion object {
        private const val LOCAL_ACCOUNT = "Sunlith"
        private val STORED_PROJECTION = arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION,
            Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY,
            Events.EVENT_TIMEZONE, Events.RRULE, Events.UID_2445, Events.EXDATE, Events.RDATE, Events.EXRULE,
            Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY, Events.STATUS, Events._SYNC_ID, Events.ORIGINAL_SYNC_ID)
    }
}

private fun Cursor.string(name: String): String = getColumnIndex(name).let { if (it < 0 || isNull(it)) "" else getString(it) }
private fun Cursor.optional(name: String): String? = string(name).takeIf(String::isNotBlank)
private fun Cursor.long(name: String): Long = getColumnIndex(name).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
private fun Cursor.optionalLong(name: String): Long? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
private fun Cursor.int(name: String): Int = getColumnIndex(name).let { if (it < 0 || isNull(it)) 0 else getInt(it) }
