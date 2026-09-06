// Background query execution and cancellation-safe publication for both shells.

package org.gilune.app

import org.gilune.plugin.sdk.SearchType

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import uniffi.gilune_ffi.GiluneException

data class QueryWork<T>(val value: T? = null, val loading: Boolean = true, val error: String? = null)

internal suspend fun <T> executeQuery(load: () -> T): QueryWork<T> = try {
    QueryWork(value = withContext(Dispatchers.IO) { load() }, loading = false)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    GiluneLog.warn("query failed", error)
    QueryWork(loading = false, error = queryFailure(error))
}

private fun queryFailure(error: Exception): String = when (error) {
    is InvalidSearchQuery -> error.message ?: "Invalid search filter."
    is SearchIndexPending -> "Search is waiting for indexing. Retry to update it."
    is GiluneException.SearchCursorExpired -> "Search results changed. Retry to refresh them."
    else -> "Search could not finish. Please retry."
}

@Composable
internal fun <T> rememberQueryWork(vararg keys: Any?, load: () -> T): State<QueryWork<T>> {
    // A new key gets new state immediately: yesterday's rows cannot be shown
    // under today's query while LaunchedEffect is awaiting its first turn.
    return androidx.compose.runtime.key(keys.toList()) {
        produceState(QueryWork<T>()) { value = executeQuery(load) }
    }
}

@Composable
internal fun rememberQueryDay(): LocalDate {
    val day by produceState(LocalDate.now()) {
        while (true) {
            // Calendar time is independent of the composition/frame clock.
            withContext(Dispatchers.Default) { delay(30_000) }
            value = LocalDate.now()
        }
    }
    return day
}

@Composable
internal fun QueryNotice(loading: Boolean, error: String?, retry: () -> Unit) {
    if (loading) Text("Searching…")
    if (error != null) {
        Text(error)
        TextButton(onClick = retry) { Text("Retry search") }
    }
}

internal data class SearchListing(
    val hits: List<SearchHit> = emptyList(),
    val total: ULong? = null,
    val generation: String? = null,
    val countError: String? = null,
    val next: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

internal class PagedSearch(val listing: SearchListing, val more: () -> Unit)

@Composable
internal fun rememberPagedSearch(state: GiluneState, query: String, types: List<SearchType>): PagedSearch {
    val open = state.gilune.value
    val day = rememberQueryDay()
    val plugins = state.plugins.toList()
    val keys = arrayOf(query, open, state.vaultCursor.value, state.searchRefresh.value,
        plugins, day)
    var listing by remember(*keys) { mutableStateOf(SearchListing(loading = query.isNotBlank() && open != null)) }
    var reader by remember(*keys) { mutableStateOf<uniffi.gilune_ffi.SearchQuery?>(null) }
    DisposableEffect(reader) {
        val current = reader
        onDispose { current?.cancel() }
    }
    var wanted by remember(*keys) { mutableStateOf(0) }
    LaunchedEffect(*keys, wanted) {
        if (query.isBlank() || open == null) { listing = SearchListing(); return@LaunchedEffect }
        val previous = listing
        listing = previous.copy(loading = true, error = null)
        if (wanted == 0) delay(180)
        if (reader == null) {
            val prepared = executeQuery {
                compileSearch(query, operators = searchOperatorsFor(plugins, day), types = types, today = day)?.prepare(open)
            }
            reader = prepared.value
            if (prepared.error != null) { listing = previous.copy(loading = false, error = prepared.error); return@LaunchedEffect }
        }
        val current = reader
        val result = executeQuery {
            if (current == null) SearchListing() else {
                var append = wanted > 0 && previous.next != null
                val page = try { current.page(if (append) previous.next else null) }
                catch (_: GiluneException.SearchCursorExpired) {
                    append = false
                    current.page(null)
                }
                if (!page.indexComplete) throw SearchIndexPending(page.issues)
                SearchListing(hits = (if (append) previous.hits else emptyList()) + page.hits.map { it.displayHit() },
                    total = page.totalHits ?: previous.total.takeIf { previous.generation == page.generation },
                    generation = page.generation, next = page.nextCursor)
            }
        }
        listing = result.value?.let { page ->
            if (page.generation == listing.generation && page.total == null) page.copy(total = listing.total, countError = listing.countError)
            else page
        } ?: previous.copy(loading = false, error = result.error)
    }
    LaunchedEffect(reader, listing.generation) {
        val current = reader ?: return@LaunchedEffect
        val generation = listing.generation ?: return@LaunchedEffect
        if (listing.total != null) return@LaunchedEffect
        val counted = executeQuery {
            current.count(generation).also { if (!it.indexComplete) throw SearchIndexPending(it.issues) }
        }
        if (listing.generation == generation) {
            listing = listing.copy(total = counted.value?.totalHits, countError = counted.error)
        }
    }
    return PagedSearch(listing) { if (!listing.loading && listing.next != null) wanted += 1 }
}
