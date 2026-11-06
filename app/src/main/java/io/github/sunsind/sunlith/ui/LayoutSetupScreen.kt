package io.github.sunsind.sunlith.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R

@Composable
fun LayoutSetupScreen(vm: SunlithViewModel) {
    val p = LocalPalette.current
    var selected by rememberSaveable { mutableStateOf("sunlith") }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.layout_setup_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.layout_setup_lead), style = MaterialTheme.typography.bodyLarge, color = p.muted)
            Spacer(Modifier.height(20.dp))
            LayoutPresetChoices(selected) { selected = it }
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.applyLayoutPreset(selected); vm.finishLayoutSetup() },
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text(stringResource(R.string.layout_continue)) }
    }
}

@Composable
fun LayoutPresetChoices(selected: String, onSelect: (String) -> Unit) {
    val p = LocalPalette.current
    val presets = listOf(
        Triple("sunlith", R.string.layout_preset_sunlith, R.string.layout_preset_sunlith_desc),
        Triple("schedule", R.string.layout_preset_schedule, R.string.layout_preset_schedule_desc),
        Triple("planner", R.string.layout_preset_planner, R.string.layout_preset_planner_desc),
    )
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        presets.forEach { (value, title, description) ->
            val chosen = selected == value
            val shape = RoundedCornerShape(14.dp)
            Surface(
                color = p.surface,
                shape = shape,
                modifier = Modifier.fillMaxWidth().border(if (chosen) 2.dp else 1.dp, if (chosen) p.accent else p.line, shape)
                    .selectable(chosen, role = Role.RadioButton) { onSelect(value) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LayoutPreview(value, Modifier.size(width = 92.dp, height = 88.dp).clearAndSetSemantics { })
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = p.ink)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                    }
                    RadioButton(chosen, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.faint))
                }
            }
        }
    }
}

@Composable
private fun LayoutPreview(preset: String, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        fun block(x: Float, y: Float, width: Float, height: Float, accent: Boolean = false) {
            drawRoundRect(
                color = if (accent) p.accent.copy(alpha = 0.75f) else p.muted.copy(alpha = 0.20f),
                topLeft = Offset(size.width * x, size.height * y),
                size = Size(size.width * width, size.height * height),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        drawRoundRect(p.background, size = size, cornerRadius = CornerRadius(6.dp.toPx()))
        block(0.08f, 0.08f, 0.36f, 0.04f, true)
        when (preset) {
            "planner" -> {
                repeat(4) { row ->
                    drawLine(p.line, Offset(size.width * 0.08f, size.height * (0.23f + row * 0.19f)), Offset(size.width * 0.92f, size.height * (0.23f + row * 0.19f)), 1.dp.toPx())
                }
                repeat(3) { col ->
                    drawLine(p.line, Offset(size.width * (0.08f + col * 0.28f), size.height * 0.20f), Offset(size.width * (0.08f + col * 0.28f), size.height * 0.92f), 1.dp.toPx())
                }
                block(0.10f, 0.27f, 0.23f, 0.21f, true)
                block(0.38f, 0.51f, 0.23f, 0.28f)
                block(0.66f, 0.30f, 0.23f, 0.15f, true)
            }
            else -> {
                repeat(3) { row -> repeat(7) { col ->
                    block(0.09f + col * 0.12f, 0.21f + row * 0.095f, 0.055f, 0.035f, row == 1 && col == 2)
                } }
                if (preset == "schedule") {
                    repeat(3) { row ->
                        block(0.09f, 0.56f + row * 0.12f, 0.10f, 0.025f)
                        block(0.25f, 0.54f + row * 0.12f, 0.025f, 0.08f, true)
                        block(0.33f, 0.56f + row * 0.12f, 0.51f, 0.035f)
                    }
                } else {
                    repeat(2) { row ->
                        block(0.09f, 0.55f + row * 0.19f, 0.82f, 0.13f)
                        block(0.13f, 0.59f + row * 0.19f, 0.025f, 0.05f, true)
                    }
                }
            }
        }
    }
}
