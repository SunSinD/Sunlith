package io.github.sunsind.sunlith.school

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SchoolScheduleDiffTest {
    private val today = LocalDate.of(2030, 10, 7)
    private fun item(id: String = "class-a", date: LocalDate = today) = SchoolItem(id, "class-key", "Full synthetic course title", "243-TEST", "01", "2", "01", date,
        date.atTime(8, 0).atZone(ZoneId.of("America/Toronto")).toInstant().toEpochMilli(), date.atTime(10, 0).atZone(ZoneId.of("America/Toronto")).toInstant().toEpochMilli(),
        false, "America/Toronto", listOf("Synthetic Teacher"), listOf("A100"), emptyList(), false)

    @Test fun portalLoginQueryIsDiscardedAndUnsafeOriginsAreRejected() {
        assertEquals("https://example.invalid/ColNet/mobile/", SchoolPortalClient.normalize("https://example.invalid/ColNet/login.asp?NoRegr=2").toString())
        assertThrows(SchoolFailure::class.java) { SchoolPortalClient.normalize("http://example.invalid/colnet/") }
        assertThrows(SchoolFailure::class.java) { SchoolPortalClient.normalize("https://user:password@example.invalid/colnet/") }
        assertThrows(SchoolFailure::class.java) { SchoolPortalClient.normalize("https://example.invalid/other/") }
    }

    @Test fun absenceUsesExactClassAndTimesAndRetainsAgendaId() {
        val original = item()
        val absence = original.copy(id = "different-absence-id", kind = "absence", teacherAbsent = true, notes = listOf("Message: See course notice"))
        val merged = SchoolSource.merge(listOf(original), listOf(absence, absence), SchoolTerm("TEST", "Test", today, today.plusDays(30)))
        assertEquals(1, merged.size)
        assertEquals(original.id, merged.single().id)
        assertEquals(original.startMillis, merged.single().startMillis)
        assertTrue(merged.single().teacherAbsent)
        assertEquals(absence.notes, merged.single().notes)
    }
    @Test fun unmatchedAbsenceStaysAsNoticeAndDoesNotCancelSimilarClass() {
        val original = item()
        val absence = original.copy(id = "absence", kind = "absence", group = "02", teacherAbsent = true)
        val merged = SchoolSource.merge(listOf(original), listOf(absence), SchoolTerm("TEST", "Test", today, today.plusDays(30)))
        assertEquals(2, merged.size)
        assertFalse(merged.first { it.id == original.id }.teacherAbsent)
        assertEquals("absence", merged.first { it.id == "absence:absence" }.kind)
    }
    @Test fun weeklyQueriesIncludePartialFirstAndLastWeeks() {
        val term = SchoolTerm("TEST", "Test", LocalDate.of(2030, 10, 9), LocalDate.of(2030, 10, 22))
        assertEquals(listOf("2030-10-07", "2030-10-14", "2030-10-21"), term.mondays().map { it.toString() })
    }
    @Test fun schoolTermsRejectOverlongAndReversedRanges() {
        assertThrows(SchoolFailure::class.java) { SchoolTerm("TEST", "Test", today, today.plusDays(366)) }
        assertThrows(SchoolFailure::class.java) { SchoolTerm("TEST", "Test", today, today.minusDays(1)) }
    }
    @Test fun unknownKindsAreRetainedAndNotificationDiffIsSilentWhenUnchanged() {
        val original = item().copy(kind = "99")
        assertTrue(SchoolDiff.changes(listOf(original), listOf(original), today).isEmpty())
        assertEquals(original, SchoolSource.merge(listOf(original), emptyList(), SchoolTerm("TEST", "Test", today, today)).single())
    }
    @Test fun changesSeparateRoomsTeachersTimeAndAbsence() {
        val original = item()
        val changed = original.copy(rooms = listOf("B200"), teachers = listOf("Other Synthetic Teacher"), startMillis = original.startMillis + 60_000, teacherAbsent = true)
        assertEquals(setOf(SchoolAlert.ROOMS, SchoolAlert.TEACHERS, SchoolAlert.CLASS_TIMES, SchoolAlert.TEACHER_ABSENCES), SchoolDiff.changes(listOf(original), listOf(changed), today).single().categories)
    }
    @Test fun disabledCategoriesDoNotReplayAfterBaselineAdvances() {
        val original = item(); val changed = original.copy(rooms = listOf("B200"))
        val delta = SchoolDiff.changes(listOf(original), listOf(changed), today)
        assertTrue(SchoolDiff.enabled(delta, SchoolNotificationPreferences()).isEmpty())
        assertTrue(SchoolDiff.enabled(delta, SchoolNotificationPreferences(true, setOf(SchoolAlert.TEACHERS))).isEmpty())
        assertTrue(SchoolDiff.enabled(SchoolDiff.changes(listOf(changed), listOf(changed), today), SchoolNotificationPreferences(true)).isEmpty())
    }
    @Test fun emptyOrLargeFutureRemovalRequiresExplicitConfirmation() {
        val before = (0 until 20).map { item("class-$it") }
        assertTrue(SchoolDiff.needsConfirmation(before, emptyList(), today))
        assertTrue(SchoolDiff.needsConfirmation(before, before.drop(5), today))
        assertFalse(SchoolDiff.needsConfirmation(before, before.drop(1), today))
        assertFalse(SchoolDiff.needsConfirmation(before.map { it.copy(date = today.minusDays(1)) }, emptyList(), today))
    }
    @Test fun deadlineAndSchoolDayChangesUseTheirOwnCategories() {
        val assessment = item("exam").copy(kind = "07", title = "Exam 1 (23:59)")
        val schoolDay = item("day").copy(kind = "08")
        val changes = SchoolDiff.changes(emptyList(), listOf(assessment, schoolDay), today)
        assertEquals(setOf(SchoolAlert.ASSESSMENTS), changes.first().categories)
        assertEquals(setOf(SchoolAlert.SCHOOL_DAYS), changes.last().categories)
    }
}
