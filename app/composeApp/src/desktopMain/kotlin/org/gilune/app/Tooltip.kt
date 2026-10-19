// A name for every control drawn as an icon alone (owner report,
// 2026-10-19). A glyph with no word beside it says what it does only to
// someone who already knows, so hovering one names it, and names its
// shortcut where it has one. The words are the accessible description's
// too, so a screen reader and the mouse are told the same thing.

package org.gilune.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// Long enough that sweeping the mouse across the top bar raises nothing;
/// short enough that resting on a glyph to ask is answered.
internal const val TOOLTIP_DELAY_MS = 500

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colours = MaterialTheme.colorScheme
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = colours.inverseSurface,
                shadowElevation = 2.dp,
                modifier = Modifier.testTag("tooltip"),
            ) {
                Text(
                    text,
                    fontSize = 11.sp,
                    color = colours.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        modifier = modifier,
        delayMillis = TOOLTIP_DELAY_MS,
        // Below the pointer rather than over the control, so the glyph
        // being asked about stays in sight.
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        content = content,
    )
}
