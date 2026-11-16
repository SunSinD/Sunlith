package io.github.sunsind.sunlith

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentProvider
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
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
import io.github.sunsind.sunlith.system.SnoozedReminders
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.system.CalendarReminderReceiver
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var preferenceSnapshot: SharedPreferencesSnapshot
    private var calendarId = 0L
    private val account = "Sunlith notification test ${UUID.randomUUID()}"
    private var oldCalendars = emptySet<Long>()
    private var oldChosen = false
    private var oldReminders = true

    @Before
    fun setUp() {
        requireEmulator()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferenceSnapshot = SharedPreferencesSnapshot(context)
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
        preferenceSnapshot.restore()
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
    fun notificationTimingUsesEnglishAndFrenchResourceText() {
        val event = createEvent(System.currentTimeMillis() + 2 * 86_400_000L, "Localized timing test")
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val french = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.CANADA_FRENCH) })

        assertTrue(ReminderNotifications.post(english, event, 15, repeat = true))
        waitForNotificationText(event.title, "Starts ")
        val englishText = manager.activeNotifications.single { it.notification.extras.getString(Notification.EXTRA_TITLE) == event.title }
            .notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(englishText.contains("In 2 days"))
        assertTrue(englishText.contains("Starts "))
        assertTrue(englishText.contains("Laval"))

        assertTrue(ReminderNotifications.post(french, event, 15, repeat = true))
        waitForNotificationText(event.title, "Commence ")
        val frenchText = manager.activeNotifications.single { it.notification.extras.getString(Notification.EXTRA_TITLE) == event.title }
            .notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(frenchText.contains("Dans 2 jours"))
        assertTrue(frenchText.contains("Commence "))
        assertTrue(frenchText.contains("Laval"))
    }

    @Test
    fun restoringSnoozesRemovesTheOriginalMalformedKeys() {
        val prefs = context.getSharedPreferences("snoozed_reminders", Context.MODE_PRIVATE)
        prefs.edit().clear().putLong("bad:1:0", System.currentTimeMillis() + 60_000)
            .putLong("007:1:0", System.currentTimeMillis() + 60_000).commit()
        SnoozedReminders.restore(context)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun separateRecurringOccurrenceStartsKeepSeparateNotificationIdentity() {
        val first = createEvent(System.currentTimeMillis() + 3_600_000L, "Recurring occurrence identity test")
        val second = first.copy(startMillis = first.startMillis + 86_400_000L, endMillis = first.endMillis + 86_400_000L)

        assertTrue(ReminderNotifications.post(context, first, 15, repeat = true))
        assertTrue(ReminderNotifications.post(context, second, 15, repeat = true))
        awaitNotification("Both recurring occurrences must be posted") {
            manager.activeNotifications.count { it.notification.extras.getString(Notification.EXTRA_TITLE) == first.title } == 2
        }
        assertEquals(2, manager.activeNotifications.count {
            it.notification.extras.getString(Notification.EXTRA_TITLE) == first.title
        })
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

    @Test
    fun providerBroadcastSharesBoundedInstanceReadAcrossSameWindowAlerts() {
        val now = System.currentTimeMillis()
        val starts = listOf(now + 600_000, now + 1_200_000, now + 1_800_000, now + 90L * 86_400_000)
        val events = starts.mapIndexed { index, start ->
            val id = repository.saveEvent(EventDraft(
                calendarId = calendarId,
                title = "Batched provider reminder $index",
                location = "Laval",
                startMillis = start,
                endMillis = start + 3_600_000,
                reminderMinutes = emptyList(),
            ))
            id to start
        }
        val excludedCalendarId = insertLocalCalendar(context, "sunlith-notification-excluded", "Excluded reminder calendar", account)
        val excludedEventId = repository.saveEvent(EventDraft(
            calendarId = excludedCalendarId, title = "Unselected calendar reminder",
            startMillis = starts.first(), endMillis = starts.first() + 3_600_000,
            reminderMinutes = emptyList(),
        ))
        val alertUris = (events + (excludedEventId to starts.first())).flatMap { (id, start) ->
            listOf(5, 15).map { minutes ->
                requireNotNull(context.contentResolver.insert(CalendarAlerts.CONTENT_URI, ContentValues().apply {
                    put(CalendarAlerts.EVENT_ID, id)
                    put(CalendarAlerts.BEGIN, start)
                    put(CalendarAlerts.END, start + 3_600_000)
                    put(CalendarAlerts.ALARM_TIME, now - minutes * 1_000L)
                    put(CalendarAlerts.CREATION_TIME, now)
                    put(CalendarAlerts.RECEIVED_TIME, 0)
                    put(CalendarAlerts.NOTIFY_TIME, 0)
                    put(CalendarAlerts.MINUTES, minutes)
                    put(CalendarAlerts.STATE, CalendarAlerts.STATE_SCHEDULED)
                }))
            }
        }
        val instanceQueries = AtomicInteger()
        val delegateContext = context
        val resolver = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
                if (uri.pathSegments.firstOrNull() == "instances") instanceQueries.incrementAndGet()
                return delegateContext.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            }
            override fun getType(uri: Uri) = delegateContext.contentResolver.getType(uri)
            override fun insert(uri: Uri, values: ContentValues?) = delegateContext.contentResolver.insert(uri, values)
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = delegateContext.contentResolver.delete(uri, selection, selectionArgs)
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = delegateContext.contentResolver.update(uri, values, selection, selectionArgs)
        }.apply {
            attachInfo(delegateContext, ProviderInfo().apply {
                authority = CalendarContract.AUTHORITY
                exported = false
            })
        })
        val repositoryContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
        }
        val countingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = repositoryContext
        }
        try {
            ReminderNotifications.postProviderAlerts(countingContext)
            events.forEachIndexed { index, _ -> waitForNotification("Batched provider reminder $index") }
            assertEquals("Same-window alerts should share one read while the distant range uses another bounded query.", 2, instanceQueries.get())
            assertFalse("Unselected calendars must not post reminders", manager.activeNotifications.any {
                it.notification.extras.getString(Notification.EXTRA_TITLE) == "Unselected calendar reminder"
            })
            assertEquals("Each event's two reminder minutes must produce its own notification.", 8, manager.activeNotifications.count {
                it.notification.extras.getString(Notification.EXTRA_TITLE)?.startsWith("Batched provider reminder") == true
            })
        } finally {
            alertUris.forEach { context.contentResolver.delete(it, null, null) }
            context.contentResolver.delete(localCalendarUri(excludedCalendarId, account), null, null)
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

    private fun waitForNotificationText(title: String, text: String) = awaitNotification("'$title' must contain '$text'") {
        manager.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE) == title &&
            it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(text) }
    }

    private fun awaitNotification(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
}
