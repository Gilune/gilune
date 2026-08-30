// Desktop entry point: capture on the left, vault explorer + editor on
// the right, vault location changeable in settings (persisted via
// Preferences). Local transcription bootstraps itself: the Whisper model
// (~75 MiB, one-time) downloads to a device-local folder on first run.

package org.gilune.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.prefs.Preferences

private val prefs: Preferences = Preferences.userRoot().node("org/gilune/app")

private const val MODEL_URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"

private fun modelFile(): File =
    File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Gilune/models/ggml-tiny.bin")

fun main() {
    System.getProperty("compose.application.resources.dir")?.let { resources ->
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing == null) resources else "$resources;$existing",
        )
    }
    application {
        var vaultDir by remember {
            mutableStateOf(
                System.getProperty("gilune.vault")
                    ?: prefs.get("vaultDir", System.getProperty("user.home") + "/Gilune"),
            )
        }
        Window(onCloseRequest = ::exitApplication, title = "Gilune") {
            MaterialTheme {
                Surface {
                    key(vaultDir) {
                        DesktopApp(vaultDir) { newDir ->
                            prefs.put("vaultDir", newDir)
                            vaultDir = newDir
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopApp(vaultDir: String, onVaultChange: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { GiluneState(vaultDir, scope) }
    val editor = remember { EditorState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state.openVault { gilune ->
            // Transcription bootstrap, off the capture path by design.
            scope.launch(Dispatchers.IO) {
                try {
                    val model = modelFile()
                    if (!model.exists()) {
                        state.message.value =
                            "downloading transcription model (~75 MiB, one-time)…"
                        model.parentFile.mkdirs()
                        val tmp = File(model.parentFile, "${model.name}.part")
                        java.net.URI(MODEL_URL).toURL().openStream().use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        tmp.renameTo(model)
                    }
                    gilune.enableWhisper(model.absolutePath)
                    state.message.value = "vault: ${gilune.vaultPath()} — transcription ready"
                    state.sync(announce = false)
                } catch (e: Throwable) {
                    state.message.value =
                        "transcription unavailable (${e.message}); captures still work"
                }
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(Modifier.weight(0.45f)) {
            CapturePanel(state, DesktopAudioRecorder(), onOpenNote = { rel ->
                val file = File(state.vaultDir, rel)
                editor.openRelPath.value = rel
                editor.text.value = if (file.exists()) file.readText() else ""
                editor.dirty.value = false
            })
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(0.55f)) {
            VaultExplorer(state, editor)
        }
    }

    androidx.compose.foundation.layout.Box {
        TextButton(onClick = { showSettings = true }) { Text("⚙") }
    }

    if (showSettings) {
        var draft by remember { mutableStateOf(vaultDir) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Vault folder") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    showSettings = false
                    if (draft.isNotBlank() && draft != vaultDir) onVaultChange(draft.trim())
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } },
        )
    }
}
