package io.github.sunsind.sunlith.school

import kotlinx.coroutines.flow.StateFlow

data class SchoolPortalLink(val code: String, val description: String)

enum class SchoolAdminStatus { NOT_LOADED, OK, SIGN_IN, UNRECOGNIZED, NETWORK, UNAVAILABLE, STORAGE }

data class SchoolMessagesState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val admin: List<SchoolAdminNotice> = emptyList(),
    val adminStatus: SchoolAdminStatus = SchoolAdminStatus.NOT_LOADED,
    val adminUpdatedMillis: Long? = null,
    val collegeNotices: List<SchoolCollegeNotice> = emptyList(),
    val suggestions: List<SchoolNoticeSuggestion> = emptyList(),
    /** Public Col.NET sign-in page; carries no session. */
    val portalUrl: String? = null,
)

interface SchoolMessagesController {
    val state: StateFlow<SchoolMessagesState>
    suspend fun refresh()
    suspend fun portalLinks(): List<SchoolPortalLink>
    suspend fun resolvePortalLink(code: String): String?
}
