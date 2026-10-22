// Desktop entry point and window scaffold (ADR 0011, amended
// 2026-08-31): top bar with quick-open and the speech microphone, rail
// with Files and Search panels, document region, status line.
// Capture-first is a pair of gestures now, not a region: Ctrl+N makes an
// Untitled note ready to type, the microphone makes a note from speech.
// Settings is a surface over the window, not a dialog, because it holds
// the plugin panel and will hold devices next.
//
// The document region is a host of tabbed panes (ADR 0014), so the
// window holds a `TabsState` where it held one buffer, the status line
// sits here rather than inside an editor, and the tab shortcuts are
// answered above any focused field.

package org.gilune.app

import org.gilune.plugin.sdk.Slots

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
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import uniffi.gilune_ffi.Gilune
import java.io.File
import kotlin.math.roundToInt

/// Where this machine keeps what belongs to it rather than to a vault.
/// Named once, because there are two of these now and they must never
/// drift apart: `%LOCALAPPDATA%\Gilune` on Windows, the home directory
/// anywhere else.
internal fun localGiluneDir(name: String): File =
    File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Gilune/$name")

/// This device's identity keypair and the roster of devices it has
/// paired with. **Outside the vault, and that is the whole point**
/// (ADR 0019 § 4): a vault is a portable artefact people copy, restore
/// and put in shared folders, and an identity inside one is a cloned
/// identity sharing a sequence space.
private fun identityDir(): File = localGiluneDir("sync")

/// What another device will call this one in its list. The machine's own
/// name is what a person already recognises; the fallback is a phrase
/// rather than a blank, because a device nobody can name is a device
/// nobody can revoke with confidence.
private fun deviceName(): String =
    (
        System.getenv("COMPUTERNAME")
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        )
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "This desktop"

/// How long the layout waits for the tabs to settle before it is
/// written: a burst of opens and closes costs one preference write.
private const val LAYOUT_SAVE_IDLE_MS = 400L

/// The rail's travel (ADR 0011 amendment). Narrower than the minimum is
/// not a rail; wider than the maximum is a window given over to a tree.
/// A drag that ends inside `RAIL_SHUT_AT` of raw travel shuts the rail
/// instead of pinning it to the minimum — VSCode's gesture — and the
/// same drag rightwards past that mark brings it back, so the mouse
/// alone can always recover the rail.
private val RAIL_MIN = 170.dp
private val RAIL_MAX = 480.dp
private val RAIL_SHUT_AT = 120.dp

/// Window-level gestures arrive as counted requests: the shortcut
/// handler lives outside DesktopApp's state, so it bumps a counter and
/// the app answers inside it. The tab gestures (ADR 0014) are handled up
/// here too, so a focused text field cannot swallow them; those carrying
/// an argument count alongside it, since pressing Ctrl+3 twice is two
/// requests for the same tab.
private class GestureRequests {
    var newNote by mutableStateOf(0)
    var search by mutableStateOf(0)
    var quickOpen by mutableStateOf(0)

    /// Count paired with the direction: true cycles forward.
    var cycleTab by mutableStateOf(0 to true)

    /// Count paired with the tab wanted, counting from one.
    var selectTab by mutableStateOf(0 to 0)

    var closeTab by mutableStateOf(0)
    var splitRight by mutableStateOf(0)

    /// Ctrl+B, which the top bar's toggle answers with the same flip.
    var toggleRail by mutableStateOf(0)

    /// Ctrl+E: the note in front into presentation mode, or back to editing.
    var togglePresentation by mutableStateOf(0)
}

/// The row above the letters only: the numeric keypad sends keys of its
/// own and nothing asked for them.
private val DIGIT_KEYS = listOf(
    Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
)

private fun digitOf(key: Key): Int? = DIGIT_KEYS.indexOf(key).takeIf { it >= 0 }?.plus(1)

/// Closing the window is a save, not a discard (ADR 0014): the window
/// owns the gesture and the app owns the buffers, so the app leaves the
/// window something to run before the process ends.
private class Shutdown {
    var run: () -> Unit = {}
}

fun main() {
    System.getProperty("compose.application.resources.dir")?.let { resources ->
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            // File.pathSeparator, not a literal ";": a packaged build always
            // has both properties set, so on Linux a hard-coded separator
            // would hand JNA one unopenable path and the kernel would never
            // load.
            "jna.library.path",
            if (existing == null) resources else resources + File.pathSeparator + existing,
        )
    }
    application {
        var vaultDir by remember { mutableStateOf(Prefs.vaultDir) }
        var theme by remember { mutableStateOf(Prefs.theme) }
        var settingsOpen by remember { mutableStateOf(false) }
        val gestures = remember { GestureRequests() }
        val shutdown = remember { Shutdown() }
        val probe = System.getProperty("gilune.probe")
        // Updates belong to the installation, not to a vault, so they live
        // out here and survive a vault switch. A probe window never looks.
        val updateScope = rememberCoroutineScope()
        val updater = remember { Updater(updateScope) }
        updater.quit = {
            shutdown.run()
            exitApplication()
        }
        LaunchedEffect(Unit) { if (probe == null) updater.checkPeriodically() }
        val dark = when (theme) {
            ThemeChoice.System -> isSystemInDarkTheme()
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
        }
        Window(
            // Nothing typed is lost to a window closing: every buffer is
            // flushed and the layout written before the process goes.
            onCloseRequest = {
                shutdown.run()
                exitApplication()
            },
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
                    event.isCtrlPressed && event.key == Key.B -> {
                        gestures.toggleRail++
                        true
                    }
                    event.isCtrlPressed && event.key == Key.Tab -> {
                        gestures.cycleTab = gestures.cycleTab.first + 1 to !event.isShiftPressed
                        true
                    }
                    event.isCtrlPressed && event.key == Key.W -> {
                        gestures.closeTab++
                        true
                    }
                    event.isCtrlPressed && event.key == Key.Backslash -> {
                        gestures.splitRight++
                        true
                    }
                    event.isCtrlPressed && event.key == Key.E -> {
                        gestures.togglePresentation++
                        true
                    }
                    event.key == Key.Escape && settingsOpen -> {
                        settingsOpen = false
                        true
                    }
                    else -> {
                        // Ctrl+1…9 selects the nth tab of the pane in
                        // front; the digit travels with the request.
                        val digit = if (event.isCtrlPressed) digitOf(event.key) else null
                        if (digit != null) {
                            gestures.selectTab = gestures.selectTab.first + 1 to digit
                            true
                        } else {
                            false
                        }
                    }
                }
            },
        ) {
            DisposableEffect(window) {
                val displayChanges = if (System.getProperty("os.name").startsWith("Windows")) {
                    watchDisplayChanges(window)
                } else null
                onDispose { displayChanges?.close() }
            }
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
                            shutdown = shutdown,
                            updater = updater.takeIf { probe == null },
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
    shutdown: Shutdown,
    updater: Updater?,
    onThemeChange: (ThemeChoice) -> Unit,
    onVaultChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember { GiluneState(vaultDir, scope) }
    // The document region's whole state: the pane tree, its tabs and one
    // buffer per open note, restored from where this vault was left.
    val tabs = remember { TabsState.restore(vaultDir, Prefs.tabsLayout(vaultDir)) }
    val tree = remember { TreeState() }
    val searchFocus = remember { FocusRequester() }
    val quickOpenFocus = remember { FocusRequester() }
    val recorder = remember { DesktopAudioRecorder() }
    val dictation = remember { DesktopAudioRecorder() }
    var recording by remember { mutableStateOf(false) }
    val transcription = remember { TranscriptionRuntime() }
    val nemo = remember { NemoHost() }
    KeepTranscriptionAsked(state) { gilune ->
        withContext(Dispatchers.IO) { startTranscription(state, gilune, transcription, nemo) }
    }
    val colours = MaterialTheme.colorScheme
    val density = LocalDensity.current

    // How wide the rail stands and whether it stands at all: device-local
    // facts, seeded from the preferences and written back as they change
    // (ADR 0011 amendment). `railRaw` is the width the pointer is asking
    // for with the clamp set aside, because a drag that runs past the
    // shut threshold is answered by where the pointer went, not by where
    // the rail was allowed to follow.
    // A width read back from the preferences is not trusted to be one the
    // rail may stand at.
    var railWidth by remember { mutableStateOf(Prefs.railWidth.dp.coerceIn(RAIL_MIN, RAIL_MAX)) }
    var railCollapsed by remember { mutableStateOf(Prefs.railCollapsed) }
    var railRaw by remember { mutableStateOf(railWidth) }
    var railHeld by remember { mutableStateOf(railWidth) }

    fun openNote(relPath: String) {
        tabs.open(relPath)
    }

    fun createNote() {
        tabs.openForEditing(createUntitledNote(state.vaultDir, tree.selectedDir))
        state.sync(announce = false)
    }

    fun setRailCollapsed(value: Boolean) {
        railCollapsed = value
        Prefs.railCollapsed = value
    }

    // The rail highlights whatever is in front rather than whatever was
    // last clicked, so the selection follows tabs and splits too
    // (ADR 0014 consequences).
    LaunchedEffect(Unit) {
        snapshotFlow { tabs.activeEditor?.openRelPath }.collect { tree.selectedFile = it }
    }

    // The layout is device-local and per vault, written once the tabs
    // stop moving; buffers are never in it — they were flushed, and a
    // restored tab re-reads its file.
    LaunchedEffect(Unit) {
        snapshotFlow { tabs.serialise() }.collectLatest {
            delay(LAYOUT_SAVE_IDLE_MS)
            Prefs.saveTabsLayout(vaultDir, it)
        }
    }

    DisposableEffect(tabs) {
        val preserve = {
            for (pane in tabs.panes()) for (tab in pane.tabs) flushEditor(vaultDir, tab.editor, tabs.onNoteSaved)
            Prefs.saveTabsLayout(vaultDir, tabs.serialise())
        }
        shutdown.run = preserve
        // Opening another vault tears this app down as surely as closing
        // the window does, and neither may lose what is in a buffer.
        onDispose {
            preserve()
            shutdown.run = {}
        }
    }

    LaunchedEffect(gestures.newNote) {
        if (gestures.newNote > 0) createNote()
    }
    LaunchedEffect(gestures.cycleTab) {
        if (gestures.cycleTab.first > 0) tabs.cycle(gestures.cycleTab.second)
    }
    LaunchedEffect(gestures.selectTab) {
        if (gestures.selectTab.first > 0) tabs.selectIndex(gestures.selectTab.second)
    }
    LaunchedEffect(gestures.closeTab) {
        // Nothing open is nothing to close; a refusal means the file
        // changed under an edited buffer and is waiting for an answer.
        if (gestures.closeTab > 0 && tabs.activeTab != null && !tabs.closeActive()) {
            state.message.value = "unsaved changes over a changed file — choose which version to keep"
        }
    }
    LaunchedEffect(gestures.togglePresentation) {
        if (gestures.togglePresentation > 0) tabs.activeEditor?.togglePresentation()
    }
    LaunchedEffect(gestures.splitRight) {
        if (gestures.splitRight > 0 && !tabs.splitRight()) {
            state.message.value = "a pane needs a second tab before it can split"
        }
    }
    LaunchedEffect(gestures.toggleRail) {
        if (gestures.toggleRail > 0) setRailCollapsed(!railCollapsed)
    }
    LaunchedEffect(gestures.search) {
        if (gestures.search > 0) {
            // A gesture that needs the rail reopens it (ADR 0011
            // amendment). The panel does not exist while the rail is
            // shut, and focus cannot reach a field that has not been
            // composed yet, so the request waits for the frame that
            // builds it.
            if (railCollapsed) {
                setRailCollapsed(false)
                withFrameNanos {}
                withFrameNanos {}
            }
            tree.railPanel = RailPanel.SEARCH
            runCatching { searchFocus.requestFocus() }
        }
    }
    LaunchedEffect(gestures.quickOpen) {
        if (gestures.quickOpen > 0) runCatching { quickOpenFocus.requestFocus() }
    }

    LaunchedEffect(Unit) {
        // A save the tab machinery makes — a tab losing the front, a tab
        // closing, the window closing — goes to the feed by the same
        // route the editor's own save takes (ADR 0020 § 2.2). It was
        // silent until this round, so a note flushed by switching tabs
        // reached the disk and nothing else in the app knew.
        tabs.onNoteSaved = { relPath -> state.noteSaved(relPath) }
        // Enrolment is the shell's to arrange and the kernel's to decide
        // (ADR 0019 § 4): this says where the identity lives and what
        // protects it, and `openVault` runs it before anything else reads
        // the vault, because it is what settles whether this machine is
        // the device the vault believes it belongs to. Set before the
        // open, never after. Nothing here binds a socket — that waits for
        // the switch in Settings ▸ Sync.
        // Transcription (NVIDIA NeMo) writes saved transcripts app-side
        // (ADR 0027 S3): every processing pass on this handle gives it the
        // jobs queued for it, when its model is loaded.
        state.externalProcessing = { gilune -> nemo.engines?.let { runNemoJobs(gilune, it::transcribe) } ?: ProviderPass() }
        state.syncSetup = { gilune ->
            val identity = identityDir()
            identity.mkdirs()
            gilune.configureSync(identity.absolutePath, deviceName(), platformKeyWrap())
        }
        // How this device finds others (§ 1): the desktop takes the Rust
        // `mdns-sd` implementation, which is also what ADR 0008's future
        // host with no JVM to borrow from inherits. Registering it binds
        // nothing — the responder starts with the listener, at the
        // switch, because UDP 5353 is a second inbound bind rather than
        // an escape from the first (§ 2).
        state.syncDiscovery = { gilune -> gilune.useBundledDiscovery() }
        state.openVault { gilune ->
            // The desktop's second transcriber (ADR 0027 S3): installed
            // switched off when whisper is already writing the notes, and
            // never switched on except by a person.
            registerNemoOnHandle(gilune)
            state.refresh()
            // The starting folders the enabled plugins declare, each once
            // per vault on this machine — after the open, because only the
            // registered plugins know them.
            runCatching {
                val made = createDeclaredFolders(vaultDir, declaredFolders(gilune.plugins()),
                    given = { Prefs.folderGiven(vaultDir, it) }, remember = { Prefs.markFolderGiven(vaultDir, it) })
                if (made.isNotEmpty()) state.refreshCount.value += 1
            }
            // UI-probe affordances (dev runs only, -Pgilune.probe): open a
            // screen so an automated screenshot can look at it. The forms
            // are settings[:section|:plugin:<id>], note:<rel>, read:<rel>,
            // props:<rel>, search:<query>, panel:<key> and
            // split:<rel>;<rel>. View state belongs to a tab, so a probe
            // flips it on the tab the open returned.
            when {
                probe == null -> Unit
                probe.startsWith("settings") -> onSettingsOpen(true)
                // note: takes a ;-list so a screenshot can show a strip
                // holding several tabs, not just one.
                probe.startsWith("note:") ->
                    probe.removePrefix("note:").split(';')
                        .filter { it.isNotBlank() }
                        .forEach { openNote(it) }
                probe.startsWith("read:") -> {
                    tabs.open(probe.removePrefix("read:")).editor.reading = true
                }
                probe.startsWith("search:") -> {
                    // A screenshot of the search panel needs the rail,
                    // whatever the preferences last left it at.
                    setRailCollapsed(false)
                    tree.railPanel = RailPanel.SEARCH
                }
                // Any rail panel by key, contributed ones included
                // (`panel:tasks`). A key nothing contributes composes the
                // tree, exactly as it does when a plugin goes away.
                probe.startsWith("panel:") -> {
                    setRailCollapsed(false)
                    tree.railPanel = RailPanel(probe.removePrefix("panel:"))
                }
                probe.startsWith("props:") -> {
                    tabs.open(probe.removePrefix("props:")).editor.openProperties = true
                }
                probe.startsWith("split:") -> {
                    val both = probe.removePrefix("split:").split(';', limit = 2)
                    both.firstOrNull()?.takeIf { it.isNotBlank() }?.let { tabs.open(it) }
                    both.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { tabs.openToSide(it) }
                }
            }
        }
    }

    val micOffered = state.plugins.contributes(Slots.CAPTURE_ACTIONS, RECORD_VOICE_ACTION)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The rail's other switch: the same flip Ctrl+B makes, kept
            // where the rail is so shutting it never hides its own way
            // back (ADR 0011 amendment).
            Tooltip(if (railCollapsed) "Show sidebar (Ctrl+B)" else "Hide sidebar (Ctrl+B)") {
                IconButton(
                    onClick = { setRailCollapsed(!railCollapsed) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "toggle sidebar (ctrl+b)",
                        modifier = Modifier.size(17.dp),
                        tint = colours.onSurfaceVariant,
                    )
                }
            }
            Text("Gilune", style = MaterialTheme.typography.titleMedium)
            QuickOpenBox(state, ::openNote, quickOpenFocus, Modifier.width(280.dp))
            Spacer(Modifier.weight(1f))
            // A voice note is worth keeping without its words, so this
            // microphone records regardless — but it says so first.
            val untranscribed = transcription.unreadyReason()
            val micWords = when {
                recording -> "Stop recording"
                untranscribed != null -> "New note from speech (not transcribed: $untranscribed)"
                else -> "New note from speech"
            }
            if (micOffered && recording) {
                MicLevelBars({ recorder.level }, onSilence = { state.message.value = QUIET_WARNING })
            }
            if (micOffered) Tooltip(micWords) {
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
            Tooltip("Sync vault") {
                IconButton(onClick = { state.sync() }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "sync vault",
                        modifier = Modifier.size(17.dp),
                        tint = colours.onSurfaceVariant,
                    )
                }
            }
            Tooltip("Settings") {
                IconButton(onClick = { onSettingsOpen(true) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "settings",
                        modifier = Modifier.size(17.dp),
                        tint = colours.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = colours.outlineVariant)

        if (updater != null) UpdateBanner(updater)
        if (state.proposals.isNotEmpty()) ProposalsBanner(state)

        Row(Modifier.fillMaxWidth().weight(1f)) {
            // A shut rail is not composed at all: nothing of it is left
            // to lay out, and the handle stands alone at the window's
            // left edge as the way back.
            if (!railCollapsed) {
                RailPanels(
                    state = state,
                    tree = tree,
                    searchFocus = searchFocus,
                    onOpen = ::openNote,
                    onCreateNote = { rel ->
                        tabs.openForEditing(rel)
                        state.sync(announce = false)
                    },
                    modifier = Modifier.width(railWidth).fillMaxHeight(),
                    initialSearchQuery = probe?.removePrefix("search:")?.takeIf { probe.startsWith("search:") } ?: "",
                    onOpenToSide = { tabs.openToSide(it) },
                    onRenamed = { old, new -> tabs.noteRenamed(old, new) },
                    onDeleted = { tabs.noteDeleted(it) },
                    onLocalScope = { rel, local -> tabs.noteLocalScope(rel, local) },
                )
            }
            DragDivider(
                tag = "rail-divider",
                // A drag starts from where the rail actually is, so a
                // shut rail's travel is measured from nothing.
                onStart = {
                    railHeld = railWidth
                    railRaw = if (railCollapsed) 0.dp else railWidth
                },
                onStop = {
                    // Released inside the shut threshold, the rail
                    // closes rather than pinning itself to its minimum;
                    // released beyond it, it keeps the width asked for.
                    // A drag that shuts the rail hands back the width it
                    // started from, so the toggle reopens the rail the
                    // user arranged rather than the sliver the gesture
                    // passed through on its way out.
                    val shut = railRaw < RAIL_SHUT_AT
                    setRailCollapsed(shut)
                    railWidth = if (shut) railHeld else railRaw.coerceIn(RAIL_MIN, RAIL_MAX)
                    Prefs.railWidth = railWidth.value.roundToInt()
                },
            ) { delta ->
                railRaw += with(density) { delta.toDp() }
                // Rightwards past the threshold reopens as it goes, so
                // the rail follows the pointer out rather than appearing
                // when the button comes up.
                if (railCollapsed && railRaw >= RAIL_SHUT_AT) setRailCollapsed(false)
                if (!railCollapsed) railWidth = railRaw.coerceIn(RAIL_MIN, RAIL_MAX)
            }
            CompositionLocalProvider(LocalTranscription provides transcription, LocalNemo provides nemo) {
                PaneHost(state, tabs, Modifier.weight(1f).fillMaxHeight(), recorder = dictation)
            }
        }

        // One window, one status line: it sat inside the editor while
        // there was one of those, and moves out here now that there may
        // be several panes (ADR 0014).
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
            val front = tabs.activeEditor
            if (front != null) {
                Text(
                    if (front.dirty) "unsaved" else "saved",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (front.dirty) colours.primary else colours.onSurfaceVariant,
                )
            }
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
            updater = updater,
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
    val matches = remember(query, state.vaultCursor.value) { searchNotes(state.vaultDir, query) }

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
