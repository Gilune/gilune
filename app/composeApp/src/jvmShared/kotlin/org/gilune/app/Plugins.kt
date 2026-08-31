// What the shell contributes and how it reads contributions back. The
// shell renders slots; what fills them comes from the registry, so
// removing a plugin removes its controls (ADR 0011). Nothing here decides
// behaviour — the kernel owns whether a plugin may run.

package org.gilune.app

import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo
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
