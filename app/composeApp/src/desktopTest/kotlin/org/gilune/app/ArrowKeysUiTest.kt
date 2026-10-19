// Down got "stuck from time to time" (owner report, 2026-10-19). The
// suggestion menu answers Up and Down while it is up, and it used to come
// up wherever the caret *landed* beside a trigger — so walking down a note
// through an @mention opened the people menu, and the next Down moved the
// menu's highlight instead of the caret. Nothing on screen said why.
//
// The menu now opens from typing, never from walking the caret; a menu
// someone did type up still keeps its arrows.
//
// The second cause was the field's own line jump: landing past a line's
// end (an empty line, a short one) it returned a *displayed* offset
// unmapped, so with task glyphs shortening the lines above, Down at the
// foot of a note fell back into the last task. The editor now makes the
// jump itself, and the column and Shift tests pin what taking the keys
// over must not lose.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.gilune.plugins.people.PERSON_TYPE
import org.junit.Rule
import org.junit.rules.RuleChain
import uniffi.gilune_ffi.Gilune
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ArrowKeysUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private fun editorOver(body: String, caret: Int): EditorState {
        val root = resources.directory("gilune-arrows")
        val kernel = resources.own(Gilune.open(root.path))
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.registerIndexers(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.initializePages(kernel)
        root.resolve("alice.md").writeText("---\ngilune-type: $PERSON_TYPE\ngilune-id: alice\nname: Alice Smith\nhandle: alice.smith\n---\n")
        kernel.reconcilePath("alice.md")
        root.resolve("note.md").writeText("---\ngilune-id: note\n---\n$body")
        val state = GiluneState(root.path, resources.scope)
        state.gilune.value = kernel
        state.plugins.addAll(kernel.plugins())
        val editor = EditorState()
        editor.open(root.path, "note.md")
        editor.reading = false
        editor.text = TextFieldValue(body, TextRange(caret))
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.size(width = 600.dp, height = 400.dp)) {
                    NoteEditor(state, editor, Modifier.size(width = 600.dp, height = 400.dp))
                }
            }
        }
        body().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        return editor
    }

    private fun body() = compose.onNodeWithTag("note-body")

    private fun press(key: Key) {
        body().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun lineOf(editor: EditorState): Int =
        editor.text.text.substring(0, editor.text.selection.start).count { it == '\n' }

    @Test
    fun downWalksThroughAnAtMentionWithoutTheMenuTakingTheKey() {
        // Column 3 of the first line lands inside "@al|ice.smith" below.
        val editor = editorOver("talk to\n@alice.smith later\nlast line", caret = 3)

        press(Key.DirectionDown)
        assertEquals(1, lineOf(editor), "onto the mention's line")
        // Long enough for a lookup to have answered, had one been asked.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("suggest-popup").fetchSemanticsNodes().size,
            "walking onto a mention opens no menu")

        press(Key.DirectionDown)
        assertEquals(2, lineOf(editor), "and the next Down leaves it")
    }

    @Test
    fun downOnTheLastLineBelowATaskStaysAtTheEnd() {
        // The owner's report, exactly: the caret on the empty line under
        // the note's last task, then Down. It used to jump up into the task.
        val body = "notes\n- [ ] first task\n- [ ] last task\n"
        val editor = editorOver(body, caret = body.length)

        press(Key.DirectionDown)

        assertEquals(body.length, editor.text.selection.start, "Down on the last line goes to its end, which is where it is")
    }

    @Test
    fun downAtTheFootOfANoteShapedLikeADailyNoteNeverClimbs() {
        val body = buildString {
            append("- [ ] an open task.\n\n# Heading\n\n")
            append("- [x] a ticked task. done:2026-10-19\n")
            append("- [x] another ticked task with more words.  done:2026-10-19\n\n")
            append("# Section\nplain words\n - indented bullet \n\n")
            append("- [ ] a task with @someone and @another \n\n#  Spaced heading\n")
            append("- [ ] task one.\n- [ ] task two.\nquestion? \n- a bullet\n-  \n\n\n")
            append("-  [ ] an odd task \n\n# More\n- [ ] the last task in the note.\n\n\n\n\n")
        }
        val editor = editorOver(body, caret = body.length)
        val lines = body.count { it == '\n' }
        // From every one of the empty lines at the foot, Down only ever
        // goes down or stays put.
        for (fromLine in lines downTo lines - 4) {
            val start = body.split('\n').take(fromLine).sumOf { it.length + 1 }
            compose.runOnIdle { editor.text = editor.text.copy(selection = TextRange(start)) }
            compose.waitForIdle()
            press(Key.DirectionDown)
            assertTrue(lineOf(editor) >= fromLine, "Down from line $fromLine went to line ${lineOf(editor)}")
        }
    }

    @Test
    fun theColumnSurvivesAPassThroughAShortLine() {
        // Taking the keys over must keep what the field did right: down
        // through an empty line and on, the caret comes out where it went in.
        val body = "a plain long line\n\nanother plain line\n- [ ] a task line here"
        val editor = editorOver(body, caret = 12)

        press(Key.DirectionDown)
        assertEquals(18, editor.text.selection.start, "the empty line's only place")
        press(Key.DirectionDown)
        assertEquals(18 + 1 + 12, editor.text.selection.start, "back at column 12")
    }

    @Test
    fun shiftDownExtendsTheSelection() {
        val body = "first line\nsecond line\nthird"
        val editor = editorOver(body, caret = 3)

        body().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.DirectionDown); keyUp(Key.ShiftLeft) }
        compose.waitForIdle()

        assertEquals(TextRange(3, 14), editor.text.selection, "anchored where it started, reaching the line below")
    }

    @Test
    fun downFromAnEmptyLineBetweenTasksGoesToTheNextLine() {
        val body = "- [ ] first task\n\n- [ ] second task\nafter"
        val editor = editorOver(body, caret = 17)

        press(Key.DirectionDown)
        assertEquals(2, lineOf(editor), "onto the second task")
        press(Key.DirectionDown)
        assertEquals(3, lineOf(editor), "and past it")
    }

    @Test
    fun aMenuSomeoneTypedUpStillKeepsItsArrows() {
        val editor = editorOver("talk to\n", caret = 8)

        body().performTextInput("@ali")
        compose.waitForFirstEditorOption("Alice Smith")
        val caret = editor.text.selection

        press(Key.DirectionDown)

        assertEquals(caret, editor.text.selection, "Down went to the menu, not the caret")
        assertTrue(compose.onAllNodesWithTag("suggest-popup").fetchSemanticsNodes().isNotEmpty(), "the menu is still up")
    }
}
