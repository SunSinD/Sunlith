package io.github.sunsind.sunlith

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.sunsind.sunlith.school.SchoolAdminNotice
import io.github.sunsind.sunlith.school.SchoolAdminStatus
import io.github.sunsind.sunlith.school.SchoolCollegeNotice
import io.github.sunsind.sunlith.school.SchoolCalendarChoice
import io.github.sunsind.sunlith.school.SchoolMessagesController
import io.github.sunsind.sunlith.school.SchoolMessagesState
import io.github.sunsind.sunlith.school.SchoolNoticeStatus
import io.github.sunsind.sunlith.school.SchoolNoticeSuggestion
import io.github.sunsind.sunlith.school.SchoolPortalLink
import io.github.sunsind.sunlith.school.SchoolNotificationPreferences
import io.github.sunsind.sunlith.school.SchoolScheduleController
import io.github.sunsind.sunlith.school.SchoolScheduleState
import io.github.sunsind.sunlith.ui.SchoolMessagesScreen
import io.github.sunsind.sunlith.ui.SchoolScheduleScreen
import io.github.sunsind.sunlith.ui.SunlithTheme
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchoolMessagesUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var device: UiDevice
    private lateinit var controller: FixtureController
    private var activity: ComponentActivity? = null
    private lateinit var localized: Context
    private var generation = 0
    private var density = 1f
    private var backedOut = 0
    private val openedDates = mutableListOf<LocalDate>()
    private val suggestions = mutableListOf<SchoolNoticeSuggestion>()
    private val openedUrls = mutableListOf<String>()
    private val affectedDay = LocalDate.of(2026, 11, 13)
    private val warningId = "fixture-warning"
    private val confirmationId = "fixture-confirmed"
    private val noticeIdentity = "fixture-college-day"
    private val suggestion = SchoolNoticeSuggestion("fixture-deadline", LocalDate.of(2026, 11, 20),
        "Synthetic registration deadline", "Source: Synthetic administration notice. Submit the form before November 20.")

    @Before
    fun setUp() {
        requireEmulator()
        device = UiDevice.getInstance(instrumentation)
        controller = FixtureController()
        // This debug host has no MainActivity observers, school controller or network startup.
        activity = instrumentation.startActivitySync(Intent().setComponent(
            ComponentName(context.packageName, ComponentActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
    }

    @After
    fun tearDown() {
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun connectedScheduleOpensMessagesAndBackReturnsToTheSameSchoolConnection() = checked {
        show(adminState(), fromSchedule = true)
        node(By.text(text(R.string.school_messages_open))).click()
        node(tag("school_messages_screen"))
        node(tag("school_admin_list"))
        assertFalse(device.hasObject(tag("school_messages_tab_mail")))
        assertFalse(device.hasObject(tag("school_messages_tab_admin")))
        node(By.desc(text(R.string.school_messages_refresh))).click()
        await { controller.refreshCalls == 2 }
        device.pressBack()
        node(By.text(text(R.string.school_title)))
        node(By.text("fixture-student"))
        node(By.text(text(R.string.school_messages_open)))
        assertFalse(device.hasObject(tag("school_messages_screen")))
        assertEquals("Returning from Messages must keep the school setup open.", 0, backedOut)
        assertTrue(openedUrls.isEmpty())
    }

    @Test
    fun administrationWarningBecomesConfirmedWithoutLosingExceptionsAndActions() = checked {
        show(adminState())
        node(By.text(text(R.string.school_admin_warning).uppercase(Locale.ROOT)))
        assertFalse(device.hasObject(By.text(text(R.string.school_admin_confirmed).uppercase(Locale.ROOT))))
        capture("administration-warning")
        scrollTo(By.textContains("La formation continue est maintenue."), "school_admin_list")
        scrollTo(By.textContains("Les laboratoires après 18 h sont maintenus."), "school_admin_list")
        scrollTo(tag("school_notice_day_$noticeIdentity"), "school_admin_list").click()
        await { openedDates.size == 1 }
        assertEquals(listOf(affectedDay), openedDates)
        assertTrue(suggestions.isEmpty())
        scrollTo(tag("school_notice_source_$noticeIdentity"), "school_admin_list").click()
        node(tag("school_admin_detail"))
        scrollTo(By.textContains("Une décision sera prise vers 7 h. Consultez cet avis de test."), "school_admin_detail")
        device.pressBack()
        node(tag("school_admin_list"))
        assertEquals(0, backedOut)

        instrumentation.runOnMainSync {
            controller.state.value = adminState(confirmed = true)
        }
        idle()
        scrollTo(By.text(text(R.string.school_admin_confirmed).uppercase(Locale.ROOT)), "school_admin_list")
        capture("administration-confirmed")
        assertFalse("A replacement must not leave a contradictory warning in the current notice card.",
            device.hasObject(By.text(text(R.string.school_admin_warning).uppercase(Locale.ROOT))))
        scrollTo(By.text(text(R.string.school_admin_superseded)), "school_admin_list")
        scrollTo(By.text(text(R.string.school_admin_scope_note)), "school_admin_list")
        scrollTo(tag("school_notice_source_$noticeIdentity"), "school_admin_list").click()
        node(tag("school_admin_detail"))
        scrollTo(By.textContains("Les cours sont levés jusqu’à 18 h."), "school_admin_detail")
        scrollTo(By.text(text(R.string.school_admin_show_day)), "school_admin_detail").click()
        await { openedDates.size == 2 }
        assertEquals(listOf(affectedDay, affectedDay), openedDates)
        device.pressBack()
        node(tag("school_admin_list"))
        scrollTo(tag("school_suggestion_add_${suggestion.id}"), "school_admin_list").click()
        await { suggestions.size == 1 }
        assertEquals("A suggestion must forward the exact date and source text for review.", listOf(suggestion), suggestions)
        assertEquals("Adding a suggestion must not itself rewrite the administration state.", adminState(confirmed = true), controller.state.value)
        assertTrue(openedUrls.isEmpty())
        device.pressBack()
        await { backedOut == 1 }
    }

    @Test
    fun aRetractedNoticeHasACorrectionLabelRatherThanAConfirmedCancellation() = checked {
        val source = SchoolAdminNotice("fixture-correction", affectedDay, "Synthetic strike withdrawn", "Direction générale",
            body = "Synthetic correction: the strike is withdrawn and classes run normally.", detailAvailable = true)
        val original = adminState(confirmed = true)
        val corrected = original.copy(admin = listOf(source), collegeNotices = original.collegeNotices.map {
            it.copy(status = SchoolNoticeStatus.UPDATED, sourceId = source.id, subject = source.subject,
                text = requireNotNull(source.body), until = null, decisionAt = null, resume = null)
        }, suggestions = emptyList())
        show(corrected)
        node(By.text(text(R.string.school_admin_updated).uppercase(Locale.ROOT)))
        assertFalse(device.hasObject(By.text(text(R.string.school_admin_confirmed).uppercase(Locale.ROOT))))
        assertFalse(device.hasObject(By.text(text(R.string.school_admin_warning).uppercase(Locale.ROOT))))
        scrollTo(tag("school_notice_source_$noticeIdentity"), "school_admin_list").click()
        node(tag("school_admin_detail"))
        scrollTo(By.textContains("the strike is withdrawn and classes run normally"), "school_admin_detail")
        assertEquals(corrected, controller.state.value)
        assertTrue("Reviewing a correction must not invent a new event or deadline.", openedDates.isEmpty() && suggestions.isEmpty())
    }

    @Test
    fun unknownAdministrationLayoutShowsItsActualStatusWithoutClaimingThereAreNoMessages() = checked {
        val statuses = listOf(
            SchoolAdminStatus.UNRECOGNIZED to R.string.school_admin_status_unrecognized,
            SchoolAdminStatus.SIGN_IN to R.string.school_admin_status_sign_in,
            SchoolAdminStatus.NETWORK to R.string.school_admin_status_network,
            SchoolAdminStatus.UNAVAILABLE to R.string.school_admin_status_unavailable,
            SchoolAdminStatus.NOT_LOADED to R.string.school_admin_not_loaded,
            SchoolAdminStatus.STORAGE to R.string.school_messages_storage,
        )
        statuses.forEach { (status, label) ->
            show(SchoolMessagesState(connected = true, adminStatus = status))
                node(By.text(text(label)))
            capture("administration-status-${status.name.lowercase(Locale.ROOT)}")
            assertFalse("A failed or unknown page must not be presented as a successfully empty inbox: $status",
                device.hasObject(By.text(text(R.string.school_admin_empty))))
            assertFalse(device.hasObject(By.text(text(R.string.school_admin_confirmed).uppercase(Locale.ROOT))))
            assertTrue("An unresolved page must not invent college notices.", openedDates.isEmpty() && suggestions.isEmpty())
        }
        show(SchoolMessagesState(connected = true, adminStatus = SchoolAdminStatus.OK))
        node(By.text(text(R.string.school_admin_empty)))
        capture("administration-empty")
        assertFalse(device.hasObject(By.text(text(R.string.school_admin_status_unrecognized))))
    }

    @Test
    fun portalLinkFailuresStayInTheDialogAndOnlyValidatedControllerUrlsAreOpened() = checked {
        show(SchoolMessagesState(connected = true, adminStatus = SchoolAdminStatus.UNRECOGNIZED))
        controller.links = listOf(SchoolPortalLink("fixture-link", "Synthetic college information"))
        node(By.text(text(R.string.school_admin_portal_links))).click()
        node(By.text("Synthetic college information")).click()
        node(By.text(text(R.string.school_admin_portal_link_failed)))
        assertEquals(listOf("fixture-link"), controller.resolved)
        assertTrue(openedUrls.isEmpty())
        controller.resolvedUrl = "https://college.example/validated-information"
        node(By.text("Synthetic college information")).click()
        await { openedUrls == listOf("https://college.example/validated-information") }
        assertFalse(device.hasObject(By.text("Synthetic college information")))
        assertEquals(listOf("fixture-link", "fixture-link"), controller.resolved)
    }

    @Test
    fun disconnectedMessagesExplainSetupAndDisableManualRefresh() = checked {
        show(SchoolMessagesState())
        node(By.text(text(R.string.school_messages_not_connected)))
        val refresh = generateSequence(node(By.desc(text(R.string.school_messages_refresh)))) { it.parent }
            .first { it.isClickable }
        assertFalse(refresh.isEnabled)
        assertFalse(device.hasObject(tag("school_admin_list")))
        assertTrue(openedDates.isEmpty() && suggestions.isEmpty() && openedUrls.isEmpty())
    }

    @Test
    fun englishAndFrenchNarrowAndLargeTextProfilesKeepFullDetailsAndActionsReachable() = checked {
        listOf("en", "fr").forEach { language ->
            listOf(1f, 2f).forEach { scale ->
                show(adminState(confirmed = true), language = language, widthDp = 320f, fontScaleOverride = scale)
                assertFalse(device.hasObject(tag("school_messages_tab_mail")))
                assertFalse(device.hasObject(tag("school_messages_tab_admin")))
                val source = controller.state.value.admin.first()
                capture("$language-${(scale * 100).toInt()}-administration")
                scrollTo(tag("school_admin_${source.id}"), "school_admin_list").click()
                node(tag("school_admin_detail"))
                node(By.text(source.subject))
                val body = scrollTo(By.text(requireNotNull(source.body)), "school_admin_detail")
                assertEquals("Messages must keep their original French in either app language", source.body, body.text)
                capture("$language-${(scale * 100).toInt()}-detail")
                device.pressBack()
                node(tag("school_admin_list"))
                scrollTo(By.text(text(R.string.school_admin_confirmed).uppercase(Locale.ROOT)), "school_admin_list")
                scrollTo(By.textStartsWith(text(R.string.school_messages_updated, "")), "school_admin_list")
                if (language == "fr") {
                    scrollTo(By.textContains("il y a"), "school_admin_list")
                    assertFalse(device.hasObject(By.textContains("hours ago")))
                }
                capture("$language-${(scale * 100).toInt()}-administration")
                val openedDateCount = openedDates.size
                scrollTo(tag("school_notice_day_$noticeIdentity"), "school_admin_list").click()
                await { openedDates.size == openedDateCount + 1 }
                assertEquals(affectedDay, openedDates.last())
                val suggestionCount = suggestions.size
                scrollTo(tag("school_suggestion_add_${suggestion.id}"), "school_admin_list").click()
                await { suggestions.size == suggestionCount + 1 }
                assertEquals(suggestion, suggestions.last())
                capture("$language-${(scale * 100).toInt()}-suggestion")
                assertTrue("School messages must stay in the app while scrolling and reviewing.", openedUrls.isEmpty())
            }
        }
    }

    private fun adminState(confirmed: Boolean = false): SchoolMessagesState {
        val warning = SchoolAdminNotice(warningId, affectedDay.minusDays(1), "Avis de test : boycott possible", "Direction générale",
            "Vie collégiale", "Une décision sera prise vers 7 h. Consultez cet avis de test.", true)
        val confirmation = SchoolAdminNotice(confirmationId, affectedDay, "Avis de test : levée des cours", "Direction générale",
            "Vie collégiale", "Les cours sont levés jusqu’à 18 h. La formation continue est maintenue.", true)
        val source = if (confirmed) confirmation else warning
        return SchoolMessagesState(connected = true, admin = if (confirmed) listOf(confirmation, warning) else listOf(warning),
            adminStatus = SchoolAdminStatus.OK, adminUpdatedMillis = 1_794_583_800_000L,
            collegeNotices = listOf(SchoolCollegeNotice(noticeIdentity, affectedDay,
                if (confirmed) SchoolNoticeStatus.CONFIRMED else SchoolNoticeStatus.WARNING,
                if (confirmed) LocalTime.of(18, 0) else null, if (confirmed) null else LocalTime.of(7, 0),
                if (confirmed) affectedDay.plusDays(3) else null, source.id, source.date, source.sender, source.subject,
                listOf("La formation continue est maintenue.", "Les laboratoires après 18 h sont maintenus."),
                requireNotNull(source.body), if (confirmed) listOf(warningId) else emptyList(), true)),
            suggestions = listOf(suggestion))
    }

    private fun show(state: SchoolMessagesState, language: String = "en", widthDp: Float = 390f,
        fontScaleOverride: Float = 1f, fromSchedule: Boolean = false) {
        controller.state.value = state
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            fontScale = fontScaleOverride
        }
        localized = object : ContextWrapper(context.createConfigurationContext(config)) {
            override fun getApplicationContext(): Context = context.applicationContext
        }
        density = device.displayWidth / widthDp
        val currentGeneration = ++generation
        val schedule = FixtureScheduleController()
        val current = requireNotNull(activity)
        instrumentation.runOnMainSync {
            current.setContent {
                key(currentGeneration) {
                    var messages by remember { mutableStateOf(!fromSchedule) }
                    CompositionLocalProvider(LocalActivity provides current, LocalActivityResultRegistryOwner provides current,
                        LocalContext provides localized, LocalConfiguration provides config,
                        LocalDensity provides Density(density, fontScaleOverride),
                        LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { openedUrls += uri } }) {
                        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
                            .testTag("school_messages_fixture_$currentGeneration")) {
                            SunlithTheme("light", false) {
                                if (messages) SchoolMessagesScreen(controller,
                                    onBack = { if (fromSchedule) messages = false else backedOut++ },
                                    onAddSuggestion = { suggestions += it }, onOpenDate = { openedDates += it })
                                else SchoolScheduleScreen(schedule, onBack = { backedOut++ }, onChooseCalendars = {},
                                    onOpenMessages = { messages = true })
                            }
                        }
                    }
                }
            }
        }
        idle()
        // setContent schedules a later Compose frame; the previous screen has the same production tags.
        // Wait for this exact replacement before obtaining any node that the old frame could still expose.
        node(tag("school_messages_fixture_$currentGeneration"))
        if (fromSchedule) node(By.text(text(R.string.school_title))) else node(tag("school_messages_screen"))
    }

    private fun text(id: Int, vararg arguments: Any): String = localized.getString(id, *arguments)
    private fun tag(value: String): BySelector = By.pkg(context.packageName).res(value)
    private fun node(selector: BySelector): UiObject2 {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        return requireNotNull(device.wait(Until.findObject(selector), 5_000)) { "Missing synthetic school-message control: $selector" }
    }
    private fun idle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        while (!condition() && SystemClock.uptimeMillis() < until) { SystemClock.sleep(50); idle() }
        assertTrue("The expected school-message UI action must finish.", condition())
    }
    private fun scrollTo(selector: BySelector, viewportTag: String): UiObject2 {
        fun visible(): UiObject2? = try {
            val viewport = node(tag(viewportTag)).visibleBounds
            device.findObject(selector)?.takeIf {
                val bounds = it.visibleBounds
                bounds.height() > 12 && viewport.contains(bounds.centerX(), bounds.centerY()) &&
                    bounds.top >= viewport.top + 2 && bounds.bottom <= viewport.bottom - 2
            }
        } catch (_: StaleObjectException) { null }
        stableVisible(::visible)?.let { return it }
        listOf(true, false).forEach { forward ->
            repeat(18) {
                val bounds = node(tag(viewportTag)).visibleBounds
                val top = bounds.top + bounds.height() / 5
                val bottom = bounds.bottom - bounds.height() / 5
                assertTrue(device.swipe(bounds.centerX(), if (forward) bottom else top,
                    bounds.centerX(), if (forward) top else bottom, 30))
                idle()
                stableVisible(::visible)?.let { return it }
            }
        }
        error("Synthetic school-message content is unreachable: $selector in $viewportTag")
    }
    private fun stableVisible(find: () -> UiObject2?): UiObject2? {
        var target = find() ?: return null
        var bounds = target.visibleBounds
        var stableSince = SystemClock.uptimeMillis()
        val deadline = stableSince + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
            target = find() ?: return null
            val current = target.visibleBounds
            if (current != bounds) { bounds = current; stableSince = SystemClock.uptimeMillis() }
            else if (SystemClock.uptimeMillis() - stableSince >= 500) return target
        }
        error("The administration control kept moving after its scroll")
    }

    private fun capture(name: String) {
        val folder = requireNotNull(context.getExternalFilesDir(null))
        assertTrue(device.takeScreenshot(File(folder, "school-messages-$name.png")))
        device.dumpWindowHierarchy(File(folder, "school-messages-$name.xml"))
    }
    private inline fun checked(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            runCatching { capture("failure") }
            throw failure
        }
    }

    private class FixtureController : SchoolMessagesController {
        override val state = MutableStateFlow(SchoolMessagesState())
        var refreshCalls = 0
        var links = emptyList<SchoolPortalLink>()
        val resolved = mutableListOf<String>()
        var resolvedUrl: String? = null
        override suspend fun refresh() { refreshCalls++ }
        override suspend fun portalLinks(): List<SchoolPortalLink> = links
        override suspend fun resolvePortalLink(code: String): String? { resolved += code; return resolvedUrl }
    }

    private class FixtureScheduleController : SchoolScheduleController {
        override val state = MutableStateFlow(SchoolScheduleState(connected = true, calendarId = 71,
            accountName = "fixture-student", portalAddress = "https://portal.example/ColNet/", timeZone = "America/Toronto",
            availableCalendars = listOf(SchoolCalendarChoice(71, "Synthetic school calendar")), calendarsLoaded = true))
        override suspend fun refresh() { }
        override suspend fun connect(portalAddress: String, username: String, password: String, timeZone: String, calendarId: Long?) = error("This fixture must not connect to a portal.")
        override suspend fun setNotifications(preferences: SchoolNotificationPreferences) = error("This fixture must not change notification settings.")
        override suspend fun applyPendingChanges() = error("This fixture must not apply calendar changes.")
        override suspend fun discardPendingChanges() = error("This fixture must not discard calendar changes.")
        override suspend fun disconnect(removeSchoolEvents: Boolean) = error("This fixture must not disconnect the school.")
    }
}
