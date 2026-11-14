package io.github.sunsind.sunlith.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TIMELINE_CENTER = 1_000_000
private const val TIMELINE_DAYS = 2_000_001

internal class TimelinePanAnchor(var index: Int, var fraction: Float = 0f) {
    fun record(index: Int, offset: Int, width: Int) {
        if (width <= 0) return
        this.index = index
        fraction = (offset.toFloat() / width).coerceIn(0f, 1f)
    }

    fun offset(width: Int): Int = (fraction * width).roundToInt()

    companion object {
        val Saver = listSaver<TimelinePanAnchor, Number>(
            save = { listOf(it.index, it.fraction) },
            restore = { TimelinePanAnchor(it[0].toInt(), it[1].toFloat()) },
        )
    }
}

/** One lazy day strip owns horizontal motion. The pinned bands project its item positions during layout. */
@Composable
internal fun ContinuousTimeline(
    vm: SunlithViewModel, f: Formats, today: LocalDate, motion: Boolean, scroll: ScrollState,
    modifier: Modifier = Modifier, onOpen: (CalendarEvent) -> Unit, onDirectionChanged: (TodayDirection?) -> Unit = {},
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    val origin = rememberSaveable { vm.selectedDate.toEpochDay() }
    fun date(index: Int) = LocalDate.ofEpochDay(origin + index - TIMELINE_CENTER)
    fun index(day: LocalDate) = (TIMELINE_CENTER + day.toEpochDay() - origin).coerceIn(0L, TIMELINE_DAYS - 1L).toInt()
    val row = rememberLazyListState(initialFirstVisibleItemIndex = index(
        if (vm.timelineNavigationRequest != vm.handledTimelineNavigation) vm.selectedDate else vm.timelineViewportStart ?: vm.selectedDate))
    val titleStyle = MaterialTheme.typography.labelMedium
    val railStyle = MaterialTheme.typography.labelSmall
    val titleSize = with(density) { titleStyle.fontSize.toDp() }
    val lineSize = with(density) { titleStyle.lineHeight.toDp() }
    val labelMeasurer = rememberTextMeasurer()
    val measuredTitle = with(density) {
        labelMeasurer.measure("Ag", titleStyle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1).size.height.toDp()
    }
    val zoom = remember { TimelineZoom() }
    val liveZoom = zoom.live
    val hour = if (liveZoom != null) timelineHourHeight(liveZoom) else timelineHourHeight(vm.weekTimelineZoom)
    val gridColors = rememberTimelineGridColors()
    val hourPx = with(density) { hour.toPx() }
    // Packing follows the saved zoom, frozen while a pinch or its glide runs, so short-event rows and grouped hours
    // stay put until the scale has settled.
    val minMinutes = ceil(timelineMinimumBlockHeight(maxOf(lineSize, measuredTitle), density) /
        timelineHourHeight(zoom.frozen ?: vm.weekTimelineZoom) * 60f).toInt()
    // Midnight sits a little below the all-day band, with its rail label centered on the line.
    val railLabelPx = remember(railStyle, density) { labelMeasurer.measure("Ag", railStyle, maxLines = 1).size.height }
    val edgePx = with(density) { (railLabelPx / 2f + 3.dp.toPx()).roundToInt() }
    val edge = with(density) { edgePx.toDp() }
    val topPx = edgePx.toFloat()
    val minBlockWidth = timelineMinLaneWidth(titleSize)
    val compact = remember(f) { CompactTimes(f.locale, f.is24Hour, f::range) }
    val labels = remember(f, compact) { (0..24).map {
        if (it == 24 && f.is24Hour) String.format(f.locale, "%02d:%02d", 24, 0)
        else compact.time(LocalTime.of(it % 24, 0))
    } }
    val allDayLabel = stringResource(R.string.all_day)
    val emptyLabel = stringResource(R.string.timeline_empty_hours)
    val loadingLabel = stringResource(R.string.timeline_loading_day)
    val events = vm.eventsByDay
    var overflowEvents by remember { mutableStateOf(emptyList<CalendarEvent>()) }
    val shortEvents = remember(events, zone, minMinutes) {
        events.mapValues { (day, list) -> shortTimelineEvents(list, day, zone, minMinutes) }
    }
    val visibleIndices by remember(row) { derivedStateOf {
        row.layoutInfo.visibleItemsInfo.filter { it.offset + it.size > 0 && it.offset < row.layoutInfo.viewportEndOffset }
            .map { it.index }.let { if (it.isEmpty()) row.firstVisibleItemIndex..row.firstVisibleItemIndex else it.min()..it.max() }
    } }
    val visibleFirst = date(visibleIndices.first)
    val visibleLast = date(visibleIndices.last)
    val cachedOccupied = remember(events, zone, minMinutes, vm.groupEmptyHours) {
        if (!vm.groupEmptyHours) emptyList() else events.flatMap { (day, list) ->
            packTimeline(list.filter { !it.isTimelineBanner() }, day, zone, minMinutes)
                .flatMap { it.blocks }.map { it.start to it.end }
        }.distinct().sortedBy { it.first }
    }
    // Use the same quiet hours throughout the loaded window; dragging must not resize the time grid.
    val grouped = vm.groupEmptyHours
    val mappedOccupied = if (grouped) cachedOccupied else emptyList()
    val gapPx = with(density) { maxOf(48.dp, lineSize * 3f).toPx() }
    val axis = remember(hourPx, mappedOccupied, grouped, gapPx) { TimelineAxis(hourPx, mappedOccupied, grouped, gapPx) }
    val zoomAxis by rememberUpdatedState { percent: Float ->
        TimelineAxis(with(density) { timelineHourHeight(percent).toPx() }, mappedOccupied, grouped, gapPx)
    }
    val pinchScope = rememberCoroutineScope()
    val latestAxis by rememberUpdatedState(axis)
    val latestTop by rememberUpdatedState(topPx)
    val reportDirection by rememberUpdatedState(onDirectionChanged)
    // One clock for the line, the dot, the rail pill and the Today direction.
    val currentMinute by rememberCurrentMinute()
    LaunchedEffect(axis, visibleFirst, visibleLast, currentMinute, topPx, onDirectionChanged) {
        snapshotFlow {
            if (today in visibleFirst..visibleLast) timelineTodayDirection(axis, currentMinute, scroll.value, scroll.viewportSize, topPx) else null
        }.collect { reportDirection(it) }
    }
    var requestedMinute by remember { mutableStateOf<Int?>(null) }
    var previousAxis by remember(scroll) { mutableStateOf(axis) }
    var previousTop by remember(scroll) { mutableFloatStateOf(topPx) }
    var pendingAnchor by remember(scroll) { mutableStateOf<TimelineAnchor?>(null) }
    SideEffect {
        if (liveZoom != null) {
            // The pinch owns the scroll; an older remap must not replay once the scale settles.
            previousAxis = axis
            previousTop = topPx
            pendingAnchor = null
            zoom.takeHandoff(scroll.value)
        } else {
            val settled = zoom.takeHandoff(scroll.value)
            if (previousAxis.gaps != axis.gaps || previousAxis.hourPx != axis.hourPx || previousTop != topPx) {
                // Capture before the new grid measures and can clamp the old pixel offset; placement of the new grid
                // restores it in the same frame (see timelinePosition). A settled pinch keeps the minute under the
                // fingers, since regrouped hours between the top and the fingers would move it.
                pendingAnchor = settled ?: pendingAnchor ?: timelineAnchor(previousAxis, previousTop, scroll.value.toFloat())
                previousAxis = axis
                previousTop = topPx
            }
        }
    }
    val readableHeaderPx = with(density) { 32.dp.toPx() }
    LaunchedEffect(row, vm, readableHeaderPx) {
        snapshotFlow {
            val items = row.layoutInfo.visibleItemsInfo.filter {
                minOf(it.offset + it.size, row.layoutInfo.viewportEndOffset) - maxOf(it.offset, 0) >= minOf(readableHeaderPx, it.size / 2f)
            }
            if (items.isEmpty()) null else Triple(items.minOf { it.index }, items.maxOf { it.index }, !row.isScrollInProgress && !vm.loadingEvents)
        }.distinctUntilChanged().collect { range ->
            range?.let { vm.showTimelineRange(date(it.first), date(it.second), load = it.third) }
        }
    }
    var firstPosition by rememberSaveable { mutableStateOf(true) }
    // An explicit date (picker, Today) moves the strip in the next measure, so no frame shows the dates in between
    // or the old week. Two fingers that take over a pending Today jump keep the strip where it is.
    val requestedRow = remember { intArrayOf(Int.MIN_VALUE) }
    SideEffect {
        val request = vm.timelineNavigationRequest
        if (request != vm.handledTimelineNavigation && requestedRow[0] != request && !(zoom.pinching && vm.selectedDate == today)) {
            requestedRow[0] = request
            val target = index(vm.selectedDate)
            if (target != row.firstVisibleItemIndex || row.firstVisibleItemScrollOffset != 0) row.requestScrollToItem(target)
        }
    }
    LaunchedEffect(vm.timelineNavigationRequest, vm.todayRequest, zoom.pinching) {
        val request = vm.timelineNavigationRequest
        var completed = false
        try {
            val pending = request != vm.handledTimelineNavigation
            if (firstPosition && scroll.isScrollInProgress) {
                // Initial positioning must not move a viewport already owned by the user's scroll mutation.
                firstPosition = false
                return@LaunchedEffect
            }
            // Two fingers take over a pending Today jump, including before the pinch activates.
            if (zoom.pinching && vm.selectedDate == today) {
                firstPosition = false
                vm.consumeTimelineNavigation(request)
                return@LaunchedEffect
            }
            if (pending && vm.selectedDate == today) {
                snapshotFlow { !zoom.pinching && zoom.live == null }.first { it }
                requestedMinute = timelineTodayMinute(currentMinute)
            }
            val target = index(vm.selectedDate)
            if (pending && (target != row.firstVisibleItemIndex || row.firstVisibleItemScrollOffset != 0)) {
                // Explicit selection changes the date atomically. Only finger-driven scrolling
                // travels through intervening dates; a picker or Today must not flash their events.
                row.scrollToItem(target)
            }
            if ((firstPosition && scroll.value == 0) || (pending && vm.selectedDate == today)) {
                withFrameNanos { }
                val y = if (vm.selectedDate == today) timelineTodayScroll(latestAxis, timelineTodayMinute(currentMinute), latestTop)
                    else latestAxis.minuteToY(7 * 60)
                scroll.scrollTo(y.roundToInt())
                // Navigation has already positioned the latest grid; an older remap must not undo it.
                pendingAnchor = null
                previousAxis = latestAxis
            }
            firstPosition = false
            withFrameNanos { }
            completed = true
        } finally {
            if (completed || currentCoroutineContext().isActive) {
                firstPosition = false
                vm.consumeTimelineNavigation(request)
            }
            requestedMinute = null
        }
    }
    // The vertical positions the navigation effect below would reach a frame or two later, applied while the grid is
    // placed so it is never drawn at an old or clamped offset. A pinch and its glide own the scroll themselves.
    val placementTarget: () -> Float? = {
        val pending = vm.timelineNavigationRequest != vm.handledTimelineNavigation
        when {
            zoom.live != null || zoom.pinching -> null
            firstPosition && scroll.isScrollInProgress -> {
                firstPosition = false
                vm.consumeTimelineNavigation(vm.timelineNavigationRequest)
                null
            }
            (firstPosition && scroll.value == 0) || (pending && vm.selectedDate == today) ->
                if (vm.selectedDate == today) timelineTodayScroll(axis, timelineTodayMinute(currentMinute), topPx)
                else axis.minuteToY(7 * 60)
            pendingAnchor != null -> (requestedMinute?.let { timelineTodayScroll(axis, it, topPx) } ?: pendingAnchor!!.scrollOn(axis, topPx))
                .also { pendingAnchor = null }
            else -> null
        }
    }
    val latestPlacement = rememberUpdatedState(placementTarget)
    val positioned = remember(scroll) { Modifier.timelinePosition(scroll) { latestPlacement.value() } }
    BoxWithConstraints(modifier.fillMaxSize().testTag("calendar_continuous_timeline")) {
        val gutter = rememberTimeGutter(labels + listOf(allDayLabel.substringBeforeLast(' '), allDayLabel.substringAfterLast(' ')), railStyle, 44.dp, maxWidth * 0.30f)
        val column = maxOf(88.dp, titleSize * 6.5f, (maxWidth - gutter) / vm.visibleWeekDays)
        val columnPx = with(density) { column.toPx() }
        var oldColumnPx by rememberSaveable { mutableFloatStateOf(columnPx) }
        val pan = rememberSaveable(saver = TimelinePanAnchor.Saver) { TimelinePanAnchor(row.firstVisibleItemIndex) }
        LaunchedEffect(row, columnPx) {
            snapshotFlow {
                val item = row.layoutInfo.visibleItemsInfo.firstOrNull { it.index == row.firstVisibleItemIndex }
                if (oldColumnPx == columnPx && item?.size == columnPx.roundToInt())
                    row.firstVisibleItemIndex to row.firstVisibleItemScrollOffset else null
            }.collect { position -> position?.let { pan.record(it.first, it.second, columnPx.roundToInt()) } }
        }
        LaunchedEffect(columnPx) {
            if (oldColumnPx != columnPx) {
                try {
                    if (vm.timelineNavigationRequest != vm.handledTimelineNavigation) {
                        // Explicit navigation owns the strip until it completes or the user interrupts it.
                        snapshotFlow { vm.timelineNavigationRequest == vm.handledTimelineNavigation }.first { it }
                        withFrameNanos { }
                        pan.record(row.firstVisibleItemIndex, row.firstVisibleItemScrollOffset, columnPx.roundToInt())
                    } else row.scrollToItem(pan.index, pan.offset(columnPx.roundToInt()))
                    oldColumnPx = columnPx
                } catch (cancelled: CancellationException) {
                    if (currentCoroutineContext().isActive) oldColumnPx = columnPx
                    throw cancelled
                }
            }
        }
        // While pinching, the strip keeps its largest height so the focal scroll is never clamped early.
        val height = with(density) {
            (if (liveZoom == null) axis.totalHeight else maxOf(axis.totalHeight, zoomAxis(TIMELINE_ZOOM_MAX * 1.25f).totalHeight)).toDp()
        }
        val headerHeight = maxOf(40.dp, with(density) { MaterialTheme.typography.bodyLarge.fontSize.toDp() } * 2.25f) +
            with(density) { railStyle.lineHeight.toDp() } + 12.dp
        val bannerHeight = minOf(maxHeight / 4, maxOf(48.dp, lineSize * 3f))
        val drag = Modifier.scrollable(row, Orientation.Horizontal, reverseDirection = !rtl)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(headerHeight)) {
                Spacer(Modifier.width(gutter))
                ProjectedDays(row, visibleIndices, column, Modifier.weight(1f).fillMaxHeight().testTag("calendar_timeline_headers").then(drag)) { i ->
                    val day = date(i)
                    TimelineDayHeader(day, day == vm.selectedDate, day == today,
                        if (vm.coversRange(day, day)) events[day].orEmpty().size else 0, f, Modifier.fillMaxSize(), todayTint = gridColors.today,
                        onClick = if (vm.weekHeaderSelection) { { vm.selectTimelineDay(day) } } else null)
                }
            }
            if (shortEvents.values.any { it.isNotEmpty() }) {
                Row(Modifier.fillMaxWidth().height(48.dp).testTag("calendar_timeline_short_events")) {
                    Spacer(Modifier.width(gutter))
                    ProjectedDays(row, visibleIndices, column, Modifier.weight(1f).fillMaxHeight().then(drag)) { i ->
                        val list = shortEvents[date(i)].orEmpty()
                        if (list.isNotEmpty()) TimelineShortEventButton(list.size) { overflowEvents = list }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(bannerHeight).testTag("calendar_timeline_all_day")) {
                Text(allDayLabel.substringBeforeLast(' ') + "\n" + allDayLabel.substringAfterLast(' '), Modifier.width(gutter).padding(end = 6.dp, top = 4.dp), color = p.muted, style = railStyle,
                    textAlign = if (rtl) androidx.compose.ui.text.style.TextAlign.Left else androidx.compose.ui.text.style.TextAlign.Right,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                ProjectedDays(row, visibleIndices, column, Modifier.weight(1f).fillMaxHeight().then(drag)) { i ->
                    val day = date(i)
                    Column(Modifier.fillMaxSize().padding(horizontal = 1.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (vm.coversRange(day, day)) events[day].orEmpty().filter { it.isTimelineBanner() }.forEach { event ->
                            AllDayChip(event, day, f, false, motion, bannerHeight) { onOpen(event) }
                        }
                    }
                }
            }
            HorizontalDivider(color = p.line)
            Row(
                Modifier.weight(1f).fillMaxWidth().testTag("calendar_timeline").timelinePinch(
                    zoom, scroll, pinchScope, motion,
                    saved = { vm.weekTimelineZoom },
                    // The grid starts [edge] below the top of the scrolling content and ends [edge] above its bottom.
                    anchorAt = { percent, scrollY, focalY -> timelineAnchor(zoomAxis(percent), topPx, scrollY, focalY) },
                    scrollFor = { percent, minute, focalY ->
                        val target = zoomAxis(percent)
                        focalScroll(target, minute, focalY - topPx, target.totalHeight + topPx * 2, scroll.viewportSize.toFloat())
                    },
                    onCommit = vm::changeWeekTimelineZoom,
                ).then(positioned).verticalScroll(scroll).padding(vertical = edge),
            ) {
                // Gap labels share the same mapping as the columns, so no event can drift away from its hour.
                Box(Modifier.width(gutter).height(height)) {
                    HourRail(labels, railStyle, hourPx, Modifier.fillMaxSize(), axis, overhang = edgePx)
                }
                LazyRow(state = row, modifier = Modifier.weight(1f).height(height)) {
                    items(TIMELINE_DAYS, key = { origin + it - TIMELINE_CENTER }, contentType = { "timeline_day" }) { i ->
                        val day = date(i)
                        val isToday = day == today
                        Box(Modifier.width(column).fillMaxHeight().drawBehind {
                            drawTimelineGrid(axis, size.width, 1, gridColors, if (isToday) 0 else -1)
                            if (isToday) drawNowLine(axis, currentMinute, size.width, 1, 0, gridColors)
                        }) {
                            if (vm.coversRange(day, day)) {
                                DayColumn(day, events[day].orEmpty().filter { !it.isTimelineBanner() }, zone, minMinutes, hour, minBlockWidth,
                                    f, compact, titleStyle, null, motion, Modifier.fillMaxSize(), onOpen, vm::newEvent,
                                    { _, list -> overflowEvents = list }, axis)
                            } else Text(loadingLabel, Modifier.padding(8.dp), color = p.muted, style = railStyle)
                            if (vm.coversRange(day, day)) axis.gaps.forEach { gap ->
                                Box(Modifier.offset(y = with(density) { gap.y.toDp() }).fillMaxWidth().height(with(density) { gap.height.toDp() }),
                                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Text(emptyLabel, Modifier.padding(4.dp), color = p.muted, style = railStyle,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (isToday) NowMarker(column, 0, currentMinute, Modifier.fillMaxSize(), axis, gridColors)
                        }
                    }
                }
            }
        }
    }
    if (overflowEvents.isNotEmpty()) {
        // Details opens with no fade and the chooser stays until the frame that draws it, so the week never shows
        // between the chooser and the event. A plain dismiss closes at once.
        val chooserScope = rememberCoroutineScope()
        val opening = remember { booleanArrayOf(false) }
        TimelineEventList(overflowEvents, f,
            onOpen = { event ->
                if (!opening[0]) {
                    opening[0] = true
                    vm.openInstantly(Screen.Details(event))
                }
            },
            onClose = {
                if (!opening[0]) overflowEvents = emptyList()
                else chooserScope.launch {
                    try { withFrameNanos { }; withFrameNanos { } } finally {
                        opening[0] = false
                        overflowEvents = emptyList()
                    }
                }
            })
    }
}

/** Reads pixel offsets in placement, keeping scroll updates out of composition. */
@Composable
private fun ProjectedDays(
    state: LazyListState, indices: IntRange, column: Dp, modifier: Modifier, content: @Composable (Int) -> Unit,
) {
    val composed = (indices.first - 3).coerceAtLeast(0)..(indices.last + 3).coerceAtMost(TIMELINE_DAYS - 1)
    Layout(content = { composed.forEach { i -> key(i) { Box(Modifier.fillMaxHeight()) { content(i) } } } }, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val width = column.roundToPx()
        val cells = measurables.map { it.measure(Constraints.fixed(width, constraints.maxHeight)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val info = state.layoutInfo.visibleItemsInfo
            val anchor = info.firstOrNull()
            if (anchor != null) cells.forEachIndexed { n, cell ->
                cell.placeRelative(anchor.offset + (composed.first + n - anchor.index) * width, 0)
            }
        }
    }
}
