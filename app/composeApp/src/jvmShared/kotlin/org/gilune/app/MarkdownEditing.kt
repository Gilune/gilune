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

import org.gilune.plugin.sdk.DecorationMatch
import org.gilune.plugin.sdk.DecorationRender
import org.gilune.plugin.sdk.DecorationRole
import org.gilune.plugin.sdk.EditorDecoration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
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
    /// A pill's tag words. Unspecified falls back to [marker].
    val pillTag: Color = Color.Unspecified,
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
///
/// `plainPillLine` is the phone's half of the caret rule: its glyphs render
/// on every line (ADR 0017 § 2), but a pill on the line being written still
/// gives way to plain text. The desktop needs nothing extra — its caret
/// line already keeps every pill back.
class MarkdownEditTransformation(
    private val palette: MarkdownPalette,
    private val caretLine: Int? = null,
    private val decorations: List<EditorDecoration> = builtInDecorations(),
    private val blocks: List<EditorBlockDecoration> = emptyList(),
    private val fontScale: Float = 1f,
    private val plainPillLine: Int? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val placed = decorate(source, caretLine, decorations, blocks, plainPillLine)
        if (placed.isEmpty()) {
            return TransformedText(styleMarkdown(source, palette, fontScale), OffsetMapping.Identity)
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
        return TransformedText(styleDecorations(transformed, placed, starts, palette, fontScale), mapping)
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

/// One pill in source coordinates: its range, how long its tag is, and its
/// mark item (`<registration>|<key>`, which colours it).
data class ReadPill(val range: IntRange, val tagLength: Int, val item: String)

/// Every pill the registrations claim in `source` — what a reading surface
/// needs to draw the same pills the editor does over text it renders
/// itself. Nothing is held back for a caret: a reader has none.
fun pillsIn(source: String, decorations: List<EditorDecoration>): List<ReadPill> =
    decorate(source, null, decorations).mapNotNull { span ->
        span.pillTag?.let { tag -> ReadPill(span.start until span.start + span.sourceLength, tag, span.pillItem) }
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
    ///
    /// `keep` is written where the range was. An empty **nested** item
    /// steps out a level instead of letting go (owner, 2026-10-21 — a
    /// child task, Return, Return is the next parent): the range is the
    /// item and the typed newline, and `keep` is the same empty item one
    /// level shallower, with the caret after it.
    data class Unwrap(val range: IntRange, val keep: String = "") : ListContinuation
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
        val indent = bullet.groupValues[1]
        if (indent.isNotEmpty()) {
            // Nested: out one level, the marker kept, ready to type.
            val emptyItem = previous.substring(indent.length, marker.value.length) + " ".takeIf { !marker.value.endsWith(" ") }.orEmpty()
            return ListContinuation.Unwrap(lineStart until caretAfterNewline, keep = indent.dropLevel() + emptyItem)
        }
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
        text.substring(0, range.first) + keep + text.substring(range.last + 1, text.length),
        range.first + keep.length,
    )
}

// ------------------------------------------------------------- nesting

/// One level of list nesting: two spaces, which nests an item under a
/// `- ` bullet in CommonMark and reads the same in Obsidian.
const val LIST_INDENT = "  "

/// What Tab or Shift+Tab does to a buffer (owner, 2026-10-21: subtasks).
data class IndentEdit(val text: String, val selectionStart: Int, val selectionEnd: Int)

/// Tab nests every list line the selection touches one level deeper, and
/// Shift+Tab lifts each one level out — prose lines among them are left
/// alone, and so is anything inside a code fence. With no list line
/// touched, Tab types one level of indentation at the caret (replacing a
/// selection) and Shift+Tab takes one level off the lines touched. The
/// selection moves with its text.
fun indentEdit(text: String, selectionStart: Int, selectionEnd: Int, outdent: Boolean): IndentEdit {
    val low = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val high = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    fun lineOf(offset: Int) = starts.indexOfLast { it <= offset }
    val first = lineOf(low)
    var last = lineOf(high)
    // A selection ending at the very start of a line does not reach into it.
    if (last > first && high == starts[last]) last--
    fun lineText(i: Int) = text.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] - 1 else text.length)

    val fenced = BooleanArray(starts.size)
    var inFence = false
    for (i in starts.indices) {
        val line = lineText(i)
        val delimiter = line.trim().startsWith("```")
        fenced[i] = inFence || delimiter
        if (delimiter) inFence = !inFence
    }
    val listLines = (first..last).filter { !fenced[it] && LIST_ITEM.containsMatchIn(lineText(it)) }
    if (listLines.isEmpty() && !outdent) {
        val written = text.substring(0, low) + LIST_INDENT + text.substring(high)
        return IndentEdit(written, low + LIST_INDENT.length, low + LIST_INDENT.length)
    }
    val targets = (if (listLines.isEmpty()) (first..last).toList() else listLines).toSet()

    // Per touched line, how many characters change at its start.
    val delta = IntArray(starts.size)
    for (i in targets) {
        delta[i] = if (outdent) -lineText(i).leadingLevelWidth() else LIST_INDENT.length
    }
    val written = buildString {
        for (i in starts.indices) {
            if (i > 0) append('\n')
            val line = lineText(i)
            when {
                delta[i] > 0 -> append(LIST_INDENT).append(line)
                delta[i] < 0 -> append(line.substring(-delta[i]))
                else -> append(line)
            }
        }
    }
    fun map(offset: Int): Int {
        val line = lineOf(offset)
        var shifted = offset
        for (i in 0 until line) shifted += delta[i]
        return when {
            delta[line] > 0 -> shifted + delta[line]
            delta[line] < 0 -> shifted - minOf(-delta[line], offset - starts[line])
            else -> shifted
        }
    }
    return IndentEdit(written, map(selectionStart.coerceIn(0, text.length)), map(selectionEnd.coerceIn(0, text.length)))
}

/// Shift+Return (owner, 2026-10-21): a new line inside the same item —
/// a note under a task rather than another task. On a list line the new
/// line is indented under the item's text (its own indentation plus one
/// level) with no marker; on an indented line that is not a list item — a
/// note already under one — it keeps that line's indentation, so a note
/// can run to several lines; anywhere else, and inside a code fence, it is
/// a plain line break. A selection is replaced, and the text after the
/// caret moves down with the break.
fun softBreak(text: String, selectionStart: Int, selectionEnd: Int): IndentEdit {
    val low = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val high = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val lineStart = lineStartFor(text, low)
    val line = text.substring(lineStart, lineEndFor(text, lineStart))
    var inFence = false
    var at = 0
    while (at < lineStart) {
        val end = lineEndFor(text, at)
        if (fenceDelimiter(text, at, end)) inFence = !inFence
        at = end + 1
    }
    val indent = when {
        inFence || line.trim().startsWith("```") -> ""
        LIST_ITEM.containsMatchIn(line) -> LIST_ITEM.find(line)!!.groupValues[1] + LIST_INDENT
        else -> line.takeWhile { it == ' ' || it == '\t' }
    }
    val inserted = "\n" + indent
    val written = text.substring(0, low) + inserted + text.substring(high)
    return IndentEdit(written, low + inserted.length, low + inserted.length)
}

/// How much one level out takes from a line's start: a tab, or up to two
/// spaces.
private fun String.leadingLevelWidth(): Int = when {
    startsWith("\t") -> 1
    else -> takeWhile { it == ' ' }.length.coerceAtMost(LIST_INDENT.length)
}

/// An indentation one level shallower.
private fun String.dropLevel(): String = substring(leadingLevelWidth())

// ------------------------------------------------------- task shortcut

/// One line of a buffer rewritten: [before] at [start] becomes [after].
/// [activated] says the rewrite went through the activation chain — a tick
/// or untick, which is an action and is written at once, as a click is —
/// rather than a marker typed onto the line, which then goes in at column
/// [insertAt].
data class TaskLineEdit(
    val start: Int,
    val before: String,
    val after: String,
    val activated: Boolean,
    val insertAt: Int = 0,
) {
    fun applyTo(text: CharSequence): String =
        text.substring(0, start) + after + text.substring(start + before.length, text.length)

    /// Where an offset in the old buffer stands in the new one, keeping its
    /// place relative to the text: ahead of the line it is untouched, past
    /// it it moves by the line's change in length, and on it it follows
    /// the characters it sat beside.
    fun map(offset: Int): Int {
        val end = start + before.length
        val delta = after.length - before.length
        return when {
            offset < start -> offset
            offset > end -> offset + delta
            // A typed marker goes in ahead of the text, so a caret at the
            // insertion point stays with the text and lands behind it.
            !activated -> if (offset - start >= insertAt) offset + delta else offset
            else -> start + followActivation(offset - start)
        }
    }

    /// The chain flips the marker in place (same length) and then stamps or
    /// strips one token somewhere after it. Past the marker the two lines
    /// differ in one region with untouched text either side; a column
    /// before that region stays — so a caret at the end of the words stays
    /// there while `done:` is appended — and one after it moves with it.
    private fun followActivation(column: Int): Int {
        val marker = TASK_MARKER.find(before)?.range?.last?.plus(1) ?: 0
        var prefix = minOf(marker, before.length, after.length)
        while (prefix < before.length && prefix < after.length && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < before.length - prefix && suffix < after.length - prefix &&
            before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
        ) suffix++
        return when {
            column <= prefix -> column
            column >= before.length - suffix -> column + after.length - before.length
            else -> minOf(column, after.length - suffix)
        }
    }
}

/// The task shortcut (Obsidian's "toggle checkbox status", Ctrl+L by
/// default) asked of the line holding `caret`:
///
/// - a task ticks or unticks through [activateLine] with the editor's own
///   registrations, so `done:` is stamped or taken away exactly as a click
///   does it;
/// - a bullet (`-`, `*`, `+`) gains a marker after it and keeps its bullet;
/// - any other line — an empty one included — becomes `- [ ] ` plus its
///   text, behind its own indentation.
///
/// Null inside a fenced block (delimiters counted in, as [listContinuation]
/// counts them: writing a marker into somebody's example would be real
/// bytes in the wrong place) and on an ordered-list item, which is out of
/// scope for the checkbox exactly as [TASK_MARKER] says — turning `1. milk`
/// into `- [ ] 1. milk` would wreck the numbering, so it is left alone.
fun taskLineShortcut(
    text: CharSequence,
    caret: Int,
    decorations: List<EditorDecoration> = builtInDecorations(),
): TaskLineEdit? {
    val bounded = caret.coerceIn(0, text.length)
    val lineStart = lineStartFor(text, bounded)
    val lineEnd = lineEndFor(text, bounded)

    var start = 0
    var inFence = false
    while (start <= lineStart) {
        val end = lineEndFor(text, start)
        if (fenceDelimiter(text, start, end)) {
            if (start == lineStart) return null
            inFence = !inFence
        }
        if (end >= lineStart) break
        start = end + 1
    }
    if (inFence) return null

    val line = text.substring(lineStart, lineEnd)
    if (TASK_MARKER.containsMatchIn(line)) {
        val activated = activateLine(line, decorations) ?: return null
        return TaskLineEdit(lineStart, line, activated, activated = true)
    }
    val bullet = LIST_ITEM.find(line)
    if (bullet != null) {
        val at = bullet.value.length
        return TaskLineEdit(lineStart, line, line.substring(0, at) + "[ ] " + line.substring(at), activated = false, insertAt = at)
    }
    if (ORDERED_ITEM.containsMatchIn(line)) return null
    val indent = line.takeWhile { it == ' ' || it == '\t' }
    return TaskLineEdit(lineStart, line, indent + "- [ ] " + line.substring(indent.length), activated = false, insertAt = indent.length)
}

private val ORDERED_ITEM = Regex("""^[ \t]*\d+[.)][ \t]""")

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
    /// A pill's tag length; null for every other kind of span.
    val pillTag: Int? = null,
    /// A pill's mark item, `<registration>|<key>`, which colours it.
    val pillItem: String = "",
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
    plainPillLine: Int? = null,
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
        // The caret's line shows its raw Markdown: nothing is replaced and
        // nothing answers a click there. Pure styling stays — a mention or
        // a due date keeps its colour while the line is being written, since
        // colour hides no byte (owner, 2026-10-19).
        val onCaretLine = index == caretLine
        // A collapsed block's lines are not in the transformed text at
        // all: the block replaced them whole, so nothing may also claim
        // ground inside one.
        if (hidden.any { index in it }) continue

        val claimed = mutableListOf<IntRange>()
        val onLine = mutableListOf<PlacedDecoration>()
        for (decoration in decorations) {
            if (fenced && !decoration.firesInsideCodeFences) continue
            if (onCaretLine && decoration.activate != null) continue
            for (match in decoration.recognise(line)) {
                if (onCaretLine && match.render !is DecorationRender.StyleOnly) continue
                if (index == plainPillLine && match.render is DecorationRender.Pill) continue
                val range = match.range
                if (range.isEmpty() || range.first < 0 || range.last >= line.length) continue
                if (claimed.any { range.first <= it.last && it.first <= range.last }) continue
                claimed += range
                val length = range.last - range.first + 1
                val render = match.render
                val replacement = when (render) {
                    is DecorationRender.Replace -> render.text
                    DecorationRender.StyleOnly, is DecorationRender.Pill -> line.substring(range.first, range.first + length)
                }
                onLine += PlacedDecoration(
                    start = start + range.first,
                    sourceLength = length,
                    replacement = replacement,
                    role = decoration.role,
                    decoration = decoration,
                    lineRange = start until start + line.length,
                    // A tag that is the whole range, or none of it, is no
                    // pill: it styles as its role does and nothing more.
                    pillTag = (render as? DecorationRender.Pill)?.tagLength?.takeIf { it in 1 until length },
                    pillItem = (render as? DecorationRender.Pill)?.let { pillItem(decoration.id, it.key) }.orEmpty(),
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
    fontScale: Float,
): AnnotatedString {
    val styled = styleMarkdown(transformed, palette, fontScale)
    if (placed.none { it.role != DecorationRole.NONE || it.pillTag != null }) return styled
    return buildAnnotatedString {
        append(styled)
        for ((index, span) in placed.withIndex()) {
            val from = starts[index]
            val to = from + span.replacement.length
            val tag = span.pillTag
            if (tag != null) {
                markPill(from, from + tag, to, palette.pillTag.takeOrElse { palette.marker }, palette.link, span.pillItem)
                continue
            }
            val style = span.role.spanStyle(palette) ?: continue
            addStyle(style, from, to)
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

fun styleMarkdown(source: String, palette: MarkdownPalette, fontScale: Float = 1f): AnnotatedString =
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
                    SpanStyle(color = palette.heading, fontWeight = FontWeight.Bold, fontSize = size * fontScale),
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
