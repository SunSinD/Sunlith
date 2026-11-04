package io.github.sunsind.sunlith.ui

import io.github.sunsind.sunlith.data.EventDraft
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorStateTest {
    @Test
    fun textOnlyEditPreservesSecondsAndSecondOccurrenceOfRepeatedDstHour() {
        val zone = ZoneId.of("America/Toronto")
        val draft = EventDraft(calendarId = 1, title = "Original",
            startMillis = Instant.parse("2026-11-01T06:30:45.123Z").toEpochMilli(),
            endMillis = Instant.parse("2026-11-01T07:30:45.123Z").toEpochMilli(),
            timeZone = zone.id)
        val saved = EditorState.from(draft, zone).copy(title = "Changed").toDraft(zone)
        assertEquals(draft.startMillis, saved.startMillis)
        assertEquals(draft.endMillis, saved.endMillis)
    }

    @Test
    fun changingStartDateRetainsExactEventLength() {
        val zone = ZoneId.of("America/Toronto")
        val draft = EventDraft(calendarId = 1, title = "Original",
            startMillis = Instant.parse("2026-11-04T14:30:45Z").toEpochMilli(),
            endMillis = Instant.parse("2026-11-04T16:00:45Z").toEpochMilli(),
            timeZone = zone.id)
        val state = EditorState.from(draft, zone)
        val saved = state.withStartDate(state.startDate.plusDays(1)).toDraft(zone)
        assertEquals(draft.startMillis + 86_400_000L, saved.startMillis)
        assertEquals(draft.endMillis - draft.startMillis, saved.endMillis - saved.startMillis)
    }
}
