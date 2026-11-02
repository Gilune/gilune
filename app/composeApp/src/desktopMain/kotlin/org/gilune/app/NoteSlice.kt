// The heights a slice's placeholders stand for (ADR 0032 stage 4). A long
// note is laid out a slice at a time; the text above and below is one
// invisible character each, as tall as that text would be at the field's
// width. Those heights are measured a chunk at a time (`sliceChunks`) and
// kept by the chunk's text, so typing inside the slice measures nothing:
// every chunk outside it is unchanged, and a chunk is measured again only
// when its own text, the width, the style or the registrations change.
//
// A chunk is measured between two lines of context, from its first line's
// top to the top of the line after it. Measured alone, a layout trims the
// room above its first line and below its last, and the pieces came out
// 3–11 px short at every boundary (spike, 2026-11-01). With context a chunk
// is within a pixel of its height inside the whole note — line tops are
// whole pixels, so each chunk's edges round — and the editor puts the line
// at the top of the view back where it was whenever the slice moves.

package org.gilune.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Constraints

internal class SliceHeights {
    private var configuration: List<Any?> = emptyList()
    private val heights = HashMap<String, Float>()

    /// Forgets every height once anything a height depends on changes.
    fun configure(vararg key: Any?) {
        val now = key.toList()
        if (now != configuration) {
            heights.clear()
            configuration = now
        }
    }

    /// The height of `chunk` of `text`, measured now if it never was.
    /// `measure` lays out a transformed chunk between its context lines.
    /// `salt` names what else a chunk holding a fence depends on — a filter
    /// block's rows decide its height — and is ignored for every other.
    fun heightOf(
        text: String,
        chunk: SliceChunk,
        salt: Int,
        transform: (String) -> AnnotatedString,
        measure: (AnnotatedString) -> Float,
    ): Float {
        val last = chunk.end == text.length
        // A chunk's own last line break separates it from the next and is
        // the context's to draw; the buffer's last chunk has no next.
        val end = if (!last && chunk.end > chunk.start && text[chunk.end - 1] == '\n') chunk.end - 1 else chunk.end
        val body = text.substring(chunk.start, end)
        val fenced = "```" in body
        val key = (if (last) "\u0000" else "") + (if (fenced) "$salt\u0000" else "") + body
        return heights.getOrPut(key) { measure(transform(body)) }
    }

    /// Drops what no chunk of the current text needs once old versions
    /// outnumber it — the versions of a chunk typed into before the slice
    /// moved off it.
    fun prune(chunks: Int) {
        if (heights.size > chunks * 3 + 64) heights.clear()
    }

    val size: Int get() = heights.size
}

/// A chunk's height at `width`: laid out between two one-line contexts,
/// from the top of its first line to the top of the line after it.
internal fun measureChunk(measurer: TextMeasurer, chunk: AnnotatedString, style: TextStyle, width: Int): Float {
    val text = buildAnnotatedString {
        append("x\n")
        append(chunk)
        append("\nx")
    }
    val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
    return layout.getLineTop(layout.lineCount - 1) - layout.getLineTop(1)
}
