// The document pane. One buffer of Markdown is the document; Rich is a
// reading of it and Raw is the same bytes unstyled (ADR 0012, stage E1).
// The mode is view state and is never written into the file. The footer
// strip carries the app's status line, mirroring the ratified layout.
//
// The buffer holds the body alone: front matter is machine contract, kept
// verbatim beside it and reassembled on every write, so editing prose can
// never disturb a note's identity.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

private const val AUTOSAVE_IDLE_MS = 1200L

class EditorState {
    var openRelPath by mutableStateOf<String?>(null)

    /// The editable buffer: the note's body only, with its selection, so
    /// the caret survives every recomposition.
    var text by mutableStateOf(TextFieldValue(""))

    /// The note's front-matter block exactly as it was read, fences
    /// included. Written back untouched unless the properties editor
    /// rewrites it.
    var frontMatterRaw by mutableStateOf("")

    var dirty by mutableStateOf(false)
    var reading by mutableStateOf(Prefs.readingMode)
    var showProperties by mutableStateOf(false)

    /// What was on disk when this buffer was read or last written. It is
    /// the hash of the whole file, front matter included, because that is
    /// what another writer would have changed.
    var hashAtOpen by mutableStateOf("")

    /// Set when the file changed underneath an edited buffer. Until it is
    /// answered, saving would destroy whatever arrived.
    var staleBuffer by mutableStateOf(false)

    /// The bytes this buffer stands for: the front matter it was opened
    /// with, then the body as edited.
    fun document(): String = assembleNote(frontMatterRaw, text.text)

    fun open(vaultDir: String, relPath: String) {
        val file = File(vaultDir, relPath)
        val content = if (file.exists()) file.readText() else ""
        val (header, body) = splitNoteRaw(content)
        openRelPath = relPath
        frontMatterRaw = header
        text = TextFieldValue(body)
        hashAtOpen = contentHash(content)
        dirty = false
        staleBuffer = false
    }

    /// A property edit is an edit like any other: the block is rewritten
    /// and the buffer goes dirty, so the same idle autosave persists it.
    fun applyProperties(entries: List<Pair<String, String>>) {
        frontMatterRaw = applyFrontMatterEdits(frontMatterRaw, entries)
        dirty = true
    }

    fun close() {
        openRelPath = null
        text = TextFieldValue("")
        frontMatterRaw = ""
        dirty = false
        staleBuffer = false
    }
}

/// Places dictated words at the caret, replacing any selection, with a
/// separating space when the text before the caret needs one. An edit
/// like any other: the buffer goes dirty and idle autosave persists it.
fun insertAtCaret(editor: EditorState, words: String) {
    val current = editor.text
    val from = current.selection.min.coerceIn(0, current.text.length)
    val to = current.selection.max.coerceIn(0, current.text.length)
    val before = current.text.substring(0, from)
    val spacer = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val inserted = spacer + words.trim()
    editor.text = TextFieldValue(
        text = before + inserted + current.text.substring(to),
        selection = TextRange(from + inserted.length),
    )
    editor.dirty = true
}

@Composable
fun NoteEditor(
    state: GiluneState,
    editor: EditorState,
    modifier: Modifier = Modifier,
    recorder: AudioRecorder? = null,
) {
    val colours = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val relPath = editor.openRelPath
            if (relPath == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("select a note, or capture a thought", color = colours.onSurfaceVariant)
                }
            } else {
                OpenNote(state, editor, relPath, recorder)
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
private fun OpenNote(
    state: GiluneState,
    editor: EditorState,
    relPath: String,
    recorder: AudioRecorder?,
) {
    val colours = MaterialTheme.colorScheme
    val file = File(state.vaultDir, relPath)
    val header = remember(editor.frontMatterRaw) { splitNote(editor.frontMatterRaw) }
    var menuOpen by remember { mutableStateOf(false) }
    var propertiesOpen by remember { mutableStateOf(false) }
    var dictating by remember { mutableStateOf(false) }
    val dictationOffered =
        recorder != null && state.plugins.contributes(Slots.NOTE_ACTIONS, DICTATE_ACTION)

    fun onDiskHash(): String? = if (file.exists()) contentHash(file.readBytes()) else null

    fun write(force: Boolean = false) {
        val document = editor.document()
        if (!force && !mayOverwrite(editor.hashAtOpen, onDiskHash())) {
            editor.staleBuffer = true
            state.message.value = "$relPath changed on disk; choose which version to keep"
            return
        }
        file.writeText(document)
        editor.hashAtOpen = contentHash(document)
        editor.dirty = false
        editor.staleBuffer = false
    }

    // Nothing typed is ever only in memory: the buffer reaches disk after
    // a pause, and reconciliation batches separately so history records
    // edits rather than keystrokes (ADR 0012).
    LaunchedEffect(editor.text.text, editor.frontMatterRaw, relPath) {
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
            if (dictationOffered) {
                IconButton(
                    onClick = {
                        if (!dictating) {
                            try {
                                recorder.start()
                                dictating = true
                                state.message.value = "dictating… click the microphone to stop"
                            } catch (e: Throwable) {
                                state.message.value = "microphone unavailable: ${e.message}"
                            }
                        } else {
                            dictating = false
                            state.dictate(recorder.stop()) { words -> insertAtCaret(editor, words) }
                        }
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = if (dictating) "stop dictating" else "dictate at the cursor",
                        modifier = Modifier.size(16.dp),
                        tint = if (dictating) colours.error else colours.onSurfaceVariant,
                    )
                }
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
                        text = { Text("Properties…", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = false
                            propertiesOpen = true
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

        if (header.frontMatter.isNotEmpty()) PropertiesStrip(header, editor)

        if (editor.reading) {
            MarkdownBody(
                remember(editor.text.text) { parseMarkdown(editor.text.text) },
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            )
        } else {
            // Styled editing (E2 + the E3 checkbox slice): markers off the
            // caret's line render as checkbox glyphs; the caret's own line
            // shows raw Markdown, so the transformation follows the caret.
            val palette = remember(colours) {
                MarkdownPalette(
                    muted = colours.outline,
                    marker = colours.primary,
                    heading = colours.onSurface,
                    code = colours.primary,
                    codeBackground = colours.surfaceVariant,
                    link = colours.primary,
                )
            }
            val line = caretLine(editor.text.text, editor.text.selection.start)
            val transformation = remember(palette, line) { MarkdownEditTransformation(palette, line) }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            // The input surface is the pane, not the text: a short note
            // still fills it, so a click anywhere lands the caret.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val paneHeight = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BasicTextField(
                        value = editor.text,
                        onValueChange = {
                            // Moving the caret is not an edit: only changed
                            // text may mark the buffer unsaved, or a note
                            // nobody typed into would sit dirty for ever.
                            editor.dirty = editor.dirty || it.text != editor.text.text
                            editor.text = it
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = paneHeight)
                            .padding(14.dp)
                            // A tap on a checkbox glyph toggles it and never
                            // moves the caret, so it is intercepted ahead of
                            // the field on the initial pass. A tap anywhere
                            // else passes through untouched.
                            .pointerInput(editor.openRelPath) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                    val text = editor.text.text
                                    val caret = caretLine(text, editor.text.selection.start)
                                    val placed = layout ?: return@awaitEachGesture
                                    val mapping = MarkdownEditTransformation(palette, caret)
                                        .filter(AnnotatedString(text))
                                        .offsetMapping
                                    val original = mapping.transformedToOriginal(
                                        placed.getOffsetForPosition(down.position),
                                    )
                                    // A click on the glyph's right half maps
                                    // just past the marker; step back one.
                                    val marker = checkboxMarkerAt(text, original)
                                        ?: checkboxMarkerAt(text, original - 1)
                                    if (marker != null && caretLine(text, marker.first) != caret) {
                                        down.consume()
                                        waitForUpOrCancellation(pass = PointerEventPass.Initial)?.consume()
                                        editor.text = editor.text.copy(text = toggleCheckbox(text, marker))
                                        editor.dirty = true
                                    }
                                }
                            },
                        onTextLayout = { layout = it },
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

    if (propertiesOpen) {
        PropertiesDialog(
            entries = header.frontMatter,
            onDismiss = { propertiesOpen = false },
            onApply = { entries ->
                propertiesOpen = false
                editor.applyProperties(entries)
            },
        )
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

/// Both segments are the same box with the label centred in it, so
/// neither label can sit higher than the other.
@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(24.dp)
            .defaultMinSize(minWidth = 46.dp)
            .background(if (selected) colours.surfaceVariant else colours.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = if (selected) colours.onSurface else colours.onSurfaceVariant,
        )
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

/// One row per front-matter pair. System keys are shown locked because
/// they are the note's identity; everything else is the user's to name,
/// change or remove.
@Composable
private fun PropertiesDialog(
    entries: List<FrontMatterEntry>,
    onDismiss: () -> Unit,
    onApply: (List<Pair<String, String>>) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val system = remember(entries) { entries.filter { it.key.startsWith(SYSTEM_KEY_PREFIX) } }
    val custom = remember(entries) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(entries.filterNot { it.key.startsWith(SYSTEM_KEY_PREFIX) }.map { it.key to it.value })
        }
    }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    fun addPending() {
        if (newKey.isBlank()) return
        custom.add(newKey to newValue)
        newKey = ""
        newValue = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (entry in system) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "system property",
                            modifier = Modifier.size(13.dp),
                            tint = colours.onSurfaceVariant,
                        )
                        Text(
                            entry.key,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colours.onSurfaceVariant,
                            modifier = Modifier.width(150.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.value,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colours.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                for (index in custom.indices) {
                    val (key, value) = custom[index]
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PropertyField(
                            value = key,
                            onChange = { custom[index] = it to custom[index].second },
                            placeholder = "key",
                            modifier = Modifier.width(169.dp),
                        )
                        PropertyField(
                            value = value,
                            onChange = { custom[index] = custom[index].first to it },
                            placeholder = "value",
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { custom.removeAt(index) }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "remove ${key.ifBlank { "property" }}",
                                modifier = Modifier.size(14.dp),
                                tint = colours.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(color = colours.outlineVariant)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PropertyField(
                        value = newKey,
                        onChange = { newKey = it },
                        placeholder = "new key",
                        modifier = Modifier.width(169.dp),
                    )
                    PropertyField(
                        value = newValue,
                        onChange = { newValue = it },
                        placeholder = "value",
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = ::addPending, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "add property",
                            modifier = Modifier.size(14.dp),
                            tint = colours.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                addPending()
                onApply(system.map { it.key to it.value } + custom.toList())
            }) { Text("Apply", fontSize = 13.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 13.sp) } },
    )
}

@Composable
private fun PropertyField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colours = MaterialTheme.colorScheme
    Box(
        modifier
            .border(1.dp, colours.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colours.outline)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = colours.onSurface,
            ),
            cursorBrush = SolidColor(colours.primary),
        )
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
