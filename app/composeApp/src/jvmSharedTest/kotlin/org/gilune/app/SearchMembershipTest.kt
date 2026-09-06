package org.gilune.app

import org.gilune.plugins.fixture.*

import org.gilune.plugin.sdk.BundledPluginExtensions
import org.gilune.plugin.sdk.IndexerRegistration
import org.gilune.plugin.sdk.SearchOperator
import org.gilune.plugin.sdk.SearchOperatorRegistration
import org.gilune.plugin.sdk.SearchSetBinding
import org.gilune.plugin.sdk.SearchType
import org.gilune.plugin.sdk.SearchTypeRegistration
import org.gilune.plugin.sdk.SearchedNote
import org.gilune.plugin.sdk.Slots

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.ConstraintOp
import uniffi.gilune_ffi.Fact
import uniffi.gilune_ffi.FactLocality
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.GiluneException
import uniffi.gilune_ffi.NoteIndexer
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration

class SearchMembershipTest {
    private class FixtureVault {
        val root = java.nio.file.Files.createTempDirectory("gilune-membership").toFile()
        val dir = root.absolutePath
        val gilune = Gilune.open(dir)
        fun write(path: String, text: String) {
            File(root, path).writeText(text)
            gilune.reconcilePath(path)
            gilune.advanceIndex()
        }
    }

    private val today = LocalDate.of(2026, 9, 5)
    @Test
    fun independentContributionsComposeIntoOneKernelRequest() {
        val registry = PluginExtensions(listOf(fixtureRows(), fixtureLabels()))
        val vault = FixtureVault()
        registry.registerPlugins(vault.gilune)
        registry.registerIndexers(vault.gilune)
        vault.write("fixture.md", "row|category=primary/red|category=secondary/blue\nrow|category=primary/green\nprose|category=primary/blue\nrow|category=primary/blue|category=primary/red\nrow\nroot=primary/red")
        val plugins = vault.gilune.plugins()
        val operators = registry.searchOperators(plugins, today)
        val types = registry.searchTypes(plugins)
        fun query(expression: String) = compileSearch(expression, operators, types, today, groupByNote = false)!!
        val compiled = query("""type:row labels.connection:primary IN ["red", "BLUE", "red"]""")
        assertEquals(setOf(rowsId, labelsId), compiled.request.requiredNamespaces.toSet())
        assertEquals(null, compiled.extension)
        assertEquals(listOf("primary/blue", "primary/red"),
            (compiled.request.clauses.last().constraints.single() as Constraint.FactIn).values)
        File(vault.dir, "fixture.md").writeText("Unreconciled replacement")
        val page = compiled.page(vault.gilune).requireComplete()
        assertEquals(2uL, page.totalHits)
        assertEquals(listOf(1u, 4u), page.hits.map { it.lines.single().number })
        assertEquals(listOf(4u), query("""type:row labels.connection:primary IN ["blue"]""").page(vault.gilune).hits.map { it.lines.single().number })
        assertEquals(listOf(2u, 5u), query("""type:row labels.connection:primary NOT IN ["red", "blue"]""").page(vault.gilune).hits.map { it.lines.single().number })
        assertEquals(0uL, query("type:row labels.connection:primary IN []").page(vault.gilune).totalHits)
        assertEquals(4uL, query("type:row labels.connection:primary NOT IN []").page(vault.gilune).totalHits)
        assertEquals(1uL, query("""type:note labels.connection:primary IN ["red"]""").page(vault.gilune).totalHits)
        val missing = compiled.request.copy(requiredNamespaces = compiled.request.requiredNamespaces + "org.example.absent")
        assertFalse(vault.gilune.search(missing, null).indexComplete)
    }

    @Test
    fun equivalentSetsKeepContinuationIdentityAcrossCompilation() {
        val vault = FixtureVault()
        val registry = PluginExtensions(listOf(fixtureLabels()))
        registry.registerPlugins(vault.gilune)
        registry.registerIndexers(vault.gilune)
        repeat(3) { vault.write("$it.md", "category=primary/red") }
        val operators = registry.searchOperators(vault.gilune.plugins(), today)
        fun compile(values: String) = compileSearch("type:note labels.connection:primary IN $values", operators, today = today, pageSize = 1u)!!
        val first = compile("""["red", "blue"]""").page(vault.gilune)
        val cursor = assertNotNull(first.nextCursor)
        assertEquals("1.md", compile("""["blue", "RED", "red"]""").page(vault.gilune, cursor).hits.single().relPath)
        assertFailsWith<GiluneException.SearchCursorExpired> { compile("""["red", "green"]""").page(vault.gilune, cursor) }
    }

    @Test
    fun strictListsHandleEscapesUnicodeAndBounds() {
        assertEquals(listOf("quote\" and \\", "😀", "赤", "line\nend"),
            parseSetLiteral("""["quote\" and \\", "\uD83D\uDE00", "赤", "line\nend"]"""))
        assertEquals(emptyList(), parseSetLiteral("[ ]"))
        for (value in listOf("[red]", "[1]", "[null]", "[true]", """[["red"]]""", """["red",]""",
            """["red"]junk""", "[\"red\"", """["\uD83D"]""", """["\uDE00"]""", """["\q"]""")) {
            assertFailsWith<InvalidSearchQuery>(value) { parseSetLiteral(value) }
        }
        assertFailsWith<InvalidSearchQuery> { parseSetLiteral(List(MAX_SET_VALUES + 1) { "\"red\"" }.joinToString(",", "[", "]")) }
        assertFailsWith<InvalidSearchQuery> { parseSetLiteral("[\"" + "é".repeat(MAX_SET_BYTES / 2 + 1) + "\"]") }
        assertEquals(MAX_SET_VALUES, parseSetLiteral(List(MAX_SET_VALUES) { "\"red\"" }.joinToString(",", "[", "]")).size)
    }

    @Test
    fun tokenizerKeepsEscapedQuotesAndBracketsInsideOneLiteral() {
        val compiled = compileSearch("""type:note labels.connection:"primary" IN ["a\" b]", "赤"] content:tail""",
            SearchOperators(fixtureLabelOperator()), today = today)!!
        assertEquals(listOf("primary/a\" b]", "primary/赤"),
            (compiled.request.clauses.first().constraints.single() as Constraint.FactIn).values)
        assertEquals(2, compiled.request.clauses.size)
    }

    @Test
    fun invalidFiltersFailWithoutDroppingTermsAndLegacyProseStaysProse() {
        val operators = SearchOperators(fixtureLabelOperator())
        for (query in listOf("type:note labels.connection:primary IN", "type:note labels.connection:primary IN red",
            """type:note labels.connection:primary IN ["red",]""", """type:note labels.connection:primary IN ["a/b"]""",
            """type:note absent.field IN ["red"]""", "type:note absent.field:red",
            """type:note -labels.connection:primary NOT IN ["red"]""", """type:note IN ["red"]""",
        )) assertFailsWith<InvalidSearchQuery>(query) { compileSearch(query, operators, today = today) }
        assertNotNull(compileSearch("ALICE IN WONDERLAND"))
        assertNotNull(compileSearch("unknown:ordinary"))
        val quoted = compileSearch("\"labels.connection:primary IN [red]\"", operators)!!
        assertTrue(quoted.request.clauses.single().constraints.single() is Constraint.Text)
    }

    @Test
    fun fieldExecutionRequiresItsOwnEnabledOwnerAndExactDeclaration() {
        val labelPlugin = fixtureLabels()
        val registry = PluginExtensions(listOf(labelPlugin))
        val available = installedPlugin(labelsId, contributions = labelPlugin.declaration.contributions)
        assertTrue(field in registry.searchOperators(listOf(available), today).names)
        for (plugins in listOf(emptyList(), listOf(available.copy(state = "disabled")), listOf(available.copy(state = "removed")),
            listOf(available.copy(pluginId = "org.example.copy")), listOf(available.copy(contributions = emptyList())))) {
            val operators = registry.searchOperators(plugins, today)
            assertFalse(field in operators.names)
            assertFailsWith<InvalidSearchQuery> { compileSearch("""type:note $field:primary IN ["red"]""", operators) }
        }
    }

    @Test
    fun duplicateFieldsAndTypeFieldCollisionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { SearchOperators(fixtureLabelOperator(), fixtureLabelOperator("LABELS.CONNECTION")) }
        assertFailsWith<IllegalArgumentException> { SearchOperators(fixtureLabelOperator("file")) }
        val type = SearchType("fixture", operators = { listOf(fixtureLabelOperator()) })
        assertFailsWith<IllegalArgumentException> { compileSearch("type:fixture", SearchOperators(fixtureLabelOperator()), listOf(type)) }
        assertFailsWith<IllegalArgumentException> { PluginExtensions(listOf(fixtureLabels(), fixtureLabels())) }
    }
}
