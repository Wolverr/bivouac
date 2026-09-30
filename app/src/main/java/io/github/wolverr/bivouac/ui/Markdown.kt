package io.github.wolverr.bivouac.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * The small subset of Markdown that models actually produce in answers:
 * headings, bullet/numbered lists, **bold**, *italic*, `code`, plus [n]
 * citation markers. Works on partial text, so it can render while streaming.
 */
fun renderAnswer(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    val lines = tablesToLists(text.split('\n')).map(::stripMath)
    lines.forEachIndexed { i, raw ->
        val heading = HEADING.matchEntire(raw)
        val bullet = BULLET.matchEntire(raw)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.08.em)) {
                inline(heading.groupValues[2], accent)
            }
            bullet != null -> {
                append(bullet.groupValues[1])  // keep indentation for nested lists
                append("•  ")
                inline(bullet.groupValues[2], accent)
            }
            else -> inline(raw, accent)
        }
        if (i < lines.lastIndex) append('\n')
    }
}

/**
 * Tables are unreadable on a phone. Each data row becomes a bold label (first
 * cell) followed by "column: value" bullets.
 */
private fun tablesToLists(lines: List<String>): List<String> {
    val out = ArrayList<String>()
    var header: List<String>? = null
    for (line in lines) {
        val t = line.trim()
        if (!(t.startsWith("|") && t.endsWith("|") && t.length > 1)) {
            header = null
            out += line
            continue
        }
        val cells = t.trim('|').split('|').map { it.trim() }
        when {
            cells.all { it.matches(Regex(":?-{2,}:?")) } -> Unit  // separator row
            header == null -> header = cells
            else -> {
                out += "**${cells.first()}**"
                cells.drop(1).forEachIndexed { i, c -> out += "- ${header.getOrNull(i + 1) ?: ""}: $c" }
            }
        }
    }
    return out
}

private val SUBSCRIPT = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉')
private val MATH = Regex("\\$\\$?([^$]{1,80}?)\\$\\$?")

/** Inline LaTeX the models like for formulas: $LiFePO_4$ -> LiFePO₄. */
private fun stripMath(line: String): String = MATH.replace(line) { m ->
    // "$5 to $10" is money, not math: only touch spans with formula syntax.
    if (m.groupValues[1].none { it == '_' || it == '^' || it == '\\' }) return@replace m.value
    m.groupValues[1]
        .replace(Regex("_\\{?(\\d+)}?")) { d -> d.groupValues[1].map { SUBSCRIPT[it] ?: it }.joinToString("") }
        .replace(Regex("\\\\(text|mathrm)\\{([^}]*)}"), "$2")
        .replace("\\times", "×").replace("\\approx", "≈").replace("\\cdot", "·")
        .replace("{", "").replace("}", "")
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])|`([^`]+)`|\\[(P?\\d{1,2})]")

private fun AnnotatedString.Builder.inline(s: String, accent: Color) {
    var last = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[1], accent) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[2], accent) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(g[3], accent) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[4]) }
            else -> withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(m.value) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}
