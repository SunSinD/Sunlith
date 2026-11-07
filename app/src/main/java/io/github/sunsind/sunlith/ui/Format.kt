package io.github.sunsind.sunlith.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

class Formats(private val context: Context, val locale: Locale) {
    private val timeFormat = DateFormat.getTimeFormat(context)
    private fun pattern(skeleton: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private val dayLong = pattern("EEEEdMMMM")
    private val dayLongYear = pattern("EEEEdMMMMy")
    private val dayShort = pattern("EEEdMMM")
    private val dayShortYear = pattern("EEEdMMMy")
    private val dayMonth = pattern("dMMM")
    private val numericDayMonth = pattern("Md")
    private val shortMonth = pattern("MMM")
    private val monthYear = pattern("MMMMy")
    private val monthOnly = pattern("LLLL")

    val is24Hour: Boolean get() = DateFormat.is24HourFormat(context)

    fun time(millis: Long): String = timeFormat.format(Date(millis))
    fun time(t: LocalTime): String = timeFormat.format(Date.from(LocalDate.of(2000, 1, 3).atTime(t).atZone(ZoneId.systemDefault()).toInstant()))

    fun dayLong(d: LocalDate): String = (if (d.year == LocalDate.now().year) dayLong else dayLongYear).format(d).cap()
    fun dayShort(d: LocalDate): String = (if (d.year == LocalDate.now().year) dayShort else dayShortYear).format(d).cap()
    fun dayShortWithYear(d: LocalDate): String = dayShortYear.format(d).cap()
    fun dayMonth(d: LocalDate): String = dayMonth.format(d)
    fun numericDayMonth(d: LocalDate): String = numericDayMonth.format(d)
    fun shortMonth(m: YearMonth): String = shortMonth.format(m.atDay(1)).cap()
    fun monthName(m: YearMonth): String = monthOnly.format(m.atDay(1)).cap()
    fun monthYear(m: YearMonth): String = monthYear.format(m.atDay(1)).cap()
    fun weekdayShort(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').cap()
    fun weekdayNarrow(d: DayOfWeek): String = d.getDisplayName(TextStyle.NARROW_STANDALONE, locale).cap()
    fun weekdayLong(d: DayOfWeek): String = d.getDisplayName(TextStyle.FULL, locale).cap()

    private fun String.cap() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    fun range(start: String, end: String): String = context.getString(R.string.time_range, start, end)
    fun timeRange(startMillis: Long, endMillis: Long): String =
        if (endMillis > startMillis) range(time(startMillis), time(endMillis)) else time(startMillis)

    fun reminder(minutes: Int): String {
        val r = context.resources
        return when {
            minutes == 0 -> r.getString(R.string.reminder_at_start)
            minutes % 10080 == 0 -> r.getQuantityString(R.plurals.reminder_weeks, minutes / 10080, minutes / 10080)
            minutes % 1440 == 0 -> r.getQuantityString(R.plurals.reminder_days, minutes / 1440, minutes / 1440)
            minutes % 60 == 0 -> r.getQuantityString(R.plurals.reminder_hours, minutes / 60, minutes / 60)
            else -> r.getQuantityString(R.plurals.reminder_minutes, minutes, minutes)
        }
    }

    fun repeat(rule: String?): String {
        val r = context.resources
        if (rule.isNullOrBlank()) return r.getString(R.string.repeat_none)
        RepeatPreset.match(rule)?.let { return r.getString(it.label) }
        val parts = rule.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).uppercase() to part.substring(i + 1)
        }.toMap()
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val (single, plural) = when (parts["FREQ"]?.uppercase()) {
            "DAILY" -> R.string.repeat_daily to R.plurals.repeat_every_days
            "WEEKLY" -> R.string.repeat_weekly to R.plurals.repeat_every_weeks
            "MONTHLY" -> R.string.repeat_monthly to R.plurals.repeat_every_months
            "YEARLY" -> R.string.repeat_yearly to R.plurals.repeat_every_years
            else -> return r.getString(R.string.repeat_custom_raw, rule)
        }
        val base = if (interval == 1) r.getString(single) else r.getQuantityString(plural, interval, interval)
        val out = mutableListOf(base)
        parts["BYDAY"]?.let { days ->
            val names = days.split(',').mapNotNull { token ->
                val code = token.trim().takeLast(2).uppercase()
                val prefix = token.trim().dropLast(2)
                val dow = WEEKDAY_CODES[code] ?: return@mapNotNull null
                val name = weekdayShort(dow)
                if (prefix.isBlank()) name else "$prefix $name"
            }
            if (names.isNotEmpty()) out += r.getString(R.string.repeat_on_days, names.joinToString(", "))
        }
        parts["COUNT"]?.toIntOrNull()?.let { out += r.getQuantityString(R.plurals.repeat_count, it, it) }
        parts["UNTIL"]?.let { until ->
            runCatching { LocalDate.parse(until.take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()?.let {
                out += r.getString(R.string.repeat_until, dayShortWithYear(it))
            }
        }
        return out.joinToString(", ")
    }

    companion object {
        val WEEKDAY_CODES = mapOf(
            "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
            "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
        )
    }
}

@Composable
fun rememberFormats(): Formats {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.getDefault()
    return remember(context, locale, configuration) { Formats(context, locale) }
}

enum class RepeatPreset(val rule: String?, val label: Int) {
    None(null, R.string.repeat_none),
    Daily("FREQ=DAILY", R.string.repeat_daily),
    Weekdays("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", R.string.repeat_weekdays),
    Weekly("FREQ=WEEKLY", R.string.repeat_weekly),
    Monthly("FREQ=MONTHLY", R.string.repeat_monthly),
    Yearly("FREQ=YEARLY", R.string.repeat_yearly);

    companion object {
        private fun normalize(rule: String) = rule.uppercase().split(';').map(String::trim).filter(String::isNotEmpty).toSet()
        fun match(rule: String?): RepeatPreset? {
            if (rule.isNullOrBlank()) return None
            val parts = normalize(rule)
            return entries.firstOrNull { it.rule != null && normalize(it.rule) == parts }
        }
    }
}

data class DaySpan(val first: LocalDate, val last: LocalDate) {
    val multiDay: Boolean get() = last.isAfter(first)
    val length: Long get() = ChronoUnit.DAYS.between(first, last) + 1
}

fun CalendarEvent.span(zone: ZoneId): DaySpan {
    if (allDay) {
        val start = CalendarDates.allDayDate(startMillis)
        val endExclusive = CalendarDates.allDayDate(endMillis)
        return DaySpan(start, if (endExclusive.isAfter(start)) endExclusive.minusDays(1) else start)
    }
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
    val last = if (endMillis > startMillis) Instant.ofEpochMilli(endMillis - 1).atZone(zone).toLocalDate() else start
    return DaySpan(start, if (last.isBefore(start)) start else last)
}

fun CalendarEvent.key(): String = "$id-$startMillis"

fun indexByDay(events: List<CalendarEvent>, zone: ZoneId, from: LocalDate, to: LocalDate): Map<LocalDate, List<CalendarEvent>> {
    val map = HashMap<LocalDate, MutableList<CalendarEvent>>()
    for (event in events) {
        val span = event.span(zone)
        var day = if (span.first.isBefore(from)) from else span.first
        val end = if (span.last.isAfter(to)) to else span.last
        while (!day.isAfter(end)) {
            map.getOrPut(day) { mutableListOf() }.add(event)
            day = day.plusDays(1)
        }
    }
    val order = compareByDescending<CalendarEvent> { it.allDay || it.span(zone).multiDay }
        .thenBy { it.startMillis }.thenBy { it.endMillis }.thenBy { it.title.lowercase() }
    return map.mapValues { (_, list) -> list.sortedWith(order) }
}

fun gridStart(month: YearMonth, mondayFirst: Boolean): LocalDate {
    val first = month.atDay(1)
    val startDow = if (mondayFirst) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val offset = (first.dayOfWeek.value - startDow.value + 7) % 7
    return first.minusDays(offset.toLong())
}

fun weekStart(date: LocalDate, mondayFirst: Boolean): LocalDate {
    val startDow = if (mondayFirst) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val offset = (date.dayOfWeek.value - startDow.value + 7) % 7
    return date.minusDays(offset.toLong())
}

fun weekDays(mondayFirst: Boolean): List<DayOfWeek> {
    val start = if (mondayFirst) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    return (0L until 7L).map { start.plus(it) }
}

fun Context.timeLabel(event: CalendarEvent, day: LocalDate, zone: ZoneId, f: Formats): String {
    val span = event.span(zone)
    if (event.allDay) {
        return if (span.multiDay) getString(R.string.all_day_day_of, (ChronoUnit.DAYS.between(span.first, day) + 1).toInt(), span.length.toInt())
        else getString(R.string.all_day)
    }
    if (!span.multiDay) return f.timeRange(event.startMillis, event.endMillis)
    return when (day) {
        span.first -> getString(R.string.starts_at, f.time(event.startMillis))
        span.last -> getString(R.string.ends_at, f.time(event.endMillis))
        else -> getString(R.string.all_day)
    }
}
