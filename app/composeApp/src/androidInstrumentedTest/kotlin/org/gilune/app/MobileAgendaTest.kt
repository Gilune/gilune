// The Agenda destination (ADR 0017 § 1, § 8, MB3) — the tasks plugin's
// `navigation` contribution on the bottom bar.
//
// Three claims, at the level each is made: the bar's contributed half is
// the live plugin list (pure); the guarded per-line write refuses a stale
// row (pure); and the tab, its four views and its checkbox behave in front
// of a screen over a real vault.
//
// The partition itself — which task lands in which view — is `Agenda.kt`'s
// and is asserted in `TasksPanelTest`. It is the same shared function on
// both platforms, so there is nothing to keep in parity here.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileAgendaTest {
    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------
    // Pure: what the bar contributes, and what it stops contributing
    // ------------------------------------------------------------------

    @Test
    fun theTasksPluginContributesTheAgendaDestination() {
        val panels = mobileNavigationPanels(listOf(installedTasksPlugin()))
        assertEquals(listOf(TASKS_RAIL_PANEL), panels.map { it.key })
        assertEquals("Tasks", panels.single().label, "the label is the contribution's own")
    }

    @Test
    fun theAgendaLeavesWithThePanelSwitchAndWithThePluginItself() {
        val switchedOff = installedTasksPlugin(settings = tasksSettingsDocument("", panel = false))
        assertTrue(mobileNavigationPanels(listOf(switchedOff)).isEmpty(), "the panel switch takes the tab")
        assertTrue(mobileNavigationPanels(listOf(installedTasksPlugin(state = "disabled"))).isEmpty())
        assertTrue(mobileNavigationPanels(listOf(installedTasksPlugin(state = "removed"))).isEmpty())
        assertTrue(mobileNavigationPanels(emptyList()).isEmpty())
    }

    @Test
    fun theBarTakesAtMostTwoContributedDestinations() {
        val three = (1..3).map {
            installedPlugin("org.example.p$it", contributions = tasksDeclaration().contributions)
        }
        assertEquals(MOBILE_NAV_PANELS, mobileNavigationPanels(three).size)
    }

    @Test
    fun aSelectionWhoseContributionHasGoneFallsBackToAShellDestination() {
        val live = mobileNavigationPanels(listOf(installedTasksPlugin()))
        assertEquals(TASKS_RAIL_PANEL, activeNavigationPanel(live, TASKS_RAIL_PANEL)?.key)
        // The switch goes while the agenda is what is on screen.
        val gone = mobileNavigationPanels(
            listOf(installedTasksPlugin(settings = tasksSettingsDocument("", panel = false))),
        )
        assertNull(activeNavigationPanel(gone, TASKS_RAIL_PANEL), "nothing contributed answers the selection")
        assertNull(activeNavigationPanel(live, null), "no selection is a shell destination")
    }

    // ------------------------------------------------------------------
    // Pure: the guarded per-line write
    // ------------------------------------------------------------------

    private val note = "---\ngilune-id: abc\n---\n# Things\n\n- [ ] milk\n- [ ] bread\n"

    /// The body of that note is `# Things`, ``, `- [ ] milk`, `- [ ] bread`,
    /// `` — so milk is body line 2, which a `TaskHit` reports as 3.
    @Test
    fun theTickRewritesOnlyTheLineTheRowCameFrom() {
        val updated = toggleTaskLine(note, bodyLine = 2, expected = "- [ ] milk")
        assertTrue(updated != null && updated.contains("- [x] milk"), updated.orEmpty())
        assertTrue(updated!!.contains("- [ ] bread"), "only the tapped line moved")
        assertTrue(updated.startsWith("---\ngilune-id: abc\n---\n"), "front matter survived")
    }

    @Test
    fun aStaleRowDeclinesRatherThanWriting() {
        // The text the row was drawn from is no longer on that line —
        // somebody edited the note, or the pass is old.
        assertNull(toggleTaskLine(note, bodyLine = 2, expected = "- [ ] oats"))
        // The line the row named has gone off the end of the note.
        assertNull(toggleTaskLine(note, bodyLine = 99, expected = "- [ ] milk"))
        assertNull(toggleTaskLine(note, bodyLine = -1, expected = "- [ ] milk"))
        // Nothing claims a line of prose, so nothing is written.
        assertNull(toggleTaskLine(note, bodyLine = 0, expected = "# Things"))
    }

    @Test
    fun tickingStampsDoneWhenThePluginThatOwnsTheStampIsOn() {
        val today = LocalDate.of(2026, 9, 1)
        val chain = builtInDecorations() + tasksDecorations(today = today)
        val stamped = toggleTaskLine(note, bodyLine = 2, expected = "- [ ] milk", decorations = chain)
        assertTrue(stamped!!.contains("- [x] milk done:2026-09-01"), stamped)
        // And stamps nothing when it is not.
        val plain = toggleTaskLine(note, bodyLine = 2, expected = "- [ ] milk")
        assertTrue(!plain!!.contains("done:"), plain)
    }

    // ------------------------------------------------------------------
    // In front of a screen: the destination over a real vault
    // ------------------------------------------------------------------

    /// A vault holding one task per view, plus two the agenda must not
    /// show: a ticked one and one past the horizon. Dates are relative to
    /// the day the test runs, so nothing here rots.
    private fun agendaVault(): GiluneState {
        val vault = Files.createTempDirectory("gilune-mobile-agenda").toFile()
        val now = LocalDate.now()
        vault.resolve("agenda.md").writeText(
            """
            ---
            gilune-id: agenda
            ---
            # Things

            - [ ] collect the keys due:${now.minusDays(2)}
            - [ ] phone the council due:$now
            - [ ] chase the plasterer due:${now.plusDays(3)}
            - [ ] someday maybe
            - [x] already done due:$now
            - [ ] far away due:${now.plusDays(30)}
            """.trimIndent(),
        )
        return GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
    }

    private fun showAgenda(
        state: GiluneState,
        onOpen: (String) -> Unit = {},
        onStartTask: () -> Unit = {},
    ) {
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    AgendaScreen(state, onOpen, onStartTask, Modifier.fillMaxSize())
                }
            }
        }
    }

    /// The agenda and the composer with the shell's own wiring between
    /// them — what "Start a task" actually does, end to end, without
    /// standing up the whole activity. The seeding rule itself is
    /// [taskDraft], which is what `MainActivity` calls too, so the one
    /// decision with a second-order case is shared rather than copied.
    @Composable
    private fun ShellWithAgenda(state: GiluneState, initialDraft: String = "") {
        var draft by remember { mutableStateOf(initialDraft) }
        var onAgenda by remember { mutableStateOf(true) }
        var focusNonce by remember { mutableStateOf(0) }
        if (onAgenda) {
            AgendaScreen(
                state,
                onOpenNote = {},
                onStartTask = {
                    onAgenda = false
                    draft = taskDraft(draft)
                    focusNonce += 1
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CaptureScreen(
                state = state,
                draft = draft,
                onDraftChange = { draft = it },
                onCommit = {},
                onOpenNote = {},
                focusNonce = focusNonce,
            )
        }
    }

    /// A vault with no task line anywhere in it.
    private fun proseOnlyVault(): GiluneState {
        val vault = Files.createTempDirectory("gilune-empty-agenda").toFile()
        vault.resolve("prose.md").writeText("# Nothing\n\njust words\n")
        return GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
    }

    @Test
    fun theDestinationGroupsTheVaultsOpenTasksUnderTheFourHeadings() {
        val state = agendaVault()
        state.plugins.add(installedTasksPlugin())
        showAgenda(state)
        compose.waitForIdle()

        compose.onNodeWithText("overdue (1)").assertIsDisplayed()
        compose.onNodeWithText("today (1)").assertIsDisplayed()
        compose.onNodeWithText("next seven days (1)").assertIsDisplayed()
        compose.onNodeWithText("undated (1)").assertIsDisplayed()
        compose.onNodeWithText("collect the keys").assertIsDisplayed()
        // Undated is folded away to begin with, so its row is not drawn
        // even though its header counts it.
        compose.onNodeWithText("someday maybe").assertDoesNotExist()
        // Neither the ticked task nor the one past the horizon is here.
        compose.onNodeWithText("already done").assertDoesNotExist()
        compose.onNodeWithText("far away").assertDoesNotExist()
    }

    @Test
    fun aGroupFoldsAndUnfolds() {
        val state = agendaVault()
        state.plugins.add(installedTasksPlugin())
        showAgenda(state)
        compose.waitForIdle()

        compose.onNodeWithText("undated (1)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("someday maybe").assertIsDisplayed()

        compose.onNodeWithText("today (1)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("phone the council").assertDoesNotExist()
    }

    @Test
    fun aRowNavigatesToTheNoteItCameFrom() {
        val state = agendaVault()
        state.plugins.add(installedTasksPlugin())
        var opened: String? = null
        showAgenda(state, onOpen = { opened = it })
        compose.waitForIdle()

        compose.onNodeWithText("phone the council").performClick()
        compose.waitForIdle()

        assertEquals("agenda.md", opened, "the body of a row opens the note")
    }

    @Test
    fun aRowsCheckboxReachesTheBytes() {
        val state = agendaVault()
        state.plugins.add(installedTasksPlugin())
        val file = File(state.vaultDir, "agenda.md")
        var opened: String? = null
        showAgenda(state, onOpen = { opened = it })
        compose.waitForIdle()

        // The first row of the first view is the overdue one.
        compose.onAllNodesWithContentDescription("task not done")[0].performClick()
        compose.waitUntil(WAIT) { file.readText().contains("- [x] collect the keys") }

        val after = file.readText()
        assertTrue(after.contains("- [x] collect the keys"), after)
        assertTrue(after.contains("- [ ] phone the council"), "only the tapped line moved")
        assertTrue(after.startsWith("---\ngilune-id: agenda\n---\n"), "front matter survived the tick")
        assertNull(opened, "the checkbox ticks; it does not navigate")

        // A ticked task is a record, not a claim on the day, so the row
        // leaves the agenda on the recompute its own write triggered.
        compose.waitForIdle()
        compose.onNodeWithText("collect the keys").assertDoesNotExist()
        compose.onNodeWithText("overdue (1)").assertDoesNotExist()
    }

    /// A vault with no tasks in it is somebody who has not met the idea
    /// yet, so the screen explains what a task is and offers a way in
    /// rather than reporting an emptiness they cannot act on.
    @Test
    fun anAgendaWithNoTasksExplainsWhatOneIsAndOffersAWayIn() {
        val state = proseOnlyVault()
        state.plugins.add(installedTasksPlugin())
        showAgenda(state)
        compose.waitForIdle()

        compose.onNodeWithText("No tasks yet").assertIsDisplayed()
        compose.onNodeWithText("- [ ]", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("start-task").assertIsDisplayed()
    }

    /// **The second-order row.** Tasks exist; none is open in the four
    /// views. The screen must not explain what a task is to somebody who
    /// has written six of them — it says the views are bounded and where
    /// the rest are, and it offers no button, because there is nothing
    /// here for one to fix.
    @Test
    fun anAgendaWhoseTasksAreAllDoneOrFarOffSaysSoInstead() {
        val vault = Files.createTempDirectory("gilune-none-open").toFile()
        val now = LocalDate.now()
        vault.resolve("done.md").writeText(
            "# Things\n\n- [x] already done due:$now\n- [ ] far away due:${now.plusDays(30)}\n",
        )
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        state.plugins.add(installedTasksPlugin())
        showAgenda(state)
        compose.waitForIdle()

        compose.onNodeWithText("Nothing open").assertIsDisplayed()
        compose.onNodeWithText("type:task", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No tasks yet").assertDoesNotExist()
        compose.onNodeWithTag("start-task").assertDoesNotExist()
    }

    @Test
    fun startingATaskSeedsTheComposerWithTheCaretAfterTheMarker() {
        val state = proseOnlyVault()
        state.plugins.add(installedTasksPlugin())
        compose.setContent { GiluneTheme(dark = false) { ShellWithAgenda(state) } }
        compose.waitForIdle()

        compose.onNodeWithTag("start-task").performClick()
        compose.waitForIdle()

        val composer = compose.onNodeWithTag("composer")
        composer.assertIsDisplayed()
        composer.assertTextContains(TASK_DRAFT)
        composer.assertIsFocused()
        // After the marker, not in front of it: the next thing typed is
        // the task's words.
        composer.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TextSelectionRange,
                TextRange(TASK_DRAFT.length),
            ),
        )
    }

    /// **The second-order row.** The button is a convenience; the draft is
    /// a thought. An occupied composer is handed back untouched and the
    /// person is simply taken to it.
    @Test
    fun startingATaskNeverOverwritesAHalfWrittenThought() {
        val state = proseOnlyVault()
        state.plugins.add(installedTasksPlugin())
        compose.setContent {
            GiluneTheme(dark = false) { ShellWithAgenda(state, initialDraft = "half a thought") }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("start-task").performClick()
        compose.waitForIdle()

        val composer = compose.onNodeWithTag("composer")
        composer.assertIsDisplayed()
        composer.assertTextContains("half a thought")
        // The rule, stated where it is kept.
        assertEquals("half a thought", taskDraft("half a thought"))
        assertEquals(TASK_DRAFT, taskDraft("   \n "), "whitespace is not a thought")
    }

    // ------------------------------------------------------------------
    // In front of a screen: the bar itself
    // ------------------------------------------------------------------

    /// The bar where the shell puts it — the foot of a full-height column
    /// — rather than floating alone at the top of an empty screen, so what
    /// is asserted is the geometry a thumb actually meets.
    private fun showBar(plugins: List<uniffi.gilune_ffi.PluginInfo>, selected: String? = null) {
        compose.setContent {
            GiluneTheme(dark = false) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxSize())
                    val panels = mobileNavigationPanels(plugins)
                    BottomBar(
                        destination = Destination.Capture,
                        panels = panels,
                        activePanelKey = activeNavigationPanel(panels, selected)?.key,
                        onSelectDestination = {},
                        onSelectPanel = {},
                    )
                }
            }
        }
    }

    /// A tab is addressed by its label rather than by its icon's
    /// description: Material 3 merges a `NavigationBarItem`'s icon into
    /// the item and drops the description when a label is shown, so the
    /// label is the only name the tab actually has. (The desktop rail's
    /// tabs are icon-only, which is why `TasksPanelTest` addresses those
    /// the other way round.)
    @Test
    fun theBarCarriesThreeShellDestinationsAndTheContributedOne() {
        showBar(listOf(installedTasksPlugin()))
        compose.waitForIdle()

        compose.onNodeWithText("Capture").assertIsDisplayed()
        compose.onNodeWithText("Notes").assertIsDisplayed()
        compose.onNodeWithText("Search").assertIsDisplayed()
        // The contributed one, beside the shell's own three and not among
        // them — its label is the contribution's, not the shell's.
        compose.onNodeWithText("Tasks").assertIsDisplayed()
    }

    @Test
    fun selectingTheContributedTabDeselectsTheShellDestination() {
        showBar(listOf(installedTasksPlugin()), selected = TASKS_RAIL_PANEL)
        compose.waitForIdle()

        compose.onNodeWithText("Tasks").assertIsSelected()
        compose.onNodeWithText("Capture").assertIsNotSelected()
    }

    @Test
    fun theContributedTabIsNotDrawnWhenThePanelSwitchIsOff() {
        showBar(
            listOf(installedTasksPlugin(settings = tasksSettingsDocument("", panel = false))),
            // The agenda was what the shell was looking at when the switch
            // went.
            selected = TASKS_RAIL_PANEL,
        )
        compose.waitForIdle()

        compose.onNodeWithText("Tasks").assertDoesNotExist()
        // The shell's own three are untouched by a plugin's absence, and
        // the selection falls back to one of them rather than to nothing.
        compose.onNodeWithText("Search").assertIsDisplayed()
        compose.onNodeWithText("Capture").assertIsSelected()
    }

    private companion object {
        const val WAIT = 10_000L
    }
}
