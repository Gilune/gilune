// The document pane is the input surface. A note with one line in it
// still fills the pane, so a click in the empty space below the text
// starts editing there rather than doing nothing (ADR 0012).

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NoteEditorUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thePresentingChipReturnsToEditingAndTheMenuPresentsAgain() {
        val (vault, editor) = openPane(reading = true)
        val before = vault.resolve("Captures/note.md").readText()
        compose.onNodeWithTag("presenting").performClick()
        compose.onNodeWithTag("note-body").assertIsFocused()
        assertFalse(editor.reading)
        compose.onNodeWithTag("note-actions").performClick()
        compose.onNodeWithTag("toggle-presentation").performClick()
        assertTrue(editor.reading)
        assertEquals(before, vault.resolve("Captures/note.md").readText())
        assertFalse(editor.dirty)
    }

    @Test
    fun clicking_the_empty_area_below_the_text_starts_editing() {
        val vault = Files.createTempDirectory("gilune-editor").toFile()
        vault.resolve("Captures").mkdirs()
        vault.resolve("Captures/note.md").writeText("---\ngilune-id: abc\n---\nshort\n")
        // The pane reads only the vault path, the status line and the
        // change tick, so no vault has to be opened to render it.
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(vault.absolutePath, "Captures/note.md")
        editor.reading = false

        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }

        // The pane holds two editable fields (title and body); the body
        // carries the tag, so the title keeps its real semantics.
        val field = compose.onNodeWithTag("note-body")
        field.assertIsNotFocused()
        val textTop = field.fetchSemanticsNode().boundsInRoot.top
        // The status line moved out to the window scaffold (ADR 0014), so
        // the pane's own foot is the bottom of the composition.
        val paneBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val clickY = paneBottom - 24f
        assertTrue(clickY - textTop > 200f, "the click lands far below the one line this note holds")
        assertTrue(
            field.fetchSemanticsNode().boundsInRoot.bottom >= clickY,
            "the input surface reaches the bottom of the pane, not the bottom of the text",
        )

        compose.onRoot().performMouseInput { click(Offset(centerX, clickY)) }

        field.assertIsFocused()
    }

    /// A vault with one note, and the pane open on it in the given mode.
    private fun openPane(reading: Boolean): Pair<File, EditorState> {
        val vault = Files.createTempDirectory("gilune-editor").toFile()
        vault.resolve("Captures").mkdirs()
        vault.resolve("Captures/note.md").writeText("---\ngilune-id: abc\n---\nfirst line\nsecond\n")
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(vault.absolutePath, "Captures/note.md")
        editor.reading = reading
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
        return vault to editor
    }

    /// The owner's report (2026-09-04): Return in the title "doesn't do
    /// anything". With the name unchanged the rename commit returned early
    /// and the key was swallowed, so nothing moved. Now the caret goes
    /// into the body, at its start.
    @Test
    fun return_in_the_title_moves_the_caret_into_the_body() {
        val (_, editor) = openPane(reading = false)
        val title = compose.onNodeWithTag("note-title")
        val body = compose.onNodeWithTag("note-body")

        title.performClick()
        title.assertIsFocused()
        body.assertIsNotFocused()

        title.performKeyInput { pressKey(Key.Enter) }

        body.assertIsFocused()
        title.assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(TextRange(0), editor.text.selection, "the caret lands at the start of the body")
            assertFalse(editor.dirty, "moving the caret is not an edit")
        }
    }

    /// One step past the obvious: a name that DID change is renamed on
    /// disk by the same Return, and the caret still arrives in the body of
    /// the renamed note.
    @Test
    fun return_after_retyping_the_title_renames_the_note_and_still_enters_the_body() {
        val (vault, editor) = openPane(reading = false)
        val title = compose.onNodeWithTag("note-title")

        title.performClick()
        title.performTextClearance()
        title.performTextInput("Renamed note")
        title.performKeyInput { pressKey(Key.Enter) }

        compose.onNodeWithTag("note-body").assertIsFocused()
        compose.runOnIdle {
            assertTrue(vault.resolve("Captures/Renamed note.md").isFile, "the file follows the name")
            assertFalse(vault.resolve("Captures/note.md").exists())
            assertEquals("Captures/Renamed note.md", editor.openRelPath)
        }
    }

    @Test
    fun afterNamingANoteTheMouseCanEnterItsBodyEvenIfItOpenedInReadMode() {
        val (vault, editor) = openPane(reading = true)
        compose.onNodeWithTag("note-title").performClick().performTextClearance()
        compose.onNodeWithTag("note-title").performTextInput("Named with mouse")
        compose.onNodeWithTag("note-body").performMouseInput { click(center) }
        compose.onNodeWithTag("note-body").assertIsFocused().performTextInput("typed in body")
        assertEquals("Captures/Named with mouse.md", editor.openRelPath)
        assertTrue(vault.resolve("Captures/Named with mouse.md").isFile)
        assertTrue(editor.text.text.contains("typed in body"))
    }

    /// The adjacent case: a note being READ has no caret to move into, so
    /// Return in its title switches it to editing and then enters the body
    /// — entering the body is editing.
    @Test
    fun return_in_the_title_of_a_note_being_read_switches_to_editing_and_enters_the_body() {
        val (_, editor) = openPane(reading = true)
        val title = compose.onNodeWithTag("note-title")

        title.performClick()
        title.performKeyInput { pressKey(Key.Enter) }

        compose.runOnIdle { assertFalse(editor.reading, "Return in the title leaves reading mode") }
        compose.onNodeWithTag("note-body").assertIsFocused()
    }

    /// And the key that must NOT enter the body: Escape restores the name
    /// and leaves the caret where it was.
    @Test
    fun escape_in_the_title_restores_the_name_and_stays_put() {
        val (vault, _) = openPane(reading = false)
        val title = compose.onNodeWithTag("note-title")

        title.performClick()
        title.performTextInput(" changed")
        title.performKeyInput { pressKey(Key.Escape) }

        title.assertIsFocused()
        compose.onNodeWithTag("note-body").assertIsNotFocused()
        compose.runOnIdle { assertTrue(vault.resolve("Captures/note.md").isFile, "nothing was renamed") }
    }
}
