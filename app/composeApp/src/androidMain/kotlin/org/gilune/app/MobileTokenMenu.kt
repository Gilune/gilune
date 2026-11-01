// A token's menu on the phone (ADR 0015 amendment 2026-10-31): a long press
// on a due date opens it. The desktop floats it beside the date; a phone
// shows it as a dialog, because a finger needs rows tall enough to hit and
// the keyboard needs room for the typed field.
//
// Where a date sits is asked of the phone's own rendering — the checkbox
// glyph is the only length change it makes — so the targets line up with
// what the field draws, as the checkbox's targets do.

package org.gilune.app

import org.gilune.plugin.sdk.EditorDecoration
import org.gilune.plugin.sdk.TokenMenu
import org.gilune.plugin.sdk.TokenMenuItem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/// One date the phone can open a menu on: its range in the source and in
/// the **shown** text, and the line a choice rewrites.
internal data class MenuTarget(
    val decoration: EditorDecoration,
    val transformedStart: Int,
    val transformedEnd: Int,
    val lineStart: Int,
    val lineEnd: Int,
)

/// Every menu-bearing token in `source`, placed in the phone's rendering:
/// each glyph edit before a token shifts it by the glyph's change in length.
internal fun menuTargets(source: String, decorations: List<EditorDecoration>): List<MenuTarget> {
    val bearing = decorations.filter { it.menu != null }
    if (bearing.isEmpty()) return emptyList()
    val tokens = menuTokens(source, caretLine = null, decorations = bearing)
    val edits = editorGlyphEdits(source, decorations)
    fun shown(offset: Int): Int = offset + edits.filter { it.first.last < offset }
        .sumOf { (range, glyph) -> glyph.length - (range.last + 1 - range.first) }
    return tokens.map { token ->
        MenuTarget(token.decoration, shown(token.range.first), shown(token.range.last + 1), token.lineRange.first, token.lineRange.last + 1)
    }
}

/// The source offset under a shown offset: the inverse of [menuTargets]'
/// shift, landing at a glyph's start when the offset falls inside one.
internal fun sourceOffset(source: String, shown: Int, decorations: List<EditorDecoration>): Int {
    var shift = 0
    for ((range, glyph) in editorGlyphEdits(source, decorations)) {
        val glyphStart = range.first + shift
        if (shown < glyphStart) break
        if (shown < glyphStart + glyph.length) return range.first
        shift += glyph.length - (range.last + 1 - range.first)
    }
    return (shown - shift).coerceIn(0, source.length)
}

/// The menu as a dialog: its title, one row per item with the day it
/// writes, and — when the menu takes words — a field whose Done key takes
/// the day the words mean.
@Composable
internal fun MobileTokenMenuDialog(menu: TokenMenu, onChoose: (TokenMenuItem) -> Unit, onDismiss: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    var typed by remember(menu) { mutableStateOf("") }
    val typedItem = remember(menu, typed) { menu.typed?.invoke(typed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("token-menu"),
        title = { Text(menu.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for ((index, item) in menu.items.withIndex()) {
                    if (item.startsGroup) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onChoose(item) }
                            .padding(horizontal = 4.dp)
                            .testTag("token-menu-item-$index"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.label, Modifier.weight(1f))
                        if (item.detail.isNotEmpty()) Text(item.detail, color = colours.onSurfaceVariant)
                    }
                }
                if (menu.typed != null) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.replace("\n", "") },
                        singleLine = true,
                        placeholder = { Text(menu.typedPlaceholder) },
                        supportingText = { Text(typedItem?.detail ?: " ") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { typedItem?.let(onChoose) }),
                        modifier = Modifier.fillMaxWidth().testTag("token-menu-field"),
                    )
                }
            }
        },
        confirmButton = {
            if (typedItem != null) TextButton(onClick = { onChoose(typedItem) }) { Text("Use ${typedItem.detail}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
