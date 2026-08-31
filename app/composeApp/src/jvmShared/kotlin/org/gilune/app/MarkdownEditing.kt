// Styled Markdown editing (ADR 0012, stages E2–E3): the source text is the
// document — headings grow, bold bolds, markers dim. E3 adds the first
// deliberate departure from "transformed text == source": off the caret's
// line a task marker renders as one checkbox glyph, so this module also
// owns the offset mapping that keeps the caret and the bytes in agreement.
// Pure over the string, so span and mapping logic is testable without a
// screen.
//
// ADR 0015 § 5 makes that departure a seam. A decoration is no longer the
// checkbox by name: it is a registration — a line recogniser, a per-match
// rendering and span role, an optional activation handler — and the
// checkbox is the shell's own first one, on the `BUILT_INS + extra` shape
// `Search.kt` already uses. Composition stays here, where ADR 0012 can be
// enforced against a producer: the shell filters the caret's line before
// asking anyone, folds every match into one offset table, resolves
// overlaps, and never lets rendering change a byte.

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

/// The palette role a decorated span takes. A producer names a role, never
/// a colour, so the theme stays the shell's. [NONE] adds no span at all —
/// the checkbox glyph, which the core styling rules dress like any other
/// character.
enum class DecorationRole { NONE, MUTED, MARKER, LINK, CODE }

/// What one recognised match renders as. [Replace] swaps those characters
/// for `text` and the lengths may differ — the shell's offset table carries
/// the difference. [StyleOnly] is the zero-delta case: the bytes render as
/// themselves and only the role's span is added, which is all a `due:` token
/// needs (ADR 0015 § 1).
sealed interface DecorationRender {
    data class Replace(val text: String) : DecorationRender

    data object StyleOnly : DecorationRender
}

/// One match a recogniser claims: a half-open range *within its line*, and
/// how it renders. A range outside the line, or an empty one, is discarded
/// by the shell rather than trusted.
data class DecorationMatch(val range: IntRange, val render: DecorationRender)

/// A registration into the `editor.decorations` seam (ADR 0015 § 5).
///
/// `recognise` is asked one line at a time and returns the ranges it claims;
/// `role` styles every one of them; `activate` — when present — rewrites the
/// whole line the user activated and makes the registration clickable. All
/// three are pure over the string: a registration never sees the buffer, and
/// nothing it returns can change a byte except through an activation the
/// user performs.
///
/// `firesInsideCodeFences` is off by default, so task metadata never becomes
/// somebody's agenda because it was quoted in an example (§ 1). The checkbox
/// slice opts in, because presentation of a GFM element is not metadata and
/// that is what the shell already did.
class EditorDecoration(
    val id: String,
    val recognise: (line: String) -> List<DecorationMatch>,
    val role: DecorationRole = DecorationRole.NONE,
    val firesInsideCodeFences: Boolean = false,
    val activate: ((line: String) -> String)? = null,
)

/// The activation-bearing span drawn under a position: which registration
/// owns it, the source range of the match itself, and the source range of
/// the line to hand [activateLine]. Both ranges are half-open.
data class DecorationHit(
    val decoration: EditorDecoration,
    val range: IntRange,
    val lineRange: IntRange,
)

/// The shell's own registrations, ahead of every plugin's (§ 5: overlaps go
/// to the first registration, built-ins first). One entry today — the E3
/// checkbox slice, re-expressed so the seam carries the same shape a plugin
/// does and the plugin is not bolted beside a hard-coded path.
fun builtInDecorations(): List<EditorDecoration> = BUILT_IN_DECORATIONS

/// Styles the buffer, and — when a caret line is supplied — lets the
/// registered decorations render on every *other* line (E3, Obsidian's
/// model: the line you are editing always shows its raw Markdown).
///
/// With `caretLine == null`, or when nothing is claimed, the transformed
/// text is byte-identical to the source and offsets are identity. Otherwise
/// every match is folded into one table and offsets travel through one
/// [OffsetMapping]. Styling is always computed against the *transformed*
/// string, so spans land where the replacements put them.
class MarkdownEditTransformation(
    private val palette: MarkdownPalette,
    private val caretLine: Int? = null,
    private val decorations: List<EditorDecoration> = builtInDecorations(),
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val placed = caretLine?.let { decorate(source, it, decorations) }.orEmpty()
        if (placed.isEmpty()) {
            return TransformedText(styleMarkdown(source, palette), OffsetMapping.Identity)
        }
        val starts = transformedStarts(placed)
        val transformed = applyDecorations(source, placed)
        // Zero-delta spans style without moving anything, so they earn no
        // table entry — and a document holding only those keeps identity
        // offsets, exactly as E2 did.
        val table = placed.filter { it.replacement.length != it.sourceLength }
        val mapping = if (table.isEmpty()) {
            OffsetMapping.Identity
        } else {
            DecorationOffsetMapping(table, source.length, transformed.length)
        }
        return TransformedText(styleDecorations(transformed, placed, starts, palette), mapping)
    }
}

/// Folds every applicable registration's activation over `line`, in
/// declaration order, and returns the rewritten line — null when nobody
/// claimed it, so a stale click leaves the document alone. Applicability is
/// re-asked of the line as it stands, so a later link sees what an earlier
/// one wrote: core flips the marker, tasks then stamps `done:` (§ 5).
fun activateLine(line: String, decorations: List<EditorDecoration> = builtInDecorations()): String? {
    var current = line
    var claimed = false
    for (decoration in decorations) {
        val activate = decoration.activate ?: continue
        if (decoration.recognise(current).isEmpty()) continue
        current = activate(current)
        claimed = true
    }
    return if (claimed) current else null
}

/// The one question a tap and a pointer both ask: which activation-bearing
/// decoration is drawn at this *transformed* offset? Decorations on the
/// caret's line are raw text and answer nothing, and a registration with no
/// activation handler is not clickable however it renders.
///
/// The offset is a cursor position, so a hit on a glyph's right half arrives
/// as the boundary just past it; a span ending exactly there answers, but
/// only once no span actually covering the offset has.
fun decorationAt(
    text: String,
    transformedOffset: Int,
    caretLine: Int?,
    decorations: List<EditorDecoration> = builtInDecorations(),
): DecorationHit? {
    val placed = caretLine?.let { decorate(text, it, decorations) }.orEmpty()
    if (placed.isEmpty()) return null
    val starts = transformedStarts(placed)
    var onEdge: DecorationHit? = null
    for ((index, span) in placed.withIndex()) {
        if (span.decoration.activate == null) continue
        val start = starts[index]
        val end = start + span.replacement.length
        if (transformedOffset in start until end) return span.hit()
        if (transformedOffset == end && onEdge == null) onEdge = span.hit()
    }
    return onEdge
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
private const val UNCHECKED_GLYPH = "☐" // BALLOT BOX
private const val CHECKED_GLYPH = "☑" // BALLOT BOX WITH CHECK

/// A task marker directly after a bullet, optionally indented. `\d+.`
/// ordered lists are deliberately out of scope for E3.
private val TASK_MARKER = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]])""")

private fun taskMarkerIn(line: String): IntRange? =
    TASK_MARKER.find(line)?.groups?.get(MARKER_GROUP)?.let { it.range.first until it.range.first + MARKER_LENGTH }

/// The checkbox slice as the shell's first registration. Same regex, same
/// glyphs, same toggle as its activation handler — the behaviour is
/// unchanged and only its shape moved. It fires inside fences because it
/// always has: a checkbox reverting to raw brackets would punish the reader
/// of a quoted example for a rule written about task *metadata* (§ 1).
private val CHECKBOX_DECORATION = EditorDecoration(
    id = "gilune.checkbox",
    role = DecorationRole.NONE,
    firesInsideCodeFences = true,
    recognise = { line ->
        val marker = taskMarkerIn(line)
        if (marker == null) {
            emptyList()
        } else {
            val glyph = if (line[marker.first + 1] == ' ') UNCHECKED_GLYPH else CHECKED_GLYPH
            listOf(DecorationMatch(marker, DecorationRender.Replace(glyph)))
        }
    },
    activate = { line -> taskMarkerIn(line)?.let { toggleCheckbox(line, it) } ?: line },
)

private val BUILT_IN_DECORATIONS = listOf(CHECKBOX_DECORATION)

/// One accepted match, placed in the source: what renders where, who owns
/// it, and the line it sits on. `replacement` is the source text itself for
/// a style-only span, so replacement and mapping are one uniform pass.
private class PlacedDecoration(
    val start: Int,
    val sourceLength: Int,
    val replacement: String,
    val role: DecorationRole,
    val decoration: EditorDecoration,
    val lineRange: IntRange,
) {
    fun hit(): DecorationHit =
        DecorationHit(decoration, start until start + sourceLength, lineRange)
}

/// Composition, and the whole of what § 5 reserves to the shell: the caret's
/// line is filtered out before any registration is consulted, fences gate
/// the registrations that asked to be gated, and overlapping claims go to
/// the first registration in declaration order. The result is sorted by
/// start and non-overlapping, which is what every stage after it assumes.
private fun decorate(
    source: String,
    caretLine: Int,
    decorations: List<EditorDecoration>,
): List<PlacedDecoration> {
    if (decorations.isEmpty()) return emptyList()
    val placed = mutableListOf<PlacedDecoration>()
    var offset = 0
    var inFence = false
    for ((index, line) in source.split('\n').withIndex()) {
        val start = offset
        offset += line.length + 1
        // Fence tracking as `styleMarkdown` does it, delimiters included in
        // the block so a recogniser cannot fire on the ``` line either.
        val delimiter = line.trim().startsWith("```")
        val fenced = inFence || delimiter
        if (delimiter) inFence = !inFence
        if (index == caretLine) continue

        val claimed = mutableListOf<IntRange>()
        val onLine = mutableListOf<PlacedDecoration>()
        for (decoration in decorations) {
            if (fenced && !decoration.firesInsideCodeFences) continue
            for (match in decoration.recognise(line)) {
                val range = match.range
                if (range.isEmpty() || range.first < 0 || range.last >= line.length) continue
                if (claimed.any { range.first <= it.last && it.first <= range.last }) continue
                claimed += range
                val length = range.last - range.first + 1
                val replacement = when (val render = match.render) {
                    is DecorationRender.Replace -> render.text
                    DecorationRender.StyleOnly -> line.substring(range.first, range.first + length)
                }
                onLine += PlacedDecoration(
                    start = start + range.first,
                    sourceLength = length,
                    replacement = replacement,
                    role = decoration.role,
                    decoration = decoration,
                    lineRange = start until start + line.length,
                )
            }
        }
        // Declaration order decides overlaps; document order decides the
        // table, and one registration may claim later ground than the next.
        placed += onLine.sortedBy { it.start }
    }
    return placed
}

private fun transformedStarts(placed: List<PlacedDecoration>): List<Int> {
    val starts = ArrayList<Int>(placed.size)
    var shift = 0
    for (span in placed) {
        starts += span.start + shift
        shift += span.replacement.length - span.sourceLength
    }
    return starts
}

private fun applyDecorations(source: String, placed: List<PlacedDecoration>): String =
    buildString(source.length) {
        var cursor = 0
        for (span in placed) {
            append(source, cursor, span.start)
            append(span.replacement)
            cursor = span.start + span.sourceLength
        }
        append(source, cursor, source.length)
    }

private fun styleDecorations(
    transformed: String,
    placed: List<PlacedDecoration>,
    starts: List<Int>,
    palette: MarkdownPalette,
): AnnotatedString {
    val styled = styleMarkdown(transformed, palette)
    if (placed.none { it.role != DecorationRole.NONE }) return styled
    return buildAnnotatedString {
        append(styled)
        for ((index, span) in placed.withIndex()) {
            val style = span.role.spanStyle(palette) ?: continue
            addStyle(style, starts[index], starts[index] + span.replacement.length)
        }
    }
}

private fun DecorationRole.spanStyle(palette: MarkdownPalette): SpanStyle? = when (this) {
    DecorationRole.NONE -> null
    DecorationRole.MUTED -> SpanStyle(color = palette.muted)
    DecorationRole.MARKER -> SpanStyle(color = palette.marker)
    DecorationRole.LINK -> SpanStyle(color = palette.link)
    DecorationRole.CODE -> SpanStyle(color = palette.code, background = palette.codeBackground)
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

/// Maps between a source and a transformed string in which some ranges
/// render at a different length. Both directions are monotone and in bounds
/// — Compose crashes on a mapping that is not.
///
/// The table is the generalisation § 5 asks for: each entry shifts every
/// offset behind it by its own delta, where the checkbox's was a constant
/// two. Offsets *inside* a replaced range have no exact image: they snap to
/// the nearer edge, which keeps the mapping monotone and costs at most the
/// range's own width of caret position. Outside those ranges it is an exact
/// inverse in both directions.
private class DecorationOffsetMapping(
    private val table: List<PlacedDecoration>,
    private val originalLength: Int,
    private val transformedLength: Int,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val bounded = offset.coerceIn(0, originalLength)
        var shift = 0
        for (span in table) {
            if (bounded >= span.start + span.sourceLength) {
                shift += span.replacement.length - span.sourceLength
                continue
            }
            if (bounded > span.start) {
                val inside = bounded - span.start
                val nearerEdge = if (inside * 2 >= span.sourceLength) span.replacement.length else 0
                return span.start + shift + nearerEdge
            }
            break
        }
        return bounded + shift
    }

    override fun transformedToOriginal(offset: Int): Int {
        val bounded = offset.coerceIn(0, transformedLength)
        var shift = 0
        for (span in table) {
            val start = span.start + shift
            if (bounded <= start) break
            val length = span.replacement.length
            if (bounded < start + length) {
                val inside = bounded - start
                return if (inside * 2 >= length) span.start + span.sourceLength else span.start
            }
            shift += length - span.sourceLength
        }
        return bounded - shift
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
