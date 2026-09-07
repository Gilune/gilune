package org.gilune.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

/** Standard plugin detail panel. Only an explicitly selected section or
 * reference executes a query, regardless of the number of links in a page. */
@Composable
internal fun DocumentLinksButton(state: GiluneState, source: String, onOpen: (String) -> Unit) {
    val kernel = state.gilune.value ?: return
    val plugins = state.plugins.toList()
    val providers = BUNDLED_PLUGIN_EXTENSIONS.documentViews(plugins)
    if (providers.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Links") }
    if (!open) return
    val definitions = rememberQueryWork(source, plugins) {
        val snapshot = inspectSourceText(source)
        providers.mapNotNull { it.describe(snapshot) }
    }.value
    var reference by remember(source, plugins) { mutableStateOf<DocumentReference?>(null) }
    var section by remember(source, plugins) { mutableStateOf<DocumentSection?>(null) }
    var create by remember { mutableStateOf<EditorCreate?>(null) }
    var cursor by remember(reference, section, state.searchRefresh.value, state.vaultCursor.value) { mutableStateOf<String?>(null) }
    var paths by remember(reference, section, state.searchRefresh.value, state.vaultCursor.value) { mutableStateOf(emptyList<Pair<String, String>>()) }
    var next by remember(reference, section) { mutableStateOf<String?>(null) }
    var referenceCount by remember(reference) { mutableStateOf<ULong?>(null) }
    val results = rememberQueryWork(kernel, reference, section, cursor, plugins, state.searchRefresh.value, state.vaultCursor.value) {
        val chosenReference = reference
        val chosenSection = section
        when {
            chosenReference != null -> {
                val page = kernel.lookup(chosenReference.query.copy(cursor = cursor, pageSize = 30u))
                if (!page.indexComplete) throw SearchIndexPending(page.issues)
                Triple(page.hits.map { it.relPath to it.relPath }, page.nextCursor, page.totalHits)
            }
            chosenSection != null -> {
                val page = kernel.queryLocations(chosenSection.query.copy(cursor = cursor, pageSize = 30u))
                if (!page.indexComplete) throw SearchIndexPending(page.issues)
                Triple(page.matches.map { hit ->
                    val detail = when (val location = hit.location) {
                        is SourceLocation.BodyLine -> "line ${location.number}"
                        is SourceLocation.MarkdownItem -> "list item"
                        SourceLocation.DocumentRoot -> "page properties"
                    }
                    hit.relPath to "${hit.relPath} · $detail"
                }, page.nextCursor, null)
            }
            else -> Triple(emptyList(), null, null)
        }
    }.value
    LaunchedEffect(results.value) {
        results.value?.let { (rows, continuation, count) ->
            paths = (if (cursor == null) emptyList() else paths) + rows
            next = continuation; referenceCount = count
        }
    }
    AlertDialog(onDismissRequest = { open = false }, title = { Text("Links") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            QueryNotice(definitions.loading || results.loading, definitions.error ?: results.error, state::retrySearchIndex)
            if (reference == null && section == null) LazyColumn {
                definitions.value.orEmpty().forEach { view ->
                    item { Text(view.title, style = MaterialTheme.typography.titleMedium) }
                    items(view.sections) { entry -> TextButton(onClick = { section = entry }) { Text(entry.label) } }
                    items(view.references) { entry -> TextButton(onClick = { reference = entry }) { Text(entry.label) } }
                }
                if (definitions.value?.isEmpty() == true) item { Text("No contributed links on this page.") }
            } else {
                TextButton(onClick = { reference = null; section = null }) { Text("Back to links") }
                Text(reference?.label ?: section?.label.orEmpty(), style = MaterialTheme.typography.titleMedium)
                if (referenceCount == 0uL) {
                    Text("Unresolved reference. The text remains searchable.")
                    reference?.create?.let { draft -> TextButton(onClick = { create = draft }) { Text("Create page…") } }
                }
                if ((referenceCount ?: 0uL) > 1uL) Text("Ambiguous reference: several pages use this identifier.", color = MaterialTheme.colorScheme.error)
                if (!results.loading && results.error == null && paths.isEmpty() && reference == null) Text("No matching locations.")
                LazyColumn {
                    items(paths) { (path, label) -> Text(label, Modifier.fillMaxWidth().clickable { open = false; onOpen(path) }.padding(vertical = 12.dp)) }
                    if (next != null) item { TextButton(onClick = { cursor = next }) { Text("Load more") } }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
    create?.let { draft ->
        BUNDLED_PLUGIN_EXTENSIONS.pageCollections(plugins).singleOrNull { it.collection.id == draft.collectionId }?.let { binding ->
            PageCreationForm(binding, PagePreset(binding.collection.createLabel, draft.values), state, {
                create = null; open = false; onOpen(it)
            }) { create = null }
        }
    }
}
