package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.gilune.plugin.sdk.*
import org.gilune.plugins.people.*
import uniffi.gilune_ffi.*

class PeoplePluginTest {
    private val registry = PluginExtensions(listOf(peopleExtensions()))
    private fun withPeople(test: (File, Gilune) -> Unit) {
        val root = Files.createTempDirectory("gilune-people").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            assertTrue(registry.initializePages(kernel).isEmpty())
            test(root, kernel)
        }
    }
    private fun query(kernel: Gilune, type: String = "person") =
        compileSearch("type:$type", types = registry.searchTypes(kernel.plugins()), maxLines = 0)!!
    private fun create(kernel: Gilune, type: String, draft: PageDraft, folder: String? = null): PageReceipt {
        val service = BoundPageCreation(kernel, PEOPLE_PLUGIN)
        val definition = service.definitions().single { it.typeId == type }
        val result = service.create(CreatePageRequest(type, definition.version, UUID.randomUUID().toString(),
            "${folder ?: definition.defaultFolder}/${draft.relativeFile}", draft.source))
        kernel.project()
        return result
    }

    @Test fun peopleCreatesAndListsWithoutTasksAndReadsOnlyIndexedDisplayFacts() = withPeople { root, kernel ->
        assertTrue(kernel.plugins().any { it.pluginId == PEOPLE_PLUGIN })
        assertFalse(kernel.plugins().any { it.pluginId == "org.memory.tasks" })
        val receipt = create(kernel, PERSON_TYPE, personDraft(mapOf("name" to "Alice Smith", "handle" to "Alice.Smith")))
        assertEquals("People/alice.smith.md", receipt.relPath)
        val file = File(root, receipt.relPath)
        val original = file.readText()
        file.writeText("unreconciled replacement")
        val hit = query(kernel).page(kernel).requireComplete().hits.single()
        assertTrue(hit.lines.isEmpty())
        val row = peopleExtensions().pageCollections.first().row(hit.facts, hit.name)
        assertEquals(PageRow("Alice Smith", "@Alice.Smith"), row)
        assertEquals(1uL, identityMatches(kernel, query(kernel), PERSON_HANDLE, "alice.smith").totalHits)
        file.writeText(original)
        val before = hit.facts.toSet()
        kernel.rebuildIndex(PEOPLE_PLUGIN)
        assertEquals(before, query(kernel).page(kernel).requireComplete().hits.single().facts.toSet())
    }

    @Test fun initialConnectionsRespectRenamesRetirementAndPluginReenabling() = withPeople { root, kernel ->
        val before = query(kernel, "person-connection").page(kernel).requireComplete().hits
        assertEquals(setOf("assign", "owner"), before.flatMap { it.facts }.filter { it.key == KEYWORD }.map { it.value }.toSet())
        val file = File(root, "People/Connections/owner.md")
        file.writeText(file.readText().replace("keyword: \"owner\"", "keyword: \"lead\"").replace("retired: false", "retired: true"))
        kernel.reconcilePath("People/Connections/owner.md")
        kernel.setPluginEnabled(PEOPLE_PLUGIN, false)
        assertTrue(registry.initializePages(kernel).isEmpty())
        kernel.setPluginEnabled(PEOPLE_PLUGIN, true)
        registry.registerPlugins(kernel)
        assertTrue(registry.initializePages(kernel).isEmpty())
        val after = query(kernel, "person-connection").page(kernel).requireComplete().hits
        assertEquals(setOf("assign", "lead"), after.flatMap { it.facts }.filter { it.key == KEYWORD }.map { it.value }.toSet())
        assertTrue(file.readText().contains("retired: true"))
        assertTrue(File(root, "Settings/Initialization/people-catalogue.md").isFile)
    }

    @Test fun definitionsArePortableAndRetirementDoesNotHideExistingKeywords() = withPeople { root, kernel ->
        create(kernel, CONNECTION_TYPE, connectionDraft(mapOf("keyword" to "reviewer", "label" to "Reviewer", "retired" to "true")))
        val before = query(kernel, "person-connection").page(kernel).requireComplete().hits
        assertEquals(3, before.size)
        assertTrue(before.any { hit -> hit.facts.any { it.key == RETIRED && it.value == "true" } })
        val exported = Files.createTempDirectory("gilune-people-export").toFile()
        root.walkTopDown().filter { it.isFile && it.extension == "md" && it.relativeTo(root).path.startsWith("People") }.forEach { file ->
            val dest = File(exported, file.relativeTo(root).path); dest.parentFile.mkdirs(); file.copyTo(dest)
        }
        Gilune.open(exported.absolutePath).use { rebuilt ->
            registry.registerPlugins(rebuilt); registry.registerIndexers(rebuilt); rebuilt.reconcile(); rebuilt.rebuildIndex(null)
            val after = query(rebuilt, "person-connection").page(rebuilt).requireComplete().hits
            assertEquals(before.associate { it.relPath to it.facts.toSet() }, after.associate { it.relPath to it.facts.toSet() })
        }
    }

    @Test fun externalPropertyChangesPreserveIdentityAndMakeNormalizedCollisionsVisible() = withPeople { root, kernel ->
        val first = create(kernel, PERSON_TYPE, personDraft(mapOf("name" to "First", "handle" to "Straße")))
        create(kernel, PERSON_TYPE, personDraft(mapOf("name" to "Second", "handle" to "STRASSE")), "Other people")
        assertEquals(2uL, identityMatches(kernel, query(kernel), PERSON_HANDLE, "strasse").totalHits)
        val file = File(root, first.relPath)
        file.writeText(file.readText().replace("handle: \"Straße\"", "handle: \"other\""))
        kernel.reconcilePath(first.relPath); kernel.project()
        assertEquals(first.objectId, kernel.documentSnapshot(first.relPath).source.objectId)
        assertEquals(1uL, identityMatches(kernel, query(kernel), PERSON_HANDLE, "strasse").totalHits)
        assertEquals(1uL, identityMatches(kernel, query(kernel), PERSON_HANDLE, "other").totalHits)
    }

    @Test fun malformedDefinitionsRemainVisibleAndYamlAliasesHaveOneInterpretation() {
        val indexer = PeopleIndexer()
        val facts = indexer.index("any.md", "---\ngilune-type: $PERSON_TYPE\ngilune-id: fixture\nbase: &base {name: Alice, handle: ALICE}\n<<: *base\n---\n")
        assertTrue(facts.any { it.predicate == PERSON_HANDLE && it.value == "alice" })
        assertFalse(facts.any { it.predicate == PROBLEM })
        val invalid = indexer.index("any.md", "---\ngilune-type: $PERSON_TYPE\nname: true\nhandle: [alice]\n---\n")
        assertTrue(invalid.any { it.predicate == PERSON_KIND })
        assertTrue(invalid.any { it.predicate == PROBLEM })
        assertFalse(invalid.any { it.predicate == PERSON_HANDLE })
        assertEquals(setOf("alice", "bob"), indexer.index("prose.md", "@alice owner: @bob")
            .filter { it.predicate == PERSON_REFERENCE }.map { it.value }.toSet())
    }

    @Test fun disabledOrRemovedOwnerCannotCreateOrContributeCollections() = withPeople { root, kernel ->
        val receipt = create(kernel, PERSON_TYPE, personDraft(mapOf("name" to "Alice", "handle" to "alice")))
        val bytes = File(root, receipt.relPath).readBytes()
        kernel.setPluginEnabled(PEOPLE_PLUGIN, false)
        assertTrue(registry.pageCollections(kernel.plugins()).isEmpty())
        assertEquals(listOf("note"), registry.searchTypes(kernel.plugins()).map { it.key })
        assertFailsWith<GiluneException.DocumentUnavailable> { BoundPageCreation(kernel, PEOPLE_PLUGIN).definitions() }
        kernel.setPluginEnabled(PEOPLE_PLUGIN, true)
        assertEquals(2, registry.pageCollections(kernel.plugins()).size)
        kernel.revokeCapability(PEOPLE_PLUGIN, PAGE_CREATE_CAPABILITY)
        registry.registerPlugins(kernel)
        assertFailsWith<GiluneException.DocumentUnavailable> { create(kernel, PERSON_TYPE, personDraft(mapOf("name" to "Bob", "handle" to "bob"))) }
        kernel.removePlugin(PEOPLE_PLUGIN); registry.registerPlugins(kernel)
        assertTrue(registry.pageCollections(kernel.plugins()).isEmpty())
        assertContentEquals(bytes, File(root, receipt.relPath).readBytes())
    }

    @Test fun collectionRegistrationsAreOwnedAndCollisionChecked() {
        val original = peopleExtensions()
        fun changed(contributions: List<PluginContribution>, collections: List<PageCollectionRegistration>) =
            BundledPluginExtensions(original.declaration.copy(contributions = contributions), pageCollections = collections)
        assertFailsWith<IllegalArgumentException> { PluginExtensions(listOf(changed(emptyList(), original.pageCollections))) }
        assertFailsWith<IllegalArgumentException> { PluginExtensions(listOf(changed(original.declaration.contributions, original.pageCollections + original.pageCollections))) }
        val impostor = installedPlugin("org.example.copy", contributions = original.declaration.contributions)
        assertTrue(registry.pageCollections(listOf(impostor)).isEmpty())
    }

    @Test fun headerOnlyPagesPageCompletelyAndCountsIncludeLaterResults() = withPeople { root, kernel ->
        for (n in 0 until 125) {
            File(root, "person-$n.md").writeText("---\ngilune-type: $PERSON_TYPE\ngilune-id: imported-$n\nname: Person $n\nhandle: ${if (n == 124) "handle0" else "handle$n"}\n---\n")
        }
        kernel.reconcile(); kernel.advanceIndex()
        val search = query(kernel)
        val start = System.nanoTime()
        var page = search.page(kernel).requireComplete()
        assertEquals(125uL, page.totalHits)
        assertEquals(50, page.hits.size)
        val counts = page.hits.map { hit ->
            val value = hit.facts.single { it.key == PERSON_HANDLE }.value
            identityMatches(kernel, search, PERSON_HANDLE, value).totalHits
        }
        println("People directory: first page plus ${counts.size} indexed identity counts in ${(System.nanoTime() - start) / 1_000_000} ms for 125 pages")
        assertEquals(2uL, identityMatches(kernel, search, PERSON_HANDLE, "handle0").totalHits)
        val paths = page.hits.map { it.relPath }.toMutableList()
        while (page.nextCursor != null) { page = search.page(kernel, page.nextCursor).requireComplete(); paths += page.hits.map { it.relPath } }
        assertEquals(125, paths.distinct().size)
    }

    @Test fun handlesAreNormalizedWithoutLosingReadableSpelling() {
        val draft = personDraft(mapOf("name" to "Élise", "handle" to "E\u0301LISE"))
        assertEquals("élise.md", draft.relativeFile)
        assertTrue(draft.source.contains("E\u0301LISE"))
        assertEquals("strasse.md", personDraft(mapOf("name" to "Straße", "handle" to "Straße")).relativeFile)
        for (invalid in listOf("alice smith", "alice@example.com", "../alice", "alice:", "alice\u200Bsmith")) {
            assertFalse(validToken(invalid), invalid)
        }
        assertTrue(validToken("alice.smith")); assertTrue(validToken("😀"))
    }
}

