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
//
// The seam has a second, **block-scoped** kind (owner, 2026-09-01: a
// `filter` block "behaves like a checkbox — renders until the caret is
// placed within it"). A registration claims a whole closed fence and says
// how many lines it needs; the fence collapses into that many blank lines
// through the same offset table, and the shell draws over them. The caret
// rule is the checkbox's rule one scope up: the caret on *any* line of the
// block, delimiters included, shows the raw fence.

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

/// A closed fenced block as it stands in the buffer: its info string, the
/// body between the delimiters, the half-open source range of the whole
/// fence — both delimiter lines included, the trailing newline not — and
/// the 0-based lines it covers.
data class FencedBlock(
    val info: String,
    val body: String,
    val range: IntRange,
    val lines: IntRange,
)

/// A **block-scoped** registration into the `editor.blocks` seam: the
/// Edit-mode half of what `BlockRegistration` does for Read mode.
///
/// `claim` is asked of a closed fence's info string and body and answers
/// with the number of **lines** the collapsed block reserves, or null to
/// decline it. Declining is a real answer, not a failure: a renderer that
/// has no result for this block yet leaves the raw fence showing, so the
/// transformation can never reserve a box nobody can fill.
class EditorBlockDecoration(
    val id: String,
    val claim: (info: String, body: String) -> Int?,
)

/// Where a collapsed block landed: the registration that took it, the
/// fence it stands for, and the offset and line count of the placeholder
/// reserving its box in the **transformed** text. An overlay anchors on
/// [transformedStart] and covers [lines] of the layout's lines, which is
/// how its height comes out exactly the height the fence gave up.
data class CollapsedBlock(
    val id: String,
    val fence: FencedBlock,
    val transformedStart: Int,
    val lines: Int,
)

/// Every closed fence in `source`, in document order. An **unterminated**
/// fence is not one: it has no end, so there is nothing to collapse and it
/// stays the raw text it is.
fun fencedBlocks(source: String): List<FencedBlock> {
    val found = mutableListOf<FencedBlock>()
    val body = mutableListOf<String>()
    var openStart = -1
    var openLine = -1
    var info = ""
    var offset = 0
    for ((index, line) in source.split('\n').withIndex()) {
        val start = offset
        offset += line.length + 1
        val delimiter = line.trim().startsWith("```")
        if (openStart < 0) {
            if (!delimiter) continue
            openStart = start
            openLine = index
            info = line.trim().removePrefix("```").trim()
            body.clear()
            continue
        }
        if (!delimiter) {
            body += line
            continue
        }
        found += FencedBlock(
            info = info,
            body = body.joinToString("\n"),
            range = openStart until start + line.length,
            lines = openLine..index,
        )
        openStart = -1
    }
    return found
}

/// The blocks a buffer collapses, in the coordinates an overlay needs.
/// Same inputs as [MarkdownEditTransformation], so what is drawn and what
/// was reserved cannot disagree: this asks the same composition the same
/// question rather than repeating its arithmetic.
fun collapsedBlocks(
    source: String,
    caretLine: Int?,
    decorations: List<EditorDecoration> = builtInDecorations(),
    blocks: List<EditorBlockDecoration> = emptyList(),
): List<CollapsedBlock> {
    if (blocks.isEmpty()) return emptyList()
    val placed = decorate(source, caretLine, decorations, blocks)
    val starts = transformedStarts(placed)
    return placed.mapIndexedNotNull { index, span ->
        val id = span.blockId ?: return@mapIndexedNotNull null
        val fence = span.fence ?: return@mapIndexedNotNull null
        CollapsedBlock(id, fence, starts[index], span.replacement.count { it == '\n' } + 1)
    }
}

/// The shell's own registrations, ahead of every plugin's (§ 5: overlaps go
/// to the first registration, built-ins first). One entry today — the E3
/// checkbox slice, re-expressed so the seam carries the same shape a plugin
/// does and the plugin is not bolted beside a hard-coded path.
fun builtInDecorations(): List<EditorDecoration> = BUILT_IN_DECORATIONS

/// Styles the buffer, and — when a caret line is supplied — lets the
/// registered decorations render on every *other* line (E3, Obsidian's
/// model: the line you are editing always shows its raw Markdown).
///
/// `caretLine == null` means there is no caret at all — an unfocused
/// buffer — so *every* line decorates: a note must open showing its
/// checkboxes, ready to be ticked (owner report, 2026-08-31). The raw-line
/// exception belongs to a caret someone can see. When nothing is claimed
/// the transformed text is byte-identical to the source and offsets are
/// identity; otherwise every match folds into one table and offsets travel
/// through one [OffsetMapping]. Styling is always computed against the
/// *transformed* string, so spans land where the replacements put them.
class MarkdownEditTransformation(
    private val palette: MarkdownPalette,
    private val caretLine: Int? = null,
    private val decorations: List<EditorDecoration> = builtInDecorations(),
    private val blocks: List<EditorBlockDecoration> = emptyList(),
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val placed = decorate(source, caretLine, decorations, blocks)
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
///
/// `blocks` is taken for the same reason the transformation takes it: a
/// collapsed block moves every offset behind it, so an answer computed
/// without one would be read off the wrong place in the layout.
fun decorationAt(
    text: String,
    transformedOffset: Int,
    caretLine: Int?,
    decorations: List<EditorDecoration> = builtInDecorations(),
    blocks: List<EditorBlockDecoration> = emptyList(),
): DecorationHit? {
    val placed = decorate(text, caretLine, decorations, blocks)
    if (placed.isEmpty()) return null
    val starts = transformedStarts(placed)
    var onEdge: DecorationHit? = null
    for ((index, span) in placed.withIndex()) {
        val hit = span.hit() ?: continue
        if (span.decoration?.activate == null) continue
        val start = starts[index]
        val end = start + span.replacement.length
        if (transformedOffset in start until end) return hit
        if (transformedOffset == end && onEdge == null) onEdge = hit
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

// ------------------------------------------------------ list continuation

/// What pressing Return on a list line does next (owner rule, 2026-09-01).
sealed interface ListContinuation {
    /// The list goes on: [prefix] — the item's own indentation and bullet,
    /// plus a fresh **unchecked** marker when it was a task — is inserted
    /// at the caret. A ticked item continues unticked, because the next
    /// thing you write down is not already done.
    data class Continue(val prefix: String) : ListContinuation

    /// The list lets go: the half-open source [range] is deleted. It
    /// covers the empty item's own text *and* the newline that was just
    /// typed, so the caret comes back onto a line that is now blank —
    /// Return on an empty item clears the marker rather than leaving a
    /// stray blank line above the one you land on.
    data class Unwrap(val range: IntRange) : ListContinuation
}

/// The one place both editors decide what Return does to a list, taken
/// over the buffer **as it stands with the newline already in it**:
/// `text[caretAfterNewline - 1]` is that newline, and the line above it is
/// the item being continued.
///
/// Reading the buffer after the break rather than before is what makes the
/// mid-item case fall out instead of being a case of its own. Return in
/// the middle of `- [ ] milk and bread` has already carried `and bread`
/// down, so inserting the prefix at the caret leaves that text after the
/// new marker — which is what Obsidian does with the same keystroke.
///
/// An item counts as **empty** only when nothing but whitespace follows
/// its marker on *either* side of the break: the marker line's own
/// remainder, and the text the break carried down. That is the whole item
/// rather than the half above the caret, so Return with the caret parked
/// at `- [ ] |milk` continues (the empty marker stays behind, `milk` moves
/// down under a new one) and only a genuinely empty item unwraps.
///
/// Ordered lists are out of scope exactly as they are for the checkbox
/// (see [TASK_MARKER]), and a list quoted inside a fence is text: writing
/// a marker into somebody's example would be the rendering rule's mistake
/// made with real bytes.
fun listContinuation(text: CharSequence, caretAfterNewline: Int): ListContinuation? {
    if (caretAfterNewline <= 0 || caretAfterNewline > text.length) return null
    val previousEnd = caretAfterNewline - 1
    if (text[previousEnd] != '\n') return null

    // Walk down to the previous line, tracking fences as `decorate` does —
    // delimiters counted into the block, so the ``` line is inside too.
    var lineStart = 0
    var inFence = false
    while (true) {
        val lineEnd = lineEndFor(text, lineStart)
        if (lineEnd >= previousEnd) break
        if (fenceDelimiter(text, lineStart, lineEnd)) inFence = !inFence
        lineStart = lineEnd + 1
    }
    if (inFence) return null

    val previous = text.substring(lineStart, previousEnd)
    val bullet = LIST_ITEM.find(previous) ?: return null
    val task = TASK_MARKER.find(previous)
    val marker = task ?: bullet
    // Whitespace either side of the break: the marker line's remainder and
    // what the break carried down are one item between them.
    val carried = text.substring(caretAfterNewline, lineEndFor(text, caretAfterNewline))
    if (previous.substring(marker.value.length).isBlank() && carried.isBlank()) {
        return ListContinuation.Unwrap(lineStart until caretAfterNewline)
    }
    val prefix = if (task != null) {
        // Groups 1–3 are the indentation, the bullet and the space between
        // it and the marker; the marker itself is written fresh and empty.
        task.groupValues[1] + task.groupValues[2] + task.groupValues[3] + "[ ] "
    } else {
        bullet.value
    }
    return ListContinuation.Continue(prefix)
}

/// The buffer a [ListContinuation] produces, for a caller that assigns a
/// whole value — the desktop's `TextFieldValue`. A caller that must edit
/// in place instead (the phone, where a whole-buffer rewrite would restart
/// the IME's composing session) reads the decision and makes the one
/// targeted edit itself; the arithmetic is here so both agree about it.
data class ContinuedBuffer(val text: String, val caret: Int)

fun ListContinuation.applyTo(text: CharSequence, caret: Int): ContinuedBuffer = when (this) {
    is ListContinuation.Continue -> ContinuedBuffer(
        text.substring(0, caret) + prefix + text.substring(caret, text.length),
        caret + prefix.length,
    )
    is ListContinuation.Unwrap -> ContinuedBuffer(
        text.substring(0, range.first) + text.substring(range.last + 1, text.length),
        range.first,
    )
}

/// Whether the step from one buffer to the next was **one newline typed**:
/// nothing else inserted, nothing else removed, and the caret left
/// standing just past it.
///
/// This is what the continuation is hung on, on both platforms, instead of
/// a key handler. A keystroke can be intercepted, but an IME's commit
/// cannot be read off one — so the editors watch for the transition the
/// keyboard actually produced and never see the key at all. A pasted
/// newline is that same transition and continues the list; a multi-line
/// paste is not one and is left exactly as it was pasted.
fun insertedNewline(
    before: CharSequence,
    selectionStart: Int,
    selectionEnd: Int,
    after: CharSequence,
    caret: Int,
): Boolean {
    val from = minOf(selectionStart, selectionEnd).coerceIn(0, before.length)
    val to = maxOf(selectionStart, selectionEnd).coerceIn(from, before.length)
    if (caret != from + 1 || caret > after.length) return false
    if (after.length != before.length - (to - from) + 1) return false
    if (after[caret - 1] != '\n') return false
    return before.regionMatches(0, after, 0, from) &&
        before.regionMatches(to, after, caret, before.length - to)
}

/// `line.trim().startsWith("```")`, asked of a range rather than of a
/// string, so walking a document costs no allocation per line.
private fun fenceDelimiter(text: CharSequence, start: Int, end: Int): Boolean {
    var index = start
    while (index < end && text[index].isWhitespace()) index++
    return end - index >= 3 && text[index] == '`' && text[index + 1] == '`' && text[index + 2] == '`'
}

private const val MARKER_LENGTH = 3
private const val MARKER_GROUP = 4
private const val UNCHECKED_GLYPH = "☐" // BALLOT BOX
private const val CHECKED_GLYPH = "☑" // BALLOT BOX WITH CHECK

/// A task marker directly after a bullet, optionally indented. `\d+.`
/// ordered lists are deliberately out of scope for E3.
private val TASK_MARKER = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]])""")

/// The bullet [TASK_MARKER] stands on, without the marker: the same three
/// groups, so a plain list item and a task item are the same shape read to
/// different depths. `\d+.` ordered lists are out of scope here too.
private val LIST_ITEM = Regex("""^([ \t]*)([-*+])([ \t]+)""")

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
///
/// A **collapsed block** is the same thing one scope up: it carries the
/// registration's id and the fence it swallowed instead of a line
/// recogniser, so one table, one mapping and one styling pass serve both
/// kinds. It bears no [EditorDecoration], which is exactly why it can
/// never be activated by a click.
private class PlacedDecoration(
    val start: Int,
    val sourceLength: Int,
    val replacement: String,
    val role: DecorationRole,
    val decoration: EditorDecoration?,
    val lineRange: IntRange,
    val blockId: String? = null,
    val fence: FencedBlock? = null,
) {
    fun hit(): DecorationHit? =
        decoration?.let { DecorationHit(it, start until start + sourceLength, lineRange) }
}

/// Composition, and the whole of what § 5 reserves to the shell: the caret's
/// line is filtered out before any registration is consulted, fences gate
/// the registrations that asked to be gated, and overlapping claims go to
/// the first registration in declaration order. The result is sorted by
/// start and non-overlapping, which is what every stage after it assumes.
private fun decorate(
    source: String,
    caretLine: Int?,
    decorations: List<EditorDecoration>,
    blocks: List<EditorBlockDecoration> = emptyList(),
): List<PlacedDecoration> {
    val collapsed = collapse(source, caretLine, blocks)
    if (decorations.isEmpty()) return collapsed
    val hidden = collapsed.mapNotNull { it.fence?.lines }
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
        // A collapsed block's lines are not in the transformed text at
        // all: the block replaced them whole, so nothing may also claim
        // ground inside one.
        if (hidden.any { index in it }) continue

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
    // Blocks and lines are disjoint by construction — a collapsed block's
    // lines were skipped above — so merging by start is all the table's
    // sorted, non-overlapping contract needs.
    return if (collapsed.isEmpty()) placed else (collapsed + placed).sortedBy { it.start }
}

/// The block-scoped half of composition, and the same three rules: the
/// caret's **block** is filtered out before any registration is asked, the
/// first registration to claim a fence takes it, and a claim reserves
/// lines rather than writing anything. `caretLine == null` — an unfocused
/// buffer — collapses every claimed block, so a note opens showing its
/// blocks exactly as it opens showing its checkboxes.
private fun collapse(
    source: String,
    caretLine: Int?,
    blocks: List<EditorBlockDecoration>,
): List<PlacedDecoration> {
    if (blocks.isEmpty()) return emptyList()
    val placed = mutableListOf<PlacedDecoration>()
    for (fence in fencedBlocks(source)) {
        if (caretLine != null && caretLine in fence.lines) continue
        for (block in blocks) {
            val lines = block.claim(fence.info, fence.body) ?: continue
            placed += PlacedDecoration(
                start = fence.range.first,
                sourceLength = fence.range.last + 1 - fence.range.first,
                // The placeholder: as many blank lines as were claimed, so
                // the box the fence gave up is exactly the box reserved.
                replacement = "\n".repeat(lines.coerceAtLeast(1) - 1),
                role = DecorationRole.NONE,
                decoration = null,
                lineRange = fence.range,
                blockId = block.id,
                fence = fence,
            )
            break
        }
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

private fun lineStartFor(text: CharSequence, offset: Int): Int {
    var index = offset
    while (index > 0 && text[index - 1] != '\n') index--
    return index
}

private fun lineEndFor(text: CharSequence, offset: Int): Int {
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
