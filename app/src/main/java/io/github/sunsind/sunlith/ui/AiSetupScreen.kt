package io.github.sunsind.sunlith.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R

private val assistants = listOf(
    "Codex" to "codex", "Claude" to "claude-code", "Cursor" to "gemini-cli-and-cursor",
    "Grok" to "grok", "OpenCode" to "opencode", "Antigravity" to "antigravity",
    "Muse Code" to "muse-code", "Pi" to "pi",
)

@Composable
fun AiSetupScreen(vm: SunlithViewModel, onBack: () -> Unit, onContinue: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf("Codex") }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = p.background, topBar = {
        ScreenTopBar(stringResource(R.string.ai_setup_title), onNavigate = onBack)
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ai_setup_lead), color = p.muted, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.ai_setup_assistant), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Column(Modifier.selectableGroup()) {
                assistants.forEach { (name, _) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .selectable(selected == name, role = Role.RadioButton) { selected = name },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected == name, onClick = null, colors = RadioButtonDefaults.colors(
                            selectedColor = p.accent, unselectedColor = p.muted,
                        ))
                    }
                }
            }
            Text(stringResource(R.string.ai_setup_steps), color = p.ink, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ai_setup_support), color = p.muted, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    val anchor = assistants.first { it.first == selected }.second
                    context.openUrl("$AI_GUIDE_URL#$anchor", vm)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
            ) { Text(stringResource(R.string.action_open_setup, selected)) }
            OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_continue_calendar))
            }
            Text(stringResource(R.string.ai_setup_resume), color = p.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
    }
}
