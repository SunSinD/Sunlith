package io.github.sunsind.sunlith.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.data.CalendarRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DeviceLinkState(val running: Boolean = false, val error: String? = null)

enum class DeviceLinkAddressKind { TAILSCALE, VPN, LOCAL_NETWORK }
data class DeviceLinkAddress(val host: String, val kind: DeviceLinkAddressKind)

/**
 * [pairedAtMillis] is null when not paired or for pairings made before this was saved. [lastSuccessMillis] is the last time
 * the current pairing got a successful answer; it is not proof the computer is connected now. Both clear on unpair or re-pair.
 */
data class DeviceLinkDiagnostics(val pairedAtMillis: Long?, val lastSuccessMillis: Long?)

object DeviceLink {
    const val PORT = 39393
    private val mutableState = MutableStateFlow(DeviceLinkState())
    val state: StateFlow<DeviceLinkState> = mutableState.asStateFlow()
    private val mutableActivity = MutableStateFlow(0L)

    /** Increases whenever [history] or [diagnostics] may have changed. Collect it and read both again off the main thread. */
    val activity: StateFlow<Long> = mutableActivity.asStateFlow()

    /** Latest computer changes, newest first, at most 100. Reads a small file the first time, so call it off the main thread. */
    fun history(context: Context): List<DeviceLinkChange> = DeviceLinkHistory.entries(context.applicationContext)

    /** Deletes the local change list; call it off the main thread. Unpairing, stopping or restarting never does this. */
    fun clearHistory(context: Context) = DeviceLinkHistory.clear(context.applicationContext)

    /** Reads small files the first time, so call it off the main thread. */
    fun diagnostics(context: Context): DeviceLinkDiagnostics {
        val app = context.applicationContext
        return DeviceLinkDiagnostics(DeviceLinkStore.pairing(app)?.pairedAtMillis, DeviceLinkStore.lastSuccess(app))
    }

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

    fun addressChoices(context: Context): List<DeviceLinkAddress> = DeviceLinkNetwork.addressChoices(context.applicationContext)

    @Synchronized
    fun createPairing(context: Context, calendarIds: Set<Long>, host: String): String {
        val app = context.applicationContext
        check(Looper.myLooper() != Looper.getMainLooper()) { "Pairing must run off the main thread." }
        require(calendarIds.isNotEmpty()) { "Choose at least one calendar." }
        require(host == LOOPBACK || host in addresses(app)) { "Choose a current private address of this phone." }
        val existing = CalendarRepository(app).calendars().mapTo(mutableSetOf()) { it.id }
        require(calendarIds.all { it in existing }) { "One of the selected calendars is no longer available." }
        val fingerprint = DeviceLinkTls.fingerprint()
        clearPairingShares(app)
        val token = DeviceLinkStore.pair(app, calendarIds)
        activityChanged()
        return String(DeviceLinkJson.write(linkedMapOf(
            "version" to 1,
            "baseUrl" to "https://$host:$PORT",
            "certificateSha256" to fingerprint,
            "token" to token,
        )), Charsets.UTF_8)
    }

    /** Removes the pairing, pairing time and last success time. The change history stays until [clearHistory]. */
    @Synchronized
    fun revoke(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Revoking must run off the main thread." }
        DeviceLinkStore.clear(context.applicationContext)
        clearPairingShares(context)
        activityChanged()
        stop(context)
    }

    fun isPaired(context: Context): Boolean = DeviceLinkStore.pairing(context.applicationContext) != null

    fun pairedCalendarIds(context: Context): Set<Long> = DeviceLinkStore.pairing(context.applicationContext)?.calendarIds.orEmpty()

    @Synchronized
    fun cachedPairingShare(context: Context): File? = File(context.cacheDir, "pairing-share").listFiles()
        ?.firstOrNull { file ->
            if (!file.isFile || !file.name.endsWith(".json")) false
            else runCatching {
                val token = org.json.JSONObject(file.readText()).getString("token")
                DeviceLinkStore.authorize(context, token) != null
            }.getOrDefault(false)
        }

    private fun clearPairingShares(context: Context) {
        File(context.cacheDir, "pairing-share").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    internal fun update(value: DeviceLinkState) { mutableState.value = value }

    internal fun activityChanged() = mutableActivity.update { it + 1 }

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
