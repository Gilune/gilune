// A long note is laid out a slice at a time (ADR 0032 stage 4), while the
// field's value stays the whole buffer. So what a person does with the
// whole note — go to its far end, select all of it, type over it, undo —
// must behave as it did, while what is laid out stays a slice.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NoteSliceUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val body = (1..2_000).joinToString("\n") { i ->
        if (i % 7 == 0) "## Heading $i" else "- [ ] line $i of a long note with words in it"
    }

    private lateinit var state: GiluneState

    private fun open(): EditorState {
        val vault = Files.createTempDirectory("gilune-slice").toFile()
        vault.resolve("Captures").mkdirs()
        vault.resolve("Captures/long.md").writeText("---\ngilune-id: abc\n---\n$body\n")
        state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(vault.absolutePath, "Captures/long.md")
        editor.reading = false
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.size(900.dp, 700.dp)) { NoteEditor(state, editor, Modifier.size(900.dp, 700.dp)) }
            }
        }
        compose.waitForIdle()
        return editor
    }

    private fun laidOut(): String {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("note-body").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().layoutInput.text.text
    }

    @Test
    fun aLongNoteLaysOutASliceAndGoingToItsEndLaysOutTheEnd() {
        val editor = open()
        val shown = laidOut()
        assertTrue(shown.length < editor.text.text.length / 4, "laid out ${shown.length} of ${editor.text.text.length}")
        assertTrue("line 1 of" in shown)
        assertTrue("line 1999 of" !in shown)

        // A search hit or a task far down: the caret goes there, and the
        // slice follows it.
        state.reveal.value = NoteLine("Captures/long.md", 1_999)
        compose.waitForIdle()
        val end = laidOut()
        assertTrue("line 1999 of" in end, "the line is laid out once the caret is there")
        assertTrue("line 1 of" !in end)
        assertTrue(end.length < editor.text.text.length / 4)

        compose.onNodeWithTag("note-body").performTextInput(" typed here")
        compose.waitForIdle()
        val lines = editor.text.text.split('\n')
        assertTrue(lines[1_998].endsWith(" typed here"), lines[1_998])
        assertTrue(lines[0].startsWith("- [ ] line 1 of") && lines.size == body.split('\n').size + 1, "nothing else moved")
        assertTrue(" typed here" in laidOut())
    }

    @Test
    fun aSelectionOfTheWholeBufferIsTypedOverAndOneUndoRestoresIt() {
        val editor = open()
        val original = editor.text.text
        compose.onNodeWithTag("note-body").performClick()
        editor.text = editor.text.copy(selection = TextRange(0, original.length))
        compose.waitForIdle()

        compose.onNodeWithTag("note-body").performTextInput("all gone")
        compose.waitForIdle()
        assertEquals("all gone", editor.text.text)

        compose.onNodeWithTag("note-body").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        compose.waitForIdle()
        assertEquals(original, editor.text.text, "one undo brings the whole note back")
    }
}
