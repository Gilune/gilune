// The navigation rail: the vault as folders and notes, with the creation
// and deletion a file browser owes you. It works the vault the way any
// third-party tool does — plain file IO, reconciled by the kernel
// afterwards (constitution rule 13 as UI).
//
// Deleting is the one gesture that is not plain file IO all the way
// down: removing a projected note raises a tombstone proposal rather
// than destroying anything, so this panel answers that proposal at the
// point of deletion. Nothing else in the app could, and leaving it
// unanswered would strand the note between deleted and kept.

package org.gilune.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

data class VaultEntry(val relPath: String, val isDir: Boolean, val depth: Int)

/// Flat, ordered listing of the vault tree with collapsed folders left
/// unopened. `.gilune` and dot-files are excluded. Pure over the
/// filesystem so it is trivially testable.
fun listVault(vaultDir: String, expanded: Set<String> = emptySet()): List<VaultEntry> {
    val root = File(vaultDir)
    val out = mutableListOf<VaultEntry>()
    fun walk(dir: File, depth: Int) {
        val children = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
        ) ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            val rel = child.relativeTo(root).invariantSeparatorsPath
            out.add(VaultEntry(rel, child.isDirectory, depth))
            if (child.isDirectory && rel in expanded) walk(child, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

class TreeState {
    val expanded = mutableStateListOf<String>()
    var selectedDir by mutableStateOf("")
    var selectedFile by mutableStateOf<String?>(null)

    fun toggle(relPath: String) {
        if (!expanded.remove(relPath)) expanded.add(relPath)
    }
}

private sealed interface TreeDialog {
    data class Create(val parent: String, val folder: Boolean, val draft: String) : TreeDialog
    data class Rename(val relPath: String, val draft: String) : TreeDialog
    data class Delete(val relPath: String) : TreeDialog
}

@Composable
fun VaultTree(
    state: GiluneState,
    tree: TreeState,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<TreeDialog?>(null) }
    val entries = remember(state.vaultTick.value, state.vaultDir, tree.expanded.toList()) {
        listVault(state.vaultDir, tree.expanded.toSet())
    }
    val colours = MaterialTheme.colorScheme

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "vault",
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { dialog = TreeDialog.Create(tree.selectedDir, folder = true, draft = "") },
            ) { Text("+ folder", fontSize = 12.sp) }
            TextButton(
                onClick = { dialog = TreeDialog.Create(tree.selectedDir, folder = false, draft = "") },
            ) { Text("+ note", fontSize = 12.sp) }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(entries, key = { it.relPath }) { entry ->
                TreeRow(entry, tree, onOpen) { dialog = it }
            }
        }
    }

    when (val open = dialog) {
        null -> Unit
        is TreeDialog.Create -> CreateDialog(state, open, onClose = { dialog = null }, onOpen = onOpen)
        is TreeDialog.Rename -> RenameDialog(state, open, onClose = { dialog = null })
        is TreeDialog.Delete -> DeleteDialog(state, open.relPath, onClose = { dialog = null })
    }
}

@Composable
private fun TreeRow(
    entry: VaultEntry,
    tree: TreeState,
    onOpen: (String) -> Unit,
    request: (TreeDialog) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val selected = if (entry.isDir) tree.selectedDir == entry.relPath else tree.selectedFile == entry.relPath
    val label = entry.relPath.substringAfterLast('/')
    val parent = if (entry.isDir) entry.relPath else entry.relPath.substringBeforeLast('/', "")

    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("New note here") { request(TreeDialog.Create(parent, false, "")) },
            ContextMenuItem("New folder here") { request(TreeDialog.Create(parent, true, "")) },
            ContextMenuItem("Rename") { request(TreeDialog.Rename(entry.relPath, label)) },
            ContextMenuItem("Delete") { request(TreeDialog.Delete(entry.relPath)) },
        )
    }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) colours.primaryContainer else colours.surface)
                .clickable {
                    if (entry.isDir) {
                        tree.selectedDir = if (tree.selectedDir == entry.relPath) "" else entry.relPath
                        tree.toggle(entry.relPath)
                    } else if (entry.relPath.endsWith(".md")) {
                        tree.selectedFile = entry.relPath
                        onOpen(entry.relPath)
                    }
                }
                .padding(start = (6 + entry.depth * 14).dp, top = 3.dp, bottom = 3.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (entry.isDir) (if (entry.relPath in tree.expanded) "▾" else "▸") else " ",
                fontSize = 10.sp,
                color = colours.onSurfaceVariant,
            )
            Text(
                label,
                fontSize = 13.sp,
                color = if (selected) colours.onPrimaryContainer else colours.onSurface,
            )
        }
    }
}

@Composable
private fun CreateDialog(
    state: GiluneState,
    request: TreeDialog.Create,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(request.draft) }
    val what = if (request.folder) "folder" else "note"
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New $what") },
        text = {
            Column {
                Text(
                    if (request.parent.isEmpty()) "in the vault root" else "in ${request.parent}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text("$what name") },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val name = draft.trim().trimEnd('/')
                    if (name.isNotEmpty() && !name.contains("..")) {
                        val base =
                            if (request.parent.isEmpty()) File(state.vaultDir)
                            else File(state.vaultDir, request.parent)
                        if (request.folder) {
                            File(base, name).mkdirs()
                        } else {
                            val file = File(base, if (name.endsWith(".md")) name else "$name.md")
                            if (!file.exists()) file.writeText("")
                            onOpen(file.relativeTo(File(state.vaultDir)).invariantSeparatorsPath)
                        }
                        state.sync(announce = false)
                    }
                    onClose()
                },
                enabled = draft.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(state: GiluneState, request: TreeDialog.Rename, onClose: () -> Unit) {
    var draft by remember { mutableStateOf(request.draft) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Rename") },
        text = {
            Column {
                Text(
                    "notes are tracked by identity, so renaming keeps their history",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val name = draft.trim()
                    val source = File(state.vaultDir, request.relPath)
                    if (name.isNotEmpty() && !name.contains("..") && !name.contains('/')) {
                        source.renameTo(File(source.parentFile, name))
                        state.sync(announce = false)
                    }
                    onClose()
                },
                enabled = draft.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(state: GiluneState, relPath: String, onClose: () -> Unit) {
    val isDir = File(state.vaultDir, relPath).isDirectory
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Delete ${relPath.substringAfterLast('/')}?") },
        text = {
            Text(
                if (isDir) "The folder and the notes inside it are removed from the vault."
                else "The note is removed from the vault. Its revisions and its original " +
                    "capture are kept — deleting a projection never destroys history.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(onClick = {
                deleteEntry(state, relPath)
                onClose()
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/// Delete a projected note and answer the tombstone proposal the deletion
/// raises. The object's identity is read before the file goes, because
/// afterwards there is nothing left to read it from.
private fun deleteEntry(state: GiluneState, relPath: String) {
    val gilune = state.gilune.value ?: return
    val file = File(state.vaultDir, relPath)
    val identities = if (file.isDirectory) {
        file.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.mapNotNull(::noteIdentity).toList()
    } else {
        listOfNotNull(noteIdentity(file))
    }
    if (!file.deleteRecursively()) {
        state.message.value = "could not delete $relPath"
        return
    }
    state.pluginAction("deleted $relPath") {
        it.reconcile()
        val open = it.openProposals().filter { proposal -> proposal.objectId in identities }
        for (proposal in open) it.resolveTombstone(proposal.proposalId, true)
        it.project()
    }
}

private fun noteIdentity(file: File): String? =
    runCatching { splitNote(file.readText()) }
        .getOrNull()
        ?.frontMatter
        ?.firstOrNull { it.key == "gilune-id" }
        ?.value
