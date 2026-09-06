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

/** Shared view over the document service; it owns no domain schema or file writes. */
@Composable
internal fun SourcePropertiesDialog(source: String, onDismiss: () -> Unit,
    onApply: (expectedHeader: String, updatedHeader: String) -> Unit) {
    val original = remember { source }
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<PropertySheet?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    val removed = remember { mutableStateListOf<String>() }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showReadOnly by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val loaded = withContext(Dispatchers.IO) { inspectSourceProperties(original) }
            values.putAll(loaded.fields.associate { it.key to (it.stringValue ?: it.valueYaml) })
            sheet = loaded
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "These properties could not be read." }
    }
    if (sheet == null && error == null) return
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Properties") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sheet == null && error == null) Text("Loading properties…")
                sheet?.fields?.sortedBy { !it.editable }?.filter { it.editable || showReadOnly }?.forEach { field ->
                    if (field.key !in removed) {
                        if (field.editable) {
                            OutlinedTextField(values[field.key].orEmpty(), { values[field.key] = it },
                                label = { Text(field.key) }, enabled = !saving, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("property-${field.key}"))
                            TextButton(onClick = { removed += field.key }, enabled = !saving) { Text("Remove ${field.key}") }
                        } else {
                            Text(field.key, style = MaterialTheme.typography.labelMedium)
                            Text(field.stringValue ?: field.valueYaml, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (sheet?.fields?.any { !it.editable } == true) {
                    TextButton(onClick = { showReadOnly = !showReadOnly }) {
                        Text(if (showReadOnly) "Hide read-only properties" else "Show read-only properties")
                    }
                }
                OutlinedTextField(newKey, { newKey = it }, label = { Text("New property") }, singleLine = true,
                    enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("property-new-key"))
                OutlinedTextField(newValue, { newValue = it }, label = { Text("New value (YAML)") }, singleLine = true,
                    enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("property-new-value"))
                Text("Quote a new value such as \"true\" to keep it as text.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = sheet != null && !saving, onClick = {
                val loaded = sheet ?: return@TextButton
                val changes = loaded.fields.filter { it.editable }.mapNotNull { field ->
                    when {
                        field.key in removed -> PropertyChange(field.key, null)
                        values[field.key] != (field.stringValue ?: field.valueYaml) -> PropertyChange(field.key,
                            values[field.key].orEmpty().let { if (field.stringValue != null) pageJsonString(it) else it })
                        else -> null
                    }
                } + if (newKey.isNotBlank()) listOf(PropertyChange(newKey.trim(), newValue)) else emptyList()
                saving = true; error = null
                scope.launch {
                    try {
                        val updated = withContext(Dispatchers.IO) { changeSourceProperties(original, loaded.contentVersion, changes).text }
                        onApply(splitNoteRaw(original).first, splitNoteRaw(updated).first)
                        onDismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "The properties could not be applied." }
                    finally { saving = false }
                }
            }) { Text(if (saving) "Applying…" else "Apply") }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } })
}
