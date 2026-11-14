package io.github.sunsind.sunlith.school

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.os.Parcel
import android.util.Log
import io.github.sunsind.sunlith.R
import java.time.LocalDate

internal class SchoolCalendarWriter(
    private val context: Context,
    private val applyBatch: (ArrayList<ContentProviderOperation>) -> Unit = { context.contentResolver.applyBatch(CalendarContract.AUTHORITY, it); Unit },
) {
    private val resolver get() = context.contentResolver
    fun calendars(): List<SchoolCalendarChoice> {
        permission()
        val rows = query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
            "${Calendars.CALENDAR_ACCESS_LEVEL}>=?", arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()))
        val names = rows.groupingBy { it[Calendars.CALENDAR_DISPLAY_NAME].orEmpty() }.eachCount()
        return rows.map {
            val name = it[Calendars.CALENDAR_DISPLAY_NAME].orEmpty()
            SchoolCalendarChoice(it.getValue(Calendars._ID)!!.toLong(), if (names.getValue(name) > 1) "$name (${it[Calendars.ACCOUNT_NAME].orEmpty()})" else name)
        }
    }
    fun destination(requested: Long?): Long {
        val choices = calendars()
        if (requested != null) return requested.also { if (choices.none { c -> c.id == it }) throw SchoolFailure(SchoolError.CALENDAR_PERMISSION) }
        val recent = context.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE).getLong("last_calendar", -1)
        if (choices.any { it.id == recent }) return recent
        return query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf("Sunlith", CalendarContract.ACCOUNT_TYPE_LOCAL, Calendars.CAL_ACCESS_CONTRIBUTOR.toString()))
            .firstOrNull()?.get(Calendars._ID)?.toLong() ?: choices.firstOrNull()?.id ?: throw SchoolFailure(SchoolError.CALENDAR_PERMISSION)
    }
    fun calendarIdentity(id: Long): Map<String, String?> {
        permission()
        val row = query(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), CALENDAR_COLUMNS, null, null).singleOrNull()
            ?: throw SchoolFailure(SchoolError.CALENDAR_PERMISSION)
        if (row[Calendars.CALENDAR_ACCESS_LEVEL]!!.toInt() < Calendars.CAL_ACCESS_CONTRIBUTOR) throw SchoolFailure(SchoolError.CALENDAR_PERMISSION)
        return row
    }
    fun pruneHistory(record: SchoolRecord, term: SchoolTerm, today: LocalDate): List<SchoolOwned> {
        schoolRequire(record.journal == null)
        val retired = record.ledger.filter { it.item.date.isBefore(today) && (it.item.date.isBefore(term.start) || it.item.date.isAfter(term.end)) }
        if (retired.isEmpty()) return record.ledger
        permission()
        if (record.calendarIdentity != calendarIdentity(record.calendarId)) throw SchoolFailure(SchoolError.CALENDAR_CHANGED)
        val rows = eventRows(record.calendarId).associateBy { it[Events._ID]!!.toLong() }
        val releases = retired.mapNotNull { owned ->
            val row = rows[owned.rowId] ?: return@mapNotNull null
            if (releasedIdentity(owned, record, row)) return@mapNotNull null
            if (!identity(owned, record.calendarId, row, context.packageName)) return@mapNotNull null
            releaseOperations(record, owned, row.managedRevision(), false)
        }
        applyChunks(record, releases)
        val retiredIds = retired.map { it.rowId }.toSet()
        return record.ledger.filter { it.rowId !in retiredIds }
    }
    fun plan(record: SchoolRecord, feed: SchoolFeed, today: LocalDate, noticeOnly: Boolean = false): SchoolJournal {
        permission()
        schoolRequire(feed.account == record.account)
        if (record.calendarIdentity != calendarIdentity(record.calendarId)) throw SchoolFailure(SchoolError.CALENDAR_CHANGED)
        val rows = eventRows(record.calendarId)
        val byId = rows.associateBy { it.getValue(Events._ID)!!.toLong() }
        val expected = record.ledger.mapNotNull { owned ->
            val row = byId[owned.rowId]
            if (row == null || !identity(owned, record.calendarId, row, context.packageName)) return@mapNotNull null
            owned.copy(revision = row.managedRevision())
        }
        val old = expected.associateBy { it.item.id }
        schoolRequire(record.ledger.map { it.item.id }.distinct().size == record.ledger.size)
        val ownedIds = record.ledger.map { it.rowId }.toSet()
        val lostIds = record.ledger.map { it.item.id }.toSet() - old.keys
        val baselineIds = if (record.initialized) record.baseline.map { it.id }.toSet() else emptySet()
        val linked = mutableListOf<SchoolOwned>()
        val usedCandidates = mutableSetOf<Long>()
        val incoming = feed.items.associateBy { it.id }
        val after = expected.filter { owned ->
            (noticeOnly && owned.item.kind != SchoolNoticeInterpreter.NOTICE_KIND) ||
                (owned.item.id !in incoming && (owned.item.date.isBefore(today) || owned.item.date.isBefore(feed.term.start) || owned.item.date.isAfter(feed.term.end)))
        }.toMutableList()
        feed.items.forEach { item ->
            if (noticeOnly && item.kind != SchoolNoticeInterpreter.NOTICE_KIND) return@forEach
            var existing = old[item.id]
            if (existing == null && item.date.isBefore(today) && (item.id in lostIds || item.id in baselineIds)) return@forEach
            if (existing != null && item.date.isBefore(today) && item.kind != SchoolNoticeInterpreter.NOTICE_KIND) { after += existing; return@forEach }
            if (existing == null && !record.initialized) {
                val provenance = rows.filter { row ->
                    row[Events._ID]!!.toLong() !in ownedIds && row[Events.CUSTOM_APP_PACKAGE] == context.packageName &&
                        (row[Events.CUSTOM_APP_URI] == releasedMarker(record.account, item.id) || orphanMarker(row[Events.CUSTOM_APP_URI], record.account, item.id))
                }
                if (provenance.any { it[Events.UID_2445].isNullOrBlank() || !linkable(it) }) throw SchoolFailure(SchoolError.LEGACY_COLLISION)
                val candidates = if (provenance.isNotEmpty()) provenance else if (item.kind != "01") emptyList() else rows.filter { row ->
                    row[Events._ID]!!.toLong() !in ownedIds && !row[Events.UID_2445].isNullOrBlank() &&
                        row[Events.CUSTOM_APP_PACKAGE].isNullOrBlank() && row[Events.CUSTOM_APP_URI].isNullOrBlank() &&
                        linkable(row) && row[Events.DTSTART] == item.startMillis.toString() && row[Events.DTEND] == item.endMillis.toString() &&
                        row[Events.ALL_DAY] == (if (item.allDay) "1" else "0") && legacyCourseGroup(row[Events.DESCRIPTION].orEmpty()) == (item.course to item.group)
                }
                if (candidates.size > 1) throw SchoolFailure(SchoolError.LEGACY_COLLISION)
                if (candidates.size == 1) {
                    val row = candidates.single()
                    val id = row[Events._ID]!!.toLong()
                    if (!usedCandidates.add(id)) throw SchoolFailure(SchoolError.LEGACY_COLLISION)
                    val marker = newMarker(record, item, rows)
                    existing = SchoolOwned(item, id, row[Events.UID_2445]!!, marker, row.withoutId())
                    linked += existing
                }
                val collision = existing == null && rows.any { row ->
                    row[Events._ID]!!.toLong() !in ownedIds && !row[Events.UID_2445].isNullOrBlank() &&
                        row[Events.CUSTOM_APP_PACKAGE].isNullOrBlank() && row[Events.CUSTOM_APP_URI].isNullOrBlank() &&
                        row[Events.DTSTART] == item.startMillis.toString() && row[Events.DTEND] == item.endMillis.toString() && row[Events.ALL_DAY] == (if (item.allDay) "1" else "0") &&
                        ((item.course.isNotBlank() && item.group.isNotBlank() && row[Events.DESCRIPTION].orEmpty().contains(item.course) && row[Events.DESCRIPTION].orEmpty().contains(item.group)) ||
                            row[Events.TITLE].orEmpty().let { it.length >= 8 && item.title.startsWith(it) })
                }
                if (collision) throw SchoolFailure(SchoolError.LEGACY_COLLISION)
            }
            val marker = existing?.marker ?: newMarker(record, item, rows)
            val uid = existing?.uid ?: "sunlith-school-${schoolHash(marker)}@sunlith"
            val changed = values(record.calendarId, item, uid, marker)
            val revision = existing?.revision?.managedRevision()?.toMutableMap() ?: NEW_REVISION.toMutableMap()
            revision.putAll(changed)
            after += SchoolOwned(item, existing?.rowId ?: -1, uid, marker, revision)
        }
        val retained = after.map { it.item.id }.toSet()
        val removed = expected.filter { it.item.id !in retained }
        val journal = SchoolJournal(after, removed, feed.items, System.currentTimeMillis(), linked, expected, feed.term.key, feed.term.name)
        localRequire(journal.json().toString().toByteArray().size <= 4 * 1024 * 1024 && after.size + removed.size <= 1500)
        steps(record, journal)
        return journal
    }
    fun summary(record: SchoolRecord, plan: SchoolJournal) = SchoolChangeSummary(
        plan.after.count { it.rowId < 0 }, plan.after.count { next -> next.rowId >= 0 && plan.linked.none { it.rowId == next.rowId } && (plan.expected ?: record.ledger).single { it.rowId == next.rowId }.revision.managedRevision() != next.revision.managedRevision() }, plan.removed.size, plan.linked.size)

    fun commit(record: SchoolRecord, plan: SchoolJournal): List<SchoolOwned> = commit(record, plan, null)

    fun commitSteps(record: SchoolRecord, plan: SchoolJournal, persist: (SchoolRecord) -> Unit): List<SchoolOwned> {
        permission()
        val parts = steps(record, plan)
        var current = record
        var ledger = plan.expected ?: record.ledger
        parts.indices.forEach { index ->
            val step = step(record, plan, parts, index, ledger)
            val journaled = current.copy(journal = step)
            persist(journaled)
            ledger = commit(journaled, step, if (parts.size > 1) "${index + 1}/${parts.size}" else null)
            if (!step.complete) {
                current = journaled.copy(ledger = ledger, journal = null)
                persist(current)
            }
        }
        return ledger
    }

    internal fun steps(record: SchoolRecord, plan: SchoolJournal): List<Set<String>> {
        val groups = (changed(record, plan).map { it.item.id } + plan.removed.map { it.item.id }).distinct()
        if (batchBytes(operations(record, plan)) <= BATCH_LIMIT) return listOf(groups.toSet())
        fun fit(ids: List<String>): List<List<String>> {
            val bytes = batchBytes(operations(record, part(record, plan, ids.toSet())))
            if (ids.size == 1) { localRequire(bytes <= BATCH_LIMIT); return listOf(ids) }
            if (bytes <= STEP_TARGET) return listOf(ids)
            return fit(ids.subList(0, ids.size / 2)) + fit(ids.subList(ids.size / 2, ids.size))
        }
        return fit(groups).map { it.toSet() }
    }

    internal fun step(record: SchoolRecord, plan: SchoolJournal, parts: List<Set<String>>, index: Int, ledger: List<SchoolOwned>): SchoolJournal {
        if (parts.size == 1) return plan
        val current = parts[index]
        val previous = ledger.associateBy { it.item.id }
        val planned = (plan.expected ?: record.ledger).associateBy { it.item.id }
        val known = (plan.after + plan.removed).map { it.item.id }.toSet()
        if (previous.size != ledger.size || ledger.any { it.item.id !in known }) throw SchoolFailure(SchoolError.STORAGE)
        val expected = ledger.map { owned ->
            if (owned.item.id !in current) owned
            else planned[owned.item.id]?.takeIf { it.rowId == owned.rowId } ?: throw SchoolFailure(SchoolError.STORAGE)
        }
        val after = plan.after.mapNotNull { if (it.item.id in current) it else previous[it.item.id] } +
            plan.removed.filter { it.item.id !in current }.mapNotNull { previous[it.item.id] }
        val complete = index == parts.lastIndex
        return SchoolJournal(after, plan.removed.filter { it.item.id in current }, if (complete) plan.baseline else emptyList(), plan.at,
            plan.linked.filter { it.item.id in current }, expected, plan.termKey, plan.termName, complete)
    }

    internal fun operations(record: SchoolRecord, plan: SchoolJournal): ArrayList<ContentProviderOperation> {
        val operations = arrayListOf<ContentProviderOperation>()
        val old = (plan.expected ?: record.ledger).associateBy { it.rowId }
        val changed = changed(record, plan)
        if (changed.isEmpty() && plan.removed.isEmpty()) return operations
        operations += assertRow(Calendars.CONTENT_URI, record.calendarId, record.calendarIdentity)
        (changed.mapNotNull { old[it.rowId] } + plan.removed).distinctBy { it.rowId }.forEach {
            operations += assertRow(Events.CONTENT_URI, it.rowId, it.revision.managedRevision())
        }
        plan.linked.forEach { operations += assertRow(Events.CONTENT_URI, it.rowId, it.revision) }
        val newMarkers = changed.filter { owned -> owned.rowId < 0 || plan.linked.any { it.rowId == owned.rowId } }.map { it.marker }
        schoolRequire(newMarkers.distinct().size == newMarkers.size)
        newMarkers.chunked(300).forEach { markers ->
            operations += ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                .withSelection("${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.CUSTOM_APP_URI} IN (${markers.joinToString(",") { "?" }})",
                    (listOf(context.packageName) + markers).toTypedArray()).withExpectedCount(0).build()
        }
        changed.forEach { owned ->
            if (owned.rowId < 0) {
                operations += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(contentValues(values(record.calendarId, owned.item, owned.uid, owned.marker).filterValues { it != null })).build()
            } else operations += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Events.CONTENT_URI, owned.rowId))
                .withValues(contentValues(values(record.calendarId, owned.item, owned.uid, owned.marker))).withExpectedCount(1).build()
        }
        plan.removed.forEach { operations += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Events.CONTENT_URI, it.rowId)).withExpectedCount(1).build() }
        return operations
    }

    private fun commit(record: SchoolRecord, plan: SchoolJournal, label: String?): List<SchoolOwned> {
        permission()
        val operations = operations(record, plan)
        if (operations.isEmpty()) return plan.after
        val bytes = batchBytes(operations)
        if (label != null) Log.i(TAG, "Calendar step $label operations=${operations.size} bytes=$bytes")
        try {
            localRequire(bytes <= BATCH_LIMIT)
            applyBatch(operations)
            return verify(record, plan) ?: throw SchoolFailure(SchoolError.STORAGE)
        } catch (e: Exception) {
            Log.w(TAG, "Calendar step ${label ?: "1/1"} stopped operations=${operations.size} bytes=$bytes type=${e.javaClass.simpleName}")
            throw e
        }
    }
    private fun changed(record: SchoolRecord, plan: SchoolJournal): List<SchoolOwned> {
        val old = (plan.expected ?: record.ledger).associateBy { it.rowId }
        return plan.after.filter { it.rowId < 0 || old[it.rowId]?.revision?.managedRevision() != it.revision.managedRevision() }
    }
    private fun part(record: SchoolRecord, plan: SchoolJournal, ids: Set<String>) = SchoolJournal(
        plan.after.filter { it.item.id in ids }, plan.removed.filter { it.item.id in ids }, emptyList(), plan.at,
        plan.linked.filter { it.item.id in ids }, plan.expected ?: record.ledger)
    fun verify(record: SchoolRecord, plan: SchoolJournal, allowContentChanges: Boolean = false): List<SchoolOwned>? {
        permission()
        schoolRequire(record.calendarIdentity == calendarIdentity(record.calendarId))
        val rows = eventRows(record.calendarId)
        if (plan.removed.any { old -> rows.any { it[Events._ID] == old.rowId.toString() && identity(old, record.calendarId, it, context.packageName) } }) return null
        val result = mutableListOf<SchoolOwned>()
        val expected = (plan.expected ?: record.ledger).associateBy { it.rowId }
        val recoverContent = allowContentChanges && (plan.after.any { it.rowId < 0 } || plan.linked.isNotEmpty() || plan.removed.isNotEmpty())
        plan.after.forEach { owned ->
            val candidates = rows.filter { it[Events.CUSTOM_APP_PACKAGE] == context.packageName && it[Events.CUSTOM_APP_URI] == owned.marker }
            if (candidates.size != 1) return null
            val row = candidates.single()
            val actual = row.managedRevision()
            if (owned.rowId >= 0 && row[Events._ID] != owned.rowId.toString()) return null
            if (!identity(owned, record.calendarId, actual, context.packageName)) return null
            val touched = expected[owned.rowId]?.revision?.managedRevision() != owned.revision.managedRevision()
            if (touched && owned.revision.managedRevision().any { (k, v) -> actual[k] != v }) {
                if (!recoverContent || actual == expected[owned.rowId]?.revision?.managedRevision()) return null
            }
            result += owned.copy(rowId = row[Events._ID]!!.toLong(), revision = actual)
        }
        return result
    }
    fun baselineUnchanged(record: SchoolRecord): Boolean {
        permission()
        if (record.calendarIdentity != calendarIdentity(record.calendarId)) return false
        val rows = eventRows(record.calendarId)
        val byId = rows.associateBy { it[Events._ID]!!.toLong() }
        val journal = record.journal ?: return true
        val before = ((journal.expected ?: record.ledger) + journal.linked).associateBy { it.rowId }
        val introduced = journal.after.filter { it.rowId < 0 || journal.linked.any { linked -> linked.rowId == it.rowId } }
        val possibleIntroductions = introduced.chunked(100).flatMap { entries ->
            val placeholders = entries.joinToString(",") { "?" }
            query(Events.CONTENT_URI, arrayOf(Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI, Events.UID_2445),
                "${Events.DELETED}=0 AND (${Events.CUSTOM_APP_URI} IN ($placeholders) OR ${Events.UID_2445} IN ($placeholders))",
                (entries.map { it.marker } + entries.map { it.uid }).toTypedArray())
        }
        if (introduced.any { entry -> possibleIntroductions.any { it[Events.CUSTOM_APP_URI] == entry.marker && it[Events.CUSTOM_APP_PACKAGE] == context.packageName } }) return false
        if (introduced.filter { it.rowId < 0 }.any { entry -> possibleIntroductions.any { it[Events.UID_2445] == entry.uid } }) return false
        if (journal.removed.any { entry -> byId[entry.rowId]?.let { identity(entry, record.calendarId, it, context.packageName) } != true }) return false
        val updates = journal.after.filter { next -> next.rowId >= 0 && before[next.rowId]?.revision?.managedRevision() != next.revision.managedRevision() }
        if (updates.any { next ->
            val original = before[next.rowId] ?: return@any true
            val actual = byId[next.rowId] ?: return@any true
            !sameRowIdentity(original.revision, actual) || actual.managedRevision() == next.revision.managedRevision()
        }) return false
        if (introduced.isNotEmpty() || journal.removed.isNotEmpty()) return true
        return updates.all { next -> byId[next.rowId]?.managedRevision() == before[next.rowId]?.revision?.managedRevision() }
    }
    fun disconnect(record: SchoolRecord, remove: Boolean) {
        try { permission() } catch (e: SchoolFailure) { if (!remove) return else throw e }
        val calendar = try { calendarIdentity(record.calendarId) } catch (e: SchoolFailure) { if (!remove) return else throw e }
        if (record.calendarIdentity != calendar) {
            if (remove) throw SchoolFailure(SchoolError.CALENDAR_CHANGED)
            return
        }
        val rows = eventRows(record.calendarId).associateBy { it[Events._ID]!!.toLong() }
        val verified = (record.journal?.after.orEmpty() + record.ledger + record.journal?.removed.orEmpty()).mapNotNull { owned ->
            val row = if (owned.rowId >= 0) rows[owned.rowId] else rows.values.singleOrNull { identity(owned, record.calendarId, it, context.packageName) }
            if (row == null) return@mapNotNull null
            if (!identity(owned, record.calendarId, row, context.packageName)) return@mapNotNull null
            owned.copy(rowId = row[Events._ID]!!.toLong()) to row.managedRevision()
        }.groupBy { it.first.rowId }.values.map { matches ->
            matches.firstOrNull { it.first.revision.managedRevision() == it.second } ?: matches.first()
        }
        if (verified.isEmpty()) return
        applyChunks(record, verified.map { (owned, revision) -> releaseOperations(record, owned, revision, remove) })
    }
    private fun releasedIdentity(owned: SchoolOwned, record: SchoolRecord, row: Map<String, String?>) =
        identity(owned.copy(marker = releasedMarker(record.account, owned.item.id)), record.calendarId, row, context.packageName)

    private fun releaseOperations(record: SchoolRecord, owned: SchoolOwned, revision: Map<String, String?>, remove: Boolean): List<ContentProviderOperation> {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, owned.rowId)
        val mutation = if (remove && owned.revision.managedRevision() == revision) ContentProviderOperation.newDelete(uri).withExpectedCount(1).build()
        else ContentProviderOperation.newUpdate(uri).withValue(Events.CUSTOM_APP_PACKAGE, context.packageName)
            .withValue(Events.CUSTOM_APP_URI, releasedMarker(record.account, owned.item.id)).withExpectedCount(1).build()
        return listOf(assertRow(Events.CONTENT_URI, owned.rowId, revision), mutation)
    }
    private fun applyChunks(record: SchoolRecord, groups: List<List<ContentProviderOperation>>) {
        fun batch() = arrayListOf(assertRow(Calendars.CONTENT_URI, record.calendarId, record.calendarIdentity))
        var operations = batch()
        var count = 0
        groups.forEach { group ->
            val candidate = ArrayList(operations).apply { addAll(group) }
            if (count > 0 && (count >= 100 || batchBytes(candidate) > BATCH_LIMIT)) {
                boundedBatch(operations)
                applyBatch(operations)
                operations = batch()
                count = 0
            }
            operations.addAll(group)
            boundedBatch(operations)
            count++
        }
        if (count > 0) applyBatch(operations)
    }
    private fun newMarker(record: SchoolRecord, item: SchoolItem, rows: List<Map<String, String?>>): String {
        val base = "sunlith-school://owned/${record.connection}/${schoolHash(record.account)}/${schoolHash(item.id)}"
        val occupied = rows.mapNotNull { it[Events.CUSTOM_APP_URI] }.toSet() + record.ledger.map { it.marker }
        return generateSequence(0) { it + 1 }.map { if (it == 0) base else "$base/$it" }.first { it !in occupied }
    }
    private fun values(calendar: Long, item: SchoolItem, uid: String, marker: String): Map<String, String?> = linkedMapOf(
        Events.CALENDAR_ID to calendar.toString(), Events.TITLE to when { item.kind == "absence" -> context.getString(R.string.school_sync_absence_notice, item.title); item.teacherAbsent -> context.getString(R.string.school_sync_absent_title, item.title); else -> item.title },
        Events.DESCRIPTION to buildString {
            if (item.teacherAbsent) appendLine(context.getString(R.string.school_sync_teacher_absent))
            if (item.course.isNotBlank()) appendLine(context.getString(R.string.school_sync_course, item.course, item.group))
            if (item.classKind in setOf("1", "2", "3")) appendLine(context.getString(R.string.school_sync_class_kind, when (item.classKind) { "1" -> context.getString(R.string.school_sync_theory); "2" -> context.getString(R.string.school_sync_lab); else -> context.getString(R.string.school_sync_stage) }))
            if (item.period.isNotBlank()) appendLine(context.getString(R.string.school_sync_period, item.period))
            if (item.periodTitle.isNotBlank()) appendLine(item.periodTitle)
            if (item.teachers.isNotEmpty()) appendLine(context.getString(R.string.school_sync_teachers, item.teachers.joinToString(", ")))
            if (item.rooms.isNotEmpty()) appendLine(context.getString(R.string.school_sync_rooms, item.rooms.joinToString(", ")))
            item.notes.forEach { appendLine(it) }
        }.trimEnd(), Events.EVENT_LOCATION to item.rooms.joinToString(", "), Events.DTSTART to item.startMillis.toString(),
        Events.DTEND to item.endMillis.toString(), Events.ALL_DAY to if (item.allDay) "1" else "0", Events.EVENT_TIMEZONE to item.zone,
        Events.STATUS to Events.STATUS_CONFIRMED.toString(), Events.UID_2445 to uid, Events.CUSTOM_APP_PACKAGE to context.packageName, Events.CUSTOM_APP_URI to marker,
        Events.RRULE to null, Events.RDATE to null, Events.EXRULE to null, Events.EXDATE to null, Events.DURATION to null,
    )
    private fun permission() {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) throw SchoolFailure(SchoolError.CALENDAR_PERMISSION)
    }
    private fun eventRows(calendar: Long) = query(Events.CONTENT_URI, arrayOf(Events._ID, *COLUMNS), "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0", arrayOf(calendar.toString()))
    private fun query(uri: android.net.Uri, columns: Array<String>, selection: String?, arguments: Array<String>?): List<Map<String, String?>> =
        (resolver.query(uri, columns, selection, arguments, null) ?: throw SchoolFailure(SchoolError.STORAGE)).use { cursor ->
            buildList { while (cursor.moveToNext()) { localRequire(size < 50_000); add(columns.associateWith { key -> cursor.getColumnIndexOrThrow(key).let { if (cursor.isNull(it)) null else cursor.getString(it) } }) } }
        }
    companion object {
        internal val COLUMNS = arrayOf(Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND,
            Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE,
            Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.STATUS, Events.UID_2445, Events.DELETED,
            Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI, Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA, Events._SYNC_ID)
        private val CALENDAR_COLUMNS = arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.NAME, Calendars.OWNER_ACCOUNT, Calendars.CALENDAR_ACCESS_LEVEL)
        private val NEW_REVISION: Map<String, String?> = mapOf(Events.DELETED to "0")
        private val MANAGED_COLUMNS = setOf(Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE, Events.RDATE,
            Events.EXRULE, Events.EXDATE, Events.STATUS, Events.UID_2445, Events.DELETED, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
        internal fun legacyCourseGroup(description: String): Pair<String, String>? {
            val match = Regex("^([A-Za-z0-9]+)\\s*-\\s*([0-9]+)\\s*$").matchEntire(description.trim()) ?: return null
            return match.groupValues[1] to match.groupValues[2]
        }
        fun identity(owned: SchoolOwned, calendar: Long, row: Map<String, String?>, packageName: String): Boolean =
            row[Events.CALENDAR_ID] == calendar.toString() && row[Events.UID_2445] == owned.uid && row[Events.CUSTOM_APP_PACKAGE] == packageName && row[Events.CUSTOM_APP_URI] == owned.marker && row[Events.DELETED] == "0"
        private fun Map<String, String?>.withoutId() = filterKeys { it != Events._ID }
        private fun Map<String, String?>.managedRevision() = filterKeys { it in MANAGED_COLUMNS }
        private fun releasedMarker(account: String, id: String) = "sunlith-school://released/$account/${schoolHash(id)}"
        private fun orphanMarker(marker: String?, account: String, id: String): Boolean {
            if (marker?.startsWith("sunlith-school://owned/") != true) return false
            val parts = marker.removePrefix("sunlith-school://owned/").split('/')
            return parts.size in 3..4 && parts[0].isNotBlank() && parts[1] == schoolHash(account) && parts[2] == schoolHash(id) &&
                (parts.size == 3 || parts[3].toIntOrNull()?.let { it > 0 } == true)
        }
        private fun sameRowIdentity(before: Map<String, String?>, actual: Map<String, String?>) =
            listOf(Events.CALENDAR_ID, Events.UID_2445, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI, Events.DELETED).all { before[it] == actual[it] }
        private fun linkable(row: Map<String, String?>) = row[Events.RRULE].isNullOrBlank() && row[Events.ORIGINAL_ID].isNullOrBlank() &&
            row[Events.ORIGINAL_SYNC_ID].isNullOrBlank() && row[Events.ORIGINAL_INSTANCE_TIME].isNullOrBlank() &&
            row[Events.STATUS] != Events.STATUS_CANCELED.toString() && row[Events.HAS_ATTENDEE_DATA] != "1" &&
            row[Events.RDATE].isNullOrBlank() && row[Events.EXDATE].isNullOrBlank() && row[Events.EXRULE].isNullOrBlank()
        private fun contentValues(values: Map<String, String?>) = ContentValues().apply { values.forEach { (k, v) -> if (v == null) putNull(k) else put(k, v) } }
        private fun assertRow(uri: android.net.Uri, id: Long, revision: Map<String, String?>) = ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(uri, id))
            .withValues(contentValues(revision)).withExpectedCount(1).build()
        private const val TAG = "SunlithSchool"
        internal const val BATCH_LIMIT = 800_000
        internal const val STEP_TARGET = 700_000
        private fun localRequire(value: Boolean) { if (!value) throw SchoolFailure(SchoolError.CALENDAR_LIMIT) }
        private fun boundedBatch(operations: ArrayList<ContentProviderOperation>) {
            localRequire(batchBytes(operations) <= BATCH_LIMIT)
        }
        internal fun batchBytes(operations: ArrayList<ContentProviderOperation>): Int {
            val parcel = Parcel.obtain()
            return try { parcel.writeTypedList(operations); parcel.dataSize() } finally { parcel.recycle() }
        }
    }
}

internal object SchoolOwnership {
    fun isManaged(context: Context, id: Long, revision: Map<String, String?>): Boolean {
        if (revision[Events.CUSTOM_APP_PACKAGE] != context.packageName || revision[Events.CUSTOM_APP_URI]?.startsWith("sunlith-school://owned/") != true) return false
        val record = SchoolStore(context).load() ?: return false
        val entries = record.ledger + record.journal?.after.orEmpty()
        return entries.any { (it.rowId == id || it.rowId < 0) && SchoolCalendarWriter.identity(it, record.calendarId, revision, context.packageName) }
    }
}
