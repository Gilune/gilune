// Settings ▸ Sync — the desktop half of ADR 0019, M5b and now M5c. It
// replaced the Devices placeholder, whose prose promised exactly this.
//
// Five things this screen has to get right, because each is a decision
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
//  - **Halting has to be visible** (§ 8). The kernel stops a peer's
//    stream rather than applying half a history, and saying nothing
//    about it would make a halted sync look exactly like an idle one —
//    "the failure mode that would make sync look like it works". So a
//    standing alert is the first thing on this screen, in words, above
//    the switch, and nothing here ever prints the kind string the kernel
//    raised it under.
//  - **Discovery is a button, and the last step of the chain is words**
//    (§ 1). Browsing costs seconds and multicast traffic and a cached
//    address is tried first for exactly that reason, so nothing here
//    looks on its own. Reachable-but-not-findable gets named rather than
//    hidden, because the typed-address fallback exists for it.
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uniffi.gilune_ffi.FoundDevice
import uniffi.gilune_ffi.IntegrityAlert
import uniffi.gilune_ffi.PairingOffer
import uniffi.gilune_ffi.PeerInfo
import uniffi.gilune_ffi.QrMatrix
import uniffi.gilune_ffi.SyncStatus

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

    // Above the switch, because a stream that halted last night is the
    // thing a person came here to find out about, and everything below
    // reads as if sync were working until it is said (§ 8).
    AlertsBanner(state.syncAlerts)

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
    // Meant to be on, and not listening. The switch is remembered across
    // restarts, so this is the state where a machine came back up and
    // could not take its port — busy, or a firewall that said no. Showing
    // the switch quietly off would be the wrong answer twice: it hides a
    // fault, and it invites a person to flip a switch that is already
    // where they left it.
    if (status.wanted && !status.enabled) {
        Text(
            "Sync is switched on, but this machine could not start listening — usually " +
                "another program already has the port, or Windows blocked it. Turn the " +
                "switch off and on again to retry.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colours.error,
        )
    }
    // The firewall paragraph is § 2 in user words. Three things it has
    // to carry, and each was a decision: the prompt is caused by this
    // switch and by nothing else, so it appears the first time and never
    // again; Private is the profile this is for and Public is the one it
    // is not; and a person without administrator rights is told what
    // happened rather than left with a switch that silently does
    // nothing. The `netsh` line itself belongs in the guide, not here —
    // a settings screen is no place for a command to be mistyped from.
    Text(
        "Switching this on is what opens a port on this machine, and nothing else " +
            "does — capture, notes and everything else work with it off. The first " +
            "time you turn it on, and only that first time, Windows asks whether to " +
            "allow Gilune through the firewall: tick Private networks and leave " +
            "Public unticked, so this device answers on your own network and not on " +
            "a café's. If you are not an administrator on this machine Windows will " +
            "simply say it was blocked — a supported state rather than a fault. " +
            "Someone with administrator rights can add the rule once, and the user " +
            "guide has the exact command.",
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
    Fact("found by others", discoveryFact(status.enabled, status.advertising, status.discovery))
    Text(
        discoveryNote(status.enabled, status.advertising, status.discovery),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
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

    // Between showing a code and typing one, because what a browse
    // returns for a stranger is a fact and nothing more: an unpaired
    // device is offered no button here, since pairing still costs a code
    // (§ 3), and the section that takes that code is the next one down.
    HorizontalDivider(color = colours.outlineVariant)
    DevicesOnThisNetwork(state, status)

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

/// Standing integrity conditions, in words (§ 8).
///
/// The kernel has raised these since M5a and nothing could show them,
/// which meant a peer whose stream had halted looked exactly like a peer
/// with nothing to send. Every sentence here has to answer the same two
/// questions the kernel's own policy answers — what stopped, and why —
/// and then say the thing that keeps this from reading as a loss:
/// applying resumes from where it stopped when the condition clears.
@Composable
private fun AlertsBanner(alerts: List<IntegrityAlert>) {
    if (alerts.isEmpty()) return
    val colours = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colours.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            alertsHeading(alerts.map { it.kind }),
            style = MaterialTheme.typography.titleSmall,
            color = colours.onErrorContainer,
        )
        for (alert in alerts) {
            Text(
                alertSentence(alert.kind, alert.subject, alert.detail),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colours.onErrorContainer,
            )
        }
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

/// What is on this network, when somebody asks (§ 1).
///
/// Deliberately a button. A paired device is reached at the address it
/// gave, and discovery runs only when that fails — a screen that browsed
/// on its own would spend the whole sync budget looking for devices it
/// already knows, and would put multicast traffic on a network every
/// time Settings opened.
@Composable
private fun DevicesOnThisNetwork(state: GiluneState, status: SyncStatus) {
    val colours = MaterialTheme.colorScheme
    // An empty list means two different things — nobody has asked, and
    // nobody answered — and only the second is news. Nothing else can
    // tell them apart, so this remembers whether anybody looked.
    var looked by remember { mutableStateOf(false) }
    val busy = state.discovering.value

    Text("Devices on this network", style = MaterialTheme.typography.titleSmall)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = {
                looked = true
                state.discoverDevices()
            },
            enabled = status.enabled && !busy,
        ) { Text(if (busy) "looking…" else "Look for devices", fontSize = 12.sp) }
        Text(
            if (status.enabled) {
                "Asks every Gilune on this network to say it is there. Pairing never " +
                    "needs this — a device you have paired is reached at the address " +
                    "it gave — so looking happens when you ask and not otherwise."
            } else {
                "Turn sync on first — announcing this device and looking for others " +
                    "are the same switch."
            },
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colours.onSurfaceVariant,
        )
    }

    for (found in state.discovered) FoundRow(found)

    if (looked && !busy && state.discovered.isEmpty()) {
        Text(
            emptyDiscoveryNote(status.discovery),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colours.onSurfaceVariant,
        )
    }
}

/// One device an advertisement named. The fingerprint is everything the
/// advertisement carried besides an address and a version — the human
/// name is exchanged inside the encrypted handshake (§ 1), so a row for
/// a device this one has never paired with cannot say what it is called.
@Composable
private fun FoundRow(found: FoundDevice) {
    val colours = MaterialTheme.colorScheme
    val wrong = !found.compatible || found.state == "revoked"
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
                found.fingerprint,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = colours.onSurface,
            )
            Text(
                "${found.address} · ${found.state}",
                fontSize = 12.sp,
                color = colours.onSurfaceVariant,
            )
            Text(
                foundDeviceNote(found.state, found.compatible),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (wrong) colours.error else colours.onSurfaceVariant,
            )
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
    // The address is the manual step of § 1's fallback chain, so it is
    // an affordance rather than a field: a device that has not moved
    // never needs it, and a row where every peer showed a text box would
    // read as if it did.
    var editing by remember(peer.staticPub) { mutableStateOf(false) }
    var typed by remember(peer.staticPub) { mutableStateOf(peer.address) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colours.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
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
                    peerActivity(peer.state, peer.lastSeenMs),
                    fontSize = 12.sp,
                    color = if (revoked) colours.error else colours.onSurfaceVariant,
                )
                Text(
                    peer.address.ifBlank { "no address recorded" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colours.onSurfaceVariant,
                )
            }
            if (!revoked) {
                TextButton(onClick = {
                    typed = peer.address
                    editing = !editing
                }) { Text(if (editing) "Cancel" else "Change address", fontSize = 11.sp) }
                OutlinedButton(onClick = {
                    state.syncAction(
                        "${peer.name.ifBlank { "that device" }} can no longer connect",
                    ) { it.revokePeer(peer.staticPub) }
                }) { Text("Revoke", fontSize = 11.sp) }
            }
        }
        if (editing && !revoked) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            "192.168.0.10:47100",
                            fontSize = 12.sp,
                            color = colours.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.width(240.dp),
                )
                Button(
                    onClick = {
                        // Taken now, for the reason the pairing field
                        // records: `syncAction` runs a moment later on
                        // another thread, by which time the field has
                        // been left behind.
                        val address = typed.trim()
                        editing = false
                        state.setPeerAddress(
                            peer.staticPub,
                            address,
                            peer.name.ifBlank { "that device" },
                        )
                    },
                    enabled = typed.isNotBlank() && typed.trim() != peer.address,
                ) { Text("Use this address", fontSize = 12.sp) }
            }
            Text(
                "This is where Gilune looks for that device first, and looking on the " +
                    "network only happens when it fails. If it moved to another " +
                    "network its address will have changed: its own Sync settings show " +
                    "the new one. Typing it is also the way through a network that " +
                    "carries ordinary traffic but stops devices finding each other.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colours.onSurfaceVariant,
            )
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

/// A paired device that has never been reached is not a device that was
/// last reached at "never": pairing succeeded and the first exchange has
/// simply not happened, which is an ordinary state on the evening
/// somebody pairs two machines and closes the lid on one.
internal fun peerActivity(state: String, lastSeenMs: Long): String {
    val standing = if (state == "revoked") "revoked" else "paired"
    return if (lastSeenMs <= 0L) {
        "$standing · not synced yet"
    } else {
        "$standing · last synced ${lastSeen(lastSeenMs)}"
    }
}

// -------------------------------------------------------------- words
//
// Everything below is a sentence and nothing else, kept out of the
// composables so the wording can be asserted without a screen. That is
// the point rather than a convenience: these sentences are the whole of
// what § 8 asks for, and a sentence nothing tests is a sentence that
// quietly becomes wrong.

/// The alert's subject and detail, carried without claiming what kind of
/// thing they are — a device id, an operation id and a blob id all
/// arrive in the same field, and a sentence that guessed would sooner or
/// later guess wrong.
private fun aside(vararg parts: String): String {
    val kept = parts.filter { it.isNotBlank() }
    return if (kept.isEmpty()) "" else kept.joinToString(", ", prefix = " (", postfix = ")")
}

/// What a standing alert says (§ 8), in user words.
///
/// The kind string is the kernel's name for a policy, never a thing to
/// show someone: each one maps to what stopped, why it stopped, and the
/// fact that applying resumes from the frontier once the condition
/// clears — which is why none of this is a loss. `sync-unknown-op-kind`
/// is deliberately not written as damage, because it is not: it is a
/// newer version's work, kept byte for byte and passed on unread.
internal fun alertSentence(kind: String, subject: String, detail: String): String {
    val about = aside(subject, detail)
    return when (kind) {
        "sync-gap" ->
            "Changes from another device arrived with one of them missing$about, so " +
                "nothing after the gap has been taken in: applying the rest would " +
                "record a history that never happened. Everything before the gap is " +
                "already here, and Gilune picks up where it left off as soon as the " +
                "missing change arrives."
        "sync-corrupt-operation" ->
            "A change did not arrive as it was written$about, so it has been left " +
                "where it is and nothing after it has been taken in — a damaged " +
                "change is not something to guess at. Gilune picks up where it left " +
                "off as soon as an undamaged copy arrives."
        "sync-unsupported-version" ->
            "Changes from another device are written in a version of Gilune's sync " +
                "format this one does not understand$about, so none of them have " +
                "been taken in. Bringing both devices to the same version is what " +
                "clears it, and Gilune picks up where it left off when it does."
        "sync-unknown-op-kind" ->
            "Something a newer version of Gilune wrote came through$about, and this " +
                "device does not know how to read that kind of entry. Nothing was " +
                "lost and nothing has stopped: it is kept exactly as it arrived and " +
                "passed on to your other devices unread, and Gilune picks up where " +
                "it left off with it once this device is running a version that " +
                "understands it."
        "sync-limit-exceeded" ->
            "One exchange reached the limit on how much a device may send at " +
                "once$about, so it was ended tidily rather than cut off part way " +
                "through a change. Nothing is half applied, and Gilune picks up " +
                "where it left off at the next sync."
        "sync-blob-digest-mismatch" ->
            "A recording or attachment did not match the fingerprint it was sent " +
                "with$about, so the bytes were thrown away rather than kept as if " +
                "they were right. Nothing else stopped, and Gilune picks up where it " +
                "left off when that file arrives intact."
        "sync-revoked-device" ->
            "Changes first made on a device you revoked were offered by another " +
                "device$about, and this one refused them. What that device sent " +
                "before you revoked it is still in your vault — revoking protects " +
                "what comes next, it does not undo. Gilune picks up where it left " +
                "off with every device you have not revoked."
        // A kind this build has never heard of is still a halt, and a
        // halt nobody is told about is the failure mode § 8 names. So
        // there is a sentence for it, and it is still not the kind
        // string.
        else ->
            "Something about a change from another device stopped it being taken " +
                "in$about. Nothing past it has been applied and nothing is half " +
                "done, and Gilune picks up where it left off once the condition " +
                "clears."
    }
}

/// The banner's heading. An unknown operation kind is not damage, so a
/// screen holding nothing else must not open by saying sync stopped.
internal fun alertsHeading(kinds: List<String>): String = when {
    kinds.isEmpty() -> ""
    kinds.all { it == "sync-unknown-op-kind" } -> "Sync kept something it could not read"
    kinds.size == 1 -> "Sync stopped short of taking everything in"
    else -> "Sync stopped short of taking everything in, for ${kinds.size} reasons"
}

/// Whether this device can be found, as against whether it can be
/// reached. Two flags rather than one, because binding TCP can succeed
/// while UDP 5353 fails, and a single flag would tell somebody their
/// device was findable when it was only reachable (§ 1).
internal fun discoveryFact(enabled: Boolean, advertising: Boolean, discovery: String): String =
    when {
        !enabled -> "no — sync is off"
        advertising -> "yes, over $discovery"
        discovery.isBlank() || discovery == "none" -> "no — nothing here can announce it"
        else -> "no — reachable, but not findable"
    }

/// The paragraph under that fact. The reachable-but-not-findable case is
/// the one this exists for: it is the state § 1's fallback chain was
/// designed around, and it has to point at the address rather than leave
/// somebody watching a device that never appears.
internal fun discoveryNote(enabled: Boolean, advertising: Boolean, discovery: String): String =
    when {
        !enabled ->
            "Nothing is announced and no port is open while sync is off, which is " +
                "the whole of what the switch above does."
        advertising ->
            "Other devices on this network can find this one by looking for it, so " +
                "pairing does not need an address typed in — though the address " +
                "still works, and is faster."
        discovery.isBlank() || discovery == "none" ->
            "This device is reachable but has no way to announce itself, so nothing " +
                "will ever find it by looking and no amount of waiting changes that. " +
                "Give the other device the address shown here — it is the same " +
                "pairing either way, and the address is the route that always works."
        else ->
            "This device is reachable but is not announcing itself: something on " +
                "this machine or this network stopped it, and it will not appear on " +
                "another device's list however long you look. That is not a fault, " +
                "and it does not stop anything — give the other device the address " +
                "shown here, which is what pairing uses anyway."
    }

/// What a browse found about one device, given that a stranger's row can
/// offer nothing but the fact. Pairing costs a code whichever way the
/// device was found (§ 3), so there is no button here to imply otherwise.
internal fun foundDeviceNote(state: String, compatible: Boolean): String = when {
    !compatible ->
        "That device is running a version of Gilune this one cannot talk to. " +
            "Bringing both to the same version is what fixes it."
    state == "paired" ->
        "Already paired with this one. Its address is kept up to date every time " +
            "the two of them sync."
    state == "revoked" ->
        "You revoked this device, so it cannot connect. Pairing it again would be " +
            "a new pairing rather than an undo."
    else ->
        "Not paired with this one. Start pairing on that device and enter its code " +
            "below — being visible on a network has never been permission to sync."
}

/// A browse that found nothing. The honest answer is almost always the
/// network rather than the timing, and § 1 asks for words rather than a
/// spinner that never ends.
internal fun emptyDiscoveryNote(discovery: String): String =
    if (discovery.isBlank() || discovery == "none") {
        "Nothing here could look, so nothing was ever going to be found. Pair by " +
            "address and code instead — both are on the other device's Sync settings " +
            "while its code is open."
    } else {
        "No devices answered. Many guest, hotel and office networks deliberately " +
            "stop devices seeing each other at all, and no amount of waiting changes " +
            "that. Pairing by address and code still works on those networks: the " +
            "other device shows both while its code is open."
    }
