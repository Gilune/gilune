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
// M5b has no discovery: a peer is reached at the address pairing recorded.
// The screen says that plainly rather than showing a device list that
// would only be empty for reasons nobody explained.

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

/// What this screen should say about the last thing that happened, or
/// null for nothing worth a line.
///
/// Every action here reports through `state.message` — pairing, revoking,
/// the switch, a declined camera — and on a phone that line is drawn above
/// the composer, which is two navigations away from the button that caused
/// it. So the sync screen draws it too, filtered exactly as the composer
/// filters it: the vault path and the opening line are a developer's
/// facts, not an outcome.
fun syncNotice(message: String): String? = message.takeUnless {
    it.isBlank() || it.startsWith("vault: ") || it == "opening vault…"
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

    // The outcome of the last thing pressed, said on the screen that has
    // the buttons. Without this the whole section is silent: a pairing
    // that failed, a code that expired and a declined camera all report
    // through the shared message line, which is drawn above the composer
    // two navigations away.
    syncNotice(state.message.value)?.let { notice ->
        Text(
            notice,
            style = MaterialTheme.typography.bodyMedium,
            color = colours.onSurfaceVariant,
            modifier = Modifier.testTag("sync-message"),
        )
    }

    if (status == null) {
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
    AddADevice(state, context)

    HorizontalDivider(color = colours.outlineVariant)
    PairedDevices(state, status)

    HorizontalDivider(color = colours.outlineVariant)
    OutlinedButton(
        onClick = { state.syncDevices() },
        modifier = Modifier.heightIn(min = TouchTarget).testTag("sync-now"),
    ) { Text("Sync with devices now") }
    Text(
        "Exchanges everything new with each paired device this phone has an address " +
            "for. A device that is not reachable is skipped and reported, not hunted " +
            "for: finding devices on their own arrives in the next stage.",
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
}
