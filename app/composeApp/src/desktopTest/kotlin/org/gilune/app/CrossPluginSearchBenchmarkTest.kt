package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.*
import org.gilune.plugins.people.*
import org.gilune.plugins.tasks.*
import org.junit.Assume.assumeTrue
import uniffi.gilune_ffi.*

/** Opt-in: GILUNE_CROSS_PLUGIN_BENCHMARK=1, desktopTest against the release kernel.
 * Real Markdown and real plugin indexers; setup is excluded from query timings. */
class CrossPluginSearchBenchmarkTest {
    private val today = LocalDate.of(2026, 9, 6)
    private val month = "due:after:2026-08-31 due:before:2026-10-01"
    private val alice = "type:task is:open people.connection:assign:@alice $month"
    private val either = """type:task is:open people.connection:assign IN ["alice", "bob"] $month"""

    // Seven Alice matches and three Bob matches per 20 tasks. These include both
    // month boundaries, continuation text, and non-assignee People connections.
    internal fun task(id: Int): String = when (id % 20) {
        0 -> "- [ ] Work $id assign: @alice due:2026-09-01"
        1 -> "- [ ] Work $id assign: @alice due:2026-09-30"
        2 -> "- [ ] Work $id\n  assign: @alice reviewer: @bob due:2026-09-15"
        3 -> "- [x] Work $id assign: @alice due:2026-09-15"
        4 -> "- [ ] Work $id assign: @bob due:2026-09-15"
        5 -> "- [ ] Work $id assign: @alice due:2026-08-31"
        6 -> "- [ ] Work $id assign: @alice due:2026-10-01"
        7 -> "- [ ] Work $id assign: @alice"
        8 -> "- [ ] Work $id owner: @alice reviewer: @bob due:2026-09-15"
        9 -> "- [ ] Work $id assign: @bob reviewer: @alice due:2026-09-15"
        10 -> "- [ ] Work $id assign: @alice due:2026-09-10"
        11 -> "- [ ] Work $id assign: @bob due:2026-09-20"
        12 -> "- [x] Work $id assign: @bob due:2026-09-01"
        13 -> "- [ ] Work $id assign: @carol due:2026-09-09"
        14 -> "- [ ] Work $id assign: @alice due:2026-09-09"
        15 -> "- [ ] Work $id assign: @alice due:2026-09-25"
        16 -> "- [ ] Work $id assign: @bob due:2026-08-31"
        17 -> "- [x] Work $id assign: @alice due:2026-10-01"
        18 -> "- [ ] Work $id due:2026-09-02"
        else -> "- [ ] Work $id assign: @alice due:2026-09-06"
    }

    private fun <T> timed(run: () -> T): Pair<Double, T> {
        val start = System.nanoTime()
        val value = run()
        return (System.nanoTime() - start) / 1_000_000.0 to value
    }

    @Test fun measureRealTaskAndPeopleQueries() {
        assumeTrue("Opt-in performance fixture", System.getenv("GILUNE_CROSS_PLUGIN_BENCHMARK") == "1")
        val root = Files.createTempDirectory("gilune-cross-plugin-bench-").toFile()
        println("BENCH fixture=$root")
        Gilune.open(root.absolutePath).use { kernel ->
            val registry = PluginExtensions(listOf(tasksExtensions(), peopleExtensions()))
            registry.registerPlugins(kernel)
            registry.registerIndexers(kernel)
            val operators = registry.searchOperators(kernel.plugins(), today)
            val types = registry.searchTypes(kernel.plugins())
            fun compile(dsl: String, size: UInt = 50u, sort: String = "") =
                compileSearch(dsl, operators, types, today, maxLines = 1,
                    pageSize = size, groupByNote = false, sortPredicate = sort)!!.also {
                    assertTrue(it.itemScoped)
                    assertNull(it.extension, "No residual plugin callback")
                }
            println("BENCH DSL=$alice")
            println("BENCH REQUEST=${compile(alice).request}")
            assertEquals(listOf(PEOPLE_PLUGIN, TASKS_PLUGIN).sorted(), compile(alice).request.requiredNamespaces)
            var written = 0
            for (total in listOf(10_000, 50_000)) {
                val setup = timed {
                    for (file in written / 100 until total / 100) {
                        File(root, "work-${file.toString().padStart(4, '0')}.md").writeText(buildString {
                            appendLine("---\nconnections: {assign: \"@alice\"}\n---")
                            appendLine("Ordinary page prose, separate from task connections.")
                            for (id in file * 100 until (file + 1) * 100) appendLine(task(id))
                        })
                    }
                    kernel.reconcile()
                    kernel.advanceIndex()
                }.first
                written = total
                println("BENCH DATA tasks=$total documents=${total / 100} setup_ms=$setup")

                repeat(200) { compile(alice) }
                val compileMs = (0 until 7).map { timed { repeat(200) { compile(alice) } }.first / 200 }.sorted()[3]
                println("BENCH DSL_COMPILE median_ms=$compileMs")

                // Stepwise filters reveal whether selectivity reduces execution work.
                val cases = listOf(
                    Triple("type", "type:task", total),
                    Triple("open", "type:task is:open", total * 17 / 20),
                    Triple("assigned", "type:task is:open people.connection:assign:@alice", total * 10 / 20),
                    Triple("alice_month", alice, total * 7 / 20),
                    Triple("alice_bob_month", either, total * 10 / 20),
                )
                for ((label, dsl, expected) in cases) {
                    val search = compile(dsl)
                    val cold = timed { search.page(kernel).requireComplete() }
                    assertEquals(expected.toULong(), cold.second.totalHits, label)
                    val samples = (0 until 5).map {
                        timed { search.page(kernel).requireComplete() }.also { (_, page) ->
                            assertEquals(expected.toULong(), page.totalHits)
                            assertEquals(50, page.hits.size)
                        }.first
                    }.sorted()
                    println("BENCH QUERY=$label DSL=$dsl matches=$expected first50_cold_ms=${cold.first} warm_median_ms=${samples[2]} warm_min_ms=${samples.first()} warm_max_ms=${samples.last()}")
                    search.prepare(kernel).use { reader ->
                        val pageTimes = (0 until 5).map {
                            timed { reader.page(null) }.also { (_, slice) ->
                                assertNull(slice.totalHits)
                                assertEquals(cold.second.hits, slice.hits)
                            }.first
                        }.sorted()
                        val generation = reader.page(null).generation
                        val counts = (0 until 5).map { timed { reader.count(generation) }.also { (_, count) ->
                            assertEquals(expected.toULong(), count.totalHits)
                            assertTrue(count.indexComplete)
                        }.first }.sorted()
                        println("BENCH INDEPENDENT query=$label tasks=$total first50_median_ms=${pageTimes[2]} count_median_ms=${counts[2]}")
                    }

                }

                for (sort in listOf("", TASK_DUE_PREDICATE)) {
                    val search = compile(alice, 500u, sort)
                    val first = (0 until 5).map { timed { search.page(kernel).requireComplete() }.first }.sorted()[2]
                    val pageOnly = search.prepare(kernel).use { reader ->
                        (0 until 5).map { timed { reader.page(null) }.first }.sorted()[2]
                    }
                    val enumeration = search.prepare(kernel).use { reader -> timed {
                        var cursor: String? = null
                        val ids = mutableSetOf<Int>()
                        var pages = 0
                        var previousDue = ""
                        do {
                            val page = reader.page(cursor).also { assertTrue(it.indexComplete) }
                            assertNull(page.totalHits)
                            for (hit in page.hits) {
                                val item = hit.items.single()
                                val id = Regex("Work (\\d+)").find(item.text)!!.groupValues[1].toInt()
                                assertTrue(ids.add(id), "Duplicate task $id")
                                assertTrue(id % 20 in setOf(0, 1, 2, 10, 14, 15, 19), "Wrong task $id")
                                if (sort.isNotEmpty()) {
                                    val due = item.facts.single { it.key == TASK_DUE_PREDICATE }.value
                                    assertTrue(due >= previousDue)
                                    previousDue = due
                                }
                            }
                            cursor = page.nextCursor
                            pages++
                        } while (cursor != null)
                        assertEquals(total * 7 / 20, ids.size)
                        pages
                    } }
                    println("BENCH ENUM sort=${sort.ifEmpty { "default" }} first500_exact_median_ms=$first first500_page_only_median_ms=$pageOnly all_uncounted_ms=${enumeration.first} pages=${enumeration.second}")
                }
            }
        }
    }
}
