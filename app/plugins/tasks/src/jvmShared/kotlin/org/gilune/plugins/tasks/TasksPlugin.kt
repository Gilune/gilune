package org.gilune.plugins.tasks

import org.gilune.plugin.sdk.BundledPluginExtensions
import org.gilune.plugin.sdk.DecorationMatch
import org.gilune.plugin.sdk.DecorationRender
import org.gilune.plugin.sdk.DecorationRole
import org.gilune.plugin.sdk.EditorDecoration
import org.gilune.plugin.sdk.IndexerRegistration
import org.gilune.plugin.sdk.SearchTypeRegistration
import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.SuggestionProviderRegistration
import org.gilune.plugin.sdk.escapeJsonString
import org.gilune.plugin.sdk.jsonBoolean
import org.gilune.plugin.sdk.jsonString

import java.time.LocalDate
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo

// ---------------------------------------------------------------------
// The bundled Tasks implementation. Domain declarations, settings and
// behavior belong here; generic search/provider resolution lives in
// PluginExtensions. UI contributions beyond those seams remain ADR 0015's
// bundled implementation, not an installable component ABI.
// ---------------------------------------------------------------------

const val TASKS_PLUGIN = "org.memory.tasks"
const val TASK_FIELDS_SLOT = "tasks.fields"

/// The plugin's own field, and the **first registration into the slot it
/// publishes** (§ 3): the field list *is* that registry, one entry long,
/// so other date fields can arrive through the same provider contract.
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

/// Durable contribution metadata paired with [tasksExtensions]. The host
/// resolves executable search and values entries by owner, slot and id;
/// the other UI slots retain their existing bundled implementations.
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
                slot = Slots.SUGGEST_VALUES,
                id = DATE_VALUES_PROVIDER,
                label = "Date suggestions",
            ),
            PluginContribution(
                slot = TASK_FIELDS_SLOT,
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
                config = """{"trigger":"/","fields":"${TASK_FIELDS_SLOT}"}""",
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
                config = """{"$TASK_PANEL_KEY":"$TASKS_RAIL_PANEL"}""",
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

/// The global filter (§ 1) as the tasks plugin's settings hold it, and
/// the one place it is read: empty — the shipped default — means every
/// GFM checklist item is a task.
///
/// Deliberately **not** gated on the plugin's state. The features that
/// read it are gated instead, and the plugin's own settings screen must
/// show what is stored even while it is switched off.
fun List<PluginInfo>.tasksFilter(): String =
    firstOrNull { it.pluginId == TASKS_PLUGIN }?.let { jsonString(it.settings, TASK_FILTER_KEY) }.orEmpty()

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
    firstOrNull { it.pluginId == TASKS_PLUGIN }?.let { jsonBoolean(it.settings, TASK_PANEL_KEY) } ?: true

/// The settings document the tasks plugin stores, written here so the
/// writer, [tasksFilter] and [tasksPanelEnabled] cannot drift apart. Both
/// keys are written every time: a caller changing one holds the other,
/// so toggling the panel can never drop the filter someone set.
fun tasksSettingsDocument(filter: String, panel: Boolean = true): String =
    """{"$TASK_FILTER_KEY":"${escapeJsonString(filter)}","$TASK_PANEL_KEY":$panel}"""

private const val TASK_FILTER_KEY = "filter"
private const val TASK_PANEL_KEY = "panel"


/** All executable search/field/index registrations belong to the domain module.
 * The host supplies only this owner's settings and generic query execution. */
fun tasksExtensions(): BundledPluginExtensions = BundledPluginExtensions(
    declaration = tasksDeclaration(),
    searchTypes = listOf(SearchTypeRegistration("task", TASK_SEARCH_TYPE) { settings ->
        taskSearchType(jsonString(settings, TASK_FILTER_KEY).orEmpty())
    }),
    suggestionProviders = listOf(SuggestionProviderRegistration(DATE_VALUES_PROVIDER) { key, _ ->
        dateValues(key)
    }),
    fieldSlots = setOf(TASK_FIELDS_SLOT),
    scopedIndexer = org.gilune.plugin.sdk.ScopedIndexerRegistration(TASKS_INDEXER_VERSION, taskPredicateKinds(), ::TasksScopedIndexer),
)
