package org.gilune.plugin.sdk

import java.time.LocalDate
import uniffi.gilune_ffi.NoteIndexer
import uniffi.gilune_ffi.SourceIndexer
import uniffi.gilune_ffi.ScopedIndexer
import uniffi.gilune_ffi.PluginDeclaration
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
    val sourceIndexer: SourceIndexerRegistration? = null,
    val searchOperators: List<SearchOperatorRegistration> = emptyList(),
    val pageCollections: List<PageCollectionRegistration> = emptyList(),
    val scopedIndexer: ScopedIndexerRegistration? = null,
    val indexedLookups: List<IndexedLookupRegistration> = emptyList(),
)

class SearchTypeRegistration(
    val contributionId: String,
    val key: String,
    val create: (settings: String) -> SearchType,
)

/** An independently owned field factory, pure over settings and query date. */
class SearchOperatorRegistration(
    val contributionId: String,
    val name: String,
    val create: (settings: String, today: LocalDate) -> SearchOperator,
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


/** Full-source input; returned fact lines are still body-relative. */
class SourceIndexerRegistration(
    val version: Long,
    val predicates: List<PredicateKind>,
    val create: () -> SourceIndexer,
)

/** Exact source, generic Markdown locations and parsed frontmatter values. */
class ScopedIndexerRegistration(
    val version: Long,
    val predicates: List<PredicateKind>,
    val create: () -> ScopedIndexer,
)
