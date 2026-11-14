package io.github.sunsind.sunlith.school

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class SchoolLogin(val token: String, val expiresAt: Long)
internal class SchoolPortalClient private constructor(address: String, private val language: String,
    private val routes: List<(URI) -> HttpsURLConnection>) {
    constructor(address: String, language: String,
        open: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection }) : this(address, language, listOf(open))
    private var routeIndex = 0
    val base: URI = normalize(address)
    suspend fun login(username: String, password: String): SchoolLogin {
        schoolRequire(username.isNotBlank() && password.isNotEmpty() && username.length <= 1000 && password.length <= 4000)
        val form = listOf("username" to username, "password" to password, "grant_type" to "password").joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val j = request("token", null, false, form) as JSONObject
        val token = j.requiredText("access_token")
        schoolRequire(token.length in 10..16_000 && token.none { it == '\r' || it == '\n' })
        val seconds = j.getLong("expires_in")
        schoolRequire(seconds in 1..(366L * 86_400))
        return SchoolLogin(token, System.currentTimeMillis() + seconds * 1000)
    }
    suspend fun fetch(token: String, zone: ZoneId, previous: List<SchoolItem> = emptyList(), initialized: Boolean = false, termKey: String = ""): SchoolFeed {
        val mfa = request("api/infoMFA", token, false) as JSONObject
        if (mfa.requiredBoolean("IndMFA")) throw SchoolFailure(SchoolError.MFA_REQUIRED)
        val access = request("api/accesPermis", token, false) as JSONObject
        if (!access.requiredBoolean("Acces")) throw SchoolFailure(SchoolError.SIGN_IN)
        val info = request("api/informations", token, false) as JSONObject
        val account = schoolHash("$base\n${info.requiredText("CleUnique")}")
        schoolRequire(info.has("ServicesAffiches") && !info.isNull("ServicesAffiches"))
        val services = info.get("ServicesAffiches").toString().uppercase(Locale.ROOT)
        if (!services.contains("AGENDA") || !services.contains("CANCELED_SESSIONS")) throw SchoolFailure(SchoolError.UNAVAILABLE)
        val termRows = request("api/periodesEtudes", token, true) as JSONArray
        schoolRequire(termRows.length() in 1..100)
        val selectedRaw = info.opt("PeriodeEtudesHoraire")
        val selected = when (selectedRaw) { is JSONObject -> selectedRaw.text("Code"); is String, is Number -> selectedRaw.toString(); else -> "" }
        val term = SchoolSource.selectTerm((0 until termRows.length()).map { SchoolSource.term(termRows.getJSONObject(it)) }, selected, LocalDate.now(zone))
        val incremental = initialized && termKey == term.key
        val rows = request("api/agenda?dateDebut=${SchoolSource.wireDate(term.start)}&dateFin=${SchoolSource.wireDate(term.end)}&cleUniqueEleve=${URLEncoder.encode(info.requiredText("CleUnique"), "UTF-8")}", token, true) as JSONArray
        val agenda = SchoolSource.agenda(rows, term, zone)
        val thisMonday = LocalDate.now(zone).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val absences = term.mondays().filter { !incremental || !it.isBefore(thisMonday) }.flatMap { monday ->
            coroutineContext.ensureActive()
            SchoolSource.absences(request("api/coursAnnules?DateDebut=${SchoolSource.wireDate(monday)}", token, true) as JSONArray, monday, zone)
        }
        val merged = SchoolSource.merge(agenda, absences, term).toMutableList()
        if (incremental) previous.filter { it.teacherAbsent && it.date.isBefore(thisMonday) && !it.date.isBefore(term.start) && !it.date.isAfter(term.end) }.forEach { old ->
            val index = merged.indexOfFirst { it.id == old.id && it.classMatch == old.classMatch }
            if (index >= 0) merged[index] = merged[index].copy(teacherAbsent = true, notes = (merged[index].notes + old.notes).distinct())
            else if (old.kind == "absence" && merged.none { it.id == old.id }) merged += old
        }
        return SchoolFeed(account, term, merged.sortedWith(compareBy<SchoolItem> { it.startMillis }.thenBy { it.id }))
    }
    suspend fun portalLinks(token: String): List<Pair<String, String>> {
        val info = request("api/informations", token, false) as JSONObject
        // A missing or null list means the college publishes no links. Any other non-list value is an unexpected answer.
        if (!info.has("Liens") || info.isNull("Liens")) return emptyList()
        val links = info.get("Liens") as? JSONArray ?: throw SchoolFailure(SchoolError.INVALID_DATA)
        schoolRequire(links.length() <= 200)
        return (0 until links.length()).mapNotNull { links.optJSONObject(it) }.mapNotNull { j ->
            val code = j.text("Code"); val description = j.text("Description")
            if (code.isBlank() || description.isBlank() || code.length > 200) null else code to description.take(200)
        }.distinctBy { it.first }
    }
    /** Returns a one-time portal URL for immediate opening. It is never stored or logged. */
    suspend fun portalLink(token: String, code: String): String? {
        schoolRequire(code.isNotBlank() && code.length <= 200)
        val j = request("api/lienPortail?cleCodeLien=${URLEncoder.encode(code, "UTF-8")}", token, false) as JSONObject
        val url = j.text("UrlLien").trim()
        return SchoolHtml.safeLink(url)?.takeIf { it.startsWith("https://", ignoreCase = true) }
    }
    private suspend fun request(relative: String, token: String?, array: Boolean, form: String? = null): Any {
        coroutineContext.ensureActive()
        val url = base.resolve(relative)
        schoolRequire(url.scheme == base.scheme && url.host == base.host && url.port == base.port && url.path.startsWith(base.path))
        // Changing the route keeps the same HTTPS URL and normal certificate/hostname verification.
        while (true) {
            try {
                return requestOn(routes[routeIndex](url), token, array, form)
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                logTransport(e)
                if (routeIndex == routes.lastIndex) throw e
                // The fallback remains selected for the rest of this sync, rather than repeating a failed route.
                routeIndex++
            } catch (e: SecurityException) {
                coroutineContext.ensureActive()
                logTransport(e)
                if (routeIndex == routes.lastIndex) throw SchoolFailure(SchoolError.NETWORK)
                routeIndex++
            }
        }
    }
    private fun logTransport(error: Exception) {
        val route = if (routes.size > 1 && routeIndex == 0) "direct" else "default"
        Log.w("SunlithSchool", "Portal ${error.javaClass.simpleName} route=$route")
    }
    private suspend fun requestOn(connection: HttpsURLConnection, token: String?, array: Boolean, form: String?): Any {
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Coba-Result", if (array) "Array" else "Object")
            connection.setRequestProperty("X-Coba-Langue-Courante", if (language == "fr") "fr" else "en")
            connection.setRequestProperty("X-Coba-Mobile-Version", "4.6.3")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val bytes = form.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status == 401) throw SchoolFailure(SchoolError.SIGN_IN)
            if (status in 500..599) throw SchoolFailure(SchoolError.UNAVAILABLE)
            val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (mime == "text/html") throw SchoolFailure(SchoolError.NETWORK)
            if (status == 403 || (form != null && status == 400)) throw SchoolFailure(SchoolError.SIGN_IN)
            if (status !in 200..299) throw SchoolFailure(SchoolError.NETWORK)
            schoolRequire(mime?.let { it == "application/json" || it.endsWith("+json") } == true)
            schoolRequire(connection.contentLengthLong <= MAX_BYTES)
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + 30_000_000_000L
                while (true) {
                    coroutineContext.ensureActive()
                    if (System.nanoTime() > deadline) throw SocketTimeoutException("School response timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    schoolRequire(bytes.size() + count <= MAX_BYTES)
                    bytes.write(buffer, 0, count)
                }
            }
            val body = bytes.toString("UTF-8")
            boundedNesting(body)
            val parser = JSONTokener(body)
            val result = parser.nextValue()
            if (unavailable(result)) throw SchoolFailure(SchoolError.UNAVAILABLE)
            schoolRequire(parser.nextClean() == '\u0000' && if (array) result is JSONArray else result is JSONObject)
            return result
        } finally { connection.disconnect() }
    }
    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        fun routed(address: String, language: String, routes: List<(URI) -> HttpsURLConnection>): SchoolPortalClient {
            require(routes.isNotEmpty() && routes.size <= 2)
            return SchoolPortalClient(address, language, routes.toList())
        }
        private fun boundedNesting(body: String) {
            var depth = 0; var quoted = false; var escaped = false
            body.forEach { char ->
                if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
                else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; schoolRequire(depth <= 32) }; '}', ']' -> { depth--; schoolRequire(depth >= 0) } }
            }
            schoolRequire(!quoted && depth == 0)
        }
        private fun unavailable(value: Any?): Boolean = when (value) {
            is String -> value == "ServicesNonDisponibles"
            is JSONObject -> value.keys().asSequence().any {
                (it == "ServicesNonDisponibles" && value.opt(it) == true) || unavailable(value.opt(it))
            }
            is JSONArray -> (0 until value.length()).any { unavailable(value.opt(it)) }
            else -> false
        }
        fun normalize(value: String): URI {
            try {
                val uri = URI(value.trim())
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null || uri.port !in listOf(-1, 443)) throw SchoolFailure(SchoolError.INVALID_PORTAL)
                val path = uri.path.orEmpty()
                val index = path.lowercase(Locale.ROOT).indexOf("/colnet")
                if (index < 0 || (path.length > index + 7 && path[index + 7] != '/')) throw SchoolFailure(SchoolError.INVALID_PORTAL)
                val prefix = path.substring(0, index + 7)
                if (path.split('/').any { it == ".." || it == "." } || '%' in uri.rawPath || '\\' in path) throw SchoolFailure(SchoolError.INVALID_PORTAL)
                return URI("https", null, uri.host.lowercase(Locale.ROOT), -1, "$prefix/mobile/", null, null)
            } catch (e: SchoolFailure) { throw e } catch (_: Exception) { throw SchoolFailure(SchoolError.INVALID_PORTAL) }
        }
    }
}
