// A task clicked in a list opens its note at the task's line (owner,
// 2026-10-21), through the real tabs and editor: the filter block lives in
// one note, the task in another, and the click must leave the caret on
// the task — in a note opened fresh, and in the note already showing.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import org.junit.Rule
import org.junit.rules.RuleChain
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class RevealLineUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private val soon = LocalDate.now().plusDays(1)
    private val task = "- [ ] buy milk due:$soon"

    private fun host(agendaReading: Boolean): Pair<GiluneState, TabsState> {
        val indexed = resources.indexed("gilune-reveal")
        indexed.write("Home/kitchen.md", "---\ngilune-id: id-kitchen\n---\n# Kitchen\n\nsome text\n$task\nafter\n")
        indexed.write("agenda.md", "---\ngilune-id: id-agenda\n---\n# Agenda\n\n```$FILTER_FENCE\ntype:task\nis:open\n```\n")
        val state = GiluneState(indexed.dir, resources.scope)
        state.gilune.value = indexed.gilune
        state.plugins.add(installedTasksPlugin())
        val tabs = TabsState(indexed.dir)
        tabs.open("agenda.md").editor.reading = agendaReading
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    // The pane shows whichever tab is in front, as the shell's does.
                    val tab = tabs.activeTab!!
                    key(tab.id) { NoteEditor(state, tab.editor, Modifier.fillMaxSize(), tabs = tabs, tab = tab) }
                }
            }
        }
        return state to tabs
    }

    private fun assertCaretOnTheTask(tabs: TabsState) {
        compose.waitUntil(10_000) {
            val editor = tabs.activeTab?.editor
            editor?.openRelPath == "Home/kitchen.md" && editor.text.selection != TextRange(0)
        }
        val editor = tabs.activeTab!!.editor
        val end = editor.text.text.indexOf(task) + task.length
        assertEquals(TextRange(end), editor.text.selection, "the caret is at the end of the task's line")
        assertFalse(editor.reading, "writing, so there is a caret")
    }

    @Test
    fun aTaskClickedWhilePresentingOpensItsNoteAtTheTask() {
        val (_, tabs) = host(agendaReading = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("buy milk", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("buy milk", substring = true).performClick()
        assertCaretOnTheTask(tabs)
    }

    @Test
    fun aTaskClickedInTheEditorsOwnBlockOpensItsNoteAtTheTask() {
        val (_, tabs) = host(agendaReading = false)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("buy milk", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("buy milk", substring = true).performClick()
        assertCaretOnTheTask(tabs)
    }

    @Test
    fun aLineAskedOfTheNoteAlreadyInFrontMovesItsCaret() {
        val (state, tabs) = host(agendaReading = false)
        tabs.open("Home/kitchen.md")
        compose.waitUntil(10_000) { tabs.activeTab?.editor?.openRelPath == "Home/kitchen.md" }
        compose.runOnIdle { state.revealNext(NoteLine("Home/kitchen.md", 4, task)) }
        assertCaretOnTheTask(tabs)
        assertEquals(null, state.reveal.value, "answered once")
    }
}
