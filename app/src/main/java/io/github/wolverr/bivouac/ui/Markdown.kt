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
    val lines = text.split('\n')
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

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])|`([^`]+)`|\\[(\\d{1,2})]")

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
