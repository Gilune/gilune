// The Android shell (ADR 0017 § 1). Home IS capture: the app opens on a
// composer, and everything else is somewhere you go from there.
//
// The window is edge-to-edge, because without it the IME insets never
// arrive at all, and every bottom-anchored surface pads for both the
// keyboard and the gesture area. The manifest supplies
// `windowSoftInputMode=adjustResize` and a DayNight window theme, so dark
// mode follows the system from the first painted pixel.

package org.gilune.app

import org.gilune.plugins.tasks.TASKS_RAIL_PANEL

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    /// What the intent that opened (or re-opened) this activity asks for.
    /// The widget, its notification and the Quick Settings tile all arrive
    /// here, and a second tap of the same cell is a second request — hence
    /// the nonce (see [LaunchRequest]).
    private val request = mutableStateOf<LaunchRequest?>(null)
    private var nonce = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge, or the IME insets never arrive (§ 2, constraints T8).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val vaultDir = vaultDir(this)
        accept(intent)
        setContent { GiluneMobileApp(vaultDir, request) }
    }

    /// `singleTop`, so a widget tap reaches the running app here rather
    /// than stacking a second copy of it.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(intent: Intent?) {
        nonce += 1
        request.value = LaunchRequest(nonce, launchFrom(intent))
    }
}

/// The shell's own destinations — three, and there will not be a fourth
/// (ADR 0017 § 1). Agenda is **not** here: it is a plugin's contribution
/// to the `navigation` slot and lives beside these on the bar, never
/// among them.
enum class Destination(val label: String) { Capture("Capture"), Notes("Notes"), Search("Search") }

/// § 1's cap on contributed destinations. Android's guidance is 3–5
/// same-level destinations in a bar and a drawer beyond that; three shell
/// destinations plus two is the ceiling that keeps the bar a bar. Overflow
/// renders nowhere — a bar does not scroll — so a third contribution is
/// simply not drawn.
const val MOBILE_NAV_PANELS = 2

/// What the bar should show beside the shell's own destinations, right
/// now: the `navigation` slot's live contributions under § 1's cap.
///
/// `navigationPanels` is shared with the desktop rail and is where both
/// conditions live — the plugin enabled, and (for the tasks agenda) its
/// own panel switch on. Nothing here knows what a tasks plugin is.
fun mobileNavigationPanels(plugins: List<uniffi.gilune_ffi.PluginInfo>): List<NavigationPanel> =
    navigationPanels(plugins).take(MOBILE_NAV_PANELS)

/// Which contributed destination is showing, given what is selected and
/// what is contributed. A selection whose contribution has gone —
/// switched off, disabled, removed — answers null, and the shell
/// destination underneath is what shows. A value, never a write:
/// composition reads state, it does not edit it.
fun activeNavigationPanel(panels: List<NavigationPanel>, selectedKey: String?): NavigationPanel? =
    panels.firstOrNull { it.key == selectedKey }

private sealed interface Screen {
    data object Home : Screen
    data class Read(val relPath: String) : Screen

    /// The live-preview editor (ADR 0017 § 2, MB2). Read and Edit are two
    /// screens rather than one with a mode, because on a phone the bottom
    /// is mode-swapped: Read has the nav bar under it, Edit has the
    /// accessory row.
    data class Edit(val relPath: String) : Screen
    data class Settings(val route: SettingsRoute) : Screen
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GiluneMobileApp(vaultDir: String, launchRequest: State<LaunchRequest?> = remember { mutableStateOf(null) }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { MobilePrefs(context) }
    val theme by prefs.theme.collectAsState(initial = ThemeChoice.System)
    val idleTimeout by prefs.idleTimeout.collectAsState(initial = IdleTimeout.Default)
    val noticeSeen by prefs.vaultNoticeSeen.collectAsState(initial = true)
    val dark = when (theme) {
        ThemeChoice.System -> isSystemInDarkTheme()
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }

    val state = remember { GiluneState(vaultDir, scope) }

    // The realtime provider is registered **here** rather than in
    // `openVault`, and that is the whole of "bundled on mobile only in v1"
    // (ADR 0018 § 5): the shared open path declares the microphone and
    // tasks for both shells, and Moonshine is neither shared nor desktop.
    // Registering grants nothing — its one capability is `network.request`,
    // which always waits for an explicit yes.
    // How this phone enrols itself (ADR 0019 § 4), set **before** the
    // vault is opened because `openVault` runs it as part of opening: the
    // identity decides whether this device is the one the vault believes
    // it belongs to, and that question is answered before anything else
    // reads it. A vault restored onto another phone re-enrols here and
    // says so on the message line.
    //
    // The identity and the roster live in `filesDir/sync` — device-local,
    // **outside the vault**, so exporting or restoring a vault never
    // clones an identity — and the key itself is sealed by the Keystore.
    // Nothing here binds a socket: that waits for the switch in Settings.
    //
    // How this phone finds other devices (ADR 0019 § 1) is set beside it
    // and for the same reason — `openVault` registers it as part of
    // opening. It is the platform's `NsdManager` rather than the Rust
    // `mdns-sd` the desktop uses, because on Android the platform owns the
    // multicast socket and borrowing it costs no `MulticastLock` and no
    // extra permission. Registering binds nothing and announces nothing:
    // that waits for the switch in Settings.
    val keyWrap = remember { KeystoreKeyWrap() }
    LaunchedEffect(Unit) {
        // Shared code has nowhere to put a warning of its own — it cannot
        // name `android.util.Log` — so the shell points the hook at one.
        // Without it an external provider that threw was silent twice: no
        // sentence and no log line.
        GiluneLog.sink = { message, cause -> android.util.Log.w("Gilune", message, cause) }
        state.syncSetup = { gilune -> gilune.configureSync(syncDir(context), deviceName(), keyWrap) }
        state.syncDiscovery = { gilune -> gilune.setDiscovery(NsdDiscovery(context.applicationContext)) }
        state.openVault { gilune ->
            // A vault that *had* the realtime provider on and has just had
            // it switched off is owed more than a message line that the
            // next thing to happen replaces: the caption stopping is a
            // change somebody will notice hours later. The flag persists;
            // Settings ▸ Plugins and the provider's own screen show it
            // until it is dismissed. A fresh install sets nothing.
            if (registerMoonshine(state, gilune).switchedOffOnUpgrade) {
                scope.launch { prefs.raiseProviderOffNotice() }
            }
        }
    }

    // Sync on open (§ 10). `shouldSyncOnForeground` is the whole rule and
    // it is pure: sync switched on, and somebody paired to sync with.
    //
    // **Keyed on the rule's answer, not just on the handle.** `resumeSync`
    // now runs behind the vault opening rather than in front of it, so at
    // the moment the handle appears the listener has not come back up yet
    // and `enabled` is still false. Watching the answer means this fires
    // the instant the switch is actually back on, and once — a second
    // resume does not change a `true` into another `true`.
    val syncOnOpen = shouldSyncOnForeground(state.syncStatus.value)
    LaunchedEffect(state.gilune.value) {
        if (state.gilune.value == null) return@LaunchedEffect
        withContext(Dispatchers.IO) { state.refreshSync() }
    }
    LaunchedEffect(state.gilune.value, syncOnOpen) {
        if (state.gilune.value != null && syncOnOpen) state.syncDevices()
    }

    // Local transcription (§ 4). The model is fetched and handed to the
    // kernel off the capture path entirely — nothing here is on the way
    // to a typable caret, and a capture never waits on any of it.
    //
    // **One call site, deliberately.** This is keyed on exactly the
    // inputs the plan reads, so it runs when the vault first produces a
    // registry and again whenever the answer would change: allowing
    // `network.request`, switching the plugin on, or choosing another
    // model. A second call site — the obvious one being `openVault`'s own
    // callback — would race this one and could start two downloads into
    // the same partial file.
    val transcription = remember { TranscriptionRuntime() }
    val transcriptionKey = state.plugins.firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }
        ?.let { "${it.state}|${it.granted.sorted().joinToString(",")}|${it.settings}" }
    LaunchedEffect(transcriptionKey) {
        val gilune = state.gilune.value ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { startTranscription(context, state, gilune, transcription) }
    }

    // The live caption's model (ADR 0018 § 4), on exactly the transcription
    // idiom above and for the same reason: one call site, keyed on the
    // inputs the plan reads, so it re-runs when allowing `network.request`,
    // switching the plugin on, or turning the caption on would change the
    // answer — and never twice at once into the same partial file.
    //
    // Nothing here is on the path to a typable caret, and no recording
    // waits on any of it: with no model there is simply no caption.
    //
    // **The key is `moonshineTrigger`**, which carries two inputs the
    // hand-rolled one missed. The *other* transcriber's state, because the
    // download now waits on the model being wanted and switching whisper
    // off is what makes it wanted. And the connection, watched rather than
    // sampled, because "Waiting for Wi-Fi" was a sentence that never
    // stopped being true — joining Wi-Fi changed nothing until somebody
    // toggled an unrelated switch.
    val moonshine = remember { MoonshineRuntime() }
    val metered = rememberConnectionIsMetered(context)
    val moonshineKey = moonshineTrigger(state.plugins, metered)
    LaunchedEffect(moonshineKey) {
        if (state.gilune.value == null) return@LaunchedEffect
        withContext(Dispatchers.IO) { startMoonshine(context, state, moonshine) }
    }

    // **The provider loop** (ADR 0018 § 2, § 3, R3). `runProcessing`
    // deliberately leaves an external processor's jobs alone, so every
    // place this state runs the kernel's loop must run Moonshine's too —
    // which is exactly what this hook is for. It is set once and read from
    // `GiluneState`, so a capture, a dictation, a sync and a catch-up all
    // reach the provider through one path rather than four call sites.
    //
    // Three conditions, and they are the shell's whole one-active rule:
    // the plugin is enabled, it is **the** active transcriber (whisper
    // being off is what makes it so), and its model is on disk. Short of
    // that this returns zero and the jobs wait — parked, not lost.
    //
    // **The whole pass crosses, not just its word count.** Until this round
    // this returned `.transcribed`, so a pass that claimed five recordings
    // and failed all five was indistinguishable from a pass that found
    // nothing — the sentence under the composer said "0 transcribed" and a
    // person read it as *nothing to do*. `ProcessingPass` folds the failures
    // into what it says.
    LaunchedEffect(state) {
        state.externalProcessing = { gilune ->
            val plugins = state.plugins.toList()
            if (plugins.activeTranscriber() == MOONSHINE_PLUGIN && moonshine.ready) {
                val dir = moonshine.modelDir
                val arch = moonshine.model?.arch
                if (dir == null || arch == null) {
                    ProviderPass()
                } else {
                    runMoonshineJobs(gilune, { MoonshineDecoder(dir, arch) })
                }
            } else {
                ProviderPass()
            }
        }
    }

    var destination by remember { mutableStateOf(Destination.Capture) }
    // Which contributed destination is selected, by the key its
    // contribution named — null means one of the shell's own is. Held as a
    // key rather than as a panel object so a plugin list that changed
    // under it cannot leave a stale label on the bar.
    var panelKey by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var draft by remember { mutableStateOf("") }

    // The `navigation` slot, read per composition from the live plugin
    // list (ADR 0015 § 5; ADR 0017 § 1, § 7). A tab appears and disappears
    // with the plugin behind it and with that plugin's own panel switch —
    // `navigationPanels` is where both conditions live, shared with the
    // desktop rail.
    val panels = mobileNavigationPanels(state.plugins)
    val activePanel = activeNavigationPanel(panels, panelKey)

    // What the widget, the recording notification and the QS tile ask for
    // (§ 4). The decision is `launchFor`, which is pure and tested; all
    // that happens here is obeying it.
    var focusComposer by remember { mutableStateOf(0) }
    var askForMic by remember { mutableStateOf(0) }
    val request = launchRequest.value
    LaunchedEffect(request) {
        val asked = request ?: return@LaunchedEffect
        destination = asked.launch.tab
        // Everything that arrives from outside asks for a shell
        // destination — a widget cell, a shortcut, a tile — so a
        // contributed panel is never what a launch lands on.
        panelKey = null
        screen = asked.launch.openNote?.let { Screen.Read(it) } ?: Screen.Home
        if (asked.launch.focusComposer) focusComposer += 1
        if (asked.launch.askForMic) askForMic += 1
    }

    // The widget's inbox count follows the vault. The cursor moves when
    // the vault's files do — this process's writes and the recording
    // service's alike — so this is "updated when something changed" with
    // nothing new to remember to call.
    LaunchedEffect(state.vaultCursor.value) { refreshWidgets(context, state.gilune.value) }

    // The idle timer (§ 1). The composer's own text is the only state it
    // governs, and expiry commits rather than discards.
    //
    // The dismissal is the composer's own (`rememberComposerDismiss`), not
    // a second copy of it: a commit lets go of the composer whether the
    // tick made it or the timer did, and coming back to a field that has
    // just been emptied on your behalf with the keyboard still up and the
    // bottom bar still hidden is the same defect either way.
    val dismissComposer = rememberComposerDismiss()
    var backgroundedAt by remember { mutableStateOf<Long?>(null) }
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    DisposableEffect(lifecycle, idleTimeout) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    val verdict = composerReturn(draft, backgroundedAt, System.currentTimeMillis(), idleTimeout)
                    if (verdict == ComposerReturn.CommitAndClear) {
                        val text = draft.trim()
                        draft = ""
                        state.captureText(text)
                        dismissComposer()
                    }
                    // A return, not the first start — which is what tells
                    // this apart from opening the app, whose own sync runs
                    // beside the vault opening above rather than here.
                    val returning = backgroundedAt != null
                    backgroundedAt = null
                    // Sync on every resume (ADR 0019 § 10): the prompt
                    // path, and the only one a person should feel.
                    if (returning && shouldSyncOnForeground(state.syncStatus.value)) {
                        state.syncDevices()
                    }
                    // The widget's recording service commits and now
                    // transcribes through a Gilune handle of its own
                    // (§ 4), so what it produced is invisible to this
                    // one's cached feed until something asks again.
                    // Coming back to the app is that moment — otherwise
                    // a note spoken into the widget reads as
                    // "transcribing…" for as long as the app stays open.
                    if (state.gilune.value != null) {
                        scope.launch(Dispatchers.IO) { runCatching { state.refresh() } }
                    }
                }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { writeVaultZip(vaultDir, it) }
                }
            }
            state.message.value = outcome.fold(
                onSuccess = { count -> if (count == null) "export failed" else "exported $count file(s)" },
                onFailure = { "export failed: ${it.message}" },
            )
        }
    }

    GiluneTheme(dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // `imePadding` first, then `navigationBarsPadding`: an inset
            // modifier consumes what it applies, so the gesture area is
            // padded only while the keyboard is not already covering it.
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                TopBar(
                    screen = screen,
                    // What Home is called is what is selected, and a
                    // contributed destination announces itself by the
                    // label its own contribution carries, verbatim.
                    homeLabel = activePanel?.label ?: destination.label,
                    onBack = { screen = backFrom(screen) },
                    onSettings = { screen = Screen.Settings(SettingsRoute.Index) },
                    // One honest gesture each way: the pencil opens the
                    // editor on the note being read, and the tick closes
                    // it — which saves, because leaving the editor is a
                    // save (ADR 0012).
                    onEdit = { relPath -> screen = Screen.Edit(relPath) },
                    onDone = { relPath -> screen = Screen.Read(relPath) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    when (val open = screen) {
                        // A contributed destination wins over the shell's
                        // own while one is selected; when its plugin goes,
                        // `activePanel` is null again and the shell
                        // destination underneath is what shows.
                        Screen.Home -> if (activePanel != null) {
                            NavigationPanelScreen(
                                key = activePanel.key,
                                state = state,
                                onOpenNote = { screen = Screen.Read(it) },
                                // An empty agenda's way in: the composer,
                                // holding a task marker, with the caret
                                // after it. **A draft already being
                                // written is never overwritten** — the
                                // person is taken to it and left alone,
                                // because losing a half-written thought to
                                // a convenience is the one thing capture
                                // may not do (rule 1).
                                onStartTask = {
                                    destination = Destination.Capture
                                    panelKey = null
                                    draft = taskDraft(draft)
                                    focusComposer += 1
                                },
                            )
                        } else {
                            when (destination) {
                                Destination.Capture -> CaptureScreen(
                                    state = state,
                                    draft = draft,
                                    onDraftChange = { draft = it },
                                    onCommit = {
                                        val text = draft.trim()
                                        if (text.isNotEmpty()) {
                                            draft = ""
                                            state.captureText(text)
                                        }
                                    },
                                    onOpenNote = { screen = Screen.Read(it) },
                                    focusNonce = focusComposer,
                                    micNonce = askForMic,
                                    transcription = transcription,
                                    moonshine = moonshine,
                                )
                                Destination.Notes ->
                                    NotesScreen(state, onOpenNote = { screen = Screen.Read(it) })
                                Destination.Search ->
                                    SearchScreen(state, onOpenNote = { screen = Screen.Read(it) })
                            }
                        }
                        is Screen.Read -> ReadScreen(
                            state,
                            open.relPath,
                            onOpenNote = { screen = Screen.Read(it) },
                        )
                        is Screen.Edit -> EditScreen(
                            state = state,
                            relPath = open.relPath,
                            // A rename moves the file under the open
                            // buffer, so the screen follows it rather
                            // than holding a path nothing answers.
                            onRenamed = { renamed -> screen = Screen.Edit(renamed) },
                            transcription = transcription,
                        )
                        is Screen.Settings -> SettingsScreen(
                            state = state,
                            route = open.route,
                            onNavigate = { screen = Screen.Settings(it) },
                            theme = theme,
                            onThemeChange = { scope.launch { prefs.setTheme(it) } },
                            idleTimeout = idleTimeout,
                            onIdleTimeoutChange = { scope.launch { prefs.setIdleTimeout(it) } },
                            onExport = { exporter.launch(exportFileName(LocalDate.now().toString())) },
                            moonshine = moonshine,
                        )
                    }
                }
                // The bottom is mode-swapped, never stacked (§ 1): while
                // the keyboard is up the composer owns the bottom of the
                // screen and the bar stands aside.
                if (screen is Screen.Home && !WindowInsets.isImeVisible) {
                    BottomBar(
                        destination = destination,
                        panels = panels,
                        activePanelKey = activePanel?.key,
                        onSelectDestination = {
                            destination = it
                            panelKey = null
                        },
                        onSelectPanel = { panelKey = it },
                    )
                }
            }

            if (!noticeSeen) {
                VaultNotice { scope.launch { prefs.markVaultNoticeSeen() } }
            }
        }
    }

    BackHandler(enabled = screen !is Screen.Home) { screen = backFrom(screen) }
}

private fun backFrom(screen: Screen): Screen = when (screen) {
    is Screen.Settings -> if (screen.route == SettingsRoute.Index) {
        Screen.Home
    } else {
        Screen.Settings(SettingsRoute.Index)
    }
    // Back out of the editor is back to the note, not out of the note.
    // The buffer is saved on the way, by the editor's own dispose.
    is Screen.Edit -> Screen.Read(screen.relPath)
    else -> Screen.Home
}

@Composable
private fun TopBar(
    screen: Screen,
    homeLabel: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onEdit: (String) -> Unit = {},
    onDone: (String) -> Unit = {},
) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (screen is Screen.Home) {
            Spacer(Modifier.width(8.dp))
        } else {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "back",
                    tint = colours.onSurfaceVariant,
                )
            }
        }
        Text(
            when (screen) {
                Screen.Home -> homeLabel
                is Screen.Read -> noteTitle(screen.relPath)
                // The editor shows the note's name in its own title
                // field, where it can be typed over, so the bar names
                // the mode instead of repeating it.
                is Screen.Edit -> "Editing"
                is Screen.Settings -> "Settings"
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        when (screen) {
            is Screen.Read -> IconButton(
                onClick = { onEdit(screen.relPath) },
                modifier = Modifier.testTag("edit"),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = "edit", tint = colours.onSurfaceVariant)
            }
            is Screen.Edit -> IconButton(
                onClick = { onDone(screen.relPath) },
                modifier = Modifier.testTag("done"),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = "done", tint = colours.primary)
            }
            else -> Unit
        }
        if (screen !is Screen.Settings) {
            IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                Icon(Icons.Outlined.Settings, contentDescription = "settings", tint = colours.onSurfaceVariant)
            }
        }
    }
}

/// The bar (ADR 0017 § 1): three shell destinations, then whatever the
/// `navigation` slot contributes, capped at [MOBILE_NAV_PANELS].
///
/// The contributed half is **data**, not a hard-coded Agenda tab: the
/// panels come from the live plugin list, their labels are their own
/// contributions' verbatim, and a plugin switched off, removed, or with
/// its panel setting off contributes nothing — so the tab is simply not
/// drawn. That is the whole of "Agenda leaves the bar with the tasks
/// plugin".
///
/// A shell destination is selected iff no contributed one is, which is why
/// `activePanelKey` is consulted before `current`: selecting Agenda must
/// not leave Capture looking selected too.
@Composable
internal fun BottomBar(
    destination: Destination,
    panels: List<NavigationPanel>,
    activePanelKey: String?,
    onSelectDestination: (Destination) -> Unit,
    onSelectPanel: (String) -> Unit,
) {
    NavigationBar(Modifier.testTag("nav")) {
        for (entry in Destination.entries) {
            NavigationBarItem(
                selected = activePanelKey == null && entry == destination,
                onClick = { onSelectDestination(entry) },
                icon = { Icon(destinationIcon(entry), contentDescription = entry.label) },
                label = { Text(entry.label) },
            )
        }
        for (panel in panels) {
            NavigationBarItem(
                selected = panel.key == activePanelKey,
                onClick = { onSelectPanel(panel.key) },
                icon = { Icon(navigationIcon(panel.key), contentDescription = panel.label) },
                label = { Text(panel.label) },
            )
        }
    }
}

private fun destinationIcon(destination: Destination): ImageVector = when (destination) {
    Destination.Capture -> Icons.Outlined.Edit
    Destination.Notes -> Icons.Outlined.Description
    Destination.Search -> Icons.Outlined.Search
}

/// A contributed destination's icon. Keyed rather than declared, exactly
/// as the desktop rail keys it (§ 7: in v1 the behaviour behind a slot id
/// is Kotlin the shell owns). A key this host does not know gets the
/// plugin glyph, so an unrecognised destination still looks like what it
/// is.
private fun navigationIcon(key: String): ImageVector = when (key) {
    TASKS_RAIL_PANEL -> Icons.Outlined.CheckBox
    else -> Icons.Outlined.Extension
}

/// What a contributed destination draws. One key is known to this host in
/// v1 — the tasks agenda — and anything else is a destination the plugin
/// named and this shell has no screen for, which is said plainly rather
/// than shown as a blank page.
@Composable
internal fun NavigationPanelScreen(
    key: String,
    state: GiluneState,
    onOpenNote: (String) -> Unit,
    onStartTask: () -> Unit = {},
) {
    val collection = BUNDLED_PLUGIN_EXTENSIONS.pageCollections(state.plugins).singleOrNull { it.collection.id == key }
    if (collection != null) {
        PageCollectionPanel(collection, state, onOpenNote, Modifier.fillMaxSize())
        return
    }
    when (key) {
        TASKS_RAIL_PANEL -> AgendaScreen(state, onOpenNote, onStartTask)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "This shell has no screen for “$key”.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/// First run says the vault truth plainly rather than letting the user
/// infer the desktop promise (§ 5).
@Composable
private fun VaultNotice(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // The first thing the app ever shows is also the first place it
        // says who it is, so the mark goes here rather than nowhere.
        icon = { GiluneLogo() },
        title = { Text("About your vault") },
        text = { Text(VAULT_TRUTH, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
