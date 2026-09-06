package org.gilune.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import org.gilune.plugin.sdk.IndexedLookupRegistration
import org.gilune.plugin.sdk.LookupContext
import org.gilune.plugin.sdk.LookupRow
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.LookupHit
import uniffi.gilune_ffi.PluginInfo

internal data class BoundIndexedLookup(val owner: PluginInfo, val registration: IndexedLookupRegistration)
internal data class LookupSuggestion(val source: LookupHit, val row: LookupRow)
internal data class LookupSuggestions(
    val context: LookupContext,
    val rows: List<LookupSuggestion>,
    val total: ULong,
    val nextCursor: String?,
    val generation: String,
)

/** Synchronous worker operation. Factory/projection calls occur outside the
 * native store lock; providers cannot re-enter native indexing/search callbacks.
 * One bounded page comes back in kernel order, with no shell filtering or sorting. */
internal fun loadIndexedLookup(
    gilune: Gilune,
    registry: PluginExtensions,
    context: LookupContext,
    pageSize: UInt = 30u,
    cursor: String? = null,
): LookupSuggestions {
    require(context.caret >= 0 && context.fragment.length <= 1024) { "Invalid lookup context" }
    val bound = registry.indexedLookup(gilune.plugins(), context.providerId)
        ?: error("Lookup provider is unavailable")
    fun checkCurrent() {
        check(registry.indexedLookup(gilune.plugins(), context.providerId) == bound) {
            "Lookup provider changed; restart lookup"
        }
    }
    val provider = bound.registration.create(bound.owner.settings)
    val query = provider.query(context.fragment).copy(pageSize = pageSize, cursor = cursor)
    checkCurrent()
    val page = gilune.lookup(query)
    if (!page.indexComplete) throw SearchIndexPending(page.issues)
    checkCurrent()
    val rows = page.hits.map { LookupSuggestion(it, provider.present(it)) }
    checkCurrent()
    return LookupSuggestions(context, rows, page.totalHits, page.nextCursor, page.generation)
}

/** Shared desktop/Android publication gate. New keys immediately clear old rows.
 * Callers supply the current index refresh/generation key and current plugins;
 * no lookup cache survives a source, configuration or lifecycle change. */
@Composable
internal fun rememberIndexedLookup(
    gilune: Gilune,
    registry: PluginExtensions,
    context: LookupContext,
    plugins: List<PluginInfo>,
    indexKey: Any?,
    pageSize: UInt = 30u,
    cursor: String? = null,
): State<QueryWork<LookupSuggestions>> = rememberQueryWork(
    gilune, registry, context, plugins, indexKey, pageSize, cursor,
) { loadIndexedLookup(gilune, registry, context, pageSize, cursor) }
