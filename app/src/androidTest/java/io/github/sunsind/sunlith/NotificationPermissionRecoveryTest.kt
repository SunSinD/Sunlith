package io.github.sunsind.sunlith

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.ReminderNotifications
import io.github.sunsind.sunlith.system.SettingsStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NotificationPermissionRecoveryTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    @Test
    fun deniedNotificationsStaySilentThenRecoverThroughThePermissionDialog() {
        assumeTrue("Run separately after pm revoke, with -e permissionRecovery true.", InstrumentationRegistry.getArguments().getString("permissionRecovery") == "true")
        requireEmulator()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("Revoke POST_NOTIFICATIONS before starting this instrumentation process.", PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val settings = SettingsStore(context)
        val oldReminders = settings.remindersEnabled
        val oldChosen = settings.hasChosenCalendars
        val oldCalendars = settings.selectedCalendarIds
        val prefs = context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE)
        val oldAsked = if (prefs.contains("notify_asked")) prefs.getBoolean("notify_asked", false) else null
        val oldAiIntro = if (prefs.contains("ai_intro_done")) prefs.getBoolean("ai_intro_done", false) else null
        val account = "Sunlith permission test ${UUID.randomUUID()}"
        val calendarId = insertLocalCalendar(context, "sunlith-permission-test", "Sunlith permission test", account)
        val repository = CalendarRepository(context)
        val start = System.currentTimeMillis() + 3_600_000
        val id = repository.saveEvent(EventDraft(calendarId = calendarId, title = "Permission recovery test", startMillis = start, endMillis = start + 3_600_000, reminderMinutes = emptyList()))
        val event = repository.events(start - 1, start + 7_200_000, setOf(calendarId)).single { it.id == id }
        settings.remindersEnabled = true
        settings.hasChosenCalendars = true
        settings.selectedCalendarIds = setOf(calendarId)
        prefs.edit().putBoolean("notify_asked", false).putBoolean("ai_intro_done", true).commit()
        var activity: android.app.Activity? = null
        try {
            assertFalse(ReminderNotifications.allowed(context))
            assertFalse(ReminderNotifications.post(context, event, 0))
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            requireNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.action_allow_notifications))), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 10_000)).click()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            assertTrue(ReminderNotifications.allowed(context))
            assertTrue(ReminderNotifications.post(context, event, 0))
            val manager = context.getSystemService(NotificationManager::class.java)
            val notifyDeadline = SystemClock.elapsedRealtime() + 5_000
            while (manager.activeNotifications.none { it.tag == "${event.id}:${event.startMillis}:0" } && SystemClock.elapsedRealtime() < notifyDeadline) SystemClock.sleep(100)
            assertTrue(manager.activeNotifications.any { it.tag == "${event.id}:${event.startMillis}:0" })
            manager.cancel("${event.id}:${event.startMillis}:0", 0)
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
            settings.remindersEnabled = oldReminders
            settings.hasChosenCalendars = oldChosen
            settings.selectedCalendarIds = oldCalendars
            prefs.edit().apply { if (oldAsked == null) remove("notify_asked") else putBoolean("notify_asked", oldAsked) }.commit()
            prefs.edit().apply { if (oldAiIntro == null) remove("ai_intro_done") else putBoolean("ai_intro_done", oldAiIntro) }.commit()
        }
    }
}
