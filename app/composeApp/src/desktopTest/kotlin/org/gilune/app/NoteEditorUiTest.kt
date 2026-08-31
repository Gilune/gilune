// The document pane is the input surface. A note with one line in it
// still fills the pane, so a click in the empty space below the text
// starts editing there rather than doing nothing (ADR 0012).

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NoteEditorUiTest {
    @get:Rule
    val compose = createComposeRule()

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
        val paneBottom = compose.onNodeWithText("saved").fetchSemanticsNode().boundsInRoot.top
        val clickY = paneBottom - 24f
        assertTrue(clickY - textTop > 200f, "the click lands far below the one line this note holds")
        assertTrue(
            field.fetchSemanticsNode().boundsInRoot.bottom >= clickY,
            "the input surface reaches the bottom of the pane, not the bottom of the text",
        )

        compose.onRoot().performMouseInput { click(Offset(centerX, clickY)) }

        field.assertIsFocused()
    }
}
