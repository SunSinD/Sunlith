package io.github.sunsind.sunlith.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.TodoItem
import java.time.ZoneId
import kotlinx.coroutines.delay

@Composable
fun TodoScreen(vm: SunlithViewModel) {
    val p = LocalPalette.current
    val f = rememberFormats()
    val zone = ZoneId.systemDefault()
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), vm.todoItems) {
        while (true) {
            value = System.currentTimeMillis()
            val next = vm.todoItems.asSequence().map { it.availableAt }.filter { it > value }.minOrNull()
            delay(next?.let { (it - value).coerceIn(100L, 30_000L) } ?: 30_000L)
        }
    }
    val available = vm.todoItems.filter { it.availableAt <= now }
    val pending = available.filter { it.key !in vm.todoCompleted }.sortedBy { it.availableAt }
    val completed = available.filter { it.key in vm.todoCompleted }.sortedByDescending { it.availableAt }
    BackHandler { vm.back() }

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = p.background,
        topBar = {
            ScreenTopBar(stringResource(R.string.todo_title), onNavigate = vm::back) {
                IconButton(onClick = vm::reloadTodos) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.todo_refresh), tint = p.ink)
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (vm.todoLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.muted, trackColor = p.line)
            LazyColumn(
                Modifier.fillMaxSize().testTag("todo_list"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.todoError?.let { error ->
                    item(key = "error") {
                        Column(Modifier.padding(8.dp)) {
                            Text(error, color = p.danger, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = vm::reloadTodos) { Text(stringResource(R.string.todo_retry)) }
                        }
                    }
                }
                if (pending.isEmpty() && vm.todoError == null && !vm.todoLoading) {
                    item(key = "empty") {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.todo_empty), color = p.ink, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.todo_empty_hint), color = p.muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(pending, key = { it.key }) { item ->
                    TodoRow(item, false, f, zone, onComplete = { vm.completeTodo(item, true) }, onOpen = { vm.open(Screen.Details(item.event)) })
                }
                if (completed.isNotEmpty()) {
                    item(key = "completed") {
                        TextButton(onClick = { showCompleted = !showCompleted }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.todo_completed, completed.size), color = p.muted)
                        }
                    }
                    if (showCompleted) items(completed, key = { it.key }) { item ->
                        TodoRow(item, true, f, zone, onComplete = { vm.completeTodo(item, false) }, onOpen = { vm.open(Screen.Details(item.event)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, done: Boolean, f: Formats, zone: ZoneId, onComplete: () -> Unit, onOpen: () -> Unit) {
    val event = item.event
    val colors = eventColors(event)
    val title = event.displayTitle().ifBlank { stringResource(R.string.untitled) }
    val checkLabel = stringResource(if (done) R.string.todo_undo else R.string.todo_complete, title)
    val day = event.span(zone).first
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.container).testTag("todo_${item.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = done, onCheckedChange = { onComplete() },
            modifier = Modifier.padding(start = 2.dp).semantics { contentDescription = checkLabel },
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = colors.content, uncheckedColor = colors.content, checkmarkColor = colors.container,
            ))
        Column(
            Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, color = colors.content, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(f.dayShortWithYear(day), color = colors.content, style = MaterialTheme.typography.bodyMedium)
            Text(context.timeLabel(event, day, zone, f), color = colors.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
