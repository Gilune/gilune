// Home is capture (ADR 0017 § 1). The app opens on this: a composer
// pinned above the keyboard with the voice action beside it, and the
// recent-capture feed above.
//
// Nothing else is on the path to the first frame. The field is typable
// before the vault is open — committing needs the kernel, typing does
// not — and the feed composes only after the first frame has been drawn.

package org.gilune.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.SourceSummary
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/// Is this capture a recording? **Every** source has a blob — a typed
/// thought's bytes are a blob too — so having one is not the question;
/// what the preserved bytes *are* is. Only audio gets a play button and
/// the § 4 pending wording.
fun isRecording(blobRelPath: String?): Boolean =
    blobRelPath != null && AUDIO_EXTENSIONS.any { blobRelPath.endsWith(it, ignoreCase = true) }

private val AUDIO_EXTENSIONS = listOf(".wav", ".m4a", ".mp3", ".ogg", ".opus")

/// What the kernel knows about a recording's words, which is all the row
/// is allowed to claim (§ 4: "the pending state is shown and is never
/// written to the file").
enum class Transcription { Absent, Downloading, Pending, Silent, Done }

/// Read off kernel state and nothing else. `preview` carries the source's
/// words once a processor has landed them (ADR 0013 puts the transcript
/// in the note body), so an empty preview on a recording means no words
/// yet — and whether any are coming is the question the row must answer
/// honestly.
///
/// **Enabled is not the same as able, and consented is not the same as
/// able either.** The transcription plugin is bundled and switched on,
/// but its model download is gated by `network.request`, which always
/// waits for an explicit yes (§ 4) — and even after that yes there is a
/// stretch of tens of megabytes during which nothing can run. So the
/// question is asked at the only level that answers it: has
/// `enableWhisper` succeeded on this handle ([TranscriptionPhase.Ready])?
/// Nothing short of that may render "transcribing…", because a job that
/// cannot run is not running.
/// **`interpreted` is the kernel's answer to "is anything still coming?"**
/// — a processor has finished a run over this source (see
/// `SourceSummary.interpreted`). Without it an empty body was read as *not
/// yet*, and a recording of silence said "transcribing…" for ever: the job
/// was done, the run recorded, and nothing was ever going to change. It is
/// a recorded fact rather than a timeout, so the row never guesses, and it
/// is asked of the run rather than of the words because one engine writes
/// an empty transcript for silence and the other writes nothing at all.
///
/// Asked **before** the plugin questions, deliberately: work that has
/// already happened is not undone by switching a transcriber off
/// afterwards, and a row that then asked for a model would be asking for
/// something it does not need.
fun transcriptionState(
    preview: String,
    plugins: List<PluginInfo>,
    phase: TranscriptionPhase,
    interpreted: Boolean = false,
): Transcription {
    if (preview.isNotBlank()) return Transcription.Done
    if (interpreted) return Transcription.Silent
    val transcriber = plugins.firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }
    val switchedOn = transcriber != null && transcriber.state == "enabled"
    if (!switchedOn) return Transcription.Absent
    return when (phase) {
        TranscriptionPhase.Ready -> Transcription.Pending
        TranscriptionPhase.Downloading -> Transcription.Downloading
        else -> Transcription.Absent
    }
}

/// The § 4 wording, exactly: *recorded · 0:42 · transcribing…* while a
/// job runs, *recorded · 0:42 · needs the transcription model* before
/// there is anything to run it. A recording whose header could not be
/// read says so by omitting the duration rather than inventing one.
///
/// The third line is this shell's addition and it is deliberate. § 4
/// names two states because it is describing a decision, not enumerating
/// a UI; between the yes and the model there is a real third situation
/// lasting tens of megabytes, and calling it "needs the transcription
/// model" when the model is at that moment arriving would be the row
/// lying in the same way the other two wordings exist to prevent.
fun voiceStatus(durationMs: Long?, transcription: Transcription): String {
    val parts = mutableListOf("recorded")
    durationMs?.let { parts += formatDuration(it) }
    when (transcription) {
        Transcription.Done -> return ""
        Transcription.Pending -> parts += "transcribing…"
        Transcription.Downloading -> parts += "downloading the transcription model…"
        // The fourth line, and the owner's words. A transcriber has been
        // over this recording and found no speech in it — so the empty
        // body is the answer rather than the wait. The recording is still
        // there and still plays: nothing was lost, nothing is coming.
        Transcription.Silent -> parts += "no speech heard"
        Transcription.Absent -> parts += "needs the transcription model"
    }
    return parts.joinToString(" · ")
}

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")

fun captureStamp(createdMs: Long): String =
    STAMP.format(Instant.ofEpochMilli(createdMs).atZone(ZoneId.systemDefault()))

/// The first line of a captured thought, which is how a text capture
/// announces itself in a list.
fun firstLine(preview: String): String =
    preview.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/// Letting go of the composer: focus off, keyboard down, nothing written.
///
/// **Both halves, always.** Hiding the keyboard while the field keeps
/// focus leaves the window still reporting an IME — which is what the
/// bottom bar reads to decide it should stand aside (`MainActivity`,
/// § 1's mode-swapped bottom) — and clearing focus alone leaves it to the
/// platform whether the keyboard follows. Doing one without the other is
/// how the bar goes missing, so this is one function and there is no
/// second copy of the pair.
///
/// One definition and two callers, deliberately: the tick and the
/// dismiss control here, and the idle timer's commit in the activity,
/// which reaches the same composer from outside it.
@Composable
fun rememberComposerDismiss(): () -> Unit {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(focus, keyboard) {
        {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
}

@Composable
fun CaptureScreen(
    state: GiluneState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusNonce: Int = 0,
    micNonce: Int = 0,
    transcription: TranscriptionRuntime = remember { TranscriptionRuntime() },
    moonshine: MoonshineRuntime = remember { MoonshineRuntime() },
) {
    // The feed is not on the path to a typable caret (§ 1), so it waits
    // for the frame that made the caret exist.
    var feedComposed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { feedComposed = true }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (feedComposed) CaptureFeed(state, onOpenNote, transcription)
        }
        Composer(state, draft, onDraftChange, onCommit, focusNonce, micNonce, moonshine)
    }
}

/// The composer. Multi-line, because a thought is not a search box: the
/// IME's action key inserts a newline and commit is the ✓ button beside
/// it, never Enter (§ 1).
@Composable
private fun Composer(
    state: GiluneState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    focusNonce: Int = 0,
    micNonce: Int = 0,
    moonshine: MoonshineRuntime = remember { MoonshineRuntime() },
) {
    val colours = MaterialTheme.colorScheme
    val context = LocalContext.current
    val ready = state.gilune.value != null
    var recording by remember { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val recorder = remember { AndroidAudioRecorder() }
    val askForMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted) state.message.value = "the microphone permission was declined"
    }

    // ------------------------------------------------- the live caption
    //
    // ADR 0018 § 1, and every line of it is a rule rather than a
    // preference. The caption is display state: nothing here writes
    // anywhere, and the three lines below are the whole of its lifecycle —
    // it exists while a recording does and not one moment longer, which is
    // both the battery courtesy § 1 asks for and the reason a stopped
    // recording cannot leave words on screen implying they were kept.
    var caption by remember { mutableStateOf(CaptionDisplay.EMPTY) }
    var session by remember { mutableStateOf<MoonshineSession?>(null) }
    val captionOn = captionOffered(state.plugins.toList(), moonshine.ready)

    fun endCaption() {
        recorder.tee = null
        session?.stop()
        session = null
        caption = CaptionDisplay.EMPTY
    }

    fun beginCaption() {
        val dir = moonshine.modelDir
        if (!captionOn || dir == null) return
        val opened = MoonshineSession(
            modelDir = dir,
            arch = moonshine.model?.arch ?: MOONSHINE_ARCH_TINY_STREAMING,
            onDisplay = { caption = it },
            // A caption that dies goes quiet — the tee comes off, the
            // block stops being drawn, and nothing is said about it on the
            // composer's notice line, because the recording is fine and
            // the person is mid-sentence.
            onFailure = {
                recorder.tee = null
                caption = CaptionDisplay.EMPTY
                session = null
            },
        )
        // Even starting is wrapped: the recording has already begun, and
        // nothing about the caption may reach back into it.
        runCatching { opened.start() }
            .onSuccess {
                session = opened
                recorder.tee = { bytes, length -> opened.feed(bytes, length) }
            }
    }

    // Leaving the screen mid-recording must not leave a native session
    // running. The recorder's own lifecycle is unchanged — this disposes
    // only the caption.
    DisposableEffect(Unit) { onDispose { endCaption() } }

    // The widget's Record cell before the microphone has been granted
    // (§ 4). A widget cannot ask, so it sent the ask here; a yes starts
    // the same foreground service the widget would have started, so there
    // is one recording path and not two. The notification permission is
    // asked for in the same breath, because the recording notification is
    // mandatory and a hidden one helps nobody.
    val askAndRecord = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        micGranted = granted[Manifest.permission.RECORD_AUDIO] ?: micGranted
        if (micGranted) {
            ContextCompat.startForegroundService(context, RecordService.startIntent(context, SOURCE_APP))
        } else {
            state.message.value = "the microphone permission was declined"
        }
    }

    val composerFocus = remember { FocusRequester() }
    val dismiss = rememberComposerDismiss()

    // ------------------------------------------------- the caret's owner
    //
    // The draft is a `String` because that is all the shell has an opinion
    // about; the **caret** is the field's, and it stays the field's for
    // every keystroke — this is the same projection Compose's own
    // `String` overload performs, with one addition.
    //
    // The addition: a draft that arrives from **outside** the field — the
    // agenda seeding `- [ ] `, a commit clearing it — lands with the caret
    // at its end. Compose's overload leaves the caret exactly where it was,
    // which for a field nobody had touched is offset zero, so a seeded
    // marker would put the caret in front of it and the next thing typed
    // would not be a task at all.
    //
    // Nothing here changes which field implementation is used (MS1
    // condition 3): it is the same legacy field the composer has always
    // been, and the app never writes a selection the field did not report,
    // except on that outside replacement.
    // Both ends of that rule, and the first one is not decoration: the
    // composer is composed **fresh** when the shell arrives at it, so a
    // seeded draft is here on the very first composition and never takes
    // the replacement branch at all. Measured, not assumed — the caret sat
    // at offset 0 in front of a `- [ ] ` this branch had never seen.
    val buffer = remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    val field = if (buffer.value.text == draft) {
        buffer.value
    } else {
        TextFieldValue(draft, TextRange(draft.length))
    }
    SideEffect { buffer.value = field }
    // Whether the composer is the thing being typed into. It is what the
    // dismiss control is drawn for: a way out that is on the screen,
    // rather than the system back gesture being the only one.
    var composerFocused by remember { mutableStateOf(false) }
    LaunchedEffect(focusNonce) {
        if (focusNonce > 0) runCatching { composerFocus.requestFocus() }
    }
    LaunchedEffect(micNonce) {
        if (micNonce == 0) return@LaunchedEffect
        if (micGranted) {
            ContextCompat.startForegroundService(context, RecordService.startIntent(context, SOURCE_APP))
        } else {
            askAndRecord.launch(recordingPermissions())
        }
    }
    // The shared gate (ADR 0011): the mic is drawn only while a plugin
    // contributes it, so removing the voice adapter removes the button.
    val micOffered = state.plugins.contributes(Slots.CAPTURE_ACTIONS, RECORD_VOICE_ACTION)

    Surface(color = colours.surfaceContainerLow, tonalElevation = 2.dp) {
        Column {
            HorizontalDivider(color = colours.outlineVariant)
            // Above the composer, below nothing (§ 1).
            //
            // Drawn for the WHOLE recording rather than from the first
            // word, and that is the calmer of the two: the block's three
            // lines are reserved by the tap that started recording, so the
            // one moment the composer moves is a moment the person caused.
            // Waiting for words would move it again at whatever
            // unpredictable instant the engine first spoke. It goes when a
            // session dies, which is the only unannounced shift left and
            // is a failure path.
            if (recording && session != null) CaptionBlock(caption)
            // The shared status line, minus the two lines that are only
            // ever a developer's: where the vault is and that it is
            // opening. Settings ▸ Vault says the first plainly and the
            // phone's answer never changes, so above the composer it is
            // clutter where "captured" and "recording…" are the point.
            val notice = state.message.value.takeUnless {
                it.isBlank() || it.startsWith("vault: ") || it == "opening vault…"
            }
            if (notice != null) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = colours.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { typed ->
                        buffer.value = typed
                        if (typed.text != draft) onDraftChange(typed.text)
                    },
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget, max = 180.dp)
                        .focusRequester(composerFocus)
                        .onFocusChanged { composerFocused = it.isFocused }
                        .testTag("composer"),
                    placeholder = { Text("What's on your mind?") },
                    // Multi-line, and the action key is a newline: commit
                    // is a decision the user makes with the tick.
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                // A way out that is on the screen. While the keyboard is
                // up the bottom bar stands aside (§ 1), so without this
                // the only exit from a focused composer is the system
                // back gesture — which is not an affordance, it is a
                // thing you have to know. Drawn only while the composer
                // has focus, because at any other moment it would be a
                // button that does nothing.
                //
                // The same glyph and the same name as the editor's, so
                // the gesture is one gesture wherever the keyboard is up.
                if (composerFocused) {
                    IconButton(
                        onClick = dismiss,
                        modifier = Modifier.size(TouchTarget).testTag("composer-dismiss"),
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardHide,
                            contentDescription = "dismiss-keyboard",
                            tint = colours.onSurfaceVariant,
                        )
                    }
                }
                if (micOffered) {
                    IconButton(
                        onClick = {
                            if (!micGranted) {
                                askForMic.launch(Manifest.permission.RECORD_AUDIO)
                            } else if (!recording) {
                                runCatching { recorder.start() }
                                    .onSuccess {
                                        recording = true
                                        state.message.value = "recording… tap the microphone to stop"
                                        // After the recording is running,
                                        // never before it: the capture is
                                        // what the tap promised.
                                        beginCaption()
                                    }
                                    .onFailure { state.message.value = "microphone unavailable: ${it.message}" }
                            } else {
                                recording = false
                                // § 1: "on stop the caption is cleared and
                                // replaced by the note's pending state" —
                                // which is the notice line above, already
                                // saying "voice captured… transcribing…".
                                // Nothing on screen may imply the caption's
                                // words were the ones kept.
                                endCaption()
                                state.captureAudio(recorder.stop())
                            }
                        },
                        enabled = ready,
                        modifier = Modifier.size(TouchTarget).testTag("mic"),
                    ) {
                        Icon(
                            if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic,
                            contentDescription = if (recording) "stop recording" else "record voice",
                            tint = if (recording) colours.error else colours.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    // **Committing lets go of the composer.** The thought
                    // is captured; keeping the caret and the keyboard
                    // afterwards left the bottom bar hidden with nothing
                    // on screen to bring it back.
                    //
                    // The blank case is stated here rather than left to
                    // the `enabled` flag alone: a tick with nothing to
                    // commit captures nothing **and takes nothing away**,
                    // so somebody who taps it mid-thought keeps their
                    // keyboard.
                    onClick = {
                        if (draft.isNotBlank()) {
                            onCommit()
                            dismiss()
                        }
                    },
                    enabled = ready && draft.isNotBlank(),
                    modifier = Modifier.size(TouchTarget).testTag("commit"),
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "capture",
                        tint = if (ready && draft.isNotBlank()) colours.primary else colours.outline,
                    )
                }
            }
        }
    }
}

/// The live caption (ADR 0018 § 1). Words while you speak, and nothing
/// else: **no caret, no selection, no tap target, and not a text field.**
///
/// Three decisions are load-bearing and all three come from R0's measured
/// event trace rather than from taste.
///
///  - **Fixed height, bottom-anchored, clipped.** The block reserves its
///    three lines when the recording starts and never resizes again, so
///    nothing above it moves for the whole recording. Growth is upward
///    into the reserved space and older lines are clipped off the top.
///    ADR 0018 § 1 exempts the caption from ADR 0017 § 2's no-reflow law —
///    a caption has no caret to jump — but the composer *underneath* it
///    does, and reserving the space is what keeps that promise anyway.
///  - **The whole revisable region is muted, not just a tail.** R0
///    recorded "fox can" becoming "fox jumps up so" and "lace of the"
///    becoming "lazy dog." — mid-phrase rewrites, not refinements. There
///    is no stable prefix to promote into the settled role, so nothing is.
///  - **Settled lines never move.** `LineCompleted` freezes a line, R0
///    asserted it for every line, and `foldCaption` enforces it here too.
@Composable
fun CaptionBlock(display: CaptionDisplay, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(CaptionHeight)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clipToBounds()
            .testTag("caption"),
        contentAlignment = Alignment.BottomStart,
    ) {
        // `unbounded` is what makes this actually grow upward. Without it
        // the column is measured against the block's own height and
        // overflows DOWNWARDS — the oldest words stay pinned to the top
        // and the words being spoken fall out of the clip, which is the
        // exact opposite of § 1 and is what the first build did. Measuring
        // unbounded and aligning to the bottom puts the newest line
        // against the bottom edge and scrolls the oldest off the top.
        Column(
            Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true),
            verticalArrangement = Arrangement.Bottom,
        ) {
            for (line in display.settled) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colours.onSurface,
                    maxLines = CAPTION_LINES,
                )
            }
            if (display.live.isNotBlank()) {
                Text(
                    display.live,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colours.onSurfaceVariant,
                    maxLines = CAPTION_LINES,
                    modifier = Modifier.testTag("caption-live"),
                )
            }
        }
    }
}

/// Three lines of `bodyMedium` plus its padding. A constant rather than a
/// wrap, because the whole point is that it does not change.
private val CaptionHeight = 68.dp

/// The recent-capture feed. Newest first, because the thing you just said
/// is the thing you are looking for.
@Composable
private fun CaptureFeed(
    state: GiluneState,
    onOpenNote: (String) -> Unit,
    transcription: TranscriptionRuntime,
) {
    val colours = MaterialTheme.colorScheme
    val player = remember { AudioPlayback() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    if (state.recent.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing captured yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("feed"),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(state.recent, key = { it.objectId }) { source ->
            CaptureRow(state, source, player, onOpenNote, transcription)
            HorizontalDivider(color = colours.outlineVariant)
        }
    }
}

@Composable
private fun CaptureRow(
    state: GiluneState,
    source: SourceSummary,
    player: AudioPlayback,
    onOpenNote: (String) -> Unit,
    transcription: TranscriptionRuntime,
) {
    val colours = MaterialTheme.colorScheme
    val recording = source.blobRelPath?.takeIf { isRecording(it) }
    val words = firstLine(source.preview)
    val open = source.projectionRelPath

    Row(
        Modifier
            .fillMaxWidth()
            .let { base -> if (open == null) base else base.clickableRow { onOpenNote(open) } }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (recording != null) {
            val playing = player.playing == source.objectId
            IconButton(
                onClick = { player.toggle(source.objectId, File(state.vaultDir, recording)) },
                modifier = Modifier.size(TouchTarget),
            ) {
                Icon(
                    if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "stop playback" else "play recording",
                    tint = colours.primary,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            if (words.isNotEmpty()) {
                Text(words, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
            }
            val status = if (recording == null) {
                ""
            } else {
                val duration = remember(recording) { readWavDuration(File(state.vaultDir, recording)) }
                voiceStatus(
                    duration,
                    transcriptionState(
                        source.preview,
                        state.plugins,
                        transcription.phase,
                        source.interpreted,
                    ),
                )
            }
            Text(
                listOf(status, captureStamp(source.createdMs)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
            )
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClickLabel = "open note", onClick = onClick)

/// The header is all that is needed, and a recording can be long, so only
/// the front of the file is read. The file's own length goes with it, so
/// a widget-streamed recording — whose header cannot know its size —
/// still shows a true duration rather than none.
private fun readWavDuration(file: File): Long? = runCatching {
    if (!file.isFile) return null
    file.inputStream().use { stream ->
        val head = ByteArray(WAV_HEADER_PROBE)
        val read = stream.read(head)
        if (read <= 0) null else wavDurationMs(head.copyOf(read), file.length())
    }
}.getOrNull()

private const val WAV_HEADER_PROBE = 128

/// One player for the whole feed: audio plays in the app because the
/// recording is the preserved source and stays useful whatever the
/// transcript says (§ 4).
private class AudioPlayback {
    var playing: String? by mutableStateOf(null)
        private set

    private var player: MediaPlayer? = null

    fun toggle(objectId: String, file: File) {
        if (playing == objectId) {
            stop()
            return
        }
        stop()
        if (!file.isFile) return
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.onSuccess {
            player = it
            playing = objectId
        }
    }

    fun stop() {
        player?.runCatching { if (isPlaying) stop() }
        player?.release()
        player = null
        playing = null
    }

    fun release() = stop()
}
