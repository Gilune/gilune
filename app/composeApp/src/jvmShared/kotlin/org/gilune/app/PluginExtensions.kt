package org.gilune.app

import org.gilune.plugin.sdk.BundledPluginExtensions
import org.gilune.plugin.sdk.PageCollectionRegistration
import org.gilune.plugin.sdk.PAGE_COLLECTIONS_SLOT
import org.gilune.plugin.sdk.PAGE_TYPES_SLOT
import org.gilune.plugin.sdk.PAGE_CREATE_CAPABILITY
import org.gilune.plugin.sdk.SearchType
import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.SuggestField
import org.gilune.plugin.sdk.jsonString

import java.time.LocalDate
import java.util.Locale
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.NoteIndexer
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.PredicateKind

/** Matches installed declarations to locally available implementations by
 * owner AND contribution id. Copying another plugin's manifest entry cannot
 * activate its code. Unsupported contributions remain inert.
 *
 * Registration collisions are programmer errors in the bundled catalogue;
 * reject them before execution rather than let registration order choose a
 * meaning. Installable component negotiation remains a separate contract. */
class PluginExtensions(implementations: List<BundledPluginExtensions>) {
    private val implementations = implementations.toList()

    init {
        unique(this.implementations.map { it.declaration.pluginId }, "plugin owner")
        unique(this.implementations.flatMap { it.pageCollections }.map { it.id }, "page collection")
        unique(this.implementations.flatMap { it.searchTypes }.map { it.key.lowercase(Locale.ROOT) } + "note", "search type")
        unique(this.implementations.flatMap { it.suggestionProviders }.map { it.id }, "suggestion provider")
        unique(this.implementations.flatMap { it.indexedLookups }.map { it.id }, "indexed lookup provider")
        unique(this.implementations.flatMap { it.fieldSlots }, "field slot")
        unique(this.implementations.flatMap { it.searchOperators }.map { it.name.lowercase(Locale.ROOT) } + SearchOperators().names + "type", "search operator")
        this.implementations.forEach { implementation ->
            implementation.indexedLookups.forEach {
                require(implementation.declares(Slots.INDEXED_LOOKUP, it.id)) { "Undeclared indexed lookup: ${it.id}" }
            }
            require(listOfNotNull(implementation.indexer, implementation.sourceIndexer, implementation.scopedIndexer).size <= 1) { "Choose one indexer input per namespace" }
            implementation.pageCollections.forEach { collection ->
                require(implementation.declares(PAGE_COLLECTIONS_SLOT, collection.id) &&
                    implementation.declares(PAGE_TYPES_SLOT, collection.pageTypeId)) { "Undeclared page collection" }
                require(collection.pageTypeId.startsWith(implementation.declaration.pluginId + "/")) { "Foreign page type" }
                unique(collection.fields.map { it.key }, "page field")
                collection.lookupProviderId?.let { id ->
                    require(implementation.indexedLookups.any { it.id == id }) { "Unavailable collection lookup provider" }
                }
            }
            unique(implementation.searchTypes.map { it.contributionId }, "search contribution")
            implementation.searchTypes.forEach {
                require(implementation.declares(Slots.SEARCH_TYPES, it.contributionId)) {
                    "Undeclared search contribution: ${it.contributionId}"
                }
            }
            unique(implementation.searchOperators.map { it.contributionId }, "search operator contribution")
            implementation.searchOperators.forEach {
                require(implementation.declares(Slots.SEARCH_OPERATORS, it.contributionId)) {
                    "Undeclared search operator: ${it.contributionId}"
                }
            }
            implementation.suggestionProviders.forEach {
                require(implementation.declares(Slots.SUGGEST_VALUES, it.id)) {
                    "Undeclared suggestion provider: ${it.id}"
                }
            }
        }
    }

    fun registerPlugins(gilune: Gilune) {
        val previous = gilune.plugins().map { it.pluginId }.toSet()
        implementations.forEach {
            val state = gilune.registerPlugin(it.declaration)
            // Bundled local page creation follows the host's installation-consent
            // policy. Never restore a revoked grant or resurrect a removed plugin.
            if (state == "enabled" && it.declaration.pluginId !in previous &&
                PAGE_CREATE_CAPABILITY in it.declaration.capabilities) {
                gilune.grantCapability(it.declaration.pluginId, PAGE_CREATE_CAPABILITY)
            }
        }
    }

    fun pageCollections(plugins: List<PluginInfo>): List<BoundPageCollection> = implementations.flatMap { implementation ->
        val owner = plugins.enabledOwnerOf(implementation) ?: return@flatMap emptyList()
        implementation.pageCollections.filter {
            owner.declares(PAGE_COLLECTIONS_SLOT, it.id) && owner.declares(PAGE_TYPES_SLOT, it.pageTypeId)
        }.map { BoundPageCollection(owner.pluginId, it) }
    }

    /** Startup/enable preparation. Failures stay visible and are retryable from
     * the collection; they must not prevent opening the user's vault. */
    fun initializePages(gilune: Gilune): List<String> = buildList {
        val plugins = gilune.plugins()
        implementations.forEach { implementation ->
            val owner = plugins.enabledOwnerOf(implementation) ?: return@forEach
            if (owner.contributions.none { it.slot == org.gilune.plugin.sdk.PAGE_INITIALIZERS_SLOT } ||
                PAGE_CREATE_CAPABILITY !in owner.granted) return@forEach
            try {
                val results = BoundPageCreation(gilune, owner.pluginId).initialize()
                if (results.any { it.status != "recorded" }) gilune.project()
            } catch (e: Exception) { add("${owner.name}: ${e.message ?: "initial pages could not be prepared"}") }
        }
    }

    /** Index maintenance keeps the existing lifecycle: disabled namespaces
     * stay warm, removed or unavailable owners are never re-registered. */
    fun registerIndexers(gilune: Gilune) {
        val installed = gilune.plugins()
        implementations.forEach { implementation ->
            val owner = installed.ownerOf(implementation) ?: return@forEach
            if (owner.state == "removed") return@forEach
            val indexer = implementation.indexer
            val sourceIndexer = implementation.sourceIndexer
            val scopedIndexer = implementation.scopedIndexer
            runCatching {
                if (scopedIndexer != null) {
                    val local = scopedIndexer.createLocal
                    if (local != null) gilune.registerLocalScopedIndexer(owner.pluginId, scopedIndexer.version, scopedIndexer.predicates, local())
                    else gilune.registerScopedIndexer(owner.pluginId, scopedIndexer.version, scopedIndexer.predicates, scopedIndexer.create())
                }
                else if (sourceIndexer != null) gilune.registerSourceIndexer(owner.pluginId, sourceIndexer.version, sourceIndexer.predicates, sourceIndexer.create())
                else if (indexer != null) gilune.registerIndexer(owner.pluginId, indexer.version, indexer.predicates, indexer.create())
            }.onFailure { GiluneLog.warn("Indexer registration failed for ${owner.pluginId}", it) }
        }
    }

    fun searchTypes(plugins: List<PluginInfo>): List<SearchType> = buildList {
        add(NOTE_SEARCH_TYPE)
        implementations.forEach { implementation ->
            val owner = plugins.enabledOwnerOf(implementation) ?: return@forEach
            implementation.searchTypes.forEach { registration ->
                if (owner.declares(Slots.SEARCH_TYPES, registration.contributionId)) {
                    val type = registration.create(owner.settings)
                    require(type.key == registration.key) { "Search factory changed its registered key" }
                    add(type)
                }
            }
        }
    }

    internal fun indexedLookup(plugins: List<PluginInfo>, id: String): BoundIndexedLookup? =
        implementations.firstNotNullOfOrNull { implementation ->
            val owner = plugins.enabledOwnerOf(implementation) ?: return@firstNotNullOfOrNull null
            if (!owner.declares(Slots.INDEXED_LOOKUP, id)) return@firstNotNullOfOrNull null
            val registration = implementation.indexedLookups.singleOrNull { it.id == id }
                ?: return@firstNotNullOfOrNull null
            BoundIndexedLookup(owner, registration)
        }

    /** Cross-domain filters are independent of all registered target types. */
    fun searchOperators(plugins: List<PluginInfo>, today: LocalDate): SearchOperators = SearchOperators(
        *implementations.flatMap { implementation ->
            val owner = plugins.enabledOwnerOf(implementation) ?: return@flatMap emptyList()
            implementation.searchOperators.mapNotNull { registration ->
                if (!owner.declares(Slots.SEARCH_OPERATORS, registration.contributionId)) return@mapNotNull null
                registration.create(owner.settings, today).also {
                    require(it.name.equals(registration.name, ignoreCase = true)) { "Search operator factory changed its registered name" }
                }
            }
        }.toTypedArray(),
    )

    /** The slot publisher, field contributor and values provider may be three
     * different plugins. All must be available and enabled. */
    fun fields(slot: String, plugins: List<PluginInfo>): List<SuggestField> {
        val publisher = implementations.singleOrNull { slot in it.fieldSlots } ?: return emptyList()
        if (plugins.enabledOwnerOf(publisher) == null) return emptyList()
        val providers = buildMap {
            implementations.forEach { implementation ->
                val owner = plugins.enabledOwnerOf(implementation) ?: return@forEach
                implementation.suggestionProviders.forEach { provider ->
                    if (owner.declares(Slots.SUGGEST_VALUES, provider.id)) put(provider.id, owner to provider)
                }
            }
        }
        val fields = plugins.contributionsTo(slot).mapNotNull { contribution ->
            val key = jsonString(contribution.config, "key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = jsonString(contribution.config, "label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val providerId = jsonString(contribution.config, "values") ?: return@mapNotNull null
            val (owner, provider) = providers[providerId] ?: return@mapNotNull null
            SuggestField(key, label, provider.create(key, owner.settings))
        }
        // Conflicting field names have no unambiguous insertion meaning.
        val counts = fields.groupingBy { it.key.lowercase(Locale.ROOT) }.eachCount()
        return fields.filter { counts[it.key.lowercase(Locale.ROOT)] == 1 }
    }

    private fun List<PluginInfo>.ownerOf(implementation: BundledPluginExtensions): PluginInfo? =
        singleOrNull { it.pluginId == implementation.declaration.pluginId && it.profile == "bundled" }

    private fun List<PluginInfo>.enabledOwnerOf(implementation: BundledPluginExtensions): PluginInfo? =
        ownerOf(implementation)?.takeIf { it.state == "enabled" }

    private fun BundledPluginExtensions.declares(slot: String, id: String): Boolean =
        declaration.contributions.any { it.slot == slot && it.id == id }

    private fun PluginInfo.declares(slot: String, id: String): Boolean =
        contributions.any { it.slot == slot && it.id == id }

    private fun unique(keys: List<String>, kind: String) {
        require(keys.all { it.isNotBlank() } && keys.distinct().size == keys.size) { "Invalid or duplicate $kind registration" }
    }
}

data class BoundPageCollection(val pluginId: String, val collection: PageCollectionRegistration)
