// Transcription roles (ADR 0033): one engine while you speak, one for
// recordings, each chosen per device from the engines the transcription
// plugins publish.
//
// Where each answer lives (§ 3):
//  - **Recordings** is two facts in kernel state: which provider takes jobs
//    (`takesJobs`, § 4) and the `recordingsEngine` key in that provider's
//    own settings document.
//  - **While you speak** is the `liveEngine` key in the settings of the
//    provider that fills it; every other installed provider carries `""`.
//    No provider holding the key at all means nobody has chosen yet, and
//    the answer is derived from the settings that came before (§ 3's
//    carry-over: nobody's engine changes until they choose).
//
// The keys are new rather than the ADR's bare `live`/`recordings` because
// `live` already names the "Show words as I dictate" boolean in two
// shipped documents; reusing it would make a stored `true` read as an
// engine key.

package org.gilune.app

import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.jsonString
import org.json.JSONArray
import org.json.JSONObject
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginInfo

enum class TranscriptionRole(val key: String) {
    LIVE("live"),
    RECORDINGS("recordings"),
}

/// One engine as a plugin declares it in [Slots.TRANSCRIPTION_ENGINES].
/// `engine` is the plugin's own key, unique within that plugin only.
data class TranscriptionEngine(
    val pluginId: String,
    val pluginName: String,
    val engine: String,
    val label: String,
    val roles: Set<TranscriptionRole>,
    val wordTimes: Boolean,
    val downloadBytes: Long?,
    val guidance: String,
) {
    fun choice(): EngineChoice = EngineChoice(pluginId, engine)
}

/// What fills a role: a plugin and its engine key.
data class EngineChoice(val pluginId: String, val engine: String)

const val LIVE_ENGINE_KEY = "liveEngine"
const val RECORDINGS_ENGINE_KEY = "recordingsEngine"

/// A [Slots.TRANSCRIPTION_ENGINES] entry, built from a plugin's own
/// catalogue data so the declaration and the catalogue cannot disagree.
fun transcriptionEngineContribution(
    id: String,
    label: String,
    engine: String,
    roles: Set<TranscriptionRole>,
    wordTimes: Boolean,
    downloadBytes: Long?,
    guidance: String,
): PluginContribution {
    val config = JSONObject()
        .put("engine", engine)
        .put("roles", JSONArray(TranscriptionRole.entries.filter { it in roles }.map { it.key }))
        .put("wordTimes", wordTimes)
        .put("guidance", guidance)
    if (downloadBytes != null) config.put("download", downloadBytes)
    return PluginContribution(Slots.TRANSCRIPTION_ENGINES, id, label, config.toString())
}

/// The transcription providers this vault has installed (removed ones
/// excluded), in precedence order, **switched-off ones included**: the
/// page offers their engines, and choosing one switches it on.
fun List<PluginInfo>.transcriptionProviders(): List<PluginInfo> =
    TRANSCRIPTION_PROVIDERS.mapNotNull { id -> firstOrNull { it.pluginId == id && it.state != "removed" } }

/// Every engine every installed provider publishes, providers in
/// precedence order and engines in declaration order. An entry whose
/// config does not parse, or that names no engine or no role, is dropped
/// rather than offered as a choice that goes nowhere.
fun List<PluginInfo>.transcriptionEngines(): List<TranscriptionEngine> =
    transcriptionProviders().flatMap { plugin ->
        plugin.contributions.filter { it.slot == Slots.TRANSCRIPTION_ENGINES }.mapNotNull { contribution ->
            runCatching {
                val config = JSONObject(contribution.config)
                val engine = config.optString("engine").trim()
                val roles = config.optJSONArray("roles")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        TranscriptionRole.entries.firstOrNull { it.key == array.optString(i) }
                    }.toSet()
                }.orEmpty()
                if (engine.isEmpty() || roles.isEmpty()) return@runCatching null
                TranscriptionEngine(
                    pluginId = plugin.pluginId,
                    pluginName = plugin.name,
                    engine = engine,
                    label = contribution.label,
                    roles = roles,
                    wordTimes = config.optBoolean("wordTimes", false),
                    downloadBytes = if (config.has("download")) config.optLong("download") else null,
                    guidance = config.optString("guidance"),
                )
            }.getOrNull()
        }
    }

fun List<TranscriptionEngine>.forRole(role: TranscriptionRole): List<TranscriptionEngine> = filter { role in it.roles }

fun List<PluginInfo>.engineFor(choice: EngineChoice?): TranscriptionEngine? =
    choice?.let { c -> transcriptionEngines().firstOrNull { it.pluginId == c.pluginId && it.engine == c.engine } }

/// The model key each provider stored before roles existed, resolved
/// against its own catalogue (absent or unknown falls back to its default).
internal fun legacyEngineKey(plugin: PluginInfo): String? = when (plugin.pluginId) {
    TRANSCRIPTION_PLUGIN -> listOf(plugin).transcriptionModel().key
    NEMO_PLUGIN -> listOf(plugin).nemoModel().key
    MOONSHINE_PLUGIN -> listOf(plugin).moonshineModel().key
    else -> null
}

/// The per-plugin "words as you speak" switch that roles retire. Absent
/// meant on for every provider.
internal fun legacyLive(plugin: PluginInfo): Boolean = when (plugin.pluginId) {
    TRANSCRIPTION_PLUGIN -> listOf(plugin).transcriptionLive()
    NEMO_PLUGIN -> listOf(plugin).nemoLive()
    MOONSHINE_PLUGIN -> listOf(plugin).moonshineCaption()
    else -> true
}

/// Whether a catalogue model can show words while someone speaks, for a
/// provider that publishes no engines (a declaration from before roles).
private fun catalogueStreams(pluginId: String, key: String): Boolean = when (pluginId) {
    NEMO_PLUGIN -> nemoModel(key).kind == NemoKind.STREAMING
    else -> true
}

/// A stored key resolved against what the plugin offers for that role:
/// the stored key, else the key its old `model` setting named, else its
/// first engine for the role. A provider that publishes no engines keeps
/// whatever was stored.
internal fun resolveEngine(plugin: PluginInfo, stored: String?, role: TranscriptionRole): String? {
    val offered = listOf(plugin).transcriptionEngines().forRole(role).map { it.engine }
    val wanted = listOfNotNull(stored?.trim()?.takeIf { it.isNotEmpty() }, legacyEngineKey(plugin))
    if (offered.isEmpty()) return wanted.firstOrNull()
    return wanted.firstOrNull { it in offered } ?: offered.first()
}

/// The provider transcribing recordings on this device: the first switched
/// on, taking jobs, in precedence order. Null is a legal state —
/// recordings are kept and wait.
fun List<PluginInfo>.recordingsProvider(): PluginInfo? =
    transcriptionProviders().firstOrNull { it.state == "enabled" && it.takesJobs }

fun List<PluginInfo>.recordingsChoice(): EngineChoice? {
    val provider = recordingsProvider() ?: return null
    val engine = resolveEngine(provider, jsonString(provider.settings, RECORDINGS_ENGINE_KEY), TranscriptionRole.RECORDINGS)
        ?: return null
    return EngineChoice(provider.pluginId, engine)
}

/// Whether anybody has chosen an engine for while you speak — Off counts.
fun List<PluginInfo>.liveChosen(): Boolean =
    transcriptionProviders().any { jsonString(it.settings, LIVE_ENGINE_KEY) != null }

/// The engine for while you speak, or null for Off — chosen Off, or the
/// provider holding the choice is switched off (the role is then empty
/// and the page says so, § 6).
///
/// Never chosen: the old active transcriber's model, if its old switch was
/// on and that model can stream; otherwise Off.
fun List<PluginInfo>.liveChoice(): EngineChoice? {
    val providers = transcriptionProviders()
    if (liveChosen()) {
        val holder = providers.firstOrNull {
            it.state == "enabled" && !jsonString(it.settings, LIVE_ENGINE_KEY).isNullOrBlank()
        } ?: return null
        val engine = resolveEngine(holder, jsonString(holder.settings, LIVE_ENGINE_KEY), TranscriptionRole.LIVE) ?: return null
        return EngineChoice(holder.pluginId, engine)
    }
    val active = recordingsProvider() ?: providers.firstOrNull { it.state == "enabled" } ?: return null
    if (!legacyLive(active)) return null
    val key = legacyEngineKey(active) ?: return null
    val offered = listOf(active).transcriptionEngines()
    val streams = if (offered.isEmpty()) {
        catalogueStreams(active.pluginId, key)
    } else {
        offered.any { it.engine == key && TranscriptionRole.LIVE in it.roles }
    }
    return if (streams) EngineChoice(active.pluginId, key) else null
}

/// The installed provider whose settings hold the live choice while it is
/// switched off — the one case where "Off" was not chosen. Null otherwise.
fun List<PluginInfo>.liveHolderSwitchedOff(): PluginInfo? =
    transcriptionProviders().firstOrNull {
        it.state != "enabled" && !jsonString(it.settings, LIVE_ENGINE_KEY).isNullOrBlank()
    }?.takeIf { liveChoice() == null }

/// A settings document with some keys set and every other key kept: the
/// language somebody chose must never be dropped by choosing an engine.
/// An unreadable document starts again from the keys given.
fun settingsWith(document: String, changes: Map<String, Any>): String {
    val json = runCatching { JSONObject(document.ifBlank { "{}" }) }.getOrElse { JSONObject() }
    for ((key, value) in changes) json.put(key, value)
    return json.toString()
}

// ---------------------------------------------------------------------
// Choosing. Each writes through the kernel; each returns the sentence the
// status line shows.
// ---------------------------------------------------------------------

private fun Gilune.writeSettings(plugin: PluginInfo, changes: Map<String, Any>) =
    setPluginSettings(plugin.pluginId, settingsWith(plugin.settings, changes))

/// Write both answers down as they stand, so that switching a plugin on or
/// off, or choosing the other role, cannot move one by changing what it
/// was derived from. Writes nothing once both are written.
fun pinTranscriptionChoices(gilune: Gilune) {
    val plugins = gilune.plugins()
    if (!plugins.liveChosen()) {
        val live = plugins.liveChoice()
        for (provider in plugins.transcriptionProviders()) {
            gilune.writeSettings(provider, mapOf(LIVE_ENGINE_KEY to if (provider.pluginId == live?.pluginId) live.engine else ""))
        }
    }
    val recordings = plugins.recordingsChoice() ?: return
    val provider = gilune.plugins().first { it.pluginId == recordings.pluginId }
    if (jsonString(provider.settings, RECORDINGS_ENGINE_KEY).isNullOrBlank()) {
        gilune.writeSettings(provider, mapOf(RECORDINGS_ENGINE_KEY to recordings.engine))
    }
}

/// Make one provider the one that takes recordings, in the order that
/// never leaves a moment with nobody taking them: a switched-off provider
/// is switched on **taking no jobs** first, then given the jobs, then every
/// other provider has its jobs withheld and its waiting recordings handed
/// over. Returns how many recordings moved.
///
/// Every other provider is withheld whether it is on or off, so switching
/// one of them on later cannot make two take jobs. Waiting recordings move
/// from all of them: a person who chose this engine for recordings wants
/// the ones still without words heard by it. Only jobs that never produced
/// anything move (the kernel's rule), so nothing already transcribed is
/// touched.
fun takeRecordings(gilune: Gilune, pluginId: String): Long {
    val plugins = gilune.plugins()
    val taker = plugins.first { it.pluginId == pluginId }
    if (taker.state != "enabled") {
        gilune.setTakesJobs(pluginId, false)
        gilune.setPluginEnabled(pluginId, true)
    }
    gilune.setTakesJobs(pluginId, true)
    var moved = 0L
    for (other in plugins.transcriptionProviders().filter { it.pluginId != pluginId }) {
        if (other.takesJobs) gilune.setTakesJobs(other.pluginId, false)
        val from = other.processorId
        val to = taker.processorId
        if (!from.isNullOrBlank() && !to.isNullOrBlank()) moved += gilune.handOverJobs(from, to)
    }
    return moved
}

/// Whisper holds one model per handle (`enableWhisper`), so both roles on
/// Whisper must name the same model: true when choosing [chosen] must
/// rewrite the other role too.
private fun whisperCoupled(plugins: List<PluginInfo>, chosen: EngineChoice, other: EngineChoice?): Boolean =
    chosen.pluginId == TRANSCRIPTION_PLUGIN && other?.pluginId == TRANSCRIPTION_PLUGIN && other.engine != chosen.engine

/// The sentence the page shows beside Whisper's rows when the other role
/// is Whisper too.
const val WHISPER_ONE_MODEL_SENTENCE =
    "Whisper uses one model at a time, so choosing a different Whisper model here changes it for both."

private fun engineName(plugins: List<PluginInfo>, choice: EngineChoice): String =
    plugins.engineFor(choice)?.label ?: choice.engine

/// Choose the engine for while you speak, or Off (null). A switched-off
/// plugin is switched on, taking no recordings if another plugin takes
/// them.
fun chooseLiveEngine(gilune: Gilune, choice: EngineChoice?): String {
    pinTranscriptionChoices(gilune)
    val said = mutableListOf<String>()
    if (choice != null) {
        val plugin = gilune.plugins().first { it.pluginId == choice.pluginId }
        if (plugin.state != "enabled") said += switchOnBesideTheTaker(gilune, choice.pluginId)
    }
    val plugins = gilune.plugins()
    for (provider in plugins.transcriptionProviders()) {
        gilune.writeSettings(provider, mapOf(LIVE_ENGINE_KEY to if (provider.pluginId == choice?.pluginId) choice.engine else ""))
    }
    if (choice == null) return "while you speak: off — dictated words arrive when you stop"
    val recordings = plugins.recordingsChoice()
    if (whisperCoupled(plugins, choice, recordings)) {
        val whisper = gilune.plugins().first { it.pluginId == TRANSCRIPTION_PLUGIN }
        gilune.writeSettings(whisper, mapOf(RECORDINGS_ENGINE_KEY to choice.engine))
        said += "recordings use it too, because Whisper uses one model at a time"
    }
    val after = gilune.plugins()
    return (listOf("while you speak: ${engineName(after, choice)}") + said).joinToString(" — ")
}

/// Choose the engine for recordings. Another plugin's engine moves the
/// recordings still waiting for words to it, and the sentence says how
/// many.
fun chooseRecordingsEngine(gilune: Gilune, choice: EngineChoice): String {
    pinTranscriptionChoices(gilune)
    val said = mutableListOf<String>()
    val before = gilune.plugins()
    val previous = before.recordingsProvider()
    val moved = if (previous?.pluginId == choice.pluginId) 0L else takeRecordings(gilune, choice.pluginId)
    val taker = gilune.plugins().first { it.pluginId == choice.pluginId }
    gilune.writeSettings(taker, mapOf(RECORDINGS_ENGINE_KEY to choice.engine))
    val live = gilune.plugins().liveChoice()
    if (whisperCoupled(before, choice, live)) {
        gilune.writeSettings(gilune.plugins().first { it.pluginId == TRANSCRIPTION_PLUGIN }, mapOf(LIVE_ENGINE_KEY to choice.engine))
        said += "while you speak uses it too, because Whisper uses one model at a time"
    }
    if (moved > 0) said += if (moved == 1L) "1 waiting recording moved to it" else "$moved waiting recordings moved to it"
    return (listOf("recordings: ${engineName(gilune.plugins(), choice)}") + said).joinToString(" — ")
}

/// Switch a provider on as the plugin manager's switch does: taking no
/// recordings when another provider already takes them, so nobody's
/// engine changes until they choose.
private fun switchOnBesideTheTaker(gilune: Gilune, pluginId: String): String {
    val plugins = gilune.plugins()
    val taker = plugins.recordingsProvider()?.takeIf { it.pluginId != pluginId }
    val name = plugins.firstOrNull { it.pluginId == pluginId }?.name ?: pluginId
    return if (taker != null) {
        gilune.setTakesJobs(pluginId, false)
        gilune.setPluginEnabled(pluginId, true)
        "$name switched on; ${taker.name} still transcribes your recordings"
    } else {
        val moved = takeRecordings(gilune, pluginId)
        "$name switched on and transcribes your recordings" +
            when {
                moved == 1L -> " (1 waiting recording moved to it)"
                moved > 1L -> " ($moved waiting recordings moved to it)"
                else -> ""
            }
    }
}

/// The plugin manager's switch for a transcription provider (§ 6).
/// Switching one on never switches another off; switching one off leaves
/// the roles it filled empty and says so.
fun switchTranscriptionProvider(gilune: Gilune, pluginId: String, enable: Boolean): String {
    pinTranscriptionChoices(gilune)
    val plugins = gilune.plugins()
    val name = plugins.firstOrNull { it.pluginId == pluginId }?.name ?: pluginId
    if (enable) return switchOnBesideTheTaker(gilune, pluginId)
    val heldRecordings = plugins.recordingsProvider()?.pluginId == pluginId
    val heldLive = plugins.liveChoice()?.pluginId == pluginId
    gilune.setPluginEnabled(pluginId, false)
    return buildList {
        add("$name switched off")
        if (heldRecordings) add("recordings: nothing chosen — they are kept and wait")
        if (heldLive) add("while you speak: nothing chosen")
    }.joinToString(" — ")
}

/// More than one provider switched on and taking jobs — a vault from
/// before roles, or the CLI — keeps the first in precedence order taking
/// them and withholds the rest (§ 4: one engine per role). Nothing is
/// switched off. Returns the sentence, or null when nothing changed.
fun keepOneRecordingsTaker(gilune: Gilune): String? {
    val takers = runCatching { gilune.plugins() }.getOrNull()
        ?.transcriptionProviders()?.filter { it.state == "enabled" && it.takesJobs }.orEmpty()
    if (takers.size < 2) return null
    for (extra in takers.drop(1)) runCatching { gilune.setTakesJobs(extra.pluginId, false) }
    return "${takers.first().name} transcribes your recordings; " +
        takers.drop(1).joinToString(" and ") { it.name } + " stays on and takes none — Settings ▸ Transcription"
}

// ---------------------------------------------------------------------
// What the page says about each engine and about speaker labels. Pure, so
// the words are tested without a window.
// ---------------------------------------------------------------------

enum class EngineReadiness { READY, DOWNLOAD, ALLOW_NETWORK, SWITCHED_OFF }

/// A row's state at the right of the page (§ 6). A switched-off plugin
/// says so first: choosing its engine switches it on.
fun engineReadiness(engine: TranscriptionEngine, plugins: List<PluginInfo>, onDisk: Boolean): EngineReadiness {
    val plugin = plugins.firstOrNull { it.pluginId == engine.pluginId }
    return when {
        plugin == null || plugin.state != "enabled" -> EngineReadiness.SWITCHED_OFF
        onDisk || engine.downloadBytes == null -> EngineReadiness.READY
        NETWORK_CAPABILITY in plugin.granted -> EngineReadiness.DOWNLOAD
        else -> EngineReadiness.ALLOW_NETWORK
    }
}

/// Bytes as a person reads a download: whole megabytes.
fun megabytes(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"

fun EngineReadiness.words(engine: TranscriptionEngine): String = when (this) {
    EngineReadiness.READY -> "Ready"
    EngineReadiness.DOWNLOAD -> "Download ${megabytes(engine.downloadBytes ?: 0)}"
    EngineReadiness.ALLOW_NETWORK -> "Allow network access"
    EngineReadiness.SWITCHED_OFF -> "Switched off"
}

/// The page's one line about speaker labels (§ 6): on or off, and whether
/// the Recordings engine reports when each word was said, which labels
/// need (ADR 0030).
fun speakerLabelsLine(plugins: List<PluginInfo>): String {
    val installed = plugins.firstOrNull { it.pluginId == SPEAKERS_PLUGIN && it.state != "removed" }
        ?: return "Speaker labels: not installed."
    if (installed.state != "enabled" || !plugins.speakerLabelsOn()) {
        return "Speaker labels: off. Switch them on in their plugin's settings to have recordings split by speaker."
    }
    val recordings = plugins.engineFor(plugins.recordingsChoice())
        ?: return "Speaker labels: on, but nothing is chosen for recordings."
    return if (recordings.wordTimes) {
        "Speaker labels: on, and they work with ${recordings.label}, which reports when each word was said."
    } else {
        "Speaker labels: on, but ${recordings.label} does not report when each word was said, so recordings " +
            "are not split by speaker."
    }
}

/// Which roles a provider fills right now, in words, for its own screen
/// (§ 6). Null when it fills none.
fun rolesFilledBy(plugins: List<PluginInfo>, pluginId: String): String? {
    val parts = buildList {
        plugins.liveChoice()?.takeIf { it.pluginId == pluginId }?.let {
            add("while you speak (${plugins.engineFor(it)?.label ?: it.engine})")
        }
        plugins.recordingsChoice()?.takeIf { it.pluginId == pluginId }?.let {
            add("recordings (${plugins.engineFor(it)?.label ?: it.engine})")
        }
    }
    return if (parts.isEmpty()) null else "Used for " + parts.joinToString(" and ") + "."
}
