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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

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
    // Edit mode — the shipped deviation from § 5
    // ------------------------------------------------------------------

    /// ADR 0015 § 5 asks for the fence to render as the list with the
    /// caret outside it, through a placeholder-and-overlay. **That is not
    /// what shipped**: Edit mode shows the raw fence, styled as any other
    /// code block, and Read mode renders. The owner has to see this, so it
    /// is asserted rather than left as an absence.
    ///
    /// What both paths share, and what this pins down, is that bytes never
    /// change either way.
    @Test
    fun editModeShowsTheFenceItselfRatherThanTheList() {
        val (state, editor) = vaultWith(fence(FILTER_FENCE, "type:task\ndue:week"))
        state.plugins.add(installedTasksPlugin())
        val before = editor.text.text
        editor.reading = false
        show(state, editor)
        compose.waitForIdle()

        compose.onNodeWithText("buy milk").assertDoesNotExist()
        assertEquals(before, editor.text.text, "the buffer is the document, and nothing rendered near it moved")
        assertEquals(false, editor.dirty)
    }
}
