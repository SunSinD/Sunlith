package io.github.sunsind.sunlith.ui

import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val GRID_ROWS = 6

/** How close (in rows) the agenda must come to a loaded edge before more dates are fetched. */
private const val EDGE_ROWS = 12

private sealed interface AgendaRow {
    val key: String
    val first: LocalDate
    val last: LocalDate

    data class Month(val month: YearMonth) : AgendaRow {
        override val key get() = "m-$month"
        override val first: LocalDate get() = month.atDay(1)
        override val last: LocalDate get() = month.atDay(1)
    }

    data class Day(val date: LocalDate, val events: List<CalendarEvent>, val continuation: Boolean = false) : AgendaRow {
        override val key get() = if (continuation) "d-$date-${events.first().key()}" else "d-$date"
        override val first get() = date
        override val last get() = date
    }

    data class Empty(val from: LocalDate, val to: LocalDate) : AgendaRow {
        override val key get() = "e-$to"
        override val first get() = from
        override val last get() = to
    }
}

/**
 * Builds one row per day ("all"), groups runs of empty days ("compact", keeping [keep] as single days), or keeps
 * only days with events ("events"). With [monthHeaders], a month label starts each month, for the continuous list.
 */
private fun buildAgenda(
    from: LocalDate, to: LocalDate, index: Map<LocalDate, List<CalendarEvent>>, display: String, keep: Set<LocalDate>, monthHeaders: Boolean,
    alwaysShow: LocalDate? = null, splitEvents: Boolean = false,
): List<AgendaRow> {
    val rows = mutableListOf<AgendaRow>()
    var emptyStart: LocalDate? = null
    var day = from
    while (!day.isAfter(to)) {
        val events = index[day].orEmpty()
        val show = day == alwaysShow || events.isNotEmpty() || display == "all" || (display == "compact" && day in keep)
        val monthStart = day.dayOfMonth == 1
        emptyStart?.let { start ->
            if (show || monthStart) { rows += AgendaRow.Empty(start, day.minusDays(1)); emptyStart = null }
        }
        if (monthHeaders && (day == from || monthStart)) rows += AgendaRow.Month(YearMonth.from(day))
        if (show) {
            if (splitEvents && events.isNotEmpty()) {
                events.forEachIndexed { index, event -> rows += AgendaRow.Day(day, listOf(event), continuation = index != 0) }
            } else rows += AgendaRow.Day(day, events)
        } else if (display == "compact" && emptyStart == null) {
            emptyStart = day
        }
        day = day.plusDays(1)
    }
    emptyStart?.let { rows += AgendaRow.Empty(it, to) }
    return rows
}

/** The row to show at the top for [date]: its month label on the 1st, its own row, the range containing it, or the next shown day. */
private fun targetIndex(rows: List<AgendaRow>, date: LocalDate): Int {
    if (rows.isEmpty()) return -1
    val found = rows.indexOfFirst { !it.last.isBefore(date) }
    return if (found < 0) rows.lastIndex else found
}

/** Animations run only when the in-app setting allows them and Android's "Remove animations" is off. */
@Composable
fun rememberMotion(enabled: Boolean): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var systemOff by remember(resolver) {
        mutableStateOf(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f)
    }
    DisposableEffect(resolver) {
        fun refresh() { systemOff = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        refresh()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled && !systemOff
}

internal enum class TodayDirection { Up, Down }

internal fun todayDirection(today: LocalDate, first: LocalDate, last: LocalDate): TodayDirection? = when {
    today.isBefore(first) -> TodayDirection.Up
    today.isAfter(last) -> TodayDirection.Down
    else -> null
}

/** Scrolling reports visible dates without changing the selected date. Only the active page owns the report. */
@Stable
private class AgendaViewport {
    var direction by mutableStateOf<TodayDirection?>(null)
        private set
    var reporting by mutableStateOf(false)
        private set
    private var owner: Any? = null

    fun report(source: Any, value: TodayDirection?) {
        owner = source
        direction = value
        reporting = true
    }

    fun release(source: Any) {
        if (owner !== source) return
        owner = null
        direction = null
        reporting = false
    }
}

/**
 * First date of a rolling 3- or 5-day timeline. It stays while the selected date is one of the shown days. A date
 * outside the window starts a new one. Changing the size keeps the start when the selection still fits, and
 * otherwise ends the window on the selection, so the days already in view stay in view.
 */
internal fun rollingWindowStart(start: LocalDate?, previousSize: Int, selected: LocalDate, size: Int): LocalDate {
    if (start != null && !selected.isBefore(start) && !selected.isAfter(start.plusDays(size - 1L))) return start
    if (start != null && previousSize != size && !selected.isBefore(start) && !selected.isAfter(start.plusDays(previousSize - 1L))) {
        return selected.minusDays(size - 1L)
    }
    return selected
}

private class WindowStart(var start: LocalDate? = null, var size: Int = 0) {
    companion object {
        val Saver = listSaver<WindowStart, Long>(
            save = { listOf(it.start?.toEpochDay() ?: Long.MIN_VALUE, it.size.toLong()) },
            restore = { WindowStart(if (it[0] == Long.MIN_VALUE) null else LocalDate.ofEpochDay(it[0]), it[1].toInt()) },
        )
    }

    fun startFor(selected: LocalDate, days: Int): LocalDate {
        val next = rollingWindowStart(start, size, selected, days)
        start = next
        size = days
        return next
    }
}

/** Everything the calendar sections share for one frame. */
private class CalendarContext(
    val vm: SunlithViewModel,
    val f: Formats,
    val dayMeasurer: TextMeasurer,
    val today: LocalDate,
    val motion: Boolean,
    val swipe: PeriodSwipeState,
    val collapse: HeaderCollapse,
    val viewport: AgendaViewport,
    val highlightToday: Boolean,
    /** 3 or 5 for a rolling timeline window, 0 otherwise. */
    val rollingDays: Int,
    val rollingStart: LocalDate,
    val timelineScroll: ScrollState,
    val timelineFocus: TimelineFocus?,
    val onFocusHandled: (TimelineFocus) -> Unit,
) {
    // A fresh request waits for provider data; an animation already started can finish during a refresh.
    val readyFocus: TimelineFocus?
        get() = timelineFocus?.takeIf { !vm.pendingDayFocus || (!vm.loadingEvents && vm.loadError == null) }

    val period: Long get() = when (vm.viewMode) {
        ViewMode.Month -> vm.month.year * 12L + vm.month.monthValue - 1
        ViewMode.Week -> if (rollingDays > 0) Math.floorDiv(rollingStart.toEpochDay(), rollingDays.toLong())
            else Math.floorDiv(weekStart(vm.selectedDate, vm.weekStartsMonday).toEpochDay(), 7L)
        ViewMode.Day -> vm.selectedDate.toEpochDay()
    }
    /** The date that represents the current period (0), or the previous (-1) or next (1) period, matching [SunlithViewModel.step]. */
    fun dateAt(k: Int): LocalDate {
        val selected = vm.selectedDate
        return when (vm.viewMode) {
            ViewMode.Month -> if (k == 0) selected else {
                val m = YearMonth.from(selected).plusMonths(k.toLong())
                if (m == YearMonth.from(today)) today else m.atDay(1)
            }
            ViewMode.Week -> if (rollingDays > 0) rollingStart.plusDays(rollingDays.toLong() * k) else selected.plusWeeks(k.toLong())
            ViewMode.Day -> selected.plusDays(k.toLong())
        }
    }

    fun dateInPeriod(period: Long): LocalDate = when (vm.viewMode) {
        ViewMode.Month -> {
            val month = YearMonth.of(Math.floorDiv(period, 12L).toInt(), Math.floorMod(period, 12L).toInt() + 1)
            when (month) { vm.month -> vm.selectedDate; YearMonth.from(today) -> today; else -> month.atDay(1) }
        }
        ViewMode.Week -> if (rollingDays > 0) {
            val n = rollingDays.toLong()
            LocalDate.ofEpochDay(period * n + Math.floorMod(rollingStart.toEpochDay(), n))
        } else vm.selectedDate.plusWeeks(period - this.period)
        ViewMode.Day -> LocalDate.ofEpochDay(period)
    }

    fun timelineDays(k: Int): List<LocalDate> {
        val d = dateAt(k)
        return if (rollingDays > 0) (0L until rollingDays.toLong()).map { d.plusDays(it) }
        else weekStart(d, vm.weekStartsMonday).let { s -> (0L until 7L).map { s.plusDays(it) } }
    }
}

@Composable
fun CalendarScreen(vm: SunlithViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var clockChange by remember { mutableIntStateOf(0) }
    DisposableEffect(vm, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                vm.refreshCurrentDate()
                if (intent?.action == Intent.ACTION_TIMEZONE_CHANGED) vm.reload()
                clockChange++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(vm, clockChange) {
        while (true) {
            vm.refreshCurrentDate()
            val now = java.time.ZonedDateTime.now()
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(java.time.Duration.between(now, midnight).toMillis().coerceAtLeast(1) + 50)
        }
    }
    val p = LocalPalette.current
    val f = rememberFormats()
    val motion = rememberMotion(vm.navigationAnimation)
    val collapse = remember(vm.viewMode, vm.collapseCalendarOnScroll, vm.dayFocusRequest.takeIf { vm.viewMode == ViewMode.Day }) {
        HeaderCollapse(vm.collapseCalendarOnScroll)
    }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var highlightToday by remember { mutableStateOf(false) }
    var handledToday by rememberSaveable { mutableIntStateOf(vm.todayRequest) }
    LaunchedEffect(vm.todayRequest) {
        if (vm.todayRequest <= handledToday) return@LaunchedEffect
        handledToday = vm.todayRequest
        launch {
            if (motion) animate(collapse.offset, 0f, animationSpec = tween(220)) { value, _ -> collapse.offset = value }
            else collapse.offset = 0f
        }
        highlightToday = true
        delay(1200)
        highlightToday = false
    }

    val today = vm.today
    val mode = vm.viewMode
    val windowDays = rollingDays(vm)
    val window = rememberSaveable(saver = WindowStart.Saver) { WindowStart() }
    val windowStart = if (windowDays > 0) window.startFor(vm.selectedDate, windowDays) else today
    val navigate: (Int) -> Unit = { dir ->
        val n = rollingDays(vm)
        if (n > 0) {
            val start = window.startFor(vm.selectedDate, n).plusDays(n.toLong() * dir)
            window.start = start
            vm.select(start)
        } else vm.step(dir)
    }
    val swipe = rememberPeriodSwipe(vm.swipeNavigation, motion, navigate)
    LaunchedEffect(mode, vm.monthLayout, vm.weekLayout, vm.dayLayout, vm.swipeNavigation) { swipe.cancel() }
    val viewport = remember { AgendaViewport() }
    val timelineScroll = rememberSaveable(mode, saver = ScrollState.Saver) { ScrollState(0) }
    var timelineFocus by remember(mode, vm.dayFocusRequest) {
        val request = vm.dayFocusRequest
        mutableStateOf(if (vm.pendingDayFocus) TimelineFocus(vm.selectedDate, request) { vm.consumeDayFocus(request) } else null)
    }
    LaunchedEffect(mode, vm.selectedDate) {
        if (mode != ViewMode.Day || timelineFocus?.date?.let { it != vm.selectedDate } == true) {
            timelineFocus = null
            vm.consumeDayFocus(vm.dayFocusRequest)
        }
    }
    val onFocusHandled: (TimelineFocus) -> Unit = remember(vm, mode, vm.dayFocusRequest) {
        { if (timelineFocus === it) { vm.consumeDayFocus(it.request); timelineFocus = null } }
    }
    val dayMeasurer = rememberTextMeasurer(cacheSize = 128)
    val c = remember(vm, f, dayMeasurer, today, motion, swipe, collapse, viewport, highlightToday, windowDays, windowStart, timelineScroll, timelineFocus, onFocusHandled) {
        CalendarContext(vm, f, dayMeasurer, today, motion, swipe, collapse, viewport, highlightToday, windowDays, windowStart, timelineScroll, timelineFocus, onFocusHandled)
    }
    val courseColors = rememberCourseColors(vm.eventsByDay, vm.courseColorSlots)

    val periodDirection = when (mode) {
        ViewMode.Month -> todayDirection(today, vm.month.atDay(1), vm.month.atEndOfMonth())
        ViewMode.Week -> if (vm.weekLayout == "timeline") c.timelineDays(0).let { todayDirection(today, it.first(), it.last()) }
            else weekStart(vm.selectedDate, vm.weekStartsMonday).let { todayDirection(today, it, it.plusDays(6)) }
        ViewMode.Day -> null
    }
    val direction = if (viewport.reporting) viewport.direction else periodDirection

    CompositionLocalProvider(LocalCourseColors provides courseColors) {
    Scaffold(
        modifier = modifier.semantics { testTagsAsResourceId = true },
        containerColor = p.background,
        topBar = {
            Column(Modifier.background(p.background).statusBarsPadding()) {
                Header(vm, f, onTitle = { showPicker = true }, onStep = swipe::animateStep)
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.newEvent() },
                containerColor = p.accent, contentColor = p.onAccent, shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Outlined.Add, stringResource(R.string.action_new_event)) }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxSize().onSizeChanged { if (swipe.width != it.width) swipe.cancel(); swipe.width = it.width }
                .periodSwipe(swipe, allowChildHorizontalScroll = mode == ViewMode.Week && vm.weekLayout == "timeline")) {
                if (mode != ViewMode.Day) StatusCards(vm, Modifier.padding(horizontal = 12.dp))
                when (mode) {
                    ViewMode.Month -> MonthBody(c, Modifier.weight(1f))
                    ViewMode.Week -> WeekBody(c, Modifier.weight(1f))
                    ViewMode.Day -> DayBody(c, Modifier.weight(1f))
                }
            }
            TodayPill(direction.takeIf { mode != ViewMode.Day }, motion, vm::goToday, Modifier.align(Alignment.BottomCenter))
        }
    }
    }

    if (showPicker) DatePick(vm.selectedDate, { showPicker = false }) { vm.selectAndFocus(it) }
}

/** Opens a day while retaining the parent period for Back. */
private fun openDay(vm: SunlithViewModel, date: LocalDate) {
    vm.openDay(date)
}

/** Events per day for [from]..[to]. Days that are not loaded yet give no events, never stale ones from another range. */
@Composable
private fun rememberDayEvents(vm: SunlithViewModel, from: LocalDate, to: LocalDate): (LocalDate) -> List<CalendarEvent> {
    val index = vm.eventsByDay
    val coverage = listOf(vm.loadedFrom, vm.loadedTo, vm.gridLoadedFrom, vm.gridLoadedTo)
    val visible = remember(index, coverage, from, to) {
        index.filter { (day, _) -> day >= from && day <= to && vm.coversRange(day, day) }
    }
    return remember(visible) { { d: LocalDate -> visible[d].orEmpty() } }
}

private fun boundedRange(range: String, date: LocalDate, mondayFirst: Boolean): Pair<LocalDate, LocalDate> = when (range) {
    "week" -> weekStart(date, mondayFirst).let { it to it.plusDays(6) }
    "day" -> date to date
    else -> YearMonth.from(date).let { it.atDay(1) to it.atEndOfMonth() }
}

/** 3 or 5 when the week shows a rolling timeline of that many days, 0 for the calendar week. */
private fun rollingDays(vm: SunlithViewModel): Int =
    if (vm.viewMode == ViewMode.Week && vm.weekLayout == "timeline" && vm.visibleWeekDays < 7) vm.visibleWeekDays else 0

private fun stepLabels(vm: SunlithViewModel): Pair<Int, Int> = when (vm.viewMode) {
    ViewMode.Month -> R.string.action_prev_month to R.string.action_next_month
    ViewMode.Week -> when (rollingDays(vm)) {
        3 -> R.string.calendar_prev_three_days to R.string.calendar_next_three_days
        5 -> R.string.calendar_prev_five_days to R.string.calendar_next_five_days
        else -> R.string.action_prev_week to R.string.action_next_week
    }
    ViewMode.Day -> R.string.action_prev_day to R.string.action_next_day
}

@Composable
private fun Header(vm: SunlithViewModel, f: Formats, onTitle: () -> Unit, onStep: (Int) -> Unit) {
    val p = LocalPalette.current
    val jumpLabel = stringResource(R.string.action_jump_to_date)
    val (prev, next) = stepLabels(vm)
    val prevLabel = stringResource(prev)
    val nextLabel = stringResource(next)
    val density = LocalDensity.current
    val titleMeasurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val arrows = vm.showNavigationArrows
        val searchInMenu = !vm.hasDayParent && arrows && (maxWidth < 480.dp || density.fontScale > 1.3f)
        val compactMenu = maxWidth < 380.dp || searchInMenu || density.fontScale > 1.3f
        val tabs = !vm.hasDayParent && density.fontScale <= 1.15f && maxWidth >= (if (arrows) 564.dp else 390.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.hasDayParent) IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), tint = p.ink) }
            if (arrows) IconButton(onClick = { onStep(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, prevLabel, tint = p.ink) }
            Column(
                Modifier.weight(1f).testTag("calendar_period_title").clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = jumpLabel, role = Role.Button, onClick = onTitle)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics {
                        heading()
                        contentDescription = if (vm.viewMode == ViewMode.Day) f.dayLong(vm.selectedDate) else f.monthYear(vm.month)
                        customActions = listOf(
                            CustomAccessibilityAction(prevLabel) { onStep(-1); true },
                            CustomAccessibilityAction(nextLabel) { onStep(1); true },
                        )
                    },
            ) {
                Text(vm.month.year.toString(), Modifier.clearAndSetSemantics {}, color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                BoxWithConstraints {
                    val full = if (vm.viewMode == ViewMode.Day) f.dayMonth(vm.selectedDate) else f.monthName(vm.month)
                    val compact = if (vm.viewMode == ViewMode.Day) f.numericDayMonth(vm.selectedDate) else f.shortMonth(vm.month)
                    val regular = MaterialTheme.typography.titleLarge
                    val width = with(density) { maxWidth.toPx() }
                    val title = if (titleMeasurer.measure(full, regular, softWrap = false, maxLines = 1).size.width <= width) full else compact
                    val style = if (titleMeasurer.measure(title, regular, softWrap = false, maxLines = 1).size.width <= width) regular else MaterialTheme.typography.bodyLarge
                    Text(title, Modifier.fillMaxWidth(), color = p.ink, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (arrows) IconButton(onClick = { onStep(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, nextLabel, tint = p.ink) }
            if (!vm.hasDayParent) {
                if (tabs) ModeTabs(vm, Modifier.width(168.dp)) else ViewMenu(vm, compactMenu, searchInMenu)
            }
            if (!searchInMenu) IconButton(onClick = vm::openSearch) { Icon(Icons.Outlined.Search, stringResource(R.string.action_search), tint = p.ink) }
            IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Outlined.SettingsIcon, stringResource(R.string.title_settings), tint = p.ink) }
        }
    }
}

private val viewLabels = listOf(ViewMode.Month to R.string.view_month, ViewMode.Week to R.string.view_week, ViewMode.Day to R.string.view_day)

@Composable
private fun ModeTabs(vm: SunlithViewModel, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.background(p.surface, RoundedCornerShape(10.dp)).padding(2.dp)) {
        viewLabels.forEach { (mode, label) ->
            val active = vm.viewMode == mode
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (active) p.raised else Color.Transparent)
                    .clickable(role = Role.Tab) { vm.changeViewMode(mode) }.semantics { selected = active },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), color = if (active) p.ink else p.muted, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ViewMenu(vm: SunlithViewModel, compact: Boolean, includeSearch: Boolean) {
    val p = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    val current = viewLabels.first { it.first == vm.viewMode }.second
    Box {
        if (compact) {
            IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("calendar_view_menu")) {
                Icon(when (vm.viewMode) {
                    ViewMode.Month -> Icons.Outlined.CalendarMonth
                    ViewMode.Week -> Icons.Outlined.CalendarViewWeek
                    ViewMode.Day -> Icons.Outlined.CalendarViewDay
                }, stringResource(R.string.calendar_switch_view), tint = p.ink)
            }
        } else {
            val switchLabel = stringResource(R.string.calendar_switch_view)
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("calendar_view_menu").semantics { contentDescription = switchLabel }) {
                Text(stringResource(current), color = p.ink, maxLines = 1)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = p.muted, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            viewLabels.forEach { (mode, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { expanded = false; vm.changeViewMode(mode) })
            }
            if (includeSearch) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.action_search)) }, onClick = { expanded = false; vm.openSearch() },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) })
            }
        }
    }
}

/**
 * Floating "Today" shortcut. It appears only when the shown period or the agenda's visible dates leave today, sits
 * centred above the bottom edge, and keeps clear of the add button on the right.
 */
@Composable
private fun TodayPill(direction: TodayDirection?, motion: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var lastDirection by remember { mutableStateOf(TodayDirection.Up) }
    val arrow = direction ?: lastDirection
    SideEffect { if (direction != null) lastDirection = direction }
    if (motion) {
        AnimatedVisibility(
            direction != null, modifier,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(120)),
        ) { TodayPillContent(arrow, onClick) }
    } else if (direction != null) {
        Box(modifier) { TodayPillContent(arrow, onClick) }
    }
}

@Composable
private fun TodayPillContent(direction: TodayDirection, onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = stringResource(R.string.action_today)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.padding(start = 88.dp, end = 88.dp, bottom = 20.dp).minimumInteractiveComponentSize()
            .testTag(if (direction == TodayDirection.Down) "calendar_today_down" else "calendar_today_up")
            .shadow(3.dp, shape).clip(shape).background(p.surface).border(1.dp, p.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (direction == TodayDirection.Down) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
            contentDescription = null, tint = p.ink, modifier = Modifier.size(22.dp))
    }
}

// region month, week and day bodies

@Composable
private fun MonthBody(c: CalendarContext, modifier: Modifier) {
    val vm = c.vm
    if (vm.monthLayout == "full") {
        val onOpenDay: (LocalDate) -> Unit = remember(vm) { { vm.openDay(it) } }
        val textMeasurer = rememberTextMeasurer(cacheSize = 256)
        val density = LocalDensity.current
        val typography = MaterialTheme.typography
        val number = with(density) { maxOf(26.dp, typography.bodyMedium.fontSize.toDp() * 1.9f) }
        val chip = with(density) { maxOf(16.dp, typography.labelSmall.lineHeight.toDp() + 4.dp) }
        Column(modifier) {
            WeekdayLabels(vm.weekStartsMonday, c.f)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val height = maxHeight
                val row = maxOf(height / 6, 56.dp, number + chip + 10.dp)
                PagedPeriod(c.swipe, c.period, Modifier.fillMaxSize()) { absolute ->
                    val d = rememberPeriodDate(c, absolute)
                    val active = absolute == c.period
                    val source = remember { Any() }
                    if (active) DisposableEffect(c.viewport) { onDispose { c.viewport.release(source) } }
                    val onDirectionChanged: (TodayDirection?) -> Unit = remember(c.viewport, source, active) {
                        { if (active) c.viewport.report(source, it) }
                    }
                    val month = remember(d.year, d.monthValue) { YearMonth.from(d) }
                    val start = gridStart(month, vm.weekStartsMonday)
                    val eventsOn = rememberDayEvents(vm, start, start.plusDays(41))
                    FullMonthGrid(
                        month, d, c.today, vm.weekStartsMonday, eventsOn, c.f, active && c.highlightToday,
                        onOpenDay = onOpenDay, row = row, number = number, chip = chip, viewportHeight = height, textMeasurer = textMeasurer,
                        todayRequest = vm.todayRequest, active = active,
                        onTodayDirectionChanged = onDirectionChanged,
                    )
                }
            }
        }
        return
    }
    if (vm.agendaRange != "continuous") {
        PagedPeriod(c.swipe, c.period, modifier) { absolute ->
            val d = rememberPeriodDate(c, absolute)
            val active = absolute == c.period
            CollapsingHeader(c.collapse, Modifier.fillMaxSize(), header = { MonthHeader(c, d, active) }) {
                val (from, to) = boundedRange(vm.agendaRange, d, vm.weekStartsMonday)
                Agenda(c, from, to, vm.agendaDisplay, d, continuous = false, active = active)
            }
        }
    } else {
        CollapsingHeader(c.collapse, modifier, header = {
            SlidingPeriod(c.swipe, c.period) { absolute -> MonthHeader(c, rememberPeriodDate(c, absolute), absolute == c.period) }
        }) {
            Agenda(c, vm.loadedFrom, vm.loadedTo, vm.agendaDisplay, vm.selectedDate, continuous = true, active = true)
        }
    }
}

@Composable
private fun rememberPeriodDate(c: CalendarContext, absolute: Long): LocalDate {
    val date = c.dateInPeriod(absolute)
    return remember(date) { date }
}

@Composable
private fun MonthHeader(c: CalendarContext, date: LocalDate, active: Boolean) {
    val vm = c.vm
    val month = remember(date.year, date.monthValue) { YearMonth.from(date) }
    val start = remember(month, vm.weekStartsMonday) { gridStart(month, vm.weekStartsMonday) }
    val eventsOn = rememberDayEvents(vm, start, start.plusDays(41))
    val onSelect: (LocalDate) -> Unit = remember(vm) { { vm.select(it) } }
    Column {
        MonthGrid(month, date, c.today, vm.weekStartsMonday, eventsOn, c.f, active && c.highlightToday, c.motion, bringSelected = active, c.dayMeasurer, onSelect)
        HorizontalDivider(color = LocalPalette.current.line)
    }
}

@Composable
private fun WeekBody(c: CalendarContext, modifier: Modifier) {
    val vm = c.vm
    val p = LocalPalette.current
    if (vm.weekLayout == "timeline") {
        val n = c.rollingDays.toLong()
        key(n, if (n > 0) Math.floorMod(c.rollingStart.toEpochDay(), n) else 0L) {
            PagedPeriod(c.swipe, c.period, modifier) { absolute ->
                val d = rememberPeriodDate(c, absolute)
                val days = remember(d, n, vm.weekStartsMonday) {
                    val start = if (n > 0) d else weekStart(d, vm.weekStartsMonday)
                    (0L until if (n > 0) n else 7L).map { start.plusDays(it) }
                }
                TimelinePeriod(c, days, if (absolute == c.period) 0 else 1, showHeaders = true)
            }
        }
        return
    }
    PagedPeriod(c.swipe, c.period, modifier) { absolute ->
        val d = rememberPeriodDate(c, absolute)
        val active = absolute == c.period
        CollapsingHeader(c.collapse, Modifier.fillMaxSize(), header = {
            Column {
                WeekdayLabels(vm.weekStartsMonday, c.f)
                WeekStrip(c, d, active)
                HorizontalDivider(color = p.line)
            }
        }) {
            val start = weekStart(d, vm.weekStartsMonday)
            Agenda(c, start, start.plusDays(6), "all", d, continuous = false, active = active)
        }
    }
}

/** Day view: a date strip for picking a day in the selected week, then cards, a timeline or the continuous schedule. */
@Composable
private fun DayBody(c: CalendarContext, modifier: Modifier) {
    if (c.vm.dayLayout == "schedule") DayPeriod(c, modifier, c.vm.selectedDate, true)
    else PagedPeriod(c.swipe, c.period, modifier) { absolute -> DayPeriod(c, Modifier.fillMaxSize(), rememberPeriodDate(c, absolute), absolute == c.period) }
}

@Composable
private fun DayPeriod(c: CalendarContext, modifier: Modifier, date: LocalDate, active: Boolean) {
    val vm = c.vm
    val p = LocalPalette.current
    Column(modifier) {
        if (vm.dayLayout != "cards") StatusCards(vm, Modifier.padding(horizontal = 12.dp))
        CollapsingHeader(c.collapse, Modifier.weight(1f), header = {
            Column(Modifier.testTag("calendar_day_picker")) {
                WeekdayLabels(vm.weekStartsMonday, c.f)
                WeekStrip(c, date, active = active)
                HorizontalDivider(color = p.line)
            }
        }) {
            when (vm.dayLayout) {
                "timeline" -> TimelinePeriod(c, remember(date) { listOf(date) }, if (active) 0 else 1, showHeaders = false)
                "schedule" -> key(vm.dayFocusRequest) {
                    Agenda(c, vm.loadedFrom, vm.loadedTo, vm.agendaDisplay, vm.selectedDate, continuous = true, active = true, compact = true)
                }
                else -> DayCards(c, date, active = active)
            }
        }
    }
}

@Composable
private fun DayCards(c: CalendarContext, day: LocalDate, active: Boolean) {
    val vm = c.vm
    if (!vm.coversRange(day, day)) {
        if (active && vm.loadError == null) CalendarLoading() else Box(Modifier.fillMaxSize())
        return
    }
    val eventsOn = rememberDayEvents(vm, day, day)
    key(day, vm.dayFocusRequest) { DayView(vm, day, eventsOn(day), c.f, showStatus = active,
        focus = if (active) c.readyFocus else null,
        onFocusHandled = c.onFocusHandled, motion = c.motion) }
}

@Composable
private fun TimelinePeriod(c: CalendarContext, days: List<LocalDate>, k: Int, showHeaders: Boolean) {
    val vm = c.vm
    val first = days.first()
    val last = days.last()
    if (!vm.coversRange(first, last)) {
        if (k == 0 && vm.loadError == null) CalendarLoading() else Box(Modifier.fillMaxSize())
        return
    }
    val eventsOn = rememberDayEvents(vm, first, last)
    val reset = if (vm.viewMode == ViewMode.Day) vm.dayFocusRequest else 0
    key(reset) {
        val scroll = rememberSaveable(saver = ScrollState.Saver) {
            ScrollState(if (k == 0 && c.timelineFocus != null) 0 else c.timelineScroll.value)
        }
        if (k == 0) LaunchedEffect(scroll) {
            snapshotFlow { scroll.value }.collect { c.timelineScroll.scrollTo(it) }
        } else LaunchedEffect(scroll, c.swipe) {
            snapshotFlow { c.swipe.pager?.isScrollInProgress == true }.collect { moving ->
                if (moving) scroll.scrollTo(c.timelineScroll.value)
            }
        }
        var overflowEvents by remember { mutableStateOf(emptyList<CalendarEvent>()) }
        Timeline(
            days, eventsOn, vm.selectedDate, c.today, c.f, showHeaders, scroll,
            autoScroll = k == 0, todayRequest = vm.todayRequest, motion = c.motion,
            onSelectDay = vm::selectAndFocus,
            onOpen = { vm.open(Screen.Details(it)) },
            onNew = { vm.newEvent(it) },
            onOverflow = { day, hidden -> if (days.size > 1) openDay(vm, day) else overflowEvents = hidden },
            focus = if (k == 0) c.readyFocus else null,
            onFocusHandled = c.onFocusHandled,
            // Columns stay readable even when period swiping is disabled.
            allowHorizontalScroll = true,
        ) {
            val selected = vm.selectedDate.coerceIn(first, last)
            Agenda(c, first, last, "all", selected, continuous = false, active = k == 0, compact = vm.viewMode == ViewMode.Day)
        }
        if (overflowEvents.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { overflowEvents = emptyList() },
                title = { Text(LocalContext.current.resources.getQuantityString(R.plurals.event_count, overflowEvents.size, overflowEvents.size)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        overflowEvents.forEach { event ->
                            TextButton(onClick = { overflowEvents = emptyList(); vm.open(Screen.Details(event)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(event.title.ifBlank { stringResource(R.string.untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { overflowEvents = emptyList() }) { Text(stringResource(R.string.action_close)) } },
            )
        }
    }
}

private fun LocalDate.coerceIn(from: LocalDate, to: LocalDate): LocalDate = if (isBefore(from)) from else if (isAfter(to)) to else this

// endregion

/**
 * Collapse state for the grid above the agenda. Scrolling the agenda forward (or dragging the grid) first slides
 * the grid away. Scrolling back only brings it back once the agenda is at the selected day again ([canExpand]),
 * so reading earlier rows does not keep pulling the grid in, and the agenda can scroll both ways.
 */
@Stable
private class HeaderCollapse(val enabled: Boolean) {
    var offset by mutableFloatStateOf(0f)
    var headerHeight = 0
    var canExpand: () -> Boolean = { true }

    fun consume(delta: Float): Float {
        if (!enabled) {
            offset = 0f
            return 0f
        }
        val height = headerHeight.toFloat()
        val old = offset.coerceIn(-height, 0f)
        val new = (old + delta).coerceIn(-height, 0f)
        offset = new
        return new - old
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (!enabled) Offset.Zero else if (available.y > 0f && !canExpand()) Offset.Zero else Offset(0f, consume(available.y))
    }
}

/**
 * Places [header] above [body]. When the header is taller than about two thirds of the space (landscape or very
 * large text), only half the space shows it at once, and it scrolls inside that area, so the body stays visible.
 */
@Composable
private fun CollapsingHeader(state: HeaderCollapse, modifier: Modifier, header: @Composable () -> Unit, body: @Composable () -> Unit) {
    val p = LocalPalette.current
    val headerScroll = rememberScrollState()
    LaunchedEffect(state.enabled) { headerScroll.scrollTo(0) }
    Layout(
        content = {
            Box(
                Modifier.testTag("calendar_grid").then(
                    if (state.enabled) Modifier.draggable(rememberDraggableState { state.consume(it) }, Orientation.Vertical)
                    else Modifier.verticalScroll(headerScroll),
                ),
            ) { header() }
            Box(Modifier.background(p.background)) { body() }
        },
        modifier = modifier.fillMaxWidth().clipToBounds().then(if (state.enabled) Modifier.nestedScroll(state.connection) else Modifier),
    ) { measurables, constraints ->
        val total = constraints.maxHeight
        val headerMax = if (state.enabled) Constraints.Infinity else (total * 0.6f).roundToInt().coerceAtLeast(1)
        val head = measurables[0].measure(constraints.copy(minHeight = 0, maxHeight = headerMax))
        state.headerHeight = head.height
        val cap = if (head.height > total * 0.65f) total / 2 else head.height
        val offset = state.offset.coerceIn(-head.height.toFloat(), 0f).roundToInt()
        val visible = if (state.enabled) (head.height + offset).coerceIn(0, cap) else head.height
        val content = measurables[1].measure(Constraints.fixed(constraints.maxWidth, (total - visible).coerceAtLeast(0)))
        layout(constraints.maxWidth, total) {
            head.place(0, if (state.enabled) offset else 0)
            content.place(0, visible)
        }
    }
}

@Composable
internal fun WeekdayLabels(mondayFirst: Boolean, f: Formats) {
    val p = LocalPalette.current
    val narrow = LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).clearAndSetSemantics { }) {
        weekDays(mondayFirst).forEach { dow ->
            Text(
                (if (narrow) f.weekdayNarrow(dow) else f.weekdayShort(dow)), Modifier.weight(1f), color = p.muted, style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip,
            )
        }
    }
}

/** Always six rows, so the grid keeps the same height in every month and the agenda never jumps. */
@Composable
private fun MonthGrid(
    month: YearMonth, selected: LocalDate, today: LocalDate, mondayFirst: Boolean, eventsOn: (LocalDate) -> List<CalendarEvent>,
    f: Formats, pulseToday: Boolean, motion: Boolean, bringSelected: Boolean, measurer: TextMeasurer, onSelect: (LocalDate) -> Unit,
) {
    val start = remember(month, mondayFirst) { gridStart(month, mondayFirst) }
    val dates = remember(start) { List(42) { start.plusDays(it.toLong()) } }
    val selectedCell = remember { BringIntoViewRequester() }
    if (bringSelected) LaunchedEffect(selected, pulseToday) { selectedCell.bringIntoView() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        WeekdayLabels(mondayFirst, f)
        Column(Modifier.fillMaxWidth()) {
            repeat(GRID_ROWS) { r ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { c ->
                        val day = dates[r * 7 + c]
                        key(day) {
                            DayCell(
                                day, day == selected, day == today, YearMonth.from(day) == month, eventsOn(day), f,
                                pulseToday && day == today, motion, measurer,
                                Modifier.weight(1f).then(if (bringSelected && day == selected) Modifier.bringIntoViewRequester(selectedCell) else Modifier), onSelect,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Seven dates of the week containing [date]. Used as the Week header and as the Day date picker. */
@Composable
private fun WeekStrip(c: CalendarContext, date: LocalDate, active: Boolean) {
    val vm = c.vm
    val start = remember(date, vm.weekStartsMonday) { weekStart(date, vm.weekStartsMonday) }
    val dates = remember(start) { List(7) { start.plusDays(it.toLong()) } }
    val onSelect: (LocalDate) -> Unit = remember(vm) { { vm.selectAndFocus(it) } }
    val eventsOn = rememberDayEvents(vm, start, start.plusDays(6))
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        dates.forEach { day ->
            key(day) {
                DayCell(
                    day, date == day, day == c.today, true, eventsOn(day), c.f, active && c.highlightToday && day == c.today, c.motion, c.dayMeasurer, Modifier.weight(1f),
                    onSelect = onSelect,
                )
            }
        }
    }
}

/** Circle size for a date number: grows with the font size, but never wider than its column. */
@Composable
private fun dayCircle(): Dp {
    val size = MaterialTheme.typography.bodyLarge.fontSize
    return with(LocalDensity.current) { maxOf(40.dp, size.toDp() * 2.25f) }
}

@Composable
private fun DayCell(
    day: LocalDate, isSelected: Boolean, isToday: Boolean, inMonth: Boolean, events: List<CalendarEvent>, f: Formats,
    pulse: Boolean, motion: Boolean, measurer: TextMeasurer, modifier: Modifier, onSelect: (LocalDate) -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val description = remember(day, isToday, events.size, f) { dayDescription(context, f, day, isToday, events.size) }
    val accents = events.take(3).map { eventAccent(it) }
    val circle = dayCircle()
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = when { isSelected -> p.onAccent; inMonth -> p.ink; else -> p.faint },
        fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
    val scale = if (pulse && motion) remember { Animatable(1f) }.also { value ->
        LaunchedEffect(value) {
            value.animateTo(1.18f, tween(140))
            value.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    } else null
    Box(
        modifier.layout { measurable, constraints ->
            val width = constraints.maxWidth
            val diameter = minOf(circle.roundToPx(), (width - 4.dp.roundToPx()).coerceAtLeast(0))
            val height = maxOf(48.dp.roundToPx(), diameter + 14.dp.roundToPx())
            val child = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { child.place(0, 0) }
        }
            .drawWithCache {
                val diameter = minOf(circle.toPx(), (size.width - 4.dp.toPx()).coerceAtLeast(0f))
                val center = Offset(size.width / 2, 3.dp.toPx() + diameter / 2)
                val text = measurer.measure(day.dayOfMonth.toString(), style, maxLines = 1, softWrap = false,
                    constraints = Constraints(maxWidth = diameter.toInt().coerceAtLeast(0)))
                val dots = accents.map { if (inMonth) it else p.faint }
                onDrawBehind {
                    scale(scale?.value ?: 1f, pivot = center) {
                        if (isSelected) drawCircle(p.accent, diameter / 2, center)
                        else if (isToday) drawCircle(p.ink, diameter / 2 - 0.75.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                        drawText(text, topLeft = Offset(center.x - text.size.width / 2, center.y - text.size.height / 2))
                    }
                    val dotSize = 5.dp.toPx()
                    val gap = 3.dp.toPx()
                    val left = (size.width - dots.size * dotSize - (dots.size - 1).coerceAtLeast(0) * gap) / 2
                    dots.forEachIndexed { index, color ->
                        drawCircle(color, dotSize / 2, Offset(left + index * (dotSize + gap) + dotSize / 2, 6.dp.toPx() + diameter + dotSize / 2))
                    }
                }
            }
            .clickable(interactionSource = null, indication = ripple()) { onSelect(day) }
            .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button },
    )
}

@Composable
private fun StatusCards(vm: SunlithViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        vm.loadError?.let { InfoCard(it, stringResource(R.string.action_retry)) { vm.reload() } }
        if (vm.loaded && vm.loadError == null) {
            when {
                vm.calendars.isEmpty() -> InfoCard(stringResource(R.string.no_calendars_on_phone), stringResource(R.string.action_create_local)) { vm.createLocalCalendar() }
                vm.activeIds.isEmpty() -> InfoCard(stringResource(R.string.no_calendars_selected), stringResource(R.string.action_choose_calendars)) { vm.open(Screen.Calendars) }
            }
        }
    }
}

// region agenda

/**
 * The agenda for [from]..[to]. A bounded agenda (one month, week or day) is a fresh list for each period and starts
 * at the selected date. The continuous agenda is one list for the whole loaded window: it keeps its scroll position
 * by row key while dates are added or dropped at either end, loads more near the edges while the user scrolls that
 * way, and only jumps when the selected date or Today changes. Nothing is shown for dates that are not loaded.
 */
@Composable
private fun Agenda(
    c: CalendarContext, from: LocalDate?, to: LocalDate?, display: String, selected: LocalDate,
    continuous: Boolean, active: Boolean, compact: Boolean = false,
) {
    val vm = c.vm
    if (from == null || to == null || !vm.coversRange(from, to) ||
        (compact && continuous && (selected.isBefore(from) || selected.isAfter(to)))) {
        if (active && vm.loadError == null) CalendarLoading() else Box(Modifier.fillMaxSize())
        return
    }
    val index = vm.eventsByDay
    val today = c.today
    val keptSelected = selected.takeIf { display == "compact" || compact }
    val rows = remember(index, from, to, display, keptSelected, today, continuous, compact) {
        buildAgenda(from, to, index, display, setOfNotNull(keptSelected, today), monthHeaders = continuous, alwaysShow = selected.takeIf { compact }, splitEvents = !compact)
    }
    if (continuous) AgendaList(c, rows, from, to, selected, true, active, compact)
    else key(from, to, display) { AgendaList(c, rows, from, to, selected, false, active, compact) }
}

@Composable
private fun AgendaList(
    c: CalendarContext, rows: List<AgendaRow>, from: LocalDate, to: LocalDate, selected: LocalDate,
    continuous: Boolean, active: Boolean, compact: Boolean,
) {
    val vm = c.vm
    val p = LocalPalette.current
    val f = c.f
    val today = c.today
    val density = LocalDensity.current
    val badgeStyle = MaterialTheme.typography.labelMedium
    val badgeCircle = with(density) { maxOf(34.dp, MaterialTheme.typography.titleMedium.fontSize.toDp() * 2f) }
    val badgeWidth = remember(f, c.dayMeasurer, density, badgeStyle, badgeCircle) {
        with(density) {
            maxOf(56.dp, badgeCircle + 8.dp, java.time.DayOfWeek.values().maxOf {
                c.dayMeasurer.measure(f.weekdayShort(it), badgeStyle, maxLines = 1).size.width.toDp()
            } + 8.dp)
        }
    }
    val anchor = remember { targetIndex(rows, selected).coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = anchor)
    val scheduleFocus = c.timelineFocus?.takeIf { active && compact && it.date == selected }
    val focusReady = scheduleFocus?.let {
        c.readyFocus === it && !it.date.isBefore(from) && !it.date.isAfter(to) && vm.coversRange(it.date, it.date)
    } == true
    val focusEvent = remember(scheduleFocus, rows) {
        scheduleFocus?.let { request ->
            focusTarget(rows.filterIsInstance<AgendaRow.Day>().firstOrNull { it.date == request.date }?.events.orEmpty(),
                request.date, ZoneId.systemDefault())
        }
    }
    var focusTop by remember(scheduleFocus) { mutableFloatStateOf(Float.NaN) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var focusedEvent by remember { mutableStateOf<String?>(null) }
    // A restored Today page must handle a newer request; other pages keep their saved scroll.
    var savedRequest by rememberSaveable { mutableIntStateOf(vm.todayRequest) }
    var handled by remember {
        mutableStateOf(selected to if (selected == today && savedRequest < vm.todayRequest) savedRequest else vm.todayRequest)
    }
    var pending by remember { mutableStateOf<LocalDate?>(null) }
    var animateNavigation by remember { mutableStateOf(c.motion) }
    val navigation = selected to vm.todayRequest
    val previousFrom = remember { arrayOf(from) }
    if (continuous && previousFrom[0] != from) {
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
        val anchorIndex = visible?.let { item -> rows.indexOfFirst { it.key == item.key } } ?: -1
        val anchorOffset = listState.firstVisibleItemScrollOffset
        SideEffect {
            previousFrom[0] = from
            // A large prepend can move the key beyond LazyColumn's nearby-key lookup window.
            if (navigation == handled && pending == null && scheduleFocus == null && anchorIndex >= 0) {
                listState.requestScrollToItem(anchorIndex, anchorOffset)
            }
        }
    }
    val focusLead = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(focusedEvent) { if (focusedEvent != null) { delay(1800); focusedEvent = null } }
    LaunchedEffect(scheduleFocus, focusReady) {
        val request = scheduleFocus ?: return@LaunchedEffect
        if (!focusReady) return@LaunchedEffect
        handled = navigation
        savedRequest = navigation.second
        pending = null
        request.start()
        try {
            awaitFocusStart(c.motion)
            if (focusEvent != null) {
                val top = withTimeoutOrNull(1_500) { snapshotFlow { focusTop }.first { it.isFinite() } }
                if (top == null) return@LaunchedEffect
                val distance = (top - viewportTop - focusLead).coerceAtLeast(0f)
                val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                if (c.motion) listState.animateScrollBy(distance, tween(focusScrollDuration(distance, viewport)))
                else listState.scrollBy(distance)
            }
            focusedEvent = focusEvent?.key()
        } finally { c.onFocusHandled(request) }
    }
    if (active) {
        SideEffect { c.collapse.canExpand = { listState.firstVisibleItemIndex <= targetIndex(rows, selected).coerceAtLeast(0) } }
        DisposableEffect(c.collapse) { onDispose { c.collapse.canExpand = { true } } }
    }

    LaunchedEffect(navigation) {
        if (!active || navigation == handled) return@LaunchedEffect
        animateNavigation = c.motion && !(continuous && vm.viewMode == ViewMode.Month &&
            YearMonth.from(handled.first) != YearMonth.from(selected))
        handled = navigation
        savedRequest = navigation.second
        pending = selected.takeIf { scheduleFocus == null }
    }
    LaunchedEffect(pending, rows, scheduleFocus) {
        if (scheduleFocus != null) return@LaunchedEffect
        val target = pending ?: return@LaunchedEffect
        if (target.isBefore(from) || target.isAfter(to)) return@LaunchedEffect
        val row = targetIndex(rows, target)
        if (row >= 0) {
            if (animateNavigation) listState.animateScrollToItem(row) else listState.requestScrollToItem(row)
        }
        pending = null
    }

    if (active) {
        LaunchedEffect(listState, rows, from, to, today, c.viewport) {
            snapshotFlow {
                val info = listState.layoutInfo
                val visible = info.visibleItemsInfo.filter {
                    it.index in rows.indices && rows[it.index] !is AgendaRow.Month &&
                        it.offset + it.size > info.viewportStartOffset && it.offset < info.viewportEndOffset
                }
                if (visible.isEmpty()) null
                else {
                    val first = visible.first().index
                    val last = visible.last().index
                    var previous = first - 1
                    while (previous >= 0 && rows[previous] is AgendaRow.Month) previous--
                    var next = last + 1
                    while (next < rows.size && rows[next] is AgendaRow.Month) next++
                    // Events-only rows also represent the omitted empty dates beside them. Continuations
                    // of the same date must keep that date until the first/last event is visible.
                    val start = if (previous < 0) from else minOf(rows[first].first, rows[previous].last.plusDays(1))
                    val end = if (next == rows.size) to else maxOf(rows[last].last, rows[next].first.minusDays(1))
                    start to end
                }
            }.collect { dates ->
                if (dates == null) c.viewport.release(listState)
                else c.viewport.report(listState, todayDirection(today, dates.first, dates.second))
            }
        }
        DisposableEffect(c.viewport) { onDispose { c.viewport.release(listState) } }
    }

    var lastDirection by remember { mutableStateOf<Boolean?>(null) }
    val extend: (Boolean) -> Unit = { forward ->
        if (vm.loadError != null) vm.reload() else { lastDirection = forward; vm.extendAgenda(forward) }
    }
    if (continuous && active) {
        val focusing by rememberUpdatedState(scheduleFocus != null)
        LaunchedEffect(listState) {
            var firedAt: Any? = null
            snapshotFlow {
                val info = listState.layoutInfo
                val visible = info.visibleItemsInfo
                if (!listState.isScrollInProgress || focusing || pending != null || visible.isEmpty() || vm.extendingAgenda || vm.loadError != null) null
                else {
                    val position = visible.first().key to listState.firstVisibleItemScrollOffset
                    when {
                        visible.first().index <= EDGE_ROWS && listState.lastScrolledBackward -> false to position
                        visible.last().index >= info.totalItemsCount - 1 - EDGE_ROWS && listState.lastScrolledForward -> true to position
                        else -> null
                    }
                }
            }.collect { request ->
                // Fire once per scroll position, so a sparse calendar never pages on its own while the list is at rest.
                if (request == null || request.second == firedAt) return@collect
                firedAt = request.second
                lastDirection = request.first
                vm.extendAgenda(request.first)
            }
        }
    }

    val listContent: @Composable (Dp) -> Unit = { gutter ->
        Box(Modifier.fillMaxSize().then(if (compact) Modifier.testTag("calendar_schedule") else Modifier)) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("calendar_agenda").onGloballyPositioned { viewportTop = it.positionInRoot().y }, state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 104.dp),
            ) {
                items(rows, key = { it.key }, contentType = { it::class }) { row ->
                    when (row) {
                        is AgendaRow.Month -> MonthLabel(row.month, f)
                        is AgendaRow.Day -> {
                            val highlighted = active && c.highlightToday && row.date == today
                            if (compact) ScheduleDay(row, row.date == selected, row.date == today, highlighted, c.motion, f, vm, gutter, c.dayMeasurer,
                                focusTarget = focusEvent?.key().takeIf { row.date == selected },
                                highlightedEvent = focusedEvent.takeIf { row.date == selected }, onFocusPosition = { focusTop = it })
                            else AgendaDay(row, row.date == selected, row.date == today, highlighted, c.motion, f, vm, badgeWidth, c.dayMeasurer)
                        }
                        is AgendaRow.Empty -> EmptyRange(row, f) { vm.select(row.from) }
                    }
                }
                if (rows.isEmpty() && vm.loaded) {
                    item(key = "none") {
                        Text(stringResource(R.string.no_events), color = p.muted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp))
                    }
                }
                if (continuous) {
                    item(key = "edge-later") {
                        AgendaEdge(vm.extendingAgenda && lastDirection == true, R.string.calendar_show_later, Modifier.fillMaxWidth()) { extend(true) }
                    }
                }
            }
            if (continuous && !listState.canScrollBackward) {
                AgendaEdge(
                    vm.extendingAgenda && lastDirection == false, R.string.calendar_show_earlier,
                    Modifier.align(Alignment.TopCenter).padding(top = 4.dp), pill = true,
                ) { extend(false) }
            }
        }
    }
    if (compact) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val allDayText = stringResource(R.string.all_day)
            val gutter = rememberTimeGutter(
                listOf(f.time(LocalTime.of(22, 55)), f.time(LocalTime.of(10, 55)), allDayText),
                MaterialTheme.typography.bodyMedium, 56.dp, maxWidth * 0.3f,
            )
            listContent(gutter)
        }
    } else listContent(0.dp)

}

/** End of the loaded dates: a small spinner while loading, otherwise a button to load more (also the retry after an error). */
@Composable
private fun AgendaEdge(loading: Boolean, label: Int, modifier: Modifier, pill: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier.heightIn(min = 56.dp).then(
            if (pill) Modifier.padding(4.dp).shadow(2.dp, shape).clip(shape).background(p.surface).border(1.dp, p.line, shape) else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            val description = stringResource(R.string.calendar_loading_dates)
            Box(Modifier.size(48.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = p.muted, strokeWidth = 2.dp)
            }
        } else {
            TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label), maxLines = 2, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun MonthLabel(month: YearMonth, f: Formats) {
    val p = LocalPalette.current
    Text(
        f.monthYear(month).uppercase(f.locale),
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
        color = p.muted, style = SectionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CalendarLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), color = LocalPalette.current.muted, strokeWidth = 2.dp)
    }
}

@Composable
private fun DateBadge(date: LocalDate, isToday: Boolean, isSelected: Boolean, f: Formats, measurer: TextMeasurer, width: Dp? = null) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val circle = with(density) { maxOf(34.dp, MaterialTheme.typography.titleMedium.fontSize.toDp() * 2f) }
    val numberStyle = MaterialTheme.typography.titleMedium.copy(
        color = when { isToday -> p.onAccent; isSelected -> p.ink; else -> p.muted },
        fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val weekdayStyle = MaterialTheme.typography.labelMedium.copy(color = if (isToday || isSelected) p.ink else p.muted)
    val weekday = f.weekdayShort(date.dayOfWeek)
    val weekdayLayout = remember(measurer, weekday, weekdayStyle, density) { measurer.measure(weekday, weekdayStyle, maxLines = 1) }
    val badgeWidth = width ?: with(density) { maxOf(56.dp, circle + 8.dp, weekdayLayout.size.width.toDp() + 8.dp) }
    val labelHeight = with(density) { weekdayLayout.size.height.toDp() }
    val description = remember(date, f) { f.dayLong(date) }
    Box(Modifier.width(badgeWidth).padding(end = 8.dp).height(circle + labelHeight)
        .clearAndSetSemantics { contentDescription = description; selected = isSelected }
        .drawWithCache {
            val diameter = circle.toPx()
            val number = measurer.measure(date.dayOfMonth.toString(), numberStyle, maxLines = 1, softWrap = false,
                constraints = Constraints(maxWidth = diameter.toInt().coerceAtLeast(0)))
            onDrawBehind {
                if (isToday) drawCircle(p.accent, diameter / 2, Offset(size.width / 2, diameter / 2))
                drawText(number, topLeft = Offset((size.width - number.size.width) / 2, (diameter - number.size.height) / 2))
                drawText(weekdayLayout, topLeft = Offset((size.width - weekdayLayout.size.width) / 2, diameter))
            }
        })
}

@Composable
private fun AgendaDay(row: AgendaRow.Day, isSelected: Boolean, isToday: Boolean, highlighted: Boolean, motion: Boolean, f: Formats, vm: SunlithViewModel, badgeWidth: Dp, measurer: TextMeasurer) {
    val p = LocalPalette.current
    val background by animateColorAsState(
        if (highlighted) p.raised else Color.Transparent,
        animationSpec = if (motion) tween(300) else snap(),
        label = "today",
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(background)
        .padding(vertical = if (row.continuation) 0.dp else 3.dp)) {
        if (row.continuation) Spacer(Modifier.width(badgeWidth)) else DateBadge(row.date, isToday, isSelected, f, measurer, badgeWidth)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.events.isEmpty()) {
                EmptyDayButton(row.date, vm)
            } else {
                row.events.forEach { e -> CalendarEventCard(e, row.date, f, onClick = { vm.open(Screen.Details(e)) }) }
            }
        }
    }
}

@Composable
private fun EmptyDayButton(date: LocalDate, vm: SunlithViewModel) {
    val p = LocalPalette.current
    val add = stringResource(R.string.action_add_event)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = add, role = Role.Button) { vm.newEvent(date) }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(stringResource(R.string.no_events), color = p.faint, style = MaterialTheme.typography.bodyMedium) }
}

/** Compact schedule day: times sit beside titles, or above them when text is large. */
@Composable
private fun ScheduleDay(row: AgendaRow.Day, isSelected: Boolean, isToday: Boolean, highlighted: Boolean, motion: Boolean, f: Formats, vm: SunlithViewModel, gutter: Dp, measurer: TextMeasurer,
    focusTarget: String?, highlightedEvent: String?, onFocusPosition: (Float) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val stacked = LocalDensity.current.fontScale > 1.3f
    val background by animateColorAsState(
        if (highlighted) p.raised else Color.Transparent,
        animationSpec = if (motion) tween(300) else snap(),
        label = "today",
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp)).background(background).padding(vertical = 2.dp)) {
        DateBadge(row.date, isToday, isSelected, f, measurer)
        Column(Modifier.weight(1f)) {
            if (row.events.isEmpty()) {
                EmptyDayButton(row.date, vm)
            } else {
                row.events.forEach { e ->
                    val title = e.displayTitle().ifBlank { stringResource(R.string.untitled) }
                    val status = absenceStatus(e, f)
                    val time = if (e.allDay || e.span(zone).multiDay) context.timeLabel(e, row.date, zone, f) else f.time(e.startMillis)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                            .then(if (e.key() == focusTarget) Modifier.onGloballyPositioned { onFocusPosition(it.positionInRoot().y) } else Modifier)
                            .focusRing(e.key() == highlightedEvent, p.ink, RoundedCornerShape(8.dp), motion)
                            .clickable(role = Role.Button) { vm.open(Screen.Details(e)) }
                            .testTag("schedule_event_${e.id}")
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (!stacked) Text(time, Modifier.width(gutter).padding(end = 8.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium)
                        Box(Modifier.padding(top = 6.dp).size(8.dp).background(eventAccent(e), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            if (stacked) Text(time, color = p.muted, style = MaterialTheme.typography.bodyMedium)
                            Text(title, color = p.ink, style = MaterialTheme.typography.bodyLarge)
                            if (status != null) Text(status, color = p.ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (e.location.isNotBlank()) {
                                Text(e.location, color = p.muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyRange(row: AgendaRow.Empty, f: Formats, onClick: () -> Unit) {
    val p = LocalPalette.current
    val short = { d: LocalDate -> "${f.weekdayShort(d.dayOfWeek)} ${d.dayOfMonth}" }
    val label = if (row.from == row.to) short(row.from) else f.range(short(row.from), short(row.to))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.faint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        Text("  " + stringResource(R.string.no_events), color = p.faint, style = MaterialTheme.typography.bodyMedium)
    }
}

// endregion

/** Day cards: all-day events first, then timed events with their start time in a shared column on the left. */
@Composable
private fun DayView(vm: SunlithViewModel, day: LocalDate, events: List<CalendarEvent>, f: Formats, showStatus: Boolean,
    focus: TimelineFocus?, onFocusHandled: (TimelineFocus) -> Unit, motion: Boolean) {
    val p = LocalPalette.current
    val today = vm.today
    val zone = ZoneId.systemDefault()
    val allDay = events.filter { it.allDay || it.span(zone).multiDay }
    val timed = events - allDay.toSet()
    val listState = rememberLazyListState()
    var focusedEvent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedEvent) { if (focusedEvent != null) { delay(1800); focusedEvent = null } }
    LaunchedEffect(focus) {
        val request = focus ?: return@LaunchedEffect
        if (request.date != day) return@LaunchedEffect
        request.start()
        try {
            awaitFocusStart(motion)
            val target = focusTarget(events, day, zone)
            val index = (allDay + timed).indexOfFirst { it.key() == target?.key() }
            if (index >= 0 && target?.isTimelineBanner() == false) {
                val row = index + if (showStatus) 2 else 1
                listState.scrollToFocus(row, motion)
                focusedEvent = target?.key()
            }
        } finally { onFocusHandled(request) }
    }
    val timeStyle = MaterialTheme.typography.bodyMedium
    val stacked = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val timeWidth = rememberTimeGutter(timed.map { f.time(it.startMillis) }, timeStyle, 64.dp, (maxWidth - 32.dp) * 0.4f)
        LazyColumn(Modifier.fillMaxSize().testTag("calendar_day_cards"), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showStatus) item(key = "status") { StatusCards(vm) }
            item(key = "head") {
                Column(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) { heading() }) {
                    Text(f.dayLong(day), color = p.ink, style = MaterialTheme.typography.titleLarge)
                    if (day == today) Text(stringResource(R.string.today), color = p.muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (events.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.no_events), color = p.muted, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { vm.newEvent(day) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_add_event)) }
                    }
                }
            }
            items(allDay, key = { "a-" + it.key() }) { e -> CalendarEventCard(e, day, f, onClick = { vm.open(Screen.Details(e)) },
                modifier = Modifier.focusRing(e.key() == focusedEvent, eventColors(e).content, RoundedCornerShape(10.dp), motion)) }
            itemsIndexed(timed, key = { _, e -> "t-" + e.key() }) { index, e ->
                Row(verticalAlignment = Alignment.Top) {
                    if (!stacked && (index == 0 || timed[index - 1].startMillis != e.startMillis)) {
                        Text(
                            f.time(e.startMillis), Modifier.width(timeWidth).padding(top = 12.dp, end = 8.dp).testTag("day_time_${e.id}"),
                            color = p.muted, style = timeStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    } else if (!stacked) Spacer(Modifier.width(timeWidth))
                    CalendarEventCard(e, day, f, onClick = { vm.open(Screen.Details(e)) }, modifier = Modifier.weight(1f).testTag("day_event_${e.id}")
                        .focusRing(e.key() == focusedEvent, eventColors(e).content, RoundedCornerShape(10.dp), motion))
                }
            }
            item(key = "spacer") { Spacer(Modifier.width(1.dp).height(1.dp)) }
        }
    }
}
