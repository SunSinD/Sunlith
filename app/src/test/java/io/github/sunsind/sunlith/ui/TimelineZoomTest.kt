package io.github.sunsind.sunlith.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
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
}
