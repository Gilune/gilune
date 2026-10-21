// The grey words in a real editor (ADR 0027 § 1): drawn in the field,
// never written by autosave.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import uniffi.gilune_ffi.LiveLine
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveDictationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun autosaveWritesSettledWordsAndNeverTheGreyOnes() {
        val vault = Files.createTempDirectory("gilune-live").toFile()
        vault.resolve("note.md").writeText("---\ngilune-id: n\n---\nNotes:")
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(vault.absolutePath, "note.md")
        editor.reading = false
        editor.text = editor.text.copy(selection = TextRange(editor.text.text.length))
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.size(width = 600.dp, height = 400.dp)) {
                    NoteEditor(state, editor, Modifier.size(width = 600.dp, height = 400.dp))
                }
            }
        }
        compose.runOnIdle {
            val session = editor.beginLive()
            editor.acceptLive(
                session,
                listOf(LiveLine(0u, "Met the", true), LiveLine(1u, "savela on", false)),
            )
        }
        compose.waitForIdle()

        val shown = compose.onNodeWithTag("note-body").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertTrue("savela on" in shown, "the grey words are drawn in the field: <$shown>")
        assertEquals("Notes: Met the", editor.text.text, "but they are not in the buffer")

        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        val onDisk = vault.resolve("note.md").readText()
        assertTrue(onDisk.endsWith("Notes: Met the"), "the settled words were saved: <$onDisk>")
        assertFalse("savela" in onDisk, "the grey words were not")
    }
}
