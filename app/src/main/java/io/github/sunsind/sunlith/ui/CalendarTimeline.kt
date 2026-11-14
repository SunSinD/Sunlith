package io.github.sunsind.sunlith.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.animation.core.animate
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull

private const val DAY_MINUTES = 24 * 60
/** Vertical inset of every event card; back-to-back events keep twice this as a visible gap. */
private val TIMELINE_BLOCK_INSET = 1.dp
/** Horizontal inset of every event card, so side-by-side cards and neighbouring days never touch. */
private val TIMELINE_CARD_SIDE_INSET = 2.dp
private val TIMELINE_CARD_SHAPE = RoundedCornerShape(8.dp)
private val TIMELINE_LABEL_PADDING = 3.dp

internal fun timelineMinimumBlockHeight(titleLine: Dp, density: Density): Dp = with(density) {
    val pixels = ceil(titleLine.toPx()).toInt() + (TIMELINE_BLOCK_INSET.roundToPx() + TIMELINE_LABEL_PADDING.roundToPx()) * 2
    maxOf(24.dp, pixels.toDp())
}

/** Hour shown at the top when a date that is not today opens with no timed events. */
private const val DEFAULT_START_HOUR = 7

internal fun CalendarEvent.isTimelineBanner(): Boolean = allDay || endMillis - startMillis >= 86_400_000L

/**
 * One timed event on one day, in minutes from local midnight, with its column inside its overlap group. [span] counts
 * its own column plus the following columns that no block overlapping it uses, so the card can widen into free room.
 */
internal class TimelineBlock(val event: CalendarEvent, val start: Int, val end: Int, val realEnd: Int = end) {
    var col = 0
    var cols = 1
    var span = 1

    /**
     * Day only: every event that shares one compact card with this one, in time order and including this one, or
     * null when the event has its own card. All members share one lane, one painted [end] and one [span].
     */
    var group: List<TimelineBlock>? = null

    /** Where this event's card starts on screen: its own start, or the first start of its compact group. */
    val cardStart: Int get() = group?.first()?.start ?: start
}

/** The event whose title a compact group shows: the longest real event, the earliest one when they tie. */
internal fun timelineGroupHead(group: List<TimelineBlock>): TimelineBlock = group.maxWith(compareBy<TimelineBlock> { it.realEnd - it.start })

/**
 * A run of events that overlap each other on screen, directly or through a chain. They share the day width in
 * [cols] columns. [start] and [end] are the minutes the run covers on screen.
 */
internal class TimelineCluster(val blocks: List<TimelineBlock>, val cols: Int) {
    val start: Int = blocks.minOf { it.start }
    val end: Int = blocks.maxOf { it.end }
}

private val timelineOrder = compareBy<TimelineBlock> { it.start }.thenByDescending { it.end }.thenBy { it.event.title.lowercase() }

/**
 * Keeps exact local starts and ends. Lanes come from real overlaps only, never from the room a title needs.
 *
 * Without [readableLabels] (Week and Continuous), a label extends only into free time before the next start or
 * midnight, so events one after another never become side by side columns.
 *
 * Day's [readableLabels] gives every card at least one measured title line ([minMinutes]). When the next event in
 * the same real lane starts before that line ends (a 1 minute event at 18:00, then others at 18:15 and 18:20), those
 * events share one compact card in that lane: it starts at the first real start, is as tall as one title line or
 * the real span of its events when that is longer, and shows one title with a count of the others. Events after
 * the card keep their own cards at their real times, and genuinely simultaneous events keep separate lanes.
 */
internal fun packTimeline(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId, minMinutes: Int, readableLabels: Boolean = false): List<TimelineCluster> {
    val blocks = events.map { e ->
        val s = Instant.ofEpochMilli(e.startMillis).atZone(zone)
        val en = Instant.ofEpochMilli(max(e.endMillis, e.startMillis)).atZone(zone)
        val start = if (s.toLocalDate().isBefore(day)) 0 else s.hour * 60 + s.minute
        val end = if (en.toLocalDate().isAfter(day)) DAY_MINUTES else en.hour * 60 + en.minute
        TimelineBlock(e, start, max(end, start + 1).coerceAtMost(DAY_MINUTES))
    }.sortedWith(timelineOrder)
    val row = minMinutes.coerceAtLeast(1)
    if (readableLabels) return readableClusters(blocks, row)
    val starts = blocks.map { it.start }.distinct()
    val nextStarts = starts.zipWithNext().toMap()
    val shownBlocks = blocks.map { block ->
        val labelEnd = (block.start + row).coerceAtMost(nextStarts[block.start] ?: DAY_MINUTES)
        TimelineBlock(block.event, block.start, max(block.end, labelEnd), block.end)
    }
    return laneClusters(shownBlocks)
}

/**
 * Day packing. Each event goes in a lane that is free at its real start. A lane whose card still shows its title
 * line is used only when no lane is free on screen, and then the event joins that card instead of opening a lane
 * the real times do not need. A new lane opens only when every lane holds an event that really overlaps.
 * Cards may extend below midnight to finish their title line.
 */
private fun readableClusters(blocks: List<TimelineBlock>, row: Int): List<TimelineCluster> {
    class Card(val col: Int, first: TimelineBlock) {
        val members = mutableListOf(first)
        var realEnd = first.end
        val start get() = members.first().start
        val end get() = max(start + row, realEnd)
        var span = 1
    }
    val clusters = mutableListOf<TimelineCluster>()
    val cards = mutableListOf<Card>()
    val lanes = mutableListOf<Card>()
    var clusterEnd = -1
    fun close() {
        if (cards.isEmpty()) return
        val cols = lanes.size
        cards.forEach { card ->
            while (card.col + card.span < cols &&
                cards.none { it.col == card.col + card.span && it.start < card.end && card.start < it.end }) card.span++
        }
        val shown = cards.flatMap { card ->
            val members = card.members.map { b ->
                TimelineBlock(b.event, b.start, card.end, b.end).also { it.col = card.col; it.cols = cols; it.span = card.span }
            }
            if (members.size > 1) members.forEach { it.group = members }
            members
        }.sortedWith(timelineOrder)
        clusters += TimelineCluster(shown, cols)
        cards.clear()
        lanes.clear()
    }
    for (b in blocks) {
        if (cards.isNotEmpty() && b.start >= clusterEnd) close()
        val free = lanes.indices.filter { lanes[it].realEnd <= b.start }
        val open = free.firstOrNull { lanes[it].end <= b.start }
        val card = when {
            open != null -> Card(open, b).also { lanes[open] = it; cards += it }
            free.isNotEmpty() -> lanes[free.minBy { lanes[it].end }].also { it.members += b; it.realEnd = max(it.realEnd, b.end) }
            else -> Card(lanes.size, b).also { lanes += it; cards += it }
        }
        clusterEnd = max(clusterEnd, card.end)
    }
    close()
    return clusters
}

/** Real overlap groups, each with the fewest columns, reusing a column as soon as its last event ends. */
private fun laneClusters(shownBlocks: List<TimelineBlock>): List<TimelineCluster> {
    val clusters = mutableListOf<TimelineCluster>()
    var current = mutableListOf<TimelineBlock>()
    val columnEnds = mutableListOf<Int>()
    var clusterEnd = -1
    fun close() {
        if (current.isEmpty()) return
        val cols = columnEnds.size
        val blocks = current
        blocks.forEach { b ->
            b.cols = cols
            while (b.col + b.span < cols && blocks.none { it.col == b.col + b.span && it.start < b.end && b.start < it.end }) b.span++
        }
        clusters += TimelineCluster(current, cols)
        current = mutableListOf()
        columnEnds.clear()
    }
    for (b in shownBlocks) {
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

/**
 * How one overlap group shares a day column: [shown] event lanes of width [lane], then [chooser] width for "+N".
 * [stacked] means not even two lanes fit, so the whole group is one card that lists its events.
 */
internal class OverflowLanes(val shown: Int, val lane: Dp, val chooser: Dp) {
    val stacked: Boolean get() = shown == 0
}

/**
 * Lanes that fit at [minLane] each. When the group needs more, the last lane becomes the chooser. When a column fits
 * only one lane, side-by-side slivers would show a letter or two, so the group becomes one full-width stacked card.
 */
internal fun overflowLanes(width: Dp, minLane: Dp, cols: Int): OverflowLanes {
    val fit = (width / minLane).toInt().coerceAtLeast(1)
    if (cols <= fit) return OverflowLanes(cols, width / cols, 0.dp)
    if (fit >= 2) return OverflowLanes(fit - 1, width / fit, width / fit)
    return OverflowLanes(0, 0.dp, width)
}

/**
 * True for a narrow card that shares its day column with other cards. Its title and time are centered, so a title
 * that wraps to two short lines stays balanced. Cards that use the whole column keep start aligned text.
 */
internal fun timelineCentersLabel(card: Dp, column: Dp): Boolean = card < column - 1.dp && card < 96.dp

/** Narrowest lane that still shows the start of a word or two at the user's text size. */
internal fun timelineMinLaneWidth(titleSize: Dp): Dp = maxOf(32.dp, titleSize * 2.6f)

internal const val TIMELINE_ZOOM_MIN = 50
internal const val TIMELINE_ZOOM_MAX = 200

/** Zoom changes the time scale, never the user's font size. */
internal fun timelineHourHeight(zoomPercent: Int): Dp = timelineHourHeight(zoomPercent.coerceIn(TIMELINE_ZOOM_MIN, TIMELINE_ZOOM_MAX).toFloat())

/** The live scale while pinching, which may briefly pass the saved range. */
internal fun timelineHourHeight(zoomPercent: Float): Dp = 64.dp * (zoomPercent.coerceAtLeast(10f) / 100f)

/**
 * Pinch scale with a soft rubber band past the saved range: the limit is felt, then [settledZoomPercent] springs back.
 */
internal fun pinchZoomPercent(startPercent: Float, scale: Float): Float {
    val raw = startPercent * scale
    val min = TIMELINE_ZOOM_MIN.toFloat()
    val max = TIMELINE_ZOOM_MAX.toFloat()
    return when {
        raw > max -> max + (raw - max) * PINCH_RUBBER_BAND
        raw < min -> min - (min - raw) * PINCH_RUBBER_BAND
        else -> raw
    }.coerceIn(min * 0.75f, max * 1.25f)
}

private const val PINCH_RUBBER_BAND = 0.3f

/** The start value that [pinchZoomPercent] turns back into [live] at scale 1, so a new finger pair never jumps. */
internal fun unbandedZoomPercent(live: Float): Float {
    val min = TIMELINE_ZOOM_MIN.toFloat()
    val max = TIMELINE_ZOOM_MAX.toFloat()
    return when {
        live > max -> max + (live - max) / PINCH_RUBBER_BAND
        live < min -> min - (min - live) / PINCH_RUBBER_BAND
        else -> live
    }
}

/** A released pinch settles inside the saved range, in 5% steps. */
internal fun settledZoomPercent(live: Float): Int =
    ((live.coerceIn(TIMELINE_ZOOM_MIN.toFloat(), TIMELINE_ZOOM_MAX.toFloat()) / 5f).roundToInt() * 5).coerceIn(TIMELINE_ZOOM_MIN, TIMELINE_ZOOM_MAX)

/** The scroll offset that keeps [minute] under [focalY], kept inside the content like the scroll state would. */
internal fun focalScroll(axis: TimelineAxis, minute: Float, focalY: Float, contentHeight: Float, viewportHeight: Float): Float =
    (axis.minuteToY(minute) - focalY).coerceIn(0f, (contentHeight - viewportHeight).coerceAtLeast(0f))

/**
 * Below this width a multi-day timeline scrolls sideways inside its page. At normal text sizes a seven-day week uses
 * compact columns, so every day fits and a drag never reveals part of the next week. Large text keeps wide columns.
 */
internal fun timelineOverflowThreshold(dayCount: Int, titleSize: Dp, fontScale: Float = 1f): Dp = when {
    dayCount <= 5 && fontScale <= 1.15f -> maxOf(40.dp, titleSize * 3.2f)
    fontScale <= 1.15f -> timelineCompactColumn(titleSize)
    dayCount <= 5 -> maxOf(48.dp, titleSize * 4f)
    else -> maxOf(60.dp, titleSize * 5f)
}

/** Narrowest seven-day column at normal text sizes: room for a hyphenated word and the date circle's digits. */
internal fun timelineCompactColumn(titleSize: Dp): Dp = maxOf(30.dp, titleSize * 2.4f)

internal fun timelineTodayDirection(axis: TimelineAxis, minute: Int, scrollY: Int, viewportHeight: Int): TodayDirection? {
    if (viewportHeight <= 0) return null
    val y = axis.minuteToY(minute)
    return when {
        y < scrollY -> TodayDirection.Up
        y > scrollY + viewportHeight -> TodayDirection.Down
        else -> null
    }
}

/** The zoom shown while two fingers pinch or the release springs back; null when the saved zoom applies. */
@Stable
internal class TimelineZoom {
    var live by mutableStateOf<Float?>(null)
    var settle: Job? = null
    /** The saved value the running [settle] lands on, known before the saved state recomposes. */
    var target: Int? = null
    /** True while a two-finger gesture owns the timeline, false during the spring. */
    var pinching by mutableStateOf(false)
}

/**
 * Two-finger pinch on the vertical distance between the fingers. One finger is never consumed, so page swipes, vertical
 * scrolling and taps behave as before. Two fingers must deliberately change their separation beyond twice the system
 * touch slop and 8% of their starting span. Multi-touch cancels event clicks; only an active pinch drives the scale.
 * The minute under the fingers stays under them, the
 * value is saved on release, then the scale springs to it with a little bounce. A new finger pair continues from the
 * current scale. A touch during the spring, or a cancelled gesture, lands on the saved value without a remap.
 */
internal fun Modifier.timelinePinch(
    zoom: TimelineZoom,
    scroll: ScrollState,
    scope: CoroutineScope,
    motion: Boolean,
    saved: () -> Int,
    minuteAt: (percent: Float, contentY: Float) -> Float,
    scrollFor: (percent: Float, minute: Float, focalY: Float) -> Float,
    onCommit: (Int) -> Unit,
): Modifier = pointerInput(zoom, scroll, scope, motion) {
    val minSpan = 48.dp.toPx()
    val spanSlop = viewConfiguration.touchSlop * 2f
    fun follow(percent: Float, minute: Float, focalY: Float) {
        scroll.dispatchRawDelta(scrollFor(percent, minute, focalY) - scroll.value)
    }
    // Shows the saved scale for one frame while still live, so clearing it changes neither the axis nor the scroll.
    fun land(target: Int, minute: Float, focalY: Float) {
        val value = target.toFloat()
        zoom.target = target
        zoom.live = value
        val placed = scrollFor(value, minute, focalY)
        scroll.dispatchRawDelta(placed - scroll.value)
        zoom.settle = scope.launch {
            withFrameNanos { }
            // A just committed zoom may have regrouped empty hours in that frame. Shift by that change only, so a
            // drag that already started keeps its own movement.
            scroll.dispatchRawDelta(scrollFor(value, minute, focalY) - placed)
            zoom.live = null
        }
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (zoom.settle?.isActive == true) {
            zoom.settle?.cancel()
            zoom.settle = null
            zoom.live?.let { live ->
                land(zoom.target ?: saved(), minuteAt(live, scroll.value + down.position.y), down.position.y)
            }
        }
        var pinching = false
        var pair: Pair<PointerId, PointerId>? = null
        var startSpan = 1f
        var startPercent = 0f
        var minute = 0f
        var focalY = 0f
        var released = false
        var hadMultiplePointers = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    if (!hadMultiplePointers) {
                        zoom.pinching = true
                        scope.launch { scroll.stopScroll() }
                    }
                    hadMultiplePointers = true
                    val a = pressed[0]
                    val b = pressed[1]
                    val span = maxOf(abs(a.position.y - b.position.y), minSpan)
                    focalY = (a.position.y + b.position.y) / 2f
                    val ids = a.id to b.id
                    if (ids != pair) {
                        // A new pair keeps the current scale and takes the minute now under its own midpoint.
                        pair = ids
                        val current = zoom.live ?: saved().toFloat()
                        startPercent = unbandedZoomPercent(current)
                        startSpan = span
                        minute = minuteAt(current, scroll.value + focalY)
                    } else {
                        if (!pinching) {
                            if (abs(span - startSpan) <= maxOf(spanSlop, startSpan * 0.08f)) {
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            pinching = true
                            zoom.pinching = true
                            zoom.settle?.cancel()
                            zoom.settle = null
                            scope.launch { scroll.stopScroll() }
                            // Activation starts at the current fingers without applying the ignored jitter as a jump.
                            startSpan = span
                            minute = minuteAt(saved().toFloat(), scroll.value + focalY)
                        }
                        val percent = pinchZoomPercent(startPercent, span / startSpan)
                        zoom.live = percent
                        follow(percent, minute, focalY)
                    }
                }
                // After a pinch, the finger left on screen must not turn into a scroll or a tap.
                if (hadMultiplePointers) event.changes.forEach { it.consume() }
            }
            released = true
        } finally {
            zoom.pinching = false
            // Detached or restarted input: save what the fingers showed instead of leaving a stale live scale.
            if (!released && pinching) zoom.live?.let { live ->
                val target = settledZoomPercent(live)
                onCommit(target)
                if (scope.isActive) {
                    land(target, minute, focalY)
                } else zoom.live = null
            }
        }
        val from = zoom.live
        if (!pinching || from == null) return@awaitEachGesture
        val target = settledZoomPercent(from)
        onCommit(target)
        zoom.target = target
        val anchor = minute
        val y = focalY
        zoom.settle = scope.launch {
            // The committed zoom can regroup empty hours. Hold the focal minute on that composition before moving.
            withFrameNanos { }
            follow(from, anchor, y)
            if (motion && from != target.toFloat()) {
                animate(from, target.toFloat(), animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                    zoom.live = value
                    follow(value, anchor, y)
                }
            } else {
                zoom.live = target.toFloat()
                withFrameNanos { }
                follow(target.toFloat(), anchor, y)
            }
            zoom.live = null
        }
    }
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
 * Reloads preserve the viewed time. [tintToday] gives today's hour tiles a stronger tint. With [onZoomChange], two fingers
 * pinch the hour height (see [timelinePinch]); [onSelectDay] null keeps the date headers read-only.
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
    onSelectDay: ((LocalDate) -> Unit)?,
    onOpen: (CalendarEvent) -> Unit,
    onNew: (LocalDate) -> Unit,
    onOverflow: (LocalDate, List<CalendarEvent>) -> Unit,
    modifier: Modifier = Modifier,
    focus: TimelineFocus? = null,
    onFocusHandled: (TimelineFocus) -> Unit = {},
    tintToday: Boolean = true,
    allowHorizontalScroll: Boolean = false,
    groupEmptyHours: Boolean = false,
    zoomPercent: Int = 100,
    sharedMinute: MutableFloatState? = null,
    handledTodayRequest: MutableIntState? = null,
    onDirectionChanged: (TodayDirection?) -> Unit = {},
    onZoomChange: ((Int) -> Unit)? = null,
    fallback: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val gridColors = rememberTimelineGridColors()
    val density = LocalDensity.current
    val zone = ZoneId.systemDefault()
    val typography = MaterialTheme.typography
    val railStyle = typography.labelSmall
    val titleStyle = if (days.size == 1) typography.bodyMedium else typography.labelMedium
    // Scale the actual text size before multiplying; sp conversion is non-linear at large font sizes.
    val titleSize = with(density) { titleStyle.fontSize.toDp() }
    val titleLine = with(density) { titleStyle.lineHeight.toDp() }
    val zoom = remember { TimelineZoom() }
    val liveZoom = zoom.live
    val hour = if (liveZoom != null) timelineHourHeight(liveZoom) else timelineHourHeight(zoomPercent)
    val readableLabels = days.size == 1 && !showHeaders
    val labelMeasurer = rememberTextMeasurer()
    val measuredTitle = with(density) {
        labelMeasurer.measure("Ag", titleStyle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1).size.height.toDp()
    }
    val minBlock = timelineMinimumBlockHeight(maxOf(titleLine, measuredTitle), density)
    // Packing follows the saved zoom, so short-event rows and grouped hours stay put while two fingers pinch.
    val minMinutes = ceil(minBlock / timelineHourHeight(zoomPercent) * 60f).toInt()
    val minColumn = if (days.size > 5 && density.fontScale <= 1.15f) timelineCompactColumn(titleSize) else maxOf(40.dp, titleSize * 3.2f)
    val overflowThreshold = timelineOverflowThreshold(days.size, titleSize, density.fontScale)
    val readableColumn = maxOf(80.dp, titleSize * 6.5f)
    val minBlockWidth = timelineMinLaneWidth(titleSize)
    val compact = remember(f) { CompactTimes(f.locale, f.is24Hour, f::range) }
    val hourLabels = remember(f, compact) {
        (0..24).map { hour ->
            if (hour == 24 && f.is24Hour) String.format(f.locale, "%02d:%02d", 24, 0)
            else compact.time(LocalTime.of(hour % 24, 0))
        }
    }
    val allDayLabel = stringResource(R.string.all_day)
    val emptyHoursLabel = stringResource(R.string.timeline_empty_hours)
    val dayEvents = days.map(eventsOn)
    val allDay = dayEvents.map { events -> events.filter { it.isTimelineBanner() } }
    val timed = dayEvents.map { events -> events.filter { !it.isTimelineBanner() } }
    val shortEvents = remember(timed, days, zone, minMinutes, readableLabels) {
        if (readableLabels) emptyList() else days.mapIndexed { i, day -> shortTimelineEvents(timed[i], day, zone, minMinutes) }
    }
    val hourPx = with(density) { hour.toPx() }
    val gapPx = with(density) { maxOf(48.dp, titleLine * 3f).toPx() }
    val packed = remember(days, timed, zone, minMinutes, readableLabels) {
        days.mapIndexed { index, day -> packTimeline(timed[index], day, zone, minMinutes, readableLabels) }
    }
    val occupied = remember(packed, groupEmptyHours) {
        if (!groupEmptyHours) emptyList() else packed.flatten().flatMap { cluster ->
            cluster.blocks.map { it.cardStart to it.end.coerceAtMost(DAY_MINUTES) }
        }
    }
    fun axisFor(px: Float) = TimelineAxis(px, occupied, groupEmpty = groupEmptyHours, gapPx = gapPx)
    val axis = remember(occupied, hourPx, gapPx, groupEmptyHours) { axisFor(hourPx) }
    val minBlockPx = with(density) { minBlock.toPx() }
    val bottomRoomPx = with(density) { if (readableLabels) 104.dp.toPx() else 0f }
    fun contentHeightPx(a: TimelineAxis): Float {
        // Packing rounds the label up to whole minutes; reserve its actual rounded pixel height. A compact group
        // reserves one label from its first start, like any other card.
        fun below(start: Int, height: Int) = (a.minuteToY(start).roundToInt() + height - a.totalHeight).coerceAtLeast(0f)
        val labelHeight = maxOf(minBlockPx, minMinutes * a.hourPx / 60f).roundToInt()
        val footer = if (readableLabels) packed.single().maxOfOrNull { cluster ->
            cluster.blocks.maxOf { below(it.cardStart, labelHeight) }
        } ?: 0f else 0f
        // Let the last label scroll above the floating Add and Today controls.
        return a.totalHeight + footer + bottomRoomPx
    }
    // While pinching, the content keeps its largest height, so scrolling to the focal minute is never clamped early.
    val timelineHeight = with(density) {
        (if (liveZoom == null) contentHeightPx(axis)
        else maxOf(contentHeightPx(axis), contentHeightPx(axisFor(timelineHourHeight(TIMELINE_ZOOM_MAX * 1.25f).toPx())))).toDp()
    }
    val zoomAxis by rememberUpdatedState { percent: Float -> axisFor(with(density) { timelineHourHeight(percent).toPx() }) }
    val zoomHeight by rememberUpdatedState { a: TimelineAxis -> contentHeightPx(a) }
    val savedZoom by rememberUpdatedState(zoomPercent)
    val commitZoom by rememberUpdatedState(onZoomChange)
    val pinchScope = rememberCoroutineScope()
    val reportDirection by rememberUpdatedState(onDirectionChanged)
    // One clock for the line, the dot, the rail pill and the Today direction.
    val currentMinute by rememberCurrentMinute()
    LaunchedEffect(scroll, autoScroll, days, today, axis, currentMinute) {
        if (!autoScroll) return@LaunchedEffect
        if (today !in days) {
            reportDirection(null)
            return@LaunchedEffect
        }
        snapshotFlow { timelineTodayDirection(axis, currentMinute, scroll.value, scroll.viewportSize) }
            .distinctUntilChanged().collect { reportDirection(it) }
    }
    var previousAxis by remember(scroll) { mutableStateOf(axis) }
    var pendingAxisMinute by remember(scroll) { mutableStateOf<Float?>(null) }
    SideEffect {
        if (liveZoom != null) {
            // The pinch keeps its own focal minute in place; a remap would hide the grid and fight the fingers.
            previousAxis = axis
        } else if (pendingAxisMinute == null && (previousAxis.gaps != axis.gaps || previousAxis.hourPx != axis.hourPx)) {
            // A shorter grid can clamp ScrollState during measure. Read its old minute first.
            pendingAxisMinute = previousAxis.yToMinute(scroll.value.toFloat())
        }
    }
    LaunchedEffect(axis, scroll) {
        if (pendingAxisMinute != null) {
            awaitScrollable(scroll)
            withFrameNanos { }
            pendingAxisMinute?.let { minute ->
                val target = axis.minuteToY(minute).coerceIn(0f, scroll.maxValue.toFloat())
                scroll.dispatchRawDelta(target - scroll.value)
            }
            pendingAxisMinute = null
        }
        previousAxis = axis
    }
    var sharedPositioned by remember {
        mutableStateOf(sharedMinute == null || (scroll.value == 0 && sharedMinute.floatValue == 0f))
    }
    LaunchedEffect(scroll) {
        if (!sharedPositioned) {
            try {
                if (awaitScrollable(scroll)) {
                    withFrameNanos { }
                    scroll.scrollTo(axis.minuteToY(sharedMinute!!.floatValue).roundToInt())
                }
            } finally { sharedPositioned = true }
        }
    }
    LaunchedEffect(scroll, autoScroll, axis, sharedMinute) {
        if (sharedMinute == null) return@LaunchedEffect
        if (autoScroll) {
            snapshotFlow { if (sharedPositioned && previousAxis === axis) axis.yToMinute(scroll.value.toFloat()) else null }
                .collect { if (it != null) sharedMinute.floatValue = it }
        } else {
            snapshotFlow { sharedMinute.floatValue }.collect { minute ->
                if (awaitScrollable(scroll)) {
                    withFrameNanos { }
                    scroll.scrollTo(axis.minuteToY(minute).roundToInt())
                }
            }
        }
    }
    val gutterLabels = if (allDay.any { it.isNotEmpty() }) {
        hourLabels + listOf(allDayLabel.substringBeforeLast(' '), allDayLabel.substringAfterLast(' '))
    } else hourLabels
    val reportFocus by rememberUpdatedState(onFocusHandled)
    var positionedFocus by remember { mutableStateOf<TimelineFocus?>(null) }
    val focusPending = autoScroll && focus != null && focus.date in days && positionedFocus !== focus
    if (focusPending) {
        val request = requireNotNull(focus)
        val target = focusTarget(eventsOn(request.date), request.date, zone)
        val minute = if (target != null && !target.isTimelineBanner())
            (startMinuteOn(target, request.date, zone) - 30).coerceAtLeast(0) else 0
        SideEffect {
            // New date pages use a fresh ScrollState. Prime it before measurement instead of
            // drawing midnight and animating through unrelated events on the way to the target.
            scroll.dispatchRawDelta(axis.minuteToY(minute) - scroll.value)
        }
    }
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
            var initialPositioned by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(scroll, autoScroll, axis) {
                if (initialPositioned) return@LaunchedEffect
                try {
                    if (autoScroll && showHeaders && scroll.value == 0 && focus == null && awaitScrollable(scroll)) {
                        val anchor = if (today in days) (currentMinute / 60 - 1).coerceAtLeast(0) else DEFAULT_START_HOUR
                        scroll.scrollTo(axis.minuteToY(anchor * 60).roundToInt().coerceIn(0, scroll.maxValue))
                    }
                } finally {
                    if (coroutineContext.isActive) initialPositioned = true
                }
            }
            val handled = handledTodayRequest ?: remember { mutableIntStateOf(todayRequest) }
            val pinching = zoom.pinching
            val settling = liveZoom != null
            LaunchedEffect(todayRequest, handled.intValue, autoScroll, scroll, axis, focus, pinching, settling) {
                // A focus request or the user's own pinch takes over, and Today does not replay after it.
                if (autoScroll && (focus != null || pinching)) {
                    handled.intValue = todayRequest
                    return@LaunchedEffect
                }
                // A Today tap during the spring waits for the saved scale instead of fighting it.
                if (settling) return@LaunchedEffect
                if (todayRequest == handled.intValue || !autoScroll || today !in days) return@LaunchedEffect
                var touchInterrupted = false
                try {
                    if (!awaitScrollable(scroll)) return@LaunchedEffect
                    withFrameNanos { }
                    scroll.stopScroll()
                    val target = axis.minuteToY((currentMinute / 60 - 1).coerceAtLeast(0) * 60).roundToInt()
                        .coerceIn(0, scroll.maxValue)
                    if (target != scroll.value) {
                        if (!motion) scroll.scrollTo(target)
                        else {
                            val animation = coroutineContext[kotlinx.coroutines.Job]!!
                            val interruption = launch {
                                scroll.interactionSource.interactions.first { it is DragInteraction.Start }
                                touchInterrupted = true
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
                } finally {
                    if (coroutineContext.isActive || touchInterrupted) handled.intValue = todayRequest
                }
            }
            LaunchedEffect(focus, autoScroll, axis) {
                val request = focus ?: return@LaunchedEffect
                if (!autoScroll || request.date !in days) return@LaunchedEffect
                request.start()
                try {
                    val target = focusTarget(eventsOn(request.date), request.date, zone)
                    val minute = if (target != null && !target.isTimelineBanner())
                        (startMinuteOn(target, request.date, zone) - 30).coerceAtLeast(0) else 0
                    if (awaitScrollable(scroll)) {
                        val y = axis.minuteToY(minute).roundToInt().coerceIn(0, scroll.maxValue)
                        scroll.scrollTo(y)
                        // Completed focus owns this position, including a concurrent axis change.
                        pendingAxisMinute = null
                        previousAxis = axis
                    }
                    positionedFocus = request
                    highlighted = target?.key()
                } finally { if (coroutineContext.isActive) reportFocus(request) }
            }
            // Only the hour grid waits for its scroll position; the dates above it stay visible and current while paging.
            Column(Modifier.fillMaxSize()) {
                if (showHeaders) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Spacer(Modifier.width(gutter))
                        Row(Modifier.weight(1f).testTag("calendar_timeline_headers").then(dayArea)) {
                            days.forEach { day ->
                                TimelineDayHeader(day, day == selected, day == today, eventsOn(day).size, f, Modifier.width(column),
                                    todayTint = gridColors.today, onClick = onSelectDay?.let { select -> { select(day) } })
                            }
                        }
                    }
                }
                if (!readableLabels && shortEvents.any { it.isNotEmpty() }) {
                    Row(Modifier.fillMaxWidth().height(48.dp).testTag("calendar_timeline_short_events")) {
                        if (days.size > 1) Spacer(Modifier.width(gutter))
                        Row(Modifier.weight(1f).then(dayArea)) {
                            days.forEachIndexed { i, day ->
                                Box(if (days.size > 1) Modifier.width(column) else Modifier.fillMaxWidth()) {
                                    if (shortEvents[i].isNotEmpty()) TimelineShortEventButton(shortEvents[i].size, days.size == 1) {
                                        onOverflow(day, shortEvents[i])
                                    }
                                }
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
                val todayIndex = days.indexOf(today)
                val tintIndex = if (tintToday) todayIndex else -1
                Row(
                    Modifier.weight(1f).fillMaxWidth().testTag("calendar_timeline")
                        .graphicsLayer {
                            alpha = if (focusPending || !sharedPositioned || !initialPositioned || pendingAxisMinute != null) 0f else 1f
                        }
                        .then(if (onZoomChange == null || days.size <= 1) Modifier else Modifier.timelinePinch(
                            zoom, scroll, pinchScope, motion,
                            saved = { savedZoom },
                            minuteAt = { percent, y -> zoomAxis(percent).yToMinute(y) },
                            scrollFor = { percent, minute, focalY ->
                                val target = zoomAxis(percent)
                                focalScroll(target, minute, focalY, zoomHeight(target), scroll.viewportSize.toFloat())
                            },
                            onCommit = { commitZoom?.invoke(it) },
                        ))
                        .verticalScroll(scroll),
                ) {
                    Box(Modifier.width(gutter).height(timelineHeight)) {
                        HourRail(hourLabels, railStyle, hourPx, Modifier.fillMaxSize(), axis)
                    }
                    Box(Modifier.weight(1f).height(timelineHeight).then(dayArea)) {
                        Row(
                            Modifier.width(column * days.size).fillMaxHeight().drawBehind {
                                val col = column.toPx()
                                drawTimelineGrid(axis, col, days.size, gridColors, tintIndex)
                                // Under the events: the time stays readable and never strikes through a title.
                                if (todayIndex >= 0) drawNowLine(axis, currentMinute, col, days.size, todayIndex, gridColors)
                            },
                        ) {
                            days.forEachIndexed { index, day ->
                                DayColumn(
                                    day, timed[index], zone, minMinutes, hour, minBlockWidth, f, compact, titleStyle,
                                    highlighted, motion, Modifier.width(column).fillMaxHeight(), onOpen, onNew, onOverflow,
                                    axis = axis,
                                    readableLabels = readableLabels, minLabelHeight = minBlock,
                                )
                            }
                        }
                        axis.gaps.forEach { gap ->
                            Box(
                                Modifier.offset(y = with(density) { gap.y.toDp() }).width(column * days.size)
                                    .height(with(density) { gap.height.toDp() }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(emptyHoursLabel, Modifier.padding(horizontal = 4.dp), color = p.muted, style = railStyle,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (todayIndex >= 0) NowMarker(column, todayIndex, currentMinute, Modifier.width(column * days.size).fillMaxHeight(), axis, gridColors)
                    }
                }
            }
        }
    }
}

/** Mirrors an x distance measured from the start edge, so lines follow the Row's order in right-to-left layouts. */
private fun DrawScope.startX(fromStart: Float): Float = if (layoutDirection == LayoutDirection.Rtl) size.width - fromStart else fromStart

/** Colors of the tiled hour grid and the current time. Today's tint is separate from the selected date's filled circle. */
@Immutable
internal class TimelineGridColors(val tile: Color, val today: Color, val now: Color, val halo: Color)

internal fun timelineGridColors(p: SunlithPalette): TimelineGridColors {
    val tile = lerp(p.background, p.line, if (p.dark) 0.55f else 0.6f)
    return TimelineGridColors(
        tile = tile,
        today = lerp(tile, p.accent, if (p.dark) 0.14f else 0.09f),
        now = p.accent,
        halo = p.background,
    )
}

@Composable
internal fun rememberTimelineGridColors(): TimelineGridColors {
    val p = LocalPalette.current
    return remember(p) { timelineGridColors(p) }
}

/** Hours that get their own tile: every hour not inside a grouped empty band. */
internal fun timelineTileHours(axis: TimelineAxis): List<Int> =
    (0 until 24).filter { h -> axis.gaps.none { h * 60 >= it.startMinute && (h + 1) * 60 <= it.endMinute } }

/**
 * Rounded hour tiles drawn in one pass, so a week adds no composable per cell. The gaps between tiles replace grid
 * lines; a grouped empty band is one softer tile per day.
 */
internal fun DrawScope.drawTimelineGrid(axis: TimelineAxis, columnWidthPx: Float, dayCount: Int, colors: TimelineGridColors, todayIndex: Int = -1) {
    val inset = 1.dp.toPx()
    val corner = 6.dp.toPx()
    val width = columnWidthPx - inset * 2
    if (width <= 0f) return
    val hours = timelineTileHours(axis)
    fun tile(color: Color, x: Float, top: Float, bottom: Float) {
        if (bottom <= top) return
        drawRoundRect(color, Offset(x, top), Size(width, bottom - top), CornerRadius(minOf(corner, (bottom - top) / 2f)))
    }
    for (day in 0 until dayCount) {
        val color = if (day == todayIndex) colors.today else colors.tile
        val start = day * columnWidthPx + inset
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - start - width else start
        hours.forEach { h -> tile(color, x, axis.minuteToY(h * 60) + inset, axis.minuteToY((h + 1) * 60) - inset) }
        axis.gaps.forEach { gap -> tile(color.copy(alpha = color.alpha * 0.5f), x, gap.y + inset, gap.y + gap.height - inset) }
    }
}

/** The current time under the events, confined to today's column. */
internal fun DrawScope.drawNowLine(axis: TimelineAxis, minute: Int, columnWidthPx: Float, dayCount: Int, todayIndex: Int, colors: TimelineGridColors) {
    if (todayIndex !in 0 until dayCount) return
    val y = axis.minuteToY(minute)
    val from = columnWidthPx * todayIndex
    drawLine(colors.now, Offset(startX(from), y), Offset(startX(from + columnWidthPx), y), strokeWidth = 2.dp.toPx())
}

/** Waits for measurement, including a measured area that already fits without scrolling. */
internal suspend fun awaitScrollable(scroll: ScrollState): Boolean =
    withTimeoutOrNull(1_000) {
        snapshotFlow { scroll.viewportSize > 0 && scroll.maxValue != Int.MAX_VALUE }.first { it }
        scroll.maxValue > 0
    } ?: false

@Composable
internal fun HourRail(
    labels: List<String>, style: TextStyle, hourPx: Float, modifier: Modifier,
    axis: TimelineAxis = TimelineAxis(hourPx, emptyList()),
) {
    val p = LocalPalette.current
    val endPad = with(LocalDensity.current) { 6.dp.roundToPx() }
    val markers = axis.normalHourMarkers
    val positions = markers.map(axis::minuteToY) + axis.gaps.map { it.y + it.height / 2f }
    Layout(
        content = {
            markers.forEach { minute -> Text(labels[minute / 60], color = p.muted, style = style, maxLines = 1, softWrap = false) }
            axis.gaps.forEach { gap ->
                Text("${labels[gap.startMinute / 60]}\n\u22ee\n${labels[gap.endMinute / 60]}", color = p.muted,
                    style = style, textAlign = TextAlign.End, maxLines = 3, softWrap = false)
            }
        },
        modifier = modifier.clearAndSetSemantics { },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                val y = (positions[i] - placeable.height / 2f).roundToInt()
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
internal fun TimelineDayHeader(
    day: LocalDate, isSelected: Boolean, isToday: Boolean, count: Int, f: Formats, modifier: Modifier,
    todayTint: Color? = null, onClick: (() -> Unit)?,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val description = dayDescription(context, f, day, isToday, count)
    val density = LocalDensity.current
    val narrow = density.fontScale > 1.3f
    val circle = with(density) { maxOf(40.dp, MaterialTheme.typography.bodyLarge.fontSize.toDp() * 2.25f) }
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val keyboardFocused by interaction.collectIsFocusedAsState()
    Column(
        modifier.heightIn(min = 48.dp).padding(horizontal = 1.dp)
            .then(if (isToday && todayTint != null) Modifier.background(todayTint, shape) else Modifier)
            // The focused date keeps its filled circle whether or not taps may change it. No ripple: the circle answers
            // a tap at once, and a ripple over the whole column would flash like a second selection. Keyboard focus
            // still gets an outline.
            .then(if (onClick == null) Modifier.clearAndSetSemantics { contentDescription = description; selected = isSelected }
                else Modifier.clip(shape).then(if (keyboardFocused) Modifier.border(2.dp, p.ink, shape) else Modifier)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                    .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button })
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
internal fun AllDayChip(event: CalendarEvent, day: LocalDate, f: Formats, highlight: Boolean, motion: Boolean, viewportHeight: Dp, onClick: () -> Unit) {
    val context = LocalContext.current
    val plainTitle = event.displayTitle().ifBlank { stringResource(R.string.untitled) }
    val title = if (event.todo) "\u2610 $plainTitle" else plainTitle
    val todoLabel = stringResource(R.string.todo_tag).takeIf { event.todo }
    val status = absenceStatus(event, f)
    val description = listOfNotNull(plainTitle, todoLabel, status, context.timeLabel(event, day, ZoneId.systemDefault(), f)).joinToString(", ")
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
            .clearAndSetSemantics { contentDescription = description; role = Role.Button; onClick { onClick(); true } }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, color = colors.content, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun DayColumn(
    day: LocalDate, events: List<CalendarEvent>, zone: ZoneId, minMinutes: Int, hour: Dp, minBlockWidth: Dp,
    f: Formats, compact: CompactTimes, style: TextStyle, highlighted: String?, motion: Boolean, modifier: Modifier,
    onOpen: (CalendarEvent) -> Unit, onNew: (LocalDate) -> Unit, onOverflow: (LocalDate, List<CalendarEvent>) -> Unit,
    axis: TimelineAxis? = null,
    readableLabels: Boolean = false,
    minLabelHeight: Dp = 0.dp,
) {
    val addLabel = stringResource(R.string.action_add_event)
    val clusters = remember(events, day, zone, minMinutes, readableLabels) { packTimeline(events, day, zone, minMinutes, readableLabels) }
    val density = LocalDensity.current
    val hourPx = with(density) { hour.toPx() }
    val yAxis = axis ?: remember(hourPx) { TimelineAxis(hourPx, emptyList()) }
    fun y(minute: Int) = yAxis.minuteToY(minute.coerceAtMost(DAY_MINUTES)) +
        if (readableLabels) (minute - DAY_MINUTES).coerceAtLeast(0) / 60f * hourPx else 0f
    val interaction = remember { MutableInteractionSource() }
    val keyboardFocused by interaction.collectIsFocusedAsState()
    val focusColor = LocalPalette.current.ink
    BoxWithConstraints(
        // Today's tint lives in the grid tiles. No ripple: a tap opens the editor, and a full-height flash read as a selection.
        // Keyboard focus still outlines the column.
        modifier.then(if (keyboardFocused) Modifier.border(2.dp, focusColor, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = addLabel, role = Role.Button) { onNew(day) },
    ) {
        val untitled = stringResource(R.string.untitled)
        val todoLabel = stringResource(R.string.todo_tag)
        val context = LocalContext.current
        fun height(start: Int, end: Int) =
            maxOf(if (readableLabels) minLabelHeight else 0.dp, with(density) { (y(end) - y(start)).toDp() })
        clusters.forEach { cluster ->
            val lanes = overflowLanes(maxWidth, minBlockWidth, cluster.cols)
            if (lanes.stacked) {
                // Packing sorts by start, then longest first, so the card starts with the event that opens the group.
                val first = cluster.blocks.first()
                val start = cluster.blocks.minOf { it.start }
                val end = cluster.blocks.maxOf { it.end }
                EventBlock(
                    first.event, day, zone, f, compact, style, cluster.blocks.any { highlighted == it.event.key() }, motion, end - start,
                    Modifier.offset(y = with(density) { y(start).toDp() }).width(maxWidth).height(height(start, end)),
                    more = cluster.blocks.drop(1).map { it.event }, keepCount = readableLabels,
                ) { onOverflow(day, cluster.blocks.map { it.event }) }
                return@forEach
            }
            val width = lanes.lane
            val hidden = mutableListOf<TimelineBlock>()
            cluster.blocks.forEach { b ->
                val group = b.group
                if (b.col >= lanes.shown) {
                    hidden += b
                } else if (group == null || b === group.first()) {
                    // A compact group is one card at its first real start that names its longest event and counts the rest.
                    val head = group?.let(::timelineGroupHead) ?: b
                    val start = b.cardStart
                    val cardWidth = width * b.span.coerceAtMost(lanes.shown - b.col)
                    EventBlock(
                        head.event, day, zone, f, compact, style, (group ?: listOf(b)).any { highlighted == it.event.key() }, motion, b.end - start,
                        Modifier.offset(x = width * b.col, y = with(density) { y(start).toDp() })
                            .width(cardWidth).height(height(start, b.end)),
                        more = group.orEmpty().filter { it !== head }.map { it.event },
                        centered = timelineCentersLabel(cardWidth, maxWidth),
                        originMillis = b.event.startMillis, keepCount = group != null,
                    ) { if (group == null) onOpen(b.event) else onOverflow(day, group.map { it.event }) }
                }
            }
            if (hidden.isNotEmpty()) {
                val start = hidden.minOf { it.start }
                val end = hidden.maxOf { it.end }
                val only = hidden.singleOrNull()?.event
                OverflowBlock(
                    hidden.size,
                    only?.let { e ->
                        listOfNotNull(e.displayTitle().ifBlank { untitled }, todoLabel.takeIf { e.todo }, absenceStatus(e, f), context.timeLabel(e, day, zone, f),
                            e.location.takeIf { it.isNotBlank() }).joinToString(", ")
                    },
                    Modifier.offset(x = maxWidth - lanes.chooser, y = with(density) { y(start).toDp() })
                        .width(lanes.chooser).height(height(start, end)),
                    singleTodo = only?.todo == true,
                ) { if (hidden.size == 1) onOpen(hidden.single().event) else onOverflow(day, hidden.map { it.event }) }
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
 * One rounded card at the event's exact start, inset so neighbouring cards never touch. The label is centered
 * vertically, and also horizontally when [centered] (see [timelineCentersLabel]). Titles wrap only between words; a word wider than the card keeps its start and an ellipsis instead of
 * breaking into a column of fragments. Secondary fields appear only when they fit in full, and room codes never
 * break. Missed periods get a bar on the start edge. A card with [more] events stands for a group (an overlap with
 * no room for readable lanes in Week, or a Day compact group): it shows one title, a "+N" count and a rail with one
 * color mark per event at its real time, and opens the group's list. A [keepCount] card (a Day group) puts the count
 * on the title's first line when that leaves a readable title, and also when there is no height for a line of its
 * own, so it never drops its count. The card starts at [originMillis]. The spoken description always has every field, and names every event of a group.
 */
@Composable
private fun EventBlock(
    event: CalendarEvent, day: LocalDate, zone: ZoneId, f: Formats, compact: CompactTimes, style: TextStyle,
    highlight: Boolean, motion: Boolean, shownMinutes: Int, modifier: Modifier,
    more: List<CalendarEvent> = emptyList(), centered: Boolean = false, originMillis: Long = event.startMillis,
    keepCount: Boolean = false, onClick: () -> Unit,
) {
    val context = LocalContext.current
    val typography = MaterialTheme.typography
    val untitled = stringResource(R.string.untitled)
    val plainTitle = event.displayTitle().ifBlank { untitled }
    val title = if (event.todo) "☐ $plainTitle" else plainTitle
    val todoText = stringResource(R.string.todo_tag)
    val time = remember(event, day, zone, compact) { blockTime(context, event, day, zone, f, compact) }
    val status = absenceStatus(event, f, compact)
    val spokenStatus = absenceStatus(event, f)
    val rooms = remember(event.location) { compactRooms(event.location) }
    val ownDescription = listOfNotNull(plainTitle, todoText.takeIf { event.todo }, spokenStatus, context.timeLabel(event, day, zone, f),
        event.location.takeIf { it.isNotBlank() }).joinToString(", ")
    val description = if (more.isEmpty()) ownDescription else {
        val count = more.size + 1
        context.resources.getQuantityString(R.plurals.event_count, count, count) + ": " + (listOf(ownDescription) + more.map { e ->
            listOfNotNull(e.displayTitle().ifBlank { untitled }, todoText.takeIf { e.todo }, absenceStatus(e, f), context.timeLabel(e, day, zone, f),
                e.location.takeIf { it.isNotBlank() }).joinToString(", ")
        }).joinToString("; ")
    }
    val colors = eventColors(event)
    // A stack's rail shows every event of the group, the first one included, since the card itself spans the group.
    val railEvents = if (more.isEmpty()) emptyList() else listOf(event) + more
    val markColors = railEvents.map { eventAccent(it) }
    val cardBackground = LocalPalette.current.background
    val shape = TIMELINE_CARD_SHAPE
    val origin = maxOf(originMillis, day.atStartOfDay(zone).toInstant().toEpochMilli())
    fun fraction(millis: Long) = ((millis - origin) / (shownMinutes.coerceAtLeast(1) * 60_000f)).coerceIn(0f, 1f)
    val missed = remember(event, origin, shownMinutes) {
        event.partialAbsences().map { (start, end) -> fraction(start) to fraction(end) }.filter { it.second > it.first }
    }
    // Each event of a stack sits on a rail at the end edge, at its real time inside the card.
    val marks = remember(railEvents, origin, shownMinutes) {
        railEvents.map { fraction(it.startMillis) to fraction(maxOf(it.endMillis, it.startMillis)) }
    }
    val bar = 4.dp
    val rail = 3.dp
    val measurer = rememberTextMeasurer()
    val configuration = LocalViewConfiguration.current
    val cardConfiguration = remember(configuration) {
        object : ViewConfiguration by configuration {
            // Expanding a narrow card's touch area over its neighbours would hide their accessibility nodes.
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides cardConfiguration) {
        BoxWithConstraints(
            modifier.padding(horizontal = TIMELINE_CARD_SIDE_INSET, vertical = TIMELINE_BLOCK_INSET).clip(shape).background(colors.container)
                .then(colors.border?.let { Modifier.border(2.dp, it, shape) } ?: Modifier)
                .then(if (missed.isEmpty() && marks.isEmpty()) Modifier else Modifier.drawBehind {
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val barWidth = bar.toPx()
                    missed.forEach { (from, to) ->
                        drawRect(colors.content, Offset(if (rtl) size.width - barWidth else 0f, from * size.height),
                            Size(barWidth, (to - from) * size.height))
                    }
                    if (marks.isNotEmpty()) {
                        val railWidth = rail.toPx()
                        val gap = 1.dp.toPx()
                        drawRect(cardBackground, Offset(if (rtl) 0f else size.width - railWidth - gap, 0f), Size(railWidth + gap, size.height))
                        val minHeight = 2.dp.toPx()
                        marks.forEachIndexed { i, (from, to) ->
                            val top = (from * size.height).coerceAtMost(size.height - minHeight)
                            drawRect(markColors[i], Offset(if (rtl) 0f else size.width - railWidth, top),
                                Size(railWidth, maxOf(minHeight, (to - from) * size.height)))
                        }
                    }
                })
                .focusRing(highlight, colors.content, shape, motion)
                .testTag("timeline_event_${event.id}")
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics { contentDescription = description; role = Role.Button; onClick { onClick(); true } },
        ) {
            val density = LocalDensity.current
            val wide = maxWidth >= 96.dp
            val narrow = maxWidth < 48.dp
            val padV = if (maxHeight < 28.dp) 1.dp else TIMELINE_LABEL_PADDING
            val innerHeight = with(density) { (maxHeight.roundToPx() - padV.roundToPx() * 2).toFloat() }
            // Narrow week cards drop the label letter spacing, which is about one extra letter per line at the same size.
            val baseStyle = if (narrow) style.copy(letterSpacing = 0.sp) else style
            val preferredStyle = if (wide) typography.bodyMedium else baseStyle
            val preferredHeight = measurer.measure("Ag", preferredStyle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false).size.height
            val titleStyle = (if (innerHeight >= preferredHeight) preferredStyle else baseStyle)
                .copy(fontWeight = FontWeight.SemiBold, hyphens = Hyphens.None, lineBreak = LineBreak.Paragraph,
                    localeList = LocaleList(f.locale.toLanguageTag()))
            val detailStyle = if (wide) typography.bodySmall else typography.labelSmall
            val statusStyle = detailStyle.copy(fontWeight = FontWeight.Bold)
            val padH = when {
                narrow -> 3.dp
                maxWidth < 72.dp -> 4.dp
                maxWidth < 160.dp -> 8.dp
                else -> 12.dp
            }
            val padStart = padH + if (missed.isEmpty()) 0.dp else bar
            val padEnd = if (marks.isEmpty()) padH else rail + 1.dp + padH.coerceAtMost(4.dp)
            // Match Modifier.padding, which rounds each inset before subtracting it from the pixel constraints.
            val innerWidth = with(density) { (maxWidth.roundToPx() - padStart.roundToPx() - padEnd.roundToPx()).coerceAtLeast(1) }
            fun measureText(text: String, textStyle: TextStyle) = measurer.measure(text, textStyle, constraints = Constraints(maxWidth = innerWidth))
            val moreText = "+${more.size}"
            val countGap = with(density) { 4.dp.roundToPx() }
            val countSize = remember(more.size, statusStyle) {
                if (more.isEmpty()) IntSize.Zero else measurer.measure(moreText, statusStyle, maxLines = 1, softWrap = false).size
            }
            val oneTitleLine = remember(titleStyle) { measurer.measure("Ag", titleStyle, maxLines = 1, softWrap = false).size.height }
            val minTitle = with(density) { timelineMinLaneWidth(titleStyle.fontSize.toDp()).roundToPx() }
            // A Day group ([keepCount]) puts its count on the first title line when the title keeps a readable start
            // beside it, and also when a line of its own would not fit, so it never loses its count. Week groups keep
            // the count on its own line below the title.
            val countInline = keepCount && more.isNotEmpty() &&
                (innerWidth - countSize.width - countGap >= minTitle || innerHeight < oneTitleLine + countSize.height)
            val titleWidth = if (countInline) (innerWidth - countSize.width - countGap).coerceAtLeast(1) else innerWidth
            val shownTitle = remember(title, titleStyle, titleWidth) {
                fitWords(title, titleWidth, f.locale) { measurer.measure(it, titleStyle, maxLines = 1, softWrap = false).size.width }
            }
            val titleLayout = remember(shownTitle, titleStyle, titleWidth) {
                measurer.measure(shownTitle, titleStyle, constraints = Constraints(maxWidth = titleWidth))
            }
            fun wholeField(text: String, textStyle: TextStyle) = measureText(text, textStyle).takeUnless { it.hasVisualOverflow }
            val moreLayout = remember(more.size, statusStyle, innerWidth, countInline) {
                if (more.isEmpty() || countInline) null else wholeField(moreText, statusStyle)
            }
            val timeLayout = remember(time, detailStyle, innerWidth) { wholeField(time, detailStyle) }
            val statusLayout = remember(status, statusStyle, innerWidth) { status?.let { wholeField(it, statusStyle) } }
            val roomLayout = remember(rooms, detailStyle, innerWidth) {
                rooms.takeIf { it.isNotEmpty() }?.let {
                    measurer.measure(it, detailStyle, softWrap = false, constraints = Constraints(maxWidth = innerWidth))
                        .takeUnless { layout -> layout.hasVisualOverflow }
                }
            }
            var left = innerHeight.coerceAtLeast(0f)
            fun take(lines: Int, height: Float): Int {
                val line = height / lines.coerceAtLeast(1)
                val n = minOf(lines, (left / line).toInt()).coerceAtLeast(0)
                left -= n * line
                return n
            }
            fun takeWhole(height: Int): Boolean {
                if (height > left) return false
                left -= height
                return true
            }
            val titleLine = titleLayout.size.height.toFloat() / titleLayout.lineCount.coerceAtLeast(1)
            val firstTitle = take(1, titleLine)
            val showMore = firstTitle > 0 && moreLayout?.let { takeWhole(it.size.height) } == true
            val showStatus = firstTitle > 0 && statusLayout?.let { takeWhole(it.size.height) } == true
            val priorityTitle = minOf(if (wide) 1 else 2, titleLayout.lineCount - firstTitle)
            var titleLines = firstTitle + take(priorityTitle, titleLine * priorityTitle)
            val showTime = firstTitle > 0 && timeLayout?.let { takeWhole(it.size.height) } == true
            titleLines += take(titleLayout.lineCount - titleLines, titleLine * (titleLayout.lineCount - titleLines))
            val showRooms = firstTitle > 0 && roomLayout?.let { takeWhole(it.size.height) } == true
            // A narrow lane beside other cards centers its lines, so a wrapped title reads as one balanced label.
            val align = if (centered) TextAlign.Center else TextAlign.Start
            Column(Modifier.fillMaxSize().padding(start = padStart, end = padEnd, top = padV, bottom = padV),
                verticalArrangement = Arrangement.Center) {
                if (countInline) {
                    Row(Modifier.fillMaxWidth()) {
                        if (titleLines > 0) Text(shownTitle, Modifier.weight(1f).alignByBaseline(), color = colors.content, style = titleStyle,
                            textAlign = align, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
                        else Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(4.dp))
                        Text(moreText, Modifier.alignByBaseline(), color = colors.content, style = statusStyle, maxLines = 1, softWrap = false)
                    }
                } else if (titleLines > 0) Text(shownTitle, Modifier.fillMaxWidth(), color = colors.content, style = titleStyle,
                    textAlign = align, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
                if (showMore) Text(moreText, Modifier.fillMaxWidth(), color = colors.content, style = statusStyle,
                    textAlign = align, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                if (showStatus && status != null) Text(status, Modifier.fillMaxWidth(), color = colors.content, style = statusStyle,
                    textAlign = align, maxLines = statusLayout!!.lineCount, overflow = TextOverflow.Clip)
                if (showTime) Text(time, Modifier.fillMaxWidth(), color = colors.content, style = detailStyle,
                    textAlign = align, maxLines = timeLayout!!.lineCount, overflow = TextOverflow.Clip)
                if (showRooms) Text(rooms, Modifier.fillMaxWidth(), color = colors.content, style = detailStyle,
                    textAlign = align, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
        }
    }
}

@Composable
private fun OverflowBlock(count: Int, single: String?, modifier: Modifier, singleTodo: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = single ?: LocalContext.current.resources.getQuantityString(R.plurals.event_count, count, count)
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier.padding(horizontal = TIMELINE_CARD_SIDE_INSET, vertical = TIMELINE_BLOCK_INSET).clip(TIMELINE_CARD_SHAPE)
            .border(1.dp, p.line, TIMELINE_CARD_SHAPE).background(p.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center,
    ) {
        val full = if (singleTodo) "☐ +$count" else "+$count"
        val room = with(LocalDensity.current) { (maxWidth - 2.dp).roundToPx() }
        val fits = remember(full, style, room) { measurer.measure(full, style, maxLines = 1, softWrap = false).size.width <= room }
        Text(if (fits) full else if (singleTodo) "☐" else "+", color = p.ink, style = style, maxLines = 1, softWrap = false)
    }
}

/**
 * A small dot at the start of today's column marks the current time above the events. Its halo stays inside the
 * narrowest title inset on either side of the edge (1dp block padding plus 2dp text padding), so even a narrow
 * seven-day lane keeps its title clear; the line itself is drawn under the events by [drawNowLine].
 */
@Composable
internal fun NowMarker(
    column: Dp, todayIndex: Int, minute: Int, modifier: Modifier,
    axis: TimelineAxis, colors: TimelineGridColors,
) {
    Spacer(
        modifier.clearAndSetSemantics { }.drawBehind {
            val y = axis.minuteToY(minute)
            val x = startX(column.toPx() * todayIndex)
            drawCircle(colors.halo, radius = 3.dp.toPx(), center = Offset(x, y))
            drawCircle(colors.now, radius = 2.dp.toPx(), center = Offset(x, y))
        },
    )
}

/** Minutes since local midnight, refreshed every 30 seconds. */
@Composable
internal fun rememberCurrentMinute(): State<Int> = produceState(LocalTime.now().let { it.hour * 60 + it.minute }) {
    while (true) {
        delay(30_000)
        value = LocalTime.now().let { it.hour * 60 + it.minute }
    }
}


internal fun shortTimelineEvents(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId, minMinutes: Int): List<CalendarEvent> =
    packTimeline(events.filter { !it.isTimelineBanner() }, day, zone, 1).flatMap { it.blocks }
        .filter { it.end - it.start < minMinutes }.map { it.event }

@Composable
internal fun TimelineShortEventButton(count: Int, fullLabel: Boolean = false, onClick: () -> Unit) {
    val label = LocalContext.current.resources.getQuantityString(R.plurals.timeline_short_event_count, count, count)
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("timeline_short_events")
        .clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
            onClick { onClick(); true }
        }) {
        Text(if (fullLabel) label else "\u22ef $count", color = LocalPalette.current.ink,
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun TimelineEventList(events: List<CalendarEvent>, f: Formats, onOpen: (CalendarEvent) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onClose,
        title = { Text(context.resources.getQuantityString(R.plurals.event_count, events.size, events.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                events.forEach { event ->
                    val title = event.displayTitle().ifBlank { context.getString(R.string.untitled) }
                    val todo = context.getString(R.string.todo_tag).takeIf { event.todo }
                    val zone = ZoneId.systemDefault()
                    val startDay = Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalDate()
                    val endDay = Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalDate()
                    val time = if (startDay == endDay) f.range(f.time(event.startMillis), f.time(event.endMillis))
                        else f.range("${f.dayLong(startDay)} ${f.time(event.startMillis)}", "${f.dayLong(endDay)} ${f.time(event.endMillis)}")
                    // Each entry keeps every field a timeline card can hide: teacher absence, exact times and room.
                    val status = absenceStatus(event, f)
                    val room = event.location.takeIf { it.isNotBlank() }
                    val description = listOfNotNull(title, todo, status, time, room).joinToString(", ")
                    TextButton(onClick = { onClose(); onOpen(event) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clearAndSetSemantics {
                            contentDescription = description
                            role = Role.Button
                            onClick { onClose(); onOpen(event); true }
                        },
                        shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(if (event.todo) "\u2610 $title" else title)
                            if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text(time, style = MaterialTheme.typography.bodySmall)
                            if (room != null) Text(room, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } })
}
