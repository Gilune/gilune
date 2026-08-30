// The Gilune capture screen, shared by desktop and Android: capture a
// thought (text or voice), see recent captures, sync the vault. The
// shell renders and forwards; every decision lives across the boundary
// (conventions § Kotlin).

package org.gilune.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.SourceSummary

const val CAPTURE_CHUNK = 64 * 1024

@Composable
fun AppScreen(vaultDir: String, recorder: AudioRecorder?) {
    var gilune by remember { mutableStateOf<Gilune?>(null) }
    var draft by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("opening vault…") }
    val recent = remember { mutableStateListOf<SourceSummary>() }
    val scope = rememberCoroutineScope()

    fun refresh(g: Gilune) {
        recent.clear()
        recent.addAll(g.recentSources(50u))
    }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val g = Gilune.open(vaultDir)
                g.project()
                refresh(g)
                gilune = g
                message = "vault: ${g.vaultPath()}"
            } catch (e: Throwable) {
                message = "failed to open vault: ${e.message}"
            }
        }
    }

    fun captureText() {
        val g = gilune ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        scope.launch(Dispatchers.IO) {
            try {
                g.captureText(text)
                g.project()
                refresh(g)
                message = "captured"
            } catch (e: Throwable) {
                message = "capture failed: ${e.message}"
            }
        }
    }

    fun toggleRecording() {
        val g = gilune ?: return
        val rec = recorder ?: return
        if (!recording) {
            try {
                rec.start()
                recording = true
                message = "recording…"
            } catch (e: Throwable) {
                message = "microphone unavailable: ${e.message}"
            }
            return
        }
        recording = false
        scope.launch(Dispatchers.IO) {
            try {
                val audio = rec.stop()
                val wav = wavBytes(audio)
                val writer = g.beginCapture("wav")
                var offset = 0
                while (offset < wav.size) {
                    val end = minOf(offset + CAPTURE_CHUNK, wav.size)
                    writer.writeChunk(wav.copyOfRange(offset, end))
                    offset = end
                }
                writer.finish()
                g.project()
                refresh(g)
                message = "voice captured (${wav.size / 1024} KiB)"
            } catch (e: Throwable) {
                message = "voice capture failed: ${e.message}"
            }
        }
    }

    fun syncVault() {
        val g = gilune ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val r = g.reconcile()
                g.project()
                refresh(g)
                message =
                    "synced: ${r.edits} edits, ${r.imports} imports, ${r.tombstoneProposals} proposals"
            } catch (e: Throwable) {
                message = "sync failed: ${e.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What's on your mind?") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { captureText() }, enabled = gilune != null) { Text("Capture") }
            if (recorder != null) {
                Button(onClick = { toggleRecording() }, enabled = gilune != null) {
                    Text(if (recording) "Stop & save" else "Record voice")
                }
            }
            Button(onClick = { syncVault() }, enabled = gilune != null) { Text("Sync vault") }
        }
        Text(message, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recent, key = { it.objectId }) { source ->
                Column {
                    Text(source.preview.ifEmpty { "(empty)" })
                    Text(
                        source.projectionRelPath ?: source.blobRelPath ?: source.objectId,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
