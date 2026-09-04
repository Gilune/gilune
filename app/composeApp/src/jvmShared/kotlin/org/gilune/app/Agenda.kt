// The agenda's arithmetic (ADR 0015 § 6, T3): the four bounded views a
// tasks agenda is — overdue, today, the next seven days, undated — as a
// partition over rows and a day.
//
// Pure: no screen, no clock, no Compose and no state. It lived beside the
// desktop panel that first drew it (`TasksPanel.kt`) and moved here whole
// when the phone's agenda destination arrived (ADR 0017 § 8, MB3), so the
// two shells share the boundaries rather than each deciding what "overdue"
// means. Nothing about it changed in the move — same package, same names,
// same behaviour — which is why the desktop's tests did not have to.
//
// What is *not* here is what a row looks like or what a tap does: the
// desktop panel navigates and never toggles, the phone's rows carry a
// checkbox (ADR 0017 § 8), and that difference is presentation each shell
// owns.

package org.gilune.app

import java.time.LocalDate

/// § 4's cap, and it is not optional: matching is cheap and *rendering*
/// results is what freezes an editor, so it sits on the rows drawn, per
/// group, with the rest counted.
const val AGENDA_ROW_CAP = 50

/// The horizon of the third view. Seven days **after** today, because
/// today is a view of its own — see [AgendaGroup].
const val AGENDA_HORIZON_DAYS = 7L

/// How many task lines one note may contribute before the engine stops
/// quoting them. An agenda wants every task in a note, not the five a
/// search panel quotes, and this is well past § 4's rendering cap.
const val AGENDA_SCAN_LINES = 200

/// Every open task in the vault, as `type:task` with no other term — the
/// query a person would type. The four views are then one partition in
/// Kotlin rather than four passes over the vault, which is also what
/// keeps them from overlapping.
const val AGENDA_QUERY = "type:$TASK_SEARCH_TYPE"

/// The four bounded views (§ 6, T3), in the order a panel shows them.
/// They **partition** the tasks they cover: no task appears twice, which
/// is why the views divide one result set by its due date rather than
/// running the `due:` operator's own values — `week` there means today
/// through today + 6 and so overlaps `today`, correctly, for a query
/// somebody typed.
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

/// The partition, pure over the rows and the day.
///
/// - **checked tasks are excluded** — an agenda lists what is open, and a
///   done task is a record, not a claim on the day. Read from the marker
///   the line carries, so it costs no second query;
/// - **overdue** — a due date strictly before today;
/// - **today** — due today;
/// - **next seven days** — tomorrow through today + [AGENDA_HORIZON_DAYS],
///   both ends included;
/// - **undated** — no due date at all.
///
/// A task due beyond the horizon is in **no** view and no panel shows it:
/// four bounded views are what T3 is, and an unbounded "later" is the list
/// an agenda exists to not be. Search answers that question —
/// `type:task due:after:…` — which is where it belongs.
///
/// Order inside a view is [taskRows]' own, which is the one order an
/// agenda needs (§ 4), so nothing here sorts. An empty view is dropped
/// rather than drawn as a header with a nought beside it.
fun agenda(rows: List<TaskHit>, today: LocalDate, cap: Int = AGENDA_ROW_CAP): List<AgendaSection> {
    val open = rows.filterNot { isCheckedTaskLine(it.text) }
    val horizon = today.plusDays(AGENDA_HORIZON_DAYS)
    return AgendaGroup.entries.mapNotNull { group ->
        val members = open.filter { group.holds(it.due, today, horizon) }
        if (members.isEmpty()) {
            null
        } else {
            AgendaSection(group, members.take(cap), (members.size - cap).coerceAtLeast(0))
        }
    }
}

/// Why an agenda has nothing to draw. Two different facts, and telling a
/// person the wrong one is worse than saying nothing: somebody with no
/// tasks at all needs to know what a task **is**, and somebody whose
/// tasks are all done or all far off needs to know the views are bounded
/// and where the rest are.
enum class AgendaEmpty {
    /// The vault holds no task lines at all.
    NO_TASKS,

    /// Tasks exist; none of them is open inside the four views — every
    /// one is ticked, or due beyond the horizon (see [agenda]).
    NONE_OPEN,
}

/// Which of the two an empty agenda is, or `null` when there is something
/// to draw. Pure over the same inputs the screen already has, so the
/// screen chooses a sentence rather than deciding what is true.
///
/// Asked of the **rows**, not of the sections: a vault whose every task
/// is ticked produces no sections at all, and rows are the only evidence
/// that the person has ever written one.
fun agendaEmpty(rows: List<TaskHit>, sections: List<AgendaSection>): AgendaEmpty? = when {
    sections.isNotEmpty() -> null
    rows.isEmpty() -> AgendaEmpty.NO_TASKS
    else -> AgendaEmpty.NONE_OPEN
}

private fun AgendaGroup.holds(due: LocalDate?, today: LocalDate, horizon: LocalDate): Boolean = when (this) {
    AgendaGroup.OVERDUE -> due != null && due < today
    AgendaGroup.TODAY -> due == today
    AgendaGroup.NEXT_SEVEN -> due != null && due > today && !due.isAfter(horizon)
    AgendaGroup.UNDATED -> due == null
}

/// A task marker directly after a bullet, optionally indented — the same
/// shape `MarkdownEditing.kt` renders as a glyph, duplicated for the
/// reason `Plugins.kt` and `TaskSearch.kt` duplicate it: that one is
/// file-private. If it is ever published, all five become the import.
private val AGENDA_TASK_LINE = Regex("""^([ \t]*)([-*+])([ \t]+)\[([ xX])]""")

/// § 1's tokens, as a row reads them off a line it is only drawing.
private val AGENDA_DATE_TOKEN = Regex("""\s(?:due|done):\d{4}-\d{2}-\d{2}\b""")

/// Ticked — `[x]`, or GFM's `[X]`. False on anything that is not a task
/// line at all, so a stray row from a note-level query is treated as open
/// rather than silently dropped.
fun isCheckedTaskLine(line: String): Boolean =
    AGENDA_TASK_LINE.find(line)?.groupValues?.get(4)?.equals("x", ignoreCase = true) == true

/// What a row says: the task's own words. The marker and the date tokens
/// come off, because a row draws both of those itself — presentation
/// only, over a copy, and reading a line never writes one.
fun taskLabel(text: String): String {
    val body = AGENDA_TASK_LINE.find(text)?.let { text.substring(it.range.last + 1) } ?: text
    return AGENDA_DATE_TOKEN.replace(body, "").trim().ifBlank { text.trim() }
}
