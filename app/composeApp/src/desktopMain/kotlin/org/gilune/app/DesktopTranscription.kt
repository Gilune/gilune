// Local transcription on the desktop: getting the chosen engines' models
// onto this machine and telling the kernel, or the NeMo host, where they
// are. Which engines is the shared roles model (`TranscriptionRoles.kt`,
// ADR 0033); the plan is the shared one (`TranscriptionPlan.kt`); this
// file is the I/O around both.

package org.gilune.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import uniffi.gilune_ffi.Gilune
import java.io.File

/// Where a downloaded model lands. One file per catalogue entry, so
/// changing the setting downloads the new model beside the old one rather
/// than over it: the previous file is left alone, and switching back is
/// not another download.
internal fun modelFile(model: WhisperModel): File =
    File(localGiluneDir("models"), model.fileName)

/// This window's runtime for the **Recordings** engine, for the controls
/// that must not promise words it cannot deliver. The default is a runtime
/// that never got started, which reads as "still starting" — never as
/// ready.
val LocalTranscription = staticCompositionLocalOf { TranscriptionRuntime() }

/// The runtime for the engine chosen for **while you speak**. Absent when
/// that role is Off.
val LocalLiveTranscription = staticCompositionLocalOf { TranscriptionRuntime() }

/// Why a dictation cannot start, or null when it can. The live engine
/// ready is enough (its words are what the person sees); without it the
/// words come from the Recordings engine at the stop, so that must be
/// ready instead.
fun dictationUnreadyReason(recordings: TranscriptionRuntime, live: TranscriptionRuntime): String? =
    if (live.ready) null else recordings.unreadyReason()

/// Whether a published engine's model is already on this computer — the
/// page's *Ready*. An engine with nothing to download is always here.
fun engineOnDisk(engine: TranscriptionEngine): Boolean = when (engine.pluginId) {
    TRANSCRIPTION_PLUGIN -> modelFile(whisperModel(engine.engine)).isFile
    NEMO_PLUGIN -> nemoModelPresent(nemoModel(engine.engine))
    else -> engine.downloadBytes == null
}

/// The one call site that brings transcription up, keyed on exactly what
/// the plan reads. It runs when the vault opens and again whenever the
/// answer could change: allowing the plugin's download, switching it on,
/// or choosing another engine or language.
///
/// It used to run once, at open. Allowing the download in Settings then
/// did nothing until the next launch, while both microphones went on
/// recording speech that nothing would transcribe (owner report,
/// 2026-10-21).
@Composable
fun KeepTranscriptionAsked(state: GiluneState, ask: suspend (Gilune) -> Unit) {
    val gilune = state.gilune.value
    val key = transcriptionPlanKey(state.plugins)
    LaunchedEffect(gilune, key) {
        if (gilune != null) ask(gilune)
    }
}

/// Bring up what both roles need, off the capture path by design: Whisper
/// enabled when either role is Whisper (one model — both roles name the
/// same one, ADR 0033), NeMo's engines for whichever roles are NeMo.
/// Nothing is loaded here: each engine loads at the first dictation or
/// recording that needs it and leaves after ten idle minutes (09 § Desktop
/// footprint) — a warm-up at open held ~700 MB for sessions in which nobody
/// spoke (owner, 2026-11-01).
///
/// Downloads are a declared capability either way, so each waits for
/// consent rather than fetching hundreds of megabytes on someone's first
/// launch (ADR 0011). Idempotent: an already-loaded model re-enables
/// harmlessly, and a changed language takes effect because enabling
/// re-reads it.
fun startTranscription(
    state: GiluneState,
    gilune: Gilune,
    recordings: TranscriptionRuntime,
    live: TranscriptionRuntime,
    nemo: NemoHost,
    nemoRoot: File = nemoModelsRoot(),
) {
    if (!recordings.working.compareAndSet(false, true)) return
    try {
        val plugins = gilune.plugins()
        registerNemoProcessor(gilune)
        val recordingsChoice = plugins.recordingsChoice()
        val liveChoice = plugins.liveChoice()
        if (recordingsChoice == null) {
            recordings.unavailable(
                if (plugins.transcriptionProviders().any { it.state == "enabled" }) {
                    "nothing is chosen for recordings — Settings ▸ Transcription"
                } else {
                    null
                },
            )
        }
        if (liveChoice == null) live.unavailable("nothing is chosen for while you speak")

        // Whisper: one model per handle, so one bring-up for both roles.
        val whisperRuntimes = listOfNotNull(
            recordings.takeIf { recordingsChoice?.pluginId == TRANSCRIPTION_PLUGIN },
            live.takeIf { liveChoice?.pluginId == TRANSCRIPTION_PLUGIN },
        )
        val whisperKey = recordingsChoice?.takeIf { it.pluginId == TRANSCRIPTION_PLUGIN }?.engine
            ?: liveChoice?.takeIf { it.pluginId == TRANSCRIPTION_PLUGIN }?.engine
        if (whisperKey != null) bringUpWhisper(state, gilune, plugins, whisperModel(whisperKey), whisperRuntimes)

        // NeMo: an engine per role, one instance when both name one model.
        val recordingsNemo = recordingsChoice?.takeIf { it.pluginId == NEMO_PLUGIN }?.let { nemoModel(it.engine) }
        val liveNemo = liveChoice?.takeIf { it.pluginId == NEMO_PLUGIN }?.let { nemoModel(it.engine) }
        val made = mutableMapOf<String, NemoEngines>()
        fun enginesFor(model: NemoModel): NemoEngines = made.getOrPut(model.key) {
            nemo.existing(model) ?: NemoEngines(model, File(nemoRoot, model.archive))
        }
        val nemoPlugin = plugins.firstOrNull { it.pluginId == NEMO_PLUGIN }
        val liveEngines = if (liveNemo != null && nemoPlugin != null &&
            fetchNemo(state, nemoPlugin, liveNemo, live, nemoRoot)
        ) {
            enginesFor(liveNemo)
        } else {
            null
        }
        val recordingsEngines = if (recordingsNemo != null && nemoPlugin != null &&
            fetchNemo(state, nemoPlugin, recordingsNemo, recordings, nemoRoot)
        ) {
            enginesFor(recordingsNemo)
        } else {
            null
        }
        nemo.replace(live = liveEngines, recordings = recordingsEngines)
        if (recordingsEngines != null) {
            recordings.ready(null)
            state.message.value = "vault: ${gilune.vaultPath()} — ${recordingsEngines.model.name} ready for recordings"
        }
        if (liveEngines != null) {
            // Ready means the files are here, not that the model is in
            // memory: it loads with the first audio of a dictation (~3 s for
            // Nemotron) on the dictation's own thread.
            live.ready(null)
            state.message.value = "vault: ${gilune.vaultPath()} — ${liveEngines.model.name} ready"
        }
        // A role filled by a plugin this computer cannot run (the phone's
        // Moonshine) is said in words rather than left "still starting".
        for ((choice, runtime) in listOf(recordingsChoice to recordings, liveChoice to live)) {
            if (choice != null && choice.pluginId != TRANSCRIPTION_PLUGIN && choice.pluginId != NEMO_PLUGIN) {
                runtime.unavailable("${plugins.firstOrNull { it.pluginId == choice.pluginId }?.name} does not run on this computer")
            }
        }
        // Recordings that waited for an engine have jobs queued; this runs them.
        if (recordingsEngines != null) state.sync(announce = false)
    } catch (e: Throwable) {
        // This runs in the window's composition; nothing here may take
        // the window down with it.
        recordings.failed(e.message)
        live.failed(e.message)
        state.message.value = "transcription unavailable (${e.message}); captures still work"
    } finally {
        recordings.working.set(false)
    }
}

private fun TranscriptionRuntime.unavailable(words: String?) {
    phase = TranscriptionPhase.Absent
    needsConsent = false
    absentWords = words
    model = null
    failure = null
}

private fun TranscriptionRuntime.ready(whisper: WhisperModel?) {
    phase = TranscriptionPhase.Ready
    needsConsent = false
    failure = null
    model = whisper
}

private fun TranscriptionRuntime.failed(why: String?) {
    phase = TranscriptionPhase.Failed
    failure = why
}

private fun bringUpWhisper(
    state: GiluneState,
    gilune: Gilune,
    plugins: List<uniffi.gilune_ffi.PluginInfo>,
    chosen: WhisperModel,
    runtimes: List<TranscriptionRuntime>,
) {
    fun each(set: TranscriptionRuntime.() -> Unit) = runtimes.forEach(set)
    val plan = transcriptionPlan(plugins, { chosen }) { modelFile(it).isFile }
    each { needsConsent = plan is TranscriptionPlan.NeedsConsent; failure = null; absentWords = null }
    when (plan) {
        TranscriptionPlan.NoTranscriber -> each { phase = TranscriptionPhase.Absent; model = null }
        is TranscriptionPlan.NeedsConsent -> {
            each { phase = TranscriptionPhase.Absent; model = plan.model }
            state.message.value =
                "transcription needs permission to download its model — Settings ▸ Plugins ▸ Transcription"
        }
        is TranscriptionPlan.Download -> {
            each { model = plan.model; phase = TranscriptionPhase.Downloading }
            state.message.value =
                "downloading the ${plan.model.key} transcription model (~${plan.model.size}, one-time)…"
            val fetched = runCatching {
                val model = modelFile(plan.model)
                model.parentFile.mkdirs()
                val partial = File(model.parentFile, "${model.name}.part")
                java.net.URI(plan.model.url).toURL().openStream().use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                check(partial.renameTo(model)) { "could not move the downloaded model into place" }
            }
            if (fetched.isFailure) {
                each { failed(fetched.exceptionOrNull()?.message) }
                state.message.value =
                    "could not download the transcription model (${fetched.exceptionOrNull()?.message}); " +
                    "your recordings are safe"
                return
            }
            enableWhisper(state, gilune, runtimes, plan.model)
        }
        is TranscriptionPlan.Enable -> {
            each { model = plan.model }
            enableWhisper(state, gilune, runtimes, plan.model)
        }
    }
}

/// Hand the kernel the file. A success here is the only thing that earns
/// [TranscriptionPhase.Ready].
private fun enableWhisper(state: GiluneState, gilune: Gilune, runtimes: List<TranscriptionRuntime>, model: WhisperModel) {
    val enabled = runCatching { gilune.enableWhisper(modelFile(model).absolutePath) }
    if (enabled.isFailure) {
        runtimes.forEach { it.failed(enabled.exceptionOrNull()?.message) }
        state.message.value = "transcription unavailable (${enabled.exceptionOrNull()?.message}); captures still work"
        return
    }
    runtimes.forEach { it.ready(model) }
    state.message.value = "vault: ${gilune.vaultPath()} — transcription ready"
    // Anything recorded while the model was arriving has a job waiting;
    // this is what runs it.
    state.sync(announce = false)
}

/// The NeMo models this window has loaded: one for while you speak and one
/// for recordings, the same instance when both roles name one model. Held
/// outside composition: a dictation, a saved transcript and the settings
/// screen all reach the same engines.
class NemoHost {
    @Volatile
    var live: NemoEngines? = null
        private set

    @Volatile
    var recordings: NemoEngines? = null
        private set

    /// An engine already loaded here for this model, whichever role holds it.
    fun existing(model: NemoModel): NemoEngines? = listOfNotNull(live, recordings).firstOrNull { it.model == model }

    @Synchronized
    fun replace(live: NemoEngines?, recordings: NemoEngines?) {
        val previous = listOfNotNull(this.live, this.recordings)
        this.live = live
        this.recordings = recordings
        for (engines in previous) {
            if (engines !== live && engines !== recordings) runCatching { engines.close() }
        }
    }
}

val LocalNemo = staticCompositionLocalOf { NemoHost() }

/// Make sure one NeMo model is on this computer: the chosen model
/// downloaded with consent and verified. True when it is here. Loading it is
/// left to the first job or dictation that needs it (09 § Desktop footprint).
private fun fetchNemo(
    state: GiluneState,
    installed: uniffi.gilune_ffi.PluginInfo,
    model: NemoModel,
    runtime: TranscriptionRuntime,
    root: File,
): Boolean {
    runtime.failure = null
    runtime.needsConsent = false
    runtime.absentWords = null
    runtime.model = null
    if (nemoModelPresent(model, root)) return true
    if (NETWORK_CAPABILITY !in installed.granted) {
        runtime.phase = TranscriptionPhase.Absent
        runtime.needsConsent = true
        state.message.value = "${installed.name} needs permission to download ${model.name} " +
            "(${model.size}) — Settings ▸ Plugins ▸ ${installed.name}"
        return false
    }
    runtime.phase = TranscriptionPhase.Downloading
    runtime.percent = 0
    state.message.value = "downloading ${model.name} (${model.size}, one time)…"
    val fetched = runCatching {
        installNemoModel(model, root, onProgress = { done ->
            val percent = (done * 100 / model.bytes).toInt()
            runtime.percent = percent
            state.message.value = "downloading ${model.name} — $percent% of ${model.size}"
        })
    }
    if (fetched.isFailure) {
        runtime.failed(fetched.exceptionOrNull()?.message)
        state.message.value = "could not download ${model.name} (${runtime.failure}); your recordings are safe"
        return false
    }
    return true
}

/// The live engine for a dictation starting now, with how much new audio
/// each update should wait for — or null, when the words arrive at the
/// stop: while you speak is Off, or the engine would not start. Whisper
/// re-decodes its window every update, so it asks once a second; a
/// streaming NeMo model costs only the new audio, so it asks five times a
/// second.
fun liveEngineFor(state: GiluneState, nemo: NemoHost): Pair<uniffi.gilune_ffi.LiveTranscriptionInterface, Double>? =
    when (state.plugins.liveChoice()?.pluginId) {
        TRANSCRIPTION_PLUGIN ->
            state.gilune.value?.let { g -> runCatching { g.beginLiveTranscription() }.getOrNull() }?.let { it to 1.0 }
        NEMO_PLUGIN -> nemo.live?.let { e -> runCatching { e.liveSession() }.getOrNull() }?.let { it to 0.2 }
        else -> null
    }
