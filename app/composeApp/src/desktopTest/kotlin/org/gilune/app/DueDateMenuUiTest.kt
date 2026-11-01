// The due-date menu through the real editor (ADR 0015 amendment
// 2026-10-31): the arrow appears beside a hovered due date — on the line
// being written too — and level with the date; a choice moves the date and
// is on disk at once; the date shortcut opens the same menu on an undated
// task, where typed words give it a first date; and with the tasks plugin
// off there is neither arrow nor menu.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Rule
import org.junit.rules.RuleChain
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DueDateMenuUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private lateinit var file: File
    private val today = LocalDate.now()

    private fun editorOn(text: String, caret: Int, pluginState: String = "enabled"): EditorState {
        val vault = resources.directory("gilune-due-menu")
        file = vault.resolve("note.md")
        file.writeText("---\ngilune-id: abc\n---\n$text")
        val state = GiluneState(vault.absolutePath, resources.scope)
        state.plugins.add(installedTasksPlugin(state = pluginState))
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

    private fun bodyLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("note-body").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    /// Moves the pointer onto a character of the body, by its offset in the
    /// shown text (the caret's line is raw, so the token's line is too).
    private fun hoverOver(offset: Int) {
        val box = bodyLayout().getBoundingBox(offset)
        compose.onNodeWithTag("note-body").performMouseInput { moveTo(box.center) }
        compose.waitForIdle()
    }

    @Test
    fun theArrowSitsLevelWithTheDateAndAChoiceIsWrittenAtOnce() {
        val due = today.plusDays(3)
        val line = "- [ ] Call the plasterer due:$due"
        val editor = editorOn("$line\nsecond line", caret = 4)

        compose.onNodeWithTag("due-menu-arrow").assertDoesNotExist()
        hoverOver(line.indexOf("due:") + 5)
        val arrow = compose.onNodeWithTag("due-menu-arrow").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("note-body").fetchSemanticsNode().boundsInRoot

        // Beside the date and inside its line: after the last digit, and
        // centred where the digits are — half a cap height above the
        // baseline — rather than on the line box. The layout gives the line
        // box, not the glyphs, so the screenshot below is the eye's check.
        val layout = bodyLayout()
        val digit = layout.getBoundingBox(line.length - 1)
        val baseline = layout.getLineBaseline(0)
        val arrowCentre = arrow.center.y - body.top
        assertTrue(arrow.left - body.left >= digit.right, "the arrow starts after the date, not over it")
        assertTrue(arrow.top - body.top >= layout.getLineTop(0) - 1 && arrow.bottom - body.top <= layout.getLineBottom(0) + 1,
            "the arrow stays inside the date's line: $arrow in ${layout.getLineTop(0)}..${layout.getLineBottom(0)}")
        assertTrue(arrowCentre < baseline, "centred above the baseline, on the digits: $arrowCentre vs $baseline")
        assertTrue(abs(arrowCentre - (digit.top + digit.bottom) / 2) < arrow.height / 2, "level with the date: $arrowCentre vs $digit")

        File("build/reports").mkdirs()
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File("build/reports/due-menu-arrow.png"))

        // A mouse, as a person uses it: onto the arrow, then a click. The
        // pointer leaving the date for the arrow must not take the arrow away.
        compose.onNodeWithTag("due-menu-arrow").performMouseInput { moveTo(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("due-menu-arrow").assertExists("the arrow stays while the pointer is on it")
        compose.onNodeWithTag("due-menu-arrow").performMouseInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("token-menu").assertExists()
        ImageIO.write(compose.onNodeWithTag("token-menu").captureToImage().toAwtImage(), "png", File("build/reports/due-menu-open.png"))
        compose.onNodeWithTag("token-menu-item-0").performClick()
        compose.waitForIdle()

        val moved = "- [ ] Call the plasterer due:${due.plusDays(1)}"
        assertEquals("$moved\nsecond line", editor.text.text)
        assertTrue(file.readText().contains(moved), "a choice is an action and is written at once: ${file.readText()}")
        compose.onNodeWithTag("token-menu").assertDoesNotExist()
    }

    @Test
    fun theDateShortcutDatesAnUndatedTaskFromTypedWords() {
        val editor = editorOn("- [ ] Order skirting\nnext", caret = 8)

        compose.onNodeWithTag("note-body").performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); pressKey(Key.D); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        val title = compose.onNodeWithTag("token-menu-title").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        assertEquals("Add a due date", title)

        compose.onNodeWithTag("token-menu-field").performTextInput("+3d")
        compose.onNodeWithTag("token-menu-field").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals("- [ ] Order skirting due:${today.plusDays(3)}\nnext", editor.text.text)
    }

    @Test
    fun offATaskLineTheShortcutOpensNothing() {
        val editor = editorOn("plain prose\n", caret = 3)
        compose.onNodeWithTag("note-body").performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); pressKey(Key.D); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("token-menu").assertDoesNotExist()
        assertEquals("plain prose\n", editor.text.text)
    }

    @Test
    fun withTheTasksPluginOffThereIsNoArrowAndNoMenu() {
        val line = "- [ ] a due:${today.plusDays(3)}"
        editorOn(line, caret = 2, pluginState = "disabled")
        hoverOver(line.indexOf("due:") + 5)
        compose.onNodeWithTag("due-menu-arrow").assertDoesNotExist()
        compose.onNodeWithTag("note-body").performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); pressKey(Key.D); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("token-menu").assertDoesNotExist()
    }
}
