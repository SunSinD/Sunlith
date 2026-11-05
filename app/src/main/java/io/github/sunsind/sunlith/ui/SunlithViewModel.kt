package io.github.sunsind.sunlith.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.DeviceCalendar
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.data.EditBlockReason
import io.github.sunsind.sunlith.data.IcsTransfer
import io.github.sunsind.sunlith.data.ImportPreview
import io.github.sunsind.sunlith.data.ImportReport
import io.github.sunsind.sunlith.system.AppRefresh
import io.github.sunsind.sunlith.system.SettingsStore
import java.io.FileNotFoundException
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ViewMode { Month, Week, Day }

sealed interface Screen {
    data object Search : Screen
    data object Settings : Screen
    data object Calendars : Screen
    data object AiSetup : Screen
    data object DeviceLink : Screen
    data object Editor : Screen
    data class Details(val event: CalendarEvent) : Screen
}

class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

data class ImportUi(
    val uri: Uri,
    val busy: Boolean = true,
    val preview: ImportPreview? = null,
    val calendarId: Long? = null,
    val report: ImportReport? = null,
    val error: String? = null,
)

class SunlithViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val repo = CalendarRepository(app)
    private val transfer = IcsTransfer(app, repo)
    private val settings = SettingsStore(app)
    private val uiPrefs = app.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE)

    var hasPermission by mutableStateOf(calendarGranted()); private set
    var notificationsGranted by mutableStateOf(notificationGranted()); private set
    var notificationStepDone by mutableStateOf(uiPrefs.getBoolean(KEY_NOTIFY_ASKED, false)); private set
    var aiIntroDone by mutableStateOf(uiPrefs.getBoolean("ai_intro_done", false)); private set

    var calendars by mutableStateOf(emptyList<DeviceCalendar>()); private set
    var activeIds by mutableStateOf(emptySet<Long>()); private set
    var events by mutableStateOf(emptyList<CalendarEvent>()); private set
    var loadedFrom by mutableStateOf<LocalDate?>(null); private set
    var loadedTo by mutableStateOf<LocalDate?>(null); private set
    var loaded by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set

    var themeMode by mutableStateOf(settings.themeMode); private set
    var weekStartsMonday by mutableStateOf(settings.weekStartsMonday); private set
    var remindersEnabled by mutableStateOf(settings.remindersEnabled); private set
    var widgetOpacity by mutableStateOf(settings.widgetOpacity); private set
    var widgetDays by mutableStateOf(settings.widgetDays); private set
    var widgetShowCalendarName by mutableStateOf(settings.widgetShowCalendarName); private set
    var widgetShowEmptyDays by mutableStateOf(settings.widgetShowEmptyDays); private set
    var agendaDisplay by mutableStateOf(settings.agendaDisplay); private set
    var collapseCalendarOnScroll by mutableStateOf(settings.collapseCalendarOnScroll); private set
    var startView by mutableStateOf(settings.startView); private set
    var navigationAnimation by mutableStateOf(settings.navigationAnimation); private set

    var selectedDate by mutableStateOf(LocalDate.now()); private set
    val month: YearMonth get() = YearMonth.from(selectedDate)
    var viewMode by mutableStateOf(launchViewMode()); private set

    /** Bumped by [goToday] so the calendar can re-center and confirm the action even when today is already selected. */
    var todayRequest by mutableStateOf(0); private set

    val stack = mutableStateListOf<Screen>()
    var editor by mutableStateOf<EditorState?>(null); private set
    private var editorInitial: EditorState? = null
    var saving by mutableStateOf(false); private set

    var searchQuery by mutableStateOf(""); private set
    var searchResults by mutableStateOf(emptyList<CalendarEvent>()); private set
    var searchLoading by mutableStateOf(false); private set
    private var searchPool: List<CalendarEvent>? = null

    var importUi by mutableStateOf<ImportUi?>(null); private set
    var exporting by mutableStateOf(false); private set

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var loadJob: Job? = null
    private var changeJob: Job? = null
    private var searchJob: Job? = null
    private var pendingLink: (() -> Unit)? = null

    init {
        if (hasPermission) reload()
    }

    // region permissions and lifecycle

    private fun calendarGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun notificationGranted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    val needsNotificationStep: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && !notificationsGranted && remindersEnabled && !notificationStepDone

    fun onResume() {
        notificationsGranted = notificationGranted()
        val granted = calendarGranted()
        val changed = granted != hasPermission
        hasPermission = granted
        if (!granted) {
            loadJob?.cancel()
            calendars = emptyList(); events = emptyList(); loaded = false
            loadedFrom = null; loadedTo = null
            stack.clear(); editor = null
            return
        }
        if (changed) refreshApp()
        reload()
        pendingLink?.let { link -> pendingLink = null; link() }
    }

    fun onCalendarPermissionResult() = onResume()

    fun finishAiIntro() {
        uiPrefs.edit().putBoolean("ai_intro_done", true).apply()
        aiIntroDone = true
    }

    fun startComputerSetup() {
        uiPrefs.edit().putBoolean("computer_setup_requested", true).apply()
        finishAiIntro()
    }

    fun openRequestedComputerSetup() {
        if (!hasPermission || needsNotificationStep || stack.isNotEmpty() || !uiPrefs.getBoolean("computer_setup_requested", false)) return
        uiPrefs.edit().remove("computer_setup_requested").apply()
        open(Screen.DeviceLink)
    }

    fun onNotificationPermissionResult(granted: Boolean, fromOnboarding: Boolean) {
        notificationsGranted = notificationGranted()
        if (fromOnboarding) finishNotificationStep(enable = granted)
        else setReminders(granted)
    }

    fun finishNotificationStep(enable: Boolean) {
        uiPrefs.edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()
        notificationStepDone = true
        if (!enable) setReminders(false)
    }

    fun onProviderChanged() {
        if (!hasPermission) return
        changeJob?.cancel()
        changeJob = viewModelScope.launch {
            delay(450)
            reload()
            invalidateSearch()
        }
    }

    private fun invalidateSearch() {
        if (Screen.Search in stack) loadSearchPool() else searchPool = null
    }

    // endregion

    // region loading

    private fun wantedRange(): Pair<LocalDate, LocalDate> {
        val start = gridStart(month, weekStartsMonday)
        return start to start.plusDays(41)
    }

    fun coversRange(from: LocalDate, to: LocalDate): Boolean =
        loadedFrom?.let { !it.isAfter(from) } == true && loadedTo?.let { !it.isBefore(to) } == true

    private fun ensureLoaded() {
        val (from, to) = wantedRange()
        if (!coversRange(from, to) || loadJob?.isActive == true) reload()
    }

    private fun eventsBetween(from: LocalDate, to: LocalDate, ids: Set<Long>): List<CalendarEvent> {
        val zone = ZoneId.systemDefault()
        val start = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return repo.events(start, end, ids)
    }

    fun reload() {
        if (!hasPermission) return
        val (from, to) = wantedRange()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val cals = withContext(Dispatchers.IO) { repo.calendars() }
                val ids = effectiveIds(cals)
                Snapshot.withMutableSnapshot {
                    calendars = cals
                    activeIds = ids
                }
                val result = withContext(Dispatchers.IO) { eventsBetween(from, to, ids) }
                if (wantedRange() != (from to to)) return@launch
                Snapshot.withMutableSnapshot {
                    events = result
                    loadedFrom = from
                    loadedTo = to
                    loadError = null
                    loaded = true
                }
                fillMissingCalendar()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                loadError = e.userMessage()
                loaded = true
            }
        }
    }

    private fun effectiveIds(cals: List<DeviceCalendar>): Set<Long> {
        val existing = cals.mapTo(mutableSetOf()) { it.id }
        return if (settings.hasChosenCalendars) settings.selectedCalendarIds.intersect(existing)
        else cals.filter { it.visible }.mapTo(mutableSetOf()) { it.id }
    }

    private fun fillMissingCalendar() {
        val current = editor ?: return
        if (current.id != null || current.calendarId != null) return
        val id = defaultCalendarId() ?: return
        editor = current.copy(calendarId = id)
        if (editorInitial?.calendarId == null) editorInitial = editorInitial?.copy(calendarId = id)
    }

    suspend fun loadDraft(id: Long): EventDraft? = withContext(Dispatchers.IO) {
        runCatching { repo.getEvent(id) }.getOrNull()
    }

    suspend fun editBlockReason(id: Long): EditBlockReason? = withContext(Dispatchers.IO) {
        runCatching { repo.editBlockReason(id) }.getOrDefault(EditBlockReason.INVALID_PROVIDER_DATA)
    }

    // endregion

    // region date navigation

    fun select(date: LocalDate) {
        selectedDate = date
        ensureLoaded()
    }

    fun goToday() {
        select(LocalDate.now())
        todayRequest++
    }

    fun step(delta: Int) {
        when (viewMode) {
            ViewMode.Month -> {
                val next = month.plusMonths(delta.toLong())
                select(if (next == YearMonth.now()) LocalDate.now() else next.atDay(1))
            }
            ViewMode.Week -> select(selectedDate.plusWeeks(delta.toLong()))
            ViewMode.Day -> select(selectedDate.plusDays(delta.toLong()))
        }
    }

    fun changeViewMode(mode: ViewMode) {
        Snapshot.withMutableSnapshot {
            if (mode == ViewMode.Day) selectedDate = LocalDate.now()
            viewMode = mode
        }
        ensureLoaded()
        if (settings.startView == START_LAST) uiPrefs.edit().putString(KEY_VIEW, mode.name).apply()
    }

    private fun launchViewMode(): ViewMode {
        val configured = settings.startView
        val name = if (configured == START_LAST) uiPrefs.getString(KEY_VIEW, null) else configured
        return ViewMode.entries.firstOrNull { it.name == name } ?: ViewMode.Month
    }

    // endregion

    // region navigation

    fun open(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun back() {
        if (stack.isEmpty()) return
        val top = stack.removeAt(stack.lastIndex)
        if (top == Screen.Editor) { editor = null; editorInitial = null }
    }

    fun openSearch() {
        open(Screen.Search)
        loadSearchPool()
    }

    fun handleLink(eventId: Long, begin: Long, create: Boolean, createDate: LocalDate? = null) {
        if (!hasPermission) { pendingLink = { handleLink(eventId, begin, create, createDate) }; return }
        if (stack.contains(Screen.Editor) && editorDirty) {
            message(context.getString(R.string.msg_finish_editing))
            return
        }
        if (stack.contains(Screen.Editor)) { editor = null; editorInitial = null }
        stack.clear()
        if (create) {
            newEvent(createDate ?: LocalDate.now())
            return
        }
        if (eventId <= 0) return
        viewModelScope.launch {
            try {
                val event = withContext(Dispatchers.IO) {
                    val draft = repo.getEvent(eventId) ?: return@withContext null
                    if (begin > 0) {
                        repo.events(begin - 1, begin + 1, setOf(draft.calendarId))
                            .firstOrNull { it.id == eventId && it.startMillis == begin }
                            ?.let { return@withContext it }
                    }
                    val cal = repo.calendars().firstOrNull { it.id == draft.calendarId }
                    val start = if (begin > 0) begin else draft.startMillis
                    CalendarEvent(eventId, draft.calendarId, draft.title, draft.description, draft.location, start,
                        start + (draft.endMillis - draft.startMillis), draft.allDay, draft.timeZone, draft.rrule,
                        cal?.color ?: 0, cal?.name ?: "")
                }
                if (event == null) {
                    message(context.getString(R.string.msg_event_missing))
                } else {
                    select(event.span(ZoneId.systemDefault()).first)
                    open(Screen.Details(event))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message(e.userMessage())
            }
        }
    }

    // endregion

    // region editor

    val writableCalendars: List<DeviceCalendar> get() = calendars.filter { it.writable }

    private fun defaultCalendarId(): Long? {
        val writable = writableCalendars
        val last = uiPrefs.getLong(KEY_LAST_CALENDAR, -1)
        return writable.firstOrNull { it.id == last }?.id
            ?: writable.firstOrNull { it.id in activeIds && !it.local }?.id
            ?: writable.firstOrNull { it.id in activeIds }?.id
            ?: writable.firstOrNull()?.id
    }

    fun newEvent(date: LocalDate = selectedDate) {
        val now = LocalDateTime.now()
        val startTime = if (date == now.toLocalDate()) LocalTime.of((now.hour + 1).coerceAtMost(23), 0) else LocalTime.of(9, 0)
        val start = date.atTime(startTime)
        val end = start.plusHours(1)
        val state = EditorState(
            id = null, calendarId = defaultCalendarId(), lockedCalendar = false,
            startDate = date, startTime = startTime, endDate = end.toLocalDate(), endTime = end.toLocalTime(),
            timeZone = TimeZone.getDefault().id, rrule = null, originalRrule = null,
            reminders = if (remindersEnabled && notificationsGranted) listOf(15) else emptyList(),
        )
        editor = state
        editorInitial = state
        open(Screen.Editor)
    }

    fun editEvent(id: Long) {
        viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { repo.getEvent(id) }
                if (draft == null) { message(context.getString(R.string.msg_event_missing)); return@launch }
                val state = EditorState.from(draft, ZoneId.systemDefault())
                editor = state
                editorInitial = state
                open(Screen.Editor)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message(e.userMessage())
            }
        }
    }

    fun updateEditor(transform: (EditorState) -> EditorState) {
        editor = editor?.let(transform)
    }

    val editorDirty: Boolean get() = editor != null && editor != editorInitial

    fun closeEditor() {
        editor = null
        editorInitial = null
        stack.remove(Screen.Editor)
    }

    fun saveEditor() {
        val state = editor ?: return
        if (saving || state.problem(writableCalendars) != null) return
        if (state.id != null && !editorDirty) { closeEditor(); return }
        saving = true
        viewModelScope.launch {
            try {
                val draft = state.toDraft(ZoneId.systemDefault())
                withContext(Dispatchers.IO) { repo.saveEvent(draft) }
                uiPrefs.edit().putLong(KEY_LAST_CALENDAR, draft.calendarId).apply()
                closeEditor()
                if (state.id != null) stack.removeAll { it is Screen.Details && it.event.id == state.id }
                refreshApp()
                invalidateSearch()
                select(state.startDate)
                reload()
                message(context.getString(R.string.msg_saved))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message(e.userMessage())
            } finally {
                saving = false
            }
        }
    }

    fun deleteEvent(id: Long) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.deleteEvent(id) }
                if (editor?.id == id) closeEditor()
                stack.removeAll { it is Screen.Details && it.event.id == id }
                refreshApp()
                invalidateSearch()
                reload()
                message(context.getString(R.string.msg_deleted))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message(e.userMessage())
            }
        }
    }

    // endregion

    // region calendars and settings

    fun setCalendarActive(id: Long, active: Boolean) {
        val next = if (active) activeIds + id else activeIds - id
        settings.selectedCalendarIds = next
        settings.hasChosenCalendars = true
        activeIds = next
        searchPool = null
        refreshApp()
        reload()
    }

    fun showLinkedCalendars(ids: Set<Long>) {
        settings.selectedCalendarIds = activeIds + ids
        settings.hasChosenCalendars = true
        activeIds = activeIds + ids
        searchPool = null
        refreshApp()
        reload()
    }

    val hasSunlithCalendar: Boolean get() = calendars.any { it.local && it.writable && it.account == "Sunlith" }

    fun createLocalCalendar(onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { repo.createLocalCalendar() }
                if (settings.hasChosenCalendars && id !in activeIds) {
                    settings.selectedCalendarIds = activeIds + id
                    activeIds = activeIds + id
                }
                val cals = withContext(Dispatchers.IO) { repo.calendars() }
                calendars = cals
                refreshApp()
                reload()
                onCreated(id)
                message(context.getString(R.string.msg_local_created))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message(e.userMessage())
            }
        }
    }

    fun setTheme(mode: String) {
        settings.themeMode = mode
        themeMode = mode
        refreshApp()
    }

    fun setMondayFirst(monday: Boolean) {
        settings.weekStartsMonday = monday
        weekStartsMonday = monday
        refreshApp()
        ensureLoaded()
    }

    fun setReminders(enabled: Boolean) {
        settings.remindersEnabled = enabled
        remindersEnabled = enabled
        refreshApp()
    }

    fun previewOpacity(value: Int) { widgetOpacity = ((value.coerceIn(0, 100) + 2) / 5) * 5 }

    fun commitOpacity() {
        settings.widgetOpacity = widgetOpacity
        widgetOpacity = settings.widgetOpacity
        refreshApp()
    }

    fun changeWidgetDays(days: Int) {
        settings.widgetDays = days
        widgetDays = settings.widgetDays
        refreshApp()
    }

    fun changeWidgetShowCalendarName(show: Boolean) {
        settings.widgetShowCalendarName = show
        widgetShowCalendarName = show
        refreshApp()
    }

    fun changeWidgetShowEmptyDays(show: Boolean) {
        settings.widgetShowEmptyDays = show
        widgetShowEmptyDays = show
        refreshApp()
    }

    fun changeAgendaDisplay(mode: String) {
        settings.agendaDisplay = mode
        agendaDisplay = settings.agendaDisplay
    }

    fun changeCollapseCalendarOnScroll(enabled: Boolean) {
        settings.collapseCalendarOnScroll = enabled
        collapseCalendarOnScroll = settings.collapseCalendarOnScroll
    }

    fun changeStartView(value: String) {
        settings.startView = value
        startView = settings.startView
        if (startView == START_LAST) uiPrefs.edit().putString(KEY_VIEW, viewMode.name).apply()
    }

    fun changeNavigationAnimation(enabled: Boolean) {
        settings.navigationAnimation = enabled
        navigationAnimation = enabled
    }

    // endregion

    // region search

    private fun loadSearchPool() {
        if (!hasPermission) return
        searchJob?.cancel()
        searchLoading = true
        searchJob = viewModelScope.launch {
            try {
                val pool = withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now()
                    val ids = effectiveIds(repo.calendars())
                    repo.events(today.minusDays(365).atStartOfDay(zone).toInstant().toEpochMilli(),
                        today.plusDays(730).atStartOfDay(zone).toInstant().toEpochMilli(), ids)
                }
                searchPool = pool
                filterSearch()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message(e.userMessage())
            } finally {
                searchLoading = false
            }
        }
    }

    fun updateSearch(query: String) {
        searchQuery = query
        if (searchPool == null) loadSearchPool() else filterSearch()
    }

    private fun filterSearch() {
        val terms = searchQuery.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val pool = searchPool.orEmpty()
        searchResults = if (terms.isEmpty()) emptyList() else pool.filter { e ->
            val hay = "${e.title}\n${e.location}\n${e.description}\n${e.calendarName}".lowercase()
            terms.all { it in hay }
        }.take(400)
    }

    // endregion

    // region import / export

    fun startImport(uri: Uri) {
        importUi = ImportUi(uri)
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
                    stream.use { transfer.inspect(it) }
                }
                if (importUi?.uri != uri) return@launch
                val dest = defaultCalendarId()
                importUi = ImportUi(uri, busy = false, preview = preview, calendarId = dest)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (importUi?.uri == uri) importUi = ImportUi(uri, busy = false, error = e.userMessage(R.string.error_read_file))
            }
        }
    }

    fun chooseImportCalendar(id: Long) {
        importUi = importUi?.copy(calendarId = id)
    }

    fun runImport() {
        val state = importUi ?: return
        val calendarId = state.calendarId ?: return
        if (state.busy) return
        importUi = state.copy(busy = true)
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(state.uri) ?: throw FileNotFoundException()
                    stream.use { transfer.import(it, calendarId) }
                }
                importUi = importUi?.copy(busy = false, report = report)
                searchPool = null
                refreshApp()
                reload()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                importUi = importUi?.copy(busy = false, error = e.userMessage())
            }
        }
    }

    fun dismissImport() { importUi = null }

    fun export(uri: Uri, calendarIds: Set<Long>) {
        if (exporting) return
        exporting = true
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val staged = File.createTempFile("sunlith-export-", ".ics", context.cacheDir)
                    try {
                        val exported = staged.outputStream().use { transfer.export(it, calendarIds) }
                        val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                            ?: context.contentResolver.openOutputStream(uri) ?: throw FileNotFoundException()
                        stream.use { output -> staged.inputStream().use { it.copyTo(output) } }
                        exported
                    } finally { staged.delete() }
                }
                message(context.resources.getQuantityString(R.plurals.msg_exported, count, count))
                if (transfer.exportWarnings.isNotEmpty()) {
                    message(context.getString(R.string.msg_export_skipped))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message(e.userMessage())
            } finally {
                exporting = false
            }
        }
    }

    // endregion

    fun message(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        messageChannel.trySend(UiMessage(text, actionLabel, action))
    }

    private fun refreshApp() {
        runCatching { AppRefresh.refresh(context) }
    }

    private fun Throwable.userMessage(fallback: Int = R.string.error_generic): String = when (this) {
        is SecurityException -> context.getString(R.string.error_permission)
        is FileNotFoundException -> context.getString(R.string.error_read_file)
        else -> message?.takeIf { it.isNotBlank() && it.length < 300 } ?: context.getString(fallback)
    }

    companion object {
        private const val START_LAST = "last"
        private const val KEY_VIEW = "view_mode"
        private const val KEY_LAST_CALENDAR = "last_calendar"
        private const val KEY_NOTIFY_ASKED = "notify_asked"
    }
}
