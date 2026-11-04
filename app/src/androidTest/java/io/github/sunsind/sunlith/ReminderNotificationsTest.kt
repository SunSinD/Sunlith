package io.github.sunsind.sunlith

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CalendarContract.CalendarAlerts
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.ReminderNotifications
import io.github.sunsind.sunlith.system.ReminderScheduler
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.system.CalendarReminderReceiver
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderNotificationsTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private lateinit var context: Context
    private lateinit var repository: CalendarRepository
    private lateinit var settings: SettingsStore
    private lateinit var manager: NotificationManager
    private var calendarId = 0L
    private val account = "Sunlith notification test ${UUID.randomUUID()}"
    private var oldCalendars = emptySet<Long>()
    private var oldChosen = false
    private var oldReminders = true

    @Before
    fun setUp() {
        requireEmulator()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = CalendarRepository(context)
        settings = SettingsStore(context)
        oldCalendars = settings.selectedCalendarIds
        oldChosen = settings.hasChosenCalendars
        oldReminders = settings.remindersEnabled
        calendarId = insertLocalCalendar(context, "sunlith-notification-test", "Sunlith notification test", account)
        settings.selectedCalendarIds = setOf(calendarId)
        settings.hasChosenCalendars = true
        settings.remindersEnabled = true
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        if (!::context.isInitialized) return
        manager.cancelAll()
        context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
        settings.selectedCalendarIds = oldCalendars
        settings.hasChosenCalendars = oldChosen
        settings.remindersEnabled = oldReminders
        ReminderScheduler.reschedule(context)
    }

    @Test
    fun notificationContainsActionsAndDoesNotDuplicateAnOccurrence() {
        val event = createEvent(System.currentTimeMillis() + 3_600_000, "Notification smoke test")
        assertTrue(ReminderNotifications.allowed(context))
        assertTrue(ReminderNotifications.post(context, event, 15))
        assertFalse(ReminderNotifications.post(context, event, 15))
        waitForNotification(event.title)
        val active = manager.activeNotifications.single { it.notification.extras.getString(Notification.EXTRA_TITLE) == event.title }
        assertEquals(ReminderNotifications.CHANNEL, active.notification.channelId)
        assertEquals(2, active.notification.actions.size)
        assertEquals("Snooze 10 min", active.notification.actions[0].title.toString())
        assertEquals("Dismiss", active.notification.actions[1].title.toString())
        assertTrue(active.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Laval"))
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(active.notification.contentIntent.isImmutable)
            assertTrue(active.notification.actions.all { it.actionIntent.isImmutable })
        }
        assertNotNull(manager.getNotificationChannel(ReminderNotifications.CHANNEL))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.openNotification()
        assertTrue("Wait until Android has rendered the notification before acting on it.", device.wait(Until.hasObject(By.pkg("com.android.systemui").text(event.title)), 10_000))
        active.notification.actions[1].actionIntent.send()
        val dismissalDeadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < dismissalDeadline && manager.activeNotifications.any { it.key == active.key }) {
            SystemClock.sleep(100)
        }
        assertTrue("The notification Dismiss action must remove the notification.", manager.activeNotifications.none { it.key == active.key })
        device.pressBack()
    }

    @Test
    fun disabledRemindersDoNotPostNotifications() {
        val event = createEvent(System.currentTimeMillis() + 3_600_000, "Disabled notification test")
        settings.remindersEnabled = false
        assertFalse(ReminderNotifications.allowed(context))
        assertFalse(ReminderNotifications.post(context, event, 15))
        assertTrue(manager.activeNotifications.none { it.notification.extras.getString(Notification.EXTRA_TITLE) == event.title })
    }

    @Test
    fun scheduledAlarmPostsTheRealCalendarEvent() {
        val event = createEvent(System.currentTimeMillis() + 7_000, "Scheduled reminder test")
        ReminderScheduler.reschedule(context)
        waitForNotification(event.title, 20_000)
        val active = manager.activeNotifications.single { it.notification.extras.getString(Notification.EXTRA_TITLE) == event.title }
        assertTrue(active.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Laval"))
    }

    @Test
    fun providerBroadcastHandlesADefaultReminderWithoutChangingAlertState() {
        val now = System.currentTimeMillis()
        val start = now + 600_000
        val id = repository.saveEvent(EventDraft(
            calendarId = calendarId,
            title = "Provider default reminder test",
            location = "Laval",
            startMillis = start,
            endMillis = start + 3_600_000,
            reminderMinutes = emptyList(),
        ))
        assertNotNull(context.contentResolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, id)
            put(Reminders.MINUTES, Reminders.MINUTES_DEFAULT)
            put(Reminders.METHOD, Reminders.METHOD_ALERT)
        }))
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply {
            put(Events.HAS_ALARM, 1)
        }, null, null)
        val alertTime = now - 1_000
        val alertUri = requireNotNull(context.contentResolver.insert(CalendarAlerts.CONTENT_URI, ContentValues().apply {
            put(CalendarAlerts.EVENT_ID, id)
            put(CalendarAlerts.BEGIN, start)
            put(CalendarAlerts.END, start + 3_600_000)
            put(CalendarAlerts.ALARM_TIME, alertTime)
            put(CalendarAlerts.CREATION_TIME, now)
            put(CalendarAlerts.RECEIVED_TIME, 0)
            put(CalendarAlerts.NOTIFY_TIME, 0)
            put(CalendarAlerts.MINUTES, Reminders.MINUTES_DEFAULT)
            put(CalendarAlerts.STATE, CalendarAlerts.STATE_SCHEDULED)
        })) { "The emulator provider must accept an isolated test alert row." }
        try {
            fun alertState(): Int = context.contentResolver.query(alertUri, arrayOf(CalendarAlerts.STATE), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                it.getInt(0)
            }
            val before = alertState()
            assertEquals(CalendarAlerts.STATE_SCHEDULED, before)
            val component = "${context.packageName}/${CalendarReminderReceiver::class.java.name}"
            val command = "am broadcast -a ${CalendarContract.ACTION_EVENT_REMINDER} -n $component -d content://com.android.calendar/$alertTime"
            val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8) } }
            assertFalse(output.contains("SecurityException"))
            waitForNotification("Provider default reminder test")
            assertEquals("The app must leave the shared provider alert state untouched.", before, alertState())
        } finally {
            context.contentResolver.delete(alertUri, null, null)
        }
    }

    private fun createEvent(start: Long, title: String): CalendarEvent {
        val id = repository.saveEvent(EventDraft(calendarId = calendarId, title = title, location = "Laval", startMillis = start, endMillis = start + 3_600_000, reminderMinutes = listOf(0)))
        return repository.events(start - 1, start + 7_200_000, setOf(calendarId)).single { it.id == id }
    }

    private fun waitForNotification(title: String, timeout: Long = 5_000) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (manager.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE) == title }) return
            SystemClock.sleep(100)
        }
        assertTrue("A reminder notification for '$title' must be posted before the timeout.", manager.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE) == title })
    }
}
