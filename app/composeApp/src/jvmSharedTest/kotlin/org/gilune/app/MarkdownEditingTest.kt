// E2's one hard invariant: styling never changes the text, so the caret
// and the bytes cannot disagree (ADR 0012). E3 spends that invariant
// deliberately — off the caret's line a task marker becomes one glyph —
// and buys it back with an offset mapping, which is what most of this
// suite exists to pin down.

package org.gilune.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TransformedText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownEditingTest {
    private val palette = MarkdownPalette(
        muted = Color.Gray,
        marker = Color.Blue,
        heading = Color.Black,
        code = Color.Blue,
        codeBackground = Color.LightGray,
        link = Color.Blue,
    )

    private val unchecked = '☐'
    private val checked = '☑'

    // Two replaced markers (lines 0 and 3) either side of a raw caret line,
    // one of them indented behind a `*` bullet, plus a trailing newline.
    private val taskDoc = "- [ ] one\nnotes\n- [x] two\n  * [ ] three\n"
    private val taskDocCaretLine = 2

    private fun transform(source: String, caretLine: Int? = null): TransformedText =
        MarkdownEditTransformation(palette, caretLine).filter(AnnotatedString(source))

    @Test
    fun stylingNeverChangesTheText() {
        val source = """
            ---
            gilune-id: abc
            ---
            # Heading

            Some **bold** and *italic* and `code`.

            - a list item
            > a quote
        """.trimIndent()
        val styled = styleMarkdown(source, palette)
        assertEquals(source, styled.text, "the source is the document; styling only decorates it")
        assertEquals(
            source,
            transform(source).text.text,
            "with no caret line the transformation is styling only",
        )
    }

    @Test
    fun boldSpansLandOnTheBoldRange() {
        val source = "Some **bold** text"
        val styled = styleMarkdown(source, palette)
        val bold = styled.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertTrue(bold.isNotEmpty(), "a bold span exists")
        assertEquals("**bold**", source.substring(bold.first().start, bold.first().end))
    }

    @Test
    fun everySpanStaysInsideTheText() {
        val source = "# H\n**b** *i* `c` [l](u) ![[e.wav]] ~~s~~\n```\nx\n```"
        val styled = styleMarkdown(source, palette)
        for (span in styled.spanStyles) {
            assertTrue(span.start in 0..source.length && span.end in 0..source.length && span.start <= span.end)
        }
    }

    @Test
    fun frontMatterIsDimmedNotHidden() {
        val source = "---\ngilune-id: abc\n---\nbody"
        val styled = styleMarkdown(source, palette)
        assertEquals(source, styled.text)
        assertTrue(
            styled.spanStyles.any { it.item.color == palette.muted && it.start == 0 },
            "the fence line carries the muted style",
        )
    }

    @Test
    fun withoutACaretLineTheTextAndEveryOffsetAreUntouched() {
        val source = "- [ ] one\n- [x] two"
        val result = transform(source)
        assertEquals(source, result.text.text, "the default constructor is still E2 behaviour")
        for (offset in 0..source.length) {
            assertEquals(offset, result.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, result.offsetMapping.transformedToOriginal(offset))
        }
    }

    @Test
    fun markersOffTheCaretLineRenderAsGlyphsAndTheCaretLineStaysRaw() {
        val source = "- [ ] one\n- [x] two"
        assertEquals("- [ ] one\n- $checked two", transform(source, caretLine = 0).text.text)
        assertEquals("- $unchecked one\n- [x] two", transform(source, caretLine = 1).text.text)
    }

    @Test
    fun severalMarkersOnSeveralLinesEachCostTwoCharacters() {
        val result = transform(taskDoc, caretLine = taskDocCaretLine)
        assertEquals("- $unchecked one\nnotes\n- [x] two\n  * $unchecked three\n", result.text.text)
        assertEquals(taskDoc.length - 4, result.text.text.length, "two markers, two characters saved each")
    }

    @Test
    fun aCaretLineHoldingTheOnlyMarkerLeavesEveryOffsetIdentical() {
        val source = "- [ ] only"
        val result = transform(source, caretLine = 0)
        assertEquals(source, result.text.text)
        for (offset in 0..source.length) {
            assertEquals(offset, result.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, result.offsetMapping.transformedToOriginal(offset))
        }
    }

    @Test
    fun everyOriginalOffsetMapsMonotonicallyIntoTransformedBounds() {
        val result = transform(taskDoc, caretLine = taskDocCaretLine)
        val transformedLength = result.text.text.length
        var previous = 0
        for (offset in 0..taskDoc.length) {
            val mapped = result.offsetMapping.originalToTransformed(offset)
            assertTrue(mapped in 0..transformedLength, "original $offset mapped to $mapped, out of bounds")
            assertTrue(mapped >= previous, "original $offset went backwards: $previous then $mapped")
            previous = mapped
        }
        assertEquals(transformedLength, result.offsetMapping.originalToTransformed(taskDoc.length))
    }

    @Test
    fun everyTransformedOffsetMapsMonotonicallyIntoOriginalBounds() {
        val result = transform(taskDoc, caretLine = taskDocCaretLine)
        val transformedLength = result.text.text.length
        var previous = 0
        for (offset in 0..transformedLength) {
            val mapped = result.offsetMapping.transformedToOriginal(offset)
            assertTrue(mapped in 0..taskDoc.length, "transformed $offset mapped to $mapped, out of bounds")
            assertTrue(mapped >= previous, "transformed $offset went backwards: $previous then $mapped")
            previous = mapped
        }
        assertEquals(taskDoc.length, result.offsetMapping.transformedToOriginal(transformedLength))
    }

    @Test
    fun offsetsAtAMarkerBoundaryMapToTheGlyphEdges() {
        val result = transform(taskDoc, caretLine = taskDocCaretLine)
        val mapping = result.offsetMapping
        for (marker in listOf(taskDoc.indexOf("[ ]"), taskDoc.lastIndexOf("[ ]"))) {
            val glyph = mapping.originalToTransformed(marker)
            assertEquals(unchecked, result.text.text[glyph], "the marker start maps onto the glyph")
            assertEquals(glyph + 1, mapping.originalToTransformed(marker + 3), "the marker end maps past the glyph")
            assertEquals(marker, mapping.transformedToOriginal(glyph))
            assertEquals(marker + 3, mapping.transformedToOriginal(glyph + 1))
        }
    }

    @Test
    fun roundTripIsExactOutsideMarkersAndWithinOneCharacterInside() {
        val result = transform(taskDoc, caretLine = taskDocCaretLine)
        for (offset in 0..taskDoc.length) {
            val back = result.offsetMapping.transformedToOriginal(
                result.offsetMapping.originalToTransformed(offset),
            )
            val marker = checkboxMarkerAt(taskDoc, offset)
            val insideAGlyph = marker != null &&
                offset > marker.first &&
                caretLine(taskDoc, offset) != taskDocCaretLine
            if (insideAGlyph) {
                assertTrue(abs(back - offset) <= 1, "original $offset came back as $back, more than a character away")
            } else {
                assertEquals(offset, back, "original $offset outside a glyph must round-trip exactly")
            }
        }
    }

    @Test
    fun spansStayInsideTheTransformedTextWhenGlyphsMixWithOtherMarkup() {
        val source = "# Heading with `code`\n- [ ] **bold** task\n- [x] a `code` task\n- [ ] plain"
        val result = transform(source, caretLine = 3)
        val length = result.text.text.length
        assertTrue(length < source.length, "two markers became glyphs")
        for (span in result.text.spanStyles) {
            assertTrue(
                span.start in 0..length && span.end in 0..length && span.start <= span.end,
                "span ${span.start}..${span.end} escapes the transformed text of length $length",
            )
        }
    }

    @Test
    fun boldOnAGlyphLineIsStyledAtTransformedOffsets() {
        val source = "notes\n- [ ] **bold** task"
        val result = transform(source, caretLine = 0)
        val transformed = result.text.text
        assertEquals("notes\n- $unchecked **bold** task", transformed)
        assertTrue(
            result.text.spanStyles.any {
                it.item.fontWeight == FontWeight.Bold && transformed.substring(it.start, it.end) == "**bold**"
            },
            "the bold span is measured against the shortened line",
        )
    }

    @Test
    fun nonAsciiBeforeAMarkerKeepsUtf16OffsetsAligned() {
        // Offsets are UTF-16 code units: an accent is one, an emoji is two.
        val source = "café 🎉 notes\n- [ ] task\n- [x] done"
        val result = transform(source, caretLine = 2)
        val marker = source.indexOf("[ ]")
        assertEquals("café 🎉 notes\n- $unchecked task\n- [x] done", result.text.text)
        assertEquals(marker until marker + 3, checkboxMarkerAt(source, marker))
        assertEquals(1, caretLine(source, marker), "the surrogate pair does not shift the line count")
        for (offset in 0..marker) {
            assertEquals(offset, result.offsetMapping.originalToTransformed(offset), "offset $offset is before any glyph")
        }
    }

    @Test
    fun caretLineIsZeroBasedAndClampsOffsetsOutOfRange() {
        val source = "one\ntwo\nthree\n"
        assertEquals(0, caretLine(source, 0))
        assertEquals(0, caretLine(source, 3), "the offset before a newline is still on its line")
        assertEquals(1, caretLine(source, 4))
        assertEquals(2, caretLine(source, source.length - 1))
        assertEquals(3, caretLine(source, source.length), "a trailing newline opens an empty last line")
        assertEquals(0, caretLine(source, -5), "a negative offset clamps to the first line")
        assertEquals(3, caretLine(source, 9999), "an offset past the end clamps to the last line")
        assertEquals(0, caretLine("", 0))
    }

    @Test
    fun checkboxMarkerAtMatchesFromAnyOfTheThreeMarkerCharacters() {
        val source = "notes\n- [ ] task"
        val marker = source.indexOf("[ ]")
        for (offset in marker until marker + 3) {
            assertEquals(marker until marker + 3, checkboxMarkerAt(source, offset), "offset $offset is on the marker")
        }
    }

    @Test
    fun checkboxMarkerAtAcceptsUpperCaseIndentationAndEveryBullet() {
        for (line in listOf("- [x] a", "* [ ] a", "+ [X] a", "  - [X] a", "\t* [x] a", "-   [ ] a")) {
            val marker = line.indexOf('[')
            assertEquals(marker until marker + 3, checkboxMarkerAt(line, marker), "`$line` opens a task line")
        }
    }

    @Test
    fun checkboxMarkerAtIgnoresBracketsThatFollowNoBullet() {
        for (line in listOf("a [ ] b", "- text [ ] more", "[ ] no bullet", "1. [ ] ordered", "> - [ ] quoted")) {
            for (offset in line.indices) {
                assertNull(checkboxMarkerAt(line, offset), "`$line` is not a task line (offset $offset)")
            }
        }
    }

    @Test
    fun checkboxMarkerAtReturnsNullOffTheMarkerAndOutOfBounds() {
        val source = "- [ ] task"
        assertNull(checkboxMarkerAt(source, 1), "the bullet is not the marker")
        assertNull(checkboxMarkerAt(source, 5), "the offset just past the marker is not on it")
        assertNull(checkboxMarkerAt(source, -1))
        assertNull(checkboxMarkerAt(source, source.length))
    }

    @Test
    fun toggleCheckboxTicksAnEmptyBox() {
        val source = "- [ ] task"
        assertEquals("- [x] task", toggleCheckbox(source, 2 until 5))
    }

    @Test
    fun toggleCheckboxClearsATickedBoxWhateverItsCase() {
        assertEquals("- [ ] task", toggleCheckbox("- [x] task", 2 until 5))
        assertEquals("- [ ] task", toggleCheckbox("- [X] task", 2 until 5))
    }

    @Test
    fun toggleCheckboxChangesNoOtherByte() {
        val source = "---\ngilune-id: abc\n---\n# H\n- [ ] one\n- [x] two\n\ntail\n"
        val marker = checkboxMarkerAt(source, source.indexOf("[x]"))!!
        val toggled = toggleCheckbox(source, marker)
        assertEquals(source.length, toggled.length)
        for (offset in source.indices) {
            if (offset != marker.first + 1) {
                assertEquals(source[offset], toggled[offset], "byte $offset must survive the toggle")
            }
        }
        assertEquals(' ', toggled[marker.first + 1])
        assertEquals(source, toggleCheckbox(toggled, marker), "toggling twice returns the original bytes")
    }

    @Test
    fun toggleCheckboxIgnoresARangeThatHoldsNoMarker() {
        val source = "- [ ] task"
        assertEquals(source, toggleCheckbox(source, 0 until 3), "the bullet is not a marker")
        assertEquals(source, toggleCheckbox(source, 2 until 4), "a two-character range is not a marker")
        assertEquals(source, toggleCheckbox(source, -3 until 0))
        assertEquals(source, toggleCheckbox(source, source.length until source.length + 3))
    }
}
