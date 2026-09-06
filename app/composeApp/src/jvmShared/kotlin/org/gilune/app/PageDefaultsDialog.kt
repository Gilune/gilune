package org.gilune.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gilune.plugin.sdk.pageJsonString
import uniffi.gilune_ffi.*
import java.util.UUID

/** Initial defaults use ordinary typed creation; subsequent edits use the normal
 * Markdown editor, its save guards, reconciliation and revision history. */
@Composable
internal fun PageDefaultsDialog(binding: BoundPageCollection, state: GiluneState,
    onOpen: (String) -> Unit, dismiss: () -> Unit) {
    val kernel = state.gilune.value ?: return
    val scope = rememberCoroutineScope()
    var definition by remember { mutableStateOf<PageDefinition?>(null) }
    var config by remember { mutableStateOf<PageDefinition?>(null) }
    var folder by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<CreatePageRequest?>(null) }
    LaunchedEffect(kernel, binding) {
        try {
            val definitions = withContext(Dispatchers.IO) { BoundPageCreation(kernel, binding.pluginId).definitions() }
            val target = definitions.single { it.typeId == binding.collection.pageTypeId }
            definition = target
            if (target.defaultsPath != null) { dismiss(); onOpen(target.defaultsPath!!); return@LaunchedEffect }
            check(target.defaultsProblem == null) { target.defaultsProblem!! }
            config = definitions.singleOrNull { it.typeId == target.defaultsTypeId }
            check(config != null) { "This page type does not provide editable defaults." }
            folder = target.defaultFolder; template = target.template
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Defaults are unavailable." }
    }
    // Present once loaded, keeping native dialog measurements stable.
    if (config == null && error == null) return
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text("Page defaults") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("These defaults will be saved as a Markdown page. They initialize future pages; existing pages keep their content.")
            OutlinedTextField(folder, { folder = it }, label = { Text("Folder") }, singleLine = true,
                enabled = !saving && pending == null, modifier = Modifier.testTag("defaults-folder"))
            OutlinedTextField(template, { template = it }, label = { Text("Markdown template") }, minLines = 4, maxLines = 10,
                enabled = !saving && pending == null, modifier = Modifier.testTag("defaults-template"))
            Text("After saving, edit the defaults page's folder property and body in the normal editor.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !saving && config != null, onClick = {
            saving = true; error = null
            val currentFolder = folder; val currentTemplate = template; val captured = config!!; val target = definition!!
            scope.launch {
                try {
                    val request = pending ?: withContext(Dispatchers.IO) {
                        val current = BoundPageCreation(kernel, binding.pluginId).definitions().single { it.typeId == target.typeId }
                        if (current.version != target.version) throw GiluneException.DocumentChanged()
                        val filename = "${captured.typeId.replace('/', '.')}.md"
                        CreatePageRequest(captured.typeId, captured.version, UUID.randomUUID().toString(),
                            if (captured.defaultFolder.isEmpty()) filename else "${captured.defaultFolder}/$filename",
                            "---\nfolder: ${pageJsonString(currentFolder)}\n---\n$currentTemplate")
                    }
                    pending = request
                    val receipt = withContext(Dispatchers.IO) {
                        val saved = BoundPageCreation(kernel, binding.pluginId).create(request)
                        kernel.project()
                        check(kernel.documentSnapshot(saved.relPath).source.objectId == saved.objectId) { "Defaults are saved but their file path is occupied. Resolve the collision and retry." }
                        saved
                    }
                    state.refresh(); dismiss(); onOpen(receipt.relPath)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is GiluneException.DocumentInvalid || e is GiluneException.DocumentChanged || e is GiluneException.DocumentUnavailable) pending = null
                    error = if (e is GiluneException.DocumentChanged) "Defaults changed. Close and reopen this form to review them." else e.message ?: "Defaults could not be saved."
                } finally { saving = false }
            }
        }) { Text(if (saving) "Saving…" else if (pending != null) "Retry publication" else "Save defaults") } },
        dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("Cancel") } })
}
