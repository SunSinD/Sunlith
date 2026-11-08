package io.github.sunsind.sunlith.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.stopScroll
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val DAY_MINUTES = 24 * 60

/** Hour shown at the top when a date that is not today opens with no timed events. */
private const val DEFAULT_START_HOUR = 7

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
 * placed at their real time, drawn as solid blocks in their event color. If the columns would be too narrow for
 * readable text (many days, large text), it shows [fallback] instead, which should be a plain list of the same days.
 *
 * [focus] is a one-time request: when its date is one of [days] and this is the active page ([autoScroll]), the
 * timeline scrolls to that date's most relevant event (see [focusTarget]), briefly rings it, and reports
 * [onFocusHandled]. Without a request, the first open scrolls to the current hour (today) or [DEFAULT_START_HOUR].
 * Reloads never move the timeline on their own. [tintToday] shades today's column slightly.
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
    focus: TimelineFocus? = null,
    onFocusHandled: (TimelineFocus) -> Unit = {},
    tintToday: Boolean = true,
    allowHorizontalScroll: Boolean = false,
    fallback: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val zone = ZoneId.systemDefault()
    val typography = MaterialTheme.typography
    val railStyle = typography.labelSmall
    val titleStyle = typography.labelMedium
    // Scale the actual text size before multiplying; sp conversion is non-linear at large font sizes.
    val titleSize = with(density) { titleStyle.fontSize.toDp() }
    val titleLine = with(density) { titleStyle.lineHeight.toDp() }
    val hour = if (days.size > 1) maxOf(88.dp, titleLine * 5.5f) else maxOf(64.dp, titleLine * 3.4f)
    val minBlock = maxOf(56.dp, titleLine + 14.dp)
    val minMinutes = ceil(minBlock / hour * 60f).toInt()
    val minColumn = maxOf(40.dp, titleSize * 3.2f)
    val overflowThreshold = maxOf(60.dp, titleSize * 5f)
    val readableColumn = maxOf(80.dp, titleSize * 6.5f)
    val minBlockWidth = maxOf(32.dp, titleSize * 2.6f)
    val compact = remember(f) { CompactTimes(f.locale, f.is24Hour, f::range) }
    val hourLabels = remember(f, compact) {
        (0..24).map { hour ->
            if (hour == 24 && f.is24Hour) String.format(f.locale, "%02d:%02d", 24, 0)
            else compact.time(LocalTime.of(hour % 24, 0))
        }
    }
    val allDayLabel = stringResource(R.string.all_day)
    val allDay = days.map { d -> eventsOn(d).filter { it.isTimelineBanner() } }
    val gutterLabels = if (allDay.any { it.isNotEmpty() }) {
        hourLabels + listOf(allDayLabel.substringBeforeLast(' '), allDayLabel.substringAfterLast(' '))
    } else hourLabels
    val reportFocus by rememberUpdatedState(onFocusHandled)
    val sideways = rememberScrollState()
    var highlighted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlighted) {
        if (highlighted == null) return@LaunchedEffect
        delay(1800)
        highlighted = null
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val gutter = rememberTimeGutter(gutterLabels, railStyle, 44.dp,
            maxWidth * if (allDay.any { it.isNotEmpty() }) 0.30f else 0.25f)
        val fitted = (maxWidth - gutter - 4.dp) / days.size
        val overflow = allowHorizontalScroll && days.size > 1 && fitted < overflowThreshold
        val column = if (overflow) readableColumn else fitted
        val dayArea = Modifier.then(if (overflow) Modifier.horizontalScroll(sideways) else Modifier)
        if (overflow) {
            // Keeps the selected weekday in view. A neighbouring page uses the same weekday, so settling on it never jumps.
            val anchor = days.indexOf(selected).takeIf { it >= 0 } ?: days.indexOfFirst { it.dayOfWeek == selected.dayOfWeek }.coerceAtLeast(0)
            val columnPx = with(density) { column.toPx() }
            LaunchedEffect(anchor, columnPx, todayRequest) {
                val viewport = withTimeoutOrNull(1_000) { snapshotFlow { sideways.viewportSize }.first { it > 0 } } ?: return@LaunchedEffect
                val start = (anchor * columnPx).roundToInt()
                val end = ((anchor + 1) * columnPx).roundToInt()
                val target = when {
                    start < sideways.value -> start
                    end > sideways.value + viewport -> end - viewport
                    else -> return@LaunchedEffect
                }.coerceIn(0, sideways.maxValue.coerceAtLeast(0))
                if (autoScroll && motion) sideways.animateScrollTo(target, tween(250)) else sideways.scrollTo(target)
            }
        }
        if (!overflow && column < minColumn) {
            fallback()
        } else {
            val allDayHeight = maxHeight / 4
            val hourPx = with(density) { hour.toPx() }
            var initialPositioned by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(scroll, autoScroll) {
                if (initialPositioned) return@LaunchedEffect
                initialPositioned = true
                if (!autoScroll || !showHeaders || scroll.value != 0 || focus != null) return@LaunchedEffect
                if (!awaitScrollable(scroll)) return@LaunchedEffect
                val anchor = if (today in days) (LocalTime.now().hour - 1).coerceAtLeast(0) else DEFAULT_START_HOUR
                scroll.scrollTo((anchor * hourPx).roundToInt())
            }
            val handled = remember { intArrayOf(todayRequest) }
            LaunchedEffect(todayRequest, autoScroll, scroll) {
                if (todayRequest == handled[0]) return@LaunchedEffect
                handled[0] = todayRequest
                if (!autoScroll) return@LaunchedEffect
                if (!awaitScrollable(scroll)) return@LaunchedEffect
                scroll.stopScroll()
                val target = ((LocalTime.now().hour - 1).coerceAtLeast(0) * hourPx).roundToInt()
                    .coerceIn(0, scroll.maxValue)
                if (target != scroll.value) {
                    if (!motion) scroll.scrollTo(target)
                    else {
                        val animation = coroutineContext[kotlinx.coroutines.Job]!!
                        val interruption = launch {
                            scroll.interactionSource.interactions.first { it is DragInteraction.Start }
                            animation.cancel()
                        }
                        try {
                            // Holding the scroll lock through the tween makes verticalScroll claim the next down,
                            // even when it starts a horizontal page swipe. This programmatic move leaves touch free.
                            animate(scroll.value.toFloat(), target.toFloat(), animationSpec = tween(300)) { value, _ ->
                                val position = value.coerceIn(0f, scroll.maxValue.toFloat())
                                scroll.dispatchRawDelta(position - scroll.value)
                            }
                        } finally { interruption.cancel() }
                    }
                }
            }
            LaunchedEffect(focus, autoScroll) {
                val request = focus ?: return@LaunchedEffect
                if (!autoScroll || request.date !in days) return@LaunchedEffect
                request.start()
                try {
                    awaitFocusStart(motion)
                    val target = focusTarget(eventsOn(request.date), request.date, zone)
                    val minute = if (target != null && !target.isTimelineBanner())
                        (startMinuteOn(target, request.date, zone) - 30).coerceAtLeast(0) else 0
                    if (awaitScrollable(scroll)) {
                        val y = (minute / 60f * hourPx).roundToInt().coerceIn(0, scroll.maxValue)
                        val distance = (y - scroll.value).toFloat()
                        if (distance != 0f) {
                            if (motion) scroll.animateScrollTo(y, tween(focusScrollDuration(distance, scroll.viewportSize)))
                            else scroll.scrollTo(y)
                        }
                    }
                    highlighted = target?.key()
                } finally { reportFocus(request) }
            }
            Column(Modifier.fillMaxSize()) {
                if (showHeaders) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Spacer(Modifier.width(gutter))
                        Row(Modifier.weight(1f).then(dayArea)) {
                            days.forEach { day ->
                                TimelineDayHeader(day, day == selected, day == today, eventsOn(day).size, f, Modifier.width(column)) { onSelectDay(day) }
                            }
                        }
                    }
                }
                if (allDay.any { it.isNotEmpty() }) {
                    Row(Modifier.fillMaxWidth().heightIn(max = allDayHeight).testTag("calendar_timeline_all_day").verticalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
                        Text(
                            allDayLabel.substringBeforeLast(' ') + "\n" + allDayLabel.substringAfterLast(' '),
                            Modifier.width(gutter).padding(end = 6.dp, top = 6.dp),
                            color = p.muted, style = railStyle,
                            textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Left else TextAlign.Right,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        Row(Modifier.weight(1f).then(dayArea)) {
                            days.forEachIndexed { i, day ->
                                Column(Modifier.width(column).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    allDay[i].forEach { e -> AllDayChip(e, day, f, highlighted == e.key(), motion, allDayHeight) { onOpen(e) } }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = p.line)
                Row(Modifier.weight(1f).fillMaxWidth().testTag("calendar_timeline").verticalScroll(scroll)) {
                    HourRail(hourLabels, railStyle, hourPx, Modifier.width(gutter).height(hour * 24))
                    Box(Modifier.weight(1f).height(hour * 24).then(dayArea)) {
                        val lineColor = p.line
                        Row(
                            Modifier.width(column * days.size).fillMaxHeight().drawBehind {
                                val stroke = 1.dp.toPx()
                                for (h in 1 until 24) {
                                    val y = h * hourPx
                                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                                }
                                for (i in 1 until days.size) {
                                    val x = startX(i * column.toPx())
                                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                                }
                            },
                        ) {
                            days.forEach { day ->
                                val timed = eventsOn(day).filter { !it.isTimelineBanner() }
                                DayColumn(
                                    day, timed, zone, minMinutes, hour, minBlockWidth, day == today && tintToday, f, compact, titleStyle,
                                    highlighted, motion, Modifier.width(column).fillMaxHeight(), onOpen, onNew, onOverflow,
                                )
                            }
                        }
                        val todayIndex = days.indexOf(today)
                        if (todayIndex >= 0) NowLine(column, todayIndex, days.size, hourPx, Modifier.width(column * days.size).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** Mirrors an x distance measured from the start edge, so lines follow the Row's order in right-to-left layouts. */
private fun DrawScope.startX(fromStart: Float): Float = if (layoutDirection == LayoutDirection.Rtl) size.width - fromStart else fromStart

/** Waits until the hourly area can scroll. Gives up (false) when it never needs to, such as on a very tall screen. */
private suspend fun awaitScrollable(scroll: ScrollState): Boolean =
    withTimeoutOrNull(1_000) { snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE } } != null

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
                val y = (i * hourPx - placeable.height / 2f).roundToInt()
                    .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                placeable.placeRelative((constraints.maxWidth - placeable.width - endPad).coerceAtLeast(0), y)
            }
        }
    }
}

/**
 * A date above a timeline column. It uses the same type as the Month and Day pickers (the [WeekdayLabels] style
 * above a [dayCircle]-sized date), shrunk only when the column is narrower than the circle.
 */
@Composable
private fun TimelineDayHeader(day: LocalDate, isSelected: Boolean, isToday: Boolean, count: Int, f: Formats, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val description = dayDescription(context, f, day, isToday, count)
    val density = LocalDensity.current
    val narrow = density.fontScale > 1.3f
    val circle = with(density) { maxOf(40.dp, MaterialTheme.typography.bodyLarge.fontSize.toDp() * 2.25f) }
    Column(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (narrow) f.weekdayNarrow(day.dayOfWeek) else f.weekdayShort(day.dayOfWeek),
            color = if (isToday || isSelected) p.ink else p.muted, style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip,
        )
        Box(
            Modifier.padding(horizontal = 2.dp, vertical = 2.dp).widthIn(max = circle).fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                .background(if (isSelected) p.accent else Color.Transparent)
                .then(if (isToday && !isSelected) Modifier.border(1.5.dp, p.ink, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(), color = if (isSelected) p.onAccent else p.ink, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
private fun AllDayChip(event: CalendarEvent, day: LocalDate, f: Formats, highlight: Boolean, motion: Boolean, viewportHeight: Dp, onClick: () -> Unit) {
    val context = LocalContext.current
    val title = event.displayTitle().ifBlank { stringResource(R.string.untitled) }
    val status = absenceStatus(event, f)
    val description = listOfNotNull(title, status, context.timeLabel(event, day, ZoneId.systemDefault(), f)).joinToString(", ")
    val colors = eventColors(event)
    val shape = RoundedCornerShape(8.dp)
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph,
        localeList = LocaleList(f.locale.toLanguageTag()))
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineHeight = remember(style, density) { measurer.measure("Ag", style, maxLines = 1).size.height.coerceAtLeast(1) }
    val maxLines = with(density) { ((viewportHeight - 12.dp).toPx() / lineHeight).toInt().coerceIn(1, 4) }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(colors.container)
            .then(colors.border?.let { Modifier.border(2.dp, it, shape) } ?: Modifier)
            .focusRing(highlight, colors.content, shape, motion)
            .testTag("timeline_event_${event.id}")
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description; role = Role.Button }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, color = colors.content, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DayColumn(
    day: LocalDate, events: List<CalendarEvent>, zone: ZoneId, minMinutes: Int, hour: Dp, minBlockWidth: Dp, tint: Boolean,
    f: Formats, compact: CompactTimes, style: TextStyle, highlighted: String?, motion: Boolean, modifier: Modifier,
    onOpen: (CalendarEvent) -> Unit, onNew: (LocalDate) -> Unit, onOverflow: (LocalDate, List<CalendarEvent>) -> Unit,
) {
    val p = LocalPalette.current
    val addLabel = stringResource(R.string.action_add_event)
    val clusters = remember(events, day, zone, minMinutes) { packTimeline(events, day, zone, minMinutes) }
    BoxWithConstraints(
        modifier.then(if (tint) Modifier.background(p.ink.copy(alpha = if (p.dark) 0.04f else 0.03f)) else Modifier)
            .clickable(onClickLabel = addLabel, role = Role.Button) { onNew(day) },
    ) {
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
                        b.event, day, zone, f, compact, style, highlighted == b.event.key(), motion, b.end - b.start,
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
    }
}

/** The time shown inside a block: a compact range for one-day events, the usual start or end label otherwise. */
private fun blockTime(context: android.content.Context, event: CalendarEvent, day: LocalDate, zone: ZoneId, f: Formats, compact: CompactTimes): String {
    val span = event.span(zone)
    if (span.multiDay) return context.timeLabel(event, day, zone, f)
    val start = Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalTime()
    if (event.endMillis <= event.startMillis) return compact.time(start)
    return compact.range(start, Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalTime())
}

/**
 * A timed event as a solid block, read from the top: the title, any teacher absence, the time, then the rooms. The
 * title and absence take priority in narrow columns; a compact one-line time follows when space permits. The rest
 * of the title and the rooms fill what is left. Missed periods get a bar on the start edge covering the missed time. The spoken
 * description always has the full title, absence, time and rooms.
 */
@Composable
private fun EventBlock(
    event: CalendarEvent, day: LocalDate, zone: ZoneId, f: Formats, compact: CompactTimes, style: TextStyle,
    highlight: Boolean, motion: Boolean, shownMinutes: Int, modifier: Modifier, onClick: () -> Unit,
) {
    val context = LocalContext.current
    val typography = MaterialTheme.typography
    val title = event.displayTitle().ifBlank { stringResource(R.string.untitled) }
    val time = remember(event, day, zone, compact) { blockTime(context, event, day, zone, f, compact) }
    val status = absenceStatus(event, f, compact)
    val spokenStatus = absenceStatus(event, f)
    val rooms = remember(event.location) { compactRooms(event.location) }
    val description = listOfNotNull(title, spokenStatus, context.timeLabel(event, day, zone, f), event.location.takeIf { it.isNotBlank() })
        .joinToString(", ")
    val colors = eventColors(event)
    val shape = RoundedCornerShape(6.dp)
    val missed = remember(event, day, zone, shownMinutes) {
        val origin = maxOf(event.startMillis, day.atStartOfDay(zone).toInstant().toEpochMilli())
        val total = shownMinutes.coerceAtLeast(1) * 60_000f
        event.partialAbsences().map { (start, end) ->
            ((start - origin) / total).coerceIn(0f, 1f) to ((end - origin) / total).coerceIn(0f, 1f)
        }.filter { it.second > it.first }
    }
    val bar = 4.dp
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier.padding(1.dp).clip(shape).background(colors.container)
            .then(colors.border?.let { Modifier.border(2.dp, it, shape) } ?: Modifier)
            .then(if (missed.isEmpty()) Modifier else Modifier.drawBehind {
                val width = bar.toPx()
                missed.forEach { (from, to) ->
                    drawRect(colors.content, Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, from * size.height),
                        Size(width, (to - from) * size.height))
                }
            })
            .focusRing(highlight, colors.content, shape, motion)
            .testTag("timeline_event_${event.id}")
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description; role = Role.Button },
    ) {
        val density = LocalDensity.current
        val wide = maxWidth >= 96.dp
        val titleStyle = (if (wide) typography.labelLarge else style)
            .copy(fontWeight = FontWeight.SemiBold, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph,
                localeList = LocaleList(f.locale.toLanguageTag()))
        val detailStyle = if (wide) typography.labelMedium else typography.labelSmall
        val statusStyle = detailStyle.copy(fontWeight = FontWeight.Bold)
        val padH = if (maxWidth < 72.dp) 3.dp else 5.dp
        val padStart = padH + if (missed.isEmpty()) 0.dp else bar
        val padV = 3.dp
        val innerWidth = with(density) { (maxWidth - padH - padStart).roundToPx().coerceAtLeast(1) }
        val innerHeight = with(density) { (maxHeight - padV * 2).toPx() }
        fun measureText(text: String, textStyle: TextStyle) = measurer.measure(text, textStyle, constraints = Constraints(maxWidth = innerWidth))
        val titleLayout = remember(title, titleStyle, innerWidth) { measureText(title, titleStyle) }
        val shownTime = remember(time, event.startMillis, zone, compact, detailStyle, innerWidth, wide) {
            if (wide || measureText(time, detailStyle).lineCount == 1) time
            else if (event.span(zone).multiDay) ""
            else compact.time(Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalTime())
                .takeIf { measureText(it, detailStyle).lineCount == 1 }.orEmpty()
        }
        val timeLayout = remember(shownTime, detailStyle, innerWidth) { measureText(shownTime, detailStyle) }
        val statusLayout = remember(status, statusStyle, innerWidth) { status?.let { measureText(it, statusStyle) } }
        val roomLayout = remember(rooms, detailStyle, innerWidth) { rooms.takeIf { it.isNotEmpty() }?.let { measureText(it, detailStyle) } }
        var left = innerHeight
        fun take(lines: Int, height: Float): Int {
            val line = height / lines.coerceAtLeast(1)
            val n = minOf(lines, (left / line).toInt()).coerceAtLeast(0)
            left -= n * line
            return n
        }
        val firstTitle = maxOf(1, take(1, titleLayout.size.height.toFloat() / titleLayout.lineCount.coerceAtLeast(1)))
        val statusLines = statusLayout?.let { take(it.lineCount, it.size.height.toFloat()) } ?: 0
        val titleLine = titleLayout.size.height.toFloat() / titleLayout.lineCount.coerceAtLeast(1)
        val priorityTitle = minOf(if (wide) 1 else 2, titleLayout.lineCount - firstTitle)
        var titleLines = firstTitle + take(priorityTitle, titleLine * priorityTitle)
        val timeLines = if (shownTime.isEmpty()) 0 else take(timeLayout.lineCount, timeLayout.size.height.toFloat())
        titleLines += take(titleLayout.lineCount - titleLines, titleLine * (titleLayout.lineCount - titleLines))
        val roomLines = roomLayout?.let { take(minOf(2, it.lineCount), it.size.height.toFloat() / it.lineCount.coerceAtLeast(1) * minOf(2, it.lineCount)) } ?: 0
        Column(Modifier.fillMaxSize().padding(start = padStart, end = padH, top = padV, bottom = padV)) {
            Text(title, Modifier.fillMaxWidth(), color = colors.content, style = titleStyle, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
            if (status != null && statusLines > 0) Text(status, Modifier.fillMaxWidth(), color = colors.content, style = statusStyle,
                maxLines = statusLines, overflow = TextOverflow.Ellipsis)
            if (timeLines > 0) Text(shownTime, Modifier.fillMaxWidth(), color = colors.content, style = detailStyle,
                maxLines = timeLines, overflow = TextOverflow.Ellipsis)
            if (roomLines > 0) Text(rooms, Modifier.fillMaxWidth(), color = colors.content, style = detailStyle,
                maxLines = roomLines, overflow = TextOverflow.Ellipsis)
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

/**
 * The current time across the whole shown range: a thin line over every day, a stronger line and a dot in today's
 * column. It is drawn above the events and ignores touches and accessibility, like the grid lines.
 */
@Composable
private fun NowLine(column: Dp, todayIndex: Int, dayCount: Int, hourPx: Float, modifier: Modifier) {
    val p = LocalPalette.current
    val now by produceState(LocalTime.now()) {
        while (true) {
            delay(30_000)
            value = LocalTime.now()
        }
    }
    val ink = p.ink
    Spacer(
        modifier.clearAndSetSemantics { }.drawBehind {
            val y = (now.hour * 60 + now.minute) / 60f * hourPx
            val left = 0f
            val col = column.toPx()
            drawLine(ink.copy(alpha = 0.45f), Offset(startX(left), y), Offset(startX(left + col * dayCount), y), strokeWidth = 1.dp.toPx())
            val from = left + col * todayIndex
            drawLine(ink, Offset(startX(from), y), Offset(startX(from + col), y), strokeWidth = 2.dp.toPx())
            drawCircle(ink, radius = 4.dp.toPx(), center = Offset(startX(from), y))
        },
    )
}
