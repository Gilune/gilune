package org.gilune.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.gilune.plugin.sdk.LookupContext

/** Standard paged lookup presentation shared by both shells. Labels and matching
 * fields come from the contributed provider; opening a result uses its source. */
@Composable
internal fun IndexedLookupList(state: GiluneState, providerId: String, fragment: String,
    onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val kernel = state.gilune.value ?: return
    val plugins = state.plugins.toList()
    val context = LookupContext(0, fragment, fragment.length, providerId, fragment)
    val indexKey = state.vaultCursor.value to state.searchRefresh.value
    val keys = arrayOf(kernel, context, plugins, indexKey)
    var cursor by remember(*keys) { mutableStateOf<String?>(null) }
    var rows by remember(*keys) { mutableStateOf(emptyList<LookupSuggestion>()) }
    var next by remember(*keys) { mutableStateOf<String?>(null) }
    var total by remember(*keys) { mutableStateOf(0uL) }
    val work = rememberIndexedLookup(kernel, BUNDLED_PLUGIN_EXTENSIONS, context, plugins, indexKey, cursor = cursor).value
    LaunchedEffect(work.value) {
        work.value?.let { page ->
            rows = (if (cursor == null) emptyList() else rows) + page.rows
            total = page.total; next = page.nextCursor
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QueryNotice(work.loading, work.error, state::retrySearchIndex)
        if (!work.loading && work.error == null && rows.isEmpty()) Text("No matches.")
        if (rows.isNotEmpty()) {
            Text("${rows.size} of $total", style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.weight(1f).testTag("lookup-results"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.source.relPath }) { suggestion ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(suggestion.source.relPath) }
                        .padding(vertical = 10.dp).testTag("lookup-result-${suggestion.source.relPath}")) {
                        Text(suggestion.row.label, style = MaterialTheme.typography.titleSmall)
                        Text(suggestion.row.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (next != null) item(key = "more") {
                    TextButton(onClick = { cursor = next }, enabled = !work.loading) { Text("Load more") }
                }
            }
        }
    }
}
