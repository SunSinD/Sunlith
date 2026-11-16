package io.github.sunsind.sunlith.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LayoutChoicesTest {
    @Test
    fun longPressChoicesMatchEachViewAndKeepTheRetiredScheduleOut() {
        assertEquals(listOf("split", "full"), layoutChoices(ViewMode.Month))
        assertEquals("The paged 3, 5 and 7 day timelines became column counts of Continuous", listOf("list", "continuous"), layoutChoices(ViewMode.Week))
        assertEquals(listOf("cards", "timeline"), layoutChoices(ViewMode.Day))
        assertFalse("schedule" in layoutChoices(ViewMode.Day))
    }
}
