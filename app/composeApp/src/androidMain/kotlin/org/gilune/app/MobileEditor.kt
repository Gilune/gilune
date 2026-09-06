// The phone's live-preview editor (ADR 0017 § 2), behind MS1.
//
// MS1's first condition is the shape of this file: the buffer is a
// `TextFieldState` under `BasicTextField(state = …)` with an
// `OutputTransformation`, never the legacy `TextFieldValue` field, which
// was measured to stop opening composing regions after a commit and to
// accumulate bytes the keyboard did not intend. The transformation states
// its edits and Compose derives the offset mapping in both directions, so
// `DecorationOffsetMapping` has no counterpart here at all.
//
// ## The no-reflow law, and how it is delivered
//
// § 2: "revealing a marker may change its colour and never the layout".
// Two mechanisms carry it, and the split is deliberate:
//
//  1. **The output transformation changes text, and only ever the
//     checkbox** — `[ ]`→☐, on every task line including the caret's
//     (§ 2's always-glyph rule). It does not consult the caret, so the
//     offset table cannot change shape as the caret moves; that
//     invariance is the largest single reduction in the risk MS1 exists
//     to measure. It is the one length-delta decoration, and the marker's
//     own shape — bullet, space, glyph, space — keeps it delimited, which
//     is MS1's second condition.
//  2. **Styling is painted by a sibling, in lockstep.** This is forced,
//     and it is the honest gap of this stage: Compose Foundation 1.7.6's
//     `TextFieldBuffer` has no styling API — `replace` and friends and
//     nothing else — and the state-based `BasicTextField` takes one
//     uniform `TextStyle`. There is no route by which a span reaches the
//     text inside a modern field on this classpath. So the field renders
//     the transformed text transparently and a `Text` painted over it
//     carries the spans.
//
// The consequence is stated rather than hidden: **the overlay may only
// carry metric-neutral spans** — colour, background, underline and
// strikethrough — because the caret, the tap targets and the line breaks
// all come from the field's own layout, and a span that changed a glyph's
// advance would put the visible text somewhere the caret is not. Heading
// size, bold weight and italic slant are therefore NOT rendered in Edit
// (Read renders all three, and it is one tap away). Mobile answers the
// legibility half by palette instead: heading text takes the accent
// colour, so a heading still reads as one.
//
// What that buys is § 2's actual reveal rule, exactly: because contrast
// is the only thing that changes at the caret's line, revealing costs
// nothing in geometry and the transformation never has to be re-derived
// for it.

package org.gilune.app

import org.gilune.plugin.sdk.DecorationMatch
import org.gilune.plugin.sdk.DecorationRender
import org.gilune.plugin.sdk.EditorDecoration
import org.gilune.plugin.sdk.Slots
import org.gilune.plugins.tasks.TASKS_PLUGIN
import org.gilune.plugins.tasks.tasksDecorations
import org.gilune.plugins.tasks.tasksFilter

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import uniffi.gilune_ffi.PluginInfo
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate

/// Parity with the desktop's own idle (`NoteEditor.kt`, file-private
/// there). Nothing typed is ever only in memory: the buffer reaches disk
/// after a pause, and reconciliation batches separately so history
/// records edits rather than keystrokes (ADR 0012).
const val AUTOSAVE_IDLE_MS = 1200L

// ---------------------------------------------------------------- render

/// The text edits the field's output transformation performs, in document
/// order: source range (inclusive, as a [DecorationMatch] reports it) and
/// the glyph it renders as.
///
/// This mirrors the composition `MarkdownEditing.kt` reserves to the shell
/// — the caret's line is *not* filtered out (mobile's always-glyph rule),
/// fences gate the registrations that asked to be gated, and overlapping
/// claims go to the first registration in declaration order — and then
/// keeps only the length-changing ones. A `StyleOnly` claim still takes
/// its ground, so a plugin's token recogniser can never be silently
/// overruled here in a way it would not be on the desktop.
///
/// `MobileEditorTest.theOutputTransformationRendersExactlyWhatTheSharedOneDoes`
/// pins it against the shared transformation, so the two cannot drift.
fun editorGlyphEdits(
    source: String,
    decorations: List<EditorDecoration> = builtInDecorations(),
): List<Pair<IntRange, String>> {
    val edits = mutableListOf<Pair<IntRange, String>>()
    var offset = 0
    var inFence = false
    for (line in source.split('\n')) {
        val start = offset
        offset += line.length + 1
        val delimiter = line.trim().startsWith("```")
        val fenced = inFence || delimiter
        if (delimiter) inFence = !inFence

        val claimed = mutableListOf<IntRange>()
        val onLine = mutableListOf<Pair<IntRange, String>>()
        for (decoration in decorations) {
            if (fenced && !decoration.firesInsideCodeFences) continue
            for (match in decoration.recognise(line)) {
                val range = match.range
                if (range.isEmpty() || range.first < 0 || range.last >= line.length) continue
                if (claimed.any { range.first <= it.last && it.first <= range.last }) continue
                claimed += range
                val render = match.render as? DecorationRender.Replace ?: continue
                onLine += (start + range.first..start + range.last) to render.text
            }
        }
        edits += onLine.sortedBy { it.first.first }
    }
    return edits
}

/// MS1's second condition, enforced rather than assumed.
///
/// The spike measured that on the modern API the IME is shown the
/// **output-transformed** text — `getTextBeforeCursor` returns `- ☐ milk`,
/// and the caret arrives in transformed coordinates — so a length-delta
/// glyph inside a word would land inside the keyboard's own dictionary
/// lookups and suggestion spans. The verdict makes that a requirement:
/// "no length-delta decoration may sit inside a word".
///
/// ADR 0017 § 2 says the checkbox is delimited on both sides — bullet,
/// space, glyph, space — and for every task line anyone writes it is. But
/// the shared recogniser does not *require* the space after the marker
/// (`TASK_MARKER` is `[-*+][ \t]+\[[ xX]]` and stops there), so `- [ ]milk`
/// would decorate, and MS1's condition would be false by one keystroke.
/// The desktop can afford that — its IME sees the raw buffer. Mobile
/// cannot, so the mobile shell declines any replacement that is not
/// whitespace-delimited on both sides.
///
/// It is applied to the whole registry rather than to the checkbox by
/// name, so the condition holds for the next registration that renders a
/// replacement too — including a plugin's, which is precisely the case
/// nobody will remember to check. Style-only claims are untouched: they
/// change no length, so no IME span can be misled by one.
fun mobileEditorDecorations(decorations: List<EditorDecoration>): List<EditorDecoration> =
    decorations.map { decoration ->
        EditorDecoration(
            id = decoration.id,
            role = decoration.role,
            firesInsideCodeFences = decoration.firesInsideCodeFences,
            activate = decoration.activate,
            recognise = { line ->
                decoration.recognise(line).filter { match ->
                    match.render !is DecorationRender.Replace || delimitedInLine(line, match.range)
                }
            },
        )
    }

private fun delimitedInLine(line: String, range: IntRange): Boolean {
    val opensAtBoundary = range.first == 0 || line[range.first - 1].isWhitespace()
    val closesAtBoundary = range.last >= line.length - 1 || line[range.last + 1].isWhitespace()
    return opensAtBoundary && closesAtBoundary
}

/// What the field shows: the source with every glyph edit applied.
fun renderedEditorText(
    source: String,
    decorations: List<EditorDecoration> = builtInDecorations(),
): String {
    var out = source
    for ((range, glyph) in editorGlyphEdits(source, decorations).asReversed()) {
        out = out.substring(0, range.first) + glyph + out.substring(range.last + 1)
    }
    return out
}

/// The whole of the decoration on the modern API. Note what is absent:
/// no offset table, no `OffsetMapping`, and no inverse to get wrong.
///
/// Edits are applied back to front so earlier indices stay valid — the one
/// piece of index care the API still asks for.
@OptIn(ExperimentalFoundationApi::class)
class MarkdownOutputTransformation(
    private val decorations: List<EditorDecoration> = builtInDecorations(),
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        for ((range, glyph) in editorGlyphEdits(asCharSequence().toString(), decorations).asReversed()) {
            replace(range.first, range.last + 1, glyph)
        }
    }
}

/// Return on a list line continues the list (owner rule, 2026-09-01), on
/// the seam the modern API provides for exactly this: an
/// [InputTransformation] is handed the change *before* it lands, with the
/// buffer it would produce and the selection it would leave, and may edit
/// that buffer in place.
///
/// Which is why the rule is here rather than in a key handler. A soft
/// keyboard sends Return as a committed newline through the input
/// connection, not as a key event anyone can intercept, and MS1's verdict
/// forbids the alternative — rewriting the whole buffer afterwards would
/// restart the IME's composing session on the very keystroke that ends a
/// line. This edits the incoming change and nothing else: one [replace] of
/// the prefix's width, or one deletion of the empty marker.
///
/// The decision is the desktop's decision, asked of the same shared
/// function, so a list continues identically on both.
@OptIn(ExperimentalFoundationApi::class)
object ListContinuationTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (!selection.collapsed) return
        val caret = selection.start
        val text = asCharSequence()
        if (!insertedNewline(originalText, originalSelection.min, originalSelection.max, text, caret)) {
            return
        }
        when (val decision = listContinuation(text, caret)) {
            is ListContinuation.Continue -> {
                replace(caret, caret, decision.prefix)
                selection = TextRange(caret + decision.prefix.length)
            }
            is ListContinuation.Unwrap -> {
                replace(decision.range.first, decision.range.last + 1, "")
                selection = TextRange(decision.range.first)
            }
            null -> {}
        }
    }
}

/// The spans the overlay paints, over the **transformed** text.
///
/// Both palettes describe the same document; the caret's line is spliced
/// from the revealed one, which is § 2's "dimmed to bright, never absent
/// to present" with nothing else able to move. `caretLine` is a line
/// index, and the glyph substitution never adds or removes a newline, so
/// line indices mean the same thing in both coordinate systems.
///
/// Every span is passed through [metricNeutral] on the way out: see the
/// file header for why the overlay may not change a glyph's advance.
fun editorStyling(
    source: String,
    dimmed: MarkdownPalette,
    revealed: MarkdownPalette,
    caretLine: Int?,
    decorations: List<EditorDecoration> = builtInDecorations(),
): AnnotatedString {
    // The shared transformation, asked with no caret: every line
    // decorates, which is exactly § 2's always-rendered column.
    val base = metricNeutral(
        MarkdownEditTransformation(dimmed, null, decorations).filter(AnnotatedString(source)).text,
    )
    if (caretLine == null) return base
    val line = lineBoundsIn(base.text, caretLine) ?: return base
    val bright = metricNeutral(
        MarkdownEditTransformation(revealed, null, decorations).filter(AnnotatedString(source)).text,
    )
    return buildAnnotatedString {
        append(base)
        for (span in bright.spanStyles) {
            val from = maxOf(span.start, line.first)
            val to = minOf(span.end, line.second)
            if (from < to) addStyle(span.item, from, to)
        }
    }
}

/// Colour, background and decoration survive; size, weight and slant do
/// not. Nothing here can move a glyph, which is the property the overlay
/// technique rests on.
private fun metricNeutral(styled: AnnotatedString): AnnotatedString = buildAnnotatedString {
    append(styled.text)
    for (span in styled.spanStyles) {
        val neutral = SpanStyle(
            color = span.item.color,
            background = span.item.background,
            textDecoration = span.item.textDecoration,
        )
        if (neutral != EMPTY_SPAN) addStyle(neutral, span.start, span.end)
    }
}

private val EMPTY_SPAN = SpanStyle()

/// Half-open bounds of the nth line, or null when there is no such line.
private fun lineBoundsIn(text: String, line: Int): Pair<Int, Int>? {
    if (line < 0) return null
    var index = 0
    var start = 0
    while (index < line) {
        val next = text.indexOf('\n', start)
        if (next < 0) return null
        start = next + 1
        index++
    }
    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    return start to end
}

/// One tappable checkbox: where its glyph is in the **transformed** text,
/// and the source line the toggle rewrites.
private data class GlyphTarget(val transformedOffset: Int, val lineStart: Int, val lineEnd: Int)

private fun glyphTargets(source: String, decorations: List<EditorDecoration>): List<GlyphTarget> {
    var shift = 0
    return editorGlyphEdits(source, decorations).map { (range, glyph) ->
        val transformed = range.first + shift
        shift += glyph.length - (range.last + 1 - range.first)
        GlyphTarget(transformed, lineStartAt(source, range.first), lineEndAt(source, range.first))
    }
}

// ----------------------------------------------------------------- state

/// One open note. The buffer holds the **body**; the front-matter block is
/// kept verbatim beside it and written back untouched, so bytes the user
/// did not change do not change (ADR 0012, mirroring the desktop's
/// `EditorState`).
@OptIn(ExperimentalFoundationApi::class)
class MobileEditorState {
    val body = TextFieldState()

    var frontMatterRaw by mutableStateOf("")

    /// What was on disk when this buffer was read or last written — the
    /// whole file, front matter included, because that is what another
    /// writer would have changed.
    var hashAtOpen by mutableStateOf("")

    /// The body as last read or written. Dirtiness is derived from it
    /// rather than tracked, so an edit that is typed and then undone is
    /// not dirty — which is what `undoState` makes reachable.
    var savedBody by mutableStateOf("")

    /// Set when the file changed underneath an edited buffer. Until it is
    /// answered, saving would destroy whatever arrived.
    var stale by mutableStateOf(false)

    var savedHeader by mutableStateOf("")

    val dirty: Boolean get() = body.text.toString() != savedBody || frontMatterRaw != savedHeader

    fun document(): String = assembleNote(frontMatterRaw, body.text.toString())

    fun open(vaultDir: String, relPath: String) {
        val file = File(vaultDir, relPath)
        val content = if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
        val (header, text) = splitNoteRaw(content)
        frontMatterRaw = header
        body.edit {
            replace(0, length, text)
            selection = TextRange(0)
        }
        body.undoState.clearHistory()
        hashAtOpen = contentHash(content)
        savedBody = text
        savedHeader = header
        stale = false
    }

    fun applyProperties(expectedHeader: String, updatedHeader: String) {
        require(frontMatterRaw == expectedHeader) { "The properties changed while this form was open. Reopen it to review the current values." }
        frontMatterRaw = updatedHeader
    }

    /// The guarded write (ADR 0012). False means the file changed under
    /// the buffer: nothing is written, and the buffer stays dirty and
    /// stale until someone answers for it.
    fun write(vaultDir: String, relPath: String, force: Boolean = false): Boolean {
        val file = File(vaultDir, relPath)
        val document = document()
        val onDisk = if (file.exists()) runCatching { contentHash(file.readBytes()) }.getOrNull() else null
        if (!force && !mayOverwrite(hashAtOpen, onDisk)) {
            stale = true
            return false
        }
        runCatching { file.writeText(document) }.onFailure { return false }
        hashAtOpen = contentHash(document)
        savedBody = body.text.toString()
        savedHeader = frontMatterRaw
        stale = false
        return true
    }
}

// ------------------------------------------------------------------ view

/// Editing one note (ADR 0017 § 2 and § 3). The title renames the file
/// when it is committed — the keyboard's Done key, or losing the focus;
/// the body is the live-preview buffer; the accessory row and its panel
/// are anchored beneath, outliving the keyboard.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditScreen(
    state: GiluneState,
    relPath: String,
    onRenamed: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    // The buffer is a parameter for the reason the desktop's `NoteEditor`
    // takes an `EditorState`: a test that asks where the caret went after
    // a tap has to be holding the thing that knows.
    editor: MobileEditorState = remember(relPath) { MobileEditorState() },
    transcription: TranscriptionRuntime = remember { TranscriptionRuntime() },
) {
    LaunchedEffect(relPath) { editor.open(state.vaultDir, relPath) }

    // The editor seams (ADR 0015 § 5), gated exactly as the desktop gates
    // them: switch the tasks plugin off and its date styling and its `/`
    // menu go, changing no byte either way.
    val plugins = state.plugins.toList()
    val filter = plugins.tasksFilter()
    val decorations = remember(plugins, filter) {
        mobileEditorDecorations(
            if (plugins.pluginContributes(TASKS_PLUGIN, Slots.EDITOR_DECORATIONS)) {
                builtInDecorations() + tasksDecorations(filter)
            } else {
                builtInDecorations()
            },
        )
    }
    val fields = remember(plugins) {
        if (plugins.pluginContributes(TASKS_PLUGIN, Slots.EDITOR_SUGGEST)) taskFields(plugins) else emptyList()
    }

    fun save(force: Boolean = false) {
        if (!editor.dirty && !force) return
        if (editor.write(state.vaultDir, relPath, force)) {
            state.noteSaved(relPath)
        } else {
            state.message.value = "$relPath changed on disk; choose which version to keep"
        }
    }

    var propertiesOpen by remember(relPath) { mutableStateOf(false) }
    val bodyText = editor.body.text.toString()

    // Idle autosave, the desktop's cadence exactly.
    LaunchedEffect(bodyText, editor.frontMatterRaw, relPath) {
        if (!editor.dirty) return@LaunchedEffect
        delay(AUTOSAVE_IDLE_MS)
        save()
    }
    // A clean buffer follows the file; a dirty one is protected instead.
    LaunchedEffect(state.vaultCursor.value, relPath) {
        val file = File(state.vaultDir, relPath)
        val current = if (file.exists()) runCatching { contentHash(file.readBytes()) }.getOrNull() else null
        if (current == null || current == editor.hashAtOpen) return@LaunchedEffect
        if (editor.dirty) editor.stale = true else editor.open(state.vaultDir, relPath)
    }
    // Leaving is a save, like the desktop's leave-a-tab.
    DisposableEffect(relPath) {
        onDispose { if (editor.dirty) editor.write(state.vaultDir, relPath) }
    }

    // The `/` menu belongs to this note; leaving it drops it. Dismissal
    // costs the buffer nothing, because a session never wrote to it.
    var session by remember(relPath) { mutableStateOf<SuggestSession?>(null) }
    var bodyFocused by remember(relPath) { mutableStateOf(false) }
    val selection = editor.body.selection
    LaunchedEffect(bodyText, selection, fields) {
        val live = session
        session = when {
            live != null -> live.updateAt(bodyText, selection.start)
            fields.isEmpty() -> null
            !selection.collapsed -> null
            editorCaretInsideFence(bodyText, selection.start) -> null
            else -> SuggestSession.openAt(bodyText, selection.start, fields, filter, LocalDate.now())
        }
    }

    // The editor paints its own ground. The shell already wraps every
    // screen in a `Surface`, so this changes nothing there — but the
    // buffer's text is painted by a sibling over a transparent field, and
    // a screen that inherited a white window background would render
    // light text on it. Dark mode is not something to leave to a parent.
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (editor.stale) {
            StaleBanner(
                onKeepMine = { save(force = true) },
                onReload = { editor.open(state.vaultDir, relPath) },
            )
        }
        // Dictation is a note action, not a row slot (§ 3), so it lives
        // here rather than in the accessory row: `note.actions` already
        // gates it, and removing the transcription plugin takes the
        // button rather than leaving a dead key in a fixed row.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { propertiesOpen = true }, modifier = Modifier.testTag("open-properties")) { Text("Properties") }
            NoteActions(state = state, editor = editor, transcription = transcription)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            NoteTitleField(
                relPath = relPath,
                onCommit = { wanted ->
                    // The title renames the file, taking the note's
                    // attachments with it (VaultFiles.renameNote). Save
                    // first: the bytes belong to the old name until they
                    // are written, and a rename that outran them would
                    // leave the edit behind under a name nothing opens.
                    if (editor.dirty) editor.write(state.vaultDir, relPath)
                    val renamed = renameNote(state.vaultDir, relPath, wanted)
                    if (renamed != null && renamed != relPath) {
                        state.noteSaved(renamed)
                        onRenamed(renamed)
                    } else if (renamed == null) {
                        state.message.value = "could not rename to “$wanted”"
                    }
                },
            )
            EditorBuffer(
                editor = editor,
                decorations = decorations,
                onFocusChanged = { focused ->
                    bodyFocused = focused
                    if (!focused) session = null
                },
                onToggled = { save(force = false) },
                caretLine = if (bodyFocused) caretLine(bodyText, selection.start) else null,
            )
        }
        EditorToolbar(
            state = editor.body,
            decorations = decorations,
            session = session,
            onSession = { session = it },
            onAction = { save() },
        )
    }
    if (propertiesOpen) SourcePropertiesDialog(editor.document(), { propertiesOpen = false }, editor::applyProperties)

}

/// Should the editor draw a dictation mic at all (ADR 0017 § 3, § 4)?
///
/// Two conditions, and the second is the ADR's line rather than a
/// refinement of the first: **"never a mic that produces nothing"**.
///
///  - A plugin must contribute the action. That is the shared gate every
///    slot uses, so removing the voice adapter removes the button.
///  - A transcriber must actually be able to run. Dictation is not
///    recording — an attached capture that nobody transcribes produces no
///    words to insert, so the button would be a control that does
///    nothing. Recording a *note* still works in that state, from the
///    capture screen, which is the honest place for audio with no
///    transcript.
///
/// Kept pure and separate from the composable so the matrix is testable
/// without a microphone.
fun dictationOffered(plugins: List<PluginInfo>, phase: TranscriptionPhase): Boolean =
    plugins.contributes(Slots.NOTE_ACTIONS, DICTATE_ACTION) && phase == TranscriptionPhase.Ready

/// The note action row above the buffer. One action in v1 — the mic —
/// and the row is absent entirely when nothing fills it, which is
/// ADR 0011's "renders nothing while empty" applied to a slot rather than
/// a region.
@Composable
private fun NoteActions(
    state: GiluneState,
    editor: MobileEditorState,
    transcription: TranscriptionRuntime,
) {
    val colours = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (!dictationOffered(state.plugins.toList(), transcription.phase)) return

    var recording by remember { mutableStateOf(false) }
    // **No live caption here, deliberately** (ADR 0018 § 1, R1). The tee
    // that feeds the caption lives on this same recorder, so wiring one up
    // would be three lines — but the caption is a block of text that
    // appears and disappears, and the thing under it here is a *buffer
    // with a caret in it*. § 1 exempts the caption from ADR 0017 § 2's
    // no-reflow law on the grounds that "a caption with no caret in it
    // costs nobody a keystroke"; above a live editor that reasoning does
    // not hold, and § 1 names the capture surface — and only it — as where
    // the caption is rendered. Dictation with a caption is a real want and
    // it needs a decided answer about what moves when it appears; that is
    // a design question, not an oversight.
    val recorder = remember { AndroidAudioRecorder() }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askForMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted) state.message.value = "the microphone permission was declined"
    }

    /// The words arrive on the main thread from `dictate`'s callback and
    /// land through the one write path the editor has. The caret is read
    /// **now**, not when recording started: a user who tapped elsewhere
    /// while speaking means the new place.
    fun insert(words: String) {
        val text = editor.body.text.toString()
        val edit = dictationEdit(text, editor.body.selection, words)
        if (edit == null) {
            state.message.value = "no words recognised"
            return
        }
        editor.body.applyEdit(edit)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("note-actions"),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                if (!micGranted) {
                    askForMic.launch(Manifest.permission.RECORD_AUDIO)
                } else if (!recording) {
                    runCatching { recorder.start() }
                        .onSuccess {
                            recording = true
                            state.message.value = "dictating… tap the microphone to stop"
                        }
                        .onFailure { state.message.value = "microphone unavailable: ${it.message}" }
                } else {
                    recording = false
                    // `dictate` commits an attached capture — durable,
                    // transcribed in the kernel, never a note of its own
                    // — and hands the words back. A crash between the two
                    // loses nothing: the audio and its transcript stay in
                    // the kernel (ADR 0013).
                    // `dictate` calls back on its IO dispatcher; the
                    // buffer is edited on the composition's, which is
                    // the main thread.
                    state.dictate(recorder.stop()) { words -> scope.launch { insert(words) } }
                }
            },
            enabled = state.gilune.value != null,
            modifier = Modifier.size(TouchTarget).testTag("dictate"),
        ) {
            Icon(
                if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic,
                contentDescription = if (recording) "stop dictating" else "dictate at the cursor",
                tint = if (recording) colours.error else colours.onSurfaceVariant,
            )
        }
    }
}

/// The buffer proper: the field, the styling painted over it, and the
/// checkbox hit boxes arbitrated above both.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorBuffer(
    editor: MobileEditorState,
    decorations: List<EditorDecoration>,
    onFocusChanged: (Boolean) -> Unit,
    onToggled: () -> Unit,
    caretLine: Int?,
) {
    val colours = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val source = editor.body.text.toString()

    // § 2's two palettes. Dimmed is where markers live; revealed is the
    // full `onSurfaceVariant` contrast they take on the caret's line.
    // Heading takes the accent because the overlay may not change type
    // size — see the file header.
    val dimmed = remember(colours) {
        MarkdownPalette(
            muted = colours.outline,
            marker = colours.primary,
            heading = colours.primary,
            code = colours.primary,
            codeBackground = colours.surfaceVariant,
            link = colours.primary,
        )
    }
    val revealed = remember(dimmed, colours) { dimmed.copy(muted = colours.onSurfaceVariant) }

    val base = MaterialTheme.typography.bodyLarge.copy(color = colours.onSurface)
    val transformation = remember(decorations) { MarkdownOutputTransformation(decorations) }
    val styled = remember(source, dimmed, revealed, caretLine, decorations) {
        editorStyling(source, dimmed, revealed, caretLine, decorations)
    }
    val targets = remember(source, decorations) { glyphTargets(source, decorations) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val target = with(density) { TouchTarget.toPx() }

    /// § 2's checkbox tap. A tap inside the glyph's 48dp box toggles and
    /// **the caret does not move**; an unfocused field stays unfocused and
    /// the keyboard does not appear, because ticking a box is not editing.
    /// Everything else falls through to the field, which places the caret.
    fun toggle(hit: GlyphTarget) {
        val text = editor.body.text.toString()
        val end = hit.lineEnd.coerceAtMost(text.length)
        val start = hit.lineStart.coerceAtMost(end)
        val rewritten = activateLine(text.substring(start, end), decorations) ?: return
        val held = editor.body.selection
        editor.body.edit {
            replace(start, end, rewritten)
            // The rewrite may change the line's length once a
            // registration stamps rather than flips; the caret stays
            // where it was and is only kept in bounds.
            selection = TextRange(
                held.start.coerceIn(0, length),
                held.end.coerceIn(0, length),
            )
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        // Toggling writes at the toggle: a tap is an action, not typing,
        // so it does not wait out the autosave idle.
        onToggled()
    }

    Box(Modifier.fillMaxWidth()) {
        BasicTextField(
            state = editor.body,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 240.dp)
                .testTag("note-body")
                .onFocusChanged { onFocusChanged(it.isFocused) },
            // Transparent, because the sibling below paints the glyphs.
            // The field still owns the layout, the caret, the selection
            // highlight and every hit test.
            textStyle = base.copy(color = Color.Transparent),
            lineLimits = TextFieldLineLimits.MultiLine(),
            cursorBrush = SolidColor(colours.primary),
            // Return continues a list. It edits the change on its way in,
            // which is why it is here and not in the output transformation
            // beside it: one changes bytes and the other may never.
            inputTransformation = ListContinuationTransformation,
            outputTransformation = transformation,
            onTextLayout = { result -> layout = result() },
        )
        // The painted text. Same string, same style, same width, so its
        // layout is the field's layout; only colour differs, and colour
        // cannot move a glyph. Semantics are cleared so the note reads as
        // one node rather than two.
        BasicText(
            text = styled,
            style = base,
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { }
                .testTag("note-body-paint"),
        )
        // Arbitrated above the field so the toggle consumes the tap
        // first (§ 2).
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(targets, layout, target) {
                    awaitEachGesture {
                        val placed = layout ?: return@awaitEachGesture
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val hit = targets.firstOrNull { glyph ->
                            hitBox(placed, glyph.transformedOffset, target).contains(down.position)
                        } ?: return@awaitEachGesture
                        down.consume()
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                            }
                            change.consume()
                            if (change.changedToUpIgnoreConsumed()) {
                                if (!moved) toggle(hit)
                                break
                            }
                        }
                    }
                },
        )
    }
}

/// A 48dp square centred on the glyph — Android's minimum, where a glyph
/// at body size is far under it (§ 2, constraints T4).
private fun hitBox(layout: TextLayoutResult, offset: Int, target: Float): Rect {
    val box = runCatching { layout.getBoundingBox(offset) }.getOrNull() ?: return Rect.Zero
    val half = target / 2f
    return Rect(
        box.center.x - half,
        box.center.y - half,
        box.center.x + half,
        box.center.y + half,
    )
}

/// The note's name, edited in place and committed on blur — the desktop's
/// title-field contract, which renames the file rather than writing a
/// heading into the document.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteTitleField(relPath: String, onCommit: (String) -> Unit) {
    val colours = MaterialTheme.colorScheme
    val title = remember(relPath) { TextFieldState(noteTitle(relPath)) }
    var focused by remember(relPath) { mutableStateOf(false) }

    /// Committing twice is free: the second call finds the name already
    /// equal to the file's and does nothing. That is what lets Done and
    /// blur both be safe, since Done's default action clears the focus.
    fun commit() {
        val wanted = title.text.toString().trim()
        if (wanted.isNotEmpty() && wanted != noteTitle(relPath)) onCommit(wanted)
    }

    BasicTextField(
        state = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .testTag("note-title")
            .onFocusChanged { focus ->
                val had = focused
                focused = focus.isFocused
                if (had && !focus.isFocused) commit()
            },
        textStyle = MaterialTheme.typography.titleLarge.copy(color = colours.onSurface),
        lineLimits = TextFieldLineLimits.SingleLine,
        // A phone has no Tab and no click-away that is certain to happen,
        // so the keyboard's own Done key is the title's commit gesture —
        // blur alone would leave a renamed title uncommitted if the user
        // went straight back.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = KeyboardActionHandler { performDefault ->
            commit()
            performDefault()
        },
        cursorBrush = SolidColor(colours.primary),
    )
}

/// The file changed under an edited buffer. Neither answer is taken for
/// the user: keeping the buffer overwrites, reloading discards it, and
/// until one is chosen nothing is written (ADR 0012).
@Composable
private fun StaleBanner(onKeepMine: () -> Unit, onReload: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("stale-banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "This note changed on disk.",
            style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReload) { Text("Reload") }
        TextButton(onClick = onKeepMine) { Text("Keep mine") }
    }
}

/// ADR 0015 § 1's fence rule, asked here because the `/` menu's machine is
/// line-scoped: [SuggestSession] sees one line and cannot know it sits
/// inside a ``` block, so withholding the call is the caller's job.
/// Mirrors `NoteEditor.kt`'s `caretIsInsideCodeFence`, which is internal
/// to the desktop source set.
internal fun editorCaretInsideFence(text: String, caret: Int): Boolean {
    val bounded = caret.coerceIn(0, text.length)
    val lines = text.substring(0, bounded).split('\n')
    var open = false
    for (index in 0 until lines.size - 1) {
        if (lines[index].trim().startsWith("```")) open = !open
    }
    return open
}
