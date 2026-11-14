package io.github.sunsind.sunlith.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {
    @Test
    fun untouchedLegacyDefaultMovesToTheNewMondayToFridayDefault() {
        assertFalse(rollingFiveDaysFromLegacy(null))
    }

    @Test
    fun explicitLegacyChoicesKeepTheirMeaningAfterTheToggleIsInverted() {
        assertTrue("An explicit 'Monday to Friday only: off' was a rolling choice", rollingFiveDaysFromLegacy(false))
        assertFalse("An explicit 'Monday to Friday only: on' stays Monday to Friday", rollingFiveDaysFromLegacy(true))
    }
}
