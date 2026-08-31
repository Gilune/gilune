// Styled Markdown editing (ADR 0012, stages E2–E3): the source text is the
// document — headings grow, bold bolds, markers dim. E3 adds the first
// deliberate departure from "transformed text == source": off the caret's
// line a task marker renders as one checkbox glyph, so this module also
// owns the offset mapping that keeps the caret and the bytes in agreement.
// Pure over the string, so span and mapping logic is testable without a
// screen.

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

/// Styles the buffer, and — when a caret line is supplied — renders task
/// markers as checkbox glyphs on every *other* line (E3, Obsidian's model:
/// the line you are editing always shows its raw Markdown).
///
/// With `caretLine == null`, or when no marker qualifies, the transformed
/// text is byte-identical to the source and offsets are identity. Otherwise
/// each qualifying `[ ]`/`[x]` becomes one glyph and offsets travel through
/// a table-driven [OffsetMapping]. Styling is always computed against the
/// *transformed* string, so spans land where the glyphs put them.
class MarkdownEditTransformation(
    private val palette: MarkdownPalette,
    private val caretLine: Int? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val glyphs = caretLine?.let { checkboxGlyphs(source, it) }.orEmpty()
        if (glyphs.isEmpty()) {
            return TransformedText(styleMarkdown(source, palette), OffsetMapping.Identity)
        }
        val transformed = replaceMarkers(source, glyphs)
        return TransformedText(
            styleMarkdown(transformed, palette),
            CheckboxOffsetMapping(glyphs, source.length, transformed.length),
        )
    }
}

/// 0-based line holding `offset`, which is clamped into the text first, so
/// this is total: an offset past the end reports the last line.
fun caretLine(text: String, offset: Int): Int {
    val bounded = offset.coerceIn(0, text.length)
    var line = 0
    for (index in 0 until bounded) if (text[index] == '\n') line++
    return line
}

/// The three-character range of the task marker that `offset` addresses, or
/// null. The offset must land *on* one of `[`, the state character or `]`
/// (an offset just past the marker is not on it), and the marker must open a
/// task-list line — a `[ ]` elsewhere in the line is ordinary text.
fun checkboxMarkerAt(text: String, offset: Int): IntRange? {
    if (offset < 0 || offset >= text.length) return null
    val lineStart = lineStartFor(text, offset)
    val line = text.substring(lineStart, lineEndFor(text, offset))
    val marker = TASK_MARKER.find(line)?.groups?.get(MARKER_GROUP) ?: return null
    val start = lineStart + marker.range.first
    return if (offset in start until start + MARKER_LENGTH) start until start + MARKER_LENGTH else null
}

/// Flips the marker at `marker` between `[ ]` and `[x]`, returning text that
/// is byte-identical outside those three characters. A range that does not
/// hold a marker returns the input unchanged, so a stale click cannot
/// corrupt the document.
fun toggleCheckbox(text: String, marker: IntRange): String {
    val start = marker.first
    if (start < 0 || marker.last != start + MARKER_LENGTH - 1 || marker.last >= text.length) return text
    if (text[start] != '[' || text[start + 2] != ']') return text
    val flipped = if (text[start + 1] == ' ') "[x]" else "[ ]"
    return text.substring(0, start) + flipped + text.substring(start + MARKER_LENGTH)
}

private const val MARKER_LENGTH = 3
private const val MARKER_GROUP = 4
private const val UNCHECKED_GLYPH = '☐' // BALLOT BOX
private const val CHECKED_GLYPH = '☑' // BALLOT BOX WITH CHECK

/// A task marker directly after a bullet, optionally indented. `\d+.`
/// ordered lists are deliberately out of scope for E3.
private val TASK_MARKER = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]])""")

private class CheckboxGlyph(val start: Int, val glyph: Char)

private fun checkboxGlyphs(source: String, caretLine: Int): List<CheckboxGlyph> {
    val glyphs = mutableListOf<CheckboxGlyph>()
    var offset = 0
    for ((index, line) in source.split('\n').withIndex()) {
        val marker = if (index == caretLine) null else TASK_MARKER.find(line)?.groups?.get(MARKER_GROUP)
        if (marker != null) {
            val state = line[marker.range.first + 1]
            glyphs += CheckboxGlyph(offset + marker.range.first, if (state == ' ') UNCHECKED_GLYPH else CHECKED_GLYPH)
        }
        offset += line.length + 1
    }
    return glyphs
}

private fun replaceMarkers(source: String, glyphs: List<CheckboxGlyph>): String =
    buildString(source.length) {
        var cursor = 0
        for (glyph in glyphs) {
            append(source, cursor, glyph.start)
            append(glyph.glyph)
            cursor = glyph.start + MARKER_LENGTH
        }
        append(source, cursor, source.length)
    }

private fun lineStartFor(text: String, offset: Int): Int {
    var index = offset
    while (index > 0 && text[index - 1] != '\n') index--
    return index
}

private fun lineEndFor(text: String, offset: Int): Int {
    var index = offset
    while (index < text.length && text[index] != '\n') index++
    return index
}

/// Maps between a source with three-character markers and a transformed
/// string where each has become one glyph. Both directions are monotone and
/// in bounds — Compose crashes on a mapping that is not.
///
/// Each glyph shortens the text by two, so offsets shift left by twice the
/// number of glyphs fully behind them. Offsets *inside* a marker have no
/// exact image: they snap to the nearer edge of the glyph, which costs at
/// most one character of caret position and keeps the mapping monotone.
/// The reverse direction always lands on a marker edge, never inside one,
/// so it is an exact inverse everywhere outside the replaced ranges.
private class CheckboxOffsetMapping(
    private val glyphs: List<CheckboxGlyph>,
    private val originalLength: Int,
    private val transformedLength: Int,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val bounded = offset.coerceIn(0, originalLength)
        var shift = 0
        for (glyph in glyphs) {
            if (bounded >= glyph.start + MARKER_LENGTH) {
                shift += MARKER_LENGTH - 1
                continue
            }
            if (bounded > glyph.start) {
                val nearerEdge = if (bounded - glyph.start >= 2) 1 else 0
                return glyph.start - shift + nearerEdge
            }
            break
        }
        return bounded - shift
    }

    override fun transformedToOriginal(offset: Int): Int {
        val bounded = offset.coerceIn(0, transformedLength)
        var shift = 0
        for (glyph in glyphs) {
            if (bounded <= glyph.start - shift) break
            shift += MARKER_LENGTH - 1
        }
        return bounded + shift
    }
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
