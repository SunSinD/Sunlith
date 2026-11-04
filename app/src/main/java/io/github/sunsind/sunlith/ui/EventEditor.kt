@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sunsind.sunlith.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.DeviceCalendar
import io.github.sunsind.sunlith.data.EventDraft
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class EditorState(
    val id: Long?,
    val calendarId: Long?,
    val lockedCalendar: Boolean,
    val title: String = "",
    val location: String = "",
    val notes: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val timeZone: String,
    val rrule: String?,
    val originalRrule: String?,
    val reminders: List<Int>,
    val originalDraft: EventDraft? = null,
) {
    val recurring: Boolean get() = !rrule.isNullOrBlank()
    val editsSeries: Boolean get() = id != null && !originalRrule.isNullOrBlank()

    fun startMillis(zone: ZoneId): Long = if (allDay) CalendarDates.allDayMillis(startDate)
    else startDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli()

    fun endMillis(zone: ZoneId): Long = if (allDay) CalendarDates.allDayMillis(endDate.plusDays(1))
    else endDate.atTime(endTime).atZone(zone).toInstant().toEpochMilli()

    fun endInvalid(): Boolean {
        if (allDay) return endDate.isBefore(startDate)
        val zone = ZoneId.systemDefault()
        val s = startMillis(zone)
        val e = endMillis(zone)
        return if (recurring) e <= s else e < s
    }

    fun problem(writable: List<DeviceCalendar>): Int? = when {
        title.isBlank() -> R.string.editor_need_title
        endInvalid() -> R.string.editor_end_before_start
        calendarId == null || writable.none { it.id == calendarId } -> R.string.editor_need_calendar
        else -> null
    }

    fun toDraft(zone: ZoneId): EventDraft {
        val original = originalDraft?.let { from(it, zone) }
        val timingUnchanged = original != null && allDay == original.allDay &&
            startDate == original.startDate && startTime == original.startTime &&
            endDate == original.endDate && endTime == original.endTime
        return EventDraft(
            id = id, calendarId = calendarId ?: error("No calendar"), title = title.trim(), description = notes,
            location = location.trim(),
            startMillis = if (timingUnchanged) originalDraft!!.startMillis else startMillis(zone),
            endMillis = if (timingUnchanged) originalDraft!!.endMillis else endMillis(zone), allDay = allDay,
            timeZone = if (allDay) "UTC" else timeZone, rrule = rrule?.takeIf(String::isNotBlank),
            reminderMinutes = reminders.distinct().sorted(),
        )
    }

    fun withStartDate(date: LocalDate): EditorState {
        val days = ChronoUnit.DAYS.between(startDate, date)
        return copy(startDate = date, endDate = endDate.plusDays(days))
    }

    fun withStartTime(time: LocalTime): EditorState {
        val oldStart = startDate.atTime(startTime)
        val duration = Duration.between(oldStart, endDate.atTime(endTime)).let { if (it.isNegative) Duration.ofHours(1) else it }
        val newEnd = startDate.atTime(time).plus(duration)
        return copy(startTime = time, endDate = newEnd.toLocalDate(), endTime = newEnd.toLocalTime())
    }

    companion object {
        fun from(d: EventDraft, zone: ZoneId): EditorState {
            val base = EditorState(
                id = d.id, calendarId = d.calendarId, lockedCalendar = d.id != null, title = d.title,
                location = d.location, notes = d.description, allDay = d.allDay,
                startDate = LocalDate.now(), startTime = LocalTime.of(9, 0), endDate = LocalDate.now(), endTime = LocalTime.of(10, 0),
                timeZone = if (d.allDay) java.util.TimeZone.getDefault().id else d.timeZone,
                rrule = d.rrule, originalRrule = d.rrule, reminders = d.reminderMinutes.distinct().sorted(),
                originalDraft = d,
            )
            if (d.allDay) {
                val s = CalendarDates.allDayDate(d.startMillis)
                val endEx = CalendarDates.allDayDate(d.endMillis)
                return base.copy(startDate = s, endDate = if (endEx.isAfter(s)) endEx.minusDays(1) else s)
            }
            val s = Instant.ofEpochMilli(d.startMillis).atZone(zone).toLocalDateTime()
            val e = Instant.ofEpochMilli(maxOf(d.endMillis, d.startMillis)).atZone(zone).toLocalDateTime()
            return base.copy(startDate = s.toLocalDate(), startTime = s.toLocalTime(), endDate = e.toLocalDate(), endTime = e.toLocalTime())
        }
    }
}

private enum class Picking { StartDate, StartTime, EndDate, EndTime }

private val ReminderPresets = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditorScreen(vm: SunlithViewModel) {
    val state = vm.editor ?: return
    val p = LocalPalette.current
    val f = rememberFormats()
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
    var pendingReminder by rememberSaveable { mutableStateOf<Int?>(null) }
    val writable = vm.writableCalendars
    val problem = state.problem(writable)

    val close: () -> Unit = { if (vm.editorDirty) confirmDiscard = true else vm.closeEditor() }
    BackHandler(onBack = close)

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val minutes = pendingReminder
        pendingReminder = null
        vm.onNotificationPermissionResult(granted, fromOnboarding = false)
        if (minutes != null) vm.updateEditor { it.copy(reminders = (it.reminders + minutes).distinct().sorted()) }
        if (!granted) vm.message(context.getString(R.string.msg_notifications_denied), context.getString(R.string.action_open_settings)) { context.openAppSettings() }
    }
    fun addReminder(minutes: Int) {
        val needs = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) {
            pendingReminder = minutes
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (!vm.remindersEnabled) vm.setReminders(true)
            vm.updateEditor { it.copy(reminders = (it.reminders + minutes).distinct().sorted()) }
        }
    }

    Scaffold(
        containerColor = p.background,
        topBar = {
            ScreenTopBar(
                title = stringResource(if (state.id == null) R.string.editor_new else R.string.editor_edit),
                navigationIcon = Icons.Outlined.Close,
                navigationLabel = stringResource(R.string.action_close),
                onNavigate = close,
            ) {
                TextButton(onClick = vm::saveEditor, enabled = problem == null && !vm.saving, modifier = Modifier.padding(end = 4.dp)) {
                    Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp),
        ) {
            TextField(
                value = state.title,
                onValueChange = { v -> vm.updateEditor { it.copy(title = v) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                textStyle = MaterialTheme.typography.headlineSmall,
                placeholder = { Text(stringResource(R.string.editor_title_hint), style = MaterialTheme.typography.headlineSmall, color = p.faint) },
                colors = plainFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 3,
            )
            if (state.editsSeries) Notice(stringResource(R.string.editor_series_note))
            HorizontalDivider(color = p.line)

            EditorRow(Icons.Outlined.Schedule, onClick = { vm.updateEditor { it.copy(allDay = !it.allDay) } }, role = Role.Switch) {
                Text(stringResource(R.string.all_day), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.allDay, onCheckedChange = { v -> vm.updateEditor { it.copy(allDay = v) } }, colors = switchColors())
            }
            DateTimeRow(
                label = stringResource(R.string.editor_starts), date = f.dayShort(state.startDate),
                time = if (state.allDay) null else f.time(state.startTime), error = false,
                onDate = { picking = Picking.StartDate }, onTime = { picking = Picking.StartTime },
            )
            DateTimeRow(
                label = stringResource(R.string.editor_ends), date = f.dayShort(state.endDate),
                time = if (state.allDay) null else f.time(state.endTime), error = state.endInvalid(),
                onDate = { picking = Picking.EndDate }, onTime = { picking = Picking.EndTime },
            )
            if (state.endInvalid()) {
                Text(stringResource(R.string.editor_end_before_start), Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp), color = p.danger, style = MaterialTheme.typography.bodyMedium)
            }
            if (!state.allDay && state.timeZone != java.util.TimeZone.getDefault().id) {
                Text(stringResource(R.string.editor_timezone_note, state.timeZone), Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp), color = p.muted, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = p.line)

            CalendarChooser(state, writable, vm)
            RepeatChooser(state, vm, f)
            HorizontalDivider(color = p.line)

            var addOpen by remember { mutableStateOf(false) }
            EditorRow(Icons.Outlined.Notifications, onClick = null) {
                Column(Modifier.weight(1f)) {
                    if (state.reminders.isEmpty()) {
                        Text(stringResource(R.string.editor_no_reminders), color = p.muted, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.reminders.forEach { minutes ->
                                InputChip(
                                    selected = false,
                                    onClick = { vm.updateEditor { it.copy(reminders = it.reminders - minutes) } },
                                    label = { Text(f.reminder(minutes)) },
                                    trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.editor_remove_reminder, f.reminder(minutes)), Modifier.size(InputChipDefaults.IconSize)) },
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { addOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.editor_add_reminder)) }
                        DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                            ReminderPresets.filter { it !in state.reminders }.forEach { minutes ->
                                DropdownMenuItem(text = { Text(f.reminder(minutes)) }, onClick = { addOpen = false; addReminder(minutes) })
                            }
                        }
                    }
                    if (state.reminders.isNotEmpty() && (!vm.remindersEnabled || !vm.notificationsGranted)) {
                        Text(stringResource(R.string.editor_reminders_off), color = p.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider(color = p.line)

            EditorField(Icons.Outlined.LocationOn, state.location, stringResource(R.string.editor_location_hint), singleLine = true) { v -> vm.updateEditor { it.copy(location = v) } }
            EditorField(Icons.AutoMirrored.Outlined.Notes, state.notes, stringResource(R.string.editor_notes_hint), singleLine = false) { v -> vm.updateEditor { it.copy(notes = v) } }

            problem?.let { if (it != R.string.editor_end_before_start && state.title.isNotBlank()) Notice(stringResource(it)) }

            if (state.id != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.editsSeries) R.string.action_delete_series else R.string.action_delete_event), color = p.danger)
                }
            }
        }
    }

    when (picking) {
        Picking.StartDate -> DatePick(state.startDate, { picking = null }) { d -> vm.updateEditor { it.withStartDate(d) } }
        Picking.EndDate -> DatePick(state.endDate, { picking = null }) { d -> vm.updateEditor { it.copy(endDate = d) } }
        Picking.StartTime -> TimePick(state.startTime, f.is24Hour, { picking = null }) { t -> vm.updateEditor { it.withStartTime(t) } }
        Picking.EndTime -> TimePick(state.endTime, f.is24Hour, { picking = null }) { t -> vm.updateEditor { it.copy(endTime = t) } }
        null -> Unit
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_body)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.closeEditor() }) { Text(stringResource(R.string.action_discard), color = p.danger) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_keep_editing)) } },
        )
    }
    if (confirmDelete && state.id != null) {
        DeleteDialog(series = state.editsSeries, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            vm.deleteEvent(state.id)
        }
    }
}

@Composable
fun DeleteDialog(series: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (series) R.string.delete_series_title else R.string.delete_event_title)) },
        text = { Text(stringResource(if (series) R.string.delete_series_body else R.string.delete_event_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(if (series) R.string.action_delete_series else R.string.action_delete), color = p.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CalendarChooser(state: EditorState, writable: List<DeviceCalendar>, vm: SunlithViewModel) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val current = vm.calendars.firstOrNull { it.id == state.calendarId }
    if (writable.isEmpty()) {
        EditorRow(Icons.Outlined.Event, onClick = null) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_no_writable), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { vm.createLocalCalendar { id -> vm.updateEditor { it.copy(calendarId = id) } } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_create_local))
                }
            }
        }
        return
    }
    Box {
        EditorRow(Icons.Outlined.Event, onClick = if (state.lockedCalendar) null else ({ open = true })) {
            if (current != null) ColorDot(current.color, Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: stringResource(R.string.editor_choose_calendar), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    current == null -> null
                    state.lockedCalendar -> stringResource(R.string.editor_calendar_locked, current.account)
                    else -> current.account.takeIf { it.isNotBlank() && it != current.name }
                }
                sub?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            writable.forEach { cal ->
                DropdownMenuItem(
                    leadingIcon = { ColorDot(cal.color) },
                    text = {
                        Column {
                            Text(cal.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (cal.account.isNotBlank() && cal.account != cal.name) Text(cal.account, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { open = false; vm.updateEditor { it.copy(calendarId = cal.id) } },
                )
            }
        }
    }
}

@Composable
private fun RepeatChooser(state: EditorState, vm: SunlithViewModel, f: Formats) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val originalCustom = state.originalRrule?.takeIf { RepeatPreset.match(it) == null }
    Box {
        EditorRow(Icons.Outlined.Repeat, onClick = { open = true }) {
            Column(Modifier.weight(1f)) {
                Text(f.repeat(state.rrule), style = MaterialTheme.typography.bodyLarge)
                if (state.rrule != null && RepeatPreset.match(state.rrule) == null) {
                    Text(stringResource(R.string.editor_custom_kept), color = p.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RepeatPreset.entries.forEach { preset ->
                DropdownMenuItem(text = { Text(stringResource(preset.label)) }, onClick = { open = false; vm.updateEditor { it.copy(rrule = preset.rule) } })
            }
            if (originalCustom != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.repeat_keep_custom, f.repeat(originalCustom))) }, onClick = { open = false; vm.updateEditor { it.copy(rrule = originalCustom) } })
            }
        }
    }
}

@Composable
private fun DateTimeRow(label: String, date: String, time: String?, error: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    val p = LocalPalette.current
    val color = if (error) p.danger else p.ink
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 56.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PickerText(date, color, Modifier.weight(1f, fill = false), onDate)
            if (time != null) PickerText(time, color, Modifier, onTime)
        }
    }
}

@Composable
private fun PickerText(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip8().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = color, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun EditorRow(icon: ImageVector, onClick: (() -> Unit)?, role: Role = Role.Button, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val p = LocalPalette.current
    val base = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    Row(
        (if (onClick != null) base.clickable(role = role, onClick = onClick) else base).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = p.muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        content()
    }
}

@Composable
private fun EditorField(icon: ImageVector, value: String, hint: String, singleLine: Boolean, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp), verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
        Icon(icon, null, tint = p.muted, modifier = Modifier.padding(top = if (singleLine) 0.dp else 16.dp).size(24.dp))
        TextField(
            value = value, onValueChange = onChange, modifier = Modifier.weight(1f),
            placeholder = { Text(hint, color = p.faint) }, colors = plainFieldColors(), singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

@Composable
private fun Notice(text: String) {
    val p = LocalPalette.current
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(p.surface, RoundedCornerShape(10.dp)).padding(12.dp),
        color = p.muted, style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun plainFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
)

@Composable
fun switchColors() = LocalPalette.current.let { p ->
    SwitchDefaults.colors(
        checkedThumbColor = p.onAccent, checkedTrackColor = p.accent, checkedBorderColor = p.accent,
        uncheckedThumbColor = p.muted, uncheckedTrackColor = p.raised, uncheckedBorderColor = p.faint,
    )
}

@Composable
fun ColorDot(color: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.size(12.dp).background(eventTint(color, p), CircleShape))
}

@Composable
fun DatePick(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state) }
}

@Composable
private fun TimePick(initial: LocalTime, is24: Boolean, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Box(Modifier.verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) { TimePicker(state) } },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

fun Modifier.clip8(): Modifier = this.then(Modifier.clip(RoundedCornerShape(8.dp)))
