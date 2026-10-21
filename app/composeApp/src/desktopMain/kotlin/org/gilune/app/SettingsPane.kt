// Settings as a surface rather than a dialog, because it has to grow —
// Sync arrived here with ADR 0019 and took the Devices placeholder's
// slot, and its screen lives in `SyncSettings.kt`. Plugins follow the
// Obsidian shape the
// owner asked for (2026-08-31): a manager section with switches, and one
// nav entry per installed plugin opening that plugin's own screen —
// permissions, events, contributions, and the remove button live there
// (ADR 0011).

package org.gilune.app

import org.gilune.plugins.tasks.TASKS_PLUGIN
import org.gilune.plugins.tasks.tasksFilter
import org.gilune.plugins.tasks.tasksPanelEnabled
import org.gilune.plugins.tasks.tasksSettingsDocument
import org.gilune.plugins.tasks.DEFAULT_TASK_SHORTCUT
import org.gilune.plugins.tasks.tasksShortcutKey
import org.gilune.plugins.tasks.tasksShortcutSetting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.kernelVersion
import javax.swing.JFileChooser

private enum class Section(val label: String) {
    General("General"),
    Vault("Vault"),
    Plugins("Plugins"),
    Sync("Sync"),
    Appearance("Appearance"),
    About("About"),
}

private sealed interface Pane {
    data class Fixed(val section: Section) : Pane
    data class Plugin(val pluginId: String) : Pane
}

private fun initialPane(initialSection: String?): Pane = when {
    initialSection == null -> Pane.Fixed(Section.General)
    initialSection.startsWith("plugin:") -> Pane.Plugin(initialSection.removePrefix("plugin:"))
    else ->
        Pane.Fixed(
            Section.entries.firstOrNull { it.label.equals(initialSection, ignoreCase = true) }
                ?: Section.Vault,
        )
}

@Composable
fun SettingsPane(
    state: GiluneState,
    vaultDir: String,
    onVaultChange: (String) -> Unit,
    theme: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    onClose: () -> Unit,
    initialSection: String? = null,
    updater: Updater? = null,
) {
    var pane by remember { mutableStateOf(initialPane(initialSection)) }
    val colours = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 72.dp, vertical = 48.dp)
                // Consume clicks so a click inside settings never reaches
                // the scrim and closes the pane out from under the user.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
            shape = RoundedCornerShape(10.dp),
            color = colours.surface,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = colours.onSurfaceVariant,
                    )
                    Text("Settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Tooltip("Close settings (Esc)") {
                        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "close settings",
                                modifier = Modifier.size(16.dp),
                                tint = colours.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(color = colours.outlineVariant)
                Row(Modifier.fillMaxSize()) {
                    SettingsNav(state, pane, Modifier.width(170.dp).fillMaxHeight()) { pane = it }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colours.outlineVariant))
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (val open = pane) {
                            is Pane.Fixed -> when (open.section) {
                                Section.General -> GeneralSettings()
                                Section.Vault -> VaultSection(state, vaultDir, onVaultChange)
                                Section.Plugins -> PluginsManager(state) { pane = Pane.Plugin(it) }
                                Section.Sync -> SyncSection(state)
                                Section.Appearance -> AppearanceSection(theme, onThemeChange)
                                Section.About -> AboutSection(state, vaultDir, updater)
                            }
                            is Pane.Plugin -> {
                                val plugin = state.plugins.firstOrNull { it.pluginId == open.pluginId }
                                if (plugin == null) {
                                    PluginsManager(state) { pane = Pane.Plugin(it) }
                                } else {
                                    PluginScreen(state, plugin)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNav(
    state: GiluneState,
    selected: Pane,
    modifier: Modifier = Modifier,
    onSelect: (Pane) -> Unit,
) {
    val colours = MaterialTheme.colorScheme

    @Composable
    fun navRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
        Text(
            label,
            fontSize = 13.sp,
            maxLines = 1,
            color = if (isSelected) colours.onPrimaryContainer else colours.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(if (isSelected) colours.primaryContainer else colours.surface)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
        for (entry in Section.entries) {
            navRow(entry.label, selected == Pane.Fixed(entry)) { onSelect(Pane.Fixed(entry)) }
        }
        if (state.plugins.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "installed plugins",
                fontSize = 11.sp,
                color = colours.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
            for (plugin in state.plugins) {
                navRow(plugin.name, selected == Pane.Plugin(plugin.pluginId)) {
                    onSelect(Pane.Plugin(plugin.pluginId))
                }
            }
        }
    }
}

/// The manager: what is installed, on or off, at a glance. Everything
/// deeper — permissions, events, removal — lives on the plugin's own
/// screen, reached from the nav or by clicking the row.
@Composable
private fun PluginsManager(state: GiluneState, onOpenPlugin: (String) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Text("Installed plugins", style = MaterialTheme.typography.titleSmall)
    for (plugin in state.plugins) {
        val on = plugin.state == "enabled"
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colours.surfaceVariant)
                .clickable { onOpenPlugin(plugin.pluginId) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                    Chip("${plugin.`class`} · ${plugin.profile}")
                }
                if (plugin.ungranted.isNotEmpty()) {
                    Text(
                        "needs your permission — open to review",
                        fontSize = 11.sp,
                        color = colours.error,
                    )
                }
            }
            if (plugin.required) {
                Text("required", fontSize = 11.sp, color = colours.onSurfaceVariant)
            } else {
                Switch(
                    checked = on,
                    onCheckedChange = { enable ->
                        setPluginSwitch(state, plugin, enable)
                    },
                )
            }
        }
    }
    HorizontalDivider(color = colours.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { state.backfill() }) { Text("Catch up missed captures", fontSize = 12.sp) }
        Text(
            "Processes captures made before a plugin was installed, or while it was off.",
            fontSize = 12.sp,
            color = colours.onSurfaceVariant,
        )
    }
    Text(
        "Installable plugins arrive with the sandboxed runtime (ADR 0010). " +
            "This build runs bundled plugins only.",
        fontSize = 12.sp,
        color = colours.onSurfaceVariant,
    )
}

/// One plugin's own screen: what it is, what it may do, what it listens
/// to, what it adds — and the switches that take any of it away.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginScreen(state: GiluneState, plugin: PluginInfo) {
    val colours = MaterialTheme.colorScheme
    val on = plugin.state == "enabled"

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(plugin.name, style = MaterialTheme.typography.titleMedium)
        Chip("${plugin.`class`} · ${plugin.profile}")
        Spacer(Modifier.weight(1f))
        if (plugin.required) {
            Text("required", fontSize = 12.sp, color = colours.onSurfaceVariant)
        } else {
            Switch(
                checked = on,
                onCheckedChange = { enable ->
                    setPluginSwitch(state, plugin, enable)
                },
            )
        }
    }

    PluginNavigationSettings(plugin)

    if (plugin.capabilities.isNotEmpty()) {
        Text("Permissions", style = MaterialTheme.typography.titleSmall)
        for (capability in plugin.capabilities) {
            val granted = capability in plugin.granted
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    capability,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (granted) {
                    Text("allowed", fontSize = 11.sp, color = colours.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        state.pluginAction("revoked $capability for ${plugin.name}") {
                            it.revokeCapability(plugin.pluginId, capability)
                        }
                    }) { Text("Revoke", fontSize = 11.sp) }
                } else {
                    Text("not allowed", fontSize = 11.sp, color = colours.error)
                    Button(onClick = {
                        state.pluginAction("allowed $capability for ${plugin.name}") {
                            it.grantCapability(plugin.pluginId, capability)
                        }
                    }) { Text("Allow", fontSize = 11.sp) }
                }
            }
        }
    }

    val events = buildList {
        plugin.subscribes?.let { add("listens to $it") }
        plugin.emits.forEach { add("emits $it") }
    }
    if (events.isNotEmpty()) {
        Text("Events", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (event in events) Chip(event, mono = true, tint = colours.surfaceVariant)
        }
    }

    if (plugin.contributions.isNotEmpty()) {
        Text("Adds to the app", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (contribution in plugin.contributions) {
                Chip("${contribution.slot} · ${contribution.label}", tint = colours.surfaceVariant)
            }
        }
    }

    if (plugin.pluginId == TRANSCRIPTION_PLUGIN) {
        HorizontalDivider(color = colours.outlineVariant)
        TranscriptionSettings(state, plugin)
    }

    if (plugin.pluginId == NEMO_PLUGIN) {
        HorizontalDivider(color = colours.outlineVariant)
        NemoSettings(state, plugin)
    }

    if (plugin.pluginId == TASKS_PLUGIN) {
        HorizontalDivider(color = colours.outlineVariant)
        TasksSettings(state, plugin)
    }

    if (plugin.pluginId == org.gilune.plugins.people.PEOPLE_PLUGIN) {
        HorizontalDivider(color = colours.outlineVariant)
        PeopleSettings(state, plugin)
    }

    if (!plugin.required) {
        HorizontalDivider(color = colours.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                state.pluginAction("${plugin.name} removed") { it.removePlugin(plugin.pluginId) }
            }) { Text("Remove ${plugin.name}", fontSize = 12.sp) }
            Text(
                "Settings and permissions go; everything it created stays in your vault.",
                fontSize = 12.sp,
                color = colours.onSurfaceVariant,
            )
        }
    }
}

/// The transcription provider's settings form. Bundled plugins render
/// known fields directly; the generic schema-driven form arrives with
/// the sandboxed runtime (ADR 0010).
///
/// The language and the model are keys of one settings document, so every
/// write here sends both (`transcriptionSettingsDocument`) on the tasks
/// precedent: changing one must never drop the other.
@Composable
private fun TranscriptionSettings(state: GiluneState, plugin: PluginInfo) {
    val colours = MaterialTheme.colorScheme
    val saved = remember(plugin.settings) { listOf(plugin).transcriptionLanguage() }
    val savedModel = remember(plugin.settings) { listOf(plugin).transcriptionModel() }
    var language by remember(plugin.settings) { mutableStateOf(saved) }
    Text("Spoken language", style = MaterialTheme.typography.titleSmall)
    Text(
        "\"auto\" detects the language but costs an extra pass (~40% slower on " +
            "short clips). A fixed code — en, sv, de… — transcribes faster.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = language,
            onValueChange = { language = it },
            singleLine = true,
            modifier = Modifier.width(120.dp),
        )
        Button(
            onClick = {
                val wanted = language.trim()
                // The model travels with the write, so setting a language
                // cannot quietly revert the model somebody chose.
                state.pluginAction("transcription language set to $wanted") {
                    it.setPluginSettings(
                        plugin.pluginId,
                        transcriptionSettingsDocument(wanted, savedModel.key, listOf(plugin).transcriptionLive()),
                    )
                }
            },
            enabled = language.trim().isNotEmpty() && language.trim() != saved,
        ) { Text("Apply", fontSize = 12.sp) }
    }

    HorizontalDivider(color = colours.outlineVariant)
    Text("Transcription model", style = MaterialTheme.typography.titleSmall)
    Text(
        "Bigger models hear better and take longer. Measured on this machine " +
            "over a 21-second clip: tiny-q5_1 0.6 s, tiny 0.6 s, base-q5_1 " +
            "1.0 s, base 1.2 s — and the base-class models are the ones that " +
            "get trade words, £ amounts and names right. A quantised model " +
            "(q5_1) transcribes the same words as its full sibling from a " +
            "smaller download, which is why base-q5_1 is the recommendation.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    for (option in WHISPER_MODELS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = option.key == savedModel.key,
                // The language travels with the write for the same reason
                // the model does above — and the stored one, not whatever
                // is half-typed in the field.
                onClick = {
                    state.pluginAction("transcription model set to ${option.key}") {
                        it.setPluginSettings(
                            plugin.pluginId,
                            transcriptionSettingsDocument(saved, option.key, listOf(plugin).transcriptionLive()),
                        )
                    }
                },
            )
            Text(option.label, fontSize = 13.sp)
        }
    }
    Text(
        "A model you have not used before is downloaded when you choose it " +
            "(one time, with the network permission above). Models already " +
            "downloaded are kept, so switching back is instant.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )

    HorizontalDivider(color = colours.outlineVariant)
    Text("Dictation", style = MaterialTheme.typography.titleSmall)
    Text(
        "With this on, dictated words appear in the note as you speak: words " +
            "Whisper may still change are shown grey and are not saved until " +
            "they settle. Whisper re-reads the last few seconds every second, so " +
            "it needs a reasonably fast processor; if it cannot keep up, the " +
            "words arrive when you stop instead, and the status line says so. " +
            "Off, the words always arrive when you stop.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    val live = remember(plugin.settings) { listOf(plugin).transcriptionLive() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(
            checked = live,
            // The language and model travel with the write, so this switch
            // cannot quietly reset either.
            onCheckedChange = { on ->
                state.pluginAction(
                    if (on) "dictated words now appear as you speak" else "dictated words now arrive when you stop",
                ) { it.setPluginSettings(plugin.pluginId, transcriptionSettingsDocument(saved, savedModel.key, on)) }
            },
            modifier = Modifier.testTag("transcription-live"),
        )
        Text("Show words as I dictate", fontSize = 13.sp)
    }
}

/// The tasks plugin's settings: § 1's global filter, the one setting the
/// recognisers, the `/` menu, the panel and queries all read, so the
/// vault can never disagree with itself about what a task is — and § 6's
/// switch over the agenda panel itself.
///
/// All three — and the task shortcut below them — are keys of one settings
/// document, so every write here sends every one (`tasksSettingsDocument`):
/// changing one must never drop another.
@Composable
private fun TasksSettings(state: GiluneState, plugin: PluginInfo) {
    val colours = MaterialTheme.colorScheme
    val saved = remember(plugin.settings) { listOf(plugin).tasksFilter() }
    val panelOn = remember(plugin.settings) { listOf(plugin).tasksPanelEnabled() }
    val shortcut = remember(plugin.settings) { tasksShortcutSetting(plugin.settings) }
    var filter by remember(plugin.settings) { mutableStateOf(saved) }
    Text("Global filter", style = MaterialTheme.typography.titleSmall)
    Text(
        "Which checkboxes count as tasks. Empty — the default — means every " +
            "checklist item is one. Set it to something like #task and only " +
            "checkbox lines carrying that text get the / menu and coloured dates. " +
            "Nothing in your vault is rewritten either way, and the change takes " +
            "effect as soon as you apply it.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            placeholder = {
                Text("empty — every checkbox is a task", fontSize = 12.sp, color = colours.onSurfaceVariant)
            },
            modifier = Modifier.width(300.dp),
        )
        Button(
            onClick = {
                val wanted = filter.trim()
                state.pluginAction(
                    if (wanted.isEmpty()) {
                        "every checkbox is a task again"
                    } else {
                        "only checkbox lines containing $wanted are tasks"
                    },
                ) { it.setPluginSettings(plugin.pluginId, tasksSettingsDocument(wanted, panelOn, shortcut)) }
            },
            // An empty filter is a legitimate value — the shipped default
            // — so only "no change" disables the button.
            enabled = filter.trim() != saved,
        ) { Text("Apply", fontSize = 12.sp) }
    }

    HorizontalDivider(color = colours.outlineVariant)
    Text("Agenda panel", style = MaterialTheme.typography.titleSmall)
    Text(
        "A tab in the rail listing what is open: overdue, today, the next " +
            "seven days, and tasks with no date. Switched off, the tab is not " +
            "added at all — the plugin keeps styling dates and offering the " +
            "/ menu. Nothing in your vault changes either way.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(
            checked = panelOn,
            // The filter travels with the write, so toggling the panel
            // cannot quietly reset what a person set it to.
            onCheckedChange = { show ->
                state.pluginAction(
                    if (show) "the agenda panel is in the rail" else "the agenda panel is off",
                ) { it.setPluginSettings(plugin.pluginId, tasksSettingsDocument(saved, show, shortcut)) }
            },
        )
        Text("Show the agenda panel", fontSize = 13.sp)
    }

    HorizontalDivider(color = colours.outlineVariant)
    Text("Task shortcut", style = MaterialTheme.typography.titleSmall)
    Text(
        "Turns the line the cursor is on into a task, or ticks the task it " +
            "already is — the same as clicking its checkbox. " +
            "Press Change, then the key or combination you want; Esc cancels.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    // The filter and the panel switch travel with the write, as above.
    KeystrokeSetting(tasksShortcutKey(plugin.settings), DEFAULT_TASK_SHORTCUT, "task-shortcut-key") { key ->
        state.pluginAction("the task shortcut is now ${key ?: DEFAULT_TASK_SHORTCUT}") {
            it.setPluginSettings(plugin.pluginId, tasksSettingsDocument(saved, panelOn, key))
        }
    }
}

/// The people plugin's settings: the keystroke for go to definition. The
/// plugin owns the binding and its default (F12); this screen records the
/// next keystroke pressed, spelled the way the editor matches it.
@Composable
private fun PeopleSettings(state: GiluneState, plugin: PluginInfo) {
    val colours = MaterialTheme.colorScheme
    val current = remember(plugin.settings) { org.gilune.plugins.people.peopleDefinitionKey(plugin.settings) }
    Text("Go to definition", style = MaterialTheme.typography.titleSmall)
    Text(
        "With the cursor on an @mention, this key opens that person's page. " +
            "Press Change, then the key or combination you want; Esc cancels.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    KeystrokeSetting(current, org.gilune.plugins.people.DEFAULT_DEFINITION_KEY, "definition-key") { key ->
        state.pluginAction(
            "go to definition is now ${key ?: org.gilune.plugins.people.DEFAULT_DEFINITION_KEY}",
        ) { it.setPluginSettings(plugin.pluginId, org.gilune.plugins.people.peopleSettingsDocument(plugin.settings, key)) }
    }
}

/// A plugin-owned keystroke: the binding as it stands, Change to record the
/// next keystroke pressed (spelled the way the editor matches it, Esc
/// cancels), and Reset back to the plugin's default — `save(null)`.
@Composable
private fun KeystrokeSetting(current: String, default: String, tag: String, save: (String?) -> Unit) {
    val colours = MaterialTheme.colorScheme
    var listening by remember { mutableStateOf(false) }
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .width(180.dp)
                .border(1.dp, if (listening) colours.primary else colours.outline, RoundedCornerShape(4.dp))
                .focusRequester(field)
                .focusable()
                .onKeyEvent { event ->
                    if (!listening || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val pressed = keystrokeOf(event) ?: return@onKeyEvent true // a modifier on its way to a chord
                    listening = false
                    if (pressed != "Escape") save(pressed)
                    true
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag(tag),
        ) {
            Text(if (listening) "Press a key…" else current, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        OutlinedButton(onClick = { listening = true; runCatching { field.requestFocus() } }) { Text("Change", fontSize = 12.sp) }
        TextButton(
            onClick = { listening = false; save(null) },
            enabled = current != default,
        ) { Text("Reset to $default", fontSize = 12.sp) }
    }
}

@Composable
private fun Chip(text: String, tint: Color? = null, mono: Boolean = false) {
    Text(
        text,
        fontSize = 11.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint ?: MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun VaultSection(state: GiluneState, vaultDir: String, onVaultChange: (String) -> Unit) {
    Text("Vault folder", style = MaterialTheme.typography.titleSmall)
    Text(
        vaultDir,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val chooser = JFileChooser(vaultDir).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Choose a vault folder"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath?.let(onVaultChange)
            }
        }) { Text("Choose…") }
        OutlinedButton(onClick = { state.sync() }) { Text("Sync now") }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("This vault", style = MaterialTheme.typography.titleSmall)
    val gilune = state.gilune.value
    if (gilune == null) {
        Text("not open", fontSize = 12.sp)
    } else {
        val status = remember(state.refreshCount.value) { runCatching { gilune.status() }.getOrNull() }
        Text(
            buildString {
                appendLine("schema version: ${runCatching { gilune.schemaVersion() }.getOrNull()}")
                appendLine("device: ${runCatching { gilune.deviceId() }.getOrNull()}")
                if (status != null) {
                    appendLine("captures: ${status.sources}")
                    appendLine("projected notes: ${status.projections}")
                    // A job belonging to a switched-off plugin is parked
                    // rather than pending work (ADR 0018 § 3), and a bare
                    // number reads as a backlog somebody has to clear.
                    val parked = waitingJobsNote(state.plugins, status.jobsPending, status.jobsParked)
                    appendLine("waiting jobs: ${status.jobsPending}${parked?.let { " ($it)" } ?: ""}")
                    appendLine("quarantined jobs: ${status.jobsQuarantined}")
                    appendLine("open proposals: ${status.proposalsOpen}")
                    appendLine("quarantined files: ${status.quarantinedFiles}")
                }
            }.trim(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Sync lives in `SyncSettings.kt`. It replaced the Devices placeholder
// that stood here and promised it (ADR 0019 § M5b).

@Composable
private fun AppearanceSection(theme: ThemeChoice, onThemeChange: (ThemeChoice) -> Unit) {
    Text("Theme", style = MaterialTheme.typography.titleSmall)
    for (choice in ThemeChoice.entries) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = choice == theme, onClick = { onThemeChange(choice) })
            Text(choice.name, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun PluginNavigationSettings(plugin: PluginInfo, navigation: NavigationVisibility = Prefs.navigation) {
    val panels = navigationPanels(listOf(plugin.copy(state = "enabled")))
    if (panels.isEmpty()) return
    Text("Navigation", style = MaterialTheme.typography.titleSmall)
    for (panel in panels) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show ${panel.label} in the sidebar", Modifier.weight(1f), fontSize = 12.sp)
            Switch(checked = panel.key !in navigation.hidden,
                onCheckedChange = { navigation.setVisible(panel.key, it) })
        }
    }
}

@Composable
private fun AboutSection(state: GiluneState, vaultDir: String, updater: Updater?) {
    Text("Gilune", style = MaterialTheme.typography.titleSmall)
    Text(
        buildString {
            appendLine("app $APP_VERSION")
            appendLine("kernel ${runCatching { kernelVersion() }.getOrDefault("unknown")}")
            appendLine("vault $vaultDir")
            appendLine(state.message.value)
        }.trim(),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (updater != null) UpdatesSection(updater)
}

/// A plugin's switch. Switching a transcriber on switches any other one
/// off — one transcriber writes your notes (ADR 0018 § 3) — and the line
/// says so; every other plugin just switches.
private fun setPluginSwitch(state: GiluneState, plugin: PluginInfo, enable: Boolean) {
    val switch = providerSwitch(state.plugins.toList(), plugin.pluginId, enable)
    state.pluginAction(switch.message) { g ->
        for (other in switch.disable) g.setPluginEnabled(other, false)
        g.setPluginEnabled(plugin.pluginId, enable)
    }
}

/// Transcription (NVIDIA NeMo)'s screen (ADR 0027 S3): which model, what
/// each one is for, its licence, and whether words show as you dictate.
/// The model and the switch are one settings document, so every write here
/// sends both.
@Composable
private fun NemoSettings(state: GiluneState, plugin: PluginInfo) {
    val colours = MaterialTheme.colorScheme
    val chosen = remember(plugin.settings) { listOf(plugin).nemoModel() }
    val live = remember(plugin.settings) { listOf(plugin).nemoLive() }
    providerSwitchWarning(state.plugins.toList(), plugin.pluginId)?.takeIf { plugin.state != "enabled" }?.let {
        Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = colours.onSurfaceVariant)
    }
    Text("Model", style = MaterialTheme.typography.titleSmall)
    Text(
        "NVIDIA's speech models, run on this computer. A model is downloaded the first time " +
            "it is used, once you have allowed network access above, and checked against its " +
            "published checksum before it is used.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    for (option in NEMO_MODELS) {
        val present = remember(option, state.vaultCursor.value) { nemoModelPresent(option) }
        Row(verticalAlignment = Alignment.Top) {
            RadioButton(
                selected = option.key == chosen.key,
                onClick = {
                    state.pluginAction("${plugin.name} will use ${option.name}") {
                        it.setPluginSettings(plugin.pluginId, nemoSettingsDocument(option.key, live))
                    }
                },
                modifier = Modifier.testTag("nemo-model-${option.key}"),
            )
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    option.name + if (present) " — downloaded" else " — ${option.size} download",
                    fontSize = 13.sp,
                )
                Text(option.guidance, fontSize = 12.sp, lineHeight = 17.sp, color = colours.onSurfaceVariant)
                Text("Licence: ${option.licence}", fontSize = 11.sp, color = colours.onSurfaceVariant)
            }
        }
    }

    HorizontalDivider(color = colours.outlineVariant)
    Text("Dictation", style = MaterialTheme.typography.titleSmall)
    Text(
        if (chosen.kind == NemoKind.STREAMING) {
            "With this on, dictated words appear in the note as you speak: words the model may " +
                "still change are shown grey and are not saved until they settle at a pause. " +
                "Off, the words arrive when you stop."
        } else {
            "${chosen.name} hears a recording once it has finished, so dictated words always " +
                "arrive when you stop. Choose a live model above to see words as you speak."
        },
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(
            checked = live && chosen.kind == NemoKind.STREAMING,
            enabled = chosen.kind == NemoKind.STREAMING,
            onCheckedChange = { on ->
                state.pluginAction(
                    if (on) "dictated words now appear as you speak" else "dictated words now arrive when you stop",
                ) { it.setPluginSettings(plugin.pluginId, nemoSettingsDocument(chosen.key, on)) }
            },
            modifier = Modifier.testTag("nemo-live"),
        )
        Text("Show words as I dictate", fontSize = 13.sp)
    }
}
