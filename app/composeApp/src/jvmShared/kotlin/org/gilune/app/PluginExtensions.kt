package org.gilune.app

import java.time.LocalDate
import java.util.Locale
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.NoteIndexer
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.PredicateKind

/** Executable registrations shipped by a bundled plugin. These callbacks get
 * values, never a host, vault path or database. This is a composition boundary,
 * not a sandbox for untrusted Kotlin code. */
class BundledPluginExtensions(
    val declaration: PluginDeclaration,
    val searchTypes: List<SearchTypeRegistration> = emptyList(),
    val suggestionProviders: List<SuggestionProviderRegistration> = emptyList(),
    val fieldSlots: Set<String> = emptySet(),
    val indexer: IndexerRegistration? = null,
)

class SearchTypeRegistration(
    val contributionId: String,
    val key: String,
    val create: (settings: String) -> SearchType,
)

class SuggestionProviderRegistration(
    val id: String,
    val create: (fieldKey: String, settings: String) -> (String, LocalDate) -> List<SuggestCandidate>,
)

class IndexerRegistration(
    val version: Long,
    val predicates: List<PredicateKind>,
    val create: () -> NoteIndexer,
)

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
        unique(this.implementations.flatMap { it.searchTypes }.map { it.key.lowercase(Locale.ROOT) } + "note", "search type")
        unique(this.implementations.flatMap { it.suggestionProviders }.map { it.id }, "suggestion provider")
        unique(this.implementations.flatMap { it.fieldSlots }, "field slot")
        this.implementations.forEach { implementation ->
            unique(implementation.searchTypes.map { it.contributionId }, "search contribution")
            implementation.searchTypes.forEach {
                require(implementation.declares(Slots.SEARCH_TYPES, it.contributionId)) {
                    "Undeclared search contribution: ${it.contributionId}"
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
        implementations.forEach { gilune.registerPlugin(it.declaration) }
    }

    /** Index maintenance keeps the existing lifecycle: disabled namespaces
     * stay warm, removed or unavailable owners are never re-registered. */
    fun registerIndexers(gilune: Gilune) {
        val installed = gilune.plugins()
        implementations.forEach { implementation ->
            val owner = installed.ownerOf(implementation) ?: return@forEach
            if (owner.state == "removed") return@forEach
            val indexer = implementation.indexer ?: return@forEach
            runCatching {
                gilune.registerIndexer(owner.pluginId, indexer.version, indexer.predicates, indexer.create())
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
