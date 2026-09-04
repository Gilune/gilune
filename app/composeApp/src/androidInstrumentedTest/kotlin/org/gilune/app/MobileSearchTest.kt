// The Search destination (ADR 0017 § 1, MB3), from both sides: what the
// empty-query hint promises is arithmetic over the live type registry and
// needs no screen, and so is where an outside tap lands; what the tab
// actually finds needs one.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileSearchTest {
    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------
    // Pure: the hint, and where a tap lands
    // ------------------------------------------------------------------

    @Test
    fun theHintOffersTheSharedDslsForms() {
        val forms = mobileQueryForms(taskForms = false).map { it.first }
        assertEquals(
            listOf("words", "\"a phrase\"", "-word", "file:", "path:", "content:", "[key:value]"),
            forms,
        )
    }

    @Test
    fun theTaskFormsAreAdvertisedOnlyWhileTheTypeIsRegistered() {
        val on = mobileQueryForms(taskForms = true).map { it.first }
        assertTrue(on.containsAll(listOf("type:task", "due:", "is:")), on.toString())
        // A hint is a promise: with the plugin off, `due:` is prose again
        // and must not be offered as a keyword.
        val off = mobileQueryForms(taskForms = false).map { it.first }
        assertTrue(off.none { it in setOf("type:task", "due:", "is:") }, off.toString())
        // The shared forms are unchanged either way — the task forms are
        // appended, never a different list.
        assertEquals(off, on.take(off.size))
    }

    @Test
    fun theSearchTypeIsWhatDecidesTheHint() {
        // The screen asks the live registry, not a flag: this is the same
        // question `SearchScreen` puts to `searchTypesFor`.
        val withTasks = searchTypesFor(listOf(installedTasksPlugin()))
        assertTrue(withTasks.any { it.key == TASK_SEARCH_TYPE })
        val without = searchTypesFor(listOf(installedTasksPlugin(state = "disabled")))
        assertTrue(without.none { it.key == TASK_SEARCH_TYPE })
    }

    @Test
    fun theSearchCellAndTheSearchShortcutBothLandOnSearch() {
        assertEquals(Destination.Search, launchFor(Routes.SEARCH).tab)
        assertEquals(Routes.SEARCH, routeForAction(ACTION_SHORTCUT_SEARCH))
        assertEquals(Destination.Search, launchFor(routeForAction(ACTION_SHORTCUT_SEARCH)).tab)
    }

    @Test
    fun theOtherShortcutsKeepTheirOwnRoutes() {
        assertEquals(Routes.RECORD, routeForAction(ACTION_SHORTCUT_RECORD))
        assertEquals(Routes.CAPTURE, routeForAction(ACTION_SHORTCUT_NEW_NOTE))
        // Record asks for the microphone rather than assuming it; New note
        // puts the caret in the composer.
        assertTrue(launchFor(routeForAction(ACTION_SHORTCUT_RECORD)).askForMic)
        assertTrue(launchFor(routeForAction(ACTION_SHORTCUT_NEW_NOTE)).focusComposer)
        // An ordinary launcher tap carries MAIN and must stay ordinary.
        assertNull(routeForAction("android.intent.action.MAIN"))
        assertNull(routeForAction(null))
        assertEquals(Destination.Capture, launchFor(routeForAction("android.intent.action.MAIN")).tab)
    }

    // ------------------------------------------------------------------
    // In front of a screen: the tab over a real vault
    // ------------------------------------------------------------------

    /// The vault behind the last screen this test showed, so a test can
    /// add a note to it and have the index hear about it.
    private var built: IndexedVault? = null

    private fun searchVaultState(): GiluneState {
        val vault = IndexedVault("gilune-search")
        built = vault
        vault.write("kitchen.md", "# Kitchen\n\nthe tap drips into the sink\n")
        vault.write("garden.md", "# Garden\n\nthe hedge needs cutting\n")
        val state = GiluneState(vault.dir, CoroutineScope(Dispatchers.Default))
        // Search is a query now (ADR 0020 § 2.4), so the screen needs the
        // handle that answers it.
        state.gilune.value = vault.gilune
        return state
    }

    private fun showSearch(state: GiluneState, onOpen: (String) -> Unit = {}) {
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { SearchScreen(state, onOpen, Modifier.fillMaxSize()) }
            }
        }
    }

    @Test
    fun aQueryReturnsTheNoteAndTheLineThatMatched() {
        val state = searchVaultState()
        state.plugins.add(installedTasksPlugin())
        showSearch(state)
        compose.waitForIdle()

        // The empty tab shows the language rather than an empty list.
        compose.onNodeWithTag("search-hint").assertIsDisplayed()

        compose.onNodeWithTag("search-field").performTextInput("drips")
        compose.waitForIdle()

        compose.onNodeWithText("kitchen").assertIsDisplayed()
        compose.onNodeWithText("the tap drips into the sink").assertIsDisplayed()
        // One note matched, so the other is not on screen.
        compose.onNodeWithText("garden").assertDoesNotExist()
    }

    @Test
    fun aHitOpensTheNoteItCameFrom() {
        val state = searchVaultState()
        var opened: String? = null
        showSearch(state) { opened = it }
        compose.waitForIdle()

        compose.onNodeWithTag("search-field").performTextInput("hedge")
        compose.waitForIdle()
        compose.onNodeWithText("garden").performClick()
        compose.waitForIdle()

        assertEquals("garden.md", opened)
    }

    @Test
    fun taskLinesAreTheHitsUnderTypeTask() {
        val state = searchVaultState()
        built!!.write("list.md", "# List\n\n- [ ] mend the fence\n\nprose about fences\n")
        state.plugins.add(installedTasksPlugin())
        showSearch(state)
        compose.waitForIdle()

        compose.onNodeWithTag("search-field").performTextInput("type:task fence")
        compose.waitForIdle()

        // The task line is quoted; the prose line mentioning the same word
        // is not, because `type:task` changed what a hit is.
        compose.onNodeWithText("- [ ] mend the fence").assertIsDisplayed()
        compose.onNodeWithText("prose about fences").assertDoesNotExist()
    }
}
