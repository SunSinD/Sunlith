package io.github.sunsind.sunlith.school

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** Minimal, non-executing HTML reader. It never loads resources; scripts, styles and embedded content are dropped. */
internal sealed interface HtmlToken
internal data class HtmlTag(val name: String, val closing: Boolean, val attributes: Map<String, String> = emptyMap()) : HtmlToken
internal data class HtmlText(val text: String) : HtmlToken

enum class SchoolSpanKind { BOLD, ITALIC, UNDERLINE, LINK }
data class SchoolSpan(val start: Int, val end: Int, val kind: SchoolSpanKind, val url: String? = null)
data class SchoolRichText(val text: String, val spans: List<SchoolSpan> = emptyList())

internal data class HtmlLink(val href: String, val onclick: String, val text: String)
internal data class HtmlCell(val tokens: List<HtmlToken>, val header: Boolean, val links: List<HtmlLink>) {
    val text: String get() = SchoolHtml.rich(tokens).text
}
internal data class HtmlTable(val rows: List<List<HtmlCell>>)
internal data class HtmlForm(val action: String, val method: String, val inputs: List<Map<String, String>>)

internal object SchoolHtml {
    const val MAX_HTML = 2_000_000
    private val RAW = setOf("script", "style", "noscript", "template", "iframe", "object", "embed", "svg", "math", "textarea", "title")
    private val VOID = setOf("br", "img", "input", "hr", "meta", "link", "frame", "area", "base", "col", "wbr", "source")
    private val BLOCK = setOf("div", "tr", "table", "ul", "ol", "dl", "dt", "dd", "section", "article", "header", "footer", "center", "form", "tbody", "thead")
    private val PARAGRAPH = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre")

    fun tokens(html: String): List<HtmlToken> {
        schoolRequire(html.length <= MAX_HTML)
        val out = ArrayList<HtmlToken>()
        var i = 0
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { out += HtmlText(decode(text.toString())); text.clear() } }
        while (i < html.length) {
            val c = html[i]
            if (c != '<') { text.append(c); i++; continue }
            if (html.startsWith("<!--", i)) { flush(); i = html.indexOf("-->", i + 4).let { if (it < 0) html.length else it + 3 }; continue }
            val next = html.getOrNull(i + 1)
            if (next == '!' || next == '?') { flush(); i = html.indexOf('>', i).let { if (it < 0) html.length else it + 1 }; continue }
            val closing = next == '/'
            val nameStart = if (closing) i + 2 else i + 1
            if (html.getOrNull(nameStart)?.isLetter() != true) { text.append(c); i++; continue }
            flush()
            var j = nameStart
            while (j < html.length && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == ':')) j++
            val name = html.substring(nameStart, j).lowercase(Locale.ROOT)
            val attributes = linkedMapOf<String, String>()
            while (j < html.length && html[j] != '>') {
                if (html[j].isWhitespace() || html[j] == '/') { j++; continue }
                val a = j
                while (j < html.length && !html[j].isWhitespace() && html[j] != '=' && html[j] != '>' && html[j] != '/') j++
                val key = html.substring(a, j).lowercase(Locale.ROOT)
                while (j < html.length && html[j].isWhitespace()) j++
                var value = ""
                if (j < html.length && html[j] == '=') {
                    j++
                    while (j < html.length && html[j].isWhitespace()) j++
                    if (j < html.length && (html[j] == '"' || html[j] == '\'')) {
                        val quote = html[j]
                        val end = html.indexOf(quote, j + 1).let { if (it < 0) html.length else it }
                        value = html.substring(j + 1, end); j = (end + 1).coerceAtMost(html.length)
                    } else {
                        val v = j
                        while (j < html.length && !html[j].isWhitespace() && html[j] != '>') j++
                        value = html.substring(v, j)
                    }
                }
                if (key.isNotEmpty() && key !in attributes) attributes[key] = decode(value)
            }
            i = (j + 1).coerceAtMost(html.length)
            if (!closing && name in RAW) {
                val end = html.indexOf("</$name", i, ignoreCase = true)
                i = if (end < 0) html.length else html.indexOf('>', end).let { if (it < 0) html.length else it + 1 }
                if (name == "iframe") out += HtmlTag(name, false, attributes)
                continue
            }
            out += HtmlTag(name, closing, if (closing) emptyMap() else attributes)
        }
        flush()
        return out
    }

    fun rich(html: String): SchoolRichText = rich(tokens(html))

    fun rich(tokens: List<HtmlToken>): SchoolRichText {
        val sb = StringBuilder()
        val spans = ArrayList<SchoolSpan>()
        val open = ArrayList<Triple<String, Int, String?>>()
        var pre = 0
        fun trailingNewlines(): Int { var n = 0; var k = sb.length - 1; while (k >= 0 && sb[k] == '\n') { n++; k-- }; return n }
        fun lineBreak(count: Int) {
            while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
            if (sb.isEmpty()) return
            repeat((count - trailingNewlines()).coerceAtLeast(0)) { sb.append('\n') }
        }
        fun close(name: String) {
            val index = open.indexOfLast { it.first == name }
            if (index < 0) return
            val (_, start, url) = open.removeAt(index)
            val kind = when (name) { "b", "strong" -> SchoolSpanKind.BOLD; "i", "em" -> SchoolSpanKind.ITALIC; "u" -> SchoolSpanKind.UNDERLINE; else -> SchoolSpanKind.LINK }
            if (sb.length > start && (kind != SchoolSpanKind.LINK || url != null)) spans += SchoolSpan(start, sb.length, kind, url)
        }
        tokens.forEach { token ->
            when (token) {
                is HtmlText -> {
                    if (pre > 0) sb.append(token.text.replace("\r\n", "\n"))
                    else {
                        val collapsed = token.text.replace(WHITESPACE, " ")
                        val atLineStart = sb.isEmpty() || sb.last() == '\n' || sb.last() == ' '
                        sb.append(if (atLineStart) collapsed.trimStart() else collapsed)
                    }
                }
                is HtmlTag -> when {
                    token.name == "br" -> { while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1); if (sb.isNotEmpty()) sb.append('\n') }
                    token.name == "hr" -> lineBreak(2)
                    token.name == "li" && !token.closing -> { lineBreak(1); sb.append("• ") }
                    token.name == "li" -> lineBreak(1)
                    token.name in PARAGRAPH -> {
                        lineBreak(2)
                        if (token.name == "pre") pre = (pre + if (token.closing) -1 else 1).coerceAtLeast(0)
                    }
                    token.name in BLOCK -> lineBreak(1)
                    token.name == "td" || token.name == "th" -> if (!token.closing && sb.isNotEmpty() && sb.last() != '\n' && sb.last() != ' ') sb.append("  ")
                    token.name == "img" -> token.attributes["alt"]?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append("[").append(it.take(200)).append("]") }
                    token.name in setOf("b", "strong", "i", "em", "u", "a") -> {
                        if (token.closing) close(token.name)
                        else open += Triple(token.name, sb.length, if (token.name == "a") safeLink(token.attributes["href"]) else null)
                    }
                }
            }
        }
        while (open.isNotEmpty()) close(open.last().first)
        while (sb.isNotEmpty() && sb.last().isWhitespace()) sb.setLength(sb.length - 1)
        val text = sb.toString()
        return SchoolRichText(text, spans.mapNotNull { s -> if (s.start >= text.length) null else s.copy(end = s.end.coerceAtMost(text.length)) })
    }

    /** Only absolute web or mail links survive; javascript:, data:, relative and other schemes become plain text. */
    fun safeLink(href: String?): String? {
        val value = href?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2000 } ?: return null
        if (value.any { it.isISOControl() || it.isWhitespace() }) return null
        return try {
            val uri = URI(value)
            when (uri.scheme?.lowercase(Locale.ROOT)) {
                "https", "http" -> if (!uri.host.isNullOrBlank() && uri.rawUserInfo == null) value else null
                "mailto" -> value
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun tables(tokens: List<HtmlToken>): List<HtmlTable> {
        class Builder {
            val rows = ArrayList<List<HtmlCell>>()
            var row: ArrayList<HtmlCell>? = null
            var cell: ArrayList<HtmlToken>? = null
            var header = false
            val links = ArrayList<HtmlLink>()
            var link: Triple<String, String, StringBuilder>? = null
            fun closeCell() {
                val tokens = cell ?: return
                link?.let { links += HtmlLink(it.first, it.second, it.third.toString().trim()) }; link = null
                (row ?: ArrayList<HtmlCell>().also { row = it }).add(HtmlCell(tokens.toList(), header, links.toList()))
                cell = null; links.clear()
            }
            fun closeRow() { closeCell(); row?.let { if (it.isNotEmpty()) rows += it.toList() }; row = null }
        }
        val done = ArrayList<HtmlTable>()
        val stack = ArrayList<Builder>()
        tokens.forEach { token ->
            val top = stack.lastOrNull()
            if (token is HtmlTag) when (token.name) {
                "table" -> if (!token.closing) { schoolRequire(stack.size < 16); stack += Builder(); return@forEach } else if (top != null) {
                    top.closeRow(); done += HtmlTable(top.rows); stack.removeAt(stack.lastIndex); return@forEach
                }
                "tr" -> if (top != null) { top.closeRow(); if (!token.closing) top.row = ArrayList(); return@forEach }
                "td", "th" -> if (top != null) {
                    top.closeCell()
                    if (!token.closing) { if (top.row == null) top.row = ArrayList(); top.cell = ArrayList(); top.header = token.name == "th" }
                    return@forEach
                }
                "a" -> if (top?.cell != null) {
                    if (!token.closing) {
                        top.link?.let { top.links += HtmlLink(it.first, it.second, it.third.toString().trim()) }
                        top.link = Triple(token.attributes["href"].orEmpty(), token.attributes["onclick"].orEmpty(), StringBuilder())
                    } else { top.link?.let { top.links += HtmlLink(it.first, it.second, it.third.toString().trim()) }; top.link = null }
                }
            }
            if (top?.cell != null) {
                top.cell!!.add(token)
                if (token is HtmlText) top.link?.third?.append(token.text)
            }
        }
        while (stack.isNotEmpty()) { val b = stack.removeAt(stack.lastIndex); b.closeRow(); done += HtmlTable(b.rows) }
        return done
    }

    fun forms(tokens: List<HtmlToken>): List<HtmlForm> {
        val forms = ArrayList<HtmlForm>()
        var current: Triple<String, String, ArrayList<Map<String, String>>>? = null
        tokens.forEach { token ->
            if (token !is HtmlTag) return@forEach
            when {
                token.name == "form" && !token.closing -> {
                    current?.let { forms += HtmlForm(it.first, it.second, it.third) }
                    current = Triple(token.attributes["action"].orEmpty(), token.attributes["method"].orEmpty().lowercase(Locale.ROOT), ArrayList())
                }
                token.name == "form" -> { current?.let { forms += HtmlForm(it.first, it.second, it.third) }; current = null }
                (token.name == "input" || token.name == "button") && !token.closing -> current?.third?.add(token.attributes + ("tag" to token.name))
            }
        }
        current?.let { forms += HtmlForm(it.first, it.second, it.third) }
        return forms
    }

    fun frames(tokens: List<HtmlToken>): List<String> = tokens.filterIsInstance<HtmlTag>()
        .filter { !it.closing && (it.name == "frame" || it.name == "iframe") }.mapNotNull { it.attributes["src"]?.trim()?.takeIf(String::isNotEmpty) }

    /** Lowercase, accent-free, single-spaced text for structural matching only (never shown). */
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(MARKS, "")
        .replace('’', '\'').replace('‘', '\'').replace('–', '-').replace('—', '-')
        .lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    fun decode(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '&') {
                val end = value.indexOf(';', i + 1)
                if (end in (i + 2)..(i + 12)) {
                    val name = value.substring(i + 1, end)
                    val code = when {
                        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
                        name.startsWith("#") -> name.substring(1).toIntOrNull()
                        else -> ENTITIES[name]
                    }
                    if (code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF) {
                        out.appendCodePoint(if (code in 0x80..0x9F) WINDOWS_1252[code - 0x80] else code); i = end + 1; continue
                    }
                }
            }
            out.append(c); i++
        }
        return out.toString()
    }

    private val WHITESPACE = Regex("[\\s\\u00A0]+")
    private val MARKS = Regex("\\p{Mn}+")
    private val WINDOWS_1252 = intArrayOf(0x20AC, 0x81, 0x201A, 0x192, 0x201E, 0x2026, 0x2020, 0x2021, 0x2C6, 0x2030, 0x160, 0x2039, 0x152, 0x8D, 0x17D, 0x8F,
        0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x2DC, 0x2122, 0x161, 0x203A, 0x153, 0x9D, 0x17E, 0x178)
    private val ENTITIES: Map<String, Int> = buildMap {
        putAll(mapOf("amp" to 38, "lt" to 60, "gt" to 62, "quot" to 34, "apos" to 39, "nbsp" to 160, "rsquo" to 0x2019, "lsquo" to 0x2018,
            "ldquo" to 0x201C, "rdquo" to 0x201D, "ndash" to 0x2013, "mdash" to 0x2014, "hellip" to 0x2026, "laquo" to 171, "raquo" to 187,
            "euro" to 0x20AC, "bull" to 0x2022, "copy" to 169, "reg" to 174, "deg" to 176, "middot" to 183, "oelig" to 0x153, "OElig" to 0x152, "shy" to 173))
        val latin = listOf("Agrave", "Aacute", "Acirc", "Atilde", "Auml", "Aring", "AElig", "Ccedil", "Egrave", "Eacute", "Ecirc", "Euml",
            "Igrave", "Iacute", "Icirc", "Iuml", "ETH", "Ntilde", "Ograve", "Oacute", "Ocirc", "Otilde", "Ouml", "times", "Oslash",
            "Ugrave", "Uacute", "Ucirc", "Uuml", "Yacute", "THORN", "szlig")
        latin.forEachIndexed { index, name -> put(name, 0xC0 + index); if (index != 23 && index != 31) put(name.replaceFirstChar { it.lowercase() }.let { if (name.length > 1 && name[1].isUpperCase()) name.lowercase() else it }, 0xE0 + index) }
        put("divide", 247); put("yuml", 255)
    }
}
