package io.github.sunsind.sunlith.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.YearMonth

/**
 * A month grid that fills the screen with six fixed rows, ruled into cells, and shows event titles as solid chips
 * inside each date. Rows never grow with their events: a date shows as many titles as fit and a "+n" count for the
 * rest. When the text is too large for six rows to fit, the rows keep a readable minimum height and the grid scrolls.
 *
 * Tapping a date calls [onOpenDay]. Long-pressing a date, or tapping its "+n", previews the whole day (every full
 * title and time) without leaving the month. By default the grid shows that preview itself in a bottom sheet, where
 * tapping an event calls [onOpenEvent] (or opens the day when it is null); pass [onPreviewDay] to show it elsewhere.
 */
@Composable
internal fun FullMonthGrid(
    month: YearMonth, selected: LocalDate, today: LocalDate, mondayFirst: Boolean,
    eventsOn: (LocalDate) -> List<CalendarEvent>, f: Formats, pulseToday: Boolean,
    onOpenDay: (LocalDate) -> Unit, modifier: Modifier = Modifier,
    todayRequest: Int = 0, active: Boolean = true, onAwayChanged: (Boolean) -> Unit = {},
    onOpenEvent: ((CalendarEvent) -> Unit)? = null,
    onPreviewDay: ((LocalDate) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val number = with(density) { maxOf(26.dp, (typography.bodyMedium.fontSize * 1.9f).toDp()) }
    val chip = with(density) { maxOf(16.dp, typography.labelSmall.lineHeight.toDp() + 4.dp) }
    val start = gridStart(month, mondayFirst)
    var previewEpochDay by rememberSaveable(month) { mutableStateOf<Long?>(null) }
    val preview: (LocalDate) -> Unit = onPreviewDay ?: { previewEpochDay = it.toEpochDay() }
    Column(modifier.fillMaxSize().padding(horizontal = 2.dp).testTag("calendar_full_month")) {
        WeekdayLabels(mondayFirst, f)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("calendar_grid")) {
            val minRow = maxOf(56.dp, number + chip + 10.dp)
            val row = maxOf(maxHeight / 6, minRow)
            val scrolls = row * 6 > maxHeight
            key(month) {
                val scroll = rememberScrollState()
                val rowPx = with(density) { row.toPx() }
                val dateHeightPx = with(density) { (number + 6.dp).toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                val reportAway by rememberUpdatedState(onAwayChanged)
                if (active && scrolls) {
                    LaunchedEffect(selected, todayRequest, rowPx) {
                        val targetRow = (java.time.temporal.ChronoUnit.DAYS.between(start, selected) / 7).coerceIn(0, 5)
                        scroll.scrollTo((targetRow * rowPx).toInt())
                    }
                    LaunchedEffect(scroll, today, start, rowPx, dateHeightPx, heightPx) {
                        val dayIndex = java.time.temporal.ChronoUnit.DAYS.between(start, today)
                        snapshotFlow {
                            val top = (dayIndex / 7) * rowPx
                            dayIndex in 0..41 && (top < scroll.value || top + dateHeightPx > scroll.value + heightPx + 1)
                        }.collect { reportAway(it) }
                    }
                    DisposableEffect(Unit) { onDispose { reportAway(false) } }
                }
                Column(if (scrolls) Modifier.verticalScroll(scroll) else Modifier) {
                    repeat(6) { r ->
                        Row(Modifier.fillMaxWidth().height(row)) {
                            repeat(7) { c ->
                                val day = start.plusDays((r * 7 + c).toLong())
                                FullMonthCell(
                                    day, day == selected, day == today, YearMonth.from(day) == month, eventsOn(day), f,
                                    pulseToday && day == today, number, chip, row, lastColumn = c == 6, lastRow = r == 5,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onPreview = { preview(day) },
                                ) { onOpenDay(day) }
                            }
                        }
                    }
                }
            }
        }
    }
    val shown = previewEpochDay?.let(LocalDate::ofEpochDay)
    if (onPreviewDay == null && active && shown != null) {
        DayPreviewSheet(
            shown, eventsOn(shown), f,
            onOpenEvent = { event -> previewEpochDay = null; onOpenEvent?.invoke(event) ?: onOpenDay(shown) },
            onOpenDay = { previewEpochDay = null; onOpenDay(it) },
            onDismiss = { previewEpochDay = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullMonthCell(
    day: LocalDate, isSelected: Boolean, isToday: Boolean, inMonth: Boolean, events: List<CalendarEvent>, f: Formats,
    pulse: Boolean, number: Dp, chip: Dp, row: Dp, lastColumn: Boolean, lastRow: Boolean, modifier: Modifier,
    onPreview: () -> Unit, onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val untitled = stringResource(R.string.untitled)
    val description = buildString {
        append(dayDescription(context, f, day, isToday, events.size))
        if (events.isNotEmpty()) append(". ").append(events.take(5).joinToString(", ") { it.title.ifBlank { untitled } })
        if (events.size > 5) append(", ").append(context.resources.getQuantityString(R.plurals.calendar_more_events, events.size - 5, events.size - 5))
    }
    val openLabel = stringResource(R.string.calendar_open_day)
    val previewLabel = stringResource(R.string.calendar_preview_events)
    val lineColor = p.line
    val slots = ((row - number - 10.dp) / chip).toInt().coerceAtLeast(0)
    val shown = if (events.size > slots) (slots - 1).coerceAtLeast(0) else events.size
    val hidden = events.size - shown
    Column(
        modifier
            .background(if (pulse) p.raised else Color.Transparent)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = stroke)
                if (lastRow) drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = stroke)
                if (!lastColumn) {
                    val x = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                }
            }
            .combinedClickable(
                onClickLabel = openLabel, role = Role.Button, onLongClickLabel = previewLabel,
                onLongClick = onPreview, onClick = onClick,
            )
            .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button }
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.size(number).clip(CircleShape)
                .background(if (isSelected) p.accent else Color.Transparent)
                .then(if (isToday && !isSelected) Modifier.border(1.5.dp, p.ink, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                color = when { isSelected -> p.onAccent; inMonth -> p.ink; else -> p.faint },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, softWrap = false,
            )
        }
        events.take(shown).forEach { e ->
            val colors = eventColors(e.color, p, dimmed = !inMonth)
            Box(
                Modifier.fillMaxWidth().height(chip - 2.dp).clip(RoundedCornerShape(4.dp)).background(colors.container)
                    .then(if (pulse && e.color == 0) Modifier.border(1.dp, p.line, RoundedCornerShape(4.dp)) else Modifier)
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    e.title.ifBlank { untitled }, color = colors.content, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                )
            }
        }
        if (hidden > 0) {
            Box(
                Modifier.fillMaxWidth().height(chip - 2.dp).clip(RoundedCornerShape(4.dp))
                    .clickable(onClickLabel = previewLabel, role = Role.Button, onClick = onPreview),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "+$hidden", Modifier.padding(horizontal = 3.dp), color = p.muted, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}
