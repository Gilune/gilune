// Bringing the realtime provider up on the phone (ADR 0018 § 4, § 5).
//
// The deliberate parallel with `Transcription.kt` is the point: this is
// the same three-step shape — decide without touching anything, fetch
// under consent, then be ready — because it is the same policy applied to
// a second provider. Where the two differ, they differ for a reason
// recorded at the difference.
//
// Four rules, and the last is the one that makes R1 shippable:
//
//  - **Nothing downloads because the app opened.** The 45 MB ask waits on
//    the plugin's `network.request` grant, which is never auto-granted,
//    and on Wi-Fi by default.
//  - **Nothing downloads for a switched-off feature.** In R1 the model's
//    only use is the caption, so a caption that is off does not fetch a
//    model against a future it might be turned on.
//  - **The caption is display, and the download is not on any capture
//    path.** A recording made while the model is arriving is unaffected;
//    it simply has no caption.
//  - **This plugin writes nothing, anywhere.** No processor, no
//    subscription, no events (see `moonshineDeclaration`). The committed
//    transcript is still whisper's. R1 changes what a person *sees* while
//    speaking and nothing about what is *kept* — which is why it can ship
//    before R2's kernel work exists.

package org.gilune.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginInfo
import java.io.File

/// What the shell should do about the realtime provider right now,
/// decided without touching the network, the disk or the kernel.
sealed interface MoonshinePlan {
    /// Not installed, removed, or switched off.
    data object NoProvider : MoonshinePlan

    /// Installed and on, but the caption — its only feature in R1 — is
    /// switched off. Distinct from [NoProvider] so the plugin's own screen
    /// can say which of the two switches is the one holding it back.
    data object CaptionOff : MoonshinePlan

    /// The model is not on disk and `network.request` has not been
    /// granted. § 4: consent is read as a **positive grant**, never as the
    /// absence of an outstanding one.
    data class NeedsConsent(val model: MoonshineModel) : MoonshinePlan

    /// Consented, but this connection is metered and Wi-Fi-only is on
    /// (the shipped default). Nothing is fetched and nothing is wrong.
    data class WaitingForWifi(val model: MoonshineModel) : MoonshinePlan

    data class Download(val model: MoonshineModel) : MoonshinePlan

    /// The files are on disk and verified. Captions can run.
    data class Ready(val model: MoonshineModel) : MoonshinePlan
}

/// The decision, pure over its three inputs.
///
/// [metered] is what the *device* says about the connection, not what the
/// setting says about metered connections — the setting is read here, so
/// the caller does not have to know the policy.
fun moonshinePlan(
    plugins: List<PluginInfo>,
    modelOnDisk: (MoonshineModel) -> Boolean,
    metered: Boolean,
): MoonshinePlan {
    val installed = plugins.firstOrNull { it.pluginId == MOONSHINE_PLUGIN }
    if (installed == null || installed.state != "enabled") return MoonshinePlan.NoProvider
    if (!plugins.moonshineCaption()) return MoonshinePlan.CaptionOff
    val chosen = plugins.moonshineModel()
    if (modelOnDisk(chosen)) return MoonshinePlan.Ready(chosen)
    if (NETWORK_CAPABILITY !in installed.granted) return MoonshinePlan.NeedsConsent(chosen)
    if (plugins.moonshineWifiOnly() && metered) return MoonshinePlan.WaitingForWifi(chosen)
    return MoonshinePlan.Download(chosen)
}

/// Where this device is with the realtime provider — the only thing the
/// plugin screen is allowed to claim.
enum class MoonshinePhase {
    /// Before the vault has produced a registry. Says nothing.
    Unknown,

    /// No provider, no consent, no Wi-Fi, or the caption is off. All four
    /// mean the same thing to a recording: no caption, and nothing wrong.
    Absent,

    Downloading,

    /// The model is on disk. A recording will caption.
    Ready,

    /// It was tried and it did not work. Recordings are unaffected.
    Failed,
}

/// The phase, the download's progress, and the last thing that went wrong.
/// A fact about *this shell on this device*, exactly as
/// [TranscriptionRuntime] is.
class MoonshineRuntime {
    var phase by mutableStateOf(MoonshinePhase.Unknown)
        internal set

    var progress by mutableStateOf<MoonshineProgress?>(null)
        internal set

    var model by mutableStateOf<MoonshineModel?>(null)
        internal set

    /// What to tell the person on the plugin screen. Deliberately not
    /// routed to the composer's notice line: the caption is an
    /// enhancement, and a 45 MB download is not something to narrate over
    /// the place where thoughts are captured.
    var notice by mutableStateOf("")
        internal set

    /// Where the model lives once [phase] is [MoonshinePhase.Ready] — the
    /// caption's one input, held here so the capture screen does not
    /// recompute a path.
    var modelDir by mutableStateOf<File?>(null)
        internal set

    val ready: Boolean get() = phase == MoonshinePhase.Ready && modelDir != null

    internal val working = java.util.concurrent.atomic.AtomicBoolean(false)
}

/// Is this connection one the user pays by the byte for? Unknown counts as
/// metered: § 4's default exists to protect somebody's data allowance, and
/// a check that cannot answer must not be the reason it is spent.
fun connectionIsMetered(context: Context): Boolean = runCatching {
    context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
}.getOrDefault(true)

/// Register the realtime provider. **Android only, and this is the only
/// call site** — `openVault` registers the shared declarations for both
/// shells, and Moonshine is not one of them: the AAR is Android-only and
/// ADR 0018 § 4 keeps whisper as the desktop engine.
///
/// Registering is idempotent and never resurrects a plugin the user
/// removed. **Nothing is granted here**: its one capability is
/// `network.request`, which under the host consent policy always waits for
/// an explicit yes — unlike the voice adapter's local recording
/// capability, which installing the app consents to.
fun registerMoonshine(state: GiluneState, gilune: Gilune) {
    runCatching {
        gilune.registerPlugin(moonshineDeclaration())
        state.refresh()
    }
}

/// Bring the provider up: fetch the model if the plan says so, and report
/// where we are. Off the capture path entirely, and idempotent — the
/// caller re-runs it whenever the plan's inputs change.
fun startMoonshine(context: Context, state: GiluneState, runtime: MoonshineRuntime) {
    if (!runtime.working.compareAndSet(false, true)) return
    try {
        bringUpMoonshine(context, state, runtime)
    } finally {
        runtime.working.set(false)
    }
}

private fun bringUpMoonshine(context: Context, state: GiluneState, runtime: MoonshineRuntime) {
    val plugins = state.plugins.toList()
    val plan = moonshinePlan(
        plugins,
        modelOnDisk = { moonshineModelPresent(moonshineModelDir(context, it)) },
        metered = connectionIsMetered(context),
    )
    when (plan) {
        MoonshinePlan.NoProvider -> {
            runtime.phase = MoonshinePhase.Absent
            runtime.model = null
            runtime.modelDir = null
            runtime.notice = ""
        }
        MoonshinePlan.CaptionOff -> {
            runtime.phase = MoonshinePhase.Absent
            runtime.modelDir = null
            runtime.notice = "The live caption is off, so its model is not downloaded."
        }
        is MoonshinePlan.NeedsConsent -> {
            runtime.phase = MoonshinePhase.Absent
            runtime.model = plan.model
            runtime.modelDir = null
            runtime.notice = consentNotice(plan.model)
        }
        is MoonshinePlan.WaitingForWifi -> {
            runtime.phase = MoonshinePhase.Absent
            runtime.model = plan.model
            runtime.modelDir = null
            runtime.notice =
                "Waiting for Wi-Fi to download the ${plan.model.size} caption model. " +
                    "Turn off “Only download on Wi-Fi” to use this connection."
        }
        is MoonshinePlan.Download -> {
            runtime.model = plan.model
            runtime.phase = MoonshinePhase.Downloading
            runtime.progress = null
            runtime.notice = "Downloading the caption model (${plan.model.size}, one time)…"
            val dir = moonshineModelDir(context, plan.model)
            val fetched = runCatching {
                val assets = moonshineManifest(plan.model)
                fetchMoonshineModel(assets, dir) { runtime.progress = it }
                markMoonshineModelReady(dir)
            }
            if (fetched.isFailure) {
                runtime.phase = MoonshinePhase.Failed
                runtime.progress = null
                runtime.notice =
                    "Could not download the caption model (${fetched.exceptionOrNull()?.message}). " +
                        "Your recordings are unaffected."
                return
            }
            ready(runtime, plan.model, dir)
        }
        is MoonshinePlan.Ready -> ready(runtime, plan.model, moonshineModelDir(context, plan.model))
    }
}

private fun ready(runtime: MoonshineRuntime, model: MoonshineModel, dir: File) {
    runtime.model = model
    runtime.modelDir = dir
    runtime.progress = null
    runtime.phase = MoonshinePhase.Ready
    runtime.notice = ""
}

/// The consent ask, with the number in it (ADR 0017 § 4 makes the size
/// part of the ask, and R0 corrected what the number is: 45 MB across 8
/// files, not the 77.7 MB the ADR estimated).
fun consentNotice(model: MoonshineModel): String =
    "The live caption needs a ${model.size} model. Allow network.request above to download it once."
