// The capture surface, shared by desktop and Android: capture a thought
// (text or voice), see recent captures, sync the vault.

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val CAPTURE_CHUNK = 64 * 1024

@Composable
fun CapturePanel(
    state: GiluneState,
    recorder: AudioRecorder?,
    onOpenNote: ((String) -> Unit)? = null,
) {
    var draft by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    val ready = state.gilune.value != null

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What's on your mind?") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        state.captureText(text)
                    }
                },
                enabled = ready,
            ) { Text("Capture") }
            if (recorder != null) {
                Button(
                    onClick = {
                        if (!recording) {
                            try {
                                recorder.start()
                                recording = true
                                state.message.value = "recording…"
                            } catch (e: Throwable) {
                                state.message.value = "microphone unavailable: ${e.message}"
                            }
                        } else {
                            recording = false
                            state.captureAudio(recorder.stop())
                        }
                    },
                    enabled = ready,
                ) { Text(if (recording) "Stop & save" else "Record voice") }
            }
            Button(onClick = { state.sync() }, enabled = ready) { Text("Sync vault") }
        }
        Text(state.message.value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        HorizontalDivider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.recent, key = { it.objectId }) { source ->
                Column {
                    val rel = source.projectionRelPath
                    if (rel != null && onOpenNote != null) {
                        androidx.compose.material3.TextButton(onClick = { onOpenNote(rel) }) {
                            Text(source.preview.ifEmpty { "(empty)" })
                        }
                    } else {
                        Text(source.preview.ifEmpty { "(empty)" })
                    }
                    Text(
                        rel ?: source.blobRelPath ?: source.objectId,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
