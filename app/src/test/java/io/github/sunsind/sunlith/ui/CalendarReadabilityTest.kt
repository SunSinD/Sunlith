package io.github.sunsind.sunlith.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.SchoolEventAbsence
import io.github.sunsind.sunlith.data.SchoolEventInfo
import io.github.sunsind.sunlith.data.SchoolEventPeriod
import io.github.sunsind.sunlith.school.SchoolClassLift
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarReadabilityTest {
    private val zone = ZoneId.of("America/Toronto")
    private val day = LocalDate.of(2026, 11, 2)

    private fun at(minute: Int) = day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun schoolEvent(from: Int, to: Int, absences: List<SchoolEventAbsence>, periods: List<SchoolEventPeriod>? = null) = CalendarEvent(
        1, 16, "Teacher absent: Musculation", "", "MON-B2410, MON-B2422; MON-DOUCHE", at(from), at(to), false, zone.id,
        school = SchoolEventInfo("109-KMB-MO", "00001", "key", "T", "01", "Musculation fonctionnelle avancée",
            periods ?: listOf(SchoolEventPeriod(1, at(from), at(to), "MON-B2410", "")), absences),
    )

    @Test
    fun everyCourseColorKeepsTextAndBoundaryContrastInBothThemes() {
        for (slot in 0 until COURSE_SLOTS) {
            val light = courseColors(slot, dark = false)
            val dark = courseColors(slot, dark = true)
            assertTrue("light text $slot", contrast(light.container, light.content) >= 4.5f)
            assertTrue("dark text $slot", contrast(dark.container, dark.content) >= 4.5f)
            assertTrue("light edge $slot", contrast(light.container, LightPalette.background) >= 3f)
            assertTrue("dark edge $slot", contrast(dark.container, DarkPalette.background) >= 3f)
        }
    }

    @Test
    fun courseSlotsAreDistinctAndIndependentOfOrder() {
        val keys = listOf("243-4A5-MO", "243-4B5-MO", "201-SN2-RE", "340-EKA-MO", "601-102-MQ", "243-4C5-MO", "109-KMB-MO", "243-4D5-MO")
        val slots = assignCourseSlots(keys)
        assertEquals(keys.size, slots.values.toSet().size)
        assertEquals(slots, assignCourseSlots(keys.reversed()))
        assertEquals(preferredCourseSlot("only"), assignCourseSlots(listOf("only")).getValue("only"))
        assertEquals(COURSE_SLOTS, assignCourseSlots((0 until COURSE_SLOTS).map { "c$it" }).values.toSet().size)
    }

    @Test
    fun savedCourseColorsSurviveChangingTheLoadedCourses() {
        val first = assignCourseSlots(listOf("course-a", "course-k"))
        val later = assignCourseSlots(listOf("new-course", "course-k"), first)
        assertEquals(first.getValue("course-a"), later.getValue("course-a"))
        assertEquals(first.getValue("course-k"), later.getValue("course-k"))
        assertEquals(2, listOf(later.getValue("new-course"), later.getValue("course-k")).toSet().size)
    }

    @Test
    fun historicalCoursesDoNotExhaustTheNewTermsColors() {
        val history = assignCourseSlots((0 until 20).map { "old-$it" })
        val current = listOf("new-a", "new-b", "new-c", "new-d", "new-e", "new-f", "new-g")
        val colors = assignCourseSlots(current, history)
        assertEquals(current.size, current.map { colors.getValue(it) }.toSet().size)
        history.forEach { (key, slot) -> assertEquals(slot, colors.getValue(key)) }
    }

    @Test
    fun sharedCampusPrefixIsDroppedOnlyWhenEveryRoomHasIt() {
        assertEquals("B2410, B2422, DOUCHE", compactRooms("MON-B2410, MON-B2422; MON-DOUCHE"))
        assertEquals("MON-B2410, LAV-A100", compactRooms("MON-B2410, LAV-A100"))
        assertEquals("ColNET", compactRooms("ColNET"))
        assertEquals("", compactRooms("  "))
    }

    @Test
    fun fullMonthKeepsChipsAtMinimumRowHeightsAcrossDensities() {
        for (scale in listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.125f, 2.375f, 2.625f, 2.75f, 3f, 4f)) {
            val density = Density(scale)
            for (number in listOf(26.6.dp, 49.4.dp, 53.2.dp)) {
                for (chip in listOf(20.dp, 22.4.dp, 24.8.dp, 32.dp, 36.dp)) {
                    for (slots in 1..3) {
                        val row = number + chip * slots + 10.dp
                        assertEquals("$slots chips at density $scale, number $number, chip $chip", slots,
                            fullMonthChipSlots(row, number, chip, density))
                    }
                }
            }
        }
    }

    @Test
    fun fullMonthDoesNotAllocateChipsBeyondAvailableHeight() {
        for (scale in listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.125f, 2.375f, 2.625f, 2.75f, 3f, 4f)) {
            val density = Density(scale)
            for (number in listOf(26.6.dp, 49.4.dp, 53.2.dp)) {
                for (chip in listOf(20.dp, 22.4.dp, 24.8.dp, 32.dp, 36.dp)) {
                    for (slots in 1..3) {
                        for (missingPixels in listOf(0.75f, 2f)) {
                            val row = number + chip * slots + 10.dp - (missingPixels / scale).dp
                            assertEquals("$missingPixels pixels short of $slots chips at density $scale, number $number, chip $chip", slots - 1,
                                fullMonthChipSlots(row, number, chip, density))
                        }
                    }
                    assertEquals(0, fullMonthChipSlots(number + 5.dp, number, chip, density))
                }
            }
        }
    }

    @Test
    fun partialAbsenceKeepsOnlyTheMissedTime() {
        val event = schoolEvent(13 * 60 + 30, 15 * 60 + 15, listOf(SchoolEventAbsence(at(13 * 60 + 30), at(14 * 60 + 20), false, listOf("Lab moved"))),
            listOf(SchoolEventPeriod(1, at(13 * 60 + 30), at(14 * 60 + 20), "MON-B2410", ""),
                SchoolEventPeriod(2, at(14 * 60 + 25), at(15 * 60 + 15), "MON-DOUCHE", "")))
        assertEquals(listOf(at(13 * 60 + 30) to at(14 * 60 + 20)), event.partialAbsences())
        assertEquals("Musculation fonctionnelle avancée", event.displayTitle())
    }

    @Test
    fun fullAbsenceIsOutlinedAndHasNoPartialRanges() {
        val event = schoolEvent(9 * 60 + 50, 10 * 60 + 40, listOf(SchoolEventAbsence(at(9 * 60 + 50), at(10 * 60 + 40), false, emptyList())))
        assertTrue(event.partialAbsences().isEmpty())
        val colors = eventColors(event, DarkPalette, CourseColors.Empty)
        assertNotNull(colors.border)
        assertEquals(DarkPalette.surface, colors.container)
        assertTrue(contrast(colors.container, colors.content) >= 4.5f)
    }

    @Test
    fun noticesKeepTheirOwnTitle() {
        val notice = schoolEvent(0, 60, emptyList()).let { it.copy(title = "Teacher absence notice: X", school = it.school!!.copy(kind = "absence")) }
        assertEquals("Teacher absence notice: X", notice.displayTitle())
        assertNull(CalendarEvent(2, 1, "Plain", "", "", 0, 1, false, zone.id).courseKeyOrNull())
    }

    @Test
    fun aVerifiedCollegeCancellationKeepsItsVisibleTitleAndOutlineWithoutInventingTeacherAbsence() {
        val regular = schoolEvent(9 * 60 + 50, 10 * 60 + 40, emptyList())
            .copy(title = "Class cancelled: Musculation")
        val cancelled = regular.copy(school = regular.school!!.copy(lift = SchoolClassLift(day, null, day.minusDays(1))))
        assertEquals("Class cancelled: Musculation", cancelled.displayTitle())
        assertEquals(io.github.sunsind.sunlith.data.SchoolAbsenceCoverage.NONE, cancelled.school!!.absenceCoverage)
        assertTrue(cancelled.partialAbsences().isEmpty())
        for (palette in listOf(LightPalette, DarkPalette)) {
            val colors = eventColors(cancelled, palette, CourseColors.Empty)
            assertNotNull(colors.border)
            assertEquals(palette.surface, colors.container)
            assertTrue(contrast(colors.container, colors.content) >= 4.5f)
        }
        val withdrawn = cancelled.copy(school = cancelled.school.copy(lift = null))
        assertEquals(regular.school.courseTitle, withdrawn.displayTitle())
        assertNull(eventColors(withdrawn, DarkPalette, CourseColors.Empty).border)
    }

    @Test
    fun rollingWindowKeepsItsStartWhileTheSelectionFits() {
        val start = LocalDate.of(2026, 11, 2)
        assertEquals(start, rollingWindowStart(start, 5, start.plusDays(4), 5))
        assertEquals(start.plusDays(5), rollingWindowStart(start, 5, start.plusDays(5), 5))
        assertEquals(start, rollingWindowStart(null, 0, start, 5))
        // 3 to 5 keeps the start, 5 to 3 ends the window on the selection so it stays in view.
        assertEquals(start, rollingWindowStart(start, 3, start.plusDays(2), 5))
        assertEquals(start.plusDays(2), rollingWindowStart(start, 5, start.plusDays(4), 3))
        assertEquals(start, rollingWindowStart(start, 5, start.plusDays(1), 3))
    }
}
