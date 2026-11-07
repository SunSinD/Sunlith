package io.github.sunsind.sunlith

import android.Manifest
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.system.CalendarHandoff
import io.github.sunsind.sunlith.system.LauncherIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.sunsind.sunlith.system.readCalendarHandoff
import io.github.sunsind.sunlith.system.restoreCalendarHandoff
import io.github.sunsind.sunlith.system.save
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithApp
import io.github.sunsind.sunlith.ui.SunlithTheme
import io.github.sunsind.sunlith.ui.SunlithViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class MainActivity : ComponentActivity() {
    private val viewModel: SunlithViewModel by viewModels()
    private var observer: ContentObserver? = null
    private var pendingHandoff by mutableStateOf<CalendarHandoff?>(null)
    private var activeHandoff: CalendarHandoff? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) { LauncherIcons.reconcile(applicationContext) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                LauncherIcons.reopenRequests(applicationContext).collect { pending ->
                    if (pending && LauncherIcons.consumeReopen(applicationContext)) {
                        startActivity(Intent.makeRestartActivityTask(ComponentName(this@MainActivity, MainActivity::class.java)))
                        finish()
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
        else {
            val restored = restoreCalendarHandoff(savedInstanceState.getBundle(STATE_HANDOFF))
            if (savedInstanceState.getBoolean(STATE_DELIVERED) && (viewModel.editor != null || viewModel.importUi != null)) activeHandoff = restored
            else pendingHandoff = restored
        }
        setContent {
            val ready = viewModel.aiIntroDone && viewModel.layoutSetupDone && viewModel.hasPermission && !viewModel.needsNotificationStep && viewModel.loaded
            LaunchedEffect(ready) { if (ready) viewModel.deliverPendingLink() }
            val editorOpen = Screen.Editor in viewModel.stack
            LaunchedEffect(pendingHandoff, ready, editorOpen, viewModel.importUi != null) {
                val item = pendingHandoff
                if (item != null && ready && !editorOpen && viewModel.importUi == null) {
                    pendingHandoff = null
                    when (item) {
                        is CalendarHandoff.CalendarFile -> {
                            viewModel.open(Screen.Settings)
                            viewModel.startImport(item.uri)
                            activeHandoff = item
                        }
                        is CalendarHandoff.Event -> {
                            val zone = ZoneId.systemDefault()
                            runCatching {
                                item.fields.validate(zone)
                                val day = Instant.ofEpochMilli(item.fields.begin).atZone(if (item.fields.allDay) ZoneOffset.UTC else zone).toLocalDate()
                                viewModel.select(day)
                                viewModel.newEvent(day)
                                viewModel.updateEditor { item.fields.editor(it, zone) }
                                activeHandoff = item
                            }.onFailure { viewModel.message(getString(R.string.handoff_invalid_event)) }
                        }
                        is CalendarHandoff.Rejected -> viewModel.message(getString(item.message))
                    }
                }
            }
            SunlithTheme(viewModel.themeMode, viewModel.materialYou) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { SunlithApp(viewModel) }
                    if (pendingHandoff != null) {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.handoff_waiting), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { pendingHandoff = null }) { Text(stringResource(R.string.action_cancel)) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val inReview = when (val item = activeHandoff) {
            is CalendarHandoff.CalendarFile -> item.takeIf { viewModel.importUi?.uri == item.uri && viewModel.importUi?.report == null }
            is CalendarHandoff.Event -> item.takeIf {
                val draft = viewModel.editor?.originalDraft
                Screen.Editor in viewModel.stack && draft != null && draft.id == null &&
                    draft.startMillis == item.fields.begin && draft.endMillis == item.fields.end &&
                    draft.allDay == item.fields.allDay && draft.title == item.fields.title &&
                    draft.description == item.fields.notes && draft.location == item.fields.location
            }
            else -> null
        }
        (pendingHandoff ?: inReview)?.let {
            outState.putBundle(STATE_HANDOFF, it.save())
            outState.putBoolean(STATE_DELIVERED, pendingHandoff == null)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        registerObserver()
    }

    override fun onResume() {
        super.onResume()
        registerObserver()
        viewModel.onResume()
    }

    override fun onStop() {
        observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        observer = null
        super.onStop()
    }

    private fun registerObserver() {
        if (observer != null) return
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        val next = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = viewModel.onProviderChanged()
        }
        runCatching {
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, next)
            observer = next
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val resolvedType = if (intent.type == null && intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "content") {
            runCatching { intent.resolveType(contentResolver) }.getOrNull()
        } else intent.type
        val handoff = readCalendarHandoff(intent, resolvedType)
        if (handoff != null) {
            when {
                handoff is CalendarHandoff.Rejected -> viewModel.message(getString(handoff.message))
                pendingHandoff != null -> viewModel.message(getString(R.string.handoff_already_waiting))
                else -> pendingHandoff = handoff
            }
            return
        }
        if (intent.action != null && intent.action != Intent.ACTION_MAIN) return
        runCatching {
            val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
            val begin = intent.getLongExtra(EXTRA_EVENT_BEGIN, -1L)
            val create = intent.getBooleanExtra(EXTRA_CREATE_EVENT, false)
            val createDate = intent.getStringExtra(EXTRA_CREATE_DATE)?.let(LocalDate::parse)
            require(createDate == null || createDate.year in 1900..2100)
            if (eventId > 0 || create) viewModel.handleLink(eventId, begin, create, createDate)
            intent.removeExtra(EXTRA_EVENT_ID)
            intent.removeExtra(EXTRA_EVENT_BEGIN)
            intent.removeExtra(EXTRA_CREATE_EVENT)
            intent.removeExtra(EXTRA_CREATE_DATE)
        }.onFailure { viewModel.message(getString(R.string.handoff_invalid_event)) }
    }

    companion object {
        private const val STATE_HANDOFF = "calendar_handoff"
        private const val STATE_DELIVERED = "calendar_handoff_delivered"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_BEGIN = "event_begin"
        const val EXTRA_CREATE_EVENT = "create_event"
        const val EXTRA_CREATE_DATE = "create_date"
    }
}
