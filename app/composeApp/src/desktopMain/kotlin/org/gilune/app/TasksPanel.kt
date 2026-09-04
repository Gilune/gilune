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
// The partition itself is pure over rows and a day, and lives in
// `Agenda.kt` in the shared set — the phone's agenda destination draws the
// same four views (ADR 0017 § 8), so the boundaries are decided once. What
// is here is what a pointer shell makes of them.
//
// T4's `filter` block renders here too (§ 4: "what renders is the type's
// row"), sharing [TaskHitRow] with the panel rather than forking it — one
// row shape per type, so an agenda row and a block row cannot drift. The
// panel groups and the block does not; everything else is the same row.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import uniffi.gilune_ffi.PluginInfo

/// The panel. `onOpen` is the rail's own — a row navigates to the note it
/// came from, and the line number is carried for the day the editor takes
/// one (§ 4 asks for a click to open the note *at* that line; opening the
/// note is what the shell can do today).
///
/// One query per recompute, keyed exactly as the search panel is: the
/// change cursor, the vault, the live types and the global filter. Never
/// per keystroke (§ 4). Honestly, that means the same staleness the
/// search panel has — a task typed into an open buffer shows once
/// autosave's write has reached the feed, not on the keystroke.
@Composable
fun TasksPanel(state: GiluneState, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    val types = searchTypesFor(state.plugins)
    val filter = state.tasksFilter()
    // `today` is read inside the pass and carried out of it, so the day
    // every view means — and the day every row's date is coloured against
    // — is the day the pass ran, not the day a recomposition happened.
    val open = state.gilune.value
    val computed = remember(state.vaultCursor.value, state.vaultDir, types.size, filter, open) {
        val today = LocalDate.now()
        today to agenda(taskRows(agendaHits(open, state.vaultDir, types, today)), today)
    }
    val today = computed.first
    val sections = computed.second
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
                    TaskHitRow(hit, today, onOpen)
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

/// **One task row, drawn the same way wherever a task line is listed** —
/// the agenda panel (§ 6) and a `filter` block (§ 4). The task's own words,
/// the note it came from beneath them, its due date right-aligned and
/// coloured by what the date is asking for.
///
/// `onOpen` is nullable because a row is only navigable where the surface
/// drawing it has somewhere to send the click; null draws the same row,
/// inert. **Rows navigate, they never toggle** (§ 4) either way.
///
/// The colour comes from the date against `today` rather than from the
/// panel's group, so the block gets the same reading with no groups at
/// all — and the panel's four views agree with it by construction, since
/// they partition on that same comparison.
@Composable
fun TaskHitRow(hit: TaskHit, today: LocalDate, onOpen: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(colours.surface)
            .let { if (onOpen == null) it else it.clickable { onOpen(hit.relPath) } }
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
                color = dueColour(due, today),
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/// Overdue takes the error colour and today the accent; anything further
/// out is ordinary secondary text, because it is not yet asking for
/// anything.
@Composable
private fun dueColour(due: LocalDate, today: LocalDate): Color = when {
    due < today -> MaterialTheme.colorScheme.error
    due == today -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ---------------------------------------------------------------------
// The `filter` block (§ 4, T4) — the shell's own `editor.blocks`
// registration, rendering beside the panel it shares its rows with.
// ---------------------------------------------------------------------

/// The shell's registration of the `filter` fence: **v1's only producer**
/// of [BlockRenderers] (§ 5). It is the shell's and not the tasks
/// plugin's, which is why a block keeps rendering — as a hint row naming
/// the type it wanted — when every removable plugin is gone.
fun filterBlockRegistration(
    state: GiluneState,
    plugins: List<PluginInfo>,
    onOpen: ((String) -> Unit)? = null,
): BlockRegistration = BlockRegistration(FILTER_FENCE) { block ->
    FilterBlockView(state, plugins, block.text, onOpen)
}

/// The id the shell's **Edit-mode** registration answers to.
const val FILTER_BLOCK_DECORATION = "gilune.filter"

/// The shell's Edit-mode registration of the same fence: the block-scoped
/// twin of [filterBlockRegistration] (owner, 2026-09-01 — a block "behaves
/// like a checkbox", rendered until the caret is placed within it).
///
/// It claims a fence **only where it already has a result** for that
/// block's text, which is what keeps the reservation honest: a block
/// nobody has evaluated yet reserves nothing and shows its raw fence, and
/// the lines it does reserve are the rows it is about to draw.
fun filterBlockDecoration(results: Map<String, FilterResult>): EditorBlockDecoration =
    EditorBlockDecoration(FILTER_BLOCK_DECORATION) { info, body ->
        if (!info.equals(FILTER_FENCE, ignoreCase = true)) null else results[body]?.let(::filterBlockLines)
    }

/// A collapsed block's rows, **one editor line each** — the Edit-mode face
/// of [FilterBlockView], sharing its result and its reading of it but not
/// its geometry: here the block sits on the editor's line grid, so a row
/// is one line high and [filterBlockLines] counts them.
///
/// Every row **wraps its content** rather than filling the width. That is
/// not styling: the space beside a row is where a click falls through to
/// the field underneath, landing the caret inside the block and revealing
/// the fence — which is how a covered block is edited. Rows themselves
/// still navigate and never edit (§ 4).
@Composable
fun FilterBlockLines(
    result: FilterResult,
    today: LocalDate,
    onOpen: ((String) -> Unit)?,
    rowHeight: Dp,
    maxRowWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        when (result) {
            is FilterResult.Hint -> FilterTextLine(result.text, rowHeight, italic = true)
            is FilterResult.Tasks -> {
                if (result.rows.isEmpty()) FilterTextLine(FILTER_NO_MATCHES, rowHeight, italic = true)
                for (row in result.rows) TaskHitLine(row, today, onOpen, rowHeight, maxRowWidth)
                if (result.more > 0) FilterTextLine("+${result.more} more", rowHeight, italic = false)
            }
            is FilterResult.Notes -> {
                if (result.rows.isEmpty()) FilterTextLine(FILTER_NO_MATCHES, rowHeight, italic = true)
                for (row in result.rows) NoteHitLine(row, onOpen, rowHeight, maxRowWidth)
                if (result.more > 0) FilterTextLine("+${result.more} more", rowHeight, italic = false)
            }
        }
    }
}

@Composable
private fun TaskHitLine(
    hit: TaskHit,
    today: LocalDate,
    onOpen: ((String) -> Unit)?,
    rowHeight: Dp,
    maxWidth: Dp,
) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(rowHeight)
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(4.dp))
            .background(colours.surface)
            .let { if (onOpen == null) it else it.clickable { onOpen(hit.relPath) }.pointerHoverIcon(PointerIcon.Hand) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            taskLabel(hit.text),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colours.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        val due = hit.due
        if (due != null) {
            Text(
                due.toString(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = dueColour(due, today),
            )
        }
    }
}

@Composable
private fun NoteHitLine(row: NoteRow, onOpen: ((String) -> Unit)?, rowHeight: Dp, maxWidth: Dp) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(rowHeight)
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(4.dp))
            .background(colours.surface)
            .let { if (onOpen == null) it else it.clickable { onOpen(row.relPath) }.pointerHoverIcon(PointerIcon.Hand) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            row.name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colours.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            row.relPath,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colours.onSurfaceVariant,
        )
    }
}

/// A line the block says something in rather than lists something in: a
/// hint, an answered question with no matches, or the footer. Never
/// clickable, so it is click-through like the space beside a row.
@Composable
private fun FilterTextLine(text: String, rowHeight: Dp, italic: Boolean) {
    Row(
        Modifier.height(rowHeight).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/// A `filter` block as a live list. Group-less — a block asked one
/// question and gets one answer — capped at [FILTER_ROW_CAP] with the rest
/// counted, and degenerate blocks get their single hint row.
///
/// **Recomputed on the tick, never per keystroke** (§ 4), keyed exactly as
/// the agenda panel is: the vault, its tick, this block's own text, and
/// the live plugin state that the type registry and the global filter
/// stand for. In Edit mode with the caret inside the block, nothing here
/// is composed at all, so no evaluation runs.
@Composable
fun FilterBlockView(
    state: GiluneState,
    plugins: List<PluginInfo>,
    block: String,
    onOpen: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colours = MaterialTheme.colorScheme
    val types = searchTypesFor(plugins)
    val filter = plugins.tasksFilter()
    val open = state.gilune.value
    val computed = remember(state.vaultDir, state.vaultCursor.value, block, types.size, filter, open) {
        val today = LocalDate.now()
        today to when (open) {
            null -> FilterResult.Hint(FILTER_NO_MATCHES)
            else -> evaluateFilterBlock(open, state.vaultDir, block, types, today)
        }
    }
    val today = computed.first
    val result = computed.second

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colours.surfaceVariant)
            .padding(vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        when (result) {
            is FilterResult.Hint -> FilterNoteRow(result.text)
            is FilterResult.Tasks -> {
                if (result.rows.isEmpty()) FilterNoteRow(FILTER_NO_MATCHES)
                for (row in result.rows) TaskHitRow(row, today, onOpen)
                FilterMore(result.more)
            }
            is FilterResult.Notes -> {
                if (result.rows.isEmpty()) FilterNoteRow(FILTER_NO_MATCHES)
                for (row in result.rows) NoteHitRow(row, onOpen)
                FilterMore(result.more)
            }
        }
    }
}

/// A note-level hit: its name, and the path that says which one it is.
@Composable
private fun NoteHitRow(row: NoteRow, onOpen: ((String) -> Unit)?) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(colours.surface)
            .let { if (onOpen == null) it else it.clickable { onOpen(row.relPath) } }
            .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            row.name,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colours.onSurface,
        )
        Text(
            row.relPath,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colours.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/// The one row a block says something in rather than listing something:
/// a hint, or an answered question with no matches. Muted and italic, so
/// it never reads as a result.
@Composable
private fun FilterNoteRow(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
    )
}

/// § 4's footer. Nought is drawn as nothing rather than as a line saying
/// so.
@Composable
private fun FilterMore(count: Int) {
    if (count <= 0) return
    Text(
        "+$count more",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 1.dp),
    )
}
