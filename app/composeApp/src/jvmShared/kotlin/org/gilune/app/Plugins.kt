// What the shell contributes and how it reads contributions back. The
// shell renders slots; what fills them comes from the registry, so
// removing a plugin removes its controls (ADR 0011). Nothing here decides
// behaviour — the kernel owns whether a plugin may run.

package org.gilune.app

import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo

object Slots {
    const val CAPTURE_ACTIONS = "capture.actions"
    const val NOTE_ACTIONS = "note.actions"
    const val NAVIGATION = "navigation"
    const val COMMAND_PALETTE = "command.palette"
    const val SETTINGS_FORM = "settings.form"
}

const val VOICE_CAPTURE_PLUGIN = "org.memory.voice-capture"
const val RECORD_VOICE_ACTION = "record-voice"
const val DICTATE_ACTION = "dictate"

/// Declared by the kernel binary when it carries a transcriber, so the
/// shell only ever reads it back.
const val TRANSCRIPTION_PLUGIN = "org.memory.transcription"
const val NETWORK_CAPABILITY = "network.request"

/// The microphone adapter. Its code lives in the shell — the kernel never
/// touches an OS API (ADR 0001) — so the shell is what declares it.
/// Capture never depends on an interpreter, which is why this is separate
/// from the transcription provider: remove transcription and recording
/// still works, and the audio waits for a transcriber that may arrive
/// later or live on another device.
fun voiceCaptureDeclaration(): PluginDeclaration =
    PluginDeclaration(
        pluginId = VOICE_CAPTURE_PLUGIN,
        name = "Voice capture",
        `class` = "adapter",
        profile = "bundled",
        required = false,
        processorId = null,
        subscribes = null,
        emits = emptyList(),
        capabilities = listOf("platform.audio-record"),
        contributions = listOf(
            PluginContribution(
                slot = Slots.CAPTURE_ACTIONS,
                id = RECORD_VOICE_ACTION,
                label = "New note from speech",
            ),
            PluginContribution(
                slot = Slots.NOTE_ACTIONS,
                id = DICTATE_ACTION,
                label = "Dictate at the cursor",
            ),
        ),
    )

/// Contributions to one slot from every plugin that is switched on. A
/// disabled or removed plugin contributes nothing, which is what makes
/// its controls disappear.
fun List<PluginInfo>.contributionsTo(slot: String): List<PluginContribution> =
    filter { it.state == "enabled" }.flatMap { plugin ->
        plugin.contributions.filter { it.slot == slot }
    }

fun List<PluginInfo>.contributes(slot: String, id: String): Boolean =
    contributionsTo(slot).any { it.id == id }
