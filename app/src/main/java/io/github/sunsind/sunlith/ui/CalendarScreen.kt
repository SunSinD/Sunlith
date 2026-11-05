package io.github.sunsind.sunlith.ui

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val GRID_ROWS = 6

private sealed interface AgendaRow {
    val key: String

    data class Day(val date: LocalDate, val events: List<CalendarEvent>) : AgendaRow { override val key get() = "d-$date" }

    data class Empty(val from: LocalDate, val to: LocalDate) : AgendaRow { override val key get() = "e-$to" }
}

/**
 * Builds one row per day ("all"), groups runs of empty days ("compact", keeping [keep] as single days), or keeps
 * only days with events ("events"). The caller supplies the displayed period.
 */
private fun buildAgenda(from: LocalDate, to: LocalDate, index: Map<LocalDate, List<CalendarEvent>>, display: String, keep: Set<LocalDate>): List<AgendaRow> {
    val rows = mutableListOf<AgendaRow>()
    var emptyStart: LocalDate? = null
    var day = from
    while (!day.isAfter(to)) {
        val events = index[day].orEmpty()
        val show = events.isNotEmpty() || display == "all" || (display == "compact" && day in keep)
        emptyStart?.let { start ->
            if (show || day.dayOfMonth == 1) { rows += AgendaRow.Empty(start, day.minusDays(1)); emptyStart = null }
        }
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

/** The row to show at the top for [date]: its own row, the range containing it, or the next shown day. */
private fun targetIndex(rows: List<AgendaRow>, date: LocalDate): Int {
    if (rows.isEmpty()) return -1
    val found = rows.indexOfFirst { row ->
        when (row) {
            is AgendaRow.Day -> !row.date.isBefore(date)
            is AgendaRow.Empty -> !row.to.isBefore(date)
        }
    }
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

@Composable
fun CalendarScreen(vm: SunlithViewModel, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val f = rememberFormats()
    val zone = ZoneId.systemDefault()
    val from = vm.loadedFrom
    val to = vm.loadedTo
    val index = remember(vm.events, from, to, zone) {
        if (from == null || to == null) emptyMap() else indexByDay(vm.events, zone, from, to)
    }
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

    Scaffold(
        modifier = modifier,
        containerColor = p.background,
        topBar = {
            Column(Modifier.background(p.background).statusBarsPadding()) {
                Header(vm, f, onTitle = { showPicker = true })
                ModeBar(vm)
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.newEvent() },
                containerColor = p.accent, contentColor = p.onAccent, shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Outlined.Add, stringResource(R.string.action_new_event)) }
        },
    ) { pad ->
        val today = LocalDate.now()
        Column(
            Modifier.fillMaxSize().padding(pad).semantics { testTagsAsResourceId = true }
                .draggable(rememberDraggableState { }, Orientation.Horizontal),
        ) {
            if (vm.viewMode != ViewMode.Day) StatusCards(vm, Modifier.padding(horizontal = 12.dp))
            when (vm.viewMode) {
                ViewMode.Month -> CollapsingHeader(collapse, Modifier.weight(1f), header = {
                    Column {
                        MonthGrid(vm.month, vm.selectedDate, today, vm.weekStartsMonday, index, f, highlightToday, motion, vm::select)
                        HorizontalDivider(color = p.line)
                    }
                }) { Agenda(vm, index, f, collapse, motion, highlightToday) }
                ViewMode.Week -> CollapsingHeader(collapse, Modifier.weight(1f), header = {
                    Column {
                        WeekdayLabels(vm.weekStartsMonday, f)
                        WeekStrip(vm, today, index, f, highlightToday, motion)
                        HorizontalDivider(color = p.line)
                    }
                }) { Agenda(vm, index, f, collapse, motion, highlightToday) }
                ViewMode.Day -> if (vm.coversRange(vm.selectedDate, vm.selectedDate)) {
                    DayView(vm, index[vm.selectedDate].orEmpty(), f)
                } else Column(Modifier.fillMaxSize()) {
                    StatusCards(vm)
                    if (vm.loadError == null) CalendarLoading()
                }
            }
        }
    }

    if (showPicker) DatePick(vm.selectedDate, { showPicker = false }) { vm.select(it) }
}

@Composable
private fun Header(vm: SunlithViewModel, f: Formats, onTitle: () -> Unit) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    val shown = vm.month
    val jumpLabel = stringResource(R.string.action_jump_to_date)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = jumpLabel, role = Role.Button, onClick = onTitle)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics { heading() },
        ) {
            Text(shown.year.toString(), color = p.muted, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(f.monthName(shown), color = p.ink, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = vm::openSearch) { Icon(Icons.Outlined.Search, stringResource(R.string.action_search), tint = p.ink) }
        TodayButton(vm::goToday)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more), tint = p.ink) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.title_calendars)) }, onClick = { menu = false; vm.open(Screen.Calendars) })
                DropdownMenuItem(text = { Text(stringResource(R.string.title_settings)) }, onClick = { menu = false; vm.open(Screen.Settings) })
            }
        }
    }
}

/** A labelled pill, so the action reads as "Today" instead of a generic calendar glyph. Large text shows today's number. */
@Composable
private fun TodayButton(onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = stringResource(R.string.action_today)
    val compact = LocalDensity.current.fontScale > 1.5f
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.minimumInteractiveComponentSize().padding(horizontal = 4.dp).clip(shape).border(1.dp, p.faint, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (compact) LocalDate.now().dayOfMonth.toString() else stringResource(R.string.today),
            color = p.ink, style = MaterialTheme.typography.labelLarge, maxLines = 1,
        )
    }
}

@Composable
private fun ModeBar(vm: SunlithViewModel) {
    val p = LocalPalette.current
    val prevLabel: Int
    val nextLabel: Int
    when (vm.viewMode) {
        ViewMode.Month -> { prevLabel = R.string.action_prev_month; nextLabel = R.string.action_next_month }
        ViewMode.Week -> { prevLabel = R.string.action_prev_week; nextLabel = R.string.action_next_week }
        ViewMode.Day -> { prevLabel = R.string.action_prev_day; nextLabel = R.string.action_next_day }
    }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
        IconButton(onClick = { vm.step(-1) }) { Icon(Icons.Outlined.ChevronLeft, stringResource(prevLabel), tint = p.ink) }
        IconButton(onClick = { vm.step(1) }) { Icon(Icons.Outlined.ChevronRight, stringResource(nextLabel), tint = p.ink) }
    }
}

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
 * large text), only half the space shows it at once, and it scrolls inside that area, so the agenda stays visible.
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
private fun WeekdayLabels(mondayFirst: Boolean, f: Formats) {
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
    month: YearMonth, selected: LocalDate, today: LocalDate, mondayFirst: Boolean, index: Map<LocalDate, List<CalendarEvent>>,
    f: Formats, pulseToday: Boolean, motion: Boolean, onSelect: (LocalDate) -> Unit,
) {
    val start = gridStart(month, mondayFirst)
    val selectedCell = remember { BringIntoViewRequester() }
    LaunchedEffect(selected, pulseToday) { selectedCell.bringIntoView() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        WeekdayLabels(mondayFirst, f)
        Column(Modifier.fillMaxWidth()) {
            repeat(GRID_ROWS) { r ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { c ->
                        val day = start.plusDays((r * 7 + c).toLong())
                        key(day) {
                            DayCell(
                                day, day == selected, day == today, YearMonth.from(day) == month, index[day].orEmpty(), f,
                                pulseToday && day == today, motion,
                                Modifier.weight(1f).then(if (day == selected) Modifier.bringIntoViewRequester(selectedCell) else Modifier), onSelect,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun WeekStrip(
    vm: SunlithViewModel, today: LocalDate, index: Map<LocalDate, List<CalendarEvent>>, f: Formats,
    pulseToday: Boolean, motion: Boolean,
) {
    val start = weekStart(vm.selectedDate, vm.weekStartsMonday)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        (0L until 7L).forEach { i ->
            val day = start.plusDays(i)
            key(day) {
                DayCell(day, vm.selectedDate == day, day == today, true, index[day].orEmpty(), f, pulseToday && day == today, motion, Modifier.weight(1f)) { vm.select(it) }
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
    val count = events.size
    val context = LocalContext.current
    val description = buildString {
        append(f.dayLong(day))
        if (isToday) append(", ").append(context.getString(R.string.today))
        append(", ").append(context.resources.getQuantityString(R.plurals.event_count, count, count))
    }
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

/**
 * Only the displayed month or week is shown. Each period starts at the selected date before its first frame.
 */
@Composable
private fun Agenda(
    vm: SunlithViewModel, index: Map<LocalDate, List<CalendarEvent>>, f: Formats, collapse: HeaderCollapse, motion: Boolean, highlightToday: Boolean,
) {
    val p = LocalPalette.current
    val (from, to) = when (vm.viewMode) {
        ViewMode.Month -> vm.month.atDay(1) to vm.month.atEndOfMonth()
        ViewMode.Week -> {
            val start = weekStart(vm.selectedDate, vm.weekStartsMonday)
            start to start.plusDays(6)
        }
        ViewMode.Day -> vm.selectedDate to vm.selectedDate
    }
    if (!vm.coversRange(from, to)) {
        if (vm.loadError == null) CalendarLoading()
        return
    }
    val today = LocalDate.now()
    val selected = vm.selectedDate
    val display = if (vm.viewMode == ViewMode.Week) "all" else vm.agendaDisplay
    val rows = remember(index, from, to, display, selected, today) {
        buildAgenda(from, to, index, display, setOf(selected, today))
    }
    key(vm.viewMode, from, to, display) {
        val anchor = targetIndex(rows, selected).coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = anchor)
        SideEffect { collapse.canExpand = { listState.firstVisibleItemIndex <= anchor } }
        var handledNavigation by remember { mutableStateOf(selected to vm.todayRequest) }
        val navigation = selected to vm.todayRequest
        LaunchedEffect(navigation) {
            if (navigation == handledNavigation) return@LaunchedEffect
            val row = targetIndex(rows, selected)
            if (row >= 0) {
                if (motion) listState.animateScrollToItem(row) else listState.requestScrollToItem(row)
            }
            handledNavigation = navigation
        }
        LazyColumn(Modifier.fillMaxSize().testTag("calendar_agenda"), state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 104.dp)) {
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                when (row) {
                    is AgendaRow.Day -> AgendaDay(row, row.date == selected, row.date == today, highlightToday && row.date == today, motion, f, vm)
                    is AgendaRow.Empty -> EmptyRange(row, f) { vm.select(row.from) }
                }
            }
            if (rows.isEmpty() && vm.loaded) {
                item(key = "none") {
                    Text(stringResource(R.string.no_events), color = p.muted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp))
                }
            }
        }
    }
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
                val add = stringResource(R.string.action_add_event)
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(onClickLabel = add, role = Role.Button) { vm.newEvent(row.date) }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(stringResource(R.string.no_events), color = p.faint, style = MaterialTheme.typography.bodyMedium) }
            } else {
                row.events.forEach { e -> EventCard(e, row.date, f, onClick = { vm.open(Screen.Details(e)) }) }
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

@Composable
private fun DayView(vm: SunlithViewModel, events: List<CalendarEvent>, f: Formats) {
    val p = LocalPalette.current
    val day = vm.selectedDate
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val allDay = events.filter { it.allDay || it.span(zone).multiDay }
    val timed = events - allDay.toSet()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val timeStyle = MaterialTheme.typography.bodyMedium
    val labelWidth = with(density) {
        timed.maxOfOrNull { measurer.measure(f.time(it.startMillis), timeStyle).size.width }?.toDp() ?: 0.dp
    } + 8.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val timeWidth = maxOf(64.dp, labelWidth).coerceAtMost((maxWidth - 32.dp) * 0.4f)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "status") { StatusCards(vm) }
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
