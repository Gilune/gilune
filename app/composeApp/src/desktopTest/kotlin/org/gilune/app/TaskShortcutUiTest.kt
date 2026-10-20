// The task shortcut through the real editor (user ask, 2026-10-20): Ctrl+L
// on a plain line makes it a task, and on a task ticks it through the same
// chain a click uses — `done:` stamped and the note on disk at once. The
// tasks plugin owns the keystroke, so the step past the obvious is a
// rebinding: once the settings name another key, Ctrl+L does nothing.

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
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.gilune.plugins.tasks.tasksSettingsDocument
import org.junit.Rule
import org.junit.rules.RuleChain
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TaskShortcutUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private lateinit var file: File

    private fun editorOn(text: String, caret: Int, settings: String = "", pluginState: String = "enabled"): EditorState {
        val vault = resources.directory("gilune-task-shortcut")
        file = vault.resolve("note.md")
        file.writeText("---\ngilune-id: abc\n---\n$text")
        val state = GiluneState(vault.absolutePath, resources.scope)
        state.plugins.add(installedTasksPlugin(state = pluginState, settings = settings))
        val editor = EditorState()
        editor.open(vault.absolutePath, "note.md")
        editor.reading = false
        editor.text = TextFieldValue(text, TextRange(caret))
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithTag("note-body").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        return editor
    }

    private fun press(vararg codes: Long) {
        val keys = codes.map { Key(it) }
        compose.onNodeWithTag("note-body").performKeyInput {
            keys.dropLast(1).forEach { keyDown(it) }
            pressKey(keys.last())
            keys.dropLast(1).reversed().forEach { keyUp(it) }
        }
        compose.waitForIdle()
    }

    @Test
    fun ctrlLMakesALineATaskThenTicksItAndTheTickIsOnDisk() {
        val editor = editorOn("milk", caret = 2)

        press(Key.CtrlLeft.keyCode, Key.L.keyCode)
        assertEquals("- [ ] milk", editor.text.text)
        assertEquals(TextRange(8), editor.text.selection, "the caret keeps its place in the word")

        press(Key.CtrlLeft.keyCode, Key.L.keyCode)
        val ticked = "- [x] milk done:${LocalDate.now()}"
        assertEquals(ticked, editor.text.text, "ticked through the click's chain, done: stamped")
        assertTrue(file.readText().endsWith(ticked), "a tick is an action and is written at once: ${file.readText()}")

        press(Key.CtrlLeft.keyCode, Key.L.keyCode)
        assertEquals("- [ ] milk", editor.text.text, "unticked, and the done date goes with it")
        assertTrue(file.readText().endsWith("\n- [ ] milk"), file.readText())
    }

    @Test
    fun aRebindingMovesTheShortcutAndCtrlLStops() {
        val editor = editorOn("milk", caret = 0, settings = tasksSettingsDocument("", shortcut = "Ctrl+Alt+T"))

        press(Key.CtrlLeft.keyCode, Key.L.keyCode)
        assertEquals("milk", editor.text.text, "Ctrl+L is no longer the binding")

        press(Key.CtrlLeft.keyCode, Key.AltLeft.keyCode, Key.T.keyCode)
        assertEquals("- [ ] milk", editor.text.text)
    }

    @Test
    fun withTheTasksPluginOffTheKeyDoesNothing() {
        val editor = editorOn("milk", caret = 0, pluginState = "disabled")

        press(Key.CtrlLeft.keyCode, Key.L.keyCode)
        assertEquals("milk", editor.text.text)
    }
}
