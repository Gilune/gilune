// The bar over a note that finds and replaces in its body (ADR 0031 § 3).
// Its state lives on the tab's editor, so each tab keeps its own pattern;
// what the pattern matches, and stepping and replacing, are the note
// editor's, because they move its selection and write its buffer.

package org.gilune.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gilune.plugins.findreplace.FindOptions
import org.gilune.plugins.findreplace.FindQuery

/// One tab's bar. `steps` and `focusRequests` are counted requests, as the
/// window's gestures are: whoever asks bumps them, and the editor answers.
class FindState {
    var open by mutableStateOf(false)
    var replacing by mutableStateOf(false)
    var pattern by mutableStateOf(TextFieldValue(""))
    var replacement by mutableStateOf("")
    var options by mutableStateOf(FindOptions())

    /// Count paired with the direction: true steps forward.
    var steps by mutableStateOf(0 to true)
    var focusRequests by mutableStateOf(0)

    /// Bumped by Esc, so the editor hands the caret back to the body.
    var closed by mutableStateOf(0)

    val query: FindQuery get() = FindQuery(pattern.text, options)

    /// Opens the bar, with the replace row when asked. A selection on one
    /// line becomes the pattern, as in every editor; either way the
    /// pattern arrives selected, ready to be typed over.
    fun show(replace: Boolean, selected: String) {
        val seed = selected.takeIf { it.isNotEmpty() && '\n' !in it } ?: pattern.text
        pattern = TextFieldValue(seed, TextRange(0, seed.length))
        open = true
        if (replace) replacing = true
        focusRequests++
    }

    fun step(forward: Boolean) {
        if (open) steps = steps.first + 1 to forward
    }

    fun close() {
        open = false
        closed++
    }
}

/// What the bar shows of the matches, worked out by the editor.
data class FindStatus(val count: Int, val current: Int?, val problem: String?)

@Composable
fun FindBar(
    find: FindState,
    status: FindStatus,
    onStep: (forward: Boolean) -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    stepKeys: (KeyEvent) -> Boolean,
) {
    val colours = MaterialTheme.colorScheme
    val patternFocus = remember { FocusRequester() }
    LaunchedEffect(find.focusRequests) {
        if (find.focusRequests == 0) return@LaunchedEffect
        // The bar may have been composed by the very request that asks.
        var attempts = 0
        while (runCatching { patternFocus.requestFocus() }.isFailure && attempts++ < 5) withFrameNanos { }
    }
    // Keys every field in the bar answers: Esc closes, and the plugin's
    // step keys (F3 by default) step from either field.
    fun barKeys(event: KeyEvent): Boolean = when {
        event.type != KeyEventType.KeyDown -> false
        event.key == Key.Escape -> { find.close(); true }
        else -> stepKeys(event)
    }
    Column(Modifier.fillMaxWidth().testTag("find-bar")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BarButton(
                if (find.replacing) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                if (find.replacing) "Hide replace" else "Show replace",
                "find-toggle-replace",
            ) { find.replacing = !find.replacing }
            BarField(
                find.pattern,
                { find.pattern = it },
                "Find",
                Modifier.weight(1f).focusRequester(patternFocus).testTag("find-pattern").onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown && event.key == Key.Enter -> { onStep(!event.isShiftPressed); true }
                        else -> barKeys(event)
                    }
                },
            )
            Text(
                status.problem ?: when {
                    find.pattern.text.isEmpty() -> ""
                    status.count == 0 -> "no matches"
                    status.current == null -> "${status.count} found"
                    else -> "${status.current + 1} of ${status.count}"
                },
                fontSize = 11.sp,
                maxLines = 1,
                color = if (status.problem != null || (status.count == 0 && find.pattern.text.isNotEmpty())) colours.error else colours.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 64.dp).testTag("find-status"),
            )
            FindSwitch("Aa", "Match case", find.options.matchCase, "find-case") { find.options = find.options.copy(matchCase = it) }
            FindSwitch("W", "Whole word", find.options.wholeWord, "find-word") { find.options = find.options.copy(wholeWord = it) }
            FindSwitch(".*", "Regular expression", find.options.regex, "find-regex") { find.options = find.options.copy(regex = it) }
            BarButton(Icons.Default.KeyboardArrowUp, "Previous match", "find-previous") { onStep(false) }
            BarButton(Icons.Default.KeyboardArrowDown, "Next match", "find-next") { onStep(true) }
            BarButton(Icons.Default.Close, "Close (Esc)", "find-close") { find.close() }
        }
        if (find.replacing) {
            Row(
                Modifier.fillMaxWidth().padding(start = 34.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                var replacement by remember { mutableStateOf(TextFieldValue(find.replacement)) }
                if (replacement.text != find.replacement) replacement = TextFieldValue(find.replacement)
                BarField(
                    replacement,
                    { replacement = it; find.replacement = it.text },
                    "Replace with",
                    Modifier.weight(1f).testTag("find-replacement").onPreviewKeyEvent { event ->
                        when {
                            event.type == KeyEventType.KeyDown && event.key == Key.Enter -> { onReplace(); true }
                            else -> barKeys(event)
                        }
                    },
                )
                TextAction("Replace", "find-replace-one", enabled = status.count > 0, onReplace)
                TextAction("All", "find-replace-all", enabled = status.count > 0, onReplaceAll)
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = colours.outlineVariant)
    }
}

@Composable
private fun BarField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, placeholder: String, modifier: Modifier) {
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
                Modifier
                    .border(1.dp, colours.outlineVariant, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) Text(placeholder, fontSize = 12.sp, color = colours.onSurfaceVariant)
                inner()
            }
        },
    )
}

@Composable
private fun BarButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Tooltip(label) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .padding(4.dp)
                .testTag(tag),
        )
    }
}

/// One of the three switches: lit while on.
@Composable
private fun FindSwitch(glyph: String, label: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Tooltip(label) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (on) colours.primaryContainer else colours.surface)
                .border(1.dp, if (on) colours.primary else colours.surface, RoundedCornerShape(4.dp))
                .clickable { onChange(!on) }
                .semantics { role = Role.Switch; selected = on }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (on) colours.onPrimaryContainer else colours.onSurfaceVariant)
        }
    }
}

@Composable
private fun TextAction(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Text(
        label,
        fontSize = 12.sp,
        color = if (enabled) colours.primary else colours.outline,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .border(1.dp, colours.outlineVariant, RoundedCornerShape(4.dp))
            .padding(vertical = 5.dp)
            .testTag(tag),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
