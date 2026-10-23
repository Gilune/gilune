// `filter` fences in the phone's Read view (ADR 0017 § 7's
// `editor.blocks`; MB3).
//
// The evaluation is `FilterBlock.kt`'s and entirely shared — one query in
// the search DSL, one pass over the vault, the same degenerate readings
// and the same hints. What is here is the mobile face of the result, and
// it is a separate registration from the desktop's `filterBlockRegistration`
// for one reason: rows a thumb has to hit are 48dp and 16sp, and the
// desktop's are a pointer's. The registration is the shell's own, not the
// tasks plugin's, so a block keeps rendering — as a hint row naming the
// type it wanted — when every removable plugin is gone.
//
// Row text navigates. A separate checkbox uses the shared guarded action
// to update the source task and refresh the query.

package org.gilune.app

import org.gilune.plugins.tasks.TaskHit
import org.gilune.plugins.tasks.tasksFilter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import uniffi.gilune_ffi.PluginInfo

/// The shell's registration of the `filter` fence for the phone's Read
/// view. A block is recomputed when the change cursor moves — when the
/// vault's files actually changed — never per keystroke; Read has no
/// keystrokes, and the rule is the same one the desktop keeps.
fun mobileFilterBlockRegistration(
    state: GiluneState,
    plugins: List<PluginInfo>,
    onOpen: ((String) -> Unit)? = null,
): BlockRegistration = BlockRegistration(FILTER_FENCE) { block ->
    MobileFilterBlock(state, plugins, block.text, onOpen)
}

/// A `filter` block as a live list. Group-less — a block asked one
/// question and gets one answer — capped at [FILTER_ROW_CAP], which is the
/// Read cap on both platforms, with the rest counted. Degenerate blocks
/// get their single hint row.
@Composable
fun MobileFilterBlock(
    state: GiluneState,
    plugins: List<PluginInfo>,
    block: String,
    onOpen: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colours = MaterialTheme.colorScheme
    // Keyed on the live plugin state that the type registry and the global
    // filter stand for, so switching the tasks plugin off turns a
    // `type:task` block into its hint on the next composition.
    val types = searchTypesFor(plugins)
    val filter = plugins.tasksFilter()
    val open = state.gilune.value
    val today = rememberQueryDay()
    val computed = rememberQueryWork(state.vaultCursor.value, state.searchRefresh.value, block, plugins, open, today) {
        if (open == null) FilterResult.Hint("Opening vault...")
        else evaluateFilterBlock(open, state.vaultDir, block, types, today, operators = searchOperatorsFor(plugins, today))
    }.value
    val result = computed.value ?: FilterResult.Hint(computed.error ?: "Searching...")


    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colours.surfaceVariant)
            .padding(vertical = 4.dp)
            .testTag("filter-block"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (computed.error != null) {
            androidx.compose.material3.TextButton(onClick = state::retrySearchIndex) { Text("Retry search") }
        }
        when (result) {
            is FilterResult.Hint -> HintRow(result.text)
            is FilterResult.Tasks -> {
                if (result.rows.isEmpty()) HintRow(FILTER_NO_MATCHES)
                for (row in result.rows) TaskRow(row, today, onOpen) { toggleTaskResult(state, it) }
                MoreRow(result.more)
            }
            is FilterResult.Notes -> {
                if (result.rows.isEmpty()) HintRow(FILTER_NO_MATCHES)
                for (row in result.rows) NoteRowView(row, onOpen)
                MoreRow(result.more)
            }
        }
    }
}

/// One task line the block matched: its own words, the note it came from,
/// and its due date coloured by what that date is asking for.
@Composable
private fun TaskRow(hit: TaskHit, today: LocalDate, onOpen: ((String) -> Unit)?, onToggle: (TaskHit) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colours.surface)
            .let { if (onOpen == null) it else it.clickable { onOpen(hit.relPath) } }
            .heightIn(min = TouchTarget)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("filter-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TaskPriorityMark(hit.priority)
        TaskResultCheckbox(hit, onToggle, Modifier.size(TouchTarget))
        Column(Modifier.weight(1f)) {
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
            )
        }
    }
}

/// A note-level hit: its name, and the path that says which one it is.
@Composable
private fun NoteRowView(row: NoteRow, onOpen: ((String) -> Unit)?) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colours.surface)
            .let { if (onOpen == null) it else it.clickable { onOpen(row.relPath) } }
            .heightIn(min = TouchTarget)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("filter-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            row.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            row.relPath,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colours.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/// The one row a block says something in rather than lists something in:
/// a hint, or an answered question with no matches. Muted and italic, so
/// it never reads as a result — and never clickable, because it is not a
/// note.
@Composable
private fun HintRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).testTag("filter-hint"),
    )
}

/// The footer. Nought is drawn as nothing rather than as a line saying so.
@Composable
private fun MoreRow(count: Int) {
    if (count <= 0) return
    Text(
        "+$count more",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

@Composable
private fun dueColour(due: LocalDate, today: LocalDate): Color = when {
    due < today -> MaterialTheme.colorScheme.error
    due == today -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
