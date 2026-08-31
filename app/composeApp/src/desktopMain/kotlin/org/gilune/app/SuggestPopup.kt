// The `/` menu on screen (ADR 0015 § 2): the shell's first caret-anchored
// popup. Published as shell infrastructure rather than built for one
// plugin — the surface renders whatever [SuggestSession] offers and knows
// nothing about dates or tasks.
//
// **It never takes the focus.** `focusable = false`, the `QuickOpenBox`
// precedent: the text field keeps the caret and every keystroke, so
// typing narrows the menu without the buffer ever losing its cursor, and
// the caller routes ↑/↓/Enter/Escape into the session before the field
// sees them. A focusable popup would take the field's focus the instant
// it appeared and break the one thing this menu must not break.

package org.gilune.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/// Places a popup against the caret rather than against a layout node.
///
/// `caret` is the caret's rectangle **in window coordinates** — from
/// `TextLayoutResult.getCursorRect` translated by the field's position —
/// so `anchorBounds`, which describes wherever the `Popup` call happens
/// to sit in the tree, is deliberately ignored, as `WindowOverlay` does.
///
/// The menu sits just below the caret; when it would fall off the foot of
/// the window it flips above (§ 2), and only when it fits in neither
/// direction is it clamped to the window. Horizontally it starts at the
/// caret and slides left as far as it must to stay inside.
class CaretPositionProvider(
    private val caret: Rect,
    private val gap: Int = 4,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val below = caret.bottom.roundToInt() + gap
        val above = caret.top.roundToInt() - gap - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }
        val x = caret.left.roundToInt()
            .coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0))
            .coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/// The menu itself. `anchor` is the caret rectangle in window
/// coordinates; `onDismiss` is the platform asking it to close (Escape
/// and every other dismissal is the caller's, since this popup takes no
/// keys). `onAccept` is optional: supplied, rows answer the mouse too.
@Composable
fun SuggestPopup(
    session: SuggestSession,
    anchor: Rect,
    onDismiss: () -> Unit,
    onAccept: ((Int) -> Unit)? = null,
) {
    val colours = MaterialTheme.colorScheme
    Popup(
        popupPositionProvider = CaretPositionProvider(anchor),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            modifier = Modifier.width(210.dp).testTag("suggest-popup"),
            shape = RoundedCornerShape(6.dp),
            color = colours.surfaceContainer,
            border = BorderStroke(1.dp, colours.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            Column {
                Column(
                    Modifier
                        .fillMaxWidth()
                        // Eleven date rows fit; a longer field registry
                        // scrolls. The highlight is not scrolled into
                        // view — an honest v1 gap, invisible until a
                        // provider offers more rows than this.
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 3.dp),
                ) {
                    for ((index, candidate) in session.candidates.withIndex()) {
                        CandidateRow(
                            candidate = candidate,
                            selected = index == session.selected,
                            onClick = onAccept?.let { accept -> { accept(index) } },
                        )
                    }
                }
                HorizontalDivider(color = colours.outlineVariant)
                Text(
                    "↵ insert · esc dismiss",
                    fontSize = 10.sp,
                    color = colours.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/// One row: the words, and the machine form right-aligned against them —
/// the resolved date, which is the whole point of a menu that promises
/// never to write a phrase into the file.
@Composable
private fun CandidateRow(
    candidate: SuggestCandidate,
    selected: Boolean,
    onClick: (() -> Unit)?,
) {
    val colours = MaterialTheme.colorScheme
    val base = Modifier
        .fillMaxWidth()
        .background(if (selected) colours.primaryContainer else Color.Transparent)
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("suggest-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            candidate.display,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) colours.onPrimaryContainer else colours.onSurface,
            modifier = Modifier.weight(1f, fill = true),
        )
        if (candidate.detail.isNotEmpty()) {
            Text(
                candidate.detail,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                color = colours.onSurfaceVariant,
            )
        }
    }
}
