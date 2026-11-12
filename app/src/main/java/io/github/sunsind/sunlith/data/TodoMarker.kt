package io.github.sunsind.sunlith.data

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.DayOfWeek
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Portable calendar metadata. Notes remain readable in other calendar clients. */
object TodoMarker {
    const val TOKEN = "[sunlith:todo]"
    private val marker = Regex("(?<![^\\s>])\\[sunlith:todo](?![^\\s<])", RegexOption.IGNORE_CASE)

    fun has(description: String): Boolean = marker.containsMatchIn(description)

    fun strip(description: String): String {
        var notes = description
        marker.findAll(description).toList().asReversed().forEach { match ->
            var start = match.range.first
            val end = match.range.last + 1
            if (notes.substring(end).isBlank()) {
                val prefix = notes.substring(0, start)
                start -= when {
                    prefix.endsWith("\r\n\r\n") -> 4
                    prefix.endsWith("\n\n") -> 2
                    else -> 0
                }
            }
            notes = notes.removeRange(start, end)
        }
        return notes
    }

    fun set(description: String, enabled: Boolean): String = when {
        !enabled -> strip(description)
        has(description) -> description
        description.isEmpty() -> TOKEN
        else -> description + "\n\n" + TOKEN
    }
}

data class TodoItem(val event: CalendarEvent, val key: String, val availableAt: Long)

internal object TodoIdentity {
    fun key(event: CalendarEvent, masterId: Long = event.id, recurring: Boolean = !event.rrule.isNullOrBlank(),
        originalInstanceTime: Long? = null, originalAllDay: Boolean = event.allDay, recurrence: TodoRecurrenceIdentity? = null): String {
        val occurrence = if (!recurring && originalInstanceTime == null) "single" else {
            val start = originalInstanceTime ?: event.startMillis
            val ordered = recurrence?.let { identity -> identity.ordinal(start)?.let { "repeat:${identity.pattern}:$it" } }
            ordered ?: if (originalAllDay) CalendarDates.allDayDate(start).toString() else start.toString()
        }
        return "${event.calendarId}:$masterId:$occurrence"
    }

    fun availableAt(event: CalendarEvent, zone: ZoneId): Long = if (event.allDay)
        CalendarDates.allDayDate(event.startMillis).atStartOfDay(zone).toInstant().toEpochMilli()
    else event.startMillis
}

/**
 * The app's repeat presets use occurrence order, so moving a whole series keeps its completion.
 * Unrecognised selectors and extra recurrence dates retain the timestamp fallback in [TodoIdentity].
 */
internal class TodoRecurrenceIdentity private constructor(
    private val first: LocalDate,
    private val zone: ZoneId,
    private val frequency: String,
    private val interval: Int,
    private val weekStart: DayOfWeek,
    private val weekdays: Set<DayOfWeek>,
    val pattern: String,
) {
    fun ordinal(startMillis: Long): Long? {
        val date = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
        if (date < first) return null
        return when (frequency) {
            "DAILY" -> ChronoUnit.DAYS.between(first, date).takeIf { it % interval == 0L }?.div(interval)
            "WEEKLY" -> {
                if (date.dayOfWeek !in weekdays) return null
                val anchor = first.with(TemporalAdjusters.previousOrSame(weekStart))
                val week = ChronoUnit.WEEKS.between(anchor, date.with(TemporalAdjusters.previousOrSame(weekStart)))
                if (week % interval != 0L) return null
                fun offset(day: DayOfWeek) = Math.floorMod(day.value - weekStart.value, 7)
                val selected = weekdays.map(::offset)
                val firstOffset = offset(first.dayOfWeek)
                val dateOffset = offset(date.dayOfWeek)
                if (week == 0L) selected.count { it >= firstOffset && it < dateOffset }.toLong()
                else selected.count { it >= firstOffset } + (week / interval - 1) * selected.size + selected.count { it < dateOffset }
            }
            "MONTHLY" -> {
                if (date.dayOfMonth != first.dayOfMonth) return null
                val months = ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.from(date))
                if (months % interval != 0L) return null
                countPeriods(months / interval, 4800 / gcd(interval, 4800)) { period ->
                    val month = Math.floorMod(first.year * 12L + first.monthValue - 1L + period * interval, 4800L)
                    YearMonth.of(2000 + (month / 12).toInt(), (month % 12).toInt() + 1).isValidDay(first.dayOfMonth)
                }
            }
            "YEARLY" -> {
                if (date.monthValue != first.monthValue || date.dayOfMonth != first.dayOfMonth) return null
                val years = date.year.toLong() - first.year
                if (years % interval != 0L) return null
                countPeriods(years / interval, 400 / gcd(interval, 400)) { period ->
                    val year = 2000 + Math.floorMod(first.year.toLong() + period * interval, 400L).toInt()
                    YearMonth.of(year, first.monthValue).isValidDay(first.dayOfMonth)
                }
            }
            else -> null
        }
    }

    private fun countPeriods(before: Long, cycle: Int, valid: (Long) -> Boolean): Long {
        val whole = before / cycle
        val remainder = before % cycle
        val fullCount = if (whole == 0L) 0 else (0 until cycle).count { valid(it.toLong()) }
        return whole * fullCount + (0 until remainder.toInt()).count { valid(it.toLong()) }
    }

    companion object {
        private val days = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
            "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

        fun from(master: EventDraft, extraDates: Boolean = false): TodoRecurrenceIdentity? {
            if (extraDates) return null
            val rule = master.rrule?.uppercase(java.util.Locale.ROOT) ?: return null
            if (runCatching { IcsCodec.recurrence(rule) }.isFailure) return null
            val entries = rule.split(';').map { it.split('=', limit = 2) }
            if (entries.any { it.size != 2 } || entries.map { it[0] }.distinct().size != entries.size) return null
            val parts = entries.associate { it[0] to it[1] }
            if (parts.keys.any { it !in setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "WKST") }) return null
            val frequency = parts["FREQ"] ?: return null
            if (frequency !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
            if (frequency != "WEEKLY" && "BYDAY" in parts) return null
            val interval = parts["INTERVAL"]?.toIntOrNull() ?: if ("INTERVAL" in parts) return null else 1
            if (interval <= 0) return null
            val zone = if (master.allDay) ZoneOffset.UTC else runCatching { ZoneId.of(master.timeZone) }.getOrNull() ?: return null
            val first = Instant.ofEpochMilli(master.startMillis).atZone(zone).toLocalDate()
            val start = parts["WKST"]?.let { days[it] ?: return null } ?: DayOfWeek.MONDAY
            val weekdays = parts["BYDAY"]?.split(',')?.map { days[it] ?: return null }?.toSet() ?: setOf(first.dayOfWeek)
            if (frequency == "WEEKLY" && first.dayOfWeek !in weekdays) return null
            // COUNT/UNTIL bound the set, but shifting its end must not reset existing completions.
            val pattern = "$frequency/$interval" + if (frequency == "WEEKLY")
                "/${start.value}/" + if ("BYDAY" in parts) weekdays.sortedBy { it.value }.joinToString(",") { it.value.toString() } else "default"
                else ""
            return TodoRecurrenceIdentity(first, zone, frequency, interval, start, weekdays, pattern)
        }

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
