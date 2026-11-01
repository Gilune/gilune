// The agenda panel (ADR 0015 § 6, T3): the tasks plugin's contributed
// rail destination, and the four bounded views an agenda actually is —
// overdue, today, the next seven days, undated. It arrives through the
// `navigation` slot and leaves with the plugin, or with the plugin's own
// panel switch; the rail knows it only by the key its contribution names.
//
// The panel's rows navigate to their source notes. Filter blocks also offer
// a separate checkbox, guarded against stale results and unsaved source edits.
//
// The partition itself is pure over rows and a day, and lives in
// `Agenda.kt` in the shared set — the phone's agenda destination draws the
// same four views (ADR 0017 § 8), so the boundaries are decided once. What
// is here is what a pointer shell makes of them.
//
// T4's `filter` block renders here too (§ 4: "what renders is the type's
// row"), sharing [TaskHitRow] with the panel rather than forking it — one
// row shape per type, so an agenda row and a block row cannot drift. The
// panel groups and the block does not; the block opts into checkbox actions.

package org.gilune.app

import org.gilune.plugins.tasks.TaskHit
import org.gilune.plugins.tasks.tasksFilter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import org.gilune.app.icons.CalendarToday
import org.gilune.app.icons.KeyboardDoubleArrowRight
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import org.gilune.plugin.sdk.TokenMenu
import org.gilune.plugin.sdk.TokenMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    val today = rememberQueryDay()
    val computed = rememberQueryWork(state.vaultCursor.value, state.searchRefresh.value, open, state.plugins.toList(), today) {
        val agenda = loadAgenda(open, types, today)
        agenda to taskDetails(state.vaultDir, agenda.sections.flatMap { it.rows })
    }.value
    val sections = computed.value?.first?.sections.orEmpty()
    val details = computed.value?.second.orEmpty()
    // Rows opened to show their notes and subtasks.
    var flipped by remember { mutableStateOf(setOf<String>()) }

    // Undated is folded away to begin with: it is the view least likely
    // to be about today, and the one most likely to be long.
    val collapsed = remember { mutableStateListOf(AgendaGroup.UNDATED) }

    if (computed.loading || computed.error != null) {
        Column(modifier) { QueryNotice(computed.loading, computed.error, state::retrySearchIndex) }
        return
    }
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
                // Within a group, a subtask under a listed parent shows there only.
                items(withoutListedSubtasks(section.rows, details), key = { "${section.group.name}:${it.relPath}:${it.line}" }) { hit ->
                    val key = hit.detailKey()
                    TaskHitRow(hit, today, onOpen, reveal = state::revealNext,
                        detail = details[key], open = key in flipped && key in details,
                        onFlip = { flipped = if (key in flipped) flipped - key else flipped + key })
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
/// inert. An optional checkbox has its own action; the row text navigates.
///
/// The colour comes from the date against `today` rather than from the
/// panel's group, so the block gets the same reading with no groups at
/// all — and the panel's four views agree with it by construction, since
/// they partition on that same comparison.
@Composable
fun TaskHitRow(
    hit: TaskHit,
    today: LocalDate,
    onOpen: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    onToggle: ((TaskHit) -> Unit)? = null,
    reveal: ((NoteLine) -> Unit)? = null,
    detail: TaskDetail? = null,
    open: Boolean = false,
    onFlip: (() -> Unit)? = null,
    rowMenu: TaskRowMenu? = null,
) {
    val colours = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth()) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .hoverable(hover)
            .clip(RoundedCornerShape(5.dp))
            .background(colours.surface)
            .let { if (onOpen == null) it else it.clickable { reveal?.invoke(hit.noteLine()); onOpen(hit.relPath) } }
            .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TaskPriorityMark(hit.priority, Modifier.padding(top = 1.dp))
        if (onToggle != null) TaskResultCheckbox(hit, onToggle)
        Column(Modifier.weight(1f)) {
            NoteResultText(
                taskLabel(hit.text),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colours.onSurface,
            )
            NoteResultText(
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
            NoteResultText(
                due.toString(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = dueColour(due, today),
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (rowMenu != null) TaskRowMenuArrow(hit, rowMenu, hover, 16.dp, Modifier.padding(top = 1.dp))
        if (detail != null) Box(Modifier.padding(top = 1.dp)) { TaskDetailMarker(detail, open, onFlip) }
    }
    if (open && detail != null) TaskDetailRows(hit, detail, onToggle, onOpen, reveal)
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

/// What a task row needs to offer its line's menu (ADR 0015 amendment
/// 2026-10-31): the menu a row's line has, and what choosing an item does.
/// Only `filter` block rows carry one — they already change their task —
/// while the agenda panel's rows still only navigate.
class TaskRowMenu(
    val menuFor: (TaskHit) -> TokenMenu?,
    val choose: (TaskHit, TokenMenuItem) -> Unit,
)

/// The menu for rows of this vault: the line's own, as the editor would
/// offer it, written through the same guard as the row's checkbox.
fun taskRowMenu(state: GiluneState, plugins: List<PluginInfo>): TaskRowMenu =
    TaskRowMenu({ taskResultMenu(plugins, it) }, { hit, item -> applyTaskResultMenu(state, hit, item) })

/// The arrow on a task row: shown while the row is hovered or its menu is
/// open, and kept in the row's layout either way so nothing shifts when it
/// appears. A dated task gets the postpone arrow; an undated one a
/// calendar, since its menu adds a date rather than moving one.
@Composable
private fun TaskRowMenuArrow(hit: TaskHit, rowMenu: TaskRowMenu, rowHover: MutableInteractionSource, size: Dp, modifier: Modifier = Modifier) {
    val hovered by rowHover.collectIsHoveredAsState()
    var request by remember(hit) { mutableStateOf<TokenMenu?>(null) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    Box(modifier.size(size).onGloballyPositioned { anchor = it.boundsInWindow() }) {
        if (hovered || request != null) {
            MenuArrowChip(
                size = size,
                onClick = { request = rowMenu.menuFor(hit) },
                icon = if (hit.due == null) Icons.Outlined.CalendarToday else Icons.Filled.KeyboardDoubleArrowRight,
                description = if (hit.due == null) "Add a due date" else "Move the due date",
                modifier = Modifier.testTag("task-row-menu"),
            )
        }
    }
    request?.let { menu ->
        TokenMenuPopup(menu, anchor, onChoose = { item -> request = null; rowMenu.choose(hit, item) }, onDismiss = { request = null })
    }
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
fun filterBlockDecoration(
    results: Map<String, FilterResult>,
    expanded: Set<String> = emptySet(),
    flipped: Map<String, Set<String>> = emptyMap(),
): EditorBlockDecoration =
    EditorBlockDecoration(FILTER_BLOCK_DECORATION) { info, body ->
        if (!info.equals(FILTER_FENCE, ignoreCase = true)) null
        else results[body]?.let { filterBlockLines(it, body in expanded, flipped[body].orEmpty()) }
    }

/// A collapsed block's rows, **one editor line each** — the Edit-mode face
/// of [FilterBlockView], sharing its result and its reading of it but not
/// its geometry: here the block sits on the editor's line grid, so a row
/// is one line high and [filterBlockLines] counts them.
///
/// Every row **wraps its content** rather than filling the width. That is
/// not styling: the space beside a row is where a click falls through to
/// the field underneath, landing the caret inside the block and revealing
/// the fence — which is how a covered block is edited. Row text navigates;
/// a checkbox updates the source task without moving the caret.
@Composable
fun FilterBlockLines(
    result: FilterResult,
    today: LocalDate,
    onOpen: ((String) -> Unit)?,
    rowHeight: Dp,
    maxRowWidth: Dp,
    modifier: Modifier = Modifier,
    onToggle: ((TaskHit) -> Unit)? = null,
    expanded: Boolean = false,
    onMore: (() -> Unit)? = null,
    onFewer: (() -> Unit)? = null,
    reveal: ((NoteLine) -> Unit)? = null,
    flipped: Set<String> = emptySet(),
    onFlip: ((String) -> Unit)? = null,
    rowMenu: TaskRowMenu? = null,
) {
    Column(modifier) {
        when (result) {
            is FilterResult.Hint -> FilterTextLine(result.text, rowHeight, italic = true)
            is FilterResult.Tasks -> {
                result.account?.let { FilterTextLine(it, rowHeight, italic = true) }
                if (result.rows.isEmpty()) FilterTextLine(FILTER_NO_MATCHES, rowHeight, italic = true)
                for (row in result.rows) {
                    val detail = result.details[row.detailKey()]
                    val open = result.detailOpen(row, flipped)
                    TaskHitLine(row, today, onOpen, rowHeight, maxRowWidth, onToggle, reveal,
                        detail, open, onFlip?.let { flip -> { flip(row.detailKey()) } }, rowMenu)
                    if (open && detail != null) TaskDetailGridLines(row, detail, rowHeight, maxRowWidth, onToggle, onOpen, reveal)
                }
                FilterFooter(result.more, expanded, onMore, onFewer, Modifier.height(rowHeight).padding(horizontal = 10.dp))
            }
            is FilterResult.Notes -> {
                result.account?.let { FilterTextLine(it, rowHeight, italic = true) }
                if (result.rows.isEmpty()) FilterTextLine(FILTER_NO_MATCHES, rowHeight, italic = true)
                for (row in result.rows) NoteHitLine(row, onOpen, rowHeight, maxRowWidth)
                FilterFooter(result.more, expanded, onMore, onFewer, Modifier.height(rowHeight).padding(horizontal = 10.dp))
            }
        }
    }
}

/// The line under a block's rows: `+N more`, which opens the next page of
/// rows (owner, 2026-10-21), and `Show fewer` once the block has been
/// opened. Each is clickable only where its words are — the space beside
/// them still lands the caret in the block underneath, as a row's does.
/// Nothing is drawn when there is nothing more and nothing to fold away.
@Composable
private fun FilterFooter(
    more: Int,
    expanded: Boolean,
    onMore: (() -> Unit)?,
    onFewer: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (more <= 0 && !expanded) return
    val colours = MaterialTheme.colorScheme
    fun Modifier.action(onClick: (() -> Unit)?, tag: String) =
        if (onClick == null) this else clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).testTag(tag)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (more > 0) {
            NoteResultText(
                "+$more more",
                fontSize = 11.sp,
                color = if (onMore != null) colours.primary else colours.outline,
                modifier = Modifier.action(onMore, "filter-more"),
            )
        }
        if (expanded && onFewer != null) {
            NoteResultText(
                "Show fewer",
                fontSize = 11.sp,
                color = colours.primary,
                modifier = Modifier.action(onFewer, "filter-fewer"),
            )
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
    onToggle: ((TaskHit) -> Unit)?,
    reveal: ((NoteLine) -> Unit)? = null,
    detail: TaskDetail? = null,
    open: Boolean = false,
    onFlip: (() -> Unit)? = null,
    rowMenu: TaskRowMenu? = null,
) {
    val colours = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    Row(
        Modifier
            .height(rowHeight)
            .widthIn(max = maxWidth)
            .hoverable(hover)
            .clip(RoundedCornerShape(4.dp))
            .background(colours.surface)
            .let { if (onOpen == null) it else it.clickable { reveal?.invoke(hit.noteLine()); onOpen(hit.relPath) }.pointerHoverIcon(PointerIcon.Hand) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onToggle != null) TaskResultCheckbox(hit, onToggle, Modifier.size(rowHeight))
        NoteResultText(
            taskLabel(hit.text),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colours.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        val due = hit.due
        if (due != null) {
            NoteResultText(
                due.toString(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = dueColour(due, today),
            )
        }
        if (rowMenu != null) TaskRowMenuArrow(hit, rowMenu, hover, (rowHeight * 0.7f).coerceAtMost(16.dp))
        if (detail != null) TaskDetailMarker(detail, open, onFlip)
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
        NoteResultText(
            row.name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colours.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        NoteResultText(
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
        NoteResultText(
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
    val today = rememberQueryDay()
    // How far this block has been opened: a page more per `+N more`.
    var cap by remember(block) { mutableStateOf(FILTER_ROW_CAP) }
    // The rows a person has opened or folded against the block's default.
    var flipped by remember(block) { mutableStateOf(setOf<String>()) }
    val computed = rememberQueryWork(state.vaultCursor.value, state.searchRefresh.value, block, plugins, open, today, cap) {
        if (open == null) FilterResult.Hint("Opening vault...")
        else evaluateFilterBlock(open, state.vaultDir, block, types, today, cap, searchOperatorsFor(plugins, today))
    }.value
    val result = computed.value ?: FilterResult.Hint(computed.error ?: "Searching...")
    val readMore: (() -> Unit)? =
        if (cap < FILTER_MAX_ROWS) ({ cap = (cap + FILTER_EXPAND_STEP).coerceAtMost(FILTER_MAX_ROWS) }) else null
    val rowMenu = remember(plugins) { taskRowMenu(state, plugins) }


    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colours.surfaceVariant)
            .padding(vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (computed.error != null) {
            androidx.compose.material3.TextButton(onClick = state::retrySearchIndex) { NoteResultText("Retry search") }
        }
        when (result) {
            is FilterResult.Hint -> FilterNoteRow(result.text)
            is FilterResult.Tasks -> {
                result.account?.let { FilterNoteRow(it) }
                if (result.rows.isEmpty()) FilterNoteRow(FILTER_NO_MATCHES)
                for (row in result.rows) {
                    val key = row.detailKey()
                    TaskHitRow(row, today, onOpen, onToggle = { toggleTaskResult(state, it) }, reveal = state::revealNext,
                        detail = result.details[key], open = result.detailOpen(row, flipped), onFlip = { flipped = if (key in flipped) flipped - key else flipped + key },
                        rowMenu = rowMenu)
                }
                FilterFooter(result.more, cap > FILTER_ROW_CAP, onMore = readMore, onFewer = { cap = FILTER_ROW_CAP }, Modifier.padding(start = 18.dp, top = 2.dp, bottom = 1.dp))
            }
            is FilterResult.Notes -> {
                result.account?.let { FilterNoteRow(it) }
                if (result.rows.isEmpty()) FilterNoteRow(FILTER_NO_MATCHES)
                for (row in result.rows) NoteHitRow(row, onOpen)
                FilterFooter(result.more, cap > FILTER_ROW_CAP, onMore = readMore, onFewer = { cap = FILTER_ROW_CAP }, Modifier.padding(start = 18.dp, top = 2.dp, bottom = 1.dp))
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
        NoteResultText(
            row.name,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colours.onSurface,
        )
        NoteResultText(
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
    NoteResultText(
        text,
        fontSize = 11.sp,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
    )
}

/// § 4's footer. Nought is drawn as nothing rather than as a line saying
/// so.
/// Where a task row's click lands: its own line, met by its text.
internal fun TaskHit.noteLine() = NoteLine(relPath, line, checkboxLine)

// ---------------------------------------------------------------------
// What sits under a task (owner, 2026-10-21): folded behind a marker on
// its row, one click to open. Notes are quiet text; subtasks tick like
// any task, through the same guarded write.
// ---------------------------------------------------------------------

/// The marker on a row with notes or subtasks under it: `▸ 2 notes · 1/3
/// done`, clickable to open or fold. Without `onFlip` it is a summary only.
@Composable
private fun TaskDetailMarker(detail: TaskDetail, open: Boolean, onFlip: (() -> Unit)?) {
    NoteResultText(
        (if (open) "▾ " else "▸ ") + detail.summary,
        fontSize = 11.sp,
        maxLines = 1,
        color = MaterialTheme.colorScheme.primary,
        modifier = if (onFlip == null) Modifier else Modifier
            .clickable(onClick = onFlip)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag("task-detail-toggle"),
    )
}

/// The task a subtask line is, for the checkbox and its guarded tick.
private fun TaskHit.subtask(line: SubtaskLine) = TaskHit(relPath, name, line.line, line.text, null, null)

/// A subtask line goes where a task row goes: its note, at its own line
/// (owner report, 2026-10-21 — a ticked subtask is listed only here, under
/// its parent, so a line with no way through left it unreachable). The
/// checkbox keeps its own action.
private fun Modifier.opensSubtask(
    row: TaskHit,
    line: SubtaskLine,
    onOpen: ((String) -> Unit)?,
    reveal: ((NoteLine) -> Unit)?,
): Modifier = if (onOpen == null) this else this
    .clickable { reveal?.invoke(row.subtask(line).noteLine()); onOpen(row.relPath) }
    .pointerHoverIcon(PointerIcon.Hand)

/// An opened row's detail in the editor's line grid: **one line each**,
/// as [filterBlockLines] counts them.
@Composable
private fun TaskDetailGridLines(
    row: TaskHit,
    detail: TaskDetail,
    rowHeight: Dp,
    maxWidth: Dp,
    onToggle: ((TaskHit) -> Unit)?,
    onOpen: ((String) -> Unit)?,
    reveal: ((NoteLine) -> Unit)?,
) {
    val colours = MaterialTheme.colorScheme
    for (note in detail.notes) {
        Row(Modifier.height(rowHeight).widthIn(max = maxWidth).padding(start = 40.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            NoteResultText(note, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colours.onSurfaceVariant)
        }
    }
    for (line in detail.subtasks) {
        Row(
            Modifier.height(rowHeight).widthIn(max = maxWidth).padding(start = 28.dp)
                .clip(RoundedCornerShape(4.dp))
                .opensSubtask(row, line, onOpen, reveal)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onToggle != null) TaskResultCheckbox(row.subtask(line), onToggle, Modifier.size(rowHeight))
            NoteResultText(taskLabel(line.text), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (line.done) colours.outline else colours.onSurface)
        }
    }
}

/// An opened row's detail where rows are free to size themselves — a
/// block in presentation mode, the agenda panel.
@Composable
private fun TaskDetailRows(
    row: TaskHit,
    detail: TaskDetail,
    onToggle: ((TaskHit) -> Unit)?,
    onOpen: ((String) -> Unit)?,
    reveal: ((NoteLine) -> Unit)?,
) {
    val colours = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (note in detail.notes) {
            NoteResultText(note, fontSize = 12.sp, color = colours.onSurfaceVariant)
        }
        for (line in detail.subtasks) {
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).opensSubtask(row, line, onOpen, reveal),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (onToggle != null) TaskResultCheckbox(row.subtask(line), onToggle)
                NoteResultText(taskLabel(line.text), fontSize = 12.sp,
                    color = if (line.done) colours.outline else colours.onSurface)
            }
        }
    }
}
