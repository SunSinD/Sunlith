package io.github.sunsind.sunlith.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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

internal suspend fun awaitFocusStart(motion: Boolean) {
    withFrameNanos { }
    withFrameNanos { }
    if (motion) delay(100)
}

internal fun focusScrollDuration(distance: Float, viewport: Int): Int =
    (550 + abs(distance) / viewport.coerceAtLeast(1) * 110).toInt().coerceIn(550, 1_800)

/** LazyList's long-distance animateScrollToItem teleports. Scroll measured pixels instead, keeping every frame continuous. */
internal suspend fun LazyListState.scrollToFocus(index: Int, motion: Boolean) {
    snapshotFlow { layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
    if (!motion) {
        scrollToItem(index)
        return
    }
    fun distance(): Float {
        val visible = layoutInfo.visibleItemsInfo
        visible.firstOrNull { it.index == index }?.let { return it.offset.toFloat() }
        val first = visible.firstOrNull() ?: return 0f
        val average = visible.map { it.size }.average().toFloat().coerceAtLeast(1f)
        return (index - first.index) * average + first.offset
    }
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    val initial = abs(distance())
    val topSpeed = maxOf(viewport * 1.5f, initial / focusScrollDuration(initial, viewport) * 1_700f)
    val acceleration = topSpeed * 5f
    val durationScale = currentCoroutineContext()[MotionDurationScale]
    var snapAfter = false
    scroll {
        var speed = 0f
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val scale = durationScale?.scaleFactor ?: 1f
            if (scale == 0f) { snapAfter = true; break }
            val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(0.032f) / scale
            previous = now
            val left = distance()
            if (abs(left) <= 1f) { scrollBy(left); break }
            speed = min(min(topSpeed, speed + acceleration * dt), sqrt(2f * acceleration * abs(left)))
            val step = sign(left) * min(abs(left), speed * dt)
            if (abs(scrollBy(step)) < abs(step) * 0.5f) break
        }
    }
    if (snapAfter) scrollToItem(index)
}

/** Minutes from midnight on [day] where [event] starts, or 0 when it began on an earlier day. */
internal fun startMinuteOn(event: CalendarEvent, day: LocalDate, zone: ZoneId): Int {
    val s = Instant.ofEpochMilli(event.startMillis).atZone(zone)
    return if (s.toLocalDate().isBefore(day)) 0 else s.hour * 60 + s.minute
}
