package org.gilune.app

import java.io.File
import kotlin.test.*
import org.gilune.plugins.people.peopleExtensions
import org.gilune.plugins.tasks.tasksExtensions
import org.junit.Assume.assumeTrue
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.ScopedIndexer
import uniffi.gilune_ffi.ScopedFact
import uniffi.gilune_ffi.SourceTextSnapshot

/** Same 10,000 tasks, different document boundaries. Retains corpora for native comparison. */
class SearchShapeBenchmarkTest {
    @Test fun profilePeopleCallbackCpu() {
        val output = System.getenv("GILUNE_UPDATE_JFR")
        assumeTrue("Opt-in callback CPU recording", output != null)
        val root = System.getenv("GILUNE_SHAPE_PROFILE")!!
        val source = uniffi.gilune_ffi.inspectSourceText(File(root, "items-10000/work-0000.md").readText())
        val producer = org.gilune.plugins.people.PeopleIndexer()
        val expected = producer.index("work-0000.md", source)
        jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).use { recording ->
            recording.destination = java.nio.file.Paths.get(output!!)
            recording.start()
            repeat(5) { sample ->
                val start = System.nanoTime()
                val facts = producer.index("work-0000.md", source)
                val ms = (System.nanoTime() - start) / 1_000_000.0
                assertEquals(expected, facts)
                println("PEOPLE_CPU sample=$sample ms=$ms facts=${facts.size}")
            }
            recording.stop()
        }
    }

    @Test fun profileExistingShapeEdits() {
        val destination = System.getenv("GILUNE_SHAPE_PROFILE")
        assumeTrue("Opt-in existing shape profiling", destination != null)
        val sizes = System.getenv("GILUNE_SHAPE_PROFILE_SIZES")?.split(',')?.map { it.toInt() }
            ?: listOf(10, 100, 1_000, 10_000)
        for (perFile in sizes) {
            val vault = File(destination!!, "items-$perFile")
            assertTrue(File(vault, ".gilune/local/index.db").isFile)
            Gilune.open(vault.absolutePath).use { kernel ->
                val implementations = listOf(tasksExtensions(), peopleExtensions())
                PluginExtensions(implementations).registerPlugins(kernel)
                val times = mutableMapOf<String, Long>()
                implementations.forEach { plugin ->
                    val registration = plugin.scopedIndexer!!
                    val producer = registration.create()
                    val owner = plugin.declaration.pluginId
                    kernel.registerScopedIndexer(owner, registration.version, registration.predicates, object : ScopedIndexer {
                        override fun index(relPath: String, source: SourceTextSnapshot): List<ScopedFact> {
                            val start = System.nanoTime()
                            return producer.index(relPath, source).also {
                                times[owner] = times.getOrDefault(owner, 0L) + System.nanoTime() - start
                            }
                        }
                    })
                }
                kernel.reconcile(); kernel.advanceIndex()
                val file = File(vault, "work-0000.md")
                val original = file.readText()
                val changed = original.replaceFirst("due:2026-09-01", "due:2026-10-01")
                assertNotEquals(original, changed)
                try {
                    repeat(3) { sample ->
                        file.writeText(changed)
                        times.clear()
                        println("PROFILE_EDIT_BEGIN items_per_document=$perFile sample=$sample")
                        val start = System.nanoTime()
                        kernel.reconcile() // Includes index maintenance.
                        val reconcileMs = (System.nanoTime() - start) / 1_000_000.0
                        val after = System.nanoTime()
                        kernel.advanceIndex()
                        val settledMs = (System.nanoTime() - after) / 1_000_000.0
                        println("SHAPE_EDIT items_per_document=$perFile sample=$sample reconcile_ms=$reconcileMs settled_advance_ms=$settledMs callbacks_ms=${times.mapValues { it.value / 1_000_000.0 }}")
                        assertEquals(implementations.map { it.declaration.pluginId }.toSet(), times.keys)
                        println("PROFILE_RESTORE_BEGIN items_per_document=$perFile sample=$sample")
                        file.writeText(original); kernel.reconcile(); kernel.advanceIndex()
                    }
                } finally {
                    file.writeText(original); kernel.reconcile(); kernel.advanceIndex()
                }
            }
        }
    }

    @Test fun prepareShapesAndMeasureSingleTaskEdits() {
        val destination = System.getenv("GILUNE_SHAPE_DIR")
        assumeTrue("Opt-in document-shape benchmark", destination != null)
        val root = File(destination!!).apply { mkdirs() }
        val sample = CrossPluginSearchBenchmarkTest()
        for (perFile in listOf(10, 100, 1_000, 10_000)) {
            val vault = File(root, "items-$perFile").apply { mkdirs() }
            assertTrue(vault.listFiles()!!.isEmpty(), "Use a fresh output directory")
            for (file in 0 until 10_000 / perFile) {
                File(vault, "work-${file.toString().padStart(4,'0')}.md").writeText(buildString {
                    appendLine("---\nconnections: {assign: \"@alice\"}\n---")
                    appendLine("Ordinary page prose, separate from task connections.")
                    for (id in file*perFile until (file+1)*perFile) appendLine(sample.task(id))
                })
            }
            Gilune.open(vault.absolutePath).use { kernel ->
                val registry=PluginExtensions(listOf(tasksExtensions(), peopleExtensions()))
                registry.registerPlugins(kernel); registry.registerIndexers(kernel)
                val start=System.nanoTime(); kernel.reconcile(); kernel.advanceIndex()
                val buildMs=(System.nanoTime()-start)/1_000_000.0
                val file=File(vault,"work-0000.md")
                val original=file.readText()
                val changed=original.replaceFirst("due:2026-09-01","due:2026-10-01")
                assertNotEquals(original,changed)
                val edits=List(3) {
                    file.writeText(changed)
                    val began=System.nanoTime();kernel.reconcile();kernel.advanceIndex()
                    val elapsed=(System.nanoTime()-began)/1_000_000.0
                    file.writeText(original);kernel.reconcile();kernel.advanceIndex()
                    elapsed
                }.sorted()
                assertEquals(original,file.readText())
                println("SHAPE items_per_document=$perFile tasks=10000 documents=${10000/perFile} build_ms=$buildMs edit_median_ms=${edits[1]} index_bytes=${File(vault,".gilune/local/index.db").length()}")
            }
        }
    }
}
