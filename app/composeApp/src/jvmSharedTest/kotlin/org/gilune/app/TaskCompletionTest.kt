// `done:` against the real kernel (ADR 0025): a box ticked in another app,
// with no `done:` written, is completed on the day the kernel recorded the
// tick — and a tick that arrived with the note's adoption stays undated.
// Also the account a query gives of itself: the days a relative date took,
// and why a value matches nothing.

package org.gilune.app

import org.gilune.plugins.tasks.WeekStart
import org.gilune.plugins.tasks.tasksSettingsDocument

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskCompletionTest {
    private val types = searchTypesFor(listOf(installedTasksPlugin()))

    private fun words(result: FilterResult): List<String> =
        (result as FilterResult.Tasks).rows.map { it.text.substringAfter("] ").trim() }

    /// A vault the kernel manages, so its notes have revisions to read.
    private fun managed(prefix: String) = IndexedVault(prefix).also { File(it.root, ".gilune-local").delete() }

    /// Rewrite a note's body under the header the kernel gave it, as an
    /// editor that is not Gilune would, and let the kernel see it.
    private fun editBody(vault: IndexedVault, relPath: String, body: String) {
        val file = File(vault.root, relPath)
        val text = file.readText()
        val header = text.substring(0, text.indexOf("---", 3) + 4)
        file.writeText(header + body)
        vault.gilune.reconcilePath(relPath)
        vault.gilune.advanceIndex()
    }

    @Test
    fun aTickMadeElsewhereIsCompletedTheDayItWasRecorded() {
        managed("gilune-completion").use { vault ->
            val today = LocalDate.now()
            vault.write("list.md", "- [x] ticked before Gilune saw it\n- [ ] post the letter\n")
            editBody(vault, "list.md", "- [x] ticked before Gilune saw it\n- [x] post the letter\n")

            fun run(block: String) = evaluateFilterBlock(vault.gilune, vault.dir, block, types, today)
            assertEquals(listOf("post the letter"), words(run("type:task done:today")))
            assertEquals(listOf("post the letter"), words(run("type:task done:thisweek")))
            assertEquals(
                listOf("ticked before Gilune saw it"),
                words(run("type:task is:done done:none")),
                "a tick that came with the adoption is undated, not dated the adoption day",
            )
            assertEquals(today, (run("type:task done:today") as FilterResult.Tasks).rows.single().done)
            assertEquals(listOf("ticked before Gilune saw it"), words(run("type:task is:done -done:today")))
            assertTrue(File(vault.root, "list.md").readText().contains("- [x] post the letter\n"), "nothing is written to the note")
        }
    }

    @Test
    fun aWrittenDoneDateWinsOverTheHistory() {
        managed("gilune-completion-written").use { vault ->
            val today = LocalDate.now()
            vault.write("list.md", "- [ ] renew\n")
            editBody(vault, "list.md", "- [x] renew done:2026-01-05\n")
            fun run(block: String) = evaluateFilterBlock(vault.gilune, vault.dir, block, types, today)
            assertEquals(listOf("renew done:2026-01-05"), words(run("type:task done:2026-01")))
            assertEquals(emptyList(), words(run("type:task done:today")))
        }
    }

    @Test
    fun aQuerySaysWhichDaysItTookAndWhyAValueMeansNothing() {
        val friday = LocalDate.of(2026, 10, 30)
        val compiled = compileSearch("type:task done:thisweek -due:lastweek", types = types, today = friday)!!
        assertEquals(listOf("done 26 Oct – 1 Nov 2026", "not due 19 Oct – 25 Oct 2026"), compiled.explanations)
        assertEquals(emptyList(), compiled.problems)

        val typo = compileSearch("type:task done:thismnth", types = types, today = friday)!!
        assertEquals(1, typo.problems.size)
        assertTrue("did you mean thismonth?" in typo.problems.single(), typo.problems.single())

        val sunday = searchTypesFor(listOf(installedTasksPlugin(settings = tasksSettingsDocument("", weekStart = WeekStart.SUNDAY))))
        assertEquals(
            listOf("done 25 Oct – 31 Oct 2026"),
            compileSearch("type:task done:thisweek", types = sunday, today = friday)!!.explanations,
            "the week-start setting reaches the query",
        )
    }

    @Test
    fun aFilterBlockWithAValueThatMeansNothingSaysSoInsteadOfListingNothing() {
        IndexedVault("gilune-completion-hint").use { vault ->
            vault.write("list.md", "- [ ] a\n")
            val result = evaluateFilterBlock(vault.gilune, vault.dir, "type:task\ndue:tomorow", types, LocalDate.of(2026, 10, 30))
            assertTrue(result is FilterResult.Hint && "did you mean tomorrow?" in result.text, result.toString())
            val dated = evaluateFilterBlock(vault.gilune, vault.dir, "type:task\ndue:thismonth", types, LocalDate.of(2026, 10, 30))
            assertEquals("due 1 Oct – 31 Oct 2026", (dated as FilterResult.Tasks).account)
        }
    }
}
