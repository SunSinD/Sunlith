package io.github.sunsind.sunlith.school

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal data class SchoolHtmlPage(val uri: URI, val html: String)

internal enum class SchoolAdminOutcome { OK, SIGN_IN, UNRECOGNIZED, NETWORK, UNAVAILABLE }
internal data class SchoolAdminResult(val outcome: SchoolAdminOutcome, val rows: List<SchoolAdminRow> = emptyList(), val details: Map<String, SchoolAdminDetail> = emptyMap(), val detailsStale: Boolean = false)

/**
 * Classic Col.NET web session (cookie based), used only to read "Message(s) de l'administration".
 * Every URL is checked to stay on the same host under the Col.NET folder. Nothing is logged, and the session lives only in memory.
 */
internal class SchoolClassicPortal(mobileBase: URI, private val routes: List<(URI) -> HttpsURLConnection>) {
    val colnet: URI = mobileBase.resolve("../").normalize()
    val loginPage: URI = colnet.resolve("login.asp")
    private val cookies = linkedMapOf<String, String>()
    private var route = 0
    private var requests = 0

    suspend fun readAdministration(username: String, password: String, cachedDetails: Map<String, SchoolAdminDetail>, today: java.time.LocalDate): SchoolAdminResult {
        val landing = try { signIn(username, password) } catch (e: SchoolFailure) {
            return SchoolAdminResult(when (e.reason) { SchoolError.SIGN_IN -> SchoolAdminOutcome.SIGN_IN; SchoolError.NETWORK -> SchoolAdminOutcome.NETWORK; SchoolError.UNAVAILABLE -> SchoolAdminOutcome.UNAVAILABLE; else -> SchoolAdminOutcome.UNRECOGNIZED })
        }
        val pages = ArrayList<SchoolHtmlPage>().apply { add(landing) }
        var frontier = listOf(landing)
        repeat(2) {
            frontier = frontier.flatMap { page ->
                SchoolHtml.frames(SchoolHtml.tokens(page.html)).mapNotNull { SchoolAdminParser.sameFolder(it, page.uri, colnet) }
            }.distinct().filter { uri -> pages.none { it.uri == uri } }.take(6).map { get(it) }
            pages += frontier
        }
        val lists = pages.mapNotNull { page -> SchoolAdminParser.list(SchoolHtml.tables(SchoolHtml.tokens(page.html)), page.uri, colnet) }.distinct()
        if (lists.size != 1) return SchoolAdminResult(SchoolAdminOutcome.UNRECOGNIZED)
        val rows = lists.single()
        val details = LinkedHashMap<String, SchoolAdminDetail>()
        rows.forEach { row -> cachedDetails[row.id]?.takeIf { SchoolAdminParser.matches(row, it) }?.let { details[row.id] = it } }
        var detailsStale = false
        rows.filter { it.link != null && !it.date.isBefore(today.minusDays(120)) }.take(MAX_DETAILS).forEach { row ->
            val page = try { get(row.link!!) } catch (e: SchoolFailure) { if (e.reason == SchoolError.SIGN_IN) return SchoolAdminResult(SchoolAdminOutcome.SIGN_IN) else null }
            val detail = page?.let { SchoolAdminParser.detail(SchoolHtml.tables(SchoolHtml.tokens(it.html))) }
                ?.takeIf { SchoolAdminParser.matches(row, it) }
            if (detail != null) details[row.id] = detail
            else {
                detailsStale = true
                cachedDetails[row.id]?.takeIf { SchoolAdminParser.matches(row, it) }?.let { details[row.id] = it }
            }
        }
        return SchoolAdminResult(SchoolAdminOutcome.OK, rows, details, detailsStale)
    }

    private suspend fun signIn(username: String, password: String): SchoolHtmlPage {
        schoolRequire(username.isNotBlank() && password.isNotEmpty())
        val page = get(loginPage)
        val (action, body) = loginRequest(page, colnet, username, password)
        val landing = send(action, body)
        if (SchoolHtml.forms(SchoolHtml.tokens(landing.html)).any { f -> f.inputs.any { it["name"] == PASSWORD } }) throw SchoolFailure(SchoolError.SIGN_IN)
        return landing
    }

    suspend fun get(uri: URI): SchoolHtmlPage = send(uri, null)

    private suspend fun send(start: URI, form: String?): SchoolHtmlPage {
        var uri = start
        var body = form
        repeat(6) {
            coroutineContext.ensureActive()
            schoolRequire(++requests <= MAX_REQUESTS)
            schoolRequire(SchoolAdminParser.sameFolder(uri.toString(), colnet, colnet) != null)
            val result = exchange(uri, body)
            if (result.second == null) return SchoolHtmlPage(uri, result.first!!)
            uri = SchoolAdminParser.sameFolder(result.second!!, uri, colnet) ?: throw SchoolFailure(SchoolError.INVALID_DATA)
            body = null
        }
        throw SchoolFailure(SchoolError.INVALID_DATA)
    }

    /** Returns (html, null) for a page or (null, location) for a redirect. */
    private suspend fun exchange(uri: URI, form: String?): Pair<String?, String?> {
        while (true) {
            try { return exchangeOn(routes[route](uri), form) } catch (e: IOException) {
                coroutineContext.ensureActive()
                if (route == routes.lastIndex) throw SchoolFailure(SchoolError.NETWORK)
                route++
            } catch (_: SecurityException) {
                if (route == routes.lastIndex) throw SchoolFailure(SchoolError.NETWORK)
                route++
            }
        }
    }

    private suspend fun exchangeOn(connection: HttpsURLConnection, form: String?): Pair<String?, String?> {
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/html")
            if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val bytes = form.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            connection.headerFields?.entries?.filter { it.key.equals("Set-Cookie", true) }?.flatMap { it.value.orEmpty() }?.forEach(::remember)
            if (status in 300..399) return null to (connection.getHeaderField("Location") ?: throw SchoolFailure(SchoolError.INVALID_DATA))
            if (status == 401 || status == 403) throw SchoolFailure(SchoolError.SIGN_IN)
            if (status == 503) throw SchoolFailure(SchoolError.UNAVAILABLE)
            if (status !in 200..299) throw SchoolFailure(SchoolError.NETWORK)
            val type = connection.contentType.orEmpty()
            if (!type.substringBefore(';').trim().equals("text/html", true)) throw SchoolFailure(SchoolError.INVALID_DATA)
            schoolRequire(connection.contentLengthLong <= MAX_BYTES)
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + 30_000_000_000L
                while (true) {
                    coroutineContext.ensureActive()
                    if (System.nanoTime() > deadline) throw SocketTimeoutException("Portal page timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    schoolRequire(bytes.size() + count <= MAX_BYTES)
                    bytes.write(buffer, 0, count)
                }
            }
            return decode(bytes.toByteArray(), type) to null
        } finally { connection.disconnect() }
    }

    private fun remember(header: String) {
        val pair = header.substringBefore(';')
        val name = pair.substringBefore('=').trim()
        if (name.isEmpty() || '=' !in pair || name.length > 200 || cookies.size >= 40) return
        val value = pair.substringAfter('=').trim()
        if (value.length > 4000 || value.any { it == '\r' || it == '\n' || it == ';' }) return
        val expired = header.split(';').drop(1).any { a -> a.trim().lowercase(Locale.ROOT).let { it == "max-age=0" || it.startsWith("max-age=-") } }
        if (expired || value.isEmpty()) cookies.remove(name) else cookies[name] = value
    }

    companion object {
        const val USER = "txtCodeUsager"
        const val PASSWORD = "txtMotDePasse"
        const val SUBMIT = "btnConnecter"
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_REQUESTS = 40
        private const val MAX_DETAILS = 15

        /**
         * Builds the sign-in POST from the one form that has both credential fields.
         * Col.NET serves two layouts of login.asp. Desktop browsers get an image button named btnConnecter.
         * Android user agents (this app's HttpsURLConnection included) get a phone layout with no button: its link runs
         * DoPostBack, which only fills the _PostBack hidden fields and submits. Verified on Montmorency, 2026-11-14: that
         * server signs in only when btnConnecter.x and .y are posted, in both layouts. The phone layout is accepted only
         * when it has no other submit control and carries the _PostBackInfo field, so an unknown form still fails closed.
         */
        internal fun loginRequest(page: SchoolHtmlPage, colnet: URI, username: String, password: String): Pair<URI, String> {
            val forms = SchoolHtml.forms(SchoolHtml.tokens(page.html)).filter { form ->
                form.inputs.any { it["name"] == USER } && form.inputs.any { it["name"] == PASSWORD }
            }
            if (forms.size != 1) throw SchoolFailure(SchoolError.INVALID_DATA)
            val form = forms.single()
            fun type(input: Map<String, String>) = input["type"].orEmpty().trim().lowercase(Locale.ROOT)
            val image = form.inputs.singleOrNull { it["name"] == SUBMIT && type(it) == "image" }
            val controls = form.inputs.filter { type(it) in setOf("submit", "image") || (it["tag"] == "button" && type(it) in setOf("", "submit")) }
            val postBack = form.inputs.any { it["name"] == POST_BACK && type(it) == "hidden" }
            val submit = when {
                image != null -> image.getValue("name")
                controls.isEmpty() && postBack -> SUBMIT
                else -> throw SchoolFailure(SchoolError.INVALID_DATA)
            }
            val action = SchoolAdminParser.sameFolder(form.action.ifBlank { page.uri.toString() }, page.uri, colnet) ?: throw SchoolFailure(SchoolError.INVALID_DATA)
            val fields = form.inputs.filter { type(it) == "hidden" && !it["name"].isNullOrBlank() }.map { it.getValue("name") to it["value"].orEmpty() } +
                listOf(USER to username, PASSWORD to password, "$submit.x" to "12", "$submit.y" to "8")
            return action to fields.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        }
        internal const val POST_BACK = "_PostBackInfo"

        internal fun decode(bytes: ByteArray, contentType: String): String {
            val declared = Regex("charset=\"?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
            val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
            if (charset != null) return String(bytes, charset)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) { String(bytes, Charset.forName("windows-1252")) }
        }
    }
}
