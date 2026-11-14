package io.github.sunsind.sunlith.school

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.sunsind.sunlith.MainActivity
import io.github.sunsind.sunlith.R

internal class SchoolNotifications(private val context: Context) {
    fun changes(record: SchoolRecord, changes: List<SchoolDelta>) {
        val enabled = enabledChanges(record, changes)
        if (enabled.isEmpty() || !allowed()) return
        val manager = channels()
        val categories = enabled.flatMap { it.categories intersect record.notifications.categories }.toSet()
        val labels = categories.sortedBy { it.ordinal }.joinToString(", ") { context.getString(label(it)) }
        val first = enabled.minBy { it.item.date }.item
        val detail = enabled.distinctBy { it.item.id }.take(6).joinToString("\n") { delta ->
            buildString {
                append("${delta.item.date}: ${delta.item.title}")
                if (SchoolAlert.TEACHER_ABSENCES in delta.categories && SchoolAlert.TEACHER_ABSENCES in record.notifications.categories) {
                    delta.item.notes.take(2).forEach { append("\n"); append(it.take(300)) }
                }
            }
        }
        val text = context.resources.getQuantityString(R.plurals.school_sync_changes_count, enabled.size, enabled.size)
        val notification = NotificationCompat.Builder(context, CHANGES)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.school_sync_notification_title))
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText("$labels\n$detail"))
            .setCategory(NotificationCompat.CATEGORY_EVENT).setAutoCancel(true)
            .setContentIntent(open("changes", first.date.toString())).build()
        try { manager.notify("school-changes", 0, notification) } catch (_: SecurityException) { }
    }
    fun messages(record: SchoolRecord, messages: List<SchoolAdminNotice>) {
        if (!record.notifications.enabled || SchoolAlert.ADMINISTRATION_MESSAGES !in record.notifications.categories || !allowed()) return
        if (messages.isEmpty()) return
        val manager = channels()
        if (messages.size > 3) {
            val text = context.resources.getQuantityString(R.plurals.school_admin_new_messages, messages.size, messages.size)
            val notification = NotificationCompat.Builder(context, MESSAGES).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.school_messages_title)).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(messages.take(10).joinToString("\n") { it.subject }))
                .setContentIntent(open("messages", null)).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE).build()
            try { manager.notify("school-message:summary", 0, notification) } catch (_: SecurityException) { }
            return
        }
        messages.forEach { message ->
            val preview = (message.body ?: context.getString(R.string.school_admin_title_only)).take(4_000)
            val notification = NotificationCompat.Builder(context, MESSAGES).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(message.subject).setContentText(preview.take(500))
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setSubText(message.sender).setContentIntent(open("messages", null))
                .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE).build()
            try { manager.notify("school-message:${schoolHash(record.connection + "\n" + message.id)}", 0, notification) } catch (_: SecurityException) { }
        }
    }
    fun failure(record: SchoolRecord, error: SchoolError) {
        if (!record.notifications.enabled || SchoolAlert.CONNECTION_PROBLEMS !in record.notifications.categories || !allowed()) return
        val text = context.getString(if (error == SchoolError.SIGN_IN || error == SchoolError.MFA_REQUIRED) R.string.school_sync_sign_in_notification else R.string.school_sync_connection_notification)
        val notification = NotificationCompat.Builder(context, PROBLEMS).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.school_sync_notification_title)).setContentText(text)
            .setContentIntent(open("connection", null)).setAutoCancel(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_ERROR).build()
        try { channels().notify("school-connection", 0, notification) } catch (_: SecurityException) { }
    }
    fun review(record: SchoolRecord, changes: List<SchoolDelta>) {
        if (enabledChanges(record, changes).isEmpty() || !allowed()) return
        val notification = NotificationCompat.Builder(context, CHANGES).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.school_review_title))
            .setContentText(context.getString(R.string.school_sync_review_notification))
            .setContentIntent(open("review", null)).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT).build()
        try { channels().notify("school-review", 0, notification) } catch (_: SecurityException) { }
    }
    fun clearReview() { context.getSystemService(NotificationManager::class.java).cancel("school-review", 0) }
    private fun enabledChanges(record: SchoolRecord, changes: List<SchoolDelta>) = SchoolDiff.enabled(changes, record.notifications)
        .filter { it.item.kind != SchoolNoticeInterpreter.NOTICE_KIND || SchoolAlert.ADMINISTRATION_MESSAGES !in record.notifications.categories }
    fun clearFailure() { context.getSystemService(NotificationManager::class.java).cancel("school-connection", 0) }
    fun clearAll() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel("school-changes", 0); manager.cancel("school-connection", 0); clearReview()
        manager.activeNotifications.filter { it.tag?.startsWith("school-message:") == true }.forEach { manager.cancel(it.tag, it.id) }
    }
    private fun allowed() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    private fun channels(): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannelGroup(NotificationChannelGroup("school", context.getString(R.string.school_sync_notification_title)))
        manager.createNotificationChannel(NotificationChannel(CHANGES, context.getString(R.string.school_sync_changes_channel), NotificationManager.IMPORTANCE_DEFAULT).apply { group = "school" })
        manager.createNotificationChannel(NotificationChannel(PROBLEMS, context.getString(R.string.school_sync_problems_channel), NotificationManager.IMPORTANCE_DEFAULT).apply { group = "school" })
        manager.createNotificationChannel(NotificationChannel(MESSAGES, context.getString(R.string.school_alert_administration), NotificationManager.IMPORTANCE_DEFAULT).apply { group = "school" })
        return manager
    }
    private fun open(kind: String, date: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse("sunlith://school/$kind"))
        if (date != null) intent.putExtra("school_event_date", date)
        else if (kind == "messages") intent.putExtra(MainActivity.EXTRA_SCHOOL_MESSAGES, true)
        else intent.putExtra("school_open_settings", true)
        return PendingIntent.getActivity(context, when (kind) { "review" -> 73203; "changes" -> 73201; "messages" -> 73204; else -> 73202 }, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun label(alert: SchoolAlert) = when (alert) {
        SchoolAlert.TEACHER_ABSENCES -> R.string.school_alert_teacher_absences
        SchoolAlert.CLASS_TIMES -> R.string.school_alert_class_times
        SchoolAlert.ROOMS -> R.string.school_alert_rooms
        SchoolAlert.TEACHERS -> R.string.school_alert_teachers
        SchoolAlert.ADDED_CLASSES -> R.string.school_alert_added
        SchoolAlert.REMOVED_CLASSES -> R.string.school_alert_removed
        SchoolAlert.ASSESSMENTS -> R.string.school_alert_assessments
        SchoolAlert.SCHOOL_DAYS -> R.string.school_alert_school_days
        SchoolAlert.CONNECTION_PROBLEMS -> R.string.school_alert_connection
        SchoolAlert.ADMINISTRATION_MESSAGES -> R.string.school_alert_administration
    }
    companion object { private const val CHANGES = "school_schedule_changes"; private const val PROBLEMS = "school_connection_problems"; private const val MESSAGES = "school_administration_messages" }
}
