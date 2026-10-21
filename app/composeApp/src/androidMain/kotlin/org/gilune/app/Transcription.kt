// Local transcription on the phone (ADR 0017 § 4).
//
// "Not a second pipeline — the same one, on a smaller CPU." Everything
// here is about getting a model file onto the device and telling the
// kernel where it is; from `enableWhisper` onwards the phone runs the
// desktop's machinery unchanged — same job loop, same narrow
// `ProcessorCtx`, same at-least-once delivery, same derived output IDs,
// same revise-subject landing in the capture note (ADR 0013).
//
// Three rules shape this file:
//
//  - **Nothing downloads because the app opened.** The ~75 MiB model is
//    gated by the same `network.request` consent the desktop asks for,
//    and consent is read off the kernel's grant list rather than assumed.
//  - **The phone's default model is not the desktop's.** ADR 0017
//    § Ratification 5 pencils `tiny-q5_1` here and keeps `base-q5_1`
//    there. That is implemented as a *read-site* difference — see
//    [androidWhisperModel] — so the shared catalogue never learns what a
//    phone is.
//  - **A capture is never waiting on any of it.** Recording commits
//    durably before a processor is asked anything (rule 1). A model that
//    never arrives costs words, never the recording.

package org.gilune.app

import android.content.Context
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginInfo
import java.io.File

/// The phone's default (ADR 0017 § Ratification 5): "tiny-q5_1 (31 MiB,
/// the measured fastest) pencilled as the phone default pending MS2".
///
/// Pencilled is the operative word. MS2 measures whisper on ARM64
/// hardware that does not exist yet, and every Android figure in this
/// repo came off an x86_64 emulator, so this is a defensible guess and
/// not a number.
const val ANDROID_DEFAULT_WHISPER_MODEL = "tiny-q5_1"

/// The platform default, implemented where the ADR puts it: at the read
/// site. `whisperModel` in the shared set resolves an unknown or absent
/// key to [DEFAULT_WHISPER_MODEL] — the desktop's `base-q5_1` — and this
/// is the same resolution against the phone's default instead.
///
/// The fallback rule is deliberately the shared one's, word for word:
/// absent, blank, or a key this build has never heard of all resolve to
/// the default rather than failing, because an unreadable setting must
/// never be what stops speech being transcribed.
fun androidWhisperModel(key: String?): WhisperModel =
    WHISPER_MODELS.firstOrNull { it.key == key?.trim() }
        ?: WHISPER_MODELS.first { it.key == ANDROID_DEFAULT_WHISPER_MODEL }

/// The chosen model on this device: the stored key, resolved against the
/// phone's default. The desktop's `transcriptionModel()` is untouched and
/// still answers `base-q5_1`, which is what keeps the two shells' defaults
/// a decision rather than drift.
fun List<PluginInfo>.androidTranscriptionModel(): WhisperModel =
    androidWhisperModel(transcriptionModelKey())

/// Where a downloaded model lands: app-private internal storage beside
/// the vault (§ 5). The consequence is already recorded and is not
/// softened here — uninstalling wipes it, exactly as it wipes the vault.
///
/// One file per catalogue entry, the desktop's rule: changing the setting
/// downloads the new model beside the old one rather than over it, so
/// switching back is not another download.
fun modelsDir(context: Context): File = File(context.filesDir, "models")

fun modelFile(context: Context, model: WhisperModel): File =
    File(modelsDir(context), model.fileName)

/// The phone's plan: the shared decision (`TranscriptionPlan.kt`), read
/// against the phone's default model.
fun transcriptionPlan(
    plugins: List<PluginInfo>,
    modelOnDisk: (WhisperModel) -> Boolean,
): TranscriptionPlan = transcriptionPlan(plugins, { it.androidTranscriptionModel() }, modelOnDisk)

/// Bring up local transcription, off the capture path by design — the
/// mobile counterpart of the desktop's `startTranscription`, and
/// deliberately the same shape.
///
/// Called on vault open and again whenever the plan changes: switching the
/// plugin on, allowing `network.request`, or choosing a different model
/// all make this worth asking again. It is idempotent — an already-loaded
/// model re-enables harmlessly — so re-asking costs a kernel call.
///
/// Failure is never fatal and never silent: the phase says so, and § 4's
/// promise that a capture made before the model lands is not lost is kept
/// by the kernel's queue plus the deliberate backfill action.
fun startTranscription(
    context: Context,
    state: GiluneState,
    gilune: Gilune,
    runtime: TranscriptionRuntime,
) {
    if (!runtime.working.compareAndSet(false, true)) return
    try {
        bringUpTranscription(context, state, gilune, runtime)
    } finally {
        runtime.working.set(false)
    }
}

private fun bringUpTranscription(
    context: Context,
    state: GiluneState,
    gilune: Gilune,
    runtime: TranscriptionRuntime,
) {
    val plugins = runCatching { gilune.plugins() }.getOrNull() ?: return
    when (val plan = transcriptionPlan(plugins) { modelFile(context, it).isFile }) {
        TranscriptionPlan.NoTranscriber -> {
            runtime.phase = TranscriptionPhase.Absent
            runtime.model = null
        }
        is TranscriptionPlan.NeedsConsent -> {
            runtime.phase = TranscriptionPhase.Absent
            runtime.model = plan.model
            state.message.value =
                "transcription needs permission to download its model — Settings ▸ Plugins"
        }
        is TranscriptionPlan.Download -> {
            runtime.model = plan.model
            runtime.phase = TranscriptionPhase.Downloading
            runtime.percent = null
            val fetched = runCatching {
                downloadModel(plan.model, modelFile(context, plan.model)) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else null
                    runtime.percent = percent
                    state.message.value = downloadNotice(plan.model, percent)
                }
            }
            if (fetched.isFailure) {
                runtime.phase = TranscriptionPhase.Failed
                state.message.value =
                    "could not download the transcription model (${fetched.exceptionOrNull()?.message}); " +
                        "your recordings are safe"
                return
            }
            enable(state, gilune, runtime, modelFile(context, plan.model))
        }
        is TranscriptionPlan.Enable -> {
            runtime.model = plan.model
            enable(state, gilune, runtime, modelFile(context, plan.model))
        }
    }
}

/// Transcription for a handle that has no UI: the recording service
/// (§ 4's widget path), which opens its own `Gilune` and must therefore
/// enable whisper on its own.
///
/// It **never downloads**. A download is tens of megabytes and a consent
/// conversation, and a foreground service started from a home-screen tap
/// is the wrong place for either — so a service that finds no model
/// commits the recording and leaves the job queued for the app, which is
/// exactly the wait § 4 already promises is safe. Returns whether a
/// processor can now run.
fun enableTranscriptionForService(context: Context, gilune: Gilune): Boolean {
    val plugins = runCatching { gilune.plugins() }.getOrNull() ?: return false
    val plan = transcriptionPlan(plugins) { modelFile(context, it).isFile }
    val model = (plan as? TranscriptionPlan.Enable)?.model ?: return false
    return runCatching { gilune.enableWhisper(modelFile(context, model).absolutePath) }.isSuccess
}

/// The line the composer shows while bytes are arriving. Pure, so the
/// wording is testable without a network.
fun downloadNotice(model: WhisperModel, percent: Int?): String =
    if (percent == null) {
        "downloading the ${model.key} transcription model (~${model.size}, one time)…"
    } else {
        "downloading the ${model.key} transcription model — $percent% of ~${model.size}"
    }

/// Hand the kernel the file and let it do what the desktop does. A
/// success here is the *only* thing that earns [TranscriptionPhase.Ready],
/// and so the only thing that lets a row say "transcribing…".
private fun enable(
    state: GiluneState,
    gilune: Gilune,
    runtime: TranscriptionRuntime,
    model: File,
) {
    val enabled = runCatching { gilune.enableWhisper(model.absolutePath) }
    if (enabled.isFailure) {
        runtime.phase = TranscriptionPhase.Failed
        state.message.value =
            "transcription unavailable (${enabled.exceptionOrNull()?.message}); captures still work"
        return
    }
    runtime.phase = TranscriptionPhase.Ready
    runtime.percent = null
    // Anything recorded while the model was arriving has a job waiting;
    // this is what runs it. Captures made while the plugin was *off*
    // queued nothing and need the deliberate backfill instead (§ 4).
    state.sync(announce = false)
}

/// The download, streamed to a `.part` file and renamed on success, so an
/// interrupted fetch can never be mistaken for a model.
private fun downloadModel(
    model: WhisperModel,
    destination: File,
    onProgress: (Long, Long) -> Unit,
) {
    destination.parentFile?.mkdirs()
    val partial = File(destination.parentFile, "${destination.name}.part")
    val connection = java.net.URI(model.url).toURL().openConnection()
    connection.connect()
    val total = connection.contentLengthLong
    var done = 0L
    var announced = 0L
    connection.getInputStream().use { input ->
        partial.outputStream().use { output ->
            val buffer = ByteArray(DOWNLOAD_CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                done += read
                // Reporting every chunk would be tens of thousands of
                // recompositions for one file.
                if (done - announced >= PROGRESS_STEP) {
                    announced = done
                    onProgress(done, total)
                }
            }
        }
    }
    check(partial.renameTo(destination)) { "could not put the model in place" }
    onProgress(done, total)
}

private const val DOWNLOAD_CHUNK = 64 * 1024

/// How much must arrive before the notice changes. A megabyte is about
/// 1% of the largest catalogue entry and 3% of the smallest.
private const val PROGRESS_STEP = 1024L * 1024L
