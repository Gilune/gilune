// `type:task` against a real vault on disk: which lines are tasks, what
// the date operators mean, and what happens to them when the plugin that
// registered them is switched off (ADR 0015 § 4).
//
// `today` is injected into every assertion and nothing here reads a
// clock. A test that asked what today is would rot on the day it was
// written, and the whole point of resolving relative words at evaluation
// is that the query does not.

package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskSearchTest {
    /// A Thursday, and the day every assertion below is written against.
    private val today = LocalDate.of(2026, 9, 3)

    private val vault: String by lazy { buildVault() }

    private fun buildVault(): String {
        val root = Files.createTempDirectory("gilune-task-search").toFile()
        fun write(relPath: String, text: String) {
            val file = File(root, relPath)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        // Body lines 1-9. Line 7 carries a date in prose and no marker;
        // line 9 is today + 7, the day `week` stops at.
        write(
            "Work/project.md",
            """
            ---
            gilune-id: id-project
            tags: work
            ---
            Notes about the project.
            - [ ] renew the passport due:2026-09-05
            - [ ] file the return due:2026-09-01
            - [x] post the letter due:2026-09-02 done:2026-09-02
            - [ ] someday tidy the loft
            * [ ] star bullet due:2026-09-03
            A due:2026-09-04 line that is not a task at all
            - [ ] deploy the thing #task due:2026-09-09
            - [ ] plan the party due:2026-09-10
            """.trimIndent(),
        )
        write(
            "Home/kitchen.md",
            """
            ---
            gilune-id: id-kitchen
            ---
            - [ ] buy milk due:2026-09-05
            Some prose about milk.
            """.trimIndent(),
        )
        // The § 1 rule that costs the most to get wrong: the checkbox in
        // the fence is documentation, not somebody's agenda.
        write(
            "Fenced/docs.md",
            """
            ---
            gilune-id: id-docs
            ---
            An example of the syntax:

            ```markdown
            - [ ] documented example due:2026-09-04
            ```

            - [ ] real task in docs due:2026-09-04
            """.trimIndent(),
        )
        return root.toString()
    }

    private fun types(filter: String = "") = listOf(NOTE_SEARCH_TYPE, taskSearchType(filter))

    /// Every hit line as `path:line`, which is what a task hit is.
    /// `maxLines` is raised because these are rows, not quoted prose.
    private fun rows(
        query: String,
        filter: String = "",
        on: LocalDate = today,
        types: List<SearchType> = types(filter),
    ): List<String> =
        searchVault(vault, query, SearchOperators(), types, on, maxLines = 50)
            .flatMap { hit -> hit.lines.map { "${hit.relPath}:${it.number}" } }

    private fun hits(query: String, on: LocalDate = today): List<SearchHit> =
        searchVault(vault, query, SearchOperators(), types(), on, maxLines = 50)

    // -----------------------------------------------------------------
    // What a task line is (§ 1).
    // -----------------------------------------------------------------

    @Test
    fun everyCheckboxIsATaskWhileTheFilterIsEmpty() {
        assertEquals(
            listOf(
                "Fenced/docs.md:7",
                "Home/kitchen.md:1",
                "Work/project.md:2",
                "Work/project.md:3",
                "Work/project.md:4",
                "Work/project.md:5",
                "Work/project.md:6",
                "Work/project.md:8",
                "Work/project.md:9",
            ),
            rows("type:task"),
            "the shipped default, and a bullet is a bullet whichever character opens it",
        )
    }

    @Test
    fun theGlobalFilterDecidesWhichCheckboxesAreTasks() {
        assertEquals(listOf("Work/project.md:8"), rows("type:task", filter = "#task"))
        assertEquals(emptyList(), rows("type:task due:today", filter = "#task"))
    }

    @Test
    fun aCheckboxInsideAFenceIsNotATask() {
        assertEquals(
            listOf("Fenced/docs.md:7"),
            rows("type:task due:2026-09-04"),
            "line 4 is the same date inside a ``` block, and is documentation",
        )
    }

    @Test
    fun aDateInProseIsNotATaskEither() {
        assertEquals(emptyList(), rows("type:task \"not a task at all\""))
    }

    @Test
    fun taskLineNumbersAreBodyRelativeAndTheTextIsTheLineAsWritten() {
        val hit = hits("type:task milk").single()
        assertEquals("Home/kitchen.md", hit.relPath)
        assertEquals(1, hit.lines.single().number, "line 1 of the body, line 4 of the file")
        assertEquals("- [ ] buy milk due:2026-09-05", hit.lines.single().text)
    }

    // -----------------------------------------------------------------
    // The narrowed-note rule (§ 4).
    // -----------------------------------------------------------------

    @Test
    fun textTermsBindToTheLineWhileNoteTermsBindToTheNote() {
        assertEquals(listOf("Work/project.md:2"), rows("type:task passport path:Work"))
        assertEquals(emptyList(), rows("type:task passport path:Home"))
        assertEquals(
            emptyList(),
            rows("type:task passport return"),
            "both words are in the note, neither line has both",
        )
        assertEquals(
            listOf("Work/project.md"),
            searchVault(vault, "passport return").map { it.relPath },
            "the same two words under `note`, where the note is the hit",
        )
    }

    @Test
    fun frontMatterStillAnswersUnderALineType() {
        assertEquals(
            listOf("Work/project.md:2", "Work/project.md:6", "Work/project.md:8"),
            rows("type:task [tags:work] due:week"),
            "the front-matter term is about the note; the date term is about each line",
        )
    }

    @Test
    fun aBareTermUnderTypeTaskHighlightsInsideTheLine() {
        val line = hits("type:task passport").single().lines.single()
        assertEquals(listOf(16..23), line.ranges)
        assertEquals("passport", line.text.substring(16, 24))
    }

    @Test
    fun aTaskKeptByADateAloneHasNothingToHighlightAndStillShows() {
        val hit = hits("type:task due:overdue").single()
        assertEquals(listOf(3, 4), hit.lines.map { it.number })
        assertTrue(hit.lines.all { it.ranges.isEmpty() })
        assertEquals(0, hit.matchCount)
    }

    // -----------------------------------------------------------------
    // The six value forms (§ 4).
    // -----------------------------------------------------------------

    @Test
    fun anIsoValueIsThatDayExactly() {
        assertEquals(listOf("Home/kitchen.md:1", "Work/project.md:2"), rows("type:task due:2026-09-05"))
        assertEquals(emptyList(), rows("type:task due:2026-09-06"))
    }

    @Test
    fun todayIsTheDayTheQueryRunsAndNotADateInTheFile() {
        assertEquals(listOf("Work/project.md:6"), rows("type:task due:today"))
        assertEquals(
            listOf("Home/kitchen.md:1", "Work/project.md:2"),
            rows("type:task due:today", on = LocalDate.of(2026, 9, 5)),
            "the same query, two days on, is a different question",
        )
    }

    @Test
    fun overdueIsStrictlyBeforeTodaySoTodayIsNotOverdue() {
        assertEquals(listOf("Work/project.md:3", "Work/project.md:4"), rows("type:task due:overdue"))
        assertEquals(
            listOf("Work/project.md:3", "Work/project.md:4", "Work/project.md:6"),
            rows("type:task due:overdue", on = LocalDate.of(2026, 9, 4)),
            "the line due today joins the list tomorrow, not before",
        )
    }

    @Test
    fun weekIsTodayThroughTheSixthDayAndStopsThere() {
        assertEquals(
            listOf("Fenced/docs.md:7", "Home/kitchen.md:1", "Work/project.md:2", "Work/project.md:6", "Work/project.md:8"),
            rows("type:task due:week"),
        )
        assertEquals(
            emptyList(),
            rows("type:task due:week due:2026-09-10"),
            "today + 7 is outside the week; today + 6 (line 8) is inside it",
        )
        assertTrue("Work/project.md:8" in rows("type:task due:week"))
    }

    @Test
    fun noneIsALineWithoutTheTokenAndNegatingItMeansHasOne() {
        assertEquals(listOf("Work/project.md:5"), rows("type:task due:none"))
        assertEquals(
            listOf("Work/project.md:4"),
            rows("type:task -done:none"),
            "no `has` keyword is needed: `-done:none` is `has a done date`",
        )
    }

    @Test
    fun beforeAndAfterAreBothExclusive() {
        assertEquals(listOf("Work/project.md:3"), rows("type:task due:before:2026-09-02"))
        assertEquals(
            listOf("Work/project.md:3", "Work/project.md:4"),
            rows("type:task due:before:2026-09-03"),
            "the 2nd arrives only once the boundary moves past it",
        )
        assertEquals(listOf("Work/project.md:9"), rows("type:task due:after:2026-09-09"))
        assertEquals(
            listOf("Work/project.md:8", "Work/project.md:9"),
            rows("type:task due:after:2026-09-05"),
            "the 5th itself is excluded, the 9th and 10th are not",
        )
    }

    @Test
    fun doneTakesTheSameFormsAsDue() {
        assertEquals(listOf("Work/project.md:4"), rows("type:task done:2026-09-02"))
        assertEquals(listOf("Work/project.md:4"), rows("type:task done:overdue"))
        assertEquals(emptyList(), rows("type:task done:today"))
        assertEquals(8, rows("type:task done:none").size, "every task but the finished one")
    }

    @Test
    fun aMalformedValueMatchesNothingRatherThanBecomingText() {
        // A typo inside a known operator has asked a date question, and
        // pretending it was prose would answer a different one.
        for (query in listOf(
            "type:task due:soonish",
            "type:task due:2026-13-40",
            "type:task due:2026-9-5",
            "type:task due:before:soon",
            "type:task due:after:",
            "type:task done:whenever",
        )) {
            assertEquals(emptyList(), rows(query), query)
        }
    }

    // -----------------------------------------------------------------
    // Degradation: the plugin off, or the type never named.
    // -----------------------------------------------------------------

    @Test
    fun dueIsProseUnderTheDefaultType() {
        assertEquals(
            listOf("Home/kitchen.md", "Work/project.md"),
            searchVault(vault, "due:2026-09-05", SearchOperators(), types(), today).map { it.relPath }.sorted(),
            "under `note` the keyword is nobody's, so it reads the bytes as written",
        )
    }

    @Test
    fun withThePluginOffTheTypeIsUnknownAndItsKeywordsAreProse() {
        val off = listOf(NOTE_SEARCH_TYPE)
        assertEquals(emptyList(), rows("type:task due:week", types = off))
        assertEquals(emptyList(), rows("type:task", types = off))
        assertEquals(
            emptyList(),
            rows("due:week", types = off),
            "`due:week` is text now, and no note contains those bytes",
        )
        assertEquals(
            listOf("Home/kitchen.md", "Work/project.md"),
            searchVault(vault, "due:2026-09-05", SearchOperators(), off, today).map { it.relPath }.sorted(),
            "text that is in the vault still finds it — nothing empties silently",
        )
    }

    // -----------------------------------------------------------------
    // The per-line surface T4 consumes.
    // -----------------------------------------------------------------

    @Test
    fun taskRowsCarryTheLineAndTheDatesAlreadyReadOffIt() {
        val row = taskRows(hits("type:task passport")).single()
        assertEquals("Work/project.md", row.relPath)
        assertEquals("project", row.name)
        assertEquals(2, row.line)
        assertEquals("- [ ] renew the passport due:2026-09-05", row.text)
        assertEquals(LocalDate.of(2026, 9, 5), row.due)
        assertEquals(null, row.done)
    }

    @Test
    fun taskRowsAreDueAscendingWithTheUndatedLast() {
        val rows = taskRows(hits("type:task"))
        assertEquals(
            listOf(
                "Work/project.md:3",
                "Work/project.md:4",
                "Work/project.md:6",
                "Fenced/docs.md:7",
                "Home/kitchen.md:1",
                "Work/project.md:2",
                "Work/project.md:8",
                "Work/project.md:9",
                "Work/project.md:5",
            ),
            rows.map { "${it.relPath}:${it.line}" },
            "due ascending, path then line within a day, undated last",
        )
        assertEquals(null, rows.last().due)
        assertEquals(LocalDate.of(2026, 9, 2), rows.first { it.done != null }.done)
    }
}
