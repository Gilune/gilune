// Settings on the phone (ADR 0017 § 5, § 7).
//
// The shape is the desktop's, which is the Obsidian shape the owner asked
// for: a manager list with switches, and one entry per installed plugin
// opening that plugin's own screen — permissions, events, contributions,
// removal. It is rendered from the shared registry, so the plugin list is
// data on both platforms and never a hard-coded set.
//
// The consent policy is the desktop's too, and it is the host's, not this
// screen's: a bundled plugin's local capabilities are granted once on
// first install, `network.request` always waits for an explicit yes, and
// nothing re-grants after a revoke or a removal. All this screen does is
// show what the kernel holds and offer the two verbs.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.kernelVersion

/// Where in settings the user is. Mobile has no two-pane nav, so a screen
/// is a place you go to and come back from.
sealed interface SettingsRoute {
    data object Index : SettingsRoute
    data object Vault : SettingsRoute
    data object Capture : SettingsRoute
    data object Plugins : SettingsRoute
    data object Appearance : SettingsRoute
    data object About : SettingsRoute
    data class Plugin(val pluginId: String) : SettingsRoute
}

@Composable
fun SettingsScreen(
    state: GiluneState,
    route: SettingsRoute,
    onNavigate: (SettingsRoute) -> Unit,
    theme: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    idleTimeout: IdleTimeout,
    onIdleTimeoutChange: (IdleTimeout) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (route) {
            SettingsRoute.Index -> SettingsIndex(state, onNavigate)
            SettingsRoute.Vault -> VaultSection(state, onExport)
            SettingsRoute.Capture -> CaptureSection(idleTimeout, onIdleTimeoutChange)
            SettingsRoute.Plugins -> PluginsManager(state) { onNavigate(SettingsRoute.Plugin(it)) }
            SettingsRoute.Appearance -> AppearanceSection(theme, onThemeChange)
            SettingsRoute.About -> AboutSection(state)
            is SettingsRoute.Plugin -> {
                val plugin = state.plugins.firstOrNull { it.pluginId == route.pluginId }
                if (plugin == null) {
                    PluginsManager(state) { onNavigate(SettingsRoute.Plugin(it)) }
                } else {
                    PluginScreen(state, plugin)
                }
            }
        }
    }
}

@Composable
private fun SettingsIndex(state: GiluneState, onNavigate: (SettingsRoute) -> Unit) {
    NavRow("Vault") { onNavigate(SettingsRoute.Vault) }
    NavRow("Capture") { onNavigate(SettingsRoute.Capture) }
    NavRow("Plugins", "${state.plugins.count { it.state == "enabled" }} of ${state.plugins.size} on") {
        onNavigate(SettingsRoute.Plugins)
    }
    NavRow("Appearance") { onNavigate(SettingsRoute.Appearance) }
    NavRow("About") { onNavigate(SettingsRoute.About) }
}

@Composable
private fun NavRow(label: String, detail: String? = null, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = TouchTarget)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = colours.onSurfaceVariant,
        )
    }
}

/// § 5, stated plainly rather than inherited by accident.
@Composable
private fun VaultSection(state: GiluneState, onExport: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Text("Where your vault lives", style = MaterialTheme.typography.titleSmall)
    Text(
        state.vaultDir,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
    Text(VAULT_TRUTH, style = MaterialTheme.typography.bodyMedium, color = colours.onSurfaceVariant)
    Button(onClick = onExport, modifier = Modifier.heightIn(min = TouchTarget).testTag("export")) {
        Text("Export a copy")
    }
    OutlinedButton(onClick = { state.sync() }, modifier = Modifier.heightIn(min = TouchTarget)) {
        Text("Sync now")
    }
    HorizontalDivider(color = colours.outlineVariant)
    Text("This vault", style = MaterialTheme.typography.titleSmall)
    val gilune = state.gilune.value
    Text(
        if (gilune == null) {
            "not open"
        } else {
            buildString {
                appendLine("schema version: ${runCatching { gilune.schemaVersion() }.getOrNull()}")
                appendLine("device: ${runCatching { gilune.deviceId() }.getOrNull()}")
                runCatching { gilune.status() }.getOrNull()?.let { status ->
                    appendLine("captures: ${status.sources}")
                    appendLine("projected notes: ${status.projections}")
                    appendLine("waiting jobs: ${status.jobsPending}")
                    appendLine("open proposals: ${status.proposalsOpen}")
                }
            }.trim()
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
}

/// The sentence first run shows and Settings repeats. One string, because
/// a promise stated twice differently is stated wrongly once.
const val VAULT_TRUTH: String =
    "Your vault is private to this app. No other app — including Obsidian and " +
        "any file manager — can see it, and uninstalling Gilune deletes it. " +
        "Until devices can sync, Export a copy is how the files leave this phone."

@Composable
private fun PluginsManager(state: GiluneState, onOpenPlugin: (String) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Text("Installed plugins", style = MaterialTheme.typography.titleSmall)
    for (plugin in state.plugins) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onOpenPlugin(plugin.pluginId) }
                .heightIn(min = TouchTarget)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("plugin-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(plugin.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${plugin.`class`} · ${plugin.profile}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colours.onSurfaceVariant,
                )
                if (plugin.ungranted.isNotEmpty()) {
                    Text(
                        "needs your permission — open to review",
                        style = MaterialTheme.typography.bodySmall,
                        color = colours.error,
                    )
                }
            }
            if (plugin.required) {
                Text("required", style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
            } else {
                Switch(
                    checked = plugin.state == "enabled",
                    onCheckedChange = { enable ->
                        state.pluginAction(
                            if (enable) "${plugin.name} switched on" else "${plugin.name} switched off",
                        ) { it.setPluginEnabled(plugin.pluginId, enable) }
                    },
                )
            }
        }
    }
    HorizontalDivider(color = colours.outlineVariant)
    OutlinedButton(
        onClick = { state.backfill() },
        modifier = Modifier.heightIn(min = TouchTarget),
    ) { Text("Catch up missed captures") }
    Text(
        "Processes captures made before a plugin was installed, or while it was off.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginScreen(state: GiluneState, plugin: PluginInfo) {
    val colours = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(plugin.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${plugin.`class`} · ${plugin.profile}",
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
            )
        }
        if (plugin.required) {
            Text("required", style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
        } else {
            Switch(
                checked = plugin.state == "enabled",
                onCheckedChange = { enable ->
                    state.pluginAction(
                        if (enable) "${plugin.name} switched on" else "${plugin.name} switched off",
                    ) { it.setPluginEnabled(plugin.pluginId, enable) }
                },
            )
        }
    }

    if (plugin.capabilities.isNotEmpty()) {
        HorizontalDivider(color = colours.outlineVariant)
        Text("Permissions", style = MaterialTheme.typography.titleSmall)
        for (capability in plugin.capabilities) {
            val granted = capability in plugin.granted
            Row(
                Modifier.fillMaxWidth().heightIn(min = TouchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        capability,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (granted) "allowed" else "not allowed",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (granted) colours.onSurfaceVariant else colours.error,
                    )
                }
                if (granted) {
                    OutlinedButton(onClick = {
                        state.pluginAction("revoked $capability for ${plugin.name}") {
                            it.revokeCapability(plugin.pluginId, capability)
                        }
                    }) { Text("Revoke") }
                } else {
                    Button(onClick = {
                        state.pluginAction("allowed $capability for ${plugin.name}") {
                            it.grantCapability(plugin.pluginId, capability)
                        }
                    }) { Text("Allow") }
                }
            }
        }
    }

    val events = buildList {
        plugin.subscribes?.let { add("listens to $it") }
        plugin.emits.forEach { add("emits $it") }
    }
    if (events.isNotEmpty()) {
        HorizontalDivider(color = colours.outlineVariant)
        Text("Events", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { for (event in events) Chip(event, mono = true) }
    }

    if (plugin.contributions.isNotEmpty()) {
        HorizontalDivider(color = colours.outlineVariant)
        Text("Adds to the app", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (contribution in plugin.contributions) {
                Chip("${contribution.slot} · ${contribution.label}")
            }
        }
    }

    if (!plugin.required) {
        HorizontalDivider(color = colours.outlineVariant)
        OutlinedButton(
            onClick = { state.pluginAction("${plugin.name} removed") { it.removePlugin(plugin.pluginId) } },
            modifier = Modifier.heightIn(min = TouchTarget),
        ) { Text("Remove ${plugin.name}") }
        Text(
            "Settings and permissions go; everything it created stays in your vault.",
            style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant,
        )
    }
}

@Composable
private fun Chip(text: String, mono: Boolean = false, tint: Color? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint ?: MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun AppearanceSection(theme: ThemeChoice, onThemeChange: (ThemeChoice) -> Unit) {
    Text("Theme", style = MaterialTheme.typography.titleSmall)
    for (choice in ThemeChoice.entries) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable { onThemeChange(choice) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = choice == theme, onClick = { onThemeChange(choice) })
            Text(choice.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/// The one number that governs returning (§ 1). It belongs beside
/// capture, not beside the theme: it decides what happens to a thought.
@Composable
private fun CaptureSection(idleTimeout: IdleTimeout, onIdleTimeoutChange: (IdleTimeout) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Text("Start a fresh capture after", style = MaterialTheme.typography.titleSmall)
    Text(
        "Coming back to a composer you left more than this long ago saves what " +
            "you had typed as a capture and gives you an empty one. Nothing is " +
            "ever discarded.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
    for (option in IdleTimeout.CHOICES) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable { onIdleTimeoutChange(option) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == idleTimeout, onClick = { onIdleTimeoutChange(option) })
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AboutSection(state: GiluneState) {
    Text("Gilune", style = MaterialTheme.typography.titleSmall)
    Text(
        buildString {
            appendLine("kernel ${runCatching { kernelVersion() }.getOrDefault("unknown")}")
            appendLine("vault ${state.vaultDir}")
            appendLine(state.message.value)
        }.trim(),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.size(4.dp))
    Text(VAULT_TRUTH, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
