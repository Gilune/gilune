package org.gilune.app

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import uniffi.gilune_ffi.GiluneException

class KernelSearchTest {
    private val today = LocalDate.of(2026, 9, 5)
    private val types = searchTypesFor(listOf(installedTasksPlugin()))

    @Test
    fun anEmptyMetadataValueStillHasItsKey() {
        val vault = IndexedVault()
        vault.write("empty.md", "---\ngilune-id: empty-fixture\nempty:\n---\nbody")
        assertEquals(1uL, compileSearch("[empty]")!!.page(vault.gilune).totalHits)
        assertEquals(0uL, compileSearch("-[empty]")!!.page(vault.gilune).totalHits)
    }

    @Test
    fun documentsMatchAcrossLinesAndResultsUseTheIndexedRevision() {
        val vault = IndexedVault()
        vault.write("words.md", "alpha first\n\nbeta second\n")
        File(vault.dir, "words.md").writeText("replacement not yet reconciled")
        val page = compileSearch("alpha beta")!!.page(vault.gilune).requireComplete()
        assertEquals(1uL, page.totalHits)
        assertEquals(listOf(1u, 3u), page.hits.single().lines.map { it.number })
        assertEquals(listOf("alpha first", "beta second"), page.hits.single().lines.map { it.text })
        assertEquals(1uL, compileSearch("\"alpha first\n\nbeta\"")!!.page(vault.gilune).totalHits)
    }

    @Test
    fun agendaSortsAndCountsAllTaskLinesBeforeApplyingItsCap() {
        val vault = IndexedVault()
        val body = buildString {
            repeat(250) { append("- [ ] ordinary $it due:2026-09-04\n") }
            append("- [ ] earliest due:2026-01-01\n")
            append("- [x] completed due:2025-01-01 done:2026-09-01\n")
            append("- [ ] no date\n")
        }
        vault.write("tasks.md", body)
        val result = loadAgenda(vault.gilune, types, today)
        val overdue = result.sections.first { it.group == AgendaGroup.OVERDUE }
        assertEquals(251, overdue.count)
        assertEquals(50, overdue.rows.size)
        assertEquals(251, overdue.rows.first().line)
        assertEquals(LocalDate.of(2026, 1, 1), overdue.rows.first().due)
        val filtered = evaluateFilterBlock(vault.gilune, vault.dir, "type:task is:open due:overdue", types, today, cap = 2)
        assertTrue(filtered is FilterResult.Tasks)
        assertEquals(249, filtered.more)
        assertEquals(251, filtered.rows.first().line)
    }

    @Test
    fun continuationHasNoDuplicatesAndExpiresAfterAnEdit() {
        val vault = IndexedVault()
        repeat(7) { vault.write("$it.md", "needle") }
        val query = compileSearch("needle", pageSize = 2u)!!
        val paths = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = query.page(vault.gilune, cursor).requireComplete()
            assertEquals(7uL, page.totalHits)
            assertTrue(page.hits.size <= 2)
            paths += page.hits.map { it.relPath }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals((0..6).map { "$it.md" }, paths)
        val continuation = query.page(vault.gilune).nextCursor!!
        vault.write("0.md", "changed needle")
        assertFailsWith<GiluneException.SearchCursorExpired> { query.page(vault.gilune, continuation) }
    }

    @Test
    fun pluginFallbackRunsOnIndexedDocumentsAndCanFailVisibly() {
        val vault = IndexedVault()
        vault.write("custom.md", "intro\n\ncustom row\n")
        val custom = SearchType("custom", extract = { note ->
            note.lines.mapIndexedNotNull { i, line -> if (line.startsWith("custom")) CandidateLine(i + 1, line) else null }
        })
        File(vault.dir, "custom.md").writeText("changed on disk")
        val page = compileSearch("type:custom", types = listOf(custom))!!.page(vault.gilune)
        assertEquals(3u, page.hits.single().lines.single().number)
        val broken = object : SearchOperator {
            override val name = "broken"
            override fun matches(note: SearchedNote, argument: String): Boolean = error("predicate failed")
        }
        assertFailsWith<GiluneException> {
            compileSearch("broken:x", operators = SearchOperators(broken))!!.page(vault.gilune)
        }
    }
}
