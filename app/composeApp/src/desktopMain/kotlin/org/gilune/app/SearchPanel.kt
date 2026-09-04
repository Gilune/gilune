// The rail's search panel: a query over the vault's contents, answered
// as the lines that matched, grouped under the note they came from.
// Reading the files is the whole of it — no index to go stale, and other
// tools' edits are found the moment they land (constitution rule 13).
//
// The query language lives in `searchVault`; this panel only shows it,
// and hints at its forms while the box is empty. What a hit *is* comes
// from the live plugin list (ADR 0015 § 4): switch the tasks plugin off
// and `type:task` stops being a type and `due:` goes back to being a
// word, here and in the hint both.

package org.gilune.app

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/// Searching is one query per keystroke on the composing thread. A query
/// is microseconds against the system index (ADR 0020); what it costs
/// beyond that is reading the notes it answered with, so the bill is
/// proportional to the hits rather than to the vault.
@Composable
fun SearchPanel(
    state: GiluneState,
    searchFocus: FocusRequester,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    val colours = MaterialTheme.colorScheme
    var query by remember { mutableStateOf(initialQuery) }
    val focusManager = LocalFocusManager.current
    // Built from the plugin list on every composition, so enabling or
    // removing a plugin changes the language on the next keystroke — no
    // registry to re-seed, nothing to restart. `filter` is a key of its
    // own because it changes what a task line is without changing the
    // list's identity.
    val types = searchTypesFor(state.plugins)
    val filter = state.tasksFilter()
    val open = state.gilune.value
    val hits = remember(query, state.vaultCursor.value, types.size, filter, open) {
        if (query.isBlank() || open == null) {
            emptyList()
        } else {
            // `today` is read here rather than inside the engine so the
            // day a relative word means is the day the query ran.
            queryVault(open, state.vaultDir, query, types = types, today = LocalDate.now())
        }
    }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp)
                .border(1.dp, colours.outlineVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = colours.onSurfaceVariant,
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocus)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            event.key == Key.Enter && hits.isNotEmpty() -> {
                                onOpen(hits.first().relPath)
                                true
                            }
                            event.key == Key.Escape -> {
                                query = ""
                                focusManager.clearFocus()
                                true
                            }
                            else -> false
                        }
                    },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = colours.onSurface),
                cursorBrush = SolidColor(colours.primary),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("search vault…", fontSize = 12.sp, color = colours.onSurfaceVariant)
                        }
                        inner()
                    }
                },
            )
        }

        when {
            query.isBlank() -> QueryForms(types.any { it.key == TASK_SEARCH_TYPE })
            hits.isEmpty() -> Text(
                "nothing matches",
                fontSize = 11.sp,
                color = colours.outline,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(bottom = 6.dp)) {
                items(hits, key = { it.relPath }) { hit -> HitRows(hit, onOpen) }
            }
        }
    }

    // The window switches panel and then asks for focus; asking again
    // from inside costs nothing and covers the case where the field had
    // not been attached yet when it did.
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
}

@Composable
private fun HitRows(hit: SearchHit, onOpen: (String) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen(hit.relPath) }
                .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 1.dp),
        ) {
            Text(
                hit.name.removeSuffix(".md"),
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
        for (line in hit.lines) {
            Text(
                emphasised(line.text, line.ranges, colours.primary),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colours.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(hit.relPath) }
                    .padding(start = 22.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            )
        }
        if (hit.moreMatches > 0) {
            Text(
                "+${hit.moreMatches} more",
                fontSize = 11.sp,
                color = colours.outline,
                modifier = Modifier.padding(start = 22.dp, top = 1.dp),
            )
        }
    }
}

/// The matched spans, emphasised in place. Offsets are into the line as
/// the search read it, so the line is shown untrimmed; they are clamped
/// regardless, since a range past the end must not take the panel down.
private fun emphasised(text: String, ranges: List<IntRange>, colour: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val style = SpanStyle(color = colour, fontWeight = FontWeight.SemiBold)
        for (range in ranges) {
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (end > start) addStyle(style, start, end)
        }
    }

private val QUERY_FORMS = listOf(
    "words" to "all of them, anywhere",
    "\"a phrase\"" to "exactly that",
    "-word" to "leave these out",
    "file:" to "in the name",
    "path:" to "in the path",
    "content:" to "in the body",
    "[key:value]" to "in the front matter",
)

/// Shown only while the tasks plugin is on: a hint is a promise, and a
/// keyword that has gone back to being prose must not be advertised.
private val TASK_QUERY_FORMS = listOf(
    "type:task" to "task lines, not notes",
    "due:" to "today, overdue, week, none, before:/after:",
    "is:" to "open, done",
)

@Composable
private fun QueryForms(taskForms: Boolean) {
    val colours = MaterialTheme.colorScheme
    Column(
        Modifier.padding(start = 12.dp, end = 10.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((form, gloss) in QUERY_FORMS + if (taskForms) TASK_QUERY_FORMS else emptyList()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    form,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colours.onSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                )
                Text(gloss, fontSize = 10.sp, color = colours.outline, modifier = Modifier.weight(1f))
            }
        }
    }
}
