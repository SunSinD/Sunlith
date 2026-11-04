package io.github.sunsind.sunlith.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columns = if (maxWidth / 2 < 150.dp || LocalDensity.current.fontScale > 1.4f) 1 else 2
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.ai_setup_support), color = p.muted, style = MaterialTheme.typography.bodyLarge)
                    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        assistants.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (name, _) ->
                                    AssistantChoice(name, selected == name, Modifier.weight(1f)) { selected = name }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    TextButton(onClick = { context.openUrl("$AI_GUIDE_URL#on-your-phone-without-a-computer", vm) }) {
                        Text(stringResource(R.string.handoff_phone_guide), color = p.muted)
                    }
                }
            }
            Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Button(
                    onClick = { context.openUrl("$AI_GUIDE_URL#${assistants.first { it.first == selected }.second}", vm) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
                ) { Text(stringResource(R.string.action_open_setup, selected), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_continue_calendar), color = p.muted, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A plain choice: the outline only marks which guide will open, it never implies a connection. */
@Composable
private fun AssistantChoice(name: String, isSelected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = 52.dp).clip(shape)
            .background(if (isSelected) p.raised else p.background)
            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) p.ink else p.line, shape)
            .selectable(isSelected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            name, color = p.ink, style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
