// The tasks plugin's reading of a note (ADR 0020 § 2.3): a pure function
// from a note's body to facts about lines, registered under the plugin's
// own id so removing the plugin takes it.
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

package org.gilune.app

import uniffi.gilune_ffi.Fact
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.NoteIndexer
import uniffi.gilune_ffi.PredicateKind
import uniffi.gilune_ffi.ValueKind

/// The namespace, which is the plugin id: removing the plugin drops the
/// namespace it named, and that is how the facts go with it (ADR 0011).
const val TASKS_INDEX_NAMESPACE = TASKS_PLUGIN

/// Change what these facts mean and change this: the namespace, and only
/// this namespace, is then derived again from the beginning of the feed.
const val TASKS_INDEXER_VERSION = 1L

/// `open` or `done`, read off the checkbox marker. Every task line has
/// exactly one, which is what makes `type:task` a question the index can
/// answer at all — the type's own constraint is the existence of this.
const val TASK_STATE_PREDICATE = "$TASKS_PLUGIN.state"
const val TASK_DUE_PREDICATE = "$TASKS_PLUGIN.due"
const val TASK_DONE_PREDICATE = "$TASKS_PLUGIN.done"

const val TASK_STATE_OPEN = "open"
const val TASK_STATE_DONE = "done"

/// The two dates are dates, so `due:before:friday` is a range and not a
/// string comparison that happens to work.
fun taskPredicateKinds(): List<PredicateKind> = listOf(
    PredicateKind(TASK_STATE_PREDICATE, ValueKind.TEXT),
    PredicateKind(TASK_DUE_PREDICATE, ValueKind.DATE),
    PredicateKind(TASK_DONE_PREDICATE, ValueKind.DATE),
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
        taskDateIn(line, DONE_FIELD)?.let { facts += Fact(at, TASK_DONE_PREDICATE, it) }
    }
    return facts
}

/// The plugin's indexer at the boundary. Registered per handle and per
/// process, exactly as an external processor is: which code exists here
/// is a fact about this process and never about the vault.
class TasksNoteIndexer : NoteIndexer {
    override fun index(relPath: String, text: String): List<Fact> = taskFacts(text)
}

/// Register every indexer this shell carries, on one handle.
///
/// **Registered whatever the plugin's switch says, and not at all once it
/// has been removed.** Disabling a plugin turns its *words* off — the
/// type and its operators leave the language, so `due:` is prose again
/// and a `type:task` block shows its hint — and that is the whole of what
/// a person asked for. Deriving its facts again on every toggle would be
/// a pass over the whole feed for a switch, and keeping them costs a few
/// rows nobody queries. Removal is the other thing entirely: the kernel
/// drops the namespace, and re-registering it on the next launch would
/// put back what somebody deliberately took away (ADR 0011).
///
/// Which is why the guard is the plugin's **presence** and not its state:
/// `plugins()` excludes tombstones, so a plugin the user removed is not
/// in the list at all and there is nothing here to register.
///
/// Idempotent, and safe on every handle a shell opens: registering the
/// same version twice changes nothing, and a version bump re-derives that
/// namespace alone.
fun registerShellIndexers(gilune: Gilune) {
    val tasks = gilune.plugins().firstOrNull { it.pluginId == TASKS_PLUGIN }
    if (tasks == null) return
    runCatching {
        gilune.registerIndexer(
            TASKS_INDEX_NAMESPACE,
            TASKS_INDEXER_VERSION,
            taskPredicateKinds(),
            TasksNoteIndexer(),
        )
    }.onFailure { GiluneLog.warn("the tasks indexer could not be registered", it) }
}
