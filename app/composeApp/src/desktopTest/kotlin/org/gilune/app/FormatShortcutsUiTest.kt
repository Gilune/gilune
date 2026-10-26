// Formatting from the keyboard through the real editor (owner ask,
// 2026-10-26): Ctrl+B, Ctrl+I and the rest write the phone toolbar's
// Markdown at the selection, and a second press takes it off. The step past
// the obvious: italic inside bold adds italic rather than eating a star, and
// one Ctrl+Z puts the text back.

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
import org.junit.Rule
import org.junit.rules.RuleChain
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FormatShortcutsUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private fun editorOn(text: String, selection: TextRange): EditorState {
        val vault = resources.directory("gilune-format-shortcuts")
        vault.resolve("note.md").writeText("---\ngilune-id: abc\n---\n$text")
        val state = GiluneState(vault.absolutePath, resources.scope)
        val editor = EditorState()
        editor.open(vault.absolutePath, "note.md")
        editor.reading = false
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithTag("note-body").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.runOnIdle { editor.text = TextFieldValue(text, selection) }
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
    fun ctrlBBoldsTheSelectionAndASecondPressTakesItOff() {
        val editor = editorOn("milk and bread", TextRange(0, 4))

        press(Key.CtrlLeft.keyCode, Key.B.keyCode)
        assertEquals("**milk** and bread", editor.text.text)
        assertEquals(TextRange(2, 6), editor.text.selection, "the selection stays on the words")
        assertEquals(true, editor.dirty)

        press(Key.CtrlLeft.keyCode, Key.B.keyCode)
        assertEquals("milk and bread", editor.text.text)
        assertEquals(TextRange(0, 4), editor.text.selection)
    }

    @Test
    fun ctrlIInsideBoldAddsItalicAndKeepsTheBold() {
        val editor = editorOn("**milk** and bread", TextRange(2, 6))

        press(Key.CtrlLeft.keyCode, Key.I.keyCode)
        assertEquals("***milk*** and bread", editor.text.text)

        press(Key.CtrlLeft.keyCode, Key.I.keyCode)
        assertEquals("**milk** and bread", editor.text.text)
    }

    @Test
    fun strikeCodeAndLinkWriteTheirMarkdown() {
        val editor = editorOn("milk", TextRange(0, 4))

        press(Key.CtrlLeft.keyCode, Key.ShiftLeft.keyCode, Key.X.keyCode)
        assertEquals("~~milk~~", editor.text.text, "Ctrl+Shift+X strikes rather than cutting")

        compose.runOnIdle { editor.text = TextFieldValue("milk", TextRange(0, 4)) }
        press(Key.CtrlLeft.keyCode, Key.ShiftLeft.keyCode, Key.C.keyCode)
        assertEquals("`milk`", editor.text.text)

        compose.runOnIdle { editor.text = TextFieldValue("see milk", TextRange(4, 8)) }
        press(Key.CtrlLeft.keyCode, Key.K.keyCode)
        assertEquals("see [milk]()", editor.text.text)
        assertEquals(TextRange(11), editor.text.selection, "the cursor waits for the address")
    }

    @Test
    fun altArrowsMoveTheLineAndBracketsNestIt() {
        val editor = editorOn("- one\n- two", TextRange(1))

        press(Key.AltLeft.keyCode, Key.DirectionDown.keyCode)
        assertEquals("- two\n- one", editor.text.text)

        press(Key.CtrlLeft.keyCode, Key.RightBracket.keyCode)
        assertEquals("- two\n  - one", editor.text.text)

        press(Key.CtrlLeft.keyCode, Key.LeftBracket.keyCode)
        assertEquals("- two\n- one", editor.text.text)
    }

    @Test
    fun oneUndoPutsTheTextBack() {
        val editor = editorOn("milk and bread", TextRange(0, 4))

        press(Key.CtrlLeft.keyCode, Key.B.keyCode)
        assertEquals("**milk** and bread", editor.text.text)

        press(Key.CtrlLeft.keyCode, Key.Z.keyCode)
        assertEquals("milk and bread", editor.text.text)
    }
}
