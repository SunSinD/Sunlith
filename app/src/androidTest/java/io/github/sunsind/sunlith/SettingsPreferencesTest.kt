package io.github.sunsind.sunlith

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sunsind.sunlith.system.SettingsStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPreferencesTest {
    private lateinit var context: Context
    private lateinit var isolated: Context
    private val files = mutableSetOf<String>()

    @Before
    fun setUp() {
        requireEmulator()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "sunlith-settings-test-${UUID.randomUUID()}-"
        isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val file = prefix + name
                files += file
                return context.getSharedPreferences(file, mode)
            }
        }
    }

    @After
    fun tearDown() {
        if (::context.isInitialized) files.forEach(context::deleteSharedPreferences)
    }

    @Test
    fun freshPreferencesFollowSystemAndShowAnOpaqueSevenDayWidget() {
        val settings = SettingsStore(isolated)
        assertEquals("system", settings.themeMode)
        assertEquals(100, settings.widgetOpacity)
        assertEquals(7, settings.widgetDays)
        assertFalse(settings.widgetShowCalendarName)
        assertTrue(settings.widgetShowEmptyDays)
        assertEquals("all", settings.agendaDisplay)
        assertEquals("Month", settings.startView)
        assertFalse(settings.collapseCalendarOnScroll)
        assertTrue(settings.navigationAnimation)
    }

    @Test
    fun widgetControlsKeepValidRangesAndFivePercentOpacitySteps() {
        val settings = SettingsStore(isolated)
        listOf(-20 to 0, 2 to 0, 3 to 5, 97 to 95, 98 to 100, 120 to 100).forEach { (requested, expected) ->
            settings.widgetOpacity = requested
            assertEquals(expected, SettingsStore(isolated).widgetOpacity)
        }
        listOf(0 to 1, 1 to 1, 7 to 7, 31 to 31, 99 to 31).forEach { (requested, expected) ->
            settings.widgetDays = requested
            assertEquals(expected, SettingsStore(isolated).widgetDays)
        }
    }

    @Test
    fun calendarAndWidgetChoicesSurviveRecreatingTheSettingsStore() {
        SettingsStore(isolated).apply {
            themeMode = "light"
            widgetDays = 14
            widgetOpacity = 65
            widgetShowCalendarName = true
            widgetShowEmptyDays = false
            agendaDisplay = "compact"
            startView = "last"
            collapseCalendarOnScroll = true
            navigationAnimation = false
        }
        val restored = SettingsStore(isolated)
        assertEquals("light", restored.themeMode)
        assertEquals(14, restored.widgetDays)
        assertEquals(65, restored.widgetOpacity)
        assertTrue(restored.widgetShowCalendarName)
        assertFalse(restored.widgetShowEmptyDays)
        assertEquals("compact", restored.agendaDisplay)
        assertEquals("last", restored.startView)
        assertTrue(restored.collapseCalendarOnScroll)
        assertFalse(restored.navigationAnimation)
    }
}
