// The document pane. One buffer of Markdown is the document; Rich is a
// reading of it and Raw is the same bytes unstyled (ADR 0012, stage E1).
// The mode is view state and is never written into the file. The footer
// strip carries the app's status line, mirroring the ratified layout.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
    val colours = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val relPath = editor.openRelPath
            if (relPath == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("select a note, or capture a thought", color = colours.onSurfaceVariant)
                }
            } else {
                OpenNote(state, editor, relPath)
            }
        }
        HorizontalDivider(color = colours.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                state.message.value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colours.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (editor.openRelPath != null) {
                Text(
                    if (editor.dirty) "unsaved" else "saved",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (editor.dirty) colours.primary else colours.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OpenNote(state: GiluneState, editor: EditorState, relPath: String) {
    val colours = MaterialTheme.colorScheme
    val file = File(state.vaultDir, relPath)
    val note = remember(editor.text) { splitNote(editor.text) }
    var menuOpen by remember { mutableStateOf(false) }
    val rawFocus = remember { FocusRequester() }

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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                relPath,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = colours.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (editor.dirty) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colours.primary))
            }
            SegmentedToggle(editor.reading) { editor.reading = it }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "note actions",
                        modifier = Modifier.size(16.dp),
                        tint = colours.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Save now", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = false
                            write()
                            state.sync(announce = false)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Reveal in Explorer", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = false
                            runCatching {
                                ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start()
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Close", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = false
                            if (editor.dirty) write()
                            editor.close()
                            state.sync(announce = false)
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = colours.outlineVariant)

        if (editor.staleBuffer) StaleBufferBanner(state, editor, relPath, ::write)

        if (note.frontMatter.isNotEmpty()) PropertiesStrip(note, editor)

        if (editor.reading) {
            MarkdownBody(
                remember(note.body) { parseMarkdown(note.body) },
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            )
        } else {
            // Styled editing (E2): the full source stays visible and
            // editable; styling rides on top with identity offsets.
            val transformation = remember(colours) {
                MarkdownEditTransformation(
                    MarkdownPalette(
                        muted = colours.outline,
                        marker = colours.primary,
                        heading = colours.onSurface,
                        code = colours.primary,
                        codeBackground = colours.surfaceVariant,
                        link = colours.primary,
                    ),
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { rawFocus.requestFocus() },
            ) {
                BasicTextField(
                    value = editor.text,
                    onValueChange = {
                        editor.text = it
                        editor.dirty = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 200.dp)
                        .padding(14.dp)
                        .focusRequester(rawFocus),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = colours.onSurface,
                    ),
                    cursorBrush = SolidColor(colours.primary),
                    visualTransformation = transformation,
                )
            }
        }
    }
}

@Composable
private fun SegmentedToggle(reading: Boolean, onChange: (Boolean) -> Unit) {
    val colours = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.clip(shape).border(1.dp, colours.outlineVariant, shape).height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment("Edit", !reading) { onChange(false) }
        Box(Modifier.width(1.dp).height(24.dp).background(colours.outlineVariant))
        Segment("Read", reading) { onChange(true) }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Text(
        label,
        fontSize = 11.sp,
        color = if (selected) colours.onSurface else colours.onSurfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (selected) colours.surfaceVariant else colours.surface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
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

    Column(
        Modifier
            .fillMaxWidth()
            .background(colours.surfaceVariant)
            .clickable { editor.showProperties = !editor.showProperties }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                if (editor.showProperties) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "properties",
                modifier = Modifier.size(14.dp),
                tint = colours.onSurfaceVariant,
            )
            Text(
                listOfNotNull(summary, revision?.let { "rev $it" }).joinToString(" · "),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colours.onSurfaceVariant,
            )
            if (conflicted) {
                Text(
                    "conflicted",
                    fontSize = 10.sp,
                    color = colours.onErrorContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colours.errorContainer)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
        }
        if (editor.showProperties) {
            for (entry in note.frontMatter) {
                Text(
                    "${entry.key}: ${entry.value}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colours.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp),
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
        Modifier.fillMaxWidth().background(colours.primaryContainer).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "This note changed on disk while you were editing it.",
            fontSize = 12.sp,
            color = colours.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { editor.open(state.vaultDir, relPath) }) { Text("Use the disk version", fontSize = 12.sp) }
        TextButton(onClick = { write(true) }) { Text("Keep mine", fontSize = 12.sp) }
    }
}
