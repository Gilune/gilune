// The agenda panel (ADR 0015 § 6, T3), from both sides: the partition is
// arithmetic over rows and a day, so its boundaries are asserted without a
// screen; the rail's composition is not, so the contributed tab and its
// off switch are asserted in front of one.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TasksPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 8, 31)

    private fun task(text: String, due: LocalDate? = null, path: String = "notes.md", line: Int = 1) =
        TaskHit(relPath = path, name = path.removeSuffix(".md"), line = line, text = text, due = due, done = null)

    private fun views(rows: List<TaskHit>, cap: Int = AGENDA_ROW_CAP) =
        agenda(rows, today, cap).associate { it.group to it.rows.size }

    // ------------------------------------------------------------------
    // The four bounded views (§ 6, T3)
    // ------------------------------------------------------------------

    @Test
    fun eachViewTakesExactlyTheDaysItNames() {
        val rows = listOf(
            task("- [ ] long overdue", today.minusDays(30)),
            task("- [ ] yesterday", today.minusDays(1)),
            task("- [ ] today", today),
            task("- [ ] tomorrow", today.plusDays(1)),
            task("- [ ] the seventh day", today.plusDays(7)),
            task("- [ ] someday"),
        )
        assertEquals(
            mapOf(
                AgendaGroup.OVERDUE to 2,
                AgendaGroup.TODAY to 1,
                AgendaGroup.NEXT_SEVEN to 2,
                AgendaGroup.UNDATED to 1,
            ),
            views(rows),
        )
    }

    @Test
    fun theBoundariesAreExactAndTheFourViewsNeverOverlap() {
        // One task at a time, so nothing can hide behind a neighbour.
        val boundaries = listOf(
            today.minusDays(1) to AgendaGroup.OVERDUE,
            today to AgendaGroup.TODAY,
            today.plusDays(1) to AgendaGroup.NEXT_SEVEN,
            today.plusDays(7) to AgendaGroup.NEXT_SEVEN,
        )
        for ((due, group) in boundaries) {
            val sections = agenda(listOf(task("- [ ] x", due)), today)
            assertEquals(listOf(group), sections.map { it.group }, "$due belongs to $group and to nothing else")
        }
    }

    @Test
    fun aTaskBeyondTheHorizonIsInNoViewAtAll() {
        // Four *bounded* views: an unbounded "later" is the list an agenda
        // exists not to be, and search answers that question instead.
        assertTrue(agenda(listOf(task("- [ ] far away", today.plusDays(8))), today).isEmpty())
    }

    @Test
    fun aCheckedTaskIsNotOnTheAgenda() {
        val rows = listOf(
            task("- [x] done today", today),
            task("- [X] done, GFM's capital", today.minusDays(2)),
            task("- [ ] still open", today),
        )
        assertEquals(mapOf(AgendaGroup.TODAY to 1), views(rows))
        assertEquals("still open", agenda(rows, today).first().rows.first().let { taskLabel(it.text) })
    }

    @Test
    fun anEmptyViewIsNotDrawnAtAll() {
        val sections = agenda(listOf(task("- [ ] only this", today)), today)
        assertEquals(listOf(AgendaGroup.TODAY), sections.map { it.group }, "no header with a nought beside it")
        assertTrue(agenda(emptyList(), today).isEmpty())
    }

    @Test
    fun theViewsComeBackInTheOrderThePanelShowsThem() {
        val rows = listOf(
            task("- [ ] u"),
            task("- [ ] s", today.plusDays(3)),
            task("- [ ] t", today),
            task("- [ ] o", today.minusDays(3)),
        )
        assertEquals(
            listOf(AgendaGroup.OVERDUE, AgendaGroup.TODAY, AgendaGroup.NEXT_SEVEN, AgendaGroup.UNDATED),
            agenda(rows, today).map { it.group },
        )
    }

    @Test
    fun theRowOrderIsTheOneTheQueryEstablishedAndNothingResortsIt() {
        val rows = listOf(
            task("- [ ] first", today.plusDays(1), path = "a.md"),
            task("- [ ] second", today.plusDays(2), path = "z.md"),
            task("- [ ] third", today.plusDays(3), path = "b.md"),
        )
        assertEquals(
            listOf("first", "second", "third"),
            agenda(rows, today).first().rows.map { taskLabel(it.text) },
        )
    }

    // ------------------------------------------------------------------
    // § 4's cap, which is on rendering and is not optional
    // ------------------------------------------------------------------

    @Test
    fun eachViewDrawsAtMostTheCapAndCountsTheRest() {
        val rows = (1..57).map { task("- [ ] task $it", today) }
        val section = agenda(rows, today, cap = AGENDA_ROW_CAP).single()
        assertEquals(AGENDA_ROW_CAP, section.rows.size)
        assertEquals(7, section.more)
        assertEquals(57, section.count, "the header still reports the whole view")
    }

    @Test
    fun aViewInsideTheCapCountsNothingExtra() {
        val section = agenda((1..3).map { task("- [ ] task $it", today) }, today).single()
        assertEquals(0, section.more)
        assertEquals(3, section.count)
    }

    @Test
    fun theCapAppliesPerViewRatherThanAcrossThePanel() {
        val rows = (1..4).map { task("- [ ] over $it", today.minusDays(1)) } +
            (1..4).map { task("- [ ] now $it", today) }
        val sections = agenda(rows, today, cap = 3)
        assertEquals(listOf(3, 3), sections.map { it.rows.size })
        assertEquals(listOf(1, 1), sections.map { it.more })
    }

    // ------------------------------------------------------------------
    // What a row says
    // ------------------------------------------------------------------

    @Test
    fun aRowShowsTheTasksOwnWordsWithoutTheMarkupItDrawsItself() {
        assertEquals("renew the passport", taskLabel("- [ ] renew the passport due:2026-09-05"))
        assertEquals("file the return", taskLabel("  * [x] file the return due:2026-09-01 done:2026-08-30"))
        assertEquals("milk", taskLabel("+ [ ] milk"))
        assertEquals("due:2026-9-5 is not a token", taskLabel("- [ ] due:2026-9-5 is not a token"))
        assertEquals(
            "- [ ] due:2026-09-05",
            taskLabel("- [ ] due:2026-09-05"),
            "a line with nothing but a token keeps its own text rather than showing blank",
        )
    }

    // ------------------------------------------------------------------
    // In front of a screen: the panel over a real vault
    // ------------------------------------------------------------------

    /// A vault holding one task per view, plus two the agenda must not
    /// show: a ticked one and one past the horizon. Dates are relative to
    /// the day the test runs, so nothing here rots.
    private fun agendaVault(): GiluneState {
        val vault = Files.createTempDirectory("gilune-agenda").toFile()
        val now = LocalDate.now()
        vault.resolve("agenda.md").writeText(
            """
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

    private fun showPanel(state: GiluneState, onOpen: (String) -> Unit = {}) {
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { TasksPanel(state, onOpen, Modifier.fillMaxSize()) }
            }
        }
    }

    @Test
    fun thePanelGroupsTheVaultsOpenTasksUnderTheFourHeadings() {
        val state = agendaVault()
        state.plugins.add(installedTasksPlugin())
        showPanel(state)
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
    fun aRowNavigatesToTheNoteItCameFrom() {
        val state = agendaVault()
        state.plugins.add(installedTasksPlugin())
        var opened: String? = null
        showPanel(state) { opened = it }
        compose.waitForIdle()

        compose.onNodeWithText("phone the council").performClick()
        compose.waitForIdle()

        assertEquals("agenda.md", opened, "rows navigate; nothing here edits a file")
    }

    @Test
    fun aVaultWithNothingOpenSaysSoRatherThanShowingEmptyHeadings() {
        val vault = Files.createTempDirectory("gilune-agenda-empty").toFile()
        vault.resolve("prose.md").writeText("no checkboxes here")
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        state.plugins.add(installedTasksPlugin())
        showPanel(state)
        compose.waitForIdle()

        compose.onNodeWithText("nothing open").assertIsDisplayed()
        compose.onNodeWithText("overdue (0)").assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // The rail: the contributed tab, and what its off switch takes away
    // ------------------------------------------------------------------

    private fun showRail(state: GiluneState, tree: TreeState) {
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    RailPanels(
                        state = state,
                        tree = tree,
                        searchFocus = FocusRequester(),
                        onOpen = {},
                        onCreateNote = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    @Test
    fun thePluginPutsAThirdTabInTheRailAndItOpensTheAgenda() {
        val state = agendaVault()
        state.plugins.add(installedTasksPlugin())
        val tree = TreeState()
        showRail(state, tree)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Tasks").performClick()
        compose.waitForIdle()

        assertEquals(RailPanel(TASKS_RAIL_PANEL), tree.railPanel)
        compose.onNodeWithText("today (1)").assertIsDisplayed()
    }

    @Test
    fun switchingThePanelOffTakesTheTabAwayAndTheRailFallsBackToFiles() {
        val state = agendaVault()
        state.plugins.add(installedTasksPlugin(settings = tasksSettingsDocument("", panel = false)))
        // The rail was already looking at the agenda when the switch went.
        val tree = TreeState().apply { railPanel = RailPanel(TASKS_RAIL_PANEL) }
        showRail(state, tree)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Tasks").assertDoesNotExist()
        compose.onNodeWithText("tasks").assertDoesNotExist()
        // Falling back is composition, not a write: the rail shows the
        // tree without editing the state it was handed.
        compose.onNodeWithText("vault").assertIsDisplayed()
        assertEquals(RailPanel(TASKS_RAIL_PANEL), tree.railPanel)
    }

    @Test
    fun removingThePluginTakesTheTabWithIt() {
        val state = agendaVault()
        state.plugins.add(installedTasksPlugin(state = "removed"))
        showRail(state, TreeState())
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Tasks").assertDoesNotExist()
        compose.onNodeWithContentDescription("files").assertIsDisplayed()
        compose.onNodeWithContentDescription("search").assertIsDisplayed()
    }

    @Test
    fun theShellsOwnPanelsAreKeyedValuesLikeAnyOther() {
        assertEquals("files", RailPanel.FILES.key)
        assertEquals("search", RailPanel.SEARCH.key)
        assertEquals(RailPanel.FILES, RailPanel("files"), "a probe naming a key reaches the same panel")
        assertFalse(RailPanel.FILES == RailPanel(TASKS_RAIL_PANEL))
    }
}
