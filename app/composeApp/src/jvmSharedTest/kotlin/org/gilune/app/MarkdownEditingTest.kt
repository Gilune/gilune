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
    fun theCaretsLineIsFilteredOutBeforeAnyRegistrationIsAsked() {
        val asked = mutableListOf<String>()
        val spy = EditorDecoration(
            id = "spy",
            recognise = { line ->
                asked += line
                emptyList()
            },
        )
        transform("one\ntwo\nthree", caretLine = 1, decorations = listOf(spy))
        assertEquals(
            listOf("one", "three"),
            asked,
            "E3's raw-on-the-caret-line rule is the shell's to honour, not a registration's",
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
        assertNull(decorationAt(source, glyph, caretLine = null), "no caret, no decorations, no hit")
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

        // The caret's line is nobody's to decorate, the plugin's included.
        val onTheCaretLine = transform(source, caretLine = 1, decorations = decorations)
        assertEquals(source, onTheCaretLine.text.text)
        assertTrue(
            onTheCaretLine.text.spanStyles.none {
                it.item.color == palette.marker && source.substring(it.start, it.end) == token
            },
            "the line being edited shows its raw Markdown, styled by the core rules only",
        )

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
}
