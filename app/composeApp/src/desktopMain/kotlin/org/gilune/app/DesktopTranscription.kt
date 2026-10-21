// Local transcription on the desktop: getting the transcription plugin's
// model onto this machine and telling the kernel where it is. The plan is
// the shared one (`TranscriptionPlan.kt`); this file is the I/O around it.

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

/// This window's transcription runtime, for the controls that must not
/// promise words it cannot deliver. The default is a runtime that never
/// got started, which reads as "still starting" — never as ready.
val LocalTranscription = staticCompositionLocalOf { TranscriptionRuntime() }

/// The one call site that brings transcription up, keyed on exactly what
/// the plan reads. It runs when the vault opens and again whenever the
/// answer could change: allowing the plugin's download, switching it on,
/// or choosing another model or language.
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

/// Bring up local transcription, off the capture path by design. Which
/// model is a plugin setting (owner decision, 2026-09-01), so this reads
/// the choice back and downloads that one; a key nobody recognises falls
/// back to the shipped default rather than leaving speech untranscribed.
///
/// The download is a declared capability either way, so it waits for
/// consent rather than fetching tens of megabytes on someone's first
/// launch (ADR 0011) — including when the setting changes to a model that
/// is not on disk yet. Idempotent: an already-loaded model re-enables
/// harmlessly, and a changed language takes effect because enabling
/// re-reads it.
fun startTranscription(state: GiluneState, gilune: Gilune, runtime: TranscriptionRuntime) {
    if (!runtime.working.compareAndSet(false, true)) return
    try {
        bringUpTranscription(state, gilune, runtime)
    } catch (e: Throwable) {
        // This runs in the window's composition; nothing here may take
        // the window down with it.
        runtime.phase = TranscriptionPhase.Failed
        runtime.failure = e.message
        state.message.value = "transcription unavailable (${e.message}); captures still work"
    } finally {
        runtime.working.set(false)
    }
}

private fun bringUpTranscription(state: GiluneState, gilune: Gilune, runtime: TranscriptionRuntime) {
    val plugins = runCatching { gilune.plugins() }.getOrElse {
        runtime.phase = TranscriptionPhase.Failed
        runtime.failure = it.message
        return
    }
    val plan = transcriptionPlan(plugins, { it.transcriptionModel() }) { modelFile(it).isFile }
    runtime.needsConsent = plan is TranscriptionPlan.NeedsConsent
    runtime.failure = null
    when (plan) {
        TranscriptionPlan.NoTranscriber -> {
            runtime.phase = TranscriptionPhase.Absent
            runtime.model = null
        }
        is TranscriptionPlan.NeedsConsent -> {
            runtime.phase = TranscriptionPhase.Absent
            runtime.model = plan.model
            state.message.value =
                "transcription needs permission to download its model — Settings ▸ Plugins ▸ Transcription"
        }
        is TranscriptionPlan.Download -> {
            runtime.model = plan.model
            runtime.phase = TranscriptionPhase.Downloading
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
                runtime.phase = TranscriptionPhase.Failed
                runtime.failure = fetched.exceptionOrNull()?.message
                state.message.value =
                    "could not download the transcription model (${runtime.failure}); your recordings are safe"
                return
            }
            enable(state, gilune, runtime, modelFile(plan.model))
        }
        is TranscriptionPlan.Enable -> {
            runtime.model = plan.model
            enable(state, gilune, runtime, modelFile(plan.model))
        }
    }
}

/// Hand the kernel the file. A success here is the only thing that earns
/// [TranscriptionPhase.Ready].
private fun enable(state: GiluneState, gilune: Gilune, runtime: TranscriptionRuntime, model: File) {
    val enabled = runCatching { gilune.enableWhisper(model.absolutePath) }
    if (enabled.isFailure) {
        runtime.phase = TranscriptionPhase.Failed
        runtime.failure = enabled.exceptionOrNull()?.message
        state.message.value = "transcription unavailable (${runtime.failure}); captures still work"
        return
    }
    runtime.phase = TranscriptionPhase.Ready
    state.message.value = "vault: ${gilune.vaultPath()} — transcription ready"
    // Anything recorded while the model was arriving has a job waiting;
    // this is what runs it.
    state.sync(announce = false)
}
