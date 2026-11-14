package io.github.sunsind.sunlith.school

import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/**
 * SYNTHETIC HTML, except the fixtures marked ANONYMIZED LIVE SHAPES: those copy the tags and attributes of the real
 * Montmorency Col.NET pages read on 2026-11-14 with every value, token and personal text replaced. The notice wording
 * is the public college notice of 2026-11-12/13. No private HTML, credential or session value is stored here.
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
        assertTrue(n.classesMarked)
        assertTrue(item.notes.any { it.contains("Cet avis concerne les cours qui commencent avant 18 h") })
        assertTrue(item.notes.any { it == "Reprise des cours le 2026-11-16." })
        assertEquals(listOf(SchoolClassLift(LocalDate.of(2026, 11, 13), LocalTime.of(18, 0), LocalDate.of(2026, 11, 13))), SchoolNoticeInterpreter.lifts(notices))
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

    // ANONYMIZED LIVE SHAPES, Montmorency Col.NET, read only on 2026-11-14. Values, tokens and text were replaced; tags,
    // attributes and the order of fields are kept. Phone layout = what an Android user agent (Dalvik) receives.
    private val phoneLogin = """<form method=post  name="frmLogin" action=/colnet/login.asp>
        <input type=text name='txtLibWebBidon' style='visibility:hidden;display:none;'>
        <input type=text name='txtLibWebBidon' style='visibility:hidden;display:none;'>
        <input id=txtCodeUsager type=text name=txtCodeUsager size=17 maxlength=15 value="" >
        <input id=txtMotDePasse type=password name=txtMotDePasse size=32 maxlength=50 value="" >
        <a href="javascript:DoPostBack('TOKEN','?TOKEN')">Connexion</a>
        <input type=hidden name=_PostBackInfo value="">
        <input type=hidden name=_PostBackPar value="">
        <input type=hidden name=_NoSeqWeb value="seq&amp;1">
        <input type=hidden name=_InfoSupp value="">
        <input type=hidden id=txtUsagerAut name=txtUsagerAut value="">
        <input type=hidden id=txtPassAut name=txtPassAut value="">
        </form>"""
    private val desktopLogin = phoneLogin.replace("size=17", "size=32").replace("""<a href="javascript:DoPostBack('TOKEN','?TOKEN')">Connexion</a>""",
        """<input type=image src='Images/Bouton_LoginFRA.png' id='btnConnecter' name=btnConnecter value="" onMouseOver="this.src='x.png'">""")
    private val liveColnet = URI("https://portal.example.invalid/colnet/")

    private fun fields(body: String) = body.split('&').associate { pair ->
        val parts = pair.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }

    @Test fun phoneLoginLayoutWithoutAnImageButtonStillPostsTheVerifiedSubmitFields() {
        for (html in listOf(phoneLogin, desktopLogin)) {
            val (action, body) = SchoolClassicPortal.loginRequest(SchoolHtmlPage(liveColnet.resolve("login.asp"), html), liveColnet, "student+1", "p&ss")
            assertEquals(URI("https://portal.example.invalid/colnet/login.asp"), action)
            val posted = fields(body)
            assertEquals("student+1", posted[SchoolClassicPortal.USER])
            assertEquals("p&ss", posted[SchoolClassicPortal.PASSWORD])
            assertEquals("12", posted["btnConnecter.x"])
            assertEquals("8", posted["btnConnecter.y"])
            assertEquals("seq&1", posted["_NoSeqWeb"])
            assertFalse("hidden decoy text fields are never sent", posted.containsKey("txtLibWebBidon"))
        }
    }

    @Test fun unknownLoginLayoutsStillFailClosed() {
        val noPostBack = phoneLogin.replace("<input type=hidden name=_PostBackInfo value=\"\">", "")
        val otherSubmit = phoneLogin.replace("</form>", "<input type=submit name=Envoyer value=OK></form>")
        val otherButton = phoneLogin.replace("</form>", "<button>OK</button></form>")
        val twoForms = phoneLogin + phoneLogin
        val crossOrigin = phoneLogin.replace("action=/colnet/login.asp", "action=https://other.invalid/colnet/login.asp")
        for (html in listOf(noPostBack, otherSubmit, otherButton, twoForms, crossOrigin, "<p>Maintenance</p>")) {
            val failure = assertThrows(SchoolFailure::class.java) {
                SchoolClassicPortal.loginRequest(SchoolHtmlPage(liveColnet.resolve("login.asp"), html), liveColnet, "student", "password")
            }
            assertEquals(SchoolError.INVALID_DATA, failure.reason)
        }
    }

    @Test fun liveListAndDetailShapesAreRecognized() {
        val onclick = "fen = window.open('affmessdir.asp?TOKEN1','MyWindowlnk','toolbar=no,location=no,scrollbars=yes,width=550,height=600'); if (window.focus) fen.focus(); return false;"
        val landing = """<table class=BluePanelSimple cellspacing=0 cellpadding=4 width=100% border=0>
            <tr><td class=Tab width=100%><b>Message(s) de l'administration</b></td></tr>
            <tr class=Content><td>
            <table class='BlueTableau' cellpadding='0' cellspacing='0' width='100%'><tr class='Header'><td  align=left >Date</td><td  align=left >Objet</td><td  align=left >De</td></tr><tr><td nowrap class='Even'  align=left >2026/11/13</td><td class='Even'  align=left ><a href=#  class=Lien onClick="$onclick">Levée des cours le 13 novembre &#8211; Boycott par les étudiantes et les étudiants</a></td><td class='Even'  align=left >Direction générale</td></tr><tr><td nowrap class='Odd'  align=left >2026/11/04</td><td class='Odd'  align=left ><a href=#  class=Lien onClick="${onclick.replace("TOKEN1", "TOKEN2")}">Rappel important</a></td><td class='Odd'  align=left >Service</td></tr></table>
            </td></tr></table>"""
        val page = liveColnet.resolve("accueil.asp?TOKEN")
        val rows = SchoolAdminParser.list(SchoolHtml.tables(SchoolHtml.tokens(landing)), page, liveColnet)!!
        assertEquals(2, rows.size)
        assertEquals(URI("https://portal.example.invalid/colnet/affmessdir.asp?TOKEN1"), rows[0].link)
        assertEquals(URI("https://portal.example.invalid/colnet/affmessdir.asp?TOKEN2"), rows[1].link)
        val detail = """<table class='Content' border='0' cellpadding='0' cellspacing='0'><tr><td nowrap class='BorderLeft'></td><td class='Content'><div class='Panel'>
            <table border=0 cellspacing=6><tr><td><b>Date</b></td><td>2026/11/13</td></tr><tr><td><b>Objet</b></td><td>Levée des cours le 13 novembre – Boycott par les étudiantes et les étudiants</td></tr>
            <tr><td><b>Source du message</b></td><td>Direction générale</td></tr><tr><td><b>Catégorie</b></td><td>Annonce</td></tr>
            <tr><td valign=top><b>Message</b></td><td>${confirmedBody.replace("\n\n", "<br><br>")}</td></tr></table></div></td></tr></table>"""
        val read = SchoolAdminParser.detail(SchoolHtml.tables(SchoolHtml.tokens(detail)))!!
        assertTrue(SchoolAdminParser.matches(rows[0], read))
        assertEquals("Annonce", read.category)
        val notice = SchoolNoticeInterpreter.interpret(listOf(SchoolAdminNotice(rows[0].id, rows[0].date, rows[0].subject, rows[0].sender, read.category, read.body, true))).single()
        assertEquals(SchoolNoticeStatus.CONFIRMED, notice.status)
        assertTrue(notice.classesMarked)
    }

    private val zone = ZoneId.of("America/Toronto")
    private fun lesson(id: String, day: LocalDate, hour: Int, kind: String = "01", allDay: Boolean = false, lift: String = ""): SchoolItem {
        val start = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return SchoolItem(id, "class-$id", "Cours $id", "243SYN", "0001", "1", kind, day, start, start + 3 * 3_600_000, allDay, zone.id,
            emptyList(), emptyList(), emptyList(), false, collegeLift = lift)
    }

    @Test fun confirmedLiftingMarksOnlyClassesBeforeItsEndTimeAndKeepsEveryIdAndTime() {
        val day = LocalDate.of(2026, 11, 13)
        val morning = lesson("a", day, 8); val evening = lesson("b", day, 18); val exam = lesson("c", day, 10, kind = "07")
        val other = lesson("d", day.plusDays(3), 8); val allDay = lesson("e", day, 0, allDay = true)
        val lifts = SchoolNoticeInterpreter.lifts(SchoolNoticeInterpreter.interpret(listOf(warning, confirmed)))
        val marked = SchoolNoticeInterpreter.applyLifts(listOf(morning, evening, exam, other, allDay), lifts, emptyList(), day.minusDays(14))
        assertEquals("2026-11-13@18:00", marked[0].collegeLift)
        assertEquals(listOf("", "", "", ""), marked.drop(1).map { it.collegeLift })
        assertEquals(listOf(morning, evening, exam, other, allDay).map { listOf(it.id, it.startMillis, it.endMillis, it.title) },
            marked.map { listOf(it.id, it.startMillis, it.endMillis, it.title) })
        assertEquals(LocalDate.of(2026, 11, 13) to LocalTime.of(18, 0), SchoolClassLift.parse(marked[0].collegeLift))
    }

    @Test fun strikeWarningThenConfirmationThenWithdrawalKeepsOneIdentityAndUnmarksClasses() {
        val day = LocalDate.of(2026, 11, 13)
        val classes = listOf(lesson("a", day, 8))
        val warned = SchoolNoticeInterpreter.interpret(listOf(warning))
        assertTrue(SchoolNoticeInterpreter.lifts(warned).isEmpty())
        val confirmedNotices = SchoolNoticeInterpreter.interpret(listOf(warning, confirmed))
        val marked = SchoolNoticeInterpreter.applyLifts(classes, SchoolNoticeInterpreter.lifts(confirmedNotices), emptyList(), day.minusDays(14))
        assertTrue(marked.single().collegeLift.isNotEmpty())
        val withdrawal = SchoolAdminNotice("x", day, "Annulation de la levée des cours le 13 novembre", "Direction générale", "Annonce",
            "La levée des cours du 13 novembre est annulée. Les cours sont maintenus.", true)
        val withdrawn = SchoolNoticeInterpreter.interpret(listOf(warning, confirmed, withdrawal)).single()
        assertEquals(SchoolNoticeStatus.UPDATED, withdrawn.status)
        assertFalse(withdrawn.classesMarked)
        assertEquals(SchoolNoticeInterpreter.items(warned, false).single().id, SchoolNoticeInterpreter.items(listOf(withdrawn), false).single().id)
        val restored = SchoolNoticeInterpreter.applyLifts(marked, SchoolNoticeInterpreter.lifts(listOf(withdrawn)), marked, day.minusDays(14))
        assertEquals(classes, restored)
        val changes = SchoolDiff.changes(marked, restored, day)
        assertEquals(setOf(SchoolAlert.SCHOOL_DAYS), changes.single().categories)
    }

    @Test fun unknownNoticeStateAndOldHistoryKeepTheMarksTheyHad() {
        val day = LocalDate.of(2026, 11, 13)
        val marked = lesson("a", day, 8, lift = "2026-11-13@18:00")
        val fresh = lesson("a", day, 8)
        assertEquals(listOf(marked), SchoolNoticeInterpreter.applyLifts(listOf(fresh), null, listOf(marked), day.minusDays(14)))
        assertEquals("days before the window are never rewritten", listOf(marked),
            SchoolNoticeInterpreter.applyLifts(listOf(fresh), emptyList(), listOf(marked), day.plusDays(1)))
        assertEquals(listOf(fresh), SchoolNoticeInterpreter.applyLifts(listOf(fresh), emptyList(), listOf(marked), day.minusDays(14)))
        val moved = lesson("a", day.plusDays(1), 8)
        assertEquals("a moved class does not inherit an old mark", listOf(moved), SchoolNoticeInterpreter.applyLifts(listOf(moved), null, listOf(marked), day.minusDays(14)))
    }

    @Test fun titleOnlyWarningOrNarrowLiftingsNeverMarkClasses() {
        val titleOnly = confirmed.copy(body = null, detailAvailable = false)
        val continuing = confirmed.copy(subject = "Avis du 13 novembre", body = "Les cours de la formation continue sont levés le 13 novembre.")
        val program = confirmed.copy(subject = "Avis du 13 novembre", body = "Les cours du programme 243 sont annulés le 13 novembre seulement.")
        listOf(titleOnly, continuing, program, warning).forEach { notice ->
            val read = SchoolNoticeInterpreter.interpret(listOf(notice))
            assertTrue(notice.subject, read.none { it.classesMarked })
            assertTrue(SchoolNoticeInterpreter.lifts(read).isEmpty())
            assertTrue(SchoolNoticeInterpreter.items(read, false).single().notes.any { it.contains("Vos cours n'ont pas été modifiés") })
        }
    }

    @Test fun datelessCorrectionsCannotRewriteAnotherScopeOrDay() {
        val partial = confirmed.copy(body = confirmedBody + "\n\nLes cours sont maintenus pour la formation continue.")
        assertEquals("a dateless partial scope sentence is not a correction", SchoolNoticeStatus.CONFIRMED,
            SchoolNoticeInterpreter.interpret(listOf(partial)).single().status)
        val otherDay = confirmed.copy(body = confirmedBody + "\n\nLa levée des cours du 20 novembre est annulée.")
        assertEquals(SchoolNoticeStatus.CONFIRMED, SchoolNoticeInterpreter.interpret(listOf(otherDay)).single { it.targetDate == LocalDate.of(2026, 11, 13) }.status)
        val undated = SchoolAdminNotice("u", LocalDate.of(2026, 11, 14), "Information", "Direction générale", body = "Les cours sont maintenus.")
        assertTrue("no affected day means no notice at all", SchoolNoticeInterpreter.interpret(listOf(undated)).isEmpty())
        val corrected = SchoolNoticeInterpreter.interpret(listOf(confirmed, undated.copy(subject = "Mise à jour du 13 novembre"))).single()
        assertEquals(SchoolNoticeStatus.UPDATED, corrected.status)
        assertFalse(corrected.classesMarked)
    }

    @Test fun partialCorrectionsKeepTheNoticeWithoutMarkingAnUnknownScope() {
        listOf(
            "Les cours sont maintenus pour la formation continue." to true,
            "Les cours sont maintenus pour la formation continue le 13 novembre." to true,
            "La levée des cours du 13 novembre est annulée pour le programme 243 seulement." to false,
            "Les cours du soir sont maintenus le 13 novembre." to false,
            "Les cours du groupe 101 sont maintenus le 13 novembre." to false,
            "Les cours du département 243 sont maintenus le 13 novembre." to false,
            "Les cours de la formation continue et du programme 243 sont maintenus." to false,
            "Les cours de la formation continue et du programme 243 auront lieu comme prévu." to false,
        ).forEach { (exception, canMark) ->
            val result = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = "$confirmedBody\n\n$exception"))).single()
            assertEquals(exception, SchoolNoticeStatus.CONFIRMED, result.status)
            assertEquals(exception, canMark, result.classesMarked)
            assertEquals(exception, LocalTime.of(18, 0), result.until)
        }
    }

    @Test fun generalMaintainedCorrectionsWithExceptionsWithdrawTheBlanketLifting() {
        val day = LocalDate.of(2026, 11, 13)
        val marked = lesson("a", day, 8, lift = "2026-11-13@18:00")
        listOf(
            "Les cours sont maintenus le 13 novembre, sauf les cours du soir.",
            "Les cours sont maintenus, sauf les cours du soir.",
            "Les cours sont maintenus, à l'exception des cours de la formation continue.",
            "Les cours sont finalement maintenus le 13 novembre.",
            "Les cours seront finalement maintenus, sauf ceux du programme 243.",
            "La levée des cours du 13 novembre est annulée, sauf pour la formation continue.",
        ).forEach { body ->
            val result = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = body))).single()
            assertEquals(body, SchoolNoticeStatus.UPDATED, result.status)
            assertFalse(body, result.classesMarked)
            assertNull(body, result.until)
            assertEquals(body, "", SchoolNoticeInterpreter.applyLifts(listOf(marked),
                SchoolNoticeInterpreter.lifts(listOf(result)), listOf(marked), day.minusDays(14)).single().collegeLift)
        }
        val conditional = confirmed.copy(body = "$confirmedBody\n\nSi les portes sont ouvertes, les cours sont maintenus.")
        // Unknown conditional phrasing cannot withdraw a confirmed blanket cancellation.
        assertEquals(SchoolNoticeStatus.CONFIRMED, SchoolNoticeInterpreter.interpret(listOf(conditional)).single().status)
    }

    @Test fun negatedMaintenanceNeverWithdrawsAConfirmedCancellation() {
        listOf("Les cours ne seront pas maintenus le 13 novembre.", "Les cours ne sont pas maintenus.",
            "Les cours ne seront plus maintenus le 13 novembre.", "Les cours sont non maintenus le 13 novembre.",
            "Les cours ne peuvent pas être maintenus le 13 novembre.", "Les cours ne seront jamais maintenus.",
            "Les cours n'ont pas été maintenus le 13 novembre.").forEach { sentence ->
            val read = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = "$confirmedBody\n\n$sentence"))).single()
            assertEquals(sentence, SchoolNoticeStatus.CONFIRMED, read.status)
            assertTrue(sentence, read.classesMarked)
            assertEquals(sentence, LocalTime.of(18, 0), SchoolNoticeInterpreter.lifts(listOf(read)).single().until)
        }
    }

    @Test fun anUnrelatedNegationNeverSuppressesAnAffirmativeMaintenanceCorrection() {
        val day = LocalDate.of(2026, 11, 13)
        val marked = lesson("a", day, 8, lift = "2026-11-13@18:00")
        listOf(
            "MISE À JOUR : Le Collège ne sera pas fermé le 13 novembre, les cours sont maintenus.",
            "La grève n'aura pas lieu, les cours sont maintenus le 13 novembre.",
            "Contrairement à ce qui a été annoncé, la levée n'aura pas lieu : les cours sont maintenus le 13 novembre.",
            "Il n'y a plus de boycott le 13 novembre; les cours sont maintenus.",
        ).forEach { sentence ->
            listOf(sentence, "$sentence\n\n$confirmedBody").forEach { body ->
                val read = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = body))).single()
                assertEquals(body, SchoolNoticeStatus.UPDATED, read.status)
                assertFalse(body, read.classesMarked)
                assertNull(body, read.until)
                assertTrue(body, SchoolNoticeInterpreter.lifts(listOf(read)).isEmpty())
                assertEquals(body, "", SchoolNoticeInterpreter.applyLifts(listOf(marked),
                    SchoolNoticeInterpreter.lifts(listOf(read)), listOf(marked), day.minusDays(14)).single().collegeLift)
            }
            val correction = confirmed.copy(id = "correction", subject = "Mise à jour du 13 novembre", body = sentence)
            listOf(listOf(confirmed, correction), listOf(correction, confirmed)).forEach { notices ->
                val chosen = SchoolNoticeInterpreter.interpret(notices).single()
                assertEquals(sentence, "correction", chosen.sourceId)
                assertEquals(sentence, SchoolNoticeStatus.UPDATED, chosen.status)
                assertFalse(sentence, chosen.classesMarked)
            }
        }
    }

    @Test fun continuingEducationJoinedToASiteNeverCancelsEveryImportedClass() {
        val day = LocalDate.of(2026, 11, 13)
        val original = lesson("unrelated-site", day, 9)
        listOf(
            "Les cours de la formation continue et ceux du pavillon B auront lieu comme prévu.",
            "Les activités de la Formation continue ainsi que les cours au Campus de l'est de Laval auront lieu comme prévu.",
            "Les cours de la formation continue de même que ceux du bâtiment B pourront avoir lieu.",
        ).forEach { exception ->
            val read = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = "$confirmedBody\n\n$exception"))).single()
            assertEquals(exception, SchoolNoticeStatus.CONFIRMED, read.status)
            assertFalse(exception, read.classesMarked)
            assertTrue(exception, SchoolNoticeInterpreter.lifts(listOf(read)).isEmpty())
            assertEquals(exception, listOf(original), SchoolNoticeInterpreter.applyLifts(listOf(original),
                SchoolNoticeInterpreter.lifts(listOf(read)), emptyList(), day.minusDays(14)))
        }
    }

    @Test fun unsupportedPartialDayTimingNeverBecomesAWholeDayLifting() {
        listOf(
            "Les cours sont levés le 13 novembre après 13h.",
            "Les cours sont levés le 13 novembre à partir de 13h.",
            "Les cours sont levés le 13 novembre en matinée.",
            "Les cours sont levés le 13 novembre dans l'après-midi.",
            "Les cours sont levés le 13 novembre de 8h à 13h.",
            "Les cours sont levés le 13 novembre. Les cours reprendront à 13h.",
            "Les cours sont levés le 13 novembre. Les cours reprennent à 13h.",
            "Les cours sont levés le 13 novembre jusqu'à 25h.",
            "Les cours sont levés le 13 novembre jusqu'à 180h.",
            "Les cours sont levés le 13 novembre jusqu'à180h.",
            "Les cours sont levés le 13 novembre jusqu'à 18h000.",
            "Les cours sont levés le 13 novembre jusqu'à 13h et jusqu'à 18h.",
            "Les cours sont levés le 13 novembre jusqu'à 13h. Les cours sont levés jusqu'à 18h.",
            "Les cours sont levés le 13 novembre jusqu'à 18h. Les cours reprendront à 13h.",
        ).forEach { body ->
            val result = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = body))).single()
            assertFalse(body, result.classesMarked)
            assertTrue(body, SchoolNoticeInterpreter.lifts(listOf(result)).isEmpty())
        }
        val contradictoryTitle = confirmed.copy(subject = "Levée des cours le 13 novembre jusqu'à 18h",
            body = "Les cours sont levés le 13 novembre jusqu'à 13h.")
        assertFalse(SchoolNoticeInterpreter.interpret(listOf(contradictoryTitle)).single().classesMarked)
        val supported = confirmed.copy(body = "Les cours sont levés le 13 novembre jusqu'à 13h. Les cours reprendront à 13h.")
        val bounded = SchoolNoticeInterpreter.interpret(listOf(supported)).single()
        assertTrue(bounded.classesMarked)
        assertEquals(LocalTime.of(13, 0), bounded.until)
    }

    @Test fun aBroadSubjectNeverMakesANarrowConditionalOrDifferentDayBodyCancelAllClasses() {
        listOf(
            "Les cours de la formation continue sont levés le 13 novembre.",
            "Les cours du programme 243 sont annulés le 13 novembre seulement.",
            "Le Collège pourrait décréter une levée des cours le 13 novembre si une ligne de piquetage est constatée.",
            "Les cours sont levés le 20 novembre.",
            "Consultez le portail pour connaître les modalités.",
        ).forEach { body ->
            val result = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body = body))).single()
            assertFalse(body, result.classesMarked)
            assertTrue(body, SchoolNoticeInterpreter.lifts(listOf(result)).isEmpty())
        }
        val broad = confirmed.copy(body = "Les cours sont levés le 13 novembre jusqu'à 18 h.")
        assertTrue(SchoolNoticeInterpreter.interpret(listOf(broad)).single().classesMarked)
    }

    @Test fun buildingAndDeliveryRestrictionsNeverCancelEveryImportedClass() {
        val cases = listOf(
            "Fermeture du pavillon B le 13 novembre" to "Le pavillon B est fermé le 13 novembre. Les cours qui s'y donnent sont annulés.",
            "Fermeture du bâtiment B le 13 novembre" to "Le bâtiment B est fermé le 13 novembre. Les cours sont annulés.",
            "Fermeture du campus de Laval le 13 novembre" to "Le campus de Laval est fermé le 13 novembre. Les cours sont annulés.",
            "Fermeture du Collège le 13 novembre" to "Le Collège est fermé le 13 novembre. Les cours en ligne sont maintenus.",
            "Levée des cours le 13 novembre" to "Les cours en présentiel sont annulés le 13 novembre.",
            "Levée des cours le 13 novembre" to "Les cours sont levés le 13 novembre. Les cours à distance seront maintenus.",
            "Levée des cours le 13 novembre" to "Les cours hybrides sont annulés le 13 novembre.",
            "Fermeture du Collège le 13 novembre" to "Le Collège est fermé le 13 novembre. Les cours en ligne ne sont pas annulés.",
            "Fermeture du Collège le 13 novembre" to "Le Collège est fermé le 13 novembre. Les cours à distance auront lieu comme prévu.",
            "Levée des cours le 13 novembre" to "Les cours sont annulés au Pavillon principal, à Espace Montmorency et au Campus de l'est de Laval, mais les cours du campus sont maintenus.",
            "Levée des cours le 13 novembre" to "Les cours au Pavillon principal sont annulés le 13 novembre, tandis que ceux à Espace Montmorency et au Campus de l'est de Laval auront lieu comme prévu.",
        )
        val day = LocalDate.of(2026, 11, 13)
        cases.forEach { (subject, body) ->
            val read = SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(subject = subject, body = body)))
            assertEquals(body, 1, read.size)
            assertFalse(body, read.single().classesMarked)
            assertTrue(body, SchoolNoticeInterpreter.lifts(read).isEmpty())
            val original = lesson("unrelated-building-or-online", day, 9)
            assertEquals(body, listOf(original), SchoolNoticeInterpreter.applyLifts(listOf(original),
                SchoolNoticeInterpreter.lifts(read), emptyList(), day.minusDays(14)))
        }
        // The actual college notice explicitly covers every regular teaching site and still qualifies.
        assertTrue(SchoolNoticeInterpreter.interpret(listOf(confirmed)).single().classesMarked)
        assertTrue(SchoolNoticeInterpreter.interpret(listOf(confirmed.copy(body =
            "Le Collège est fermé le 13 novembre. Les cours sont annulés."))).single().classesMarked)
    }

    @Test fun anUnreadReplacementKeepsExistingMarksWithoutMarkingNewOrMovedClasses() {
        val day = LocalDate.of(2026, 11, 13)
        val marked = lesson("a", day, 8, lift = "2026-11-13@18:00")
        val newClass = lesson("b", day, 9)
        val moved = lesson("a", day.plusDays(1), 8)
        val unknown = listOf(SchoolClassLift(day, null, day, preserveOnly = true))
        assertEquals(listOf(marked, newClass, moved), SchoolNoticeInterpreter.applyLifts(
            listOf(marked.copy(collegeLift = ""), newClass, moved), unknown, listOf(marked), day.minusDays(14)))
        val nextDay = lesson("c", day.plusDays(1), 10)
        val withKnownLifting = unknown + SchoolClassLift(day.plusDays(1), LocalTime.of(18, 0), day)
        val result = SchoolNoticeInterpreter.applyLifts(listOf(marked, nextDay), withKnownLifting, listOf(marked), day.minusDays(14))
        assertEquals(marked, result.first())
        assertEquals("2026-11-13@18:00", result.last().collegeLift)
    }

    @Test fun noticeUpdatesAreReportedAsSchoolDayChanges() {
        val warned = SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(warning)), true).single()
        val done = SchoolNoticeInterpreter.items(SchoolNoticeInterpreter.interpret(listOf(confirmed)), true).single()
        val changes = SchoolDiff.changes(listOf(warned), listOf(done), LocalDate.of(2026, 11, 13))
        assertEquals(setOf(SchoolAlert.SCHOOL_DAYS), changes.single().categories)
    }
}
