// The document pane. One buffer of Markdown is the document; Rich is a
// reading of it and Raw is the same bytes unstyled (ADR 0012, stage E1).
// The mode is view state and is never written into the file.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

private const val AUTOSAVE_IDLE_MS = 1200L

class EditorState {
    var openRelPath by mutableStateOf<String?>(null)
    var text by mutableStateOf("")
    var dirty by mutableStateOf(false)
    var reading by mutableStateOf(Prefs.readingMode)
    var showProperties by mutableStateOf(false)

    /// What was on disk when this buffer was read or last written.
    var hashAtOpen by mutableStateOf("")

    /// Set when the file changed underneath an edited buffer. Until it is
    /// answered, saving would destroy whatever arrived.
    var staleBuffer by mutableStateOf(false)

    fun open(vaultDir: String, relPath: String) {
        val file = File(vaultDir, relPath)
        val content = if (file.exists()) file.readText() else ""
        openRelPath = relPath
        text = content
        hashAtOpen = contentHash(content)
        dirty = false
        staleBuffer = false
    }

    fun close() {
        openRelPath = null
        text = ""
        dirty = false
        staleBuffer = false
    }
}

@Composable
fun NoteEditor(state: GiluneState, editor: EditorState, modifier: Modifier = Modifier) {
    val relPath = editor.openRelPath
    if (relPath == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "select a note, or capture a thought",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val file = File(state.vaultDir, relPath)
    val note = remember(editor.text) { splitNote(editor.text) }

    fun onDiskHash(): String? = if (file.exists()) contentHash(file.readBytes()) else null

    fun write(force: Boolean = false) {
        if (!force && !mayOverwrite(editor.hashAtOpen, onDiskHash())) {
            editor.staleBuffer = true
            state.message.value = "$relPath changed on disk; choose which version to keep"
            return
        }
        file.writeText(editor.text)
        editor.hashAtOpen = contentHash(editor.text)
        editor.dirty = false
        editor.staleBuffer = false
    }

    // Nothing typed is ever only in memory: the buffer reaches disk after
    // a pause, and reconciliation batches separately so history records
    // edits rather than keystrokes (ADR 0012).
    LaunchedEffect(editor.text, relPath) {
        if (!editor.dirty) return@LaunchedEffect
        delay(AUTOSAVE_IDLE_MS)
        write()
    }

    // A clean buffer follows the file; a dirty one is protected instead.
    LaunchedEffect(state.vaultTick.value, relPath) {
        val current = onDiskHash() ?: return@LaunchedEffect
        if (current == editor.hashAtOpen) return@LaunchedEffect
        if (editor.dirty) editor.staleBuffer = true else editor.open(state.vaultDir, relPath)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                relPath,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (editor.dirty) Text("unsaved", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { editor.reading = true }, enabled = !editor.reading) {
                Text("Rich", fontSize = 12.sp)
            }
            TextButton(onClick = { editor.reading = false }, enabled = editor.reading) {
                Text("Raw", fontSize = 12.sp)
            }
            TextButton(onClick = {
                write()
                state.sync(announce = false)
            }) { Text("Save", fontSize = 12.sp) }
            TextButton(onClick = {
                if (editor.dirty) write()
                editor.close()
                state.sync(announce = false)
            }) { Text("Close", fontSize = 12.sp) }
        }
        HorizontalDivider()

        if (editor.staleBuffer) StaleBufferBanner(state, editor, relPath, ::write)

        if (note.frontMatter.isNotEmpty()) PropertiesStrip(note, editor)

        if (editor.reading) {
            MarkdownBody(
                remember(note.body) { parseMarkdown(note.body) },
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            OutlinedTextField(
                value = editor.text,
                onValueChange = {
                    editor.text = it
                    editor.dirty = true
                },
                modifier = Modifier.fillMaxSize().padding(12.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
        }
    }
}

/// The machine header, shown as properties rather than hidden. It is
/// contract — an identity the vault and every other tool match on — so
/// it stays visible and is written back untouched.
@Composable
private fun PropertiesStrip(note: Note, editor: EditorState) {
    val colours = MaterialTheme.colorScheme
    val summary = note.frontMatter.firstOrNull { it.key == "gilune-type" }?.value?.substringAfterLast('.')
    val revision = note.frontMatter.firstOrNull { it.key == "gilune-revision" }?.value?.take(8)
    val conflicted = note.frontMatter.any { it.key == "gilune-conflicted" && it.value == "true" }

    Column(Modifier.fillMaxWidth().background(colours.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editor.showProperties = !editor.showProperties }) {
                Text(if (editor.showProperties) "▾" else "▸", fontSize = 11.sp)
            }
            Text(
                listOfNotNull(summary, revision?.let { "rev $it" }).joinToString(" · "),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colours.onSurfaceVariant,
            )
            if (conflicted) {
                Text("conflicted", fontSize = 11.sp, color = colours.error)
            }
        }
        if (editor.showProperties) {
            for (entry in note.frontMatter) {
                Text(
                    "${entry.key}: ${entry.value}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colours.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StaleBufferBanner(
    state: GiluneState,
    editor: EditorState,
    relPath: String,
    write: (Boolean) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colours.primaryContainer).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "This note changed on disk while you were editing it.",
            fontSize = 12.sp,
            color = colours.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { editor.open(state.vaultDir, relPath) }) { Text("Use the disk version") }
        TextButton(onClick = { write(true) }) { Text("Keep mine") }
    }
}
