package io.github.sunsind.sunlith.ui

import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.text.font.FontWeight
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

    data class Day(val date: LocalDate, val events: List<CalendarEvent>) : AgendaRow {
        override val key get() = "d-$date"
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
): List<AgendaRow> {
    val rows = mutableListOf<AgendaRow>()
    var emptyStart: LocalDate? = null
    var day = from
    while (!day.isAfter(to)) {
        val events = index[day].orEmpty()
        val show = events.isNotEmpty() || display == "all" || (display == "compact" && day in keep)
        val monthStart = day.dayOfMonth == 1
        emptyStart?.let { start ->
            if (show || monthStart) { rows += AgendaRow.Empty(start, day.minusDays(1)); emptyStart = null }
        }
        if (monthHeaders && (day == from || monthStart)) rows += AgendaRow.Month(YearMonth.from(day))
        if (show) {
            rows += AgendaRow.Day(day, events)
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
    val configuration = LocalConfiguration.current
    val systemOff = remember(context, configuration) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    return enabled && !systemOff
}

/** Whether the visible part of the agenda is away from today. Only scrolling sets it; the selected date never changes from scrolling. */
@Stable
private class AgendaViewport {
    var away by mutableStateOf(false)
}

/** First date of the three-day timeline. It only moves when the selected date leaves the three shown days. */
private class WindowStart(var start: LocalDate? = null) {
    companion object {
        val Saver = Saver<WindowStart, Long>(
            save = { it.start?.toEpochDay() ?: Long.MIN_VALUE },
            restore = { WindowStart(if (it == Long.MIN_VALUE) null else LocalDate.ofEpochDay(it)) },
        )
    }

    fun startFor(selected: LocalDate): LocalDate {
        val s = start
        if (s != null && !selected.isBefore(s) && !selected.isAfter(s.plusDays(2))) return s
        start = selected
        return selected
    }
}

/** Everything the calendar sections share for one frame. */
private class CalendarContext(
    val vm: SunlithViewModel,
    val f: Formats,
    val today: LocalDate,
    val motion: Boolean,
    val swipe: PeriodSwipeState,
    val collapse: HeaderCollapse,
    val viewport: AgendaViewport,
    val highlightToday: Boolean,
    val threeDays: Boolean,
    val threeStart: LocalDate,
    val timelineScroll: ScrollState,
) {
    /** The date that represents the current period (0), or the previous (-1) or next (1) period, matching [SunlithViewModel.step]. */
    fun dateAt(k: Int): LocalDate {
        val selected = vm.selectedDate
        return when (vm.viewMode) {
            ViewMode.Month -> if (k == 0) selected else {
                val m = YearMonth.from(selected).plusMonths(k.toLong())
                if (m == YearMonth.from(today)) today else m.atDay(1)
            }
            ViewMode.Week -> if (threeDays) threeStart.plusDays(3L * k) else selected.plusWeeks(k.toLong())
            ViewMode.Day -> selected.plusDays(k.toLong())
        }
    }

    fun timelineDays(k: Int): List<LocalDate> {
        val d = dateAt(k)
        return if (threeDays) (0L until 3L).map { d.plusDays(it) }
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
    val collapse = remember(vm.viewMode, vm.collapseCalendarOnScroll) { HeaderCollapse(vm.collapseCalendarOnScroll) }
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
    val threeDays = mode == ViewMode.Week && vm.weekLayout == "timeline" && vm.visibleWeekDays == 3
    val window = rememberSaveable(saver = WindowStart.Saver) { WindowStart() }
    val threeStart = window.startFor(vm.selectedDate)
    val navigate: (Int) -> Unit = { dir ->
        if (threeDays) {
            val start = window.startFor(vm.selectedDate).plusDays(3L * dir)
            window.start = start
            vm.select(start)
        } else vm.step(dir)
    }
    val swipe = rememberPeriodSwipe(vm.swipeNavigation, motion, navigate)
    LaunchedEffect(mode, vm.monthLayout, vm.weekLayout, vm.dayLayout, vm.swipeNavigation) { swipe.cancel() }
    val viewport = remember { AgendaViewport() }
    val timelineScroll = rememberSaveable(mode, saver = ScrollState.Saver) { ScrollState(0) }
    val c = CalendarContext(vm, f, today, motion, swipe, collapse, viewport, highlightToday, threeDays, threeStart, timelineScroll)

    val periodHasToday = when (mode) {
        ViewMode.Month -> vm.month == YearMonth.from(today)
        ViewMode.Week -> if (vm.weekLayout == "timeline") today in c.timelineDays(0)
            else weekStart(vm.selectedDate, vm.weekStartsMonday) == weekStart(today, vm.weekStartsMonday)
        ViewMode.Day -> vm.selectedDate == today
    }

    Scaffold(
        modifier = modifier,
        containerColor = p.background,
        topBar = {
            Column(Modifier.background(p.background).statusBarsPadding()) {
                Header(vm, f, onTitle = { showPicker = true }, onStep = swipe::animateStep)
                ModeBar(vm, onStep = swipe::animateStep)
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
            Column(Modifier.fillMaxSize().onSizeChanged { if (swipe.width != it.width) swipe.cancel(); swipe.width = it.width }.periodSwipe(swipe)) {
                if (mode != ViewMode.Day) StatusCards(vm, Modifier.padding(horizontal = 12.dp))
                when (mode) {
                    ViewMode.Month -> MonthBody(c, Modifier.weight(1f))
                    ViewMode.Week -> WeekBody(c, Modifier.weight(1f))
                    ViewMode.Day -> DayBody(c, Modifier.weight(1f))
                }
            }
            TodayPill(!periodHasToday || viewport.away, motion, today, vm::goToday, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (showPicker) DatePick(vm.selectedDate, { showPicker = false }) { vm.select(it) }
}

/** Opens [date] in the Day view. Changing the view first resets the date to today, so the date is selected after. */
private fun openDay(vm: SunlithViewModel, date: LocalDate) {
    vm.changeViewMode(ViewMode.Day)
    vm.select(date)
}

/** Events per day for [from]..[to]. Days that are not loaded yet give no events, never stale ones from another range. */
@Composable
private fun rememberDayEvents(vm: SunlithViewModel, from: LocalDate, to: LocalDate): (LocalDate) -> List<CalendarEvent> {
    val events = vm.events
    val zone = ZoneId.systemDefault()
    val index = remember(events, from, to, zone) { indexByDay(events, zone, from, to) }
    val coverage = listOf(vm.loadedFrom, vm.loadedTo, vm.gridLoadedFrom, vm.gridLoadedTo)
    return remember(index, coverage) { { d: LocalDate -> if (vm.coversRange(d, d)) index[d].orEmpty() else emptyList() } }
}

private fun boundedRange(range: String, date: LocalDate, mondayFirst: Boolean): Pair<LocalDate, LocalDate> = when (range) {
    "week" -> weekStart(date, mondayFirst).let { it to it.plusDays(6) }
    "day" -> date to date
    else -> YearMonth.from(date).let { it.atDay(1) to it.atEndOfMonth() }
}

private fun stepLabels(vm: SunlithViewModel): Pair<Int, Int> = when (vm.viewMode) {
    ViewMode.Month -> R.string.action_prev_month to R.string.action_next_month
    ViewMode.Week -> if (vm.weekLayout == "timeline" && vm.visibleWeekDays == 3)
        R.string.calendar_prev_three_days to R.string.calendar_next_three_days
        else R.string.action_prev_week to R.string.action_next_week
    ViewMode.Day -> R.string.action_prev_day to R.string.action_next_day
}

@Composable
private fun Header(vm: SunlithViewModel, f: Formats, onTitle: () -> Unit, onStep: (Int) -> Unit) {
    val p = LocalPalette.current
    val shown = vm.month
    val jumpLabel = stringResource(R.string.action_jump_to_date)
    val (prev, next) = stepLabels(vm)
    val prevLabel = stringResource(prev)
    val nextLabel = stringResource(next)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = jumpLabel, role = Role.Button, onClick = onTitle)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics {
                    heading()
                    customActions = listOf(
                        CustomAccessibilityAction(prevLabel) { onStep(-1); true },
                        CustomAccessibilityAction(nextLabel) { onStep(1); true },
                    )
                },
        ) {
            Text(shown.year.toString(), color = p.muted, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(f.monthName(shown), color = p.ink, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = vm::openSearch) { Icon(Icons.Outlined.Search, stringResource(R.string.action_search), tint = p.ink) }
        IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Outlined.SettingsIcon, stringResource(R.string.title_settings), tint = p.ink) }
    }
}

/**
 * Floating "Today" shortcut. It appears only when the shown period or the agenda's visible dates leave today, sits
 * centred above the bottom edge, and keeps clear of the add button on the right. Large text shows today's number.
 */
@Composable
private fun TodayPill(visible: Boolean, motion: Boolean, today: LocalDate, onClick: () -> Unit, modifier: Modifier) {
    if (motion) {
        AnimatedVisibility(
            visible, modifier,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.9f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f),
        ) { TodayPillContent(today, onClick) }
    } else if (visible) {
        Box(modifier) { TodayPillContent(today, onClick) }
    }
}

@Composable
private fun TodayPillContent(today: LocalDate, onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = stringResource(R.string.action_today)
    val compact = LocalDensity.current.fontScale > 1.5f
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.padding(start = 88.dp, end = 88.dp, bottom = 20.dp).minimumInteractiveComponentSize()
            .shadow(3.dp, shape).clip(shape).background(p.surface).border(1.dp, p.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (compact) today.dayOfMonth.toString() else stringResource(R.string.today),
            color = p.ink, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ModeBar(vm: SunlithViewModel, onStep: (Int) -> Unit) {
    val p = LocalPalette.current
    val (prevLabel, nextLabel) = stepLabels(vm)
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = if (vm.showNavigationArrows) 4.dp else 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).background(p.surface, RoundedCornerShape(10.dp)).padding(3.dp)) {
            listOf(ViewMode.Month to R.string.view_month, ViewMode.Week to R.string.view_week, ViewMode.Day to R.string.view_day).forEach { (mode, label) ->
                val active = vm.viewMode == mode
                Box(
                    Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (active) p.raised else Color.Transparent)
                        .clickable(role = Role.Tab) { vm.changeViewMode(mode) }
                        .semantics { selected = active },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(label), color = if (active) p.ink else p.muted, style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        if (vm.showNavigationArrows) {
            IconButton(onClick = { onStep(-1) }) { Icon(Icons.Outlined.ChevronLeft, stringResource(prevLabel), tint = p.ink) }
            IconButton(onClick = { onStep(1) }) { Icon(Icons.Outlined.ChevronRight, stringResource(nextLabel), tint = p.ink) }
        }
    }
}

// region month, week and day bodies

@Composable
private fun MonthBody(c: CalendarContext, modifier: Modifier) {
    val vm = c.vm
    val p = LocalPalette.current
    if (vm.monthLayout == "full") {
        SlidingPeriod(c.swipe, modifier) { k ->
            val d = c.dateAt(k)
            val month = YearMonth.from(d)
            val start = gridStart(month, vm.weekStartsMonday)
            val eventsOn = rememberDayEvents(vm, start, start.plusDays(41))
            FullMonthGrid(
                month, d, c.today, vm.weekStartsMonday, eventsOn, c.f, k == 0 && c.highlightToday,
                onOpenDay = { openDay(vm, it) }, todayRequest = vm.todayRequest, active = k == 0,
                onAwayChanged = { if (k == 0) c.viewport.away = it },
            )
        }
        return
    }
    CollapsingHeader(c.collapse, modifier, header = {
        SlidingPeriod(c.swipe) { k ->
            val d = c.dateAt(k)
            val month = YearMonth.from(d)
            val start = gridStart(month, vm.weekStartsMonday)
            val eventsOn = rememberDayEvents(vm, start, start.plusDays(41))
            Column {
                MonthGrid(month, d, c.today, vm.weekStartsMonday, eventsOn, c.f, k == 0 && c.highlightToday, c.motion, bringSelected = k == 0, vm::select)
                HorizontalDivider(color = p.line)
            }
        }
    }) {
        if (vm.agendaRange == "continuous") {
            Agenda(c, vm.loadedFrom, vm.loadedTo, vm.agendaDisplay, vm.selectedDate, continuous = true, active = true)
        } else {
            SlidingPeriod(c.swipe, Modifier.fillMaxSize()) { k ->
                val d = c.dateAt(k)
                val (from, to) = boundedRange(vm.agendaRange, d, vm.weekStartsMonday)
                Agenda(c, from, to, vm.agendaDisplay, d, continuous = false, active = k == 0)
            }
        }
    }
}

@Composable
private fun WeekBody(c: CalendarContext, modifier: Modifier) {
    val vm = c.vm
    val p = LocalPalette.current
    if (vm.weekLayout == "timeline") {
        SlidingPeriod(c.swipe, modifier) { k -> TimelinePeriod(c, c.timelineDays(k), k, showHeaders = true) }
        return
    }
    CollapsingHeader(c.collapse, modifier, header = {
        Column {
            WeekdayLabels(vm.weekStartsMonday, c.f)
            SlidingPeriod(c.swipe) { k -> WeekStrip(c, c.dateAt(k), active = k == 0) }
            HorizontalDivider(color = p.line)
        }
    }) {
        SlidingPeriod(c.swipe, Modifier.fillMaxSize()) { k ->
            val d = c.dateAt(k)
            val start = weekStart(d, vm.weekStartsMonday)
            Agenda(c, start, start.plusDays(6), "all", d, continuous = false, active = k == 0)
        }
    }
}

/** Day view: a date strip for picking a day in the selected week, then cards, a timeline or the continuous schedule. */
@Composable
private fun DayBody(c: CalendarContext, modifier: Modifier) {
    val vm = c.vm
    val p = LocalPalette.current
    Column(modifier) {
        if (vm.dayLayout != "cards") StatusCards(vm, Modifier.padding(horizontal = 12.dp))
        CollapsingHeader(c.collapse, Modifier.weight(1f), header = {
            Column(Modifier.testTag("calendar_day_picker")) {
                WeekdayLabels(vm.weekStartsMonday, c.f)
                WeekStrip(c, vm.selectedDate, active = true)
                HorizontalDivider(color = p.line)
            }
        }) {
            when (vm.dayLayout) {
                "timeline" -> SlidingPeriod(c.swipe, Modifier.fillMaxSize()) { k -> TimelinePeriod(c, listOf(c.dateAt(k)), k, showHeaders = false) }
                "schedule" -> Agenda(c, vm.loadedFrom, vm.loadedTo, vm.agendaDisplay, vm.selectedDate, continuous = true, active = true, compact = true)
                else -> SlidingPeriod(c.swipe, Modifier.fillMaxSize()) { k -> DayCards(c, c.dateAt(k), active = k == 0) }
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
    key(day) { DayView(vm, day, eventsOn(day), c.f, showStatus = active) }
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
    val scroll = if (k == 0) c.timelineScroll else rememberScrollState(c.timelineScroll.value)
    var overflowEvents by remember { mutableStateOf(emptyList<CalendarEvent>()) }
    Timeline(
        days, eventsOn, vm.selectedDate, c.today, c.f, showHeaders, scroll,
        autoScroll = k == 0, todayRequest = vm.todayRequest, motion = c.motion,
        onSelectDay = vm::select,
        onOpen = { vm.open(Screen.Details(it)) },
        onNew = { vm.newEvent(it) },
        onOverflow = { day, hidden -> if (days.size > 1) openDay(vm, day) else overflowEvents = hidden },
    ) {
        val selected = vm.selectedDate.coerceIn(first, last)
        Agenda(c, first, last, "all", selected, continuous = false, active = k == 0)
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
                (if (narrow) f.weekdayNarrow(dow) else f.weekdayShort(dow)).uppercase(f.locale), Modifier.weight(1f), color = p.muted, style = SectionLabel,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip,
            )
        }
    }
}

/** Always six rows, so the grid keeps the same height in every month and the agenda never jumps. */
@Composable
private fun MonthGrid(
    month: YearMonth, selected: LocalDate, today: LocalDate, mondayFirst: Boolean, eventsOn: (LocalDate) -> List<CalendarEvent>,
    f: Formats, pulseToday: Boolean, motion: Boolean, bringSelected: Boolean, onSelect: (LocalDate) -> Unit,
) {
    val start = gridStart(month, mondayFirst)
    val selectedCell = remember { BringIntoViewRequester() }
    if (bringSelected) LaunchedEffect(selected, pulseToday) { selectedCell.bringIntoView() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        WeekdayLabels(mondayFirst, f)
        Column(Modifier.fillMaxWidth()) {
            repeat(GRID_ROWS) { r ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { c ->
                        val day = start.plusDays((r * 7 + c).toLong())
                        key(day) {
                            DayCell(
                                day, day == selected, day == today, YearMonth.from(day) == month, eventsOn(day), f,
                                pulseToday && day == today, motion,
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
    val start = weekStart(date, vm.weekStartsMonday)
    val eventsOn = rememberDayEvents(vm, start, start.plusDays(6))
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        (0L until 7L).forEach { i ->
            val day = start.plusDays(i)
            key(day) {
                DayCell(
                    day, date == day, day == c.today, true, eventsOn(day), c.f, active && c.highlightToday && day == c.today, c.motion, Modifier.weight(1f),
                ) { vm.select(it) }
            }
        }
    }
}

/** Circle size for a date number: grows with the font size, but never wider than its column. */
@Composable
private fun dayCircle(): Dp {
    val size = MaterialTheme.typography.bodyLarge.fontSize
    return with(LocalDensity.current) { maxOf(40.dp, (size * 2.25f).toDp()) }
}

@Composable
private fun DayCell(
    day: LocalDate, isSelected: Boolean, isToday: Boolean, inMonth: Boolean, events: List<CalendarEvent>, f: Formats,
    pulse: Boolean, motion: Boolean, modifier: Modifier, onSelect: (LocalDate) -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val description = dayDescription(context, f, day, isToday, events.size)
    val interaction = remember { MutableInteractionSource() }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulse, motion) {
        if (pulse && motion) {
            scale.animateTo(1.18f, tween(140))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        } else scale.snapTo(1f)
    }
    Column(
        modifier.heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, indication = null) { onSelect(day) }
            .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.padding(horizontal = 2.dp).widthIn(max = dayCircle()).fillMaxWidth().aspectRatio(1f)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .clip(CircleShape)
                .background(if (isSelected) p.accent else Color.Transparent)
                .then(if (isToday && !isSelected) Modifier.border(1.5.dp, p.ink, CircleShape) else Modifier)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                color = when { isSelected -> p.onAccent; inMonth -> p.ink; else -> p.faint },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
            )
        }
        Row(Modifier.padding(top = 3.dp).height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            events.take(3).forEach { e ->
                Box(Modifier.size(5.dp).background(if (inMonth) eventTint(e.color, p) else p.faint, CircleShape))
            }
        }
    }
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
    if (from == null || to == null || !vm.coversRange(from, to)) {
        if (active && vm.loadError == null) CalendarLoading() else Box(Modifier.fillMaxSize())
        return
    }
    val zone = ZoneId.systemDefault()
    val events = vm.events
    val index = remember(events, from, to, zone) { indexByDay(events, zone, from, to) }
    val today = c.today
    val rows = remember(index, from, to, display, selected, today, continuous) {
        buildAgenda(from, to, index, display, setOf(selected, today), monthHeaders = continuous)
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
    val anchor = remember { targetIndex(rows, selected).coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = anchor)
    if (active) {
        SideEffect { c.collapse.canExpand = { listState.firstVisibleItemIndex <= targetIndex(rows, selected).coerceAtLeast(0) } }
        DisposableEffect(c.collapse) { onDispose { c.collapse.canExpand = { true } } }
    }

    var handled by remember { mutableStateOf(selected to vm.todayRequest) }
    var pending by remember { mutableStateOf<LocalDate?>(null) }
    val navigation = selected to vm.todayRequest
    LaunchedEffect(navigation) {
        if (!active || navigation == handled) return@LaunchedEffect
        handled = navigation
        pending = selected
    }
    LaunchedEffect(pending, rows) {
        val target = pending ?: return@LaunchedEffect
        if (target.isBefore(from) || target.isAfter(to)) return@LaunchedEffect
        val row = targetIndex(rows, target)
        if (row >= 0) {
            if (c.motion) listState.animateScrollToItem(row) else listState.requestScrollToItem(row)
        }
        pending = null
    }

    if (active) {
        LaunchedEffect(listState, rows, today) {
            snapshotFlow {
                val visible = listState.layoutInfo.visibleItemsInfo.filter { it.index < rows.size }
                if (visible.isEmpty()) false
                else today.isBefore(rows[visible.first().index].first) || today.isAfter(rows[visible.last().index].last)
            }.collect { c.viewport.away = it }
        }
        DisposableEffect(Unit) { onDispose { c.viewport.away = false } }
    }

    var lastDirection by remember { mutableStateOf<Boolean?>(null) }
    val extend: (Boolean) -> Unit = { forward ->
        if (vm.loadError != null) vm.reload() else { lastDirection = forward; vm.extendAgenda(forward) }
    }
    if (continuous && active) {
        LaunchedEffect(listState) {
            var firedAt: Any? = null
            snapshotFlow {
                val info = listState.layoutInfo
                val visible = info.visibleItemsInfo
                if (visible.isEmpty() || vm.extendingAgenda || vm.loadError != null) null
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

    BoxWithConstraints(Modifier.fillMaxSize().then(if (compact) Modifier.testTag("calendar_schedule") else Modifier)) {
        val allDayText = stringResource(R.string.all_day)
        val gutter = if (compact) {
            rememberTimeGutter(
                listOf(f.time(LocalTime.of(22, 55)), f.time(LocalTime.of(10, 55)), allDayText),
                MaterialTheme.typography.bodyMedium, 56.dp, maxWidth * 0.3f,
            )
        } else 0.dp
        LazyColumn(
            Modifier.fillMaxSize().testTag("calendar_agenda"), state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 104.dp),
        ) {
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                when (row) {
                    is AgendaRow.Month -> MonthLabel(row.month, f)
                    is AgendaRow.Day -> {
                        val highlighted = active && c.highlightToday && row.date == today
                        if (compact) ScheduleDay(row, row.date == selected, row.date == today, highlighted, c.motion, f, vm, gutter)
                        else AgendaDay(row, row.date == selected, row.date == today, highlighted, c.motion, f, vm)
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
private fun DateBadge(date: LocalDate, isToday: Boolean, isSelected: Boolean, f: Formats) {
    val p = LocalPalette.current
    val circle = with(LocalDensity.current) { maxOf(34.dp, (MaterialTheme.typography.titleMedium.fontSize * 2f).toDp()) }
    Column(Modifier.widthIn(min = 56.dp).padding(end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(circle).background(if (isToday) p.accent else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(), color = when { isToday -> p.onAccent; isSelected -> p.ink; else -> p.muted },
                style = MaterialTheme.typography.titleMedium, fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
            )
        }
        Text(f.weekdayShort(date.dayOfWeek), color = if (isToday || isSelected) p.ink else p.muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun AgendaDay(row: AgendaRow.Day, isSelected: Boolean, isToday: Boolean, highlighted: Boolean, motion: Boolean, f: Formats, vm: SunlithViewModel) {
    val p = LocalPalette.current
    val background by animateColorAsState(
        if (highlighted) p.raised else Color.Transparent,
        animationSpec = if (motion) tween(300) else snap(),
        label = "today",
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(background).padding(vertical = 3.dp)) {
        DateBadge(row.date, isToday, isSelected, f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.events.isEmpty()) {
                EmptyDayButton(row.date, vm)
            } else {
                row.events.forEach { e -> EventCard(e, row.date, f, onClick = { vm.open(Screen.Details(e)) }) }
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

/** Compact schedule day: the time sits in a fixed column beside each title, without cards. */
@Composable
private fun ScheduleDay(row: AgendaRow.Day, isSelected: Boolean, isToday: Boolean, highlighted: Boolean, motion: Boolean, f: Formats, vm: SunlithViewModel, gutter: Dp) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val background by animateColorAsState(
        if (highlighted) p.raised else Color.Transparent,
        animationSpec = if (motion) tween(300) else snap(),
        label = "today",
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp)).background(background).padding(vertical = 2.dp)) {
        DateBadge(row.date, isToday, isSelected, f)
        Column(Modifier.weight(1f)) {
            if (row.events.isEmpty()) {
                EmptyDayButton(row.date, vm)
            } else {
                row.events.forEach { e ->
                    val title = e.title.ifBlank { stringResource(R.string.untitled) }
                    val time = if (e.allDay || e.span(zone).multiDay) context.timeLabel(e, row.date, zone, f) else f.time(e.startMillis)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { vm.open(Screen.Details(e)) }
                            .testTag("schedule_event_${e.id}")
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(time, Modifier.width(gutter).padding(end = 8.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.padding(top = 6.dp).size(8.dp).background(eventTint(e.color, p), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = p.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (e.location.isNotBlank()) {
                                Text(e.location, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun DayView(vm: SunlithViewModel, day: LocalDate, events: List<CalendarEvent>, f: Formats, showStatus: Boolean) {
    val p = LocalPalette.current
    val today = vm.today
    val zone = ZoneId.systemDefault()
    val allDay = events.filter { it.allDay || it.span(zone).multiDay }
    val timed = events - allDay.toSet()
    val timeStyle = MaterialTheme.typography.bodyMedium
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val timeWidth = rememberTimeGutter(timed.map { f.time(it.startMillis) }, timeStyle, 64.dp, (maxWidth - 32.dp) * 0.4f)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            items(allDay, key = { "a-" + it.key() }) { e -> EventCard(e, day, f, onClick = { vm.open(Screen.Details(e)) }) }
            items(timed, key = { "t-" + it.key() }) { e ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        f.time(e.startMillis), Modifier.width(timeWidth).padding(top = 12.dp, end = 8.dp),
                        color = p.muted, style = timeStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    EventCard(e, day, f, onClick = { vm.open(Screen.Details(e)) }, modifier = Modifier.weight(1f).testTag("day_event_${e.id}"))
                }
            }
            item(key = "spacer") { Spacer(Modifier.width(1.dp).height(1.dp)) }
        }
    }
}
