// The files list's right-click menu, drawn by the shell so one of its
// items can carry a switch.
//
// Compose's context menu takes a list of `ContextMenuItem(label, onClick)`
// and draws them itself; nothing in that contract can hold a control.
// But the item class is open and the renderer is a composition local, so
// the tree supplies both: a `SwitchMenuItem` that knows whether it is on,
// and a representation that draws every item the way the default does and
// that one with a `MiniSwitch` at its end. Everything else about the menu
// — opening on right-click, closing on a choice, Escape, a click outside —
// stays Compose's.

package org.gilune.app

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition

/// A menu item with a switch at its end: `checked` is what the switch
/// shows, `enabled` whether choosing it does anything, `tag` what a test
/// finds it by. Choosing the item is choosing the switch.
class SwitchMenuItem(
    label: String,
    val checked: Boolean,
    val enabled: Boolean,
    val tag: String,
    onClick: () -> Unit,
) : ContextMenuItem(label, onClick)

/// Draws the tree's menu: the default's shape in the theme's colours,
/// with a `SwitchMenuItem` carrying its switch.
object GiluneContextMenuRepresentation : ContextMenuRepresentation {
    // The position provider that puts a popup at a point is the one the
    // default representation uses too; it is simply not stable API yet.
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        val colours = MaterialTheme.colorScheme
        fun close() { state.status = ContextMenuState.Status.Closed }
        // A switch item is flipped in place: the menu stays open and its
        // items are read again, so the switch shows the new state. This
        // counter is what asks for that re-read; the items themselves are
        // built fresh by the tree each time.
        var flipped by remember { mutableStateOf(0) }
        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(positionPx = status.rect.center),
            onDismissRequest = ::close,
            properties = PopupProperties(focusable = true),
            onKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { close(); true } else false
            },
        ) {
            Column(
                Modifier
                    .shadow(6.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(colours.surfaceContainer)
                    .padding(vertical = 4.dp)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 160.dp),
            ) {
                @Suppress("UNUSED_VARIABLE") val reread = flipped
                for (item in items()) {
                    val switch = item as? SwitchMenuItem
                    val enabled = switch?.enabled ?: true
                    val choose: () -> Unit = {
                        if (switch != null) {
                            switch.onClick()
                            flipped++
                        } else {
                            close()
                            item.onClick()
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .then(
                                if (switch != null) {
                                    Modifier
                                        .testTag(switch.tag)
                                        .semantics {
                                            role = Role.Switch
                                            toggleableState = if (switch.checked) ToggleableState.On else ToggleableState.Off
                                        }
                                } else Modifier,
                            )
                            .clickable(enabled = enabled, onClick = choose)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.label,
                            fontSize = 13.sp,
                            color = if (enabled) colours.onSurface else colours.onSurfaceVariant,
                        )
                        if (switch != null) {
                            Spacer(Modifier.weight(1f).width(16.dp))
                            // The row is the switch for semantics and for
                            // clicks; the control only shows the state.
                            MiniSwitch(
                                checked = switch.checked,
                                enabled = switch.enabled,
                                onToggle = choose,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        }
                    }
                }
            }
        }
    }
}
