package io.github.sunsind.sunlith.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.DeviceCalendar
import java.time.LocalDate

const val AI_GUIDE_URL = "https://github.com/SunSinD/Sunlith/blob/main/docs/AI-INTEGRATION.md"
const val SOURCE_URL = "https://github.com/SunSinD/Sunlith"
const val MIGRATION_URL = "https://github.com/SunSinD/Sunlith/blob/main/docs/MIGRATION.md"

private val IcsTypes = arrayOf("text/calendar", "text/x-vcalendar", "application/ics", "application/octet-stream", "text/plain")

fun Context.openUrl(url: String, vm: SunlithViewModel) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        vm.message(getString(R.string.msg_no_browser))
    }
}

fun Context.openAppSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun SettingsScreen(vm: SunlithViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("root") }
    val back = { if (page == "root") vm.back() else page = "root" }
    BackHandler(onBack = back)
    var help by rememberSaveable { mutableStateOf<Int?>(null) }
    var exportChooser by rememberSaveable { mutableStateOf(false) }
    var exportIds by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onNotificationPermissionResult(granted, fromOnboarding = false)
        if (!granted) vm.message(context.getString(R.string.msg_notifications_denied), context.getString(R.string.action_open_settings)) { context.openAppSettings() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::startImport) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) vm.export(uri, exportIds)
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    val title = when (page) {
        "appearance" -> R.string.beta_settings_appearance
        "calendar" -> R.string.beta_settings_calendar
        "widget" -> R.string.beta_settings_widget
        "reminders" -> R.string.beta_settings_reminders
        "transfer" -> R.string.beta_settings_transfer
        "about" -> R.string.beta_settings_about
        else -> R.string.title_settings
    }
    Scaffold(containerColor = p.background, topBar = { ScreenTopBar(stringResource(title), onNavigate = back) }) { pad ->
        key(page) {
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
                when (page) {
                    "root" -> {
                        ClickRow(stringResource(R.string.device_link_title), stringResource(R.string.device_link_lead)) { vm.open(Screen.DeviceLink) }
                        HorizontalDivider(color = p.line, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        listOf(
                            "appearance" to R.string.beta_settings_appearance,
                            "calendar" to R.string.beta_settings_calendar,
                            "widget" to R.string.beta_settings_widget,
                            "reminders" to R.string.beta_settings_reminders,
                            "transfer" to R.string.beta_settings_transfer,
                        ).forEach { (target, label) ->
                            ClickRow(stringResource(label), null) { page = target }
                        }
                        HorizontalDivider(color = p.line, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        ClickRow(stringResource(R.string.ai_setup_title), null) { vm.open(Screen.AiSetup) }
                        ClickRow(stringResource(R.string.beta_settings_about), null) { page = "about" }
                    }
                    "appearance" -> {
                        ToggleRow(stringResource(R.string.beta_follow_system), null, vm.themeMode == "system") { follow ->
                            vm.setTheme(if (follow) "system" else if (p.dark) "dark" else "light")
                        }
                        if (vm.themeMode != "system") {
                            ToggleRow(stringResource(R.string.beta_dark_theme), null, vm.themeMode == "dark") { vm.setTheme(if (it) "dark" else "light") }
                        }
                        ToggleRow(stringResource(R.string.beta_navigation_animation), null, vm.navigationAnimation, vm::changeNavigationAnimation)
                    }
                    "calendar" -> {
                        ClickRow(stringResource(R.string.settings_choose_calendars), pluralStringResource(R.plurals.calendars_shown, vm.activeIds.size, vm.activeIds.size)) { vm.open(Screen.Calendars) }
                        if (!vm.hasSunlithCalendar) ClickRow(stringResource(R.string.action_create_local), null) { vm.createLocalCalendar() }
                        ToggleRow(stringResource(R.string.settings_monday), null, vm.weekStartsMonday, vm::setMondayFirst)
                        ToggleRow(stringResource(R.string.beta_collapse_calendar), null, vm.collapseCalendarOnScroll, vm::changeCollapseCalendarOnScroll)
                        SectionTitle(stringResource(R.string.beta_start_view))
                        listOf("Month" to R.string.view_month, "Week" to R.string.view_week, "Day" to R.string.view_day, "last" to R.string.beta_last_view).forEach { (value, label) ->
                            ChoiceRow(stringResource(label), vm.startView == value) { vm.changeStartView(value) }
                        }
                        SectionTitle(stringResource(R.string.beta_agenda_display))
                        listOf("all" to R.string.beta_all_days, "compact" to R.string.beta_compact_days, "events" to R.string.beta_event_days).forEach { (value, label) ->
                            ChoiceRow(stringResource(label), vm.agendaDisplay == value) { vm.changeAgendaDisplay(value) }
                        }
                    }
                    "widget" -> {
                        SectionTitle(stringResource(R.string.beta_widget_days))
                        var days by remember(vm.widgetDays) { mutableStateOf(vm.widgetDays.toFloat()) }
                        val daysValue = pluralStringResource(R.plurals.beta_widget_days_value, Math.round(days), Math.round(days))
                        Text(daysValue, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        val daysLabel = stringResource(R.string.beta_widget_days)
                        Slider(
                            value = days, onValueChange = { days = Math.round(it).toFloat() },
                            onValueChangeFinished = { vm.changeWidgetDays(Math.round(days)) },
                            valueRange = 1f..31f, steps = 29,
                            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).semantics { contentDescription = daysLabel; stateDescription = daysValue },
                            colors = sliderColors(),
                        )
                        ToggleRow(stringResource(R.string.beta_widget_empty_days), null, vm.widgetShowEmptyDays, vm::changeWidgetShowEmptyDays)
                        ToggleRow(stringResource(R.string.beta_widget_calendar_name), null, vm.widgetShowCalendarName, vm::changeWidgetShowCalendarName)
                        SectionTitle(stringResource(R.string.settings_widget_opacity))
                        Text("${vm.widgetOpacity}%", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        val opacityLabel = stringResource(R.string.settings_widget_opacity)
                        Slider(
                            value = vm.widgetOpacity.toFloat(), onValueChange = { vm.previewOpacity(Math.round(it)) },
                            onValueChangeFinished = vm::commitOpacity, valueRange = 0f..100f, steps = 19,
                            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).semantics { contentDescription = opacityLabel; stateDescription = "${vm.widgetOpacity}%" },
                            colors = sliderColors(),
                        )
                    }
                    "reminders" -> {
                        ToggleRow(
                            stringResource(R.string.settings_reminders_toggle),
                            if (vm.remindersEnabled && !vm.notificationsGranted) stringResource(R.string.settings_reminders_blocked) else null,
                            vm.remindersEnabled && vm.notificationsGranted,
                        ) { on ->
                            if (!on) vm.setReminders(false)
                            else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else vm.setReminders(true)
                        }
                        ClickRow(stringResource(R.string.beta_notification_settings), null) { context.openAppSettings() }
                    }
                    "transfer" -> {
                        ClickRow(stringResource(R.string.settings_import), null) {
                            try { importLauncher.launch(IcsTypes) } catch (_: ActivityNotFoundException) { vm.message(context.getString(R.string.msg_no_file_picker)) }
                        }
                        ClickRow(stringResource(R.string.settings_export), if (vm.exporting) stringResource(R.string.settings_exporting) else null) {
                            exportIds = vm.activeIds
                            exportChooser = true
                        }
                        ClickRow(stringResource(R.string.help_migration_title), null) { help = R.string.help_migration_title }
                    }
                    "about" -> {
                        ClickRow(stringResource(R.string.about_version, version), null, onClick = null)
                        ClickRow(stringResource(R.string.about_source), SOURCE_URL.removePrefix("https://")) { context.openUrl(SOURCE_URL, vm) }
                    }
                }
            }
        }
    }

    when (help) {
        R.string.help_migration_title -> HelpDialog(stringResource(R.string.help_migration_title), stringResource(R.string.help_migration_body), stringResource(R.string.action_open_guide), { help = null }) { context.openUrl(MIGRATION_URL, vm) }
    }

    if (exportChooser) {
        CalendarPickDialog(
            title = stringResource(R.string.export_title), calendars = vm.calendars, selected = exportIds,
            onToggle = { id, on -> exportIds = if (on) exportIds + id else exportIds - id },
            confirm = stringResource(R.string.action_choose_file), confirmEnabled = exportIds.isNotEmpty(),
            onDismiss = { exportChooser = false },
        ) {
            exportChooser = false
            try { exportLauncher.launch("sunlith-${LocalDate.now()}.ics") } catch (_: ActivityNotFoundException) { vm.message(context.getString(R.string.msg_no_file_picker)) }
        }
    }

    ImportDialog(vm)
}

@Composable
private fun ClickRow(title: String, subtitle: String?, onClick: (() -> Unit)?) {
    val p = LocalPalette.current
    val base = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    Row(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = p.ink)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.muted) }
        }
        if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 12.dp).size(20.dp), tint = p.muted)
    }
}

@Composable
private fun ChoiceRow(title: String, selected: Boolean, onSelect: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.faint))
    }
}

@Composable
private fun sliderColors() = LocalPalette.current.let { p ->
    SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.raised)
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = p.ink)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.muted) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, colors = switchColors())
    }
}

@Composable
private fun HelpDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onAction: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onAction) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun CalendarPickDialog(
    title: String, calendars: List<DeviceCalendar>, selected: Set<Long>, onToggle: (Long, Boolean) -> Unit,
    confirm: String, confirmEnabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                calendars.forEach { cal ->
                    val on = cal.id in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Checkbox) { onToggle(cal.id, it) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, colors = checkColors())
                        Spacer(Modifier.width(12.dp))
                        ColorDot(cal.color)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cal.name, maxLines = 2, overflow = TextOverflow.Ellipsis, color = p.ink)
                            val account = if (cal.local) stringResource(R.string.calendar_on_device) else cal.account
                            if (account.isNotBlank() && account != cal.name) {
                                Text(account, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun checkColors() = LocalPalette.current.let { p ->
    CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.faint, checkmarkColor = p.onAccent)
}

@Composable
private fun ImportDialog(vm: SunlithViewModel) {
    val state = vm.importUi ?: return
    val p = LocalPalette.current
    val writable = vm.writableCalendars
    AlertDialog(
        onDismissRequest = { if (!state.busy) vm.dismissImport() },
        title = { Text(stringResource(if (state.report != null) R.string.import_done_title else R.string.import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val report = state.report
                val preview = state.preview
                when {
                    state.error != null -> Text(state.error, color = p.danger)
                    report != null -> {
                        Text(pluralStringResource(R.plurals.import_imported, report.imported, report.imported))
                        if (report.duplicates > 0) Text(pluralStringResource(R.plurals.import_duplicates, report.duplicates, report.duplicates), color = p.muted)
                        if (report.skipped > 0) Text(pluralStringResource(R.plurals.import_skipped, report.skipped, report.skipped), color = p.muted)
                        Messages(report.messages)
                    }
                    preview == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = p.muted, strokeWidth = 2.dp, modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.import_reading))
                    }
                    else -> {
                        Text(pluralStringResource(R.plurals.import_found, preview.total, preview.total))
                        Text(pluralStringResource(R.plurals.import_importable, preview.importable, preview.importable), color = p.muted)
                        if (preview.skipped > 0) Text(pluralStringResource(R.plurals.import_will_skip, preview.skipped, preview.skipped), color = p.muted)
                        Messages(preview.messages)
                        if (preview.importable > 0) {
                            HorizontalDivider(color = p.line)
                            Text(stringResource(R.string.import_destination), style = MaterialTheme.typography.titleSmall)
                            if (writable.isEmpty()) {
                                Text(stringResource(R.string.editor_no_writable), color = p.muted)
                                TextButton(onClick = { vm.createLocalCalendar { vm.chooseImportCalendar(it) } }) { Text(stringResource(R.string.action_create_local)) }
                            }
                            writable.forEach { cal ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(state.calendarId == cal.id, role = Role.RadioButton) { vm.chooseImportCalendar(cal.id) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = state.calendarId == cal.id, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.faint))
                                    Spacer(Modifier.width(8.dp))
                                    ColorDot(cal.color)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(cal.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (cal.account.isNotBlank() && cal.account != cal.name) Text(cal.account, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (state.busy) Text(stringResource(R.string.import_running), color = p.muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val preview = state.preview
            if (state.report == null && state.error == null && preview != null && preview.importable > 0) {
                TextButton(onClick = vm::runImport, enabled = !state.busy && state.calendarId != null && writable.any { it.id == state.calendarId }) {
                    Text(stringResource(R.string.action_import))
                }
            } else {
                TextButton(onClick = vm::dismissImport, enabled = !state.busy || state.preview == null) { Text(stringResource(if (state.report != null) R.string.action_done else R.string.action_close)) }
            }
        },
        dismissButton = {
            if (state.report == null && state.error == null && state.preview != null && state.preview.importable > 0) {
                TextButton(onClick = vm::dismissImport, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun Messages(messages: List<String>) {
    if (messages.isEmpty()) return
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        messages.take(8).forEach { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall) }
        if (messages.size > 8) Text(pluralStringResource(R.plurals.more_messages, messages.size - 8, messages.size - 8), color = p.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun CalendarsScreen(vm: SunlithViewModel) {
    val p = LocalPalette.current
    BackHandler { vm.back() }
    val otherCalendars = stringResource(R.string.unknown_account)
    val groups = remember(vm.calendars, otherCalendars) { vm.calendars.groupBy { it.account.ifBlank { otherCalendars } }.toList() }
    Scaffold(containerColor = p.background, topBar = { ScreenTopBar(stringResource(R.string.title_calendars), onNavigate = vm::back) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 48.dp)) {
            item { Text(stringResource(R.string.calendars_intro), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium) }
            if (vm.loaded && vm.calendars.isEmpty()) {
                item { Text(stringResource(R.string.no_calendars_on_phone), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            groups.forEach { (account, cals) ->
                item(key = "a-$account") { SectionTitle(account) }
                items(cals, key = { it.id }) { cal ->
                    val on = cal.id in vm.activeIds
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(on, role = Role.Checkbox) { vm.setCalendarActive(cal.id, it) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorDot(cal.color)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cal.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val notes = buildList {
                                if (!cal.writable) add(stringResource(R.string.calendar_read_only))
                                if (cal.local) add(stringResource(R.string.calendar_on_device))
                                if (!cal.visible) add(stringResource(R.string.calendar_hidden_system))
                            }
                            if (notes.isNotEmpty()) Text(notes.joinToString(", "), color = p.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Checkbox(checked = on, onCheckedChange = null, colors = checkColors())
                    }
                }
            }
            if (!vm.hasSunlithCalendar) {
                item {
                    HorizontalDivider(color = p.line, modifier = Modifier.padding(top = 16.dp))
                    ClickRow(stringResource(R.string.action_create_local), stringResource(R.string.settings_local_sub)) { vm.createLocalCalendar() }
                }
            }
        }
    }
}
