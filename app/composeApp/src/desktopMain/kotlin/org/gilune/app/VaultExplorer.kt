// Desktop vault tooling: a file tree over the vault (create folders and
// notes), an editor panel for Markdown, and the settings dialog. The
// panel works the vault like any third-party tool would — plain file IO,
// reconciled in by the kernel afterwards (constitution rule 13 as UI).

package org.gilune.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

data class VaultEntry(val relPath: String, val isDir: Boolean, val depth: Int)

/// Flat, ordered listing of the vault tree, `.gilune` and temp files
/// excluded. Pure over the filesystem so it is trivially testable.
fun listVault(vaultDir: String): List<VaultEntry> {
    val root = File(vaultDir)
    val out = mutableListOf<VaultEntry>()
    fun walk(dir: File, depth: Int) {
        val children = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            val rel = child.relativeTo(root).invariantSeparatorsPath
            out.add(VaultEntry(rel, child.isDirectory, depth))
            if (child.isDirectory) walk(child, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

class EditorState {
    val openRelPath = mutableStateOf<String?>(null)
    val text = mutableStateOf("")
    val dirty = mutableStateOf(false)
}

@Composable
fun VaultExplorer(state: GiluneState, editor: EditorState) {
    var selectedDir by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // name draft, isFolder
    val entries = remember(state.vaultTick.value, state.vaultDir) { listVault(state.vaultDir) }

    fun openNote(rel: String) {
        val file = File(state.vaultDir, rel)
        editor.openRelPath.value = rel
        editor.text.value = if (file.exists()) file.readText() else ""
        editor.dirty.value = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { newName = "" to true }) { Text("New folder") }
            Button(onClick = { newName = "" to false }) { Text("New note") }
            Text(
                if (selectedDir.isEmpty()) "in: vault root" else "in: $selectedDir",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().weight(0.45f)) {
            items(entries, key = { it.relPath }) { entry ->
                val indent = (entry.depth * 16).dp
                val label = entry.relPath.substringAfterLast('/')
                Text(
                    text = if (entry.isDir) "📁 $label" else label,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indent, top = 2.dp, bottom = 2.dp)
                        .clickable {
                            if (entry.isDir) {
                                selectedDir = if (selectedDir == entry.relPath) "" else entry.relPath
                            } else if (entry.relPath.endsWith(".md")) {
                                openNote(entry.relPath)
                            }
                        },
                )
            }
        }
        HorizontalDivider()
        val open = editor.openRelPath.value
        if (open == null) {
            Text("select a note to read or edit", fontSize = 12.sp)
        } else {
            Text(
                open + (if (editor.dirty.value) "  (unsaved)" else ""),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            OutlinedTextField(
                value = editor.text.value,
                onValueChange = {
                    editor.text.value = it
                    editor.dirty.value = true
                },
                modifier = Modifier.fillMaxWidth().weight(0.55f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        File(state.vaultDir, open).writeText(editor.text.value)
                        editor.dirty.value = false
                        state.sync(announce = false)
                        state.message.value = "saved $open"
                    },
                    enabled = editor.dirty.value,
                ) { Text("Save") }
                TextButton(onClick = {
                    editor.openRelPath.value = null
                    editor.dirty.value = false
                }) { Text("Close") }
            }
        }
    }

    newName?.let { (draft, isFolder) ->
        AlertDialog(
            onDismissRequest = { newName = null },
            title = { Text(if (isFolder) "New folder" else "New note (.md)") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { newName = it to isFolder },
                    placeholder = { Text(if (isFolder) "folder name" else "note name") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = draft.trim().trimEnd('/')
                        if (name.isNotEmpty() && !name.contains("..")) {
                            val base = if (selectedDir.isEmpty()) File(state.vaultDir)
                            else File(state.vaultDir, selectedDir)
                            if (isFolder) {
                                File(base, name).mkdirs()
                            } else {
                                val file = File(base, if (name.endsWith(".md")) name else "$name.md")
                                if (!file.exists()) file.writeText("")
                                openNote(file.relativeTo(File(state.vaultDir)).invariantSeparatorsPath)
                            }
                            state.sync(announce = false)
                        }
                        newName = null
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newName = null }) { Text("Cancel") } },
        )
    }
}
