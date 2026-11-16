package io.github.sunsind.sunlith.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.system.AppUpdateController
import io.github.sunsind.sunlith.system.AppUpdateIntents
import io.github.sunsind.sunlith.system.AppUpdateState
import io.github.sunsind.sunlith.system.AppUpdater
import io.github.sunsind.sunlith.system.UpdateCommand
import io.github.sunsind.sunlith.system.UpdateProblem

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    internal val controller = AppUpdateController(AppUpdater.create(application), viewModelScope)
}

/** Manual update check for the About page. Nothing here touches the network until the user taps it. */
@Composable
fun AppUpdateSection() {
    val vm: AppUpdateViewModel = viewModel()
    AppUpdateSection(vm.controller)
}

/** [openOverride] lets tests receive the exact intents instead of opening Android screens. */
@Composable
internal fun AppUpdateSection(controller: AppUpdateController, openOverride: ((Intent) -> Unit)? = null) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val command by controller.command.collectAsState()
    val override by rememberUpdatedState(openOverride)
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller.onInstallerReturned() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller.onPermissionReturned() }
    DisposableEffect(controller) {
        controller.attach()
        onDispose { controller.detach() }
    }
    LaunchedEffect(command) {
        val next = command ?: return@LaunchedEffect
        if (!controller.consume(next)) return@LaunchedEffect
        try {
            val intent = when (next) {
                UpdateCommand.OpenPermission -> AppUpdateIntents.permission(context)
                is UpdateCommand.OpenInstaller -> AppUpdateIntents.installer(context, next.file)
            }
            val open = override
            when {
                open != null -> open(intent)
                next is UpdateCommand.OpenInstaller -> installer.launch(intent)
                else -> permission.launch(intent)
            }
        } catch (_: ActivityNotFoundException) {
            controller.onLaunchFailed()
        } catch (_: IllegalArgumentException) {
            controller.onLaunchFailed()
        }
    }

    Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }) {
        when (val shown = state) {
            AppUpdateState.Idle -> UpdateRow(stringResource(R.string.app_update_check), onClick = controller::check)
            AppUpdateState.UpToDate -> UpdateRow(stringResource(R.string.app_update_check), stringResource(R.string.app_update_up_to_date), onClick = controller::check)
            AppUpdateState.Checking -> UpdateRow(stringResource(R.string.app_update_checking), spinner = true)
            is AppUpdateState.Available -> UpdateRow(stringResource(R.string.app_update_available, shown.version),
                action = stringResource(R.string.app_update_action_update), onAction = controller::update)
            is AppUpdateState.Downloading -> {
                val fraction = (shown.bytes.toFloat() / shown.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                UpdateRow(stringResource(R.string.app_update_downloading, shown.version),
                    stringResource(R.string.app_update_percent, (fraction * 100).toInt()), progress = fraction,
                    action = stringResource(R.string.action_cancel), onAction = controller::cancel)
            }
            is AppUpdateState.Preparing -> UpdateRow(stringResource(R.string.app_update_preparing), spinner = true)
            is AppUpdateState.NeedsPermission -> UpdateRow(stringResource(R.string.app_update_permission), stringResource(R.string.app_update_permission_detail),
                action = stringResource(R.string.app_update_action_allow), onAction = controller::install)
            is AppUpdateState.Ready -> UpdateRow(stringResource(R.string.app_update_ready, shown.version),
                action = stringResource(R.string.app_update_action_install), onAction = controller::install)
            is AppUpdateState.Installing -> UpdateRow(stringResource(R.string.app_update_installing, shown.version), stringResource(R.string.app_update_installing_detail),
                action = stringResource(R.string.app_update_action_install), onAction = controller::install)
            is AppUpdateState.Failed -> UpdateRow(stringResource(problemText(shown.problem)),
                action = stringResource(R.string.action_retry), onAction = controller::retry)
        }
    }
}

private fun problemText(problem: UpdateProblem) = when (problem) {
    UpdateProblem.NETWORK -> R.string.app_update_error_network
    UpdateProblem.RATE_LIMITED -> R.string.app_update_error_busy
    UpdateProblem.NOT_FOUND -> R.string.app_update_error_not_found
    UpdateProblem.NOT_VERIFIED -> R.string.app_update_error_not_verified
    UpdateProblem.STORAGE -> R.string.app_update_error_storage
    UpdateProblem.NO_INSTALLER -> R.string.app_update_error_installer
}

@Composable
private fun UpdateRow(
    title: String, detail: String? = null, spinner: Boolean = false, progress: Float? = null,
    action: String? = null, onAction: () -> Unit = {}, onClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val base = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("app_update_row")
    Column(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge, color = p.ink)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.muted) }
            }
            if (spinner) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), color = p.muted, strokeWidth = 2.dp)
        }
        if (progress != null) {
            val label = stringResource(R.string.app_update_progress)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("app_update_progress").semantics { contentDescription = label },
                color = p.accent, trackColor = p.raised,
            )
        }
        if (action != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag("app_update_action"),
                    colors = ButtonDefaults.textButtonColors(contentColor = p.ink)) { Text(action) }
            }
        }
    }
}
