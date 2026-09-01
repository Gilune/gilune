// The Agenda destination (ADR 0017 § 1, MB3): the tasks plugin's
// contribution to the `navigation` slot, drawn as a bottom-bar tab.
//
// It is not a shell destination. It is on the bar because the plugin is
// enabled and its own panel switch is on, and it leaves with either
// (ADR 0015 § 6, T3) — `navigationPanels` decides, in `MainActivity`, and
// this screen is only what that decision draws.
//
// The four bounded views and their boundaries are `Agenda.kt`'s, shared
// with the desktop panel, so overdue means the same thing on both. What is
// mobile is the reach: 16sp rows, a 48dp checkbox on every row, and
// headers big enough to fold with a thumb.
//
// **Rows toggle here, and on the desktop they do not.** The desktop panel
// declines deliberately — "toggling from here would write to a file the
// user is not looking at" (ADR 0015 § 4) — but ADR 0017 § 8 asks for
// toggling on the phone by name, and the phone's argument is the opposite
// one: there is no second pane to open the note in beside the list, so an
// agenda you cannot tick is an agenda you have to leave to use. The
// mobile ADR line wins for the mobile surface; neither shell changed the
// other. The write is guarded per line ([toggleTaskLine]), so a row drawn
// from a stale pass declines rather than overwriting.

package org.gilune.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDate

@Composable
fun AgendaScreen(
    state: GiluneState,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = MaterialTheme.colorScheme
    val plugins = state.plugins.toList()
    val types = searchTypesFor(plugins)
    val filter = plugins.tasksFilter()

    // One pass over the vault per recompute, keyed exactly as the desktop
    // panel is: the vault, its tick, the live types and the global filter.
    // Never per keystroke. `today` is read inside the pass and carried out
    // of it, so the day every view means is the day the pass ran.
    val computed = remember(state.vaultTick.value, state.vaultDir, types.size, filter) {
        val today = LocalDate.now()
        today to agenda(
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
    val today = computed.first
    val sections = computed.second

    // The same chain the editor folds over a line it is toggling, gated
    // the same way — so ticking from the agenda stamps `done:` exactly as
    // ticking in the editor does, and stamps nothing when the plugin that
    // owns the stamp is off.
    val decorations = remember(plugins, filter) {
        if (plugins.pluginContributes(TASKS_PLUGIN, Slots.EDITOR_DECORATIONS)) {
            builtInDecorations() + tasksDecorations(filter)
        } else {
            builtInDecorations()
        }
    }

    fun toggle(hit: TaskHit) {
        val file = File(state.vaultDir, hit.relPath)
        val current = runCatching { file.readText() }.getOrNull()
        // `TaskHit.line` is body-relative and 1-based; the writer's is
        // 0-based.
        val updated = current?.let { toggleTaskLine(it, hit.line - 1, hit.text, decorations) }
        if (updated == null) {
            // The line moved, changed, went, or nothing claimed it. All of
            // those mean leave the document alone; the tick simply does
            // not happen, and the list is re-read so the screen agrees
            // with the disk again.
            state.message.value = "that task changed on disk; the agenda reloaded"
            state.noteSaved()
            return
        }
        runCatching { file.writeText(updated) }
            .onSuccess { state.noteSaved() }
            .onFailure { state.message.value = "could not save: ${it.message}" }
    }

    // Undated is folded away to begin with: it is the view least likely to
    // be about today, and the one most likely to be long.
    val collapsed = remember { mutableStateListOf(AgendaGroup.UNDATED) }

    if (sections.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing open.",
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier.fillMaxSize().testTag("agenda")) {
        for (section in sections) {
            val open = section.group !in collapsed
            item(key = "group-${section.group.name}") {
                GroupHeader(section, open) {
                    if (!collapsed.remove(section.group)) collapsed.add(section.group)
                }
            }
            if (!open) continue
            items(section.rows, key = { "${section.group.name}:${it.relPath}:${it.line}" }) { hit ->
                AgendaRow(hit, today, onToggle = { toggle(hit) }, onOpen = { onOpenNote(hit.relPath) })
            }
            if (section.more > 0) {
                item(key = "more-${section.group.name}") {
                    Text(
                        "+${section.more} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = colours.outline,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/// A view's header: what it is, how many are in it — the whole membership,
/// so a header never under-reports what the cap held back — and the
/// chevron that folds it.
@Composable
private fun GroupHeader(section: AgendaSection, open: Boolean, onToggle: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = TouchTarget)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(chevronFor(open), contentDescription = null, tint = colours.onSurfaceVariant)
        Text(
            "${section.group.label} (${section.count})",
            style = MaterialTheme.typography.titleSmall,
            color = colours.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun chevronFor(open: Boolean): ImageVector =
    if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight

/// One task, as a thumb meets it: a 48dp box that ticks it, and a body
/// that opens the note it lives in. The two targets are separate on
/// purpose — a tap that means "done" and a tap that means "show me" must
/// not be the same gesture landing a few pixels apart.
///
/// Every row here is open by construction (the partition drops ticked
/// tasks), so the glyph is always the empty box and the tick always has
/// somewhere to go.
@Composable
private fun AgendaRow(hit: TaskHit, today: LocalDate, onToggle: () -> Unit, onOpen: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TouchTarget).testTag("agenda-row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(TouchTarget)
                    .clickable(onClickLabel = "toggle task") {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggle()
                    }
                    .semantics { contentDescription = "task not done" },
                contentAlignment = Alignment.Center,
            ) {
                Text(UNCHECKED_BOX, color = colours.onSurfaceVariant)
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .heightIn(min = TouchTarget)
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    taskLabel(hit.text),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hit.relPath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colours.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val due = hit.due
            if (due != null) {
                Text(
                    due.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = dueColour(due, today),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        HorizontalDivider(color = colours.outlineVariant)
    }
}

/// The same glyph the rendered Markdown draws for `- [ ]`, so a task looks
/// like the same thing wherever the shell shows it.
private const val UNCHECKED_BOX = "☐" // BALLOT BOX

/// Overdue takes the error colour and today the accent; anything further
/// out is ordinary secondary text, because it is not yet asking for
/// anything. The reading comes from the date against `today` rather than
/// from the group, so a row says the same thing wherever it is listed.
@Composable
private fun dueColour(due: LocalDate, today: LocalDate): Color = when {
    due < today -> MaterialTheme.colorScheme.error
    due == today -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
