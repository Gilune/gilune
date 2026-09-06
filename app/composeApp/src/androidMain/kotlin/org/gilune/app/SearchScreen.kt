// The Search destination (ADR 0017 § 1, MB3): the third shell tab, over
// the shared DSL and nothing else.
//
// There is no mobile query language. Kernel search is the same service the
// desktop rail runs, `searchTypesFor` builds the same registry from the
// same live plugin list, and what a hit *is* therefore changes with the
// plugins here exactly as it does there (ADR 0015 § 4): switch the tasks
// plugin off and `type:task` stops being a type, `due:` goes back to being
// a word, and the hint stops advertising either.
//
// What differs from `SearchPanel.kt` is only the ergonomics a thumb needs:
// 16sp rows, 48dp targets, the field focused when the tab opens, and no
// keyboard shortcuts to speak of — Enter-opens-the-first-hit is a pointer
// affordance, and on a phone the hit itself is the target.

package org.gilune.app

import org.gilune.plugins.tasks.TASK_SEARCH_TYPE

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/// One pass over the vault per keystroke, on the composing thread — the
/// desktop panel's honest position, unchanged: a personal vault is small,
/// and if a large one ever feels it the fix is a debounce or a pass off
/// the main thread, not an index that can go stale (constitution rule 13).
@Composable
fun SearchScreen(
    state: GiluneState,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    val colours = MaterialTheme.colorScheme
    var query by remember { mutableStateOf(initialQuery) }
    val focus = remember { FocusRequester() }

    // Built from the plugin list on every composition, so enabling or
    // removing a plugin changes the language on the next keystroke.
    val types = searchTypesFor(state.plugins)
    val search = rememberPagedSearch(state, query, types)
    val hits = search.listing.hits

    // The shell already pads for the keyboard, so this consumes nothing
    // under it; it is here for the case the screen is hosted alone — a
    // test, or a future single-screen entry point — where the results
    // would otherwise run under the IME.
    Column(modifier.fillMaxSize().imePadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focus)
                .testTag("search-field"),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search your vault", style = MaterialTheme.typography.bodyLarge) },
            textStyle = MaterialTheme.typography.bodyLarge,
        )

        QueryNotice(search.listing.loading, search.listing.error, state::retrySearchIndex)
        if (search.listing.generation != null && search.listing.total == null) {
            Text(if (search.listing.countError == null) "Counting results…" else "Total unavailable")
        }
        if (search.listing.next != null) {
            androidx.compose.material3.TextButton(onClick = search.more, enabled = !search.listing.loading) {
                Text(search.listing.total?.let { "Load more (${hits.size} of $it)" } ?: "Load more (${hits.size} loaded)")
            }
        }
        when {
            query.isBlank() -> QueryFormList(types.any { it.key == TASK_SEARCH_TYPE })
            hits.isEmpty() && (search.listing.loading || search.listing.error != null) -> Unit
            hits.isEmpty() -> Text(
                "Nothing matches.",
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize().testTag("search-results")) {
                items(hits, key = { it.relPath }) { hit -> HitGroup(hit, onOpenNote) }
            }
        }
    }

    // The tab opens with the caret already in the field: search is a verb,
    // and arriving here having chosen it is the same gesture as beginning
    // to type. Wrapped because a focus request before the field attaches
    // throws rather than waiting.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/// One note that matched, with the lines that matched under it. Under
/// `type:task` those lines are task lines, drawn as the same rows — the
/// note is still the group, because the note is still what a tap opens.
@Composable
private fun HitGroup(hit: SearchHit, onOpenNote: (String) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("search-hit")) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { onOpenNote(hit.relPath) }
                .heightIn(min = TouchTarget)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                hit.name.removeSuffix(".md"),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hit.relPath,
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        for (line in hit.lines) {
            Text(
                emphasised(line.text.trim(), line.text, line.ranges, colours.primary),
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenNote(hit.relPath) }
                    .heightIn(min = TouchTarget)
                    .padding(start = 28.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                    .testTag("search-line"),
            )
        }
        if (hit.moreMatches > 0) {
            Text(
                "+${hit.moreMatches} more",
                style = MaterialTheme.typography.bodySmall,
                color = colours.outline,
                modifier = Modifier.padding(start = 28.dp, bottom = 6.dp),
            )
        }
        HorizontalDivider(color = colours.outlineVariant)
    }
}

/// The matched spans, emphasised in place.
///
/// Offsets are into the line **as the engine read it**, so trimming for
/// the screen has to shift them by however much came off the front; they
/// are clamped regardless, since a range past the end must not take the
/// screen down.
private fun emphasised(
    shown: String,
    original: String,
    ranges: List<IntRange>,
    colour: Color,
): AnnotatedString = buildAnnotatedString {
    append(shown)
    val shift = original.length - original.trimStart().length
    val style = SpanStyle(color = colour, fontWeight = FontWeight.SemiBold)
    for (range in ranges) {
        val start = (range.first - shift).coerceIn(0, shown.length)
        val end = (range.last + 1 - shift).coerceIn(start, shown.length)
        if (end > start) addStyle(style, start, end)
    }
}

/// The empty-query hint: what the language actually offers, right now.
///
/// Pure, so its content is asserted without a screen. It mirrors the
/// desktop panel's two lists (`SearchPanel.kt`), which are file-private
/// there; if either is ever published this becomes the import. The task
/// forms are appended **only while the tasks plugin registers its type** —
/// a hint is a promise, and a keyword that has gone back to being prose
/// must not be advertised.
fun mobileQueryForms(taskForms: Boolean): List<Pair<String, String>> =
    MOBILE_QUERY_FORMS + if (taskForms) MOBILE_TASK_QUERY_FORMS else emptyList()

private val MOBILE_QUERY_FORMS = listOf(
    "words" to "all of them, anywhere",
    "\"a phrase\"" to "exactly that",
    "-word" to "leave these out",
    "file:" to "in the name",
    "path:" to "in the path",
    "content:" to "in the body",
    "[key:value]" to "in the front matter",
)

private val MOBILE_TASK_QUERY_FORMS = listOf(
    "type:task" to "task lines, not notes",
    "due:" to "today, overdue, week, none, before:/after:",
    "is:" to "open, done",
)

@Composable
private fun QueryFormList(taskForms: Boolean) {
    val colours = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("search-hint"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((form, gloss) in mobileQueryForms(taskForms)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    form,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colours.onSurface,
                )
                Text(
                    gloss,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colours.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
