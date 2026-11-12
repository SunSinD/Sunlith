package io.github.sunsind.sunlith.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

data class DeviceCalendar(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val writable: Boolean,
    val visible: Boolean,
    val local: Boolean,
)

data class CalendarEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val description: String,
    val location: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String,
    val rrule: String? = null,
    val color: Int = 0,
    val calendarName: String = "",
    val school: SchoolEventInfo? = null,
) {
    val todo: Boolean = TodoMarker.has(description)
}

enum class SchoolAbsenceCoverage { NONE, PARTIAL, FULL }

data class SchoolEventPeriod(
    val eventId: Long,
    val startMillis: Long,
    val endMillis: Long,
    val location: String,
    val description: String,
)

data class SchoolEventAbsence(
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val notes: List<String>,
)

data class SchoolEventInfo(
    val course: String,
    val group: String,
    val classKey: String,
    val classKind: String,
    val kind: String,
    val courseTitle: String,
    val periods: List<SchoolEventPeriod>,
    val absences: List<SchoolEventAbsence>,
) {
    val courseKey: String get() = course
    val absenceCoverage: SchoolAbsenceCoverage get() {
        if (absences.isEmpty()) return SchoolAbsenceCoverage.NONE
        if (periods.isEmpty()) return SchoolAbsenceCoverage.FULL
        val fullyAbsent = periods.all { period ->
            var coveredUntil = period.startMillis
            absences.sortedBy { it.startMillis }.forEach { absence ->
                if (absence.allDay) coveredUntil = period.endMillis
                else if (absence.startMillis <= coveredUntil && absence.endMillis > coveredUntil) coveredUntil = absence.endMillis
            }
            coveredUntil >= period.endMillis
        }
        return if (fullyAbsent) SchoolAbsenceCoverage.FULL else SchoolAbsenceCoverage.PARTIAL
    }
}

data class EventDraft(
    val id: Long? = null,
    val calendarId: Long,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val timeZone: String = TimeZone.getDefault().id,
    val rrule: String? = null,
    val reminderMinutes: List<Int> = listOf(15),
)

enum class EditBlockReason { MISSING, READ_ONLY, GUESTS, RECURRENCE_EXCEPTION, INVALID_PROVIDER_DATA, SCHOOL_MANAGED }

internal data class ReminderPolicy(val maximumCount: Int, val alertsAllowed: Boolean, val maximumMinutes: Int, val writeMethod: Int = 1) {
    fun accepted(minutes: List<Int>): List<Int> = if (!alertsAllowed) emptyList()
        else minutes.distinct().filter { it in 0..maximumMinutes }.sorted().take(maximumCount.coerceAtLeast(0))
}

object CalendarDates {
    fun allDayMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun allDayDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()

    fun duration(startMillis: Long, endMillis: Long, allDay: Boolean): String {
        require(endMillis >= startMillis) { "The event must end after it starts." }
        return if (allDay) {
            val days = java.time.temporal.ChronoUnit.DAYS.between(allDayDate(startMillis), allDayDate(endMillis))
            require(days > 0) { "An all-day event must last at least one day." }
            "P${days}D"
        } else {
            "PT${(endMillis - startMillis) / 1000}S"
        }
    }

    fun durationEndMillis(startMillis: Long, duration: String, allDay: Boolean, timeZone: String): Long {
        val value = biweekly.util.Duration.parse(duration)
        require(!value.isPrior) { "Event duration cannot be negative." }
        val zone = if (allDay) ZoneOffset.UTC else ZoneId.of(timeZone)
        return Instant.ofEpochMilli(startMillis).atZone(zone)
            .plusWeeks((value.weeks ?: 0).toLong()).plusDays((value.days ?: 0).toLong())
            .plusHours((value.hours ?: 0).toLong()).plusMinutes((value.minutes ?: 0).toLong())
            .plusSeconds((value.seconds ?: 0).toLong()).toInstant().toEpochMilli()
    }

    fun providerDurationEndMillis(startMillis: Long, duration: String): Long {
        require(duration.length <= 80 && Regex("\\+?P(?=\\d|T\\d)(?:\\d+W|(?:\\d+D)?(?:T(?=\\d)(?:\\d+H)?(?:\\d+M)?(?:\\d+S)?)?)").matches(duration)) {
            "The calendar contains a malformed event duration."
        }
        val value = biweekly.util.Duration.parse(duration)
        require(!value.isPrior) { "Event duration cannot be negative." }
        return Math.addExact(startMillis, value.toMillis())
    }

    internal fun timingUnchanged(original: EventDraft, updated: EventDraft): Boolean =
        original.startMillis == updated.startMillis && original.endMillis == updated.endMillis &&
            original.allDay == updated.allDay && (original.allDay || original.timeZone == updated.timeZone)

    fun validate(draft: EventDraft) {
        require(draft.title.isNotBlank()) { "Give the event a title." }
        require(draft.title.length <= 1000 && draft.description.length <= 100_000 && draft.location.length <= 10_000) {
            "The event details are too long."
        }
        require(draft.endMillis >= draft.startMillis) { "The event must end after it starts." }
        ZoneId.of(draft.timeZone)
        if (draft.allDay) {
            require(draft.startMillis == allDayMillis(allDayDate(draft.startMillis))) { "All-day dates must use UTC midnight." }
            require(draft.endMillis == allDayMillis(allDayDate(draft.endMillis))) { "All-day dates must use UTC midnight." }
            require(draft.endMillis > draft.startMillis) { "An all-day event must last at least one day." }
        }
        require(draft.reminderMinutes.all { it in 0..525_600 }) { "Reminder times must be between now and one year before the event." }
        draft.rrule?.takeIf { it.isNotBlank() }?.let { rule ->
            require(rule.length <= 2048 && '\n' !in rule && '\r' !in rule) { "Invalid recurrence rule." }
            require(Regex("(^|;)FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;|$)", RegexOption.IGNORE_CASE).containsMatchIn(rule)) {
                "This recurrence frequency is not supported."
            }
            require(draft.endMillis > draft.startMillis) { "A repeating event must have a duration." }
            IcsCodec.recurrence(rule)
        }
    }
}

internal data class StoredEvent(
    val draft: EventDraft,
    val uid: String,
    val exdate: String? = null,
    val rdate: String? = null,
    val exrule: String? = null,
    val originalId: Long? = null,
    val originalInstanceTime: Long? = null,
    val originalAllDay: Boolean = false,
    val canceled: Boolean = false,
    val syncId: String? = null,
    val originalSyncId: String? = null,
    val originalDuration: String? = null,
    val invalidReason: String? = null,
)
