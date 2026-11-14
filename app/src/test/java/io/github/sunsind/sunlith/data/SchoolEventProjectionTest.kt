package io.github.sunsind.sunlith.data

import io.github.sunsind.sunlith.school.SchoolClassLift
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SchoolEventProjectionTest {
    private val zone = ZoneId.of("America/Toronto")
    private val day = LocalDate.of(2030, 10, 7)
    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun event(id: Long, start: Long = at(9, 50), end: Long = at(10, 40), room: String = "A100",
        kind: String = "01", absent: Boolean = false): CalendarEvent {
        val description = "Synthetic period $id"
        return CalendarEvent(id, 7, "Synthetic course", description, room, start, end, false, zone.id,
            school = SchoolEventInfo("243TEST", "01", "class-key", "2", kind, "Synthetic course",
                listOf(SchoolEventPeriod(id, start, end, room, description)),
                if (absent || kind == "absence") listOf(SchoolEventAbsence(start, end, false, listOf("Synthetic notice"))) else emptyList()))
    }
    private fun project(vararg events: CalendarEvent) = projectSchoolEvents(events.toList(), zone)

    @Test fun fiveMinuteRoomChangeJoinsOneFullClassAndKeepsEveryPeriod() {
        val first = event(1, room = "A100, A101")
        val second = event(2, at(10, 45), at(11, 35), "SHOWER")
        val joined = project(second, first).single()
        assertEquals(first.id, joined.id)
        assertEquals(first.startMillis, joined.startMillis)
        assertEquals(second.endMillis, joined.endMillis)
        assertEquals("A100, A101; SHOWER", joined.location)
        assertEquals(listOf(1L, 2L), joined.school!!.periods.map { it.eventId })
        assertEquals(SchoolAbsenceCoverage.NONE, joined.school.absenceCoverage)
        assertTrue(joined.description.contains(second.description))
        assertEquals(first.endMillis, first.school!!.periods.single().endMillis)
    }

    @Test fun absentFirstPeriodAndRegularSecondPeriodStayPartiallyAbsent() {
        val absent = event(1, at(13, 30), at(14, 20), kind = "absence")
            .copy(title = "Teacher absence notice: Synthetic course")
        val regular = event(2, at(14, 25), at(15, 15), "SHOWER")
        val joined = project(absent, regular).single()
        assertEquals("Synthetic course", joined.title)
        assertEquals(absent.startMillis, joined.startMillis)
        assertEquals(regular.endMillis, joined.endMillis)
        assertEquals(SchoolAbsenceCoverage.PARTIAL, joined.school!!.absenceCoverage)
        assertEquals(absent.endMillis, joined.school.absences.single().endMillis)
        assertEquals(setOf(1L, 2L), joined.school.periods.map { it.eventId }.toSet())
    }

    @Test fun exactAbsenceJoinsItsClassWithoutDuplicatingTimeOrLosingNotes() {
        val regular = event(1)
        val notice = event(3, kind = "absence")
        val joined = project(regular, notice).single()
        assertEquals(regular.id, joined.id)
        assertEquals(1, joined.school!!.periods.size)
        assertEquals(SchoolAbsenceCoverage.FULL, joined.school.absenceCoverage)
        assertEquals(listOf("Synthetic notice"), joined.school.absences.single().notes)
    }

    @Test fun partialOverlappingNoticeKeepsExactTimeAndDoesNotCancelWholeClass() {
        val regular = event(1)
        val notice = event(3, at(10), at(10, 20), kind = "absence")
        val joined = project(regular, notice).single()
        assertEquals(regular.startMillis, joined.startMillis)
        assertEquals(regular.endMillis, joined.endMillis)
        assertEquals(SchoolAbsenceCoverage.PARTIAL, joined.school!!.absenceCoverage)
        assertEquals(at(10), joined.school.absences.single().startMillis)
    }

    @Test fun fullDayAbsenceAttachesOnlyToItsExactClassAndDate() {
        val regular = event(1)
        val other = event(2, at(12), at(12, 50)).let { it.copy(school = it.school!!.copy(group = "02")) }
        val start = CalendarDates.allDayMillis(day)
        val end = CalendarDates.allDayMillis(day.plusDays(1))
        val notice = event(3, start, end, kind = "absence").let {
            it.copy(allDay = true, timeZone = "UTC", school = it.school!!.copy(
                absences = listOf(SchoolEventAbsence(start, end, true, listOf("Day notice")))))
        }
        val joined = project(regular, notice, other)
        assertEquals(2, joined.size)
        assertEquals(SchoolAbsenceCoverage.FULL, joined.first { it.id == 1L }.school!!.absenceCoverage)
        assertEquals(SchoolAbsenceCoverage.NONE, joined.first { it.id == 2L }.school!!.absenceCoverage)
        assertEquals(2, project(regular, notice.copy(startMillis = CalendarDates.allDayMillis(day.plusDays(1)),
            endMillis = CalendarDates.allDayMillis(day.plusDays(2)))).size)
    }

    @Test fun allAbsentPeriodsAreFullAndSeparatePartialIntervalsCanCoverOnePeriod() {
        val first = event(1, absent = true)
        val second = event(2, at(10, 45), at(11, 35), absent = true)
        assertEquals(SchoolAbsenceCoverage.FULL, project(first, second).single().school!!.absenceCoverage)
        val split = first.school!!.copy(absences = listOf(
            SchoolEventAbsence(at(9, 50), at(10, 10), false, emptyList()),
            SchoolEventAbsence(at(10, 10), at(10, 40), false, emptyList())))
        assertEquals(SchoolAbsenceCoverage.FULL, split.absenceCoverage)
    }

    @Test fun lunchAndLongerChangeoversRemainSeparate() {
        assertEquals(2, project(event(1), event(2, at(10, 46), at(11, 36))).size)
        assertEquals(2, project(event(1), event(2, at(13), at(13, 50))).size)
        assertEquals(1, project(event(1), event(2, at(10, 40), at(11, 30))).size)
    }

    @Test fun personalRowsAndAssessmentsAreNeverCollapsedIntoClasses() {
        val first = event(1)
        val following = event(2, at(10, 45), at(11, 35))
        assertEquals(2, project(first.copy(school = null), following.copy(school = null)).size)
        assertEquals(2, project(first, following.let { it.copy(school = it.school!!.copy(kind = "07")) }).size)
        assertEquals(2, project(first, following, event(3, at(10), at(10, 20)).copy(school = null)).size)
    }

    @Test fun classCourseGroupCalendarKindDateAndZoneBoundariesArePreserved() {
        val first = event(1)
        val second = event(2, at(10, 45), at(11, 35))
        val boundaries = listOf(
            second.copy(calendarId = 8),
            second.copy(timeZone = "America/New_York"),
            second.copy(startMillis = at(10, 45, day.plusDays(1)), endMillis = at(11, 35, day.plusDays(1))),
            second.copy(school = second.school!!.copy(course = "243OTHER")),
            second.copy(school = second.school.copy(group = "02")),
            second.copy(school = second.school.copy(classKey = "other-key")),
            second.copy(school = second.school.copy(classKind = "1")),
        )
        boundaries.forEach { assertEquals(2, project(first, it).size) }
    }

    @Test fun ambiguousOrUnrelatedAbsenceStaysVisible() {
        val regular = event(1)
        val different = event(3, kind = "absence").let { it.copy(school = it.school!!.copy(classKey = "other-key")) }
        assertEquals(2, project(regular, different).size)
        assertEquals(2, project(regular, event(3, at(14), at(14, 50), kind = "absence")).size)
        assertEquals(2, project(regular, event(3, kind = "absence").let { it.copy(school = it.school!!.copy(classKind = "1")) }).size)
    }

    @Test fun overlappingClassesDoNotCollapseAndProjectionIsIdempotent() {
        val first = event(1)
        val overlapping = event(2, at(10), at(10, 50))
        assertEquals(2, project(first, overlapping).size)
        val joined = project(first, event(3, at(10, 45), at(11, 35)))
        assertEquals(joined, projectSchoolEvents(joined, zone))
    }

    @Test fun cancelledPeriodsKeepTheirNoticeWhenCombinedAndKeepEveryOriginalId() {
        val lift = SchoolClassLift(day, LocalTime.of(18, 0), day.minusDays(1))
        fun marked(event: CalendarEvent) = event.copy(title = "Class cancelled: Synthetic course",
            school = event.school!!.copy(lift = lift))
        val first = marked(event(1))
        val second = marked(event(2, at(10, 45), at(11, 35)))
        val joined = project(first, second).single()
        assertEquals(lift, joined.school!!.lift)
        assertEquals("Class cancelled: Synthetic course", joined.title)
        assertEquals(listOf(1L, 2L), joined.school.periods.map { it.eventId })
        assertEquals(first.startMillis, joined.startMillis)
        assertEquals(second.endMillis, joined.endMillis)
        assertEquals(SchoolAbsenceCoverage.NONE, joined.school.absenceCoverage)
        assertEquals(listOf(joined), projectSchoolEvents(listOf(joined), zone))
    }

    @Test fun aClassThatResumesAfterTheNoticeBoundaryNeverJoinsTheCancelledRun() {
        val before = event(1, at(17), at(17, 55)).let {
            it.copy(title = "Class cancelled: Synthetic course", school = it.school!!.copy(
                lift = SchoolClassLift(day, LocalTime.of(18, 0), day.minusDays(1))))
        }
        val after = event(2, at(18), at(18, 50))
        val classes = project(before, after)
        assertEquals(listOf(1L, 2L), classes.map { it.id })
        assertNotNull(classes.first().school!!.lift)
        assertNull(classes.last().school!!.lift)
        assertEquals(before.endMillis, classes.first().endMillis)
        assertEquals(after.startMillis, classes.last().startMillis)

        val withdrawn = project(before.copy(title = "Synthetic course", school = before.school!!.copy(lift = null)), after).single()
        assertNull(withdrawn.school!!.lift)
        assertEquals("Synthetic course", withdrawn.title)
        assertEquals(listOf(1L, 2L), withdrawn.school.periods.map { it.eventId })
    }
}
