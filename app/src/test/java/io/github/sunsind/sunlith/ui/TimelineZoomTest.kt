package io.github.sunsind.sunlith.ui

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineZoomTest {
    @Test
    fun minimumLabelsKeepAWholeTextLineAfterPixelRoundedPadding() {
        for (scale in listOf(1f, 1.5f, 1.764f, 1.846f, 2f, 2.5f, 2.625f, 2.75f, 3f, 3.5f)) {
            with(Density(scale)) {
                val removedPixels = 2 * 1.dp.roundToPx() + 2 * 3.dp.roundToPx()
                for (linePixels in 1..180) {
                    val reserved = timelineMinimumBlockHeight(linePixels.toDp(), this).roundToPx()
                    assertTrue("A title must survive actual padding at density=$scale, line=$linePixels, block=$reserved",
                        reserved - removedPixels >= linePixels)
                    assertTrue(reserved >= 24.dp.roundToPx())
                }
            }
        }
    }

    @Test
    fun pinchFollowsTheFingersInsideTheRangeAndResistsPastIt() {
        assertEquals(150f, pinchZoomPercent(100f, 1.5f), 0.001f)
        assertEquals(50f, pinchZoomPercent(100f, 0.5f), 0.001f)
        val over = pinchZoomPercent(150f, 2f)
        assertTrue(over > 200f && over < 300f)
        assertEquals(200f + 100f * 0.3f, over, 0.001f)
        val under = pinchZoomPercent(60f, 0.5f)
        assertTrue(under < 50f && under > 30f)
        assertEquals(250f, pinchZoomPercent(200f, 100f), 0.001f)
        assertEquals(37.5f, pinchZoomPercent(50f, 0.01f), 0.001f)
    }

    @Test
    fun aNewFingerPairContinuesFromTheShownScaleWithoutAJump() {
        for (live in listOf(37.5f, 42f, 50f, 100f, 200f, 215f, 250f)) {
            assertEquals(live, pinchZoomPercent(unbandedZoomPercent(live), 1f), 0.001f)
        }
        assertTrue(pinchZoomPercent(unbandedZoomPercent(215f), 1.1f) > 215f)
        assertTrue(pinchZoomPercent(unbandedZoomPercent(42f), 0.9f) < 42f)
    }

    @Test
    fun releaseSettlesInsideTheSavedRangeInFivePercentSteps() {
        assertEquals(200, settledZoomPercent(236f))
        assertEquals(50, settledZoomPercent(38f))
        assertEquals(135, settledZoomPercent(137.4f))
        assertEquals(140, settledZoomPercent(137.6f))
        assertEquals(100, settledZoomPercent(100f))
    }

    @Test
    fun liveAndSavedZoomShareOneScaleSoTheSettleNeverJumps() {
        for (percent in listOf(50, 75, 100, 135, 200)) assertEquals(timelineHourHeight(percent), timelineHourHeight(percent.toFloat()))
        assertEquals(64.dp * 2.5f, timelineHourHeight(250f))
        assertEquals(timelineHourHeight(200), timelineHourHeight(999))
    }

    @Test
    fun focalMinuteStaysUnderTheFingersWhileTheScaleChanges() {
        val viewport = 1_000f
        val focalY = 400f
        val before = TimelineAxis(64f, emptyList())
        val minute = before.yToMinute(300f + focalY)
        for (hourPx in listOf(32f, 80f, 128f, 160f)) {
            val after = TimelineAxis(hourPx, emptyList())
            val scroll = focalScroll(after, minute, focalY, after.totalHeight, viewport)
            if (scroll > 0f && scroll < after.totalHeight - viewport) assertEquals(minute, after.yToMinute(scroll + focalY), 0.01f)
        }
    }

    @Test
    fun focalMinuteHoldsAcrossGroupedEmptyHours() {
        val occupied = listOf(540 to 600, 900 to 960)
        val after = TimelineAxis(120f, occupied, groupEmpty = true, gapPx = 48f)
        val scroll = focalScroll(after, 930f, 200f, after.totalHeight, 300f)
        assertEquals(930f, after.yToMinute(scroll + 200f), 0.01f)
    }

    @Test
    fun focalScrollStopsAtTheContentEdgesInsteadOfShowingBlankSpace() {
        val axis = TimelineAxis(32f, emptyList())
        assertEquals(0f, focalScroll(axis, 10f, 500f, axis.totalHeight, 600f), 0.001f)
        assertEquals(axis.totalHeight - 600f, focalScroll(axis, 1_439f, 0f, axis.totalHeight, 600f), 0.001f)
        assertEquals(0f, focalScroll(axis, 720f, 0f, 500f, 600f), 0.001f)
    }

    @Test
    fun hourTilesSkipGroupedEmptyBandsButKeepEveryOtherHour() {
        assertEquals((0 until 24).toList(), timelineTileHours(TimelineAxis(64f, emptyList())))
        val grouped = TimelineAxis(64f, listOf(540 to 600), groupEmpty = true)
        val tiles = timelineTileHours(grouped)
        grouped.gaps.forEach { gap ->
            for (h in gap.startMinute / 60 until gap.endMinute / 60) assertFalse(h in tiles)
        }
        assertTrue(9 in tiles)
        assertEquals(24, tiles.size + grouped.gaps.sumOf { (it.endMinute - it.startMinute) / 60 })
    }

    @Test
    fun todayTintIsStrongerThanTheGridAndDistinctFromTheSelectionAccent() {
        for (palette in listOf(DarkPalette, LightPalette)) {
            val colors = timelineGridColors(palette)
            assertTrue(colors.today != colors.tile)
            assertTrue(colors.today != palette.accent)
            assertEquals(palette.accent, colors.now)
        }
    }

    /** Every value the release glide shows between [from] and its settled zoom, sampled each 60 Hz frame. */
    private fun glide(from: Float, to: Float): List<Float> {
        val animation = TargetBasedAnimation(timelineZoomSettleSpec(), Float.VectorConverter, from, to)
        val frame = 16_666_667L
        return (0..(animation.durationNanos / frame + 1)).map { animation.getValueFromNanos(it * frame) }
    }

    @Test
    fun aPinchReleasedPastEitherLimitGlidesBackWithoutDippingPastTheLimit() {
        for ((from, limit) in listOf(250f to 200, 236f to 200, 201f to 200, 37.5f to 50, 44f to 50)) {
            val target = settledZoomPercent(from)
            assertEquals(limit, target)
            val values = glide(from, target.toFloat())
            assertEquals("The glide ends exactly on the saved zoom", target.toFloat(), values.last(), 0.001f)
            values.zipWithNext().forEach { (a, b) ->
                assertTrue("$from: the scale moves one way only, never a bounce ($a then $b)",
                    if (from > target) b <= a + 0.001f else b >= a - 0.001f)
            }
            assertTrue("$from: no frame passes the limit on the inside", values.all { if (from > target) it >= target - 0.001f else it <= target + 0.001f })
        }
    }

    @Test
    fun anInRangeReleaseAlsoSettlesWithoutOvershoot() {
        val values = glide(137.4f, settledZoomPercent(137.4f).toFloat())
        assertTrue(values.all { it >= 135f - 0.001f && it <= 137.4f + 0.001f })
    }

    @Test
    fun anEmptyDayEveningEndsWithOnlyRoomForTheMidnightLabel() {
        val grid = 24 * 64f
        val label = 11f
        val clearance = 104f
        assertEquals("No cards: no tall blank footer", grid + label, timelineContentHeight(grid, 0f, label, clearance), 0.001f)
        val tenPm = 22 * 64f
        assertEquals("A card ending at 10 PM is already far above the floating controls",
            grid + label, timelineContentHeight(grid, tenPm, label, clearance), 0.001f)
    }

    /** What timelinePosition draws on the first frame of the new grid: rounded and kept inside the content. */
    private fun placed(anchor: TimelineAnchor, axis: TimelineAxis, top: Float, viewport: Float): Int =
        anchor.scrollOn(axis, top).roundToInt().coerceIn(0, (axis.totalHeight + top * 2 - viewport).roundToInt().coerceAtLeast(0))

    @Test
    fun anUnchangedGridKeepsEveryScrollIncludingTheEdgeRoomAboveMidnight() {
        val axis = TimelineAxis(64f, listOf(540 to 600), groupEmpty = true, gapPx = 48f)
        val top = 12f
        for (scroll in listOf(0f, 1f, 6f, 11f, 12f, 13f, 200f, top + axis.totalHeight - 1f, top + axis.totalHeight + 9f)) {
            assertEquals("Scroll $scroll", scroll, timelineAnchor(axis, top, scroll).scrollOn(axis, top), 0.01f)
        }
    }

    @Test
    fun aRegroupKeepsTheLeadingEdgeRoomInsteadOfSnappingToMidnight() {
        val before = TimelineAxis(64f, listOf(540 to 600), groupEmpty = true, gapPx = 48f)
        val after = TimelineAxis(64f, listOf(120 to 180, 540 to 600), groupEmpty = true, gapPx = 48f)
        assertTrue(before.gaps != after.gaps)
        for (scroll in listOf(0f, 5f, 12f)) {
            assertEquals(scroll, timelineAnchor(before, 12f, scroll).scrollOn(after, 12f), 0.01f)
        }
    }

    @Test
    fun aRescaleKeepsTheMinuteAtThePhysicalTopOfTheViewport() {
        val top = 12f
        val before = TimelineAxis(64f, emptyList())
        val after = TimelineAxis(128f, emptyList())
        val scroll = top + before.minuteToY(600f)
        val shown = timelineAnchor(before, top, scroll).scrollOn(after, top)
        assertEquals("10:00 stays at the top edge", 600f, after.yToMinute(shown - top), 0.01f)
        // The old remap read the content offset as a grid offset, which moves the top by the edge room times the scale change.
        val raw = after.minuteToY(before.yToMinute(scroll))
        assertTrue(abs(after.yToMinute(raw - top) - 600f) > 3f)
    }

    /**
     * Ending a pinch clears the frozen packing, so empty hours can regroup between the top of the screen and the
     * fingers. Here the empty band before the afternoon starts two hours later once it settles.
     */
    @Test
    fun aSettledPinchKeepsTheMinuteUnderTheFingersWhenEmptyHoursRegroupAboveThem() {
        val top = 12f
        val viewport = 400f
        val focalY = 300f
        val frozen = TimelineAxis(100f, listOf(120 to 180, 840 to 900), groupEmpty = true, gapPx = 48f)
        val settled = TimelineAxis(100f, listOf(120 to 300, 840 to 900), groupEmpty = true, gapPx = 48f)
        assertEquals(240, frozen.gaps.first().startMinute)
        assertEquals(360, settled.gaps.first().startMinute)
        val scroll = top + frozen.minuteToY(180f)
        val anchor = timelineAnchor(frozen, top, scroll, focalY)
        val fingers = frozen.yToMinute(scroll + focalY - top)
        assertEquals(fingers, anchor.minute, 0.01f)
        val first = placed(anchor, settled, top, viewport)
        assertEquals("The first settled frame draws the same minute under the fingers", fingers,
            settled.yToMinute(first + focalY - top), 1f)
        val topOnly = placed(timelineAnchor(frozen, top, scroll), settled, top, viewport)
        assertTrue("Keeping only the top minute would move the fingers' minute by hours",
            abs(settled.yToMinute(topOnly + focalY - top) - fingers) > 60f)
    }

    @Test
    fun theSettledHandoffKeepsADragThatAlreadyStartedAndIsUsedOnce() {
        val axis = TimelineAxis(64f, emptyList())
        val top = 12f
        val zoom = TimelineZoom()
        zoom.live = 150f
        zoom.frozen = 100
        zoom.finish(timelineAnchor(axis, top, 400f, 250f), 400)
        assertEquals(null, zoom.live)
        assertEquals(null, zoom.frozen)
        val handoff = zoom.takeHandoff(418)
        assertEquals("A finger that dragged 18 px keeps its drag", 418f, handoff!!.scrollOn(axis, top), 0.01f)
        assertEquals("The handoff never replays", null, zoom.takeHandoff(418))
        zoom.finish()
        assertEquals("A finish without an anchor leaves nothing to restore", null, zoom.takeHandoff(0))
    }

    @Test
    fun aLateCardStillScrollsAboveTheFloatingControls() {
        val grid = 24 * 64f
        assertEquals("A card ending at midnight keeps the whole clearance below it",
            grid + 104f, timelineContentHeight(grid, grid, 11f, 104f), 0.001f)
        val pastMidnight = grid + 30f
        assertEquals("A one line card at 23:59 that runs past midnight counts only once",
            pastMidnight + 104f, timelineContentHeight(grid, pastMidnight, 11f, 104f), 0.001f)
    }
}
