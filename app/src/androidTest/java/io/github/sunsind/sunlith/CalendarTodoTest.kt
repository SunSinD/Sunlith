package io.github.sunsind.sunlith

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.data.TodoMarker
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.system.TodoStore
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.CalendarScreen
import io.github.sunsind.sunlith.ui.SunlithTheme
import io.github.sunsind.sunlith.ui.LightPalette
import java.io.File
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarTodoTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var english: Context
    private lateinit var device: UiDevice
    private lateinit var repository: CalendarRepository
    private lateinit var vm: SunlithViewModel
    private var activity: ComponentActivity? = null
    private var oldLocales: LocaleList? = null
    private var calendarLocale = Locale.ENGLISH
    private val snapshots = mutableMapOf<String, Map<String, *>>()
    private val existingPreferences = mutableSetOf<String>()
    private val account = "Sunlith to-do test ${UUID.randomUUID()}"
    private var calendarId = 0L
    private var oldId = 0L
    private var futureId = 0L
    private var candidateId = 0L
    private val oldTitle = "Synthetic old actionable milestone"
    private val futureTitle = "Synthetic future actionable milestone"
    private val notes = "Synthetic notes with café."
    private val now = System.currentTimeMillis()

    @Before fun setUp() {
        requireEmulator()
        context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("todoDisplay") == "true") {
            calendarLocale = Locale.forLanguageTag(arguments.getString("calendarLocale") ?: arguments.getString("timelineLanguage") ?: "en")
            assumeTrue("Before API 33 the external profile must set the requested system language.",
                Build.VERSION.SDK_INT >= 33 || context.resources.configuration.locales[0].language == calendarLocale.language)
        }
        english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        device = UiDevice.getInstance(instrumentation)
        listOf("sunlith", "sunlith_ui", "todo_done").forEach { name ->
            if (File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").exists()) existingPreferences += name
            snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        context.getSharedPreferences("todo_done", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).let {
            oldLocales = it.applicationLocales
            it.applicationLocales = LocaleList.forLanguageTags(calendarLocale.toLanguageTag())
        }
        context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("ai_intro_done", true).putBoolean("layout_setup_done", true).putBoolean("notify_asked", true).commit()
        calendarId = insertLocalCalendar(context, "sunlith-todo-test", "To-do test calendar", account)
        SettingsStore(context).apply {
            selectedCalendarIds = setOf(calendarId)
            hasChosenCalendars = true
            startView = "Month"
            themeMode = "light"
            materialYou = false
            navigationAnimation = false
            remindersEnabled = false
            monthLayout = "split"
            agendaRange = "month"
            agendaDisplay = "all"
            collapseCalendarOnScroll = false
            showNavigationArrows = false
            dayLayout = "cards"
        }
        repository = CalendarRepository(context)
        val oldStart = LocalDate.now().minusYears(4).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        oldId = add(oldTitle, oldStart, tagged = true)
        futureId = add(futureTitle, now + 2 * DAY, tagged = true)
        val candidateStart = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        candidateId = add("Synthetic tag candidate", candidateStart, tagged = false, reminders = listOf(15, 60))
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as ComponentActivity
        onMain { vm = ViewModelProvider(requireNotNull(activity))[SunlithViewModel::class.java] }
        awaitState("The isolated calendar must finish loading") { !vm.loadingEvents && vm.events.any { it.id == candidateId } }
    }

    @After fun tearDown() {
        if (!::context.isInitialized) return
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        instrumentation.waitForIdleSync()
        if (calendarId != 0L) context.contentResolver.delete(localCalendarUri(calendarId, account), null, null)
        snapshots.forEach { (name, values) ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            restoreTestPreferences(prefs, values)
            if (name !in existingPreferences) {
                prefs.edit().clear().commit()
                context.deleteSharedPreferences(name)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) oldLocales?.let { context.getSystemService(LocaleManager::class.java).applicationLocales = it }
    }

    @Test fun oldStartedTasksAppearAndCompletionCanBeReloadedAndUndone() {
        onMain {
            assertFalse("The old task must lie outside the calendar's loaded window", vm.events.any { it.id == oldId })
            vm.openTodos()
        }
        awaitState("The dedicated To-do query must finish before the list is inspected") {
            !vm.todoLoading && vm.todoItems.any { it.event.id == oldId }
        }
        node(By.pkg(context.packageName).res("todo_list"))
        node(By.pkg(context.packageName).text(oldTitle))
        awaitState("The dedicated query must retain future tasks for activation") { !vm.todoLoading && vm.todoItems.any { it.event.id == futureId } }
        assertFalse("Future tasks must not appear before their start", device.hasObject(By.pkg(context.packageName).text(futureTitle)))
        val old = vm.todoItems.single { it.event.id == oldId }
        node(By.pkg(context.packageName).desc(english.getString(R.string.todo_complete, oldTitle))).click()
        awaitState("Marking done must update the list") { old.key in vm.todoCompleted }
        assertTrue(TodoStore(context).isCompleted(old.key))
        onMain { vm.reloadTodos() }
        awaitState("Completion must survive a provider reload") { !vm.todoLoading && old.key in vm.todoCompleted }
        assertTrue(device.wait(Until.gone(By.pkg(context.packageName).text(oldTitle)), 5000))
        node(By.pkg(context.packageName).text(english.getString(R.string.todo_completed, 1))).click()
        node(By.pkg(context.packageName).desc(english.getString(R.string.todo_undo, oldTitle))).click()
        awaitState("Undo must return the task to the pending list") { old.key !in vm.todoCompleted }
        assertFalse(TodoStore(context).isCompleted(old.key))
        onMain { vm.reloadTodos() }
        awaitState("Undo must survive reload") { !vm.todoLoading && old.key !in vm.todoCompleted }
        node(By.pkg(context.packageName).text(oldTitle))
    }

    @Test fun detailsAndEditorHideMetadataAndTaggingPreservesCalendarFields() {
        val original = requireNotNull(repository.getEvent(candidateId))
        val event = vm.events.single { it.id == candidateId }
        onMain { vm.open(Screen.Details(event)) }
        val todoSwitch = By.pkg(context.packageName).desc(english.getString(R.string.todo_tag))
        scrollTo(todoSwitch)
        node(todoSwitch).click()
        awaitDraft(candidateId) { TodoMarker.has(it.description) }
        val tagged = requireNotNull(repository.getEvent(candidateId))
        assertEquals("Tagging must rewrite only the description", original.copy(description = TodoMarker.set(original.description, true)), tagged)
        scrollTo(By.pkg(context.packageName).text(notes))
        assertFalse(device.hasObject(By.pkg(context.packageName).textContains(TodoMarker.TOKEN)))
        onMain { vm.editEvent(candidateId) }
        awaitState("Editor must parse the tag separately from notes") { vm.editor?.let { it.id == candidateId && it.todo && it.notes == notes } == true }
        scrollTo(By.pkg(context.packageName).text(notes))
        assertFalse(device.hasObject(By.pkg(context.packageName).textContains(TodoMarker.TOKEN)))
        onMain { vm.updateEditor { it.copy(todo = false) } }
        node(By.pkg(context.packageName).text(english.getString(R.string.action_save))).click()
        awaitState("Saving the tag change must close the editor") { vm.editor == null && !vm.saving }
        assertEquals("Removing a tag must preserve original notes, exact timing and reminders", original, repository.getEvent(candidateId))
    }

    @Test fun repeatingOccurrencesHaveSeparateStableCompletionKeysAndSinglesKeepOldHistory() {
        val start = now - 2 * DAY
        val seriesId = repository.saveEvent(EventDraft(calendarId = calendarId, title = "Synthetic daily task",
            description = TodoMarker.TOKEN, startMillis = start, endMillis = start + 900_000L,
            rrule = "FREQ=DAILY;COUNT=4", reminderMinutes = emptyList()))
        val items = repository.todoEvents(setOf(calendarId), now)
        assertTrue(items.any { it.event.id == oldId })
        val occurrences = items.filter { it.event.id == seriesId }.sortedBy { it.availableAt }
        assertEquals(4, occurrences.size)
        assertEquals(4, occurrences.map { it.key }.toSet().size)
        assertTrue(occurrences.first().availableAt < now)
        assertTrue(occurrences.last().availableAt > now)
        val first = occurrences.first()
        TodoStore(context).setCompleted(first.key, true)
        val reloaded = repository.todoEvents(setOf(calendarId), now).filter { it.event.id == seriesId }
        assertEquals(occurrences.map { it.key }, reloaded.map { it.key })
        assertEquals(listOf(first.key), reloaded.filter { TodoStore(context).isCompleted(it.key) }.map { it.key })
    }

    @Test fun completionsSurviveWholeSeriesMovesForTimedWeeklyAndAllDayTasks() {
        val today = LocalDate.now()
        data class Case(val rule: String, val allDay: Boolean, val first: LocalDate, val move: Long)
        val cases = listOf(
            Case("FREQ=DAILY;COUNT=4", false, today.minusDays(2), 1_800_000L),
            Case("FREQ=WEEKLY;COUNT=4", false, today.minusWeeks(2), DAY + 1_800_000L),
            Case("FREQ=DAILY;COUNT=4", true, today.minusDays(2), DAY),
        )
        cases.forEachIndexed { index, case ->
            val start = if (case.allDay) io.github.sunsind.sunlith.data.CalendarDates.allDayMillis(case.first) else at(case.first, 9 * 60)
            val id = repository.saveEvent(EventDraft(calendarId = calendarId, title = "Synthetic movable task $index",
                description = TodoMarker.TOKEN, startMillis = start, endMillis = start + if (case.allDay) DAY else 900_000L,
                allDay = case.allDay, timeZone = if (case.allDay) "UTC" else ZoneId.systemDefault().id,
                rrule = case.rule, reminderMinutes = emptyList()))
            val before = repository.todoEvents(setOf(calendarId), now).filter { it.event.id == id }
            assertEquals(4, before.size)
            val done = before[1]
            TodoStore(context).setCompleted(done.key, true)
            val original = requireNotNull(repository.getEvent(id))
            repository.saveEvent(original.copy(startMillis = original.startMillis + case.move, endMillis = original.endMillis + case.move))
            val after = repository.todoEvents(setOf(calendarId), now).filter { it.event.id == id }
            assertEquals("A series move must retain occurrence identities for ${case.rule}, allDay=${case.allDay}",
                before.map { it.key }, after.map { it.key })
            assertEquals("Only the same occurrence must remain complete", listOf(done.key),
                after.filter { TodoStore(context).isCompleted(it.key) }.map { it.key })
        }
    }

    @Test fun todoStaysReachableWithWideTabsAndNarrowParentDayActions() {
        onMain {
            vm.openCalendar()
            vm.changeShowNavigationArrows(false)
            vm.changeViewMode(ViewMode.Month)
            installCalendarHost(420)
        }
        assertTrue("The date title must retain visible width beside wide tabs",
            node(By.pkg(context.packageName).res("calendar_period_title")).visibleBounds.width() > 24)
        captureFinal("todo-wide-tabs-header")
        val todoAction = node(By.pkg(context.packageName).res("calendar_todo_action"))
        assertTrue("The To-do action must retain a useful visible touch target", todoAction.visibleBounds.let { it.width() >= 40 && it.height() >= 40 })
        todoAction.click()
        awaitState("To-do must remain reachable when wide tabs replace the view menu") { vm.stack.lastOrNull() == Screen.Todo }
        onMain {
            vm.openCalendar()
            vm.changeShowNavigationArrows(true)
            vm.openDay(vm.today)
            installCalendarHost(280)
        }
        assertTrue("A 280px parent Day header must retain room for a readable numeric date",
            node(By.pkg(context.packageName).res("calendar_period_title")).visibleBounds.width() >= 72)
        captureFinal("todo-narrow-parent-header")
        val more = By.pkg(context.packageName).res("calendar_header_actions")
        node(more).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.todo_title)))
        node(By.pkg(context.packageName).text(english.getString(R.string.action_search))).click()
        awaitState("The compact parent action menu must retain Search") { vm.stack.lastOrNull() == Screen.Search }
        onMain { vm.openCalendar() }
        node(more).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.title_settings))).click()
        awaitState("The compact parent action menu must retain Settings") { vm.stack.lastOrNull() == Screen.Settings }
        onMain { vm.openCalendar() }
        node(more).click()
        node(By.pkg(context.packageName).text(english.getString(R.string.todo_title))).click()
        awaitState("The compact parent action menu must open To-do") { vm.stack.lastOrNull() == Screen.Todo }
    }

    @Test fun finalMinuteDayLabelsScrollAboveFloatingActionsAtEveryZoom() {
        val day = LocalDate.now().plusDays(2)
        val id = repository.saveEvent(EventDraft(calendarId = calendarId, title = "Synthetic final-minute zoom check",
            startMillis = at(day, 23 * 60 + 59), endMillis = at(day.plusDays(1), 0), reminderMinutes = emptyList()))
        onMain {
            vm.changeDayLayout("timeline")
            vm.changeViewMode(ViewMode.Day)
            vm.select(day)
            vm.reload()
        }
        awaitState("The final-minute fixture must load and finish positioning") {
            !vm.loadingEvents && !vm.pendingDayFocus && vm.events.any { it.id == id }
        }
        listOf(50, 100, 200).forEach { zoom ->
            onMain { vm.changeDayTimelineZoom(zoom) }
            revealTimelineEvent(id)
            val body = node(By.pkg(context.packageName).res("calendar_timeline")).visibleBounds
            val event = node(By.pkg(context.packageName).res("timeline_event_$id")).visibleBounds
            val safeBottom = body.bottom - (104 * context.resources.displayMetrics.density).toInt()
            assertTrue("The whole midnight label must scroll above floating actions at $zoom%: $event / $body",
                event.top >= body.top && event.bottom <= safeBottom)
            captureFinal("day-midnight-zoom-$zoom")
        }
    }

    @Test fun optInSyntheticV068DisplayCheckpoints() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit synthetic v0.6.8 display opt-in required.", arguments.getString("todoDisplay") == "true")
        assertEquals("Display checkpoints must save their evidence", "true", arguments.getString("captureFinal"))
        val french = calendarLocale.language == "fr"
        val ongoingTitle = if (french) "Démarche synthétique à terminer quand vous avez du temps libre"
            else "Synthetic task to finish when you have some free time"
        val ongoingId = repository.saveEvent(EventDraft(calendarId = calendarId, title = ongoingTitle,
            description = TodoMarker.set(if (french) "Notes synthétiques. Aucun rendez-vous personnel." else "Synthetic notes. No personal appointments.", true),
            startMillis = now - 300_000L, endMillis = now + 7_200_000L, reminderMinutes = emptyList()))
        onMain { vm.openTodos() }
        awaitState("Both the old and ongoing tasks must load") { !vm.todoLoading && vm.todoItems.any { it.event.id == ongoingId } && vm.todoItems.any { it.event.id == oldId } }
        node(By.pkg(context.packageName).res("todo_list"))
        captureFinal("todo-pending")
        val old = vm.todoItems.single { it.event.id == oldId }
        onMain { vm.completeTodo(old, true) }
        val completed = By.pkg(context.packageName).text(localized().getString(R.string.todo_completed, 1))
        scrollTo(completed)
        node(completed).click()
        captureFinal("todo-completed")
        val ongoing = vm.todoItems.single { it.event.id == ongoingId }.event
        onMain { vm.open(Screen.Details(ongoing)) }
        node(By.pkg(context.packageName).text(ongoingTitle))
        scrollTo(By.pkg(context.packageName).desc(localized().getString(R.string.todo_tag)))
        node(By.pkg(context.packageName).desc(localized().getString(R.string.todo_tag)))
        captureFinal("todo-details")
        onMain { vm.editEvent(ongoingId) }
        awaitState("The tagged editor must be ready") { vm.editor?.id == ongoingId && vm.editor?.todo == true }
        scrollTo(By.pkg(context.packageName).text(localized().getString(R.string.todo_tag)))
        captureFinal("todo-editor")

        val friday = LocalDate.now().plusWeeks(2).with(DayOfWeek.FRIDAY)
        val monday = friday.minusDays(4)
        val weekIds = (0L..8L).associateWith { index ->
            val date = monday.plusDays(index)
            val title = if (french) "Activité synthétique ${index + 1}" else "Synthetic activity ${index + 1}"
            repository.saveEvent(EventDraft(calendarId = calendarId, title = title,
                startMillis = at(date, 10 * 60), endMillis = at(date, 11 * 60), reminderMinutes = emptyList()))
        }
        val shortTitle = if (french) "Rappel synthétique de quinze minutes" else "Synthetic fifteen-minute reminder"
        val shortId = repository.saveEvent(EventDraft(calendarId = calendarId, title = shortTitle,
            description = TodoMarker.TOKEN, startMillis = at(friday, 18 * 60), endMillis = at(friday, 18 * 60 + 15), reminderMinutes = emptyList()))
        val followingId = repository.saveEvent(EventDraft(calendarId = calendarId,
            title = if (french) "Activité synthétique de soixante-quinze minutes" else "Synthetic seventy-five-minute activity",
            startMillis = at(friday, 18 * 60 + 20), endMillis = at(friday, 19 * 60 + 35), reminderMinutes = emptyList()))
        val midnightId = repository.saveEvent(EventDraft(calendarId = calendarId,
            title = if (french) "Dernière minute synthétique" else "Synthetic final-minute reminder",
            startMillis = at(friday, 23 * 60 + 59), endMillis = at(friday.plusDays(1), 0), reminderMinutes = emptyList()))
        onMain {
            vm.closeEditor(); vm.openCalendar()
            vm.changeKeepSelectedDate(true)
            vm.changeGroupEmptyHours(false)
            vm.changeWeekLayout("timeline"); vm.changeVisibleWeekDays(5); vm.changeFiveDayWorkweek(false)
            vm.changeViewMode(ViewMode.Week); vm.select(friday); vm.reload()
        }
        awaitState("The rolling five-day timeline must load the seeded Friday") { !vm.loadingEvents && vm.events.any { it.id == weekIds[4L] } }
        revealTimelineEvent(requireNotNull(weekIds[4L]))
        assertTrue(device.hasObject(header(friday)))
        captureFinal("five-day-rolling")
        onMain { vm.open(Screen.Settings) }
        node(By.pkg(context.packageName).text(localized().getString(R.string.beta_settings_appearance))).click()
        val toggle = By.pkg(context.packageName).text(localized().getString(R.string.settings_five_day_workweek))
        scrollTo(toggle)
        node(toggle).click()
        awaitState("The visible workweek switch must turn on") { vm.fiveDayWorkweek }
        captureFinal("workweek-setting")
        onMain { vm.back() }
        awaitState("Returning from Settings must retain the selected Friday") { vm.stack.isEmpty() && vm.selectedDate == friday && vm.workweekTimeline() }
        revealTimelineEvent(requireNotNull(weekIds[4L]))
        assertFalse("The workweek must not compose a weekend header", device.hasObject(header(friday.plusDays(1))))
        captureFinal("five-day-workweek")
        onMain {
            vm.changeDayLayout("timeline"); vm.changeDayTimelineZoom(100)
            vm.changeViewMode(ViewMode.Day); vm.selectAndFocus(friday)
        }
        awaitState("The Day timeline must load its short events") { !vm.loadingEvents && vm.events.any { it.id == shortId } && !vm.pendingDayFocus }
        revealTimelineEvent(shortId)
        node(By.pkg(context.packageName).res("timeline_event_$followingId"))
        captureFinal("day-short-titles")
        revealTimelineEvent(midnightId)
        captureFinal("day-midnight-title")
        onMain {
            vm.changeShowNavigationArrows(true)
            vm.changeViewMode(ViewMode.Month)
            vm.openDay(friday)
        }
        awaitState("The parent Day header must finish focusing") { vm.hasDayParent && !vm.pendingDayFocus }
        assertTrue("The parent Day title must retain visible width",
            node(By.pkg(context.packageName).res("calendar_period_title")).visibleBounds.width() > 0)
        val logicalWidth = device.displayWidth / context.resources.displayMetrics.density
        node(By.pkg(context.packageName).desc(localized().getString(if (logicalWidth < 480) R.string.todo_actions else R.string.todo_title)))
        captureFinal("parent-day-header")
    }

    private fun add(title: String, start: Long, tagged: Boolean, reminders: List<Int> = emptyList()): Long = repository.saveEvent(
        EventDraft(calendarId = calendarId, title = title, description = TodoMarker.set(notes, tagged),
            startMillis = start, endMillis = start + 900_000L, reminderMinutes = reminders),
    )

    private fun onMain(action: () -> Unit) {
        instrumentation.runOnMainSync(action)
        instrumentation.waitForIdleSync()
        device.waitForIdle()
    }

    private fun awaitState(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) { instrumentation.waitForIdleSync(); device.waitForIdle(); return }
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun awaitDraft(id: Long, condition: (EventDraft) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (repository.getEvent(id)?.let(condition) == true) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected the calendar provider to save the To-do tag")
    }

    private fun node(selector: BySelector): UiObject2 {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        return requireNotNull(device.wait(Until.findObject(selector), 10_000L)) { "Expected To-do control: $selector" }
    }

    private fun localized(): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(calendarLocale) })

    private fun installCalendarHost(width: Int) {
        requireNotNull(activity).setContent {
            SunlithTheme("light", false) {
                Box(Modifier.fillMaxSize().background(LightPalette.background).safeDrawingPadding()) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                        Box(Modifier.width(width.dp).height(500.dp)) { CalendarScreen(vm) }
                    }
                }
            }
        }
    }

    private fun header(date: LocalDate): BySelector = By.pkg(context.packageName)
        .descStartsWith(Formats(localized(), calendarLocale).dayLong(date) + ",")

    private fun at(date: LocalDate, minute: Int): Long = date.atStartOfDay(ZoneId.systemDefault()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun captureFinal(name: String) {
        device.waitForIdle()
        SystemClock.sleep(350)
        val prefix = InstrumentationRegistry.getArguments().getString("todoEvidencePrefix") ?: "final-v068"
        assertTrue("The checkpoint PNG must be saved", device.takeScreenshot(File(context.filesDir, "$prefix-$name.png")))
        device.dumpWindowHierarchy(File(context.filesDir, "$prefix-$name.xml"))
    }

    private fun revealTimelineEvent(id: Long) {
        val selector = By.pkg(context.packageName).res("timeline_event_$id")
        val timeline = By.pkg(context.packageName).res("calendar_timeline")
        fun findTimeline(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (root.viewIdResourceName == "calendar_timeline") return root
            for (index in 0 until root.childCount) root.getChild(index)?.let { child -> findTimeline(child)?.let { return it } }
            return null
        }
        for (attempt in 0 until 24) {
            node(timeline)
            val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val scroller = requireNotNull(findTimeline(root)) { "The timeline must expose its vertical scroll action" }
            if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break
            device.waitForIdle()
            SystemClock.sleep(100)
        }
        repeat(64) {
            val body = node(timeline).visibleBounds
            val found = device.findObject(selector)?.visibleBounds?.takeUnless { bounds -> bounds.isEmpty }
            val density = context.resources.displayMetrics.density
            val safeBottom = body.bottom - (104 * density).toInt()
            if (found != null && found.height() >= 16 * density && found.top >= body.top && found.bottom <= safeBottom) {
                device.waitForIdle()
                return
            }
            val x = body.left + (12 * density).toInt()
            val top = body.top + (24 * density).toInt()
            val bottom = safeBottom.coerceAtLeast(top + (48 * density).toInt())
            val distance = (bottom - top) * 55 / 100
            val start = top + (bottom - top) * 3 / 4
            assertTrue(device.swipe(x, start, x, start - distance, 28))
            device.waitForIdle()
        }
        captureFinal("timeline-event-$id-failure")
        throw AssertionError("The timeline must visibly expose event $id for its checkpoint")
    }

    private fun scrollTo(selector: BySelector) {
        repeat(8) {
            if (device.hasObject(selector)) return
            val scroll = device.findObject(By.pkg(context.packageName).scrollable(true)) ?: return@repeat
            scroll.scroll(Direction.DOWN, 0.6f)
            device.waitForIdle()
        }
        node(selector)
    }

    companion object { private const val DAY = 86_400_000L }
}
