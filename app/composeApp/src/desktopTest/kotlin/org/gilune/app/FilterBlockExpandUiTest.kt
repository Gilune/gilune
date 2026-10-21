// `+N more` opens the list (owner, 2026-10-21). A filter block shows a
// page of rows and counts the rest; clicking the count shows the next
// page, and `Show fewer` folds it back. In the editor the block also holds
// open exactly the lines it now draws, so the text below moves with it.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.rules.RuleChain
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FilterBlockExpandUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private fun show(tasks: Int, reading: Boolean): EditorState {
        val indexed = resources.indexed("gilune-filter-expand")
        val lines = (1..tasks).joinToString("\n") { "- [ ] errand %03d".format(it) }
        indexed.write("Home/errands.md", "---\ngilune-id: id-errands\n---\n$lines\n")
        indexed.write("agenda.md", "---\ngilune-id: id-agenda\n---\n# Agenda\n\n```$FILTER_FENCE\ntype:task\nis:open\n```\n\nbelow the block\n")
        val state = GiluneState(indexed.dir, resources.scope)
        state.gilune.value = indexed.gilune
        state.plugins.add(installedTasksPlugin())
        val editor = EditorState()
        editor.open(indexed.dir, "agenda.md")
        editor.reading = reading
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
        return editor
    }

    private fun rows() = compose.onAllNodesWithText("errand ", substring = true).fetchSemanticsNodes().size

    private fun awaitRows(count: Int) {
        runCatching { compose.waitUntil(10_000) { rows() == count } }
            .onFailure { throw AssertionError("wanted $count rows, saw ${rows()}: " +
                compose.onAllNodesWithText("errand ", substring = true).fetchSemanticsNodes().take(3)
                    .map { it.config.toString().take(200) }, it) }
    }

    @Test
    fun inTheEditorMoreOpensThePageAndFewerFoldsItBack() {
        val editor = show(tasks = 12, reading = false)
        awaitRows(FILTER_EDIT_ROW_CAP)
        compose.onAllNodesWithText("+4 more").fetchSemanticsNodes().single()

        compose.onNodeWithTag("filter-more").performClick()
        awaitRows(12)
        assertEquals(0, compose.onAllNodesWithTag("filter-more").fetchSemanticsNodes().size, "nothing more to open")
        assertEquals("agenda.md", editor.openRelPath)

        compose.onNodeWithTag("filter-fewer").performClick()
        awaitRows(FILTER_EDIT_ROW_CAP)
        assertEquals(0, compose.onAllNodesWithTag("filter-fewer").fetchSemanticsNodes().size, "folded back")
        compose.onAllNodesWithText("+4 more").fetchSemanticsNodes().single()
    }

    @Test
    fun openingABlockNeverChangesTheNote() {
        val editor = show(tasks = 12, reading = false)
        val before = editor.text.text
        awaitRows(FILTER_EDIT_ROW_CAP)
        compose.onNodeWithTag("filter-more").performClick()
        awaitRows(12)
        assertEquals(before, editor.text.text, "the rows are drawn into reserved lines, never written")
        assertEquals(false, editor.dirty)
    }

    @Test
    fun whilePresentingMoreOpensTheNextFifty() {
        show(tasks = FILTER_ROW_CAP + 5, reading = true)
        awaitRows(FILTER_ROW_CAP)
        compose.onAllNodesWithText("+5 more").fetchSemanticsNodes().single()

        compose.onNodeWithTag("filter-more").performScrollTo().performClick()
        awaitRows(FILTER_ROW_CAP + 5)
        compose.onNodeWithTag("filter-fewer").performScrollTo().performClick()
        awaitRows(FILTER_ROW_CAP)
    }
}
