package io.github.sunsind.sunlith.school

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

data class SchoolPortalLink(val code: String, val description: String)

/** The college's own Col.NET links. An empty [Loaded] list means the college publishes none, which is not an error. */
sealed interface SchoolPortalLinks {
    data class Loaded(val links: List<SchoolPortalLink>) : SchoolPortalLinks
    /** [status] says why: SIGN_IN, NETWORK, UNAVAILABLE, UNRECOGNIZED (unexpected answer) or NOT_LOADED (not connected). */
    data class Failed(val status: SchoolAdminStatus) : SchoolPortalLinks
}

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
    /** Null until links were loaded once. False when the college publishes no extra Col.NET links. */
    val portalLinksAvailable: Boolean? = null,
)

interface SchoolMessagesController {
    val state: StateFlow<SchoolMessagesState>
    suspend fun refresh()
    suspend fun portalLinks(): List<SchoolPortalLink>
    suspend fun resolvePortalLink(code: String): String?
    /** Like [portalLinks], but keeps a real empty list apart from sign-in, network and unexpected answers. */
    suspend fun loadPortalLinks(): SchoolPortalLinks = try { SchoolPortalLinks.Loaded(portalLinks()) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { SchoolPortalLinks.Failed(SchoolAdminStatus.NETWORK) }
}
