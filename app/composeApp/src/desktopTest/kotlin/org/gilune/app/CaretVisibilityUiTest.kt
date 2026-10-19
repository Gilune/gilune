// The page follows the caret (owner report, 2026-10-19): Return at the
// foot of a long note used to put the caret below the pane, and the
// person had to scroll to see what they were typing. The body sits in a
// scroll container of its own, and nothing told it where the caret went.
//
// The second and third tests are the step past the obvious: following the
// caret must not become snapping to it. Scrolling away with the wheel
// leaves the page where it was put, and a change the field did not make
// (a ticked checkbox, dictation landing) does not drag the page back.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CaretVisibilityUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = (1..40).joinToString("\n") { "line $it" }

    private fun show(body: String, caret: Int): EditorState {
        val vault = Files.createTempDirectory("gilune-caret").toFile()
        vault.resolve("Captures").mkdirs()
        vault.resolve("Captures/note.md").writeText("---\ngilune-id: abc\n---\n$body")
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(vault.absolutePath, "Captures/note.md")
        editor.reading = false
        editor.text = TextFieldValue(body, TextRange(caret))
        compose.setContent {
            GiluneTheme(dark = false) {
                // A short pane, so forty lines overflow it by a long way.
                Box(Modifier.size(width = 600.dp, height = 320.dp)) {
                    NoteEditor(state, editor, Modifier.size(width = 600.dp, height = 320.dp))
                }
            }
        }
        compose.onNodeWithTag("note-body").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        return editor
    }

    private fun scroll(): Float =
        compose.onNodeWithTag("note-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun maxScroll(): Float =
        compose.onNodeWithTag("note-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    private fun pressReturn(times: Int) {
        repeat(times) {
            compose.onNodeWithTag("note-body").performKeyInput { pressKey(Key.Enter) }
            compose.waitForIdle()
        }
    }

    @Test
    fun returnBelowThePaneScrollsThePageToKeepTheCaretInView() {
        // The caret on the last line: focusing the field shows it, so the
        // page starts at its foot — and every Return grows the foot.
        show(lines, caret = lines.length)
        val before = maxScroll()
        assertTrue(before > 0f, "forty lines overflow the pane")

        pressReturn(3)

        assertTrue(maxScroll() > before, "three lines more to scroll")
        assertEquals(maxScroll(), scroll(), 1f, "the caret is on the last line, so the page is at its foot")
    }

    @Test
    fun theWheelCanStillTakeThePageAwayFromTheCaret() {
        show(lines, caret = lines.length)
        pressReturn(1)
        val atCaret = scroll()
        assertTrue(atCaret > 0f)

        compose.onNodeWithTag("note-scroll").performMouseInput { moveTo(center); scroll(-5f) }
        compose.waitForIdle()

        assertTrue(scroll() < atCaret, "the wheel moved the page up, and it stayed there: ${scroll()} vs $atCaret of ${maxScroll()}")
    }

    @Test
    fun aChangeTheFieldDidNotMakeLeavesThePageWhereItIs() {
        // The caret is at the top; the person has scrolled to the foot.
        val editor = show(lines, caret = 0)
        compose.onNodeWithTag("note-scroll").performMouseInput { moveTo(center); scroll(40f) }
        compose.waitForIdle()
        val read = scroll()
        assertTrue(read > 0f)

        // The path a ticked checkbox and landing dictation take: the
        // buffer is replaced from outside the field.
        compose.runOnIdle { editor.text = editor.text.copy(text = editor.text.text + "\nmore") }
        compose.waitForIdle()

        assertEquals(read, scroll(), 1f, "the page stayed where the person was reading")
    }
}
