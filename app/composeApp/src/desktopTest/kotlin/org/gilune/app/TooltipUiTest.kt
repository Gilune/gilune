// An icon alone names itself on hover (owner report, 2026-10-19). Pinned
// on a real control rather than the helper alone, so a site that drops
// its wrapper fails here; and pinned both sides of the delay, because a
// tooltip that rises the instant the mouse passes is noise of its own.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TooltipUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun restingOnTheNoteActionsGlyphNamesItAfterTheDelay() {
        val vault = Files.createTempDirectory("gilune-tooltip").toFile()
        vault.resolve("Captures").mkdirs()
        vault.resolve("Captures/note.md").writeText("---\ngilune-id: abc\n---\nhello")
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(vault.absolutePath, "Captures/note.md")
        editor.reading = false
        editor.text = TextFieldValue("hello", TextRange(0))
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("note-actions").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(TOOLTIP_DELAY_MS / 2L)
        assertEquals(0, compose.onAllNodesWithText("Note actions").fetchSemanticsNodes().size,
            "passing over the glyph raises nothing")

        compose.mainClock.advanceTimeBy(TOOLTIP_DELAY_MS.toLong())
        assertEquals(1, compose.onAllNodesWithText("Note actions").fetchSemanticsNodes().size,
            "resting on it names it")
    }
}
