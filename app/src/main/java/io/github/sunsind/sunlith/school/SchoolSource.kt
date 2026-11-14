package io.github.sunsind.sunlith.school

import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import org.json.JSONObject

internal class SchoolFailure(val reason: SchoolError) : Exception(reason.name)

internal fun schoolRequire(value: Boolean) { if (!value) throw SchoolFailure(SchoolError.INVALID_DATA) }
internal fun schoolHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal data class SchoolTerm(val code: String, val name: String, val start: LocalDate, val end: LocalDate) {
    init { schoolRequire(code.isNotBlank() && !end.isBefore(start) && ChronoUnit.DAYS.between(start, end) < 366) }
    val key: String get() = schoolHash("$code\n$start\n$end")
    fun mondays(): List<LocalDate> = generateSequence(start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(end) }.toList()
}

internal data class SchoolItem(
    val id: String, val classKey: String, val title: String, val course: String, val group: String,
    val classKind: String, val kind: String, val date: LocalDate, val startMillis: Long, val endMillis: Long,
    val allDay: Boolean, val zone: String, val teachers: List<String>, val rooms: List<String>,
    val notes: List<String>, val teacherAbsent: Boolean, val order: Int = 0, val period: String = "", val periodTitle: String = "",
) {
    val classMatch: List<String> get() = listOf(classKey, course, group, date.toString(), startMillis.toString(), endMillis.toString(), allDay.toString())
    fun json(): JSONObject = JSONObject().put("id", id).put("class", classKey).put("title", title).put("course", course)
        .put("group", group).put("classKind", classKind).put("kind", kind).put("date", date.toString())
        .put("start", startMillis).put("end", endMillis).put("allDay", allDay).put("zone", zone)
        .put("teachers", JSONArray(teachers)).put("rooms", JSONArray(rooms)).put("notes", JSONArray(notes))
        .put("absent", teacherAbsent).put("order", order).put("period", period).put("periodTitle", periodTitle)
    companion object {
        fun read(j: JSONObject) = SchoolItem(j.getString("id"), j.getString("class"), j.getString("title"), j.getString("course"),
            j.getString("group"), j.getString("classKind"), j.getString("kind"), LocalDate.parse(j.getString("date")),
            j.getLong("start"), j.getLong("end"), j.getBoolean("allDay"), j.getString("zone"),
            j.getJSONArray("teachers").strings(), j.getJSONArray("rooms").strings(), j.getJSONArray("notes").strings(), j.getBoolean("absent"), j.getInt("order"), j.optString("period", ""), j.optString("periodTitle", ""))
    }
}

internal data class SchoolFeed(val account: String, val term: SchoolTerm, val items: List<SchoolItem>)

internal object SchoolSource {
    private val dateFormat = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
    private val timeFormat = DateTimeFormatter.ofPattern("HHmm").withResolverStyle(ResolverStyle.STRICT)
    fun date(value: String): LocalDate { schoolRequire(Regex("[0-9]{8}").matches(value)); return LocalDate.parse(value, dateFormat) }
    fun wireDate(value: LocalDate): String = value.format(dateFormat)
    fun term(j: JSONObject): SchoolTerm = SchoolTerm(j.requiredText("Code"), j.requiredText("Description"), date(j.requiredText("DateDebut")), date(j.requiredText("DateFin")))
    fun selectTerm(terms: List<SchoolTerm>, selected: String, today: LocalDate): SchoolTerm {
        schoolRequire(terms.map { it.code }.distinct().size == terms.size)
        if (selected.isNotBlank()) return terms.singleOrNull { it.code == selected } ?: throw SchoolFailure(SchoolError.INVALID_DATA)
        return terms.singleOrNull { !today.isBefore(it.start) && !today.isAfter(it.end) } ?: throw SchoolFailure(SchoolError.UNAVAILABLE)
    }
    fun agenda(rows: JSONArray, term: SchoolTerm, zone: ZoneId): List<SchoolItem> {
        schoolRequire(rows.length() <= 4000)
        val items = (0 until rows.length()).map { parse(rows.getJSONObject(it), zone, false) }
        schoolRequire(items.all { !it.date.isBefore(term.start) && !it.date.isAfter(term.end) })
        schoolRequire(items.map { it.id }.distinct().size == items.size)
        return items
    }
    fun absences(rows: JSONArray, monday: LocalDate, zone: ZoneId): List<SchoolItem> {
        schoolRequire(rows.length() <= 1000)
        return (0 until rows.length()).map { parse(rows.getJSONObject(it), zone, true) }.also { list ->
            schoolRequire(list.all { !it.date.isBefore(monday) && !it.date.isAfter(monday.plusWeeks(1)) })
            schoolRequire(list.map { it.id }.distinct().size == list.size)
        }
    }
    fun merge(agenda: List<SchoolItem>, absences: List<SchoolItem>, term: SchoolTerm): List<SchoolItem> {
        val unique = absences.groupBy { it.id }.map { (_, rows) -> schoolRequire(rows.distinct().size == 1); rows.first() }
        val result = agenda.toMutableList()
        unique.filter { !it.date.isBefore(term.start) && !it.date.isAfter(term.end) }.forEach { absence ->
            val matches = result.indices.filter { index -> result[index].kind == "01" && absence.classKey.isNotBlank() && result[index].classMatch == absence.classMatch }
            schoolRequire(matches.size <= 1)
            if (matches.size == 1) {
                val index = matches.single()
                result[index] = result[index].copy(teacherAbsent = true, notes = (result[index].notes + absence.notes).distinct())
            } else result += absence.copy(id = "absence:${absence.id}", kind = "absence")
        }
        schoolRequire(result.map { it.id }.distinct().size == result.size)
        return result.sortedWith(compareBy<SchoolItem> { it.startMillis }.thenBy { it.id })
    }
    private fun parse(j: JSONObject, zone: ZoneId, absence: Boolean): SchoolItem {
        val day = date(j.requiredText("Date"))
        val allDay = if (absence) j.optBoolean("JourneeComplete", false) else j.requiredBoolean("JourneeComplete")
        val start: Long
        val end: Long
        if (allDay) { start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        else {
            fun at(key: String): Long {
                val raw = j.requiredText(key)
                schoolRequire(Regex("[0-9]{4}").matches(raw))
                val local = day.atTime(LocalTime.parse(raw, timeFormat))
                val offsets = zone.rules.getValidOffsets(local)
                schoolRequire(offsets.size == 1)
                return local.toInstant(offsets.single()).toEpochMilli()
            }
            start = at("HeureDebut"); end = at("HeureFin")
            schoolRequire(end > start && end - start <= 86_400_000)
        }
        val teachers = j.names("Enseignants")
        val rooms = j.names("Locaux")
        val kind = if (absence) "absence" else j.requiredText("Type").padStart(2, '0')
        val classKey = j.text("CleClasse")
        val course = j.text("Cours")
        val group = j.text("Groupe")
        if (absence || kind == "01") schoolRequire(classKey.isNotBlank() && course.isNotBlank() && group.isNotBlank())
        val notes = listOf("NoteAbsence", "Commentaire", "Message").mapNotNull { key -> j.text(key).takeIf { it.isNotBlank() } }.distinct()
        return SchoolItem(j.requiredText("CleUnique"), classKey, j.requiredText("Titre"), course, group, j.text("TypeClasse"), kind, day,
            start, end, allDay, if (allDay) "UTC" else zone.id, teachers, rooms, notes,
            absence || j.requiredBoolean("IndAbsence"), j.optInt("OrdreJourneeComplete", 0), j.text("NumeroPeriode"), j.text("TitreTypePeriode"))
    }
}

internal data class SchoolDelta(val item: SchoolItem, val categories: Set<SchoolAlert>)
internal object SchoolDiff {
    fun changes(previous: List<SchoolItem>, next: List<SchoolItem>, today: LocalDate): List<SchoolDelta> {
        val before = previous.associateBy { it.id }; val after = next.associateBy { it.id }
        fun category(item: SchoolItem) = when (item.kind) { "07" -> SchoolAlert.ASSESSMENTS; "08", SchoolNoticeInterpreter.NOTICE_KIND -> SchoolAlert.SCHOOL_DAYS; "absence" -> SchoolAlert.TEACHER_ABSENCES; else -> SchoolAlert.ADDED_CLASSES }
        return buildList {
            next.filter { !it.date.isBefore(today) }.forEach { item ->
                val old = before[item.id]
                val changed = linkedSetOf<SchoolAlert>()
                if (old == null) { changed += category(item); if (item.teacherAbsent) changed += SchoolAlert.TEACHER_ABSENCES }
                else if (old != item) {
                    if (old.teacherAbsent != item.teacherAbsent || ((old.teacherAbsent || item.teacherAbsent) && old.notes != item.notes)) changed += SchoolAlert.TEACHER_ABSENCES
                    if (old.startMillis != item.startMillis || old.endMillis != item.endMillis || old.allDay != item.allDay) changed += SchoolAlert.CLASS_TIMES
                    if (old.rooms != item.rooms) changed += SchoolAlert.ROOMS
                    if (old.teachers != item.teachers) changed += SchoolAlert.TEACHERS
                    if (item.kind == "07") changed += SchoolAlert.ASSESSMENTS
                    if (item.kind == "08" || item.kind == SchoolNoticeInterpreter.NOTICE_KIND) changed += SchoolAlert.SCHOOL_DAYS
                }
                if (changed.isNotEmpty()) add(SchoolDelta(item, changed))
            }
            previous.filter { it.id !in after && !it.date.isBefore(today) }.forEach { item ->
                add(SchoolDelta(item, setOf(when (item.kind) { "07" -> SchoolAlert.ASSESSMENTS; "08", SchoolNoticeInterpreter.NOTICE_KIND -> SchoolAlert.SCHOOL_DAYS; "absence" -> SchoolAlert.TEACHER_ABSENCES; else -> SchoolAlert.REMOVED_CLASSES })))
            }
        }
    }
    fun needsConfirmation(previous: List<SchoolItem>, next: List<SchoolItem>, today: LocalDate): Boolean {
        val old = previous.filter { !it.date.isBefore(today) }; val ids = next.map { it.id }.toSet()
        val removed = old.count { it.id !in ids }
        return old.isNotEmpty() && (next.none { !it.date.isBefore(today) } || (removed >= 5 && removed * 4 >= old.size))
    }
    fun enabled(changes: List<SchoolDelta>, preferences: SchoolNotificationPreferences): List<SchoolDelta> =
        if (!preferences.enabled) emptyList() else changes.filter { (it.categories intersect preferences.categories).isNotEmpty() }
}

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
internal fun JSONObject.text(key: String): String {
    if (!has(key) || isNull(key)) return ""
    val raw = get(key)
    schoolRequire(raw is String || raw is Number)
    return raw.toString().also { schoolRequire(it.length <= 20_000) }
}
internal fun JSONObject.requiredText(key: String): String = text(key).also { schoolRequire(it.isNotBlank()) }
internal fun JSONObject.requiredBoolean(key: String): Boolean { schoolRequire(has(key) && get(key) is Boolean); return getBoolean(key) }
private fun JSONObject.names(key: String): List<String> {
    schoolRequire(has(key) && get(key) is JSONArray)
    val rows = getJSONArray(key); schoolRequire(rows.length() <= 100)
    return (0 until rows.length()).map { rows.getJSONObject(it).requiredText("Nom") }
}
