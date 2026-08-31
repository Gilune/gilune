// The agenda panel (ADR 0015 § 6, T3): the tasks plugin's contributed
// rail destination, and the four bounded views an agenda actually is —
// overdue, today, the next seven days, undated. It arrives through the
// `navigation` slot and leaves with the plugin, or with the plugin's own
// panel switch; the rail knows it only by the key its contribution names.
//
// It is a reader and nothing else. Every row is a line that already sits
// in a note, the query is the one search engine over the vault on disk
// (§ 4: one grammar, one engine), and **rows navigate, they do not edit**
// — a click opens the note, because toggling from here would write to a
// file the user is not looking at.
//
// The partition is pure over rows and a day, so the boundaries below are
// testable without a screen.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/// § 4's cap, and it is not optional: matching is cheap and *rendering*
/// results is what freezes an editor, so it sits on the rows drawn, per
/// group, with the rest counted.
const val AGENDA_ROW_CAP = 50

/// The horizon of the third view. Seven days **after** today, because
/// today is a view of its own — see [AgendaGroup].
const val AGENDA_HORIZON_DAYS = 7L

/// How many task lines one note may contribute before the engine stops
/// quoting them. The panel wants every task in a note, not the five a
/// search panel quotes, and this is well past § 4's rendering cap.
private const val AGENDA_SCAN_LINES = 200

/// Every open task in the vault, as `type:task` with no other term — the
/// query a person would type. The four views are then one partition in
/// Kotlin rather than four passes over the vault, which is also what
/// keeps them from overlapping.
private const val AGENDA_QUERY = "type:$TASK_SEARCH_TYPE"

/// The four bounded views (§ 6, T3), in the order the panel shows them.
/// They **partition** the tasks they cover: no task appears twice, which
/// is why the panel divides one result set by its due date rather than
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
/// A task due beyond the horizon is in **no** view and the panel does not
/// show it: four bounded views are what T3 is, and an unbounded "later"
/// is the list an agenda exists to not be. Search answers that question —
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

/// § 1's tokens, as the panel reads them off a line it is only drawing.
private val AGENDA_DATE_TOKEN = Regex("""\s(?:due|done):\d{4}-\d{2}-\d{2}\b""")

/// Ticked — `[x]`, or GFM's `[X]`. False on anything that is not a task
/// line at all, so a stray row from a note-level query is treated as open
/// rather than silently dropped.
private fun isCheckedTaskLine(line: String): Boolean =
    AGENDA_TASK_LINE.find(line)?.groupValues?.get(4)?.equals("x", ignoreCase = true) == true

/// What a row says: the task's own words. The marker and the date tokens
/// come off, because the row draws both of those itself — presentation
/// only, over a copy, and this panel never writes a byte anywhere.
fun taskLabel(text: String): String {
    val body = AGENDA_TASK_LINE.find(text)?.let { text.substring(it.range.last + 1) } ?: text
    return AGENDA_DATE_TOKEN.replace(body, "").trim().ifBlank { text.trim() }
}

/// The panel. `onOpen` is the rail's own — a row navigates to the note it
/// came from, and the line number is carried for the day the editor takes
/// one (§ 4 asks for a click to open the note *at* that line; opening the
/// note is what the shell can do today).
///
/// One pass over the vault per recompute, keyed exactly as the search
/// panel is: the vault tick, the vault, the live types and the global
/// filter. Never per keystroke (§ 4). Honestly, that means the same
/// staleness the search panel has — a task typed into an open buffer
/// shows once autosave's write has been followed by a tick, not on the
/// keystroke.
@Composable
fun TasksPanel(state: GiluneState, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    val types = searchTypesFor(state.plugins)
    val filter = state.tasksFilter()
    val sections = remember(state.vaultTick.value, state.vaultDir, types.size, filter) {
        // `today` is read here rather than inside the partition so the
        // day every view means is the day the pass ran.
        val today = LocalDate.now()
        agenda(
            taskRows(
                searchVault(
                    state.vaultDir,
                    AGENDA_QUERY,
                    types = types,
                    today = today,
                    maxLines = AGENDA_SCAN_LINES,
                ),
            ),
            today,
        )
    }
    // Undated is folded away to begin with: it is the view least likely
    // to be about today, and the one most likely to be long.
    val collapsed = remember { mutableStateListOf(AgendaGroup.UNDATED) }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("tasks", fontSize = 11.sp, color = colours.onSurfaceVariant, maxLines = 1)
        }

        if (sections.isEmpty()) {
            Text(
                "nothing open",
                fontSize = 11.sp,
                color = colours.outline,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(bottom = 6.dp)) {
            for (section in sections) {
                val open = section.group !in collapsed
                item(key = "group-${section.group.name}") {
                    GroupHeader(section, open) {
                        if (!collapsed.remove(section.group)) collapsed.add(section.group)
                    }
                }
                if (!open) continue
                items(section.rows, key = { "${section.group.name}:${it.relPath}:${it.line}" }) { hit ->
                    TaskRow(hit, section.group, onOpen)
                }
                if (section.more > 0) {
                    item(key = "more-${section.group.name}") {
                        Text(
                            "+${section.more} more",
                            fontSize = 11.sp,
                            color = colours.outline,
                            modifier = Modifier.padding(start = 18.dp, top = 1.dp, bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(section: AgendaSection, open: Boolean, onToggle: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            chevronFor(open),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = colours.onSurfaceVariant,
        )
        Text(
            "${section.group.label} (${section.count})",
            fontSize = 11.sp,
            color = colours.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun chevronFor(open: Boolean): ImageVector =
    if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight

@Composable
private fun TaskRow(hit: TaskHit, group: AgendaGroup, onOpen: (String) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(colours.surface)
            .clickable { onOpen(hit.relPath) }
            .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                taskLabel(hit.text),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colours.onSurface,
            )
            Text(
                hit.relPath,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colours.onSurfaceVariant,
            )
        }
        val due = hit.due
        if (due != null) {
            Text(
                due.toString(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = dueColour(group),
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/// Overdue takes the error colour and today the accent; the week ahead is
/// ordinary secondary text, because it is not yet asking for anything.
@Composable
private fun dueColour(group: AgendaGroup): Color = when (group) {
    AgendaGroup.OVERDUE -> MaterialTheme.colorScheme.error
    AgendaGroup.TODAY -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
