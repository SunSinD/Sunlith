package io.github.sunsind.sunlith.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Today
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private sealed interface AgendaRow {
    data class Day(val date: LocalDate, val events: List<CalendarEvent>) : AgendaRow
    data class Empty(val from: LocalDate, val to: LocalDate) : AgendaRow
}

private fun buildAgenda(from: LocalDate, to: LocalDate, index: Map<LocalDate, List<CalendarEvent>>, keep: Set<LocalDate>): List<AgendaRow> {
    val rows = mutableListOf<AgendaRow>()
    var emptyStart: LocalDate? = null
    var day = from
    while (!day.isAfter(to)) {
        val events = index[day].orEmpty()
        if (events.isEmpty() && day !in keep) {
            if (emptyStart == null) emptyStart = day
        } else {
            emptyStart?.let { rows += AgendaRow.Empty(it, day.minusDays(1)); emptyStart = null }
            rows += AgendaRow.Day(day, events)
        }
        day = day.plusDays(1)
    }
    emptyStart?.let { rows += AgendaRow.Empty(it, to) }
    return rows
}

@Composable
fun CalendarScreen(vm: SunlithViewModel, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val f = rememberFormats()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val from = vm.loadedFrom
    val to = vm.loadedTo
    val index = remember(vm.events, from, to, zone) {
        if (from == null || to == null) emptyMap() else indexByDay(vm.events, zone, from, to)
    }
    var showPicker by rememberSaveable { mutableStateOf(false) }

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
        val swipe = Modifier.swipeDays(onPrevious = { vm.step(-1) }, onNext = { vm.step(1) })
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (vm.viewMode) {
                ViewMode.Month -> {
                    Agenda(vm, index, f, vm.selectedDate, vm.selectedDate.plusDays(SunlithViewModel.AGENDA_DAYS), Modifier) {
                        MonthGrid(vm.month, vm.selectedDate, today, vm.weekStartsMonday, index, f, vm::select, swipe)
                        HorizontalDivider(color = p.line)
                    }
                }
                ViewMode.Week -> {
                    val start = weekStart(vm.selectedDate, vm.weekStartsMonday)
                    WeekdayLabels(vm.weekStartsMonday, f)
                    Row(swipe.fillMaxWidth().padding(horizontal = 4.dp)) {
                        (0L until 7L).forEach { i ->
                            val day = start.plusDays(i)
                            DayCell(day, vm.selectedDate == day, day == today, true, index[day].orEmpty(), f, Modifier.weight(1f)) { vm.select(it) }
                        }
                    }
                    HorizontalDivider(color = p.line)
                    Agenda(vm, index, f, start, start.plusDays(6), Modifier)
                }
                ViewMode.Day -> DayView(vm, index[vm.selectedDate].orEmpty(), f, swipe)
            }
        }
    }

    if (showPicker) DatePick(vm.selectedDate, { showPicker = false }) { vm.select(it) }
}

@Composable
private fun Header(vm: SunlithViewModel, f: Formats, onTitle: () -> Unit) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    val shown = if (vm.viewMode == ViewMode.Month) vm.month else YearMonth.from(vm.selectedDate)
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
        IconButton(onClick = vm::goToday) { Icon(Icons.Outlined.Today, stringResource(R.string.action_today), tint = p.ink) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more), tint = p.ink) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.title_calendars)) }, onClick = { menu = false; vm.open(Screen.Calendars) })
                DropdownMenuItem(text = { Text(stringResource(R.string.title_settings)) }, onClick = { menu = false; vm.open(Screen.Settings) })
            }
        }
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

@Composable
private fun Modifier.swipeDays(onPrevious: () -> Unit, onNext: () -> Unit): Modifier {
    val prev by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    return this.pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (total > threshold) prev() else if (total < -threshold) next() },
        ) { change, amount ->
            total += amount
            change.consume()
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

@Composable
private fun MonthGrid(
    month: YearMonth, selected: LocalDate, today: LocalDate, mondayFirst: Boolean,
    index: Map<LocalDate, List<CalendarEvent>>, f: Formats, onSelect: (LocalDate) -> Unit, swipe: Modifier,
) {
    val start = gridStart(month, mondayFirst)
    val first = month.atDay(1)
    val leading = java.time.temporal.ChronoUnit.DAYS.between(start, first).toInt()
    val rows = (leading + month.lengthOfMonth() + 6) / 7
    Column(swipe.fillMaxWidth().padding(horizontal = 4.dp)) {
        WeekdayLabels(mondayFirst, f)
        repeat(rows) { r ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { c ->
                    val day = start.plusDays((r * 7 + c).toLong())
                    DayCell(day, day == selected, day == today, YearMonth.from(day) == month, index[day].orEmpty(), f, Modifier.weight(1f), onSelect)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DayCell(
    day: LocalDate, isSelected: Boolean, isToday: Boolean, inMonth: Boolean, events: List<CalendarEvent>, f: Formats,
    modifier: Modifier, onSelect: (LocalDate) -> Unit,
) {
    val p = LocalPalette.current
    val count = events.size
    val context = LocalContext.current
    val description = buildString {
        append(f.dayLong(day))
        if (isToday) append(", ").append(context.getString(R.string.today))
        append(", ").append(context.resources.getQuantityString(R.plurals.event_count, count, count))
    }
    Column(
        modifier.heightIn(min = 52.dp).padding(2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) p.raised else Color.Transparent)
            .clickable { onSelect(day) }
            .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.sizeIn(minWidth = 30.dp, minHeight = 30.dp).background(if (isToday) p.accent else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                color = when { isToday -> p.onAccent; inMonth -> p.ink; else -> p.faint },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 4.dp),
                maxLines = 1,
            )
        }
        Row(Modifier.height(8.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            events.take(3).forEach { e ->
                Box(Modifier.size(5.dp).background(if (inMonth) eventTint(e.color, p) else p.faint, CircleShape))
            }
        }
    }
}

@Composable
private fun StatusCards(vm: SunlithViewModel) {
    vm.loadError?.let { InfoCard(it, stringResource(R.string.action_retry)) { vm.reload() } }
    if (vm.loaded && vm.loadError == null) {
        when {
            vm.calendars.isEmpty() -> InfoCard(stringResource(R.string.no_calendars_on_phone), stringResource(R.string.action_create_local)) { vm.createLocalCalendar() }
            vm.activeIds.isEmpty() -> InfoCard(stringResource(R.string.no_calendars_selected), stringResource(R.string.action_choose_calendars)) { vm.open(Screen.Calendars) }
        }
    }
}

@Composable
private fun Agenda(vm: SunlithViewModel, index: Map<LocalDate, List<CalendarEvent>>, f: Formats, from: LocalDate, to: LocalDate, modifier: Modifier, header: (@Composable () -> Unit)? = null) {
    val today = LocalDate.now()
    val rows = remember(index, from, to, vm.selectedDate, today) { buildAgenda(from, to, index, setOf(vm.selectedDate, today)) }
    val listState = rememberLazyListState()
    LaunchedEffect(from, vm.viewMode) { listState.scrollToItem(0) }
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 104.dp)) {
        if (header != null) item(key = "calendar") { header() }
        item(key = "status") { StatusCards(vm) }
        items(rows, key = { row ->
            when (row) {
                is AgendaRow.Day -> "d-${row.date}"
                is AgendaRow.Empty -> "e-${row.from}"
            }
        }) { row ->
            when (row) {
                is AgendaRow.Day -> AgendaDay(row, row.date == vm.selectedDate, row.date == today, f, vm)
                is AgendaRow.Empty -> EmptyRange(row, f) { vm.select(row.from) }
            }
        }
    }
}

@Composable
private fun DateBadge(date: LocalDate, isToday: Boolean, isSelected: Boolean, f: Formats) {
    val p = LocalPalette.current
    Column(Modifier.widthIn(min = 56.dp).padding(end = 8.dp, top = 4.dp)) {
        Text(
            date.dayOfMonth.toString(), color = if (isToday || isSelected) p.ink else p.muted,
            style = MaterialTheme.typography.headlineSmall, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
        )
        Text(f.weekdayShort(date.dayOfWeek), color = if (isToday || isSelected) p.ink else p.muted, style = MaterialTheme.typography.bodyMedium)
        if (isToday) Text(stringResource(R.string.today), color = p.muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AgendaDay(row: AgendaRow.Day, isSelected: Boolean, isToday: Boolean, f: Formats, vm: SunlithViewModel) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        DateBadge(row.date, isToday, isSelected, f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.events.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) { vm.newEvent(row.date) }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(stringResource(R.string.no_events_tap), color = p.muted, style = MaterialTheme.typography.bodyMedium) }
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
    val label = when {
        row.from == row.to -> short(row.from)
        YearMonth.from(row.from) == YearMonth.from(row.to) -> "${short(row.from)} – ${short(row.to)}"
        else -> "${f.dayShort(row.from)} – ${f.dayShort(row.to)}"
    }
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
private fun DayView(vm: SunlithViewModel, events: List<CalendarEvent>, f: Formats, swipe: Modifier) {
    val p = LocalPalette.current
    val day = vm.selectedDate
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val allDay = events.filter { it.allDay || it.span(zone).multiDay }
    val timed = events - allDay.toSet()
    LazyColumn(swipe.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    f.time(e.startMillis), Modifier.widthIn(min = 64.dp).padding(top = 12.dp, end = 8.dp),
                    color = p.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                )
                EventCard(e, day, f, onClick = { vm.open(Screen.Details(e)) }, modifier = Modifier.weight(1f))
            }
        }
        item(key = "spacer") { Spacer(Modifier.width(1.dp).height(1.dp)) }
    }
}
