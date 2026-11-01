package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import org.gilune.app.icons.CheckBox
import org.gilune.app.icons.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.gilune.plugins.tasks.TaskHit

@Composable
internal fun TaskResultCheckbox(hit: TaskHit, onToggle: (TaskHit) -> Unit, modifier: Modifier = Modifier) {
    val line = hit.checkboxLine
    val marker = checkboxMarkerAt(line, line.indexOf('['))
    val checked = marker != null && line[marker.first + 1] != ' '
    Box(
        modifier.size(22.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(hit) })
            .semantics { contentDescription = "${if (checked) "Reopen" else "Complete"} task: ${taskLabel(hit.text)}" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}
