// Notes and subtasks under a task, in a filter block (owner, 2026-10-21):
// folded behind a marker by default, opened by a click, opened for every
// row by `show:notes`, and a subtask ticked from the list writes the note.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.rules.RuleChain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TaskDetailUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private lateinit var dir: String

    private fun show(block: String, reading: Boolean) {
        val indexed = resources.indexed("gilune-task-detail")
        dir = indexed.dir
        indexed.write("Home/show.md", "---\ngilune-id: id-show\n---\n- [ ] prepare the show\n  need the samples\n  - [ ] book the room\n  - [x] tell the team\n")
        indexed.write("agenda.md", "---\ngilune-id: id-agenda\n---\n# Agenda\n\n```$FILTER_FENCE\n$block\n```\n")
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
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun inTheEditorTheMarkerSummarisesAndAClickOpensTheRow() {
        show("type:task\nis:open", reading = false)
        compose.waitUntil(10_000) { shown("1 note · 1/2 done") }
        assertTrue(!shown("need the samples"), "folded by default")
        assertTrue(!shown("book the room"), "its subtask is not listed again as a row of its own")

        compose.onNodeWithTag("task-detail-toggle").performClick()
        compose.waitUntil(10_000) { shown("need the samples") }
        assertTrue(shown("tell the team"), "a ticked subtask is listed under its parent even though is:open left it out")
    }

    @Test
    fun showNotesOpensEveryRowAndAClickFoldsOne() {
        show("type:task\nis:open\nshow:notes", reading = true)
        compose.waitUntil(10_000) { shown("need the samples") }
        compose.onNodeWithTag("task-detail-toggle").performScrollTo().performClick()
        compose.waitUntil(10_000) { !shown("need the samples") }
    }

    @Test
    fun tickingASubtaskFromTheListWritesTheNote() {
        show("type:task\nis:open\nshow:notes", reading = true)
        compose.waitUntil(10_000) { shown("need the samples") }
        // It matches is:open too, but its parent is listed, so it appears
        // once: under the parent.
        val boxes = compose.onAllNodesWithContentDescription("Complete task: book the room")
        assertTrue(boxes.fetchSemanticsNodes().size == 1, "listed once, under its parent")
        boxes[0].performScrollTo().performClick()
        compose.waitUntil(10_000) { File(dir, "Home/show.md").readText().contains("  - [x] book the room") }
    }
}
