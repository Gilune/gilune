package org.gilune.app

import java.io.File
import java.time.LocalDate
import kotlin.test.*
import org.gilune.plugins.people.peopleExtensions
import org.gilune.plugins.tasks.tasksExtensions
import org.gilune.plugins.tasks.TASK_DUE_PREDICATE
import org.junit.Assume.assumeTrue
import uniffi.gilune_ffi.*

/** Exports actual compiler output for the read-only native comparison. No second DSL parser. */
class DslMatrixBenchmarkTest {
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + buildString {
            for (c in value) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            }
        } + "\""
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> error("Unsupported JSON value: $value")
    }

    private fun constraint(c: Constraint): Map<String, Any?> = when (c) {
        is Constraint.Fact -> mapOf("kind" to "fact", "predicate" to c.predicate, "op" to c.op.name, "value" to c.value)
        is Constraint.FactAt -> mapOf("kind" to "at", "predicate" to c.predicate, "op" to c.op.name, "value" to c.value, "locality" to c.locality.name)
        is Constraint.FactIn -> mapOf("kind" to "in", "predicate" to c.predicate, "values" to c.values, "locality" to c.locality.name)
        is Constraint.Text -> mapOf("kind" to "text", "target" to c.target.name, "value" to c.query)
    }

    @Test fun exportRealDslAndRebuildIdenticalCorpus() {
        val originPath = System.getenv("GILUNE_MATRIX_ORIGIN")
        assumeTrue("Opt-in DSL matrix", originPath != null)
        val output = File(requireNotNull(System.getenv("GILUNE_MATRIX_OUTPUT"))).apply { mkdirs() }
        val origin = File(originPath!!)
        val vault = File(output, "schema6-vault").apply { mkdirs() }
        val sources = origin.listFiles()!!.filter { it.extension == "md" }.sortedBy { it.name }
        assertEquals(500, sources.size)
        for (source in sources) source.copyTo(File(vault, source.name), overwrite = true)
        val today = LocalDate.of(2026, 9, 6)
        val month = "due:after:2026-08-31 due:before:2026-10-01"
        val alice = "type:task is:open people.connection:assign:@alice $month"
        val filters = listOf(
            "all" to "type:task",
            "open" to "type:task is:open",
            "done" to "type:task is:done",
            "not_done" to "type:task -is:done",
            "alice" to "type:task people.connection:assign:@alice",
            "alice_open" to "type:task is:open people.connection:assign:@alice",
            "alice_month" to alice,
            "either_month" to """type:task is:open people.connection:assign IN ["alice", "bob"] $month""",
            "not_bob_month" to "type:task is:open -people.connection:assign:@bob $month",
            "not_either" to """type:task people.connection:assign NOT IN ["alice", "bob"]""",
            "empty_membership" to "type:task people.connection:assign IN []",
            "empty_negative_membership" to "type:task people.connection:assign NOT IN []",
            "duplicate_membership" to """type:task people.connection:assign IN ["alice", "alice", "bob"]""",
            "absent_person" to "type:task people.connection:assign:@absent",
            "unicode_absent" to "type:task people.connection:assign:@élise",
            "owner_alice" to "type:task people.connection:owner:@alice",
            "reviewer_alice" to "type:task people.connection:reviewer:@alice",
            "paired_roles" to "type:task people.connection:assign:@alice people.connection:reviewer:@bob",
            "wrong_role_pair" to "type:task people.connection:owner:@bob people.connection:reviewer:@alice",
            "two_assignees" to "type:task people.connection:assign:@alice people.connection:assign:@bob",
            "missing_due" to "type:task due:none",
            "present_due" to "type:task -due:none",
            "missing_done" to "type:task done:none",
            "today" to "type:task due:today",
            "week" to "type:task due:week",
            "not_week" to "type:task -due:week",
            "overdue" to "type:task due:overdue",
            "boundary_start" to "type:task due:2026-09-01",
            "boundary_end" to "type:task due:2026-09-30",
            "contradictory_dates" to "type:task due:after:2026-10-01 due:before:2026-09-01",
            "contradictory_state" to "type:task is:open is:done",
            "redundant_ranges" to "type:task $month due:after:2026-09-05 due:before:2026-09-26",
            "invalid_date" to "type:task due:soonish",
            "negated_invalid_date" to "type:task -due:soonish",
            "body_broad" to "type:task content:Work",
            "body_short" to "type:task content:or",
            "body_sparse" to "type:task content:\"Work 49999\"",
            "body_absent" to "type:task content:quartz",
            "body_negative" to "type:task -content:alice",
            "bare_ranked" to "type:task alice",
            "path_one" to "type:task path:work-0499",
            "path_and_facts" to "$alice path:work-0499",
            "file_match" to "type:task file:work-0001",
            "root_property" to "type:task [connections]",
            "missing_root_property" to "type:task -[title]",
            "unknown_keyword" to "type:task nonsense:value",
        )
        Gilune.open(vault.absolutePath).use { kernel ->
            val registry = PluginExtensions(listOf(tasksExtensions(), peopleExtensions()))
            registry.registerPlugins(kernel)
            registry.registerIndexers(kernel)
            val start = System.nanoTime()
            kernel.reconcile()
            kernel.advanceIndex()
            println("MATRIX schema6_reconcile_ms=${(System.nanoTime()-start)/1_000_000.0} vault=$vault")
            val operators = registry.searchOperators(kernel.plugins(), today)
            val types = registry.searchTypes(kernel.plugins())
            val cases = filters.map { Triple(it.first, it.second, "") } + listOf(
                Triple("alice_month_due", alice, TASK_DUE_PREDICATE),
                Triple("all_due_nullable", "type:task", TASK_DUE_PREDICATE),
                Triple("open_due_nullable", "type:task is:open", TASK_DUE_PREDICATE),
                Triple("missing_due_sorted", "type:task due:none", TASK_DUE_PREDICATE),
                Triple("sparse_due_sorted", "type:task people.connection:assign:@absent", TASK_DUE_PREDICATE),
                Triple("alice_grouped", alice, ""),
                Triple("alice_grouped_due", alice, TASK_DUE_PREDICATE),
            )
            val encoded = cases.map { (name, dsl, sort) ->
                fun compile() = compileSearch(dsl, operators, types, today, maxLines=1,
                    pageSize=50u, groupByNote=name.contains("grouped"), sortPredicate=sort)!!
                repeat(10) { compile() }
                val timings = List(5) {
                    val began=System.nanoTime(); repeat(30) { compile() }; (System.nanoTime()-began)/30_000_000.0
                }.sorted()
                val search=compile()
                assertTrue(search.itemScoped)
                // Native tests cannot execute a Kotlin residual; keep these visible.
                val residualMs = if (search.extension != null) {
                    val began=System.nanoTime(); val page=search.page(kernel).requireComplete()
                    println("MATRIX residual name=$name count=${page.totalHits}")
                    (System.nanoTime()-began)/1_000_000.0
                } else null
                val q=search.request
                mapOf("name" to name, "dsl" to dsl, "compile_ms" to timings[2], "residual_ms" to residualMs,
                    "grouped" to q.groupByNote, "sort" to q.sortPredicate,
                    "body_needles" to q.bodyNeedles, "name_needles" to q.nameNeedles,
                    "required" to q.requiredNamespaces, "clauses" to q.clauses.map { c ->
                        mapOf("negated" to c.negated, "sensitive" to c.caseSensitive, "never" to c.never,
                            "residual" to c.residual?.toInt(), "constraints" to c.constraints.map(::constraint))
                    })
            }
            File(output, "dsl-cases.json").writeText(json(encoded))
            println("MATRIX exported=${encoded.size}")
        }
        for (source in sources) assertTrue(source.readBytes().contentEquals(File(vault, source.name).readBytes()))
    }
}
