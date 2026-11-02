// A slice's placeholders are as tall as the text they stand for (ADR 0032
// stage 4). The text is measured a chunk at a time, each between two lines
// of context; added up, the chunks must come to what the whole note
// measures, or scrolling past a slice's edge would jump.

package org.gilune.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteSliceTest {
    private val palette = MarkdownPalette(
        muted = Color.Gray, marker = Color.Blue, heading = Color.Black,
        code = Color.Blue, codeBackground = Color.LightGray, link = Color.Blue,
    )
    private val density = Density(1.25f, 1f)
    private val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr, 0)
    private val style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)

    private val note = (1..1_200).joinToString("\n") { i ->
        when (i % 6) {
            0 -> "## Section $i"
            1 -> "- [ ] task number $i due:2026-11-02 with some words to wrap across the line, and then some more of them"
            2 -> "Paragraph $i with **bold**, *italic*, a [[link]] and `code` to give the renderer some text to lay out."
            3 -> "> quoted line $i"
            4 -> "- bullet $i under the paragraph"
            else -> ""
        }
    }

    private fun transform(text: String) =
        MarkdownEditTransformation(palette, null, builtInDecorations()).filter(AnnotatedString(text)).text

    // Each chunk's height inside the whole note — from its first line's top
    // to the next chunk's — is what it measures between its context lines,
    // to within the pixel each edge rounds to — the first chunk too, which is
    // measured with nothing above it, as it sits in the note.
    @Test
    fun eachChunkMeasuresWithinAPixelOfItsHeightInTheWholeNote() {
        val shown = MarkdownEditTransformation(palette, null, builtInDecorations()).filter(AnnotatedString(note))
        for (width in listOf(420, 900)) {
            val whole = measurer.measure(shown.text, style, constraints = Constraints(maxWidth = width))
            fun top(offset: Int) = whole.getLineTop(whole.getLineForOffset(shown.offsetMapping.originalToTransformed(offset)))
            val heights = SliceHeights()
            val chunks = sliceChunks(note)
            assertTrue(chunks.size > 20)
            for ((index, chunk) in chunks.withIndex()) {
                val inNote = (if (chunk.end == note.length) whole.size.height.toFloat() else top(chunk.end)) - top(chunk.start)
                val measured = heights.heightOf(note, chunk, 0, ::transform) { body, first -> measureChunk(measurer, body, style, width, first) }
                assertTrue(kotlin.math.abs(inNote - measured) <= 1f, "width $width, chunk $index: $measured measured, $inNote in the note")
            }
        }
    }

    @Test
    fun aChunkIsMeasuredOnceAndAgainOnlyWhenItsTextOrConfigurationChanges() {
        val heights = SliceHeights()
        heights.configure(600)
        val chunks = sliceChunks(note)
        var measured = 0
        fun height(text: String, chunk: SliceChunk) =
            heights.heightOf(text, chunk, 0, ::transform) { body, first -> measured++; measureChunk(measurer, body, style, 600, first) }
        chunks.forEach { height(note, it) }
        assertEquals(chunks.size, measured)
        chunks.forEach { height(note, it) }
        assertEquals(chunks.size, measured, "nothing measured twice")
        // Typing in one chunk: only that chunk is new.
        val at = chunks[chunks.size / 2].start + 3
        val typed = note.substring(0, at) + "typed" + note.substring(at)
        sliceChunks(typed).forEach { height(typed, it) }
        // The typed line may also move a cut beside it: one or two new.
        assertTrue(measured - chunks.size in 1..2, "${measured - chunks.size} chunks measured again")
        val afterTyping = measured
        heights.configure(700)
        chunks.forEach { height(note, it) }
        assertEquals(afterTyping + chunks.size, measured, "a new width measures again")
    }
}
