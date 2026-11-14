package io.github.sunsind.sunlith.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.SchoolAbsenceCoverage
import java.text.BreakIterator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Fill and text colors for an event block. [border] marks a full teacher absence or a verified college cancellation. */
@Immutable
internal data class EventColors(val container: Color, val content: Color, val border: Color? = null)

/**
 * Course fills. Light themes use deep tones under white text, dark themes light tones under near-black text. Every
 * pair keeps at least 5:1 text contrast, and every fill keeps at least 3:1 against the app background.
 */
private val COURSE_LIGHT = longArrayOf(
    0xFF1A5FB4, 0xFF2E7D32, 0xFF7B1FA2, 0xFFB4450E, 0xFFC62828, 0xFF00695C, 0xFF3F51B5, 0xFFAD1457, 0xFF6D4C41, 0xFF5C6B00,
)
private val COURSE_DARK = longArrayOf(
    0xFF8EC5FF, 0xFF9BD7A0, 0xFFD7A6E8, 0xFFFFB870, 0xFFF4A3A3, 0xFF7FD3C8, 0xFFAEB8F5, 0xFFF7A6C8, 0xFFD2BBAE, 0xFFCFD97A,
)
internal const val COURSE_SLOTS = 10
private val COURSE_INK_DARK = Color(0xFF121212)

internal fun courseColors(slot: Int, dark: Boolean): EventColors {
    val index = Math.floorMod(slot, COURSE_SLOTS)
    return if (dark) EventColors(Color(COURSE_DARK[index]), COURSE_INK_DARK) else EventColors(Color(COURSE_LIGHT[index]), Color.White)
}

internal fun preferredCourseSlot(key: String): Int = Math.floorMod(key.hashCode(), COURSE_SLOTS)

/**
 * Keeps saved course slots and assigns new courses the next free slot while the palette has room.
 * New keys are sorted so event order cannot affect their initial assignment.
 */
internal fun assignCourseSlots(keys: Collection<String>, previous: Map<String, Int> = emptyMap()): Map<String, Int> {
    val result = previous.filterValues { it in 0 until COURSE_SLOTS }.toMutableMap()
    val active = keys.filter { it.isNotBlank() }.toSet()
    val counts = IntArray(COURSE_SLOTS)
    active.mapNotNull { result[it] }.forEach { counts[it]++ }
    active.filter { it !in result }.sorted().forEach { key ->
        val preferred = preferredCourseSlot(key)
        val slot = (0 until COURSE_SLOTS).map { (preferred + it) % COURSE_SLOTS }.minBy { counts[it] }
        counts[slot]++
        result[key] = slot
    }
    return result
}

/** Course color slots for every school course currently loaded. Outside a provider, each course uses its hash slot. */
@Immutable
internal class CourseColors(private val slots: Map<String, Int>) {
    fun slot(key: String): Int = slots[key] ?: preferredCourseSlot(key)

    companion object {
        val Empty = CourseColors(emptyMap())
    }
}

internal val LocalCourseColors = staticCompositionLocalOf { CourseColors.Empty }

internal fun CalendarEvent.courseKeyOrNull(): String? = school?.courseKey?.takeIf { it.isNotBlank() }

@Composable
internal fun rememberCourseColors(eventsByDay: Map<LocalDate, List<CalendarEvent>>, saved: Map<String, Int> = emptyMap()): CourseColors {
    val keys = remember(eventsByDay) {
        eventsByDay.values.asSequence().flatten().mapNotNull { it.courseKeyOrNull() }.toSortedSet()
    }
    return remember(keys, saved) { CourseColors(assignCourseSlots(keys, saved)) }
}

/**
 * School classes take their saved course color; personal events use a stable title color. A college cancellation or
 * full teacher absence is drawn outlined, with explicit text so color is never the only signal.
 */
internal fun eventColors(event: CalendarEvent, palette: SunlithPalette, courses: CourseColors, dimmed: Boolean = false): EventColors {
    if (dimmed) return eventColors(event.color, palette, dimmed = true)
    val base = event.courseKeyOrNull()?.let { courseColors(courses.slot(it), palette.dark) }
        ?: courseColors(preferredCourseSlot("event:${event.title.trim().lowercase(Locale.ROOT)}"), palette.dark)
    if (event.school?.absenceCoverage != SchoolAbsenceCoverage.FULL && event.school?.lift == null) return base
    val border = if (base.container == palette.raised) palette.ink else base.container
    return EventColors(palette.surface, palette.ink, border)
}

@Composable
@ReadOnlyComposable
internal fun eventColors(event: CalendarEvent, dimmed: Boolean = false): EventColors =
    eventColors(event, LocalPalette.current, LocalCourseColors.current, dimmed)

/** A small marker color (dots, swatches) that matches the event's block color. */
@Composable
@ReadOnlyComposable
internal fun eventAccent(event: CalendarEvent): Color {
    val p = LocalPalette.current
    return eventColors(event, p, LocalCourseColors.current).let { it.border ?: it.container }
}

/** Keep a verified college cancellation in even the smallest Month chip. Teacher absence has its own status line. */
internal fun CalendarEvent.displayTitle(): String =
    school?.takeIf { it.kind == "01" && it.lift == null && it.courseTitle.isNotBlank() }?.courseTitle ?: title

/** Absence ranges inside this event, clipped to it and merged. Empty for a full or all-day absence. */
internal fun CalendarEvent.partialAbsences(): List<Pair<Long, Long>> {
    val info = school ?: return emptyList()
    if (info.absenceCoverage != SchoolAbsenceCoverage.PARTIAL) return emptyList()
    val merged = mutableListOf<Pair<Long, Long>>()
    info.absences.filter { !it.allDay }
        .map { maxOf(it.startMillis, startMillis) to minOf(it.endMillis, endMillis) }
        .filter { it.second > it.first }
        .sortedBy { it.first }
        .forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, range.second)
            else merged += range
        }
    return merged
}

/**
 * Rooms for a narrow block: the distinct rooms of every period, without a campus prefix that all of them share
 * ("MON-B2410, MON-DOUCHE" becomes "B2410, DOUCHE"). Rooms with different prefixes stay as they are.
 */
internal fun compactRooms(location: String): String {
    val rooms = location.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (rooms.isEmpty()) return ""
    val prefixes = rooms.map { ROOM_PREFIX.matchEntire(it)?.groupValues?.get(1) }
    val shared = prefixes.first()?.takeIf { first -> prefixes.all { it == first } }
    return rooms.joinToString(", ") { if (shared != null) it.substring(shared.length + 1) else it }
}

private val ROOM_PREFIX = Regex("([A-Za-z]{2,5})-(.+)")

/**
 * A title that wraps only where the line breaker may wrap it (spaces, line breaks, after a hyphen or slash) in a card
 * [width] pixels wide: a piece wider than the card keeps its longest start that fits, followed by an ellipsis, instead
 * of being split across lines. The separators are kept as they are. Scripts written without spaces between words are
 * returned unchanged, since they wrap between characters normally.
 */
internal fun fitWords(text: String, width: Int, locale: Locale = Locale.ROOT, measure: (String) -> Int): String {
    if (text.codePoints().anyMatch { it.toUnspacedScript() }) return text
    val lines = BreakIterator.getLineInstance(locale).apply { setText(text) }
    val out = StringBuilder(text.length + 4)
    var start = lines.first()
    var end = lines.next()
    while (end != BreakIterator.DONE) {
        val piece = text.substring(start, end)
        // Trailing spaces and line breaks hang past the line end, so only the visible part has to fit.
        val word = piece.trimEnd()
        out.append(if (word.isEmpty() || measure(word) <= width) piece else shortenWord(word, width, measure) + piece.substring(word.length))
        start = end
        end = lines.next()
    }
    return out.toString()
}

private fun shortenWord(word: String, width: Int, measure: (String) -> Int): String {
    val boundaries = BreakIterator.getCharacterInstance().apply { setText(word) }
    val first = boundaries.following(0)
    var end = first
    while (true) {
        val next = boundaries.next()
        if (next == BreakIterator.DONE || next >= word.length || measure(word.substring(0, next) + ELLIPSIS) > width) break
        end = next
    }
    // A single letter that cannot take an ellipsis is still more useful than an empty line.
    return if (end == first && measure(word.substring(0, first) + ELLIPSIS) > width) word.substring(0, first)
    else word.substring(0, end) + ELLIPSIS
}

private const val ELLIPSIS = "\u2026"

private fun Int.toUnspacedScript(): Boolean = when (Character.UnicodeScript.of(this)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
    Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN -> true
    else -> false
}

/**
 * A solid fill for an event, made from its calendar color so the same event always gets the same fill. Dark themes
 * get a soft pastel and light themes a mid tone; the text is pure black or white, whichever contrasts more, which
 * always gives at least 4.5:1. Events without a color, and [dimmed] ones (dates outside the shown month), use the
 * neutral raised surface instead.
 */
internal fun eventColors(argb: Int, palette: SunlithPalette, dimmed: Boolean = false): EventColors {
    if (dimmed) return EventColors(palette.raised, palette.muted)
    if (argb == 0) return EventColors(palette.raised, palette.ink)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb or 0xFF000000.toInt(), hsv)
    if (palette.dark) {
        hsv[1] = (hsv[1] * 0.55f).coerceAtMost(0.5f)
        hsv[2] = hsv[2].coerceIn(0.72f, 0.86f)
    } else {
        hsv[1] = (hsv[1] * 0.75f).coerceAtMost(0.7f)
        hsv[2] = hsv[2].coerceIn(0.50f, 0.66f)
    }
    val fill = Color(android.graphics.Color.HSVToColor(hsv))
    return EventColors(fill, readableOn(fill))
}

/** Black or white, whichever has the higher contrast ratio on [fill]. */
internal fun readableOn(fill: Color): Color {
    val l = fill.luminance()
    return if (1.05f / (l + 0.05f) >= (l + 0.05f) / 0.05f) Color.White else Color.Black
}

/** A ring that fades in while [active] is true. Used to point out the event a date opened on. */
@Composable
internal fun Modifier.focusRing(active: Boolean, color: Color, shape: Shape, motion: Boolean): Modifier {
    val alpha by animateFloatAsState(if (active) 1f else 0f, if (motion) tween(if (active) 200 else 600) else tween(0), label = "focusRing")
    return if (alpha <= 0f) this else border(2.dp, color.copy(alpha = alpha), shape)
}

/**
 * Short, localized time ranges for narrow event blocks: "8:30 - 10:30 p.m." instead of repeating the day period,
 * "8 - 9 a.m." for whole hours, "20:30 - 22:30" in 24-hour time. Uses the app's localized range separator, and
 * ordinary spaces so the text can wrap between the start and the end in a narrow column.
 */
@Stable
internal class CompactTimes(locale: Locale, is24Hour: Boolean, private val join: (String, String) -> String) {
    private val is24 = is24Hour
    private val hm = formatter(locale, if (is24Hour) "Hm" else "hm")
    private val h = if (is24Hour) hm else formatter(locale, "h")
    private val bareHm = bare(locale, if (is24Hour) null else "hm")
    private val bareH = bare(locale, if (is24Hour) null else "h")
    private val period = runCatching { DateTimeFormatter.ofPattern("a", locale) }.getOrNull()

    fun time(t: LocalTime): String = clean((if (!is24 && t.minute == 0) h else hm).format(t))

    fun range(start: LocalTime, end: LocalTime): String {
        val samePeriod = !is24 && period != null && bareHm != null && bareH != null && period.format(start) == period.format(end)
        val first = if (samePeriod) clean((if (start.minute == 0) bareH!! else bareHm!!).format(start)) else time(start)
        return join(first, time(end))
    }

    private fun clean(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    private companion object {
        fun formatter(locale: Locale, skeleton: String): DateTimeFormatter = runCatching {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        }.getOrElse { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }

        /** The 12-hour pattern without its day period, only when the period is a single trailing field. */
        fun bare(locale: Locale, skeleton: String?): DateTimeFormatter? {
            if (skeleton == null) return null
            val pattern = runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull() ?: return null
            if ('\'' in pattern || 'B' in pattern || 'b' in pattern || pattern.count { it == 'a' } != 1) return null
            val trimmed = pattern.trimEnd()
            if (!trimmed.endsWith("a")) return null
            val rest = trimmed.dropLast(1).trimEnd(' ', ' ', ' ')
            return runCatching { DateTimeFormatter.ofPattern(rest, locale) }.getOrNull()
        }
    }
}

/**
 * Explicit date opens consume their request before moving, so recreation cannot replay a scroll in progress.
 */
@Stable
internal class TimelineFocus(val date: LocalDate, val request: Int = 0, private val onStart: () -> Unit = {}) {
    fun start() = onStart()
}

/**
 * The first timed event on the chosen day, including today. All-day-only dates use their first banner.
 */
internal fun focusTarget(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId): CalendarEvent? {
    val timed = events.filter { !it.isTimelineBanner() }.sortedWith(compareBy<CalendarEvent> { it.startMillis }.thenBy { it.endMillis })
    if (timed.isEmpty()) return events.firstOrNull { it.isTimelineBanner() }
    return timed.firstOrNull { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == day } ?: timed.first()
}

/** Minutes from midnight on [day] where [event] starts, or 0 when it began on an earlier day. */
internal fun startMinuteOn(event: CalendarEvent, day: LocalDate, zone: ZoneId): Int {
    val s = Instant.ofEpochMilli(event.startMillis).atZone(zone)
    return if (s.toLocalDate().isBefore(day)) 0 else s.hour * 60 + s.minute
}

/**
 * School status keeps college cancellations distinct from teacher absences. Teacher absences include the exact missed
 * time when only part of the class is affected. Null when neither applies. [compact] shortens narrow timeline labels.
 */
@Composable
internal fun absenceStatus(event: CalendarEvent, f: Formats, compact: CompactTimes? = null): String? {
    val info = event.school ?: return null
    val cancellation = info.lift?.let { lift ->
        val until = lift.until
        if (until == null) stringResource(R.string.calendar_class_cancelled)
        else {
            val time = compact?.time(until) ?: f.time(until)
            stringResource(R.string.calendar_class_cancelled_until, time)
        }
    }
    val teacher = when (info.absenceCoverage) {
        SchoolAbsenceCoverage.NONE -> null
        SchoolAbsenceCoverage.FULL -> stringResource(if (compact != null) R.string.calendar_absent_short else R.string.calendar_teacher_absent)
        SchoolAbsenceCoverage.PARTIAL -> {
            val ranges = event.partialAbsences()
            if (ranges.isEmpty()) return cancellation
            val zone = ZoneId.systemDefault()
            val text = ranges.joinToString(", ") { (start, end) ->
                if (compact == null) f.timeRange(start, end)
                else compact.range(Instant.ofEpochMilli(start).atZone(zone).toLocalTime(), Instant.ofEpochMilli(end).atZone(zone).toLocalTime())
            }
            stringResource(if (compact != null) R.string.calendar_absent_short_range else R.string.calendar_teacher_absent_range, text)
        }
    }
    return listOfNotNull(cancellation, teacher).takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/**
 * An event card for lists: title, then any school status, the time and the room. Combined school classes list
 * each period's room with its start time when the rooms differ.
 */
@Composable
internal fun CalendarEventCard(event: CalendarEvent, day: LocalDate, f: Formats, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val time = context.timeLabel(event, day, zone, f)
    val title = event.displayTitle().ifBlank { stringResource(R.string.untitled) }
    val colors = eventColors(event)
    val status = absenceStatus(event, f)
    val periods = event.school?.periods.orEmpty().sortedBy { it.startMillis }
    val roomsByPeriod = periods.size > 1 && periods.map { it.location.trim() }.distinct().size > 1
    val shape = RoundedCornerShape(10.dp)
    val typography = MaterialTheme.typography
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 52.dp).clip(shape).background(colors.container)
            .then(colors.border?.let { Modifier.border(2.dp, it, shape) } ?: Modifier)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.content, style = typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (status != null) Text(status, color = colors.content, style = typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(time, color = colors.content, style = typography.bodyMedium)
            if (roomsByPeriod) {
                periods.filter { it.location.isNotBlank() }.forEach { period ->
                    Text(stringResource(R.string.calendar_period_room, f.time(period.startMillis), period.location), color = colors.content,
                        style = typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else if (event.location.isNotBlank()) {
                Text(event.location, color = colors.content, style = typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (event.todo) {
            Icon(Icons.Outlined.CheckBoxOutlineBlank, stringResource(R.string.todo_tag), tint = colors.content,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp).size(16.dp))
        }
        if (!event.rrule.isNullOrBlank()) {
            Icon(Icons.Outlined.Repeat, stringResource(R.string.repeating), tint = colors.content, modifier = Modifier.padding(horizontal = 12.dp).size(16.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}
