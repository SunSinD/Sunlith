package io.github.sunsind.sunlith.ui

import io.github.sunsind.sunlith.data.CalendarEvent
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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
    fun shortLabelsUseFreeTimeWithoutCreatingFalseOverlaps() {
        val clusters = packTimeline(listOf(event(1, 540, 545), event(2, 560, 565)), date, zone, 30)
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.cols == 1 })
        val blocks = clusters.flatMap { it.blocks }
        assertEquals(listOf(540, 560), blocks.map { it.start })
        assertEquals(listOf(560, 590), blocks.map { it.end })
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
        assertEquals(1435, block.start)
        assertEquals(1440, block.end)
        assertEquals(5, block.end - block.start)
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

    @Test
    fun adjacentTinyEventsKeepExactStartsAndAFullWidthLane() {
        val clusters = packTimeline(listOf(event(1, 540, 545), event(2, 545, 550), event(3, 550, 551)), date, zone, 53)
        assertEquals(3, clusters.size)
        assertTrue(clusters.all { it.cols == 1 })
        val blocks = clusters.flatMap { it.blocks }
        assertEquals(listOf(540, 545, 550), blocks.map { it.start })
        assertEquals(listOf(545, 550, 603), blocks.map { it.end })
    }

    @Test
    fun realShortOverlapsStillUseSeparateLanes() {
        val clusters = packTimeline(listOf(event(1, 540, 545), event(2, 543, 546), event(3, 546, 547)), date, zone, 30)
        assertEquals(2, clusters.size)
        assertEquals(2, clusters.first().cols)
        assertEquals(listOf(540, 543), clusters.first().blocks.map { it.start })
        assertEquals(listOf(545, 546), clusters.first().blocks.map { it.end })
        assertEquals(1, clusters.last().cols)
    }

    @Test
    fun finalMinuteAndMidnightPointEventsNeverShiftEarlier() {
        for (minute in listOf(0, 1, 1438, 1439)) {
            val block = packTimeline(listOf(event(1, minute, minute)), date, zone, 53).single().blocks.single()
            assertEquals(minute, block.start)
            assertEquals(minOf(1440, minute + 53), block.end)
        }
    }

    @Test
    fun labelsAcrossEveryShownDayProtectTheirExpandedSpanFromGrouping() {
        val first = packTimeline(listOf(event(1, 10, 15)), date, zone, 15)
        val last = packTimeline(listOf(event(2, 775, 776)), date, zone, 90)
        val spans = (first + last).flatMap { it.blocks }.map { it.start to it.end }
        val axis = TimelineAxis(90f, spans, groupEmpty = true)
        assertEquals(listOf(120 to 660, 960 to 1440), axis.gaps.map { it.startMinute to it.endMinute })
        spans.forEach { (start, end) ->
            assertEquals((end - start) / 60f * 90f, axis.minuteToY(end) - axis.minuteToY(start), 0.01f)
        }
    }

    @Test
    fun foldCoverFitsFiveNormalColumnsWhileSevenAndLargeFontsOverflow() {
        val available = 280.dp - 44.dp - 4.dp
        assertTrue(available / 5 >= timelineOverflowThreshold(5, 12.dp))
        assertTrue(available / 7 < timelineOverflowThreshold(7, 12.dp))
        assertTrue(available / 5 < timelineOverflowThreshold(5, 20.dp, 1.5f))
        assertEquals(40.dp, timelineOverflowThreshold(5, 12.dp))
        assertEquals(60.dp, timelineOverflowThreshold(7, 12.dp))
        assertTrue(available / 5 >= timelineOverflowThreshold(5, 13.8.dp, 1.15f))
        assertTrue(available / 5 < timelineOverflowThreshold(5, 14.4.dp, 1.2f))
        assertEquals(48.dp, timelineOverflowThreshold(5, 12.dp, 1.2f))
    }

    @Test
    fun tinyEventShortcutRetainsEveryAdjacentAndIsolatedShortEvent() {
        val items = listOf(event(1, 540, 545), event(2, 545, 550), event(3, 550, 555), event(4, 600, 660), event(5, 800, 800))
        assertEquals(listOf(1L, 2L, 3L, 5L), shortTimelineEvents(items, date, zone, 15).map { it.id })
    }

    @Test
    fun resizingUsesTheSavedDayAndFractionBeforeRowNormalization() {
        val anchor = TimelinePanAnchor(40)
        anchor.record(40, 150, 200)
        assertEquals(40, anchor.index)
        assertEquals(75, anchor.offset(100))
        assertEquals(300, anchor.offset(400))
        anchor.record(41, 25, 100)
        assertEquals(41, anchor.index)
        assertEquals(50, anchor.offset(200))
    }

    @Test
    fun distantNavigationApproachesFromTheDirectionOfTravel() {
        assertEquals(102, timelineApproachIndex(500, 100, 1000))
        assertEquals(498, timelineApproachIndex(100, 500, 1000))
        assertEquals(2, timelineApproachIndex(500, 0, 1000))
        assertEquals(998, timelineApproachIndex(100, 1000, 1000))
    }

    @Test
    fun nowDirectionUsesGroupedAxisAndViewportEdges() {
        val axis = TimelineAxis(90f, listOf(555 to 600), groupEmpty = true)
        assertEquals(TodayDirection.Down, timelineTodayDirection(axis, 600, 0, 100))
        assertEquals(TodayDirection.Up, timelineTodayDirection(axis, 0, 100, 100))
        assertNull(timelineTodayDirection(axis, 555, 100, 100))
        assertNull(timelineTodayDirection(axis, 0, 0, 100))
        assertNull(timelineTodayDirection(axis, 1440, 234, 100))
        assertNull(timelineTodayDirection(axis, 600, 0, 0))
    }
}
