package org.gilune.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

/** One shared preview/history surface on both platforms. Plugin removal hides
 * new actions, while durable progress and generic guarded undo stay available. */
@Composable
internal fun RefactoringButton(state: GiluneState, path: String, source: String) {
    if (state.gilune.value == null) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Refactor…") }
    if (open) RefactoringDialog(state, path, source) { open = false }
}

@Composable
internal fun RefactoringDialog(state: GiluneState, path: String, source: String, onClose: () -> Unit) {
    val kernel = state.gilune.value ?: return
    val scope = rememberCoroutineScope()
    val plugins = state.plugins.toList()
    var refresh by remember { mutableStateOf(0) }
    val actions = rememberQueryWork(source, plugins) {
        val snapshot = inspectSourceText(source)
        BUNDLED_PLUGIN_EXTENSIONS.refactorings(plugins).flatMap { binding -> binding.registration.actions(snapshot).map { binding to it } }
    }.value
    val history = rememberQueryWork(kernel, refresh) { kernel.refactoringPlans() }.value
    var action by remember { mutableStateOf<Pair<BoundRefactoring, RefactoringAction>?>(null) }
    var destination by remember(action) { mutableStateOf("") }
    var merge by remember(action) { mutableStateOf(false) }
    var folder by remember(action) { mutableStateOf("") }
    var plan by remember { mutableStateOf<RefactoringPlan?>(null) }
    var offset by remember(plan?.id) { mutableStateOf(0uL) }
    var excluded by remember(plan?.id) { mutableStateOf(emptySet<String>()) }
    val entries = rememberQueryWork(kernel, plan?.id, refresh, offset) {
        plan?.let { kernel.refactoringEntries(it.id, offset, 20u) }.orEmpty()
    }.value
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf<RefactoringEntry?>(null) }
    val cancel = remember { AtomicBoolean(false) }
    fun run(work: () -> RefactoringPlan) {
        busy = true; error = null; cancel.set(false)
        scope.launch {
            try { plan = withContext(Dispatchers.IO) { work() }; state.refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Refactoring could not finish. Its journal retains the results." }
            finally { busy = false; progress = ""; refresh++ }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(plan?.label ?: "Rename references") }, text = {
        Column(Modifier.width(420.dp).heightIn(max = 510.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress.ifEmpty { "Working…" }) }
            val selectedPlan = plan
            val selectedAction = action
            when {
                selectedPlan != null -> {
                    Text("${selectedPlan.applied} of ${selectedPlan.total} documents applied · ${selectedPlan.state}")
                    Text("Changes update the visible Markdown. Files with unsaved edits or conflicts need a fresh preview.", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(entries.value.orEmpty(), key = { it.id }) { entry ->
                            Row {
                                if (entry.state == "pending") Checkbox(entry.id !in excluded, enabled = !busy, onCheckedChange = { checked ->
                                    excluded = if (checked) excluded - entry.id else excluded + entry.id
                                })
                                TextButton(onClick = { diff = entry }) { Text("${entry.relPath} · ${entry.state}") }
                            }
                            if (entry.detail.isNotBlank()) Text(entry.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row {
                        if (offset > 0uL) TextButton(onClick = { offset -= 20uL }) { Text("Previous") }
                        if (offset + 20uL < selectedPlan.total) TextButton(onClick = { offset += 20uL }) { Text("Next") }
                    }
                    if (!busy) {
                        if (selectedPlan.reviewToken != null && selectedPlan.applied < selectedPlan.total) TextButton(onClick = {
                            run { applyDocumentRefactoring(kernel, state.vaultDir, BUNDLED_PLUGIN_EXTENSIONS, selectedPlan.id,
                                excluded = excluded, cancelled = cancel::get, progress = { entry -> scope.launch { progress = entry.relPath; refresh++ } }) }
                        }) { Text("Apply reviewed changes / resume") }
                        if (selectedPlan.applied > 0uL) TextButton(onClick = { run { kernel.prepareRefactoringUndo(selectedPlan.id) } }) { Text("Preview undo") }
                        // A text replace has no defining page to look again
                        // from; its own panel prepares a fresh one.
                        if (selectedPlan.provider != "memory:refactoring.undo" &&
                            selectedPlan.provider != org.gilune.plugins.findreplace.FIND_REPLACE_PLUGIN) TextButton(onClick = {
                            run {
                                val config = refactoringConfiguration(selectedPlan.configuration)
                                val binding = BUNDLED_PLUGIN_EXTENSIONS.refactorings(kernel.plugins()).single { it.registration.id == selectedPlan.provider }
                                prepareDocumentRefactoring(kernel, state.vaultDir, BUNDLED_PLUGIN_EXTENSIONS, binding,
                                    config.getValue("definition"), config.intent(), config.getValue("folder"), previous = selectedPlan, cancelled = cancel::get)
                            }
                        }) { Text("Find remaining occurrences / new preview") }
                        TextButton(onClick = { plan = null; action = null }) { Text("Back to history") }
                    }
                    Text("The journal and recovery copies stay on this device until its local data is removed. Managed edits sync as normal revisions; unmanaged files remain plain local files.", style = MaterialTheme.typography.bodySmall)
                }
                selectedAction != null -> {
                    Text("${selectedAction.second.label}: ${selectedAction.second.current}")
                    OutlinedTextField(destination, { destination = it }, enabled = !busy, label = { Text("New name") }, singleLine = true)
                    OutlinedTextField(folder, { folder = it }, enabled = !busy, label = { Text("Folder scope (empty means entire vault)") }, singleLine = true)
                    if (selectedAction.second.allowMerge) Row {
                        Checkbox(merge, { merge = it }, enabled = !busy)
                        Text("Merge into an existing definition")
                    }
                    Text("The defining page is included. Save open edits before preparing; conflicts remain explicit in the preview.")
                    TextButton(enabled = !busy, onClick = {
                        val intent = RefactoringIntent(selectedAction.second.operation, selectedAction.second.current, destination, merge)
                        run { prepareDocumentRefactoring(kernel, state.vaultDir, BUNDLED_PLUGIN_EXTENSIONS, selectedAction.first, path, intent, folder, cancelled = cancel::get) }
                    }) { Text("Prepare preview") }
                    TextButton(enabled = !busy, onClick = { action = null }) { Text("Back") }
                }
                else -> {
                    QueryNotice(actions.loading || history.loading, actions.error ?: history.error, { refresh++ })
                    LazyColumn {
                        items(actions.value.orEmpty()) { item -> TextButton(onClick = { action = item }) { Text(item.second.label) } }
                        item { Text("Previous operations", style = MaterialTheme.typography.titleSmall) }
                        items(history.value.orEmpty(), key = { it.id }) { entry -> TextButton(onClick = { plan = entry }) { Text("${entry.label} · ${entry.state}") } }
                    }
                }
            }
        }
    }, confirmButton = {
        if (busy) TextButton(onClick = { cancel.set(true) }) { Text("Stop after current document") }
        else TextButton(onClick = onClose) { Text("Close") }
    })
    diff?.let { entry ->
        val recovery = rememberQueryWork(kernel, entry.id, refresh) { kernel.refactoringRecoveryCopies(entry.id) }.value
        var copy by remember(entry.id) { mutableStateOf<RefactoringRecoveryCopy?>(null) }
        AlertDialog(onDismissRequest = { diff = null }, title = { Text(entry.relPath) }, text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item { Text("Before", style = MaterialTheme.typography.titleSmall) }
                items(entry.beforeText.lines()) { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                item { Text("After", style = MaterialTheme.typography.titleSmall) }
                items(entry.afterText.lines()) { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                items(recovery.value.orEmpty(), key = { it.name }) { saved ->
                    TextButton(onClick = { copy = saved }) { Text("Inspect retained copy: ${saved.name.substringAfter('.')}") }
                }
            }
        }, confirmButton = { TextButton(onClick = { diff = null }) { Text("Close preview") } })
        copy?.let { saved ->
            AlertDialog(onDismissRequest = { copy = null }, title = { Text("Retained document") }, text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(saved.text.lines()) { SelectionContainer { Text(it) } }
                }
            }, confirmButton = { TextButton(enabled = !busy, onClick = {
                copy = null; diff = null
                run { kernel.prepareRefactoringRecovery(entry.id, saved.name) }
            }) { Text("Preview restoration") } }, dismissButton = { TextButton(onClick = { copy = null }) { Text("Close") } })
        }
    }
}
