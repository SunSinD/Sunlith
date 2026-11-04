package io.github.sunsind.sunlith.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import io.github.sunsind.sunlith.MainActivity
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.system.AppRefresh
import io.github.sunsind.sunlith.system.SettingsStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

class AgendaWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, manager, ids)
        val pending = goAsync()
        AppRefresh.refresh(context) { pending.finish() }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        update(context, manager, intArrayOf(id))
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AgendaWidgetReceiver::class.java))
            update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val today = LocalDate.now()
            val locale = context.resources.configuration.locales[0]
            val datePattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMd")
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_agenda)
                views.setTextViewText(R.id.widget_header, today.dayOfWeek.getDisplayName(TextStyle.FULL, locale).uppercase(locale))
                views.setTextViewText(R.id.widget_date, today.format(DateTimeFormatter.ofPattern(datePattern, locale)))
                views.setInt(R.id.widget_background, "setImageAlpha", SettingsStore(context).widgetOpacity * 255 / 100)
                val service = Intent(context, AgendaWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                service.data = android.net.Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
                views.setRemoteAdapter(R.id.widget_events, service)
                views.setEmptyView(R.id.widget_events, R.id.widget_empty)
                val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                views.setOnClickPendingIntent(R.id.widget_heading, activityPending(context, id, open, "header"))
                views.setOnClickPendingIntent(R.id.widget_empty, activityPending(context, id, open, "empty"))
                views.setOnClickPendingIntent(R.id.widget_add, activityPending(context, id, Intent(open).putExtra("create_event", true), "add"))
                val templateFlags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                views.setPendingIntentTemplate(R.id.widget_events, activityPending(context, id, open, "event", templateFlags))
                manager.updateAppWidget(id, views)
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_events)
            }
        }

        private fun activityPending(context: Context, widgetId: Int, source: Intent, action: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE): PendingIntent {
            val intent = Intent(source).setData(Uri.parse("sunlith://widget/$widgetId/$action"))
            return PendingIntent.getActivity(context, widgetId, intent, flags)
        }
    }
}
