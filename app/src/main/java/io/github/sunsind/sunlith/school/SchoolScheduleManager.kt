package io.github.sunsind.sunlith.school

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.sunsind.sunlith.system.AppRefresh
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import org.json.JSONException

class SchoolScheduleManager internal constructor(
    private val context: Context,
    private val writer: SchoolCalendarWriter = SchoolCalendarWriter(context),
    private val notices: SchoolNoticeProvider? = null,
    private val clientFactory: (String, String) -> SchoolPortalClient = { address, language ->
        SchoolPortalClient.routed(address, language, SchoolPortalNetwork.routes(context))
    },
) : SchoolScheduleController {
    private enum class Stage { START, RECOVER, PORTAL, PRUNE, PLAN, CALENDAR }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val generation = AtomicLong()
    private val store = SchoolStore(context)
    @Volatile private var stage = Stage.START
    private val notifier = SchoolNotifications(context)
    private val mutableState = MutableStateFlow(SchoolScheduleState())
    override val state: StateFlow<SchoolScheduleState> = mutableState.asStateFlow()
    private var record: SchoolRecord? = null
    private var pending: SchoolFeed? = null
    private var pendingPlan: SchoolJournal? = null
    private var calendarChoices: List<SchoolCalendarChoice> = emptyList()
    private var calendarsLoaded = false
    private var defaultCalendar: Long? = null
    @Volatile private var activeRequest: Job? = null
    @Volatile private var foreground: Job? = null
    @Volatile private var foregroundWanted = false
    @Volatile private var disconnectRequested = false
    private var lastAttempt = -THROTTLE

    init {
        scope.launch {
            try {
                mutex.withLock {
                    try {
                        record = store.load()
                        recover()
                        publish()
                        if (record != null) schedule()
                    } catch (e: Exception) { publish(); mutableState.value = mutableState.value.copy(error = error(e).also { diagnose(it, e) }, connected = record != null) }
                }
            } finally { ready.complete(Unit) }
        }
    }

    override suspend fun connect(portalAddress: String, username: String, password: String, timeZone: String, calendarId: Long?) {
        val requestedGeneration = generation.get()
        scope.async {
            ready.await()
            mutex.withLock {
                operation {
                    recover()
                    stage = Stage.START
                    val stamp = requestedGeneration
                    if (generation.get() != stamp) throw CancellationException()
                    val zone = try { ZoneId.of(timeZone) } catch (_: Exception) { throw SchoolFailure(SchoolError.INVALID_DATA) }
                    val client = clientFactory(portalAddress, language())
                    val destination = writer.destination(calendarId ?: record?.calendarId)
                    val previous = record
                    if (previous != null && (previous.portal != client.base.toString() || previous.username != username.trim() || previous.calendarId != destination || previous.zone != zone.id)) throw SchoolFailure(SchoolError.INVALID_DATA)
                    stage = Stage.PORTAL
                    if (previous == null) notices?.clear()
                    val (login, fetched) = withTimeout(180_000) {
                        val login = client.login(username.trim(), password)
                        login to client.fetch(login.token, zone, previous?.baseline.orEmpty(), previous?.initialized == true, previous?.termKey.orEmpty())
                    }
                    currentCoroutineContext().ensureActive()
                    if (generation.get() != stamp) throw CancellationException()
                    if (previous != null && previous.account != fetched.account) throw SchoolFailure(SchoolError.SIGN_IN)
                    val connected = (previous ?: SchoolRecord(client.base.toString(), username.trim(), password, zone.id, destination,
                        fetched.account, UUID.randomUUID().toString(), login.token, login.expiresAt, calendarIdentity = writer.calendarIdentity(destination)))
                        .copy(password = password, token = login.token, expiresAt = login.expiresAt, term = fetched.term.name, termSource = fetched.term)
                    store.save(connected)
                    record = connected
                    lastAttempt = SystemClock.elapsedRealtime()
                    val feed = withNotices(fetched, connected)
                    applyFeed(feed, false, stamp)
                    notices?.refreshSoon()
                    schedule()
                    if (foregroundWanted && foreground?.isActive != true) onForeground()
                }
            }
        }.await()
    }
    override suspend fun refresh() {
        val stamp = generation.get()
        scope.async { refreshInternal(false, stamp) }.await()
    }
    internal suspend fun refreshForJob() { notices?.refreshForJob(); refreshInternal(false, waitForLock = true) }
    /** Called by the messages reader after college notices changed, so notice events update without waiting for the next periodic sync. */
    internal fun syncNotices() { scope.launch { syncCachedNotices() } }
    internal suspend fun syncCachedNotices() {
        val stamp = generation.get()
        ready.await()
        mutex.withLock {
            if (disconnectRequested || generation.get() != stamp) return@withLock
            operation {
                recover()
                val current = record ?: return@operation
                if (!current.initialized || pending != null) return@operation
                val dates = current.baseline.map { it.date }
                val today = LocalDate.now(ZoneId.of(current.zone))
                val term = current.termSource ?: SchoolTerm(current.termKey.ifBlank { "cached" }, current.term,
                    dates.minOrNull() ?: today.minusDays(180), dates.maxOrNull() ?: today.plusDays(184))
                val feed = withNotices(SchoolFeed(current.account, term, current.baseline), current)
                if (feed.items == current.baseline) return@operation
                stage = Stage.PLAN
                val plan = writer.plan(current, feed, today, noticeOnly = true).copy(termKey = current.termKey, termName = current.term,
                    at = current.lastSuccess ?: System.currentTimeMillis())
                val changes = SchoolDiff.changes(current.baseline, feed.items, today)
                withContext(NonCancellable) {
                    if (generation.get() != stamp) throw CancellationException()
                    stage = Stage.CALENDAR
                    val ledger = try { writer.commitSteps(current, plan) { store.save(it); record = it } }
                        finally { AppRefresh.refresh(context) }
                    // A notice refresh does not advance the agenda's successful-sync time or term identity.
                    val next = current.copy(ledger = ledger, baseline = feed.items, journal = null)
                    store.save(next); record = next
                    notifier.changes(next, changes)
                    publish()
                }
            }
        }
    }
    private suspend fun refreshInternal(throttled: Boolean, stamp: Long = generation.get(), waitForLock: Boolean = false) {
        ready.await()
        withContext(Dispatchers.IO) {
            if (waitForLock) mutex.lock() else if (!mutex.tryLock()) return@withContext
            try {
                if (record == null) return@withContext
                if (disconnectRequested) return@withContext
                if (generation.get() != stamp) return@withContext
                if (throttled && SystemClock.elapsedRealtime() - lastAttempt < THROTTLE) return@withContext
                lastAttempt = SystemClock.elapsedRealtime()
                operation {
                    recover()
                    if (record == null) { publish(); return@operation }
                    val feed = fetch()
                    currentCoroutineContext().ensureActive()
                    if (generation.get() != stamp) throw CancellationException()
                    applyFeed(feed, false, stamp)
                }
            } finally { mutex.unlock() }
        }
    }
    override suspend fun applyPendingChanges() {
        val requestedGeneration = generation.get()
        scope.async {
            ready.await()
            mutex.withLock {
                val expected = pending ?: return@withLock
                operation {
                    recover()
                    val stamp = requestedGeneration
                    if (generation.get() != stamp) throw CancellationException()
                    val fresh = fetch()
                    if (expected != fresh) { pending = fresh; showPending(fresh); return@operation }
                    val current = prepareRecord(fresh)
                    stage = Stage.PLAN
                    val newPlan = writer.plan(current, fresh, LocalDate.now(ZoneId.of(current.zone)))
                    if (pendingPlan?.linked != newPlan.linked) { pending = fresh; showPending(fresh, newPlan); return@operation }
                    applyFeed(fresh, true, stamp, newPlan)
                }
            }
        }.await()
    }
    override suspend fun discardPendingChanges() {
        ready.await()
        withContext(Dispatchers.IO) { mutex.withLock { pending = null; pendingPlan = null; notifier.clearReview(); publish() } }
    }
    override suspend fun setNotifications(preferences: SchoolNotificationPreferences) {
        updateNotifications { preferences }
    }
    override suspend fun setNotificationsEnabled(enabled: Boolean) = updateNotifications { it.copy(enabled = enabled) }
    override suspend fun setCategory(alert: SchoolAlert, enabled: Boolean) = updateNotifications {
        it.copy(categories = if (enabled) it.categories + alert else it.categories - alert)
    }
    private suspend fun updateNotifications(transform: (SchoolNotificationPreferences) -> SchoolNotificationPreferences) {
        scope.async {
            ready.await()
            mutex.withLock {
                val current = record ?: return@withLock
                try {
                    val next = current.copy(notifications = transform(current.notifications))
                    store.save(next); record = next
                    if (!next.notifications.enabled) notifier.clearAll()
                    mutableState.value = mutableState.value.copy(notifications = next.notifications)
                } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = SchoolError.STORAGE) }
            }
        }.await()
    }
    override suspend fun disconnect(removeSchoolEvents: Boolean) {
        disconnectRequested = true
        generation.incrementAndGet()
        activeRequest?.cancel()
        foreground?.cancel(); foreground = null
        scope.async {
            ready.await()
            mutex.withLock {
                try { operation {
                    val current = record ?: return@operation
                    withContext(NonCancellable) {
                        stage = Stage.CALENDAR
                        val disconnecting = current.copy(disconnecting = if (removeSchoolEvents) "remove" else "release")
                        store.save(disconnecting); record = disconnecting
                        writer.disconnect(disconnecting, removeSchoolEvents)
                        AppRefresh.refresh(context)
                        store.clear(); record = null; pending = null; pendingPlan = null
                        notices?.clear()
                        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
                        notifier.clearAll()
                        publish()
                    }
                } } finally { disconnectRequested = false }
            }
        }.await()
    }
    fun onForeground() {
        foregroundWanted = true
        if (foreground?.isActive == true) return
        foreground = scope.launch {
            ready.await()
            if (record == null) return@launch
            scheduleIfConnected()
            while (isActive) { notices?.refreshSoon(); refreshInternal(true); delay(60_000) }
        }
    }
    fun onBackground() { foregroundWanted = false; foreground?.cancel(); foreground = null }
    fun onOpen() {
        scope.launch {
            ready.await()
            mutex.withLock {
                calendarChoices = try { writer.calendars() } catch (_: Exception) { emptyList() }
                defaultCalendar = try { writer.destination(null) } catch (_: Exception) { null }
                calendarsLoaded = true
                mutableState.value = mutableState.value.copy(availableCalendars = calendarChoices, calendarId = record?.calendarId ?: defaultCalendar, calendarsLoaded = true)
            }
            if (record != null) refreshInternal(true)
        }
    }
    fun scheduleIfConnected() {
        scope.launch { ready.await(); mutex.withLock { if (record != null) runCatching { schedule() } } }
    }
    private suspend fun fetch(): SchoolFeed = withTimeout(180_000) {
        stage = Stage.PORTAL
        var current = record ?: throw SchoolFailure(SchoolError.SIGN_IN)
        val client = clientFactory(current.portal, language())
        val zone = ZoneId.of(current.zone)
        suspend fun login() {
            val result = client.login(current.username, current.password)
            current = current.copy(token = result.token, expiresAt = result.expiresAt)
            store.save(current); record = current
        }
        if (current.expiresAt <= System.currentTimeMillis() + 60_000) login()
        val feed = try { client.fetch(current.token, zone, current.baseline, current.initialized, current.termKey) } catch (e: SchoolFailure) {
            if (e.reason != SchoolError.SIGN_IN) throw e
            login(); client.fetch(current.token, zone, current.baseline, current.initialized, current.termKey)
        }
        if (feed.account != current.account) throw SchoolFailure(SchoolError.SIGN_IN)
        withNotices(feed, current)
    }
    /**
     * Adds dated college notice events from the local notice cache (no network here, so the schedule sync never waits on it).
     * If the notice reader fails or has never succeeded, notice events already applied stay as they were.
     */
    private fun withNotices(feed: SchoolFeed, current: SchoolRecord): SchoolFeed {
        val provider = notices ?: return feed
        val inTerm = { item: SchoolItem -> !item.date.isBefore(feed.term.start) && !item.date.isAfter(feed.term.end) }
        val extra = try { provider.items(current, feed.term, language() != "fr") } catch (e: Exception) {
            Log.w("SunlithSchool", "Notice cache skipped type=${e.javaClass.simpleName}")
            current.baseline.filter { it.kind == SchoolNoticeInterpreter.NOTICE_KIND }
        }.filter { it.kind == SchoolNoticeInterpreter.NOTICE_KIND && inTerm(it) }.distinctBy { it.id }
        val items = feed.items.filter { it.kind != SchoolNoticeInterpreter.NOTICE_KIND } + extra
        return feed.copy(items = items.sortedWith(compareBy<SchoolItem> { it.startMillis }.thenBy { it.id }))
    }
    private suspend fun applyFeed(feed: SchoolFeed, confirmed: Boolean, stamp: Long, preparedPlan: SchoolJournal? = null) {
        val current = if (preparedPlan == null) prepareRecord(feed) else record ?: throw SchoolFailure(SchoolError.SIGN_IN)
        val today = LocalDate.now(ZoneId.of(current.zone))
        stage = Stage.PLAN
        val plan = preparedPlan ?: writer.plan(current, feed, today)
        val coveredBaseline = current.baseline.filter { !it.date.isBefore(feed.term.start) && !it.date.isAfter(feed.term.end) }
        if (!confirmed && (plan.linked.isNotEmpty() || (feed.items.isEmpty() && coveredBaseline.any { !it.date.isBefore(today) }) || SchoolDiff.needsConfirmation(coveredBaseline, feed.items, today))) {
            pending = feed; showPending(feed, plan); return
        }
        currentCoroutineContext().ensureActive()
        if (generation.get() != stamp) throw CancellationException()
        val changes = if (current.initialized && current.termKey == feed.term.key) SchoolDiff.changes(coveredBaseline, feed.items, today) else emptyList()
        withContext(NonCancellable) {
            if (generation.get() != stamp) throw CancellationException()
            val summary = writer.summary(current, plan)
            val ledger = if (summary.added + summary.updated + summary.removed + summary.linked == 0) plan.after else {
                stage = Stage.CALENDAR
                try { writer.commitSteps(current, plan) { store.save(it); record = it } } finally { AppRefresh.refresh(context) }
            }
            val next = current.copy(term = feed.term.name, termKey = feed.term.key, termSource = feed.term, ledger = ledger, baseline = feed.items, initialized = true, lastSuccess = plan.at, lastFailure = null, journal = null, pendingReview = null)
            store.save(next); record = next; pending = null; pendingPlan = null
            notifier.clearFailure()
            notifier.clearReview()
            notifier.changes(next, changes)
            publish()
        }
    }
    private suspend fun prepareRecord(feed: SchoolFeed): SchoolRecord = withContext(NonCancellable) {
        val current = record ?: throw SchoolFailure(SchoolError.SIGN_IN)
        stage = Stage.PRUNE
        val ledger = writer.pruneHistory(current, feed.term, LocalDate.now(ZoneId.of(current.zone)))
        if (ledger == current.ledger) current else current.copy(ledger = ledger).also {
            store.save(it); record = it; AppRefresh.refresh(context)
        }
    }
    private suspend fun showPending(feed: SchoolFeed, plan: SchoolJournal? = null) {
        val current = if (plan == null) prepareRecord(feed) else record ?: return
        stage = Stage.PLAN
        val actual = plan ?: writer.plan(current, feed, LocalDate.now(ZoneId.of(current.zone)))
        val fingerprint = schoolHash(feed.term.key + "\n" + feed.items.joinToString("\n") { it.json().toString() })
        val newReview = current.pendingReview != fingerprint
        if (newReview || current.lastFailure != null) {
            val next = current.copy(pendingReview = fingerprint, lastFailure = null)
            store.save(next); record = next
            if (newReview && current.initialized) notifier.review(next, SchoolDiff.changes(
                current.baseline.filter { !it.date.isBefore(feed.term.start) && !it.date.isAfter(feed.term.end) },
                feed.items, LocalDate.now(ZoneId.of(current.zone)),
            ))
        }
        notifier.clearFailure()
        pendingPlan = actual
        publish()
        mutableState.value = mutableState.value.copy(pendingChanges = writer.summary(current, actual), termName = feed.term.name)
    }
    private fun recover() {
        val current = record ?: return
        stage = Stage.RECOVER
        if (current.disconnecting != null) {
            writer.disconnect(current, current.disconnecting == "remove")
            AppRefresh.refresh(context)
            store.clear(); record = null; pending = null; pendingPlan = null
            notices?.clear()
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            notifier.clearAll()
            publish()
            return
        }
        val journal = current.journal ?: return
        val adopted = writer.verify(current, journal) ?: if (writer.baselineUnchanged(current)) null
            else writer.verify(current, journal, allowContentChanges = true) ?: throw SchoolFailure(SchoolError.STORAGE)
        val next = when {
            adopted == null -> current.copy(journal = null)
            journal.complete -> current.copy(ledger = adopted, baseline = journal.baseline, termKey = journal.termKey, term = journal.termName.ifBlank { current.term }, initialized = true, lastSuccess = journal.at, journal = null, lastFailure = null, pendingReview = null)
            else -> current.copy(ledger = adopted, journal = null)
        }
        store.save(next); record = next
        if (adopted != null) { if (journal.complete) notifier.clearReview(); AppRefresh.refresh(context) }
    }
    private suspend fun operation(block: suspend () -> Unit) {
        activeRequest = currentCoroutineContext()[Job]
        stage = Stage.START
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try { block() }
        catch (e: CancellationException) { if (e is TimeoutCancellationException) failed(SchoolError.NETWORK.also { diagnose(it, e) }) else throw e }
        catch (e: Exception) { failed(error(e).also { diagnose(it, e) }) }
        finally {
            activeRequest = null
            mutableState.value = mutableState.value.copy(busy = false)
        }
    }
    private fun failed(reason: SchoolError) {
        val current = record
        if (current != null && current.lastFailure != reason.name && current.journal == null && current.disconnecting == null) {
            val next = current.copy(lastFailure = reason.name)
            try {
                store.save(next); record = next
                if (current.lastFailure == null || reason == SchoolError.SIGN_IN || reason == SchoolError.MFA_REQUIRED) notifier.failure(next, reason)
            } catch (_: Exception) { }
        }
        publish()
        mutableState.value = mutableState.value.copy(error = reason)
    }
    private fun publish() {
        val current = record
        if (current != null) {
            calendarChoices = try { writer.calendars() } catch (_: Exception) { emptyList() }
            calendarsLoaded = true
        }
        val choices = calendarChoices
        mutableState.value = if (current == null) SchoolScheduleState(availableCalendars = choices, calendarId = defaultCalendar, calendarsLoaded = calendarsLoaded, busy = mutableState.value.busy)
        else SchoolScheduleState(connected = true, busy = mutableState.value.busy, portalAddress = current.portal, accountName = current.username,
            termName = current.term, timeZone = current.zone, lastSuccessMillis = current.lastSuccess, calendarId = current.calendarId,
            availableCalendars = choices, calendarsLoaded = calendarsLoaded, eventCount = current.ledger.size, notifications = current.notifications,
            pendingChanges = if (pending != null) mutableState.value.pendingChanges else null)
    }
    private fun schedule() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, SchoolSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15 * 60_000L).build())
    }
    private fun language() = context.resources.configuration.locales[0].language
    private fun error(e: Exception): SchoolError {
        val reason = when (e) {
            is SchoolFailure -> e.reason
            is IOException -> SchoolError.NETWORK
            is JSONException, is java.time.DateTimeException, is IllegalArgumentException -> SchoolError.INVALID_DATA
            is SecurityException -> SchoolError.CALENDAR_PERMISSION
            else -> SchoolError.STORAGE
        }
        return if (reason == SchoolError.INVALID_DATA && stage in LOCAL_STAGES) SchoolError.STORAGE else reason
    }
    private fun diagnose(reason: SchoolError, e: Exception) {
        Log.w("SunlithSchool", "Sync stopped stage=${stage.name.lowercase()} reason=${reason.name} type=${e.javaClass.simpleName}")
    }
    companion object {
        private const val THROTTLE = 5 * 60_000L
        private val LOCAL_STAGES = setOf(Stage.RECOVER, Stage.PRUNE, Stage.PLAN, Stage.CALENDAR)
        private const val JOB_ID = 0x534348
        @Volatile private var instance: SchoolScheduleManager? = null
        fun get(context: Context): SchoolScheduleManager = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app -> SchoolScheduleManager(app, notices = SchoolMessagesManager.get(app)) }.also { instance = it }
        }
    }
}
