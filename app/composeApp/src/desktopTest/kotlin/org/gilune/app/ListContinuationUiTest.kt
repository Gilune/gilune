// Return continues a list, through the real field (owner rule,
// 2026-09-01). `ListContinuationTest` pins what the rule decides; this
// pins that the editor asks it at all — on the keystroke a person
// actually presses, with the buffer the field actually produced.
//
// The third test is the one worth having: the `/` menu also lives on
// Enter, and it must keep it. It does so structurally rather than by
// agreement — while a session is up the preview handler consumes the key
// and the field never sees a change to continue — which is exactly the
// kind of claim that is worth a test rather than a comment.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
class ListContinuationUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun editorOver(body: String, caret: Int): Triple<GiluneState, EditorState, File> {
        val vault = Files.createTempDirectory("gilune-continue").toFile()
        vault.resolve("Captures").mkdirs()
        val note = vault.resolve("Captures/note.md")
        note.writeText("---\ngilune-id: abc\n---\n$body")
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(vault.absolutePath, "Captures/note.md")
        editor.reading = false
        editor.text = TextFieldValue(body, TextRange(caret))
        return Triple(state, editor, note)
    }

    private fun show(state: GiluneState, editor: EditorState) {
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
    }

    private fun body() = compose.onNodeWithTag("note-body")

    private fun pressReturn() {
        body().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        body().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
    }

    @Test
    fun returnAfterATaskLineOpensAFreshUncheckedTask() {
        val (state, editor, note) = editorOver("- [ ] milk", caret = 10)
        show(state, editor)

        pressReturn()

        assertEquals("- [ ] milk\n- [ ] ", editor.text.text)
        assertEquals(TextRange(17), editor.text.selection, "the caret is past the new marker, ready to type")
        // Continuing is an ordinary edit, which is the whole of its
        // bookkeeping: it marks the buffer dirty and the idle autosave
        // carries it to disk like any other keystroke. Asserting the bytes
        // rather than the flag, because by the time a test can look the
        // save has usually already happened — which is the point.
        compose.waitUntil(timeoutMillis = 5_000) { note.readText().contains("- [ ] milk\n- [ ] ") }
    }

    @Test
    fun returnOnAnEmptyItemLetsGoOfTheList() {
        val (state, editor, _) = editorOver("- [ ] milk\n- [ ] ", caret = 17)
        show(state, editor)

        pressReturn()

        assertEquals("- [ ] milk\n", editor.text.text, "the empty marker went and left no blank line")
        assertEquals(TextRange(11), editor.text.selection, "the caret came back onto the freed line")
    }

    @Test
    fun returnOnAPlainBulletContinuesItAndReturnAgainEndsIt() {
        // The escape hatch reached the way a person reaches it: twice.
        val (state, editor, _) = editorOver("- milk", caret = 6)
        show(state, editor)

        pressReturn()
        assertEquals("- milk\n- ", editor.text.text)

        pressReturn()
        assertEquals("- milk\n", editor.text.text, "a list never traps you")
    }

    @Test
    fun theSuggestMenuKeepsItsEnterAndWritesADateRatherThanACheckbox() {
        val (state, editor, _) = editorOver("- [ ] milk ", caret = 11)
        state.plugins.add(installedTasksPlugin())
        show(state, editor)

        // `/`, Enter through the single field, `tom`, Enter — the `/`
        // menu's own path (ADR 0015 § 2), every step of it on the key the
        // continuation would otherwise take.
        body().performTextInput("/")
        compose.waitForFirstEditorOption("Due date")
        body().performKeyInput { pressKey(Key.Enter) }
        compose.waitForFirstEditorOption("today")
        assertEquals(1, compose.onAllNodesWithTag("suggest-popup").fetchSemanticsNodes().size)
        body().performTextInput("tom")
        compose.waitForFirstEditorOption("tomorrow")
        val beforeAcceptance = editor.text.text
        body().performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { editor.text.text != beforeAcceptance }

        assertTrue(
            Regex("""^- \[ ] milk due:\d{4}-\d{2}-\d{2} $""").matches(editor.text.text),
            "the date was written: <${editor.text.text}>",
        )
        assertFalse(editor.text.text.contains("\n"), "and no line was ever broken")
    }
}
