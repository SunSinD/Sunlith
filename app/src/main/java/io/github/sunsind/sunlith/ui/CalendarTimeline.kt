package io.github.sunsind.sunlith.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val DAY_MINUTES = 24 * 60

internal fun CalendarEvent.isTimelineBanner(): Boolean = allDay || endMillis - startMillis >= 86_400_000L

/** One timed event on one day, in minutes from local midnight, with its column inside its overlap group. */
internal class TimelineBlock(val event: CalendarEvent, val start: Int, val end: Int) {
    var col = 0
    var cols = 1
}

/** A run of events that overlap each other, directly or through a chain. They share the day width in [cols] columns. */
internal class TimelineCluster(val blocks: List<TimelineBlock>, val cols: Int)

/**
 * Places timed events by their local wall-clock time on [day]. Each event takes at least [minMinutes] so short
 * events stay readable and tappable; that minimum also counts when deciding overlaps, so blocks never cover each
 * other. Overlapping events are packed into the fewest columns, first come first placed.
 */
internal fun packTimeline(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId, minMinutes: Int): List<TimelineCluster> {
    val blocks = events.map { e ->
        val s = Instant.ofEpochMilli(e.startMillis).atZone(zone)
        val en = Instant.ofEpochMilli(max(e.endMillis, e.startMillis)).atZone(zone)
        var start = if (s.toLocalDate().isBefore(day)) 0 else s.hour * 60 + s.minute
        val end = if (en.toLocalDate().isAfter(day)) DAY_MINUTES else en.hour * 60 + en.minute
        var shownEnd = max(end, start + minMinutes).coerceAtMost(DAY_MINUTES)
        if (shownEnd - start < minMinutes) start = (shownEnd - minMinutes).coerceAtLeast(0)
        if (shownEnd <= start) shownEnd = (start + 1).coerceAtMost(DAY_MINUTES)
        TimelineBlock(e, start, shownEnd)
    }.sortedWith(compareBy<TimelineBlock> { it.start }.thenByDescending { it.end }.thenBy { it.event.title.lowercase() })

    val clusters = mutableListOf<TimelineCluster>()
    var current = mutableListOf<TimelineBlock>()
    val columnEnds = mutableListOf<Int>()
    var clusterEnd = -1
    fun close() {
        if (current.isEmpty()) return
        val cols = columnEnds.size
        current.forEach { it.cols = cols }
        clusters += TimelineCluster(current, cols)
        current = mutableListOf()
        columnEnds.clear()
    }
    for (b in blocks) {
        if (current.isNotEmpty() && b.start >= clusterEnd) close()
        val free = columnEnds.indexOfFirst { it <= b.start }
        if (free >= 0) {
            b.col = free
            columnEnds[free] = b.end
        } else {
            b.col = columnEnds.size
            columnEnds += b.end
        }
        current += b
        clusterEnd = max(clusterEnd, b.end)
    }
    close()
    return clusters
}

/** Width for a column of time labels: the widest label plus a little room, kept within a share of the screen. */
@Composable
internal fun rememberTimeGutter(labels: List<String>, style: TextStyle, minimum: Dp, maximum: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(labels, style, density) {
        with(density) { labels.maxOfOrNull { measurer.measure(it, style).size.width }?.toDp() ?: 0.dp }
    }
    return maxOf(minimum, widest + 8.dp).coerceAtMost(maxOf(minimum, maximum))
}

/** Visible label and spoken description for a date header or cell. */
internal fun dayDescription(context: android.content.Context, f: Formats, day: LocalDate, isToday: Boolean, count: Int): String = buildString {
    append(f.dayLong(day))
    if (isToday) append(", ").append(context.getString(R.string.today))
    append(", ").append(context.resources.getQuantityString(R.plurals.event_count, count, count))
}

/**
 * An hourly grid for one or more [days]: day headers (optional), an all-day area, an hour rail and timed events
 * placed at their real time. If the columns would be too narrow for readable text (many days, large text), it shows
 * [fallback] instead, which should be a plain list of the same days.
 */
@Composable
internal fun Timeline(
    days: List<LocalDate>,
    eventsOn: (LocalDate) -> List<CalendarEvent>,
    selected: LocalDate,
    today: LocalDate,
    f: Formats,
    showHeaders: Boolean,
    scroll: ScrollState,
    autoScroll: Boolean,
    todayRequest: Int,
    motion: Boolean,
    onSelectDay: (LocalDate) -> Unit,
    onOpen: (CalendarEvent) -> Unit,
    onNew: (LocalDate) -> Unit,
    onOverflow: (LocalDate, List<CalendarEvent>) -> Unit,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val zone = ZoneId.systemDefault()
    val typography = MaterialTheme.typography
    val railStyle = typography.labelSmall
    val titleStyle = typography.labelMedium
    val hour = with(density) { maxOf(64.dp, (titleStyle.lineHeight * 3.4f).toDp()) }
    val minBlock = with(density) { maxOf(40.dp, titleStyle.lineHeight.toDp() + 14.dp) }
    val minMinutes = ceil(minBlock / hour * 60f).toInt()
    val minColumn = with(density) { maxOf(40.dp, (titleStyle.fontSize * 3.2f).toDp()) }
    val minBlockWidth = with(density) { maxOf(32.dp, (titleStyle.fontSize * 2.6f).toDp()) }
    val hourLabels = remember(f) { (1 until 24).map { f.time(LocalTime.of(it, 0)) } }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val gutter = rememberTimeGutter(hourLabels, railStyle, 44.dp, maxWidth * 0.25f)
        val column = (maxWidth - gutter - 4.dp) / days.size
        if (column < minColumn) {
            fallback()
        } else {
            val allDayHeight = maxHeight / 4
            val hourPx = with(density) { hour.toPx() }
            LaunchedEffect(scroll, autoScroll) {
                if (!autoScroll || scroll.value != 0) return@LaunchedEffect
                snapshotFlowMax(scroll)
                val anchor = if (today in days) (LocalTime.now().hour - 1).coerceAtLeast(0) else 7
                scroll.scrollTo((anchor * hourPx).roundToInt())
            }
            val handled = remember { intArrayOf(todayRequest) }
            LaunchedEffect(todayRequest) {
                if (todayRequest == handled[0] || !autoScroll) return@LaunchedEffect
                handled[0] = todayRequest
                val target = ((LocalTime.now().hour - 1).coerceAtLeast(0) * hourPx).roundToInt()
                if (motion) scroll.animateScrollTo(target, tween(300)) else scroll.scrollTo(target)
            }
            Column(Modifier.fillMaxSize()) {
                if (showHeaders) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Spacer(Modifier.width(gutter))
                        days.forEach { day ->
                            TimelineDayHeader(day, day == selected, day == today, eventsOn(day).size, f, Modifier.width(column)) { onSelectDay(day) }
                        }
                    }
                }
                val allDay = days.map { d -> eventsOn(d).filter { it.isTimelineBanner() } }
                if (allDay.any { it.isNotEmpty() }) {
                    Row(Modifier.fillMaxWidth().heightIn(max = allDayHeight).testTag("calendar_timeline_all_day").verticalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
                        Text(
                            stringResource(R.string.all_day), Modifier.width(gutter).padding(end = 6.dp, top = 6.dp),
                            color = p.muted, style = railStyle, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        days.forEachIndexed { i, day ->
                            Column(Modifier.width(column).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                allDay[i].forEach { e -> AllDayChip(e, day, f, titleStyle) { onOpen(e) } }
                            }
                        }
                    }
                }
                HorizontalDivider(color = p.line)
                Box(Modifier.weight(1f).fillMaxWidth().testTag("calendar_timeline").verticalScroll(scroll)) {
                    val lineColor = p.line
                    Row(
                        Modifier.height(hour * 24).fillMaxWidth().drawBehind {
                            val left = gutter.toPx()
                            for (h in 1 until 24) {
                                val y = h * hourPx
                                drawLine(lineColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                            }
                            for (i in 1 until days.size) {
                                val x = left + i * column.toPx()
                                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                            }
                        },
                    ) {
                        HourRail(hourLabels, railStyle, hourPx, Modifier.width(gutter).fillMaxHeight())
                        days.forEach { day ->
                            val timed = eventsOn(day).filter { !it.isTimelineBanner() }
                            DayColumn(
                                day, timed, zone, minMinutes, hour, minBlockWidth, day == today, f, titleStyle,
                                Modifier.width(column).fillMaxHeight(), onOpen, onNew, onOverflow,
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun snapshotFlowMax(scroll: ScrollState) {
    snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE }
}

@Composable
private fun HourRail(labels: List<String>, style: TextStyle, hourPx: Float, modifier: Modifier) {
    val p = LocalPalette.current
    val endPad = with(LocalDensity.current) { 6.dp.roundToPx() }
    Layout(
        content = { labels.forEach { Text(it, color = p.muted, style = style, maxLines = 1, softWrap = false) } },
        modifier = modifier.clearAndSetSemantics { },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                val y = ((i + 1) * hourPx - placeable.height / 2f).roundToInt()
                placeable.place((constraints.maxWidth - placeable.width - endPad).coerceAtLeast(0), y)
            }
        }
    }
}

@Composable
private fun TimelineDayHeader(day: LocalDate, isSelected: Boolean, isToday: Boolean, count: Int, f: Formats, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val description = dayDescription(context, f, day, isToday, count)
    val circle = with(LocalDensity.current) { maxOf(32.dp, (MaterialTheme.typography.titleSmall.fontSize * 2f).toDp()) }
    Column(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(f.weekdayShort(day.dayOfWeek), color = if (isToday || isSelected) p.ink else p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip)
        Box(
            Modifier.size(circle).clip(CircleShape)
                .background(if (isSelected) p.accent else Color.Transparent)
                .then(if (isToday && !isSelected) Modifier.border(1.5.dp, p.ink, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(), color = if (isSelected) p.onAccent else p.ink, style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
private fun AllDayChip(event: CalendarEvent, day: LocalDate, f: Formats, style: TextStyle, onClick: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val title = event.title.ifBlank { stringResource(R.string.untitled) }
    val description = title + ", " + context.timeLabel(event, day, ZoneId.systemDefault(), f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(RoundedCornerShape(6.dp)).background(p.surface)
            .testTag("timeline_event_${event.id}")
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 3.dp).width(3.dp).height(18.dp).background(eventTint(event.color, p), RoundedCornerShape(2.dp)))
        Text(title, Modifier.padding(horizontal = 4.dp), color = p.ink, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DayColumn(
    day: LocalDate, events: List<CalendarEvent>, zone: ZoneId, minMinutes: Int, hour: Dp, minBlockWidth: Dp, isToday: Boolean,
    f: Formats, style: TextStyle, modifier: Modifier,
    onOpen: (CalendarEvent) -> Unit, onNew: (LocalDate) -> Unit, onOverflow: (LocalDate, List<CalendarEvent>) -> Unit,
) {
    val p = LocalPalette.current
    val addLabel = stringResource(R.string.action_add_event)
    val clusters = remember(events, day, zone, minMinutes) { packTimeline(events, day, zone, minMinutes) }
    BoxWithConstraints(modifier.clickable(onClickLabel = addLabel, role = Role.Button) { onNew(day) }) {
        val maxCols = (maxWidth / minBlockWidth).toInt().coerceAtLeast(1)
        clusters.forEach { cluster ->
            val overflow = cluster.cols > maxCols
            val cols = if (overflow) maxCols else cluster.cols
            val width = maxWidth / cols
            val hidden = mutableListOf<TimelineBlock>()
            cluster.blocks.forEach { b ->
                if (overflow && b.col >= maxCols - 1) {
                    hidden += b
                } else {
                    EventBlock(
                        b.event, day, f, style,
                        Modifier.offset(x = width * b.col, y = hour * (b.start / 60f)).width(width).height(hour * ((b.end - b.start) / 60f)),
                    ) { onOpen(b.event) }
                }
            }
            if (hidden.isNotEmpty()) {
                val start = hidden.minOf { it.start }
                val end = hidden.maxOf { it.end }
                OverflowBlock(
                    hidden.size,
                    Modifier.offset(x = width * (maxCols - 1), y = hour * (start / 60f)).width(width).height(hour * ((end - start) / 60f)),
                ) { onOverflow(day, hidden.map { it.event }) }
            }
        }
        if (isToday) NowLine(hour)
    }
}

@Composable
private fun EventBlock(event: CalendarEvent, day: LocalDate, f: Formats, style: TextStyle, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val title = event.title.ifBlank { stringResource(R.string.untitled) }
    val time = context.timeLabel(event, day, ZoneId.systemDefault(), f)
    val description = "$title, $time"
    BoxWithConstraints(
        modifier.padding(1.dp).clip(RoundedCornerShape(6.dp)).background(p.raised)
            .testTag("timeline_event_${event.id}")
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description; role = Role.Button },
    ) {
        val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
        val lines = ((maxHeight - 8.dp) / lineHeight).toInt().coerceAtLeast(1)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.padding(vertical = 4.dp).width(3.dp).fillMaxHeight().background(eventTint(event.color, p), RoundedCornerShape(2.dp)))
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text(title, color = p.ink, style = style, fontWeight = FontWeight.Medium, maxLines = if (lines >= 2) lines - 1 else 1, overflow = TextOverflow.Ellipsis)
                if (lines >= 2) Text(time, color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun OverflowBlock(count: Int, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = LocalContext.current.resources.getQuantityString(R.plurals.event_count, count, count)
    Box(
        modifier.padding(1.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, p.line, RoundedCornerShape(6.dp)).background(p.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.TopCenter,
    ) {
        Text("+$count", Modifier.padding(top = 4.dp), color = p.ink, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun NowLine(hour: Dp) {
    val p = LocalPalette.current
    val now by produceState(LocalTime.now()) {
        while (true) {
            delay(30_000)
            value = LocalTime.now()
        }
    }
    val y = hour * ((now.hour * 60 + now.minute) / 60f)
    Box(Modifier.fillMaxWidth().offset(y = y - 1.dp).height(2.dp).background(p.ink).clearAndSetSemantics { })
    Box(Modifier.offset(x = (-3).dp, y = y - 4.dp).size(8.dp).background(p.ink, CircleShape).clearAndSetSemantics { })
}
