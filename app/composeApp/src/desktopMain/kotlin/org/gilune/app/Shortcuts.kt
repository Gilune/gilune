// Every keyboard shortcut the desktop answers, in one place (owner ask,
// 2026-10-27: "a shortcuts window you can open to see all the keyboard
// shortcuts, from the system and all plugins, in one place").
//
// The formatting actions are data here rather than branches in the
// editor's key handler, and the handler, the editor's right-click menu and
// the shortcuts window all read this one list — so a key cannot be bound
// in one and named differently in another. The window's other rows name
// keys the window and editor handle in code (Main.kt, NoteEditor.kt);
// plugin rows are read from each enabled plugin's own setting, so a key
// someone rebinds shows as they bound it.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gilune.plugins.findreplace.FIND_REPLACE_PLUGIN
import org.gilune.plugins.findreplace.FindReplaceKey
import org.gilune.plugins.findreplace.findReplaceKey
import org.gilune.plugins.tasks.TASKS_PLUGIN
import org.gilune.plugins.tasks.tasksShortcut
import org.gilune.plugins.tasks.tasksDateShortcut
import uniffi.gilune_ffi.PluginInfo

/// One formatting action of the desktop editor: what the menu and the
/// window call it, the keystroke that runs it (spelt as [keystrokeOf]
/// spells it, so it is matched exactly), and the shared edit it makes —
/// the phone toolbar's own, so a note formats the same from either.
/// `startsGroup` draws a rule above it in the menu.
internal class FormatAction(
    val label: String,
    val keys: String,
    val startsGroup: Boolean = false,
    val edit: (String, TextRange) -> BufferEdit?,
)

/// Bold to code on Obsidian's keys; headings and lists on the word
/// processors' Ctrl+Shift+digit (Ctrl+digit alone selects a tab, and
/// Ctrl+Alt is AltGr on many keyboards, where it types ² and ³).
internal val FORMAT_ACTIONS: List<FormatAction> = listOf(
    FormatAction("Bold", "Ctrl+B") { text, selection -> emphasisEdit(text, selection, "**") },
    FormatAction("Italic", "Ctrl+I") { text, selection -> emphasisEdit(text, selection, "*") },
    FormatAction("Strikethrough", "Ctrl+Shift+X") { text, selection -> emphasisEdit(text, selection, "~~") },
    FormatAction("Inline code", "Ctrl+Shift+C") { text, selection -> emphasisEdit(text, selection, "`") },
    FormatAction("Link", "Ctrl+K") { text, selection -> linkEdit(text, selection) },
    FormatAction("Heading 1", "Ctrl+Shift+1", startsGroup = true) { text, selection -> headingEdit(text, selection, 1) },
    FormatAction("Heading 2", "Ctrl+Shift+2") { text, selection -> headingEdit(text, selection, 2) },
    FormatAction("Heading 3", "Ctrl+Shift+3") { text, selection -> headingEdit(text, selection, 3) },
    FormatAction("Bulleted list", "Ctrl+Shift+8") { text, selection -> bulletEdit(text, selection) },
    FormatAction("Numbered list", "Ctrl+Shift+7") { text, selection -> numberedEdit(text, selection) },
    FormatAction("Quote", "Ctrl+Shift+9") { text, selection -> quoteEdit(text, selection) },
)

/// The formatting action a keystroke names, or null.
internal fun formatActionFor(pressed: String): FormatAction? = FORMAT_ACTIONS.firstOrNull { keystrokeMatches(it.keys, pressed) }

internal data class Shortcut(val keys: String, val action: String)

internal data class ShortcutGroup(val title: String, val shortcuts: List<Shortcut>)

/// Everything the window lists, grouped. Plugin groups follow the shell's
/// and appear only while their plugin is switched on.
internal fun shortcutGroups(plugins: List<PluginInfo>): List<ShortcutGroup> = buildList {
    add(
        ShortcutGroup(
            "General",
            listOf(
                Shortcut("Ctrl+N", "New note"),
                Shortcut("Ctrl+P", "Open a note by name"),
                // Ctrl+F is find and replace's while it is on (ADR 0031 § 5).
                Shortcut(if (findReplaceOn(plugins)) "Ctrl+Shift+F" else "Ctrl+F", "Search the vault"),
                Shortcut("Ctrl+Shift+B", "Show or hide the sidebar"),
                Shortcut("Ctrl+E", "Read the note in front, or go back to editing"),
                Shortcut(SHORTCUTS_KEYS, "Keyboard shortcuts"),
                Shortcut("Esc", "Close settings, or leave reading"),
            ),
        ),
    )
    add(
        ShortcutGroup(
            "Tabs and panes",
            listOf(
                Shortcut("Ctrl+Tab", "Next tab"),
                Shortcut("Ctrl+Shift+Tab", "Previous tab"),
                Shortcut("Ctrl+1 … Ctrl+9", "Go to that tab"),
                Shortcut("Ctrl+W", "Close the tab"),
                Shortcut("Ctrl+\\", "Move the tab into a pane to the right"),
            ),
        ),
    )
    add(
        ShortcutGroup(
            "Editing",
            listOf(
                Shortcut("Enter", "On a list item: the next item (a number counts on)"),
                Shortcut("Enter twice", "Leave the list, or step out of a nested item"),
                Shortcut("Shift+Enter", "A new line under the same item"),
                Shortcut("Tab", "Nest list lines"),
                Shortcut("Shift+Tab", "Lift list lines out a level"),
                Shortcut("Ctrl+]", "Nest list lines"),
                Shortcut("Ctrl+[", "Lift list lines out a level"),
                Shortcut("Alt+Up", "Move the line up"),
                Shortcut("Alt+Down", "Move the line down"),
                Shortcut("Ctrl+Z", "Undo"),
                Shortcut("Ctrl+Shift+Z", "Redo"),
            ),
        ),
    )
    add(ShortcutGroup("Formatting", FORMAT_ACTIONS.map { Shortcut(it.keys, it.label) }))

    // Plugins' own keys, as each plugin's settings bind them today.
    val byPlugin = linkedMapOf<String, MutableList<Shortcut>>()
    if (plugins.any { it.pluginId == TASKS_PLUGIN && it.state == "enabled" }) {
        byPlugin.getOrPut(pluginName(plugins, TASKS_PLUGIN)) { mutableListOf() } +=
            Shortcut(plugins.tasksShortcut(), "Make the line a task, or tick it")
        byPlugin.getValue(pluginName(plugins, TASKS_PLUGIN)) +=
            Shortcut(plugins.tasksDateShortcut(), "Postpone the task's due date, or add one")
    }
    if (findReplaceOn(plugins)) {
        byPlugin.getOrPut(pluginName(plugins, FIND_REPLACE_PLUGIN)) { mutableListOf() } +=
            FindReplaceKey.entries.map { Shortcut(plugins.findReplaceKey(it), it.label) }
    }
    for (binding in BUNDLED_PLUGIN_EXTENSIONS.definitions(plugins)) {
        byPlugin.getOrPut(pluginName(plugins, binding.pluginId)) { mutableListOf() } +=
            Shortcut(binding.key, binding.registration.label)
    }
    for ((name, shortcuts) in byPlugin) add(ShortcutGroup(name, shortcuts))
}

private fun findReplaceOn(plugins: List<PluginInfo>) =
    plugins.any { it.pluginId == FIND_REPLACE_PLUGIN && it.state == "enabled" }

private fun pluginName(plugins: List<PluginInfo>, id: String): String =
    plugins.firstOrNull { it.pluginId == id }?.name?.takeIf { it.isNotBlank() } ?: id

/// The keystroke that opens the window, as the window names it (the
/// handler in Main.kt matches `Key.Slash`; AWT would spell it "Slash").
internal const val SHORTCUTS_KEYS = "Ctrl+/"

/// Whether a row answers a filter: every word of it in the keys or the
/// action, ignoring case.
internal fun Shortcut.matches(filter: String): Boolean =
    filter.split(' ').filter { it.isNotBlank() }.all { word ->
        keys.contains(word, ignoreCase = true) || action.contains(word, ignoreCase = true)
    }

/// The window: a filter over every group, the keys in a fixed column.
@Composable
internal fun ShortcutsDialog(plugins: List<PluginInfo>, onDismiss: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val groups = remember(plugins) { shortcutGroups(plugins) }
    var filter by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keyboard shortcuts") },
        text = {
            Column(Modifier.widthIn(min = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter, e.g. bold or ctrl+shift") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("shortcuts-filter"),
                )
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).testTag("shortcuts-list"),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    var shown = 0
                    for (group in groups) {
                        val rows = group.shortcuts.filter { it.matches(filter) }
                        if (rows.isEmpty()) continue
                        shown += rows.size
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                group.title,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = colours.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            for (row in rows) ShortcutRow(row)
                        }
                    }
                    if (shown == 0) {
                        Text(
                            "No shortcut matches “$filter”.",
                            fontSize = 13.sp,
                            color = colours.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/// One row; the row under the pointer is shaded so the eye can follow it
/// across the gap to its keys.
@Composable
private fun ShortcutRow(row: Shortcut) {
    val colours = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) colours.surfaceContainerHighest else Color.Transparent)
            .hoverable(interaction)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { if (hovered) stateDescription = "hovered" }
            .testTag("shortcut:${row.keys}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.action, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Text(
            row.keys,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = colours.onSurfaceVariant,
        )
    }
}
