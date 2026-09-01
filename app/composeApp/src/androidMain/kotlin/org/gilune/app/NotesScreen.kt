// The Notes destination and the Read view behind it (ADR 0017 § 8).
//
// There is no editor in MB1, so Read is the whole of a note's surface —
// and because a rendered view has no caret, **ticking a box ships before
// the editor does**. The tap goes through the shared `activateLine`, so
// the tasks plugin's `done:` stamp applies exactly as it does on the
// desktop, and through a guarded write, so a file that moved under the
// reader is never overwritten.

package org.gilune.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun NotesScreen(
    state: GiluneState,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = MaterialTheme.colorScheme
    val notes = remember(state.vaultTick.value) { listNotes(state.vaultDir) }
    if (notes.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No notes yet. Anything you capture lands here.",
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("notes")) {
        items(notes, key = { it.relPath }) { note ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenNote(note.relPath) }
                    .heightIn(min = TouchTarget)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(note.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (note.folder.isNotEmpty()) {
                    Text(
                        note.folder,
                        style = MaterialTheme.typography.bodySmall,
                        color = colours.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            HorizontalDivider(color = colours.outlineVariant)
        }
    }
}

/// Reading one note. The title is the file name; the body is the shared
/// renderer over the shared block model, at mobile type — which is what
/// gets `filter` blocks and every other registered renderer for free.
@Composable
fun ReadScreen(
    state: GiluneState,
    relPath: String,
    modifier: Modifier = Modifier,
) {
    val colours = MaterialTheme.colorScheme
    val file = remember(relPath) { File(state.vaultDir, relPath) }

    // Re-read whenever the vault says it may have changed — a projection
    // rewrite, a sync, or this view's own toggle.
    var content by remember(relPath) { mutableStateOf("") }
    var hashAtRead by remember(relPath) { mutableStateOf("") }
    var reloadTick by remember(relPath) { mutableStateOf(0) }
    LaunchedEffect(relPath, state.vaultTick.value, reloadTick) {
        val text = runCatching { file.readText() }.getOrDefault("")
        content = text
        hashAtRead = contentHash(text)
    }

    val body = remember(content) { splitNoteRaw(content).second }
    val blocks = remember(body) { parseMarkdown(body) }

    fun toggle(line: Int) {
        val onDisk = runCatching { file.readText() }.getOrNull()
        val updated = toggleTaskAtLine(
            current = onDisk ?: return,
            hashAtRead = hashAtRead,
            hashOnDisk = onDisk.let(::contentHash),
            bodyLine = line,
        )
        if (updated == null) {
            // Either the file moved under the reader or nothing claimed
            // the line. Both answers are "leave the document alone" —
            // reload so what is on screen is what is on disk.
            reloadTick++
            state.message.value = "the note changed on disk; reloaded"
            return
        }
        runCatching { file.writeText(updated) }
            .onSuccess {
                content = updated
                hashAtRead = contentHash(updated)
                state.noteSaved()
            }
            .onFailure { state.message.value = "could not save: ${it.message}" }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(noteTitle(relPath), style = MaterialTheme.typography.titleLarge)
        if (content.isEmpty()) {
            Text(
                "This note is empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
            )
        } else {
            MarkdownBody(
                blocks,
                modifier = Modifier.fillMaxWidth().testTag("note-body"),
                onToggleTask = { line -> toggle(line) },
                checkboxTarget = TouchTarget,
            )
        }
    }
}
