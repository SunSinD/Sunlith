package io.github.sunsind.sunlith.ui

import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.EditBlockReason
import io.github.sunsind.sunlith.data.SchoolEventInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenStateTest {
    private val details = Screen.Details(event(1, "Class"))

    @Test
    fun screensFadeOverEachOtherAndCalendarTransitionsAreCuts() {
        val calendar = ScreenLayer(null, 0)
        val settings = ScreenLayer(Screen.Settings, 1)
        val link = ScreenLayer(Screen.DeviceLink, 2)
        assertEquals(ScreenMotion.Cut, screenMotion(calendar, settings, motion = true))
        assertEquals(ScreenMotion.Cut, screenMotion(calendar, ScreenLayer(Screen.Search, 1), motion = true))
        assertEquals(ScreenMotion.Cut, screenMotion(calendar, ScreenLayer(Screen.Todo, 1), motion = true))
        assertEquals(ScreenMotion.Enter, screenMotion(settings, link, motion = true))
        assertEquals(ScreenMotion.Return, screenMotion(link, settings, motion = true))
        assertEquals("Back to the calendar must never fade a screen over it.", ScreenMotion.Cut, screenMotion(settings, calendar, motion = true))
        assertEquals(ScreenMotion.Cut, screenMotion(link, calendar, motion = true))
    }

    @Test
    fun aScreenOpenedFromAClosingDialogAndReducedMotionAreCuts() {
        val calendar = ScreenLayer(null, 0)
        assertEquals(ScreenMotion.Cut, screenMotion(calendar, ScreenLayer(details, 1, instant = true), motion = true))
        assertEquals(ScreenMotion.Cut, screenMotion(calendar, ScreenLayer(Screen.Todo, 1), motion = false))
        assertEquals(ScreenMotion.Cut, screenMotion(ScreenLayer(Screen.Todo, 1), calendar, motion = false))
    }

    @Test
    fun monthGridDrilldownDefaultsToAllHoursAndOnlyKeepsKnownLayouts() {
        assertEquals("timeline", drilldownLayoutOrDefault(null))
        assertEquals("timeline", drilldownLayoutOrDefault("schedule"))
        assertEquals("cards", drilldownLayoutOrDefault("cards"))
        assertEquals("timeline", drilldownLayoutOrDefault("timeline"))
    }

    @Test
    fun detailsFollowTheLoadedEventAndOnlyUseTheDraftForAnUnloadedOne() {
        val opened = event(7, "Old text")
        val current = opened.copy(description = "New text", calendarName = "School")
        assertSame(current, liveDetailsEvent(opened, listOf(event(8, "Other"), current)))
        assertNull(liveDetailsEvent(opened, listOf(event(8, "Other"))))
        assertEquals("New text", detailsDescription(current, live = true, draftDescription = "Draft text"))
        assertEquals("Draft text", detailsDescription(opened, live = false, draftDescription = "Draft text"))
        assertEquals("Old text", detailsDescription(opened, live = false, draftDescription = null))
    }

    @Test
    fun aSchoolProjectionIsKnownToBeManagedBeforeTheProviderAnswers() {
        val plain = event(3, "")
        val school = plain.copy(school = SchoolEventInfo("243", "01", "c1", "T", "01", "Circuits", emptyList(), emptyList()))
        assertNull(knownDetailsBlock(plain))
        assertEquals(EditBlockReason.SCHOOL_MANAGED, knownDetailsBlock(school))
    }

    @Test
    fun movedDetailsFollowOnlyAnUnambiguousNonrecurringEvent() {
        val opened = event(9, "Original time")
        val moved = opened.copy(startMillis = opened.startMillis + 60_000, endMillis = opened.endMillis + 60_000)
        assertSame(moved, liveDetailsEvent(opened, listOf(moved)))
        assertNull(liveDetailsEvent(opened, listOf(moved, moved.copy(startMillis = moved.startMillis + 60_000))))
        assertNull(liveDetailsEvent(opened, listOf(moved.copy(rrule = "FREQ=DAILY"))))
        assertNull(liveDetailsEvent(opened, listOf(moved.copy(calendarId = 2))))
        val recurring = opened.copy(rrule = "FREQ=DAILY")
        assertNull(liveDetailsEvent(recurring, listOf(moved.copy(rrule = recurring.rrule))))
        assertSame(recurring, liveDetailsEvent(recurring, listOf(moved, recurring)))
    }

    private fun event(id: Long, description: String) =
        CalendarEvent(id, 1, "Event $id", description, "", 1_000L * id, 1_000L * id + 3_600_000L, false, "UTC")
}
