@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sunsind.sunlith.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Fill and text colors for an event drawn as a solid block. */
@Immutable
internal data class EventColors(val container: Color, val content: Color)

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
 * A one-time request to bring a date's most relevant event into view in the hourly timeline. Each instance is
 * handled once; data reloads that do not create a new request never move the timeline.
 */
@Stable
internal class TimelineFocus(val date: LocalDate)

/**
 * The event a timeline should scroll to on [day]: for today, the first event that has not ended yet (or the last one
 * if all are over); for other days, the first timed event. Days with only all-day events give the first of those.
 */
internal fun focusTarget(events: List<CalendarEvent>, day: LocalDate, now: LocalDateTime, zone: ZoneId): CalendarEvent? {
    val timed = events.filter { !it.isTimelineBanner() }.sortedWith(compareBy<CalendarEvent> { it.startMillis }.thenBy { it.endMillis })
    if (timed.isEmpty()) return events.firstOrNull { it.isTimelineBanner() }
    if (day != now.toLocalDate()) return timed.firstOrNull {
        Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == day
    } ?: timed.first()
    val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    return timed.firstOrNull { it.endMillis > nowMillis } ?: timed.last()
}

/** Minutes from midnight on [day] where [event] starts, or 0 when it began on an earlier day. */
internal fun startMinuteOn(event: CalendarEvent, day: LocalDate, zone: ZoneId): Int {
    val s = Instant.ofEpochMilli(event.startMillis).atZone(zone)
    return if (s.toLocalDate().isBefore(day)) 0 else s.hour * 60 + s.minute
}

/**
 * Every event on [day] with its full title and time, in a modal bottom sheet. It reads titles that a month cell
 * has to cut, without leaving the month. Tapping an event opens it; "Open day" goes to the Day view.
 */
@Composable
internal fun DayPreviewSheet(
    day: LocalDate, events: List<CalendarEvent>, f: Formats,
    onOpenEvent: (CalendarEvent) -> Unit, onOpenDay: (LocalDate) -> Unit, onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val untitled = stringResource(R.string.untitled)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = p.surface, contentColor = p.ink,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics { heading() }) {
                Text(f.dayLong(day), color = p.ink, style = MaterialTheme.typography.titleMedium)
                Text(
                    context.resources.getQuantityString(R.plurals.event_count, events.size, events.size),
                    color = p.muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { onOpenDay(day) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.calendar_open_day), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (events.isEmpty()) {
            Text(
                stringResource(R.string.no_events), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                color = p.muted, style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(events) { event ->
                    val colors = eventColors(event.color, p)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpenEvent(event) }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(14.dp).background(colors.container, RoundedCornerShape(4.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(event.title.ifBlank { untitled }, color = p.ink, style = MaterialTheme.typography.bodyLarge)
                            Text(context.timeLabel(event, day, zone, f), color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
