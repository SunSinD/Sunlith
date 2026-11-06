package io.github.sunsind.sunlith.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.YearMonth

/**
 * A month grid that fills the screen with six fixed rows and shows event titles inside each date. Rows never grow
 * with their events: a date shows as many titles as fit and a "+n" count for the rest. When the text is too large
 * for six rows to fit, the rows keep a readable minimum height and the grid scrolls. Tapping a date opens it in the
 * Day view, where every event is listed.
 */
@Composable
internal fun FullMonthGrid(
    month: YearMonth, selected: LocalDate, today: LocalDate, mondayFirst: Boolean,
    eventsOn: (LocalDate) -> List<CalendarEvent>, f: Formats, pulseToday: Boolean,
    onOpenDay: (LocalDate) -> Unit, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val number = with(density) { maxOf(26.dp, (typography.bodyMedium.fontSize * 1.9f).toDp()) }
    val chip = with(density) { maxOf(16.dp, typography.labelSmall.lineHeight.toDp() + 4.dp) }
    val start = gridStart(month, mondayFirst)
    Column(modifier.fillMaxSize().padding(horizontal = 2.dp).testTag("calendar_full_month")) {
        WeekdayLabels(mondayFirst, f)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("calendar_grid")) {
            val minRow = maxOf(56.dp, number + chip + 10.dp)
            val row = maxOf(maxHeight / 6, minRow)
            val scrolls = row * 6 > maxHeight
            key(month) {
                Column(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
                    repeat(6) { r ->
                        Row(Modifier.fillMaxWidth().height(row)) {
                            repeat(7) { c ->
                                val day = start.plusDays((r * 7 + c).toLong())
                                FullMonthCell(
                                    day, day == selected, day == today, YearMonth.from(day) == month, eventsOn(day), f,
                                    pulseToday && day == today, number, chip, row, Modifier.weight(1f).fillMaxHeight(),
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
    pulse: Boolean, number: Dp, chip: Dp, row: Dp, modifier: Modifier, onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val description = dayDescription(context, f, day, isToday, events.size)
    val openLabel = stringResource(R.string.calendar_open_day)
    val lineColor = p.line
    val slots = ((row - number - 10.dp) / chip).toInt().coerceAtLeast(0)
    val shown = if (events.size > slots) (slots - 1).coerceAtLeast(0) else events.size
    val hidden = events.size - shown
    Column(
        modifier
            .drawBehind { drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
            .background(if (pulse) p.raised else Color.Transparent)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button }
            .padding(horizontal = 1.dp, vertical = 3.dp),
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
            Row(
                Modifier.fillMaxWidth().height(chip - 2.dp).clip(RoundedCornerShape(3.dp)).background(p.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(if (inMonth) eventTint(e.color, p) else p.faint))
                Text(
                    e.title.ifBlank { stringResource(R.string.untitled) }, Modifier.padding(horizontal = 2.dp),
                    color = if (inMonth) p.ink else p.muted, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                )
            }
        }
        if (hidden > 0) {
            Text("+$hidden", color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
    }
}
