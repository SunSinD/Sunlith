package io.github.sunsind.sunlith.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.data.CalendarRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceLinkState(val running: Boolean = false, val error: String? = null)

object DeviceLink {
    const val PORT = 39393
    private val mutableState = MutableStateFlow(DeviceLinkState())
    val state: StateFlow<DeviceLinkState> = mutableState.asStateFlow()

    fun start(context: Context) {
        val app = context.applicationContext
        missingPermission(app)?.let { update(DeviceLinkState(false, it)); return }
        try {
            ContextCompat.startForegroundService(app, Intent(app, DeviceLinkService::class.java))
        } catch (_: Exception) {
            update(DeviceLinkState(false, START_FAILED))
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        try {
            app.startService(Intent(app, DeviceLinkService::class.java).setAction(DeviceLinkService.ACTION_STOP))
        } catch (_: IllegalStateException) {
            app.stopService(Intent(app, DeviceLinkService::class.java))
        }
        update(DeviceLinkState(false, null))
    }

    fun addresses(context: Context): List<String> = DeviceLinkNetwork.addresses(context.applicationContext)

    fun createPairing(context: Context, calendarIds: Set<Long>, host: String): String {
        val app = context.applicationContext
        check(Looper.myLooper() != Looper.getMainLooper()) { "Pairing must run off the main thread." }
        require(calendarIds.isNotEmpty()) { "Choose at least one calendar." }
        require(host == LOOPBACK || host in addresses(app)) { "Choose a current private address of this phone." }
        val existing = CalendarRepository(app).calendars().mapTo(mutableSetOf()) { it.id }
        require(calendarIds.all { it in existing }) { "One of the selected calendars is no longer available." }
        val fingerprint = DeviceLinkTls.fingerprint()
        val token = DeviceLinkStore.pair(app, calendarIds)
        return String(DeviceLinkJson.write(linkedMapOf(
            "version" to 1,
            "baseUrl" to "https://$host:$PORT",
            "certificateSha256" to fingerprint,
            "token" to token,
        )), Charsets.UTF_8)
    }

    fun revoke(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Revoking must run off the main thread." }
        DeviceLinkStore.clear(context.applicationContext)
        stop(context)
    }

    fun isPaired(context: Context): Boolean = DeviceLinkStore.pairing(context.applicationContext) != null

    fun pairedCalendarIds(context: Context): Set<Long> = DeviceLinkStore.pairing(context.applicationContext)?.calendarIds.orEmpty()

    internal fun update(value: DeviceLinkState) { mutableState.value = value }

    internal fun missingPermission(context: Context): String? {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted(Manifest.permission.READ_CALENDAR) || !granted(Manifest.permission.WRITE_CALENDAR)) return CALENDAR_MISSING
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) return NOTIFICATIONS_MISSING
        return null
    }

    internal const val LOOPBACK = "127.0.0.1"
    internal const val CALENDAR_MISSING = "Allow calendar access before starting the phone link."
    internal const val NOTIFICATIONS_MISSING = "Allow notifications before starting the phone link."
    internal const val START_FAILED = "The phone link could not start. Open Sunlith and try again."
    internal const val BIND_FAILED = "The phone link could not open port 39393. Another app may be using it."
}
