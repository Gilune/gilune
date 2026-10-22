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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

    // Read the facts and the standing conditions **on entry**, on the
    // phone's own idiom (`SyncScreen.SyncSection`). Without this the
    // desktop showed whatever the last action had left behind: on a fresh
    // launch, nothing — so a stream that halted overnight was invisible
    // until somebody pressed a button, which is § 8's failure mode with a
    // longer fuse. Off the main thread, because reading them touches the
    // identity file.
    LaunchedEffect(state.gilune.value) {
        if (state.gilune.value != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { state.refreshSync() }
        }
    }

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
    AlertsBanner(state.syncAlerts, state::readmitDevice)

    // The firewall is this switch's other half on Windows: the port is
    // bound here, and whether anything reaches it is a rule Windows holds.
    // Switching on asks for the rule when it is not there (one elevated
    // prompt, naming Gilune); the row below says where it stands.
    val firewallScope = rememberCoroutineScope()
    val firewall = remember { FirewallState(installedApp()?.exe, firewallScope) }
    LaunchedEffect(status.enabled) { firewall.refresh() }
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
                if (on) firewall.allowIfNeeded()
            },
        )
        Text("Let other devices reach this one", fontSize = 13.sp)
    }
    FirewallRow(firewall)
    // Meant to be on, and not listening. The switch is remembered across
    // restarts, so this is the state where a machine came back up and
    // could not take its port, because another program holds it. (A
    // firewall never makes a bind fail; it only drops what arrives.)
    // Showing the switch quietly off would be the wrong answer twice: it
    // hides a fault, and it invites a person to flip a switch that is
    // already where they left it.
    if (status.wanted && !status.enabled) {
        Text(
            "Sync is switched on, but this machine could not start listening — usually " +
                "another program already has the port. Turn the switch off and on again " +
                "to retry.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colours.error,
        )
    }
    // The firewall paragraph is § 2 in user words. The rule is Private
    // profile only and the app asks for it, once, with the one prompt
    // Windows has for administrator approval; an all-users install already
    // carries it from the installer. So the paragraph says: the port opens
    // here and nowhere else; the rule is asked for once and names Gilune;
    // and a refusal is a supported state the row above reports, not a
    // fault. The `netsh` line itself belongs in the guide, not here — a
    // settings screen is no place for a command to be mistyped from.
    Text(
        "Switching this on is what opens a port on this machine, and nothing else " +
            "does — capture, notes and everything else work with it off. Other devices " +
            "reach it through a Windows Firewall rule for private networks only, never " +
            "public ones, so this device answers on your own network and not on a " +
            "café's. The first time you switch on, Gilune asks Windows for that rule " +
            "and Windows asks once for an administrator's approval; the line above the " +
            "switch says where it stands, and offers to ask again if it was refused. " +
            "The user guide has the command an administrator can run instead.",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )

    HorizontalDivider(color = colours.outlineVariant)
    Text("This device", style = MaterialTheme.typography.titleSmall)
    Fact("name", status.deviceName)
    Fact("fingerprint", status.fingerprint)
    Fact("key protection", status.keyProtection)
    // Every address this machine answers on, not just the best-ranked one.
    // A laptop with a hypervisor or a VPN has several and cannot know which
    // one the other device shares; showing one was the difference between
    // somebody reading the right address off this screen and giving up.
    for (address in deviceAddresses(status)) {
        Fact("listening on", address)
    }
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
/// with nothing to send. The sentences themselves live in `SyncAlerts.kt`
/// beside the phone's — one table, because a kind worded on one shell and
/// silent on the other is the same failure in half the places.
///
/// **A banner holding nothing that is damage is not painted as an error.**
/// An unread entry from a newer version and a note waiting for its own
/// history are both worth saying and neither is a fault, and a red box is
/// how a person learns to stop reading the surface that has to be read
/// when it does go red.
/// One condition carries a decision rather than only a description — a
/// device claiming to have been one this vault removed — so the banner can
/// offer it. [onAct] is given the alert's subject, which for that kind is
/// the device id to re-admit.
@Composable
private fun AlertsBanner(alerts: List<IntegrityAlert>, onAct: (String) -> Unit) {
    if (alerts.isEmpty()) return
    val colours = MaterialTheme.colorScheme
    val damage = alerts.any { alertIsDamage(it) }
    val ground = if (damage) colours.errorContainer else colours.surfaceVariant
    val ink = if (damage) colours.onErrorContainer else colours.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            alertsHeading(alerts),
            style = MaterialTheme.typography.titleSmall,
            color = ink,
        )
        for (alert in alerts) {
            Text(
                alertSentence(alert.kind, alert.subject, alert.detail, DESKTOP_ALERT_WORDS),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = ink,
            )
            val action = alertAction(alert.kind)
            if (action != null && alert.subject.isNotBlank()) {
                TextButton(onClick = { onAct(alert.subject) }) { Text(action) }
            }
        }
    }
}

/// How many countdown ticks pass between two reads of the kernel's facts
/// while a pairing window is open.
///
/// Three seconds is the whole argument: the facts that change under this
/// window come from the *other* device — a peer that pairs, an attempt it
/// burns — so they arrive at human speed, and a second-by-second query
/// bought nothing but a blocked frame.
internal const val PAIRING_FACTS_EVERY_TICKS = 3

/// Every address a pairing window should offer, in the kernel's own order
/// (best first).
///
/// A machine cannot know which of its addresses a phone shares a network
/// with, so it publishes every candidate and lets the joiner dial. The
/// order is the kernel's ranking — same segment first, then routable, and
/// an unusable address is dropped before it ever gets here — so the first
/// row is the one to try first.
///
/// `address` is the first of `addresses` by contract. It is kept as the
/// fallback rather than assumed away: a kernel that could name only one
/// must still put that one on the screen, because a pairing window with no
/// address on it is a window that cannot be used.
///
/// Blank is dropped rather than drawn: an empty `address:` row is a fact
/// nobody can act on.
///
/// The listening half of the same question is [deviceAddresses], which is
/// shared with the phone; this one stays here because only the desktop
/// shows a window's addresses as text a person reads out.
internal fun pairingAddresses(offer: PairingOffer): List<String> {
    val published = offer.addresses.filter { it.isNotBlank() }
    return published.ifEmpty { listOf(offer.address).filter { it.isNotBlank() } }
}

/// Whether this tick of the countdown should also re-read the facts.
///
/// Pure, so the two rates can be asserted without a window: the clock at
/// 1 Hz, the kernel every [PAIRING_FACTS_EVERY_TICKS], and always once at
/// expiry — the last read is what takes an unusable code off the screen.
internal fun pairingShouldRefresh(tick: Int, expired: Boolean): Boolean =
    expired || tick % PAIRING_FACTS_EVERY_TICKS == 0

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
    // **Two rates, and the kernel is never asked on the UI thread.** The
    // first round of this window ticked once a second and called
    // `refreshSync` on every tick — from the composition's own dispatcher,
    // which is the main thread. `refreshSync` opens the identity file and
    // reads the peer roster, so a pairing window sat there stalling the
    // frame clock once a second for the whole three minutes a code lives,
    // on the one screen where a person is watching a countdown.
    //
    // The countdown is a second hand and stays at 1 Hz; it needs nothing
    // but the clock. The facts are a query and go to a background
    // dispatcher every third tick (`pairingShouldRefresh`) — and always
    // once more the moment the code expires, so the window stops offering
    // something that no longer works.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(offer.code) {
        var tick = 0
        while (true) {
            nowMs = System.currentTimeMillis()
            val expired = nowMs >= offer.expiresMs
            if (pairingShouldRefresh(tick, expired)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    state.refreshSync()
                }
            }
            if (expired) break
            delay(1000)
            tick += 1
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
            // **Every address this machine publishes, not a guess at one.**
            // A laptop can be on its own network, a hypervisor's, a
            // container bridge's and a VPN's at once, and it cannot tell
            // which of them the phone shares — so it does not choose. The
            // list is what the kernel offers; the pick is the joiner's,
            // which is the only place the question can be answered.
            val addresses = pairingAddresses(offer)
            for (address in addresses) {
                Fact(if (addresses.size == 1) "address" else "addresses", address)
            }
            if (addresses.size > 1) {
                Text(
                    "This machine is on more than one network. Type whichever address " +
                        "belongs to the network the other device is on — a wrong one simply " +
                        "does not connect, and the code stays good until it expires. " +
                        "Scanning the code instead skips the question: the phone is handed " +
                        "all of them.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colours.onSurfaceVariant,
                )
            }
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

// ------------------------------------------------------------ firewall

/// Where the Windows Firewall stands for the installed launcher, and the
/// one action there is: ask for the rule, with administrator approval.
/// Null `launcher` is a build that is not an installed Windows app (a dev
/// run, Linux), which has no row.
class FirewallState(private val launcher: File?, private val scope: CoroutineScope) {
    var verdict by mutableStateOf<FirewallVerdict?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /// What the last ask came back with, when it was not the rule.
    var lastOutcome by mutableStateOf<FirewallOutcome?>(null)
        private set

    val shown: Boolean get() = launcher != null

    fun refresh() {
        val exe = launcher ?: return
        scope.launch { verdict = withContext(Dispatchers.IO) { WindowsFirewall.inspect(exe) } }
    }

    /// Switching sync on: the rule is asked for when it is missing or a
    /// block stands in its way. One prompt; the person's answer is the
    /// answer.
    fun allowIfNeeded() {
        val exe = launcher ?: return
        if (busy) return
        scope.launch {
            val now = withContext(Dispatchers.IO) { WindowsFirewall.inspect(exe) }
            verdict = now
            if (now == FirewallVerdict.MISSING || now == FirewallVerdict.BLOCKED) allow()
        }
    }

    fun allow() {
        val exe = launcher ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { WindowsFirewall.allow(exe) }
                lastOutcome = outcome.takeIf { it != FirewallOutcome.Allowed }
                verdict = withContext(Dispatchers.IO) { WindowsFirewall.inspect(exe) }
            } finally {
                busy = false
            }
        }
    }
}

/// One line under the switch: the firewall's answer in words, and a
/// button when there is something to do about it. Says nothing while the
/// rule is in place — the paragraph below already explains it.
@Composable
internal fun FirewallRow(firewall: FirewallState) {
    if (!firewall.shown) return
    val colours = MaterialTheme.colorScheme
    val verdict = firewall.verdict ?: return
    val words = when (verdict) {
        FirewallVerdict.ALLOWED -> "Windows Firewall lets Gilune through on private networks."
        FirewallVerdict.MISSING -> "Windows Firewall does not let Gilune through yet — other devices cannot reach this one until it does."
        FirewallVerdict.BLOCKED -> "A Windows Firewall rule is blocking Gilune on private networks — what a dismissed prompt leaves behind. It beats every allow beside it."
        FirewallVerdict.UNKNOWN -> return
    }
    val refused = when (val outcome = firewall.lastOutcome) {
        FirewallOutcome.Declined -> " The last request was declined."
        is FirewallOutcome.Failed -> " The last request failed: ${outcome.words}."
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            words + refused,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (verdict == FirewallVerdict.ALLOWED) colours.onSurfaceVariant else colours.error,
            modifier = Modifier.weight(1f),
        )
        if (verdict != FirewallVerdict.ALLOWED) {
            OutlinedButton(onClick = firewall::allow, enabled = !firewall.busy) {
                Text(if (verdict == FirewallVerdict.BLOCKED) "Fix the rule" else "Allow on private networks", fontSize = 12.sp)
            }
        }
    }
}
