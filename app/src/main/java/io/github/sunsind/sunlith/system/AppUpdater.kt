package io.github.sunsind.sunlith.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val APP_PACKAGE = "io.github.sunsind.sunlith"

internal enum class UpdateProblem { NETWORK, RATE_LIMITED, NOT_FOUND, NOT_VERIFIED, STORAGE, NO_INSTALLER }

internal class UpdateFailure(val problem: UpdateProblem) : Exception(problem.name)

internal data class UpdateVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})")
        fun parse(text: String?): UpdateVersion? {
            val match = pattern.matchEntire(text ?: return null) ?: return null
            return UpdateVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }
        fun fromTag(tag: String?): UpdateVersion? = if (tag != null && tag.startsWith("v")) parse(tag.substring(1)) else null
    }
}

internal data class UpdateAsset(val name: String, val url: URI, val size: Long, val sha256: String)

internal data class UpdateRelease(val version: UpdateVersion, val apk: UpdateAsset, val sums: UpdateAsset)

internal object AppUpdateRelease {
    const val REPOSITORY = "SunSinD/Sunlith"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val SUMS_NAME = "SHA256SUMS.txt"
    const val MAX_METADATA = 1L shl 20
    const val MAX_SUMS = 16L * 1024
    const val MAX_APK = 64L shl 20
    private val apkTypes = setOf("application/vnd.android.package-archive", "application/octet-stream")
    private val sumsTypes = setOf("text/plain", "application/octet-stream")
    private val hex = Regex("[0-9a-fA-F]{64}")
    private val sumsLine = Regex("([0-9a-fA-F]{64}) [ *](\\S(?:.*\\S)?)")

    fun apkName(version: UpdateVersion) = "Sunlith-v$version.apk"
    fun downloadUrl(version: UpdateVersion, name: String) = "https://github.com/$REPOSITORY/releases/download/v$version/$name"

    /** Returns null when the latest stable release is not newer than [installed]; assets are then not inspected. */
    fun parse(bytes: ByteArray, installed: UpdateVersion?): UpdateRelease? {
        val root = UpdateJson.parse(bytes) as? Map<*, *> ?: throw notVerified()
        if (root["draft"] != false || root["prerelease"] != false) throw notVerified()
        val version = UpdateVersion.fromTag(root["tag_name"] as? String) ?: throw notVerified()
        if (installed != null && version <= installed) return null
        val assets = root["assets"] as? List<*> ?: throw notVerified()
        return UpdateRelease(version, asset(assets, apkName(version), version, MAX_APK, apkTypes),
            asset(assets, SUMS_NAME, version, MAX_SUMS, sumsTypes))
    }

    private fun asset(assets: List<*>, name: String, version: UpdateVersion, max: Long, types: Set<String>): UpdateAsset {
        val matches = assets.filter { (it as? Map<*, *>)?.get("name") == name }
        if (matches.isEmpty()) throw UpdateFailure(UpdateProblem.NOT_FOUND)
        if (matches.size != 1) throw notVerified()
        val asset = matches.single() as Map<*, *>
        if (asset["state"] != "uploaded") throw UpdateFailure(UpdateProblem.NOT_FOUND)
        val size = asset["size"] as? Long ?: throw notVerified()
        if (size !in 1..max) throw notVerified()
        val type = (asset["content_type"] as? String)?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (type !in types) throw notVerified()
        val url = downloadUrl(version, name)
        if (asset["browser_download_url"] != url) throw notVerified()
        val digest = sha256Digest(asset["digest"] as? String) ?: throw notVerified()
        return UpdateAsset(name, URI(url), size, digest)
    }

    fun sha256Digest(text: String?): String? {
        if (text == null || !text.startsWith("sha256:")) return null
        val value = text.substring(7)
        return if (hex.matches(value)) value.lowercase(Locale.ROOT) else null
    }

    fun checksumFor(bytes: ByteArray, name: String): String {
        val text = strictText(bytes) ?: throw notVerified()
        val found = text.split('\n').map { it.removeSuffix("\r") }.filter { it.isNotBlank() }.mapNotNull { line ->
            val match = sumsLine.matchEntire(line) ?: throw notVerified()
            if (match.groupValues[2] == name) match.groupValues[1].lowercase(Locale.ROOT) else null
        }
        return found.singleOrNull() ?: throw notVerified()
    }

    private fun notVerified() = UpdateFailure(UpdateProblem.NOT_VERIFIED)
}

internal fun strictText(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) { null }

internal object UpdateJson {
    private const val MAX_DEPTH = 16
    private val factory: JsonFactory = JsonFactoryBuilder()
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(MAX_DEPTH).maxStringLength(512 * 1024).maxNumberLength(32).maxNameLength(256)
            .maxDocumentLength(AppUpdateRelease.MAX_METADATA).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()

    fun parse(bytes: ByteArray): Any? {
        if (bytes.size > AppUpdateRelease.MAX_METADATA) throw invalid()
        val text = strictText(bytes) ?: throw invalid()
        try {
            factory.createParser(text).use { parser ->
                val value = read(parser, parser.nextToken() ?: throw invalid(), 1)
                if (parser.nextToken() != null) throw invalid()
                return value
            }
        } catch (_: IOException) { throw invalid() }
    }

    private fun read(parser: JsonParser, token: JsonToken, depth: Int): Any? = when (token) {
        JsonToken.START_OBJECT -> {
            if (depth > MAX_DEPTH) throw invalid()
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                val next = parser.nextToken() ?: throw invalid()
                if (next == JsonToken.END_OBJECT) break
                if (next != JsonToken.FIELD_NAME) throw invalid()
                val name = parser.currentName() ?: throw invalid()
                if (map.containsKey(name)) throw invalid()
                map[name] = read(parser, parser.nextToken() ?: throw invalid(), depth + 1)
            }
            map
        }
        JsonToken.START_ARRAY -> {
            if (depth > MAX_DEPTH) throw invalid()
            val list = ArrayList<Any?>()
            while (true) {
                val next = parser.nextToken() ?: throw invalid()
                if (next == JsonToken.END_ARRAY) break
                if (list.size >= 1_000) throw invalid()
                list += read(parser, next, depth + 1)
            }
            list
        }
        JsonToken.VALUE_STRING -> parser.text
        JsonToken.VALUE_NUMBER_INT -> when (parser.numberType) {
            JsonParser.NumberType.INT, JsonParser.NumberType.LONG -> parser.longValue
            else -> throw invalid()
        }
        JsonToken.VALUE_NUMBER_FLOAT -> parser.doubleValue
        JsonToken.VALUE_TRUE -> true
        JsonToken.VALUE_FALSE -> false
        JsonToken.VALUE_NULL -> null
        else -> throw invalid()
    }

    private fun invalid() = UpdateFailure(UpdateProblem.NOT_VERIFIED)
}

internal class UpdateResponse(
    val status: Int, val location: String?, val contentType: String?, val contentLength: Long,
    val body: InputStream, private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() { runCatching { body.close() }; onClose() }
}

internal fun interface UpdateTransport {
    fun open(uri: URI, api: Boolean): UpdateResponse

    fun open(uri: URI, api: Boolean, request: UpdateRequest): UpdateResponse = open(uri, api).also { response ->
        request.bind { response.close() }
    }
}

internal class UpdateRequest(private val deadline: Long, private val now: () -> Long) : Closeable {
    private var closed = false
    private var disconnect: (() -> Unit)? = null

    fun remainingMillis(): Int {
        val remaining = (deadline - now()) / 1_000_000
        if (remaining <= 0) throw UpdateFailure(UpdateProblem.NETWORK)
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun bind(action: () -> Unit) {
        val rejected = synchronized(this) {
            if (closed) true else { disconnect = action; false }
        }
        if (rejected) { action(); throw IOException("Update request closed") }
    }

    override fun close() {
        val action = synchronized(this) { closed = true; disconnect.also { disconnect = null } }
        runCatching { action?.invoke() }
    }
}

internal object HttpsUpdateTransport : UpdateTransport {
    override fun open(uri: URI, api: Boolean): UpdateResponse =
        open(uri, api, UpdateRequest(System.nanoTime() + 35_000_000_000L, System::nanoTime))

    override fun open(uri: URI, api: Boolean, request: UpdateRequest): UpdateResponse {
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        request.bind { connection.disconnect() }
        connection.instanceFollowRedirects = false
        connection.connectTimeout = minOf(15_000, request.remainingMillis())
        connection.readTimeout = minOf(20_000, request.remainingMillis())
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "Sunlith-Android")
        if (api) {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        } else {
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream else connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
            return UpdateResponse(status, connection.getHeaderField("Location"), connection.contentType, connection.contentLengthLong, body) {
                connection.disconnect()
            }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }
}

/** Follows at most a few HTTPS redirects, and only between the GitHub hosts that serve this release. */
internal class UpdateFetcher(private val transport: UpdateTransport, private val now: () -> Long = System::nanoTime) {
    enum class Kind(val first: Set<String>, val redirects: Set<String>, val api: Boolean, val seconds: Long) {
        METADATA(setOf("api.github.com"), setOf("api.github.com"), true, 30),
        ASSET(setOf("github.com"), setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"), false, 600),
    }

    suspend fun read(uri: URI, kind: Kind, max: Long): ByteArray {
        val output = ByteArrayOutputStream()
        stream(uri, kind, max, exact = false, sink = { buffer, count -> output.write(buffer, 0, count) })
        return output.toByteArray()
    }

    suspend fun stream(uri: URI, kind: Kind, max: Long, exact: Boolean, sink: (ByteArray, Int) -> Unit, progress: (Long) -> Unit = {}): Long {
        val deadline = now() + kind.seconds * 1_000_000_000L
        val request = UpdateRequest(deadline, now)
        return coroutineScope {
            // Register cancellation before connecting, and close sockets when the overall deadline expires.
            val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { delay(kind.seconds * 1_000) } finally { request.close() }
            }
            try {
                val response = open(uri, kind, request)
                request.bind { response.close() }
                val mime = response.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                if (mime == "text/html") throw UpdateFailure(UpdateProblem.NETWORK)
                if (response.contentLength > max || (exact && response.contentLength >= 0 && response.contentLength != max)) {
                    throw UpdateFailure(UpdateProblem.NOT_VERIFIED)
                }
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    if (now() > deadline) throw UpdateFailure(UpdateProblem.NETWORK)
                    val count = network { response.body.read(buffer) }
                    coroutineContext.ensureActive()
                    if (count < 0) break
                    if (total + count > max) throw UpdateFailure(UpdateProblem.NOT_VERIFIED)
                    sink(buffer, count)
                    total += count
                    progress(total)
                }
                if (exact && total != max) throw UpdateFailure(UpdateProblem.NETWORK)
                coroutineContext.ensureActive()
                request.remainingMillis()
                total
            } finally {
                closer.cancel()
                request.close()
            }
        }
    }

    private suspend fun open(start: URI, kind: Kind, request: UpdateRequest): UpdateResponse {
        var uri = start
        var allowed = kind.first
        repeat(MAX_HOPS + 1) {
            if (!safe(uri, allowed)) throw UpdateFailure(UpdateProblem.NOT_VERIFIED)
            coroutineContext.ensureActive()
            request.remainingMillis()
            val response = network { transport.open(uri, kind.api, request) }
            request.remainingMillis()
            when (response.status) {
                in 200..299 -> return response
                301, 302, 303, 307, 308 -> {
                    val location = response.location
                    response.close()
                    uri = try { uri.resolve(URI(location ?: throw UpdateFailure(UpdateProblem.NOT_VERIFIED))) }
                    catch (_: URISyntaxException) { throw UpdateFailure(UpdateProblem.NOT_VERIFIED) }
                    allowed = kind.redirects
                }
                else -> {
                    response.close()
                    throw UpdateFailure(when (response.status) {
                        403, 429 -> UpdateProblem.RATE_LIMITED
                        404, 410 -> UpdateProblem.NOT_FOUND
                        else -> UpdateProblem.NETWORK
                    })
                }
            }
        }
        throw UpdateFailure(UpdateProblem.NOT_VERIFIED)
    }

    private suspend inline fun <T> network(block: () -> T): T = try { block() } catch (e: IOException) {
        coroutineContext.ensureActive()
        throw UpdateFailure(UpdateProblem.NETWORK)
    }

    companion object {
        const val MAX_HOPS = 4
        fun safe(uri: URI, hosts: Set<String>): Boolean = uri.isAbsolute && uri.scheme == "https" && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.host?.lowercase(Locale.ROOT) in hosts
    }
}

internal data class PackageFacts(
    val packageName: String, val versionName: String?, val versionCode: Long,
    val signers: Set<String>, val signerHistory: Set<String>, val multipleSigners: Boolean,
)

internal enum class CandidateCheck { NEWER, NOT_NEWER, INVALID }

internal fun signersCompatible(installed: PackageFacts, candidate: PackageFacts): Boolean {
    if (installed.signers.isEmpty() || candidate.signers.isEmpty()) return false
    if (installed.signers == candidate.signers) return true
    if (installed.multipleSigners || candidate.multipleSigners) return false
    return installed.signers.single() in candidate.signerHistory
}

internal fun checkCandidate(installed: PackageFacts, candidate: PackageFacts?, expected: UpdateVersion): CandidateCheck = when {
    candidate == null -> CandidateCheck.INVALID
    installed.packageName != APP_PACKAGE || candidate.packageName != APP_PACKAGE -> CandidateCheck.INVALID
    !signersCompatible(installed, candidate) -> CandidateCheck.INVALID
    candidate.versionName != expected.toString() -> CandidateCheck.INVALID
    candidate.versionCode <= installed.versionCode -> CandidateCheck.NOT_NEWER
    else -> CandidateCheck.NEWER
}

internal interface UpdatePlatform {
    fun installed(): PackageFacts
    fun archive(file: File): PackageFacts?
    fun canInstallPackages(): Boolean
}

@Suppress("DEPRECATION")
internal class AndroidUpdatePlatform(private val context: Context) : UpdatePlatform {
    private val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    override fun installed(): PackageFacts =
        facts(context.packageManager.getPackageInfo(context.packageName, flags)) ?: throw UpdateFailure(UpdateProblem.NOT_VERIFIED)

    override fun archive(file: File): PackageFacts? =
        runCatching { context.packageManager.getPackageArchiveInfo(file.path, flags or PackageManager.GET_SIGNATURES)?.let(::facts) }.getOrNull()

    override fun canInstallPackages() = context.packageManager.canRequestPackageInstalls()

    private fun facts(info: PackageInfo): PackageFacts? {
        val code: Long
        val current: List<Signature>
        val history: List<Signature>
        val multiple: Boolean
        if (Build.VERSION.SDK_INT >= 28) {
            code = info.longVersionCode
            val signing = info.signingInfo ?: return null
            multiple = signing.hasMultipleSigners()
            current = signing.apkContentsSigners?.toList().orEmpty()
            history = if (multiple) current else signing.signingCertificateHistory?.toList().orEmpty()
        } else {
            code = info.versionCode.toLong()
            current = info.signatures?.toList().orEmpty()
            multiple = current.size > 1
            history = current
        }
        return PackageFacts(info.packageName ?: return null, info.versionName, code,
            current.map { sha256(it.toByteArray()) }.toSet(), history.map { sha256(it.toByteArray()) }.toSet(), multiple)
    }
}

internal data class PendingUpdate(val version: UpdateVersion, val versionCode: Long, val size: Long, val sha256: String)

internal interface PendingStore {
    fun load(): PendingUpdate?
    fun save(pending: PendingUpdate?)
}

internal class PrefsPendingStore(private val prefs: SharedPreferences) : PendingStore {
    override fun load(): PendingUpdate? {
        val version = UpdateVersion.parse(prefs.getString("version", null)) ?: return null
        val sha = prefs.getString("sha256", null)?.takeIf { it.length == 64 } ?: return null
        return PendingUpdate(version, prefs.getLong("versionCode", 0), prefs.getLong("size", 0), sha)
    }

    override fun save(pending: PendingUpdate?) {
        val editor = prefs.edit().clear()
        if (pending != null) editor.putString("version", pending.version.toString()).putLong("versionCode", pending.versionCode)
            .putLong("size", pending.size).putString("sha256", pending.sha256)
        editor.commit()
    }
}

internal sealed interface CheckResult {
    data object UpToDate : CheckResult
    data class Available(val release: UpdateRelease) : CheckResult
}

internal sealed interface DownloadResult {
    data object UpToDate : DownloadResult
    data class Ready(val pending: PendingUpdate) : DownloadResult
}

/** Manual updater. Nothing here runs unless the user asks; [restore] and [cleanUpAfterReplace] stay offline. */
internal class AppUpdater(
    private val transport: UpdateTransport,
    val platform: UpdatePlatform,
    val dir: File,
    private val store: PendingStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val fetcher = UpdateFetcher(transport)

    fun apkFile(version: UpdateVersion) = File(dir, AppUpdateRelease.apkName(version))

    suspend fun check(): CheckResult = withContext(io) {
        val installed = platform.installed()
        val bytes = fetcher.read(URI(AppUpdateRelease.LATEST_URL), UpdateFetcher.Kind.METADATA, AppUpdateRelease.MAX_METADATA)
        val release = AppUpdateRelease.parse(bytes, UpdateVersion.parse(installed.versionName))
        if (release == null) { discard(); CheckResult.UpToDate } else CheckResult.Available(release)
    }

    suspend fun download(release: UpdateRelease, progress: (Long, Long) -> Unit): DownloadResult = withContext(io) {
        val installed = platform.installed()
        discard()
        if (!dir.isDirectory && !dir.mkdirs()) throw UpdateFailure(UpdateProblem.STORAGE)
        if (dir.usableSpace < release.apk.size + SPACE_MARGIN) throw UpdateFailure(UpdateProblem.STORAGE)
        val sums = fetcher.read(release.sums.url, UpdateFetcher.Kind.ASSET, release.sums.size)
        if (sums.size.toLong() != release.sums.size || sha256(sums) != release.sums.sha256) throw UpdateFailure(UpdateProblem.NOT_VERIFIED)
        if (AppUpdateRelease.checksumFor(sums, release.apk.name) != release.apk.sha256) throw UpdateFailure(UpdateProblem.NOT_VERIFIED)

        val temp = File(dir, AppUpdateRelease.apkName(release.version) + ".download")
        val target = apkFile(release.version)
        var kept = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                FileOutputStream(temp).use { out ->
                    fetcher.stream(release.apk.url, UpdateFetcher.Kind.ASSET, release.apk.size, exact = true,
                        sink = { buffer, count -> out.write(buffer, 0, count); digest.update(buffer, 0, count) },
                        progress = { progress(it, release.apk.size) })
                    out.fd.sync()
                }
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw UpdateFailure(UpdateProblem.STORAGE)
            }
            if (hex(digest.digest()) != release.apk.sha256) throw UpdateFailure(UpdateProblem.NOT_VERIFIED)
            if (!temp.renameTo(target)) throw UpdateFailure(UpdateProblem.STORAGE)
            coroutineContext.ensureActive()
            val candidate = platform.archive(target)
            when (checkCandidate(installed, candidate, release.version)) {
                CandidateCheck.INVALID -> throw UpdateFailure(UpdateProblem.NOT_VERIFIED)
                CandidateCheck.NOT_NEWER -> DownloadResult.UpToDate
                CandidateCheck.NEWER -> {
                    val pending = PendingUpdate(release.version, candidate!!.versionCode, release.apk.size, release.apk.sha256)
                    store.save(pending)
                    kept = true
                    DownloadResult.Ready(pending)
                }
            }
        } finally {
            temp.delete()
            if (!kept) target.delete()
        }
    }

    /** Re-checks the saved download from disk before it can be reused. */
    suspend fun restore(): PendingUpdate? = withContext(io) {
        val pending = store.load()
        if (pending == null) { discard(); return@withContext null }
        val file = apkFile(pending.version)
        val valid = runCatching {
            file.isFile && file.length() == pending.size && fileSha256(file) == pending.sha256 &&
                platform.archive(file).let { candidate ->
                    checkCandidate(platform.installed(), candidate, pending.version) == CandidateCheck.NEWER &&
                        candidate?.versionCode == pending.versionCode
                }
        }.getOrDefault(false)
        if (valid) pending else { discard(); null }
    }

    fun installedAtLeast(pending: PendingUpdate): Boolean = platform.installed().versionCode >= pending.versionCode

    /** Runs after Android replaces the app; no hashing, only a version comparison. */
    fun cleanUpAfterReplace() {
        val pending = store.load()
        if (pending == null || runCatching { installedAtLeast(pending) }.getOrDefault(false)) discard()
    }

    fun discard() {
        store.save(null)
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    suspend fun discardNow() = withContext(io) { discard() }

    companion object {
        const val DIR = "app-update"
        private const val SPACE_MARGIN = 16L shl 20

        fun create(context: Context): AppUpdater {
            val app = context.applicationContext
            return AppUpdater(HttpsUpdateTransport, AndroidUpdatePlatform(app), File(app.cacheDir, DIR),
                PrefsPendingStore(app.getSharedPreferences("app_update", Context.MODE_PRIVATE)))
        }
    }
}

internal fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return hex(digest.digest())
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val version: String) : AppUpdateState
    data class Downloading(val version: String, val bytes: Long, val total: Long) : AppUpdateState
    data class Preparing(val version: String) : AppUpdateState
    data class NeedsPermission(val version: String) : AppUpdateState
    data class Ready(val version: String) : AppUpdateState
    data class Installing(val version: String) : AppUpdateState
    data class Failed(val problem: UpdateProblem) : AppUpdateState
}

internal sealed interface UpdateCommand {
    data object OpenPermission : UpdateCommand
    data class OpenInstaller(val file: File) : UpdateCommand
}

/**
 * Holds the manual update flow. Creating it only re-checks a saved download; network work starts from [check] and [update].
 * Activity launches are handed to the visible UI as one-shot [command]s and are never repeated on resume.
 */
internal class AppUpdateController(private val updater: AppUpdater, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private val mutableCommand = MutableStateFlow<UpdateCommand?>(null)
    val command: StateFlow<UpdateCommand?> = mutableCommand.asStateFlow()
    private var job: Job? = null
    private var release: UpdateRelease? = null
    private var pending: PendingUpdate? = null
    private var attached = 0

    init {
        step {
            val restored = updater.restore()
            if (restored != null && mutableState.value == AppUpdateState.Idle) {
                pending = restored
                mutableState.value = AppUpdateState.Ready(restored.version.toString())
            }
        }
    }

    val busy: Boolean get() = job?.isActive == true

    fun attach() { attached++ }

    fun detach() {
        attached = (attached - 1).coerceAtLeast(0)
        if (attached > 0) return
        val dropped = mutableCommand.value
        mutableCommand.value = null
        val current = mutableState.value
        if (dropped is UpdateCommand.OpenInstaller && current is AppUpdateState.Installing) mutableState.value = AppUpdateState.Ready(current.version)
    }

    fun check() {
        if (busy) return
        when (mutableState.value) {
            AppUpdateState.Idle, AppUpdateState.UpToDate, is AppUpdateState.Failed -> Unit
            else -> return
        }
        mutableState.value = AppUpdateState.Checking
        step {
            when (val result = updater.check()) {
                CheckResult.UpToDate -> { release = null; pending = null; mutableState.value = AppUpdateState.UpToDate }
                is CheckResult.Available -> { release = result.release; mutableState.value = AppUpdateState.Available(result.release.version.toString()) }
            }
        }
    }

    fun retry() {
        if (busy || mutableState.value !is AppUpdateState.Failed) return
        if (pending == null) check() else step {
            val restored = updater.restore()
            pending = restored
            mutableState.value = if (restored != null) AppUpdateState.Ready(restored.version.toString())
            else AppUpdateState.Failed(UpdateProblem.NOT_VERIFIED)
        }
    }

    fun update() {
        val target = release ?: return
        if (mutableState.value !is AppUpdateState.Available) return
        val version = target.version.toString()
        mutableState.value = AppUpdateState.Downloading(version, 0, target.apk.size)
        step {
            var shown = -1L
            val result = try {
                updater.download(target) { bytes, total ->
                    val percent = bytes * 100 / total
                    if (percent != shown) {
                        shown = percent
                        val next = if (bytes >= total) AppUpdateState.Preparing(version) else AppUpdateState.Downloading(version, bytes, total)
                        mutableState.update { if (it is AppUpdateState.Downloading || it is AppUpdateState.Preparing) next else it }
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { updater.discardNow() }
                throw e
            }
            when (result) {
                DownloadResult.UpToDate -> { release = null; mutableState.value = AppUpdateState.UpToDate }
                is DownloadResult.Ready -> {
                    pending = result.pending
                    mutableState.value = AppUpdateState.Ready(version)
                    if (attached > 0) beginInstall(result.pending, openPermission = false)
                }
            }
        }
    }

    fun cancel() {
        val target = release ?: return
        if (mutableState.value !is AppUpdateState.Downloading) return
        job?.cancel()
        mutableState.value = AppUpdateState.Available(target.version.toString())
    }

    /** Re-checks the saved file, then asks Android for permission or opens its installer. Installing allows a manual reopen. */
    fun install() {
        val saved = pending ?: return
        if (busy) return
        when (mutableState.value) {
            is AppUpdateState.Ready, is AppUpdateState.NeedsPermission, is AppUpdateState.Installing -> Unit
            else -> return
        }
        mutableState.value = AppUpdateState.Preparing(saved.version.toString())
        step {
            val checked = updater.restore()
            if (checked == null) {
                pending = null
                mutableState.value = if (updater.installedAtLeast(saved)) AppUpdateState.UpToDate
                else AppUpdateState.Failed(UpdateProblem.NOT_VERIFIED)
            } else beginInstall(checked, openPermission = true)
        }
    }

    private fun beginInstall(saved: PendingUpdate, openPermission: Boolean) {
        val version = saved.version.toString()
        if (!updater.platform.canInstallPackages()) {
            mutableState.value = AppUpdateState.NeedsPermission(version)
            if (openPermission && attached > 0) mutableCommand.value = UpdateCommand.OpenPermission
        } else if (attached > 0) {
            mutableState.value = AppUpdateState.Installing(version)
            mutableCommand.value = UpdateCommand.OpenInstaller(updater.apkFile(saved.version))
        } else mutableState.value = AppUpdateState.Ready(version)
    }

    /** The UI calls this right before it starts the activity, so the same command is never launched twice. */
    fun consume(command: UpdateCommand): Boolean {
        if (mutableCommand.value != command) return false
        mutableCommand.value = null
        return true
    }

    fun onPermissionReturned() {
        val saved = pending ?: return
        if (mutableState.value !is AppUpdateState.NeedsPermission) return
        if (updater.platform.canInstallPackages()) beginInstall(saved, openPermission = false)
    }

    /** Android's result code says nothing reliable here; only the installed version can show the update worked. */
    fun onInstallerReturned() {
        val saved = pending ?: return
        if (mutableState.value !is AppUpdateState.Installing) return
        if (runCatching { updater.installedAtLeast(saved) }.getOrDefault(false)) {
            pending = null
            mutableState.value = AppUpdateState.UpToDate
            step { updater.discardNow() }
        } else mutableState.value = AppUpdateState.Ready(saved.version.toString())
    }

    fun onLaunchFailed() {
        mutableState.value = AppUpdateState.Failed(UpdateProblem.NO_INSTALLER)
    }

    suspend fun awaitIdle() {
        while (true) {
            val current = job ?: return
            current.join()
            if (current === job) return
        }
    }

    private fun step(block: suspend () -> Unit) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: UpdateFailure) { coroutineContext.ensureActive(); mutableState.value = AppUpdateState.Failed(e.problem) }
            catch (_: Exception) { coroutineContext.ensureActive(); mutableState.value = AppUpdateState.Failed(UpdateProblem.NETWORK) }
        }
    }
}

internal object AppUpdateIntents {
    const val APK_MIME = "application/vnd.android.package-archive"

    fun authority(context: Context) = "${context.packageName}.fileprovider"

    fun installer(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val system = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.distinct()
        chooseInstaller(system)?.let(intent::setPackage)
        return intent
    }

    fun chooseInstaller(systemPackages: List<String>): String? = systemPackages.singleOrNull()
        ?: systemPackages.firstOrNull { it == "com.google.android.packageinstaller" || it == "com.android.packageinstaller" }

    fun permission(context: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
}

class AppUpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result = goAsync()
        val app = context.applicationContext
        Thread {
            try { AppUpdater.create(app).cleanUpAfterReplace() } catch (_: Exception) { } finally { result.finish() }
        }.start()
    }
}
