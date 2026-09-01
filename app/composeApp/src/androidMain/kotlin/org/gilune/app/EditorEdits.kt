// The toolbar's arithmetic (ADR 0017 § 3), kept apart from any screen so
// every formatting action can be tested without a keyboard.
//
// § 3: "every formatting action is a composition over shared primitives …
// nothing writes bytes except through those". The primitive on the modern
// API is `TextFieldState.edit { replace(start, end, text) }`, and the
// desktop's `replaceRange` is the same maths over a `TextFieldValue` —
// MS1's port note names it as one of the helpers needing a second entry
// point. So the maths is ported rather than the wrapper: every function
// here answers **which exact range to replace, and with exactly what** —
// the same question `Acceptance` answers for the `/` menu — and
// [applyEdit] is the only thing that touches a buffer.
//
// Every edit is minimal on purpose. Replacing the whole document would
// work and would be wrong twice over: it would collapse the undo history
// into one entry per keystroke-sized action, and it would restart the
// IME's composing session on every button press.

package org.gilune.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/// One replacement, in source coordinates: `start until end` becomes
/// [replacement], and the caret or selection lands at [selection] in the
/// coordinates of the text *after* the write.
data class BufferEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val selection: TextRange,
)

/// The one write path the toolbar has. Nothing else in the editor writes
/// bytes on a button press.
fun TextFieldState.applyEdit(edit: BufferEdit?) {
    if (edit == null) return
    edit {
        replace(edit.start, edit.end, edit.replacement)
        selection = edit.selection
    }
}

/// A task marker directly after a bullet, optionally indented. Mirrors
/// `MarkdownEditing.kt`'s `TASK_MARKER`, which is file-private, exactly as
/// `Suggest.kt` and `Plugins.kt` mirror it — one definition of a task line
/// is the point of ADR 0015 § 1, so if it is ever published all four
/// become the import.
private val EDIT_TASK_LINE = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]])""")
private val EDIT_BULLET = Regex("""^([ \t]*)([-*+])([ \t]+)""")
private val EDIT_HEADING = Regex("""^(#{1,6})([ \t]+)""")
private val EDIT_QUOTE = Regex("""^([ \t]*)(>[ \t]?)""")

/// Bold, italic, strikethrough and code, on one rule: the marker is added
/// when it is absent and taken away when it is already there, so the
/// button is a toggle rather than a stutter.
///
/// With nothing selected the word under the caret is the target, which is
/// what a phone user means by tapping **B** mid-word. With no word either
/// — an empty line, or the caret on whitespace — the markers are written
/// empty and the caret lands between them, ready to type into.
fun emphasisEdit(text: String, selection: TextRange, marker: String): BufferEdit {
    val (from, to) = emphasisTarget(text, selection)
    val width = marker.length
    val wrappedOutside = from >= width &&
        to + width <= text.length &&
        text.regionMatches(from - width, marker, 0, width) &&
        text.regionMatches(to, marker, 0, width)
    if (wrappedOutside) {
        val inner = text.substring(from, to)
        return BufferEdit(from - width, to + width, inner, TextRange(from - width, to - width))
    }
    val inner = text.substring(from, to)
    val wrappedInside = inner.length >= width * 2 &&
        inner.startsWith(marker) &&
        inner.endsWith(marker)
    if (wrappedInside) {
        val stripped = inner.substring(width, inner.length - width)
        return BufferEdit(from, to, stripped, TextRange(from, from + stripped.length))
    }
    val written = marker + inner + marker
    return BufferEdit(from, to, written, TextRange(from + width, from + width + inner.length))
}

/// What **B** acts on: the selection when there is one, otherwise the word
/// the caret sits in or beside.
private fun emphasisTarget(text: String, selection: TextRange): Pair<Int, Int> {
    val from = selection.min.coerceIn(0, text.length)
    val to = selection.max.coerceIn(from, text.length)
    if (from != to) return from to to
    var start = from
    while (start > 0 && !text[start - 1].isWordBreak()) start--
    var end = from
    while (end < text.length && !text[end].isWordBreak()) end++
    return start to end
}

private fun Char.isWordBreak(): Boolean = isWhitespace() || this == '\n'

/// The checkbox button (§ 3). A line that is already a task toggles
/// through the shared [activateLine], so the tasks plugin's `done:` stamp
/// applies exactly as it does to a tap on the glyph and to the desktop.
/// A line that is not a task becomes one, keeping its indent and reusing
/// its bullet if it has one.
fun checkboxEdit(
    text: String,
    selection: TextRange,
    decorations: List<EditorDecoration> = builtInDecorations(),
): BufferEdit? {
    val caret = selection.min.coerceIn(0, text.length)
    val start = lineStartAt(text, caret)
    val end = lineEndAt(text, caret)
    val line = text.substring(start, end)
    if (EDIT_TASK_LINE.containsMatchIn(line)) {
        val rewritten = activateLine(line, decorations) ?: return null
        return BufferEdit(start, end, rewritten, shifted(selection, end, rewritten.length - line.length))
    }
    val bullet = EDIT_BULLET.find(line)
    val written = if (bullet != null) {
        line.substring(0, bullet.value.length) + "[ ] " + line.substring(bullet.value.length)
    } else {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        "$indent- [ ] " + line.substring(indent.length)
    }
    return BufferEdit(start, end, written, shifted(selection, end, written.length - line.length))
}

/// The list button: a bullet on, or off again. Acts on every line the
/// selection touches, so turning three lines into a list is one tap.
fun bulletEdit(text: String, selection: TextRange): BufferEdit =
    perLine(text, selection) { line ->
        val bullet = EDIT_BULLET.find(line)
        if (bullet != null) {
            line.substring(0, bullet.groupValues[1].length) + line.substring(bullet.value.length)
        } else {
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            "$indent- " + line.substring(indent.length)
        }
    }

/// Blockquote, on the same per-line toggle.
fun quoteEdit(text: String, selection: TextRange): BufferEdit =
    perLine(text, selection) { line ->
        val quote = EDIT_QUOTE.find(line)
        if (quote != null) {
            line.substring(0, quote.groupValues[1].length) + line.substring(quote.value.length)
        } else {
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            "$indent> " + line.substring(indent.length)
        }
    }

/// Headings. Tapping the level a line already has takes it off, so the
/// panel's H1/H2/H3 are three toggles rather than a one-way trip.
fun headingEdit(text: String, selection: TextRange, level: Int): BufferEdit =
    perLine(text, selection) { line ->
        val hashes = "#".repeat(level.coerceIn(1, 6))
        val existing = EDIT_HEADING.find(line)
        val body = if (existing == null) line else line.substring(existing.value.length)
        if (existing != null && existing.groupValues[1] == hashes) body else "$hashes $body"
    }

/// Indent and outdent, two spaces at a time. Outdenting a line with no
/// indent leaves it exactly as it was rather than eating its first
/// character.
fun indentEdit(text: String, selection: TextRange, outdent: Boolean): BufferEdit =
    perLine(text, selection) { line ->
        if (!outdent) {
            INDENT + line
        } else {
            when {
                line.startsWith(INDENT) -> line.substring(INDENT.length)
                line.startsWith("\t") -> line.substring(1)
                line.startsWith(" ") -> line.substring(1)
                else -> line
            }
        }
    }

/// Move the caret's line up or down, swapping it with its neighbour. The
/// selection travels with the line, which is what makes the button
/// repeatable: tapping it four times moves the line four rows.
fun moveLineEdit(text: String, selection: TextRange, up: Boolean): BufferEdit? {
    val caret = selection.min.coerceIn(0, text.length)
    val start = lineStartAt(text, caret)
    val end = lineEndAt(text, caret)
    if (up) {
        if (start == 0) return null
        val previousStart = lineStartAt(text, start - 1)
        val line = text.substring(start, end)
        val previous = text.substring(previousStart, start - 1)
        val written = "$line\n$previous"
        return BufferEdit(previousStart, end, written, shiftedBy(selection, previousStart - start))
    }
    if (end >= text.length) return null
    val nextEnd = lineEndAt(text, end + 1)
    val line = text.substring(start, end)
    val next = text.substring(end + 1, nextEnd)
    val written = "$next\n$line"
    return BufferEdit(start, nextEnd, written, shiftedBy(selection, next.length + 1))
}

/// A Markdown link. With a selection it becomes the label and the caret
/// lands in the empty destination; with none, both halves are empty and
/// the caret lands in the label, where a phone user starts typing.
fun linkEdit(text: String, selection: TextRange): BufferEdit {
    val from = selection.min.coerceIn(0, text.length)
    val to = selection.max.coerceIn(from, text.length)
    val label = text.substring(from, to)
    val written = "[$label]()"
    val caret = if (label.isEmpty()) from + 1 else from + written.length - 1
    return BufferEdit(from, to, written, TextRange(caret))
}

private const val INDENT = "  "

/// Rewrites every line the selection touches and reports one replacement
/// over the whole block, so a multi-line action is one undo step. The
/// selection is preserved where it can be — a caret keeps its column,
/// shifted by what was written before it — and otherwise spans the
/// rewritten block.
private fun perLine(text: String, selection: TextRange, rewrite: (String) -> String): BufferEdit {
    val from = selection.min.coerceIn(0, text.length)
    val to = selection.max.coerceIn(from, text.length)
    val start = lineStartAt(text, from)
    val end = lineEndAt(text, to)
    val lines = text.substring(start, end).split('\n')
    val written = lines.joinToString("\n") { rewrite(it) }
    if (selection.collapsed) {
        val firstDelta = rewrite(lines.first()).length - lines.first().length
        return BufferEdit(start, end, written, shifted(selection, end, firstDelta))
    }
    return BufferEdit(start, end, written, TextRange(start, start + written.length))
}

/// Keeps a caret where it was, moved by `delta` when it sits after the
/// text that changed length, and never out of the line it was on.
private fun shifted(selection: TextRange, lineEnd: Int, delta: Int): TextRange {
    val caret = (selection.min + delta).coerceAtLeast(0)
    return TextRange(caret.coerceAtMost(lineEnd + delta))
}

private fun shiftedBy(selection: TextRange, delta: Int): TextRange =
    TextRange((selection.min + delta).coerceAtLeast(0), (selection.max + delta).coerceAtLeast(0))

internal fun lineStartAt(text: String, offset: Int): Int {
    var index = offset.coerceIn(0, text.length)
    while (index > 0 && text[index - 1] != '\n') index--
    return index
}

internal fun lineEndAt(text: String, offset: Int): Int {
    var index = offset.coerceIn(0, text.length)
    while (index < text.length && text[index] != '\n') index++
    return index
}
