package org.gilune.plugins.fixture

import java.time.LocalDate
import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

val rowsId = "org.example.rows"
val labelsId = "org.example.labels"
val field = "labels.connection"

fun declaration(id: String, contributions: List<PluginContribution>) =
    PluginDeclaration(id, id, "domain", "bundled", false, null, null, emptyList(), emptyList(), contributions)

fun fixtureRows() = BundledPluginExtensions(
    declaration = declaration(rowsId, listOf(PluginContribution(Slots.SEARCH_TYPES, "rows", "Rows"))),
    searchTypes = listOf(SearchTypeRegistration("rows", "row") {
        SearchType("row", extract = { error("Kernel owns selection") }, indexed = true,
            constraints = listOf(Constraint.FactAt("$rowsId.kind", ConstraintOp.EQ, "row", FactLocality.EXACT)),
            indexNamespaces = listOf(rowsId))
    }),
    indexer = IndexerRegistration(1, emptyList()) {
        object : NoteIndexer {
            override fun index(relPath: String, text: String) = text.lines().mapIndexedNotNull { index, line ->
                if (line.split('|').first() == "row") Fact((index + 1).toUInt(), "$rowsId.kind", "row") else null
            }
        }
    },
)

fun fixtureLabels() = BundledPluginExtensions(
    declaration = declaration(labelsId, listOf(PluginContribution(Slots.SEARCH_OPERATORS, "connection", "Connections"))),
    searchOperators = listOf(SearchOperatorRegistration("connection", field) { _, _ -> fixtureLabelOperator() }),
    indexer = IndexerRegistration(1, emptyList()) {
        object : NoteIndexer {
            override fun index(relPath: String, text: String): List<Fact> = text.lines().flatMapIndexed { index, line ->
                line.split('|').mapNotNull { token ->
                    when {
                        token.startsWith("category=") -> Fact((index + 1).toUInt(), "$labelsId.pair", token.removePrefix("category="))
                        token.startsWith("root=") -> Fact(null, "$labelsId.pair", token.removePrefix("root="))
                        else -> null
                    }
                }
            }
        }
    },
)

// No reference to the target plugin, its ID, predicates or types.
fun fixtureLabelOperator(name: String = field) = object : SearchOperator {
    override val name = name
    override val indexNamespaces = listOf(labelsId)
    override fun matches(note: SearchedNote, argument: String): Boolean = error("No JVM matching")
    override fun setBinding(argument: String): SearchSetBinding {
        require(argument.isNotBlank()) { "Choose a connection category." }
        return SearchSetBinding("$labelsId.pair", FactLocality.EXACT) { value ->
            require('/' !in value) { "A label cannot contain '/'." }
            "$argument/" + value.lowercase()
        }
    }
}

