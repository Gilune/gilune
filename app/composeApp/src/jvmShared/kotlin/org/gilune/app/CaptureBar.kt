// The capture surface, shared by desktop and Android. Always present and
// never more than a keystroke away (constitution rule 1 as UI): on
// desktop it sits across the foot of the window, on Android it is the
// screen. The microphone is drawn only when a plugin contributes it, so
// removing the voice-capture adapter removes the button (ADR 0011).

package org.gilune.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val CAPTURE_CHUNK = 64 * 1024

@Composable
fun CaptureBar(
    state: GiluneState,
    recorder: AudioRecorder?,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    val ready = state.gilune.value != null
    val micOffered =
        recorder != null && state.plugins.contributes(Slots.CAPTURE_ACTIONS, RECORD_VOICE_ACTION)

    fun capture() {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            draft = ""
            state.captureText(text)
        }
    }

    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("What's on your mind?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { capture() }),
            )
            if (micOffered) {
                OutlinedButton(
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
                ) { Text(if (recording) "Stop" else "Record") }
            }
            Button(onClick = { capture() }, enabled = ready) { Text("Capture") }
        }
        Text(
            state.message.value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
}

/// The capture feed. Desktop shows the vault tree instead; this is the
/// mobile surface and the fallback where there is no file browser.
@Composable
fun RecentList(
    state: GiluneState,
    onOpenNote: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.recent, key = { it.objectId }) { source ->
            Column {
                val rel = source.projectionRelPath
                if (rel != null && onOpenNote != null) {
                    TextButton(onClick = { onOpenNote(rel) }) {
                        Text(source.preview.ifEmpty { "(empty)" })
                    }
                } else {
                    Text(source.preview.ifEmpty { "(empty)" })
                }
                Text(
                    rel ?: source.blobRelPath ?: source.objectId,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
