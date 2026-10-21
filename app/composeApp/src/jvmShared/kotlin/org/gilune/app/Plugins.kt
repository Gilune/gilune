// What the shell contributes and how it reads contributions back. The
// shell renders slots; what fills them comes from the registry, so
// removing a plugin removes its controls (ADR 0011). Nothing here decides
// behaviour — the kernel owns whether a plugin may run.

package org.gilune.app

import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.escapeJsonString
import org.gilune.plugin.sdk.jsonBoolean
import org.gilune.plugin.sdk.jsonString
import org.gilune.plugins.tasks.TASKS_PLUGIN
import org.gilune.plugins.tasks.tasksFilter
import org.gilune.plugins.tasks.tasksPanelEnabled

import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.SourceSummary

const val VOICE_CAPTURE_PLUGIN = "org.memory.voice-capture"
const val RECORD_VOICE_ACTION = "record-voice"
const val DICTATE_ACTION = "dictate"

/// Declared by the kernel binary when it carries a transcriber, so the
/// shell only ever reads it back.
const val TRANSCRIPTION_PLUGIN = "org.memory.transcription"
const val NETWORK_CAPABILITY = "network.request"

/// The capabilities a provider needs to read a claimed capture's bytes and
/// write the words back (ADR 0018 § 5). **Local**: consented by installing
/// the application under the host consent policy, never by a prompt — the
/// network ask is the model download and nothing else.
val PROVIDER_LOCAL_CAPABILITIES = listOf("blobs.read", "objects.write")

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

/// Does *this* plugin, switched on, put anything in that slot? The
/// question a shell region asks before composing a seam: `contributes`
/// answers "somebody does", which is the wrong question when the
/// behaviour behind the id is one named plugin's (ADR 0015 § 7 — in v1
/// the behaviour is Kotlin in the shell, so the shell must know whose).
fun List<PluginInfo>.pluginContributes(pluginId: String, slot: String): Boolean =
    any { it.pluginId == pluginId && it.state == "enabled" && it.contributions.any { c -> c.slot == slot } }

/// One live rail destination: the [RailPanel] key the contribution's
/// `config` names, and the label the tab announces itself by.
data class NavigationPanel(val key: String, val label: String)

/// The `navigation` slot ADR 0011 declared and no code ever read
/// (ADR 0015 § 5, T3): what the rail should show tabs for, right now.
///
/// A contribution names its panel in `config` — `{"panel":"tasks"}` —
/// because three fixed strings cannot carry a payload and smuggling one
/// into `id` is not a seam. One that names none is dropped rather than
/// offered as a tab that goes nowhere.
///
/// **The tasks panel carries a second condition** (§ 6, T3): its own
/// settings switch. Off, and the tab is not contributed at all — the same
/// shape as every other `contributes(…)` gate, one condition further in.
/// The condition is written here, in the shell, for the reason § 7 gives:
/// in v1 the behaviour behind a slot id is Kotlin the shell owns, so the
/// shell is what knows whose panel this is (debt 10).
fun navigationPanels(plugins: List<PluginInfo>): List<NavigationPanel> =
    plugins.filter { it.state == "enabled" }.flatMap { plugin ->
        val muted = plugin.pluginId == TASKS_PLUGIN && !plugins.tasksPanelEnabled()
        if (muted) {
            emptyList()
        } else {
            plugin.contributions
                .filter { it.slot == Slots.NAVIGATION }
                .mapNotNull { contribution ->
                    jsonString(contribution.config, "panel")
                        ?.let { NavigationPanel(it, contribution.label) }
                }
        }
    }

// ---------------------------------------------------------------------
// The transcription provider's settings. Its two keys — the spoken
// language and the model — are one document on the tasks precedent, and
// for the same reason: a write that carries one key must carry the other,
// or setting a language silently reverts the model somebody chose.
//
// The kernel reads `language` out of this document itself
// (`enableWhisper`); the model is the shell's read, because the shell is
// what puts the file on disk.
// ---------------------------------------------------------------------

private const val LANGUAGE_KEY = "language"
private const val MODEL_KEY = "model"
private const val LIVE_KEY = "live"

/// Whisper's own detection pass — what the field means when nobody has
/// set a language.
const val DEFAULT_TRANSCRIPTION_LANGUAGE = "auto"

/// Where the ggml models are published. One base, so a catalogue entry
/// carries a file name rather than a URL somebody has to keep in step.
private const val WHISPER_MODEL_BASE_URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

/// One downloadable whisper model as data: what it is called upstream and
/// on disk, how big the download is, and the word the settings screen
/// puts beside it. Nothing here decides anything — [whisperModel] and the
/// settings screen read this list, and Main.kt turns an entry into a file
/// and a URL.
data class WhisperModel(
    val key: String,
    val fileName: String,
    val size: String,
    val note: String = "",
) {
    val url: String get() = WHISPER_MODEL_BASE_URL + fileName

    /// "base-q5_1 — recommended, 57 MiB": the key a person chose by, then
    /// what choosing it costs.
    val label: String get() = if (note.isEmpty()) "$key — $size" else "$key — $note, $size"
}

/// The models the desktop app offers, fastest first (owner benchmark,
/// 2026-09-01, 21.5 s clip, 8 threads, language set: 553 / 632 / 1019 /
/// 1190 ms in this order).
///
/// The quantised variants are the interesting rows: measured against
/// their full siblings they transcribe the same words, so `base-q5_1`
/// buys base-class accuracy — ring mains, £ amounts, trade words tiny
/// mangles — for a download *smaller* than tiny's and about a second of
/// wall time on a twenty-second clip. Hence the default.
val WHISPER_MODELS: List<WhisperModel> = listOf(
    WhisperModel("tiny-q5_1", "ggml-tiny-q5_1.bin", "31 MiB", "fastest"),
    WhisperModel("tiny", "ggml-tiny.bin", "75 MiB"),
    WhisperModel("base-q5_1", "ggml-base-q5_1.bin", "57 MiB", "recommended"),
    WhisperModel("base", "ggml-base.bin", "142 MiB"),
)

/// The desktop default (owner decision, 2026-09-01). The phone's will not
/// be this one — `tiny-q5_1` is pencilled in there — which is the whole
/// reason the model is a setting rather than a constant in the download.
const val DEFAULT_WHISPER_MODEL = "base-q5_1"

/// A stored key resolved against the catalogue. Absent, blank, or a key
/// this build has never heard of — a vault written by a later version, a
/// hand-edited settings document — all fall back to the default rather
/// than failing: an unreadable setting must never be what stops speech
/// being transcribed.
fun whisperModel(key: String?): WhisperModel =
    WHISPER_MODELS.firstOrNull { it.key == key?.trim() }
        ?: WHISPER_MODELS.first { it.key == DEFAULT_WHISPER_MODEL }

/// The model key as stored, **unresolved** — null when nobody has chosen
/// one. The one thing [transcriptionModel] cannot tell a caller apart:
/// once resolved, "absent" and "explicitly the default" are the same
/// answer.
///
/// It exists because the model is the *shell's* read (see this section's
/// header) and there are now two shells with different defaults. ADR 0017
/// § Ratification 5 puts `tiny-q5_1` on the phone and leaves `base-q5_1`
/// on the desktop, and the ADR is explicit that this is a read-site
/// difference: [DEFAULT_WHISPER_MODEL] stays the desktop's, and the
/// Android shell resolves this key against its own default instead. A
/// shared default that knew about platforms would be the shared set
/// learning what a phone is.
fun List<PluginInfo>.transcriptionModelKey(): String? =
    firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }?.let { jsonString(it.settings, MODEL_KEY) }

/// The model the transcription plugin's settings name. Not gated on the
/// plugin's state, for [tasksFilter]'s reason: its own settings screen
/// must show what is stored even while it is switched off.
fun List<PluginInfo>.transcriptionModel(): WhisperModel = whisperModel(transcriptionModelKey())

/// The spoken language the same document holds. Absent means whisper
/// detects it, which is the shipped behaviour and costs an extra pass.
fun List<PluginInfo>.transcriptionLanguage(): String =
    firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }?.let { jsonString(it.settings, LANGUAGE_KEY) }
        ?: DEFAULT_TRANSCRIPTION_LANGUAGE

/// "Show words as I dictate" (ADR 0027 amendment). **Absent means on**,
/// the caption's precedent: a document written before the key existed
/// must read as the shipped behaviour.
fun List<PluginInfo>.transcriptionLive(): Boolean =
    firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }?.let { jsonBoolean(it.settings, LIVE_KEY) } ?: true

/// The settings document the transcription plugin stores, written here so
/// the writer, [transcriptionLanguage], [transcriptionModel] and
/// [transcriptionLive] cannot drift apart. Every key goes every time:
/// whichever control a person touched, the others' stored values travel
/// with the write.
fun transcriptionSettingsDocument(language: String, model: String, live: Boolean): String =
    """{"$LANGUAGE_KEY":"${escapeJsonString(language)}","$MODEL_KEY":"${escapeJsonString(model)}","$LIVE_KEY":$live}"""

// ---------------------------------------------------------------------
// The realtime provider (ADR 0018 § 5). Declared here beside the other
// bundled declarations, and **registered from the Android shell only** —
// the AAR is Android-only and § 4 keeps whisper as the desktop engine, so
// `openVault` must not learn about it. MainActivity registers it; the
// desktop never does.
// ---------------------------------------------------------------------

const val MOONSHINE_PLUGIN = "org.memory.moonshine"

/// One downloadable streaming model as data. `arch` is the bare `int`
/// `Transcriber.loadFromFiles(path, arch)` takes — R0 found that slot is
/// the MODEL ARCHITECTURE and not flags, and that passing the wrong one
/// fails with a message naming the *path*, which reads like a missing
/// file. 2 is `MOONSHINE_MODEL_ARCH_TINY_STREAMING`.
///
/// One entry in v1, deliberately: R0 measured streaming-tiny and nothing
/// else ("Only streaming-tiny was measured" — its honest gaps), so a
/// catalogue offering small or medium would be offering numbers nobody
/// has. The shape is the whisper catalogue's so a second row costs a line
/// when someone measures one.
data class MoonshineModel(
    val key: String,
    val language: String,
    val arch: Int,
    /// The consent ask, **measured** by R0 (45,233,659 B across 8 files),
    /// not the 77.7 MB ADR 0018 § 4 estimated before it ran.
    val size: String,
    val files: Int,
) {
    val label: String get() = "$key — $size, $files files"
}

const val MOONSHINE_ARCH_TINY_STREAMING = 2

val MOONSHINE_MODELS: List<MoonshineModel> = listOf(
    MoonshineModel("tiny-streaming-en", "en", MOONSHINE_ARCH_TINY_STREAMING, "45 MB", 8),
)

const val DEFAULT_MOONSHINE_MODEL = "tiny-streaming-en"

/// The stored key resolved against the catalogue, on the whisper rule:
/// absent, blank, or a key this build never heard of resolves to the
/// default rather than failing.
fun moonshineModel(key: String?): MoonshineModel =
    MOONSHINE_MODELS.firstOrNull { it.key == key?.trim() }
        ?: MOONSHINE_MODELS.first { it.key == DEFAULT_MOONSHINE_MODEL }

/// Moonshine's own processor id (ADR 0018 § 3): **distinct from whisper's
/// `transcribe`, deliberately**. A shared id would put both plugins on one
/// subscription row — disabling either would disable the other — and both
/// would write origin `processor:transcribe`, leaving *which engine wrote
/// these words* unanswerable from the vault. Provenance that cannot name
/// the interpreter is a defect, not a saving.
const val MOONSHINE_PROCESSOR = "moonshine-transcribe"

/// The kernel's own event names, as the shell must spell them to declare a
/// subscription. Duplicated here rather than exported over the FFI, which
/// is the same shape `voiceCaptureDeclaration` already lives with; the
/// external route's kernel tests pin the pair, so a drift is a red test.
const val SOURCE_READY_EVENT = "org.memory.source-ready"
const val REVISION_ADDED_EVENT = "org.memory.revision-added"

/// **The declaration grew at R3** (ADR 0018 § 6): R1 declared no processor
/// because the plugin wrote nothing and a declared subscription would have
/// queued jobs for an implementation that did not exist. R2 built the route,
/// and this is where the thing the declaration names becomes true — the
/// committed transcript is now this engine's, over the committed blob,
/// claimed and submitted across the FFI processor route.
///
/// Capabilities are § 5's three. `blobs.read` and `objects.write` are what
/// claiming and submitting actually do, and they are local — granted once
/// on first install, never by a prompt. `network.request` is the model
/// download and always waits for an explicit yes.
fun moonshineDeclaration(): PluginDeclaration =
    PluginDeclaration(
        pluginId = MOONSHINE_PLUGIN,
        name = "Transcription (Moonshine)",
        `class` = "provider",
        profile = "bundled",
        required = false,
        processorId = MOONSHINE_PROCESSOR,
        subscribes = SOURCE_READY_EVENT,
        emits = listOf(REVISION_ADDED_EVENT),
        capabilities = PROVIDER_LOCAL_CAPABILITIES + NETWORK_CAPABILITY,
        contributions = listOf(
            PluginContribution(
                slot = Slots.SETTINGS_FORM,
                id = "moonshine-settings",
                label = "Live caption, model and downloads",
            ),
        ),
    )

// ---------------------------------------------------------------------
// Transcription (NVIDIA NeMo) — ADR 0027 S3. NVIDIA's speech models run
// through sherpa-onnx's JVM library, app-side like Moonshine, and write the
// committed transcript through the same external processor route.
// Declared here beside the other bundled declarations and **registered by
// the desktop shell only**: the native library this build carries is the
// desktop's.
//
// The id names the runtime, not a model family (ADR 0027 amendment): ids
// are permanent and the model list may grow.
// ---------------------------------------------------------------------

const val NEMO_PLUGIN = "org.memory.sherpa"

/// Distinct from whisper's and Moonshine's, for ADR 0018 § 3's reason: the
/// vault must be able to say which engine wrote a note's words.
const val NEMO_PROCESSOR = "sherpa-transcribe"

/// Whether a model can show words while someone is still speaking.
enum class NemoKind { STREAMING, WHOLE_RECORDING }

/// One downloadable model as data. Each is one release archive of
/// sherpa-onnx's model collection, verified against the SHA-256 the release
/// publishes before it is unpacked; `archive` is also the folder it unpacks
/// to. The guidance sentence is the measured one (ADR 0027 research note).
data class NemoModel(
    val key: String,
    val name: String,
    val archive: String,
    val sha256: String,
    val bytes: Long,
    val size: String,
    val kind: NemoKind,
    val licence: String,
    val guidance: String,
) {
    val url: String get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive.tar.bz2"
    val label: String get() = "$name — $size download"
}

val NEMO_MODELS: List<NemoModel> = listOf(
    NemoModel(
        key = "nemotron-0.6b",
        name = "Nemotron 0.6B",
        archive = "sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25",
        sha256 = "0ae73a41cd51599dc7cac9ac083d9d35de53d762ca45923505fde47a3751814b",
        bytes = 463_945_198,
        size = "464 MB",
        kind = NemoKind.STREAMING,
        licence = "NVIDIA Open Model License",
        guidance = "The most accurate live model measured: words appear about a second after you " +
            "say them and keep up easily. Uses about 0.6 GB of memory while you dictate.",
    ),
    NemoModel(
        key = "fastconformer",
        name = "FastConformer",
        archive = "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8",
        sha256 = "da93061cbf7b708b6b65976f70b29f519be29df750d8cdcabf98c65645930f13",
        bytes = 105_913_204,
        size = "106 MB",
        kind = NemoKind.STREAMING,
        licence = "CC-BY-4.0",
        guidance = "Light and fast, for slower machines. Less accurate, and writes no capitals " +
            "or punctuation.",
    ),
    NemoModel(
        key = "parakeet-tdt-0.6b-v2",
        name = "Parakeet TDT 0.6B v2",
        archive = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        sha256 = "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad",
        bytes = 482_468_385,
        size = "482 MB",
        kind = NemoKind.WHOLE_RECORDING,
        licence = "CC-BY-4.0",
        guidance = "Very accurate, with capitals and punctuation, but it hears a recording " +
            "once it has finished: dictated words arrive when you stop, never as you speak.",
    ),
)

const val DEFAULT_NEMO_MODEL = "nemotron-0.6b"

/// The stored key resolved against the catalogue, on the whisper rule.
fun nemoModel(key: String?): NemoModel =
    NEMO_MODELS.firstOrNull { it.key == key?.trim() } ?: NEMO_MODELS.first { it.key == DEFAULT_NEMO_MODEL }

fun List<PluginInfo>.nemoModel(): NemoModel =
    nemoModel(firstOrNull { it.pluginId == NEMO_PLUGIN }?.let { jsonString(it.settings, MODEL_KEY) })

/// "Show words as I dictate" for this plugin. Absent means on, as for
/// whisper; a model that cannot stream ignores it.
fun List<PluginInfo>.nemoLive(): Boolean =
    firstOrNull { it.pluginId == NEMO_PLUGIN }?.let { jsonBoolean(it.settings, LIVE_KEY) } ?: true

/// Both keys every time, on the transcription document's rule.
fun nemoSettingsDocument(model: String, live: Boolean): String =
    """{"$MODEL_KEY":"${escapeJsonString(model)}","$LIVE_KEY":$live}"""

/// Capabilities as Moonshine's (ADR 0018 § 5): `blobs.read` and
/// `objects.write` are local and granted once on first install;
/// `network.request` is the model download and always waits for a yes.
fun nemoDeclaration(): PluginDeclaration =
    PluginDeclaration(
        pluginId = NEMO_PLUGIN,
        name = "Transcription (NVIDIA NeMo)",
        `class` = "provider",
        profile = "bundled",
        required = false,
        processorId = NEMO_PROCESSOR,
        subscribes = SOURCE_READY_EVENT,
        emits = listOf(REVISION_ADDED_EVENT),
        capabilities = PROVIDER_LOCAL_CAPABILITIES + NETWORK_CAPABILITY,
        contributions = listOf(
            PluginContribution(
                slot = Slots.SETTINGS_FORM,
                id = "nemo-settings",
                label = "Model and dictation",
            ),
        ),
    )

// ---------------------------------------------------------------------
// One active transcriber (ADR 0018 § 3). The kernel needs no new concept:
// which provider is active is **host policy**, and disabled already means
// "nothing new is queued and queued jobs are claimable by nobody" —
// parked, not destroyed. So the whole rule is the two functions below plus
// the shell obeying them.
// ---------------------------------------------------------------------

/// The transcription providers this host knows about, in **precedence
/// order**: whisper first because it is the incumbent and the packaged
/// default, and because ADR 0018 § 6 forbids R3 shipping a packaged mobile
/// default before MS2 has measured both engines on real hardware.
///
/// The order matters only in the state § 3 names as its open hole — a CLI
/// user or a second host enabling both. This host then treats the first as
/// the active one and the second claims nothing, which narrows the hole to
/// a race rather than closing it; only the kernel field § 7 Q4 defers can
/// close it.
val TRANSCRIPTION_PROVIDERS = listOf(TRANSCRIPTION_PLUGIN, MOONSHINE_PLUGIN, NEMO_PLUGIN)

/// The one provider writing committed transcripts right now, or null when
/// none is enabled — which is a legal state, not a fault: recordings still
/// commit and their jobs wait.
fun List<PluginInfo>.activeTranscriber(): String? =
    TRANSCRIPTION_PROVIDERS.firstOrNull { id ->
        any { it.pluginId == id && it.state == "enabled" }
    }

/// What flipping one plugin's switch must do to the others, and what to
/// tell the person (§ 3: "made mutually exclusive — enabling Moonshine
/// disables whisper, and the confirmation says so in words").
///
/// [disable] is empty for everything that is not a provider and for every
/// switch-off: turning a transcriber off must never turn another one on,
/// because enabling a provider is a decision only a person makes.
data class ProviderSwitch(val disable: List<String>, val message: String)

fun providerSwitch(plugins: List<PluginInfo>, pluginId: String, enable: Boolean): ProviderSwitch {
    val plugin = plugins.firstOrNull { it.pluginId == pluginId }
    val name = plugin?.name ?: pluginId
    val plain = if (enable) "$name switched on" else "$name switched off"
    if (!enable || pluginId !in TRANSCRIPTION_PROVIDERS) return ProviderSwitch(emptyList(), plain)
    val displaced = plugins.filter {
        it.pluginId != pluginId && it.pluginId in TRANSCRIPTION_PROVIDERS && it.state == "enabled"
    }
    if (displaced.isEmpty()) return ProviderSwitch(emptyList(), plain)
    return ProviderSwitch(
        displaced.map { it.pluginId },
        "$plain — ${displaced.joinToString(" and ") { it.name }} switched off. " +
            "One transcriber writes your notes.",
    )
}

/// The sentence the switch shows **before** it is flipped, so "enabling
/// this turns that off" is something a person reads rather than discovers.
/// Null when the switch displaces nothing.
fun providerSwitchWarning(plugins: List<PluginInfo>, pluginId: String): String? {
    val displaced = providerSwitch(plugins, pluginId, enable = true).disable
    if (displaced.isEmpty()) return null
    val names = displaced.mapNotNull { id -> plugins.firstOrNull { it.pluginId == id }?.name }
    return "Switching this on switches ${names.joinToString(" and ")} off — " +
        "one transcriber writes your notes."
}

/// What registering a transcription provider must do to its own switch.
///
/// **Registering is not choosing.** The kernel defaults a plugin it has
/// never seen to Enabled, which is right for a plugin that is the only one
/// of its kind and wrong for the second transcriber: bundling Moonshine
/// then switched it on for everybody — on a fresh install and, worse, on
/// an R1 → R3 upgrade where the row already existed — leaving two
/// transcribers on at once. The visible half was a Plugins screen showing
/// both; the invisible half was every recording queueing a second job that
/// nothing in the process would ever claim, climbing forever.
///
/// So a provider registered while another transcriber is already active is
/// registered and then **switched straight off**. ADR 0018 § 6 keeps
/// whisper the incumbent until MS2 has measured both engines on real
/// hardware, so Moonshine is opt-in; § 3's precedence order is what decides
/// which one "already active" means, and that order is the shell's whole
/// one-transcriber rule.
///
/// It is a switch-off and never a switch-on: turning a transcriber on is a
/// decision only a person makes, so a person who chose Moonshine and
/// switched whisper off keeps their choice through every later open.
data class ProviderRegistration(val switchOff: Boolean, val message: String?)

/// [plugins] is the registry **after** the declaration was registered.
fun providerRegistration(plugins: List<PluginInfo>, pluginId: String): ProviderRegistration {
    val none = ProviderRegistration(false, null)
    if (pluginId !in TRANSCRIPTION_PROVIDERS) return none
    val plugin = plugins.firstOrNull { it.pluginId == pluginId } ?: return none
    if (plugin.state != "enabled") return none
    // The active one is the first enabled provider in precedence order.
    // Being it is exactly what "nobody else is writing the transcripts"
    // means, so there is nothing to do.
    val active = plugins.activeTranscriber()
    if (active == null || active == pluginId) return none
    val incumbent = plugins.firstOrNull { it.pluginId == active }?.name ?: active
    return ProviderRegistration(
        true,
        "${plugin.name} is installed and switched off — $incumbent is writing your " +
            "notes. One transcriber writes your notes; switch this one on in " +
            "Settings ▸ Plugins to change that.",
    )
}

/// Register a transcription provider **already switched off** when another
/// transcriber is writing the notes (ADR 0018 § 3).
///
/// **The window this closes.** `registerPlugin` defaults a plugin the vault
/// has never seen to Enabled, so the shipped order was: register (on) →
/// read the registry → switch off. Between the first and the last, this
/// vault has two enabled transcribers and a live subscription for the new
/// one — and the vault is shared: the recording service holds its own
/// handle, and multi-instance on one vault is supported use. A capture
/// committed by that other handle inside the window queues a job for a
/// provider that is about to be switched off, and a job queued for a
/// disabled provider is parked rather than run — an orphan nobody claims,
/// counted in Settings ▸ About as work waiting on the person.
///
/// So a provider arriving into a vault that already has an active
/// transcriber is registered disabled in **one** call, and there is no
/// moment when both are on.
///
/// [applyOneTranscriberRule] stays, and is not made redundant by this: it
/// answers the *other* case, an upgraded vault whose provider row already
/// exists and is already enabled. Registration cannot fix that one, because
/// there the plugin's state is a decision the vault has already recorded.
///
/// [installed] is the row **before** this registration — null when the vault
/// has never seen this plugin.
fun registerProviderRespectingTheRule(
    gilune: Gilune,
    declaration: PluginDeclaration,
    installed: PluginInfo?,
): String {
    val fresh = installed == null
    val occupied = runCatching { gilune.plugins().activeTranscriber() }.getOrNull()
    val displaced = fresh &&
        declaration.pluginId in TRANSCRIPTION_PROVIDERS &&
        occupied != null &&
        occupied != declaration.pluginId
    return if (displaced) {
        gilune.registerPluginDisabled(declaration)
    } else {
        gilune.registerPlugin(declaration)
    }
}

/// What an **upgraded** vault is told, once, when the one-transcriber rule
/// takes a provider away from it.
///
/// [providerRegistration]'s message is a message-line sentence: it goes
/// under the composer, is replaced by the next thing that happens, and is
/// gone before anybody who was not looking at the screen at that second
/// could read it. That is right for a fresh install, where nothing was ever
/// taken away — the provider simply arrived switched off.
///
/// It is wrong for the case this exists for. A person who had the live
/// caption running found it silently gone after an update, with no sentence
/// they could still find and nothing on the plugin's own screen saying why.
/// So the upgrade case persists a notice that stays until it is dismissed,
/// and says the one thing a message line cannot: **where to get it back**.
///
/// Pure, and phrased in the plugins' own names so a renamed plugin cannot
/// leave the sentence naming something the screen does not show.
fun providerUpgradeNotice(switchedOffName: String, incumbentName: String): String =
    "$switchedOffName was switched off because one transcriber writes your notes — " +
        "switch it on here to use the live caption instead of $incumbentName"

/// Apply that rule on one handle, and answer with what to say — or null
/// when nothing changed, which is every open after the first.
///
/// **Both handles run it** (ADR 0018 § 2's headlessness note): the activity
/// registers the declaration, and the recording service opens a `Gilune` of
/// its own on a vault the activity may not have opened since the upgrade.
/// Plugin state is vault state, so whichever gets there first settles it
/// and the other finds nothing to do.
fun applyOneTranscriberRule(gilune: Gilune, pluginId: String): String? {
    val decision = providerRegistration(
        runCatching { gilune.plugins() }.getOrNull() ?: return null,
        pluginId,
    )
    if (!decision.switchOff) return null
    return if (runCatching { gilune.setPluginEnabled(pluginId, false) }.isSuccess) {
        decision.message
    } else {
        null
    }
}

/// What "waiting jobs" means when a plugin is switched off.
///
/// Disabled means **parked, not destroyed** (ADR 0018 § 3): nothing new is
/// queued and queued jobs are claimable by nobody. That is the shipped
/// behaviour and it is the right one — switching a provider back on
/// resumes the very same job — but it also means the count in Settings ▸
/// About can hold jobs nobody will ever claim, which reads as a backlog
/// somebody has to clear. An upgraded vault that had both transcribers on
/// for a while carries exactly that.
///
/// **Two numbers, both counted by the kernel.** The first round of this
/// said "some are parked" — a hedge, because the shell could see which
/// plugins were off but not how much work belonged to them, and a sentence
/// that says "some" of a number a person is looking at answers nothing. The
/// kernel counts parked work directly now ([jobsParked], a subset of
/// [jobsPending]), so the sentence says how many are waiting on a switch and
/// how many an enabled plugin could run this minute.
///
/// Null when there is nothing to explain: no jobs waiting, or none of them
/// parked. A vault where every waiting job is parked and a vault where none
/// is are different states and read differently.
fun waitingJobsNote(plugins: List<PluginInfo>, jobsPending: Long, jobsParked: Long): String? {
    if (jobsPending <= 0L || jobsParked <= 0L) return null
    val off = plugins.filter { it.state != "enabled" && !it.processorId.isNullOrBlank() }
    // The kernel says work is parked, so it is — even if this shell cannot
    // name the plugin holding it (one it does not know about, or one
    // removed since). The number is the fact; the name is a courtesy.
    val whose = if (off.isEmpty()) {
        "a plugin that is switched off"
    } else {
        off.joinToString(" and ") { it.name }
    }
    val running = jobsPending - jobsParked
    val rest = when {
        running <= 0L -> "nothing else is waiting"
        running == 1L -> "1 more is waiting on Gilune rather than on you"
        else -> "$running more are waiting on Gilune rather than on you"
    }
    return "$jobsParked parked for $whose, which is switched off — they wait rather than " +
        "run, and nothing is waiting on you; $rest"
}

/// A capture that preserved audio — the only kind a transcriber has
/// anything to say about. Read off the blob's own path rather than a media
/// key the summary does not carry.
fun SourceSummary.isRecording(): Boolean =
    blobRelPath?.endsWith(".wav", ignoreCase = true) == true

/// How far back a catch-up count looks. Deliberately generous and
/// deliberately bounded: the question is asked on a settings tap, and a
/// vault with more recordings than this has a bigger problem than an
/// undercount.
const val WORDLESS_SCAN_LIMIT: UInt = 2000u

/// **The scoped catch-up's set** (ADR 0018 § 3): recordings that still have
/// no words. Switching provider must not re-transcribe a vault — idempotency
/// is keyed `{processor_id}:{event_id}`, so a *different* processor id makes
/// every past capture a fresh job, and a blanket run would rewrite every note
/// body with the new engine's words. Nothing would be lost, but every note
/// would change.
///
/// So this is what the shell counts and says before it runs anything, and it
/// is the same question the provider loop asks of each job it claims — one
/// definition of "has no words yet", asked in both places.
fun wordlessRecordings(gilune: Gilune, limit: UInt = WORDLESS_SCAN_LIMIT): List<SourceSummary> =
    gilune.recentSources(limit).filter { it.isRecording() && it.hasNoWords() }

/// Whether this summary's own preview says the capture has no words yet.
///
/// **One query rather than one per recording.** This used to ask
/// `objectText` for every recording in the list — up to two thousand calls
/// across the boundary, each opening its own read, behind a settings tap on
/// a phone. The answer was already in hand: `recent_sources` builds
/// `preview` from the head document's own `text`, and falls back to a
/// bracketed stand-in naming the media (`[wav capture]`) precisely when
/// there is no text to show.
///
/// So the two shapes that mean "no words" are a blank preview — a document
/// whose `text` is empty — and that stand-in. Anything else is words
/// somebody or something wrote, and the catch-up leaves it alone.
///
/// **The coupling is to the kernel's preview and is deliberate**: this is
/// the same judgement the provider makes per job from the document itself,
/// so a drift shows up as the catch-up count disagreeing with what the
/// catch-up does, and `wordlessRecordingsCountTheSameSetTheProviderSkips`
/// asserts the two agree against a real kernel.
fun SourceSummary.hasNoWords(): Boolean =
    preview.isBlank() || (preview.startsWith("[") && preview.endsWith(" capture]"))

/// Local capabilities this declaration introduces that the installed one
/// never offered, and so that nobody has ever been asked about or revoked
/// (host consent policy; ADR 0018 § 5).
///
/// It exists because a plugin's declaration may **grow** — R1's Moonshine
/// declared `network.request` alone and R3's needs `blobs.read` and
/// `objects.write` to claim and submit. Granting on first install alone
/// would leave an R1 vault's provider permanently short of two local
/// capabilities it was never offered; granting everything on every
/// registration would re-grant after a revoke, which is precisely what the
/// policy forbids. "Newly declared" is the honest middle: never offered
/// before, so never refused.
fun newlyDeclaredLocalCapabilities(
    installed: PluginInfo?,
    declared: List<String>,
    local: List<String> = PROVIDER_LOCAL_CAPABILITIES,
): List<String> {
    val known = installed?.capabilities.orEmpty()
    return declared.filter { it in local && it !in known }
}

private const val CAPTION_KEY = "caption"
private const val WIFI_ONLY_KEY = "wifiOnly"

/// **Absent means on** (ADR 0018 § 5: caption on by default). A settings
/// document written before this key existed — or none at all — must read
/// as the shipped default rather than as a switch nobody ever saw quietly
/// holding the feature back. Same rule the tasks panel switch uses.
///
/// Not gated on the plugin's state, for [tasksFilter]'s reason: the
/// plugin's own screen must show what is stored even while it is off.
fun List<PluginInfo>.moonshineCaption(): Boolean =
    firstOrNull { it.pluginId == MOONSHINE_PLUGIN }?.let { jsonBoolean(it.settings, CAPTION_KEY) } ?: true

/// **Absent means on**, and this default is the cautious one rather than
/// the convenient one: 45 MB over somebody's mobile data because an app
/// decided to fetch is exactly what § 4's rule exists to prevent.
fun List<PluginInfo>.moonshineWifiOnly(): Boolean =
    firstOrNull { it.pluginId == MOONSHINE_PLUGIN }?.let { jsonBoolean(it.settings, WIFI_ONLY_KEY) } ?: true

fun List<PluginInfo>.moonshineModelKey(): String? =
    firstOrNull { it.pluginId == MOONSHINE_PLUGIN }?.let { jsonString(it.settings, MODEL_KEY) }

fun List<PluginInfo>.moonshineModel(): MoonshineModel = moonshineModel(moonshineModelKey())

/// All three keys travel on every write, on the tasks and transcription
/// precedent: whichever control a person touched, the other two carry
/// their stored values, so turning the caption off can never revert the
/// model or silently re-enable a mobile-data download.
fun moonshineSettingsDocument(model: String, caption: Boolean, wifiOnly: Boolean): String =
    """{"$MODEL_KEY":"${escapeJsonString(model)}","$CAPTION_KEY":$caption,"$WIFI_ONLY_KEY":$wifiOnly}"""
