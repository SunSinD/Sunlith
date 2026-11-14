package io.github.sunsind.sunlith.school

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Hooks the schedule sync uses. [items] reads only the local cache and never touches the network. */
internal interface SchoolNoticeProvider {
    fun items(record: SchoolRecord, term: SchoolTerm, english: Boolean): List<SchoolItem>
    /** Confirmed class liftings from the local cache, or null when no notice read has succeeded yet (keep existing marks). */
    fun classLifts(record: SchoolRecord, term: SchoolTerm): List<SchoolClassLift>? = null
    fun refreshSoon()
    suspend fun refreshForJob()
    fun clear()
}

internal data class SchoolMessagesCache(
    val account: String, val connection: String,
    val admin: List<SchoolAdminNotice> = emptyList(), val details: Map<String, SchoolAdminDetail> = emptyMap(),
    val adminAt: Long? = null, val adminStatus: SchoolAdminStatus = SchoolAdminStatus.NOT_LOADED,
    val seen: Map<String, String?> = emptyMap(),
) {
    fun json(): JSONObject = JSONObject().put("version", 1).put("account", account).put("connection", connection)
        .put("admin", JSONArray(admin.map { JSONObject().put("id", it.id).put("date", it.date.toString()).put("subject", it.subject).put("sender", it.sender).put("link", it.detailAvailable) }))
        .put("details", JSONObject().also { d -> details.forEach { (k, v) -> d.put(k, detailJson(v)) } })
        .put("seen", JSONObject().also { s -> seen.forEach { (id, body) -> s.put(id, body ?: JSONObject.NULL) } })
        .put("adminAt", adminAt ?: JSONObject.NULL).put("adminStatus", adminStatus.name)

    // Older caches used the visible list as their alert baseline. Migrate it without alerting again.
    fun history(): Map<String, String?> = if (seen.isNotEmpty()) seen else admin.associate { it.id to it.body?.let(::schoolHash) }

    fun remember(): SchoolMessagesCache {
        val history = LinkedHashMap(history())
        admin.forEach { message ->
            val fingerprint = message.body?.let(::schoolHash) ?: history[message.id]
            history.remove(message.id)
            history[message.id] = fingerprint
        }
        return copy(seen = history.entries.toList().takeLast(5_000).associate { it.toPair() })
    }

    fun arrivals(previous: SchoolMessagesCache): List<SchoolAdminNotice> {
        if (previous.adminAt == null) return emptyList()
        val history = previous.history()
        fun meaning(message: SchoolAdminNotice): SchoolCollegeNotice? =
            SchoolNoticeInterpreter.interpret(listOf(message)).singleOrNull()?.copy(text = "", detailRead = false, sender = "")
        val previousMessages = previous.admin.associateBy { it.id }
        return admin.filter { message ->
            !history.containsKey(message.id) || (message.body != null && when {
                history[message.id] != null -> history[message.id] != schoolHash(message.body)
                else -> meaning(message)?.let { it != previousMessages[message.id]?.let(::meaning) } == true
            })
        }
    }

    // Keep whole bodies, newest first. A body that cannot fit is shown explicitly as title only.
    fun bounded(): SchoolMessagesCache {
        val kept = linkedMapOf<String, SchoolAdminDetail>()
        var bytes = copy(details = emptyMap()).json().toString().toByteArray(Charsets.UTF_8).size + 29
        admin.sortedByDescending { it.date }.forEach { message ->
            details[message.id]?.let { detail ->
                val entry = JSONObject().put(message.id, detailJson(detail)).toString().toByteArray(Charsets.UTF_8).size - 2
                val added = entry + if (kept.isEmpty()) 0 else 1
                if (bytes + added <= SchoolMessagesStore.MAX_BYTES) { kept[message.id] = detail; bytes += added }
            }
        }
        return copy(details = kept, admin = admin.map { it.copy(body = kept[it.id]?.body, category = kept[it.id]?.category.orEmpty()) })
    }
    companion object {
        private fun detailJson(v: SchoolAdminDetail) = JSONObject().put("date", v.date.toString()).put("subject", v.subject)
            .put("source", v.source).put("category", v.category).put("body", v.body)
        fun read(j: JSONObject): SchoolMessagesCache {
            schoolRequire(j.getInt("version") == 1)
            val details = j.getJSONObject("details").let { d -> d.keys().asSequence().associateWith { k -> d.getJSONObject(k).let { v -> SchoolAdminDetail(LocalDate.parse(v.getString("date")), v.getString("subject"), v.getString("source"), v.getString("category"), v.getString("body")) } } }
            val admin = j.getJSONArray("admin").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { r ->
                val id = r.getString("id"); val detail = details[id]
                SchoolAdminNotice(id, LocalDate.parse(r.getString("date")), r.getString("subject"), r.getString("sender"), detail?.category.orEmpty(), detail?.body, r.getBoolean("link"))
            } } }
            return SchoolMessagesCache(j.getString("account"), j.getString("connection"), admin, details,
                if (j.isNull("adminAt")) null else j.getLong("adminAt"), SchoolAdminStatus.valueOf(j.getString("adminStatus")),
                j.optJSONObject("seen")?.let { s -> s.keys().asSequence().associateWith { id -> if (s.isNull(id)) null else s.getString(id) } }.orEmpty())
        }
    }
}

internal class SchoolMessagesStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "school-messages-v1.bin"))
    fun load(): SchoolMessagesCache? = synchronized(LOCK) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
        try {
            val data = file.openRead().use { input -> schoolRequire(input.available() <= MAX_BYTES); input.readBytes() }
            schoolRequire(data.size >= 29 && data[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
            cipher.updateAAD(AAD)
            val json = JSONObject(String(cipher.doFinal(data.copyOfRange(13, data.size)), Charsets.UTF_8))
            SchoolMessagesCache.read(json).also { if (json.has("mail") || json.has("folders")) save(it) }
        } catch (_: Exception) { clear(); null }
    }
    fun save(cache: SchoolMessagesCache) = synchronized(LOCK) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(cache.json().toString().toByteArray(Charsets.UTF_8))
        schoolRequire(bytes.size <= MAX_BYTES)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = synchronized(LOCK) { file.delete() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    companion object {
        internal val LOCK = Any()
        internal const val MAX_BYTES = 8 * 1024 * 1024
        private const val ALIAS = "sunlith-school-messages-v1"
        private val AAD = "sunlith-school-messages-v1".toByteArray(Charsets.UTF_8)
    }
}

internal class SchoolMessagesManager(
    private val context: Context,
    private val clientFactory: (String, String) -> SchoolPortalClient = { address, language ->
        SchoolPortalClient.routed(address, language, SchoolPortalNetwork.routes(context))
    },
    private val classicFactory: (SchoolPortalClient) -> SchoolClassicPortal = { client -> SchoolClassicPortal(client.base, SchoolPortalNetwork.routes(context)) },
    private val onNoticesChanged: () -> Unit = { SchoolScheduleManager.get(context).syncNotices() },
    private val onNewMessages: (SchoolRecord, List<SchoolAdminNotice>) -> Unit = { record, messages -> SchoolNotifications(context).messages(record, messages) },
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) : SchoolMessagesController, SchoolNoticeProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val store = SchoolMessagesStore(context)
    private val mutableState = MutableStateFlow(SchoolMessagesState())
    override val state: StateFlow<SchoolMessagesState> = mutableState.asStateFlow()
    @Volatile private var cache: SchoolMessagesCache? = null
    @Volatile private var session: Pair<String, SchoolLogin>? = null
    @Volatile private var lastAttempt: Long? = null
    @Volatile private var signInBlockedToken: String? = null
    @Volatile private var active: Job? = null

    init { scope.launch { synchronized(SchoolMessagesStore.LOCK) { cache = current(record()) }; publish(record()) } }

    override suspend fun refresh() { scope.async { sync(force = true) }.await() }
    override fun refreshSoon() { scope.launch { sync(force = false) } }
    override suspend fun refreshForJob() { scope.async { sync(force = false, waitForActive = true) }.await() }

    /** Cache only. Before any successful administration read, notice events already in the calendar are kept as they are. */
    override fun items(record: SchoolRecord, term: SchoolTerm, english: Boolean): List<SchoolItem> {
        val cached = synchronized(SchoolMessagesStore.LOCK) { cache?.takeIf { it.account == record.account && it.connection == record.connection } }
        val today = LocalDate.now(ZoneId.of(record.zone))
        val inTerm = { item: SchoolItem -> !item.date.isBefore(term.start) && !item.date.isAfter(term.end) }
        if (cached?.adminAt == null) return record.baseline.filter { it.kind == SchoolNoticeInterpreter.NOTICE_KIND && inTerm(it) }
        val notices = SchoolNoticeInterpreter.interpret(cached.admin)
        val unknownDates = unresolvedNoticeDates(cached.admin, notices, record.baseline)
        val preserved = record.baseline.filter { it.kind == SchoolNoticeInterpreter.NOTICE_KIND && it.date in unknownDates }
        val preservedIds = preserved.map { it.id }.toSet()
        return (SchoolNoticeInterpreter.items(notices, english = false)
            .filterNot { it.id in preservedIds } + preserved)
            .filter { inTerm(it) && !it.date.isBefore(today.minusDays(14)) }
    }

    override fun classLifts(record: SchoolRecord, term: SchoolTerm): List<SchoolClassLift>? {
        val cached = synchronized(SchoolMessagesStore.LOCK) { cache?.takeIf { it.account == record.account && it.connection == record.connection } }
        if (cached?.adminAt == null) return null
        val notices = SchoolNoticeInterpreter.interpret(cached.admin)
        val unknownDates = unresolvedNoticeDates(cached.admin, notices, record.baseline)
        // An unread or missing source is not a withdrawal. Keep only marks already on each class for that day; a
        // newly arriving class must not gain a cancellation without a read confirmation.
        return (SchoolNoticeInterpreter.lifts(notices) + unknownDates.map { SchoolClassLift(it, null, it, preserveOnly = true) })
            .filter { !it.date.isBefore(term.start) && !it.date.isAfter(term.end) }
    }

    private fun unresolvedNoticeDates(admin: List<SchoolAdminNotice>, notices: List<SchoolCollegeNotice>, baseline: List<SchoolItem>): Set<LocalDate> {
        val knownDates = notices.filter { it.detailRead }.map { it.targetDate }.toSet()
        val titles = admin.filter { it.body == null }.map { it.subject }.toSet()
        val previousDates = baseline.filter { item -> item.kind == SchoolNoticeInterpreter.NOTICE_KIND &&
            item.notes.any { note -> titles.any { note == "Objet : $it" || note == "Subject: $it" } } }.map { it.date }
        val interpretedDates = notices.map { it.targetDate }.toSet()
        val missingDates = baseline.filter {
            it.kind == SchoolNoticeInterpreter.NOTICE_KIND || it.collegeLift.isNotEmpty()
        }.map { it.date }.filterNot { it in interpretedDates }
        return (notices.filter { !it.detailRead }.map { it.targetDate } + previousDates + missingDates)
            .filterNot { it in knownDates }.toSet()
    }

    override fun clear() {
        generation.incrementAndGet()
        active?.cancel()
        synchronized(SchoolMessagesStore.LOCK) { store.clear(); cache = null }
        session = null; lastAttempt = null; signInBlockedToken = null
        mutableState.value = SchoolMessagesState()
    }

    override suspend fun portalLinks(): List<SchoolPortalLink> = when (val result = loadPortalLinks()) {
        is SchoolPortalLinks.Loaded -> result.links
        is SchoolPortalLinks.Failed -> throw SchoolFailure(when (result.status) { SchoolAdminStatus.SIGN_IN -> SchoolError.SIGN_IN; SchoolAdminStatus.UNRECOGNIZED -> SchoolError.INVALID_DATA; SchoolAdminStatus.UNAVAILABLE -> SchoolError.UNAVAILABLE; else -> SchoolError.NETWORK })
    }

    override suspend fun loadPortalLinks(): SchoolPortalLinks = scope.async {
        val stamp = generation.get()
        val record = record()?.takeIf { it.disconnecting == null } ?: return@async SchoolPortalLinks.Failed(SchoolAdminStatus.NOT_LOADED)
        val client = clientFactory(record.portal, language())
        val result = try {
            SchoolPortalLinks.Loaded(withTimeout(60_000) { withSession(client, record) { token -> client.portalLinks(token) } }.map { SchoolPortalLink(it.first, it.second) })
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            diagnose("links", e)
            SchoolPortalLinks.Failed(when (reason(e)) { SchoolError.SIGN_IN, SchoolError.MFA_REQUIRED -> SchoolAdminStatus.SIGN_IN; SchoolError.NETWORK -> SchoolAdminStatus.NETWORK; SchoolError.UNAVAILABLE -> SchoolAdminStatus.UNAVAILABLE; else -> SchoolAdminStatus.UNRECOGNIZED })
        }
        val current = record()
        if (generation.get() == stamp && current?.account == record.account && current.connection == record.connection && current.disconnecting == null) {
            if (result is SchoolPortalLinks.Loaded) mutableState.value = mutableState.value.copy(portalLinksAvailable = result.links.isNotEmpty())
            result
        } else SchoolPortalLinks.Failed(SchoolAdminStatus.NOT_LOADED)
    }.await()

    override suspend fun resolvePortalLink(code: String): String? = scope.async {
        val stamp = generation.get()
        val record = record() ?: return@async null
        val client = clientFactory(record.portal, language())
        val link = withSession(client, record) { token ->
            if (client.portalLinks(token).none { it.first == code }) null else client.portalLink(token, code)
        }
        val current = record()
        if (generation.get() == stamp && current?.account == record.account && current.connection == record.connection && current.disconnecting == null) link else null
    }.await()

    private suspend fun sync(force: Boolean, waitForActive: Boolean = false) {
        if (waitForActive) mutex.lock() else if (!mutex.tryLock()) return
        try {
            val now = elapsedRealtime()
            if (!force && lastAttempt?.let { now - it < BACKGROUND_INTERVAL } == true) return
            val stamp = generation.get()
            val record = record()
            if (record == null || record.disconnecting != null) {
                synchronized(SchoolMessagesStore.LOCK) { store.clear(); cache = null }
                publish(null); return
            }
            if (!force && signInBlockedToken == record.token) return
            lastAttempt = now
            active = kotlinx.coroutines.currentCoroutineContext()[Job]
            mutableState.value = mutableState.value.copy(busy = true)
            var next = synchronized(SchoolMessagesStore.LOCK) { current(record) } ?: SchoolMessagesCache(record.account, record.connection)
            val previous = next
            val client = clientFactory(record.portal, language())
            val previousNotices = SchoolNoticeInterpreter.interpret(next.admin)
            try {
                val zone = ZoneId.of(record.zone)
                val result = withTimeout(120_000) { classicFactory(client).readAdministration(record.username, record.password, next.details, LocalDate.now(zone)) }
                next = if (result.outcome == SchoolAdminOutcome.OK) {
                    val details = result.details
                    next.copy(seen = next.history(), admin = result.rows.map { row ->
                        val d = details[row.id]
                        SchoolAdminNotice(row.id, row.date, row.subject, row.sender, d?.category.orEmpty(), d?.body, row.link != null)
                    }, details = details, adminAt = System.currentTimeMillis(), adminStatus = if (result.detailsStale) SchoolAdminStatus.NETWORK else SchoolAdminStatus.OK).remember()
                } else next.copy(adminStatus = SchoolAdminStatus.valueOf(result.outcome.name))
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                diagnose("admin", e)
                next = next.copy(adminStatus = when (reason(e)) { SchoolError.SIGN_IN -> SchoolAdminStatus.SIGN_IN; SchoolError.NETWORK -> SchoolAdminStatus.NETWORK; SchoolError.UNAVAILABLE -> SchoolAdminStatus.UNAVAILABLE; else -> SchoolAdminStatus.UNRECOGNIZED })
            }
            signInBlockedToken = if (next.adminStatus == SchoolAdminStatus.SIGN_IN) record.token else null
            val arrived = next.arrivals(previous)
            next = next.bounded()
            val committed = synchronized(SchoolMessagesStore.LOCK) {
                val latest = record()
                if (generation.get() != stamp || latest == null || latest.account != record.account || latest.connection != record.connection || latest.disconnecting != null) false
                else {
                    try {
                        store.save(next); cache = next; publish(latest)
                        if (arrived.isNotEmpty()) try { onNewMessages(latest, arrived) } catch (e: Exception) { diagnose("notification", e) }
                        true
                    }
                    catch (e: Exception) { diagnose("cache", e); mutableState.value = mutableState.value.copy(adminStatus = SchoolAdminStatus.STORAGE); false }
                }
            }
            if (!committed) return
            if (generation.get() == stamp && SchoolNoticeInterpreter.interpret(next.admin) != previousNotices) onNoticesChanged()
        } finally {
            active = null
            mutableState.value = mutableState.value.copy(busy = false)
            mutex.unlock()
        }
    }

    private suspend fun <T> withSession(client: SchoolPortalClient, record: SchoolRecord, block: suspend (String) -> T): T {
        suspend fun login(): String = client.login(record.username, record.password).also { session = record.account to it }.token
        val known = session?.takeIf { it.first == record.account && it.second.expiresAt > System.currentTimeMillis() + 60_000 }?.second?.token
            ?: record.token.takeIf { record.expiresAt > System.currentTimeMillis() + 60_000 } ?: login()
        return try { block(known) } catch (e: SchoolFailure) { if (e.reason != SchoolError.SIGN_IN) throw e; block(login()) }
    }

    private fun current(record: SchoolRecord?): SchoolMessagesCache? {
        if (record == null) { store.clear(); return null }
        val loaded = cache ?: store.load()
        if (loaded != null && (loaded.account != record.account || loaded.connection != record.connection)) { store.clear(); return null }
        return loaded
    }

    private fun publish(record: SchoolRecord?) {
        val c = if (record == null) null else synchronized(SchoolMessagesStore.LOCK) { cache?.takeIf { it.account == record.account && it.connection == record.connection } }
        if (record == null) { mutableState.value = SchoolMessagesState(busy = mutableState.value.busy); return }
        val notices = SchoolNoticeInterpreter.interpret(c?.admin.orEmpty())
        val today = LocalDate.now(ZoneId.of(record.zone))
        mutableState.value = mutableState.value.copy(
            connected = true,
            admin = c?.admin.orEmpty(), adminStatus = c?.adminStatus ?: SchoolAdminStatus.NOT_LOADED, adminUpdatedMillis = c?.adminAt,
            collegeNotices = notices.filter { !it.targetDate.isBefore(today.minusDays(14)) },
            suggestions = SchoolNoticeInterpreter.suggestions(c?.admin.orEmpty(), today, notices.map { it.targetDate }.toSet()),
            portalUrl = runCatching { SchoolPortalClient.normalize(record.portal).resolve("../login.asp").normalize().toString() }.getOrNull(),
        )
    }

    private fun record(): SchoolRecord? = try { SchoolStore(context).load() } catch (_: SchoolFailure) { null }
    private fun language() = context.resources.configuration.locales[0].language
    private fun reason(e: Exception) = when (e) {
        is SchoolFailure -> e.reason
        is IOException, is TimeoutCancellationException -> SchoolError.NETWORK
        is JSONException, is java.time.DateTimeException, is IllegalArgumentException -> SchoolError.INVALID_DATA
        else -> SchoolError.UNAVAILABLE
    }
    private fun diagnose(part: String, e: Exception) = Log.w("SunlithSchool", "Messages $part stopped type=${e.javaClass.simpleName}")

    companion object {
        private const val BACKGROUND_INTERVAL = 5 * 60_000L
        @Volatile private var instance: SchoolMessagesManager? = null
        fun get(context: Context): SchoolMessagesManager = instance ?: synchronized(this) {
            instance ?: SchoolMessagesManager(context.applicationContext).also { instance = it }
        }
    }
}
