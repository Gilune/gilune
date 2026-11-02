// Settings ▸ Transcription (ADR 0033 § 6): one engine while you speak, one
// for recordings, chosen from every engine the transcription plugins
// publish. Nothing downloads from here — an engine whose model is not on
// this computer opens its plugin's own screen, where the permission, the
// licence and the progress live.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TranscriptionPage(state: GiluneState, onOpenPlugin: (String) -> Unit) {
    val colours = MaterialTheme.colorScheme
    val plugins = state.plugins.toList()
    val engines = remember(plugins) { plugins.transcriptionEngines() }
    val onDisk = remember(engines, state.vaultCursor.value, state.refreshCount.value) {
        engines.associateWith { runCatching { engineOnDisk(it) }.getOrDefault(false) }
    }
    val live = plugins.liveChoice()
    val recordings = plugins.recordingsChoice()

    Text("Transcription", style = MaterialTheme.typography.titleMedium)
    if (engines.isEmpty()) {
        Text(
            "No transcription plugin is installed, so recordings are kept without words.",
            fontSize = 12.sp,
            color = colours.onSurfaceVariant,
        )
        return
    }
    Text(
        "Choose one engine for words while you speak and one for recordings. They can come from " +
            "different plugins: a fast live engine for dictation and an accurate one for recordings.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )

    fun choose(role: TranscriptionRole, engine: TranscriptionEngine?) {
        state.pluginActionTelling("choosing an engine") { g ->
            when {
                engine == null -> chooseLiveEngine(g, null)
                role == TranscriptionRole.LIVE -> chooseLiveEngine(g, engine.choice())
                else -> chooseRecordingsEngine(g, engine.choice())
            }
        }
        // The download, its permission and its progress live on the
        // plugin's own screen.
        if (engine != null && onDisk[engine] != true) onOpenPlugin(engine.pluginId)
    }

    HorizontalDivider(color = colours.outlineVariant)
    Text("While you speak", style = MaterialTheme.typography.titleSmall)
    Text(
        "Words appear in the note as you dictate. Off, dictated words arrive when you stop.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    EngineRow(
        title = "Off",
        tag = null,
        guidance = "Dictated words arrive when you stop, from the engine chosen for recordings.",
        status = null,
        selected = live == null,
        testTag = "engine-live-off",
    ) { choose(TranscriptionRole.LIVE, null) }
    for (engine in engines.forRole(TranscriptionRole.LIVE)) {
        val readiness = engineReadiness(engine, plugins, onDisk[engine] == true)
        EngineRow(
            title = engine.label,
            tag = engine.pluginName,
            guidance = engine.guidance,
            status = readiness.words(engine),
            selected = live == engine.choice(),
            testTag = "engine-live-${engine.pluginId}-${engine.engine}",
        ) { choose(TranscriptionRole.LIVE, engine) }
    }
    plugins.liveHolderSwitchedOff()?.let {
        Note("Nothing chosen: ${it.name} is switched off. Switch it on, or choose another engine.")
    }
    if (recordings?.pluginId == TRANSCRIPTION_PLUGIN) Note(WHISPER_ONE_MODEL_SENTENCE)

    HorizontalDivider(color = colours.outlineVariant)
    Text("Recordings", style = MaterialTheme.typography.titleSmall)
    Text(
        "Voice notes, recordings you load, Transcribe again, and the copy of every dictation that is " +
            "kept with the note. Choosing an engine from another plugin hands it the recordings still " +
            "waiting for words, and the status line says how many.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    if (recordings == null) {
        Note("Nothing chosen — recordings are kept and wait.", Modifier.testTag("recordings-nothing-chosen"))
    }
    for (engine in engines.forRole(TranscriptionRole.RECORDINGS)) {
        val readiness = engineReadiness(engine, plugins, onDisk[engine] == true)
        EngineRow(
            title = engine.label,
            tag = engine.pluginName,
            guidance = engine.guidance,
            status = readiness.words(engine),
            selected = recordings == engine.choice(),
            testTag = "engine-recordings-${engine.pluginId}-${engine.engine}",
        ) { choose(TranscriptionRole.RECORDINGS, engine) }
    }
    if (live?.pluginId == TRANSCRIPTION_PLUGIN) Note(WHISPER_ONE_MODEL_SENTENCE)

    HorizontalDivider(color = colours.outlineVariant)
    Text(
        speakerLabelsLine(plugins),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
        modifier = Modifier.testTag("transcription-speaker-labels"),
    )
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/// One radio row: the engine's name, its plugin as a small tag, the
/// guidance sentence, and its state at the right.
@Composable
private fun EngineRow(
    title: String,
    tag: String?,
    guidance: String,
    status: String?,
    selected: Boolean,
    testTag: String,
    onChoose: () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onChoose),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onChoose, modifier = Modifier.testTag(testTag))
        Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 13.sp)
                if (tag != null) {
                    Text(
                        tag,
                        fontSize = 11.sp,
                        color = colours.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(colours.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(guidance, fontSize = 12.sp, lineHeight = 17.sp, color = colours.onSurfaceVariant)
        }
        if (status != null) {
            Text(
                status,
                fontSize = 11.sp,
                color = if (status == "Ready") colours.onSurfaceVariant else colours.primary,
                modifier = Modifier.padding(top = 14.dp, end = 4.dp).testTag("$testTag-state"),
            )
        }
    }
}
