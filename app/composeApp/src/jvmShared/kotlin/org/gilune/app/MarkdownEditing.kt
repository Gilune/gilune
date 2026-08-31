// Styled Markdown editing (ADR 0012, stage E2): the source text is the
// document and every character of it stays visible — headings grow,
// bold bolds, markers dim, and nothing ever shifts an offset, so the
// caret and the bytes cannot disagree. Pure over the string, so the
// span logic is testable without a screen.

package org.gilune.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

data class MarkdownPalette(
    val muted: Color,
    val marker: Color,
    val heading: Color,
    val code: Color,
    val codeBackground: Color,
    val link: Color,
)

/// Styling only — the returned string is always the input string, so the
/// transformation can use identity offsets.
class MarkdownEditTransformation(private val palette: MarkdownPalette) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styleMarkdown(text.text, palette), OffsetMapping.Identity)
}

private val HEADING = Regex("""^(#{1,6})\s""")
private val LIST_MARKER = Regex("""^(\s*)([-*+]|\d+\.)\s""")
private val CODE_SPAN = Regex("""`[^`\n]+`""")
private val BOLD = Regex("""\*\*[^*\n]+\*\*""")
private val ITALIC = Regex("""(?<!\*)\*(?!\*)[^*\n]+(?<!\*)\*(?!\*)""")
private val STRIKE = Regex("""~~[^~\n]+~~""")
private val LINK = Regex("""\[([^\]\n]*)]\(([^)\n]*)\)""")
private val WIKI = Regex("""!?\[\[[^\]\n]+]]""")

fun styleMarkdown(source: String, palette: MarkdownPalette): AnnotatedString =
    buildAnnotatedString {
        append(source)
        val mutedStyle = SpanStyle(color = palette.muted)
        var offset = 0
        var inFrontMatter = false
        var frontMatterClosed = false
        var inFence = false
        for ((index, line) in source.split('\n').withIndex()) {
            val start = offset
            val end = start + line.length
            offset = end + 1
            val trimmed = line.trim()

            if (index == 0 && trimmed == "---") {
                inFrontMatter = true
                addStyle(mutedStyle, start, end)
                continue
            }
            if (inFrontMatter && !frontMatterClosed) {
                addStyle(mutedStyle, start, end)
                if (trimmed == "---") {
                    inFrontMatter = false
                    frontMatterClosed = true
                }
                continue
            }
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                addStyle(mutedStyle, start, end)
                continue
            }
            if (inFence) {
                addStyle(SpanStyle(color = palette.code), start, end)
                continue
            }

            val heading = HEADING.find(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                val size = when (level) {
                    1 -> 17.sp
                    2 -> 15.sp
                    else -> 14.sp
                }
                addStyle(
                    SpanStyle(color = palette.heading, fontWeight = FontWeight.Bold, fontSize = size),
                    start,
                    end,
                )
                addStyle(mutedStyle, start, start + level)
            }
            val quote = line.startsWith(">")
            if (quote) {
                addStyle(SpanStyle(color = palette.muted, fontStyle = FontStyle.Italic), start, end)
            }
            LIST_MARKER.find(line)?.let { m ->
                addStyle(
                    SpanStyle(color = palette.marker, fontWeight = FontWeight.Bold),
                    start + m.groupValues[1].length,
                    start + m.value.length,
                )
            }

            for (m in CODE_SPAN.findAll(line)) {
                addStyle(
                    SpanStyle(color = palette.code, background = palette.codeBackground),
                    start + m.range.first,
                    start + m.range.last + 1,
                )
            }
            for (m in BOLD.findAll(line)) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start + m.range.first, start + m.range.last + 1)
                addStyle(mutedStyle, start + m.range.first, start + m.range.first + 2)
                addStyle(mutedStyle, start + m.range.last - 1, start + m.range.last + 1)
            }
            for (m in ITALIC.findAll(line)) {
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start + m.range.first, start + m.range.last + 1)
                addStyle(mutedStyle, start + m.range.first, start + m.range.first + 1)
                addStyle(mutedStyle, start + m.range.last, start + m.range.last + 1)
            }
            for (m in STRIKE.findAll(line)) {
                addStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                    start + m.range.first,
                    start + m.range.last + 1,
                )
            }
            for (m in LINK.findAll(line)) {
                val label = m.groups[1]
                if (label != null) {
                    addStyle(
                        SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline),
                        start + label.range.first,
                        start + label.range.last + 1,
                    )
                }
                val destination = m.groups[2]
                if (destination != null) {
                    addStyle(mutedStyle, start + destination.range.first, start + destination.range.last + 1)
                }
            }
            for (m in WIKI.findAll(line)) {
                addStyle(SpanStyle(color = palette.link), start + m.range.first, start + m.range.last + 1)
            }
        }
    }
