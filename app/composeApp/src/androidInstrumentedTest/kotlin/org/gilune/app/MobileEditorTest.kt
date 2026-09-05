// The mobile editor's arithmetic (ADR 0017 § 2 and § 3). No screen, no
// keyboard: these are the claims that must hold before any of it is
// drawn, and they are the ones a regression would silently break.
//
// The byte-invariance claim is stated carefully. On the modern API an
// `OutputTransformation` **cannot** change the document — the buffer it
// writes to is not the `TextFieldState`, which is MS1's second finding and
// the reason the API was chosen. So there is no "did it change the bytes"
// test to write here that the type system has not already won; the test
// worth writing is the one about the *rendering*, and about the conditions
// the rendering must satisfy for the IME's sake.

package org.gilune.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.isUnspecified
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalFoundationApi::class)
class MobileEditorTest {

    // ------------------------------------------------- what the field shows

    @Test
    fun theOutputTransformationRendersExactlyWhatTheSharedTransformationDoes() {
        // The field's text comes from `MarkdownOutputTransformation`; the
        // spans painted over it come from the shared
        // `MarkdownEditTransformation`. If those two ever disagreed about
        // the string, the paint would sit beside the glyphs rather than on
        // them — so they are pinned against each other over a corpus that
        // includes every shape the recognisers argue about.
        for (source in CORPUS) {
            for (decorations in listOf(MOBILE, MOBILE_WITH_TASKS)) {
                val shared = MarkdownEditTransformation(PALETTE, null, decorations)
                    .filter(AnnotatedString(source)).text.text
                assertEquals(shared, renderedEditorText(source, decorations), "rendering of:\n$source")
            }
        }
    }

    @Test
    fun everyTaskMarkerRendersAsAGlyphIncludingTheCaretLines() {
        // § 2's always-glyph rule, which is also what designs the tap trap
        // out: the transformation is never asked where the caret is, so
        // there is no line on which the target vanishes under the finger
        // that aimed at it.
        val rendered = renderedEditorText("- [ ] milk\n- [x] bread\n", MOBILE)
        assertEquals("- ☐ milk\n- ☑ bread\n", rendered)
    }

    @Test
    fun theOffsetTableDoesNotChangeShapeAsTheCaretMoves() {
        // The mechanical claim § 2 rests on, and the largest single
        // reduction in the risk MS1 measured: with one caret-independent
        // decoration the edits are the same list wherever the caret is.
        val source = "- [ ] milk\n- [x] bread\n"
        val edits = editorGlyphEdits(source, MOBILE)
        assertEquals(2, edits.size)
        assertEquals(listOf(2..4, 13..15), edits.map { it.first })
        assertEquals(listOf("☐", "☑"), edits.map { it.second })
    }

    @Test
    fun noReplacementEverSitsInsideAWord() {
        // MS1's second condition, as a test. On the modern API the IME is
        // shown the transformed text, so a glyph inside a word would land
        // inside the keyboard's dictionary lookups.
        for (source in CORPUS + listOf("- [ ]milk\n", "- [x]done\n", "  * [ ]x")) {
            for ((range, _) in editorGlyphEdits(source, MOBILE_WITH_TASKS)) {
                val line = source.substring(lineStartAt(source, range.first), lineEndAt(source, range.first))
                val at = range.first - lineStartAt(source, range.first)
                val end = at + (range.last - range.first)
                assertTrue(at == 0 || line[at - 1].isWhitespace(), "opens at a boundary in “$line”")
                assertTrue(
                    end >= line.length - 1 || line[end + 1].isWhitespace(),
                    "closes at a boundary in “$line”",
                )
            }
        }
    }

    @Test
    fun theMobileShellDeclinesAMarkerTheSharedRuleWouldRender() {
        // The one place mobile deliberately renders less than the desktop,
        // and why: `- [ ]milk` is a task line to the shared recogniser but
        // an undelimited glyph to a keyboard.
        assertEquals("- ☐milk", renderedEditorText("- [ ]milk", builtInDecorations()))
        assertEquals("- [ ]milk", renderedEditorText("- [ ]milk", MOBILE))
        // And a properly written one is untouched by the rule.
        assertEquals("- ☐ milk", renderedEditorText("- [ ] milk", MOBILE))
    }

    // ------------------------------------------------------------- styling

    @Test
    fun stylingNeverCarriesASpanThatCouldMoveAGlyph() {
        // The invariant the whole overlay technique rests on: the field
        // owns the layout, the paint is a sibling, and a span that changed
        // an advance would put the visible text somewhere the caret is
        // not. Size, weight, slant, letter spacing and family must never
        // reach the overlay — which is exactly why heading size and bold
        // weight are not rendered in Edit (see MobileEditor.kt's header).
        for (source in CORPUS) {
            for (caret in listOf(null, 0, 1, 2)) {
                val styled = editorStyling(source, PALETTE, REVEALED, caret, MOBILE_WITH_TASKS)
                for (span in styled.spanStyles) {
                    assertTrue(span.item.fontSize.isUnspecified, "font size in:\n$source")
                    assertNull(span.item.fontWeight, "font weight in:\n$source")
                    assertNull(span.item.fontStyle, "font style in:\n$source")
                    assertTrue(span.item.letterSpacing.isUnspecified, "letter spacing in:\n$source")
                    assertNull(span.item.fontFamily, "font family in:\n$source")
                }
            }
        }
    }

    @Test
    fun stylingIsAppliedOverTheRenderedTextNotTheSource() {
        val source = "- [ ] milk\n"
        val styled = editorStyling(source, PALETTE, REVEALED, null, MOBILE)
        assertEquals(renderedEditorText(source, MOBILE), styled.text)
    }

    @Test
    fun revealIsAContrastChangeOnTheCaretsLineAndNothingElse() {
        // § 2: "dimmed to bright, never absent to present". The rendered
        // string is identical either way — that is the no-reflow law —
        // and the only difference is the colour the markers take. Two
        // heading lines, because a heading's `#` is a marker that is
        // always present and always dimmed.
        val source = "# One\n# Two\n"
        val away = editorStyling(source, PALETTE, REVEALED, 1, MOBILE)
        val none = editorStyling(source, PALETTE, REVEALED, null, MOBILE)
        assertEquals(none.text, away.text, "revealing changed the text")
        assertEquals(source, away.text, "and nothing was replaced at all")

        // Line 1 is the caret's, so its `#` takes the revealed contrast;
        // line 0's keeps the dimmed one.
        assertEquals(PALETTE.muted, colourAt(none, 0), "line 0 marker, no caret")
        assertEquals(PALETTE.muted, colourAt(none, 6), "line 1 marker, no caret")
        assertEquals(PALETTE.muted, colourAt(away, 0), "line 0 marker, caret elsewhere")
        assertEquals(REVEALED.muted, colourAt(away, 6), "the caret's line is revealed")
        assertFalse(
            none.spanStyles.any { it.item.color == REVEALED.muted },
            "nothing is revealed while the buffer is unfocused",
        )
    }

    @Test
    fun aDueTokenIsStyledAndTheDocumentIsNotTouched() {
        // ADR 0015 § 1's zero-delta token: it renders as itself and only
        // takes a colour, so `due:2026-09-05` reads identically in
        // Notepad.
        val source = "- [ ] milk due:2026-09-05\n"
        val styled = editorStyling(source, PALETTE, REVEALED, null, MOBILE_WITH_TASKS)
        assertTrue(styled.text.contains("due:2026-09-05"), "the token is its own bytes")
        val token = styled.text.indexOf("due:")
        assertTrue(
            styled.spanStyles.any { it.start <= token && it.end >= token + 4 && it.item.color == PALETTE.marker },
            "the token is coloured",
        )
    }

    // ---------------------------------------------------- toolbar arithmetic

    @Test
    fun boldWrapsTheSelectionAndUnwrapsItAgain() {
        val wrapped = emphasisEdit("milk and bread", TextRange(0, 4), "**")
        assertEquals(BufferEdit(0, 4, "**milk**", TextRange(2, 6)), wrapped)
        assertEquals("**milk** and bread", applied("milk and bread", wrapped))

        val unwrapped = emphasisEdit("**milk** and bread", TextRange(2, 6), "**")
        assertEquals("milk and bread", applied("**milk** and bread", unwrapped))
        assertEquals(TextRange(0, 4), unwrapped.selection)
    }

    @Test
    fun boldWithNoSelectionTakesTheWordUnderTheCaret() {
        val edit = emphasisEdit("milk and bread", TextRange(6), "**")
        assertEquals("milk **and** bread", applied("milk and bread", edit))
    }

    @Test
    fun boldOnAnEmptyLineWritesTheMarkersAndSitsBetweenThem() {
        val edit = emphasisEdit("", TextRange(0), "**")
        assertEquals("****", applied("", edit))
        assertEquals(TextRange(2), edit.selection)
    }

    @Test
    fun italicAndStrikeAndCodeAreTheSameToggle() {
        assertEquals("*milk*", applied("milk", emphasisEdit("milk", TextRange(0, 4), "*")))
        assertEquals("~~milk~~", applied("milk", emphasisEdit("milk", TextRange(0, 4), "~~")))
        assertEquals("`milk`", applied("milk", emphasisEdit("milk", TextRange(0, 4), "`")))
    }

    @Test
    fun theListButtonTogglesABulletOnEveryLineItTouches() {
        val on = bulletEdit("milk\nbread", TextRange(0, 10))
        assertEquals("- milk\n- bread", applied("milk\nbread", on))
        val off = bulletEdit("- milk\n- bread", TextRange(0, 14))
        assertEquals("milk\nbread", applied("- milk\n- bread", off))
    }

    @Test
    fun indentAddsTwoSpacesAndOutdentNeverEatsACharacter() {
        assertEquals("  milk", applied("milk", indentEdit("milk", TextRange(0), outdent = false)))
        assertEquals("milk", applied("  milk", indentEdit("  milk", TextRange(0), outdent = true)))
        // Nothing to remove: the line comes back exactly as it was.
        assertEquals("milk", applied("milk", indentEdit("milk", TextRange(0), outdent = true)))
    }

    @Test
    fun headingsToggleAtTheirOwnLevel() {
        assertEquals("# Title", applied("Title", headingEdit("Title", TextRange(0), 1)))
        assertEquals("## Title", applied("# Title", headingEdit("# Title", TextRange(0), 2)))
        assertEquals("Title", applied("# Title", headingEdit("# Title", TextRange(0), 1)))
    }

    @Test
    fun theCheckboxButtonMakesALineATaskAndThenTogglesIt() {
        val made = checkboxEdit("milk", TextRange(0), MOBILE)
        assertEquals("- [ ] milk", applied("milk", assertNotNull(made)))
        val ticked = checkboxEdit("- [ ] milk", TextRange(0), MOBILE)
        assertEquals("- [x] milk", applied("- [ ] milk", assertNotNull(ticked)))
        // A bullet keeps its bullet rather than growing a second one.
        val bulleted = checkboxEdit("- milk", TextRange(0), MOBILE)
        assertEquals("- [ ] milk", applied("- milk", assertNotNull(bulleted)))
    }

    @Test
    fun tickingThroughTheToolbarStampsTheDoneDateJustAsATapDoes() {
        // The toolbar toggles through the shared `activateLine`, so the
        // tasks plugin's write-on-action applies identically to a button,
        // a tap on the glyph, and the desktop.
        val today = LocalDate.of(2026, 9, 1)
        val decorations = mobileEditorDecorations(builtInDecorations() + tasksDecorations("", today))
        val edit = assertNotNull(checkboxEdit("- [ ] milk", TextRange(0), decorations))
        assertEquals("- [x] milk done:2026-09-01", applied("- [ ] milk", edit))
    }

    @Test
    fun moveLineSwapsWithItsNeighbourAndCarriesTheCaret() {
        val down = assertNotNull(moveLineEdit("one\ntwo\nthree", TextRange(1), up = false))
        assertEquals("two\none\nthree", applied("one\ntwo\nthree", down))
        assertEquals(TextRange(5), down.selection)

        val up = assertNotNull(moveLineEdit("one\ntwo\nthree", TextRange(5), up = true))
        assertEquals("two\none\nthree", applied("one\ntwo\nthree", up))
        // The first line has nowhere to go, and says so rather than
        // rewriting the document to no effect.
        assertNull(moveLineEdit("one\ntwo", TextRange(0), up = true))
        assertNull(moveLineEdit("one\ntwo", TextRange(5), up = false))
    }

    @Test
    fun aLinkTakesTheSelectionAsItsLabelAndLandsTheCaretInTheDestination() {
        val withLabel = linkEdit("see milk", TextRange(4, 8))
        assertEquals("see [milk]()", applied("see milk", withLabel))
        assertEquals(TextRange(11), withLabel.selection)

        val empty = linkEdit("", TextRange(0))
        assertEquals("[]()", applied("", empty))
        assertEquals(TextRange(1), empty.selection)
    }

    @Test
    fun theSlashButtonSuppliesTheWordBoundaryTheTriggerNeeds() {
        // `SuggestSession.triggered` only fires at a word boundary, so a
        // `/` typed mid-word would open nothing.
        assertEquals("- [ ] milk /", applied("- [ ] milk ", slashEdit("- [ ] milk ", TextRange(11))))
        assertEquals("- [ ] milk /", applied("- [ ] milk", slashEdit("- [ ] milk", TextRange(10))))
        assertTrue(SuggestSession.triggered("- [ ] milk /", 12, ""))
    }

    // -------------------------------------------------- suggest, on the state

    @Test
    fun acceptingACandidateWritesTheTokenAndLeavesTheCaretAfterIt() {
        val state = TextFieldState("- [ ] milk /", initialSelection = TextRange(12))
        val session = assertNotNull(
            SuggestSession.openAt("- [ ] milk /", 12, FIELDS, "", TODAY),
            "the trigger opens on a task line",
        )
        // Stage one passes through to the field's values; stage two
        // writes. Tapping names a row, which is what ↑/↓ do on a desktop.
        val values = assertNotNull(acceptSuggestion(state, session, 0), "stage one advances")
        assertEquals(SuggestStage.VALUES, values.stage)
        assertEquals("- [ ] milk /", state.text.toString(), "advancing wrote nothing")

        val today = values.candidates.indexOfFirst { it.display.equals("today", ignoreCase = true) }
        assertTrue(today >= 0, "today is offered")
        assertNull(acceptSuggestion(state, values, today), "accepting ends the session")
        assertEquals("- [ ] milk due:2026-09-01 ", state.text.toString())
        assertEquals(TextRange(26), state.selection, "the caret is immediately after what was written")
    }

    @Test
    fun acceptanceIsExactlyTheSharedReplaceRangeMaths() {
        // The desktop writes an `Acceptance` through `replaceRange` over a
        // `TextFieldValue`; mobile writes it through `TextFieldState.edit`.
        // MS1's port note calls for an adapter, so the two are pinned
        // against each other rather than trusted to agree.
        val source = "- [ ] milk /du"
        val state = TextFieldState(source, initialSelection = TextRange(source.length))
        val session = assertNotNull(SuggestSession.openAt(source, 12, FIELDS, "", TODAY))
            .update("- [ ] milk /du", 14, TODAY)
        val values = assertNotNull(acceptSuggestion(state, assertNotNull(session), 0))
        val index = values.candidates.indexOfFirst { it.insert != null }
        val expected = replaceRange(
            androidx.compose.ui.text.input.TextFieldValue(source, TextRange(source.length)),
            values.replaceRange,
            "${values.candidates[index].insert} ",
        )
        acceptSuggestion(state, values, index)
        assertEquals(expected.text, state.text.toString())
        assertEquals(expected.selection, state.selection)
    }

    @Test
    fun theSuggesterIsWithheldInsideAFence() {
        val text = "```\n- [ ] milk /\n```"
        assertTrue(editorCaretInsideFence(text, 16), "the caret is inside the fence")
        assertFalse(editorCaretInsideFence("- [ ] milk /", 12))
    }

    // ------------------------------------------------------------- the file

    @Test
    fun theBufferHoldsTheBodyAndTheFrontMatterIsWrittenBackVerbatim() {
        val editor = MobileEditorState()
        val vault = java.nio.file.Files.createTempDirectory("gilune-editor").toFile()
        val note = java.io.File(vault, "note.md")
        note.writeText("---\ngilune-id: abc\ngilune-blob: k.wav\n---\n- [ ] milk\n")
        editor.open(vault.absolutePath, "note.md")

        assertEquals("- [ ] milk\n", editor.body.text.toString(), "the buffer is the body alone")
        assertFalse(editor.dirty)
        editor.body.edit { replace(0, length, "- [x] milk\n") }
        assertTrue(editor.dirty)
        assertTrue(editor.write(vault.absolutePath, "note.md"))
        assertEquals("---\ngilune-id: abc\ngilune-blob: k.wav\n---\n- [x] milk\n", note.readText())
    }

    @Test
    fun aFileThatMovedUnderTheBufferIsNotOverwritten() {
        val editor = MobileEditorState()
        val vault = java.nio.file.Files.createTempDirectory("gilune-stale").toFile()
        val note = java.io.File(vault, "note.md")
        note.writeText("---\ngilune-id: abc\n---\nmine\n")
        editor.open(vault.absolutePath, "note.md")
        editor.body.edit { replace(0, length, "mine, edited\n") }

        note.writeText("---\ngilune-id: abc\n---\ntheirs\n")
        assertFalse(editor.write(vault.absolutePath, "note.md"), "the guard refused")
        assertTrue(editor.stale)
        assertEquals("---\ngilune-id: abc\n---\ntheirs\n", note.readText(), "nothing was written")

        // Forcing is the answer the banner takes, and only it may pass.
        assertTrue(editor.write(vault.absolutePath, "note.md", force = true))
        assertEquals("---\ngilune-id: abc\n---\nmine, edited\n", note.readText())
    }

    @Test
    fun renamingANoteTakesItsAttachmentsWithIt() {
        val vault = java.nio.file.Files.createTempDirectory("gilune-rename").toFile()
        java.io.File(vault, "kitchen.md").writeText("---\ngilune-id: abc\n---\nhi\n")
        java.io.File(vault, "kitchen.wav").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals("Shopping.md", renameNote(vault.absolutePath, "kitchen.md", "Shopping"))
        assertTrue(java.io.File(vault, "Shopping.md").exists())
        assertTrue(java.io.File(vault, "Shopping.wav").exists(), "the audio followed its note")
        assertFalse(java.io.File(vault, "kitchen.wav").exists())

        // Refusals: a path, traversal, an empty name, and a name already taken.
        assertNull(renameNote(vault.absolutePath, "Shopping.md", "sub/other"))
        assertNull(renameNote(vault.absolutePath, "Shopping.md", ".."))
        assertNull(renameNote(vault.absolutePath, "Shopping.md", "  "))
        java.io.File(vault, "Taken.md").writeText("x")
        assertNull(renameNote(vault.absolutePath, "Shopping.md", "Taken"))
        assertEquals("Shopping.md", renameNote(vault.absolutePath, "Shopping.md", "Shopping"))
    }

    // ---------------------------------------------------------------- rig

    private fun applied(text: String, edit: BufferEdit): String =
        text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)

    private fun colourAt(styled: AnnotatedString, offset: Int): Color? =
        styled.spanStyles.lastOrNull { offset >= it.start && offset < it.end }?.item?.color

    companion object {
        private val TODAY: LocalDate = LocalDate.of(2026, 9, 1)
        private val MOBILE = mobileEditorDecorations(builtInDecorations())
        private val MOBILE_WITH_TASKS =
            mobileEditorDecorations(builtInDecorations() + tasksDecorations("", TODAY))
        private val FIELDS = listOf(assertNotNull(SuggestField("due", "Due date", dateValues("due"))))

        private val PALETTE = MarkdownPalette(
            muted = Color(0xFF808080),
            marker = Color(0xFF3F5B8B),
            heading = Color(0xFF102030),
            code = Color(0xFF204030),
            codeBackground = Color(0xFFEEEEEE),
            link = Color(0xFF0000EE),
        )
        private val REVEALED = PALETTE.copy(muted = Color(0xFFCCCCCC))

        /// Every shape the recognisers argue about: plain and indented
        /// tasks, both states, a done token, a fence (the checkbox fires
        /// inside one, the tasks plugin does not), front matter, and a
        /// `[ ]` that is not a task at all.
        private val CORPUS = listOf(
            "",
            "just prose\n",
            "- [ ] milk\n- [x] bread\n",
            "# Heading\n\n- [ ] milk due:2026-09-05\n- [x] bread done:2026-09-01\n",
            "  - [X] indented\n\t* [ ] tabbed\n",
            "```\n- [ ] inside a fence\n```\n- [ ] outside it\n",
            "> quoted **bold** and *italic* and `code`\n",
            "a [link](http://example.com) and a [ ] that is not a task\n",
            "- [ ] milk\n\n- [ ] bread with due:2026-09-05 in the middle of it\n",
        )
    }
}
