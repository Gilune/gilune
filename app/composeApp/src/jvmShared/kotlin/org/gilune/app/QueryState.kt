// Background query execution and cancellation-safe publication for both shells.

package org.gilune.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val total: ULong = 0u,
    val next: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

internal class PagedSearch(val listing: SearchListing, val more: () -> Unit)

@Composable
internal fun rememberPagedSearch(state: GiluneState, query: String, types: List<SearchType>): PagedSearch {
    val open = state.gilune.value
    val day = rememberQueryDay()
    val keys = arrayOf(query, open, state.vaultCursor.value, state.searchRefresh.value,
        state.plugins.toList(), day)
    var listing by remember(*keys) { mutableStateOf(SearchListing(loading = query.isNotBlank() && open != null)) }
    var wanted by remember(*keys) { mutableStateOf(0) }
    LaunchedEffect(*keys, wanted) {
        if (query.isBlank() || open == null) { listing = SearchListing(); return@LaunchedEffect }
        val previous = listing
        listing = previous.copy(loading = true, error = null)
        if (wanted == 0) delay(180)
        val result = executeQuery {
            val compiled = compileSearch(query, types = types, today = day)
            if (compiled == null) SearchListing() else {
                var append = wanted > 0 && previous.next != null
                val page = try { compiled.page(open, if (append) previous.next else null) }
                catch (_: GiluneException.SearchCursorExpired) {
                    append = false
                    compiled.page(open)
                }.requireComplete()
                SearchListing(hits = (if (append) previous.hits else emptyList()) + page.hits.map { it.displayHit() },
                    total = page.totalHits, next = page.nextCursor)
            }
        }
        listing = result.value ?: previous.copy(loading = false, error = result.error)
    }
    return PagedSearch(listing) { if (!listing.loading && listing.next != null) wanted += 1 }
}
