package io.github.sunsind.sunlith.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.school.SchoolAlert
import io.github.sunsind.sunlith.school.SchoolChangeSummary
import io.github.sunsind.sunlith.school.SchoolError
import io.github.sunsind.sunlith.school.SchoolScheduleController
import io.github.sunsind.sunlith.school.SchoolScheduleState
import io.github.sunsind.sunlith.school.SchoolPortalClient
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STALE_AFTER_MILLIS = 24L * 60 * 60 * 1000
private const val MONTMORENCY_PORTAL = "https://enligne.cmontmorency.qc.ca/ColNet/"

private fun isMontmorencyPortal(address: String) = runCatching {
    SchoolPortalClient.normalize(address).toString().equals(SchoolPortalClient.normalize(MONTMORENCY_PORTAL).toString(), ignoreCase = true)
}.getOrDefault(false)

private val AlertGroups = listOf(
    R.string.school_group_classes to listOf(
        SchoolAlert.CLASS_TIMES, SchoolAlert.ROOMS, SchoolAlert.TEACHERS, SchoolAlert.ADDED_CLASSES, SchoolAlert.REMOVED_CLASSES,
    ),
    R.string.school_group_notices to listOf(SchoolAlert.TEACHER_ABSENCES, SchoolAlert.ASSESSMENTS, SchoolAlert.SCHOOL_DAYS),
    R.string.school_group_connection to listOf(SchoolAlert.CONNECTION_PROBLEMS),
)

private fun SchoolAlert.label(): Int = when (this) {
    SchoolAlert.TEACHER_ABSENCES -> R.string.school_alert_teacher_absences
    SchoolAlert.CLASS_TIMES -> R.string.school_alert_class_times
    SchoolAlert.ROOMS -> R.string.school_alert_rooms
    SchoolAlert.TEACHERS -> R.string.school_alert_teachers
    SchoolAlert.ADDED_CLASSES -> R.string.school_alert_added
    SchoolAlert.REMOVED_CLASSES -> R.string.school_alert_removed
    SchoolAlert.ASSESSMENTS -> R.string.school_alert_assessments
    SchoolAlert.SCHOOL_DAYS -> R.string.school_alert_school_days
    SchoolAlert.CONNECTION_PROBLEMS -> R.string.school_alert_connection
}

private fun SchoolError.message(): Int = when (this) {
    SchoolError.INVALID_PORTAL -> R.string.school_error_portal
    SchoolError.SIGN_IN -> R.string.school_error_sign_in
    SchoolError.MFA_REQUIRED -> R.string.school_error_mfa
    SchoolError.NETWORK -> R.string.school_error_network
    SchoolError.CALENDAR_PERMISSION -> R.string.school_error_calendar_permission
    SchoolError.INVALID_DATA -> R.string.school_error_data
    SchoolError.UNAVAILABLE -> R.string.school_error_unavailable
    SchoolError.STORAGE -> R.string.school_error_storage
    SchoolError.LEGACY_COLLISION -> R.string.school_error_existing_classes
    SchoolError.CALENDAR_CHANGED -> R.string.school_error_calendar_changed
}

private fun Context.notificationsAllowed(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }.onFailure { openAppSettings() }
}

private fun zoneLabel(id: String): String {
    val offset = runCatching { ZoneId.of(id).rules.getOffset(Instant.now()).id }.getOrNull()
    val utc = when (offset) { null -> ""; "Z" -> " (UTC)"; else -> " (UTC$offset)" }
    return id.replace('_', ' ') + utc
}

@Composable
fun SchoolScheduleScreen(controller: SchoolScheduleController, onBack: () -> Unit, onChooseCalendars: () -> Unit) {
    val p = LocalPalette.current
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var changing by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val showForm = !state.connected || changing

    fun launchAction(action: suspend () -> Unit, after: () -> Unit = {}) {
        failed = false
        scope.launch {
            try {
                action()
                after()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
    }

    val back = { if (state.connected && changing && !state.busy) changing = false else onBack() }
    BackHandler(onBack = back)

    Scaffold(containerColor = p.background, topBar = { ScreenTopBar(stringResource(R.string.school_title), onNavigate = back) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp),
        ) {
            if (failed) SchoolNotice(stringResource(R.string.school_error_generic), danger = true)
            if (showForm) {
                SignInForm(
                    state = state,
                    changing = state.connected,
                    onCancel = { changing = false },
                    onChooseCalendars = onChooseCalendars,
                ) { portal, user, password, zone, calendarId, clear ->
                    launchAction({ controller.connect(portal, user, password, zone, calendarId) }) {
                        val now = controller.state.value
                        if (now.connected && now.error == null) {
                            clear()
                            changing = false
                        }
                    }
                }
            } else {
                ConnectedContent(
                    state = state,
                    onSync = { launchAction({ controller.refresh() }) },
                    onApply = { launchAction({ controller.applyPendingChanges() }) },
                    onDiscard = { launchAction({ controller.discardPendingChanges() }) },
                    onNotificationsEnabled = { enabled -> launchAction({ controller.setNotificationsEnabled(enabled) }) },
                    onCategory = { alert, enabled -> launchAction({ controller.setCategory(alert, enabled) }) },
                    onChangeConnection = { changing = true },
                    onDisconnect = { remove -> launchAction({ controller.disconnect(remove) }) { changing = false } },
                )
            }
        }
    }
}

@Composable
private fun SignInForm(
    state: SchoolScheduleState,
    changing: Boolean,
    onCancel: () -> Unit,
    onChooseCalendars: () -> Unit,
    onConnect: (String, String, String, String, Long?, () -> Unit) -> Unit,
) {
    val p = LocalPalette.current
    var portal by rememberSaveable { mutableStateOf(state.portalAddress) }
    var school by rememberSaveable { mutableStateOf(if (changing) { if (isMontmorencyPortal(state.portalAddress)) "montmorency" else "custom" } else "") }
    var pickSchool by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf(if (changing) state.accountName else "") }
    // Never saved to the instance state bundle.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var zone by rememberSaveable { mutableStateOf(state.timeZone) }
    var chosenCalendar by rememberSaveable { mutableStateOf(state.calendarId) }
    var more by rememberSaveable { mutableStateOf(false) }
    var pickZone by rememberSaveable { mutableStateOf(false) }
    val calendars = state.availableCalendars
    val calendarId = if (changing) state.calendarId else (chosenCalendar?.takeIf { id -> calendars.any { it.id == id } }
        ?: state.calendarId?.takeIf { id -> calendars.any { it.id == id } }
        ?: calendars.firstOrNull()?.id)
    val destination = calendars.firstOrNull { it.id == calendarId }
    val busy = state.busy
    val ready = !busy && calendarId != null && school.isNotBlank() && portal.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (ready) onConnect(portal.trim(), username.trim(), password, zone, calendarId) { password = ""; showPassword = false }
    }

    SectionTitle(stringResource(R.string.school_provider_colnet))
    if (!changing) SchoolNote(stringResource(R.string.school_intro))
    if (changing) SchoolValueRow(stringResource(R.string.school_institution), stringResource(if (school == "montmorency") R.string.school_montmorency else R.string.school_other_colnet))
    else SchoolClickRow(
        stringResource(R.string.school_institution),
        stringResource(when (school) { "montmorency" -> R.string.school_montmorency; "custom" -> R.string.school_other_colnet; else -> R.string.school_choose_institution }),
        enabled = !busy,
    ) { pickSchool = true }
    val error = state.error
    if (error != null && !busy) SchoolNotice(stringResource(error.message()), danger = true)
    if (changing) {
        if (school == "custom") SchoolValueRow(stringResource(R.string.school_portal_short), portal)
        SchoolValueRow(stringResource(R.string.school_username), username)
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (school == "custom" && !changing) OutlinedTextField(
            value = portal, onValueChange = { portal = it },
            label = { Text(stringResource(R.string.school_portal)) },
            singleLine = true, enabled = !busy, readOnly = changing,
            isError = error == SchoolError.INVALID_PORTAL,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next,
            ),
            colors = schoolFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (school == "custom" && !changing) Text(stringResource(R.string.school_portal_help), color = p.muted, style = MaterialTheme.typography.bodySmall)
        if (!changing) OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text(stringResource(R.string.school_username)) },
            singleLine = true, enabled = !busy, readOnly = changing,
            isError = error == SchoolError.SIGN_IN,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text, imeAction = ImeAction.Next,
            ),
            colors = schoolFieldColors(),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(stringResource(R.string.school_password)) },
            singleLine = true, enabled = !busy,
            isError = error == SchoolError.SIGN_IN,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        stringResource(if (showPassword) R.string.school_password_hide else R.string.school_password_show),
                        tint = p.muted,
                    )
                }
            },
            colors = schoolFieldColors(),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        )
        Text(stringResource(R.string.school_private_note), color = p.muted, style = MaterialTheme.typography.bodySmall)
    }

    when {
        calendars.size > 1 && !changing -> {
            SectionTitle(stringResource(R.string.school_destination))
            Column(Modifier.selectableGroup()) {
                calendars.forEach { cal ->
                    val on = cal.id == calendarId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(on, enabled = !busy, role = Role.RadioButton) { chosenCalendar = cal.id }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = on, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.faint))
                        Spacer(Modifier.width(12.dp))
                        Text(cal.name, Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        destination != null -> SchoolNote(stringResource(R.string.school_destination_single, destination.name))
        changing && state.calendarsLoaded -> SchoolNotice(stringResource(R.string.school_error_calendar_permission), danger = true)
        state.calendarsLoaded -> SchoolNotice(stringResource(R.string.school_no_calendar), action = stringResource(R.string.action_choose_calendars), onAction = onChooseCalendars)
    }

    ExpandRow(stringResource(R.string.school_more_options), more) { more = !more }
    if (more) {
        if (changing) SchoolValueRow(stringResource(R.string.school_time_zone), zoneLabel(zone))
        else SchoolClickRow(
            stringResource(R.string.school_time_zone), zoneLabel(zone), enabled = !busy,
        ) { pickZone = true }
        SchoolNote(stringResource(R.string.school_time_zone_sub))
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = submit, enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = p.onAccent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.school_connecting))
            } else {
                Text(stringResource(R.string.school_connect))
            }
        }
        if (changing) {
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }

    if (pickZone) ZoneDialog(zone, onDismiss = { pickZone = false }) { zone = it; pickZone = false }
    if (pickSchool) AlertDialog(
        onDismissRequest = { pickSchool = false },
        containerColor = p.surface,
        title = { Text(stringResource(R.string.school_choose_institution), color = p.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ZoneOption(stringResource(R.string.school_montmorency), school == "montmorency") {
                    school = "montmorency"; portal = MONTMORENCY_PORTAL; zone = "America/Toronto"; pickSchool = false
                }
                ZoneOption(stringResource(R.string.school_other_colnet), school == "custom") {
                    school = "custom"; portal = ""; zone = ZoneId.systemDefault().id; pickSchool = false
                }
                Text(stringResource(R.string.school_supported_note), color = p.muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { pickSchool = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConnectedContent(
    state: SchoolScheduleState,
    onSync: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onNotificationsEnabled: (Boolean) -> Unit,
    onCategory: (SchoolAlert, Boolean) -> Unit,
    onChangeConnection: () -> Unit,
    onDisconnect: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    var more by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }

    state.pendingChanges?.let { PendingCard(it, state.busy, onApply, onDiscard) }
    StatusCard(state, onSync, onChangeConnection)

    NotificationsSection(state, onNotificationsEnabled, onCategory)

    ExpandRow(stringResource(R.string.school_more_options), more) { more = !more }
    if (more) {
        SchoolValueRow(stringResource(R.string.school_time_zone), zoneLabel(state.timeZone))
        if (isMontmorencyPortal(state.portalAddress)) SchoolValueRow(stringResource(R.string.school_institution), stringResource(R.string.school_montmorency))
        else if (state.portalAddress.isNotBlank()) SchoolValueRow(stringResource(R.string.school_portal_short), state.portalAddress)
        SchoolClickRow(stringResource(R.string.school_change_connection), null, enabled = !state.busy, onClick = onChangeConnection)
        SchoolClickRow(stringResource(R.string.school_disconnect), null, enabled = !state.busy, color = p.danger) { confirmDisconnect = true }
    }

    if (confirmDisconnect) {
        DisconnectDialog(state.eventCount, onDismiss = { confirmDisconnect = false }) { remove ->
            confirmDisconnect = false
            onDisconnect(remove)
        }
    }
}

@Composable
private fun StatusCard(state: SchoolScheduleState, onSync: () -> Unit, onSignInAgain: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val last = state.lastSuccessMillis
    val stale = last == null || now - last > STALE_AFTER_MILLIS
    val calendarName = state.availableCalendars.firstOrNull { it.id == state.calendarId }?.name
    SchoolCard {
        Text(
            state.termName.ifBlank { stringResource(R.string.school_connected) },
            color = p.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading() },
        )
        if (state.accountName.isNotBlank()) {
            Text(state.accountName, color = p.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (calendarName != null) {
            Text(
                pluralStringResource(R.plurals.school_event_count, state.eventCount, state.eventCount, calendarName),
                color = p.muted, style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        val status = when {
            state.busy -> stringResource(R.string.school_syncing)
            last == null -> stringResource(R.string.school_never_updated)
            stale -> stringResource(
                R.string.school_stale,
                DateUtils.formatDateTime(context, last, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
            )
            now - last < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.school_updated_now)
            else -> stringResource(R.string.school_updated, DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS).toString())
        }
        Row(Modifier.semantics(mergeDescendants = true) { if (state.busy) liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = p.muted, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                status, color = if (stale && !state.busy && last != null) p.danger else p.ink,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val error = state.error
        if (error != null && !state.busy) {
            Text(
                stringResource(error.message()) + if (error == SchoolError.STORAGE) " " + stringResource(R.string.school_storage_recovery) else "",
                color = p.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            when (error) {
                SchoolError.SIGN_IN ->
                    CardTextButton(stringResource(R.string.school_sign_in_again), onClick = onSignInAgain)
                SchoolError.CALENDAR_PERMISSION ->
                    CardTextButton(stringResource(R.string.school_open_android_settings)) { context.openAppSettings() }
                else -> Unit
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onSync, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.raised, contentColor = p.ink),
        ) { Text(stringResource(R.string.school_sync_now)) }
        Text(stringResource(R.string.school_refresh_note), color = p.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PendingCard(
    changes: SchoolChangeSummary,
    busy: Boolean,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    val p = LocalPalette.current
    val stacked = LocalDensity.current.fontScale > 1.3f
    SchoolCard(emphasis = true) {
        Text(
            stringResource(if (changes.linked > 0) R.string.school_link_title else R.string.school_review_title),
            color = p.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        if (changes.linked > 0) PendingLine(pluralStringResource(R.plurals.school_pending_linked, changes.linked, changes.linked))
        if (changes.linked > 0) PendingLine(stringResource(R.string.school_link_managed))
        if (changes.added > 0) PendingLine(pluralStringResource(R.plurals.school_pending_added, changes.added, changes.added))
        if (changes.updated > 0) PendingLine(pluralStringResource(R.plurals.school_pending_updated, changes.updated, changes.updated))
        if (changes.removed > 0) PendingLine(pluralStringResource(R.plurals.school_pending_removed, changes.removed, changes.removed))
        Text(stringResource(R.string.school_pending_hold), color = p.muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        val apply: @Composable (Modifier) -> Unit = { m ->
            Button(
                onClick = onApply, enabled = !busy, modifier = m.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
            ) { Text(stringResource(R.string.school_apply)) }
        }
        val discard: @Composable (Modifier) -> Unit = { m ->
            TextButton(onClick = onDiscard, enabled = !busy, modifier = m.heightIn(min = 48.dp)) { Text(stringResource(R.string.school_not_now)) }
        }
        if (stacked) {
            apply(Modifier.fillMaxWidth())
            discard(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                discard(Modifier.weight(1f))
                apply(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PendingLine(text: String) {
    Text(text, color = LocalPalette.current.ink, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun NotificationsSection(
    state: SchoolScheduleState,
    onEnabled: (Boolean) -> Unit,
    onCategory: (SchoolAlert, Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val prefs = state.notifications
    var allowed by remember { mutableStateOf(context.notificationsAllowed()) }
    var denied by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        allowed = context.notificationsAllowed()
        if (allowed) denied = false
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = context.notificationsAllowed()
        if (granted && allowed) {
            denied = false
            onEnabled(true)
        } else {
            denied = true
        }
    }
    val on = prefs.enabled && allowed

    SectionTitle(stringResource(R.string.school_notifications_section))
    SchoolToggleRow(
        title = stringResource(R.string.school_notifications),
        subtitle = stringResource(R.string.school_notifications_sub),
        checked = on,
        enabled = true,
    ) { enable ->
        if (!enable) {
            onEnabled(false)
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            denied = true
        } else {
            onEnabled(true)
        }
    }
    if (denied || (prefs.enabled && !allowed)) {
        SchoolNotice(
            stringResource(R.string.school_notifications_blocked),
            action = stringResource(R.string.school_open_android_settings),
            onAction = { context.openNotificationSettings() },
        )
    }
    if (on) {
        AlertGroups.forEach { (group, alerts) ->
            Text(
                stringResource(group),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
                color = p.muted, style = MaterialTheme.typography.labelLarge,
            )
            alerts.forEach { alert ->
                val checked = alert in prefs.categories
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(checked, role = Role.Checkbox) { keep -> onCategory(alert, keep) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(alert.label()), Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = checked, onCheckedChange = null, colors = checkColors())
                }
            }
        }
    }
}

@Composable
private fun DisconnectDialog(eventCount: Int, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    var remove by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(stringResource(R.string.school_disconnect_title), color = p.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.school_disconnect_body), color = p.ink, style = MaterialTheme.typography.bodyMedium)
                if (eventCount > 0) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(remove, role = Role.Checkbox) { remove = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = remove, onCheckedChange = null, colors = checkColors())
                        Spacer(Modifier.width(12.dp))
                        Text(
                            pluralStringResource(R.plurals.school_disconnect_remove, eventCount, eventCount),
                            Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (remove) Text(stringResource(R.string.school_disconnect_remove_note), color = p.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(remove && eventCount > 0) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(if (remove && eventCount > 0) R.string.school_disconnect_remove_confirm else R.string.school_disconnect),
                    color = if (remove && eventCount > 0) p.danger else p.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ZoneDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    val device = remember { ZoneId.systemDefault().id }
    val zones = remember {
        ZoneId.getAvailableZoneIds()
            .filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()
            .map { it to zoneLabel(it) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) zones else zones.filter { it.second.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(stringResource(R.string.school_time_zone), color = p.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.school_time_zone_search)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    colors = schoolFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).selectableGroup()) {
                    if (query.isBlank()) {
                        item(key = "device") {
                            ZoneOption(stringResource(R.string.school_time_zone_device, zoneLabel(device)), current == device) { onPick(device) }
                            HorizontalDivider(color = p.line)
                        }
                    }
                    if (shown.isEmpty()) {
                        item(key = "none") {
                            Text(stringResource(R.string.school_time_zone_none), Modifier.padding(vertical = 12.dp), color = p.muted)
                        }
                    }
                    items(shown, key = { it.first }) { (id, label) ->
                        ZoneOption(label, current == id) { onPick(id) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ZoneOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.faint))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SchoolCard(emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .then(if (emphasis) Modifier.background(p.raised, shape) else Modifier.background(p.surface, shape))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun CardTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(text) }
}

@Composable
private fun SchoolNote(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = LocalPalette.current.muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SchoolNotice(text: String, danger: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    val p = LocalPalette.current
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(p.surface, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (action != null) 4.dp else 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, color = if (danger) p.danger else p.ink, style = MaterialTheme.typography.bodyMedium)
        if (action != null) CardTextButton(action, onAction)
    }
}

@Composable
private fun ExpandRow(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val state = stringResource(if (expanded) R.string.school_expanded else R.string.school_collapsed)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyLarge)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = p.muted)
    }
}

@Composable
private fun SchoolClickRow(title: String, subtitle: String?, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) color ?: p.ink else p.faint, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodyMedium) }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 12.dp).size(20.dp), tint = p.muted)
    }
}

@Composable
private fun SchoolValueRow(title: String, value: String) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, color = p.ink, style = MaterialTheme.typography.bodyLarge)
        Text(value, color = p.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SchoolToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = switchColors())
    }
}

@Composable
private fun schoolFieldColors() = LocalPalette.current.let { p ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = p.ink, unfocusedTextColor = p.ink, disabledTextColor = p.muted,
        focusedBorderColor = p.accent, unfocusedBorderColor = p.faint, disabledBorderColor = p.line,
        focusedLabelColor = p.ink, unfocusedLabelColor = p.muted, disabledLabelColor = p.faint,
        cursorColor = p.accent, errorBorderColor = p.danger, errorLabelColor = p.danger, errorCursorColor = p.danger,
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
    )
}
