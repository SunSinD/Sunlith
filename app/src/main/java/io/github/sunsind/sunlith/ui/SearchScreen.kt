package io.github.sunsind.sunlith.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SearchScreen(vm: SunlithViewModel) {
    val p = LocalPalette.current
    val f = rememberFormats()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler { vm.back() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val groups = remember(vm.searchResults) {
        val upcoming = vm.searchResults.filter { !it.span(zone).last.isBefore(today) }.sortedBy { it.startMillis }
        val past = vm.searchResults.filter { it.span(zone).last.isBefore(today) }.sortedByDescending { it.startMillis }
        upcoming to past
    }

    Scaffold(
        containerColor = p.background,
        topBar = {
            Column(Modifier.background(p.background).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), tint = p.ink) }
                    TextField(
                        value = vm.searchQuery, onValueChange = vm::updateSearch,
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        placeholder = { Text(stringResource(R.string.search_hint), color = p.faint) },
                        singleLine = true, colors = plainFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    )
                    if (vm.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.updateSearch("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_clear), tint = p.muted) }
                    }
                }
                if (vm.searchLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.muted, trackColor = p.line)
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).imePadding(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                vm.searchQuery.isBlank() -> item { Hint(stringResource(R.string.search_scope)) }
                !vm.searchLoading && vm.searchResults.isEmpty() -> item { Hint(stringResource(R.string.search_none)) }
            }
            results(groups.first, null, f, vm)
            results(groups.second, R.string.search_past, f, vm)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(events: List<CalendarEvent>, header: Int?, f: Formats, vm: SunlithViewModel) {
    if (events.isEmpty()) return
    if (header != null) item(key = "h-$header") { SectionTitle(stringResource(header), Modifier.padding(start = 0.dp)) }
    items(events, key = { "${header ?: 0}-" + it.key() }) { e ->
        val day = e.span(ZoneId.systemDefault()).first
        Column {
            Text(
                f.dayShortWithYear(day), color = LocalPalette.current.muted, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
            )
            EventCard(e, day, f, onClick = { vm.open(Screen.Details(e)) })
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(16.dp), color = LocalPalette.current.muted, style = MaterialTheme.typography.bodyMedium)
}
