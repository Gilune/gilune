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
import org.gilune.plugin.sdk.TokenMenu
import org.gilune.plugin.sdk.TokenMenuItem

import java.time.LocalDate

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
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
fun fencedBlocks(source: String): List<FencedBlock> = fencedBlocks(source.split('\n'))

private fun fencedBlocks(lines: List<String>): List<FencedBlock> {
    val found = mutableListOf<FencedBlock>()
    val body = mutableListOf<String>()
    var openStart = -1
    var openLine = -1
    var info = ""
    var offset = 0
    for ((index, line) in lines.withIndex()) {
        val start = offset
        offset += line.length + 1
        val delimiter = line.isFenceDelimiter()
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

/// `line.trim().startsWith("```")` without the trimmed copy, which every
/// line of every pass used to make.
private fun String.isFenceDelimiter(): Boolean {
    var index = 0
    while (index < length && this[index].isWhitespace()) index++
    return startsWith("```", index)
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
    memo: LineMemo = LineMemo(),
    slice: EditorSlice? = null,
): List<CollapsedBlock> {
    if (blocks.isEmpty()) return emptyList()
    val placed = placements(source, caretLine, decorations, blocks, null, memo, slice)
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
///
/// `styleWindow` is the range of *transformed* offsets near the screen
/// (ADR 0032 stage 3): colours are drawn there only, and everything that
/// moves text — replacements, collapsed blocks, wrap-changing spans — stays
/// whole-document, so the offset mapping and every line's position are the
/// same whatever the window. Null styles everything.
///
/// The result is kept for the last text asked about: a field and the
/// editor's own geometry questions ask for the same text several times a
/// frame, and each answer used to re-parse the whole note. `memo` keeps
/// each line's answers across texts, so a keystroke re-reads only the
/// lines it changed; an editor passes one per open buffer.
class MarkdownEditTransformation(
    private val palette: MarkdownPalette,
    private val caretLine: Int? = null,
    private val decorations: List<EditorDecoration> = builtInDecorations(),
    private val blocks: List<EditorBlockDecoration> = emptyList(),
    private val fontScale: Float = 1f,
    private val plainPillLine: Int? = null,
    private val styleWindow: IntRange? = null,
    private val memo: LineMemo = LineMemo(),
    /// The part of a long buffer laid out (ADR 0032 stage 4); null lays out
    /// all of it. The style window is not used with a slice: the slice is
    /// already about what can be seen.
    private val slice: EditorSlice? = null,
) : VisualTransformation {
    @Volatile private var last: Pair<String, TransformedText>? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        last?.let { (seen, result) -> if (seen === source || seen == source) return result }
        return transform(source).also { last = source to it }
    }

    private fun transform(source: String): TransformedText {
        val cut = slice?.takeIf { it.cuts(source) }
        val placed = placements(source, caretLine, decorations, blocks, plainPillLine, memo, cut)
        if (placed.isEmpty()) {
            return TransformedText(styleMarkdown(source, palette, fontScale, styleWindow, memo), OffsetMapping.Identity)
        }
        if (cut != null) return slicedText(source, placed, cut)
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
        return TransformedText(styleDecorations(transformed, placed, starts, palette, fontScale, styleWindow, memo), mapping)
    }

    /// The slice laid out between its two placeholders. The middle is
    /// styled as a text of its own — it starts and ends on a line break
    /// outside any fence, so no rule reads across its edges — and each
    /// placeholder is a paragraph exactly as tall as the text it stands
    /// for. A paragraph boundary starts a new line, so the placeholders
    /// need no line breaks of their own.
    private fun slicedText(source: String, placed: List<PlacedDecoration>, cut: EditorSlice): TransformedText {
        val starts = transformedStarts(placed)
        val transformed = applyDecorations(source, placed)
        val table = placed.filter { it.replacement.length != it.sourceLength }
        val mapping = if (table.isEmpty()) OffsetMapping.Identity else DecorationOffsetMapping(table, source.length, transformed.length)
        val top = if (cut.start > 0) 1 else 0
        val bottom = if (cut.middleEnd(source.length) < source.length) 1 else 0
        val inner = placed.subList(top, placed.size - bottom)
        val innerStarts = starts.subList(top, starts.size - bottom).map { it - top }
        val middle = transformed.substring(top, transformed.length - bottom)
        val styled = styleDecorations(middle, inner, innerStarts, palette, fontScale, null, memo)
        val text = buildAnnotatedString {
            if (top == 1) withStyle(placeholderStyle(cut.above)) { append(SLICE_PLACEHOLDER) }
            append(styled)
            if (bottom == 1) withStyle(placeholderStyle(cut.below)) { append(SLICE_PLACEHOLDER) }
        }
        return TransformedText(text, mapping)
    }
}

/// The part of a long buffer the field lays out (ADR 0032 stage 4): whole
/// lines from `start`, a line start, to `tail` characters before the
/// buffer's end, also a line start (zero is the end itself). The text above
/// and below is laid out as one invisible character each, `above` and
/// `below` tall — what that text measures at the field's width.
///
/// The end is counted from the buffer's end so that typing inside the
/// slice leaves it valid: neither edge moves. A slice that no longer fits
/// the buffer cuts nothing, and the buffer is laid out whole.
data class EditorSlice(val start: Int, val tail: Int, val above: TextUnit, val below: TextUnit) {
    /// Where the laid-out middle ends: before the line break that ends the
    /// slice's last line, which the placeholder below absorbs.
    fun middleEnd(length: Int): Int = if (tail == 0) length else length - tail - 1

    fun contains(offset: Int, length: Int): Boolean = offset in start..middleEnd(length)

    internal fun cuts(source: String): Boolean {
        val end = source.length - tail
        if (start == 0 && tail == 0) return false
        if (start < 0 || tail < 0 || start > end) return false
        if (start > 0 && source[start - 1] != '\n') return false
        if (tail > 0 && source[end - 1] != '\n') return false
        return start <= middleEnd(source.length)
    }
}

/// What stands for the text outside a slice: nothing a reader can see.
const val SLICE_PLACEHOLDER = '​' // ZERO WIDTH SPACE

private fun placeholderStyle(height: TextUnit) = ParagraphStyle(
    lineHeight = height,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/// [decorate], then the slice: what lies outside it gives way to one
/// placeholder entry each side, in the same table every other replacement
/// uses, so every offset question — the field's, a click's, a menu's —
/// is answered against the text actually laid out.
private fun placements(
    source: String,
    caretLine: Int?,
    decorations: List<EditorDecoration>,
    blocks: List<EditorBlockDecoration>,
    plainPillLine: Int?,
    memo: LineMemo,
    slice: EditorSlice?,
): List<PlacedDecoration> {
    val placed = decorate(source, caretLine, decorations, blocks, plainPillLine, memo)
    if (slice == null || !slice.cuts(source)) return placed
    val middleEnd = slice.middleEnd(source.length)
    return buildList {
        if (slice.start > 0) add(slicePlaceholder(0, slice.start))
        placed.filterTo(this) { it.start >= slice.start && it.start + it.sourceLength <= middleEnd }
        if (middleEnd < source.length) add(slicePlaceholder(middleEnd, source.length - middleEnd))
    }
}

private fun slicePlaceholder(start: Int, length: Int) = PlacedDecoration(
    start = start,
    sourceLength = length,
    replacement = SLICE_PLACEHOLDER.toString(),
    role = DecorationRole.NONE,
    decoration = null,
    lineRange = start until start + length,
)

/// A run of whole lines, from one line start to the next run's (or the
/// buffer's end): the unit a slice is made of and measured in.
data class SliceChunk(val start: Int, val end: Int)

/// Below this many lines a note is laid out whole: it already meets the
/// memory target (ADR 0032), and a slice would only add moving parts.
const val SLICE_MIN_LINES = 400

/// A long buffer cut into chunks for the slice (ADR 0032 stage 4). A cut
/// falls before a line where a hash of that line and the one above lands
/// on one in [SLICE_CHUNK_LINES]: chunks average that many lines, and the
/// cuts depend only on the text around them, so typing in one chunk moves
/// no other chunk's edges and their measured heights stay good. A cut
/// never falls inside a fence, so no rule reads across one; a run of
/// [SLICE_CHUNK_MAX_LINES] lines with no cut is cut anyway.
fun sliceChunks(source: String): List<SliceChunk> {
    val chunks = mutableListOf<SliceChunk>()
    var chunkStart = 0
    var lines = 0
    var offset = 0
    var inFence = false
    var previous = 0
    var index = 0
    while (offset <= source.length) {
        val lineEnd = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
        val hash = lineHash(source, offset, lineEnd)
        if (index > 0 && !inFence && lines > 0 && offset > chunkStart &&
            (mix(previous * 31 + hash) % SLICE_CHUNK_LINES == 0 || lines >= SLICE_CHUNK_MAX_LINES)
        ) {
            chunks += SliceChunk(chunkStart, offset)
            chunkStart = offset
            lines = 0
        }
        if (fenceDelimiter(source, offset, lineEnd)) inFence = !inFence
        lines++
        previous = hash
        index++
        if (lineEnd == source.length) break
        offset = lineEnd + 1
    }
    chunks += SliceChunk(chunkStart, source.length)
    return chunks
}

const val SLICE_CHUNK_LINES = 32
const val SLICE_CHUNK_MAX_LINES = 160

/// The chunks a slice's edges name — the one starting at `start` to the
/// one ending `tail` before the buffer's end — or null when either edge is
/// not a chunk edge any more.
fun sliceRangeOf(chunks: List<SliceChunk>, start: Int, tail: Int, length: Int): IntRange? {
    if (start < 0) return null
    val first = chunks.binarySearchBy(start) { it.start }.takeIf { it >= 0 } ?: return null
    val last = chunks.binarySearchBy(length - tail) { it.end }.takeIf { it >= 0 } ?: return null
    return if (last >= first) first..last else null
}

/// The chunk holding `offset`; an offset past the end is the last chunk's.
fun chunkIndexOf(chunks: List<SliceChunk>, offset: Int): Int {
    var low = 0
    var high = chunks.lastIndex
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (chunks[mid].start <= offset) low = mid else high = mid - 1
    }
    return low
}

private fun lineHash(text: String, from: Int, to: Int): Int {
    var hash = 0
    for (index in from until to) hash = 31 * hash + text[index].code
    return hash
}

/// A finaliser, so a line's hash spreads into its low bits.
private fun mix(value: Int): Int {
    var h = value
    h = h xor (h ushr 16)
    h *= -0x7a143595
    h = h xor (h ushr 13)
    h *= -0x3d4d51cb
    h = h xor (h ushr 16)
    return h and Int.MAX_VALUE
}

/// The chunks a slice should hold (ADR 0032 stage 4), as an index range.
/// `tops` is each chunk's top in the field (n + 1 entries, the last the
/// bottom of the text) and the view is the scroll window in the same
/// coordinates. The current slice is kept while it still reaches half a
/// screen beyond the view each side and is not much larger than needed,
/// so scrolling moves it about once a screen; otherwise it is rebuilt to
/// reach a screen and a half each side. `caretChunk`, when given and near
/// the view, is kept inside with a chunk to spare either side, so a step
/// of the caret never leaves the laid-out text. A caret far from the view
/// — the page scrolled away from it — is not stretched to: that would lay
/// out everything between.
fun sliceChunksFor(current: IntRange?, tops: FloatArray, viewTop: Float, viewHeight: Float, caretChunk: Int?): IntRange {
    val count = tops.size - 1
    if (count <= 0) return 0..0
    fun covering(margin: Float): IntRange {
        val from = (viewTop - margin)
        val to = (viewTop + viewHeight + margin)
        var first = 0
        while (first < count - 1 && tops[first + 1] <= from) first++
        var last = first
        while (last < count - 1 && tops[last + 1] < to) last++
        return first..last
    }
    val near = covering(viewHeight * 1.5f)
    val caret = caretChunk?.takeIf { it in (near.first - 1)..(near.last + 1) }
    fun withCaret(range: IntRange): IntRange {
        caret ?: return range
        return minOf(range.first, (caret - 1).coerceAtLeast(0))..maxOf(range.last, (caret + 1).coerceAtMost(count - 1))
    }
    val needed = withCaret(covering(viewHeight / 2))
    val allowed = withCaret(covering(viewHeight * 3))
    if (current != null && current.first <= needed.first && current.last >= needed.last &&
        current.first >= allowed.first && current.last <= allowed.last && current.last < count
    ) {
        return current
    }
    return withCaret(near)
}

/// What each line of a note was found to hold, kept from one pass to the
/// next (ADR 0032). A keystroke changes a line or two, and every recogniser
/// and every Markdown style rule answers from a line's text alone — the
/// decoration contract makes recognisers pure over the string — so the
/// lines a keystroke did not touch answer from here instead of being read
/// again. Re-reading them was most of what a keystroke allocated: a regex
/// matcher per line per registration, for every line of the note.
///
/// Lines no pass asks about any more are swept, so the memo stays about
/// the size of the note it serves. A registration is remembered by identity:
/// a rebuilt registry is a new set of recognisers and starts afresh, as
/// does a new palette or font scale.
class LineMemo {
    private val recognised = java.util.IdentityHashMap<EditorDecoration, Generations<List<DecorationMatch>>>()
    private val askedThisPass = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<EditorDecoration, Boolean>())
    private val colouredLines = Generations<List<LineSpan>>()
    private val plainLines = Generations<List<LineSpan>>()
    private var styles: MarkdownStyles? = null

    internal fun recognise(decoration: EditorDecoration, line: String): List<DecorationMatch> {
        askedThisPass += decoration
        return recognised.getOrPut(decoration) { Generations() }.get(line, decoration.recognise)
    }

    internal fun endRecognising() {
        recognised.keys.retainAll(askedThisPass)
        recognised.values.forEach { it.turn() }
        askedThisPass.clear()
    }

    internal fun styles(palette: MarkdownPalette, fontScale: Float): MarkdownStyles {
        styles?.let { if (it.palette == palette && it.fontScale == fontScale) return it }
        colouredLines.clear()
        plainLines.clear()
        return MarkdownStyles(palette, fontScale).also { styles = it }
    }

    internal fun lineSpans(line: String, coloured: Boolean, styles: MarkdownStyles): List<LineSpan> =
        (if (coloured) colouredLines else plainLines).get(line) { markdownLineSpans(it, coloured, styles) }

    internal fun endStyling() {
        colouredLines.turn()
        plainLines.turn()
    }
}

/// Answers keyed by line, each stamped with the last pass that asked for
/// it. A hit allocates nothing. Lines no pass asks about any more — the
/// versions of a line being typed, one per keystroke — are swept once they
/// outnumber the lines the last pass asked about, so the map stays about
/// the size of the note without being rebuilt every keystroke.
internal class Generations<V : Any> {
    private class Entry<V>(val value: V, var pass: Int)

    private val entries = HashMap<String, Entry<V>>()
    private var pass = 0
    private var askedThisPass = 0

    fun get(line: String, compute: (String) -> V): V {
        val entry = entries[line]
        if (entry != null) {
            if (entry.pass != pass) {
                entry.pass = pass
                askedThisPass++
            }
            return entry.value
        }
        askedThisPass++
        return compute(line).also { entries[line] = Entry(it, pass) }
    }

    fun turn() {
        if (entries.size > askedThisPass * 2 + SWEEP_SLACK) entries.values.removeIf { it.pass != pass }
        pass++
        askedThisPass = 0
    }

    fun clear() {
        entries.clear()
        askedThisPass = 0
    }

    /// How many lines are held; for tests.
    val size: Int get() = entries.size

    private companion object {
        const val SWEEP_SLACK = 64
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
    slice: EditorSlice? = null,
): DecorationHit? {
    val placed = placements(text, caretLine, decorations, blocks, null, LineMemo(), slice)
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

/// A token whose registration carries a menu, where it sits in both texts:
/// its source range and line (to rewrite), and its half-open range in the
/// **transformed** text (to draw beside). ADR 0015 amendment 2026-10-31.
data class MenuToken(
    val decoration: EditorDecoration,
    val range: IntRange,
    val lineRange: IntRange,
    val transformedStart: Int,
    val transformedEnd: Int,
)

/// Every token a menu-bearing registration draws, in document order. Asked
/// of the same composition the field shows, so a token on the caret's line
/// is here too: pure styling stays there, and the line being written is the
/// one a person most often wants to move.
fun menuTokens(
    text: String,
    caretLine: Int?,
    decorations: List<EditorDecoration>,
    blocks: List<EditorBlockDecoration> = emptyList(),
    slice: EditorSlice? = null,
): List<MenuToken> {
    if (decorations.none { it.menu != null }) return emptyList()
    val placed = placements(text, caretLine, decorations, blocks, null, LineMemo(), slice)
    val starts = transformedStarts(placed)
    return placed.mapIndexedNotNull { index, span ->
        val decoration = span.decoration?.takeIf { it.menu != null } ?: return@mapIndexedNotNull null
        MenuToken(decoration, span.start until span.start + span.sourceLength, span.lineRange,
            starts[index], starts[index] + span.replacement.length)
    }
}

/// A menu over one line of a buffer, and the source range of that line.
data class LineMenu(val menu: TokenMenu, val lineRange: IntRange)

/// The menu for the line holding `offset`: the first registration whose
/// `menu` has something to offer there, in declaration order — the right-
/// click menu's question and the date shortcut's. A line inside a fence
/// asks only registrations that fire there, as their styling does.
fun lineMenuAt(
    text: CharSequence,
    offset: Int,
    decorations: List<EditorDecoration>,
    today: LocalDate,
): LineMenu? {
    if (decorations.none { it.menu != null }) return null
    val bounded = offset.coerceIn(0, text.length)
    val lineStart = lineStartFor(text, bounded)
    val lineEnd = lineEndFor(text, bounded)
    var start = 0
    var fenced = false
    while (start <= lineStart) {
        val end = lineEndFor(text, start)
        if (fenceDelimiter(text, start, end)) {
            fenced = start == lineStart || !fenced
        }
        if (end >= lineStart) break
        start = end + 1
    }
    val line = text.substring(lineStart, lineEnd)
    for (decoration in decorations) {
        val ask = decoration.menu ?: continue
        if (fenced && !decoration.firesInsideCodeFences) continue
        val menu = ask(line, today) ?: continue
        return LineMenu(menu, lineStart until lineEnd)
    }
    return null
}

/// The text with one menu item applied to the line it was offered for —
/// null when the line no longer reads as it did, so a menu left open while
/// something else changed the buffer writes nothing.
fun applyMenuItem(text: String, lineRange: IntRange, expectedLine: String, item: TokenMenuItem): String? {
    val from = lineRange.first
    val to = (lineRange.last + 1).coerceIn(from, text.length)
    if (from > text.length || text.substring(from, to) != expectedLine) return null
    // The range must still be the whole line: one that grew past it, or a
    // line joined onto it, has changed even where those characters agree.
    if ((from > 0 && text[from - 1] != '\n') || (to < text.length && text[to] != '\n')) return null
    return text.substring(0, from) + item.rewrite(expectedLine) + text.substring(to)
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
    ///
    /// On a numbered item the prefix carries the next number, and
    /// [renumber] moves the later items of the same list up by one so the
    /// list still counts (owner, 2026-10-27): each is a range of digits in
    /// the buffer as handed in — all of them below the caret's line — and
    /// the number to write there. Empty for bullets, and for a list
    /// numbered `1.` `1.` `1.` on purpose, which continues with its own
    /// number.
    data class Continue(
        val prefix: String,
        val renumber: List<Renumbering> = emptyList(),
    ) : ListContinuation

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

/// One later list number rewritten: the digits at [range] become [number].
data class Renumbering(val range: IntRange, val number: String)

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
/// A numbered item continues with the next number and renumbers the rest
/// of its list (see [orderedContinuation]). A list quoted inside a fence
/// is text: writing a marker into somebody's example would be the
/// rendering rule's mistake made with real bytes.
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
    ORDERED_ITEM.find(previous)?.let { return orderedContinuation(text, lineStart, previous, it, caretAfterNewline) }
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
    is ListContinuation.Continue -> {
        // The later numbers first, from the bottom up, so each range still
        // means what it meant; they all sit below the caret, so the prefix
        // goes in last and moves none of them.
        val written = StringBuilder(text)
        for (r in renumber.sortedByDescending { it.range.first }) written.replace(r.range.first, r.range.last + 1, r.number)
        written.insert(caret, prefix)
        ContinuedBuffer(written.toString(), caret + prefix.length)
    }
    is ListContinuation.Unwrap -> ContinuedBuffer(
        text.substring(0, range.first) + keep + text.substring(range.last + 1, text.length),
        range.first + keep.length,
    )
}

/// Return on a numbered item (owner, 2026-10-27: "why don't numbered
/// lists auto increment"). `previous` is the item's line, starting at
/// `lineStart`, and `item` its [ORDERED_ITEM] match.
///
/// - With text, the list goes on at the next number, behind the item's own
///   indentation, delimiter (`.` or `)`) and spacing. The later items of
///   the same list — same indentation and delimiter, past any nested lines
///   and blank lines between them — move up one each, but only while they
///   would otherwise repeat a number: the first that already counts past
///   the new one is left alone, and so is everything after it, so a list
///   numbered with a deliberate gap keeps its gap.
/// - A list written `1.` `1.` `1.` (CommonMark numbers it for you) is kept
///   that way: when the item beside it carries the same number, the new
///   item does too, and nothing is renumbered.
/// - Empty, it lets go like a bullet does; nested, it steps out to the
///   item it sits under, taking that item's next number — or its bullet,
///   when a numbered list is nested in a bulleted one.
private fun orderedContinuation(
    text: CharSequence,
    lineStart: Int,
    previous: String,
    item: MatchResult,
    caretAfterNewline: Int,
): ListContinuation {
    val indent = item.groupValues[1]
    val number = item.groupValues[2].toLong()
    val delimiter = item.groupValues[3]
    val carried = text.substring(caretAfterNewline, lineEndFor(text, caretAfterNewline))
    if (previous.substring(item.value.length).isBlank() && carried.isBlank()) {
        if (indent.isEmpty()) return ListContinuation.Unwrap(lineStart until caretAfterNewline)
        val outer = enclosingItemAbove(text, lineStart, indent.length)
        val keep = when {
            outer == null -> indent.dropLevel() + item.value.substring(indent.length)
            outer.ordered -> outer.indent + (outer.number + 1) + outer.delimiter + outer.spacing
            else -> outer.indent + outer.delimiter + outer.spacing
        }
        return ListContinuation.Unwrap(lineStart until caretAfterNewline, keep = keep)
    }

    val below = siblingsBelow(text, lineEndFor(text, caretAfterNewline), indent, delimiter)
    val above = siblingAbove(text, lineStart, indent, delimiter)
    val lazy = below.firstOrNull()?.second == number || above == number
    val next = if (lazy) number else number + 1
    val renumber = buildList {
        if (lazy) return@buildList
        var expected = next + 1
        for ((digits, value) in below) {
            if (value >= expected) break
            add(Renumbering(digits, expected.toString()))
            expected++
        }
    }
    return ListContinuation.Continue(indent + next + delimiter + item.groupValues[4], renumber)
}

/// A list item's opening, as [enclosingItemAbove] reads it.
private class ItemOpening(val indent: String, val ordered: Boolean, val number: Long, val delimiter: String, val spacing: String)

/// The nearest item above `lineStart` indented less than `width` — the
/// one a nested item sits under — or null when a less-indented line that
/// is not an item comes first.
private fun enclosingItemAbove(text: CharSequence, lineStart: Int, width: Int): ItemOpening? {
    var end = lineStart - 1
    while (end >= 0) {
        val start = lineStartFor(text, end)
        val line = text.substring(start, end)
        end = start - 1
        if (line.isBlank() || line.leadingWidth() >= width) continue
        ORDERED_ITEM.find(line)?.let {
            return ItemOpening(it.groupValues[1], true, it.groupValues[2].toLong(), it.groupValues[3], it.groupValues[4])
        }
        LIST_ITEM.find(line)?.let { return ItemOpening(it.groupValues[1], false, 0, it.groupValues[2], it.groupValues[3]) }
        return null
    }
    return null
}

/// The later items of a numbered list, from the line after `from` (a line
/// end): each one's digits and value, while lines stay in the list —
/// blank, indented deeper than `indent`, or a numbered item at exactly
/// `indent` with the same delimiter.
private fun siblingsBelow(text: CharSequence, from: Int, indent: String, delimiter: String): List<Pair<IntRange, Long>> = buildList {
    if (from >= text.length) return@buildList
    var start = from + 1
    while (true) {
        val end = lineEndFor(text, start)
        val line = text.substring(start, end)
        if (line.isNotBlank() && line.leadingWidth() <= indent.length) {
            val sibling = ORDERED_ITEM.find(line)
            if (sibling == null || sibling.groupValues[1] != indent || sibling.groupValues[3] != delimiter) break
            val digits = sibling.groups[2]!!.range
            add((start + digits.first..start + digits.last) to sibling.groupValues[2].toLong())
        }
        if (end >= text.length) break
        start = end + 1
    }
}

/// The number of the item just above `lineStart` in the same list, if
/// there is one — the same walk as [siblingsBelow], upwards, one item.
private fun siblingAbove(text: CharSequence, lineStart: Int, indent: String, delimiter: String): Long? {
    var end = lineStart - 1
    while (end >= 0) {
        val start = lineStartFor(text, end)
        val line = text.substring(start, end)
        end = start - 1
        if (line.isBlank() || line.leadingWidth() > indent.length) continue
        val sibling = ORDERED_ITEM.find(line) ?: return null
        return if (sibling.groupValues[1] == indent && sibling.groupValues[3] == delimiter) sibling.groupValues[2].toLong() else null
    }
    return null
}

private fun String.leadingWidth(): Int = takeWhile { it == ' ' || it == '\t' }.length

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

/// A numbered list item: its indentation, number (CommonMark allows up to
/// nine digits), delimiter and the spacing after it.
private val ORDERED_ITEM = Regex("""^([ \t]*)(\d{1,9})([.)])([ \t]+)""")

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
    memo: LineMemo = LineMemo(),
): List<PlacedDecoration> = synchronized(memo) {
    val lines = source.split('\n')
    val collapsed = collapse(lines, caretLine, blocks)
    if (decorations.isEmpty()) return collapsed
    val hidden = collapsed.mapNotNull { it.fence?.lines }
    val placed = mutableListOf<PlacedDecoration>()
    var offset = 0
    var inFence = false
    for ((index, line) in lines.withIndex()) {
        val start = offset
        offset += line.length + 1
        // Fence tracking as `styleMarkdown` does it, delimiters included in
        // the block so a recogniser cannot fire on the ``` line either.
        val delimiter = line.isFenceDelimiter()
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

        var claimed: MutableList<IntRange>? = null
        var onLine: MutableList<PlacedDecoration>? = null
        for (decoration in decorations) {
            if (fenced && !decoration.firesInsideCodeFences) continue
            if (onCaretLine && decoration.activate != null) continue
            for (match in memo.recognise(decoration, line)) {
                if (onCaretLine && match.render !is DecorationRender.StyleOnly) continue
                if (index == plainPillLine && match.render is DecorationRender.Pill) continue
                val range = match.range
                if (range.isEmpty() || range.first < 0 || range.last >= line.length) continue
                if (claimed?.any { range.first <= it.last && it.first <= range.last } == true) continue
                val taken = claimed ?: mutableListOf<IntRange>().also { claimed = it }
                taken += range
                val length = range.last - range.first + 1
                val render = match.render
                val replacement = when (render) {
                    is DecorationRender.Replace -> render.text
                    DecorationRender.StyleOnly, is DecorationRender.Pill -> line.substring(range.first, range.first + length)
                }
                val spans = onLine ?: mutableListOf<PlacedDecoration>().also { onLine = it }
                spans += PlacedDecoration(
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
        onLine?.let { placed += if (it.size == 1) it else it.sortedBy { span -> span.start } }
    }
    memo.endRecognising()
    // Blocks and lines are disjoint by construction — a collapsed block's
    // lines were skipped above — so merging by start is all the table's
    // sorted, non-overlapping contract needs.
    if (collapsed.isEmpty()) placed else (collapsed + placed).sortedBy { it.start }
}

/// The block-scoped half of composition, and the same three rules: the
/// caret's **block** is filtered out before any registration is asked, the
/// first registration to claim a fence takes it, and a claim reserves
/// lines rather than writing anything. `caretLine == null` — an unfocused
/// buffer — collapses every claimed block, so a note opens showing its
/// blocks exactly as it opens showing its checkboxes.
private fun collapse(
    lines: List<String>,
    caretLine: Int?,
    blocks: List<EditorBlockDecoration>,
): List<PlacedDecoration> {
    if (blocks.isEmpty()) return emptyList()
    val placed = mutableListOf<PlacedDecoration>()
    for (fence in fencedBlocks(lines)) {
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
    window: IntRange? = null,
    memo: LineMemo = LineMemo(),
): AnnotatedString {
    if (placed.none { it.role != DecorationRole.NONE || it.pillTag != null }) {
        return styleMarkdown(transformed, palette, fontScale, window, memo)
    }
    // One builder for the Markdown and the decorations: appending a styled
    // string to a second builder copied every span of the note again.
    return buildAnnotatedString {
        append(laidOut(transformed))
        styleMarkdownInto(this, transformed, palette, fontScale, window, memo)
        for ((index, span) in placed.withIndex()) {
            val from = starts[index]
            val to = from + span.replacement.length
            // Every role and pill is colour alone: off the window, nothing.
            if (window != null && (to < window.first || from > window.last)) continue
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

/// `window`, when given, is the range of offsets near the screen (ADR 0032
/// stage 3). Lines outside it keep only the spans that change how a line
/// wraps — font size, weight and slant — so off-screen text lays out
/// exactly as it will when it scrolls in; their colours are left off. Each
/// span is a native style object Skia rebuilds on every keystroke, and a
/// long note's colours are thousands of them.
fun styleMarkdown(
    source: String,
    palette: MarkdownPalette,
    fontScale: Float = 1f,
    window: IntRange? = null,
    memo: LineMemo = LineMemo(),
): AnnotatedString =
    buildAnnotatedString {
        append(laidOut(source))
        styleMarkdownInto(this, source, palette, fontScale, window, memo)
    }

/// The text a field lays out for `text`: each carriage return drawn as a
/// zero-width space. Same length, so no offset moves, and the same width —
/// nothing — so nothing on screen does. Skia lays a paragraph holding
/// carriage returns out in time that grows with the square of its length:
/// a 64 KB note with Windows line endings took two seconds a keystroke
/// where the same note with `\n` alone took twenty milliseconds (ADR 0032).
/// The buffer keeps its bytes; only what is drawn changes. Every rule reads
/// the text as it is, so styling cannot depend on the stand-in.
internal fun laidOut(text: String): String =
    if ('\r' in text) text.replace('\r', CARRIAGE_RETURN_DRAWN_AS) else text

internal const val CARRIAGE_RETURN_DRAWN_AS = '​' // ZERO WIDTH SPACE

private fun styleMarkdownInto(
    builder: AnnotatedString.Builder,
    source: String,
    palette: MarkdownPalette,
    fontScale: Float,
    window: IntRange?,
    memo: LineMemo,
) = synchronized(memo) {
    val styles = memo.styles(palette, fontScale)
    var offset = 0
    var inFrontMatter = false
    var frontMatterClosed = false
    var inFence = false
    for ((index, line) in source.split('\n').withIndex()) {
        val start = offset
        val end = start + line.length
        offset = end + 1
        val coloured = window == null || (end >= window.first && start <= window.last)

        if (index == 0 && line.trim() == "---") {
            inFrontMatter = true
            if (coloured) builder.addStyle(styles.muted, start, end)
            continue
        }
        if (inFrontMatter && !frontMatterClosed) {
            if (coloured) builder.addStyle(styles.muted, start, end)
            if (line.trim() == "---") {
                inFrontMatter = false
                frontMatterClosed = true
            }
            continue
        }
        if (line.isFenceDelimiter()) {
            inFence = !inFence
            if (coloured) builder.addStyle(styles.muted, start, end)
            continue
        }
        if (inFence) {
            if (coloured) builder.addStyle(styles.fenced, start, end)
            continue
        }
        for (span in memo.lineSpans(line, coloured, styles)) {
            builder.addStyle(span.style, start + span.start, start + span.end)
        }
    }
    memo.endStyling()
}

/// One style over a line, in the line's own offsets.
internal class LineSpan(val start: Int, val end: Int, val style: SpanStyle)

/// Every style [styleMarkdown] draws with, made once per palette and font
/// scale rather than once per line: a style is immutable, so every span of
/// a kind can share one.
internal class MarkdownStyles(val palette: MarkdownPalette, val fontScale: Float) {
    val muted = SpanStyle(color = palette.muted)
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val italic = SpanStyle(fontStyle = FontStyle.Italic)
    val fenced = SpanStyle(color = palette.code)
    val code = SpanStyle(color = palette.code, background = palette.codeBackground)
    val quote = SpanStyle(color = palette.muted, fontStyle = FontStyle.Italic)
    val listMarker = SpanStyle(color = palette.marker, fontWeight = FontWeight.Bold)
    val strike = SpanStyle(textDecoration = TextDecoration.LineThrough)
    val linkLabel = SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)
    val wiki = SpanStyle(color = palette.link)
    private val headingSizes = listOf(17.sp, 15.sp, 14.sp).map { it * fontScale }
    private val headings = headingSizes.map { SpanStyle(color = palette.heading, fontWeight = FontWeight.Bold, fontSize = it) }
    private val plainHeadings = headingSizes.map { SpanStyle(fontWeight = FontWeight.Bold, fontSize = it) }

    fun heading(level: Int, coloured: Boolean): SpanStyle =
        (if (coloured) headings else plainHeadings)[(level - 1).coerceAtMost(2)]
}

/// One body line's spans — everything but front matter and fences, which
/// depend on the lines around them. The order is the order spans are laid
/// down in, which decides overlaps. Off the style window only what changes
/// how the line wraps is kept: size, weight and slant.
private fun markdownLineSpans(line: String, coloured: Boolean, styles: MarkdownStyles): List<LineSpan> {
    var spans: MutableList<LineSpan>? = null
    fun add(style: SpanStyle, start: Int, end: Int) {
        (spans ?: mutableListOf<LineSpan>().also { spans = it }).add(LineSpan(start, end, style))
    }

    val heading = HEADING.find(line)
    if (heading != null) {
        val level = heading.groupValues[1].length
        add(styles.heading(level, coloured), 0, line.length)
        if (coloured) add(styles.muted, 0, level)
    }
    if (line.startsWith(">")) {
        add(if (coloured) styles.quote else styles.italic, 0, line.length)
    }
    LIST_MARKER.find(line)?.let { m ->
        add(if (coloured) styles.listMarker else styles.bold, m.groupValues[1].length, m.value.length)
    }

    if (coloured && '`' in line) {
        for (m in CODE_SPAN.findAll(line)) add(styles.code, m.range.first, m.range.last + 1)
    }
    if ('*' in line) {
        for (m in BOLD.findAll(line)) {
            add(styles.bold, m.range.first, m.range.last + 1)
            if (!coloured) continue
            add(styles.muted, m.range.first, m.range.first + 2)
            add(styles.muted, m.range.last - 1, m.range.last + 1)
        }
        for (m in ITALIC.findAll(line)) {
            add(styles.italic, m.range.first, m.range.last + 1)
            if (!coloured) continue
            add(styles.muted, m.range.first, m.range.first + 1)
            add(styles.muted, m.range.last, m.range.last + 1)
        }
    }
    if (!coloured) return spans ?: emptyList()

    if ('~' in line) {
        for (m in STRIKE.findAll(line)) add(styles.strike, m.range.first, m.range.last + 1)
    }
    if ('[' in line) {
        for (m in LINK.findAll(line)) {
            m.groups[1]?.let { add(styles.linkLabel, it.range.first, it.range.last + 1) }
            m.groups[2]?.let { add(styles.muted, it.range.first, it.range.last + 1) }
        }
        for (m in WIKI.findAll(line)) add(styles.wiki, m.range.first, m.range.last + 1)
    }
    return spans ?: emptyList()
}

/// The style window an editor should draw with (ADR 0032 stage 3), given
/// the one it has and the offsets now on screen. It reaches one screenful
/// beyond the visible text on each side and stays put until the visible
/// text leaves its inner half, so scrolling restyles the note about once a
/// screen rather than every frame. Offsets are the laid-out (transformed)
/// text's; `length` is that text's length.
fun nextStyleWindow(current: IntRange?, visible: IntRange, length: Int): IntRange {
    val span = (visible.last - visible.first).coerceAtLeast(1)
    if (current != null) {
        val topHeld = current.first == 0 || visible.first >= current.first + span / 2
        val bottomHeld = current.last >= length || visible.last <= current.last - span / 2
        if (topHeld && bottomHeld) return current
    }
    return (visible.first - span).coerceAtLeast(0)..(visible.last + span).coerceAtMost(length)
}

/// The window a note is first drawn with, before its layout can say what is
/// on screen: the top of the note, about two tall screens of text.
const val INITIAL_STYLE_WINDOW_CHARS = 8_000

/// The offset `lines` lines of `text` away from `offset` (negative is up),
/// keeping the column as far as the line allows; clamped at either end.
fun stepLines(text: String, offset: Int, lines: Int): Int {
    val bounded = offset.coerceIn(0, text.length)
    var start = text.lastIndexOf('\n', bounded - 1) + 1
    val column = bounded - start
    repeat(kotlin.math.abs(lines)) {
        start = if (lines > 0) {
            val next = text.indexOf('\n', start)
            if (next < 0) return text.length else next + 1
        } else {
            if (start == 0) return 0
            text.lastIndexOf('\n', start - 2) + 1
        }
    }
    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    return (start + column).coerceAtMost(end)
}
