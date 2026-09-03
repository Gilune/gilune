// Settings ▸ Sync — the desktop half of ADR 0019 § M5b. It replaces the
// Devices placeholder, whose prose promised exactly this.
//
// Three things this screen has to get right, because each is a decision
// the ADR made rather than a layout choice:
//
//  - **The switch is the moment the socket is bound** (§ 2), and nothing
//    else binds it. So the explanation of the Windows firewall prompt
//    sits beside the switch, before it is flipped, and it asks for the
//    Private profile by name.
//  - **The six-digit path is never behind a tab** (§ 3). The code and the
//    QR stand side by side, because desktop-to-desktop pairing has no QR
//    at all and the code path can never read as the secondary one.
//  - **Revocation is not an undo** (§ 9). It protects what comes next;
//    what a device already sent stays in the vault, and this screen says
//    so where the button is rather than in a document nobody opens.
//
// The device exchange behind "Sync with devices now" is `syncDevices`,
// never `sync` — that has meant "reconcile the vault's files" since M2,
// and the Vault page's own "Sync now" is still that one.

package org.gilune.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uniffi.gilune_ffi.PairingOffer
import uniffi.gilune_ffi.PeerInfo
import uniffi.gilune_ffi.QrMatrix

/// Big enough to scan from a phone held at arm's length, and no bigger:
/// the code beside it is the path that always works.
private val QR_SIDE = 240.dp

/// The quiet zone the QR specification asks for, in modules. Without it
/// a reader has nothing to find the code's edges against.
private const val QR_QUIET_MODULES = 4

@Composable
internal fun SyncSection(state: GiluneState) {
    val colours = MaterialTheme.colorScheme
    val status = state.syncStatus.value

    Text("Sync between your devices", style = MaterialTheme.typography.titleSmall)

    if (status == null) {
        Text(
            "This device has not enrolled yet. Sync becomes available once a vault " +
                "is open — if it stays unavailable, the status line at the foot of " +
                "the window says why.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colours.onSurfaceVariant,
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Switch(
            checked = status.enabled,
            onCheckedChange = { on ->
                state.syncAction(
                    if (on) "sync is on — this device is reachable" else "sync is off",
                ) { it.setSyncEnabled(on) }
            },
        )
        Text("Let other devices reach this one", fontSize = 13.sp)
    }
    Text(
        "Switching this on is what opens a port on this machine, and nothing else " +
            "does — capture, notes and everything else work with it off. Windows " +
            "will ask once whether to allow Gilune through the firewall: allow it " +
            "on Private networks only. Devices are not found automatically yet, so " +
            "pairing uses the address shown below.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )

    HorizontalDivider(color = colours.outlineVariant)
    Text("This device", style = MaterialTheme.typography.titleSmall)
    Fact("name", status.deviceName)
    Fact("fingerprint", status.fingerprint)
    Fact("key protection", status.keyProtection)
    status.listeningOn?.let { Fact("listening on", it) }
    Text(
        if (status.keyProtection == "none") {
            "Nothing on this machine protected this device's identity key, so it is " +
                "stored as it is. Anyone who can read your files can read it."
        } else {
            "Windows protects this device's identity key with your account, so another " +
                "account on this machine — or the drive on its own — cannot read it. It " +
                "is not protected from programs running as you."
        },
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = if (status.keyProtection == "none") colours.error else colours.onSurfaceVariant,
    )
    // What opening this vault here meant, when it meant anything. The
    // ADR asks the UI to say so plainly "rather than letting a user
    // wonder why their laptop appears twice" — and the two cases are not
    // the same news, so they are not the same sentence.
    when (status.enrolment) {
        "adopted" -> Text(
            "This vault had no device identity recorded yet, so this machine claimed the " +
                "one the vault was already using. Nothing about your notes changed.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colours.onSurfaceVariant,
        )
        "re-enrolled" -> Text(
            "This vault last belonged to a different device, so this machine enrolled as " +
                "a new one with a history of its own. That is why one vault can show two " +
                "devices — a vault copied or restored somewhere else always becomes a new " +
                "device, and the original is unaffected.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colours.onSurfaceVariant,
        )
    }

    HorizontalDivider(color = colours.outlineVariant)
    PairADevice(state, status.pairing, status.enabled)

    HorizontalDivider(color = colours.outlineVariant)
    EnterACode(state, status.enabled)

    HorizontalDivider(color = colours.outlineVariant)
    Text("Paired devices", style = MaterialTheme.typography.titleSmall)
    if (status.peers.isEmpty()) {
        Text(
            "None yet. Pair one above, or enter a code this device shows you.",
            fontSize = 12.sp,
            color = colours.onSurfaceVariant,
        )
    } else {
        for (peer in status.peers) PeerRow(state, peer)
        Text(
            "Revoking a device stops it connecting again and closes any session with " +
                "it now. It does not take back what it already sent you: everything " +
                "already in your vault stays there. Pairing it again later is a new " +
                "pairing, not an undo.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colours.onSurfaceVariant,
        )
    }

    HorizontalDivider(color = colours.outlineVariant)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = { state.syncDevices() },
            enabled = status.peers.any { it.state == "paired" },
        ) { Text("Sync with devices now", fontSize = 12.sp) }
        Text(
            "Exchanges changes with every paired device this one has an address for. " +
                "Different from Sync now on the Vault page, which only re-reads the " +
                "files in this vault.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/// The listening half of a pairing: this device shows a code, and shows
/// the same secret as a QR beside it for a phone that would rather scan
/// than type. Both are the same exchange (§ 3) — only the entropy of the
/// secret differs — so neither is hidden behind the other.
@Composable
private fun PairADevice(state: GiluneState, offer: PairingOffer?, enabled: Boolean) {
    val colours = MaterialTheme.colorScheme
    Text("Pair a device", style = MaterialTheme.typography.titleSmall)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = {
                state.syncAction("a pairing code is open") { it.startPairing() }
            },
            enabled = enabled,
        ) { Text(if (offer == null) "Start pairing" else "New code", fontSize = 12.sp) }
        Text(
            if (enabled) {
                "Opens a code the other device can use once, for a few minutes."
            } else {
                "Turn sync on first — a pairing needs a port to answer on."
            },
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colours.onSurfaceVariant,
        )
    }

    if (offer == null) return

    // The clock the countdown reads, and the only place this screen asks
    // the kernel anything on its own.
    //
    // It ticks **only while a window is open**, which is exactly the
    // stretch in which facts arrive from somewhere other than this
    // window: the peer that types the code is on the other device, and
    // the attempts it burns are not this device's doing. So each tick
    // re-reads the facts — the new peer appears in the list the moment it
    // pairs, a failed guess shows one fewer attempt, and an expired code
    // leaves the screen when it stops working rather than at the next
    // thing the user happens to click. Nothing polls once the window has
    // closed.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(offer.code) {
        while (true) {
            nowMs = System.currentTimeMillis()
            state.refreshSync()
            if (nowMs >= offer.expiresMs) break
            delay(1000)
        }
    }
    val remaining = offer.expiresMs - nowMs

    // The QR is asked for once per code. It is the same secret the code
    // carries, encoded in Rust and painted here (§ 7) — the payload
    // itself never crosses as a string this shell could log.
    val qr = remember(offer.code) {
        state.gilune.value?.let { g -> runCatching { g.pairingQr() }.getOrNull() }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "On the other device, choose \"Enter a code from another device\" and " +
                    "type these.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colours.onSurfaceVariant,
            )
            Text(
                if (remaining > 0) spacedCode(offer.code) else "expired",
                fontSize = 34.sp,
                fontFamily = FontFamily.Monospace,
                color = if (remaining > 0) colours.onSurface else colours.error,
            )
            Fact("address", offer.address)
            Text(
                if (remaining > 0) {
                    "Expires in ${countdown(remaining)} · ${offer.attemptsLeft} attempts left · " +
                        "usable once"
                } else {
                    "This code has expired. Start a new one — a code that could be tried " +
                        "forever would not be worth six digits."
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (remaining > 0) colours.onSurfaceVariant else colours.error,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (qr != null && remaining > 0) {
                QrCode(qr)
                Text(
                    "or scan with the phone",
                    fontSize = 12.sp,
                    color = colours.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/// The joining half: this device types what another one is showing.
@Composable
private fun EnterACode(state: GiluneState, enabled: Boolean) {
    val colours = MaterialTheme.colorScheme
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Text("Enter a code from another device", style = MaterialTheme.typography.titleSmall)
    Text(
        "The other device's Sync settings show both of these while its code is open.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            singleLine = true,
            placeholder = {
                Text("192.168.0.10:47100", fontSize = 12.sp, color = colours.onSurfaceVariant)
            },
            modifier = Modifier.width(240.dp),
        )
        androidx.compose.material3.OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            placeholder = {
                Text("123456", fontSize = 12.sp, color = colours.onSurfaceVariant)
            },
            modifier = Modifier.width(140.dp),
        )
        Button(
            onClick = {
                // Both values are taken **now**. `syncAction` runs its
                // lambda on another thread a moment later, and a lambda
                // that read `code` there would read whatever the field
                // holds by then — which is nothing, because clearing it
                // is the next line. Cost one failed pairing to find.
                val host = address.trim()
                val secret = code.trim()
                // A code is single-use whether or not it worked, so the
                // field never keeps one for a second try.
                code = ""
                state.syncAction("paired with the device at $host") {
                    it.pairWithCode(host, secret)
                }
            },
            enabled = enabled && address.isNotBlank() && code.isNotBlank(),
        ) { Text("Pair", fontSize = 12.sp) }
    }
    if (!enabled) {
        Text(
            "Turn sync on first — pairing exchanges keys both ways.",
            fontSize = 12.sp,
            color = colours.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeerRow(state: GiluneState, peer: PeerInfo) {
    val colours = MaterialTheme.colorScheme
    val revoked = peer.state == "revoked"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colours.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                peer.name.ifBlank { "unnamed device" },
                style = MaterialTheme.typography.titleSmall,
                color = if (revoked) colours.onSurfaceVariant else colours.onSurface,
            )
            Text(
                peer.fingerprint,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colours.onSurfaceVariant,
            )
            Text(
                if (revoked) {
                    "revoked · last heard from ${lastSeen(peer.lastSeenMs)}"
                } else {
                    "paired · last heard from ${lastSeen(peer.lastSeenMs)}"
                },
                fontSize = 12.sp,
                color = if (revoked) colours.error else colours.onSurfaceVariant,
            )
        }
        if (!revoked) {
            OutlinedButton(onClick = {
                state.syncAction("${peer.name.ifBlank { "that device" }} can no longer connect") {
                    it.revokePeer(peer.staticPub)
                }
            }) { Text("Revoke", fontSize = 11.sp) }
        }
    }
}

/// The QR, painted from the module matrix the kernel handed over.
///
/// Deliberately black on white in both themes: this is a machine-readable
/// object, not a piece of the interface, and a light-on-dark code is a
/// code some readers refuse.
@Composable
private fun QrCode(matrix: QrMatrix) {
    val side = matrix.width.toInt()
    if (side <= 0 || matrix.modules.size < side * side) return
    Canvas(Modifier.size(QR_SIDE).clip(RoundedCornerShape(4.dp)).background(Color.White)) {
        val span = side + QR_QUIET_MODULES * 2
        val module = size.minDimension / span
        for (row in 0 until side) {
            for (col in 0 until side) {
                if (!matrix.modules[row * side + col]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(
                        (col + QR_QUIET_MODULES) * module,
                        (row + QR_QUIET_MODULES) * module,
                    ),
                    // A hair of bleed, so rounding never leaves white
                    // hairlines between neighbouring dark modules.
                    size = Size(module + 0.5f, module + 0.5f),
                )
            }
        }
    }
}

/// One labelled fact, in the monospace the rest of Settings uses for
/// things a person compares character by character.
@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$label:",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/// `123 456` — six digits are read aloud in threes, and typed in threes.
private fun spacedCode(code: String): String =
    if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

private fun countdown(remainingMs: Long): String {
    val seconds = (remainingMs / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun lastSeen(ms: Long): String =
    if (ms <= 0L) {
        "never"
    } else {
        java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))
    }
