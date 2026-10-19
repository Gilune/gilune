// The document pane. One buffer of Markdown is the document; Rich is a
// reading of it and Raw is the same bytes unstyled (ADR 0012, stage E1).
// The mode is view state and is never written into the file. The footer
// strip carries the app's status line, mirroring the ratified layout.
//
// The buffer holds the body alone: front matter is machine contract, kept
// verbatim beside it and reassembled on every write, so editing prose can
// never disturb a note's identity.

package org.gilune.app

import org.gilune.plugin.sdk.FrontMatterEntry
import org.gilune.plugin.sdk.Slots
import org.gilune.plugins.tasks.TASKS_PLUGIN
import org.gilune.plugins.tasks.tasksDecorations
import org.gilune.plugins.tasks.tasksFilter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate

private const val AUTOSAVE_IDLE_MS = 1200L

/// The body field's inset. Named because a collapsed block's overlay is
/// placed in the *text's* coordinates and has to add it back: the layout
/// measures from inside this padding, the overlay is a sibling outside it.
private val EDITOR_PADDING = 14.dp

/// ADR 0015 § 1's fence rule, asked **here** because the `/` menu's
/// machine is line-scoped: [SuggestSession] sees one line and cannot know
/// it sits inside a ``` block, so withholding the call is the caller's
/// job. Counts the fence delimiters on the complete lines above the
/// caret, exactly as `styleMarkdown` and the decoration seam track them.
internal fun caretIsInsideCodeFence(text: String, caret: Int): Boolean {
    val bounded = caret.coerceIn(0, text.length)
    val lines = text.substring(0, bounded).split('\n')
    var open = false
    // The last element is the caret's own line up to the caret, which is
    // not yet a delimiter of anything above it.
    for (index in 0 until lines.size - 1) {
        if (lines[index].trim().startsWith("```")) open = !open
    }
    return open
}

/// The list-continuation rule as the desktop field meets it: two buffers,
/// the one before the keystroke and the one the field just produced.
/// Answers the value to assign instead, or null to assign what was typed.
///
/// The shared rule owns every judgement — whether this was a typed newline
/// at all, whether the line above it is an item, and what to write. All
/// that is desktop here is the pair of `TextFieldValue`s, and the fresh
/// value it builds: the composition is dropped rather than carried, since
/// a buffer with new text in it can have no composing region left over.
private fun continued(before: TextFieldValue, typed: TextFieldValue): TextFieldValue? {
    if (!typed.selection.collapsed) return null
    val caret = typed.selection.start
    if (!insertedNewline(before.text, before.selection.min, before.selection.max, typed.text, caret)) {
        return null
    }
    val written = listContinuation(typed.text, caret)?.applyTo(typed.text, caret) ?: return null
    return TextFieldValue(written.text, TextRange(written.caret))
}

class EditorState : LiveDocumentBuffer {
    private var bufferVault: String? = null
    override val documentFile: String? get() = openRelPath?.let { path -> bufferVault?.let { File(it, path).absoluteFile.normalize().path } }
    override val hasUnsavedText: Boolean get() = dirty
    var openRelPath by mutableStateOf<String?>(null)

    /// The editable buffer: the note's body only, with its selection, so
    /// the caret survives every recomposition.
    var text by mutableStateOf(TextFieldValue(""))

    /// The note's front-matter block exactly as it was read, fences
    /// included. Written back untouched unless the properties editor
    /// rewrites it.
    var frontMatterRaw by mutableStateOf("")

    var dirty by mutableStateOf(false)

    /// Presentation mode (ADR 0012 amendment, owner 2026-10-19): the
    /// rendered note with no caret, where a click acts — ticks a task,
    /// opens a link or a mention — instead of starting an edit. Editing is
    /// simply what a note is, so this is a view someone asks for (the ⋯
    /// menu, Ctrl+E) and never a preference notes open in.
    var reading by mutableStateOf(false)
    var showProperties by mutableStateOf(false)

    /// Raised when presentation mode ends, so editing resumes with the
    /// caret where it was left rather than at the top; the body field
    /// lowers it once it has the focus.
    var resumeEditing by mutableStateOf(false)

    /// Into presentation mode, or back out to editing at the old caret.
    fun togglePresentation() {
        reading = !reading
        if (!reading) resumeEditing = true
    }

    /// Raised by the shell after it creates a note, so the placeholder
    /// name arrives selected and ready to be typed over. The title field
    /// lowers it once it has the focus.
    var focusTitle by mutableStateOf(false)

    /// Raised by the title field when Return is pressed in it (owner,
    /// 2026-09-04: "I expect return to take me into the body"), so the
    /// caret lands at the start of the body. The body field lowers it once
    /// it has the focus.
    var focusBody by mutableStateOf(false)

    /// Raised by the shell (menus, probes) to open the properties dialog
    /// for the current note; the pane lowers it once shown.
    var openProperties by mutableStateOf(false)

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
        bufferVault = vaultDir
        DocumentEditGate.register(this)
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

    /// Apply a document-service proposal to the same header the form reviewed.
    fun applyProperties(expectedHeader: String, updatedHeader: String) {
        require(frontMatterRaw == expectedHeader) { "The properties changed while this form was open. Reopen it to review the current values." }
        if (updatedHeader != frontMatterRaw) { frontMatterRaw = updatedHeader; dirty = true }
    }

    fun close() {
        bufferVault = null
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

/// Words go to the note that started the session, wherever it has got to
/// (ADR 0014). A tab that has been closed since is not a reason to lose
/// them: they go to the status line instead, where they can be read and
/// copied. Only a pane's front tab is composed, so a tab that has fallen
/// behind one has no autosave loop and is flushed here.
private fun deliverDictation(state: GiluneState, tabs: TabsState, tabId: Long, words: String) {
    val located = tabs.panes().firstNotNullOfOrNull { pane ->
        pane.tabs.firstOrNull { it.id == tabId }?.let { pane to it }
    }
    if (located == null) {
        state.message.value = "dictation finished after its note closed: $words"
        return
    }
    val (pane, target) = located
    insertAtCaret(target.editor, words)
    if (pane.active !== target) flushEditor(tabs.vaultDir, target.editor, tabs.onNoteSaved)
}

/// The tab arguments are how a pane hands the editor its place in the
/// document region: `tabs` and `tab` say whose buffer this is, so
/// dictation and the unsaved dot can be spoken for elsewhere, and
/// `onCloseTab` routes Close through the tab rather than emptying the
/// buffer. All three default to nothing, which is the pre-tab editor.
@Composable
fun NoteEditor(
    state: GiluneState,
    editor: EditorState,
    modifier: Modifier = Modifier,
    recorder: AudioRecorder? = null,
    onRenamed: (String) -> Unit = {},
    tabs: TabsState? = null,
    tab: Tab? = null,
    onCloseTab: (() -> Unit)? = null,
) {
    val colours = MaterialTheme.colorScheme
    // The status line is the window's, not this pane's: several panes
    // must still show one of it (ADR 0014).
    Column(modifier.fillMaxSize()) {
        val relPath = editor.openRelPath
        if (relPath == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("select a note, or capture a thought", color = colours.onSurfaceVariant)
            }
        } else {
            androidx.compose.runtime.CompositionLocalProvider(LocalDocumentTextStyle provides LocalNoteAppearance.current.edit.textStyle) {
                OpenNote(state, editor, relPath, recorder, onRenamed, tabs, tab, onCloseTab)
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
    onRenamed: (String) -> Unit,
    tabs: TabsState?,
    tab: Tab?,
    onCloseTab: (() -> Unit)?,
) {
    val colours = MaterialTheme.colorScheme
    val file = File(state.vaultDir, relPath)
    val appearance = LocalNoteAppearance.current
    val header = remember(editor.frontMatterRaw) { splitNote(editor.frontMatterRaw) }
    var menuOpen by remember { mutableStateOf(false) }
    var refactoringOpen by remember { mutableStateOf(false) }
    if (refactoringOpen) RefactoringDialog(state, relPath, editor.document()) { refactoringOpen = false }
    var propertiesOpen by remember { mutableStateOf(false) }
    LaunchedEffect(editor.openProperties) {
        if (editor.openProperties) {
            propertiesOpen = true
            editor.openProperties = false
        }
    }
    var dictating by remember { mutableStateOf(false) }
    val dictationOffered =
        recorder != null && state.plugins.contributes(Slots.NOTE_ACTIONS, DICTATE_ACTION)
    // Dictation outlives the tab it was started from being in front, so
    // the session belongs to TabsState; without one it is this pane's.
    val dictatingHere = if (tabs != null && tab != null) tabs.dictationTabId == tab.id else dictating
    val dictatingElsewhere =
        tabs != null && tabs.dictationTabId != null && tabs.dictationTabId != tab?.id

    fun onDiskHash(): String? = if (file.exists()) contentHash(file.readBytes()) else null

    // Where a rendered block's row text sends a click. A row opens its note through the pane
    // showing this editor, which is the one rule every opener takes
    // (ADR 0014) — reuse the tab, else a new one. Without tabs, which is
    // the pre-tab editor and the tests, there is nowhere to send a click
    // and the rows draw inert.
    val openRow: ((String) -> Unit)? = tabs?.let { host ->
        { relPath: String ->
            host.open(relPath)
            Unit
        }
    }

    // One write path in the app: autosave, leaving a tab and closing one
    // all go through the same guard (ADR 0012). `force` is the answer the
    // stale-buffer banner takes, and only it may pass the guard.
    //
    // A write announces itself, by name: the file it just wrote is
    // reconciled and indexed before the cursor moves, so a panel woken by
    // the cursor reads an index that already knows about this note
    // (ADR 0020 § 2.2). A save nobody hears about is an agenda that only
    // catches up on reload (owner report, 2026-08-31).
    fun write(force: Boolean = false) {
        if (force) synchronized(DocumentEditGate.monitor) {
            val document = editor.document()
            file.writeText(document)
            editor.hashAtOpen = contentHash(document)
            editor.dirty = false
            editor.staleBuffer = false
            state.noteSaved(relPath)
            return
        }
        val wasDirty = editor.dirty
        if (!flushEditor(state.vaultDir, editor)) {
            state.message.value = "$relPath changed on disk; choose which version to keep"
        } else if (wasDirty) {
            traceCompletion("save-index-refresh")
            state.noteSaved(relPath)
        }
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
    LaunchedEffect(state.vaultCursor.value, relPath) {
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
            // With tabs the dot rides in the tab, where it can be seen
            // from a note that is not in front.
            if (tabs == null && editor.dirty) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colours.primary))
            }
            if (dictationOffered) {
                fun begin() {
                    try {
                        recorder.start()
                        if (tabs != null && tab != null) tabs.dictationTabId = tab.id else dictating = true
                        state.message.value = "dictating… click the microphone to stop"
                    } catch (e: Throwable) {
                        state.message.value = "microphone unavailable: ${e.message}"
                    }
                }

                fun end() {
                    val target = if (tabs != null && tab != null) tab.id else null
                    if (tabs != null) tabs.dictationTabId = null else dictating = false
                    val audio = recorder.stop()
                    if (tabs != null && target != null) {
                        state.dictate(audio) { words -> deliverDictation(state, tabs, target, words) }
                    } else {
                        state.dictate(audio) { words -> insertAtCaret(editor, words) }
                    }
                }

                val dictationWords = when {
                    dictatingElsewhere -> "Dictating in another note"
                    dictatingHere -> "Stop dictating"
                    else -> "Dictate at the cursor"
                }
                Tooltip(dictationWords) {
                    IconButton(
                        onClick = {
                            when {
                                // One session at a time: the other note's
                                // microphone is still live.
                                dictatingElsewhere -> state.message.value = "already dictating in another note"
                                dictatingHere -> end()
                                else -> begin()
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Mic,
                            contentDescription = dictationWords.lowercase(),
                            modifier = Modifier.size(16.dp),
                            tint = when {
                                dictatingHere -> colours.error
                                dictatingElsewhere -> colours.outline
                                else -> colours.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            DocumentLinksButton(state, editor.document(), openRow ?: {})
            // Editing needs no switch: it is what a note is. Presenting
            // says so, and is its own way back.
            if (editor.reading) {
                Tooltip("Back to editing (Esc or Ctrl+E)") {
                    TextButton(onClick = editor::togglePresentation, modifier = Modifier.testTag("presenting")) {
                        Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Presenting", Modifier.padding(start = 5.dp), fontSize = 13.sp)
                    }
                }
            }
            Box {
                Tooltip("Note actions") {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp).testTag("note-actions")) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "note actions",
                            modifier = Modifier.size(16.dp),
                            tint = colours.onSurfaceVariant,
                        )
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (editor.reading) "Back to editing" else "Presentation mode", fontSize = 13.sp) },
                        trailingIcon = { Text("Ctrl+E", fontSize = 11.sp, color = colours.onSurfaceVariant) },
                        onClick = { menuOpen = false; editor.togglePresentation() },
                        modifier = Modifier.testTag("toggle-presentation"),
                    )
                    if (state.gilune.value != null) {
                        DropdownMenuItem(
                            text = { Text("Rename references…", fontSize = 13.sp) },
                            onClick = { menuOpen = false; refactoringOpen = true },
                        )
                    }
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
                            // A tab closes through its pane, which may
                            // refuse; without one, closing empties the
                            // buffer as it always did.
                            if (onCloseTab != null) {
                                onCloseTab()
                            } else {
                                if (editor.dirty) write()
                                editor.close()
                            }
                            state.sync(announce = false)
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = colours.outlineVariant)

        if (editor.staleBuffer) StaleBufferBanner(state, editor, relPath, ::write)

        // Fixed above the body rather than scrolled with it: the title is
        // the note's name, not its first line, and both modes show it.
        NoteTitle(state, editor, relPath, onRenamed)

        if (header.frontMatter.isNotEmpty()) PropertiesStrip(header, editor)

        // The editor seams (ADR 0015 § 5). What is composed comes from
        // the registry: the shell's own registrations lead, and the
        // tasks plugin's date styling and `/` menu are here only while
        // that plugin is enabled and still declares them. Switch it off
        // and the tokens are prose and the menu never opens — no byte
        // changes either way, which is what removability has to mean.
        // Both modes tick through the same list, so a task ticked while
        // presenting is stamped `done:` exactly as one ticked in the editor.
        val plugins = state.plugins.toList()
        val filter = plugins.tasksFilter()
        val decorations = remember(plugins, filter) {
            if (plugins.pluginContributes(TASKS_PLUGIN, Slots.EDITOR_DECORATIONS)) {
                builtInDecorations() + tasksDecorations(filter)
            } else {
                builtInDecorations()
            }
        }

        if (editor.reading) {
            PresentedNote(state, editor, decorations, openRow) { write() }
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
            // The raw-line exception follows a caret someone can see: an
            // unfocused buffer has none, so a note opens with every task
            // drawn as a checkbox, ready to be ticked (owner, 2026-08-31).
            var bodyFocused by remember { mutableStateOf(false) }
            val bodyFocus = remember { FocusRequester() }
            // Return in the title hands the caret to the body, at its
            // start: the first line is where a name leads. Moving the
            // caret is not an edit, so `dirty` is left alone.
            // Leaving presentation mode asks for the same focus, but keeps
            // the caret where it was.
            LaunchedEffect(editor.focusBody, editor.resumeEditing) {
                if (!editor.focusBody && !editor.resumeEditing) return@LaunchedEffect
                if (editor.focusBody) editor.text = editor.text.copy(selection = TextRange(0))
                // The field may have been composed by the very key that
                // raised the flag (a note being read switches to editing
                // first), and a requester answers only once its node is
                // attached — the same lesson as the rail's Ctrl+F. So ask,
                // and if the field is not there yet, wait a frame and ask
                // again, a few times at most.
                var attempts = 0
                while (runCatching { bodyFocus.requestFocus() }.isFailure && attempts++ < 5) {
                    withFrameNanos { }
                }
                editor.focusBody = false
                editor.resumeEditing = false
            }
            val line =
                if (bodyFocused) caretLine(editor.text.text, editor.text.selection.start) else null

            // T4 in Edit mode (owner, 2026-09-01): a `filter` block behaves
            // like a checkbox — rendered until the caret is placed within
            // it. Its cadence is § 4's, not a keystroke's: the buffer as
            // **settled**, so a block recomputes once typing stops, and the
            // opened buffer seeds it so a note opens with its blocks drawn.
            var settled by remember(relPath) { mutableStateOf(editor.text.text) }
            LaunchedEffect(editor.text.text) {
                delay(AUTOSAVE_IDLE_MS)
                settled = editor.text.text
            }
            // The caret's own block is never evaluated: it is showing its
            // raw fence, and a query nobody can see is not worth a pass
            // over the vault (§ 4).
            val wanted = remember(settled, line) {
                val fences = fencedBlocks(settled)
                val caretBody = fences.firstOrNull { line != null && line in it.lines }?.body
                fences.filter { it.info.equals(FILTER_FENCE, ignoreCase = true) }
                    .map { it.body }
                    .distinct()
                    .filterNot { it == caretBody }
            }
            val open = state.gilune.value
            val blockDay = rememberQueryDay()
            val evaluated = rememberQueryWork(state.vaultCursor.value, state.searchRefresh.value, wanted, plugins, open, blockDay) {
                val types = searchTypesFor(plugins)
                wanted.associateWith { body ->
                    if (open == null) FilterResult.Hint("Opening vault...")
                    else evaluateFilterBlock(open, state.vaultDir, body, types, blockDay, FILTER_EDIT_ROW_CAP, searchOperatorsFor(plugins, blockDay))
                }
            }.value
            if (evaluated.error != null) {
                androidx.compose.material3.TextButton(onClick = state::retrySearchIndex) { Text("Retry search") }
            }
            val results = evaluated.value ?: wanted.associateWith { FilterResult.Hint(evaluated.error ?: "Searching...") }
            val blockDecorations = remember(results) { listOf(filterBlockDecoration(results)) }

            val transformation = remember(palette, line, decorations, blockDecorations, appearance.edit.size) {
                MarkdownEditTransformation(palette, line, decorations, blockDecorations, appearance.edit.size / 13f)
            }
            // Where each collapsed block's placeholder landed, asked of the
            // same composition the field is showing, so what is drawn and
            // what was reserved cannot drift apart.
            val collapsed = remember(editor.text.text, line, decorations, blockDecorations) {
                collapsedBlocks(editor.text.text, line, decorations, blockDecorations)
            }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            var hoveringActivation by remember { mutableStateOf(false) }

            // The page follows the caret (owner, 2026-10-19). The body
            // scrolls in a container of its own, so a Return at the foot
            // of the pane put the caret out of sight. Only a change the
            // field itself made asks: a ticked checkbox or dictation
            // landing replaces the buffer from outside, and must leave the
            // page where the person was reading — as must the wheel.
            val caretView = remember { BringIntoViewRequester() }
            var caretMoves by remember { mutableStateOf(0) }
            val currentTransformation by rememberUpdatedState(transformation)
            val density = LocalDensity.current
            LaunchedEffect(caretMoves) {
                if (caretMoves == 0) return@LaunchedEffect
                // The layout of the change arrives with the next frame.
                withFrameNanos { }
                val placed = layout ?: return@LaunchedEffect
                val caret = currentTransformation.filter(AnnotatedString(editor.text.text)).offsetMapping
                    .originalToTransformed(editor.text.selection.end)
                    .coerceIn(0, placed.layoutInput.text.length)
                // A margin below the caret's line, so it never sits flush
                // against the pane's edge.
                val margin = with(density) { EDITOR_PADDING.toPx() }
                val rect = placed.getCursorRect(caret)
                caretView.bringIntoView(rect.copy(top = rect.top - margin, bottom = rect.bottom + margin))
            }

            // The `/` menu belongs to this note and this mode; leaving
            // either drops it. Dismissal costs the buffer nothing, because
            // a session never wrote to it (ADR 0015 § 2).
            //
            // And only from typing (owner report, 2026-10-19): the menu
            // answers Up and Down while it is up, so a menu that opened
            // wherever the caret *landed* beside a trigger — walking down a
            // note through an @mention — took the next arrow key and the
            // caret seemed stuck. An edit opens it; moving the caret
            // without one closes it, as in any other editor.
            var typedHere by remember(relPath) { mutableStateOf(false) }
            val bodyStart = editor.frontMatterRaw.length
            val completions = rememberEditorCompletions(state, editor.document(), bodyStart + editor.text.selection.start,
                editor.text.selection.collapsed && typedHere, documentKey = editor.openRelPath.orEmpty()) { start, end, replacement ->
                require(start >= bodyStart) { "Insertion must stay in the editable body." }
                editor.text = replaceRange(editor.text, (start - bodyStart) until (end - bodyStart), replacement)
                editor.dirty = true
                bodyFocus.requestFocus()
            }
            var fieldOrigin by remember { mutableStateOf(Offset.Zero) }

            // Up and Down are the editor's own (owner report, 2026-10-19).
            // The field's line jump returns a *displayed* offset unmapped
            // whenever the caret lands past a line's end — an empty line, a
            // short one — so with decorations shortening the lines above,
            // Down at the foot of a note fell back into the task above it.
            // The same jump is made here through the one offset table.
            //
            // The column a run of vertical moves keeps is remembered until
            // anything else moves the caret: down through a short line and
            // on, and the caret comes back out where it went in.
            var verticalX by remember { mutableStateOf<Float?>(null) }
            fun moveByLines(lines: Int, extend: Boolean): Boolean {
                val placed = layout ?: return false
                val value = editor.text
                val mapping = transformation.filter(AnnotatedString(value.text)).offsetMapping
                val from = mapping.originalToTransformed(value.selection.end).coerceIn(0, placed.layoutInput.text.length)
                val x = verticalX ?: placed.getCursorRect(from).left
                val line = placed.getLineForOffset(from) + lines
                val target = when {
                    line < 0 -> 0
                    line >= placed.lineCount -> value.text.length
                    else -> mapping.transformedToOriginal(
                        placed.getOffsetForPosition(Offset(x, (placed.getLineTop(line) + placed.getLineBottom(line)) / 2)),
                    ).coerceIn(0, value.text.length)
                }
                editor.text = value.copy(selection = if (extend) TextRange(value.selection.start, target) else TextRange(target))
                verticalX = x
                caretMoves++
                typedHere = false
                return true
            }

            /// The one question both the tap and the pointer ask of the
            /// same machinery: which activation-bearing decoration is
            /// drawn under this position? The seam takes the offset
            /// straight from the layout and answers in source
            /// coordinates. Decorations on the caret's line are raw text
            /// and answer nothing; so does a style-only registration —
            /// every one the tasks plugin makes in T1 — which is why a
            /// due token is inert to the mouse by construction.
            fun activationUnder(position: Offset): DecorationHit? {
                val placed = layout ?: return null
                val text = editor.text.text
                val caret =
                    if (bodyFocused) caretLine(text, editor.text.selection.start) else null
                return decorationAt(
                    text,
                    placed.getOffsetForPosition(position),
                    caret,
                    decorations,
                    blockDecorations,
                )
            }

            /// Activation is the only route by which a decoration may
            /// change a byte, and it changes exactly one line: the chain
            /// folds left over every registration that claims it and the
            /// result is written once, as one edit (§ 5). With the shell
            /// standing alone that is the checkbox toggle, unchanged.
            fun activate(hit: DecorationHit) {
                val text = editor.text.text
                val from = hit.lineRange.first
                val to = (hit.lineRange.last + 1).coerceIn(from, text.length)
                val rewritten = activateLine(text.substring(from, to), decorations) ?: return
                val written = text.substring(0, from) + rewritten + text.substring(to)
                val selection = editor.text.selection
                editor.text = editor.text.copy(
                    text = written,
                    // A rewrite may change the line's length once a
                    // registration stamps rather than flips; the caret
                    // stays where it was and is only kept in bounds.
                    selection = TextRange(
                        selection.start.coerceIn(0, written.length),
                        selection.end.coerceIn(0, written.length),
                    ),
                )
                editor.dirty = true
                // § 1: toggling writes at the toggle. A click is an action,
                // not typing — it does not wait out the autosave idle, so
                // the agenda drops the task the moment it is ticked.
                write()
            }

            // The input surface is the pane, not the text: a short note
            // still fills it, so a click anywhere lands the caret.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val paneHeight = maxHeight
                val blockWidth = maxWidth - EDITOR_PADDING * 2
                // A box rather than a column because the field is not the
                // only thing in it: a collapsed block draws over the lines
                // the field reserved for it, and being its sibling here is
                // what makes it scroll with the text it covers.
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("note-scroll")) {
                    BasicTextField(
                        value = editor.text,
                        onValueChange = { typed ->
                            // Return on a list line continues the list
                            // (owner rule, 2026-09-01). It is caught here,
                            // as a change to the buffer, rather than in
                            // the preview key handler below, for two
                            // reasons. An IME is never interrupted: the
                            // newline is read off the text the keyboard
                            // committed, so no key is taken out of its
                            // hands. And the `/` menu keeps its Enter for
                            // nothing: while a session is live the handler
                            // consumes the key and the field never sees
                            // it, so no change arrives and there is
                            // nothing here to yield.
                            val changed = continued(editor.text, typed) ?: typed
                            // Moving the caret is not an edit: only changed
                            // text may mark the buffer unsaved, or a note
                            // nobody typed into would sit dirty for ever.
                            editor.dirty = editor.dirty || changed.text != editor.text.text
                            if (changed.text != editor.text.text || changed.selection != editor.text.selection) caretMoves++
                            if (changed.text != editor.text.text || changed.selection != editor.text.selection) verticalX = null
                            if (changed.text != editor.text.text) typedHere = true
                            else if (changed.selection != editor.text.selection) typedHere = false
                            editor.text = changed

                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = paneHeight)
                            .padding(EDITOR_PADDING)
                            // Inside the padding, so its space is the text
                            // layout's and a cursor rect needs no shifting.
                            .bringIntoViewRequester(caretView)
                            // Window coordinates for the caret popup: the
                            // layout gives a rectangle in the field's own
                            // space, and this is what puts it on screen.
                            .onGloballyPositioned { fieldOrigin = it.localToWindow(Offset.Zero) }
                            .focusRequester(bodyFocus)
                            // A field the caret has left cannot have a menu
                            // over its caret.
                            .onFocusChanged {
                                traceCompletion("body-focus=${it.isFocused}")
                                bodyFocused = it.isFocused
                                if (!it.isFocused) completions.dismiss()
                            }
                            // The menu's keys, and **only** while it is up:
                            // with no session every key is the field's, so
                            // nothing about ordinary editing changes. The
                            // popup is not focusable, so this is where its
                            // keyboard lives (§ 2).
                            .onPreviewKeyEvent { event ->
                                when {
                                    event.type != KeyEventType.KeyDown -> false
                                    !completions.visible -> when {
                                        event.isCtrlPressed || event.isAltPressed || event.isMetaPressed -> false
                                        event.key == Key.DirectionDown -> moveByLines(1, event.isShiftPressed)
                                        event.key == Key.DirectionUp -> moveByLines(-1, event.isShiftPressed)
                                        else -> false
                                    }
                                    event.key == Key.DirectionDown -> { completions.move(1); true }
                                    event.key == Key.DirectionUp -> { completions.move(-1); true }
                                    event.key == Key.Enter -> { completions.choose(completions.selected); true }
                                    event.key == Key.Escape -> { completions.dismiss(); true }
                                    else -> false
                                }
                            }

                            // A tap on a checkbox glyph toggles it and never
                            // moves the caret, so it is intercepted ahead of
                            // the field on the initial pass. A tap anywhere
                            // else passes through untouched.
                            .pointerInput(editor.openRelPath, decorations) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                    val hit = activationUnder(down.position) ?: return@awaitEachGesture
                                    down.consume()
                                    waitForUpOrCancellation(pass = PointerEventPass.Initial)?.consume()
                                    activate(hit)
                                }
                            }
                            // A glyph answers a click, so it must not look
                            // like text. Only a move asks the question.
                            .pointerInput(editor.openRelPath, decorations) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        when (event.type) {
                                            PointerEventType.Move ->
                                                hoveringActivation =
                                                    activationUnder(event.changes.last().position) != null
                                            PointerEventType.Exit -> hoveringActivation = false
                                            else -> {}
                                        }
                                    }
                                }
                            }
                            // The field sets an I-beam of its own inside,
                            // so the override has to reach past it.
                            .pointerHoverIcon(
                                if (hoveringActivation) PointerIcon.Hand else PointerIcon.Text,
                                overrideDescendants = true,
                            )
                            // Named so tests can ask for the body without
                            // ambiguity against the title field.
                            .testTag("note-body"),
                        onTextLayout = { layout = it },
                        textStyle = TextStyle(
                            fontFamily = appearance.edit.font.family,
                            fontSize = appearance.edit.size.sp,
                            lineHeight = appearance.edit.lineHeight.sp,
                            color = colours.onSurface,
                        ),
                        cursorBrush = SolidColor(colours.primary),
                        visualTransformation = transformation,
                    )

                    // The blocks the transformation collapsed, drawn into
                    // the blank lines it left for them. A block whose
                    // result has gone since draws nothing rather than a
                    // stale list — but it cannot: the registration only
                    // claims a fence it has a result for.
                    val laidOut = layout
                    if (laidOut != null) {
                        for (block in collapsed) {
                            val result = results[block.fence.body] ?: continue
                            CollapsedFilterBlock(block, laidOut, result, blockDay, openRow, blockWidth) { toggleTaskResult(state, it) }
                        }
                    }
                }

                val placed = layout
                if (placed != null && (completions.visible || completions.creation != null)) {
                    val transformed = transformation.filter(AnnotatedString(editor.text.text)).offsetMapping
                        .originalToTransformed(editor.text.selection.start)
                    val anchor = placed.getCursorRect(transformed.coerceIn(0, placed.layoutInput.text.length)).translate(fieldOrigin)
                    androidx.compose.ui.window.Popup(CaretPositionProvider(anchor),
                        onDismissRequest = { traceCompletion("popup-dismiss"); completions.dismiss() },
                        properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                        Box(Modifier.width(360.dp).testTag("suggest-popup")) {
                            EditorCompletionMenu(completions, state, openRow ?: {})
                        }
                    }
                }


            }
        }
    }

    if (propertiesOpen) {
        SourcePropertiesDialog(
            source = editor.document(),
            onDismiss = { propertiesOpen = false },
            onApply = editor::applyProperties,
        )
    }
}

/// One collapsed `filter` block, drawn over the blank lines the
/// transformation reserved for it (owner, 2026-09-01).
///
/// The box is anchored and sized **from the layout itself** — the top of
/// the placeholder's first line to the bottom of its last — so it tracks
/// scrolling, re-wrapping and any change of metrics with no second source
/// of truth, and its rows are those lines exactly.
///
/// **Nothing here consumes a pointer event except a row.** A click beside
/// one reaches the field underneath, which lands the caret in the
/// placeholder — and a caret inside the block is what brings the raw fence
/// back. That is the answer to how a covered block is edited.
@Composable
private fun CollapsedFilterBlock(
    block: CollapsedBlock,
    layout: TextLayoutResult,
    result: FilterResult,
    today: LocalDate,
    onOpen: ((String) -> Unit)?,
    width: Dp,
    onToggle: (org.gilune.plugins.tasks.TaskHit) -> Unit,
) {
    // The layout is one frame behind a buffer that has just changed, so
    // every read of it is asked for rather than assumed.
    val bounds = runCatching {
        val first = layout.getLineForOffset(block.transformedStart)
        val last = (first + block.lines - 1).coerceAtMost(layout.lineCount - 1)
        layout.getLineTop(first) to layout.getLineBottom(last)
    }.getOrNull() ?: return
    val density = LocalDensity.current
    val top = with(density) { bounds.first.toDp() }
    val height = with(density) { (bounds.second - bounds.first).toDp() }
    if (height <= 0.dp || width <= 0.dp) return
    Box(
        Modifier
            .offset(x = EDITOR_PADDING, y = EDITOR_PADDING + top)
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        FilterBlockLines(result, today, onOpen, height / block.lines, width, onToggle = onToggle)
    }
}

/// The note's name, editable where it is read. A note's title is its
/// file name — the vault is the store — so committing one renames the
/// file, attachments and all, by the same path the tree's rename takes.
@Composable
private fun NoteTitle(
    state: GiluneState,
    editor: EditorState,
    relPath: String,
    onRenamed: (String) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val stem = relPath.substringAfterLast('/').removeSuffix(".md")
    var name by remember(relPath) { mutableStateOf(TextFieldValue(stem, TextRange(stem.length))) }
    val focus = remember { FocusRequester() }

    fun restore() {
        name = TextFieldValue(stem, TextRange(stem.length))
    }

    // The name and the path it renames are read from the same
    // composition, so a commit can never rename the note that arrived
    // after it — the click that opens another note also takes the focus.
    fun commit() {
        val wanted = name.text.trim()
        if (wanted == stem) return
        val renamed = renameNote(state.vaultDir, relPath, wanted)
        if (renamed == null) {
            state.message.value = "could not rename $stem"
            restore()
            return
        }
        if (editor.openRelPath == relPath) {
            editor.openRelPath = renamed
            onRenamed(renamed)
        }
        state.sync(announce = false)
    }

    LaunchedEffect(editor.focusTitle) {
        if (!editor.focusTitle) return@LaunchedEffect
        name = TextFieldValue(stem, TextRange(0, stem.length))
        focus.requestFocus()
        editor.focusTitle = false
    }

    BasicTextField(
        value = name,
        onValueChange = { name = it; editor.reading = false },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)
            .focusRequester(focus)
            .testTag("note-title")
            // Losing the focus is a commit: a name left in the field is a
            // name the user meant.
            .onFocusChanged { if (!it.isFocused) commit() }
            .onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    // Return commits the name and moves into the body
                    // (owner, 2026-09-04) — whether or not the name
                    // changed, or the key does nothing visible at all. A
                    // note being read has no caret to move into, so it
                    // is switched to editing first: entering the body is
                    // editing.
                    event.key == Key.Enter -> {
                        commit()
                        editor.reading = false
                        editor.focusBody = true
                        true
                    }
                    event.key == Key.Escape -> {
                        restore()
                        true
                    }
                    else -> false
                }
            },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = colours.onSurface,
        ),
        cursorBrush = SolidColor(colours.primary),
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
