package io.github.sunsind.sunlith.system

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.widget.AgendaWidgetReceiver
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

object AppRefresh {
    internal val executor = Executors.newSingleThreadExecutor()
    fun refresh(context: Context, complete: (() -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            try {
                runCatching { AgendaWidgetReceiver.updateAll(app) }
                runCatching { ReminderScheduler.reschedule(app) }
                runCatching { CalendarChangeJobService.schedule(app) }
            } finally { complete?.invoke() }
        }
    }
}

class CalendarChangeJobService : JobService() {
    private val running = ConcurrentHashMap<Int, AtomicBoolean>()
    override fun onStartJob(params: JobParameters): Boolean {
        val stopped = AtomicBoolean(false)
        running.put(params.jobId, stopped)?.set(true)
        AppRefresh.executor.execute {
            var failed = false
            try {
                if (!stopped.get()) AgendaWidgetReceiver.updateAll(this)
                if (!stopped.get()) ReminderScheduler.reschedule(this)
            } catch (_: SecurityException) {
                failed = false
            } catch (_: Exception) {
                failed = true
            } finally {
                if (running.remove(params.jobId, stopped) && !stopped.get()) {
                    jobFinished(params, failed)
                    if (!failed && params.jobId == JOB_ID) runCatching { schedule(this, true) }
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.set(true)
        return true
    }

    companion object {
        private const val JOB_ID = 4031
        private const val MAINTENANCE_ID = 4033
        fun schedule(context: Context, replace: Boolean = false) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(MAINTENANCE_ID) == null) {
                scheduler.schedule(JobInfo.Builder(MAINTENANCE_ID, ComponentName(context, CalendarChangeJobService::class.java))
                    .setPeriodic(6 * 60 * 60 * 1000L).setPersisted(true).build())
            }
            if (!replace && scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJobService::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1000L)
                .setTriggerContentMaxDelay(5000L)
                .build())
        }
    }
}

class CalendarRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        AppRefresh.refresh(context) { pending.finish() }
    }
}
