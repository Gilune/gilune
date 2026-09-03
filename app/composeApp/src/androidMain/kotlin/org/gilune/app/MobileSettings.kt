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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.kernelVersion

/// Where in settings the user is. Mobile has no two-pane nav, so a screen
/// is a place you go to and come back from.
sealed interface SettingsRoute {
    data object Index : SettingsRoute
    data object Vault : SettingsRoute
    data object Capture : SettingsRoute
    data object Plugins : SettingsRoute

    /// Pairing and peers (ADR 0019). Its own place rather than a corner of
    /// Vault: the vault is where notes are, and this is who else may have
    /// them.
    data object Sync : SettingsRoute
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
    moonshine: MoonshineRuntime = remember { MoonshineRuntime() },
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
            SettingsRoute.Sync -> SyncSection(state)
            SettingsRoute.Appearance -> AppearanceSection(theme, onThemeChange)
            SettingsRoute.About -> AboutSection(state)
            is SettingsRoute.Plugin -> {
                val plugin = state.plugins.firstOrNull { it.pluginId == route.pluginId }
                if (plugin == null) {
                    PluginsManager(state) { onNavigate(SettingsRoute.Plugin(it)) }
                } else {
                    PluginScreen(state, plugin, moonshine)
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
    NavRow("Sync", syncSummary(state.syncStatus.value)) { onNavigate(SettingsRoute.Sync) }
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
        "Export a copy is how the files leave this phone; Settings ▸ Sync is how " +
        "they reach the devices you have paired, and nothing else."

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
                    onCheckedChange = { enable -> switchPlugin(state, plugin, enable) },
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
private fun PluginScreen(state: GiluneState, plugin: PluginInfo, moonshine: MoonshineRuntime) {
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
                onCheckedChange = { enable -> switchPlugin(state, plugin, enable) },
                modifier = Modifier.testTag("plugin-switch"),
            )
        }
    }

    // § 3's confirmation, said **before** the switch rather than only
    // after it: enabling a transcriber switches the other one off, and a
    // person should read that rather than discover it.
    providerSwitchWarning(state.plugins.toList(), plugin.pluginId)?.let { warning ->
        if (plugin.state != "enabled") {
            Text(
                warning,
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
                modifier = Modifier.testTag("provider-warning"),
            )
        }
    }
    if (plugin.pluginId in TRANSCRIPTION_PROVIDERS && plugin.state == "enabled") {
        TranscriberCatchUp(state, plugin)
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

    // A bundled plugin's known fields, rendered directly; the generic
    // schema-driven form arrives with the sandboxed runtime (ADR 0010).
    // Same idiom as the desktop's, with the wording adapted to a phone.
    if (plugin.pluginId == TRANSCRIPTION_PLUGIN) {
        HorizontalDivider(color = colours.outlineVariant)
        TranscriptionSettings(state, plugin)
    }
    if (plugin.pluginId == MOONSHINE_PLUGIN) {
        HorizontalDivider(color = colours.outlineVariant)
        MoonshineSettings(state, plugin, moonshine)
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

/// **One active transcriber** (ADR 0018 § 3), which is a *shell* rule: the
/// kernel needs no new concept because disabled already means "nothing new
/// is queued and queued jobs are claimable by nobody" — parked, not
/// destroyed. So the whole enforcement is this: enabling a transcription
/// provider switches every other one off, in that order, and the message
/// says so in words.
///
/// The displaced provider goes off **first**. For the instant between two
/// writes one transcriber is better than two, and this is the ordering that
/// cannot leave both on if the second call throws.
private fun switchPlugin(state: GiluneState, plugin: PluginInfo, enable: Boolean) {
    val switch = providerSwitch(state.plugins.toList(), plugin.pluginId, enable)
    state.pluginAction(switch.message) { gilune ->
        for (displaced in switch.disable) gilune.setPluginEnabled(displaced, false)
        gilune.setPluginEnabled(plugin.pluginId, enable)
    }
}

/// The catch-up a provider switch may offer, **scoped and counted**
/// (ADR 0018 § 3).
///
/// The trap this exists to avoid is worth stating where the button is:
/// idempotency is keyed `{processor_id}:{event_id}`, so a *different*
/// processor id makes every past capture a fresh job. A blanket catch-up
/// under a changed provider would decode the whole vault again and rewrite
/// every note body with the new engine's words. Nothing would be lost —
/// each is a revision — but every note would change.
///
/// So the number below is recordings that still have **no words**, that is
/// what the sentence promises, and the provider keeps the promise by
/// completing any job whose subject already has words without writing an
/// output.
@Composable
private fun TranscriberCatchUp(state: GiluneState, plugin: PluginInfo) {
    val colours = MaterialTheme.colorScheme
    var wordless by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.vaultTick.value, plugin.pluginId, plugin.state) {
        val gilune = state.gilune.value ?: return@LaunchedEffect
        wordless = withContext(Dispatchers.IO) {
            runCatching { wordlessRecordings(gilune).size }.getOrNull()
        }
    }
    val count = wordless ?: return
    HorizontalDivider(color = colours.outlineVariant)
    Text("Recordings without words", style = MaterialTheme.typography.titleSmall)
    Text(
        if (count == 0) {
            "Every recording in your vault has words."
        } else {
            "$count recording${if (count == 1) "" else "s"} in your vault ha" +
                "${if (count == 1) "s" else "ve"} no words yet — made before a " +
                "transcriber was installed, or while one was switched off. " +
                "Recordings that already have words are left exactly as they " +
                "are: switching transcriber never re-transcribes your vault."
        },
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
        modifier = Modifier.testTag("wordless-count"),
    )
    if (count > 0) {
        OutlinedButton(
            onClick = { state.backfill() },
            modifier = Modifier.heightIn(min = TouchTarget).testTag("catch-up"),
        ) { Text("Transcribe them with ${plugin.name}") }
    }
}

/// The transcription provider's settings on a phone (ADR 0017 § 4, § 7).
///
/// The desktop's form, with two differences that are both the ADR's:
///
///  - **The recommendation is different.** `base-q5_1` is recommended on
///    a desktop because it hears trade words and £ amounts for about a
///    second more. On a phone the CPU is 2–4× slower and the ADR pencils
///    `tiny-q5_1` (§ Ratification 5), so that is what is marked — and it
///    is marked as *pencilled*, because MS2 has not run and no figure in
///    this repo has ever come off ARM64.
///  - **Sizes matter more.** A 142 MiB download onto a metered phone
///    connection is a different proposition, so every row shows its cost
///    and the catalogue's own labels carry it.
///
/// Both keys are one settings document, so every write sends both
/// (`transcriptionSettingsDocument`) on the tasks precedent: changing one
/// must never drop the other.
@Composable
private fun TranscriptionSettings(state: GiluneState, plugin: PluginInfo) {
    val colours = MaterialTheme.colorScheme
    val saved = remember(plugin.settings) { listOf(plugin).transcriptionLanguage() }
    // The phone's default, resolved at the read site — the desktop's
    // `transcriptionModel()` still answers base-q5_1, which is what keeps
    // the two shells' defaults a decision rather than drift.
    val savedModel = remember(plugin.settings) { listOf(plugin).androidTranscriptionModel() }
    var language by remember(plugin.settings) { mutableStateOf(saved) }

    Text("Spoken language", style = MaterialTheme.typography.titleSmall)
    Text(
        "\"auto\" detects the language but costs an extra pass, which is " +
            "about 40% longer on a short clip. A fixed code — en, sv, de… — " +
            "transcribes faster.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = language,
            onValueChange = { language = it },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("language"),
        )
        Button(
            onClick = {
                val wanted = language.trim()
                // The model travels with the write, so setting a language
                // cannot quietly revert the model somebody chose.
                state.pluginAction("spoken language set to $wanted — takes effect next launch") {
                    it.setPluginSettings(
                        plugin.pluginId,
                        transcriptionSettingsDocument(wanted, savedModel.key),
                    )
                }
            },
            enabled = language.trim().isNotEmpty() && language.trim() != saved,
            modifier = Modifier.heightIn(min = TouchTarget),
        ) { Text("Apply") }
    }

    HorizontalDivider(color = colours.outlineVariant)
    Text("Transcription model", style = MaterialTheme.typography.titleSmall)
    Text(
        "Bigger models hear better and take longer, and on a phone that " +
            "trade is sharper than on a desktop: the same clip is expected to " +
            "take two to four times as long, and transcription competes for " +
            "battery. tiny-q5_1 is the phone recommendation — the smallest " +
            "download and the fastest measured model — but it is pencilled in " +
            "rather than measured: no figure here has yet come off a real " +
            "phone. The quantised models (q5_1) transcribe the same words as " +
            "their full siblings from a smaller download.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
    for (option in WHISPER_MODELS) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
                .clickable {
                    // The language travels with the write for the same
                    // reason the model does above — and the stored one,
                    // not whatever is half-typed in the field.
                    state.pluginAction("transcription model set to ${option.key}") {
                        it.setPluginSettings(
                            plugin.pluginId,
                            transcriptionSettingsDocument(saved, option.key),
                        )
                    }
                }
                .testTag("model-option"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option.key == savedModel.key, onClick = null)
            Text(
                phoneModelLabel(option),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    Text(
        "A model you have not chosen before is downloaded once, with the " +
            "network permission above. Models already downloaded are kept, so " +
            "switching back costs nothing. Recordings made before a model " +
            "arrives are never lost — they wait, and Catch up missed captures " +
            "collects any made while the plugin was off.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
}

/// The realtime provider's own screen (ADR 0018 § 5).
///
/// Three controls and a lot of honesty. The wording carries R0's measured
/// numbers rather than the ADR's estimates — the download is **45 MB, not
/// 77.7 MB**, and the latency figures came off an x86_64 emulator and have
/// never been measured on a phone, which is said here rather than left for
/// someone to assume.
///
/// All three keys travel on every write (`moonshineSettingsDocument`), on
/// the tasks precedent: turning the caption off must not silently revert
/// the model or re-enable a mobile-data download.
@Composable
private fun MoonshineSettings(state: GiluneState, plugin: PluginInfo, moonshine: MoonshineRuntime) {
    val colours = MaterialTheme.colorScheme
    val settings = listOf(plugin)
    val caption = remember(plugin.settings) { settings.moonshineCaption() }
    val wifiOnly = remember(plugin.settings) { settings.moonshineWifiOnly() }
    val model = remember(plugin.settings) { settings.moonshineModel() }

    fun write(describe: String, caption: Boolean, wifiOnly: Boolean) {
        state.pluginAction(describe) {
            it.setPluginSettings(plugin.pluginId, moonshineSettingsDocument(model.key, caption, wifiOnly))
        }
    }

    Text("Live caption", style = MaterialTheme.typography.titleSmall)
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Show words while you speak",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = caption,
            onCheckedChange = { on ->
                write(if (on) "live caption on" else "live caption off", on, wifiOnly)
            },
            modifier = Modifier.testTag("caption-switch"),
        )
    }
    Text(
        "While you record in the app, the words appear above the composer as " +
            "you say them. They are shown and never saved: the note's words " +
            "are read again from the recording itself after you stop, by this " +
            "same engine, with the whole recording to go on. The caption " +
            "clears when you stop.\n\n" +
            "Expect it to rewrite itself mid-sentence — “the lace of the” " +
            "becoming “the lazy dog.” — because it revises as it hears more. " +
            "Grey text is still changing; black text is settled and will not " +
            "change again.\n\n" +
            "Measured on an emulator: once the model is loaded the words " +
            "arrive about 0.65 s after you start speaking and run about " +
            "0.15 s behind you. The first recording after you open the app " +
            "waits about a second longer, because the model loads while you " +
            "are already recording — the recording never waits for it. No " +
            "figure here has been measured on a real phone, and battery " +
            "cost has not been measured at all.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )

    HorizontalDivider(color = colours.outlineVariant)
    Text("Model", style = MaterialTheme.typography.titleSmall)
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).testTag("moonshine-model"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                moonshineStatus(moonshine.phase, moonshine.progress),
                style = MaterialTheme.typography.bodySmall,
                color = if (moonshine.phase == MoonshinePhase.Failed) colours.error else colours.onSurfaceVariant,
            )
        }
    }
    if (moonshine.notice.isNotBlank()) {
        Text(moonshine.notice, style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
    }
    Text(
        "The app itself carries the engine; the ${model.size} model is " +
            "the one thing it fetches, once, and only after you allow " +
            "network.request above. It is stored in this app's private " +
            "storage, so uninstalling removes it with everything else. " +
            "The same model writes both the caption and the note's words, " +
            "so it is downloaded whenever this transcriber is on — turning " +
            "the caption off does not remove the need for it.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )

    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Only download on Wi-Fi",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = wifiOnly,
            onCheckedChange = { on ->
                write(if (on) "downloads wait for Wi-Fi" else "downloads may use any connection", caption, on)
            },
            modifier = Modifier.testTag("wifi-switch"),
        )
    }
}

/// Where the model is, in one line. Pure over the two values it reads —
/// not over the runtime — so the wording is testable without a network,
/// and so a progress figure the server never supplied cannot become a
/// percentage nobody measured.
fun moonshineStatus(phase: MoonshinePhase, progress: MoonshineProgress?): String = when (phase) {
    MoonshinePhase.Unknown -> "checking…"
    MoonshinePhase.Absent -> "not downloaded"
    MoonshinePhase.Downloading -> progress?.let {
        val percent = it.percent
        if (percent == null) {
            "downloading file ${it.fileIndex} of ${it.fileCount}…"
        } else {
            "downloading — $percent%, file ${it.fileIndex} of ${it.fileCount}"
        }
    } ?: "downloading…"
    MoonshinePhase.Ready -> "downloaded and ready"
    MoonshinePhase.Failed -> "download failed — your recordings are unaffected"
}

/// The catalogue's own label, with the desktop's recommendation swapped
/// for the phone's. The shared `WhisperModel.note` says "recommended" of
/// `base-q5_1` because the shared catalogue is written from the desktop
/// benchmark; on a phone that word belongs to a different row, and saying
/// so here rather than editing the catalogue is what keeps one shell's
/// advice out of the other's.
fun phoneModelLabel(model: WhisperModel): String = when (model.key) {
    ANDROID_DEFAULT_WHISPER_MODEL -> "${model.key} — recommended on phones, ${model.size}"
    DEFAULT_WHISPER_MODEL -> "${model.key} — hears more, ${model.size}"
    else -> "${model.key} — ${model.size}"
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
