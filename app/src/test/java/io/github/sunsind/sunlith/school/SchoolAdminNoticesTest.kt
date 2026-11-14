package io.github.sunsind.sunlith.school

import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

/**
 * SYNTHETIC HTML. The real authenticated Col.NET markup was not captured; these fixtures only reproduce the visible
 * structure (Date / Objet / De list, label/value detail) and the notice wording transcribed from screenshots of 2026-11-12/13.
 */
class SchoolAdminNoticesTest {
    @Test fun administrationDetailsKeepLongFrenchTextWithoutTranslationOrTruncation() {
        val body = "Été comme hiver, les étudiantes et les étudiants gardent ce texte original. ".repeat(450).trim()
        val html = "<table><tr><td>Date</td><td>2026-11-13</td></tr><tr><td>Objet</td><td>Information du Collège</td></tr>" +
            "<tr><td>Message</td><td>$body</td></tr></table>"
        val detail = requireNotNull(SchoolAdminParser.detail(SchoolHtml.tables(SchoolHtml.tokens(html))))
        assertEquals("Information du Collège", detail.subject)
        assertTrue(body.length > 20_000)
        assertEquals(body, detail.body)
    }
    private val colnet = URI("https://portal.example.invalid/colnet/")
    private val page = colnet.resolve("accueil.asp")

    private val warningBody = """
        Dépôt d'un avis de boycott le 13 novembre, par les étudiantes et les étudiants du Collège Montmorency

        À la suite des résultats d'un référendum tenu auprès de ses membres, nous avons été informés aujourd'hui, par l'Association Générale des étudiantes et étudiants de Montmorency (AGEM), d'un avis de boycott par les étudiantes et les étudiants qui pourrait avoir lieu le vendredi 13 novembre.

        Il est à noter que le Collège pourrait décréter une levée des cours vendredi matin, vers 7 h 15, si une ligne de piquetage est constatée et que les activités de manifestation étudiantes sont significatives aux portes du Collège, et y entravent l'accès.

        Nous ne pouvons donc pas anticiper le déroulement des événements pour vendredi, mais soyez assurés que nous vous informerons rapidement des développements au regard de celui-ci et de ses conséquences sur le calendrier scolaire.

        Merci de votre collaboration.
        La Direction
    """.trimIndent()

    private val confirmedBody = """
        La Direction a constaté une ligne de piquetage ainsi que la tenue d'activités de manifestation étudiantes significatives aux portes du Collège, et celles-ci y entravent l'accès.

        Par conséquent, les cours à la formation régulière se déroulant au Pavillon principal, à Espace Montmorency et au Campus de l'est de Laval (Complexe de sécurité incendie), ainsi que les stages à l'interne, les activités de la clinique-école et toutes autres activités sont levés pour la journée (jusqu'à 18h). Les activités prévues après 18h pourront avoir lieu telles que planifiées.

        Les activités de la Formation continue prévues au campus principal seront replacées au Centre de formation continue (Galeries Laval). Toutes les activités de la Formation continue prévues dans les autres pavillons auront lieu comme prévu.

        Les cours et les activités régulières reprendront le lundi 16 novembre et nous vous informerons rapidement de l'impact sur le calendrier scolaire.

        Merci de votre collaboration.
    """.trimIndent()

    private val warning = SchoolAdminNotice("w", LocalDate.of(2026, 11, 12), "Dépôt d'un avis de boycott le 13 novembre, par les étudiantes et les étudiants", "Direction générale", "Annonce", warningBody, true)
    private val confirmed = SchoolAdminNotice("c", LocalDate.of(2026, 11, 13), "Levée des cours le 13 novembre – Boycott par les étudiantes et les étudiants", "Direction générale", "Annonce", confirmedBody, true)

    private fun listHtml(vararg rows: String) = """
        <html><head><title>Coba.Net</title><script>document.write('<table><tr><td>Date</td><td>Objet</td><td>De</td></tr></table>')</script></head><body>
        <table><tr><th colspan=3>Message(s) de l&#39;administration</th></tr>
        <tr><td>Date</td><td>Objet</td><td>De</td></tr>
        ${rows.joinToString("\n")}
        </table></body></html>
    """.trimIndent()

    @Test fun htmlDropsScriptsKeepsOnlySafeLinksAndDecodesEntities() {
        val rich = SchoolHtml.rich("""<p>Bonjour&nbsp;<b>Sunny</b> &eacute;t&eacute; &#8211; &rsquo;</p><script>alert(1)</script><img src="https://tracker.invalid/p.gif">
            <p><a href="javascript:alert(1)">bad</a> <a href="https://www.example.invalid/x">good</a> <a href="//cdn.invalid/x">rel</a></p>""")
        assertTrue(rich.text.startsWith("Bonjour Sunny été – ’") || rich.text.startsWith("Bonjour Sunny été – ’"))
        assertFalse(rich.text.contains("alert"))
        assertFalse(rich.text.contains("tracker"))
        val links = rich.spans.filter { it.kind == SchoolSpanKind.LINK }
        assertEquals(listOf("https://www.example.invalid/x"), links.map { it.url })
        assertEquals("good", rich.text.substring(links.single().start, links.single().end))
        assertTrue(rich.spans.any { it.kind == SchoolSpanKind.BOLD && rich.text.substring(it.start, it.end) == "Sunny" })
        assertTrue(rich.text.contains("\n\n"))
    }

    @Test fun listIsRecognizedFromItsExactHeaderAndOnlySameFolderDetailLinksAreKept() {
        val html = listHtml(
            """<tr><td>2026/11/13</td><td><a href="affmessdir.asp?NoMess=11">Levée des cours le 13 novembre &ndash; Boycott par les étudiantes et les étudiants</a></td><td>Direction générale</td></tr>""",
            """<tr><td>2026/11/12</td><td><a href="#" onclick="javascript:window.open('affmessdir.asp?NoMess=10','m');return false;">Résultats du scrutin AGEM et appel à la mobilisation</a></td><td>AGEM</td></tr>""",
            """<tr><td>2026/11/12</td><td><a href="https://evil.invalid/colnet/affmessdir.asp?NoMess=9">Dépôt d'un avis de boycott le 13 novembre, par les étudiantes et les étudiants</a></td><td>Direction générale</td></tr>""",
            """<tr><td>2026/10/15</td><td><a href="javascript:ouvrir(42)">Mention au bulletin A2026</a></td><td></td></tr>""",
        )
        val rows = SchoolAdminParser.list(SchoolHtml.tables(SchoolHtml.tokens(html)), page, colnet)!!
        assertEquals(4, rows.size)
        assertEquals(LocalDate.of(2026, 11, 13), rows[0].date)
        assertEquals("Levée des cours le 13 novembre – Boycott par les étudiantes et les étudiants", rows[0].subject)
        assertEquals(URI("https://portal.example.invalid/colnet/affmessdir.asp?NoMess=11"), rows[0].link)
        assertEquals(URI("https://portal.example.invalid/colnet/affmessdir.asp?NoMess=10"), rows[1].link)
        assertNull("other host is refused", rows[2].link)
        assertNull("javascript without a literal URL is never guessed", rows[3].link)
        assertEquals("", rows[3].sender)
    }

    @Test fun malformedNonemptyAdministrationRowsAreNotAcceptedAsAnEmptyList() {
        for (row in listOf("<tr><td>13 novembre 2026</td><td>Avis du Collège</td><td>Direction</td></tr>",
            "<tr><td>2026/11/13</td><td></td><td>Direction</td></tr>", "<tr><td>Avis illisible</td></tr>")) {
            val failure = assertThrows(SchoolFailure::class.java) {
                SchoolAdminParser.list(SchoolHtml.tables(SchoolHtml.tokens(listHtml(row))), page, colnet)
            }
            assertEquals(SchoolError.INVALID_DATA, failure.reason)
        }
        assertTrue(SchoolAdminParser.list(SchoolHtml.tables(SchoolHtml.tokens(listHtml())), page, colnet)!!.isEmpty())
    }

    @Test fun unknownOrAmbiguousLayoutsFailClosed() {
        val other = "<table><tr><td>Date</td><td>Sujet</td><td>Auteur</td></tr><tr><td>2026/11/13</td><td>x</td><td>y</td></tr></table>"
        assertNull(SchoolAdminParser.list(SchoolHtml.tables(SchoolHtml.tokens(other)), page, colnet))
        val two = listHtml("<tr><td>2026/11/13</td><td>A</td><td>B</td></tr>") + listHtml("<tr><td>2026/11/12</td><td>C</td><td>D</td></tr>")
        assertThrows(SchoolFailure::class.java) { SchoolAdminParser.list(SchoolHtml.tables(SchoolHtml.tokens(two)), page, colnet) }
        assertNull(SchoolAdminParser.validate("../login.asp", colnet.resolve("x/affmessdir.asp"), colnet))
        assertNull(SchoolAdminParser.validate("https://user@portal.example.invalid/colnet/affmessdir.asp", page, colnet))
        assertNull(SchoolAdminParser.sameFolder("https://portal.example.invalid/other/page.asp", page, colnet))
    }

    @Test fun detailIsReadFromLabelValueRowsAndMustMatchItsListRow() {
        val html = """<table><tr><th colspan=2>MESSAGE DE L'ADMINISTRATION</th></tr>
            <tr><td>Date</td><td>2026/11/13</td></tr>
            <tr><td>Objet</td><td>Levée des cours le 13 novembre – Boycott par les étudiantes et les étudiants</td></tr>
            <tr><td>Source du<br>message</td><td>Direction générale</td></tr>
            <tr><td>Catégorie</td><td>Annonce</td></tr>
            <tr><td>Message</td><td>${confirmedBody.replace("\n\n", "<br><br>")}</td></tr></table><input type=button value=Fermer>"""
        val detail = SchoolAdminParser.detail(SchoolHtml.tables(SchoolHtml.tokens(html)))!!
        assertEquals("Direction générale", detail.source)
        assertEquals("Annonce", detail.category)
        assertTrue(detail.body.contains("Galeries Laval"))
        assertTrue(detail.body.contains("\n\n"))
        val row = SchoolAdminRow(LocalDate.of(2026, 11, 13), "Levée des cours le 13 novembre – Boycott par les étudiantes et les étudiants", "Direction générale", null)
        assertTrue(SchoolAdminParser.matches(row, detail))
        assertFalse(SchoolAdminParser.matches(row.copy(date = LocalDate.of(2026, 11, 12)), detail))
        val missingMessage = html.replace("<td>Message</td>", "<td>Texte</td>")
        assertNull(SchoolAdminParser.detail(SchoolHtml.tables(SchoolHtml.tokens(missingMessage))))
    }

    @Test fun conditionalBoycottNoticeIsOnlyAWarningForTheAffectedDay() {
        val result = SchoolNoticeInterpreter.interpret(listOf(warning)).single()
        assertEquals(LocalDate.of(2026, 11, 13), result.targetDate)
        assertEquals(SchoolNoticeStatus.WARNING, result.status)
        assertEquals(LocalTime.of(7, 15), result.decisionAt)
        assertNull(result.until)
        val item = SchoolNoticeInterpreter.items(listOf(result), english = true).single()
        assertTrue(item.title.contains("not confirmed"))
        assertTrue(item.allDay)
        assertTrue(item.notes.first().startsWith("Warning only"))
    }

    @Test fun confirmationReplacesTheWarningInPlaceWithExactTimesAndExceptions() {
        val warned = SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(warning)), english = false).single()
        val notices = SchoolNoticeInterpreter.interpret(listOf(confirmed, warning))
        val n = notices.single()
        assertEquals(SchoolNoticeStatus.CONFIRMED, n.status)
        assertEquals(LocalTime.of(18, 0), n.until)
        assertEquals(LocalDate.of(2026, 11, 16), n.resume)
        assertEquals(listOf("w"), n.superseded)
        assertEquals("c", n.sourceId)
        assertTrue(n.exceptions.any { it.contains("après 18h") })
        assertTrue(n.exceptions.any { it.contains("Galeries Laval") })
        assertTrue(n.exceptions.any { it.contains("autres pavillons") })
        assertTrue(n.exceptions.none { it.contains("sont levés") })
        val item = SchoolNoticeInterpreter.items(notices, english = false).single()
        assertEquals("same calendar identity as the warning", warned.id, item.id)
        assertEquals("Levée des cours jusqu'à 18 h (avis du Collège)", item.title)
        assertEquals(SchoolNoticeInterpreter.NOTICE_KIND, item.kind)
        assertTrue(item.notes.any { it.contains("Vos cours n'ont pas été modifiés") })
        assertTrue(item.notes.any { it == "Reprise des cours le 2026-11-16." })
    }

    @Test fun titleOnlyRowsStillClassifyButSayTheTextWasNotRead() {
        val n = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = null, detailAvailable = false))).single()
        assertEquals(SchoolNoticeStatus.CONFIRMED, n.status)
        assertNull(n.until)
        assertFalse(n.detailRead)
        assertTrue(SchoolNoticeInterpreter.items(listOf(n), true).single().notes.any { it.contains("Only the notice title") })
        val conditionalTitle = confirmed.copy(subject = "Possible levée des cours le 13 novembre", body = null)
        assertEquals(SchoolNoticeStatus.WARNING, SchoolNoticeInterpreter.interpret(listOf(conditionalTitle)).single().status)
    }

    @Test fun unrelatedNoticesProduceNothingAndDeadlinesBecomeSuggestionsOnly() {
        val unrelated = listOf(
            SchoolAdminNotice("a", LocalDate.of(2026, 11, 12), "Résultats du scrutin AGEM et appel à la mobilisation", "AGEM"),
            SchoolAdminNotice("b", LocalDate.of(2026, 11, 6), "Formulaire d'avis de départ - HIVER 2027", "Service de l'organisation et du cheminement scolaires",
                body = "Remplissez le formulaire. La date limite est le 20 novembre 2026. Merci."),
        )
        assertTrue(SchoolNoticeInterpreter.interpret(unrelated).isEmpty())
        val suggestions = SchoolNoticeInterpreter.suggestions(unrelated, LocalDate.of(2026, 11, 13), emptySet())
        assertEquals(listOf(LocalDate.of(2026, 11, 20)), suggestions.map { it.date })
        assertTrue(suggestions.single().detail.contains("date limite"))
        assertTrue(SchoolNoticeInterpreter.suggestions(unrelated, LocalDate.of(2026, 11, 21), emptySet()).isEmpty())
    }

    @Test fun yearIsInferredNearTheNoticeDate() {
        assertEquals(listOf(LocalDate.of(2027, 1, 5)), SchoolNoticeInterpreter.dates("reprise le 5 janvier", LocalDate.of(2026, 12, 18)))
        assertEquals(listOf(LocalDate.of(2026, 12, 1)), SchoolNoticeInterpreter.dates("le 1er decembre", LocalDate.of(2026, 11, 13)))
    }

    @Test fun negatedOrWithdrawnCancellationsAreCorrectionsEvenWithAnUnchangedSubject() {
        val bodies = listOf(
            "Il n'y aura pas de levée des cours le 13 novembre.",
            "Annulation de la levée des cours le 13 novembre.",
            "La levée des cours annoncée est annulée. Les cours sont maintenus le 13 novembre.",
            "Les cours ne seront pas annulés le 13 novembre.",
        )
        bodies.forEach { body ->
            val result = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = body))).single()
            assertEquals(body, SchoolNoticeStatus.UPDATED, result.status)
            assertNull(result.until)
            assertFalse(SchoolNoticeInterpreter.items(listOf(result), true).single().title.contains("Classes lifted"))
        }
    }

    @Test fun newerCorrectionReplacesAnOlderConfirmationWithTheSameCalendarIdentity() {
        val correction = confirmed.copy(id = "correction", date = confirmed.date.plusDays(1),
            body = "La levée des cours du 13 novembre est annulée. Les cours sont maintenus.")
        val before = SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(confirmed)), true).single()
        val after = SchoolNoticeInterpreter.interpret(listOf(confirmed, correction)).single()
        assertEquals("correction", after.sourceId)
        assertEquals(SchoolNoticeStatus.UPDATED, after.status)
        assertEquals(before.id, SchoolNoticeInterpreter.items(listOf(after), true).single().id)
    }

    @Test fun maintainedClassesNeedNoCancellationKeywordsAndEveningOrNextDayResumptionIsNotARetraction() {
        val correction = confirmed.copy(subject = "Cours maintenus le 13 novembre", body = "Les cours ne sont pas annulés le 13 novembre.")
        assertEquals(SchoolNoticeStatus.UPDATED, SchoolNoticeInterpreter.interpret(listOf(correction)).single().status)
        listOf("Les cours sont levés le 13 novembre. Les cours seront repris le 14 novembre.",
            "Les cours sont levés le 13 novembre jusqu'à 18 h. Les cours sont maintenus après 18 h.").forEach { body ->
            assertEquals(body, SchoolNoticeStatus.CONFIRMED, SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = body))).single().status)
        }
    }

    @Test fun emptyRecognizedListAndUnrelatedDatedBoycottAreNotUnreadableOrClassClosures() {
        assertEquals(emptyList<SchoolAdminRow>(), SchoolAdminParser.list(SchoolHtml.tables(SchoolHtml.tokens(listHtml())), page, colnet))
        val unrelated = confirmed.copy(subject = "Boycott commercial le 13 novembre", body = "Le commerce sera fermé.")
        assertTrue(SchoolNoticeInterpreter.interpret(listOf(unrelated)).isEmpty())
    }

    @Test fun noticeUpdatesAreReportedAsSchoolDayChanges() {
        val warned = SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(warning)), true).single()
        val done = SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(confirmed)), true).single()
        val changes = SchoolDiff.changes(listOf(warned), listOf(done), LocalDate.of(2026, 11, 13))
        assertEquals(setOf(SchoolAlert.SCHOOL_DAYS), changes.single().categories)
    }
}
