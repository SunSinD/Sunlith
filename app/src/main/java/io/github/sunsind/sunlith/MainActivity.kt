package io.github.sunsind.sunlith

import android.Manifest
import android.content.Intent
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
import io.github.sunsind.sunlith.ui.SunlithApp
import io.github.sunsind.sunlith.ui.SunlithViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: SunlithViewModel by viewModels()
    private var observer: ContentObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { SunlithApp(viewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
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
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val begin = intent.getLongExtra(EXTRA_EVENT_BEGIN, -1L)
        val create = intent.getBooleanExtra(EXTRA_CREATE_EVENT, false)
        if (eventId > 0 || create) viewModel.handleLink(eventId, begin, create)
        intent.removeExtra(EXTRA_EVENT_ID)
        intent.removeExtra(EXTRA_EVENT_BEGIN)
        intent.removeExtra(EXTRA_CREATE_EVENT)
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_BEGIN = "event_begin"
        const val EXTRA_CREATE_EVENT = "create_event"
    }
}
