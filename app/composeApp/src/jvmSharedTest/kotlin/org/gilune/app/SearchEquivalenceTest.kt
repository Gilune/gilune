package org.gilune.app

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchEquivalenceTest {
    /// A Thursday. Everything dated in the corpus is written against it,
    /// so `today`, `overdue` and `week` mean something fixed.
    private val today = LocalDate.of(2026, 9, 3)

    private val plugins = listOf(installedTasksPlugin())
    private val types = searchTypesFor(plugins)

    private val vault: IndexedVault by lazy { corpus() }

    /// Forty-four notes: kernel-written captures, hand-written notes with
    /// front matter of their own, tasks in every state the language can
    /// ask about, a fenced example that must never be found as a task,
    /// notes in folders, and a few whose only distinguishing feature is
    /// their name.
    private fun corpus(): IndexedVault {
        val built = IndexedVault("gilune-equivalence")
        // Twelve captures, so the corpus holds notes whose front matter
        // the kernel wrote rather than a test.
        for (n in 1..12) {
            built.capture("a captured thought numbered $n about the kitchen and the wall")
        }
        built.write(
            "Work/project.md",
            """
            ---
            gilune-id: id-project
            tags: work
            status: draft
            ---
            Notes about the project.
            - [ ] renew the passport due:2026-09-05
            - [ ] file the return due:2026-09-01
            - [x] post the letter due:2026-09-02 done:2026-09-02
            - [ ] someday tidy the loft
            """.trimIndent(),
        )
        built.write(
            "Work/second.md",
            """
            ---
            gilune-id: id-second
            tags: work, urgent
            ---
            - [ ] chase the plasterer due:2026-09-03
            - [x] pay the invoice done:2026-09-03
            A line of ordinary prose mentioning a plasterer.
            """.trimIndent(),
        )
        built.write(
            "Home/kitchen.md",
            """
            ---
            gilune-id: id-kitchen
            mood: Calm
            ---
            - [ ] buy milk due:2026-09-05
            Some prose about milk and the kitchen.
            """.trimIndent(),
        )
        built.write(
            "Docs/filters.md",
            """
            ---
            gilune-id: id-docs
            ---
            How to write one, with due: mentioned in prose:

            ```filter
            type:task
            - [ ] the documented example due:2026-09-04
            ```
            """.trimIndent(),
        )
        built.write(
            "Docs/overdue notes.md",
            """
            ---
            gilune-id: id-overdue
            ---
            - [ ] the very late one due:2026-08-01
            - [ ] no date at all
            """.trimIndent(),
        )
        // Twenty-five plainer notes, so a bare term has a corpus to be
        // wrong about and the ranking has ties to break.
        for (n in 1..25) {
            built.write(
                "Plain/note-$n.md",
                "a plain note number $n\nit mentions milk on its second line\nand ends\n",
            )
        }
        return built
    }

    /// Every operator the shipped language has, and the shapes a person
    /// actually types. Each is asked of both engines.
    private val queries = listOf(
        "",
        "milk",
        "MILK",
        "\"about the kitchen\"",
        "kitchen wall",
        "-milk",
        "milk -kitchen",
        "note-3",
        "file:note-3",
        "file:kitchen",
        "path:Work",
        "path:Plain/",
        "content:milk",
        "content:plasterer",
        "-content:milk",
        "[tags]",
        "[tags:work]",
        "[tags:urgent]",
        "[mood:calm]",
        "[gilune-type]",
        "-[tags]",
        "-[tags:work]",
        "[nobody-wrote-this]",
        "type:note",
        "type:note milk",
        "type:widget",
        "type:task",
        "type:task due:none",
        "type:task -due:none",
        "type:task due:today",
        "type:task due:overdue",
        "type:task due:week",
        "type:task due:2026-09-05",
        "type:task due:2026-02-30",
        "type:task due:soonish",
        "type:task due:before:today",
        "type:task due:after:today",
        "type:task due:before:friday",
        "type:task -due:today",
        "type:task -due:week",
        "type:task -due:overdue",
        "type:task done:none",
        "type:task done:2026-09-02",
        "type:task -done:none",
        "type:task is:open",
        "type:task is:done",
        "type:task -is:open",
        "type:task is:maybe",
        "type:task -is:maybe",
        "type:task path:Work",
        "type:task path:Work due:none",
        "type:task [tags:work]",
        "type:task -[tags:work]",
        "type:task passport",
        "type:task \"tidy the loft\"",
        "type:task file:kitchen",
        "type:task milk due:week",
        "type:task due:week is:open path:Home",
        "documented example",
        "type:task documented",
        "no-such-word-anywhere",
        "of",
        "il",
    )

    private fun scanned(query: String) = searchVault(vault.dir, query, types = types, today = today)

    private fun asked(query: String) = queryVault(vault.gilune, vault.dir, query, types = types, today = today)

    @Test
    fun everyOperatorAnswersWhatReadingEveryFileWouldHave() {
        val differed = mutableListOf<String>()
        for (query in queries) {
            val oracle = scanned(query)
            val engine = asked(query)
            if (oracle != engine) {
                differed += "$query: scan ${oracle.map { it.relPath }} vs query ${engine.map { it.relPath }}"
            }
        }
        assertEquals(emptyList(), differed, "the index and the scan disagree")
    }

    @Test
    fun theCorpusIsBigEnoughAndVariedEnoughToBeEvidence() {
        // A corpus that quietly shrank would make the test above pass by
        // asking nothing.
        assertTrue(scanned("type:note").size >= 40, "at least forty notes")
        assertTrue(scanned("type:task").isNotEmpty())
        assertTrue(scanned("[tags:work]").isNotEmpty())
        assertTrue(queries.size >= 40, "and enough questions to be worth asking")
        // Every query in the list must be answerable at all; a typo in the
        // table would otherwise compare two empty answers for ever.
        val answered = queries.count { scanned(it).isNotEmpty() }
        assertTrue(answered >= queries.size / 2, "half the table finds something: $answered")
    }

    // ------------------------------------------------------------------
    // The deltas this round found, each stated rather than hidden
    // ------------------------------------------------------------------

    @Test
    fun shortAndLongUnicodeTermsHaveTheSameCaseFolding() {
        val accented = IndexedVault("gilune-short-terms")
        accented.write("Cafe.md", "a note about a CAFÉ and its éclairs\n")
        for (query in listOf("café", "ÉC", "é")) {
            val scanned = searchVault(accented.dir, query)
            assertTrue(scanned.isNotEmpty())
            assertEquals(scanned, queryVault(accented.gilune, accented.dir, query))
        }
    }

    @Test
    fun aQuarantinedFileIsNotInTheIndexAndSoIsNotFound() {
        // The other delta, and it is the kernel's rule rather than this
        // round's: a file whose front matter the kernel cannot read is
        // quarantined and its rows are dropped, so its words are
        // unfindable until a person decides what it is. The scan reads
        // every `.md` on disk and does not know that.
        val held = IndexedVault("gilune-quarantined")
        val rel = held.capture("a note that will lose its head")
        java.io.File(held.dir, rel).writeText("---\nno colon here\n---\nthe quarantined words\n")
        held.catchUp()
        assertTrue(searchVault(held.dir, "quarantined words").isNotEmpty())
        assertTrue(
            queryVault(held.gilune, held.dir, "quarantined words").isEmpty(),
            "a quarantined file waits for a person rather than answering queries",
        )
    }
}
