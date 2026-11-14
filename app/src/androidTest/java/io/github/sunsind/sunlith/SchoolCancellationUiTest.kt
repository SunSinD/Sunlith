package io.github.sunsind.sunlith

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.SchoolEventInfo
import io.github.sunsind.sunlith.data.SchoolEventPeriod
import io.github.sunsind.sunlith.school.SchoolClassLift
import io.github.sunsind.sunlith.ui.CalendarEventCard
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.SunlithTheme
import io.github.sunsind.sunlith.ui.TimelineEventList
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchoolCancellationUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var activity: ComponentActivity
    private lateinit var device: UiDevice
    private val day = LocalDate.of(2026, 11, 13)
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() {
        requireEmulator()
        device = UiDevice.getInstance(instrumentation)
        activity = instrumentation.startActivitySync(Intent(context, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
    }

    @After
    fun tearDown() {
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    private fun event(): CalendarEvent {
        val start = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(9, 50).atZone(zone).toInstant().toEpochMilli()
        return CalendarEvent(-71, 1, "Class cancelled: Circuits", "", "A100", start, end, false, zone.id,
            school = SchoolEventInfo("243-TEST", "01", "class-key", "2", "01", "Circuits",
                listOf(SchoolEventPeriod(-71, start, end, "A100", "")), emptyList(),
                SchoolClassLift(day, LocalTime.of(18, 0), day.minusDays(1))))
    }

    @Test
    fun collegeCancellationIsVisibleInTheCardAndChooserAndDisappearsAfterWithdrawal() {
        for (locale in listOf(Locale.ENGLISH, Locale.FRENCH)) {
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            // Dialog reads the host View's context. Give the view itself the locale, so the separate dialog window
            // has the same resources as the card, rather than overriding only composition locals in an English view.
            val localized = ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault).apply {
                applyOverrideConfiguration(configuration)
            }
            val cancelled = event().copy(title = localized.getString(R.string.school_sync_lifted_title, "Circuits"))
            val shown = mutableStateOf(cancelled)
            val chooser = mutableStateOf(false)
            var opened: CalendarEvent? = null
            val formats = Formats(localized, locale)
            val status = localized.getString(R.string.calendar_class_cancelled_until, formats.time(LocalTime.of(18, 0)))
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(localized).apply {
                    setContent {
                        SunlithTheme("light", false) {
                            Box(Modifier.fillMaxSize().padding(16.dp).semantics { testTagsAsResourceId = true }) {
                                CalendarEventCard(shown.value, day, formats, onClick = { chooser.value = true })
                                if (chooser.value) TimelineEventList(listOf(shown.value), formats,
                                    onOpen = { opened = it }, onClose = { chooser.value = false })
                            }
                        }
                    }
                })
            }
            assertNotNull("The cancelled title must stay visible in ${locale.language}.",
                device.wait(Until.findObject(By.text(cancelled.title)), 5_000))
            assertNotNull("The college cancellation must have visible text in ${locale.language}.",
                device.wait(Until.findObject(By.text(status)), 5_000))
            assertFalse("A college cancellation is not a teacher absence.",
                device.hasObject(By.text(localized.getString(R.string.calendar_teacher_absent))))
            instrumentation.runOnMainSync { chooser.value = true }
            val row = requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).res("timeline_event_choice_-71")), 5_000))
            assertTrue("The chooser must use ${locale.language} cancellation status '$status': ${row.contentDescription}",
                row.contentDescription.orEmpty().contains(status))
            row.click()
            assertTrue(device.wait(Until.gone(By.pkg(context.packageName).res("timeline_event_list")), 5_000))
            assertEquals(cancelled.id, opened?.id)
            instrumentation.runOnMainSync { shown.value = cancelled.copy(title = "Circuits", school = cancelled.school!!.copy(lift = null)) }
            assertNotNull(device.wait(Until.findObject(By.text("Circuits")), 5_000))
            assertTrue("Withdrawal removes the cancellation status.", device.wait(Until.gone(By.text(status)), 5_000))
            assertFalse(device.hasObject(By.text(cancelled.title)))
        }
    }
}
