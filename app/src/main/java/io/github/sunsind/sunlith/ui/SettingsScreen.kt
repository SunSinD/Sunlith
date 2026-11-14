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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.DeviceCalendar
import io.github.sunsind.sunlith.system.LauncherIcons
import java.time.LocalDate

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
fun SettingsScreen(vm: SunlithViewModel, initialPage: String = "root") {
    val p = LocalPalette.current
    val context = LocalContext.current
    var page by rememberSaveable(initialPage) { mutableStateOf(initialPage) }
    if (page == "widget") DisposableEffect(vm) { onDispose { vm.commitOpacity() } }
    val back = { if (page == "root" || page == initialPage) vm.back() else page = "root" }
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
        "general" -> R.string.settings_general
        "transfer" -> R.string.beta_settings_transfer
        "widget" -> R.string.beta_settings_widget
        "about" -> R.string.beta_settings_about
        else -> R.string.title_settings
    }
    val motion = rememberMotion(vm.navigationAnimation)
    Scaffold(containerColor = p.background, topBar = { ScreenTopBar(stringResource(title), onNavigate = back) }) { pad ->
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad),
            transitionSpec = {
                (if (motion) fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                else EnterTransition.None togetherWith ExitTransition.None).using(null)
            },
            label = "settings_page",
        ) { shown ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                when (shown) {
                    "root" -> {
                        ClickRow(stringResource(R.string.settings_general), null) { page = "general" }
                        ClickRow(stringResource(R.string.beta_settings_appearance), null) { page = "appearance" }
                        ClickRow(stringResource(R.string.title_calendars), null) { vm.open(Screen.Calendars) }
                        ClickRow(stringResource(R.string.beta_settings_widget), null) { page = "widget" }
                        ClickRow(stringResource(R.string.device_link_title), null) { vm.open(Screen.DeviceLink) }
                        ClickRow(stringResource(R.string.beta_settings_transfer), null) { page = "transfer" }
                        ClickRow(stringResource(R.string.beta_settings_about), null) { page = "about" }
                    }
                    "appearance" -> {
                        SectionTitle(stringResource(R.string.settings_layout))
                        val shownPreset = if (vm.monthLayout == "full") "planner" else "sunlith"
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            LayoutPresetChoices(shownPreset) {
                                if (it != shownPreset) vm.changeMonthLayout(if (it == "planner") "full" else "split")
                            }
                        }
                        DropdownRow(
                            stringResource(R.string.view_week),
                            if (vm.weekLayout == "timeline") "timeline${vm.visibleWeekDays}" else vm.weekLayout,
                            listOf(
                                "list" to stringResource(R.string.settings_week_list),
                                "timeline3" to stringResource(R.string.settings_week_timeline3),
                                "timeline5" to stringResource(R.string.settings_week_timeline5),
                                "timeline7" to stringResource(R.string.settings_week_timeline7),
                                "continuous" to stringResource(R.string.settings_week_continuous),
                            ),
                        ) { choice ->
                            if (choice == "list" || choice == "continuous") vm.changeWeekLayout(choice)
                            else {
                                val days = when (choice) { "timeline3" -> 3; "timeline5" -> 5; else -> 7 }
                                if (vm.visibleWeekDays != days) vm.changeVisibleWeekDays(days)
                                if (vm.weekLayout != "timeline") vm.changeWeekLayout("timeline")
                            }
                        }
                        if (vm.weekLayout == "timeline" && vm.visibleWeekDays == 5) {
                            ToggleRow(stringResource(R.string.settings_rolling_five_days), stringResource(R.string.settings_rolling_five_days_short), vm.rollingFiveDays, vm::changeRollingFiveDays)
                        }
                        ToggleRow(stringResource(R.string.settings_week_header_selection), stringResource(R.string.settings_week_header_selection_short), vm.weekHeaderSelection, vm::changeWeekHeaderSelection)
                        if (vm.weekLayout != "list") TimelineZoomRow(stringResource(R.string.settings_week_zoom), vm.weekTimelineZoom, vm::changeWeekTimelineZoom)
                        DropdownRow(
                            stringResource(R.string.view_day), vm.dayLayout,
                            listOf("cards" to stringResource(R.string.settings_week_list),
                                "timeline" to stringResource(R.string.settings_day_all_hours),
                                "schedule" to stringResource(R.string.custom_schedule)),
                            vm::changeDayLayout,
                        )
                        // The title already says Week, so the toggle needs no second line.
                        ToggleRow(stringResource(R.string.settings_group_empty_hours), null, vm.groupEmptyHours, vm::changeGroupEmptyHours)
                        ToggleRow(stringResource(R.string.beta_collapse_calendar), null, vm.collapseCalendarOnScroll, vm::changeCollapseCalendarOnScroll)
                        SectionTitle(stringResource(R.string.custom_colors))
                        DropdownRow(
                            stringResource(R.string.settings_theme),
                            vm.themeMode,
                            listOf(
                                "system" to stringResource(R.string.theme_system),
                                "light" to stringResource(R.string.theme_light),
                                "dark" to stringResource(R.string.theme_dark),
                            ),
                            vm::setTheme,
                        )
                        ToggleRow(
                            stringResource(R.string.settings_wallpaper_colors),
                            if (Build.VERSION.SDK_INT < 31) stringResource(R.string.custom_material_you_fallback_short) else null,
                            vm.materialYou,
                            vm::changeMaterialYou,
                        )
                        LauncherIconSection(vm)
                    }
                    "general" -> {
                        DropdownRow(
                            stringResource(R.string.beta_start_view),
                            vm.startView,
                            listOf(
                                "Month" to stringResource(R.string.view_month),
                                "Week" to stringResource(R.string.view_week),
                                "Day" to stringResource(R.string.view_day),
                                "last" to stringResource(R.string.beta_last_view),
                            ),
                            vm::changeStartView,
                        )
                        DropdownRow(
                            stringResource(R.string.settings_day_view),
                            vm.dayLayout,
                            listOf(
                                "timeline" to stringResource(R.string.settings_day_all_hours),
                                "cards" to stringResource(R.string.settings_week_list),
                                "schedule" to stringResource(R.string.custom_schedule),
                            ),
                            vm::changeDayLayout,
                        )
                        ToggleRow(stringResource(R.string.settings_monday), null, vm.weekStartsMonday, vm::setMondayFirst)

                        SectionTitle(stringResource(R.string.custom_agenda))
                        DropdownRow(
                            stringResource(R.string.settings_agenda_range),
                            vm.agendaRange,
                            listOf(
                                "continuous" to stringResource(R.string.custom_continuous),
                                "month" to stringResource(R.string.view_month),
                                "week" to stringResource(R.string.view_week),
                                "day" to stringResource(R.string.view_day),
                            ),
                            vm::changeAgendaRange,
                        )
                        DropdownRow(
                            stringResource(R.string.settings_agenda_days),
                            vm.agendaDisplay,
                            listOf(
                                "all" to stringResource(R.string.settings_agenda_all),
                                "compact" to stringResource(R.string.settings_agenda_grouped),
                                "events" to stringResource(R.string.settings_agenda_events),
                            ),
                            vm::changeAgendaDisplay,
                        )

                        SectionTitle(stringResource(R.string.custom_navigation))
                        ToggleRow(stringResource(R.string.settings_keep_date), null, vm.keepSelectedDate, vm::changeKeepSelectedDate)
                        ToggleRow(stringResource(R.string.custom_navigation_arrows), null, vm.showNavigationArrows, vm::changeShowNavigationArrows)
                        ToggleRow(stringResource(R.string.custom_swipe_navigation), null, vm.swipeNavigation, vm::changeSwipeNavigation)
                        ToggleRow(stringResource(R.string.beta_navigation_animation), null, vm.navigationAnimation, vm::changeNavigationAnimation)

                        SectionTitle(stringResource(R.string.beta_settings_reminders))
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
                    "widget" -> {
                        SettingsNote(stringResource(R.string.settings_widget_shared))
                        SectionTitle(stringResource(R.string.beta_widget_days))
                        var days by remember(vm.widgetDays) { mutableStateOf(vm.widgetDays.toFloat()) }
                        val daysValue = pluralStringResource(R.plurals.beta_widget_days_value, Math.round(days), Math.round(days))
                        Text(daysValue, Modifier.padding(horizontal = 16.dp), color = p.ink, style = MaterialTheme.typography.bodyLarge)
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
                        Text("${vm.widgetOpacity}%", Modifier.padding(horizontal = 16.dp), color = p.ink, style = MaterialTheme.typography.bodyLarge)
                        val opacityLabel = stringResource(R.string.settings_widget_opacity)
                        Slider(
                            value = vm.widgetOpacity.toFloat(), onValueChange = { vm.previewOpacity(Math.round(it)) },
                            onValueChangeFinished = vm::commitOpacity, valueRange = 0f..100f, steps = 19,
                            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).semantics { contentDescription = opacityLabel; stateDescription = "${vm.widgetOpacity}%" },
                            colors = sliderColors(),
                        )
                    }
                    "about" -> {
                        ValueRow(stringResource(R.string.settings_version), version)
                        ClickRow(stringResource(R.string.about_source), null) { context.openUrl(SOURCE_URL, vm) }
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

/** Home-screen icon choice. Each preview is the real adaptive icon, drawn with this phone's icon mask. */
@Composable
private fun LauncherIconSection(vm: SunlithViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val selected = vm.launcherIcon
    var pendingStyle by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = vm.launcherIconBusy
    val styles = remember { listOf(LauncherIcons.Style.Light, LauncherIcons.Style.Dark) }
    SectionTitle(stringResource(R.string.launcher_icon_title))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        styles.forEach { style ->
            val chosen = selected == style
            val iconLabel = stringResource(R.string.launcher_icon_choice, stringResource(style.label))
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.weight(1f).clip(shape).border(if (chosen) 2.dp else 1.dp, if (chosen) p.accent else p.line, shape)
                    .semantics { contentDescription = iconLabel }
                    .selectable(chosen, enabled = !busy, role = Role.RadioButton) {
                        if (!chosen && !busy) {
                            pendingStyle = style.key
                        }
                    }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LauncherIconPreview(style.icon, 56.dp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(style.label), style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val target = styles.firstOrNull { it.key == pendingStyle }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) pendingStyle = null },
            containerColor = p.surface,
            title = { Text(stringResource(R.string.launcher_icon_notice_title), color = p.ink) },
            text = { Text(stringResource(R.string.launcher_icon_notice_body), color = p.ink, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val chosenStyle = target
                        pendingStyle = null
                        vm.changeLauncherIcon(chosenStyle)
                    },
                ) { Text(stringResource(R.string.launcher_icon_apply)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingStyle = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun LauncherIconPreview(icon: Int, size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(icon, px) { ContextCompat.getDrawable(context, icon)?.toBitmap(px, px)?.asImageBitmap() }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
}

@Composable
private fun SettingsNote(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = LocalPalette.current.muted, style = MaterialTheme.typography.bodyMedium)
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
        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 12.dp).size(20.dp), tint = p.muted)
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.ink)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = p.muted, maxLines = 1)
    }
}

/** Shows the current choice, stacking it below the title at large text sizes. */
@Composable
private fun <T> DropdownRow(title: String, value: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second.orEmpty()
    val stacked = LocalDensity.current.fontScale > 1.3f
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.DropdownList) { expanded = true }
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (stacked) Text(title, style = MaterialTheme.typography.bodyLarge, color = p.ink)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!stacked) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.ink)
                Spacer(Modifier.width(12.dp))
            }
            Box(if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f, fill = false)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(current, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = if (stacked) TextAlign.Start else TextAlign.End)
                    Icon(Icons.Outlined.ArrowDropDown, null, tint = p.muted)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = p.surface,
                    modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                    options.forEach { (key, label) ->
                        val on = key == value
                        DropdownMenuItem(
                            text = { Text(label, color = p.ink) },
                            onClick = { expanded = false; if (!on) onSelect(key) },
                            trailingIcon = { if (on) Icon(Icons.Outlined.Check, null, tint = p.accent) },
                            modifier = Modifier.testTag("settings_choice_$key").semantics { selected = on },
                        )
                    }
                }
            }
        }
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
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.import_destination), style = MaterialTheme.typography.titleSmall)
                            if (writable.isEmpty()) {
                                Text(stringResource(R.string.editor_no_writable), color = p.muted)
                                TextButton(onClick = { vm.createLocalCalendar { vm.chooseImportCalendar(it) } }) { Text(stringResource(R.string.action_create_local)) }
                            }
                            Column(Modifier.selectableGroup()) {
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
private fun TimelineZoomRow(title: String, value: Int, onChange: (Int) -> Unit) {
    // Pinching saves any 5% step, so the current value stays listed and checked.
    DropdownRow(title, value.toString(), (listOf(50, 75, 100, 125, 150, 200) + value).distinct().sorted().map {
        it.toString() to "$it%"
    }) { onChange(it.toInt()) }
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
            item(key = "school-schedule") {
                ClickRow(stringResource(R.string.school_title), null) { vm.open(Screen.SchoolSchedule) }
            }
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
                    Spacer(Modifier.height(16.dp))
                    ClickRow(stringResource(R.string.action_create_local), stringResource(R.string.settings_local_sub)) { vm.createLocalCalendar() }
                }
            }
        }
    }
}
