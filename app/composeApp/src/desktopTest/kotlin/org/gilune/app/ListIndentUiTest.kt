// Subtasks (owner, 2026-10-21): a task nested under another is an
// ordinary Markdown list nested two spaces in, which Obsidian reads the
// same way. Tab on a list line nests it under the one above, Shift+Tab
// lifts it back out, and Return on an empty nested item steps out a level
// before it lets go of the list — so writing a parent, its children, and
// the next parent is Return, Tab, Return, Return.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ListIndentUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun editorOver(body: String, caret: Int): EditorState {
        val vault = Files.createTempDirectory("gilune-indent").toFile()
        vault.resolve("note.md").writeText("---\ngilune-id: abc\n---\n$body")
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(vault.absolutePath, "note.md")
        editor.reading = false
        editor.text = TextFieldValue(body, TextRange(caret))
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
        body().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        return editor
    }

    private fun body() = compose.onNodeWithTag("note-body")

    private fun press(vararg codes: Long) {
        body().performKeyInput {
            val keys = codes.map { Key(it) }
            keys.dropLast(1).forEach { keyDown(it) }
            pressKey(keys.last())
            keys.dropLast(1).reversed().forEach { keyUp(it) }
        }
        compose.waitForIdle()
    }

    @Test
    fun tabNestsATaskUnderTheOneAboveAndShiftTabLiftsItOut() {
        val body = "- [ ] parent\n- [ ] child"
        val editor = editorOver(body, caret = body.length)

        press(Key.Tab.keyCode)
        assertEquals("- [ ] parent\n  - [ ] child", editor.text.text)
        assertEquals(TextRange(body.length + 2), editor.text.selection, "the caret moves with its text")

        press(Key.ShiftLeft.keyCode, Key.Tab.keyCode)
        assertEquals(body, editor.text.text)
        assertEquals(TextRange(body.length), editor.text.selection)
    }

    @Test
    fun writingAParentItsChildrenAndTheNextParentIsReturnTabReturnReturn() {
        val editor = editorOver("- [ ] parent", caret = 12)

        press(Key.Enter.keyCode)
        press(Key.Tab.keyCode)
        body().performTextInput("first child")
        press(Key.Enter.keyCode)
        assertEquals("- [ ] parent\n  - [ ] first child\n  - [ ] ", editor.text.text, "a child continues at its own depth")

        press(Key.Enter.keyCode)
        assertEquals("- [ ] parent\n  - [ ] first child\n- [ ] ", editor.text.text,
            "Return on an empty child steps out a level rather than ending the list")

        press(Key.Enter.keyCode)
        assertEquals("- [ ] parent\n  - [ ] first child\n", editor.text.text, "and at the top level it lets go, as before")
    }

    @Test
    fun tabOnSeveralSelectedLinesNestsEachListLine() {
        val body = "- [ ] parent\n- [ ] one\n- [ ] two\nafter"
        val editor = editorOver(body, caret = 0)
        compose.runOnIdle { editor.text = editor.text.copy(selection = TextRange(body.indexOf("one"), body.indexOf("two") + 1)) }

        press(Key.Tab.keyCode)
        assertEquals("- [ ] parent\n  - [ ] one\n  - [ ] two\nafter", editor.text.text, "prose lines are left alone")
    }

    @Test
    fun shiftReturnOnATaskStartsANoteUnderItAndReturnAfterTheNoteIsPlain() {
        val editor = editorOver("- [ ] Prepare the show", caret = 22)

        press(Key.ShiftLeft.keyCode, Key.Enter.keyCode)
        body().performTextInput("Need the samples.")
        assertEquals("- [ ] Prepare the show\n  Need the samples.", editor.text.text, "a note under the task, no checkbox")

        press(Key.ShiftLeft.keyCode, Key.Enter.keyCode)
        body().performTextInput("And the build.")
        assertEquals("- [ ] Prepare the show\n  Need the samples.\n  And the build.", editor.text.text, "the note runs on")
    }

    @Test
    fun tabOutsideAListStillTypesIndentation() {
        val editor = editorOver("plain", caret = 0)
        press(Key.Tab.keyCode)
        assertEquals("  plain", editor.text.text, "two spaces, the unit the lists use")
    }
}
