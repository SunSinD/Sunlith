package io.github.sunsind.sunlith.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val SCHOOL_CHANGEOVER_MILLIS = 5 * 60_000L

/** A display projection only: provider rows, reminder times and external event IDs remain unchanged. */
fun projectSchoolEvents(events: List<CalendarEvent>, zone: ZoneId): List<CalendarEvent> {
    if (events.none { it.school != null }) return events
    fun day(event: CalendarEvent): LocalDate = if (event.allDay) CalendarDates.allDayDate(event.startMillis)
        else Instant.ofEpochMilli(event.startMillis).atZone(runCatching { ZoneId.of(event.timeZone) }.getOrDefault(zone)).toLocalDate()
    fun identity(event: CalendarEvent): List<Any>? = event.school?.takeIf {
        it.classKey.isNotBlank() && it.course.isNotBlank() && it.group.isNotBlank()
    }?.let { listOf(event.calendarId, it.classKey, it.course, it.group, day(event)) }
    fun sameIdentity(first: CalendarEvent, second: CalendarEvent): Boolean =
        identity(first) != null && identity(first) == identity(second) &&
            (first.allDay || second.allDay || first.timeZone == second.timeZone)
    fun absenceMatches(notice: CalendarEvent, event: CalendarEvent): Boolean = sameIdentity(notice, event) &&
        (notice.school!!.classKind.isBlank() || notice.school.classKind == event.school!!.classKind)
    fun classEvent(event: CalendarEvent) = event.school?.kind == "01" && !event.allDay && event.endMillis > event.startMillis
    fun adjacent(first: CalendarEvent, second: CalendarEvent) = second.startMillis >= first.endMillis &&
        second.startMillis - first.endMillis <= SCHOOL_CHANGEOVER_MILLIS

    val classes = events.filter(::classEvent).toMutableList()
    val remaining = mutableListOf<CalendarEvent>()
    val notices = events.filter { it.school?.kind == "absence" }
    notices.forEach { notice ->
        val matching = classes.indices.filter { absenceMatches(notice, classes[it]) }
        val overlapping = matching.filter { notice.allDay ||
            (notice.startMillis < classes[it].endMillis && notice.endMillis > classes[it].startMillis) }
        val ordered = overlapping.sortedBy { classes[it].startMillis }
        val fits = notice.allDay || (ordered.isNotEmpty() && notice.startMillis >= classes[ordered.first()].startMillis &&
            notice.endMillis <= classes[ordered.last()].endMillis && ordered.zipWithNext().all { (a, b) ->
                adjacent(classes[a], classes[b]) && classes[a].school?.classKind == classes[b].school?.classKind
            })
        if (ordered.isNotEmpty() && fits) {
            ordered.forEach { index ->
                val event = classes[index]
                classes[index] = event.copy(school = event.school!!.copy(absences =
                    (event.school.absences + notice.school!!.absences).distinct()))
            }
        } else remaining += notice
    }

    // A timed absence can itself be a canceled period omitted from the regular agenda.
    // It joins a class only when the complete identity and neighboring period establish that relationship.
    val candidates = classes.toMutableList()
    val unattached = mutableListOf<CalendarEvent>()
    remaining.forEach { notice ->
        val matching = classes.filter { sameIdentity(notice, it) }
        val kinds = matching.map { it.school!!.classKind }.distinct()
        val kind = notice.school!!.classKind
        if (!notice.allDay && notice.endMillis > notice.startMillis && kinds.size == 1 &&
            (kind.isBlank() || kind == kinds.single())) {
            candidates += notice.copy(school = notice.school.copy(classKind = kinds.single()))
        } else unattached += notice
    }

    val projected = mutableListOf<CalendarEvent>()
    // A confirmed lifting can end between periods. Keep its marked run separate from the class that resumes later.
    candidates.groupBy { event ->
        val info = requireNotNull(event.school)
        listOf(identity(event), event.timeZone, info.classKind, info.lift)
    }.values.forEach { rows ->
        val runs = mutableListOf<MutableList<CalendarEvent>>()
        rows.sortedWith(compareBy<CalendarEvent> { it.startMillis }.thenBy { it.endMillis }.thenBy { it.id }).forEach { event ->
            val previous = runs.lastOrNull()?.lastOrNull()
            if (previous != null && adjacent(previous, event) && identity(event) != null) runs.last() += event
            else runs += mutableListOf(event)
        }
        runs.forEach { run ->
            val actual = run.firstOrNull(::classEvent)
            if (actual == null || run.size == 1) projected += run
            else {
                val periods = run.flatMap { it.school!!.periods }.distinct().sortedBy { it.startMillis }
                val absences = run.flatMap { it.school!!.absences }.distinct().sortedBy { it.startMillis }
                projected += actual.copy(
                    id = run.first().id,
                    title = if (actual.school!!.lift != null) actual.title else actual.school.courseTitle,
                    startMillis = run.first().startMillis,
                    endMillis = run.last().endMillis,
                    location = periods.map { it.location }.filter { it.isNotBlank() }.distinct().joinToString("; "),
                    description = periods.map { it.description }.filter { it.isNotBlank() }.distinct().joinToString("\n\n"),
                    school = actual.school.copy(periods = periods, absences = absences),
                )
            }
        }
    }
    return (projected + unattached + events.filter { !classEvent(it) && it.school?.kind != "absence" })
        .sortedWith(compareBy<CalendarEvent> { it.startMillis }.thenBy { it.endMillis }.thenBy { it.id })
}
