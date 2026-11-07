package io.github.sunsind.sunlith.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.YearMonth

/**
 * A month grid that fills the screen with six fixed rows, ruled into cells, and shows event titles as solid chips
 * inside each date. Rows never grow with their events: a date shows as many titles as fit, and the Day view lists
 * them all. When the text is too large for six rows to fit, the rows keep a readable minimum height and the grid
 * scrolls.
 *
 * Tapping any date calls [onOpenDay], whether it has no events, a few, or more than fit.
 */
@Composable
internal fun FullMonthGrid(
    month: YearMonth, selected: LocalDate, today: LocalDate, mondayFirst: Boolean,
    eventsOn: (LocalDate) -> List<CalendarEvent>, f: Formats, pulseToday: Boolean,
    onOpenDay: (LocalDate) -> Unit, modifier: Modifier = Modifier,
    row: Dp, number: Dp, chip: Dp, viewportHeight: Dp, textMeasurer: TextMeasurer,
    todayRequest: Int = 0, active: Boolean = true, onAwayChanged: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val start = remember(month, mondayFirst) { gridStart(month, mondayFirst) }
    val dates = remember(start) { List(42) { start.plusDays(it.toLong()) } }
    Column(modifier.fillMaxSize().padding(horizontal = 2.dp).testTag("calendar_full_month")) {
        Box(Modifier.weight(1f).fillMaxWidth().testTag("calendar_grid")) {
            val scrolls = row * 6 > viewportHeight
            key(month) {
                val scroll = rememberScrollState()
                val rowPx = with(density) { row.toPx() }
                val dateHeightPx = with(density) { (number + 6.dp).toPx() }
                val heightPx = with(density) { viewportHeight.toPx() }
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
                                val day = dates[r * 7 + c]
                                FullMonthCell(
                                    day, day == selected, day == today, YearMonth.from(day) == month, eventsOn(day), f,
                                    pulseToday && day == today, number, chip, row, textMeasurer, lastColumn = c == 6, lastRow = r == 5,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                ) { onOpenDay(day) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullMonthCell(
    day: LocalDate, isSelected: Boolean, isToday: Boolean, inMonth: Boolean, events: List<CalendarEvent>, f: Formats,
    pulse: Boolean, number: Dp, chip: Dp, row: Dp, measurer: TextMeasurer, lastColumn: Boolean, lastRow: Boolean, modifier: Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val untitled = stringResource(R.string.untitled)
    val description = remember(day, isToday, events, f, untitled) {
        buildString {
            append(dayDescription(context, f, day, isToday, events.size))
            if (events.isNotEmpty()) append(". ").append(events.take(5).joinToString(", ") { it.title.ifBlank { untitled } })
            if (events.size > 5) append(", ").append(context.resources.getQuantityString(R.plurals.calendar_more_events, events.size - 5, events.size - 5))
        }
    }
    val openLabel = stringResource(R.string.calendar_open_day)
    val lineColor = p.line
    val slots = ((row - number - 10.dp) / chip).toInt().coerceAtLeast(0)
    val shown = minOf(events.size, slots)
    val numberStyle = MaterialTheme.typography.bodyMedium.copy(
        color = when { isSelected -> p.onAccent; inMonth -> p.ink; else -> p.faint },
        fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
    )
    val chipStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
    Box(
        modifier
            .drawWithCache {
                val inset = 2.dp.toPx()
                val top = 3.dp.toPx()
                val available = (size.width - inset * 2).coerceAtLeast(0f)
                val numberSize = number.toPx()
                val circleWidth = minOf(numberSize, available)
                val circleLeft = (size.width - circleWidth) / 2
                val numberText = measurer.measure(day.dayOfMonth.toString(), numberStyle, maxLines = 1,
                    softWrap = false, constraints = Constraints(maxWidth = circleWidth.toInt().coerceAtLeast(0)))
                val chipHeight = (chip - 2.dp).toPx()
                val chipTop = top + numberSize + 2.dp.toPx()
                val labels = events.take(shown).map { event ->
                    val colors = eventColors(event.color, p, dimmed = !inMonth)
                    val text = measurer.measure(event.title.ifBlank { untitled }, chipStyle.copy(color = colors.content),
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        constraints = Constraints(maxWidth = (available - 6.dp.toPx()).toInt().coerceAtLeast(0)))
                    Triple(event, colors, text)
                }
                onDrawBehind {
                    if (pulse) drawRect(p.raised)
                    val stroke = 1.dp.toPx()
                    drawLine(lineColor, Offset.Zero, Offset(size.width, 0f), strokeWidth = stroke)
                    if (lastRow) drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = stroke)
                    if (!lastColumn) {
                        val x = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                    }
                    val circleRadius = minOf(circleWidth, numberSize) / 2
                    val center = Offset(size.width / 2, top + numberSize / 2)
                    if (isSelected) drawCircle(p.accent, circleRadius, center)
                    else if (isToday) drawCircle(p.ink, circleRadius - 0.75.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                    drawText(numberText, topLeft = Offset(circleLeft + (circleWidth - numberText.size.width) / 2,
                        top + (numberSize - numberText.size.height) / 2))
                    labels.forEachIndexed { index, (event, colors, text) ->
                        val y = chipTop + index * chip.toPx()
                        drawRoundRect(colors.container, Offset(inset, y), Size(available, chipHeight), CornerRadius(4.dp.toPx()))
                        if (pulse && event.color == 0) drawRoundRect(p.line, Offset(inset, y), Size(available, chipHeight),
                            CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
                        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - inset - 3.dp.toPx() - text.size.width else inset + 3.dp.toPx()
                        drawText(text, topLeft = Offset(x, y + (chipHeight - text.size.height) / 2))
                    }
                }
            }
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button },
    )
}
