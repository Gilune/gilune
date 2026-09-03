// Settings ▸ Sync on the phone (ADR 0019 § 3, § 4, § 9).
//
// Everything a person needs to pair this phone with their other devices,
// and nothing that implies more than the milestone delivers. Three rules
// from the ADR are visible in the layout itself:
//
//  - **Scan and the six-digit code sit side by side.** The camera is a
//    convenience and never a condition of syncing (§ 3, § 7), so the two
//    address-and-code fields are on screen whether the camera was ever
//    allowed or not.
//  - **Turning sync on is the moment a socket is bound** (§ 2). Nothing
//    below binds anything until the switch is on, and the wording says so
//    rather than leaving a person to wonder what changed.
//  - **Revocation protects what comes next and retracts nothing** (§ 9).
//    That sentence is on the screen beside the button, because a UI that
//    implies an undo is the one way this can mislead.
//
// M5c adds the three things a live network needs (§ 1, § 8, § 10), and
// each of them exists because its absence would be a silence:
//
//  - **Standing integrity alerts are drawn at the top.** The kernel has
//    halted a peer's stream since M5a and nothing could show it, which
//    made a halted stream look exactly like an idle one — § 8's own
//    "failure mode that would make sync look like it works".
//  - **Looking for devices is a button.** A browse costs seconds and
//    multicast traffic, and § 1 keeps discovery off the connect path, so
//    nothing here browses on its own.
//  - **The cadence is stated honestly** (§ 10). Fifteen minutes is the
//    floor WorkManager clamps to, not a promise the phone keeps in a
//    pocket, and the screen says so instead of implying a clock.

package org.gilune.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.gilune_ffi.PeerInfo
import uniffi.gilune_ffi.SyncStatus

/// Where this device's identity and roster live: app-private internal
/// storage, **outside the vault** (ADR 0019 § 4). One function, on
/// `vaultDir`'s and `modelsDir`'s idiom, so the activity and anything
/// later can never disagree about the path.
///
/// The placement is the ADR's sharpest decision and it is mechanical: a
/// vault is a portable artefact that gets exported and copied, and an
/// identity inside one is a cloned identity with a shared sequence space.
/// Keeping it here is what makes "a restored phone is a new device" a
/// consequence rather than a check somebody has to remember.
fun syncDir(context: Context): String =
    context.filesDir.resolve("sync").absolutePath

/// Throw this device's sync state away: identity, roster, and the
/// remembered on-or-off. Answers how many entries it removed.
///
/// **Device-local and nothing else.** The vault is not here and is not
/// touched — that is the whole point of § 4's placement — so this is
/// recoverable in the only sense that matters: notes are unaffected, and
/// what is lost is a pairing, which is a thing people re-do.
///
/// It exists for one state, described in `identityUnreadable`: a sealed
/// identity file whose Keystore key did not survive. Before this round a
/// phone in that state had no way out inside the app at all.
fun clearSyncState(dir: java.io.File): Int {
    val entries = dir.listFiles() ?: return 0
    return entries.count { it.deleteRecursively() }
}

/// What this phone calls itself to the devices it pairs with. The human
/// name is exchanged **inside** the encrypted handshake (§ 1), never in
/// the advertisement, so this is a courtesy to the person reading their
/// desktop's peer list rather than anything a stranger on the network
/// sees.
fun deviceName(): String = Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "This phone"

fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/// The one line the settings index shows. Pure, so the wording is decided
/// in one place and can be read without a device.
fun syncSummary(status: SyncStatus?): String {
    if (status == null) return "not set up on this device"
    val paired = status.peers.count { it.state == "paired" }
    val devices = when (paired) {
        0 -> "no paired devices"
        1 -> "1 paired device"
        else -> "$paired paired devices"
    }
    return if (status.enabled) "on · $devices" else "off · $devices"
}

/// What actually protected the identity key, said as a sentence rather
/// than as a token. The `none` case is the one that matters: the ADR
/// allows the fallback and asks for it to be stated, so it is stated.
fun keyProtectionSentence(protection: String): String = when (protection) {
    KEY_PROTECTION_KEYSTORE ->
        "This device's key is sealed by the phone's Keystore. That protects it at " +
            "rest and from every other app; it does not protect it from Gilune itself."
    "none" ->
        "Nothing is protecting this device's key beyond the app's own private " +
            "storage — it is stored unwrapped. Uninstalling removes it with everything else."
    else ->
        "This device's key is sealed by $protection. That protects it at rest; it " +
            "does not protect it from Gilune itself."
}

/// A six-digit code, as typed. Digits only and never more than six, so
/// the button below can be honest about when it is ready.
fun normalisedPairingCode(typed: String): String = typed.filter { it.isDigit() }.take(6)

/// What the "This device" block says about being findable (ADR 0019 § 1).
///
/// `advertising` is deliberately separate from `enabled` in the kernel,
/// and this is why: binding TCP can succeed while opening UDP 5353 fails,
/// and a device that is reachable but not findable is the exact state the
/// fallback chain exists for. One sentence for both would tell a person
/// their phone is findable when it is only reachable.
fun discoverySentence(enabled: Boolean, advertising: Boolean, discovery: String): String = when {
    discovery.isBlank() || discovery == "none" ->
        "This phone has no way to announce itself or to look for others, so it reaches " +
            "your devices at the addresses pairing recorded. That works; it just means " +
            "nothing is found on its own."

    !enabled ->
        "While sync is off this phone announces nothing. Turn it on and your other " +
            "devices can find it here without being told an address."

    advertising ->
        "This phone is announcing itself on this network, so your other devices can find " +
            "it without being told an address."

    else ->
        "Sync is on, so your paired devices can reach this phone — but it is not " +
            "announcing itself, so nothing will find it on its own. Some networks block " +
            "those announcements. Pair with the six-digit code and an address, or type an " +
            "address for a device below."
}

/// What a device seen on the network is, to this phone. `state` is
/// `paired`, `revoked` or `unknown`, and incompatibility outranks all
/// three: a device running another version is present and unreachable,
/// which is worth saying before anything about pairing.
fun foundDeviceState(state: String, compatible: Boolean): String = when {
    !compatible -> "running a version this phone cannot talk to"
    state == "paired" -> "already paired with this phone"
    state == "revoked" -> "revoked — it cannot reach your notes"
    else -> "not paired yet"
}

/// Whether opening or returning to the app should exchange anything
/// (ADR 0019 § 10). Two conditions, and the second one matters: a resume
/// with nobody paired would dial nothing and cost a thread for the
/// privilege, so the prompt path stays quiet until there is a device to
/// be prompt with.
fun shouldSyncOnForeground(status: SyncStatus?): Boolean =
    status != null && status.enabled && status.peers.any { it.state == "paired" }

/// The same question for the **background** pass, and it reads a different
/// field on purpose (ADR 0019 § 2, § 10).
///
/// `enabled` is a bound listener **in this process**. `SyncWorker` starts a
/// process of its own, dials out and never listens — § 2 keeps the listener
/// behind the switch in Settings — so `enabled` is false there whatever the
/// person chose, and gating on it would mean the background sync never ran
/// at all.
///
/// `wanted` is the persisted choice, kept device-local beside the identity.
/// It is the only thing in a fresh process that can say whether sync is
/// switched on, and a worker that did not consult it would exchange
/// operations for somebody who had switched sync off — the kernel's own
/// outbound permission is process-local and starts permissive by design, so
/// nothing below this would have stopped it.
fun shouldSyncInBackground(status: SyncStatus?): Boolean =
    status != null && status.wanted && status.peers.any { it.state == "paired" }

/// The sentence for a look that found nothing (§ 1's third step: words,
/// not a spinner). Client isolation is named because it is the usual
/// cause and because "wait a bit longer" is advice that never works.
const val NOTHING_FOUND =
    "Nothing answered on this network. Many guest, hotel and office networks stop " +
        "devices from seeing each other at all, and waiting will not change that — pair " +
        "with an address instead, which the other device shows beside its code."

/// What this screen should say about the last thing that happened, or
/// null for nothing worth a line.
///
/// Every action here reports through `state.message` — pairing, revoking,
/// the switch, a declined camera — and on a phone that line is drawn above
/// the composer, which is two navigations away from the button that caused
/// it. So the sync screen draws it too, filtered exactly as the composer
/// filters it: the vault path and the opening line are a developer's
/// facts, not an outcome.
/// The unreadable-identity line is dropped too, and for the opposite
/// reason to the others: it is not a developer's fact, it is the most
/// important thing on the screen — and the screen says it properly, in
/// full sentences, beside the button that fixes it. Drawing the kernel's
/// own wording above that as well would be the same news twice, once in a
/// form nobody can act on.
///
/// [identityUnreadable] is that state as the **typed** answer the state
/// holds, passed in rather than sniffed out of the message: the sentence
/// this drops is the kernel's, and reading its words to decide would make
/// its phrasing part of the contract.
fun syncNotice(message: String, identityUnreadable: Boolean = false): String? =
    message.takeUnless {
        it.isBlank() ||
            it.startsWith("vault: ") ||
            it == "opening vault…" ||
            identityUnreadable
    }

@Composable
fun SyncSection(state: GiluneState) {
    val colours = MaterialTheme.colorScheme
    val context = LocalContext.current
    val status = state.syncStatus.value

    // The facts are refreshed off the main thread, on `syncAction`'s own
    // idiom — reading them touches the identity file.
    LaunchedEffect(state.gilune.value) {
        if (state.gilune.value != null) withContext(Dispatchers.IO) { state.refreshSync() }
    }

    Text("Sync with your other devices", style = MaterialTheme.typography.titleSmall)

    // Above everything, because a halted stream that nobody mentions is
    // indistinguishable from an idle one (§ 8).
    SyncAlerts(state)

    // The outcome of the last thing pressed, said on the screen that has
    // the buttons. Without this the whole section is silent: a pairing
    // that failed, a code that expired and a declined camera all report
    // through the shared message line, which is drawn above the composer
    // two navigations away.
    syncNotice(state.message.value, state.identityUnreadable.value)?.let { notice ->
        Text(
            notice,
            style = MaterialTheme.typography.bodyMedium,
            color = colours.onSurfaceVariant,
            modifier = Modifier.testTag("sync-message"),
        )
    }

    if (status == null) {
        // The one unavailable state a person can get out of, and the way
        // out. Everything else here is a bug or a transient; this is a
        // restored backup, and until this round it was a dead end (§ 4).
        // The typed answer the enrolment recorded, not a phrase read back
        // out of the message line: a destructive button may not be gated on
        // wording somebody might improve.
        if (state.identityUnreadable.value) {
            Text(
                identityUnreadableSentence("this phone"),
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
                modifier = Modifier.testTag("sync-identity-unreadable"),
            )
            Button(
                onClick = { resetSyncOnThisPhone(state, context) },
                modifier = Modifier.heightIn(min = TouchTarget).testTag("sync-reset"),
            ) { Text("Reset sync on this phone") }
            return
        }
        Text(
            "Sync is not set up on this device yet. If this keeps saying so, the " +
                "message at the bottom of Settings ▸ About says why.",
            style = MaterialTheme.typography.bodyMedium,
            color = colours.onSurfaceVariant,
            modifier = Modifier.testTag("sync-unavailable"),
        )
        return
    }

    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sync on this phone", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = status.enabled,
            onCheckedChange = { on ->
                state.syncAction(if (on) "sync is on" else "sync is off") { it.setSyncEnabled(on) }
                // The background schedule follows the switch and nothing
                // else (§ 10). The choice itself is remembered by the
                // kernel, beside the identity and the roster — `enabled`
                // is a bound socket and a bound socket does not survive
                // the app being killed, so `wanted` is the durable half
                // and `resumeSync` on open is what binds again. Without
                // that, this gate never fired on a fresh launch and the
                // phone only ever synced if somebody re-toggled a switch
                // that already looked off.
                if (on) scheduleBackgroundSync(context) else cancelBackgroundSync(context)
            },
            modifier = Modifier.testTag("sync-switch"),
        )
    }
    Text(
        "While this is on, your other devices can reach this phone over the local " +
            "network — that is the moment anything is bound, and nothing is bound " +
            "before it. While it is off, joining a device that is showing a code and " +
            "Sync with devices now both still work; it is only the other direction " +
            "that closes.\n\n" +
            "Your notes never leave this phone except to devices you have paired.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
    // Meant to be on, and not listening. The switch is remembered across
    // launches now, so this is the state where the app came back up and
    // could not take its port. Showing the switch quietly off would hide
    // a fault and invite a person to flip something already where they
    // left it.
    if (status.wanted && !status.enabled) {
        Text(
            "Sync is switched on, but this phone could not start listening — usually " +
                "something else already has the port. Turn the switch off and on again " +
                "to retry.",
            style = MaterialTheme.typography.bodySmall,
            color = colours.error,
            modifier = Modifier.testTag("sync-bind-failed"),
        )
    }
    // One line under the switch, and the full ranked list under "This
    // device" — `sync-addresses` there is where every address this phone
    // answers on is drawn, with the sentence saying which to try first.
    // Listing them twice on one screen would be two answers to one
    // question, so this stays the single best-ranked fact.
    status.listeningOn?.let { address ->
        Text(
            "Listening on $address",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant,
            modifier = Modifier.testTag("sync-listening"),
        )
    }

    HorizontalDivider(color = colours.outlineVariant)
    ThisDevice(status)

    HorizontalDivider(color = colours.outlineVariant)
    DevicesOnThisNetwork(state, status)

    HorizontalDivider(color = colours.outlineVariant)
    AddADevice(state, context)

    HorizontalDivider(color = colours.outlineVariant)
    PairedDevices(state, status)

    HorizontalDivider(color = colours.outlineVariant)
    OutlinedButton(
        onClick = { state.syncDevices() },
        modifier = Modifier.heightIn(min = TouchTarget).testTag("sync-now"),
    ) { Text("Sync with devices now") }
    Text(
        "Exchanges everything new with each paired device. Gilune tries the address it " +
            "last used first, and looks for the device on this network only when that " +
            "fails — which is what keeps syncing quick and cheap. A device it still " +
            "cannot reach is reported, never hunted for in silence.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
    // § 10, in the ADR's own terms. WorkManager clamps a periodic job to
    // fifteen minutes, and Doze then batches deferred work into
    // maintenance windows that lengthen the longer a phone stays idle, so
    // a promise of "every fifteen minutes" would be one the phone does
    // not keep. Saying it here is cheaper than a support answer later.
    Text(
        "Gilune also syncs on its own: every time you open the app or come back to it, " +
            "and in the background about every fifteen minutes while this phone is on a " +
            "network that does not charge by the megabyte and the battery is not low.\n\n" +
            "Fifteen minutes is a floor, not a promise. While you are using the phone it " +
            "is close to that; sitting in a pocket overnight it stretches toward an hour " +
            "or worse, because Android batches background work the longer a device stays " +
            "idle. Anything you want straight away, open the app.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
        modifier = Modifier.testTag("sync-cadence"),
    )
}

/// Clear the device-local sync state and enrol again, on `syncAction`'s
/// thread and with `syncAction`'s reporting.
///
/// Enrolment goes through `state.syncSetup` rather than through a second
/// copy of the `configureSync` call: the shell nominates the directory and
/// the `KeyWrap` in exactly one place (ADR 0019 § 4), and a recovery path
/// that spelled them again is a recovery path that could disagree with the
/// thing it is recovering.
private fun resetSyncOnThisPhone(state: GiluneState, context: Context) {
    val setup = state.syncSetup ?: return
    state.syncAction("sync was reset — this phone is a new device and has to be paired again") {
        clearSyncState(java.io.File(syncDir(context)))
        state.syncStatus.value = setup(it)
        // The state that offered this button is over: a fresh identity was
        // minted and it opens. Clearing the flag here rather than leaving
        // it to the next vault open is what takes the destructive button
        // off the screen the moment it has nothing left to fix.
        state.identityUnreadable.value = false
    }
}

/// The alerts banner (§ 8). Absent entirely when nothing stands, because
/// a permanently drawn "all well" box is how people stop reading a
/// surface that has to be read when it does appear.
///
/// The sentences are `SyncAlerts.kt`'s, shared with the desktop and
/// carrying this shell's noun for itself. **The ground follows
/// `alertIsDamage`**: an unread entry from a newer version and a note
/// waiting for its own history are both worth saying and neither is a
/// fault, and painting them red teaches a person to stop reading the box.
@Composable
private fun SyncAlerts(state: GiluneState) {
    if (state.syncAlerts.isEmpty()) return
    val colours = MaterialTheme.colorScheme
    val damage = state.syncAlerts.any { alertIsDamage(it) }
    Surface(
        color = if (damage) colours.errorContainer else colours.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("sync-alerts"),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                alertsHeading(state.syncAlerts.toList()),
                style = MaterialTheme.typography.titleSmall,
                color = if (damage) colours.onErrorContainer else colours.onSurfaceVariant,
                modifier = Modifier.testTag("sync-alerts-heading"),
            )
            for (alert in state.syncAlerts) {
                Text(
                    alertSentence(alert.kind, alert.subject, alert.detail, PHONE_ALERT_WORDS),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (damage) colours.onErrorContainer else colours.onSurfaceVariant,
                    modifier = Modifier.testTag("sync-alert"),
                )
                // One condition asks for a decision rather than only
                // describing one: a device claiming to have been one this
                // phone removed. The offer belongs beside the sentence
                // that explains it, and nowhere else.
                val action = alertAction(alert.kind)
                if (action != null && alert.subject.isNotBlank()) {
                    TextButton(
                        onClick = { state.readmitDevice(alert.subject) },
                        modifier = Modifier.testTag("sync-alert-action"),
                    ) { Text(action) }
                }
            }
        }
    }
}

/// The first step of § 1's fallback chain made visible: what is out there
/// right now, asked for rather than watched.
@Composable
private fun DevicesOnThisNetwork(state: GiluneState, status: SyncStatus) {
    val colours = MaterialTheme.colorScheme
    // Whether anybody has looked yet. An empty list before a look means
    // nothing at all, and saying "nothing found" then would be a lie the
    // screen told itself.
    var looked by remember { mutableStateOf(false) }
    val looking = state.discovering.value

    Text("Devices on this network", style = MaterialTheme.typography.titleSmall)
    Text(
        "Looking is deliberate and costs a couple of seconds. Gilune does not do it on " +
            "its own — when it already knows a device's address it goes straight there.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            looked = true
            state.discoverDevices()
        },
        // Off means nothing is bound and nothing is announced, so a look
        // would only ever find devices that could not answer back.
        enabled = status.enabled && !looking,
        modifier = Modifier.heightIn(min = TouchTarget).testTag("sync-look"),
    ) { Text(if (looking) "Looking…" else "Look for devices") }

    if (!status.enabled) {
        Text(
            "Turn sync on to look for devices.",
            style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant,
            modifier = Modifier.testTag("sync-look-off"),
        )
        return
    }
    if (looking) {
        Text(
            "Looking…",
            style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant,
            modifier = Modifier.testTag("sync-looking"),
        )
        return
    }
    if (state.discovered.isEmpty()) {
        if (looked) {
            Text(
                NOTHING_FOUND,
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
                modifier = Modifier.testTag("sync-none-found"),
            )
        }
        return
    }
    for (device in state.discovered) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("found-row"),
        ) {
            Text(
                device.fingerprint,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                device.address,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
            )
            Text(
                foundDeviceState(device.state, device.compatible),
                style = MaterialTheme.typography.bodySmall,
                color = if (device.compatible) colours.onSurfaceVariant else colours.error,
            )
        }
    }
    Text(
        "A device shows its own fingerprint in its Sync settings. Comparing the two is " +
            "how you know you are pairing with what you meant to — copy the address above " +
            "into the field below and use the six-digit code that device is showing.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
}

@Composable
private fun ThisDevice(status: SyncStatus) {
    val colours = MaterialTheme.colorScheme
    Text("This device", style = MaterialTheme.typography.titleSmall)
    Text(status.deviceName, style = MaterialTheme.typography.bodyLarge)
    Text(
        status.fingerprint,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("sync-fingerprint"),
    )
    Text(
        "That is this phone's fingerprint. It is what your other device shows " +
            "beside this one in its own list, and comparing the two is how you know " +
            "you paired with what you meant to.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
    Text(
        keyProtectionSentence(status.keyProtection),
        style = MaterialTheme.typography.bodySmall,
        color = if (status.keyProtection == "none") colours.error else colours.onSurfaceVariant,
        modifier = Modifier.testTag("sync-protection"),
    )
    // Where this phone answers, in the kernel's own ranking (§ 1). Shown
    // because the other device may have to be told it: discovery is
    // filtered on plenty of networks, and the typed-address fallback needs
    // an address to type. A phone on Wi-Fi and a VPN at once has more than
    // one and cannot know which the desktop shares, so it lists them all
    // rather than picking — `deviceAddresses` is the same rule the desktop
    // draws from. Nothing is drawn while it is not listening: an address it
    // does not answer on is worse than none.
    val reachableAt = deviceAddresses(status)
    if (reachableAt.isNotEmpty()) {
        Text(
            reachableAt.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant,
            modifier = Modifier.testTag("sync-addresses"),
        )
        Text(
            if (reachableAt.size == 1) {
                "That is where this phone answers. Type it into the other device if it " +
                    "cannot find this one on its own."
            } else {
                "That is where this phone answers — it is on more than one network, and " +
                    "it cannot tell which one your other device is on. The first is the " +
                    "likeliest; if it does not work, try the next."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant,
        )
    }
    // Reachable and findable are two facts, and the honest case is the
    // one where they disagree (§ 1).
    Text(
        discoverySentence(status.enabled, status.advertising, status.discovery),
        style = MaterialTheme.typography.bodySmall,
        color = if (status.enabled && !status.advertising) colours.error else colours.onSurfaceVariant,
        modifier = Modifier.testTag("sync-discovery"),
    )
}

/// Both pairing paths, on one screen, always (§ 3).
@Composable
private fun AddADevice(state: GiluneState, context: Context) {
    val colours = MaterialTheme.colorScheme
    var scanning by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    // The runtime-permission idiom this shell already uses (CaptureScreen):
    // asked for at the moment of the tap, and a refusal is a line on the
    // message row rather than a dialog arguing with the person.
    val askForCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanning = true
        } else {
            state.message.value =
                "the camera permission was declined — the six-digit code still pairs devices"
        }
    }

    Text("Add a device", style = MaterialTheme.typography.titleSmall)
    Text(
        "On the other device, open Settings ▸ Sync and start pairing. It shows a " +
            "six-digit code, an address, and — on a desktop — a QR code. Either one " +
            "works, and the code is the one that always works.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )

    if (scanning) {
        QrScanner(
            onPayload = { payload ->
                scanning = false
                state.syncAction("paired") { it.pairFromQr(payload) }
            },
            onFailure = { detail ->
                scanning = false
                state.message.value = "the camera could not be opened: $detail"
            },
        )
        OutlinedButton(
            onClick = { scanning = false },
            modifier = Modifier.heightIn(min = TouchTarget).testTag("sync-scan-cancel"),
        ) { Text("Stop scanning") }
    } else {
        Button(
            onClick = {
                if (hasCameraPermission(context)) {
                    scanning = true
                } else {
                    askForCamera.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = Modifier.heightIn(min = TouchTarget).testTag("sync-scan"),
        ) { Text("Scan a QR code") }
    }

    Text("Or type the six-digit code", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = address,
        onValueChange = { address = it.trim() },
        singleLine = true,
        label = { Text("Address") },
        placeholder = { Text("192.168.1.20:47510") },
        modifier = Modifier.fillMaxWidth().testTag("sync-address"),
    )
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = normalisedPairingCode(it) },
            singleLine = true,
            label = { Text("Code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("sync-code"),
        )
        Button(
            onClick = {
                val host = address.trim()
                val typed = code
                state.syncAction("paired") { it.pairWithCode(host, typed) }
                address = ""
                code = ""
            },
            enabled = address.isNotBlank() && code.length == 6,
            modifier = Modifier.heightIn(min = TouchTarget).testTag("sync-pair"),
        ) { Text("Pair") }
    }
    Text(
        "The code works once and expires, and a few wrong guesses abandon it — the " +
            "other device will show you a new one.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
}

@Composable
private fun PairedDevices(state: GiluneState, status: SyncStatus) {
    val colours = MaterialTheme.colorScheme
    Text("Paired devices", style = MaterialTheme.typography.titleSmall)
    if (status.peers.isEmpty()) {
        Text(
            "None yet. Nothing can reach your notes until you pair a device here.",
            style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant,
            modifier = Modifier.testTag("no-peers"),
        )
        return
    }
    for (peer in status.peers) {
        PeerRow(state, peer)
    }
    Text(
        "Revoking a device stops what comes next: it can no longer reach this phone " +
            "and nothing it sends is accepted again, whichever device carries it. It " +
            "is not an undo — everything that device already sent you stays in your " +
            "vault, exactly as it is.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
        modifier = Modifier.testTag("revoke-truth"),
    )
}

@Composable
private fun PeerRow(state: GiluneState, peer: PeerInfo) {
    val colours = MaterialTheme.colorScheme
    val revoked = peer.state == "revoked"
    var editing by remember(peer.staticPub) { mutableStateOf(false) }
    var typed by remember(peer.staticPub) { mutableStateOf(peer.address) }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .padding(vertical = 4.dp)
            .testTag("peer-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                peer.name.ifBlank { "an unnamed device" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (revoked) colours.onSurfaceVariant else colours.onSurface,
            )
            Text(
                peer.fingerprint,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
            )
            Text(
                if (revoked) "revoked" else peer.address.ifBlank { "no address yet" },
                style = MaterialTheme.typography.bodySmall,
                color = if (revoked) colours.error else colours.onSurfaceVariant,
            )
        }
        if (!revoked) {
            OutlinedButton(
                onClick = {
                    state.syncAction("revoked ${peer.name.ifBlank { peer.fingerprint }}") {
                        it.revokePeer(peer.staticPub)
                    }
                },
                modifier = Modifier.heightIn(min = TouchTarget).testTag("peer-revoke"),
            ) { Text("Revoke") }
        }
    }
    // § 1's middle step, and the one a person reaches for when a network
    // routes unicast but filters the announcements: tell this phone where
    // the device is. It is stored rather than dialled once — an address
    // that worked is the address to try first next time.
    if (revoked) return
    if (!editing) {
        OutlinedButton(
            onClick = {
                typed = peer.address
                editing = true
            },
            modifier = Modifier.heightIn(min = TouchTarget).testTag("peer-address-edit"),
        ) { Text(if (peer.address.isBlank()) "Set an address" else "Change the address") }
        return
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it.trim() },
        singleLine = true,
        label = { Text("Address") },
        placeholder = { Text("192.168.1.20:51837") },
        modifier = Modifier.fillMaxWidth().testTag("peer-address-field"),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                state.setPeerAddress(peer.staticPub, typed, peer.name.ifBlank { "that device" })
                editing = false
            },
            enabled = typed.contains(':'),
            modifier = Modifier.heightIn(min = TouchTarget).testTag("peer-address-save"),
        ) { Text("Save") }
        OutlinedButton(
            onClick = { editing = false },
            modifier = Modifier.heightIn(min = TouchTarget).testTag("peer-address-cancel"),
        ) { Text("Cancel") }
    }
    Text(
        "The other device shows its address in its own Sync settings, as " +
            "host:port. Gilune tries it before looking for anything.",
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
    )
}
