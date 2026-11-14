package io.github.sunsind.sunlith.ui

import io.github.sunsind.sunlith.data.CalendarEvent
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TimelinePackingTest {
    @Test
    fun zoomUsesTheSameCompactTimeScaleForBothTimelines() {
        assertEquals(32.dp, timelineHourHeight(50))
        assertEquals(64.dp, timelineHourHeight(100))
        assertEquals(128.dp, timelineHourHeight(200))
        assertEquals(32.dp, timelineHourHeight(-1))
        assertEquals(128.dp, timelineHourHeight(999))
    }

    private val date = LocalDate.of(2026, 11, 5)
    private val zone = ZoneId.of("America/Toronto")

    private fun event(id: Long, from: Int, to: Int) = CalendarEvent(
        id, 1, "Event $id", "", "",
        date.atStartOfDay(zone).plusMinutes(from.toLong()).toInstant().toEpochMilli(),
        date.atStartOfDay(zone).plusMinutes(to.toLong()).toInstant().toEpochMilli(),
        false, zone.id,
    )

    /** The cards Day draws: one per event, except one per compact group. */
    private fun cards(clusters: List<TimelineCluster>) = clusters.flatMap { it.blocks }.filter { it.group == null || it === it.group!!.first() }

    @Test
    fun dayLabelsThatWouldCollideShareOneCompactCardInsteadOfFalseSideBySideLanes() {
        val clusters = packTimeline(listOf(event(1, 1080, 1095), event(2, 1100, 1105)), date, zone, 30,
            readableLabels = true)
        val cluster = clusters.single()
        assertEquals("Events one after another are not drawn as overlapping columns", 1, cluster.cols)
        assertEquals(listOf(1080, 1100), cluster.blocks.map { it.start })
        assertEquals("Every event keeps its real end", listOf(1095, 1105), cluster.blocks.map { it.realEnd })
        val card = cards(clusters).single()
        assertEquals(listOf(1L, 2L), card.group!!.map { it.event.id })
        assertEquals("The card starts at the first real start", 1080, card.cardStart)
        assertEquals("The card is one title line tall, not one line per event", 1080 + 30, card.end)
        assertEquals("The longest event names the card", 1L, timelineGroupHead(card.group!!).event.id)
    }

    /** The user's screenshot: three 1 minute events at 18:00, 18:15 and 18:20, then an ordinary 19:00 class. */
    @Test
    fun oneMinuteEventsAtSixPmShareOneCompactCardAtEveryTextSize() {
        val trio = listOf(event(1, 1080, 1081), event(2, 1095, 1096), event(3, 1100, 1101))
        val later = event(4, 1140, 1200)
        for (minimum in listOf(28, 30, 53)) {
            val clusters = packTimeline(trio + later, date, zone, minimum, readableLabels = true)
            assertEquals("$minimum: the 19:00 class is not pulled into the group", 2, clusters.size)
            val group = clusters.first()
            assertEquals("$minimum: one lane, never a diagonal staircase", 1, group.cols)
            assertTrue(group.blocks.all { it.col == 0 && it.span == 1 })
            assertEquals(listOf(1L, 2L, 3L), group.blocks.map { it.event.id })
            assertEquals("$minimum: real starts are kept", listOf(1080, 1095, 1100), group.blocks.map { it.start })
            assertEquals("$minimum: real ends are kept", listOf(1081, 1096, 1101), group.blocks.map { it.realEnd })
            val card = cards(listOf(group)).single()
            assertEquals("$minimum: all three share one card", 3, card.group!!.size)
            assertEquals(1080, card.cardStart)
            assertEquals("$minimum: one title line, not three rows", 1080 + minimum, card.end)
            assertEquals(1L, timelineGroupHead(card.group!!).event.id)
            val after = cards(listOf(clusters.last())).single()
            assertEquals("$minimum: the class keeps its own full width card at its real time", 1140 to 1200, after.start to after.end)
            assertNull(after.group)
            assertEquals(1, clusters.last().cols)
        }
        // Week keeps its exact short blocks: no group and no extra lanes.
        val week = packTimeline(trio, date, zone, 30)
        assertTrue(week.flatMap { it.blocks }.all { it.group == null })
        assertTrue(week.all { it.cols == 1 })
    }

    @Test
    fun genuinelySimultaneousShortEventsStaySideBySideCards() {
        val items = (1..3).map { event(it.toLong(), 1080, 1081) }
        for (minimum in listOf(30, 53)) {
            val cluster = packTimeline(items, date, zone, minimum, readableLabels = true).single()
            assertEquals(3, cluster.cols)
            assertEquals(listOf(0, 1, 2), cluster.blocks.map { it.col }.sorted())
            assertTrue("Events at the same minute really overlap and keep their own cards", cluster.blocks.all { it.group == null })
            assertTrue(cluster.blocks.all { it.start == 1080 && it.realEnd == 1081 && it.end == 1080 + minimum })
        }
    }

    @Test
    fun shortLabelsInsideARealOverlapGroupOnlyInTheirOwnLane() {
        // 9:00 to 10:00 overlaps a 9:00 to 9:10 event, then a 9:15 event; the 9:15 title must not open a third lane.
        val items = listOf(event(1, 540, 600), event(2, 540, 550), event(3, 555, 556))
        assertEquals("Week needs only the two real lanes", 2, packTimeline(items, date, zone, 30).single().cols)
        for (minimum in listOf(30, 53)) {
            val cluster = packTimeline(items, date, zone, minimum, readableLabels = true).single()
            assertEquals("$minimum: two real lanes", 2, cluster.cols)
            val byId = cluster.blocks.associateBy { it.event.id }
            val long = byId.getValue(1)
            assertNull("$minimum: the long class keeps its own card", long.group)
            assertEquals(0, long.col)
            assertEquals(540 to 600, long.start to long.end)
            assertEquals(1, long.span)
            val short = byId.getValue(2)
            assertEquals(1, short.col)
            assertEquals(listOf(2L, 3L), short.group!!.map { it.event.id })
            assertEquals(1, byId.getValue(3).col)
            assertEquals(540 + minimum, short.end)
            assertEquals(listOf(540, 555), short.group!!.map { it.start })
            assertEquals(listOf(550, 556), short.group!!.map { it.realEnd })
        }
        assertDayInvariants(items, 30)
    }

    @Test
    fun backToBackHalfHoursStayFullWidthAtNormalTextAndShareACardOnlyWhenTitlesCannotFit() {
        val items = listOf(event(1, 600, 630), event(2, 630, 660))
        for (minimum in listOf(28, 30)) {
            val clusters = packTimeline(items, date, zone, minimum, readableLabels = true)
            assertEquals(2, clusters.size)
            assertTrue(clusters.flatMap { it.blocks }.all { it.group == null })
            assertTrue(clusters.all { it.cols == 1 })
        }
        val large = cards(packTimeline(items, date, zone, 45, readableLabels = true)).single()
        assertEquals("At very large text the two titles share one card", listOf(600, 630), large.group!!.map { it.start })
        assertEquals("The card ends at the real end, not one line per event", 660, large.end)
    }

    @Test
    fun aGroupTakesOnlyEventsThatStartInsideItsTitleLine() {
        val items = listOf(event(1, 540, 541), event(2, 550, 551), event(3, 580, 581), event(4, 640, 700))
        val clusters = packTimeline(items, date, zone, 30, readableLabels = true)
        val shown = cards(clusters)
        assertEquals(3, shown.size)
        assertEquals("Only 9:10 starts before the 9:00 title line ends", listOf(1L, 2L), shown[0].group!!.map { it.event.id })
        assertEquals(570, shown[0].end)
        assertNull("9:40 starts after that line and keeps its own card", shown[1].group)
        assertEquals(580 to 610, shown[1].start to shown[1].end)
        assertEquals("The 10:40 class keeps its real full width card", 640 to 700, shown[2].start to shown[2].end)
        assertTrue(clusters.all { it.cols == 1 })
    }

    @Test
    fun aClassRightAfterAOneMinuteReminderStaysNamedOnTheCard() {
        val card = cards(packTimeline(listOf(event(1, 539, 540), event(2, 540, 630)), date, zone, 30, readableLabels = true)).single()
        assertEquals(listOf(1L, 2L), card.group!!.map { it.event.id })
        assertEquals(539 to 630, card.cardStart to card.end)
        assertEquals("The class, not the reminder, names the card", 2L, timelineGroupHead(card.group!!).event.id)
    }

    @Test
    fun aTitleThatWouldReachALaterEventJoinsAFreeLaneInsteadOfOpeningALane() {
        // Two short simultaneous events at 9:00, then a 9:15 event that really overlaps neither.
        val items = listOf(event(1, 540, 550), event(2, 540, 545), event(3, 555, 600))
        val cluster = packTimeline(items, date, zone, 30, readableLabels = true).single()
        assertEquals("Only the two real lanes", 2, cluster.cols)
        val byId = cluster.blocks.associateBy { it.event.id }
        assertEquals(listOf(1L, 3L), byId.getValue(1).group!!.map { it.event.id })
        assertEquals("The 45 minute event names its card", 3L, timelineGroupHead(byId.getValue(1).group!!).event.id)
        assertEquals(600, byId.getValue(1).end)
        assertNull(byId.getValue(2).group)
        assertEquals(570, byId.getValue(2).end)
        assertDayInvariants(items, 30)
    }

    /**
     * Day packing on many generated schedules and title heights: every event keeps its real start and end, a card is
     * one title line or its real span, real overlaps never share a lane, the lanes never exceed the real overlaps,
     * and no two painted cards cover each other.
     */
    @Test
    fun dayPackingKeepsRealTimesAndNeverInventsLanesOnGeneratedSchedules() {
        val random = java.util.Random(20261113L)
        repeat(400) { round ->
            val disjoint = round % 2 == 0
            val items = mutableListOf<CalendarEvent>()
            var minute = random.nextInt(120)
            for (id in 1..(1 + random.nextInt(8))) {
                val length = if (random.nextInt(3) == 0) 1 + random.nextInt(3) else 1 + random.nextInt(90)
                if (minute + length >= 24 * 60) break
                items += event(id.toLong(), minute, minute + length)
                minute += if (disjoint) length + random.nextInt(40) else random.nextInt(30)
            }
            if (items.isEmpty()) return@repeat
            for (minimum in listOf(1, 15, 28, 30, 53, 90)) {
                val clusters = assertDayInvariants(items, minimum)
                if (disjoint) assertTrue("Events that never overlap never become columns: $minimum", clusters.all { it.cols == 1 })
            }
        }
    }

    private fun assertDayInvariants(items: List<CalendarEvent>, minimum: Int): List<TimelineCluster> {
        fun minuteOf(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(zone).let { it.hour * 60 + it.minute }
        val clusters = packTimeline(items, date, zone, minimum, readableLabels = true)
        val blocks = clusters.flatMap { it.blocks }
        assertEquals("Every event appears once", items.map { it.id }.sorted(), blocks.map { it.event.id }.sorted())
        blocks.forEach { b ->
            val real = items.single { it.id == b.event.id }
            assertEquals("Real start of ${b.event.id}", minuteOf(real.startMillis), b.start)
            assertEquals("Real end of ${b.event.id}", maxOf(minuteOf(real.endMillis), b.start + 1), b.realEnd)
            assertTrue(b.end >= b.realEnd)
        }
        clusters.forEach { cluster ->
            // The lanes equal the most events that really happen at one moment.
            val peak = cluster.blocks.maxOf { b -> cluster.blocks.count { it.start <= b.start && b.start < it.realEnd } }
            assertEquals("No false lanes ($minimum): ${cluster.blocks.map { it.start to it.realEnd }}", peak, cluster.cols)
            for (a in cluster.blocks) for (b in cluster.blocks) {
                if (a !== b && a.start < b.realEnd && b.start < a.realEnd) assertTrue("Real overlaps need separate lanes", a.col != b.col)
            }
            val shown = cards(listOf(cluster))
            shown.forEach { card ->
                val members = card.group ?: listOf(card)
                assertTrue(members.all { it.col == card.col && it.span == card.span && it.end == card.end })
                assertEquals("A card is one title line or its real span, never one line per event ($minimum)",
                    maxOf(card.cardStart + minimum, members.maxOf { it.realEnd }), card.end)
                if (card.group != null) assertTrue("Only events that start inside the first title line share a card",
                    members.all { it.start < card.cardStart + minimum })
                assertTrue(card.col + card.span <= cluster.cols)
            }
            for (a in shown) for (b in shown) {
                if (a !== b && a.cardStart < b.end && b.cardStart < a.end) {
                    assertTrue("Painted cards ${a.event.id} and ${b.event.id} must not cover each other",
                        a.col + a.span <= b.col || b.col + b.span <= a.col)
                }
            }
        }
        clusters.zipWithNext().forEach { (a, b) -> assertTrue("Separate groups never cover each other", a.end <= b.start) }
        return clusters
    }

    @Test
    fun onlyNarrowCardsBesideOtherCardsCenterTheirText() {
        assertTrue("Five Day meetings in a phone column", timelineCentersLabel(330.dp / 5, 330.dp))
        assertTrue("Two lanes in a five day Week column", timelineCentersLabel(40.dp, 80.dp))
        assertFalse("An ordinary Day card keeps start aligned text", timelineCentersLabel(330.dp, 330.dp))
        assertFalse("A lone narrow Week card keeps start aligned text", timelineCentersLabel(48.dp, 48.dp))
        assertFalse("Two wide Day lanes keep start aligned text", timelineCentersLabel(165.dp, 330.dp))
    }

    @Test
    fun dayLabelsReserveRoomBelowMidnightWithoutMovingTheirStartOrTheClock() {
        val block = packTimeline(listOf(event(1, 1439, 1440)), date, zone, 30,
            readableLabels = true).single().blocks.single()
        assertEquals(1439, block.start)
        assertEquals(1469, block.end)
        val axis = TimelineAxis(64f, listOf(block.start to block.end))
        assertEquals(1536f, axis.minuteToY(1440), 0.01f)
        assertEquals(1439 / 60f * 64f, axis.minuteToY(block.start), 0.01f)
    }

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
    fun nowDirectionUsesGroupedAxisAndViewportEdges() {
        val axis = TimelineAxis(90f, listOf(555 to 600), groupEmpty = true)
        assertEquals(TodayDirection.Down, timelineTodayDirection(axis, 600, 0, 100))
        assertEquals(TodayDirection.Up, timelineTodayDirection(axis, 0, 100, 100))
        assertNull(timelineTodayDirection(axis, 555, 100, 100))
        assertNull(timelineTodayDirection(axis, 0, 0, 100))
        assertNull(timelineTodayDirection(axis, 1440, 234, 100))
        assertNull(timelineTodayDirection(axis, 600, 0, 0))
    }

    /** The grid starts [top] px into the scrolling content, so the now line is drawn that much lower. */
    @Test
    fun nowDirectionCountsTheRoomAboveMidnight() {
        val axis = TimelineAxis(60f, emptyList())
        val top = 20f
        assertNull("Drawn at 620, inside 610..710", timelineTodayDirection(axis, 600, 610, 100, top))
        assertEquals("Without the room it would wrongly point up", TodayDirection.Up, timelineTodayDirection(axis, 600, 610, 100))
        assertEquals("Drawn at 620, below 500..600", TodayDirection.Down, timelineTodayDirection(axis, 600, 500, 100, top))
        assertNull(timelineTodayDirection(axis, 600, 500, 100))
        assertNull("Exactly on the bottom edge is still visible", timelineTodayDirection(axis, 600, 520, 100, top))
        assertEquals(TodayDirection.Down, timelineTodayDirection(axis, 600, 519, 100, top))
        assertNull("Exactly on the top edge is still visible", timelineTodayDirection(axis, 600, 620, 100, top))
        assertEquals(TodayDirection.Up, timelineTodayDirection(axis, 600, 621, 100, top))
        assertNull("Midnight at the very top, with the edge room showing", timelineTodayDirection(axis, 0, 0, 100, top))
        assertNull("Midnight just scrolled into the edge room", timelineTodayDirection(axis, 0, 15, 100, top))
        assertNull("The end of the day at the bottom of the content", timelineTodayDirection(axis, 1440, 24 * 60 + 40 - 100, 100, top))
    }

    /** Today lands where the target hour's events are drawn, top + axis y, not on the bare axis y. */
    @Test
    fun todayScrollPutsTheTargetHourAtTheTopOfTheViewport() {
        val top = 20f
        assertEquals(9 * 60, timelineTodayMinute(10 * 60 + 59))
        assertEquals(0, timelineTodayMinute(59))
        assertEquals(0, timelineTodayMinute(60 + 30))
        listOf(TimelineAxis(60f, emptyList()), TimelineAxis(60f, listOf(14 * 60 to 15 * 60), groupEmpty = true, gapPx = 40f)).forEach { axis ->
            val scroll = timelineTodayScroll(axis, 14 * 60, top)
            assertEquals("Drawn at the very top of the viewport", 0f, top + axis.minuteToY(14 * 60) - scroll, 0.01f)
            assertNull("The now line just after it stays on screen", timelineTodayDirection(axis, 14 * 60 + 70, scroll.toInt(), 200, top))
        }
        assertEquals("Midnight keeps the room above it", 0f, timelineTodayScroll(TimelineAxis(60f, emptyList()), 0, top), 0f)
    }

    @Test
    fun narrowWeekColumnsStackAnOverlapInsteadOfShowingSlivers() {
        val lane = timelineMinLaneWidth(12.dp)
        assertTrue("A lane must hold the start of a word, not one letter", lane >= 32.dp)
        val sevenDay = overflowLanes(48.dp, lane, 3)
        assertTrue("Three simultaneous events in a 48dp column become one readable group card", sevenDay.stacked)
        assertEquals(0, sevenDay.shown)
        assertEquals("The group card uses the whole column", 48.dp, sevenDay.chooser)
        val alone = overflowLanes(48.dp, lane, 1)
        assertFalse("A lone event keeps its own card", alone.stacked)
        assertEquals(1, alone.shown)
        assertEquals(48.dp, alone.lane)
        assertEquals(0.dp, alone.chooser)
    }

    @Test
    fun lanesThatFitStayReadableAndOnlyTheExtraEventsMoveToTheChooser() {
        for (width in listOf(64.dp, 120.dp, 330.dp)) for (cols in 1..12) {
            val lanes = overflowLanes(width, 32.dp, cols)
            assertFalse("A column with room for two lanes never stacks: $width $cols", lanes.stacked)
            assertTrue("Every shown lane stays readable: $width $cols ${lanes.lane}", lanes.lane >= 32.dp)
            assertTrue(lanes.shown in 1..cols)
            assertTrue("Lanes and chooser fit the column: $width $cols", lanes.lane * lanes.shown + lanes.chooser <= width + 0.01.dp)
            if (lanes.shown < cols) assertTrue("Hidden events keep a readable chooser", lanes.chooser >= 32.dp)
            else assertEquals("No chooser when every event fits", 0.dp, lanes.chooser)
        }
        val day = overflowLanes(330.dp, timelineMinLaneWidth(14.dp), 3)
        assertEquals("A phone Day shows three overlaps side by side", 3, day.shown)
        assertEquals(110.dp, day.lane)
    }

    @Test
    fun ordinaryDayEventsStayFullWidthAtTheirRealTimes() {
        // Normal 30 and 60 minute events, including a back to back pair, at the Day title reservation for 100% and 115% text.
        val items = listOf(event(1, 540, 600), event(2, 600, 630), event(3, 660, 720), event(4, 840, 870))
        for (minimum in listOf(27, 30)) {
            val clusters = packTimeline(items, date, zone, minimum, readableLabels = true)
            assertEquals("Every ordinary event is its own group ($minimum)", 4, clusters.size)
            clusters.forEach { cluster ->
                val block = cluster.blocks.single()
                assertEquals(1, cluster.cols)
                assertEquals(0, block.col)
                assertEquals(1, block.span)
                val lanes = overflowLanes(330.dp, timelineMinLaneWidth(16.1.dp), cluster.cols)
                assertFalse(lanes.stacked)
                assertEquals("A lone Day event uses the whole column", 330.dp, lanes.lane)
            }
            val blocks = clusters.flatMap { it.blocks }
            assertEquals(listOf(540, 600, 660, 840), blocks.map { it.start })
            assertEquals("No ordinary event is lengthened", listOf(600, 630, 720, 870), blocks.map { it.end })
        }
    }

    @Test
    fun threeToFiveSimultaneousEventsAreSeparateCardsOnDayAndOneGroupOnlyInANarrowWeek() {
        for (count in 3..5) {
            val items = (1..count).map { event(it.toLong(), 750, 810) } + event(9, 840, 900)
            for (readable in listOf(true, false)) {
                val clusters = packTimeline(items, date, zone, 30, readableLabels = readable)
                assertEquals(2, clusters.size)
                val together = clusters.first()
                assertEquals("$count simultaneous events need $count lanes", count, together.cols)
                assertEquals((0 until count).toList(), together.blocks.map { it.col }.sorted())
                assertTrue(together.blocks.all { it.start == 750 && it.end == 810 && it.span == 1 })
                assertEquals("The later event is not pulled into the group", 1, clusters.last().cols)
            }
            val day = overflowLanes(330.dp, timelineMinLaneWidth(16.1.dp), count)
            assertFalse("A phone Day never groups $count simultaneous events", day.stacked)
            assertEquals(count, day.shown)
            assertEquals(0.dp, day.chooser)
            val week = overflowLanes(48.dp, timelineMinLaneWidth(12.dp), count)
            assertTrue("A narrow seven day column groups $count simultaneous events", week.stacked)
            assertFalse("A lone event in the same column keeps its own card", overflowLanes(48.dp, timelineMinLaneWidth(12.dp), 1).stacked)
        }
    }

    @Test
    fun freeNeighbouringLanesWidenACardWithoutCoveringAnyOverlap() {
        val cluster = packTimeline(listOf(event(1, 540, 660), event(2, 540, 600), event(3, 540, 570), event(4, 600, 660)),
            date, zone, 15).single()
        assertEquals(3, cluster.cols)
        val byId = cluster.blocks.associateBy { it.event.id }
        assertEquals("The 10:00 card may use the lane freed at 9:30", 2, byId.getValue(4).span)
        assertEquals(1, byId.getValue(2).span)
        cluster.blocks.forEach { block ->
            assertTrue(block.span >= 1 && block.col + block.span <= cluster.cols)
            cluster.blocks.filter { it !== block && it.start < block.end && block.start < it.end }.forEach { other ->
                assertTrue("Card ${block.event.id} must not widen over ${other.event.id}", other.col !in block.col until block.col + block.span)
            }
        }
    }

    private val tenPerChar = { text: String -> text.length * 10 }

    @Test
    fun overlongWordsKeepTheirStartWithAnEllipsisInsteadOfBreakingIntoFragments() {
        assertEquals("A long word keeps its start on one line", "Elect\u2026 lab", fitWords("Electromagnetism lab", 60, measure = tenPerChar))
        assertEquals("Words that fit are untouched", "Digital lab", fitWords("Digital lab", 70, measure = tenPerChar))
        // A hyphen is a wrap point: the part before it fits and keeps its hyphen, only the long rest is shortened.
        assertEquals("Circuits-numériqu\u2026", fitWords("Circuits-numériques", 90, measure = tenPerChar))
    }

    @Test
    fun separatorsAndSpacingStayExactlyAsTyped() {
        for (text in listOf("Lab A  Lab B", "Line one\nLine two", "TP-labo/salle 4", "Lab\u00a0B12", "  Leading and trailing  ")) {
            assertEquals("Nothing that fits may change: [$text]", text, fitWords(text, 1_000, measure = tenPerChar))
        }
        assertEquals("Only the long word is shortened; the double space, line break, hyphen and slash stay",
            "Électroma\u2026  avancé\nTP-labo/sa",
            fitWords("Électromagnétisme  avancé\nTP-labo/sa", 100, measure = tenPerChar))
    }

    @Test
    fun emojiAndScriptsWithoutSpacesAreNeverCutIntoBrokenCharacters() {
        val scientist = "👩\u200d🔬"
        assertEquals("$scientist Lab", fitWords("$scientist Lab", 1_000, measure = tenPerChar))
        val tight = fitWords("$scientist${scientist}Lab", 40, measure = tenPerChar)
        assertTrue("A cut never leaves half a character: $tight", tight.codePoints().noneMatch { it in 0xD800..0xDFFF })
        assertTrue("A cut keeps the real start: $tight", tight.startsWith("👩"))
        val chinese = "数字电路实验课"
        assertEquals("Chinese wraps between characters normally", chinese, fitWords(chinese, 20, measure = tenPerChar))
        val japanese = "電子回路の実験"
        assertEquals(japanese, fitWords(japanese, 20, java.util.Locale.JAPANESE, tenPerChar))
    }

    @Test
    fun largeTextWeeksScrollSidewaysInsteadOfSqueezingColumns() {
        val phone = 412.dp - 60.dp
        assertTrue(phone / 7 < timelineOverflowThreshold(7, 24.dp, 2f))
        assertTrue((280.dp - 48.dp) / 5 < timelineOverflowThreshold(5, 20.dp, 1.5f))
        for (days in listOf(3, 5, 7)) {
            assertTrue(timelineOverflowThreshold(days, 13.8.dp, 1.15f) <= timelineOverflowThreshold(days, 24.dp, 2f))
        }
    }

    /** The user's crowded Friday: classes, a marker, three simultaneous labs, and a 15, 5 and 5 minute run at 18:00. */
    private fun crowdedFriday() = listOf(
        event(1, 540, 660), event(2, 690, 720), event(3, 735, 825), event(4, 735, 825), event(5, 735, 825),
        event(6, 840, 900), event(7, 1080, 1095), event(8, 1095, 1100), event(9, 1100, 1105),
    )

    @Test
    fun crowdedDayKeepsEveryRealStartAndNeverStacksTwoShownEventsInOneLane() {
        for (readable in listOf(false, true)) for (minimum in listOf(15, 30, 53)) {
            val clusters = packTimeline(crowdedFriday(), date, zone, minimum, readableLabels = readable)
            val blocks = clusters.flatMap { it.blocks }
            assertEquals("Every event appears once ($readable, $minimum)", (1L..9L).toList(), blocks.map { it.event.id }.sorted())
            blocks.forEach { block ->
                val id = block.event.id.toInt()
                val real = crowdedFriday().single { it.id == block.event.id }
                val realStart = java.time.Instant.ofEpochMilli(real.startMillis).atZone(zone).let { it.hour * 60 + it.minute }
                val realEnd = java.time.Instant.ofEpochMilli(real.endMillis).atZone(zone).let { it.hour * 60 + it.minute }
                assertEquals("Event $id must start at its real minute", realStart, block.start)
                assertTrue("Event $id must never be drawn shorter than its real time", block.end >= realEnd)
            }
            clusters.forEach { cluster ->
                assertTrue(cluster.blocks.all { it.col in 0 until cluster.cols })
                // Events of one compact group share its single card; every other pair of painted cards needs its own lane.
                val shown = cards(listOf(cluster))
                for (a in shown) for (b in shown) {
                    if (a !== b && a.cardStart < b.end && b.cardStart < a.end) {
                        assertTrue("Shown cards ${a.event.id} and ${b.event.id} must not share a lane", a.col != b.col)
                    }
                }
            }
            if (readable) assertDayInvariants(crowdedFriday(), minimum)
            val labs = blocks.filter { it.event.id in 3L..5L }
            assertEquals("Three simultaneous labs need three lanes", 3, labs.map { it.col }.toSet().size)
            if (!readable || minimum <= 30) assertTrue("At normal text the labs stay side by side cards", labs.all { it.group == null })
            assertTrue("Week never groups on Day's title rule", readable || blocks.all { it.group == null })
            if (readable && minimum >= 30) {
                val shortRun = blocks.single { it.event.id == 7L }.group
                assertEquals("The 18:00, 18:15 and 18:20 run is one compact card, not false lanes ($minimum)",
                    listOf(7L, 8L, 9L), shortRun?.map { it.event.id })
                assertEquals("One title line, not three rows", 1080 + minimum, shortRun!!.first().end)
            }
        }
    }

    /** A fake one-line measure: every character is 10 px wide. */
    private fun width(text: String) = text.length * 10

    @Test
    fun aNarrowWeekCardShowsItsWholeRangeWhenItFitsOnOneLine() {
        assertEquals("8 - 10:45 a.m.", timelineCardTime(listOf("8 - 10:45 a.m.", "8 a.m."), 140, ::width))
    }

    /** The user's screenshot: "+1", "8 -", "10:45", "a.m." stacked like an equation in a narrow Week lane. */
    @Test
    fun aRangeTooWideForTheLaneFallsBackToItsWholeStartInsteadOfBreakingApart() {
        val shown = timelineCardTime(listOf("8 - 10:45 a.m.", "8 a.m."), 70, ::width)
        assertEquals("8 a.m.", shown)
    }

    @Test
    fun aLaneTooNarrowForAnyWholeTimeShowsNoTimeRatherThanFragments() {
        assertNull(timelineCardTime(listOf("11:40 a.m. - 1:25 p.m.", "11:40 a.m."), 60, ::width))
        for (lane in 0..300 step 5) {
            val shown = timelineCardTime(listOf("11:40 a.m. - 1:25 p.m.", "11:40 a.m."), lane, ::width)
            assertTrue("A shown time always fits on one line of the lane", shown == null || width(shown) <= lane)
        }
    }
}
