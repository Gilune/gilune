// A page type's property sheet, drawn (owner, 2026-10-26: "we need a way to
// show information on specific page types … making some of the data in that
// section editable"). Shared by the desktop's properties section and the
// phone's Properties window.
//
// The sheet's values are read from the page's own front matter; every edit
// is handed back as one PropertyChange, which the surface applies to the
// buffer it holds through the lossless front-matter edit and saves. Nothing
// here touches a file.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gilune.plugin.sdk.PageChoiceOption
import org.gilune.plugin.sdk.PageProperty
import org.gilune.plugin.sdk.PagePropertySheet
import org.gilune.plugin.sdk.pageJsonString
import uniffi.gilune_ffi.PropertyChange
import uniffi.gilune_ffi.changeSourceProperties
import uniffi.gilune_ffi.inspectSourceProperties

/// The value each of a sheet's keys holds in `source`, as text: a string's
/// own words, anything else its YAML (`true`, `false`). Absent keys are
/// absent. Null when the header cannot be read — the sheet then draws
/// nothing rather than guess.
fun sheetValues(source: String): Map<String, String>? = runCatching {
    inspectSourceProperties(source).fields.associate { it.key to (it.stringValue ?: it.valueYaml) }
}.getOrNull()

/// The change one edit makes, as the front matter will hold it.
fun pagePropertyChange(property: PageProperty, value: String?): PropertyChange = when (property) {
    is PageProperty.Switch -> PropertyChange(property.key, if (value == "true") "true" else "false")
    else -> PropertyChange(property.key, value?.let(::pageJsonString))
}

/// `source` with one change applied, through the kernel's lossless edit.
fun sourceWithChange(source: String, change: PropertyChange): String =
    changeSourceProperties(source, inspectSourceProperties(source).contentVersion, listOf(change)).text

/// One row of a sheet, whatever it holds.
private val SHEET_ROW = 32.dp

/// The sheet's rows. `onChange` applies an edit; `onRename` opens the
/// page's Rename references tool, or is null where the surface has none.
@Composable
fun PagePropertySheetView(
    sheet: PagePropertySheet,
    source: String,
    onChange: (PropertyChange) -> Unit,
    onRename: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val values = remember(source) { sheetValues(source) } ?: return
    Column(modifier.testTag("page-sheet-${sheet.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (property in sheet.properties) {
            // Every row is one height whatever its control, so the sheet
            // reads as an even list (owner, 2026-10-26: the switch and the
            // Rename button had each brought their own). Only a text row
            // may grow, to say why a value was refused.
            val height = if (property is PageProperty.Text) Modifier.heightIn(min = SHEET_ROW) else Modifier.height(SHEET_ROW)
            Row(height.testTag("page-row-${property.key}"), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    property.label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(96.dp),
                )
                val value = values[property.key]
                val tag = "page-property-${property.key}"
                when (property) {
                    is PageProperty.Text -> TextValue(property, value.orEmpty(), tag) { onChange(pagePropertyChange(property, it)) }
                    // Scaling a switch does not shrink the space it takes,
                    // so the row's height holds it to the others'.
                    is PageProperty.Switch -> Box(Modifier.height(SHEET_ROW), contentAlignment = Alignment.CenterStart) {
                        Switch(
                            checked = value == "true",
                            onCheckedChange = { onChange(pagePropertyChange(property, it.toString())) },
                            modifier = Modifier.scale(0.7f).testTag(tag),
                        )
                    }
                    is PageProperty.Choice -> ChoiceValue(property, value, tag) { option ->
                        onChange(pagePropertyChange(property, option.value))
                    }
                    is PageProperty.ReadOnly -> {
                        Text(value.orEmpty(), fontSize = 13.sp, modifier = Modifier.testTag(tag))
                        // A link, as "All properties" is: a button would
                        // bring its own taller touch area into the row.
                        if (property.renameReferences && onRename != null) {
                            Text(
                                "Rename…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .clickable(onClickLabel = "Rename references", onClick = onRename)
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                                    .testTag("$tag-rename"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/// Free text, committed on Enter or when the field loses focus — never per
/// keystroke, so typing a label is one edit, not one per letter. A required
/// value left blank goes back to what the page says.
@Composable
private fun TextValue(property: PageProperty.Text, saved: String, tag: String, onCommit: (String) -> Unit) {
    var text by remember(saved) { mutableStateOf(saved) }
    var problem by remember(saved) { mutableStateOf<String?>(null) }
    fun commit() {
        val wanted = text.trim()
        if (wanted == saved) return
        if (property.required && wanted.isEmpty()) {
            problem = "${property.label} can't be empty."
            text = saved
            return
        }
        problem = null
        onCommit(wanted)
    }
    Column {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(220.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .onFocusChanged { if (!it.isFocused) commit() }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { commit(); true } else false
                }
                .testTag(tag),
        )
        problem?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
    }
}

/// The current option as a swatch and its name; a click offers them all.
@Composable
private fun ChoiceValue(property: PageProperty.Choice, value: String?, tag: String, onChoose: (PageChoiceOption) -> Unit) {
    val tones = pillToneColours()
    val current = property.options.firstOrNull { it.value == value } ?: property.options.first()
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clickable(onClickLabel = property.label) { open = true }
                .semantics { contentDescription = "${property.label}: ${current.label}" }
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Dot(tones.getValue(current.tone).tag, 14.dp)
            Text(current.label, fontSize = 13.sp)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in property.options) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Dot(tones.getValue(option.tone).tag, 14.dp) },
                    trailingIcon = { if (option == current) Icon(Icons.Default.Check, contentDescription = "current") },
                    onClick = { open = false; if (option != current) onChoose(option) },
                    modifier = Modifier.testTag("$tag:${option.value ?: "default"}"),
                )
            }
        }
    }
}

@Composable
private fun Dot(fill: Color, size: Dp) {
    Box(
        Modifier
            .size(size)
            .background(fill, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}
