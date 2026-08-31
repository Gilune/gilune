// The capture surface, shared by desktop and Android. Always present and
// never more than a keystroke away (constitution rule 1 as UI): on
// desktop it sits across the foot of the window, on Android it is the
// screen. The microphone is drawn only when a plugin contributes it, so
// removing the voice-capture adapter removes the button (ADR 0011).

package org.gilune.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val CAPTURE_CHUNK = 64 * 1024

@Composable
fun CaptureBar(
    state: GiluneState,
    recorder: AudioRecorder?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    showMessage: Boolean = true,
    shortcutHint: String? = null,
) {
    var draft by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    val ready = state.gilune.value != null
    val colours = MaterialTheme.colorScheme
    val micOffered =
        recorder != null && state.plugins.contributes(Slots.CAPTURE_ACTIONS, RECORD_VOICE_ACTION)

    fun capture() {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            draft = ""
            state.captureText(text)
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colours.onSurfaceVariant,
            )
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            capture()
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                enabled = ready,
                textStyle = TextStyle(fontSize = 13.sp, color = colours.onSurface),
                cursorBrush = SolidColor(colours.primary),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text("What's on your mind?", fontSize = 13.sp, color = colours.onSurfaceVariant)
                        }
                        inner()
                    }
                },
            )
            if (shortcutHint != null && draft.isEmpty()) {
                Text(
                    shortcutHint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colours.outline,
                )
            }
            if (micOffered) {
                IconButton(
                    onClick = {
                        if (!recording) {
                            try {
                                recorder.start()
                                recording = true
                                state.message.value = "recording… click the microphone to stop"
                            } catch (e: Throwable) {
                                state.message.value = "microphone unavailable: ${e.message}"
                            }
                        } else {
                            recording = false
                            state.captureAudio(recorder.stop())
                        }
                    },
                    enabled = ready,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = if (recording) "stop recording" else "record voice",
                        modifier = Modifier.size(18.dp),
                        tint = if (recording) colours.error else colours.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = { capture() },
                enabled = ready,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) { Text("Capture", fontSize = 12.sp) }
        }
        if (showMessage) {
            Text(
                state.message.value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colours.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp, bottom = 8.dp),
            )
        }
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
