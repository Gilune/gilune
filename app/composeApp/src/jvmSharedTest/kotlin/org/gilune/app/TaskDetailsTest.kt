// What sits under a task (owner, 2026-10-21): its notes and its direct
// subtasks, read from the note so a subtask the query did not match still
// counts.

package org.gilune.app

import org.gilune.plugins.tasks.TaskHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskDetailsTest {
    private val body = """
        # Head

        - [ ] parent task
          a note line
          - a bullet note
          - [ ] child one
            child note
          - [x] child two

          after a gap
        - [ ] sibling
          sibling note
    """.trimIndent()

    @Test
    fun notesAndDirectSubtasksAreReadAndAChildsOwnNotesStayItsOwn() {
        val detail = taskDetailAt(body, 3)
        assertEquals(listOf("a note line", "• a bullet note", "after a gap"), detail.notes)
        assertEquals(listOf(6 to false, 8 to true), detail.subtasks.map { it.line to it.done })
        assertEquals("  - [ ] child one", detail.subtasks.first().text, "the line as written, for the guarded tick")
        assertEquals("3 notes · 1/2 done", detail.summary)
    }

    @Test
    fun aSubtaskHasItsOwnDetailAndASiblingStopsTheParent() {
        assertEquals(listOf("child note"), taskDetailAt(body, 6).notes)
        assertEquals(listOf("sibling note"), taskDetailAt(body, 11).notes)
        assertTrue(taskDetailAt(body, 8).isEmpty)
        assertTrue(taskDetailAt("- [ ] alone\n\nprose after", 1).isEmpty, "a blank line then prose is not under it")
    }

    @Test
    fun aSubtaskUnderAListedParentIsListedOnceAndOnlyThere() {
        val parent = TaskHit("n.md", "n", 3, "- [ ] parent task", null, null)
        val child = TaskHit("n.md", "n", 6, "  - [ ] child one", null, null)
        val sibling = TaskHit("n.md", "n", 10, "- [ ] sibling", null, null)
        val elsewhere = TaskHit("m.md", "m", 6, "  - [ ] child one", null, null)
        val details = mapOf(parent.detailKey() to taskDetailAt(body, 3))
        assertEquals(listOf(parent, sibling, elsewhere), withoutListedSubtasks(listOf(parent, child, sibling, elsewhere), details),
            "the child goes; a task at the same line in another note does not")
        assertEquals(listOf(child, sibling), withoutListedSubtasks(listOf(child, sibling), details),
            "with its parent not listed, a subtask keeps its own row")
    }

    @Test
    fun theSummaryReadsNaturally() {
        assertEquals("1 note", TaskDetail(listOf("x"), emptyList()).summary)
        assertEquals("0/1 done", TaskDetail(emptyList(), listOf(SubtaskLine(2, "  - [ ] a", false))).summary)
    }

    @Test
    fun showNotesIsADisplayLineAndNeverPartOfTheQuery() {
        val block = "type:task\nshow:notes\nis:open"
        assertEquals("type:task is:open", filterQuery(block))
        assertTrue(filterShowsNotes(block))
        assertTrue(!filterShowsNotes("type:task\nis:open"))
    }

    @Test
    fun anOpenedRowReservesALinePerNoteAndSubtask() {
        val parent = TaskHit("n.md", "n", 3, "- [ ] parent task", null, null)
        val result = FilterResult.Tasks(listOf(parent), 0, mapOf(parent.detailKey() to taskDetailAt(body, 3)))
        assertEquals(1, filterBlockLines(result), "folded: the row alone")
        assertEquals(1 + 5, filterBlockLines(result, flipped = setOf(parent.detailKey())), "opened: three notes, two subtasks")
        assertEquals(6, filterBlockLines(result.copy(showDetails = true)), "show:notes opens it without a click")
        assertEquals(1, filterBlockLines(result.copy(showDetails = true), flipped = setOf(parent.detailKey())), "and a click folds it")
    }
}
