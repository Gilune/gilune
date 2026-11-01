// Replace across notes, in the Search panel (ADR 0031 § 4): the panel's
// query says which notes, these rows say what to change. Shown only while
// the find and replace plugin contributes them; the work itself is
// NoteReplacement.kt's, on the refactoring journal.

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import org.gilune.app.icons.CheckBox
import org.gilune.app.icons.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gilune.plugins.findreplace.FindOptions
import org.gilune.plugins.findreplace.FindQuery

/// Lines shown per note in the preview; the count beside the note is all
/// of them.
private const val PREVIEW_LINES = 8

/// The rows and, once a pattern is typed, the preview that stands in for
/// the search results. `focusRequests` puts the caret in the pattern.
@Composable
internal fun NotesReplace(state: GiluneState, scope: String, focusRequests: Int, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    var pattern by remember { mutableStateOf(TextFieldValue("")) }
    var replacement by remember { mutableStateOf(TextFieldValue("")) }
    var options by remember { mutableStateOf(FindOptions()) }
    var excluded by remember { mutableStateOf(emptySet<String>()) }
    var preview by remember { mutableStateOf(NotesFind()) }
    var reading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var undoable by remember { mutableStateOf<String?>(null) }
    val patternFocus = remember { FocusRequester() }
    val work = rememberCoroutineScope()
    val query = FindQuery(pattern.text, options)
    val kernel = state.gilune.value
    val plugins = state.plugins.toList()
    val day = rememberQueryDay()

    LaunchedEffect(focusRequests) {
        if (focusRequests == 0) return@LaunchedEffect
        var attempts = 0
        while (runCatching { patternFocus.requestFocus() }.isFailure && attempts++ < 5) withFrameNanos { }
    }
    // Read the notes once typing settles; any change to the vault reads
    // them again, so the preview is never older than the files.
    LaunchedEffect(query, replacement.text, scope, kernel, state.vaultCursor.value, plugins, day) {
        if (kernel == null || query.pattern.isEmpty()) { preview = NotesFind(); reading = false; return@LaunchedEffect }
        reading = true
        delay(300)
        preview = try {
            withContext(Dispatchers.IO) {
                val paths = notesInScope(kernel, state.vaultDir, scope, searchOperatorsFor(plugins, day), searchTypesFor(plugins), day)
                findInNotes(state.vaultDir, paths, query, replacement.text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NotesFind(problem = e.message ?: "the notes could not be read")
        }
        reading = false
    }

    val ticked = preview.notes.filter { it.relPath !in excluded && !it.unsaved }
    fun apply() {
        val open = kernel ?: return
        busy = true
        outcome = null
        val chosen = ticked
        val written = replacement.text
        work.launch {
            try {
                val done = withContext(Dispatchers.IO) { replaceInNotes(open, state.vaultDir, chosen, query, written, AtomicBoolean(false)::get) }
                outcome = buildString {
                    append("replaced ${done.replaced} in ${done.notes} ${if (done.notes == 1) "note" else "notes"}")
                    if (done.skipped.isNotEmpty()) append(" · ${done.skipped.size} not changed: " + done.skipped.joinToString("; ") { "${it.first} — ${it.second}" })
                }
                undoable = done.plan.id.takeIf { done.notes > 0 }
                excluded = emptySet()
                state.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                outcome = e.message ?: "the replace could not finish; Refactor history keeps what was done"
            } finally {
                busy = false
            }
        }
    }
    fun undo(planId: String) {
        val open = kernel ?: return
        busy = true
        work.launch {
            try {
                val plan = withContext(Dispatchers.IO) { undoNotesReplacement(open, state.vaultDir, planId) }
                outcome = "put back ${plan.applied} of ${plan.total} ${if (plan.total == 1uL) "note" else "notes"}"
                undoable = null
                state.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                outcome = e.message ?: "undo could not finish; Refactor history keeps what was done"
            } finally {
                busy = false
            }
        }
    }

    Column(modifier.testTag("notes-replace")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PanelField(pattern, { pattern = it }, "find", Modifier.weight(1f).focusRequester(patternFocus).testTag("notes-find"))
            PanelSwitch("Aa", "Match case", options.matchCase, "notes-case") { options = options.copy(matchCase = it) }
            PanelSwitch("W", "Whole word", options.wholeWord, "notes-word") { options = options.copy(wholeWord = it) }
            PanelSwitch(".*", "Regular expression", options.regex, "notes-regex") { options = options.copy(regex = it) }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PanelField(replacement, { replacement = it }, "replace with", Modifier.weight(1f).testTag("notes-replacement"))
        }
        val count = ticked.sumOf { it.matches.size }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                preview.problem ?: when {
                    pattern.text.isEmpty() -> if (scope.isBlank()) "in every note" else "in the notes the search finds"
                    reading -> "reading notes…"
                    preview.notes.isEmpty() -> "no matches"
                    else -> "${preview.matchCount} in ${preview.notes.size} ${if (preview.notes.size == 1) "note" else "notes"}"
                },
                fontSize = 11.sp,
                color = if (preview.problem != null) colours.error else colours.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("notes-status"),
            )
            Text(
                "Replace in ${ticked.size}",
                fontSize = 12.sp,
                color = if (count > 0 && !busy) colours.primary else colours.outline,
                modifier = Modifier
                    .border(1.dp, colours.outlineVariant, RoundedCornerShape(4.dp))
                    .clickable(enabled = count > 0 && !busy && !reading, onClick = ::apply)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("notes-replace-all"),
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
        outcome?.let { said ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(said, fontSize = 11.sp, color = colours.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("notes-outcome"))
                undoable?.let { plan ->
                    Text(
                        "Undo",
                        fontSize = 12.sp,
                        color = colours.primary,
                        modifier = Modifier.clickable(enabled = !busy) { undo(plan) }.padding(4.dp).testTag("notes-undo"),
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 4.dp, bottom = 6.dp)) {
            items(preview.notes, key = { it.relPath }) { note ->
                NotePreview(note, note.relPath !in excluded, onOpen) { on ->
                    excluded = if (on) excluded - note.relPath else excluded + note.relPath
                }
            }
        }
    }
}

@Composable
private fun NotePreview(note: NoteFind, ticked: Boolean, onOpen: (String) -> Unit, onTick: (Boolean) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 5.dp).testTag("notes-note:${note.relPath}")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                if (ticked && !note.unsaved) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = if (ticked) "Included" else "Left out",
                tint = if (note.unsaved) colours.outline else colours.primary,
                modifier = Modifier.size(15.dp).clickable(enabled = !note.unsaved) { onTick(!ticked) }.testTag("notes-tick:${note.relPath}"),
            )
            Text(
                note.relPath.removeSuffix(".md"),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { onOpen(note.relPath) },
            )
            Text("${note.matches.size}", fontSize = 11.sp, color = colours.onSurfaceVariant)
        }
        if (note.unsaved) {
            Text("unsaved edits — save the note to include it", fontSize = 11.sp, color = colours.error, modifier = Modifier.padding(start = 28.dp))
        }
        for (line in note.lines(PREVIEW_LINES)) {
            Text(
                emphasised(line.text, line.ranges, colours.primary),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colours.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp, top = 1.dp),
            )
        }
        val shown = note.lines(PREVIEW_LINES).sumOf { it.ranges.size }
        if (note.matches.size > shown) {
            Text("+${note.matches.size - shown} more", fontSize = 11.sp, color = colours.outline, modifier = Modifier.padding(start = 28.dp))
        }
    }
}

@Composable
private fun PanelField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, placeholder: String, modifier: Modifier) {
    val colours = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 12.sp, color = colours.onSurface, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(colours.primary),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.border(1.dp, colours.outlineVariant, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) Text("$placeholder…", fontSize = 12.sp, color = colours.onSurfaceVariant)
                inner()
            }
        },
    )
}

@Composable
private fun PanelSwitch(glyph: String, label: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Tooltip(label) {
        Box(
            Modifier
                .size(22.dp)
                .border(1.dp, if (on) colours.primary else colours.outlineVariant, RoundedCornerShape(4.dp))
                .clickable { onChange(!on) }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (on) colours.primary else colours.onSurfaceVariant)
        }
    }
}
