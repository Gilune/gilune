package org.gilune.app

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.gilune.plugins.people.*
import org.gilune.plugins.tasks.*
import uniffi.gilune_ffi.*

class LocalIndexerTest {
    @Test fun localPluginPartitionsEqualFullFactsAcrossMarkdownContexts() {
        val examples = listOf(
            "---\nconnections: {owner: '@alice'}\n---\n- [ ] One assign: @bob due:2026-09-20\n  more @carol\n  - [x] Child owner: @alice\n\nOutside @dave\n",
            "```\n- [ ] hidden @alice\n```\n- [ ] visible `@hidden` @bob\n",
            "- [ ] 😀 due:2026-09-20\r\n\r\nowner: @alice reviewer: @bob\r\n",
            "[not a person](https://example.com/@alice) and \\@escaped; @bob\n",
        )
        examples.forEach { text ->
            val source = inspectSourceText(text)
            val locations = listOf(SourceLocation.DocumentRoot) + source.lines.map { SourceLocation.BodyLine(it.number) } + source.items.map { SourceLocation.MarkdownItem(it.key) }
            val people = PeopleIndexer()
            val tasks = TasksScopedIndexer()
            assertEquals(people.index("a.md", source).toSet(), locations.flatMap { people.indexLocations("a.md", source, listOf(it)) }.toSet())
            assertEquals(tasks.index("a.md", source).toSet(), locations.flatMap { tasks.indexLocations("a.md", source, listOf(it)) }.toSet())
        }
    }

    @Test fun ffiLocalProducerSeesOnlyChangedLocationsAndRebuildRemainsEquivalent() {
        val root = Files.createTempDirectory("gilune-local-input").toFile()
        val file = File(root, "a.md")
        file.writeText("- [ ] task assign: @alice due:2026-09-20\n".repeat(1000))
        Gilune.open(root.absolutePath).use { kernel ->
            var selected = 0
            var returned = 0
            val producer = PeopleIndexer()
            kernel.registerLocalScopedIndexer(PEOPLE_PLUGIN, 1, emptyList(), object : LocalScopedIndexer {
                override fun indexLocations(relPath: String, source: SourceTextSnapshot, locations: List<SourceLocation>): List<ScopedFact> {
                    selected = locations.size
                    return producer.indexLocations(relPath, source, locations).also { returned = it.size }
                }
            })
            kernel.reconcilePath("a.md")
            assertTrue(selected > 1000)
            file.writeText(file.readText().replaceFirst("@alice", "@robert"))
            kernel.reconcilePath("a.md")
            assertEquals(2, selected)
            assertEquals(4, returned)
            fun matching(handle: String): Int {
                var cursor: String? = null
                var count = 0
                do {
                    val query = LocationQuery(listOf(Constraint.FactAt(PERSON_REFERENCE, ConstraintOp.EQ, handle, FactLocality.EXACT)), LocationKind.MARKDOWN_ITEM, listOf(PEOPLE_PLUGIN), 500u, cursor)
                    val page = kernel.queryLocations(query)
                    count += page.matches.size
                    cursor = page.nextCursor
                } while (cursor != null)
                return count
            }
            assertEquals(1, matching("robert"))
            assertEquals(999, matching("alice"))
            kernel.rebuildIndex(null)
            assertEquals(1, matching("robert"))
            assertEquals(999, matching("alice"))
        }
    }
}
