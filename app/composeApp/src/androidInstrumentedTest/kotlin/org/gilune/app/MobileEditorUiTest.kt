// The mobile editor on a device (ADR 0017 § 2, § 3; MB2). What is worth
// putting on an emulator is the half the arithmetic cannot reach: whether
// a tap on a glyph toggles the box **without moving the caret**, whether
// the `/` candidates really appear above the accessory row and write a
// real date, whether the bytes land on their own after a pause, and
// whether undo undoes an edit that arrived the way a keyboard sends one.
//
// The rendering claims stay in `MobileEditorTest` where they are exact.
// A screenshot cannot assert a colour table, and a pixel test would fail
// for reasons that are not the editor's.

package org.gilune.app

import org.gilune.plugins.tasks.TASKS_PLUGIN

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalFoundationApi::class)
class MobileEditorUiTest {
    private val rule = createComposeRule()

    // The test drives the real InputConnection. A second keyboard must not
    // independently select a composing word while the test inserts text.
    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(Ms1InputSessionRule()).around(rule)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun theRenderingNeverReachesTheDocument() {
        val (state, note) = vaultWith("- [ ] milk\n- [x] bread\n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }

        // The field is showing ☐ and ☑ — `MobileEditorTest` pins what the
        // transformation renders — and the document holds neither. On this
        // API that is a fact of the type, not a rule the shell keeps: the
        // buffer an output transformation writes to is not the state.
        assertEquals("- [ ] milk\n- [x] bread\n", editor.body.text.toString())
        assertEquals(NOTE_HEAD + "- [ ] milk\n- [x] bread\n", note.readText())
        rule.onNodeWithTag("note-body").assertExists()
        rule.onNodeWithTag("accessory-row").assertExists()
    }

    @Test
    fun tappingTheGlyphTogglesTheBoxAndTheCaretDoesNotMove() {
        val (state, note) = vaultWith("- [ ] milk\n- [ ] bread\n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }

        // A caret somewhere it would be obvious to lose: § 2's whole point
        // is that tapping a box is not editing, so nothing about the
        // insertion point may change.
        rule.runOnUiThread { editor.body.edit { selection = TextRange(18) } }
        rule.waitForIdle()
        val before = editor.body.selection

        // Inside the first glyph's 48dp box, which is centred on a glyph
        // sitting at the third column of the first line.
        rule.onNodeWithTag("note-body").performTouchInput { click(Offset(20f, 20f)) }
        rule.waitForIdle()

        assertEquals("- [x] milk\n- [ ] bread\n", editor.body.text.toString(), "the box was ticked")
        assertEquals(before, editor.body.selection, "the caret did not move")
        // Ticking writes at the toggle rather than waiting out the idle.
        rule.waitUntil(WAIT) { note.readText().contains("- [x] milk") }
    }

    @Test
    fun tappingAwayFromAGlyphTogglesNothing() {
        val (state, note) = vaultWith("- [ ] milk\n- [ ] bread\n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }

        // Well to the right of any checkbox: the field takes the tap and
        // places the caret, and no box changes state.
        rule.onNodeWithTag("note-body").performTouchInput { click(Offset(width - 8f, 20f)) }
        rule.waitForIdle()
        assertEquals("- [ ] milk\n- [ ] bread\n", editor.body.text.toString())
        assertEquals(NOTE_HEAD + "- [ ] milk\n- [ ] bread\n", note.readText())
    }

    @Test
    fun theSlashMenuOffersCandidatesAboveTheRowAndWritesARealDate() {
        // The one test that needs the kernel: stage one is read from the
        // plugin registry, so a hard-coded field list would prove nothing
        // about the seam.
        val (state, note) = vaultWith("- [ ] milk \n")
        state.openVault()
        rule.waitUntil(WAIT) { state.plugins.any { it.pluginId == TASKS_PLUGIN && it.state == "enabled" } }
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }
        rule.runOnUiThread { editor.body.edit { selection = TextRange(11) } }

        // The row's `/` types the trigger the shared machine listens for.
        rule.onNodeWithTag("slash").performClick()
        rule.waitUntil(WAIT) {
            rule.onAllNodesWithTag("editor-completions").fetchSemanticsNodes().isNotEmpty()
        }
        // Stage one is the registered field; tapping it is Enter.
        rule.onNodeWithText("Due date").performClick()
        rule.waitUntil(WAIT) {
            rule.onAllNodes(hasText("today", ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("today", ignoreCase = true).performClick()
        rule.waitForIdle()

        val today = LocalDate.now().toString()
        rule.waitUntil(WAIT) { editor.body.text.toString().contains("due:$today") }
        assertTrue(
            editor.body.text.toString().contains("due:$today"),
            "the token was written: ${editor.body.text}",
        )
        assertFalse(editor.body.text.toString().contains("/"), "the trigger was cleaned up")
        rule.waitUntil(WAIT) { note.readText().contains("due:$today") }
    }

    @Test
    fun typedTextReachesDiskAfterThePause() {
        val (state, note) = vaultWith("start\n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }

        rule.onNodeWithTag("note-body").performTextInput("typed ")
        rule.waitUntil(WAIT) { note.readText().contains("typed ") }
        assertTrue(note.readText().startsWith(NOTE_HEAD), "front matter written back verbatim")
        assertTrue(note.readText().contains("start"), "and the body it was opened with survived")
    }

    @Test
    fun undoUndoesAnEditTheKeyboardCommitted() {
        val (state, _) = vaultWith("milk\n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }

        // Focus the field, then speak to it the way a keyboard does — a
        // real `InputConnection`, not the semantics action — because that
        // is the edit MS1 asked whether undo captures.
        rule.onNodeWithTag("note-body").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.waitUntil(WAIT) {
            rule.runOnUiThread { imeConnection(resumedDecorView(), EditorInfo()) != null }
        }
        ime { setSelection(0, 0) }
        ime {
            beginBatchEdit()
            setComposingText("fresh ", 1)
            finishComposingText()
            endBatchEdit()
        }
        assertEquals("fresh milk\n", editor.body.text.toString())
        assertTrue(editor.body.undoState.canUndo, "the commit entered the undo history")

        rule.onNodeWithTag("undo").performClick()
        rule.waitForIdle()
        assertEquals("milk\n", editor.body.text.toString(), "undo restored the document")
    }

    /// Return continues a list (owner rule, 2026-09-01), sent the way a
    /// soft keyboard sends it: a committed newline through a real
    /// `InputConnection`, inside a batch, because that is the shape MS1
    /// measured and the only one that exercises the input transformation
    /// the rule is hung on.
    ///
    /// The caret assertion is the mobile-specific half. The keyboard is
    /// working in the **transformed** coordinates the glyph leaves behind
    /// — `- ☐ milk` — while the continuation writes source bytes, so
    /// where the caret ends up is a claim worth making rather than
    /// assuming.
    @Test
    fun returnAfterATaskLineOpensAFreshUncheckedTask() {
        val (state, note) = vaultWith("- [ ] milk\n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }
        focusBody()
        caretAt(editor, 10)

        typeReturn()

        assertEquals("- [ ] milk\n- [ ] \n", editor.body.text.toString())
        assertEquals(TextRange(17), editor.body.selection, "the caret is past the new marker")
        rule.waitUntil(WAIT) { note.readText().contains("- [ ] milk\n- [ ] ") }
    }

    @Test
    fun returnOnAnEmptyItemLetsGoOfTheList() {
        val (state, _) = vaultWith("- [ ] milk\n- [ ] \n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }
        focusBody()
        caretAt(editor, 17)

        typeReturn()

        // The marker went and left no blank line where it had been; the
        // note's own trailing newline is untouched.
        assertEquals("- [ ] milk\n\n", editor.body.text.toString())
        assertEquals(TextRange(11), editor.body.selection, "the caret came back onto the freed line")
    }

    /// What undo owes the phone, where there is no Ctrl-Z and the toolbar
    /// button is the only way back. The continuation is written inside the
    /// keystroke's own input transformation rather than as a second edit
    /// after it, so the buffer never records two steps: one undo takes
    /// back the newline and the marker together.
    @Test
    fun oneUndoTakesBackTheReturnAndTheMarkerItWrote() {
        val (state, _) = vaultWith("- [ ] milk\n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }
        focusBody()
        caretAt(editor, 10)

        typeReturn()
        assertEquals("- [ ] milk\n- [ ] \n", editor.body.text.toString())
        assertTrue(editor.body.undoState.canUndo, "the keystroke entered the undo history")

        rule.onNodeWithTag("undo").performClick()
        rule.waitForIdle()
        assertEquals("- [ ] milk\n", editor.body.text.toString(), "one step back, marker and all")
    }

    @Test
    fun theRowOutlivesTheKeyboardAndMoreOpensThePanel() {
        val (state, _) = vaultWith("milk\n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }

        // The row is anchored while the note is in Edit mode, keyboard or
        // no keyboard — the top-voted Obsidian toolbar complaint.
        rule.onNodeWithTag("accessory-row").assertExists()
        rule.onNodeWithTag("editor-panel").assertDoesNotExist()
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithTag("editor-panel").assertExists()
        // Everything the row has no slot for is reachable without
        // scrolling anything.
        for (tag in listOf("heading-1", "quote", "code", "link", "strike", "indent", "outdent", "move-up", "move-down", "redo")) {
            rule.onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun aRowButtonWritesThroughTheSharedMaths() {
        val (state, _) = vaultWith("milk\n")
        val editor = MobileEditorState()
        show(state, editor)
        rule.waitUntil(WAIT) { editor.body.text.toString().isNotEmpty() }

        rule.runOnUiThread { editor.body.edit { selection = TextRange(0, 4) } }
        rule.onNodeWithTag("bold").performClick()
        rule.waitForIdle()
        assertEquals("**milk**\n", editor.body.text.toString())

        rule.runOnUiThread { editor.body.edit { selection = TextRange(0) } }
        rule.onNodeWithTag("checkbox").performClick()
        rule.waitForIdle()
        assertTrue(editor.body.text.toString().startsWith("- [ ] **milk**"))
    }

    @Test
    fun thePaintedTextLaysOutExactlyAsTheFieldDoes() {
        // The assumption the editor's whole rendering rests on, measured
        // rather than assumed: the field owns the layout, the caret and
        // every hit test, and the spans are painted by a sibling `Text`.
        // That is only sound while the two lay out identically — same
        // string, same style, same width, and styling that cannot change
        // a glyph's advance (`MobileEditorTest` pins the last of those).
        //
        // If this ever fails, the paint has drifted off the glyphs and
        // the answer is to take the offending span out of the overlay,
        // not to nudge it back.
        val source = "# A heading\n- [ ] a task that is long enough to wrap on a narrow phone column\n" +
            "with **bold** and *italic* and `code` and a [link](http://example.com) in it\n"
        val decorations = mobileEditorDecorations(builtInDecorations())
        var field: TextLayoutResult? = null
        var paint: TextLayoutResult? = null
        val state = TextFieldState(source)

        rule.setContent {
            GiluneTheme(dark = false) {
                val base = MaterialTheme.typography.bodyLarge
                val styled = editorStyling(source, PALETTE, PALETTE, 1, decorations)
                Box(Modifier.width(320.dp)) {
                    BasicTextField(
                        state = state,
                        textStyle = base,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        outputTransformation = MarkdownOutputTransformation(decorations),
                        onTextLayout = { result -> field = result() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BasicText(
                        styled,
                        style = base,
                        onTextLayout = { result -> paint = result },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        rule.waitUntil(WAIT) { field != null && paint != null }

        val a = field!!
        val b = paint!!
        assertEquals(a.lineCount, b.lineCount, "the same number of lines")
        assertEquals(a.size, b.size, "the same box")
        for (line in 0 until a.lineCount) {
            assertEquals(a.getLineTop(line), b.getLineTop(line), "top of line $line")
            assertEquals(a.getLineEnd(line), b.getLineEnd(line), "wrap of line $line")
        }
    }

    // ---------------------------------------------------------------- rig

    private fun vaultWith(body: String): Pair<GiluneState, File> {
        val vault = Files.createTempDirectory("gilune-edit").toFile()
        val note = File(vault, "note.md")
        note.writeText(NOTE_HEAD + body)
        return GiluneState(vault.absolutePath, scope) to note
    }

    private fun show(state: GiluneState, editor: MobileEditorState) {
        rule.setContent {
            GiluneTheme(dark = false) {
                EditScreen(state, "note.md", modifier = Modifier.fillMaxSize(), editor = editor)
            }
        }
        rule.waitForIdle()
    }

    /// Focus the buffer and wait until the framework has actually handed
    /// out an input connection for it — the field is not typeable the
    /// instant it is focused.
    private fun focusBody() {
        rule.onNodeWithTag("note-body").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.waitUntil(WAIT) {
            rule.runOnUiThread { imeConnection(resumedDecorView(), EditorInfo()) != null }
        }
    }

    /// The caret in **source** coordinates, set the way the shell itself
    /// sets one. Deliberately not through the connection: the IME's
    /// offsets are the transformed ones, and a test that placed the caret
    /// in them would be asserting about its own arithmetic.
    private fun caretAt(editor: MobileEditorState, offset: Int) {
        rule.runOnUiThread { editor.body.edit { selection = TextRange(offset) } }
        rule.waitForIdle()
    }

    /// Return, as a keyboard sends it: a committed newline inside a batch.
    private fun typeReturn() {
        ime {
            beginBatchEdit()
            commitText("\n", 1)
            endBatchEdit()
        }
    }

    private fun <R> ime(block: InputConnection.() -> R): R {
        val result = rule.runOnUiThread {
            val connection = imeConnection(resumedDecorView(), EditorInfo())
                ?: error("no InputConnection — the field has no IME session")
            connection.block()
        }
        rule.waitForIdle()
        return result
    }

    companion object {
        private const val WAIT = 10_000L
        private const val NOTE_HEAD = "---\ngilune-id: abc\n---\n"

        private val PALETTE = MarkdownPalette(
            muted = Color(0xFF808080),
            marker = Color(0xFF3F5B8B),
            heading = Color(0xFF102030),
            code = Color(0xFF204030),
            codeBackground = Color(0xFFEEEEEE),
            link = Color(0xFF0000EE),
        )
    }
}
