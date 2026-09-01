// The `filter` block in front of a screen (ADR 0015 § 4, T4): a fence the
// shell registered renders as a live list, a fence nobody registered is
// still an ordinary code block, and a block whose type has gone says so.
//
// The evaluation itself is asserted without a screen in `FilterBlockTest`;
// what is asserted here is only what a screen can answer — that the seam
// resolves, that rows navigate, and that Edit mode is what it is.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FilterBlockUiTest {
    @get:Rule
    val compose = createComposeRule()

    /// Due tomorrow, so `due:week` keeps it whatever day the test runs.
    private val soon = LocalDate.now().plusDays(1)

    /// A vault with one task in one note, and a second note holding the
    /// blocks under test. The task is deliberately **not** in the note
    /// being read, so a row can only have come from the query.
    private fun vaultWith(blocks: String): Pair<GiluneState, EditorState> {
        val root = Files.createTempDirectory("gilune-filter-ui").toFile()
        fun write(relPath: String, text: String) {
            val file = File(root, relPath)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        write("Home/kitchen.md", "---\ngilune-id: id-kitchen\n---\n- [ ] buy milk due:$soon\n")
        write("agenda.md", "---\ngilune-id: id-agenda\n---\n# Agenda\n\n$blocks")
        val state = GiluneState(root.absolutePath, CoroutineScope(Dispatchers.Default))
        val editor = EditorState()
        editor.open(root.absolutePath, "agenda.md")
        editor.reading = true
        return state to editor
    }

    private fun fence(info: String, body: String) = "```$info\n$body\n```\n"

    private fun show(state: GiluneState, editor: EditorState) {
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
    }

    // ------------------------------------------------------------------
    // The seam resolves: a registered fence draws rows, an unregistered
    // one is still a code block
    // ------------------------------------------------------------------

    @Test
    fun aRegisteredFenceRendersItsMatchesAndAnUnregisteredOneStaysACodeBlock() {
        val (state, editor) = vaultWith(
            fence(FILTER_FENCE, "type:task\ndue:week") + "\n" + fence("sql", "select 1"),
        )
        state.plugins.add(installedTasksPlugin())
        show(state, editor)
        compose.waitForIdle()

        // The query ran and its row is on screen.
        compose.onNodeWithText("buy milk").assertIsDisplayed()
        compose.onNodeWithText("Home/kitchen.md").assertIsDisplayed()
        // T4 is not done until an unregistered fence still renders as an
        // ordinary code block: `sql` is drawn as the bytes it holds…
        compose.onNodeWithText("select 1").assertIsDisplayed()
        // …and the filter fence is not, because it drew a list instead.
        compose.onNodeWithText("type:task", substring = true).assertDoesNotExist()
    }

    @Test
    fun aBlockWithNoTypeDrawsOneHintRowAndNeverGuesses() {
        val (state, editor) = vaultWith(fence(FILTER_FENCE, "due:week"))
        state.plugins.add(installedTasksPlugin())
        show(state, editor)
        compose.waitForIdle()

        compose.onNodeWithText("a filter names its type: — try type:note or type:task").assertIsDisplayed()
        compose.onNodeWithText("buy milk").assertDoesNotExist()
    }

    @Test
    fun anEmptyBlockSaysItMatchesNothingRatherThanListingEverything() {
        val (state, editor) = vaultWith(fence(FILTER_FENCE, ""))
        state.plugins.add(installedTasksPlugin())
        show(state, editor)
        compose.waitForIdle()

        compose.onNodeWithText("an empty filter matches nothing").assertIsDisplayed()
        compose.onNodeWithText("buy milk").assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // Removal: the fence is the shell's, the type was the plugin's (§ 4)
    // ------------------------------------------------------------------

    @Test
    fun withTheTasksPluginGoneTheBlockStillRendersAndNamesWhatIsMissing() {
        val (state, editor) = vaultWith(fence(FILTER_FENCE, "type:task\ndue:week"))
        // No tasks plugin at all — the block is the shell's, its type was
        // not.
        show(state, editor)
        compose.waitForIdle()

        compose.onNodeWithText("no type called task is registered — is its plugin on?").assertIsDisplayed()
        compose.onNodeWithText("buy milk").assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // Rows navigate, and they do not edit (§ 4)
    // ------------------------------------------------------------------

    @Test
    fun clickingARowOpensTheNoteItCameFrom() {
        val (state, editor) = vaultWith(fence(FILTER_FENCE, "type:task\ndue:week"))
        state.plugins.add(installedTasksPlugin())
        val tabs = TabsState(state.vaultDir)
        val tab = tabs.open("agenda.md")
        tab.editor.open(state.vaultDir, "agenda.md")
        tab.editor.reading = true
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    NoteEditor(state, tab.editor, Modifier.fillMaxSize(), tabs = tabs, tab = tab)
                }
            }
        }
        compose.waitForIdle()

        val before = File(state.vaultDir, "Home/kitchen.md").readText()
        compose.onNodeWithText("buy milk").performClick()
        compose.waitForIdle()

        assertEquals("Home/kitchen.md", tabs.activeTab?.relPath, "a row opens the note it came from")
        assertEquals(
            before,
            File(state.vaultDir, "Home/kitchen.md").readText(),
            "rows navigate, they do not edit: nothing was written to a file the user was not looking at",
        )
        // The row was inert about the buffer it was drawn in, too.
        assertEquals(false, editor.dirty)
    }

    // ------------------------------------------------------------------
    // Edit mode: the block behaves like a checkbox (owner, 2026-09-01)
    // ------------------------------------------------------------------

    /// The owner declined the shipped raw-fence fallback: a `filter` block
    /// "behaves like a checkbox — renders until the caret is placed within
    /// it". Rendered while the caret is outside; the raw fence back the
    /// moment it is on **any** of the block's lines, delimiters included.
    ///
    /// What both states share, and what this pins as its predecessor did,
    /// is that no byte moves either way.
    @Test
    fun editModeRendersTheBlockUntilTheCaretIsPlacedInsideIt() {
        val (state, editor) = vaultWith(fence(FILTER_FENCE, "type:task\ndue:week"))
        state.plugins.add(installedTasksPlugin())
        val before = editor.text.text
        editor.reading = false
        show(state, editor)
        compose.waitForIdle()

        // The caret is outside the block — there is not one yet — so the
        // query ran and its row is drawn over the lines the fence gave up.
        compose.onNodeWithText("buy milk").assertIsDisplayed()

        // Onto each line of the block in turn, the two delimiters
        // included: every one of them shows the raw fence.
        compose.onNodeWithTag("note-body").performClick()
        for (inside in listOf("```filter", "type:task", "due:week")) {
            caretAt(editor, before.indexOf(inside))
            compose.onNodeWithText("buy milk").assertDoesNotExist()
        }
        caretAt(editor, before.lastIndexOf("```"))
        compose.onNodeWithText("buy milk").assertDoesNotExist()

        // And out again.
        caretAt(editor, 0)
        compose.onNodeWithText("buy milk").assertIsDisplayed()

        assertEquals(before, editor.text.text, "the buffer is the document: rendering it moved no byte")
        assertEquals(false, editor.dirty)
    }

    @Test
    fun inEditModeARowStillNavigatesToTheNoteItCameFrom() {
        val (state, tabs, tab) = editorWithTabs()
        val before = File(state.vaultDir, "Home/kitchen.md").readText()

        compose.onNodeWithText("buy milk").performClick()
        compose.waitForIdle()

        assertEquals(
            "Home/kitchen.md",
            tabs.activeTab?.relPath,
            "a row navigates in Edit mode exactly as it does in Read mode",
        )
        assertEquals(
            before,
            File(state.vaultDir, "Home/kitchen.md").readText(),
            "rows navigate, they do not edit",
        )
        assertEquals(false, tab.editor.dirty)
    }

    /// How a covered block is edited: rows are drawn to their own width
    /// precisely so the space beside one belongs to the buffer. A click
    /// there is not consumed, the field lands the caret inside the
    /// placeholder, and that reveals the fence.
    @Test
    fun aClickBesideARowFallsThroughToTheBufferAndRevealsTheFence() {
        val (state, tabs, tab) = editorWithTabs()
        val before = tab.editor.text.text

        val row = compose.onNodeWithText("buy milk").fetchSemanticsNode().boundsInRoot
        val edge = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        val beside = minOf(row.right + 60f, edge - 20f)
        assertTrue(beside > row.right + 8f, "there is space beside the row to click")

        compose.onRoot().performMouseInput { click(Offset(beside, row.center.y)) }
        compose.waitForIdle()

        assertEquals("agenda.md", tabs.activeTab?.relPath, "the click was not the row's")
        compose.onNodeWithText("buy milk").assertDoesNotExist()
        assertEquals(before, tab.editor.text.text, "and landing a caret is not an edit")
        assertEquals(state.vaultDir, tabs.vaultDir)
    }

    /// The block in Edit mode, in a pane that can open notes, so a row's
    /// click has somewhere to go.
    private fun editorWithTabs(): Triple<GiluneState, TabsState, Tab> {
        val (state, _) = vaultWith(fence(FILTER_FENCE, "type:task\ndue:week"))
        state.plugins.add(installedTasksPlugin())
        val tabs = TabsState(state.vaultDir)
        val tab = tabs.open("agenda.md")
        tab.editor.open(state.vaultDir, "agenda.md")
        tab.editor.reading = false
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    NoteEditor(state, tab.editor, Modifier.fillMaxSize(), tabs = tabs, tab = tab)
                }
            }
        }
        compose.waitForIdle()
        return Triple(state, tabs, tab)
    }

    private fun caretAt(editor: EditorState, offset: Int) {
        compose.runOnUiThread { editor.text = editor.text.copy(selection = TextRange(offset)) }
        compose.waitForIdle()
    }
}
