// E2's one hard invariant: styling never changes the text, so the caret
// and the bytes cannot disagree (ADR 0012). E3 spends that invariant
// deliberately — off the caret's line a task marker becomes one glyph —
// and buys it back with an offset mapping, which is what most of this
// suite exists to pin down.

package org.gilune.app

import org.gilune.plugin.sdk.DecorationMatch
import org.gilune.plugin.sdk.DecorationRender
import org.gilune.plugin.sdk.DecorationRole
import org.gilune.plugin.sdk.EditorDecoration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun withoutACaretEveryMarkerRendersAsAGlyph() {
        // No caret line means no caret at all — an unfocused, just-opened
        // buffer — and a note must open showing its checkboxes, ready to
        // be ticked (owner report, 2026-08-31). The raw-line exception
        // belongs to a caret someone can see.
        val source = "- [ ] one\n- [x] two"
        assertEquals("- $unchecked one\n- $checked two", transform(source).text.text)
        val result = transform(source)
        val back = result.offsetMapping.transformedToOriginal(
            result.offsetMapping.originalToTransformed(source.length),
        )
        assertEquals(source.length, back, "the document's end still round-trips exactly")
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

    // ---------------------------------------------------------------
    // The `editor.decorations` seam (ADR 0015 § 5). Everything above
    // this line is the contract the refactor had to preserve: the
    // checkbox behaves identically now that it arrives as the shell's
    // own first registration rather than by name.
    // ---------------------------------------------------------------

    private fun transform(source: String, caretLine: Int, decorations: List<EditorDecoration>): TransformedText =
        MarkdownEditTransformation(palette, caretLine, decorations).filter(AnnotatedString(source))

    /// A test registration replacing every literal occurrence of `pattern`.
    private fun replacing(
        id: String,
        pattern: String,
        replacement: String,
        role: DecorationRole = DecorationRole.NONE,
        activate: ((String) -> String)? = null,
    ) = EditorDecoration(
        id = id,
        role = role,
        activate = activate,
        recognise = { line ->
            Regex(Regex.escape(pattern)).findAll(line)
                .map { DecorationMatch(it.range.first until it.range.last + 1, DecorationRender.Replace(replacement)) }
                .toList()
        },
    )

    // The tasks plugin's due token, built here exactly as ADR 0015 § 1
    // specifies it — `\sdue:` plus an ISO date, on a task line only, styled
    // where it stands and never replaced. It is not registered by the
    // shell; this is the second producer proving the seam carries one.
    private val taskLine = Regex("""^[ \t]*[-*+][ \t]+\[[ xX]]""")
    private val duePattern = Regex("""\sdue:\d{4}-\d{2}-\d{2}\b""")
    private val dueDecoration = EditorDecoration(
        id = "org.memory.tasks.due",
        role = DecorationRole.MARKER,
        recognise = { line ->
            if (!taskLine.containsMatchIn(line)) {
                emptyList()
            } else {
                duePattern.findAll(line)
                    .map { DecorationMatch(it.range.first + 1 until it.range.last + 1, DecorationRender.StyleOnly) }
                    .toList()
            }
        },
    )

    /// Both directions over the whole document: in bounds, never going
    /// backwards, and the two ends meeting. Compose crashes on a mapping
    /// that breaks any of the three.
    private fun assertMappingIsSound(source: String, result: TransformedText) {
        val transformed = result.text.text
        var previous = 0
        for (offset in 0..source.length) {
            val mapped = result.offsetMapping.originalToTransformed(offset)
            assertTrue(mapped in 0..transformed.length, "original $offset mapped to $mapped, out of bounds")
            assertTrue(mapped >= previous, "original $offset went backwards: $previous then $mapped")
            previous = mapped
        }
        assertEquals(transformed.length, result.offsetMapping.originalToTransformed(source.length))
        previous = 0
        for (offset in 0..transformed.length) {
            val mapped = result.offsetMapping.transformedToOriginal(offset)
            assertTrue(mapped in 0..source.length, "transformed $offset mapped to $mapped, out of bounds")
            assertTrue(mapped >= previous, "transformed $offset went backwards: $previous then $mapped")
            previous = mapped
        }
        assertEquals(source.length, result.offsetMapping.transformedToOriginal(transformed.length))
    }

    @Test
    fun theBuiltInRegistryIsTheCheckboxSliceAndIsWhatTheDefaultUses() {
        assertEquals(1, builtInDecorations().size, "one built-in today: the checkbox")
        val explicit = transform(taskDoc, taskDocCaretLine, builtInDecorations())
        val default = transform(taskDoc, taskDocCaretLine)
        assertEquals(default.text.text, explicit.text.text)
        for (offset in 0..taskDoc.length) {
            assertEquals(
                default.offsetMapping.originalToTransformed(offset),
                explicit.offsetMapping.originalToTransformed(offset),
                "offset $offset must not care whether the registry was passed or defaulted",
            )
        }
    }

    @Test
    fun anEmptyRegistryLeavesTheTextAndEveryOffsetAlone() {
        val source = "- [ ] one\n- [x] two"
        val result = transform(source, caretLine = 0, decorations = emptyList())
        assertEquals(source, result.text.text, "nothing registered, nothing rendered")
        for (offset in 0..source.length) {
            assertEquals(offset, result.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, result.offsetMapping.transformedToOriginal(offset))
        }
    }

    @Test
    fun matchesOfDifferentLengthsOnOneLineFoldIntoOneOffsetTable() {
        val source = "notes\n- [ ] AB and XYZ here"
        val decorations = builtInDecorations() +
            replacing("grow", "AB", "<<<<<") +
            replacing("shrink", "XYZ", "~")
        val result = transform(source, caretLine = 0, decorations = decorations)
        val transformed = result.text.text
        assertEquals("notes\n- $unchecked <<<<< and ~ here", transformed)
        assertMappingIsSound(source, result)

        // Each replacement's own delta carries every offset behind it: -2
        // for the glyph, +3 for the grown span, -2 for the shrunk one.
        val mapping = result.offsetMapping
        for ((sourceText, renderedText) in listOf("[ ]" to unchecked.toString(), "AB" to "<<<<<", "XYZ" to "~")) {
            val start = source.indexOf(sourceText)
            val end = start + sourceText.length
            val renderedStart = transformed.indexOf(renderedText)
            val renderedEnd = renderedStart + renderedText.length
            assertEquals(renderedStart, mapping.originalToTransformed(start), "`$sourceText` starts where it renders")
            assertEquals(renderedEnd, mapping.originalToTransformed(end), "`$sourceText` ends where it renders")
            assertEquals(start, mapping.transformedToOriginal(renderedStart), "and back again")
            assertEquals(end, mapping.transformedToOriginal(renderedEnd))
        }
    }

    @Test
    fun anOffsetInsideAReplacedRangeSnapsToTheNearerEdgeInBothDirections() {
        val source = "notes\n- [ ] AB and XYZ here"
        val decorations = builtInDecorations() + replacing("grow", "AB", "<<<<<")
        val result = transform(source, caretLine = 0, decorations = decorations)
        val mapping = result.offsetMapping
        val start = source.indexOf("AB")
        val rendered = result.text.text.indexOf("<<<<<")

        // Source side: two characters became five, so the midpoint rounds
        // out to the far edge rather than landing in the middle of a span
        // that no longer has a middle.
        assertEquals(rendered + 5, mapping.originalToTransformed(start + 1))
        // Transformed side: the first two of five come back to the start,
        // the last two to the end — monotone, and never inside the source
        // range where no caret may sit.
        assertEquals(start, mapping.transformedToOriginal(rendered + 1))
        assertEquals(start, mapping.transformedToOriginal(rendered + 2))
        assertEquals(start + 2, mapping.transformedToOriginal(rendered + 3))
        assertEquals(start + 2, mapping.transformedToOriginal(rendered + 4))
    }

    @Test
    fun aStyleOnlySpanColoursWithoutMovingAnySingleOffset() {
        val source = "notes\n- [ ] pay the bill due:2026-09-05"
        val result = transform(source, caretLine = 0, decorations = listOf(dueDecoration))
        assertEquals(source, result.text.text, "a zero-delta registration changes no byte and no length")
        for (offset in 0..source.length) {
            assertEquals(offset, result.offsetMapping.originalToTransformed(offset), "offset $offset moved")
            assertEquals(offset, result.offsetMapping.transformedToOriginal(offset), "offset $offset moved")
        }
        val token = "due:2026-09-05"
        assertTrue(
            result.text.spanStyles.any {
                it.item.color == palette.marker && source.substring(it.start, it.end) == token
            },
            "the token is styled where it stands",
        )
    }

    @Test
    fun overlappingMatchesGoToTheFirstRegistrationInDeclarationOrder() {
        val source = "notes\nabcdef"
        val first = replacing("first", "abcd", "1")
        val second = replacing("second", "cdef", "2")
        assertEquals(
            "notes\n1ef",
            transform(source, caretLine = 0, decorations = listOf(first, second)).text.text,
            "the first registration claims the ground it overlaps",
        )
        assertEquals(
            "notes\nab2",
            transform(source, caretLine = 0, decorations = listOf(second, first)).text.text,
            "swap the order and the other one wins — order is the whole rule",
        )
    }

    @Test
    fun builtInsLeadTheRegistryAndTakeAContestedRange() {
        val source = "notes\n- [ ] task"
        val plugin = replacing("plugin", "[ ]", "!!")
        assertEquals(
            "notes\n- $unchecked task",
            transform(source, caretLine = 0, decorations = builtInDecorations() + plugin).text.text,
            "built-ins first, so the checkbox keeps the marker",
        )
        assertEquals(
            "notes\n- !! task",
            transform(source, caretLine = 0, decorations = listOf(plugin) + builtInDecorations()).text.text,
            "and it keeps it by position, not by privilege",
        )
    }

    @Test
    fun theCaretsLineKeepsPureStylingButNothingThatReplacesOrAnswersAClick() {
        // E3's raw-on-the-caret-line rule is still the shell's to honour,
        // not a registration's — narrowed (owner, 2026-10-19) to what it
        // was for: nothing *replaced* on the line being written, and nothing
        // there answers a click. A style-only registration is still asked.
        val asked = mutableListOf<String>()
        val spy = EditorDecoration(id = "spy", recognise = { line -> asked += line; emptyList() })
        val clickable = mutableListOf<String>()
        val activating = EditorDecoration(
            id = "clickable",
            recognise = { line -> clickable += line; emptyList() },
            activate = { it },
        )
        val replacing = EditorDecoration(
            id = "replacing",
            recognise = { line -> listOf(DecorationMatch(0 until minOf(1, line.length), DecorationRender.Replace("#"))) },
        )
        val result = transform("one\ntwo\nthree", caretLine = 1, decorations = listOf(spy, activating, replacing))

        assertEquals(listOf("one", "two", "three"), asked, "pure styling is asked of every line")
        assertEquals(listOf("one", "three"), clickable, "an activating registration never sees the caret's line")
        assertEquals("#ne\ntwo\n#hree", result.text.text, "and nothing is replaced on it")
    }

    @Test
    fun aMentionInTheTaskBeingEditedKeepsItsHighlight() {
        // The owner's report, exactly: editing a task that names someone
        // used to drop the person's highlight along with the checkbox glyph.
        val source = "- [ ] ask @alice.smith about it"
        val decorations = builtInDecorations() + org.gilune.plugins.people.peopleMentionDecoration()
        val result = transform(source, caretLine = 0, decorations = decorations)
        assertEquals(source, result.text.text, "the checkbox is raw `[ ]` while its line is edited")
        assertTrue(
            result.text.spanStyles.any { it.item.color == palette.link && source.substring(it.start, it.end) == "@alice.smith" },
            "the mention is still drawn as a link",
        )
    }

    @Test
    fun aRegistrationThatOptsInStillFiresInsideAFence() {
        val source = "notes\n```\n- [ ] quoted\n```"
        assertEquals(
            "notes\n```\n- $unchecked quoted\n```",
            transform(source, caretLine = 0).text.text,
            "presentation of a GFM element is not task metadata; the checkbox renders as it always has",
        )
    }

    @Test
    fun activateLineFoldsEveryHandlerWithTheCheckboxAsTheFirstLink() {
        // The T2 shape from § 5, one stage early: core flips the marker,
        // the second link then stamps or strips the done date, and the
        // shell writes the line once.
        val stamp = EditorDecoration(
            id = "org.memory.tasks.done",
            recognise = { line ->
                if (taskLine.containsMatchIn(line)) listOf(DecorationMatch(0 until 1, DecorationRender.StyleOnly))
                else emptyList()
            },
            activate = { line ->
                if (line.contains("[x]")) "$line done:2026-08-31" else line.replace(" done:2026-08-31", "")
            },
        )
        val chain = builtInDecorations() + stamp
        assertEquals(
            "- [x] file the return done:2026-08-31",
            activateLine("- [ ] file the return", chain),
            "the second link sees the line the first one wrote",
        )
        assertEquals(
            "- [ ] file the return",
            activateLine("- [x] file the return done:2026-08-31", chain),
            "and folding back the other way strips it again",
        )
    }

    @Test
    fun activateLineIsTheCheckboxToggleWhenTheShellStandsAlone() {
        assertEquals("- [x] a", activateLine("- [ ] a"), "remove the plugin and the toggle still toggles")
        assertEquals("- [ ] a", activateLine("- [x] a"))
        assertNull(activateLine("plain prose"), "nothing claimed the line, so the document is left alone")
        assertNull(activateLine("- [ ] a", emptyList()))
        assertNull(activateLine("- [ ] a", listOf(dueDecoration)), "a registration with no handler activates nothing")
    }

    @Test
    fun decorationAtAnswersTheCheckboxPathThroughTheSeam() {
        val source = "notes\n- [ ] task"
        val marker = source.indexOf("[ ]")
        val glyph = transform(source, caretLine = 0).text.text.indexOf(unchecked)

        val hit = assertNotNull(decorationAt(source, glyph, caretLine = 0))
        assertEquals(marker until marker + 3, hit.range, "the hit carries the source range the toggle needs")
        assertEquals("gilune.checkbox", hit.decoration.id)
        assertEquals(
            "notes\n- [x] task",
            toggleCheckbox(source, hit.range),
            "the checkbox path is expressible through the general helper",
        )
        assertEquals(
            "- [x] task",
            activateLine(source.substring(hit.lineRange.first, hit.lineRange.last + 1)),
            "and so is the activation, from the line the hit names",
        )

        // A click on the glyph's right half lands the cursor just past it.
        assertEquals(hit.range, decorationAt(source, glyph + 1, caretLine = 0)?.range)
        assertNull(decorationAt(source, glyph + 2, caretLine = 0), "past the glyph is ordinary text")
        assertNull(decorationAt(source, glyph, caretLine = 1), "a marker on the caret's line is raw and answers nothing")
        // No caret at all — the unfocused buffer — leaves every glyph
        // clickable: opening a note and immediately ticking its first
        // task is the use this exists for.
        assertNotNull(decorationAt(source, glyph, caretLine = null), "no caret means every marker answers")
    }

    @Test
    fun decorationAtIgnoresARegistrationThatBearsNoActivation() {
        val source = "notes\n- [ ] pay the bill due:2026-09-05"
        val token = source.indexOf("due:")
        assertNull(
            decorationAt(source, token, caretLine = 0, decorations = listOf(dueDecoration)),
            "styling a token does not make it clickable",
        )
    }

    // ---------------------------------------------------------------
    // The block-scoped kind (owner, 2026-09-01): a `filter` block behaves
    // like a checkbox — rendered until the caret is placed within it. The
    // whole risk is the offset mapping across a fence that is not there,
    // so most of what follows is arithmetic.
    // ---------------------------------------------------------------

    /// Three lines of fence with prose either side.
    private val blockDoc = "intro\n```filter\ntype:task\n```\nafter\n"

    /// A registration claiming one info string and reserving a fixed
    /// number of lines: the shape the shell's `filter` renderer has, with
    /// no vault behind it, so this suite stays pure.
    private fun claiming(id: String, info: String, lines: Int) =
        EditorBlockDecoration(id) { fenceInfo, _ -> if (fenceInfo == info) lines else null }

    private fun transformBlocks(
        source: String,
        caretLine: Int?,
        blocks: List<EditorBlockDecoration>,
    ): TransformedText =
        MarkdownEditTransformation(palette, caretLine, builtInDecorations(), blocks)
            .filter(AnnotatedString(source))

    @Test
    fun fencedBlocksReadsAClosedFenceAndLeavesAnUnterminatedOneAlone() {
        val fence = fencedBlocks(blockDoc).single()
        assertEquals("filter", fence.info)
        assertEquals("type:task", fence.body)
        assertEquals("```filter\ntype:task\n```", blockDoc.substring(fence.range.first, fence.range.last + 1))
        assertEquals(1..3, fence.lines, "the delimiters are the block's own lines")
        assertTrue(
            fencedBlocks("intro\n```filter\ntype:task\n").isEmpty(),
            "an unterminated fence has no end, so there is nothing to collapse",
        )
        assertTrue(fencedBlocks("no fences here").isEmpty())
        val two = fencedBlocks("```a\n1\n```\n\n```\n2\n```")
        assertEquals(listOf("a", ""), two.map { it.info }, "the info string is whatever follows the ticks")
        assertEquals(listOf("1", "2"), two.map { it.body })
    }

    @Test
    fun aClaimedBlockCollapsesToExactlyTheLinesItReserved() {
        for (lines in 1..5) {
            val result = transformBlocks(blockDoc, caretLine = 0, blocks = listOf(claiming("f", "filter", lines)))
            assertEquals(
                "intro\n" + "\n".repeat(lines - 1) + "\nafter\n",
                result.text.text,
                "$lines reserved lines",
            )
        }
        // Three lines of fence for three lines of placeholder: nothing
        // below the block moved up or down the page.
        val same = transformBlocks(blockDoc, caretLine = 0, blocks = listOf(claiming("f", "filter", 3)))
        assertEquals(blockDoc.count { it == '\n' }, same.text.text.count { it == '\n' })
    }

    @Test
    fun theCaretOnAnyLineOfTheBlockShowsTheWholeFenceRaw() {
        val blocks = listOf(claiming("f", "filter", 3))
        for (caret in 1..3) {
            assertEquals(
                blockDoc,
                transformBlocks(blockDoc, caret, blocks).text.text,
                "the caret on line $caret is inside the block, delimiters included",
            )
        }
        for (caret in listOf(0, 4)) {
            assertEquals(
                "intro\n\n\n\nafter\n",
                transformBlocks(blockDoc, caret, blocks).text.text,
                "the caret on line $caret is outside it",
            )
        }
        assertEquals(
            "intro\n\n\n\nafter\n",
            transformBlocks(blockDoc, null, blocks).text.text,
            "an unfocused buffer has no caret at all, so the block renders",
        )
    }

    @Test
    fun anInfoStringNoRegistrationClaimsIsLeftExactlyAsItWas() {
        val sql = "intro\n```sql\nselect 1\n```\nafter\n"
        assertEquals(sql, transformBlocks(sql, 0, listOf(claiming("f", "filter", 3))).text.text)
        assertEquals(blockDoc, transformBlocks(blockDoc, 0, emptyList()).text.text, "and E3 is what it was")
    }

    @Test
    fun nothingElseClaimsGroundInsideACollapsedBlock() {
        val source = "```filter\n- [ ] quoted\n```\n"
        val blocks = listOf(claiming("f", "filter", 1))
        assertEquals("\n", transformBlocks(source, caretLine = null, blocks = blocks).text.text)
        // With the caret inside it the fence is raw again, and the
        // checkbox draws there exactly as § 1 has always had it.
        assertEquals(
            "```filter\n- $unchecked quoted\n```\n",
            transformBlocks(source, caretLine = 0, blocks = blocks).text.text,
        )
    }

    @Test
    fun everyOffsetRoundTripsAcrossACollapsedBlock() {
        val blocks = listOf(claiming("f", "filter", 2))
        val result = transformBlocks(blockDoc, 0, blocks)
        assertMappingIsSound(blockDoc, result)

        val fence = fencedBlocks(blockDoc).single()
        val mapping = result.offsetMapping
        val start = fence.range.first
        val end = fence.range.last + 1
        val delta = 1 - (end - start) // one newline of placeholder for the fence
        for (offset in 0..start) {
            assertEquals(offset, mapping.originalToTransformed(offset), "offset $offset is before the block")
            assertEquals(offset, mapping.transformedToOriginal(offset))
        }
        for (offset in end..blockDoc.length) {
            assertEquals(offset + delta, mapping.originalToTransformed(offset), "offset $offset is after the block")
            assertEquals(offset, mapping.transformedToOriginal(offset + delta), "and comes back exactly")
        }
        // Both edges are exact, which is what lets a caret sit either side
        // of a block that is not there.
        assertEquals(start, mapping.transformedToOriginal(mapping.originalToTransformed(start)))
        assertEquals(end, mapping.transformedToOriginal(mapping.originalToTransformed(end)))
    }

    @Test
    fun everyPlaceholderPositionComesBackInsideTheFenceSoArrowingIntoItRevealsIt() {
        val blocks = listOf(claiming("f", "filter", 4))
        val result = transformBlocks(blockDoc, 0, blocks)
        val block = collapsedBlocks(blockDoc, 0, blocks = blocks).single()
        // Down-arrow and a click both hand the field a transformed offset
        // on one of the placeholder's lines. Every one of them maps back
        // into the fence, which is the whole of "moving into a block
        // reveals it" — there is nowhere else for the caret to land.
        for (offset in block.transformedStart until block.transformedStart + block.lines) {
            val source = result.offsetMapping.transformedToOriginal(offset)
            assertTrue(
                caretLine(blockDoc, source) in block.fence.lines,
                "transformed $offset came back to line ${caretLine(blockDoc, source)}, outside the block",
            )
        }
    }

    @Test
    fun editingAtEitherEdgeOfACollapsedBlockChangesTheSourceTheUserCanSee() {
        val result = transformBlocks(blockDoc, 0, listOf(claiming("f", "filter", 2)))
        val mapping = result.offsetMapping
        val transformed = result.text.text

        // Backspace at the end of the line above the block deletes the
        // character it looks like it deletes.
        val above = mapping.transformedToOriginal(transformed.indexOf('\n'))
        assertEquals(blockDoc.indexOf('\n'), above)
        assertEquals("intr\n```filter\ntype:task\n```\nafter\n", blockDoc.removeRange(above - 1, above))

        // And typing at the start of the line below reaches that line, not
        // the fence hiding under it.
        val below = mapping.transformedToOriginal(transformed.indexOf("after"))
        assertEquals(blockDoc.indexOf("after"), below)
        assertEquals(
            "intro\n```filter\ntype:task\n```\nXafter\n",
            blockDoc.substring(0, below) + "X" + blockDoc.substring(below),
        )
    }

    @Test
    fun decorationsAfterACollapsedBlockStillLandOnTheirGlyphs() {
        val source = "```filter\ntype:task\n```\n- [ ] one\n- [x] two"
        val blocks = listOf(claiming("f", "filter", 2))
        val result = transformBlocks(source, caretLine = 4, blocks = blocks)
        assertEquals("\n\n- $unchecked one\n- [x] two", result.text.text)
        assertMappingIsSound(source, result)

        // The click path is asked with the same blocks, so the offset the
        // layout would report is the offset the seam answers.
        val glyph = result.text.text.indexOf(unchecked)
        val marker = source.indexOf("[ ]")
        val hit = assertNotNull(
            decorationAt(source, glyph, caretLine = 4, decorations = builtInDecorations(), blocks = blocks),
        )
        assertEquals(marker until marker + 3, hit.range)
        assertNull(
            decorationAt(source, glyph, caretLine = 4),
            "the same offset in the uncollapsed document is not the glyph — which is why the seam takes the blocks",
        )
    }

    @Test
    fun collapsedBlocksReportsWhereEachPlaceholderLanded() {
        val blocks = listOf(claiming("f", "filter", 3))
        val reported = collapsedBlocks(blockDoc, caretLine = 0, blocks = blocks).single()
        assertEquals("f", reported.id)
        assertEquals("type:task", reported.fence.body)
        assertEquals(3, reported.lines)
        assertEquals(
            blockDoc.indexOf("```"),
            reported.transformedStart,
            "nothing before the block moved, so it starts where the fence did",
        )
        assertEquals(
            '\n',
            transformBlocks(blockDoc, 0, blocks).text.text[reported.transformedStart],
            "and the placeholder's first line is blank",
        )
        assertTrue(
            collapsedBlocks(blockDoc, caretLine = 2, blocks = blocks).isEmpty(),
            "a block showing its raw fence has no box to draw over",
        )
        assertTrue(collapsedBlocks(blockDoc, caretLine = 0, blocks = emptyList()).isEmpty())
    }

    @Test
    fun theFirstRegistrationToClaimAFenceTakesIt() {
        val two = listOf(claiming("first", "filter", 1), claiming("second", "filter", 4))
        val taken = collapsedBlocks(blockDoc, 0, blocks = two).single()
        assertEquals("first", taken.id, "declaration order decides a block exactly as it decides a line")
        assertEquals(1, taken.lines)
    }

    @Test
    fun aBlockAtEitherEndOfTheDocumentMapsSoundlyToo() {
        val sources = listOf(
            "```filter\nq\n```",
            "```filter\nq\n```\ntail",
            "head\n\n```filter\nq\n```",
            "```filter\nq\n```\n```filter\nr\n```\n- [ ] after both",
        )
        for (source in sources) {
            for (caret in listOf(null, 0, 1, 2, 3)) {
                assertMappingIsSound(source, transformBlocks(source, caret, listOf(claiming("f", "filter", 4))))
            }
        }
    }

    @Test
    fun theDueTokenRidesTheSeamBesideTheCheckbox() {
        val decorations = builtInDecorations() + dueDecoration
        val source = "notes\n- [ ] renew the passport due:2026-09-05"
        val result = transform(source, caretLine = 0, decorations = decorations)
        val transformed = result.text.text
        val token = "due:2026-09-05"

        // Two producers, one line: the checkbox replaces, the token only
        // colours, and both survive the same fold.
        assertEquals("notes\n- $unchecked renew the passport $token", transformed)
        assertTrue(
            result.text.spanStyles.any {
                it.item.color == palette.marker && transformed.substring(it.start, it.end) == token
            },
            "the token is styled at its transformed offsets, behind the glyph's two-character shift",
        )
        assertMappingIsSound(source, result)
        assertEquals(
            transformed.indexOf(token),
            result.offsetMapping.originalToTransformed(source.indexOf(token)),
            "the token moved by the glyph's delta, not by its own",
        )
        assertEquals(
            source.indexOf(token),
            result.offsetMapping.transformedToOriginal(transformed.indexOf(token)),
        )

        // The caret's line shows its raw Markdown — the checkbox is `[ ]`
        // again — but colour hides no byte, so the token keeps its colour
        // while the line is being written (owner, 2026-10-19).
        val onTheCaretLine = transform(source, caretLine = 1, decorations = decorations)
        assertEquals(source, onTheCaretLine.text.text, "nothing replaced on the caret's line")
        assertTrue(
            onTheCaretLine.text.spanStyles.any {
                it.item.color == palette.marker && source.substring(it.start, it.end) == token
            },
            "the token is still coloured where the caret is",
        )
        assertMappingIsSound(source, onTheCaretLine)

        // And a documented example is not somebody's agenda (§ 1): inside a
        // fence the token is text, while the opted-in checkbox still draws.
        val fenced = """
            notes
            ```markdown
            - [ ] example due:2026-09-05
            ```
            - [ ] real due:2026-09-06
        """.trimIndent()
        val quoted = transform(fenced, caretLine = 0, decorations = decorations)
        val quotedText = quoted.text.text
        val styled = quoted.text.spanStyles.filter {
            it.item.color == palette.marker && quotedText.substring(it.start, it.end).startsWith("due:")
        }
        assertEquals(1, styled.size, "only the token outside the fence is a due date")
        assertEquals("due:2026-09-06", quotedText.substring(styled.single().start, styled.single().end))
        assertEquals(2, quotedText.count { it == unchecked }, "both checkboxes still render")
        assertMappingIsSound(fenced, quoted)
    }

    // ADR 0032 stage 3: a style window draws colours near the screen only.
    // Everything that moves text — the transformed string, the offset
    // mapping, and every span that changes how a line wraps — must be the
    // same whatever the window, or scrolling would jump and clicks land on
    // the wrong character.
    private val windowDoc = (1..60).joinToString("\n") { i ->
        when (i % 6) {
            0 -> "## Section $i"
            1 -> "- [ ] task $i with a [link](to/$i) and `code`"
            2 -> "Paragraph $i with **bold**, *italic* and ~~gone~~ words."
            3 -> "> quoted $i"
            4 -> "- bullet $i"
            else -> ""
        }
    }

    private fun metric(text: AnnotatedString) = text.spanStyles
        .filter { it.item.fontWeight != null || it.item.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified || it.item.fontStyle != null }
        .map { Triple(it.start, it.end, Triple(it.item.fontWeight, it.item.fontSize, it.item.fontStyle)) }
        .toSet()

    @Test
    fun aStyleWindowChangesColoursOnlyAndOnlyOutsideTheWindow() {
        val decorations = builtInDecorations()
        val whole = MarkdownEditTransformation(palette, null, decorations).filter(AnnotatedString(windowDoc))
        val middle = windowDoc.length / 2
        val window = middle..(middle + 300)
        val windowed = MarkdownEditTransformation(palette, null, decorations, styleWindow = window).filter(AnnotatedString(windowDoc))

        assertEquals(whole.text.text, windowed.text.text, "the laid-out text is the same")
        for (offset in 0..windowDoc.length) {
            assertEquals(whole.offsetMapping.originalToTransformed(offset), windowed.offsetMapping.originalToTransformed(offset))
        }
        for (offset in 0..whole.text.length) {
            assertEquals(whole.offsetMapping.transformedToOriginal(offset), windowed.offsetMapping.transformedToOriginal(offset))
        }
        assertEquals(metric(whole.text), metric(windowed.text), "every wrap-changing span is kept, everywhere")

        val colours = windowed.text.spanStyles.filter { it.item.color != Color.Unspecified }
        assertTrue(colours.isNotEmpty(), "the window itself is coloured")
        for (span in colours) {
            val lineStart = whole.text.text.lastIndexOf('\n', span.start - 1) + 1
            val lineEnd = whole.text.text.indexOf('\n', span.start).let { if (it < 0) whole.text.length else it }
            assertTrue(lineEnd >= window.first && lineStart <= window.last, "a colour off the window's lines at ${span.start}")
        }
        val colourCount = { text: AnnotatedString -> text.spanStyles.count { it.item.color != Color.Unspecified } }
        assertTrue(
            colourCount(windowed.text) * 3 < colourCount(whole.text),
            "the window's eighth of the note keeps few colours: ${colourCount(windowed.text)} of ${colourCount(whole.text)}",
        )
        // Inside the window it draws exactly what the whole note draws.
        val inside = { text: AnnotatedString -> text.spanStyles.filter { it.start >= window.first + 80 && it.end <= window.last - 80 }.toSet() }
        assertEquals(inside(whole.text), inside(windowed.text))
    }

    @Test
    fun aTransformationAnswersTheSameTextOnceAndANewTextAfresh() {
        val transformation = MarkdownEditTransformation(palette)
        val first = transformation.filter(AnnotatedString(windowDoc))
        assertTrue(first === transformation.filter(AnnotatedString(windowDoc)), "asked again, the same answer")
        val edited = transformation.filter(AnnotatedString(windowDoc + "x"))
        assertTrue(edited !== first)
        assertEquals(MarkdownEditTransformation(palette).filter(AnnotatedString(windowDoc + "x")).text, edited.text)
    }

    // ADR 0032: a keystroke re-reads only the lines it changed. Whatever a
    // shared memo remembers, every answer must be the one a transformation
    // with nothing remembered gives — including when the change is not on
    // the line itself: a fence opened above, the caret moving, the window
    // moving, the registry or the palette replaced.
    @Test
    fun aSharedMemoAnswersExactlyWhatAFreshTransformationAnswers() {
        val memo = LineMemo()
        // Two registrations under one id that claim different ranges: a
        // memo that remembered by id would answer the second with the first.
        val tag = { pattern: String ->
            EditorDecoration(
                id = "test.tag",
                role = DecorationRole.MARKER,
                recognise = { line ->
                    Regex(pattern).findAll(line).map { DecorationMatch(it.range, DecorationRender.StyleOnly) }.toList()
                },
            )
        }
        val firstTag = tag("#[a-z]+")
        val secondTag = tag("#[a-z]{2}")
        val usual = palette
        val other = palette.copy(muted = Color.Magenta)
        class Step(
            val text: String,
            val caret: Int? = null,
            val window: IntRange? = null,
            val decorations: List<EditorDecoration> = builtInDecorations() + firstTag,
            val palette: MarkdownPalette = usual,
        )
        val start = windowDoc + "\nsome #tag here"
        val steps = listOf(
            Step(start),
            Step(start + "x"),
            Step(start.replaceFirst("Paragraph 2", "Paragraph 2 **more**")),
            Step("```\n" + start, caret = 3),
            Step("```\n" + start + "\n```", caret = 3),
            Step(start, caret = 7, window = 200..600),
            Step(start, caret = 7, window = 1200..1800),
            Step(start, decorations = builtInDecorations() + secondTag),
            Step(start, palette = other),
            Step(start.replace("- [ ]", "- [x]")),
        )
        for ((index, step) in steps.withIndex()) {
            val shared = MarkdownEditTransformation(step.palette, step.caret, step.decorations, styleWindow = step.window, memo = memo)
                .filter(AnnotatedString(step.text))
            val fresh = MarkdownEditTransformation(step.palette, step.caret, step.decorations, styleWindow = step.window)
                .filter(AnnotatedString(step.text))
            assertEquals(fresh.text.text, shared.text.text, "step $index text")
            assertEquals(fresh.text.spanStyles, shared.text.spanStyles, "step $index spans, in order")
            for (offset in 0..step.text.length) {
                assertEquals(fresh.offsetMapping.originalToTransformed(offset), shared.offsetMapping.originalToTransformed(offset), "step $index offset $offset")
            }
        }
    }

    // Typing makes a new version of one line per keystroke; the memo must
    // not keep every one of them, and must not recompute lines it holds.
    @Test
    fun theMemoStaysTheSizeOfTheNoteWhileALineIsTyped() {
        val memo = Generations<Int>()
        val note = (1..100).map { "line $it" }
        var computed = 0
        var typed = ""
        repeat(2_000) {
            typed += "a"
            for (line in note + typed) memo.get(line) { computed++; it.length }
            memo.turn()
        }
        assertTrue(memo.size <= 2 * 101 + 64 + 1, "held ${memo.size} lines")
        assertEquals(100 + 2_000, computed, "each unchanged line computed once, each typed version once")
    }

    // A carriage return is drawn as a zero-width space (Skia's layout of a
    // paragraph holding them grows with the square of its length). The
    // rules still read the text as it is: `##\r` is a heading because `\s`
    // matches the carriage return the file actually holds.
    @Test
    fun carriageReturnsAreDrawnAsZeroWidthAndEveryRuleStillReadsThem() {
        val source = "- [ ] a\r\n##\r\nend\r"
        val result = MarkdownEditTransformation(palette).filter(AnnotatedString(source))
        val zwsp = CARRIAGE_RETURN_DRAWN_AS
        assertEquals("- ☐ a$zwsp\n##$zwsp\nend$zwsp", result.text.text)
        assertTrue('\r' !in result.text.text)
        val headingStart = source.indexOf("##") - 2 // the checkbox drew two characters fewer
        assertTrue(
            result.text.spanStyles.any { it.start == headingStart && it.item.fontWeight == FontWeight.Bold },
            "the empty heading is still one: ${result.text.spanStyles}",
        )
        val withoutReturns = MarkdownEditTransformation(palette).filter(AnnotatedString(source.replace('\r', 'r')))
        for (offset in 0..source.length) {
            assertEquals(withoutReturns.offsetMapping.originalToTransformed(offset), result.offsetMapping.originalToTransformed(offset))
        }
        val plain = styleMarkdown("a\r\nb", palette)
        assertEquals("a$zwsp\nb", plain.text)
    }

    // ADR 0032 stage 4: a slice lays out only its own lines, between two
    // placeholders. Inside it, everything must be what the whole buffer
    // would have shown — the same characters, spans and offsets, shifted
    // by the placeholder — and every offset outside lands on a placeholder
    // edge, never inside the slice.
    private val sliceDoc = (1..90).joinToString("\n") { i ->
        when (i % 6) {
            0 -> "## Section $i"
            1 -> "- [ ] task $i with a [link](to/$i)"
            2 -> "Paragraph $i with **bold** and *italic*."
            3 -> "> quoted $i"
            else -> "line $i"
        }
    }

    private fun lineStart(text: String, line: Int): Int {
        var offset = 0
        repeat(line) { offset = text.indexOf('\n', offset) + 1 }
        return offset
    }

    @Test
    fun aSliceLaysOutItsLinesExactlyAsTheWholeBufferDoesBetweenTwoPlaceholders() {
        val start = lineStart(sliceDoc, 30)
        val end = lineStart(sliceDoc, 60)
        val slice = EditorSlice(start, sliceDoc.length - end, 400.sp, 900.sp)
        val whole = MarkdownEditTransformation(palette, null, builtInDecorations()).filter(AnnotatedString(sliceDoc))
        val sliced = MarkdownEditTransformation(palette, null, builtInDecorations(), slice = slice).filter(AnnotatedString(sliceDoc))

        val wholeFrom = whole.offsetMapping.originalToTransformed(start)
        val wholeTo = whole.offsetMapping.originalToTransformed(end - 1)
        val middle = whole.text.text.substring(wholeFrom, wholeTo)
        assertEquals("$SLICE_PLACEHOLDER$middle$SLICE_PLACEHOLDER", sliced.text.text)
        for (offset in start..end - 1) {
            assertEquals(
                whole.offsetMapping.originalToTransformed(offset) - wholeFrom + 1,
                sliced.offsetMapping.originalToTransformed(offset),
                "offset $offset",
            )
        }
        for (offset in 0 until start) assertTrue(sliced.offsetMapping.originalToTransformed(offset) in 0..1)
        for (offset in end until sliceDoc.length) {
            assertTrue(sliced.offsetMapping.originalToTransformed(offset) >= sliced.text.length - 1)
        }
        // The middle's spans are the whole buffer's, shifted.
        val wholeSpans = whole.text.spanStyles
            .filter { it.start >= wholeFrom && it.end <= wholeTo }
            .map { Triple(it.start - wholeFrom + 1, it.end - wholeFrom + 1, it.item) }
        assertEquals(wholeSpans.toSet(), sliced.text.spanStyles.map { Triple(it.start, it.end, it.item) }.toSet())
        // Each placeholder is a paragraph of its own height.
        val heights = sliced.text.paragraphStyles.map { Triple(it.start, it.end, it.item.lineHeight) }
        assertEquals(listOf(Triple(0, 1, 400.sp), Triple(sliced.text.length - 1, sliced.text.length, 900.sp)), heights)
    }

    @Test
    fun aSliceSurvivesTypingInsideItAndCutsNothingOnceItNoLongerFits() {
        val start = lineStart(sliceDoc, 30)
        val end = lineStart(sliceDoc, 60)
        val slice = EditorSlice(start, sliceDoc.length - end, 400.sp, 900.sp)
        val typed = sliceDoc.substring(0, start + 3) + "new words" + sliceDoc.substring(start + 3)
        val sliced = MarkdownEditTransformation(palette, null, builtInDecorations(), slice = slice).filter(AnnotatedString(typed))
        assertTrue(sliced.text.text.startsWith(SLICE_PLACEHOLDER), "still cut above")
        assertTrue("new words" in sliced.text.text)
        assertTrue(sliced.text.text.endsWith(SLICE_PLACEHOLDER), "still cut below")
        // Text removed above the slice: its start is no longer a line start,
        // so the buffer is laid out whole rather than cut mid-line.
        val shorter = sliceDoc.substring(5)
        val whole = MarkdownEditTransformation(palette, null, builtInDecorations()).filter(AnnotatedString(shorter))
        assertEquals(whole.text.text, MarkdownEditTransformation(palette, null, builtInDecorations(), slice = slice).filter(AnnotatedString(shorter)).text.text)
    }

    @Test
    fun clicksMenusAndCollapsedBlocksAnswerInTheSlicedText() {
        val start = lineStart(sliceDoc, 30)
        val end = lineStart(sliceDoc, 60)
        val slice = EditorSlice(start, sliceDoc.length - end, 400.sp, 900.sp)
        val sliced = MarkdownEditTransformation(palette, null, builtInDecorations(), slice = slice).filter(AnnotatedString(sliceDoc))
        val task = sliceDoc.indexOf("- [ ] task 31")
        val glyph = sliced.offsetMapping.originalToTransformed(task + 2)
        assertEquals(unchecked, sliced.text.text[glyph])
        val hit = assertNotNull(decorationAt(sliceDoc, glyph, null, builtInDecorations(), slice = slice))
        assertEquals(task + 2, hit.range.first)
        // The same glyph's place in the whole text is somewhere else.
        val outside = sliceDoc.indexOf("- [ ] task 1 ")
        assertNull(decorationAt(sliceDoc, 0, null, builtInDecorations(), slice = slice), "the placeholder answers nothing (task at ${outside})")
    }

    @Test
    fun chunksCoverTheTextAtLineStartsOutsideFencesAndStayPutAroundAnEdit() {
        val doc = (1..2_000).joinToString("\n") { i ->
            when {
                i % 97 == 0 -> "```"
                else -> "line $i with some words"
            }
        }
        val chunks = sliceChunks(doc)
        assertEquals(0, chunks.first().start)
        assertEquals(doc.length, chunks.last().end)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        for (chunk in chunks.drop(1)) assertEquals('\n', doc[chunk.start - 1], "a chunk starts a line")
        assertTrue(chunks.size in 20..200, "about ${SLICE_CHUNK_LINES} lines a chunk: ${chunks.size} chunks")
        val fenced = fencedBlocks(doc)
        for (chunk in chunks.drop(1)) {
            assertTrue(fenced.none { chunk.start > it.range.first && chunk.start <= it.range.last }, "no cut inside a fence at ${chunk.start}")
        }
        // The cap gives way to a fence, which is never cut: at most the cap
        // and one fence (96 lines here).
        for (chunk in chunks) {
            val lines = doc.substring(chunk.start, chunk.end).count { it == '\n' }
            assertTrue(lines <= SLICE_CHUNK_MAX_LINES + 97, "a chunk of $lines lines")
        }

        // Typing in one chunk — even a new line — leaves every other
        // chunk's text as it was, so its measured height still holds.
        val middle = chunks[chunks.size / 2]
        val at = middle.start + 4
        val edited = doc.substring(0, at) + "typed\nmore" + doc.substring(at)
        val before = chunks.map { doc.substring(it.start, it.end) }.toSet()
        val after = sliceChunks(edited).map { edited.substring(it.start, it.end) }
        assertTrue(after.count { it !in before } <= 2, "chunks changed: ${after.count { it !in before }}")
    }

    @Test
    fun theSliceHoldsWhileTheViewStaysInsideAndFollowsTheCaret() {
        val tops = FloatArray(41) { it * 500f } // 40 chunks of 500 px
        val first = sliceChunksFor(null, tops, viewTop = 5_000f, viewHeight = 1_000f, caretChunk = null)
        assertTrue(first.first <= 7 && first.last >= 13, "a screen and a half either side: $first")
        assertEquals(first, sliceChunksFor(first, tops, 5_300f, 1_000f, null), "a small scroll keeps it")
        val moved = sliceChunksFor(first, tops, 9_000f, 1_000f, null)
        assertTrue(moved != first && moved.first <= 15 && moved.last >= 21, "a long scroll moves it: $moved")
        val withCaret = sliceChunksFor(first, tops, 5_000f, 1_000f, caretChunk = 14)
        assertTrue(withCaret.first <= 13 && withCaret.last >= 15, "the caret's chunk and one each side: $withCaret")
        assertEquals(0..2, sliceChunksFor(null, FloatArray(4) { it * 100f }, 0f, 1_000f, null), "a short text is one slice")
        // A caret the page has scrolled far from is not stretched to: that
        // would lay out every chunk between.
        val away = sliceChunksFor(first, tops, 5_000f, 1_000f, caretChunk = 39)
        assertTrue(39 !in away && away.last - away.first < 10, "the view's chunks only: $away")
    }

    @Test
    fun stepsOffASliceEdgeMoveByTheBuffersOwnLinesKeepingTheColumn() {
        val text = "first line\nab\nthird line here\nlast"
        val inFirst = 6 // "first |line"
        assertEquals(text.indexOf("ab") + 2, stepLines(text, inFirst, 1), "column clamped to a short line")
        assertEquals(text.indexOf("third") + 6, stepLines(text, inFirst, 2), "column kept")
        assertEquals(text.length, stepLines(text, inFirst, 9), "past the end is the end")
        assertEquals(inFirst, stepLines(text, text.indexOf("third") + 6, -2))
        assertEquals(0, stepLines(text, 3, -1), "above the first line is the start")
    }

    @Test
    fun theStyleWindowReachesAScreenBeyondAndHoldsUntilTheScreenLeavesItsMiddle() {
        val first = nextStyleWindow(null, 1000..2000, 10_000)
        assertEquals(0..3000, first, "one screenful either side, clamped at the start")
        assertEquals(first, nextStyleWindow(first, 1200..2200, 10_000), "a small scroll keeps the window")
        val moved = nextStyleWindow(first, 2600..3600, 10_000)
        assertEquals(1600..4600, moved, "past the inner half, it moves to centre the screen")
        assertEquals(9000..10_000, nextStyleWindow(null, 9500..10_000, 10_000), "clamped at the end")
        val atEnd = nextStyleWindow(null, 9500..10_000, 10_000)
        assertEquals(atEnd, nextStyleWindow(atEnd, 9600..10_000, 10_000), "held at the end of the text")
    }
}
