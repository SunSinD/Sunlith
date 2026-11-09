package io.github.sunsind.sunlith.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAxisTest {
    private val hourPx = 90f

    @Test
    fun groupingDefaultsOffAndKeepsEveryHour() {
        val axis = TimelineAxis(hourPx, listOf(555 to 600))
        assertEquals(2160f, axis.totalHeight, 0.001f)
        assertTrue(axis.gaps.isEmpty())
        assertEquals((0..24).map { it * 60 }, axis.normalHourMarkers)
        assertEquals(832.5f, axis.minuteToY(555), 0.001f)
    }

    @Test
    fun entirelyEmptyDaysKeepTheFullAxisForAddingEvents() {
        val axis = TimelineAxis(hourPx, emptyList(), groupEmpty = true)
        assertEquals(2160f, axis.totalHeight, 0.001f)
        assertTrue(axis.gaps.isEmpty())
        assertEquals(25, axis.normalHourMarkers.size)
    }

    @Test
    fun groupsLeadingAndTrailingHoursWithOneHourContext() {
        val axis = TimelineAxis(hourPx, listOf(555 to 600), groupEmpty = true)
        assertEquals(listOf(0 to 480, 660 to 1440), axis.gaps.map { it.startMinute to it.endMinute })
        assertEquals(334f, axis.totalHeight, 0.001f)
        assertEquals(32f, axis.minuteToY(480), 0.001f)
        assertEquals(302f, axis.minuteToY(660), 0.001f)
        assertEquals(listOf(540, 600), axis.normalHourMarkers)
    }

    @Test
    fun groupsInteriorRunsAcrossTheUnionOfVisibleDays() {
        val axis = TimelineAxis(hourPx, listOf(60 to 120, 600 to 660), groupEmpty = true)
        assertEquals(listOf(180 to 540, 720 to 1440), axis.gaps.map { it.startMinute to it.endMinute })
        val gap = axis.gaps.first()
        assertEquals(270f, gap.y, 0.001f)
        assertEquals(32f, gap.height, 0.001f)
        assertEquals(360f, axis.yToMinute(gap.y + gap.height / 2f), 0.001f)
        assertEquals(604f, axis.totalHeight, 0.001f)
    }

    @Test
    fun oneAndTwoHourEmptyRunsRemainAtNormalScale() {
        val axis = TimelineAxis(hourPx, listOf(60 to 120, 360 to 420), groupEmpty = true)
        assertFalse(axis.gaps.any { it.startMinute < 480 })
        assertEquals(180f, axis.minuteToY(300) - axis.minuteToY(180), 0.001f)
        assertTrue(240 in axis.normalHourMarkers)
    }

    @Test
    fun overlappingAndRepeatedSpansHaveTheSameAxisAsTheirUnion() {
        val separate = TimelineAxis(hourPx, listOf(270 to 330, 300 to 420, 270 to 330), groupEmpty = true)
        val merged = TimelineAxis(hourPx, listOf(270 to 420), groupEmpty = true)
        assertEquals(merged.gaps, separate.gaps)
        assertEquals(merged.totalHeight, separate.totalHeight, 0.001f)
    }

    @Test
    fun midnightAndEndOfDayEventsRemainOutsideCollapsedHours() {
        val axis = TimelineAxis(hourPx, listOf(-30 to 30, 1410 to 1500), groupEmpty = true)
        assertEquals(listOf(120 to 1320), axis.gaps.map { it.startMinute to it.endMinute })
        assertEquals(0f, axis.minuteToY(0), 0.001f)
        assertEquals(axis.totalHeight, axis.minuteToY(1440), 0.001f)
        assertTrue(0 in axis.normalHourMarkers)
        assertTrue(1440 in axis.normalHourMarkers)
    }

    @Test
    fun pointEventsProtectTheirHourAndContext() {
        val axis = TimelineAxis(hourPx, listOf(600 to 600), groupEmpty = true)
        assertEquals(listOf(0 to 540, 720 to 1440), axis.gaps.map { it.startMinute to it.endMinute })
        assertTrue(600 in axis.normalHourMarkers)
    }

    @Test
    fun mappingIsStrictlyIncreasingAndReversibleIncludingInsideGaps() {
        val axis = TimelineAxis(hourPx, listOf(60 to 120, 600 to 660), groupEmpty = true)
        var previous = -1f
        for (minute in 0..1440) {
            val y = axis.minuteToY(minute)
            assertTrue("Minute $minute must follow the previous minute", y > previous)
            assertEquals(minute.toFloat(), axis.yToMinute(y), 0.01f)
            previous = y
        }
        for (pixel in 0..axis.totalHeight.toInt()) {
            assertEquals(pixel.toFloat(), axis.minuteToY(axis.yToMinute(pixel.toFloat())), 0.01f)
        }
    }

    @Test
    fun mappingClampsBothEndsAndGapHeightNeverExpandsTime() {
        val axis = TimelineAxis(hourPx, listOf(555 to 600), groupEmpty = true, gapPx = 5000f)
        assertEquals(0f, axis.minuteToY(-100), 0.001f)
        assertEquals(axis.totalHeight, axis.minuteToY(2000), 0.001f)
        assertEquals(0f, axis.yToMinute(-100f), 0.001f)
        assertEquals(1440f, axis.yToMinute(5000f), 0.001f)
        assertEquals(2160f, axis.totalHeight, 0.001f)
    }
}
