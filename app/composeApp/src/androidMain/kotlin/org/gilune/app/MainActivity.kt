// The Android shell (ADR 0017 § 1). Home IS capture: the app opens on a
// composer, and everything else is somewhere you go from there.
//
// The window is edge-to-edge, because without it the IME insets never
// arrive at all, and every bottom-anchored surface pads for both the
// keyboard and the gesture area. The manifest supplies
// `windowSoftInputMode=adjustResize` and a DayNight window theme, so dark
// mode follows the system from the first painted pixel.

package org.gilune.app

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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
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

/// The shell's destinations. Search and Agenda arrive with MB3; the bar
/// renders what exists rather than a row of coming-soon tabs.
enum class Destination(val label: String) { Capture("Capture"), Notes("Notes") }

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

    LaunchedEffect(Unit) { state.openVault() }

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

    var destination by remember { mutableStateOf(Destination.Capture) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var draft by remember { mutableStateOf("") }

    // What the widget, the recording notification and the QS tile ask for
    // (§ 4). The decision is `launchFor`, which is pure and tested; all
    // that happens here is obeying it.
    var focusComposer by remember { mutableStateOf(0) }
    var askForMic by remember { mutableStateOf(0) }
    val request = launchRequest.value
    LaunchedEffect(request) {
        val asked = request ?: return@LaunchedEffect
        destination = asked.launch.tab
        screen = asked.launch.openNote?.let { Screen.Read(it) } ?: Screen.Home
        if (asked.launch.focusComposer) focusComposer += 1
        if (asked.launch.askForMic) askForMic += 1
    }

    // The widget's inbox count follows the vault. `vaultTick` is bumped by
    // every refresh, so this is "updated on app refresh" with nothing new
    // to remember to call.
    LaunchedEffect(state.vaultTick.value) { refreshWidgets(context) }

    // The idle timer (§ 1). The composer's own text is the only state it
    // governs, and expiry commits rather than discards.
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
                    }
                    backgroundedAt = null
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
                    destination = destination,
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
                        Screen.Home -> when (destination) {
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
                            )
                            Destination.Notes ->
                                NotesScreen(state, onOpenNote = { screen = Screen.Read(it) })
                        }
                        is Screen.Read -> ReadScreen(state, open.relPath)
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
                        )
                    }
                }
                // The bottom is mode-swapped, never stacked (§ 1): while
                // the keyboard is up the composer owns the bottom of the
                // screen and the bar stands aside.
                if (screen is Screen.Home && !WindowInsets.isImeVisible) {
                    BottomBar(destination) { destination = it }
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
    destination: Destination,
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
                Screen.Home -> destination.label
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

/// Capture and Notes today. Search and Agenda arrive with MB3 — the bar
/// renders the destinations that exist, and `navigation` contributions
/// are read but render no panel yet, so nothing here is a promise.
@Composable
private fun BottomBar(current: Destination, onSelect: (Destination) -> Unit) {
    NavigationBar(Modifier.testTag("nav")) {
        for (entry in Destination.entries) {
            NavigationBarItem(
                selected = entry == current,
                onClick = { onSelect(entry) },
                icon = {
                    Icon(
                        if (entry == Destination.Capture) Icons.Outlined.Edit else Icons.Outlined.Description,
                        contentDescription = entry.label,
                    )
                },
                label = { Text(entry.label) },
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
        title = { Text("About your vault") },
        text = { Text(VAULT_TRUTH, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
