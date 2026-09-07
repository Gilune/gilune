package org.gilune.app

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.gilune.plugin.sdk.*
import org.gilune.plugins.people.*
import org.gilune.plugins.fixture.*
import uniffi.gilune_ffi.*

class DocumentRefactoringTest {
    private fun fixture(run: (File, Gilune, PluginExtensions) -> Unit) {
        val root = Files.createTempDirectory("gilune-refactoring").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            val registry = PluginExtensions(listOf(peopleExtensions()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel); registry.initializePages(kernel)
            val definition = BoundPageCreation(kernel, PEOPLE_PLUGIN).definitions().single { it.typeId == PERSON_TYPE }
            BoundPageCreation(kernel, PEOPLE_PLUGIN).create(CreatePageRequest(PERSON_TYPE, definition.version, java.util.UUID.randomUUID().toString(),
                "People/alice.md", "---\nname: Alice\nhandle: alice\n---\n# About\n"))
            kernel.project()
            run(root, kernel, registry)
        }
    }

    @Test fun handleRenameUpdatesMarkdownAndStructuredConnectionsAndUndoRestoresThem() = fixture { root, kernel, registry ->
        val source = "---\nconnections: {owner: \"@alice\", reviewer: [\"@bob\", \"@alice\"]}\nunknown: [a, b]\n---\nHello @alice.\n- item owner: @alice\n`@alice` unchanged\n"
        File(root, "foreign.md").writeText(source)
        val binding = registry.refactorings(kernel.plugins()).single()
        val plan = prepareDocumentRefactoring(kernel, root.path, registry, binding, "People/alice.md", RefactoringIntent("handle", "alice", "alice.smith"))
        val entries = kernel.refactoringEntries(plan.id, 0u, 100u)
        assertTrue(entries.all { it.state == "pending" }, entries.joinToString { "${it.relPath}: ${it.detail}" })
        assertEquals(source, File(root, "foreign.md").readText(), "preview does not write source")
        val applied = applyDocumentRefactoring(kernel, root.path, registry, plan.id)
        assertEquals("completed", applied.state)
        val changed = File(root, "foreign.md").readText()
        assertTrue(changed.contains("Hello @alice.smith."))
        assertTrue(changed.contains("`@alice` unchanged"))
        assertTrue(changed.contains("unknown: [a, b]"))
        assertFalse(changed.contains("gilune-id"))
        val undo = kernel.prepareRefactoringUndo(plan.id)
        val inverse = kernel.refactoringEntries(undo.id, 0u, 100u)
        assertTrue(inverse.all { it.state == "pending" }, inverse.joinToString { "${it.relPath}: ${it.state}: ${it.detail}" })
        assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, undo.id).state)
        assertEquals(source, File(root, "foreign.md").readText())
    }

    @Test fun keywordMergeRetiresTheOldDefinitionAndKeepsBothPeoplePaired() = fixture { root, kernel, registry ->
        File(root, "note.md").writeText("---\nconnections: {owner: \"@alice\", assign: \"@bob\"}\n---\nowner: @alice assign: @bob\n")
        val plan = prepareDocumentRefactoring(kernel, root.path, registry, registry.refactorings(kernel.plugins()).single(),
            "People/Connections/owner.md", RefactoringIntent("keyword", "owner", "assign", true))
        assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, plan.id).state)
        assertTrue(File(root, "People/Connections/owner.md").readText().contains("retired: true"))
        val facts = PeopleIndexer().index("note.md", File(root, "note.md").readText())
        assertTrue(facts.any { it.value == connectionValue("assign", "alice") })
        assertTrue(facts.any { it.value == connectionValue("assign", "bob") })
    }

    @Test fun conflictsPreventDefinitionCompletionAndRemovedProvidersCannotResume() = fixture { root, kernel, registry ->
        File(root, "note.md").writeText("@alice")
        val plan = prepareDocumentRefactoring(kernel, root.path, registry, registry.refactorings(kernel.plugins()).single(),
            "People/alice.md", RefactoringIntent("handle", "alice", "alicia"))
        File(root, "note.md").writeText("my later @alice")
        assertEquals("partial", applyDocumentRefactoring(kernel, root.path, registry, plan.id).state)
        assertTrue(File(root, "People/alice.md").readText().contains("handle: alice"))
        assertEquals("my later @alice", File(root, "note.md").readText())
        kernel.removePlugin(PEOPLE_PLUGIN)
        assertFailsWith<IllegalStateException> { applyDocumentRefactoring(kernel, root.path, registry, plan.id) }
        assertTrue(kernel.refactoringPlans().any { it.id == plan.id })
    }

    @Test fun lateOldSpellingCreatesAnExplicitFollowupWithoutAliases() = fixture { root, kernel, registry ->
        File(root, "note.md").writeText("@alice")
        val binding = registry.refactorings(kernel.plugins()).single()
        val intent = RefactoringIntent("handle", "alice", "alicia")
        val plan = prepareDocumentRefactoring(kernel, root.path, registry, binding, "People/alice.md", intent)
        assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, plan.id).state)
        File(root, "offline.md").writeText("Later @alice")
        kernel.reconcilePath("offline.md")
        assertEquals("Later @alice", File(root, "offline.md").readText())
        val followup = prepareDocumentRefactoring(kernel, root.path, registry, binding, "People/alice.md", intent, previous = kernel.refactoringPlan(plan.id))
        assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, followup.id).state)
        assertEquals("Later @alicia", File(root, "offline.md").readText())
    }

    @Test fun cancellationResumesAndSelectedScopeDoesNotCompleteTheDefinitionEarly() = fixture { root, kernel, registry ->
        repeat(3) { File(root, "$it.md").writeText("@alice") }
        val plan = prepareDocumentRefactoring(kernel, root.path, registry, registry.refactorings(kernel.plugins()).single(),
            "People/alice.md", RefactoringIntent("handle", "alice", "alicia"))
        var stop = false
        val first = applyDocumentRefactoring(kernel, root.path, registry, plan.id, cancelled = { stop }, progress = { stop = true })
        assertEquals(1uL, first.applied)
        assertTrue(File(root, "People/alice.md").readText().contains("handle: alice"))
        assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, plan.id).state)
        repeat(3) { assertEquals("@alicia", File(root, "$it.md").readText()) }
    }

    @Test fun largeDocumentRenameEnumeratesEveryOccurrenceAndRebuildsIdenticalFacts() = fixture { root, kernel, registry ->
        val measuring = java.util.concurrent.atomic.AtomicBoolean(true)
        val peakHeap = java.util.concurrent.atomic.AtomicLong(0)
        val sampler = Thread {
            val deadline = System.nanoTime() + 120_000_000_000
            while (measuring.get() && System.nanoTime() < deadline) {
                val runtime = Runtime.getRuntime()
                peakHeap.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                Thread.sleep(10)
            }
        }.apply { isDaemon = true; start() }
        val original = (0..10_000).joinToString("\n", postfix = "\n") { "- item $it owner: @alice" }
        File(root, "large.md").writeText(original)
        val started = System.nanoTime()
        val plan = prepareDocumentRefactoring(kernel, root.path, registry, registry.refactorings(kernel.plugins()).single(),
            "People/alice.md", RefactoringIntent("handle", "alice", "alicia"))
        val prepared = System.nanoTime()
        assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, plan.id).state)
        val applied = System.nanoTime()
        measuring.set(false); sampler.join()
        assertEquals(original.replace("@alice", "@alicia"), File(root, "large.md").readText())
        fun count(): Int {
            var count = 0
            var cursor: String? = null
            do {
                val page = kernel.queryLocations(LocationQuery(listOf(Constraint.FactAt(PERSON_REFERENCE, ConstraintOp.EQ, "alicia", FactLocality.EXACT)),
                    LocationKind.ANY, listOf(PEOPLE_PLUGIN), 100u, cursor))
                assertTrue(page.indexComplete)
                count += page.matches.size; cursor = page.nextCursor
            } while (cursor != null)
            return count
        }
        assertEquals(10_001, count())
        kernel.rebuildIndex(null)
        assertEquals(10_001, count())
        println("10,001-reference rename: prepare=${(prepared-started)/1_000_000}ms apply=${(applied-prepared)/1_000_000}ms sampled heap peak=${peakHeap.get()/1024/1024}MiB (10ms samples, including fixture/index callbacks)")
    }

    @Test fun unrelatedConsumerUsesPreviewApplyAndUndoWithoutEitherDomainPlugin() {
        val root = Files.createTempDirectory("gilune-label-refactor").toFile()
        Gilune.open(root.path).use { kernel ->
            val registry = PluginExtensions(listOf(refactoringFixture()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            File(root, "definition.md").writeText("---\nlabel: red\n---\n")
            File(root, "note.md").writeText("label=red\n")
            val plan = prepareDocumentRefactoring(kernel, root.path, registry, registry.refactorings(kernel.plugins()).single(),
                "definition.md", RefactoringIntent("label", "red", "blue"))
            assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, plan.id).state)
            kernel.removePlugin(REFACTOR_FIXTURE)
            val undo = kernel.prepareRefactoringUndo(plan.id)
            assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, undo.id).state)
            assertEquals("label=red\n", File(root, "note.md").readText())
        }
    }

    @Test fun dirtyBuffersAndExcludedFilesLeaveTheDefinitionIncomplete() = fixture { root, kernel, registry ->
        File(root, "note.md").writeText("@alice")
        val buffer = object : LiveDocumentBuffer {
            override val documentFile = File(root, "note.md").absolutePath
            override var hasUnsavedText = true
        }
        DocumentEditGate.register(buffer)
        val binding = registry.refactorings(kernel.plugins()).single()
        val intent = RefactoringIntent("handle", "alice", "alicia")
        val blocked = prepareDocumentRefactoring(kernel, root.path, registry, binding, "People/alice.md", intent)
        assertTrue(kernel.refactoringEntries(blocked.id, 0u, 100u).any { it.state == "issue" && it.relPath == "note.md" })
        assertEquals("partial", applyDocumentRefactoring(kernel, root.path, registry, blocked.id).state)
        buffer.hasUnsavedText = false
        val plan = prepareDocumentRefactoring(kernel, root.path, registry, binding, "People/alice.md", intent)
        val entry = kernel.refactoringEntries(plan.id, 0u, 100u).single { it.relPath == "note.md" }
        applyDocumentRefactoring(kernel, root.path, registry, plan.id, excluded = setOf(entry.id))
        assertEquals("@alice", File(root, "note.md").readText())
        assertTrue(File(root, "People/alice.md").readText().contains("handle: alice"))
        buffer.hasUnsavedText = true
        assertFailsWith<IllegalStateException> { applyDocumentRefactoring(kernel, root.path, registry, plan.id) }
        buffer.hasUnsavedText = false
        assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, plan.id).state)
    }

    @Test fun unicodeCrLfAndDestinationChangesStayBoundToTheReview() = fixture { root, kernel, registry ->
        val source = "\uFEFF---\r\nunknown: café\r\n---\r\nHello 🌍 @alice. `@alice`\r\n"
        File(root, "unicode.md").writeText(source)
        val binding = registry.refactorings(kernel.plugins()).single()
        val intent = RefactoringIntent("handle", "alice", "álïce")
        val plan = prepareDocumentRefactoring(kernel, root.path, registry, binding, "People/alice.md", intent)
        File(root, "collision.md").writeText("---\ngilune-type: $PERSON_TYPE\ngilune-id: collision\nname: Someone else\nhandle: álïce\n---\n")
        kernel.reconcilePath("collision.md")
        assertFailsWith<IllegalStateException> { applyDocumentRefactoring(kernel, root.path, registry, plan.id) }
        assertEquals(source, File(root, "unicode.md").readText())
        File(root, "collision.md").writeText("ordinary note")
        kernel.reconcilePath("collision.md")
        assertEquals("completed", applyDocumentRefactoring(kernel, root.path, registry, plan.id).state)
        assertEquals(source.replace("🌍 @alice", "🌍 @álïce"), File(root, "unicode.md").readText())
    }
}
