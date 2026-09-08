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
        compose.onNodeWithText("Read").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Read font size").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(24f) }
        compose.onNodeWithContentDescription("Read line height").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(36f) }
        compose.onNodeWithText("Reset Read defaults").performScrollTo().performClick()
        assertEquals(NoteTypeSettings.Read, appearance.reading)
        assertEquals(18, appearance.edit.size)
        compose.onNodeWithText("Note text", useUnmergedTree = true).performScrollTo()
        val screenshot = compose.onRoot().captureToImage()
        org.jetbrains.skia.Image.makeFromBitmap(screenshot.asSkiaBitmap()).use { image ->
            image.encodeToData()!!.use { data -> File("build/note-appearance-general.png").writeBytes(data.bytes) }
        }
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
