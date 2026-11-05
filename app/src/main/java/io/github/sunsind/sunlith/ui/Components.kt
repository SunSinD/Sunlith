package io.github.sunsind.sunlith.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ScreenTopBar(
    title: String,
    navigationIcon: ImageVector = Icons.AutoMirrored.Outlined.ArrowBack,
    navigationLabel: String = stringResource(R.string.action_back),
    onNavigate: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.background).statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigate) { Icon(navigationIcon, navigationLabel, tint = p.ink) }
        Text(
            title, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun EventCard(event: CalendarEvent, day: LocalDate, f: Formats, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val time = context.timeLabel(event, day, zone, f)
    val title = event.title.ifBlank { stringResource(R.string.untitled) }
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
            .background(p.surface).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 8.dp).width(3.dp).fillMaxHeight().background(eventTint(event.color, p), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = p.ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(time, color = p.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            if (event.location.isNotBlank()) {
                Text(event.location, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!event.rrule.isNullOrBlank()) {
            Icon(Icons.Outlined.Repeat, stringResource(R.string.repeating), tint = p.faint, modifier = Modifier.padding(horizontal = 12.dp).size(16.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
fun InfoCard(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).background(p.surface, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, color = p.ink, style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

private val SunGold = Color(0xFFF2B33D)

private val SunRays = listOf(
    Offset(54f, 49.5f) to Offset(54f, 51.5f),
    Offset(54f, 72.5f) to Offset(54f, 74.5f),
    Offset(41.5f, 62f) to Offset(43.5f, 62f),
    Offset(64.5f, 62f) to Offset(66.5f, 62f),
    Offset(45.2f, 53.2f) to Offset(46.6f, 54.6f),
    Offset(61.4f, 69.4f) to Offset(62.8f, 70.8f),
    Offset(62.8f, 53.2f) to Offset(61.4f, 54.6f),
    Offset(46.6f, 69.4f) to Offset(45.2f, 70.8f),
)

/** Matches the calendar and sun used by the launcher icon. */
@Composable
fun SunMark(modifier: Modifier = Modifier) {
    val ink = LocalPalette.current.ink
    Canvas(modifier.size(56.dp)) {
        val unit = size.minDimension / 56f
        fun at(point: Offset) = Offset((point.x - 26f) * unit, (point.y - 26f) * unit)
        val stroke = 3.5f * unit
        drawRoundRect(ink, at(Offset(31f, 33f)), Size(46f * unit, 45f * unit), CornerRadius(6f * unit), style = Stroke(stroke))
        drawLine(ink, at(Offset(31f, 44f)), at(Offset(77f, 44f)), stroke)
        listOf(43f, 65f).forEach { x -> drawLine(ink, at(Offset(x, 29f)), at(Offset(x, 38f)), stroke, StrokeCap.Round) }
        drawCircle(SunGold, 7f * unit, at(Offset(54f, 62f)))
        SunRays.forEach { (from, to) -> drawLine(SunGold, at(from), at(to), 2.5f * unit, StrokeCap.Round) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(
        text.uppercase(), modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
        color = p.muted, style = SectionLabel,
    )
}
