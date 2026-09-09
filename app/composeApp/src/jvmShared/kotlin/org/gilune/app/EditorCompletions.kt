package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

internal data class BoundEditorCompletion(val owner: PluginInfo, val registration: EditorCompletionRegistration)
internal data class EditorChoice(val binding: BoundEditorCompletion, val proposal: EditorCompletion, val option: EditorOption)
internal data class EditorOffers(val context: EditorContext, val choices: List<EditorChoice>, val hasMore: Boolean)

/** Pure contributors are invoked outside the kernel lock. Every page is bounded;
 * only explicit 'more' requests extend the visible window. */
internal fun loadEditorOffers(kernel: Gilune?, registry: PluginExtensions, context: EditorContext,
    continuationOwner: String? = null, pages: Int = 1, plugins: List<PluginInfo> = kernel?.plugins().orEmpty()): EditorOffers {
    val choices = mutableListOf<EditorChoice>()
    var hasMore = false
    val view = try { SourceView(inspectSourceText(context.text)) }
        catch (_: GiluneException.DocumentInvalid) { return EditorOffers(context, emptyList(), false) }
    for (binding in registry.editorCompletions(plugins)) {
        if (continuationOwner != null && binding.registration.id != continuationOwner) continue
        val fields = binding.registration.fieldSlot?.let { registry.fields(it, plugins) }.orEmpty()
        val proposal = binding.registration.complete(context.copy(fields = fields), binding.owner.settings) ?: continue
        require(proposal.start in 0..proposal.end && proposal.end == context.caret)
        if (context.toolbar) {
            // Inspect a virtual token at an empty caret too: blank paragraphs
            // are eligible, fenced code and frontmatter remain excluded.
            val virtual = try { SourceView(inspectSourceText(context.text.substring(0, context.caret) + "x" + context.text.substring(context.caret))) }
                catch (_: GiluneException.DocumentInvalid) { continue }
            val offset = virtual.byteOffset(context.caret)
            if (virtual.snapshot.textRanges.none { offset >= it.start && offset < it.end }) continue
        } else {
            val offset = view.byteOffset(proposal.start)
            if (view.snapshot.textRanges.none { offset >= it.start && offset < it.end }) continue
        }
        val query = proposal.lookup
        if (query != null && kernel != null) {
            var cursor: String? = null
            for (pageNumber in 0 until pages) {
                val page = kernel.lookup(query.copy(pageSize = 30u, cursor = cursor))
                if (!page.indexComplete) throw SearchIndexPending(page.issues)
                page.hits.forEach { choices += EditorChoice(binding, proposal, proposal.present(it)) }
                cursor = page.nextCursor
                if (cursor == null) break
            }
            hasMore = hasMore || cursor != null
        }
        choices += proposal.options.map { EditorChoice(binding, proposal, it) }
    }
    if (kernel != null) check(kernel.plugins() == plugins) { "Plugins changed. Retry the menu." }
    return EditorOffers(context, choices, hasMore)
}

internal class EditorCompletionUi {
    var selected by mutableStateOf(0)
    var offers: EditorOffers? by mutableStateOf(null)
    var offersOwner: Pair<Gilune?, List<PluginInfo>>? = null
    var loading by mutableStateOf(false)
    var error: String? by mutableStateOf(null)
    var creation: Pair<EditorChoice, EditorCreate>? by mutableStateOf(null)
    var creationContext: EditorContext? = null
    var created: (EditorChoice, EditorCreate, String) -> Unit = { _, _, _ -> }
    var choose: (Int) -> Unit = {}
    var dismiss: () -> Unit = {}
    var more: () -> Unit = {}
    var toolbar: () -> Unit = {}
    val visible get() = loading || error != null || offers?.choices?.isNotEmpty() == true
    fun move(delta: Int) {
        val count = offers?.choices?.size ?: 0
        if (count > 0) selected = (selected + delta + count) % count
    }
}

private fun validateEditorOption(kernel: Gilune?, registry: PluginExtensions, plugins: List<PluginInfo>,
    binding: BoundEditorCompletion, option: EditorOption) {
    check(registry.editorCompletions(kernel?.plugins() ?: plugins).contains(binding)) { "This action is no longer available." }
    (listOfNotNull(option.unique) + option.requiredDefinitions).forEach { query ->
        val result = checkNotNull(kernel).lookup(query.copy(pageSize = 1u, cursor = null))
        if (!result.indexComplete) throw SearchIndexPending(result.issues)
        check(result.totalHits == 1uL) { "This identifier is missing or shared by multiple pages. Resolve the definitions before inserting." }
    }
}

@Composable
internal fun rememberEditorCompletions(state: GiluneState, text: String, caret: Int, active: Boolean,
    documentKey: String = "",
    insert: (Int, Int, String) -> Unit): EditorCompletionUi {
    val ui = remember(documentKey) { EditorCompletionUi() }
    val scope = rememberCoroutineScope()
    val kernel = state.gilune.value
    val plugins = state.plugins.toList()
    val day = rememberQueryDay()
    var continuation by remember(documentKey) { mutableStateOf<EditorContinuation?>(null) }
    var owner by remember(documentKey) { mutableStateOf<String?>(null) }
    var toolbar by remember(documentKey) { mutableStateOf(false) }
    var dismissed by remember(documentKey) { mutableStateOf<Pair<String, Int>?>(null) }
    var pages by remember(text, caret, continuation) { mutableStateOf(1) }
    val context = EditorContext(text, caret, day, continuation, toolbar, documentKey = documentKey)
    val latestContext by rememberUpdatedState(context)
    val latestInsert by rememberUpdatedState(insert)
    val bindings = BUNDLED_PLUGIN_EXTENSIONS.editorCompletions(plugins)
    val triggered = continuation != null || toolbar || bindings.any { binding -> binding.registration.triggers.any { editorTrigger(context, it) != null } }
    val enabled = active && dismissed != (text to caret) && triggered
    val work = rememberQueryWork(kernel, context, enabled, plugins, state.searchRefresh.value,
        state.vaultCursor.value, pages, owner) {
        if (!enabled) EditorOffers(context, emptyList(), false)
        else loadEditorOffers(kernel, BUNDLED_PLUGIN_EXTENSIONS, context, owner, pages, kernel?.plugins() ?: plugins)
    }.value
    LaunchedEffect(work, context, enabled, kernel, plugins) {
        // A save can refresh the index while the pointer is moving to an
        // option. Keep that same query's rows until replacement results arrive.
        // A changed document, query, kernel or plugin set must clear them.
        val sameOwner = ui.offersOwner == (kernel to plugins)
        ui.offers = if (!enabled) null else work.value ?: ui.offers?.takeIf {
            work.loading && sameOwner && it.context == context
        }
        ui.offersOwner = kernel to plugins
        ui.error = work.error
        ui.loading = enabled && work.loading
        ui.selected = 0
        traceCompletion("publish loading=${ui.loading} rows=${ui.offers?.choices?.size ?: 0}", context)
    }
    // Leaving the continuation's line or deleting its trigger ends that stage.
    LaunchedEffect(text, caret, active) {
        continuation?.let {
            if (!active || caret < it.fragmentStart || !text.regionMatches(it.start, it.triggerText, 0, it.triggerText.length) ||
                text.substring(it.start.coerceAtMost(text.length), caret.coerceIn(it.start.coerceAtMost(text.length), text.length)).contains('\n')) {
                continuation = null; owner = null
            }
        }
    }
    ui.dismiss = { traceCompletion("dismiss", context); dismissed = text to caret; continuation = null; owner = null; toolbar = false; ui.offers = null; ui.error = null }
    ui.toolbar = { dismissed = null; continuation = null; owner = null; toolbar = true }
    ui.more = { pages++ }
    ui.choose = choose@{ index ->
        traceCompletion("choose index=$index", latestContext)
        val offers = ui.offers ?: return@choose
        val choice = offers.choices.getOrNull(index) ?: return@choose
        if (offers.context != latestContext) return@choose
        // Native validation runs on IO; publish acceptance on the platform UI
        // thread so the editor observes the edit and schedules its autosave.
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val current = withContext(Dispatchers.IO) {
                    validateEditorOption(kernel, BUNDLED_PLUGIN_EXTENSIONS, plugins, choice.binding, choice.option)
                    true
                }
                if (!current || latestContext != offers.context) return@launch
                val option = choice.option
                val next = option.continueWith
                val create = option.create
                val insertion = option.insertion
                when {
                    next != null -> {
                        continuation = EditorContinuation(choice.proposal.start, caret, next, text.substring(choice.proposal.start, caret))
                        owner = choice.binding.registration.id
                    }
                    create != null -> { ui.creationContext = offers.context; ui.creation = choice to create }
                    insertion != null -> {
                        continuation = null; owner = null; toolbar = false
                        latestInsert(choice.proposal.start, choice.proposal.end, insertion)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ui.error = e.message ?: "The action could not complete." }
        }
    }
    ui.created = { choice, creation, path ->
        val input = ui.creationContext
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val option = withContext(Dispatchers.IO) {
                    check(BUNDLED_PLUGIN_EXTENSIONS.editorCompletions(checkNotNull(kernel).plugins()).contains(choice.binding)) { "The provider is no longer available." }
                    val option = creation.complete?.invoke(kernel.documentSnapshot(path).content)
                        ?: error("The new page has no insertion proposal.")
                    validateEditorOption(kernel, BUNDLED_PLUGIN_EXTENSIONS, plugins, choice.binding, option)
                    option
                }
                val token = option.insertion ?: error("The new page has no valid insertion value.")
                check(input?.text == latestContext.text && input.caret == latestContext.caret && input.documentKey == latestContext.documentKey) { "Page created, but the editor changed. Choose it from lookup again." }
                continuation = null; owner = null; toolbar = false; dismissed = null
                latestInsert(choice.proposal.start, choice.proposal.end, token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ui.error = e.message ?: "The page is created; reopen lookup to insert it." }
        }
    }
    return ui
}

// Opt-in CI diagnostics contain identities/counts, never document contents.
internal fun traceCompletion(event: String, context: EditorContext? = null) {
    if (java.lang.Boolean.getBoolean("gilune.uiDiagnostics")) {
        println("GiluneCompletion thread=${Thread.currentThread().id} event=$event context=${context?.hashCode()} caret=${context?.caret}")
    }
}

@Composable
internal fun EditorCompletionMenu(ui: EditorCompletionUi, state: GiluneState, onOpen: (String) -> Unit = {}) {
    if (ui.visible) Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag("editor-completions")) {
        Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState()).padding(6.dp)) {
            // Retained options must stay under the pointer during a refresh.
            // Inserting a loading row above them changes their click positions.
            if (ui.loading && ui.offers?.choices.isNullOrEmpty()) Text("Finding suggestions…")
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ui.offers?.choices?.forEachIndexed { index, choice ->
                Column(Modifier.fillMaxWidth().background(if (index == ui.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .clickable { ui.choose(index) }.padding(8.dp).testTag("completion-$index")) {
                    Text(choice.option.label)
                    if (choice.option.detail.isNotBlank()) Text(choice.option.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row {
                if (ui.offers?.hasMore == true) TextButton(onClick = ui.more) { Text("Load more") }
                TextButton(onClick = ui.dismiss) { Text("Dismiss") }
            }
        }
    }
    ui.creation?.let { (choice, create) ->
        val binding = BUNDLED_PLUGIN_EXTENSIONS.pageCollections(state.plugins.toList()).singleOrNull { it.collection.id == create.collectionId }
        if (binding != null) PageCreationForm(binding, PagePreset(binding.collection.createLabel, create.values), state, {
            ui.creation = null
            if (create.complete != null) ui.created(choice, create, it) else { ui.dismiss(); onOpen(it) }
        }) { ui.creation = null }
    }
}
