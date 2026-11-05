package org.gilune.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class NoteAppearanceUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(run: (File, GiluneState, NoteAppearance) -> Unit) {
        val root = Files.createTempDirectory("gilune-fonts").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { run(root, GiluneState(root.path, scope), NoteAppearance()) }
        finally { compose.setContent {}; scope.cancel(); root.deleteRecursively() }
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test fun generalSettingsHaveSeparateLivePreviewsAndResetOnlyTheSelectedMode() = fixture { root, state, appearance ->
        compose.setContent {
            CompositionLocalProvider(LocalNoteAppearance provides appearance) {
                GiluneTheme(false) { SettingsPane(state, root.path, {}, ThemeChoice.Light, {}, {}) }
            }
        }
        compose.onNodeWithText("General").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit font size").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(18f) }
        compose.onNodeWithContentDescription("Edit font").performScrollTo().performClick()
        compose.onNodeWithText("Serif").performClick()
        assertEquals(18, appearance.edit.size)
        assertEquals(NoteFont.Serif, appearance.edit.font)
        compose.onNodeWithTag("edit-font-preview", useUnmergedTree = true).performScrollTo()
        assertEquals(18.sp, layout(compose.onNodeWithTag("edit-font-preview", useUnmergedTree = true)).layoutInput.style.fontSize)
        compose.onNodeWithContentDescription("Edit characters per line").assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit limit line width").performScrollTo().performClick()
        assertTrue(appearance.edit.limitWidth)
        compose.onNodeWithContentDescription("Edit characters per line").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(72f) }
        assertEquals(72, appearance.edit.lineWidth)
        compose.onNodeWithContentDescription("Place text centre").performScrollTo().performClick()
        assertEquals(NoteAlign.Centre, appearance.align)
        compose.onNodeWithText("Presentation").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Presentation font size").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(24f) }
        compose.onNodeWithContentDescription("Presentation line height").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(36f) }
        compose.onNodeWithText("Reset Presentation defaults").performScrollTo().performClick()
        assertEquals(NoteTypeSettings.Read, appearance.reading)
        assertEquals(18, appearance.edit.size)
        compose.onNodeWithText("Note text", useUnmergedTree = true).performScrollTo()
        val screenshot = compose.onRoot().captureToImage()
        org.jetbrains.skia.Image.makeFromBitmap(screenshot.asSkiaBitmap()).use { image ->
            image.encodeToData()!!.use { data -> File("build/note-appearance-general.png").writeBytes(data.bytes) }
        }
    }

    // 40 words, 199 characters: twelve words make a 59-character line.
    private val words = List(40) { "word" }.joinToString(" ")

    private fun lineText(layout: TextLayoutResult, line: Int) =
        layout.layoutInput.text.text.substring(layout.getLineStart(line), layout.getLineEnd(line))

    private fun showEditor(state: GiluneState, editor: EditorState, appearance: NoteAppearance) {
        compose.setContent {
            CompositionLocalProvider(LocalNoteAppearance provides appearance) {
                GiluneTheme(false) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
    }

    @Test fun aLimitedLineWrapsAtItsCharacterCountAndLeftKeepsTheTextWhereItWas() = fixture { root, state, appearance ->
        root.resolve("note.md").writeText(words)
        val editor = EditorState().also { it.open(root.path, "note.md"); it.reading = false }
        showEditor(state, editor, appearance)
        val body = compose.onNodeWithTag("note-body")
        val leftBefore = body.getBoundsInRoot().left
        assertTrue(lineText(layout(body), 0).trimEnd().length > 60, "the test pane must be wider than the limit")
        compose.runOnIdle { appearance.update(false, appearance.edit.copy(limitWidth = true, lineWidth = 60)) }
        compose.waitForIdle()
        val limited = layout(body)
        for (line in 0 until limited.lineCount) assertTrue(lineText(limited, line).trimEnd().length <= 60)
        assertEquals(59, lineText(limited, 0).trimEnd().length)
        assertEquals(leftBefore, body.getBoundsInRoot().left)
        assertEquals(body.getBoundsInRoot().left, compose.onNodeWithTag("note-title").getBoundsInRoot().left)
        assertEquals(words, editor.document())
        assertFalse(editor.dirty)
    }

    @Test fun centredTextHasEvenMarginsAndTheTitleAndReadingFollowIt() = fixture { root, state, appearance ->
        root.resolve("note.md").writeText(words)
        val editor = EditorState().also { it.open(root.path, "note.md"); it.reading = false }
        appearance.update(false, appearance.edit.copy(limitWidth = true, lineWidth = 60))
        appearance.update(true, appearance.reading.copy(limitWidth = true, lineWidth = 50))
        appearance.updateAlign(NoteAlign.Centre)
        showEditor(state, editor, appearance)
        val pane = compose.onRoot().getBoundsInRoot()
        val body = compose.onNodeWithTag("note-body").getBoundsInRoot()
        val title = compose.onNodeWithTag("note-title").getBoundsInRoot()
        assertTrue(body.left > 100.dp)
        assertTrue((body.left - (pane.right - body.right)).value.let { kotlin.math.abs(it) } <= 1f)
        assertEquals(body.left, title.left)
        assertTrue(lineText(layout(compose.onNodeWithTag("note-body")), 0).trimEnd().length <= 60)
        val screenshot = compose.onRoot().captureToImage()
        org.jetbrains.skia.Image.makeFromBitmap(screenshot.asSkiaBitmap()).use { image ->
            image.encodeToData()!!.use { data -> File("build/note-column-centred.png").writeBytes(data.bytes) }
        }

        compose.runOnIdle { editor.reading = true }
        compose.waitForIdle()
        val paragraph = compose.onNodeWithText(words).getBoundsInRoot()
        val readTitle = compose.onNodeWithTag("note-title").getBoundsInRoot()
        // The presented body's padding is 4dp wider than the title's.
        assertTrue((paragraph.left - readTitle.left - 4.dp).value.let { kotlin.math.abs(it) } <= 1f)
        assertTrue((paragraph.left - (pane.right - paragraph.right)).value.let { kotlin.math.abs(it) } <= 1f)
    }

    @Test fun aClickInTheMarginPutsTheCaretOnTheLineBesideIt() = fixture { root, state, appearance ->
        root.resolve("note.md").writeText(words)
        val editor = EditorState().also { it.open(root.path, "note.md"); it.reading = false; it.text = it.text.copy(selection = TextRange(0)) }
        appearance.update(false, appearance.edit.copy(limitWidth = true, lineWidth = 60))
        appearance.updateAlign(NoteAlign.Centre)
        showEditor(state, editor, appearance)
        val placed = layout(compose.onNodeWithTag("note-body"))
        val scroll = compose.onNodeWithTag("note-scroll").getBoundsInRoot()
        val body = compose.onNodeWithTag("note-body").getBoundsInRoot()
        val density = compose.density
        val (x, top) = with(density) { 4.dp.toPx() to (body.top - scroll.top).toPx() }
        val y = top + (placed.getLineTop(2) + placed.getLineBottom(2)) / 2f
        compose.onNodeWithTag("note-scroll").performMouseInput { click(androidx.compose.ui.geometry.Offset(x, y)) }
        compose.waitForIdle()
        assertEquals(TextRange(placed.getLineStart(2)), editor.text.selection)
        compose.onNodeWithTag("note-body").assertIsFocused()
        // The right margin lands at the end of its line.
        val right = with(density) { (scroll.right - scroll.left - 4.dp).toPx() }
        compose.onNodeWithTag("note-scroll").performMouseInput { click(androidx.compose.ui.geometry.Offset(right, y)) }
        compose.waitForIdle()
        assertEquals(placed.getLineStart(2), editor.text.selection.start.let { placed.getLineStart(placed.getLineForOffset(it)) })
        assertEquals(words, editor.document())
    }

    @Test fun editorAndReaderUseTheirOwnStylesWithoutChangingTheNoteOrCaret() = fixture { root, state, appearance ->
        val source = "## Heading\n\nA paragraph.\n\nAnother paragraph."
        root.resolve("note.md").writeText(source)
        val editor = EditorState().also { it.open(root.path, "note.md"); it.reading = false; it.text = it.text.copy(selection = TextRange(4)) }
        compose.setContent {
            CompositionLocalProvider(LocalNoteAppearance provides appearance) {
                GiluneTheme(false) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
        compose.runOnIdle { appearance.update(false, NoteTypeSettings(NoteFont.Serif, 20, 32)) }
        val edit = layout(compose.onNodeWithTag("note-body"))
        assertEquals(20.sp, edit.layoutInput.style.fontSize)
        assertEquals(32.sp, edit.layoutInput.style.lineHeight)
        assertEquals(FontFamily.Serif, edit.layoutInput.style.fontFamily)
        assertTrue(edit.layoutInput.text.spanStyles.any { it.item.fontSize.isSp && it.item.fontSize.value > 20f })
        assertEquals(TextRange(4), editor.text.selection)
        compose.runOnIdle { appearance.update(true, NoteTypeSettings(NoteFont.SansSerif, 24, 38, 20)); editor.reading = true }
        val read = layout(compose.onNodeWithText("A paragraph."))
        assertEquals(24.sp, read.layoutInput.style.fontSize)
        assertEquals(38.sp, read.layoutInput.style.lineHeight)
        assertEquals(FontFamily.SansSerif, read.layoutInput.style.fontFamily)
        assertEquals(source, editor.document())
        assertEquals(source, root.resolve("note.md").readText())
        assertFalse(editor.dirty)
    }
}
