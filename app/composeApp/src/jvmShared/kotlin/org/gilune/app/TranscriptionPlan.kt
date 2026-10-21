// Whether this shell can turn speech into words right now, decided the
// same way on both shells (ADR 0017 § 4).
//
// The transcription plugin being switched on is not the same as a
// transcriber being able to run: the model has to be on disk, which needs
// the plugin's `network.request` consent the first time, and then the
// kernel has to accept it. Each shell keeps one [TranscriptionRuntime]
// that says which of those it has reached, and nothing short of
// [TranscriptionPhase.Ready] may promise words.
//
// This used to live in the Android shell alone. The desktop asked its
// question once, when the vault opened, and kept the answer in a status
// line that the next message overwrote — so a desktop whose model was
// never allowed to download recorded speech that nothing would ever
// transcribe, and nothing on screen said so (owner report, 2026-10-21).

package org.gilune.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uniffi.gilune_ffi.PluginInfo

/// What the shell should do about transcription right now, decided
/// without touching the network, the disk or the kernel — so the decision
/// is testable and the I/O around it is thin.
sealed interface TranscriptionPlan {
    /// Not installed, removed, or switched off. Nothing to do, and
    /// nothing to apologise for: the recording still commits.
    data object NoTranscriber : TranscriptionPlan

    /// The model is not on disk and the download has not been consented
    /// to. § 4: "nothing downloads because the app opened".
    data class NeedsConsent(val model: WhisperModel) : TranscriptionPlan

    data class Download(val model: WhisperModel) : TranscriptionPlan

    /// The file is already there — switching models back and forth costs
    /// nothing after the first time.
    data class Enable(val model: WhisperModel) : TranscriptionPlan
}

/// The decision. Consent is read as a **positive grant** rather than as
/// the absence of an outstanding one: a capability that is not declared
/// at all leaves both lists empty, and "no explicit yes" must fail towards
/// not downloading tens of megabytes.
///
/// `chosen` is the shell's read of the model setting, because the two
/// shells default differently (ADR 0017 § Ratification 5).
fun transcriptionPlan(
    plugins: List<PluginInfo>,
    chosen: (List<PluginInfo>) -> WhisperModel,
    modelOnDisk: (WhisperModel) -> Boolean,
): TranscriptionPlan {
    val installed = plugins.firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }
    if (installed == null || installed.state != "enabled") return TranscriptionPlan.NoTranscriber
    val model = chosen(listOf(installed))
    if (modelOnDisk(model)) return TranscriptionPlan.Enable(model)
    if (NETWORK_CAPABILITY !in installed.granted) return TranscriptionPlan.NeedsConsent(model)
    return TranscriptionPlan.Download(model)
}

/// Exactly the inputs [transcriptionPlan] reads from the registry, as one
/// value. A shell keys its single bring-up call site on this, so allowing
/// `network.request`, switching the plugin on or choosing another model
/// asks again at once rather than at the next launch.
fun transcriptionPlanKey(plugins: List<PluginInfo>): String? =
    plugins.firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }
        ?.let { "${it.state}|${it.granted.sorted().joinToString(",")}|${it.settings}" }

/// Where this device actually is, which is the only thing the UI is
/// allowed to claim (§ 4: "the pending state is shown and is never
/// written to the file").
///
/// [Ready] is the load-bearing one. It means `enableWhisper` returned on
/// this handle — a model file is open and a queued job can genuinely run.
/// Nothing short of that may render "transcribing…", which is the whole
/// point of the enabled-≠-able rule one level deeper than the plugin
/// switch.
enum class TranscriptionPhase {
    /// Before the vault is open and the plan has been read. Says nothing.
    Unknown,

    /// No transcriber, or one waiting on consent it has not been given.
    Absent,

    Downloading,

    /// The model is loaded. Jobs run.
    Ready,

    /// It was tried and it did not work. Captures are unaffected.
    Failed,
}

/// The phase, plus how far a download has got. Held apart from
/// [GiluneState] because it is a fact about *this shell on this device*,
/// and the kernel has no opinion about it.
class TranscriptionRuntime {
    var phase by mutableStateOf(TranscriptionPhase.Unknown)

    /// Percent complete, or null when the server did not say how big the
    /// file was. A download with no total still reports bytes in the
    /// message line rather than pretending to a percentage.
    var percent by mutableStateOf<Int?>(null)

    /// The model being fetched or loaded, for the settings screen.
    var model by mutableStateOf<WhisperModel?>(null)

    /// Why the last attempt did not reach [TranscriptionPhase.Ready], in
    /// the kernel's or the network's words; null otherwise.
    var failure by mutableStateOf<String?>(null)

    /// Whether the last plan was waiting on the download permission —
    /// the one [TranscriptionPhase.Absent] a person can fix with a click.
    var needsConsent by mutableStateOf(false)

    val ready: Boolean get() = phase == TranscriptionPhase.Ready

    /// Belt and braces around the download. The shell has one call site
    /// and keys it so it cannot re-enter, but a partially written model
    /// is the one failure here that would be silent and durable, so the
    /// guard is stated rather than inferred from the caller's shape.
    val working = java.util.concurrent.atomic.AtomicBoolean(false)
}

/// Why speech will not become words here yet, in a person's words — or
/// null when it will. What a microphone says before anyone speaks into
/// it, so nobody records a dictation that cannot land.
fun TranscriptionRuntime.unreadyReason(): String? = when (phase) {
    TranscriptionPhase.Ready -> null
    TranscriptionPhase.Unknown -> "transcription is still starting"
    TranscriptionPhase.Downloading ->
        "the transcription model is still downloading" + (percent?.let { " ($it%)" } ?: "")
    TranscriptionPhase.Failed ->
        "transcription is unavailable here" + (failure?.let { " ($it)" } ?: "")
    TranscriptionPhase.Absent ->
        if (needsConsent) {
            "transcription needs its model — allow the download in Settings ▸ Plugins ▸ Transcription"
        } else {
            "no transcription plugin is switched on — Settings ▸ Plugins"
        }
}
