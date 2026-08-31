// Settings as a surface rather than a dialog, because it has to grow:
// devices and sync land here next. The plugin section is the one that
// matters — it is where a person can see what each plugin may do, what
// it listens to, what it adds to the app, and take any of it away
// (ADR 0011).

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.kernelVersion
import javax.swing.JFileChooser

private enum class Section(val label: String) {
    Vault("Vault"),
    Plugins("Plugins"),
    Appearance("Appearance"),
    About("About"),
}

@Composable
fun SettingsPane(
    state: GiluneState,
    vaultDir: String,
    onVaultChange: (String) -> Unit,
    theme: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    onClose: () -> Unit,
) {
    var section by remember { mutableStateOf(Section.Vault) }
    val colours = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose)) {
        Surface(
            Modifier.fillMaxSize().padding(48.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(10.dp),
            color = colours.surface,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(150.dp).fillMaxHeight().padding(8.dp)) {
                        for (entry in Section.entries) {
                            val selected = entry == section
                            Text(
                                entry.label,
                                fontSize = 13.sp,
                                color = if (selected) colours.onPrimaryContainer else colours.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (selected) colours.primaryContainer else colours.surface)
                                    .clickable { section = entry }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colours.outlineVariant))
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (section) {
                            Section.Vault -> VaultSection(state, vaultDir, onVaultChange)
                            Section.Plugins -> PluginsSection(state)
                            Section.Appearance -> AppearanceSection(theme, onThemeChange)
                            Section.About -> AboutSection(state, vaultDir)
                        }
                    }
                }
            }
        }
    }
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
    HorizontalDivider()
    Text("This vault", style = MaterialTheme.typography.titleSmall)
    val gilune = state.gilune.value
    if (gilune == null) {
        Text("not open", fontSize = 12.sp)
    } else {
        val status = remember(state.vaultTick.value) { runCatching { gilune.status() }.getOrNull() }
        Text(
            buildString {
                appendLine("schema version: ${runCatching { gilune.schemaVersion() }.getOrNull()}")
                appendLine("device: ${runCatching { gilune.deviceId() }.getOrNull()}")
                if (status != null) {
                    appendLine("captures: ${status.sources}")
                    appendLine("projected notes: ${status.projections}")
                    appendLine("waiting jobs: ${status.jobsPending}")
                    appendLine("quarantined jobs: ${status.jobsQuarantined}")
                    appendLine("open proposals: ${status.proposalsOpen}")
                    appendLine("quarantined files: ${status.quarantinedFiles}")
                }
            }.trim(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PluginsSection(state: GiluneState) {
    Text("Installed plugins", style = MaterialTheme.typography.titleSmall)
    for (plugin in state.plugins) PluginCard(state, plugin)
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { state.backfill() }) { Text("Catch up missed captures") }
        Text(
            "Processes captures made before a plugin was installed, or while it was off.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "Installable plugins arrive with the sandboxed runtime (ADR 0010). " +
            "This build runs bundled plugins only.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCard(state: GiluneState, plugin: PluginInfo) {
    val colours = MaterialTheme.colorScheme
    var expanded by remember(plugin.pluginId) { mutableStateOf(false) }
    val on = plugin.state == "enabled"

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colours.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (expanded) "▾" else "▸",
                fontSize = 11.sp,
                color = colours.onSurfaceVariant,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            Text(plugin.name, style = MaterialTheme.typography.titleSmall)
            Chip("${plugin.`class`} · ${plugin.profile}")
            Box(Modifier.weight(1f))
            Text(
                if (plugin.required) "required" else plugin.state,
                fontSize = 11.sp,
                color = if (on) colours.primary else colours.onSurfaceVariant,
            )
        }

        if (plugin.ungranted.isNotEmpty()) {
            Text(
                "waiting for your permission: ${plugin.ungranted.joinToString(", ")}",
                fontSize = 12.sp,
                color = colours.error,
            )
        }

        if (expanded) {
            if (plugin.capabilities.isNotEmpty()) {
                Text("permissions", fontSize = 11.sp, color = colours.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (capability in plugin.capabilities) {
                        val granted = capability in plugin.granted
                        Chip(
                            "$capability${if (granted) "" else " — not allowed"}",
                            tint = if (granted) colours.surface else colours.errorContainer,
                        )
                    }
                }
            }
            val events = buildList {
                plugin.subscribes?.let { add("listens to $it") }
                plugin.emits.forEach { add("emits $it") }
            }
            if (events.isNotEmpty()) {
                Text("events", fontSize = 11.sp, color = colours.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (event in events) Chip(event, mono = true)
                }
            }
            if (plugin.contributions.isNotEmpty()) {
                Text("adds to the app", fontSize = 11.sp, color = colours.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (contribution in plugin.contributions) {
                        Chip("${contribution.slot} · ${contribution.label}")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (capability in plugin.ungranted) {
                OutlinedButton(onClick = {
                    state.pluginAction("allowed $capability for ${plugin.name}") {
                        it.grantCapability(plugin.pluginId, capability)
                    }
                }) { Text("Allow $capability", fontSize = 12.sp) }
            }
            if (!plugin.required) {
                OutlinedButton(onClick = {
                    state.pluginAction(if (on) "${plugin.name} switched off" else "${plugin.name} switched on") {
                        it.setPluginEnabled(plugin.pluginId, !on)
                    }
                }) { Text(if (on) "Disable" else "Enable", fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    state.pluginAction("${plugin.name} removed") { it.removePlugin(plugin.pluginId) }
                }) { Text("Remove", fontSize = 12.sp) }
            }
        }
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
private fun AppearanceSection(theme: ThemeChoice, onThemeChange: (ThemeChoice) -> Unit) {
    Text("Theme", style = MaterialTheme.typography.titleSmall)
    for (choice in ThemeChoice.entries) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = choice == theme, onClick = { onThemeChange(choice) })
            Text(choice.name, fontSize = 13.sp)
        }
    }
    HorizontalDivider()
    Text("Notes open", style = MaterialTheme.typography.titleSmall)
    var reading by remember { mutableStateOf(Prefs.readingMode) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = reading, onClick = { reading = true; Prefs.readingMode = true })
        Text("rendered", fontSize = 13.sp)
        RadioButton(selected = !reading, onClick = { reading = false; Prefs.readingMode = false })
        Text("as raw Markdown", fontSize = 13.sp)
    }
}

@Composable
private fun AboutSection(state: GiluneState, vaultDir: String) {
    Text("Gilune", style = MaterialTheme.typography.titleSmall)
    Text(
        buildString {
            appendLine("kernel ${runCatching { kernelVersion() }.getOrDefault("unknown")}")
            appendLine("vault $vaultDir")
            appendLine(state.message.value)
        }.trim(),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
