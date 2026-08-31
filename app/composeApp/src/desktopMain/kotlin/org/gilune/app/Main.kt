// Desktop entry point and window scaffold (ADR 0011, amended
// 2026-08-31): top bar with quick-open and the speech microphone, rail
// with Files and Search panels, document pane. Capture-first is a pair
// of gestures now, not a region: Ctrl+N makes an Untitled note ready to
// type, the microphone makes a note from speech. Settings is a surface
// over the window, not a dialog, because it holds the plugin panel and
// will hold devices next.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Mic
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.gilune_ffi.Gilune
import java.io.File

private const val MODEL_URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"

private fun modelFile(): File =
    File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
        "Gilune/models/ggml-tiny.bin",
    )

/// Window-level gestures arrive as counted requests: the shortcut
/// handler lives outside DesktopApp's state, so it bumps a counter and
/// the app answers inside it.
private class GestureRequests {
    var newNote by mutableStateOf(0)
    var search by mutableStateOf(0)
    var quickOpen by mutableStateOf(0)
}

fun main() {
    System.getProperty("compose.application.resources.dir")?.let { resources ->
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing == null) resources else "$resources;$existing",
        )
    }
    application {
        var vaultDir by remember { mutableStateOf(Prefs.vaultDir) }
        var theme by remember { mutableStateOf(Prefs.theme) }
        var settingsOpen by remember { mutableStateOf(false) }
        val gestures = remember { GestureRequests() }
        val probe = System.getProperty("gilune.probe")
        val dark = when (theme) {
            ThemeChoice.System -> isSystemInDarkTheme()
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = if (probe == null) "Gilune" else "Gilune probe",
            icon = painterResource("gilune-logo-v2.png"),
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
            onPreviewKeyEvent = { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.isCtrlPressed && event.key == Key.N -> {
                        gestures.newNote++
                        true
                    }
                    event.isCtrlPressed && event.key == Key.F -> {
                        gestures.search++
                        true
                    }
                    event.isCtrlPressed && event.key == Key.P -> {
                        gestures.quickOpen++
                        true
                    }
                    event.key == Key.Escape && settingsOpen -> {
                        settingsOpen = false
                        true
                    }
                    else -> false
                }
            },
        ) {
            GiluneTheme(dark) {
                Surface(Modifier.fillMaxSize()) {
                    key(vaultDir) {
                        DesktopApp(
                            vaultDir = vaultDir,
                            theme = theme,
                            probe = probe,
                            settingsOpen = settingsOpen,
                            onSettingsOpen = { settingsOpen = it },
                            gestures = gestures,
                            onThemeChange = { theme = it; Prefs.theme = it },
                            onVaultChange = { Prefs.vaultDir = it; vaultDir = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopApp(
    vaultDir: String,
    theme: ThemeChoice,
    probe: String?,
    settingsOpen: Boolean,
    onSettingsOpen: (Boolean) -> Unit,
    gestures: GestureRequests,
    onThemeChange: (ThemeChoice) -> Unit,
    onVaultChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember { GiluneState(vaultDir, scope) }
    val editor = remember { EditorState() }
    val tree = remember { TreeState() }
    val searchFocus = remember { FocusRequester() }
    val quickOpenFocus = remember { FocusRequester() }
    val recorder = remember { DesktopAudioRecorder() }
    var recording by remember { mutableStateOf(false) }
    val colours = MaterialTheme.colorScheme

    fun openNote(relPath: String) {
        tree.selectedFile = relPath
        editor.open(state.vaultDir, relPath)
    }

    fun createNote() {
        openNote(createUntitledNote(state.vaultDir, tree.selectedDir))
        editor.focusTitle = true
        state.sync(announce = false)
    }

    LaunchedEffect(gestures.newNote) {
        if (gestures.newNote > 0) createNote()
    }
    LaunchedEffect(gestures.search) {
        if (gestures.search > 0) {
            tree.railPanel = RailPanel.SEARCH
            runCatching { searchFocus.requestFocus() }
        }
    }
    LaunchedEffect(gestures.quickOpen) {
        if (gestures.quickOpen > 0) runCatching { quickOpenFocus.requestFocus() }
    }

    LaunchedEffect(Unit) {
        state.openVault { gilune ->
            scope.launch(Dispatchers.IO) { startTranscription(state, gilune) }
            // UI-probe affordances (dev runs only, -Pgilune.probe): open a
            // screen so an automated screenshot can look at it.
            when {
                probe == null -> Unit
                probe.startsWith("settings") -> onSettingsOpen(true)
                probe.startsWith("note:") -> openNote(probe.removePrefix("note:"))
                probe.startsWith("read:") -> {
                    editor.reading = true
                    openNote(probe.removePrefix("read:"))
                }
                probe.startsWith("search:") -> {
                    tree.railPanel = RailPanel.SEARCH
                }
                probe.startsWith("props:") -> {
                    openNote(probe.removePrefix("props:"))
                    editor.openProperties = true
                }
            }
        }
    }

    val micOffered = state.plugins.contributes(Slots.CAPTURE_ACTIONS, RECORD_VOICE_ACTION)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Gilune", style = MaterialTheme.typography.titleMedium)
            QuickOpenBox(state, ::openNote, quickOpenFocus, Modifier.width(280.dp))
            Spacer(Modifier.weight(1f))
            if (micOffered) {
                IconButton(
                    onClick = {
                        if (!recording) {
                            try {
                                recorder.start()
                                recording = true
                                state.message.value = "recording a new note… click the microphone to stop"
                            } catch (e: Throwable) {
                                state.message.value = "microphone unavailable: ${e.message}"
                            }
                        } else {
                            recording = false
                            state.captureAudio(recorder.stop(), ::openNote)
                        }
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = if (recording) "stop recording" else "new note from speech",
                        modifier = Modifier.size(17.dp),
                        tint = if (recording) colours.error else colours.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { state.sync() }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "sync vault",
                    modifier = Modifier.size(17.dp),
                    tint = colours.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onSettingsOpen(true) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "settings",
                    modifier = Modifier.size(17.dp),
                    tint = colours.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = colours.outlineVariant)

        if (state.proposals.isNotEmpty()) ProposalsBanner(state)

        Row(Modifier.fillMaxWidth().weight(1f)) {
            RailPanels(
                state = state,
                tree = tree,
                searchFocus = searchFocus,
                onOpen = ::openNote,
                onCreateNote = { rel ->
                    openNote(rel)
                    editor.focusTitle = true
                    state.sync(announce = false)
                },
                modifier = Modifier.width(240.dp).fillMaxHeight(),
                initialSearchQuery = probe?.removePrefix("search:")?.takeIf { probe.startsWith("search:") } ?: "",
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(colours.outlineVariant))
            NoteEditor(
                state,
                editor,
                Modifier.weight(1f).fillMaxHeight(),
                recorder = DesktopAudioRecorder(),
                onRenamed = { tree.selectedFile = it },
            )
        }
    }

    if (settingsOpen) {
        SettingsPane(
            state = state,
            vaultDir = vaultDir,
            onVaultChange = { onSettingsOpen(false); onVaultChange(it) },
            theme = theme,
            onThemeChange = onThemeChange,
            onClose = { onSettingsOpen(false) },
            initialSection = probe?.removePrefix("settings:")?.takeIf { probe.startsWith("settings:") },
        )
    }
}

/// Quick-open by name (Ctrl+P): type a fragment, Enter opens the best
/// match. Content and metadata search live in the rail's Search panel.
@Composable
private fun QuickOpenBox(
    state: GiluneState,
    onOpen: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colours = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val matches = remember(query, state.vaultTick.value) { searchNotes(state.vaultDir, query) }

    fun open(relPath: String) {
        onOpen(relPath)
        query = ""
        focusManager.clearFocus()
    }

    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, colours.outlineVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = colours.onSurfaceVariant,
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            event.key == Key.Enter && matches.isNotEmpty() -> {
                                open(matches.first())
                                true
                            }
                            event.key == Key.Escape -> {
                                query = ""
                                focusManager.clearFocus()
                                true
                            }
                            else -> false
                        }
                    },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = colours.onSurface),
                cursorBrush = SolidColor(colours.primary),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("open a note…", fontSize = 12.sp, color = colours.onSurfaceVariant)
                        }
                        inner()
                    }
                },
            )
            if (query.isEmpty()) {
                Text(
                    "ctrl+p",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colours.outline,
                )
            }
        }
        DropdownMenu(
            expanded = matches.isNotEmpty(),
            onDismissRequest = { query = "" },
            properties = PopupProperties(focusable = false),
        ) {
            for (relPath in matches.take(8)) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(relPath.substringAfterLast('/').removeSuffix(".md"), fontSize = 13.sp)
                            Text(
                                relPath,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colours.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { open(relPath) },
                )
            }
        }
    }
}

/// Deletions made by other tools await a decision here; deletions made in
/// the app answer their own proposal at the point of deletion (ADR 0011).
@Composable
private fun ProposalsBanner(state: GiluneState) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colours.errorContainer).padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val count = state.proposals.size
        Text(
            if (count == 1) "A note was deleted outside the app — keep its history or let it go?"
            else "$count notes were deleted outside the app — keep their history or let them go?",
            fontSize = 12.sp,
            color = colours.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            state.pluginAction("restored what other tools deleted") { g ->
                for (proposal in g.openProposals()) g.resolveTombstone(proposal.proposalId, false)
                g.project()
            }
        }) { Text("Restore", fontSize = 12.sp) }
        TextButton(onClick = {
            state.pluginAction("accepted the deletions") { g ->
                for (proposal in g.openProposals()) g.resolveTombstone(proposal.proposalId, true)
                g.project()
            }
        }) { Text("Delete", fontSize = 12.sp) }
    }
    HorizontalDivider(color = colours.outlineVariant)
}

/// Bring up local transcription, off the capture path by design. The
/// model download is a declared capability, so it waits for consent
/// rather than fetching 75 MiB on someone's first launch (ADR 0011).
private fun startTranscription(state: GiluneState, gilune: Gilune) {
    try {
        val installed = gilune.plugins().firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }
        if (installed == null || installed.state != "enabled") {
            state.message.value = "vault: ${gilune.vaultPath()}"
            return
        }
        val model = modelFile()
        if (!model.exists()) {
            if (!gilune.isGranted(TRANSCRIPTION_PLUGIN, NETWORK_CAPABILITY)) {
                state.message.value =
                    "transcription needs permission to download its model — Settings ▸ Plugins"
                return
            }
            state.message.value = "downloading transcription model (~75 MiB, one-time)…"
            model.parentFile.mkdirs()
            val partial = File(model.parentFile, "${model.name}.part")
            java.net.URI(MODEL_URL).toURL().openStream().use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            partial.renameTo(model)
        }
        gilune.enableWhisper(model.absolutePath)
        state.message.value = "vault: ${gilune.vaultPath()} — transcription ready"
        state.sync(announce = false)
    } catch (e: Throwable) {
        state.message.value = "transcription unavailable (${e.message}); captures still work"
    }
}
