// The shipped producer reads shared Markdown items and emits Tasks-owned facts.
// Legacy body-line helpers below remain for compatibility tests and scan oracles.
//
// It says three things about a task line and interprets none of them
// further: whether the box is ticked, and the two dates § 1 of ADR 0015
// stores. What "overdue" means, which lines the global filter admits and
// how a `filter` block draws them all stay in the plugin's own code —
// the index is storage and a join, not a second opinion.
//
// Pure over the text, with no vault access at all, which is what lets the
// same function be a sandboxed component later (ADR 0020 § 2.3) and what
// makes it testable without a kernel.

package org.gilune.plugins.tasks

import uniffi.gilune_ffi.Fact
import uniffi.gilune_ffi.NoteIndexer
import uniffi.gilune_ffi.PredicateKind
import uniffi.gilune_ffi.ValueKind
import uniffi.gilune_ffi.ScopedFact
import uniffi.gilune_ffi.ScopedIndexer
import uniffi.gilune_ffi.SourceLocation
import uniffi.gilune_ffi.SourceTextSnapshot
import org.gilune.plugin.sdk.SourceView

/// The namespace, which is the plugin id: removing the plugin drops the
/// namespace it named, and that is how the facts go with it (ADR 0011).
const val TASKS_INDEX_NAMESPACE = TASKS_PLUGIN

/// Change what these facts mean and change this: the namespace, and only
/// this namespace, is then derived again from the beginning of the feed.
/// Version 4 (2026-10-30) adds [TASK_COMPLETED_PREDICATE].
const val TASKS_INDEXER_VERSION = 4L

/// `open` or `done`, read off the checkbox marker. Every task line has
/// exactly one, which is what makes `type:task` a question the index can
/// answer at all — the type's own constraint is the existence of this.
const val TASK_STATE_PREDICATE = "$TASKS_PLUGIN.state"
const val TASK_DUE_PREDICATE = "$TASKS_PLUGIN.due"
const val TASK_DONE_PREDICATE = "$TASKS_PLUGIN.done"
/// When a ticked task was completed (owner, 2026-10-30): its written
/// `done:` date, or — when the line carries none, because it was ticked in
/// another app — the day the kernel's line history says the line reached
/// its current text (ADR 0025). Absent when neither is known: a tick made
/// before Gilune first adopted the note stays undated rather than taking
/// the adoption day. `done:` queries ask this; nothing is written to the
/// note.
const val TASK_COMPLETED_PREDICATE = "$TASKS_PLUGIN.completed"
/// The priority word a line states, `high` or `low`; absent for the
/// ordinary level (ADR 0015 amendment 2026-10-23).
const val TASK_PRIORITY_PREDICATE = "$TASKS_PLUGIN.priority"
/// Every task line's sort key, `<rank>|<due or ~>` ([taskOrderKey]): the
/// one derived fact here, and the one predicate every task list sorts
/// by, so priority then due date then place-in-note is a single sort.
const val TASK_ORDER_PREDICATE = "$TASKS_PLUGIN.order"

const val TASK_STATE_OPEN = "open"
const val TASK_STATE_DONE = "done"

/// The two dates are dates, so `due:before:friday` is a range and not a
/// string comparison that happens to work.
fun taskPredicateKinds(): List<PredicateKind> = listOf(
    PredicateKind(TASK_STATE_PREDICATE, ValueKind.TEXT),
    PredicateKind(TASK_DUE_PREDICATE, ValueKind.DATE),
    PredicateKind(TASK_DONE_PREDICATE, ValueKind.DATE),
    PredicateKind(TASK_COMPLETED_PREDICATE, ValueKind.DATE),
    PredicateKind(TASK_PRIORITY_PREDICATE, ValueKind.TEXT),
    PredicateKind(TASK_ORDER_PREDICATE, ValueKind.TEXT),
)

/// What the plugin says about one note.
///
/// **The global filter is deliberately not applied here.** It is a
/// setting somebody changes, and facts derived under it would have to be
/// thrown away and re-derived whenever they did — a full pass over the
/// vault for a text field. Instead every checkbox line is indexed and the
/// filter narrows at query time, where it is a text constraint on the
/// line and an exact test when the note is read (`taskLines`). A vault
/// whose filter is empty — the shipped default — is unaffected either
/// way.
///
/// Line numbers are 1-based within the body, which is what the kernel
/// promises an indexer and what `TaskHit.line` already means.
fun taskFacts(text: String): List<Fact> {
    val facts = mutableListOf<Fact>()
    var inFence = false
    text.lines().forEachIndexed { index, line ->
        // § 1's fence rule, and the same tracking `taskLines` does:
        // delimiters count as inside, so a documented example never
        // becomes somebody's agenda.
        val delimiter = line.trim().startsWith("```")
        val fenced = inFence || delimiter
        if (delimiter) inFence = !inFence
        if (fenced) return@forEachIndexed
        val marker = taskMarkerOf(line) ?: return@forEachIndexed
        val at = (index + 1).toUInt()
        facts += Fact(at, TASK_STATE_PREDICATE, if (marker == 'x') TASK_STATE_DONE else TASK_STATE_OPEN)
        taskDateIn(line, DUE_FIELD)?.let { facts += Fact(at, TASK_DUE_PREDICATE, it) }
        taskDateIn(line, DONE_FIELD)?.let {
            facts += Fact(at, TASK_DONE_PREDICATE, it)
            // This body-only path has no line history: a written date only.
            facts += Fact(at, TASK_COMPLETED_PREDICATE, it)
        }
        val priority = taskPriorityIn(line)
        priority?.let { facts += Fact(at, TASK_PRIORITY_PREDICATE, it.token) }
        facts += Fact(at, TASK_ORDER_PREDICATE, taskOrderKey(priority, taskDateIn(line, DUE_FIELD)))
    }
    return facts
}

/// The plugin's indexer at the boundary. Registered per handle and per
/// process, exactly as an external processor is: which code exists here
/// is a fact about this process and never about the vault.
class TasksNoteIndexer : NoteIndexer {
    override fun index(relPath: String, text: String): List<Fact> = taskFacts(text)
}

/** Shipped producer. Shared Markdown structure supplies item boundaries;
 * Tasks interprets only its checkbox and date syntax within that item. */
class TasksScopedIndexer : ScopedIndexer, uniffi.gilune_ffi.LocalScopedIndexer {
    override fun indexLocations(relPath: String, source: SourceTextSnapshot, locations: List<SourceLocation>): List<ScopedFact> {
        val selected = locations.toSet()
        return index(relPath, source.copy(items = source.items.filter { SourceLocation.MarkdownItem(it.key) in selected }))
    }
    override fun index(relPath: String, source: SourceTextSnapshot): List<ScopedFact> {
        val view = SourceView(source)
        return source.items.flatMap { item ->
            val own = item.ownRanges.joinToString("\n") { range ->
                source.text.substring(view.charOffset(range.start), view.charOffset(range.end)).trimEnd('\r', '\n')
            }
            val marker = taskMarkerOf(own.lineSequence().firstOrNull().orEmpty()) ?: return@flatMap emptyList()
            val location = SourceLocation.MarkdownItem(item.key)
            buildList {
                add(ScopedFact(location, TASK_STATE_PREDICATE, if (marker == 'x') TASK_STATE_DONE else TASK_STATE_OPEN))
                taskDateIn(own, DUE_FIELD)?.let { add(ScopedFact(location, TASK_DUE_PREDICATE, it)) }
                val written = taskDateIn(own, DONE_FIELD)
                written?.let { add(ScopedFact(location, TASK_DONE_PREDICATE, it)) }
                val completed = written ?: if (marker == 'x') inferredCompletion(source, item) else null
                completed?.let { add(ScopedFact(location, TASK_COMPLETED_PREDICATE, it)) }
                val priority = taskPriorityIn(own)
                priority?.let { add(ScopedFact(location, TASK_PRIORITY_PREDICATE, it.token)) }
                add(ScopedFact(location, TASK_ORDER_PREDICATE, taskOrderKey(priority, taskDateIn(own, DUE_FIELD))))
            }
        }
    }
}

/// The day the item's checkbox line reached its current text, from the
/// kernel's line history (ADR 0025) — when a tick made elsewhere happened,
/// for a line whose `[x]` carries no date. Null when the history does not
/// know: a snapshot built outside indexing, or a line already present when
/// Gilune adopted the note. In this device's own calendar, as every other
/// day the plugin resolves is.
internal fun inferredCompletion(
    source: SourceTextSnapshot,
    item: uniffi.gilune_ffi.SourceItem,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String? {
    val start = item.ownRanges.firstOrNull()?.start ?: item.range.start
    val line = source.lines.firstOrNull { start >= it.range.start && (start < it.range.end || it.range.start == it.range.end) }
    val since = line?.sinceMs ?: return null
    return java.time.Instant.ofEpochMilli(since).atZone(zone).toLocalDate().toString()
}
