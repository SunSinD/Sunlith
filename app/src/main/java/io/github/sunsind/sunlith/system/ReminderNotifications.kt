package io.github.sunsind.sunlith.system

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.MainActivity
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object ReminderNotifications {
    const val CHANNEL = "event_reminders"
    fun allowed(context: Context): Boolean = SettingsStore(context).remindersEnabled &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun post(context: Context, event: CalendarEvent, minutes: Int, repeat: Boolean = false): Boolean {
        if (!allowed(context)) return false
        val key = "${event.id}:${event.startMillis}:$minutes"
        if (!repeat && wasShown(context, key)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_HIGH).apply { description = context.getString(R.string.notification_channel_description) })
        val id = key.hashCode()
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse("sunlith://event/${event.id}/${event.startMillis}/$minutes"))
            .putExtra("event_id", event.id).putExtra("event_begin", event.startMillis), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(context, id, eventIntent(context, "SNOOZE", event, minutes), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, id, eventIntent(context, "DISMISS", event, minutes), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
        val time = if (event.allDay) context.getString(R.string.all_day) else context.getString(R.string.notification_time_range, format.format(Instant.ofEpochMilli(event.startMillis)), format.format(Instant.ofEpochMilli(event.endMillis)))
        val text = if (event.location.isBlank()) time else context.getString(R.string.notification_location_separator, time, event.location)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(event.title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EVENT).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(false)
            .addAction(0, context.getString(R.string.notification_snooze), snooze).addAction(0, context.getString(R.string.notification_dismiss), dismiss).build()
        try { manager.notify(key, 0, notification) } catch (_: SecurityException) { return false }
        if (!repeat) recordShown(context, key)
        return true
    }

    internal fun wasShown(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences("notification_history", Context.MODE_PRIVATE)
        val ordered = prefs.getString("shown_ordered", null)?.lineSequence()?.filter(String::isNotBlank)?.toList()
        return if (ordered != null) key in ordered else key in prefs.getStringSet("shown", emptySet()).orEmpty()
    }

    private fun recordShown(context: Context, key: String) {
        val prefs = context.getSharedPreferences("notification_history", Context.MODE_PRIVATE)
        val old = prefs.getString("shown_ordered", null)?.lineSequence()?.filter(String::isNotBlank)?.toList()
            ?: prefs.getStringSet("shown", emptySet()).orEmpty().toList()
        val updated = (old.filter { it != key } + key).takeLast(500)
        prefs.edit().putString("shown_ordered", updated.joinToString("\n")).remove("shown").apply()
    }

    internal fun eventIntent(context: Context, action: String, event: CalendarEvent, minutes: Int): Intent =
        Intent(context, NotificationActionReceiver::class.java).setAction("${context.packageName}.$action")
            .setData(Uri.parse("sunlith://reminder/${event.id}/${event.startMillis}/$minutes/$action"))
            .putExtra("event_id", event.id).putExtra("event_begin", event.startMillis).putExtra("minutes", minutes)

    internal fun postProviderAlerts(context: Context) {
        if (!allowed(context)) return
        val now = System.currentTimeMillis()
        val settings = SettingsStore(context)
        val repo = CalendarRepository(context)
        val ids = if (settings.hasChosenCalendars) settings.selectedCalendarIds else repo.calendars().filter { it.visible }.map { it.id }.toSet()
        context.contentResolver.query(CalendarContract.CalendarAlerts.CONTENT_URI,
            arrayOf(CalendarContract.CalendarAlerts.EVENT_ID, CalendarContract.CalendarAlerts.BEGIN, CalendarContract.CalendarAlerts.MINUTES),
            "${CalendarContract.CalendarAlerts.ALARM_TIME}<=? AND ${CalendarContract.CalendarAlerts.ALARM_TIME}>=? AND ${CalendarContract.CalendarAlerts.STATE}!=?",
            arrayOf(now.toString(), (now - 300_000L).toString(), CalendarContract.CalendarAlerts.STATE_DISMISSED.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val begin = cursor.getLong(1)
                val minutes = cursor.getInt(2)
                val event = repo.events(begin - 86_400_000L, begin + 86_400_000L, ids).firstOrNull { it.id == id && it.startMillis == begin } ?: continue
                post(context, event, minutes)
            }
        }
    }
}

object ReminderScheduler {
    private const val REQUEST_CODE = 4032
    fun reschedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(nextIntent(context))
        if (!ReminderNotifications.allowed(context) || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            SnoozedReminders.clear(context)
            return
        }
        SnoozedReminders.restore(context)
        val repo = CalendarRepository(context)
        val settings = SettingsStore(context)
        val ids = if (settings.hasChosenCalendars) settings.selectedCalendarIds else repo.calendars().filter { it.visible }.map { it.id }.toSet()
        if (ids.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val events = repo.events(LocalDate.now().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), LocalDate.now().plusDays(366).atStartOfDay(zone).toInstant().toEpochMilli(), ids)
        val eventIds = events.map { it.id }.distinct()
        val reminders = mutableMapOf<Long, MutableList<Int>>()
        eventIds.chunked(200).forEach { group ->
            context.contentResolver.query(CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID} IN (${group.joinToString(",") { "?" }}) AND ${CalendarContract.Reminders.MINUTES}>=0 AND (${CalendarContract.Reminders.METHOD}=? OR ${CalendarContract.Reminders.METHOD}=?)",
                (group.map(Long::toString) + listOf(CalendarContract.Reminders.METHOD_ALERT.toString(), CalendarContract.Reminders.METHOD_DEFAULT.toString())).toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) reminders.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(cursor.getInt(1))
            }
        }
        var next: Triple<CalendarEvent, Int, Long>? = null
        events.forEach { event -> reminders[event.id].orEmpty().forEach { minutes ->
            val at = effectiveStart(event) - minutes.toLong() * 60_000L
            val key = "${event.id}:${event.startMillis}:$minutes"
            if (at in (now - 120_000L)..now && !ReminderNotifications.wasShown(context, key)) ReminderNotifications.post(context, event, minutes)
            if (at > now && !ReminderNotifications.wasShown(context, key) && (next == null || at < next!!.third)) next = Triple(event, minutes, at)
        } }
        next?.let { (event, minutes, at) ->
            val intent = nextIntent(context, ReminderNotifications.eventIntent(context, "REMIND", event, minutes))
            scheduleAlarm(alarm, at, intent)
        }
    }

    internal fun scheduleAlarm(manager: AlarmManager, at: Long, intent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
    }

    internal fun resolve(context: Context, intent: Intent): CalendarEvent? {
        val id = intent.getLongExtra("event_id", -1)
        val begin = intent.getLongExtra("event_begin", -1)
        if (id <= 0 || begin <= 0) return null
        val repo = CalendarRepository(context)
        val draft = repo.getEvent(id) ?: return null
        val settings = SettingsStore(context)
        if (settings.hasChosenCalendars && draft.calendarId !in settings.selectedCalendarIds) return null
        return repo.events(begin - 86_400_000L, begin + 86_400_000L, setOf(draft.calendarId)).firstOrNull { it.id == id && it.startMillis == begin }
    }

    internal fun effectiveStart(event: CalendarEvent): Long = if (event.allDay)
        Instant.ofEpochMilli(event.startMillis).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    else event.startMillis

    private fun nextIntent(context: Context, data: Intent? = null): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction("${context.packageName}.NEXT_REMINDER")
        data?.extras?.let(intent::putExtras)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

internal object SnoozedReminders {
    private fun preferences(context: Context) = context.getSharedPreferences("snoozed_reminders", Context.MODE_PRIVATE)
    private fun key(intent: Intent) = "${intent.getLongExtra("event_id", -1)}:${intent.getLongExtra("event_begin", -1)}:${intent.getIntExtra("minutes", 0)}"
    private fun pending(context: Context, intent: Intent): PendingIntent = PendingIntent.getBroadcast(context, key(intent).hashCode(),
        Intent(intent).setAction("${context.packageName}.SNOOZED").setData(Uri.parse("sunlith://snooze/${key(intent)}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, event: CalendarEvent, minutes: Int, at: Long) {
        val intent = ReminderNotifications.eventIntent(context, "SNOOZED", event, minutes)
        preferences(context).edit().putLong(key(intent), at).apply()
        ReminderScheduler.scheduleAlarm(context.getSystemService(AlarmManager::class.java), at, pending(context, intent))
    }

    fun fire(context: Context, intent: Intent) {
        val prefs = preferences(context)
        if (!prefs.contains(key(intent))) return
        prefs.edit().remove(key(intent)).apply()
        ReminderScheduler.resolve(context, intent)?.let { ReminderNotifications.post(context, it, intent.getIntExtra("minutes", 0), repeat = true) }
    }

    fun cancel(context: Context, intent: Intent) {
        preferences(context).edit().remove(key(intent)).apply()
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, intent))
    }

    fun restore(context: Context) {
        val prefs = preferences(context)
        val now = System.currentTimeMillis()
        prefs.all.forEach { (key, value) ->
            val parts = key.split(':')
            val at = value as? Long
            if (parts.size != 3 || at == null) { prefs.edit().remove(key).apply(); return@forEach }
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .putExtra("event_id", parts[0].toLongOrNull() ?: -1).putExtra("event_begin", parts[1].toLongOrNull() ?: -1)
                .putExtra("minutes", parts[2].toIntOrNull() ?: 0)
            if (at < now - 86_400_000L || runCatching { ReminderScheduler.resolve(context, intent) }.getOrNull() == null) cancel(context, intent)
            else if (at <= now) fire(context, intent)
            else ReminderScheduler.scheduleAlarm(context.getSystemService(AlarmManager::class.java), at, pending(context, intent))
        }
    }

    fun clear(context: Context) {
        preferences(context).all.keys.forEach { key ->
            val parts = key.split(':')
            if (parts.size == 3) cancel(context, Intent(context, NotificationActionReceiver::class.java)
                .putExtra("event_id", parts[0].toLongOrNull() ?: -1).putExtra("event_begin", parts[1].toLongOrNull() ?: -1)
                .putExtra("minutes", parts[2].toIntOrNull() ?: 0))
        }
        preferences(context).edit().clear().apply()
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        AppRefresh.executor.execute {
            try {
                val action = intent.action?.substringAfterLast('.')
                if (action == "DISMISS") {
                    val tag = "${intent.getLongExtra("event_id", -1)}:${intent.getLongExtra("event_begin", -1)}:${intent.getIntExtra("minutes", 0)}"
                    context.getSystemService(NotificationManager::class.java).cancel(tag, 0)
                    SnoozedReminders.cancel(context, intent)
                    return@execute
                }
                if (action == "SNOOZED") {
                    SnoozedReminders.fire(context, intent)
                    return@execute
                }
                val event = runCatching { ReminderScheduler.resolve(context, intent) }.getOrNull()
                if (event != null) {
                    val minutes = intent.getIntExtra("minutes", 0)
                    val tag = "${event.id}:${event.startMillis}:$minutes"
                    when (action) {
                        "SNOOZE" -> {
                            context.getSystemService(NotificationManager::class.java).cancel(tag, 0)
                            SnoozedReminders.schedule(context, event, minutes, System.currentTimeMillis() + 600_000L)
                        }
                        "NEXT_REMINDER" -> ReminderNotifications.post(context, event, minutes)
                    }
                }
                runCatching { ReminderScheduler.reschedule(context) }
            } catch (_: SecurityException) {
                context.getSystemService(NotificationManager::class.java).cancelAll()
            } finally { pending.finish() }
        }
    }
}

class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderNotifications.allowed(context)) return
        val pending = goAsync()
        AppRefresh.executor.execute {
            try {
                runCatching { ReminderNotifications.postProviderAlerts(context) }
                runCatching { ReminderScheduler.reschedule(context) }
            } finally { pending.finish() }
        }
    }
}
