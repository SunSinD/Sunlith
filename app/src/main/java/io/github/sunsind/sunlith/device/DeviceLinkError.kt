package io.github.sunsind.sunlith.device

internal class DeviceLinkError(val status: Int, val code: String, override val message: String, val reason: String? = null) : RuntimeException(message) {
    companion object {
        fun badRequest(message: String) = DeviceLinkError(400, "bad_request", message)
        fun unauthorized() = DeviceLinkError(401, "unauthorized", "The pairing token is missing, revoked or invalid. Pair the phone again.")
        fun calendarNotAllowed(message: String = "This calendar is not allowed for this pairing.") = DeviceLinkError(403, "calendar_not_allowed", message)
        fun notFound(message: String = "The calendar or event was not found.") = DeviceLinkError(404, "not_found", message)
        fun revisionMismatch() = DeviceLinkError(409, "revision_mismatch", "The event changed since it was read. Read it again and use its latest etag.")
        fun operationConflict(message: String) = DeviceLinkError(409, "operation_conflict", message)
        fun blocked(message: String, reason: String? = null) = DeviceLinkError(422, "blocked", message, reason)
        fun rateLimited() = DeviceLinkError(429, "rate_limited", "Too many requests. Wait a minute and try again.")
        fun tooLarge(message: String = "The request or result is too large.") = DeviceLinkError(413, "too_large", message)
        fun permissionMissing() = DeviceLinkError(503, "permission_missing", "Calendar access is not allowed on the phone. Open Sunlith and allow it.")
        fun internal() = DeviceLinkError(500, "internal", "The phone could not complete the request.")
    }
}
