package org.gilune.app

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.gilune.plugin.sdk.*
import org.gilune.plugins.people.*
import org.gilune.plugins.fixture.*
import uniffi.gilune_ffi.*

class IndexedLookupTest {
    private fun withPeople(run: (File, Gilune, PluginExtensions) -> Unit) {
        val root = Files.createTempDirectory("gilune-lookup").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            val registry = PluginExtensions(listOf(peopleExtensions()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            run(root, kernel, registry)
        }
    }
    private fun person(root: File, kernel: Gilune, path: String, handle: String, name: String) {
        File(root, path).writeText("---\ngilune-type: $PERSON_TYPE\ngilune-id: imported-$path\nname: ${pageJsonString(name)}\nhandle: ${pageJsonString(handle)}\n---\n")
        kernel.reconcilePath(path)
    }
    private fun context(fragment: String) = LookupContext(1, "buffer-version", 4, PEOPLE_LOOKUP, fragment)

    @Test fun peopleDeclaresCompleteRankedAlternativesAndPreservesCollidingSources() = withPeople { root, kernel, registry ->
        person(root, kernel, "prefix.md", "Alice.Smith", "A name")
        person(root, kernel, "exact.md", "Alice", "Z name")
        person(root, kernel, "duplicate.md", "ALICE", "B name")
        person(root, kernel, "name.md", "bob", "Alice by name")
        person(root, kernel, "unrelated.md", "carol", "Carol")
        val requested = context("@ALICE")
        var cursor: String? = null
        val rows = mutableListOf<LookupSuggestion>()
        do {
            val page = loadIndexedLookup(kernel, registry, requested, 1u, cursor)
            assertEquals(requested, page.context); assertEquals(4uL, page.total)
            rows += page.rows; cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(listOf("duplicate.md", "exact.md", "prefix.md", "name.md"), rows.map { it.source.relPath })
        assertEquals(listOf("@ALICE ", "@Alice ", "@Alice.Smith ", "@bob "), rows.map { it.row.insertion })
        assertTrue(rows.all { it.row.detail.contains(it.source.relPath) && it.source.sourceVersion.isNotBlank() })
        File(root, "exact.md").writeText("unreconciled edit")
        assertEquals(4uL, loadIndexedLookup(kernel, registry, requested).total)
        kernel.reconcilePath("exact.md")
        assertEquals(3uL, loadIndexedLookup(kernel, registry, requested).total)
        val before = loadIndexedLookup(kernel, registry, requested).rows
        kernel.rebuildIndex(null)
        assertEquals(before, loadIndexedLookup(kernel, registry, requested).rows)
        assertEquals(0uL, loadIndexedLookup(kernel, registry, context("missing")).total)
        assertFalse(File(root, "People/missing.md").exists())
    }

    @Test fun unicodeIncompletePagesAndLatePageMatchesAreNotTruncated() = withPeople { root, kernel, registry ->
        repeat(65) { person(root, kernel, "$it.md", "handle.$it", "Name $it") }
        person(root, kernel, "unicode.md", "Straße", "Équipe")
        person(root, kernel, "broken.md", "", "Broken")
        val named = loadIndexedLookup(kernel, registry, context("E\u0301Q"))
        assertEquals("@Straße ", named.rows.single().row.insertion)
        assertEquals("unicode.md", loadIndexedLookup(kernel, registry, context("STRASS")).rows.single().source.relPath)
        assertEquals("64.md", loadIndexedLookup(kernel, registry, context("handle.64")).rows.first().source.relPath)
        val incomplete = loadIndexedLookup(kernel, registry, context("Broken")).rows.single()
        assertNull(incomplete.row.insertion)
        var cursor: String? = null
        val paths = mutableSetOf<String>()
        do {
            val page = loadIndexedLookup(kernel, registry, context(""), 10u, cursor)
            assertEquals(67uL, page.total)
            page.rows.forEach { assertTrue(paths.add(it.source.relPath)) }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(67, paths.size)
    }

    @Test fun unrelatedConsumerUsesTheSameServiceWithoutPeopleOrTasks() {
        val root = Files.createTempDirectory("gilune-label-lookup").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            val registry = PluginExtensions(listOf(lookupFixture()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            File(root, "label.md").writeText("---\nlabel: Important\n---\n")
            kernel.reconcilePath("label.md")
            val context = LookupContext(7, "source", 2, LABEL_LOOKUP, "Imp")
            assertEquals("label:Important ", loadIndexedLookup(kernel, registry, context).rows.single().row.insertion)
            kernel.removePlugin(LOOKUP_FIXTURE)
            assertFailsWith<IllegalStateException> { loadIndexedLookup(kernel, registry, context) }
        }
    }

    @Test fun lifecycleAndRegistrationIdentityGateExecutableProviders() = withPeople { root, kernel, registry ->
        val registered = peopleExtensions()
        assertFailsWith<IllegalArgumentException> {
            PluginExtensions(listOf(BundledPluginExtensions(registered.declaration.copy(contributions = emptyList()), indexedLookups = registered.indexedLookups)))
        }
        assertFailsWith<IllegalArgumentException> {
            PluginExtensions(listOf(BundledPluginExtensions(registered.declaration, indexedLookups = registered.indexedLookups + registered.indexedLookups)))
        }
        person(root, kernel, "alice.md", "alice", "Alice")
        val owner = kernel.plugins().single { it.pluginId == PEOPLE_PLUGIN }
        assertNotNull(registry.indexedLookup(listOf(owner), PEOPLE_LOOKUP))
        for (state in listOf("disabled", "removed")) assertNull(registry.indexedLookup(listOf(owner.copy(state = state)), PEOPLE_LOOKUP))
        assertNull(registry.indexedLookup(listOf(owner.copy(pluginId = "impostor")), PEOPLE_LOOKUP))
        assertNull(registry.indexedLookup(listOf(owner.copy(contributions = emptyList())), PEOPLE_LOOKUP))
        val initial = loadIndexedLookup(kernel, registry, context(""), 1u)
        person(root, kernel, "bob.md", "bob", "Bob")
        assertNotEquals(initial.generation, loadIndexedLookup(kernel, registry, context("")).generation)
        kernel.removePlugin(PEOPLE_PLUGIN)
        assertFailsWith<IllegalStateException> { loadIndexedLookup(kernel, registry, context("")) }
    }
}
