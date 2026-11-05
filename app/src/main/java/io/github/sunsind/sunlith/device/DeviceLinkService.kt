package io.github.sunsind.sunlith.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.sunsind.sunlith.MainActivity
import io.github.sunsind.sunlith.R
import java.util.concurrent.Executors

/** Foreground service that keeps the HTTPS phone link open while the user wants it. Never restarted by the system or at boot. */
class DeviceLinkService : Service() {
    private var server: DeviceLinkServer? = null
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lifecycle.execute {
                server?.stop()
                server = null
                DeviceLink.update(DeviceLinkState(false, null))
                Handler(Looper.getMainLooper()).post {
                    // A later Start command keeps this instance alive and reopens the server in queue order.
                    if (stopSelfResult(startId)) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            }
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (_: Exception) {
            DeviceLink.update(DeviceLinkState(false, DeviceLink.START_FAILED))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        DeviceLink.missingPermission(this)?.let {
            DeviceLink.update(DeviceLinkState(false, it))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        lifecycle.execute {
            if (destroyed) return@execute
            if (server != null) {
                DeviceLink.update(DeviceLinkState(true, null))
                return@execute
            }
            val created = DeviceLinkServer(this)
            try {
                created.start()
                server = created
                DeviceLink.update(DeviceLinkState(true, null))
            } catch (_: Exception) {
                created.stop()
                DeviceLink.update(DeviceLinkState(false, DeviceLink.BIND_FAILED))
                Handler(Looper.getMainLooper()).post { stopSelfResult(startId) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        lifecycle.execute {
            server?.stop()
            server = null
            DeviceLink.update(DeviceLink.state.value.copy(running = false))
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.device_link_channel_title), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, DeviceLinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.device_link_notification_title))
            .setContentText(getString(R.string.device_link_notification_text))
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.device_link_stop), stop)
            .build()
    }

    companion object {
        // One queue orders closing the old service before binding a replacement.
        private val lifecycle = Executors.newSingleThreadExecutor { Thread(it, "sunlith-link-lifecycle").apply { isDaemon = true } }
        private const val CHANNEL = "device_link"
        private const val NOTIFICATION_ID = 4041
        internal const val ACTION_STOP = "io.github.sunsind.sunlith.device.STOP"
    }
}
