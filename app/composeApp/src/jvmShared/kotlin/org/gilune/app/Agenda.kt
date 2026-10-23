// Task plugin agenda requests and presentation. Matching and sorting run in the kernel.

package org.gilune.app

import org.gilune.plugin.sdk.SearchType
import org.gilune.plugins.tasks.TASK_ORDER_PREDICATE
import org.gilune.plugins.tasks.TASK_SEARCH_TYPE
import org.gilune.plugins.tasks.TaskHit
import org.gilune.plugins.tasks.kernelTaskRows

import java.time.LocalDate

/// § 4's cap, and it is not optional: matching is cheap and *rendering*
/// results is what freezes an editor, so it sits on the rows drawn, per
/// group, with the rest counted.
const val AGENDA_ROW_CAP = 50

/// The horizon of the third view. Seven days **after** today, because
/// today is a view of its own — see [AgendaGroup].
const val AGENDA_HORIZON_DAYS = 7L

/// The plugin vocabulary for a task query.
const val AGENDA_QUERY = "type:$TASK_SEARCH_TYPE"

internal data class AgendaResult(val sections: List<AgendaSection>, val empty: AgendaEmpty?)

/** The domain plugin defines the four groups; the kernel filters, orders,
 * counts, and limits each group before any rows cross the boundary. */
internal fun loadAgenda(gilune: uniffi.gilune_ffi.Gilune?, types: List<SearchType>, today: LocalDate): AgendaResult {
    if (gilune == null) return AgendaResult(emptyList(), AgendaEmpty.NO_TASKS)
    val queries = listOf(
        AgendaGroup.OVERDUE to "due:overdue",
        AgendaGroup.TODAY to "due:today",
        AgendaGroup.NEXT_SEVEN to "due:after:$today due:before:${today.plusDays(AGENDA_HORIZON_DAYS + 1)}",
        AgendaGroup.UNDATED to "due:none",
    )
    val sections = queries.mapNotNull { (group, terms) ->
        val query = compileSearch("type:task is:open $terms", types = types, today = today,
            maxLines = 1, pageSize = AGENDA_ROW_CAP.toUInt(), groupByNote = false,
            sortPredicate = TASK_ORDER_PREDICATE) ?: return@mapNotNull null
        val page = query.page(gilune).requireComplete()
        if (page.totalHits == 0uL) null else {
            val rows = kernelTaskRows(page.hits)
            AgendaSection(group, rows, (page.totalHits - rows.size.toULong()).coerceAtMost(Int.MAX_VALUE.toULong()).toInt())
        }
    }
    if (sections.isNotEmpty()) return AgendaResult(sections, null)
    val total = compileSearch(AGENDA_QUERY, types = types, today = today,
        maxLines = 0, pageSize = 1u, groupByNote = false)?.page(gilune)?.requireComplete()?.totalHits ?: 0uL
    return AgendaResult(emptyList(), if (total == 0uL) AgendaEmpty.NO_TASKS else AgendaEmpty.NONE_OPEN)
}

/// Disjoint agenda groups in display order.
enum class AgendaGroup(val label: String) {
    OVERDUE("overdue"),
    TODAY("today"),
    NEXT_SEVEN("next seven days"),
    UNDATED("undated"),
}

/// One populated view: the rows it draws and how many it did not.
/// `count` is the whole membership, so a header never under-reports what
/// the cap held back.
data class AgendaSection(val group: AgendaGroup, val rows: List<TaskHit>, val more: Int) {
    val count: Int get() = rows.size + more
}

/// Why no agenda group contains a task.
enum class AgendaEmpty {
    /// The vault holds no task lines at all.
    NO_TASKS,

    /// Tasks exist; none of them is open inside the four views — every
    /// one is ticked, or due beyond the horizon.
    NONE_OPEN,
}

/// A task marker directly after a bullet, optionally indented — the same
/// shape `MarkdownEditing.kt` renders as a glyph, duplicated for the
/// reason `Plugins.kt` and `TaskSearch.kt` duplicate it: that one is
/// file-private. If it is ever published, all five become the import.
private val AGENDA_TASK_LINE = Regex("""^([ \t]*)([-*+])([ \t]+)\[([ xX])]""")

/// § 1's tokens, as a row reads them off a line it is only drawing.
private val AGENDA_DATE_TOKEN = Regex("""\s(?:due|done):\d{4}-\d{2}-\d{2}\b|\spriority:(?:high|normal|low)\b""")

/// Ticked — `[x]`, or GFM's `[X]`. False on anything that is not a task
/// line at all, so a stray row from a note-level query is treated as open
/// rather than silently dropped.
fun isCheckedTaskLine(line: String): Boolean =
    AGENDA_TASK_LINE.find(line)?.groupValues?.get(4)?.equals("x", ignoreCase = true) == true

/// What a row says: the task's own words. The marker and the date tokens
/// come off, because a row draws both of those itself — presentation
/// only, over a copy, and reading a line never writes one.
fun taskLabel(text: String): String {
    // The task's own line: what is indented under it is its detail, shown
    // folded beneath the row (owner, 2026-10-21), never run into its label.
    val line = text.substringBefore('\n')
    val body = AGENDA_TASK_LINE.find(line)?.let { line.substring(it.range.last + 1) } ?: line
    return AGENDA_DATE_TOKEN.replace(body, "").trim().ifBlank { line.trim() }
}
