// A task's priority where a task row is drawn (ADR 0015 amendment
// 2026-10-23): one shape for the desktop panel, the filter blocks and the
// mobile screens, so a row says the same thing wherever it is listed.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.gilune.plugins.tasks.TaskPriority

/// A thin bar at the row's edge: the error colour for high, the outline
/// for low, nothing at all for the ordinary level — the absence is the
/// common case and draws no attention.
@Composable
fun TaskPriorityMark(priority: TaskPriority?, modifier: Modifier = Modifier) {
    val level = priority ?: return
    val colour = priorityColour(level)
    Box(
        modifier
            .width(3.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colour)
            .semantics { contentDescription = "${level.label.lowercase()} priority" }
            .testTag("task-priority:${level.token}"),
    )
}

@Composable
fun priorityColour(level: TaskPriority): Color = when (level) {
    TaskPriority.HIGH -> MaterialTheme.colorScheme.error
    TaskPriority.LOW -> MaterialTheme.colorScheme.outline
}
