package io.github.sunsind.sunlith.system

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMigrationTest {
    @Test
    fun anOldPagedTimelineBecomesContinuousWithItsOwnColumnCount() {
        for (days in listOf(3, 5, 7)) {
            assertEquals(WeekLayoutMigration("continuous", days), migrateWeekLayout("timeline", days))
        }
        assertEquals("A timeline that never saved a count showed seven days", WeekLayoutMigration("continuous", 7), migrateWeekLayout("timeline", null))
        assertEquals("An unreadable count falls back to the seven days it showed", WeekLayoutMigration("continuous", 7), migrateWeekLayout("timeline", 4))
    }

    @Test
    fun anOldContinuousWeekKeepsTheSevenColumnsItShowedUnlessItSavedACount() {
        assertEquals(WeekLayoutMigration(null, 7), migrateWeekLayout("continuous", null))
        assertEquals(WeekLayoutMigration(null, null), migrateWeekLayout("continuous", 3))
    }

    @Test
    fun anExplicitListAndAnUnsetWeekAreLeftAlone() {
        assertEquals("List stays List with its saved count", WeekLayoutMigration(null, null), migrateWeekLayout("list", 7))
        assertEquals("An unset Week takes the new continuous five day default", WeekLayoutMigration(null, null), migrateWeekLayout(null, null))
        assertEquals("continuous", SettingsStore.DEFAULT_WEEK_LAYOUT)
        assertEquals(5, SettingsStore.DEFAULT_VISIBLE_DAYS)
    }
}
