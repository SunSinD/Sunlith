package io.github.sunsind.sunlith.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LayoutChoicesTest {
    @Test
    fun longPressChoicesMatchEachViewAndKeepTheRetiredScheduleOut() {
        assertEquals(listOf("split", "full"), layoutChoices(ViewMode.Month))
        assertEquals(listOf("list", "timeline3", "timeline5", "timeline7", "continuous"), layoutChoices(ViewMode.Week))
        assertEquals(listOf("cards", "timeline"), layoutChoices(ViewMode.Day))
        assertFalse("schedule" in layoutChoices(ViewMode.Day))
    }
}
