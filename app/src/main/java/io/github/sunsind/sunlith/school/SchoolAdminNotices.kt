package io.github.sunsind.sunlith.school

import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** One row of "Message(s) de l'administration", plus its detail when the portal exposed a validated detail link. */
data class SchoolAdminNotice(
    val id: String, val date: LocalDate, val subject: String, val sender: String,
    val category: String = "", val body: String? = null, val detailAvailable: Boolean = false,
)

enum class SchoolNoticeStatus { WARNING, CONFIRMED, UPDATED }

/** A dated college-wide notice (class lifting / boycott / closure). Identity is the affected day, so a warning is replaced in place by its confirmation. */
data class SchoolCollegeNotice(
    val identity: String, val targetDate: LocalDate, val status: SchoolNoticeStatus,
    val until: LocalTime?, val decisionAt: LocalTime?, val resume: LocalDate?,
    val sourceId: String, val sourceDate: LocalDate, val sender: String, val subject: String,
    val exceptions: List<String>, val text: String, val superseded: List<String>, val detailRead: Boolean,
)

data class SchoolNoticeSuggestion(val id: String, val date: LocalDate, val title: String, val detail: String)

internal data class SchoolAdminRow(val date: LocalDate, val subject: String, val sender: String, val link: URI?) {
    val id: String get() = schoolHash("admin\n$date\n${SchoolHtml.normalize(subject)}\n${SchoolHtml.normalize(sender)}")
}
internal data class SchoolAdminDetail(val date: LocalDate, val subject: String, val source: String, val category: String, val body: String)

internal object SchoolAdminParser {
    private val DATE = Regex("^(\\d{4})[/-](\\d{2})[/-](\\d{2})$")
    private val LABELS = mapOf("date" to "date", "objet" to "subject", "source du message" to "source", "categorie" to "category", "message" to "message")

    fun date(value: String): LocalDate? = DATE.matchEntire(value.trim())?.let { m ->
        try { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) } catch (_: Exception) { null }
    }

    private fun label(cell: HtmlCell) = SchoolHtml.normalize(cell.text).trimEnd(':', ' ')

    /**
     * Finds the administration list: a table with an exact Date / Objet / De header followed by dated rows.
     * Returns null when no such table exists. Two different candidate lists are ambiguous and fail closed.
     */
    fun list(tables: List<HtmlTable>, page: URI, colnet: URI): List<SchoolAdminRow>? {
        val found = tables.mapNotNull { table ->
            val header = table.rows.indexOfFirst { row -> row.size == 3 && row.map(::label) == listOf("date", "objet", "de") }
            if (header < 0) return@mapNotNull null
            val rows = table.rows.drop(header + 1).mapNotNull { row ->
                if (row.all { it.text.isBlank() }) return@mapNotNull null
                schoolRequire(row.size == 3)
                val day = date(row[0].text) ?: throw SchoolFailure(SchoolError.INVALID_DATA)
                val subject = row[1].text.replace('\n', ' ').trim()
                schoolRequire(subject.isNotEmpty() && subject.length <= 500)
                SchoolAdminRow(day, subject, row[2].text.replace('\n', ' ').trim().take(200), detailLink(row[1].links, page, colnet))
            }
            rows
        }.distinct()
        if (found.isEmpty()) return null
        schoolRequire(found.size == 1 && found.single().size <= 500)
        return found.single()
    }

    /**
     * A detail link is accepted only when it points at the same host, under the Col.NET folder, at affmessdir.asp.
     * A javascript: link qualifies only when it carries exactly one quoted literal naming affmessdir.asp; nothing is executed.
     */
    fun detailLink(links: List<HtmlLink>, page: URI, colnet: URI): URI? {
        val candidates = links.flatMap { link ->
            val raw = listOf(link.href, link.onclick).filter { it.isNotBlank() }
            raw.flatMap { value ->
                if (value.trim().lowercase(Locale.ROOT).startsWith("javascript:") || value == link.onclick) {
                    QUOTED.findAll(value).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { it.contains("affmessdir.asp", ignoreCase = true) }.toList()
                } else listOf(value)
            }
        }.mapNotNull { validate(it, page, colnet) }.distinct()
        return candidates.singleOrNull()
    }

    fun validate(value: String, page: URI, colnet: URI): URI? = try {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 2000 || trimmed.any { it.isISOControl() } || '\\' in trimmed) null
        else {
            val resolved = page.resolve(URI(trimmed.substringBefore('#'))).normalize()
            val path = resolved.path.orEmpty()
            if (resolved.scheme == "https" && resolved.host.equals(colnet.host, true) && resolved.port in listOf(-1, 443) && resolved.rawUserInfo == null &&
                path.lowercase(Locale.ROOT).startsWith(colnet.path.lowercase(Locale.ROOT)) && !path.contains("..") &&
                path.substringAfterLast('/').equals("affmessdir.asp", true)) resolved else null
        }
    } catch (_: Exception) { null }

    /** Same-origin pages under the Col.NET folder only (used for redirects and frames). */
    fun sameFolder(value: String, page: URI, colnet: URI): URI? = try {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 2000 || trimmed.any { it.isISOControl() } || '\\' in trimmed || trimmed.lowercase(Locale.ROOT).startsWith("javascript:")) null
        else page.resolve(URI(trimmed.substringBefore('#'))).normalize().takeIf { r ->
            r.scheme == "https" && r.host.equals(colnet.host, true) && r.port in listOf(-1, 443) && r.rawUserInfo == null &&
                r.path.orEmpty().lowercase(Locale.ROOT).startsWith(colnet.path.lowercase(Locale.ROOT)) && !r.path.orEmpty().contains("..")
        }
    } catch (_: Exception) { null }

    /** A detail page is a label/value table with Date, Objet and Message, each exactly once. */
    fun detail(tables: List<HtmlTable>): SchoolAdminDetail? {
        val found = tables.mapNotNull { table ->
            val pairs = table.rows.filter { it.size >= 2 }.mapNotNull { row -> LABELS[label(row[0])]?.let { it to row[1] } }
            val keys = pairs.map { it.first }
            if (listOf("date", "subject", "message").any { k -> keys.count { it == k } != 1 } || keys.distinct().size != keys.size) return@mapNotNull null
            val map = pairs.toMap()
            val day = date(map.getValue("date").text) ?: return@mapNotNull null
            val subject = map.getValue("subject").text.replace('\n', ' ').trim()
            val body = map.getValue("message").text.trim()
            if (subject.isEmpty() || body.isEmpty()) return@mapNotNull null
            SchoolAdminDetail(day, subject, map["source"]?.text?.trim().orEmpty().take(200), map["category"]?.text?.trim().orEmpty().take(200), body)
        }.distinct()
        return found.singleOrNull()
    }

    fun matches(row: SchoolAdminRow, detail: SchoolAdminDetail) =
        row.date == detail.date && SchoolHtml.normalize(row.subject) == SchoolHtml.normalize(detail.subject)

    private val QUOTED = Regex("'([^'\"]{1,500})'|\"([^'\"]{1,500})\"")
}

internal object SchoolNoticeInterpreter {
    private val MONTHS = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre")
    private val DAY_MONTH = Regex("\\b(\\d{1,2})(?:er)?\\s+(${MONTHS.joinToString("|")})\\b(?:\\s+(\\d{4}))?")
    private val ISO = Regex("\\b(\\d{4})[-/](\\d{2})[-/](\\d{2})\\b")
    private val TRIGGER = Regex("lev[eé]e?s? des cours|cours[^.]{0,400}\\b(?:sont|seront|est|sera) (?:leves|annules|suspendus)|boycott|greve|fermeture du college|college (?:sera |est )?ferme")
    private val CANCEL = Regex("levee des cours|\\b(?:sont|seront) (?:leves|annules|suspendus)\\b|\\b(?:est|sera) ferme\\b|fermeture du college")
    private val CONDITIONAL = Regex("\\b(pourrait|pourraient|pourrions|si une|si les|s'il|eventuel\\w*|possible\\w*|risque|advenant|probable\\w*|rumeur|non confirme\\w*)\\b")
    private val UNTIL = Regex("jusqu'?\\s?a\\s+(\\d{1,2})\\s?h\\s?(\\d{2})?")
    private val TIME = Regex("\\b(\\d{1,2})\\s?h\\s?(\\d{2})?\\b")
    private val RESUME = Regex("reprendr\\w*")
    private val EXCEPTION = Regex("formation continue|apres \\d{1,2}\\s?h|comme prevu|telles? que planifiee?s|pourront avoir lieu|auront lieu")
    private val DEADLINE = Regex("date limite|au plus tard|d'ici le|avant le|jusqu'au|deadline|no later than")
    private val RETRACTION = Regex("(?:pas|aucune|sans) (?:de |une )?(?:levee des cours|annulation des cours|fermeture du college)|(?:annulation|annulee?|retiree?) (?:de la |d'une |la )?levee des cours|cours[^.!?]{0,40}ne (?:sont|seront) pas (?:annules|leves|suspendus)|levee des cours[^.!?]{0,60}(?:annulee|retiree|pas decretee)")
    private val MAINTAINED = Regex("cours (?:sont |seront )?maintenus")
    private val AFTER_HOUR = Regex("apres \\d{1,2}\\s*h")
    private val CLASS_SCOPE = Regex("\\b(cours|college|montmorency|campus|pavillon)\\b")

    internal fun sentences(text: String): List<String> = text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }

    internal fun dates(normalized: String, reference: LocalDate): List<LocalDate> {
        val found = ArrayList<Pair<Int, LocalDate>>()
        DAY_MONTH.findAll(normalized).forEach { m ->
            val day = m.groupValues[1].toInt(); val month = MONTHS.indexOf(m.groupValues[2]) + 1
            val explicit = m.groupValues[3].toIntOrNull()
            val date = if (explicit != null) runCatching { LocalDate.of(explicit, month, day) }.getOrNull()
            else (reference.year - 1..reference.year + 1).mapNotNull { y -> runCatching { LocalDate.of(y, month, day) }.getOrNull() }
                .filter { !it.isBefore(reference.minusDays(60)) }.minByOrNull { it.toEpochDay() }
            if (date != null) found += m.range.first to date
        }
        ISO.findAll(normalized).forEach { m -> runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()?.let { found += m.range.first to it } }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun time(match: MatchResult?): LocalTime? = match?.let {
        val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toIntOrNull() ?: 0
        if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
    }

    private data class Reading(val notice: SchoolAdminNotice, val target: LocalDate, val status: SchoolNoticeStatus, val until: LocalTime?,
        val decisionAt: LocalTime?, val resume: LocalDate?, val exceptions: List<String>, val text: String)

    private fun read(notice: SchoolAdminNotice): Reading? {
        val subject = SchoolHtml.normalize(notice.subject)
        val body = notice.body.orEmpty()
        val bodySentences = sentences(body)
        val all = listOf(notice.subject) + bodySentences
        fun relevant(text: String): Boolean = SchoolHtml.normalize(text).let {
            TRIGGER.containsMatchIn(it) || RETRACTION.containsMatchIn(it) || MAINTAINED.containsMatchIn(it)
        }
        if (all.none(::relevant)) return null
        if (all.none { CLASS_SCOPE.containsMatchIn(SchoolHtml.normalize(it)) }) return null
        val subjectDates = dates(subject, notice.date)
        val target = when {
            subjectDates.size == 1 -> subjectDates.single()
            subjectDates.size > 1 -> return null
            else -> bodySentences.filter(::relevant)
                .flatMap { dates(SchoolHtml.normalize(it), notice.date) }.distinct().singleOrNull() ?: return null
        }
        if (target.isBefore(notice.date.minusDays(14)) || target.isAfter(notice.date.plusDays(60))) return null
        // A correction in the body takes precedence over an unchanged cancellation subject.
        val updated = all.any { sentence ->
            val normalized = SchoolHtml.normalize(sentence)
            (RETRACTION.containsMatchIn(normalized) || MAINTAINED.containsMatchIn(normalized)) &&
                !AFTER_HOUR.containsMatchIn(normalized) && dates(normalized, notice.date).all { it == target }
        }
        val cancelSentences = all.filter { CANCEL.containsMatchIn(SchoolHtml.normalize(it)) }
        val confirmed = !updated && cancelSentences.any { !CONDITIONAL.containsMatchIn(SchoolHtml.normalize(it)) }
        val conditional = cancelSentences.filter { CONDITIONAL.containsMatchIn(SchoolHtml.normalize(it)) }
        val until = if (!confirmed) null else cancelSentences.filter { !CONDITIONAL.containsMatchIn(SchoolHtml.normalize(it)) }
            .firstNotNullOfOrNull { time(UNTIL.find(SchoolHtml.normalize(it))) }
        val decisionAt = if (confirmed) null else conditional.firstNotNullOfOrNull { time(TIME.find(SchoolHtml.normalize(it))) }
        val resume = bodySentences.firstOrNull { RESUME.containsMatchIn(SchoolHtml.normalize(it)) }
            ?.let { dates(SchoolHtml.normalize(it), notice.date).firstOrNull { d -> d.isAfter(target) } }
        val exceptions = if (!confirmed) emptyList() else bodySentences.filter { s ->
            val n = SchoolHtml.normalize(s); EXCEPTION.containsMatchIn(n) && !CANCEL.containsMatchIn(n)
        }.map { it.take(400) }.take(6)
        return Reading(notice, target, when { updated -> SchoolNoticeStatus.UPDATED; confirmed -> SchoolNoticeStatus.CONFIRMED; else -> SchoolNoticeStatus.WARNING }, until, decisionAt, resume, exceptions,
            (if (body.isBlank()) notice.subject else body).take(3_000))
    }

    /** Groups readings by affected day. Newer notices replace older ones, including corrections. */
    fun interpret(notices: List<SchoolAdminNotice>): List<SchoolCollegeNotice> =
        notices.mapNotNull(::read).groupBy { it.target }.map { (target, readings) ->
            val best = readings.sortedWith(compareByDescending<Reading> { it.notice.date }
                .thenByDescending { it.status == SchoolNoticeStatus.UPDATED }.thenByDescending { it.notice.body != null }).first()
            SchoolCollegeNotice("college-day:$target", target, best.status, best.until, best.decisionAt, best.resume,
                best.notice.id, best.notice.date, best.notice.sender.ifBlank { best.notice.category }, best.notice.subject, best.exceptions, best.text,
                readings.filter { it !== best }.map { it.notice.id }, best.notice.body != null)
        }.sortedBy { it.targetDate }

    fun suggestions(notices: List<SchoolAdminNotice>, today: LocalDate, excluded: Set<LocalDate>): List<SchoolNoticeSuggestion> =
        notices.filter { it.body != null }.flatMap { notice ->
            sentences(notice.body.orEmpty()).filter { DEADLINE.containsMatchIn(SchoolHtml.normalize(it)) }.flatMap { sentence ->
                dates(SchoolHtml.normalize(sentence), notice.date).filter { !it.isBefore(today) && it !in excluded }.map { date ->
                    SchoolNoticeSuggestion(schoolHash("suggest\n${notice.id}\n$date\n$sentence").take(24), date, notice.subject, sentence.take(400))
                }
            }
        }.distinctBy { it.id }.sortedBy { it.date }.take(30)

    /** Calendar items for confirmed or warned college days. Classes themselves are never edited; scope is not inferred. */
    fun items(notices: List<SchoolCollegeNotice>, english: Boolean): List<SchoolItem> = notices.map { n ->
        val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)
        fun t(time: LocalTime) = if (english) time.toString() else if (time.minute == 0) "${time.hour} h" else "${time.hour} h ${"%02d".format(time.minute)}"
        val title = when {
            n.status == SchoolNoticeStatus.UPDATED -> if (english) "Class disruption notice updated (check college notice)" else "Avis de perturbation des cours mis à jour (vérifiez l'avis du Collège)"
            n.status == SchoolNoticeStatus.CONFIRMED && n.until != null -> if (english) "Classes lifted until ${t(n.until)} (college notice)" else "Levée des cours jusqu'à ${t(n.until)} (avis du Collège)"
            n.status == SchoolNoticeStatus.CONFIRMED -> if (english) "Classes lifted (college notice)" else "Levée des cours (avis du Collège)"
            n.decisionAt != null -> if (english) "Possible class lifting, not confirmed (decision around ${t(n.decisionAt)})" else "Levée des cours possible, non confirmée (décision vers ${t(n.decisionAt)})"
            else -> if (english) "Possible class disruption, not confirmed" else "Perturbation possible des cours, non confirmée"
        }
        val notes = buildList {
            add(if (n.status == SchoolNoticeStatus.CONFIRMED) {
                if (english) "Confirmed by ${n.sender} in the notice of ${n.sourceDate.format(dateFmt)}." else "Confirmé par ${n.sender} dans l'avis du ${n.sourceDate.format(dateFmt)}."
            } else if (n.status == SchoolNoticeStatus.UPDATED) {
                if (english) "The earlier disruption notice was corrected. Check the full notice for current arrangements." else "L'avis de perturbation précédent a été corrigé. Vérifiez le texte complet pour les modalités actuelles."
            } else {
                if (english) "Warning only. This is not a confirmed cancellation (notice of ${n.sourceDate.format(dateFmt)}, ${n.sender})." else "Avertissement seulement. Ce n'est pas une annulation confirmée (avis du ${n.sourceDate.format(dateFmt)}, ${n.sender})."
            })
            add((if (english) "Subject: " else "Objet : ") + n.subject)
            n.exceptions.forEach { add((if (english) "Exception: " else "Exception : ") + it) }
            n.resume?.let { add(if (english) "Classes resume ${it.format(dateFmt)}." else "Reprise des cours le ${it.format(dateFmt)}.") }
            add(if (english) "Your classes were not changed. Check which sites and programs the notice covers." else "Vos cours n'ont pas été modifiés. Vérifiez quels lieux et programmes l'avis touche.")
            if (!n.detailRead) add(if (english) "Only the notice title could be read. Open Col.NET for the full text." else "Seul le titre de l'avis a pu être lu. Ouvrez Col.NET pour le texte complet.")
            add("")
            add(n.text)
        }
        val start = n.targetDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        SchoolItem("notice:${schoolHash(n.identity)}", "", title, "", "", "", NOTICE_KIND, n.targetDate, start,
            n.targetDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, "UTC", emptyList(), emptyList(), notes, false)
    }

    const val NOTICE_KIND = "notice"
}
