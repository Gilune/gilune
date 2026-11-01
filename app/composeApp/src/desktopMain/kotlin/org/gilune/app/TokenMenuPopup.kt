// A registration's own menu on screen (ADR 0015 amendment 2026-10-31): the
// due-date menu today, opened from the arrow beside a `due:` token, the
// right-click menu, the date shortcut, or a task row. Unlike the `/` menu it
// takes the focus: it has a field of its own for words like "friday", and
// nothing is being typed into the note while it is open. Escape, a click
// outside, or a choice closes it.
//
// It knows nothing about dates: it shows a `TokenMenu` and hands back the
// item chosen. Applying it — to a buffer or to a file — is the caller's.

package org.gilune.app

import org.gilune.plugin.sdk.TokenMenu
import org.gilune.plugin.sdk.TokenMenuItem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/// The menu as a popup under `anchor` (window coordinates). The field takes
/// the focus at once, so a date can simply be typed; ↑ and ↓ move through
/// the rows and Enter takes the one marked — the typed row as soon as the
/// words mean a day.
@Composable
fun TokenMenuPopup(
    menu: TokenMenu,
    anchor: Rect,
    onChoose: (TokenMenuItem) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        popupPositionProvider = CaretPositionProvider(anchor),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        TokenMenuView(menu, onChoose, onDismiss)
    }
}

/// The menu's face, apart from where it floats, so a test can compose it.
@Composable
fun TokenMenuView(menu: TokenMenu, onChoose: (TokenMenuItem) -> Unit, onDismiss: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    var typed by remember(menu) { mutableStateOf("") }
    val typedItem = remember(menu, typed) { menu.typed?.invoke(typed) }
    // The marked row: an index into the items, or `items.size` for the
    // typed row. Words that mean a day mark it; clearing them marks the
    // first item again.
    var selected by remember(menu) { mutableStateOf(0) }
    LaunchedEffect(typedItem != null) {
        if (typedItem != null) selected = menu.items.size
        else if (selected == menu.items.size) selected = 0
    }
    val rows = menu.items.size + if (menu.typed != null) 1 else 0
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    fun choose(index: Int) {
        val item = if (index == menu.items.size) typedItem else menu.items.getOrNull(index)
        if (item != null) onChoose(item)
    }

    Surface(
        modifier = Modifier.width(250.dp).testTag("token-menu"),
        shape = RoundedCornerShape(6.dp),
        color = colours.surfaceContainer,
        border = BorderStroke(1.dp, colours.outlineVariant),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                menu.title,
                fontSize = 11.sp,
                color = colours.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp).testTag("token-menu-title"),
            )
            for ((index, item) in menu.items.withIndex()) {
                if (item.startsGroup) HorizontalDivider(Modifier.padding(vertical = 3.dp), color = colours.outlineVariant)
                MenuRow(item.label, item.detail, index == selected, "token-menu-item-$index") { choose(index) }
            }
            if (menu.typed != null) {
                HorizontalDivider(Modifier.padding(vertical = 3.dp), color = colours.outlineVariant)
                val marked = selected == menu.items.size
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (marked && typedItem != null) colours.primaryContainer else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .border(1.dp, colours.outline, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        if (typed.isEmpty()) {
                            Text(menu.typedPlaceholder, fontSize = 12.sp, color = colours.onSurfaceVariant, maxLines = 1)
                        }
                        BasicTextField(
                            value = typed,
                            onValueChange = { typed = it.replace("\n", "") },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp, color = colours.onSurface),
                            cursorBrush = SolidColor(colours.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(field)
                                .testTag("token-menu-field")
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionDown -> { selected = (selected + 1) % rows; true }
                                        Key.DirectionUp -> { selected = (selected - 1 + rows) % rows; true }
                                        Key.Enter, Key.NumPadEnter -> { choose(selected); true }
                                        Key.Escape -> { onDismiss(); true }
                                        else -> false
                                    }
                                },
                        )
                    }
                    Text(
                        typedItem?.detail ?: "",
                        fontSize = 11.sp,
                        color = colours.onSurfaceVariant,
                        modifier = Modifier
                            .let { if (typedItem == null) it else it.clickable { choose(menu.items.size) } }
                            .testTag("token-menu-typed"),
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, detail: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colours.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) colours.onPrimaryContainer else colours.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (detail.isNotEmpty()) {
            Text(detail, fontSize = 11.sp, maxLines = 1, color = colours.onSurfaceVariant)
        }
    }
}

/// The small button that opens a token's menu: the arrow beside a due date
/// in the editor, and on a task row. `size` is the caller's, because in the
/// editor it is measured from the text it sits beside.
@Composable
fun MenuArrowChip(
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.KeyboardDoubleArrowRight,
    description: String = "Move the due date",
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colours = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .hoverable(interaction)
            // Pressing it must not take the focus from the note: the caret
            // stays where it was, and the line it is on keeps its raw form
            // — a focus change would re-render the line under the pointer.
            .focusProperties { canFocus = false }
            .background(colours.surface, RoundedCornerShape(4.dp))
            .border(1.dp, colours.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = colours.onSurfaceVariant, modifier = Modifier.size(size * 0.78f))
    }
}
