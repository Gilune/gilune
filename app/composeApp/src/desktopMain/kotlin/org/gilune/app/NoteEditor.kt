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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.text.style.TextAlign
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
            OpenNote(state, editor, relPath, recorder, onRenamed, tabs, tab, onCloseTab)
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
    val header = remember(editor.frontMatterRaw) { splitNote(editor.frontMatterRaw) }
    var menuOpen by remember { mutableStateOf(false) }
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

    // Where a rendered block's rows send a click (ADR 0015 § 4: rows
    // navigate, they do not edit). A row opens its note through the pane
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
        if (force) {
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
                        contentDescription = when {
                            dictatingElsewhere -> "dictating in another note"
                            dictatingHere -> "stop dictating"
                            else -> "dictate at the cursor"
                        },
                        modifier = Modifier.size(16.dp),
                        tint = when {
                            dictatingHere -> colours.error
                            dictatingElsewhere -> colours.outline
                            else -> colours.onSurfaceVariant
                        },
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

        if (editor.reading) {
            // The `editor.blocks` seam (ADR 0015 § 5, T4). The Code branch
            // resolves through a table keyed by info string; the shell's
            // own `filter` is its only entry, and every other fence — and
            // every fence at all with this list empty — is the code box it
            // has always been. Reading the cursor here is what makes a
            // block recompute when the vault moves rather than per
            // keystroke (§ 4).
            MarkdownBody(
                remember(editor.text.text) { parseMarkdown(editor.text.text) },
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                renderers = BlockRenderers(
                    listOf(
                        filterBlockRegistration(
                            state,
                            state.plugins.toList(),
                            onOpen = openRow,
                        ),
                    ),
                ),
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
            // The editor seams (ADR 0015 § 5). What is composed comes from
            // the registry: the shell's own registrations lead, and the
            // tasks plugin's date styling and `/` menu are here only while
            // that plugin is enabled and still declares them. Switch it off
            // and the tokens are prose and the menu never opens — no byte
            // changes either way, which is what removability has to mean.
            val plugins = state.plugins.toList()
            val filter = plugins.tasksFilter()
            val decorations = remember(plugins, filter) {
                if (plugins.pluginContributes(TASKS_PLUGIN, Slots.EDITOR_DECORATIONS)) {
                    builtInDecorations() + tasksDecorations(filter)
                } else {
                    builtInDecorations()
                }
            }
            // Stage one's fields, gated the same way and additionally by
            // the both-enabled rule inside `taskFields`. Empty means
            // `openAt` can never fire, so a removed plugin takes the
            // trigger with it rather than leaving a menu with no rows.
            val fields = remember(plugins) {
                if (plugins.pluginContributes(TASKS_PLUGIN, Slots.EDITOR_SUGGEST)) {
                    taskFields(plugins)
                } else {
                    emptyList()
                }
            }

            // The raw-line exception follows a caret someone can see: an
            // unfocused buffer has none, so a note opens with every task
            // drawn as a checkbox, ready to be ticked (owner, 2026-08-31).
            var bodyFocused by remember { mutableStateOf(false) }
            val bodyFocus = remember { FocusRequester() }
            // Return in the title hands the caret to the body, at its
            // start: the first line is where a name leads. Moving the
            // caret is not an edit, so `dirty` is left alone.
            LaunchedEffect(editor.focusBody) {
                if (!editor.focusBody) return@LaunchedEffect
                editor.text = editor.text.copy(selection = TextRange(0))
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
            val evaluated = remember(state.vaultDir, state.vaultCursor.value, wanted, plugins, filter, open) {
                val day = LocalDate.now()
                val types = searchTypesFor(plugins)
                day to wanted.associateWith { body ->
                    if (open == null) {
                        FilterResult.Hint(FILTER_NO_MATCHES)
                    } else {
                        evaluateFilterBlock(open, state.vaultDir, body, types, day, FILTER_EDIT_ROW_CAP)
                    }
                }
            }
            val blockDay = evaluated.first
            val results = evaluated.second
            val blockDecorations = remember(results) { listOf(filterBlockDecoration(results)) }

            val transformation = remember(palette, line, decorations, blockDecorations) {
                MarkdownEditTransformation(palette, line, decorations, blockDecorations)
            }
            // Where each collapsed block's placeholder landed, asked of the
            // same composition the field is showing, so what is drawn and
            // what was reserved cannot drift apart.
            val collapsed = remember(editor.text.text, line, decorations, blockDecorations) {
                collapsedBlocks(editor.text.text, line, decorations, blockDecorations)
            }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            var hoveringActivation by remember { mutableStateOf(false) }

            // The `/` menu belongs to this note and this mode; leaving
            // either drops it. Dismissal costs the buffer nothing, because
            // a session never wrote to it (ADR 0015 § 2).
            var session by remember(relPath) { mutableStateOf<SuggestSession?>(null) }
            var fieldOrigin by remember { mutableStateOf(Offset.Zero) }

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
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                            editor.text = changed
                            // A live session re-reads the line; otherwise
                            // the trigger is offered the caret. `openAt`
                            // asks § 2's own questions — a `/` at a word
                            // boundary on a task line — so all that is
                            // owed here is what it cannot see: an empty
                            // registry, a selection, and the fence rule.
                            val live = session
                            session = when {
                                live != null -> live.updateAt(changed.text, changed.selection.start)
                                fields.isEmpty() -> null
                                !changed.selection.collapsed -> null
                                caretIsInsideCodeFence(changed.text, changed.selection.start) -> null
                                else -> SuggestSession.openAt(
                                    changed.text,
                                    changed.selection.start,
                                    fields,
                                    filter,
                                    LocalDate.now(),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = paneHeight)
                            .padding(EDITOR_PADDING)
                            // Window coordinates for the caret popup: the
                            // layout gives a rectangle in the field's own
                            // space, and this is what puts it on screen.
                            .onGloballyPositioned { fieldOrigin = it.localToWindow(Offset.Zero) }
                            .focusRequester(bodyFocus)
                            // A field the caret has left cannot have a menu
                            // over its caret.
                            .onFocusChanged {
                                bodyFocused = it.isFocused
                                if (!it.isFocused) session = null
                            }
                            // The menu's keys, and **only** while it is up:
                            // with no session every key is the field's, so
                            // nothing about ordinary editing changes. The
                            // popup is not focusable, so this is where its
                            // keyboard lives (§ 2).
                            .onPreviewKeyEvent { event ->
                                val live = session
                                when {
                                    live == null -> false
                                    event.type != KeyEventType.KeyDown -> false
                                    event.key == Key.DirectionDown -> {
                                        session = live.moveDown()
                                        true
                                    }
                                    event.key == Key.DirectionUp -> {
                                        session = live.moveUp()
                                        true
                                    }
                                    event.key == Key.Enter -> {
                                        when (val outcome = live.accept()) {
                                            // The session decided the exact
                                            // range and the exact text; the
                                            // caller is what writes.
                                            is SuggestOutcome.Insert -> {
                                                editor.text = replaceRange(
                                                    editor.text,
                                                    outcome.acceptance.replaceRange,
                                                    outcome.acceptance.text,
                                                )
                                                editor.dirty = true
                                                session = null
                                            }
                                            is SuggestOutcome.Open -> session = outcome.session
                                            SuggestOutcome.Dismissed -> session = null
                                        }
                                        true
                                    }
                                    // Consumed, so dismissing the menu
                                    // cannot also close settings behind it.
                                    // The `/` and what follows stay: they
                                    // are the user's own bytes.
                                    event.key == Key.Escape -> {
                                        session = null
                                        true
                                    }
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
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
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
                            CollapsedFilterBlock(block, laidOut, result, blockDay, openRow, blockWidth)
                        }
                    }
                }

                // The caret-anchored menu (ADR 0015 § 2). The session's
                // document offset travels through the transformation the
                // field is actually showing, then through the layout,
                // then into window coordinates — so the popup follows the
                // caret rather than a node, and a checkbox glyph earlier
                // on the line cannot put it in the wrong place.
                val live = session
                val placed = layout
                if (live != null && placed != null) {
                    val anchor = runCatching {
                        val transformed = transformation
                            .filter(AnnotatedString(editor.text.text))
                            .offsetMapping
                            .originalToTransformed(live.caret)
                        placed
                            .getCursorRect(transformed.coerceIn(0, placed.layoutInput.text.length))
                            .translate(fieldOrigin)
                    }.getOrNull()
                    if (anchor != null) {
                        SuggestPopup(live, anchor, onDismiss = { session = null })
                    }
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
        FilterBlockLines(result, today, onOpen, height / block.lines, width)
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
        onValueChange = { name = it },
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

/// Properties open over the window, as settings do, so the scrim starts
/// at the window's corner rather than the pane's and nothing behind it
/// can be clicked while they are open.
private val WindowOverlay = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
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

    Popup(
        popupPositionProvider = WindowOverlay,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                Modifier
                    .width(520.dp)
                    // Consume clicks so a click inside the card never
                    // reaches the scrim and closes it under the user.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
                shape = RoundedCornerShape(10.dp),
                color = colours.surface,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colours.onSurfaceVariant,
                        )
                        Text(
                            "Properties",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "close properties",
                                modifier = Modifier.size(16.dp),
                                tint = colours.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = colours.outlineVariant)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (entry in system) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colours.surfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
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
                                    color = colours.onSurface,
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
                                    // The locked rows carry a lock and an
                                    // inset before their key; this makes
                                    // both value columns start together.
                                    modifier = Modifier.width(177.dp),
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
                                modifier = Modifier.width(177.dp),
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
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 13.sp) }
                        Button(onClick = {
                            addPending()
                            onApply(system.map { it.key to it.value } + custom.toList())
                        }) { Text("Apply", fontSize = 13.sp) }
                    }
                }
            }
        }
    }
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
            // On the dialog's plain surface the hairline variant reads as
            // nearly invisible (owner feedback 2026-08-31): full outline.
            .border(1.dp, colours.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colours.onSurfaceVariant)
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
