// Desktop entry point and window scaffold: navigation rail, document
// pane, and a capture bar that is always there (ADR 0011). Settings is a
// surface over the window, not a dialog, because it holds the plugin
// panel and will hold devices next.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        val dark = when (theme) {
            ThemeChoice.System -> isSystemInDarkTheme()
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Gilune",
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
        ) {
            GiluneTheme(dark) {
                Surface(Modifier.fillMaxSize()) {
                    key(vaultDir) {
                        DesktopApp(
                            vaultDir = vaultDir,
                            theme = theme,
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
    onThemeChange: (ThemeChoice) -> Unit,
    onVaultChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember { GiluneState(vaultDir, scope) }
    val editor = remember { EditorState() }
    val tree = remember { TreeState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state.openVault { gilune ->
            scope.launch(Dispatchers.IO) { startTranscription(state, gilune) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Gilune", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.sync() }) { Text("Sync", fontSize = 12.sp) }
            TextButton(onClick = { showSettings = true }) { Text("Settings", fontSize = 12.sp) }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().weight(1f)) {
            VaultTree(
                state = state,
                tree = tree,
                onOpen = { editor.open(state.vaultDir, it) },
                modifier = Modifier.width(240.dp).fillMaxHeight(),
            )
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            NoteEditor(state, editor, Modifier.weight(1f).fillMaxHeight())
        }
        HorizontalDivider()
        CaptureBar(state, DesktopAudioRecorder(), Modifier.fillMaxWidth())
    }

    if (showSettings) {
        SettingsPane(
            state = state,
            vaultDir = vaultDir,
            onVaultChange = { showSettings = false; onVaultChange(it) },
            theme = theme,
            onThemeChange = onThemeChange,
            onClose = { showSettings = false },
        )
    }
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
