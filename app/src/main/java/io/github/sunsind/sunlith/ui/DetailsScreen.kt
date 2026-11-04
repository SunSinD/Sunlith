package io.github.sunsind.sunlith.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.data.EditBlockReason
import java.time.Instant
import java.time.ZoneId

@Composable
fun DetailsScreen(vm: SunlithViewModel, event: CalendarEvent) {
    val p = LocalPalette.current
    val f = rememberFormats()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val calendar = vm.calendars.firstOrNull { it.id == event.calendarId }
    val writable = calendar?.writable == true
    val series = !event.rrule.isNullOrBlank()
    var draft by remember(event.id) { mutableStateOf<EventDraft?>(null) }
    var blockReason by remember(event.id) { mutableStateOf<EditBlockReason?>(null) }
    var permissionsLoaded by remember(event.id) { mutableStateOf(false) }
    LaunchedEffect(event.id, vm.events) {
        permissionsLoaded = false
        draft = vm.loadDraft(event.id)
        blockReason = vm.editBlockReason(event.id)
        permissionsLoaded = true
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmSeriesEdit by rememberSaveable { mutableStateOf(false) }
    BackHandler { vm.back() }

    Scaffold(
        containerColor = p.background,
        topBar = {
            ScreenTopBar(title = "", onNavigate = vm::back) {
                if (writable && permissionsLoaded && blockReason == null) {
                    IconButton(onClick = { if (series) confirmSeriesEdit = true else vm.editEvent(event.id) }) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit), tint = p.ink)
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete), tint = p.ink) }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 8.dp).size(14.dp).background(eventTint(event.color, p), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(16.dp))
                SelectionContainer {
                    Text(
                        event.title.ifBlank { stringResource(R.string.untitled) }, color = p.ink,
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            DetailRow(Icons.Outlined.Schedule) {
                val span = event.span(zone)
                when {
                    event.allDay && !span.multiDay -> {
                        PrimaryText(f.dayLong(span.first))
                        SecondaryText(stringResource(R.string.all_day))
                    }
                    event.allDay -> {
                        PrimaryText(f.range(f.dayShort(span.first), f.dayShort(span.last)))
                        SecondaryText(stringResource(R.string.all_day_days, span.length.toInt()))
                    }
                    !span.multiDay -> {
                        PrimaryText(f.dayLong(span.first))
                        SecondaryText(f.timeRange(event.startMillis, event.endMillis))
                    }
                    else -> {
                        val endDate = Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalDate()
                        PrimaryText("${f.dayShort(span.first)}, ${f.time(event.startMillis)}")
                        PrimaryText("${f.dayShort(endDate)}, ${f.time(event.endMillis)}")
                    }
                }
                if (!event.allDay && event.timeZone.isNotBlank() && event.timeZone != java.util.TimeZone.getDefault().id) {
                    SecondaryText(stringResource(R.string.details_timezone, event.timeZone))
                }
            }
            val rule = draft?.rrule ?: event.rrule
            if (!rule.isNullOrBlank()) DetailRow(Icons.Outlined.Repeat) { PrimaryText(f.repeat(rule)) }
            DetailRow(Icons.Outlined.Event) {
                PrimaryText(calendar?.name ?: event.calendarName.ifBlank { stringResource(R.string.unknown_calendar) })
                calendar?.account?.takeIf { it.isNotBlank() && it != calendar.name }?.let { SecondaryText(it) }
            }
            if (calendar != null && !calendar.writable) DetailRow(Icons.Outlined.Lock) { SecondaryText(stringResource(R.string.details_read_only)) }
            if (writable && permissionsLoaded && blockReason != null) DetailRow(Icons.Outlined.Lock) {
                SecondaryText(stringResource(when (blockReason) {
                    EditBlockReason.GUESTS -> R.string.details_guests
                    EditBlockReason.RECURRENCE_EXCEPTION -> R.string.details_exceptions
                    EditBlockReason.MISSING -> R.string.msg_event_missing
                    EditBlockReason.READ_ONLY -> R.string.details_read_only
                    else -> R.string.details_unsupported
                }))
            }
            if (event.location.isNotBlank()) {
                DetailRow(Icons.Outlined.LocationOn, onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(event.location))))
                    } catch (_: ActivityNotFoundException) {
                        vm.message(context.getString(R.string.msg_no_maps))
                    }
                }) { PrimaryText(event.location) }
            }
            draft?.reminderMinutes?.takeIf { it.isNotEmpty() }?.let { reminders ->
                DetailRow(Icons.Outlined.Notifications) { reminders.sorted().forEach { PrimaryText(f.reminder(it)) } }
            }
            val notes = draft?.description ?: event.description
            if (notes.isNotBlank()) {
                DetailRow(Icons.AutoMirrored.Outlined.Notes) {
                    SelectionContainer { Text(notes, color = p.ink, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }

    if (confirmSeriesEdit) {
        AlertDialog(
            onDismissRequest = { confirmSeriesEdit = false },
            title = { Text(stringResource(R.string.edit_series_title)) },
            text = { Text(stringResource(R.string.edit_series_body)) },
            confirmButton = { TextButton(onClick = { confirmSeriesEdit = false; vm.editEvent(event.id) }) { Text(stringResource(R.string.action_edit_series)) } },
            dismissButton = { TextButton(onClick = { confirmSeriesEdit = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDelete) {
        DeleteDialog(series = series, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            vm.deleteEvent(event.id)
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val base = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    Row(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = p.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun PrimaryText(text: String) {
    Text(text, color = LocalPalette.current.ink, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SecondaryText(text: String) {
    Text(text, color = LocalPalette.current.muted, style = MaterialTheme.typography.bodyMedium)
}
