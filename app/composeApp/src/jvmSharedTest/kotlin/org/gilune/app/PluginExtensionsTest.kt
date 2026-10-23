package org.gilune.app

import org.gilune.plugin.sdk.BundledPluginExtensions
import org.gilune.plugin.sdk.IndexerRegistration
import org.gilune.plugin.sdk.SearchType
import org.gilune.plugin.sdk.SearchTypeRegistration
import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.SuggestCandidate
import org.gilune.plugin.sdk.SuggestionProviderRegistration
import org.gilune.plugins.tasks.TASK_FIELDS_SLOT
import org.gilune.plugins.tasks.tasksExtensions
import org.gilune.plugins.tasks.tasksSettingsDocument

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.ConstraintOp
import uniffi.gilune_ffi.Fact
import uniffi.gilune_ffi.NoteIndexer
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.PredicateKind
import uniffi.gilune_ffi.ValueKind

class PluginExtensionsTest {
    private val today = LocalDate.of(2026, 9, 5)
    private val bookmarkId = "org.example.bookmarks"
    private val bookmarkPredicate = "$bookmarkId.label"
    private val namesId = "org.example.names"
    private val namesProviderId = "$namesId.suggestions"

    private fun declaration(id: String, contributions: List<PluginContribution>) = PluginDeclaration(
        pluginId = id, name = id, `class` = "domain", profile = "bundled", required = false,
        processorId = null, subscribes = null, emits = emptyList(), capabilities = emptyList(),
        contributions = contributions,
    )

    private fun installed(implementation: BundledPluginExtensions, state: String = "enabled", settings: String = "") =
        installedPlugin(implementation.declaration.pluginId, state, implementation.declaration.contributions, settings)

    private fun bookmarks(key: String = "bookmark") = BundledPluginExtensions(
        declaration = declaration(bookmarkId, listOf(PluginContribution(Slots.SEARCH_TYPES, key, "Bookmarks"))),
        searchTypes = listOf(SearchTypeRegistration(key, key) { settings ->
            SearchType(
                key = key,
                extract = { error("An exact indexed type must not extract results in the shell") },
                constraints = listOf(Constraint.Fact(bookmarkPredicate, ConstraintOp.EQ, settings.ifBlank { "keep" })),
                indexed = true,
                indexNamespaces = listOf(bookmarkId),
            )
        }),
        indexer = IndexerRegistration(1, listOf(PredicateKind(bookmarkPredicate, ValueKind.TEXT))) {
            object : NoteIndexer {
                override fun index(relPath: String, text: String): List<Fact> = text.lines().mapIndexedNotNull { i, line ->
                    line.takeIf { it.startsWith(">> ") }?.let { Fact((i + 1).toUInt(), bookmarkPredicate, it.removePrefix(">> ")) }
                }
            }
        },
    )

    private fun names() = BundledPluginExtensions(
        declaration = declaration(namesId, listOf(PluginContribution(Slots.SUGGEST_VALUES, namesProviderId, "Names"))),
        suggestionProviders = listOf(SuggestionProviderRegistration(namesProviderId) { key, settings ->
            { fragment, _ ->
                listOf(settings.ifBlank { "alice.smith" })
                    .filter { it.startsWith(fragment) }
                    .map { SuggestCandidate(it, "Person", "$key: @$it") }
            }
        }),
    )

    private fun assignment(state: String = "enabled", slot: String = TASK_FIELDS_SLOT): PluginInfo = installedPlugin(
        "org.example.assignment", state,
        listOf(PluginContribution(slot, "assign", "Assigned to", """{"key":"assign","label":"Assigned to","values":"$namesProviderId"}""")),
    )

    @Test
    fun aSecondDomainRunsThroughRegistrationIndexingAndKernelSearch() {
        val bookmarks = bookmarks()
        val registry = PluginExtensions(listOf(tasksExtensions(), bookmarks))
        val vault = IndexedVault()
        registry.registerPlugins(vault.gilune)
        registry.registerIndexers(vault.gilune)
        vault.write("mixed.md", ">> keep\n>> later\n- [ ] ordinary\n- [ ] selected #task\n")
        val plugins = listOf(installedTasksPlugin(settings = tasksSettingsDocument("#task")), installed(bookmarks, settings = "later"))
        val types = registry.searchTypes(plugins)
        assertEquals(listOf("note", "task", "bookmark"), types.map { it.key })
        // All domain predicates execute on the indexed snapshot, even after the file changes.
        File(vault.dir, "mixed.md").writeText("unreconciled replacement")
        val page = compileSearch("type:bookmark", types = types)!!.page(vault.gilune).requireComplete()
        assertEquals(listOf(">> later"), page.hits.single().lines.map { it.text })
        val tasks = compileSearch("type:task", types = types)!!.page(vault.gilune).requireComplete()
        assertEquals(listOf("- [ ] selected #task"), tasks.hits.single().items.map { it.text })
    }

    @Test
    fun executableSearchNeedsItsOwnEnabledOwnerAndExactDeclaration() {
        val bookmarks = bookmarks()
        val registry = PluginExtensions(listOf(bookmarks))
        val impostor = installedPlugin("org.example.copy", contributions = bookmarks.declaration.contributions)
        val wrongEntry = installedPlugin(bookmarkId, contributions = listOf(PluginContribution(Slots.SEARCH_TYPES, "different", "Different")))
        for (plugins in listOf(emptyList(), listOf(impostor), listOf(wrongEntry), listOf(installed(bookmarks, "disabled")), listOf(installed(bookmarks, "removed")))) {
            assertEquals(listOf("note"), registry.searchTypes(plugins).map { it.key })
        }
        assertEquals(listOf("note", "bookmark"), registry.searchTypes(listOf(installed(bookmarks))).map { it.key })
    }

    @Test
    fun anotherProviderSuppliesRealValuesUsingItsOwnSettings() {
        val names = names()
        val registry = PluginExtensions(listOf(tasksExtensions(), names))
        val plugins = listOf(installedTasksPlugin(), assignment(), installed(names, settings = "bob.jones"))
        val fields = registry.fields(TASK_FIELDS_SLOT, plugins)
        assertEquals(listOf("due", "high", "low", "assign"), fields.map { it.key })
        assertEquals("due:2026-09-06", fields.first().values("tomorrow", today).first().insert)
        assertEquals("assign: @bob.jones", fields.last().values("bob", today).single().insert)
        assertTrue(fields.last().values("alice", today).isEmpty())
    }

    @Test
    fun publisherContributorAndProviderAreIndependentlyGated() {
        val names = names()
        val registry = PluginExtensions(listOf(tasksExtensions(), names))
        for (state in listOf("disabled", "removed")) {
            assertTrue(registry.fields(TASK_FIELDS_SLOT, listOf(installedTasksPlugin(state), assignment(), installed(names))).isEmpty())
            assertEquals(listOf("due", "high", "low"), registry.fields(TASK_FIELDS_SLOT, listOf(installedTasksPlugin(), assignment(state), installed(names))).map { it.key })
            assertEquals(listOf("due", "high", "low"), registry.fields(TASK_FIELDS_SLOT, listOf(installedTasksPlugin(), assignment(), installed(names, state))).map { it.key })
        }
        assertEquals(listOf("due", "high", "low"), registry.fields(TASK_FIELDS_SLOT, listOf(installedTasksPlugin(), assignment())).map { it.key })
        assertEquals(listOf("due", "high", "low", "assign"), registry.fields(TASK_FIELDS_SLOT, listOf(installedTasksPlugin(), assignment(), installed(names))).map { it.key })
    }

    @Test
    fun copiedProviderDeclarationsCannotActivateAnotherOwnersCode() {
        val names = names()
        val registry = PluginExtensions(listOf(tasksExtensions(), names))
        val impostor = installedPlugin("org.example.copy", contributions = names.declaration.contributions)
        val undeclaredOwner = installedPlugin(namesId)
        assertEquals(listOf("due", "high", "low"), registry.fields(TASK_FIELDS_SLOT, listOf(installedTasksPlugin(), assignment(), impostor, undeclaredOwner)).map { it.key })
    }

    @Test
    fun fieldsWorkWithoutTheTasksPluginOrItsSlot() {
        val slot = "example.review.fields"
        val publisher = BundledPluginExtensions(declaration("org.example.review", emptyList()), fieldSlots = setOf(slot))
        val names = names()
        val registry = PluginExtensions(listOf(publisher, names))
        val fields = registry.fields(slot, listOf(installed(publisher), installed(names), assignment(slot = slot)))
        assertEquals("assign: @alice.smith", fields.single().values("alice", today).single().insert)
        assertTrue(registry.fields("unknown.slot", listOf(installed(publisher), installed(names))).isEmpty())
    }

    @Test
    fun duplicatePublicNamesAreRejectedRatherThanDependingOnRegistrationOrder() {
        assertFailsWith<IllegalArgumentException> { PluginExtensions(listOf(tasksExtensions(), bookmarks("task"))) }
        assertFailsWith<IllegalArgumentException> { PluginExtensions(listOf(bookmarks("NOTE"))) }
        assertFailsWith<IllegalArgumentException> { PluginExtensions(listOf(names(), names())) }
        val names = names()
        val duplicateProvider = BundledPluginExtensions(
            declaration("org.example.other", names.declaration.contributions),
            suggestionProviders = names.suggestionProviders,
        )
        assertFailsWith<IllegalArgumentException> { PluginExtensions(listOf(names, duplicateProvider)) }
        val duplicate = BundledPluginExtensions(declaration("org.example.other", emptyList()), fieldSlots = setOf(TASK_FIELDS_SLOT))
        assertFailsWith<IllegalArgumentException> { PluginExtensions(listOf(tasksExtensions(), duplicate)) }
    }

    @Test
    fun conflictingFieldContributionsAreNotSilentlyOverridden() {
        val names = names()
        val registry = PluginExtensions(listOf(tasksExtensions(), names))
        val copy = installedPlugin("org.example.other", contributions = assignment().contributions)
        assertEquals(listOf("due", "high", "low"), registry.fields(TASK_FIELDS_SLOT, listOf(installedTasksPlugin(), installed(names), assignment(), copy)).map { it.key })
    }

    @Test
    fun indexRegistrationPreservesRemovedOwnersAndDoesNotResurrectThem() {
        val bookmarks = bookmarks()
        val registry = PluginExtensions(listOf(bookmarks))
        val vault = IndexedVault()
        registry.registerPlugins(vault.gilune)
        registry.registerIndexers(vault.gilune)
        vault.gilune.removePlugin(bookmarkId)
        registry.registerPlugins(vault.gilune)
        registry.registerIndexers(vault.gilune)
        assertTrue(vault.gilune.plugins().none { it.pluginId == bookmarkId })
        assertEquals(listOf("note"), registry.searchTypes(vault.gilune.plugins()).map { it.key })
    }
}
