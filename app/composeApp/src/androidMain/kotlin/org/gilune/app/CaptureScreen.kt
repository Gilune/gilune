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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
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
enum class Transcription { Absent, Pending, Done }

/// Read off kernel state and nothing else. `preview` carries the source's
/// words once a processor has landed them (ADR 0013 puts the transcript
/// in the note body), so an empty preview on a recording means no words
/// yet — and whether any are coming is the question the row must answer
/// honestly.
///
/// **Enabled is not the same as able.** The transcription plugin is
/// bundled and switched on, but its ~75 MiB model download is gated by
/// `network.request`, which always waits for an explicit yes (§ 4). Until
/// that consent is given there is no model, so nothing is transcribing
/// and saying otherwise would be the row lying about the one thing it
/// exists to report. An outstanding capability is exactly § 4's "before
/// consent" state, and its words are "needs the transcription model".
fun transcriptionState(preview: String, plugins: List<PluginInfo>): Transcription {
    if (preview.isNotBlank()) return Transcription.Done
    val transcriber = plugins.firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }
    val able = transcriber != null && transcriber.state == "enabled" && transcriber.ungranted.isEmpty()
    return if (able) Transcription.Pending else Transcription.Absent
}

/// The § 4 wording, exactly: *recorded · 0:42 · transcribing…* while a
/// job runs, *recorded · 0:42 · needs the transcription model* before
/// there is anything to run it. A recording whose header could not be
/// read says so by omitting the duration rather than inventing one.
fun voiceStatus(durationMs: Long?, transcription: Transcription): String {
    val parts = mutableListOf("recorded")
    durationMs?.let { parts += formatDuration(it) }
    when (transcription) {
        Transcription.Done -> return ""
        Transcription.Pending -> parts += "transcribing…"
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
) {
    // The feed is not on the path to a typable caret (§ 1), so it waits
    // for the frame that made the caret exist.
    var feedComposed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { feedComposed = true }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (feedComposed) CaptureFeed(state, onOpenNote)
        }
        Composer(state, draft, onDraftChange, onCommit, focusNonce, micNonce)
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
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget, max = 180.dp)
                        .focusRequester(composerFocus)
                        .testTag("composer"),
                    placeholder = { Text("What's on your mind?") },
                    // Multi-line, and the action key is a newline: commit
                    // is a decision the user makes with the tick.
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
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
                                    }
                                    .onFailure { state.message.value = "microphone unavailable: ${it.message}" }
                            } else {
                                recording = false
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
                    onClick = onCommit,
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

/// The recent-capture feed. Newest first, because the thing you just said
/// is the thing you are looking for.
@Composable
private fun CaptureFeed(state: GiluneState, onOpenNote: (String) -> Unit) {
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
            CaptureRow(state, source, player, onOpenNote)
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
                voiceStatus(duration, transcriptionState(source.preview, state.plugins))
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
