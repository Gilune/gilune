// `filter` fences in the phone's Read view (ADR 0017 § 7, MB3).
//
// The evaluation is shared and asserted in `FilterBlockTest`; what is
// asserted here is the wiring — that Read hands the shared renderer table
// a registration for the fence, that its rows navigate, and that a fence
// nobody can answer falls back the way the shared code says it does rather
// than to a code box.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MobileFilterBlockTest {
    @get:Rule
    val compose = createComposeRule()

    private val fence = "```"

    /// A vault with a note carrying a `filter` block, and a second note
    /// holding the task it should find.
    private fun blockVault(block: String): GiluneState {
        val vault = IndexedVault("gilune-filter")
        val now = LocalDate.now()
        vault.write("board.md", "# Board\n\n$fence" + "filter\n$block\n$fence\n")
        vault.write(
            "jobs.md",
            "---\ngilune-id: jobs\n---\n# Jobs\n\n- [ ] mend the gate due:$now\n",
        )
        val state = GiluneState(vault.dir, CoroutineScope(Dispatchers.Default))
        // A block is a query now (ADR 0020 § 2.4).
        state.gilune.value = vault.gilune
        return state
    }

    private fun showRead(state: GiluneState, onOpen: (String) -> Unit = {}) {
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { ReadScreen(state, "board.md", onOpen, Modifier.fillMaxSize()) }
            }
        }
    }

    @Test
    fun aFilterFenceRendersItsRowsInsteadOfACodeBox() {
        val state = blockVault("type:task\ndue:today")
        state.plugins.add(installedTasksPlugin())
        showRead(state)
        compose.waitForIdle()

        compose.onNodeWithTag("filter-block").assertIsDisplayed()
        compose.onNodeWithText("mend the gate").assertIsDisplayed()
        // The fence's own text is not on screen: the block rendered, it
        // did not fall through to the monospace box.
        compose.onNodeWithText("type:task\ndue:today").assertDoesNotExist()
    }

    @Test
    fun aRowNavigatesToTheNoteTheTaskLivesIn() {
        val state = blockVault("type:task")
        state.plugins.add(installedTasksPlugin())
        var opened: String? = null
        showRead(state) { opened = it }
        compose.waitForIdle()

        compose.onNodeWithText("mend the gate").performClick()
        compose.waitForIdle()

        assertEquals("jobs.md", opened, "rows navigate; a block is read-only")
    }

    @Test
    fun aBlockThatNamesNoTypeGetsAHintRatherThanAGuess() {
        val state = blockVault("due:today")
        state.plugins.add(installedTasksPlugin())
        showRead(state)
        compose.waitForIdle()

        compose.onNodeWithTag("filter-hint").assertIsDisplayed()
        compose.onNodeWithText("mend the gate").assertDoesNotExist()
    }

    @Test
    fun aTypeNobodyRegisteredSaysSoAndNamesIt() {
        // The plugin that registers `type:task` is off, so the block asks
        // for a type nobody has — which is the honest answer rather than
        // an empty list.
        val state = blockVault("type:task")
        state.plugins.add(installedTasksPlugin(state = "disabled"))
        showRead(state)
        compose.waitForIdle()

        compose.onNodeWithText("no type called task is registered — is its plugin on?").assertIsDisplayed()
    }

    @Test
    fun anAnsweredBlockWithNoMatchesSaysSo() {
        val state = blockVault("type:task \"nothing like this\"")
        state.plugins.add(installedTasksPlugin())
        showRead(state)
        compose.waitForIdle()

        compose.onNodeWithText(FILTER_NO_MATCHES).assertIsDisplayed()
    }
}
