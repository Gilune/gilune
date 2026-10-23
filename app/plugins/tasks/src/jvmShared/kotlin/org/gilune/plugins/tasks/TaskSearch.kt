// The tasks plugin's contribution to the search language (ADR 0015 § 4):
// the `task` type — what a hit is — and the `due:`/`done:`/`is:`
// operators scoped to it. Nothing here widens the grammar; it registers
// into the one `Search.kt` already has, which is the whole point of § 4's
// "one grammar, one engine".
//
// Pure over strings and an injected `today`, so it is testable without a
// screen and a mobile reader could evaluate the same query (ADR 0006).
// Nothing reads a clock: `today` arrives from the evaluator, because a
// relative word resolves **at evaluation** and never becomes a stored
// date.

package org.gilune.plugins.tasks

import org.gilune.plugin.sdk.CandidateLine
import org.gilune.plugin.sdk.Compiled
import org.gilune.plugin.sdk.SearchOperator
import org.gilune.plugin.sdk.SearchType
import org.gilune.plugin.sdk.SearchedNote

import java.time.LocalDate
import java.util.Locale
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.ConstraintOp
import uniffi.gilune_ffi.TextTarget

/// The key this plugin registers. A hit is a task item.
const val TASK_SEARCH_TYPE = "task"

/// `type:task`, with `due:`, `done:` and `is:` scoped to it (§ 4). `filter` is
/// § 1's global setting, read by the caller from the plugin's own
/// settings so recogniser, menu and query never disagree about what a
/// task is; empty — the shipped default — means every GFM checklist item
/// is one.
///
/// The type is a registration and carries no day: its date operators are
/// built per query, against the day that query runs. `is:` needs no day,
/// so it is one value registered beside them.
///
/// `constraints` is how the type reaches the index (ADR 0020 § 2.4):
/// every recognised item carries a state fact. The kernel applies the global
/// case-sensitive text filter to the item's own content, excluding nested items.
/// [taskLines] remains a legacy scan oracle; indexed item search never invokes it.
fun taskSearchType(filter: String = ""): SearchType = SearchType(
    key = TASK_SEARCH_TYPE,
    operators = { today ->
        listOf(DateFieldOperator(DUE_FIELD, today), DateFieldOperator(DONE_FIELD, today), MARKER_OPERATOR, PRIORITY_OPERATOR)
    },
    extract = { note -> taskLines(note, filter) },
    constraints = buildList {
        add(taskFact(TASK_STATE_PREDICATE, ConstraintOp.EXISTS, ""))
    },
    indexed = true,
    caseSensitiveFilter = filter,
    indexNamespaces = listOf(TASKS_INDEX_NAMESPACE),
    itemScoped = true,
)

// ---------------------------------------------------------------------
// The rows behind the hits — the seam T4 consumes.
// ---------------------------------------------------------------------

/// One task item a query kept, with its indexed dates and first body line.
///
/// The surface T4's `filter` block renderer consumes: an
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
    /// The level the line states, or null for the ordinary one (ADR 0015
    /// amendment 2026-10-23). Last and defaulted so the callers that build
    /// a hit by hand keep compiling.
    val priority: TaskPriority? = null,
) {
    /** Exact first physical line used by the guarded checkbox edit. */
    val checkboxLine: String get() = text.substringBefore('\n')
}

/** Domain presentation over facts returned by the kernel; no task recognition
 * or date-token parsing is required on the result path. */
fun kernelTaskRows(hits: List<uniffi.gilune_ffi.SearchHit>): List<TaskHit> = hits.flatMap { hit ->
    hit.items.map { item ->
        fun date(predicate: String): LocalDate? = item.facts.firstOrNull { it.key == predicate }
            ?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val level = item.facts.firstOrNull { it.key == TASK_PRIORITY_PREDICATE }?.value
        TaskHit(hit.relPath, hit.name, item.firstLine.toInt(), item.text,
            date(TASK_DUE_PREDICATE), date(TASK_DONE_PREDICATE),
            TaskPriority.entries.firstOrNull { it.token == level })
    }
}

// ---------------------------------------------------------------------
// The extractor: which lines are tasks.
// ---------------------------------------------------------------------

/// A task marker directly after a bullet, optionally indented — the same
/// shape `MarkdownEditing.kt` renders as a checkbox glyph, duplicated
/// because that one is file-private, exactly as `Suggest.kt` and
/// `Plugins.kt` duplicate it. If it is ever published, all four become
/// the import: one definition of a task line is the point of § 1.
///
/// Group 4 is the character between the brackets, as `TasksPanel.kt`
/// captures it: the extractor only asks whether a line matches, but `is:`
/// asks which marker it carries, and reading it off the one recogniser
/// keeps the two answers from ever disagreeing.
private val TASK_SEARCH_TASK_LINE = Regex("""^([ \t]*)([-*+])([ \t]+)\[([ xX])]""")

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
        if (taskMarkerOf(line) == null) return@forEachIndexed
        if (filter.isNotEmpty() && !line.contains(filter)) return@forEachIndexed
        candidates.add(CandidateLine(index + 1, line))
    }
    return candidates
}

// ---------------------------------------------------------------------
// The operators.
// ---------------------------------------------------------------------

private const val MARKER_FIELD = "is"

/// `due:` and `done:`, the two § 1 stores and the only dates this type
/// scopes. Under the narrowed-note rule the note handed to [matches]
/// **is one line**, so reading its body reads the task line.
///
/// No needle hooks: the operator filters, it does not highlight — the
/// line is the hit, and emphasising the date it was selected by would say
/// nothing the row does not already show.
private class DateFieldOperator(override val name: String, private val today: LocalDate) : SearchOperator {
    private val token = tokenPattern(name)
    private val predicate = if (name == DUE_FIELD) TASK_DUE_PREDICATE else TASK_DONE_PREDICATE

    override fun matches(note: SearchedNote, argument: String): Boolean =
        dateMatches(dateIn(note.body, token), argument, today)

    /// The value forms, as constraints on the indexed date (ADR 0020
    /// § 2.4). `none` is the interesting one and it is the reason
    /// `NotExists` exists at all: the plugin's own operator asks whether
    /// a token is **absent**, which nothing else in the operator set can
    /// say, and its negation is the plainest possible `Exists`.
    ///
    /// Every other negation is left to the read. `-due:week` is "not
    /// between two dates", whose complement is a disjunction, and a
    /// conjunction cannot hold one — so it narrows nothing and the note
    /// decides, which is exactly the fallback this seam is built around.
    override fun compile(argument: String, negated: Boolean): Compiled {
        val value = argument.trim().lowercase(Locale.ROOT)
        if (value == NONE_VALUE) {
            val op = if (negated) ConstraintOp.EXISTS else ConstraintOp.NOT_EXISTS
            return Compiled.Exact(listOf(taskFact(predicate, op, "")))
        }
        if (negated) return Compiled.Scan
        return when {
            value == TODAY_VALUE -> on(ConstraintOp.EQ, today)
            value == OVERDUE_VALUE -> on(ConstraintOp.LT, today)
            value == WEEK_VALUE -> Compiled.Exact(
                listOf(
                    taskFact(predicate, ConstraintOp.GE, today.toString()),
                    taskFact(predicate, ConstraintOp.LE, today.plusDays(WEEK_DAYS).toString()),
                ),
            )
            value.startsWith(BEFORE_VALUE) ->
                boundary(value, BEFORE_VALUE, today)?.let { on(ConstraintOp.LT, it) } ?: Compiled.Never
            value.startsWith(AFTER_VALUE) ->
                boundary(value, AFTER_VALUE, today)?.let { on(ConstraintOp.GT, it) } ?: Compiled.Never
            else -> isoDate(value)?.let { on(ConstraintOp.EQ, it) } ?: Compiled.Never
        }
    }

    private fun on(op: ConstraintOp, date: LocalDate) =
        Compiled.Exact(listOf(taskFact(predicate, op, date.toString())))
}

/// `is:` — the checkbox itself, the one thing about a task that is not a
/// date: `is:open` is the `[ ]` marker, `is:done` is `[x]` or `[X]`.
///
/// The marker is read off the same recogniser that decided the line was a
/// task at all, under the narrowed-note rule, so the answer is the line's
/// own bytes and not a second opinion about them.
///
/// Negation is the DSL's, not ours: `-is:done` is "not done", and on task
/// lines it is exactly `is:open`, because a line the extractor yielded
/// carries one marker or the other. No day is involved, so unlike the
/// date operators this one needs no `today` and is a single value.
private val MARKER_OPERATOR = object : SearchOperator {
    override val name = MARKER_FIELD

    override fun matches(note: SearchedNote, argument: String): Boolean =
        markerMatches(markerIn(note.body), argument)

    /// A task line carries exactly one state fact, so the negation is
    /// expressible here where it is not for a date: "no fact saying
    /// open" and "the fact says done" are the same set of lines.
    override fun compile(argument: String, negated: Boolean): Compiled {
        val value = when (argument.trim().lowercase(Locale.ROOT)) {
            OPEN_VALUE -> TASK_STATE_OPEN
            DONE_VALUE -> TASK_STATE_DONE
            // A value that is neither keeps nothing, and its negation
            // keeps everything the rest of the query found.
            else -> return if (negated) Compiled.Scan else Compiled.Never
        }
        val op = if (negated) ConstraintOp.NE else ConstraintOp.EQ
        return Compiled.Exact(listOf(taskFact(TASK_STATE_PREDICATE, op, value)))
    }
}

/// `priority:` — the one word field (ADR 0015 amendment 2026-10-23):
/// `high` and `low` are the two levels a line may state; `none` is a line
/// stating neither, and `normal` is the same set, because the ordinary
/// level written out means what leaving it out means. Anything else
/// matches nothing, as a typo inside `due:` does.
///
/// Negating a level falls to the read: "not high" is "low or ordinary",
/// a disjunction the conjunction cannot hold — the same fallback the date
/// operators take for `-due:week`.
private val PRIORITY_OPERATOR = object : SearchOperator {
    override val name = PRIORITY_FIELD

    override fun matches(note: SearchedNote, argument: String): Boolean {
        val value = argument.trim().lowercase(Locale.ROOT)
        val stated = taskPriorityIn(note.body)
        if (value == NONE_VALUE || value == NORMAL_VALUE) return stated == null
        val level = TaskPriority.entries.firstOrNull { it.token == value } ?: return false
        return stated == level
    }

    override fun compile(argument: String, negated: Boolean): Compiled {
        val value = argument.trim().lowercase(Locale.ROOT)
        if (value == NONE_VALUE || value == NORMAL_VALUE) {
            val op = if (negated) ConstraintOp.EXISTS else ConstraintOp.NOT_EXISTS
            return Compiled.Exact(listOf(taskFact(TASK_PRIORITY_PREDICATE, op, "")))
        }
        val level = TaskPriority.entries.firstOrNull { it.token == value }
            ?: return if (negated) Compiled.Scan else Compiled.Never
        if (negated) return Compiled.Scan
        return Compiled.Exact(listOf(taskFact(TASK_PRIORITY_PREDICATE, ConstraintOp.EQ, level.token)))
    }
}

/// § 4's value forms, and the whole of them:
///
/// - `YYYY-MM-DD` — that day exactly
/// - `today` — the evaluator's day, so the query never rots
/// - `overdue` — **strictly** before today; today is not overdue
/// - `week` — today through today + 6, both ends included
/// - `none` — the line carries no such token. `-due:none` is therefore
///   "has a due date", which is why no `has`/`no` keywords exist
/// - `before:<when>` / `after:<when>` — both **exclusive**, so
///   `before:2026-09-05` does not match the 5th and `after:2026-09-05`
///   does not either
///
/// `<when>` is anything [resolveDatePhrase] resolves — an ISO date, or a
/// relative word: `before:today`, `after:tomorrow`, `after:monday`, where
/// a weekday is its **next** occurrence and never today (owner request,
/// 2026-08-31, closing the gap to "due before today"). Resolution happens
/// at evaluation against the day the query runs, like every other
/// relative word here, so no query stores a date that rots. The resolver's
/// multi-word phrases ("next week") cannot reach here — the DSL splits a
/// query on whitespace — and the truncated `before:next` is simply a
/// value it does not resolve.
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
        value.startsWith(BEFORE_VALUE) -> boundary(value, BEFORE_VALUE, today)?.let { date < it } == true
        value.startsWith(AFTER_VALUE) -> boundary(value, AFTER_VALUE, today)?.let { date > it } == true
        else -> isoDate(value)?.let { date == it } == true
    }
}

/// The day a `before:`/`after:` value names, or null if it names none —
/// [resolveDatePhrase] is the one resolver, so the operator and the `/`
/// menu accept the same words and validate an ISO date the same way.
private fun boundary(value: String, prefix: String, today: LocalDate): LocalDate? =
    resolveDatePhrase(value.removePrefix(prefix), today)

/// The marker values, and the whole of them: `open` is `[ ]`, `done` is
/// `[x]` or `[X]`. Case-insensitive on both sides — the value as typed
/// and the marker as written — and anything else matches nothing, by the
/// same rule that makes `due:soonish` empty rather than prose.
private fun markerMatches(marker: Char?, argument: String): Boolean {
    if (marker == null) return false
    return when (argument.trim().lowercase(Locale.ROOT)) {
        OPEN_VALUE -> marker == ' '
        DONE_VALUE -> marker == 'x'
        else -> false
    }
}

/// The marker a line carries, lower-cased, or null when the line is not a
/// task at all — which under `type:task` it always is.
private fun markerIn(line: String): Char? = taskMarkerOf(line)

/// The one reader of a task line's checkbox, shared by the operator that
/// asks about it and the indexer that records it. Two answers to "is this
/// a task, and is it ticked" would be one fact with two homes, and the
/// index would drift from the language the moment either was touched.
internal fun taskMarkerOf(line: String): Char? =
    TASK_SEARCH_TASK_LINE.find(line)?.groupValues?.get(4)?.firstOrNull()?.lowercaseChar()

/// The ISO date one field's token carries on a line, or null. The same
/// reader the operators use, so a fact and a query see the same token —
/// including "the first one wins" on a line somebody wrote twice.
internal fun taskDateIn(line: String, field: String): String? =
    dateIn(line, tokenPattern(field))?.toString()

internal const val DUE_FIELD = "due"
internal const val DONE_FIELD = "done"
internal const val PRIORITY_FIELD = "priority"

/// The two levels a task may be given beyond the ordinary (ADR 0015
/// amendment 2026-10-23). No token is the ordinary level, so the word has
/// two values and the sort rank three.
enum class TaskPriority(val token: String, val label: String, val rank: Int) {
    HIGH("high", "High", 1),
    LOW("low", "Low", 3),
}

/// What a line with no priority word, or with `priority:normal`, sorts as.
const val TASK_NORMAL_RANK = 2

/// § 1's shape for the one word field: `priority:` plus one of three
/// words, whitespace-separated and position-independent, like a date
/// token. Mirrors the decoration's pattern in `TasksPlugin.kt`.
internal val PRIORITY_TOKEN = Regex("""\spriority:(high|normal|low)\b""")

/// The level a line states, or null for the ordinary one — which
/// `priority:normal` also is: the word is accepted so a person may write
/// the default out, and it means exactly what leaving it out means.
fun taskPriorityIn(line: String): TaskPriority? =
    PRIORITY_TOKEN.find(line)?.groupValues?.get(1)?.let { word -> TaskPriority.entries.firstOrNull { it.token == word } }

/// The one key every task list sorts by: the rank, then the due date —
/// `~` sorts after any date, so an undated task follows the dated ones of
/// its rank — with the kernel's path-and-line order breaking ties, which
/// is the person's own order within a note. One text key rather than a
/// two-key kernel sort, so the ordering needs no kernel change.
fun taskOrderKey(priority: TaskPriority?, dueIso: String?): String =
    "${priority?.rank ?: TASK_NORMAL_RANK}|${dueIso ?: "~"}"

private const val NONE_VALUE = "none"
private const val NORMAL_VALUE = "normal"
private const val TODAY_VALUE = "today"
private const val OVERDUE_VALUE = "overdue"
private const val WEEK_VALUE = "week"
private const val BEFORE_VALUE = "before:"
private const val AFTER_VALUE = "after:"
private const val OPEN_VALUE = "open"
private const val DONE_VALUE = "done"

/// `week` is today plus the six days after it — seven days counting
/// today, which is what "the next seven days" means to a person reading
/// their own agenda.
private const val WEEK_DAYS = 6L

/// § 1's token: `key:` plus an ISO date, whitespace-separated and
/// position-independent, never parsed from the end backwards. Mirrors
/// `Plugins.kt`'s `tokenPattern`, so the styler and the query recognise
/// the same bytes.
private fun tokenPattern(key: String) = Regex("""\s$key:\d{4}-\d{2}-\d{2}\b""")

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

private fun taskFact(predicate: String, op: ConstraintOp, value: String) =
    Constraint.FactAt(predicate, op, value, uniffi.gilune_ffi.FactLocality.EXACT)
