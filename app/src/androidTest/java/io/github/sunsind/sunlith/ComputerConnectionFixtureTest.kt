package io.github.sunsind.sunlith

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.device.DeviceLink
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComputerConnectionFixtureTest {
    @get:Rule
    val permissions: GrantPermissionRule = computerConnectionPermissions()

    @Test
    fun exposeIsolatedCalendarUntilExternalClientFinishes() {
        assumeTrue("Enable only for an external emulator client with -e deviceLinkExternal true.", InstrumentationRegistry.getArguments().getString("deviceLinkExternal") == "true")
        val fixture = ComputerConnectionHarness()
        val exported = File(fixture.context.getExternalFilesDir(null), "device-link-test.json")
        val metadata = File(fixture.context.getExternalFilesDir(null), "device-link-test-metadata.json")
        val finished = File(fixture.context.filesDir, "device-link-test-finished")
        var prepared = false
        try {
            fixture.prepare()
            prepared = true
            assertTrue("A stale external sentinel must be removable.", !finished.exists() || finished.delete())
            val repository = CalendarRepository(fixture.context)
            val start = System.currentTimeMillis() + 86_400_000
            val excludedEventId = repository.saveEvent(EventDraft(calendarId = fixture.displayCalendarId, title = "Excluded external test event", startMillis = start, endMillis = start + 3_600_000, reminderMinutes = emptyList()))
            fixture.launch()
            exported.writeText(DeviceLink.createPairing(fixture.context, setOf(fixture.linkedCalendarId), "127.0.0.1"))
            metadata.writeText(JSONObject().put("calendarId", fixture.linkedCalendarId).put("excludedCalendarId", fixture.displayCalendarId).put("excludedEventId", excludedEventId).toString())
            fixture.instrumentation.runOnMainSync { DeviceLink.start(requireNotNull(fixture.activity)) }
            fixture.await("External fixture did not start visibly") { DeviceLink.state.value.running && fixture.hasConnectionNotification() }
            Log.i("ComputerConnectionFixture", "DEVICE_LINK_EXTERNAL_READY ${exported.absolutePath}")
            val deadline = SystemClock.elapsedRealtime() + 180_000
            while (!finished.exists() && SystemClock.elapsedRealtime() < deadline) {
                assertNull("External connection failed while waiting for the client.", DeviceLink.state.value.error)
                assertTrue("External connection stopped before client completion.", DeviceLink.state.value.running)
                SystemClock.sleep(200)
            }
            assertTrue("External client did not signal completion within 180 seconds.", finished.exists())
            assertNull(DeviceLink.state.value.error)
            assertTrue(DeviceLink.state.value.running)
        } finally {
            if (prepared) {
                exported.delete()
                metadata.delete()
                finished.delete()
            }
            fixture.close()
        }
    }
}
