package io.github.sunsind.sunlith.ui

import io.github.sunsind.sunlith.data.CalendarEvent
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelinePackingTest {
    private val date = LocalDate.of(2026, 11, 5)
    private val zone = ZoneId.of("America/Toronto")

    private fun event(id: Long, from: Int, to: Int) = CalendarEvent(
        id, 1, "Event $id", "", "",
        date.atStartOfDay(zone).plusMinutes(from.toLong()).toInstant().toEpochMilli(),
        date.atStartOfDay(zone).plusMinutes(to.toLong()).toInstant().toEpochMilli(),
        false, zone.id,
    )

    @Test
    fun chainedOverlapsReuseAFreeLaneWithoutCoveringOtherEvents() {
        val clusters = packTimeline(listOf(event(1, 540, 600), event(2, 570, 630), event(3, 600, 660)), date, zone, 30)
        assertEquals(1, clusters.size)
        assertEquals(2, clusters.single().cols)
        val blocks = clusters.single().blocks
        for (a in blocks) for (b in blocks) {
            if (a !== b && a.start < b.end && b.start < a.end) assertTrue(a.col != b.col)
        }
        assertEquals(blocks[0].col, blocks[2].col)
    }

    @Test
    fun minimumTouchHeightParticipatesInOverlapPlacement() {
        val blocks = packTimeline(listOf(event(1, 540, 545), event(2, 560, 565)), date, zone, 30).single().blocks
        assertTrue(blocks.all { it.end - it.start >= 30 })
        assertTrue(blocks[0].col != blocks[1].col)
    }

    @Test
    fun eventsMeetingAtABoundaryNeedOnlyOneLane() {
        val clusters = packTimeline(listOf(event(1, 540, 600), event(2, 600, 660)), date, zone, 30)
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.cols == 1 })
    }

    @Test
    fun shortLateEventRemainsInsideItsDay() {
        val block = packTimeline(listOf(event(1, 1435, 1439)), date, zone, 30).single().blocks.single()
        assertEquals(1440, block.end)
        assertEquals(30, block.end - block.start)
    }

    @Test
    fun overnightTimedEventIsSplitBetweenItsTwoHourlyColumns() {
        val overnight = event(1, 22 * 60, 25 * 60)
        assertTrue(!overnight.isTimelineBanner())
        val first = packTimeline(listOf(overnight), date, zone, 30).single().blocks.single()
        val second = packTimeline(listOf(overnight), date.plusDays(1), zone, 30).single().blocks.single()
        assertEquals(1320, first.start)
        assertEquals(1440, first.end)
        assertEquals(0, second.start)
        assertEquals(60, second.end)
    }

    @Test
    fun zeroDurationEventStillHasAUsableVisibleBlock() {
        val block = packTimeline(listOf(event(1, 600, 600)), date, zone, 30).single().blocks.single()
        assertEquals(600, block.start)
        assertEquals(630, block.end)
    }
}
