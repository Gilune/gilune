// The navigation rail: the vault as folders and notes, with the creation
// and deletion a file browser owes you. It works the vault the way any
// third-party tool does — plain file IO, reconciled by the kernel
// afterwards (constitution rule 13 as UI). New items are named inline in
// the tree, not in a modal (ADR 0011 design session).
//
// Deleting is the one gesture that is not plain file IO all the way
// down: removing a projected note raises a tombstone proposal rather
// than destroying anything, so this panel answers that proposal at the
// point of deletion. Deletions made outside the app surface as a banner
// instead.

package org.gilune.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
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

/// Notes matching a query, best first: name prefix beats name substring
/// beats path substring. Pure, for the jump-to search in the top bar.
fun searchNotes(vaultDir: String, query: String): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val root = File(vaultDir)
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .onEnter { !it.name.startsWith(".") }
        .filter { it.isFile && it.name.endsWith(".md") }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .filter { it.lowercase().contains(q) }
        .sortedWith(
            compareBy(
                { rel ->
                    val name = rel.substringAfterLast('/').lowercase()
                    when {
                        name.startsWith(q) -> 0
                        name.contains(q) -> 1
                        else -> 2
                    }
                },
                { it.length },
            ),
        )
        .toList()
}

class TreeState {
    val expanded = mutableStateListOf<String>()
    var selectedDir by mutableStateOf("")
    var selectedFile by mutableStateOf<String?>(null)

    fun toggle(relPath: String) {
        if (!expanded.remove(relPath)) expanded.add(relPath)
    }
}

private data class PendingCreate(val parent: String, val folder: Boolean)

private sealed interface TreeDialog {
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
    var pending by remember { mutableStateOf<PendingCreate?>(null) }
    val entries = remember(state.vaultTick.value, state.vaultDir, tree.expanded.toList()) {
        listVault(state.vaultDir, tree.expanded.toSet())
    }
    // The Captures folder is the inbox: the count is simply how many
    // notes still sit in it, shown quietly — never a nagging badge.
    val capturesCount = remember(state.vaultTick.value, state.vaultDir) {
        File(state.vaultDir, "Captures").listFiles()?.count { it.name.endsWith(".md") } ?: 0
    }
    val colours = MaterialTheme.colorScheme

    fun startCreate(folder: Boolean, parent: String = tree.selectedDir) {
        if (parent.isNotEmpty() && parent !in tree.expanded) tree.expanded.add(parent)
        pending = PendingCreate(parent, folder)
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (tree.selectedDir.isEmpty()) "vault" else tree.selectedDir,
                fontSize = 11.sp,
                color = colours.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { startCreate(folder = true) }, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Outlined.CreateNewFolder,
                    contentDescription = "new folder",
                    modifier = Modifier.size(16.dp),
                    tint = colours.onSurfaceVariant,
                )
            }
            IconButton(onClick = { startCreate(folder = false) }, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.NoteAdd,
                    contentDescription = "new note",
                    modifier = Modifier.size(16.dp),
                    tint = colours.onSurfaceVariant,
                )
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(bottom = 6.dp)) {
            val atRoot = pending?.takeIf { it.parent.isEmpty() }
            if (atRoot != null) {
                item(key = "pending-root") {
                    InlineNameRow(state, atRoot, depth = 0, onDone = { pending = null }, onOpen = onOpen)
                }
            }
            items(entries, key = { it.relPath }) { entry ->
                TreeRow(entry, tree, state.vaultDir, capturesCount, onOpen, ::startCreate) { dialog = it }
                val inside = pending?.takeIf { entry.isDir && it.parent == entry.relPath }
                if (inside != null) {
                    InlineNameRow(
                        state,
                        inside,
                        depth = entry.depth + 1,
                        onDone = { pending = null },
                        onOpen = onOpen,
                    )
                }
            }
        }
    }

    when (val open = dialog) {
        null -> Unit
        is TreeDialog.Rename -> RenameDialog(state, open, onClose = { dialog = null })
        is TreeDialog.Delete -> DeleteDialog(state, open.relPath, onClose = { dialog = null })
    }
}

@Composable
private fun TreeRow(
    entry: VaultEntry,
    tree: TreeState,
    vaultDir: String,
    capturesCount: Int,
    onOpen: (String) -> Unit,
    startCreate: (Boolean, String) -> Unit,
    request: (TreeDialog) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val selected =
        if (entry.isDir) tree.selectedDir == entry.relPath else tree.selectedFile == entry.relPath
    val label = entry.relPath.substringAfterLast('/')
    val parentDir = if (entry.isDir) entry.relPath else entry.relPath.substringBeforeLast('/', "")

    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("New note here") { startCreate(false, parentDir) },
            ContextMenuItem("New folder here") { startCreate(true, parentDir) },
            ContextMenuItem("Rename") { request(TreeDialog.Rename(entry.relPath, label)) },
            ContextMenuItem("Delete") { request(TreeDialog.Delete(entry.relPath)) },
            ContextMenuItem("Reveal in Explorer") { revealInExplorer(vaultDir, entry) },
        )
    }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) colours.primaryContainer else colours.surface)
                .clickable {
                    if (entry.isDir) {
                        tree.selectedDir = if (tree.selectedDir == entry.relPath) "" else entry.relPath
                        tree.toggle(entry.relPath)
                    } else if (entry.relPath.endsWith(".md")) {
                        onOpen(entry.relPath)
                    }
                }
                .padding(start = (6 + entry.depth * 14).dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (entry.isDir) {
                Icon(
                    if (entry.relPath in tree.expanded) Icons.Default.KeyboardArrowDown
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colours.onSurfaceVariant,
                )
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colours.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.width(14.dp))
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = colours.onSurfaceVariant,
                )
            }
            Text(
                if (entry.isDir) label else label.removeSuffix(".md"),
                fontSize = 13.sp,
                maxLines = 1,
                color = if (selected) colours.onPrimaryContainer else colours.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (entry.isDir && entry.relPath == "Captures" && capturesCount > 0) {
                Spacer(Modifier.weight(1f))
                Text("$capturesCount", fontSize = 11.sp, color = colours.onSurfaceVariant)
            }
        }
    }
}

/// The inline naming row: type, Enter creates, Escape cancels. A new
/// note opens in the editor immediately.
@Composable
private fun InlineNameRow(
    state: GiluneState,
    pending: PendingCreate,
    depth: Int,
    onDone: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    fun commit() {
        val trimmed = name.trim().trimEnd('/')
        if (trimmed.isNotEmpty() && !trimmed.contains("..") && !trimmed.contains('/')) {
            val base =
                if (pending.parent.isEmpty()) File(state.vaultDir) else File(state.vaultDir, pending.parent)
            if (pending.folder) {
                File(base, trimmed).mkdirs()
            } else {
                val file = File(base, if (trimmed.endsWith(".md")) trimmed else "$trimmed.md")
                if (!file.exists()) file.writeText("")
                onOpen(file.relativeTo(File(state.vaultDir)).invariantSeparatorsPath)
            }
            state.sync(announce = false)
        }
        onDone()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (12 + depth * 14).dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .border(1.dp, colours.outline, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            if (pending.folder) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = colours.onSurfaceVariant,
        )
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Enter -> {
                            commit()
                            true
                        }
                        event.key == Key.Escape -> {
                            onDone()
                            true
                        }
                        else -> false
                    }
                },
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = colours.onSurface),
            cursorBrush = SolidColor(colours.primary),
        )
        if (!pending.folder) {
            Text(".md", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colours.outline)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

private fun revealInExplorer(vaultDir: String, entry: VaultEntry) {
    val target = File(vaultDir, entry.relPath)
    runCatching {
        if (entry.isDir) {
            ProcessBuilder("explorer.exe", target.absolutePath).start()
        } else {
            ProcessBuilder("explorer.exe", "/select,${target.absolutePath}").start()
        }
    }
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
    if (state.gilune.value == null) return
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
