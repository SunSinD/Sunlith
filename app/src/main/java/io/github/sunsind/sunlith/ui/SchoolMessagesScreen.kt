package io.github.sunsind.sunlith.ui

import android.icu.text.RelativeDateTimeFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.school.SchoolAdminNotice
import io.github.sunsind.sunlith.school.SchoolAdminStatus
import io.github.sunsind.sunlith.school.SchoolCollegeNotice
import io.github.sunsind.sunlith.school.SchoolMessagesController
import io.github.sunsind.sunlith.school.SchoolNoticeStatus
import io.github.sunsind.sunlith.school.SchoolNoticeSuggestion
import io.github.sunsind.sunlith.school.SchoolPortalLink
import io.github.sunsind.sunlith.school.SchoolPortalLinks
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Col.NET administration messages in their original language.
 * Bodies render as text: no scripts, images or remote content are loaded.
 */
@Composable
fun SchoolMessagesScreen(
    controller: SchoolMessagesController,
    onBack: () -> Unit,
    onAddSuggestion: (SchoolNoticeSuggestion) -> Unit = {},
    onOpenDate: (LocalDate) -> Unit = {},
) {
    val p = LocalPalette.current
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var openAdmin by rememberSaveable { mutableStateOf<String?>(null) }
    var links by remember { mutableStateOf<List<SchoolPortalLink>?>(null) }
    var linkFailed by remember { mutableStateOf(false) }
    var linksProblem by remember { mutableStateOf<SchoolAdminStatus?>(null) }
    var linksLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { try { controller.refresh() } catch (e: CancellationException) { throw e } catch (_: Exception) { } }

    val admin = state.admin.firstOrNull { it.id == openAdmin }
    val back: () -> Unit = { if (admin != null) openAdmin = null else onBack() }
    BackHandler(onBack = back)
    fun openPortal() { state.portalUrl?.let { runCatching { uri.openUri(it) } } }

    Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("school_messages_screen"), containerColor = p.background, topBar = {
        ScreenTopBar(admin?.subject ?: stringResource(R.string.school_messages_title), onNavigate = back) {
            if (state.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp, color = p.muted)
            else IconButton(onClick = { scope.launch { try { controller.refresh() } catch (e: CancellationException) { throw e } catch (_: Exception) { } } }, enabled = state.connected) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.school_messages_refresh), tint = p.ink)
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                !state.connected -> Note(stringResource(R.string.school_messages_not_connected))
                admin != null -> AdminDetail(admin, state.collegeNotices.firstOrNull { it.sourceId == admin.id }, ::openPortal, onOpenDate)
                else -> AdminList(
                    state.admin, state.adminStatus, state.adminUpdatedMillis, state.collegeNotices, state.suggestions,
                    onOpen = { openAdmin = it.id }, onOpenDate = onOpenDate, onAddSuggestion = onAddSuggestion,
                    onOpenPortal = ::openPortal,
                    // A college that publishes no links gets no links button. An empty answer opens Col.NET instead of an empty dialog.
                    onLinks = if (state.portalLinksAvailable == false) null else ({
                        if (!linksLoading) scope.launch {
                            linksLoading = true
                            try {
                                when (val result = controller.loadPortalLinks()) {
                                    is SchoolPortalLinks.Loaded -> if (result.links.isEmpty()) openPortal() else { linkFailed = false; links = result.links }
                                    is SchoolPortalLinks.Failed -> linksProblem = result.status
                                }
                            } finally { linksLoading = false }
                        }
                    }),
                    linksLoading = linksLoading,
                )
            }
        }
    }
    linksProblem?.let { problem ->
        AlertDialog(onDismissRequest = { linksProblem = null },
            confirmButton = { TextButton({ linksProblem = null; openPortal() }, enabled = state.portalUrl != null) { Text(stringResource(R.string.school_admin_open_portal)) } },
            dismissButton = { TextButton({ linksProblem = null }) { Text(stringResource(R.string.school_messages_close)) } },
            title = { Text(stringResource(R.string.school_admin_portal_links)) },
            text = {
                Text(stringResource(when (problem) {
                    SchoolAdminStatus.SIGN_IN -> R.string.school_admin_portal_links_sign_in
                    SchoolAdminStatus.UNAVAILABLE -> R.string.school_admin_status_unavailable
                    SchoolAdminStatus.UNRECOGNIZED -> R.string.school_admin_portal_links_unrecognized
                    SchoolAdminStatus.NOT_LOADED -> R.string.school_messages_not_connected
                    else -> R.string.school_admin_portal_links_network
                }), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = p.ink)
            })
    }
    links?.let { list ->
        AlertDialog(onDismissRequest = { links = null }, confirmButton = { TextButton({ links = null }) { Text(stringResource(R.string.school_messages_close)) } },
            title = { Text(stringResource(R.string.school_admin_portal_links)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (linkFailed) Text(stringResource(R.string.school_admin_portal_link_failed), color = p.danger)
                    list.forEach { link ->
                        Text(link.description, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                            scope.launch {
                                val url = try { controller.resolvePortalLink(link.code) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                                if (url == null) linkFailed = true else { linkFailed = false; links = null; runCatching { uri.openUri(url) } }
                            }
                        }.padding(vertical = 12.dp), color = p.ink, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            })
    }
}

@Composable
private fun AdminList(
    notices: List<SchoolAdminNotice>, status: SchoolAdminStatus, updated: Long?, college: List<SchoolCollegeNotice>,
    suggestions: List<SchoolNoticeSuggestion>, onOpen: (SchoolAdminNotice) -> Unit, onOpenDate: (LocalDate) -> Unit,
    onAddSuggestion: (SchoolNoticeSuggestion) -> Unit, onOpenPortal: () -> Unit, onLinks: (() -> Unit)?, linksLoading: Boolean,
) {
    val p = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize().testTag("school_admin_list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
        val message = when (status) {
            SchoolAdminStatus.OK -> null
            SchoolAdminStatus.NOT_LOADED -> R.string.school_admin_not_loaded
            SchoolAdminStatus.SIGN_IN -> R.string.school_admin_status_sign_in
            SchoolAdminStatus.UNRECOGNIZED -> R.string.school_admin_status_unrecognized
            SchoolAdminStatus.NETWORK -> R.string.school_admin_status_network
            SchoolAdminStatus.UNAVAILABLE -> R.string.school_admin_status_unavailable
            SchoolAdminStatus.STORAGE -> R.string.school_messages_storage
        }
        if (message != null) item {
            Card {
                Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (status == SchoolAdminStatus.NOT_LOADED) p.ink else p.danger, style = MaterialTheme.typography.bodyMedium)
                Row { TextButton(onOpenPortal, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.school_admin_open_portal)) }
                    if (onLinks != null) TextButton(onLinks, Modifier.heightIn(min = 48.dp), enabled = !linksLoading) { Text(stringResource(R.string.school_admin_portal_links)) } }
            }
        }
        item { Updated(updated) }
        if (college.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.school_admin_notices)) }
            items(college, key = { it.identity }) { n -> CollegeCard(n, onOpenDate) { notices.firstOrNull { it.id == n.sourceId }?.let(onOpen) } }
        }
        if (suggestions.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.school_admin_suggestions)); Note(stringResource(R.string.school_admin_suggestions_note)) }
            items(suggestions, key = { it.id }) { s ->
                Card {
                    Text(longDate(s.date), color = p.ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(s.title, color = p.muted, style = MaterialTheme.typography.bodySmall)
                    Text(s.detail, color = p.ink, style = MaterialTheme.typography.bodyMedium)
                    TextButton({ onAddSuggestion(s) }, Modifier.heightIn(min = 48.dp).testTag("school_suggestion_add_${s.id}")) { Text(stringResource(R.string.school_admin_suggestion_add)) }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.school_admin_list)) }
        if (notices.isEmpty() && status == SchoolAdminStatus.OK) item { Note(stringResource(R.string.school_admin_empty)) }
        items(notices, key = { it.id }) { n ->
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("school_admin_${n.id}").clickable(role = Role.Button) { onOpen(n) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(n.subject, color = p.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOf(shortDate(n.date), n.sender).filter { it.isNotBlank() }.joinToString(" · "), color = p.muted, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = p.line)
        }
    }
}

@Composable
private fun CollegeCard(n: SchoolCollegeNotice, onOpenDate: (LocalDate) -> Unit, onSource: () -> Unit) {
    val p = LocalPalette.current
    val confirmed = n.status == SchoolNoticeStatus.CONFIRMED
    Card(emphasis = true) {
        Text(stringResource(when (n.status) { SchoolNoticeStatus.CONFIRMED -> R.string.school_admin_confirmed; SchoolNoticeStatus.UPDATED -> R.string.school_admin_updated; else -> R.string.school_admin_warning }).uppercase(),
            color = if (confirmed) p.danger else p.muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(longDate(n.targetDate), color = p.ink, style = MaterialTheme.typography.titleMedium)
        Text(n.subject, color = p.ink, style = MaterialTheme.typography.bodyMedium)
        Text(listOf(n.sender, stringResource(R.string.school_admin_posted, shortDate(n.sourceDate))).filter { it.isNotBlank() }.joinToString(" · "), color = p.muted, style = MaterialTheme.typography.bodySmall)
        n.until?.let { Text(stringResource(R.string.school_admin_until, shortTime(it)), color = p.ink, style = MaterialTheme.typography.bodyMedium) }
        n.decisionAt?.let { Text(stringResource(R.string.school_admin_decision, shortTime(it)), color = p.ink, style = MaterialTheme.typography.bodyMedium) }
        n.resume?.let { Text(stringResource(R.string.school_admin_resume, longDate(it)), color = p.ink, style = MaterialTheme.typography.bodyMedium) }
        if (n.exceptions.isNotEmpty()) {
            Text(stringResource(R.string.school_admin_exceptions), Modifier.padding(top = 4.dp), color = p.muted, style = MaterialTheme.typography.labelMedium)
            n.exceptions.forEach { Text("• $it", color = p.ink, style = MaterialTheme.typography.bodySmall) }
        }
        if (n.superseded.isNotEmpty()) Text(stringResource(R.string.school_admin_superseded), color = p.muted, style = MaterialTheme.typography.bodySmall)
        if (!n.detailRead) Text(stringResource(R.string.school_admin_title_only), color = p.muted, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (n.classesMarked) R.string.school_admin_classes_marked else R.string.school_admin_scope_note), color = p.muted, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton({ onOpenDate(n.targetDate) }, Modifier.heightIn(min = 48.dp).testTag("school_notice_day_${n.identity}")) { Text(stringResource(R.string.school_admin_show_day)) }
            TextButton(onSource, Modifier.heightIn(min = 48.dp).testTag("school_notice_source_${n.identity}")) { Text(stringResource(R.string.school_admin_open_notice)) }
        }
    }
}

@Composable
private fun AdminDetail(n: SchoolAdminNotice, college: SchoolCollegeNotice?, onOpenPortal: () -> Unit, onOpenDate: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().testTag("school_admin_detail").verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(n.subject, Modifier.semantics { heading() }, color = p.ink, style = MaterialTheme.typography.titleMedium)
        Text(longDate(n.date), color = p.muted, style = MaterialTheme.typography.bodySmall)
        if (n.sender.isNotBlank()) Text(stringResource(R.string.school_admin_source, n.sender), color = p.ink, style = MaterialTheme.typography.bodyMedium)
        if (n.category.isNotBlank()) Text(stringResource(R.string.school_admin_category, n.category), color = p.muted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.line)
        if (n.body != null) SelectionContainer { Text(n.body, color = p.ink, style = MaterialTheme.typography.bodyLarge) }
        else {
            Text(stringResource(R.string.school_admin_title_only), color = p.muted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onOpenPortal, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.school_admin_open_portal)) }
        }
        if (college != null) TextButton({ onOpenDate(college.targetDate) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.school_admin_show_day)) }
    }
}

@Composable
private fun Card(emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().background(if (emphasis) p.raised else p.surface, RoundedCornerShape(12.dp))
        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun Note(text: String, danger: Boolean = false) {
    val p = LocalPalette.current
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (danger) p.danger else p.muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Updated(millis: Long?) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { RelativeDateTimeFormatter.getInstance(locale) }
    val text = if (millis == null) stringResource(R.string.school_messages_never)
    else {
        val elapsed = System.currentTimeMillis() - millis
        val minutes = kotlin.math.abs(elapsed / 60_000)
        val direction = if (elapsed >= 0) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
        val relative = when {
            minutes == 0L -> formatter.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
            minutes < 60 -> formatter.format(minutes.toDouble(), direction, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
            minutes < 1440 -> formatter.format((minutes / 60).toDouble(), direction, RelativeDateTimeFormatter.RelativeUnit.HOURS)
            else -> formatter.format((minutes / 1440).toDouble(), direction, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        }
        stringResource(R.string.school_messages_updated, relative)
    }
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = LocalPalette.current.faint, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun shortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalConfiguration.current.locales[0]))
@Composable
private fun longDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(LocalConfiguration.current.locales[0]))
@Composable
private fun shortTime(time: LocalTime): String = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0]))
