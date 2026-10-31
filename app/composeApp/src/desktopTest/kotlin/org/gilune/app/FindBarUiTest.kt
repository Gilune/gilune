// The find bar over a note (ADR 0029 § 3), through the real editor. The
// step past the obvious: typing finds from where the caret was, stepping
// wraps, Replace on an unselected match only selects it, Replace all is
// one Ctrl+Z, a bad pattern is said, and with the plugin off there is no
// bar however it was asked for.

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.gilune.plugins.findreplace.FIND_REPLACE_PLUGIN
import org.junit.Rule
import org.junit.rules.RuleChain
import uniffi.gilune_ffi.Gilune
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FindBarUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private fun editorOver(body: String, caret: Int, pluginOn: Boolean = true): EditorState {
        val root = resources.directory("gilune-find-bar")
        val kernel = resources.own(Gilune.open(root.path))
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        root.resolve("note.md").writeText("---\ngilune-id: note\n---\n$body")
        val state = GiluneState(root.path, resources.scope)
        state.gilune.value = kernel
        state.plugins.addAll(kernel.plugins().filter { pluginOn || it.pluginId != FIND_REPLACE_PLUGIN })
        val editor = EditorState()
        editor.open(root.path, "note.md")
        editor.reading = false
        editor.text = TextFieldValue(body, TextRange(caret))
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.size(width = 700.dp, height = 400.dp)) {
                    NoteEditor(state, editor, Modifier.size(width = 700.dp, height = 400.dp))
                }
            }
        }
        compose.waitForIdle()
        return editor
    }

    private fun status() = compose.onNodeWithTag("find-status").fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performTextInput(text)
        compose.waitForIdle()
    }

    private fun press(tag: String, key: Key, shift: Boolean = false) {
        compose.onNodeWithTag(tag).performKeyInput { if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun typingFindsFromTheCaretAndEnterStepsAndWraps() {
        val editor = editorOver("milk, bread, milk, eggs, milk", caret = 8)
        compose.runOnIdle { editor.find.show(replace = false, selected = "") }
        compose.waitForIdle()
        type("find-pattern", "milk")

        assertEquals(TextRange(13, 17), editor.text.selection, "the first match after the caret, not the first in the note")
        assertEquals("2 of 3", status())

        press("find-pattern", Key.Enter)
        assertEquals(TextRange(25, 29), editor.text.selection)
        press("find-pattern", Key.Enter)
        assertEquals(TextRange(0, 4), editor.text.selection, "past the last wraps to the first")
        press("find-pattern", Key.Enter, shift = true)
        assertEquals(TextRange(25, 29), editor.text.selection, "and back again")
        assertEquals("milk, bread, milk, eggs, milk", editor.text.text, "finding never writes")
        assertEquals(false, editor.dirty)
    }

    @Test
    fun replaceChangesTheCurrentMatchAndSelectsTheNext() {
        val editor = editorOver("a cat, a cat, a cat", caret = 0)
        compose.runOnIdle { editor.find.show(replace = true, selected = "") }
        compose.waitForIdle()
        type("find-pattern", "cat")
        type("find-replacement", "dog")

        compose.onNodeWithTag("find-replace-one").performClick()
        compose.waitForIdle()
        assertEquals("a dog, a cat, a cat", editor.text.text)
        assertEquals(TextRange(9, 12), editor.text.selection, "the next match is selected, ready for the next press")
        assertTrue(editor.dirty)
    }

    @Test
    fun replaceOnAnUnselectedMatchOnlySelectsIt() {
        val editor = editorOver("a cat", caret = 0)
        compose.runOnIdle {
            editor.find.show(replace = true, selected = "")
            editor.find.pattern = TextFieldValue("cat")
            editor.find.replacement = "dog"
        }
        compose.waitForIdle()
        // Typing selected the match; move off it so nothing is current.
        compose.runOnIdle { editor.text = editor.text.copy(selection = TextRange(0)) }
        compose.waitForIdle()
        compose.onNodeWithTag("find-replace-one").performClick()
        compose.waitForIdle()
        assertEquals("a cat", editor.text.text, "the first press shows what the second will change")
        assertEquals(TextRange(2, 5), editor.text.selection)
    }

    @Test
    fun replaceAllIsOneUndo() {
        val editor = editorOver("Cat, cat and CAT.", caret = 0)
        compose.runOnIdle { editor.find.show(replace = true, selected = "") }
        compose.waitForIdle()
        type("find-pattern", "cat")
        type("find-replacement", "dog")
        compose.onNodeWithTag("find-replace-all").performClick()
        compose.waitForIdle()
        assertEquals("dog, dog and dog.", editor.text.text)

        compose.onNodeWithTag("note-body").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag("note-body").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        compose.waitForIdle()
        assertEquals("Cat, cat and CAT.", editor.text.text)
    }

    @Test
    fun regexGroupsAndABrokenPatternIsSaid() {
        val editor = editorOver("due 31/10 and 01/11", caret = 0)
        compose.runOnIdle {
            editor.find.show(replace = true, selected = "")
            editor.find.options = editor.find.options.copy(regex = true)
        }
        compose.waitForIdle()
        type("find-pattern", "(\\d\\d)/(\\d\\d")
        assertTrue(status().startsWith("not a valid pattern"), status())

        type("find-pattern", ")")
        type("find-replacement", "$2-$1")
        compose.onNodeWithTag("find-replace-all").performClick()
        compose.waitForIdle()
        assertEquals("due 10-31 and 11-01", editor.text.text)
    }

    @Test
    fun aOneLineSelectionSeedsThePatternAndEscKeepsTheMatchSelected() {
        val editor = editorOver("pick a colour, any colour", caret = 0)
        compose.runOnIdle {
            editor.text = editor.text.copy(selection = TextRange(7, 13))
            editor.find.show(replace = false, selected = "colour")
        }
        compose.waitForIdle()
        assertEquals("colour", editor.find.pattern.text)
        press("find-pattern", Key.Enter)
        assertEquals(TextRange(19, 25), editor.text.selection)
        press("find-pattern", Key.Escape)
        assertEquals(false, editor.find.open)
        assertEquals(0, compose.onAllNodesWithTag("find-bar").fetchSemanticsNodes().size)
        assertEquals(TextRange(19, 25), editor.text.selection, "the caret stays on the match")
    }

    @Test
    fun withThePluginOffThereIsNoBar() {
        val editor = editorOver("milk", caret = 0, pluginOn = false)
        compose.runOnIdle { editor.find.show(replace = true, selected = "") }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("find-bar").fetchSemanticsNodes().size)
        // The editor's first settle reads the vault off the UI thread; a test
        // this short otherwise ends inside it and the vault cannot be removed.
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }
}
