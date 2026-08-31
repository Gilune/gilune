// The tasks plugin's contribution to the search language (ADR 0015 § 4):
// the `task` type — what a hit is — and the `due:`/`done:` operators
// scoped to it. Nothing here widens the grammar; it registers into the
// one `Search.kt` already has, which is the whole point of § 4's "one
// grammar, one engine".
//
// Pure over strings and an injected `today`, so it is testable without a
// screen and a mobile reader could evaluate the same query (ADR 0006).
// Nothing reads a clock: `today` arrives from the evaluator, because a
// relative word resolves **at evaluation** and never becomes a stored
// date.

package org.gilune.app

import java.time.LocalDate
import java.util.Locale
import uniffi.gilune_ffi.PluginInfo

/// The key this plugin registers. A hit is a task line, not a note.
const val TASK_SEARCH_TYPE = "task"

/// `type:task`, with `due:` and `done:` scoped to it (§ 4). `filter` is
/// § 1's global setting, read by the caller from the plugin's own
/// settings so recogniser, menu and query never disagree about what a
/// task is; empty — the shipped default — means every GFM checklist item
/// is one.
///
/// The type is a registration and carries no day: its operators are built
/// per query, against the day that query runs.
fun taskSearchType(filter: String = ""): SearchType = SearchType(
    key = TASK_SEARCH_TYPE,
    operators = { today -> listOf(DateFieldOperator(DUE_FIELD, today), DateFieldOperator(DONE_FIELD, today)) },
    extract = { note -> taskLines(note, filter) },
)

/// The types the language offers right now, from the live plugin list:
/// `note` always, `task` only while the tasks plugin is switched on. Off
/// or removed, `type:task` is a type nobody registered — no hits — and
/// `due:` is prose again, changing no bytes (§ 4, Removal).
///
/// The gate is the declared `search.types` contribution, enabled-gated
/// like every other slot (ADR 0015 § 5).
fun searchTypesFor(plugins: List<PluginInfo>): List<SearchType> =
    if (plugins.pluginContributes(TASKS_PLUGIN, Slots.SEARCH_TYPES)) {
        listOf(NOTE_SEARCH_TYPE, taskSearchType(plugins.tasksFilter()))
    } else {
        listOf(NOTE_SEARCH_TYPE)
    }

// ---------------------------------------------------------------------
// The rows behind the hits — the seam T4 consumes.
// ---------------------------------------------------------------------

/// One task line a query kept, with its dates already read off it.
///
/// The **per-line surface T4's `filter` block renderer consumes**: an
/// agenda row needs the note it came from, the line to navigate to, the
/// text to draw and the dates to sort by, and every one of them is here,
/// so the renderer never re-derives what the query already established.
data class TaskHit(
    val relPath: String,
    val name: String,
    val line: Int,
    val text: String,
    val due: LocalDate?,
    val done: LocalDate?,
)

/// A `type:task` result set as rows. **T4 calls this**, having run
///
/// ```
/// searchVault(dir, query, types = searchTypesFor(plugins), today = …, maxLines = <its cap>)
/// ```
///
/// — `maxLines` because the engine's default quotes five lines of a note
/// and a block wants every task in it, up to § 4's rendering cap.
///
/// The order is the one order an agenda needs (§ 4), so there are no sort
/// instructions to get wrong: due ascending, undated last, then path,
/// then line number.
///
/// Hits from a note-level query are lines of prose, not tasks; handing
/// them here yields rows with no dates rather than an error, because the
/// caller that chose the type is the one that knows.
fun taskRows(hits: List<SearchHit>): List<TaskHit> = hits
    .flatMap { hit ->
        hit.lines.map { line ->
            TaskHit(
                relPath = hit.relPath,
                name = hit.name,
                line = line.number,
                text = line.text,
                due = dateIn(line.text, DUE_TOKEN),
                done = dateIn(line.text, DONE_TOKEN),
            )
        }
    }
    .sortedWith(TASK_ORDER)

private val TASK_ORDER = compareBy<TaskHit>(
    { it.due == null },
    { it.due ?: LocalDate.MAX },
    { it.relPath },
    { it.line },
)

// ---------------------------------------------------------------------
// The extractor: which lines are tasks.
// ---------------------------------------------------------------------

/// A task marker directly after a bullet, optionally indented — the same
/// shape `MarkdownEditing.kt` renders as a checkbox glyph, duplicated
/// because that one is file-private, exactly as `Suggest.kt` and
/// `Plugins.kt` duplicate it. If it is ever published, all four become
/// the import: one definition of a task line is the point of § 1.
private val TASK_SEARCH_TASK_LINE = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]])""")

/// § 1's three rules, and all three: a task marker, the global filter,
/// and **never inside a fenced code block** — the documented `filter`
/// examples of § 4 must never become somebody's agenda.
///
/// Fence tracking mirrors `styleMarkdown` and the decoration pass:
/// delimiters count as inside the block, so a ``` line is never a
/// candidate either.
private fun taskLines(note: SearchedNote, filter: String): List<CandidateLine> {
    val candidates = mutableListOf<CandidateLine>()
    var inFence = false
    note.lines.forEachIndexed { index, line ->
        val delimiter = line.trim().startsWith("```")
        val fenced = inFence || delimiter
        if (delimiter) inFence = !inFence
        if (fenced) return@forEachIndexed
        if (!TASK_SEARCH_TASK_LINE.containsMatchIn(line)) return@forEachIndexed
        if (filter.isNotEmpty() && !line.contains(filter)) return@forEachIndexed
        candidates.add(CandidateLine(index + 1, line))
    }
    return candidates
}

// ---------------------------------------------------------------------
// The operators.
// ---------------------------------------------------------------------

private const val DUE_FIELD = "due"
private const val DONE_FIELD = "done"

/// `due:` and `done:`, the two § 1 stores and the only two § 6 scopes to
/// this type. Under the narrowed-note rule the note handed to [matches]
/// **is one line**, so reading its body reads the task line.
///
/// No needle hooks: the operator filters, it does not highlight — the
/// line is the hit, and emphasising the date it was selected by would say
/// nothing the row does not already show.
private class DateFieldOperator(override val name: String, private val today: LocalDate) : SearchOperator {
    private val token = tokenPattern(name)

    override fun matches(note: SearchedNote, argument: String): Boolean =
        dateMatches(dateIn(note.body, token), argument, today)
}

/// § 4's value forms, and the whole of them:
///
/// - `YYYY-MM-DD` — that day exactly
/// - `today` — the evaluator's day, so the query never rots
/// - `overdue` — **strictly** before today; today is not overdue
/// - `week` — today through today + 6, both ends included
/// - `none` — the line carries no such token. `-due:none` is therefore
///   "has a due date", which is why no `has`/`no` keywords exist
/// - `before:<date>` / `after:<date>` — both **exclusive**, so
///   `before:2026-09-05` does not match the 5th and `after:2026-09-05`
///   does not either
///
/// `<date>` is an ISO date and only that: `today` is already a value in
/// its own right and `overdue` already says `before:today`.
///
/// Anything else matches **nothing**. A typo inside a known operator is
/// an empty result, not a text search: the DSL's degradation rule is
/// about an unknown *keyword*, and `due:soonish` has asked a date
/// question that cannot be answered by pretending it was prose.
private fun dateMatches(date: LocalDate?, argument: String, today: LocalDate): Boolean {
    val value = argument.trim().lowercase(Locale.ROOT)
    return when {
        value == NONE_VALUE -> date == null
        date == null -> false
        value == TODAY_VALUE -> date == today
        value == OVERDUE_VALUE -> date < today
        value == WEEK_VALUE -> !date.isBefore(today) && !date.isAfter(today.plusDays(WEEK_DAYS))
        value.startsWith(BEFORE_VALUE) -> isoDate(value.removePrefix(BEFORE_VALUE))?.let { date < it } == true
        value.startsWith(AFTER_VALUE) -> isoDate(value.removePrefix(AFTER_VALUE))?.let { date > it } == true
        else -> isoDate(value)?.let { date == it } == true
    }
}

private const val NONE_VALUE = "none"
private const val TODAY_VALUE = "today"
private const val OVERDUE_VALUE = "overdue"
private const val WEEK_VALUE = "week"
private const val BEFORE_VALUE = "before:"
private const val AFTER_VALUE = "after:"

/// `week` is today plus the six days after it — seven days counting
/// today, which is what "the next seven days" means to a person reading
/// their own agenda.
private const val WEEK_DAYS = 6L

/// § 1's token: `key:` plus an ISO date, whitespace-separated and
/// position-independent, never parsed from the end backwards. Mirrors
/// `Plugins.kt`'s `tokenPattern`, so the styler and the query recognise
/// the same bytes.
private fun tokenPattern(key: String) = Regex("""\s$key:\d{4}-\d{2}-\d{2}\b""")

private val DUE_TOKEN = tokenPattern(DUE_FIELD)
private val DONE_TOKEN = tokenPattern(DONE_FIELD)

/// The date a line carries for one field, or null. The first token wins:
/// a line with two due dates is the user's to sort out, and we read it
/// left to right as anyone would.
private fun dateIn(line: String, token: Regex): LocalDate? =
    token.find(line)?.value?.substringAfter(':')?.let(::isoDate)

/// The stored form, and the only form a query's `<date>` may take. A
/// well-shaped date that does not exist (`2026-02-30`) is not one.
private fun isoDate(text: String): LocalDate? =
    if (!ISO_DATE.matches(text)) null else runCatching { LocalDate.parse(text) }.getOrNull()

private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
