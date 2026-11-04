package io.github.sunsind.sunlith

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.CalendarContract.Calendars
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.system.SettingsStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoFixtureTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    @Test
    fun seedDemoCalendarAndLaunch() {
        requireEmulator()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = CalendarRepository(context)
        val account = "Sunlith demo"
        val previous = context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?",
            arrayOf(account, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL, "sunlith-demo-fixture"),
            null,
        )?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }.orEmpty()
        previous.forEach { context.contentResolver.delete(localCalendarUri(it, account), null, null) }
        val calendarId = insertLocalCalendar(context, "sunlith-demo-fixture", "Sunlith demo", account)
        val today = LocalDate.now()
        val firstEventDay = if (LocalTime.now().isBefore(LocalTime.of(19, 0))) today else today.plusDays(1)
        val zone = ZoneId.systemDefault()
        fun add(day: LocalDate, hour: Int, durationMinutes: Int, title: String, location: String = "", recurrence: String? = null) {
            val start = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            repository.saveEvent(EventDraft(
                calendarId = calendarId,
                title = title,
                description = "Sample event for Sunlith screenshots and emulator testing.",
                location = location,
                startMillis = start,
                endMillis = start + durationMinutes * 60_000L,
                timeZone = zone.id,
                rrule = recurrence,
                reminderMinutes = listOf(15),
            ))
        }
        add(firstEventDay, 17, 120, "Driving class", "Laval")
        add(firstEventDay.plusDays(1), 17, 60, "Dentist appointment", "Laval")
        add(firstEventDay.plusDays(1), 19, 90, "Study session", "At home")
        add(firstEventDay.plusDays(2), 9, 180, "Circuits numériques lab", "Collège Montmorency", "FREQ=WEEKLY;COUNT=6")
        repository.saveEvent(EventDraft(
            calendarId = calendarId,
            title = "Lab report due",
            startMillis = CalendarDates.allDayMillis(firstEventDay.plusDays(3)),
            endMillis = CalendarDates.allDayMillis(firstEventDay.plusDays(4)),
            allDay = true,
            timeZone = "UTC",
            reminderMinutes = emptyList(),
        ))
        SettingsStore(context).apply {
            selectedCalendarIds = setOf(calendarId)
            hasChosenCalendars = true
            themeMode = "dark"
            weekStartsMonday = true
            widgetOpacity = 96
        }
        context.getSharedPreferences("sunlith_ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("ai_intro_done", true).commit()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        UiDevice.getInstance(instrumentation).waitForIdle()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("demoCalendarId", calendarId.toString())
            putString("demoDate", today.toString())
            putString("demoData", "Synthetic sample events, created only by instrumentation on an emulator.")
        })
    }
}
