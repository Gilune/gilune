// Per-mode note typography controls and previews. Preferences affect rendering
// immediately; note content remains owned by its editor.
package org.gilune.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun GeneralSettings() {
    val appearance = LocalNoteAppearance.current
    var reading by remember { mutableStateOf(false) }
    val mode = if (reading) "Presentation" else "Edit"
    val settings = if (reading) appearance.reading else appearance.edit
    Text("Note text", style = MaterialTheme.typography.titleMedium)
    Text("Changes apply immediately and are saved on this device.", style = MaterialTheme.typography.bodySmall)
    TabRow(selectedTabIndex = if (reading) 1 else 0) {
        Tab(selected = !reading, onClick = { reading = false }, text = { Text("Edit") })
        Tab(selected = reading, onClick = { reading = true }, text = { Text("Presentation") })
    }
    var fontsOpen by remember { mutableStateOf(false) }
    Text("Font", style = MaterialTheme.typography.titleSmall)
    Box {
        OutlinedButton(onClick = { fontsOpen = true }, modifier = Modifier.semantics { contentDescription = "$mode font" }) {
            Text(settings.font.label)
        }
        DropdownMenu(expanded = fontsOpen, onDismissRequest = { fontsOpen = false }) {
            NoteFont.entries.forEach { font ->
                DropdownMenuItem(text = { Text(font.label, fontFamily = font.family) }, onClick = {
                    appearance.update(reading, settings.copy(font = font)); fontsOpen = false
                })
            }
        }
    }
    NumberSlider("Font size", "$mode font size", settings.size, 10..32) {
        appearance.update(reading, settings.copy(size = it))
    }
    NumberSlider("Line height", "$mode line height", settings.lineHeight, settings.size..96) {
        appearance.update(reading, settings.copy(lineHeight = it))
    }
    if (reading) NumberSlider("Space between blocks", "Read block spacing", settings.blockSpacing, 0..32) {
        appearance.update(true, settings.copy(blockSpacing = it))
    }
    TextButton(onClick = { appearance.update(reading, if (reading) NoteTypeSettings.Read else NoteTypeSettings.Edit) }) {
        Text("Reset $mode defaults")
    }
    Text("Preview", style = MaterialTheme.typography.titleSmall)
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        if (reading) {
            MarkdownBody(parseMarkdown("## A note to remember\n\nA little room to think.\n\n- One thing at a time"),
                Modifier.padding(12.dp).testTag("read-font-preview"), textStyle = settings.textStyle, blockSpacing = settings.blockSpacing.dp)
        } else {
            Text("# A note to remember\nA little room to think.\n- [ ] One thing at a time", style = settings.textStyle,
                modifier = Modifier.padding(12.dp).testTag("edit-font-preview"))
        }
    }
}

@Composable
private fun NumberSlider(label: String, description: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: $value", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(160.dp))
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f).semantics { contentDescription = description })
    }
}
