package io.github.sunsind.sunlith.school

import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow

enum class SchoolAlert {
    TEACHER_ABSENCES, CLASS_TIMES, ROOMS, TEACHERS, ADDED_CLASSES, REMOVED_CLASSES,
    ASSESSMENTS, SCHOOL_DAYS, CONNECTION_PROBLEMS,
}

data class SchoolNotificationPreferences(
    val enabled: Boolean = false,
    val categories: Set<SchoolAlert> = SchoolAlert.entries.toSet(),
)

enum class SchoolError {
    INVALID_PORTAL, SIGN_IN, MFA_REQUIRED, NETWORK, CALENDAR_PERMISSION,
    INVALID_DATA, UNAVAILABLE, STORAGE, LEGACY_COLLISION, CALENDAR_CHANGED,
}

data class SchoolChangeSummary(val added: Int, val updated: Int, val removed: Int, val linked: Int = 0)

data class SchoolCalendarChoice(val id: Long, val name: String)

data class SchoolScheduleState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val portalAddress: String = "",
    val accountName: String = "",
    val termName: String = "",
    val timeZone: String = ZoneId.systemDefault().id,
    val lastSuccessMillis: Long? = null,
    val error: SchoolError? = null,
    val calendarId: Long? = null,
    val availableCalendars: List<SchoolCalendarChoice> = emptyList(),
    val calendarsLoaded: Boolean = false,
    val eventCount: Int = 0,
    val notifications: SchoolNotificationPreferences = SchoolNotificationPreferences(),
    val pendingChanges: SchoolChangeSummary? = null,
)

interface SchoolScheduleController {
    val state: StateFlow<SchoolScheduleState>
    suspend fun connect(portalAddress: String, username: String, password: String, timeZone: String, calendarId: Long? = null)
    suspend fun refresh()
    suspend fun setNotifications(preferences: SchoolNotificationPreferences)
    suspend fun setNotificationsEnabled(enabled: Boolean) = setNotifications(state.value.notifications.copy(enabled = enabled))
    suspend fun setCategory(alert: SchoolAlert, enabled: Boolean) = setNotifications(state.value.notifications.let {
        it.copy(categories = if (enabled) it.categories + alert else it.categories - alert)
    })
    suspend fun applyPendingChanges()
    suspend fun discardPendingChanges()
    suspend fun disconnect(removeSchoolEvents: Boolean)
}
