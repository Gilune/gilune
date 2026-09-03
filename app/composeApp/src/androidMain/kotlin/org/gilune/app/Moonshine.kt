// Bringing the realtime provider up on the phone (ADR 0018 § 4, § 5).
//
// The deliberate parallel with `Transcription.kt` is the point: this is
// the same three-step shape — decide without touching anything, fetch
// under consent, then be ready — because it is the same policy applied to
// a second provider. Where the two differ, they differ for a reason
// recorded at the difference.
//
// Three rules, and the last one changed at R3:
//
//  - **Nothing downloads because the app opened.** The 45 MB ask waits on
//    the plugin's `network.request` grant, which is never auto-granted,
//    and on Wi-Fi by default.
//  - **Neither the caption nor the download is on any capture path.** A
//    recording made while the model is arriving is unaffected; it simply
//    has no caption, and its words wait for the model exactly as they
//    already wait for whisper's.
//  - **At R3 this plugin writes.** R1's rule here was that it wrote
//    nothing anywhere and the committed transcript stayed whisper's;
//    ADR 0018 § 6 R3 is where that stops being true. The declaration now
//    names a processor, and `MoonshineProvider.kt` claims its jobs and
//    submits their words. What is still true is that the *caption* writes
//    nothing: the kept words are always a fresh decode of the committed
//    blob (§ 2), never the caption's text.

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

    /// Switched on, and nothing on this phone would use the model: the
    /// caption is off **and** another engine is writing the transcripts.
    /// 45 MB is not fetched on the chance somebody turns one of them on.
    data class Unused(val model: MoonshineModel) : MoonshinePlan

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
///
/// **R3 removed a condition and this round put half of it back, which is
/// worth spelling out.** R1 read the caption switch here, because the
/// caption was the model's only use and fetching 45 MB for a switched-off
/// feature would have been a fault. R3 dropped that, because the same
/// model now decodes the *committed transcript* (§ 2) and gating on the
/// caption would leave a person who turned the caption off with a
/// transcriber that can never transcribe. Both are right about their own
/// half, and dropping the condition outright was the error: it made the
/// model unconditional for an enabled plugin, so an R1 user who had turned
/// the caption off got a silent 45 MB fetch on upgrade.
///
/// The honest condition is the union — [moonshineModelWanted]: the caption
/// is on, **or** this is the engine writing the transcripts. Either use is
/// a use; neither being true is nobody needing the bytes.
fun moonshinePlan(
    plugins: List<PluginInfo>,
    modelOnDisk: (MoonshineModel) -> Boolean,
    metered: Boolean,
): MoonshinePlan {
    val installed = plugins.firstOrNull { it.pluginId == MOONSHINE_PLUGIN }
    if (installed == null || installed.state != "enabled") return MoonshinePlan.NoProvider
    val chosen = plugins.moonshineModel()
    // Already here: there is nothing to decide and nothing to fetch, so
    // what the switches say does not matter.
    if (modelOnDisk(chosen)) return MoonshinePlan.Ready(chosen)
    // Before consent, deliberately: a person who would not use the model
    // should not be asked to allow a download for it.
    if (!moonshineModelWanted(plugins)) return MoonshinePlan.Unused(chosen)
    if (NETWORK_CAPABILITY !in installed.granted) return MoonshinePlan.NeedsConsent(chosen)
    if (plugins.moonshineWifiOnly() && metered) return MoonshinePlan.WaitingForWifi(chosen)
    return MoonshinePlan.Download(chosen)
}

/// Whether anything on this phone would use the caption model if it were
/// here. Two uses, either of which is enough (ADR 0018 § 1, § 2).
fun moonshineModelWanted(plugins: List<PluginInfo>): Boolean =
    plugins.moonshineCaption() || plugins.activeTranscriber() == MOONSHINE_PLUGIN

/// Everything [moonshinePlan] reads, as one value — the key the shell's
/// single bring-up call site is keyed on.
///
/// **Three of its parts are not obvious, and each was a defect.** The
/// *other* transcriber's state is in here because `moonshineModelWanted`
/// asks who is active, so switching whisper off changes this plan without
/// touching Moonshine's own row. The connection is in here because
/// "Waiting for Wi-Fi" is a plan that must be re-decided when Wi-Fi
/// arrives, and nothing else would ever ask again. And it is null before a
/// registry exists, so the effect does not run against an empty list and
/// conclude there is no provider.
fun moonshineTrigger(plugins: List<PluginInfo>, metered: Boolean): String? {
    val moonshine = plugins.firstOrNull { it.pluginId == MOONSHINE_PLUGIN } ?: return null
    val incumbent = plugins.firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }?.state ?: "absent"
    return listOf(
        moonshine.state,
        moonshine.granted.sorted().joinToString(","),
        moonshine.settings,
        incumbent,
        metered.toString(),
    ).joinToString("|")
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

/// The same answer, **watched** rather than sampled.
///
/// "Waiting for Wi-Fi to download the caption model" was a true sentence
/// that never stopped being true: the plan read the connection once, when
/// something about the plugin changed, so joining Wi-Fi changed nothing
/// until a person happened to toggle a switch. A default-network callback
/// is the cheapest thing that makes the wait end on its own — it holds no
/// socket and asks for no permission this app does not already declare
/// (`ACCESS_NETWORK_STATE`).
///
/// Registration failing is not a fault: the value stays what the one-off
/// read said, which is the behaviour that shipped.
@androidx.compose.runtime.Composable
fun rememberConnectionIsMetered(context: Context): Boolean {
    var metered by androidx.compose.runtime.remember { mutableStateOf(connectionIsMetered(context)) }
    androidx.compose.runtime.DisposableEffect(context) {
        val manager = runCatching {
            context.getSystemService(ConnectivityManager::class.java)
        }.getOrNull()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Every one of them re-asks the same question rather than
            // inferring an answer from which callback fired: the
            // authority is `isActiveNetworkMetered`, and a callback is
            // only the prompt to consult it again.
            override fun onAvailable(network: android.net.Network) {
                metered = connectionIsMetered(context)
            }

            override fun onLost(network: android.net.Network) {
                metered = connectionIsMetered(context)
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: android.net.NetworkCapabilities,
            ) {
                metered = connectionIsMetered(context)
            }
        }
        val registered = runCatching { manager?.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose {
            if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        }
    }
    return metered
}

/// Register the realtime provider. **Android only, and this is the only
/// call site** — `openVault` registers the shared declarations for both
/// shells, and Moonshine is not one of them: the AAR is Android-only and
/// ADR 0018 § 4 keeps whisper as the desktop engine.
///
/// Registering is idempotent and never resurrects a plugin the user
/// removed.
///
/// **What is granted here, and what never is.** The provider's local
/// capabilities — `blobs.read` and `objects.write`, which are what claiming
/// a job and submitting its words actually do — are consented by installing
/// the application, on the same host policy the voice adapter and whisper
/// already follow. `network.request` is not among them and never will be:
/// the 45 MB model download always waits for an explicit yes, and nothing
/// re-grants after a revoke.
///
/// They are granted when the declaration **first offers** them rather than
/// only on first install, because this declaration grew at R3: an R1 vault
/// has a Moonshine that never mentioned either capability, so nobody has
/// ever been asked about them and nobody has refused them. A capability the
/// installed declaration already named is never re-granted, which is what
/// keeps a revoke a revoke.
///
/// It also registers this handle as the provider's implementation (§ 2), so
/// that jobs for `moonshine-transcribe` are left for `claimJob` instead of
/// being claimed by a kernel loop with nothing to run them.
///
/// **And it switches the provider off if whisper is still the transcriber**
/// (`applyOneTranscriberRule`, ADR 0018 § 3, § 6). Registering is not
/// choosing: the kernel defaults an unseen plugin to Enabled, so bundling
/// this one switched it on for everybody — two transcribers at once, and a
/// second job per recording that nothing in this process ever claimed.
/// Moonshine is opt-in until MS2 has measured both engines on real
/// hardware, and this is where that stops being a sentence in an ADR.
fun registerMoonshine(state: GiluneState, gilune: Gilune) {
    runCatching {
        val declaration = moonshineDeclaration()
        val installed = gilune.plugins().firstOrNull { it.pluginId == MOONSHINE_PLUGIN }
        val newly = newlyDeclaredLocalCapabilities(installed, declaration.capabilities)
        val registered = gilune.registerPlugin(declaration)
        var said: String? = null
        if (registered != "removed") {
            for (capability in newly) {
                runCatching { gilune.grantCapability(MOONSHINE_PLUGIN, capability) }
            }
            said = applyOneTranscriberRule(gilune, MOONSHINE_PLUGIN)
        }
        registerMoonshineProcessor(gilune, gilune.plugins())
        state.refresh()
        // Said only when the switch actually moved — which is once, on the
        // install or the upgrade that introduced the second provider — and
        // never over an enrolment notice, which is the one thing about
        // opening this vault a person has to be told.
        if (said != null && state.message.value.startsWith("vault: ")) {
            state.message.value = said
        }
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
    // Re-declared here as well as at open, because this runs whenever the
    // plugin's state changes: switching the provider on mid-session must
    // register its implementation on this handle, or its jobs would be
    // queued with nobody in this process able to claim them.
    state.gilune.value?.let { registerMoonshineProcessor(it, plugins) }
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
        is MoonshinePlan.Unused -> {
            runtime.phase = MoonshinePhase.Absent
            runtime.model = plan.model
            runtime.modelDir = null
            runtime.notice =
                "Nothing here would use the ${plan.model.size} caption model yet, so it " +
                    "has not been downloaded. Turn the live caption on, or make this the " +
                    "engine that writes your transcripts, and it is fetched then."
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

/// The provider for a handle that has no UI: the recording service
/// (ADR 0018 § 2's headlessness note, and § 6 R3's "`RecordService`
/// registering the external processor so widget captures still get
/// words"). Returns a decoder factory when this handle may transcribe, and
/// null when it may not.
///
/// **It never downloads**, on exactly `enableTranscriptionForService`'s
/// rule: a 45 MB fetch is a consent conversation, and a foreground service
/// started from a home-screen tap is the wrong place for one. A service
/// that finds no model commits the recording and leaves the job queued for
/// the app.
///
/// It registers the processor **before** deciding whether it can run one,
/// because registration is what stops `runProcessing` on this handle from
/// claiming a job it has no implementation for and burning its lease.
fun moonshineProviderForService(context: Context, gilune: Gilune): (() -> MoonshineDecoder)? {
    // The one-transcriber rule runs on **this** handle too, because this
    // handle may be the first thing to open the vault since the upgrade
    // that introduced the second provider: a phone whose owner records
    // through the widget and never opens the app would otherwise queue a
    // moonshine job per recording that nothing claims. Plugin state is
    // vault state, so whichever handle gets here first settles it.
    runCatching { applyOneTranscriberRule(gilune, MOONSHINE_PLUGIN) }
    val plugins = runCatching { gilune.plugins() }.getOrNull() ?: return null
    if (!registerMoonshineProcessor(gilune, plugins)) return null
    if (plugins.activeTranscriber() != MOONSHINE_PLUGIN) return null
    val model = plugins.moonshineModel()
    val dir = moonshineModelDir(context, model)
    if (!moonshineModelPresent(dir)) return null
    return { MoonshineDecoder(dir, model.arch) }
}

/// The consent ask, with the number in it (ADR 0017 § 4 makes the size
/// part of the ask, and R0 corrected what the number is: 45 MB across 8
/// files, not the 77.7 MB the ADR estimated).
fun consentNotice(model: MoonshineModel): String =
    "The live caption needs a ${model.size} model. Allow network.request above to download it once."
