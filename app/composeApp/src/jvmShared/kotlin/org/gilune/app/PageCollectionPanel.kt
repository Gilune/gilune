package org.gilune.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gilune.plugin.sdk.PagePreset
import uniffi.gilune_ffi.CreatePageRequest
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.ConstraintOp
import uniffi.gilune_ffi.FactLocality
import uniffi.gilune_ffi.SearchClause
import uniffi.gilune_ffi.GiluneException
import uniffi.gilune_ffi.SearchHit
import java.util.UUID

/** Standard contribution renderer. Field names, text production, row presentation
 * and type membership come from the owner; no domain schema is dispatched here. */
@Composable
internal fun PageCollectionPanel(binding: BoundPageCollection, state: GiluneState,
    onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val collection = binding.collection
    val kernel = state.gilune.value
    val plugins = state.plugins.toList()
    val keys = arrayOf(kernel, binding, plugins, state.vaultCursor.value, state.searchRefresh.value)
    var hits by remember(*keys) { mutableStateOf(emptyList<SearchHit>()) }
    var duplicates by remember(*keys) { mutableStateOf(emptyMap<String, ULong>()) }
    var next by remember(*keys) { mutableStateOf<String?>(null) }
    var total by remember(*keys) { mutableStateOf(0uL) }
    var wanted by remember(*keys) { mutableStateOf(0) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var form by remember(binding) { mutableStateOf<PagePreset?>(null) }
    var defaults by remember(binding) { mutableStateOf(false) }
    var lookupText by remember(binding) { mutableStateOf("") }
    val lookupActive = collection.lookupProviderId != null && lookupText.isNotEmpty()
    val setupKeys = arrayOf(kernel, binding, plugins, state.searchRefresh.value)
    var prepared by remember(*setupKeys) { mutableStateOf(false) }
    var setupError by remember(*setupKeys) { mutableStateOf<String?>(null) }
    LaunchedEffect(*setupKeys) {
        if (kernel == null) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                val owner = plugins.singleOrNull { it.pluginId == binding.pluginId }
                if (owner?.contributions?.any { it.slot == org.gilune.plugin.sdk.PAGE_INITIALIZERS_SLOT } == true &&
                    org.gilune.plugin.sdk.PAGE_CREATE_CAPABILITY in owner.granted) {
                    if (BoundPageCreation(kernel, binding.pluginId).initialize().any { it.status != "recorded" }) kernel.project()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { setupError = e.message ?: "Initial pages could not be prepared." }
        prepared = true
    }
    LaunchedEffect(*keys, wanted, prepared, lookupActive) {
        if (kernel == null || !prepared || lookupActive) return@LaunchedEffect
        loading = true
        val result = executeQuery {
            val query = compileSearch("type:${collection.searchType}", types = searchTypesFor(plugins), maxLines = 0)!!
            var append = wanted > 0 && next != null
            val page = try { query.page(kernel, if (append) next else null) }
                catch (_: GiluneException.SearchCursorExpired) { append = false; query.page(kernel) }
            page.requireComplete()
            val counts = collection.identityPredicate?.let { predicate ->
                page.hits.mapNotNull { it.facts.firstOrNull { fact -> fact.key == predicate }?.value }.distinct().associateWith { value ->
                    val count = identityMatches(kernel, query, predicate, value)
                    check(count.generation == page.generation) { "Index changed while checking identifiers. Retry to refresh." }
                    count.totalHits
                }
            }.orEmpty()
            Triple(page, append, counts)
        }
        result.value?.let { (page, append, counts) ->
            duplicates = (if (append) duplicates else emptyMap()) + counts
            hits = (if (append) hits else emptyList()) + page.hits
            next = page.nextCursor
            total = page.totalHits
        }
        error = result.error; loading = false
    }
    Column(modifier.padding(12.dp).testTag("page-collection-${collection.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(collection.title, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { form = PagePreset(collection.createLabel, emptyMap()) }, enabled = kernel != null) { Text(collection.createLabel) }
            TextButton(onClick = { defaults = true }, enabled = kernel != null) { Text("Page defaults") }
            collection.presets.forEach { preset ->
                TextButton(onClick = { form = preset }, enabled = kernel != null) { Text(preset.label) }
            }
            if (collection.lookupProviderId != null) OutlinedTextField(lookupText, { lookupText = it },
                label = { Text("Find in ${collection.title}") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("collection-lookup"))
            if (!lookupActive) QueryNotice(loading, error, state::retrySearchIndex)
            if (setupError != null) QueryNotice(false, setupError, state::retrySearchIndex)
            if (!lookupActive && !loading && error == null && hits.isEmpty()) Text("No pages yet.")
            if (!lookupActive && hits.isNotEmpty()) Text("${hits.size} of $total", style = MaterialTheme.typography.labelSmall)
        }
        if (lookupActive && kernel != null) IndexedLookupList(state, collection.lookupProviderId!!, lookupText, onOpen, Modifier.weight(1f))
        if (!lookupActive && hits.isNotEmpty()) LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits, key = { "page:${it.relPath}" }) { hit ->
                val row = collection.row(hit.facts, hit.name)
                Column(Modifier.fillMaxWidth().clickable { onOpen(hit.relPath) }.padding(vertical = 10.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall)
                    if (row.subtitle.isNotBlank()) Text(row.subtitle, style = MaterialTheme.typography.bodySmall)
                    val identity = hit.facts.firstOrNull { it.key == collection.identityPredicate }?.value
                    val count = duplicates[identity] ?: 0uL
                    if (count > 1uL) Text("This identifier is shared by $count pages. Check their properties.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    row.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (next != null) item(key = "more") { TextButton(onClick = { wanted += 1 }, enabled = !loading) { Text("Load more") } }
        }
    }
    form?.let { preset -> PageCreationForm(binding, preset, state, onOpen) { form = null } }
    if (defaults) PageDefaultsDialog(binding, state, onOpen) { defaults = false }
}

@Composable
internal fun PageCreationForm(binding: BoundPageCollection, preset: PagePreset, state: GiluneState,
    onOpen: (String) -> Unit, initialFolder: String? = null, dismiss: () -> Unit) {
    val collection = binding.collection
    val kernel = state.gilune.value ?: return
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String, String>().apply {
        collection.fields.forEach { put(it.key, preset.values[it.key] ?: it.initial) }
    } }
    var folder by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf<String?>(null) }
    var template by remember { mutableStateOf("") }
    var showTemplate by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Preserve the UUID and exact request if publication/refresh fails after commit.
    var pending by remember { mutableStateOf<CreatePageRequest?>(null) }
    LaunchedEffect(kernel, binding.pluginId, collection.pageTypeId, reload) {
        try {
            val definition = withContext(Dispatchers.IO) { BoundPageCreation(kernel, binding.pluginId).definitions().single { it.typeId == collection.pageTypeId } }
            check(definition.defaultsProblem == null) { definition.defaultsProblem!! }
            withContext(Dispatchers.Main.immediate) {
                folder = initialFolder ?: definition.defaultFolder; template = definition.template; version = definition.version
                if (reload > 0) showTemplate = true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { withContext(Dispatchers.Main.immediate) { error = e.message ?: "Page creation is unavailable." } }
    }
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(preset.label) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                collection.fields.forEach { field ->
                    OutlinedTextField(values[field.key].orEmpty(), { values[field.key] = it },
                        label = { Text(field.label) }, placeholder = { Text(field.hint) }, singleLine = true,
                        enabled = !saving && pending == null, modifier = Modifier.testTag("page-field-${field.key}"))
                }
                OutlinedTextField(folder.orEmpty(), { folder = it }, label = { Text("Folder") },
                    enabled = !saving && pending == null, singleLine = true)
                TextButton(onClick = { showTemplate = !showTemplate }) { Text(if (showTemplate) "Hide template" else "Show template") }
                if (showTemplate) OutlinedTextField(template, {}, readOnly = true, label = { Text("Initial Markdown template") }, maxLines = 8)
                Text("After creation, open the page to edit its notes and properties.", style = MaterialTheme.typography.bodySmall)
                if (version == null) TextButton(onClick = { error = null; reload += 1 }, enabled = !saving) { Text("Reload defaults") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !saving && version != null, onClick = {
            saving = true; error = null
            val currentValues = values.toMap(); val currentFolder = folder.orEmpty(); val currentVersion = version!!
            val currentTemplate = template
            scope.launch {
                try {
                    val request = pending ?: withContext(Dispatchers.IO) {
                        val currentDefinition = BoundPageCreation(kernel, binding.pluginId).definitions().single { it.typeId == collection.pageTypeId }
                        if (currentDefinition.version != currentVersion) throw GiluneException.DocumentChanged()
                        val draft = collection.prepare(currentValues, currentTemplate)
                        val identityPredicate = collection.identityPredicate
                        val identityValue = draft.identityValue
                        if (identityPredicate != null && identityValue != null) {
                            val query = compileSearch("type:${collection.searchType}", types = searchTypesFor(kernel.plugins()), maxLines = 0)!!
                            require(identityMatches(kernel, query, identityPredicate, identityValue).totalHits == 0uL) {
                                "A page already uses that identifier. Open the existing page or choose another."
                            }
                        }
                        CreatePageRequest(collection.pageTypeId, currentVersion, UUID.randomUUID().toString(),
                            if (currentFolder.isBlank()) draft.relativeFile else "$currentFolder/${draft.relativeFile}", draft.source)
                    }
                    pending = request
                    val receipt = withContext(Dispatchers.IO) { BoundPageCreation(kernel, binding.pluginId).create(request) }
                    withContext(Dispatchers.IO) { kernel.project() }
                    val published = withContext(Dispatchers.IO) { kernel.documentSnapshot(receipt.relPath) }
                    check(published.source.objectId == receipt.objectId) { "The page is saved, but its file path is occupied. Resolve the collision before retrying." }
                    state.refresh(); dismiss(); onOpen(receipt.relPath)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is GiluneException.DocumentInvalid || e is GiluneException.DocumentChanged || e is GiluneException.DocumentUnavailable) pending = null
                    if (e is GiluneException.DocumentChanged) version = null
                    error = if (e is GiluneException.DocumentChanged) "Page defaults changed. Reload and review them before creating." else e.message ?: "Page creation could not finish."
                }
                finally { saving = false }
            }
        }) { Text(if (saving) "Creating…" else if (pending != null) "Retry publication" else "Create") } },
        dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("Cancel") } })
}


internal fun identityMatches(kernel: uniffi.gilune_ffi.Gilune, query: KernelSearch, predicate: String, value: String) =
    kernel.search(query.request.copy(clauses = query.request.clauses + SearchClause(
        listOf(Constraint.FactAt(predicate, ConstraintOp.EQ, value, FactLocality.DOCUMENT_ROOT)), false, false, null, false),
        cursor = null, pageSize = 1u, maxLines = 0u), query.extension).requireComplete()
