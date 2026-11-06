package io.github.sunsind.sunlith.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.device.DeviceLink
import io.github.sunsind.sunlith.device.DeviceLinkChange
import io.github.sunsind.sunlith.device.DeviceLinkChangeAction
import io.github.sunsind.sunlith.device.DeviceLinkChangeField
import io.github.sunsind.sunlith.device.DeviceLinkDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DeviceLinkScreen(vm: SunlithViewModel) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val connection by DeviceLink.state.collectAsState()
    val activity by DeviceLink.activity.collectAsState()
    var page by rememberSaveable { mutableStateOf("connection") }
    var history by remember { mutableStateOf(emptyList<DeviceLinkChange>()) }
    var diagnostics by remember { mutableStateOf(DeviceLinkDiagnostics(null, null)) }
    var paired by remember { mutableStateOf(false) }
    var ids by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    var hosts by remember { mutableStateOf(emptyList<String>()) }
    var host by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var unpair by remember { mutableStateOf(false) }
    var sharedUri by remember { mutableStateOf<Uri?>(null) }
    var exportRequest by remember { mutableStateOf<Pair<Set<Long>, String>?>(null) }
    val back = { if (page == "connection") vm.back() else page = "connection" }
    BackHandler(onBack = back)

    LaunchedEffect(activity, refresh) {
        val data = withContext(Dispatchers.IO) { DeviceLink.history(context) to DeviceLink.diagnostics(context) }
        history = data.first
        diagnostics = data.second
    }

    LaunchedEffect(refresh) {
        val data = withContext(Dispatchers.IO) {
            Triple(DeviceLink.isPaired(context), DeviceLink.pairedCalendarIds(context), DeviceLink.addresses(context))
        }
        paired = data.first
        if (paired) ids = data.second
        hosts = data.third
        if (host !in hosts) host = hosts.firstOrNull().orEmpty()
    }
    LaunchedEffect(connection.error) {
        connection.error?.let { vm.message(it) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) DeviceLink.start(context)
        else vm.message(context.getString(R.string.device_link_notification_required), context.getString(R.string.action_open_settings)) { context.openAppSettings() }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val request = exportRequest
        exportRequest = null
        if (uri != null) {
            if (request == null) vm.message(context.getString(R.string.device_link_export_retry))
            else scope.launch {
                busy = true
                var created = false
                try {
                    withContext(Dispatchers.IO) {
                        val config = DeviceLink.createPairing(context, request.first, request.second)
                        created = true
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(config.toByteArray(Charsets.UTF_8)) }
                    }
                    paired = true
                    ids = request.first
                    sharedUri = uri
                    vm.message(context.getString(R.string.device_link_exported))
                } catch (c: CancellationException) {
                    if (created) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { DeviceLink.revoke(context) }
                    throw c
                } catch (_: Exception) {
                    if (created) withContext(Dispatchers.IO) { DeviceLink.revoke(context) }
                    paired = false
                    vm.message(context.getString(R.string.device_link_export_error))
                } finally {
                    busy = false
                }
            }
        }
    }

    if (page == "history") {
        ConnectionHistoryScreen(history, busy, onBack = back, onClear = {
            scope.launch {
                busy = true
                try { withContext(Dispatchers.IO) { DeviceLink.clearHistory(context) } }
                catch (_: Exception) { vm.message(context.getString(R.string.device_link_failed)) }
                finally { busy = false }
            }
        })
        return
    }
    if (page == "details") {
        Scaffold(containerColor = p.background, topBar = { ScreenTopBar(stringResource(R.string.device_link_details), onNavigate = back) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { ConnectionDetail(stringResource(R.string.device_link_status), stringResource(if (connection.running) R.string.device_link_on else R.string.device_link_off)) }
                item { ConnectionDetail(stringResource(R.string.device_link_paired_since), diagnostics.pairedAtMillis?.let { connectionDate(context, it) }
                    ?: stringResource(if (paired) R.string.device_link_older_pairing else R.string.device_link_not_paired)) }
                item { ConnectionDetail(stringResource(R.string.device_link_last_request), diagnostics.lastSuccessMillis?.let { connectionDate(context, it) } ?: stringResource(R.string.device_link_no_contact)) }
                item { ConnectionDetail(stringResource(R.string.device_link_address), hosts.joinToString("\n").ifBlank { context.getString(R.string.device_link_no_network) }) }
                item { Text(stringResource(R.string.device_link_details_help), color = p.muted, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        return
    }
    Scaffold(containerColor = p.background, topBar = {
        ScreenTopBar(stringResource(R.string.device_link_title), onNavigate = vm::back, actions = {
            TextButton(onClick = { refresh++; vm.reload() }, enabled = !busy) { Text(stringResource(R.string.action_refresh)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.device_link_lead), color = p.muted, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { page = "history" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.device_link_history)) }
                        TextButton(onClick = { page = "details" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.device_link_details)) }
                    }
                    if (paired) {
                        Text(stringResource(if (connection.running) R.string.device_link_on else R.string.device_link_off), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.device_link_running_help), color = p.muted, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { context.openAppSettings() }) { Text(stringResource(R.string.device_link_background)) }
                        Button(onClick = {
                            if (connection.running) DeviceLink.stop(context)
                            else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else DeviceLink.start(context)
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy) {
                            Text(stringResource(if (connection.running) R.string.device_link_stop else R.string.device_link_start))
                        }
                        sharedUri?.let { uri ->
                            TextButton(onClick = {
                                try {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newRawUri("Sunlith pairing", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, context.getString(R.string.device_link_share)))
                                } catch (_: ActivityNotFoundException) {
                                    vm.message(context.getString(R.string.device_link_no_share))
                                }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.device_link_share)) }
                        }
                    } else {
                        Text(stringResource(R.string.device_link_consent), color = p.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.device_link_access)) }
            items(vm.calendars.filter { !paired || it.id in ids }, key = { it.id }) { calendar ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(calendar.id in ids, enabled = !paired && !busy, role = Role.Checkbox) {
                            ids = if (it) ids + calendar.id else ids - calendar.id
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorDot(calendar.color)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(calendar.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (calendar.local) stringResource(R.string.calendar_on_device) else calendar.account,
                            color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        )
                        if (!calendar.writable) Text(stringResource(R.string.calendar_read_only), color = p.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(calendar.id in ids, onCheckedChange = null, enabled = !paired && !busy,
                        colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = p.onAccent))
                }
            }
            if (!paired) {
                if (!vm.hasSunlithCalendar) item {
                    TextButton(onClick = { vm.createLocalCalendar(showInApp = false) { ids = ids + it } }, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.action_create_local))
                    }
                }
                item { SectionTitle(stringResource(R.string.device_link_address)) }
                if (hosts.isEmpty()) item {
                    Text(stringResource(R.string.device_link_no_network), Modifier.padding(16.dp), color = p.muted)
                }
                items(hosts, key = { "host-$it" }) { address ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(address == host, enabled = !busy, role = Role.RadioButton) { host = address }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(address == host, onClick = null, enabled = !busy)
                        Spacer(Modifier.width(12.dp))
                        Text(address)
                    }
                }
                item {
                    Button(onClick = {
                        exportRequest = ids.toSet() to host
                        try { fileLauncher.launch("sunlith-pairing.json") }
                        catch (_: ActivityNotFoundException) { exportRequest = null; vm.message(context.getString(R.string.msg_no_file_picker)) }
                    }, enabled = !busy && ids.isNotEmpty() && ids.any { id -> vm.calendars.any { it.id == id && it.writable } } && host.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.device_link_export))
                    }
                }
            } else item {
                TextButton(onClick = { unpair = true }, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.device_link_unpair))
                }
            }
            item {
                HorizontalDivider(color = p.line, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                TextButton(onClick = { context.openUrl("https://github.com/SunSinD/Sunlith/blob/main/docs/AI-INTEGRATION.md#direct-phone-connection", vm) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.device_link_guide))
                }
            }
        }
    }
    if (unpair) AlertDialog(
        onDismissRequest = { unpair = false }, containerColor = p.surface,
        title = { Text(stringResource(R.string.device_link_unpair)) },
        text = { Text(stringResource(R.string.device_link_unpair_body)) },
        confirmButton = { TextButton(onClick = {
            unpair = false
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) { DeviceLink.revoke(context) }
                    paired = false
                    sharedUri = null
                    refresh++
                    vm.message(context.getString(R.string.device_link_unpaired))
                } catch (_: Exception) { vm.message(context.getString(R.string.device_link_failed)) }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.device_link_unpair)) } },
        dismissButton = { TextButton(onClick = { unpair = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun connectionDate(context: android.content.Context, millis: Long): String = DateUtils.formatDateTime(
    context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
)

@Composable
private fun ConnectionDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = LocalPalette.current.muted)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConnectionHistoryScreen(history: List<DeviceLinkChange>, busy: Boolean, onBack: () -> Unit, onClear: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = p.background, topBar = {
        ScreenTopBar(stringResource(R.string.device_link_history), onNavigate = onBack, actions = {
            if (history.isNotEmpty()) TextButton(onClick = { confirmClear = true }, enabled = !busy) { Text(stringResource(R.string.device_link_history_clear)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Text(stringResource(R.string.device_link_history_help), Modifier.padding(16.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium) }
            if (history.isEmpty()) item { Text(stringResource(R.string.device_link_history_empty), Modifier.padding(16.dp), color = p.muted) }
            items(history) { change ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val title = change.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.untitled)
                    Text(when (change.action) {
                        DeviceLinkChangeAction.CREATE -> stringResource(R.string.device_link_history_added, title)
                        DeviceLinkChangeAction.UPDATE -> stringResource(R.string.device_link_history_edited, title)
                        DeviceLinkChangeAction.DELETE -> stringResource(R.string.device_link_history_deleted, title)
                        DeviceLinkChangeAction.IMPORT -> stringResource(R.string.device_link_history_imported, change.imported, change.duplicates, change.skipped)
                    }, style = MaterialTheme.typography.bodyLarge)
                    Text(connectionDate(context, change.atMillis), color = p.muted, style = MaterialTheme.typography.bodySmall)
                    if (change.fields.isNotEmpty()) Text(change.fields.joinToString(", ") { field -> context.getString(when (field) {
                        DeviceLinkChangeField.TITLE -> R.string.device_link_field_title
                        DeviceLinkChangeField.DESCRIPTION -> R.string.device_link_field_description
                        DeviceLinkChangeField.LOCATION -> R.string.device_link_field_location
                        DeviceLinkChangeField.TIME -> R.string.device_link_field_time
                        DeviceLinkChangeField.RECURRENCE -> R.string.device_link_field_recurrence
                        DeviceLinkChangeField.REMINDERS -> R.string.device_link_field_reminders
                    }) }, color = p.muted, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color = p.line, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, containerColor = p.surface,
        title = { Text(stringResource(R.string.device_link_history_clear)) },
        text = { Text(stringResource(R.string.device_link_history_clear_body)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }, enabled = !busy) { Text(stringResource(R.string.device_link_history_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
