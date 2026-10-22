// A switch small enough to sit at the end of a tree row.
//
// Material's own `Switch` is 52×32 dp by contract and does not scale down
// without its layout box, so a row that wanted one would grow to fit it.
// This draws the same idea — a track, a thumb at one end or the other —
// at a size that fits a 13 sp line, with the theme's colours, and
// nothing else: no ripple, no animation, one click.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/// On means the row syncs; off means it is kept on this device. A
/// disabled switch shows a state the row inherits and cannot change here.
@Composable
internal fun MiniSwitch(
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = MaterialTheme.colorScheme
    val track = if (checked) colours.primary else colours.outlineVariant
    val thumb = if (checked) colours.onPrimary else colours.surface
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .size(width = 24.dp, height = 13.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(thumb))
    }
}
