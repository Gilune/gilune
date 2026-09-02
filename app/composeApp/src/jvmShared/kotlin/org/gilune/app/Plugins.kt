// What the shell contributes and how it reads contributions back. The
// shell renders slots; what fills them comes from the registry, so
// removing a plugin removes its controls (ADR 0011). Nothing here decides
// behaviour — the kernel owns whether a plugin may run.

package org.gilune.app

import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.SourceSummary
import java.time.LocalDate

object Slots {
    const val CAPTURE_ACTIONS = "capture.actions"
    const val NOTE_ACTIONS = "note.actions"
    const val NAVIGATION = "navigation"
    const val COMMAND_PALETTE = "command.palette"
    const val SETTINGS_FORM = "settings.form"

    /// The editor seams ADR 0015 § 5 adds. Shell-published: anyone may
    /// contribute, and the shell renders what they declare.
    const val EDITOR_DECORATIONS = "editor.decorations"
    const val EDITOR_SUGGEST = "editor.suggest"
    const val SEARCH_TYPES = "search.types"

    /// A fence info string plus a renderer (§ 5), on the same shell-owned
    /// terms as [EDITOR_DECORATIONS]: rendering may never change a byte,
    /// and an info string nobody registered stays an ordinary code block.
    ///
    /// **Its first and only producer in v1 is the shell itself** — the
    /// `filter` fence belongs to the search machinery, not to the tasks
    /// plugin (§ 4), which is what makes a block go on rendering when
    /// every removable plugin is gone. The tasks plugin contributes
    /// nothing here; what it contributes is a *type* ([SEARCH_TYPES]),
    /// and a block that asked for a type nobody registered says so.
    ///
    /// Honestly, one producer is the shape that let `settings.form` rot:
    /// the registry is real ([BlockRenderers]) and takes a contribution as
    /// an entry, but the second producer is still notional.
    const val EDITOR_BLOCKS = "editor.blocks"

    /// **Published by a plugin, not by the shell** (ADR 0015 § 3) — the
    /// tasks plugin owns task-line semantics, so a field must compose
    /// through it. A contribution here is live only while *both* the
    /// publisher and the contributor are enabled; see [taskFields].
    const val TASKS_FIELDS = "tasks.fields"
}

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
                    jsonString(contribution.config, PANEL_KEY)
                        ?.let { NavigationPanel(it, contribution.label) }
                }
        }
    }

// ---------------------------------------------------------------------
// The tasks plugin (ADR 0015). A bundled Kotlin shell plugin on the
// voice-capture precedent: no processor, no subscription, no emitted
// events, and **no capabilities** — its whole surface is contributions to
// shell-rendered slots, and consent is the enable switch (§ 7).
// ---------------------------------------------------------------------

const val TASKS_PLUGIN = "org.memory.tasks"

/// The plugin's own field, and the **first registration into the slot it
/// publishes** (§ 3): the field list *is* that registry, one entry long,
/// so a People plugin's `assignee` arrives through the same door rather
/// than beside a hard-coded case.
const val TASKS_DUE_FIELD = "due"
const val TASKS_DECORATIONS_CONTRIBUTION = "task-dates"
const val TASKS_SUGGEST_CONTRIBUTION = "slash-menu"
const val TASKS_NAVIGATION_CONTRIBUTION = "tasks"

/// The rail panel the navigation contribution names, and so the key the
/// shell's `RailPanel` carries while the agenda is showing. Equal to the
/// contribution's id here, and not by rule: the id names the contribution
/// among this plugin's, the `config` names the destination, and a second
/// panel from the same plugin would part them.
const val TASKS_RAIL_PANEL = "tasks"

/// Declared here for the same reason the microphone is: the behaviour
/// behind these ids is shell Kotlin in v1, so the shell is what declares
/// them. The ids and the JSON `config` are the wasm trajectory — a
/// sandboxed plugin declares the same four strings once the runtime can
/// carry the behaviour behind them (§ 7).
fun tasksDeclaration(): PluginDeclaration =
    PluginDeclaration(
        pluginId = TASKS_PLUGIN,
        name = "Tasks",
        `class` = "domain",
        profile = "bundled",
        required = false,
        processorId = null,
        subscribes = null,
        emits = emptyList(),
        capabilities = emptyList(),
        contributions = listOf(
            PluginContribution(
                slot = Slots.TASKS_FIELDS,
                id = TASKS_DUE_FIELD,
                label = "Due date",
                config = """{"key":"due","label":"Due date","values":"$DATE_VALUES_PROVIDER"}""",
            ),
            PluginContribution(
                slot = Slots.EDITOR_DECORATIONS,
                id = TASKS_DECORATIONS_CONTRIBUTION,
                label = "Due and done dates",
                // The declarative form of what `tasksDecorations` does in
                // Kotlin, carried for the future the ADR is honest about:
                // nothing reads it in v1, and it is here so the two
                // descriptions can be checked against each other when a
                // wasm host has to read one (§ 7).
                config = """{"line":"task","tokens":[""" +
                    """{"key":"due","pattern":"\\sdue:\\d{4}-\\d{2}-\\d{2}\\b","role":"marker"},""" +
                    """{"key":"done","pattern":"\\sdone:\\d{4}-\\d{2}-\\d{2}\\b","role":"muted"}],""" +
                    """"onToggle":"done"}""",
            ),
            PluginContribution(
                slot = Slots.EDITOR_SUGGEST,
                id = TASKS_SUGGEST_CONTRIBUTION,
                label = "Inline field menu (/)",
                config = """{"trigger":"/","fields":"${Slots.TASKS_FIELDS}"}""",
            ),
            PluginContribution(
                slot = Slots.SEARCH_TYPES,
                id = "task",
                label = "type:task in search and filters",
                config = """{"type":"task","operators":["due","done"]}""",
            ),
            // The dead slot becomes real (§ 5, T3). The `config` names the
            // panel, so the shell routes to it by key rather than by
            // knowing which plugin asked — the shape a declarative host
            // will read unchanged.
            PluginContribution(
                slot = Slots.NAVIGATION,
                id = TASKS_NAVIGATION_CONTRIBUTION,
                label = "Tasks",
                config = """{"$PANEL_KEY":"$TASKS_RAIL_PANEL"}""",
            ),
        ),
    )

/// A task marker directly after a bullet, optionally indented. Mirrors
/// `MarkdownEditing.kt`'s `TASK_MARKER`, which is file-private, exactly
/// as `Suggest.kt` mirrors it — one definition of a task line is the
/// point of § 1, so if it is ever published all three become the import.
private val TASKS_TASK_LINE = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]])""")

/// § 1's token pattern: `key:` plus an ISO date, whitespace-separated and
/// position-independent, never parsed from the end backwards. The leading
/// `\s` is part of the match and is trimmed off the styled range, so only
/// the token itself takes the colour.
private fun tokenPattern(key: String) = Regex("""\s$key:\d{4}-\d{2}-\d{2}\b""")

private val DUE_TOKEN = tokenPattern("due")
private val DONE_TOKEN = tokenPattern("done")

private const val TASKS_MARKER_GROUP = 4
private const val TASKS_MARKER_LENGTH = 3

/// Is this line a task the plugin owns? The global filter (§ 1) is one
/// setting read by every recogniser here, so the vault never disagrees
/// with itself; empty — the shipped default — means every GFM checklist
/// item is a task.
private fun isTaskLine(line: String, filter: String): Boolean =
    TASKS_TASK_LINE.containsMatchIn(line) && (filter.isEmpty() || line.contains(filter))

/// The three characters of the marker, or null on a line that is not a
/// task list item.
private fun taskMarkerRange(line: String): IntRange? =
    TASKS_TASK_LINE.find(line)?.groups?.get(TASKS_MARKER_GROUP)
        ?.let { it.range.first until it.range.first + TASKS_MARKER_LENGTH }

/// A ticked marker — `[x]` or GFM's `[X]`. False on prose.
private fun isCheckedTask(line: String): Boolean =
    taskMarkerRange(line)?.let { line[it.first + 1] != ' ' } == true

/// § 1's write-on-action, first half: a **ticked** task line gains
/// ` done:<today>` unless it already carries one. Every other line comes
/// back byte-identical — prose, an unticked task, one already stamped — so
/// folding this over a line is safe wherever the chain reaches it.
///
/// The token is appended, never inserted: position-independence is the
/// *reader's* rule, and appending is the one placement that cannot disturb
/// bytes the user typed.
fun stampDone(line: String, today: LocalDate): String {
    if (!isCheckedTask(line)) return line
    if (DONE_TOKEN.containsMatchIn(line)) return line
    return "$line done:$today"
}

/// The other half: unticking takes the done date away again. Exactly one
/// token goes, with the whitespace the pattern owns, from wherever on the
/// line it sits — never parsed from the end backwards (§ 1).
fun stripDone(line: String): String {
    if (taskMarkerRange(line) == null || isCheckedTask(line)) return line
    val token = DONE_TOKEN.find(line) ?: return line
    return line.removeRange(token.range)
}

/// One `key:ISO` token recogniser. **StyleOnly**: the bytes render as
/// themselves and only the role's colour is added, so `due:2026-09-05`
/// reads identically in Notepad, and no activation — T1 styles, it does
/// not toggle (§ 6).
private fun tokenDecoration(
    id: String,
    pattern: Regex,
    role: DecorationRole,
    filter: String,
): EditorDecoration = EditorDecoration(
    id = id,
    role = role,
    // The seam's default, named rather than inherited because § 1 turns
    // on it: a documented example must never become somebody's agenda.
    firesInsideCodeFences = false,
    recognise = { line ->
        if (!isTaskLine(line, filter)) {
            emptyList()
        } else {
            pattern.findAll(line)
                .map { DecorationMatch(it.range.first + 1 until it.range.last + 1, DecorationRender.StyleOnly) }
                .toList()
        }
    },
)

/// The done-date stamper (§ 1's toggle rule), and the plugin's only
/// activation. It recognises the **task marker itself** rather than a
/// token, because a line about to be ticked carries no `done:` yet for a
/// token recogniser to find: the registration that must run at the toggle
/// is the one that recognises the toggle.
///
/// It renders nothing. The range it claims is the checkbox's, the shell's
/// registrations lead, and a contested range goes to the first one (§ 5),
/// so this claim is always discarded — [DecorationRender.StyleOnly] with
/// [DecorationRole.NONE] is what that would cost if it ever won: the bytes
/// as themselves and no span. Activation is a separate question, asked of
/// every registration that recognises the *evolving* line, so losing the
/// render costs the stamper nothing and it reads the marker the checkbox
/// has just flipped.
private fun doneStamper(filter: String, today: LocalDate): EditorDecoration = EditorDecoration(
    id = "org.memory.tasks.done-stamp",
    role = DecorationRole.NONE,
    firesInsideCodeFences = false,
    recognise = { line ->
        val marker = if (isTaskLine(line, filter)) taskMarkerRange(line) else null
        if (marker == null) emptyList() else listOf(DecorationMatch(marker, DecorationRender.StyleOnly))
    },
    activate = { line -> if (isCheckedTask(line)) stampDone(line, today) else stripDone(line) },
)

/// The plugin's `editor.decorations` registrations (§ 1): a due date takes
/// the marker colour, a done date the muted one, and the stamper writes
/// the done date at the toggle. All three fire only on a task line and
/// never inside a fence. `filter` is the global setting — recognisers read
/// the one setting so the vault never disagrees with itself — and defaults
/// to § 1's shipped empty, meaning every GFM checklist item is a task.
///
/// `today` is resolved when the registry is built, not at the click, so a
/// session held open across midnight stamps the day it opened; re-resolving
/// would need a supplier the seam does not carry.
fun tasksDecorations(filter: String = "", today: LocalDate = LocalDate.now()): List<EditorDecoration> = listOf(
    tokenDecoration("org.memory.tasks.due", DUE_TOKEN, DecorationRole.MARKER, filter),
    tokenDecoration("org.memory.tasks.done", DONE_TOKEN, DecorationRole.MUTED, filter),
    doneStamper(filter, today),
)

/// The `/` menu's stage one, read from the registry rather than from a
/// list in the shell.
///
/// **The both-enabled gate** (§ 3) is the two conditions in this one
/// expression: `contributionsTo` drops a disabled *contributor*, and the
/// `pluginContributes` guard drops every contribution when the
/// *publisher* — the tasks plugin — is off or removed. Either side away
/// and the field leaves the menu, changing no bytes: `assignee:anna` is
/// then plain text, exactly as `due:2026-09-05` is when tasks is gone.
///
/// A contribution this host cannot serve — an unknown values provider —
/// is dropped rather than offered as a row that does nothing.
fun taskFields(plugins: List<PluginInfo>): List<SuggestField> {
    val publisherOn = plugins.any { it.pluginId == TASKS_PLUGIN && it.state == "enabled" }
    if (!publisherOn) return emptyList()
    return plugins.contributionsTo(Slots.TASKS_FIELDS).mapNotNull { contribution ->
        val config = contribution.config
        val key = jsonString(config, "key") ?: return@mapNotNull null
        val label = jsonString(config, "label") ?: return@mapNotNull null
        val values = jsonString(config, "values") ?: return@mapNotNull null
        suggestField(key, label, values)
    }
}

/// The global filter (§ 1) as the tasks plugin's settings hold it, and
/// the one place it is read: empty — the shipped default — means every
/// GFM checklist item is a task.
///
/// Deliberately **not** gated on the plugin's state. The features that
/// read it are gated instead, and the plugin's own settings screen must
/// show what is stored even while it is switched off.
fun List<PluginInfo>.tasksFilter(): String =
    firstOrNull { it.pluginId == TASKS_PLUGIN }?.let { jsonString(it.settings, FILTER_KEY) }.orEmpty()

/// The agenda panel's off switch (§ 6, T3): "a plugin setting turns the
/// panel off; while it is off the shell does not compose the tab".
///
/// **Absent means on.** The panel ships with the plugin, so a settings
/// document written before this key existed — or none at all — must read
/// as the shipped default rather than as a switch nobody has ever seen
/// silently holding a feature back.
///
/// Not gated on the plugin's state, for [tasksFilter]'s reason: the
/// plugin's own settings screen must show what is stored even while it is
/// switched off, and the rail is what gates.
fun List<PluginInfo>.tasksPanelEnabled(): Boolean =
    firstOrNull { it.pluginId == TASKS_PLUGIN }?.let { jsonBoolean(it.settings, PANEL_KEY) } ?: true

/// The settings document the tasks plugin stores, written here so the
/// writer, [tasksFilter] and [tasksPanelEnabled] cannot drift apart. Both
/// keys are written every time: a caller changing one holds the other,
/// so toggling the panel can never drop the filter someone set.
fun tasksSettingsDocument(filter: String, panel: Boolean = true): String =
    """{"$FILTER_KEY":"${escapeJsonString(filter)}","$PANEL_KEY":$panel}"""

private const val FILTER_KEY = "filter"
private const val PANEL_KEY = "panel"

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

/// The settings document the transcription plugin stores, written here so
/// the writer, [transcriptionLanguage] and [transcriptionModel] cannot
/// drift apart. Both keys go every time: whichever control a person
/// touched, the other's stored value travels with the write.
fun transcriptionSettingsDocument(language: String, model: String): String =
    """{"$LANGUAGE_KEY":"${escapeJsonString(language)}","$MODEL_KEY":"${escapeJsonString(model)}"}"""

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
        name = "Realtime transcription (Moonshine)",
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
val TRANSCRIPTION_PROVIDERS = listOf(TRANSCRIPTION_PLUGIN, MOONSHINE_PLUGIN)

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
    gilune.recentSources(limit)
        .filter { it.isRecording() }
        .filter { runCatching { gilune.objectText(it.objectId) }.getOrNull().isNullOrBlank() }

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

/// One string value out of a flat JSON document, escapes handled. Plugin
/// settings and contribution configs are opaque strings with no schema
/// (a debt ADR 0015 names), and the shell reads a handful of known keys
/// out of them; a targeted extraction avoids growing a JSON dependency
/// for that. `\uXXXX` is not decoded — no key the shell reads uses one.
private fun jsonString(document: String, key: String): String? =
    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        .find(document)?.groupValues?.get(1)?.let(::unescapeJsonString)

/// The same targeted extraction for a boolean. Absent — or anything that
/// is not `true`/`false` — is null, so every caller states its own default
/// rather than inheriting one from a parse failure.
private fun jsonBoolean(document: String, key: String): Boolean? =
    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false)\\b")
        .find(document)?.groupValues?.get(1)?.toBooleanStrictOrNull()

private fun unescapeJsonString(raw: String): String = buildString(raw.length) {
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char == '\\' && index + 1 < raw.length) {
            append(
                when (val escaped = raw[index + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    'b' -> '\b'
                    else -> escaped
                },
            )
            index += 2
        } else {
            append(char)
            index++
        }
    }
}

private fun escapeJsonString(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
