// The `/` menu inside the real editor (ADR 0015 § 2): typing the trigger
// on a task line opens it, Escape leaves the buffer exactly as typed, and
// Enter twice writes an ISO date the user never spelled out. The gate is
// checked from the other side too — with the plugin absent the same
// keystroke is just a character, which is what removability means.

package org.gilune.app

import org.gilune.plugins.tasks.tasksSettingsDocument

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
class TasksEditorUiTest {
    @get:Rule
    val compose = createComposeRule()

    /// A note holding one task line, with the caret parked at its end
    /// after a space — the word boundary § 2's trigger asks for.
    private fun editorOverTaskLine(): Pair<GiluneState, EditorState> {
        val vault = Files.createTempDirectory("gilune-tasks").toFile()
        vault.resolve("Captures").mkdirs()
        vault.resolve("Captures/note.md").writeText("---\ngilune-id: abc\n---\n- [ ] milk \n")
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(vault.absolutePath, "Captures/note.md")
        editor.reading = false
        editor.text = TextFieldValue("- [ ] milk ", TextRange(11))
        return state to editor
    }

    private fun show(state: GiluneState, editor: EditorState) {
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
    }

    private fun body() = compose.onNodeWithTag("note-body")

    /// The popup lives in its own composition root, so it is counted
    /// rather than asserted against — zero means dismissed.
    private fun menus(): Int =
        compose.onAllNodesWithTag("suggest-popup").fetchSemanticsNodes().size

    @Test
    fun typingTheTriggerOnATaskLineOpensTheMenu() {
        val (state, editor) = editorOverTaskLine()
        state.plugins.add(installedTasksPlugin())
        show(state, editor)

        body().performTextInput("/")
        compose.waitForIdle()

        assertEquals(1, menus(), "the caret popup is up")
        assertTrue(editor.text.text.endsWith("/"), "the trigger is the user's own byte and stays in the buffer")
    }

    @Test
    fun escapeDismissesTheMenuAndLeavesTheBufferExactlyAsTyped() {
        val (state, editor) = editorOverTaskLine()
        state.plugins.add(installedTasksPlugin())
        show(state, editor)

        body().performTextInput("/")
        compose.waitForIdle()
        assertEquals(1, menus())

        body().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()

        assertEquals(0, menus(), "the menu is gone")
        assertEquals("- [ ] milk /", editor.text.text, "nothing removed what the user typed")
    }

    @Test
    fun enterTwiceWritesAnIsoDateTheUserNeverSpelledOut() {
        val (state, editor) = editorOverTaskLine()
        state.plugins.add(installedTasksPlugin())
        show(state, editor)

        // `/`, Enter through the single field, `tom`, Enter — § 2's path.
        body().performTextInput("/")
        compose.waitForIdle()
        body().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(1, menus(), "stage one passed through to the field's values")

        body().performTextInput("tom")
        compose.waitForIdle()
        body().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()

        assertEquals(0, menus(), "accepting closes the menu")
        // The resolved date is whatever tomorrow is when the test runs, so
        // the shape is what is asserted — a phrase never reaches the file.
        assertTrue(
            Regex("""^- \[ ] milk due:\d{4}-\d{2}-\d{2} $""").matches(editor.text.text),
            "the token replaced the trigger and everything typed after it: <${editor.text.text}>",
        )
        assertFalse(editor.text.text.contains("tom"), "no natural language reached the buffer")
        // Writing a token is an edit like any other, so it takes the one
        // write path and idle autosave persists it — no special case, and
        // nothing the plugin does reaches the file on its own.
        val note = File(state.vaultDir, "Captures/note.md")
        compose.waitUntil(timeoutMillis = 5_000) { note.readText().contains("due:") }
        assertTrue(
            Regex("""due:\d{4}-\d{2}-\d{2}""").containsMatchIn(note.readText()),
            "the ISO date is on disk: <${note.readText()}>",
        )
    }

    @Test
    fun withTheTasksPluginAbsentTheSameKeystrokeIsJustACharacter() {
        val (state, editor) = editorOverTaskLine()
        show(state, editor)

        body().performTextInput("/")
        compose.waitForIdle()

        assertEquals(0, menus(), "no plugin, no menu")
        assertEquals("- [ ] milk /", editor.text.text, "and the character is simply typed")
    }

    @Test
    fun switchingThePluginOffTakesTheMenuAway() {
        val (state, editor) = editorOverTaskLine()
        state.plugins.add(installedTasksPlugin(state = "disabled"))
        show(state, editor)

        body().performTextInput("/")
        compose.waitForIdle()

        assertEquals(0, menus(), "a disabled publisher registers no field, so the trigger cannot fire")
    }

    /// § 1: "a change takes effect on the next recompute, rewriting
    /// nothing". Applying the setting is a plugin action, which refreshes
    /// the plugin list; everything downstream reads that list per
    /// composition, so there is nothing to restart. Verified here rather
    /// than assumed.
    @Test
    fun changingTheGlobalFilterTakesEffectWithoutARestart() {
        val (state, editor) = editorOverTaskLine()
        state.plugins.add(installedTasksPlugin(settings = tasksSettingsDocument("#task")))
        show(state, editor)

        // The line carries no #task, so under this filter it is not a task.
        body().performTextInput("/")
        compose.waitForIdle()
        assertEquals(0, menus(), "the filter is honoured")

        // What Settings ▸ Apply does: write the settings document, refresh
        // the plugin list. No relaunch, and no byte of the note touched.
        compose.runOnUiThread {
            editor.text = TextFieldValue("- [ ] milk ", TextRange(11))
            state.plugins[0] = installedTasksPlugin(settings = tasksSettingsDocument(""))
        }
        compose.waitForIdle()

        body().performTextInput("/")
        compose.waitForIdle()
        assertEquals(1, menus(), "the same buffer, the same session, the new setting")
    }

    @Test
    fun theMenuNeverOpensInsideAFencedCodeBlock() {
        val (state, editor) = editorOverTaskLine()
        state.plugins.add(installedTasksPlugin())
        editor.text = TextFieldValue("```\n- [ ] quoted \n```", TextRange(17))
        show(state, editor)

        body().performTextInput("/")
        compose.waitForIdle()

        assertEquals(0, menus(), "§ 1: a task line inside a fence is not a task")
    }

    // The fence rule is the caller's, because the session machine is
    // line-scoped and cannot see the lines above it. It is arithmetic over
    // the text, so it is worth asking directly as well.
    @Test
    fun theFenceGuardCountsDelimitersAboveTheCaret() {
        val document = "intro\n```\ninside\n```\nafter\n"
        assertFalse(caretIsInsideCodeFence(document, 0), "before any fence")
        assertFalse(caretIsInsideCodeFence(document, document.indexOf("```")), "on the opening delimiter")
        assertTrue(caretIsInsideCodeFence(document, document.indexOf("inside") + 3), "between the delimiters")
        assertFalse(caretIsInsideCodeFence(document, document.indexOf("after")), "after the block closes")
        assertFalse(caretIsInsideCodeFence(document, document.length), "and at the very end")
        assertTrue(caretIsInsideCodeFence("```\nstill open\n", 8), "an unclosed fence runs to the end")
        assertFalse(caretIsInsideCodeFence("", 5), "an out-of-range caret is answered, not thrown at")
    }
}
