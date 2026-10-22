// What a round of syncing says while it runs and after it ends (ADR 0019
// § 1 — "words, not a spinner").
//
// **One rule, both shells**, for the reason `SyncAddresses` gives: the
// kernel tells the round as it goes — which peer, which stage — and the
// sentences a screen makes of that are a decision, not a layout choice.
// Before this round "Sync with devices now" sat silent for the length of
// a connect timeout, a browse and a second dial, then printed one line in
// the foot of the window: a button that did nothing visible for twelve
// seconds read as a button that did nothing. Now a screen can say which
// device is being contacted and how, and keep the outcome where the button
// is rather than in a status line somebody has already stopped reading.
//
// Everything here is pure, so the wording can be pinned without a kernel
// and the two shells cannot drift apart on it.

package org.gilune.app

import uniffi.gilune_ffi.FoundDevice
import uniffi.gilune_ffi.SyncProgressEvent
import uniffi.gilune_ffi.SyncReport
import uniffi.gilune_ffi.SyncStage

/// One peer's part in a round, as the kernel's events told it: its last
/// reported stage and whatever that stage carried.
data class PeerOutcome(
    val peer: String,
    val stage: SyncStage,
    val applied: Long = 0,
    val sent: Long = 0,
    val reason: String = "",
)

/// A finished round: when, what the kernel reported, and each peer's
/// part in it.
data class SyncRound(
    val endedMs: Long,
    val report: SyncReport,
    val peers: List<PeerOutcome>,
)

/// What the round is doing right now, in words. Present tense, because it
/// is drawn while the stage is under way; the trailing ellipsis is what
/// says "still happening" without a spinner.
fun progressSentence(event: SyncProgressEvent): String = when (event.stage) {
    SyncStage.CONNECTING -> "contacting ${event.peer}…"
    SyncStage.LOOKING ->
        "${event.peer} did not answer at its last address — looking for it on this network…"
    SyncStage.EXCHANGING -> "exchanging notes with ${event.peer}…"
    SyncStage.DONE -> "finished with ${event.peer}"
    SyncStage.FAILED -> "could not sync with ${event.peer}"
}

/// Fold a round's events into one outcome per peer, in the order the
/// peers were first mentioned. The last stage reported for a peer is its
/// outcome: a peer that was contacted, looked for and then exchanged with
/// ends as `EXCHANGING` only if the round died under it, which is the one
/// case `outcomeSentence` has to word as an interruption.
fun peerOutcomes(events: List<SyncProgressEvent>): List<PeerOutcome> {
    val order = mutableListOf<String>()
    val latest = mutableMapOf<String, PeerOutcome>()
    for (event in events) {
        if (event.peer !in latest) order += event.peer
        latest[event.peer] = PeerOutcome(
            peer = event.peer,
            stage = event.stage,
            applied = event.applied,
            sent = event.sent,
            reason = event.reason,
        )
    }
    return order.map { latest.getValue(it) }
}

/// One peer's line in the "last sync" block.
///
/// `FAILED` carries the kernel's own sentence for why, which already
/// starts with the peer's name — so it is shown as it is rather than
/// behind a second "NB1606:" nobody needs. The other stages are the
/// shell's words.
fun outcomeSentence(outcome: PeerOutcome): String = when (outcome.stage) {
    SyncStage.DONE -> when {
        outcome.applied == 0L && outcome.sent == 0L -> "${outcome.peer}: already up to date"
        else -> "${outcome.peer}: ${count(outcome.applied, "received")}, ${count(outcome.sent, "sent")}"
    }
    SyncStage.FAILED -> outcome.reason.ifBlank { "${outcome.peer}: could not be reached" }
    SyncStage.CONNECTING -> "${outcome.peer}: interrupted while contacting it"
    SyncStage.LOOKING -> "${outcome.peer}: interrupted while looking for it"
    SyncStage.EXCHANGING -> "${outcome.peer}: interrupted while exchanging notes"
}

private fun count(n: Long, what: String): String = "$n $what"

/// The one line a status bar gets for a finished round. The detail lives
/// on the sync screen; this says only whether it is worth going there.
fun roundSummary(round: SyncRound): String {
    val unreachable = round.peers.count { it.stage == SyncStage.FAILED }
    val halted = round.report.halted.size
    val moved = round.report.applied > 0 || round.report.sent > 0
    return when {
        round.peers.isEmpty() && halted == 0 -> "device sync: nothing to sync with"
        unreachable == 0 && halted == 0 && !moved -> "devices are already up to date"
        unreachable == 0 && halted == 0 ->
            "device sync: ${round.report.applied} received, ${round.report.sent} sent" +
                if (round.report.incomplete) " (more to come)" else ""
        unreachable == round.peers.size && round.peers.size == 1 ->
            "device sync: ${round.peers.first().peer} could not be reached — Settings ▸ Sync says why"
        else ->
            "device sync: $unreachable of ${round.peers.size} devices could not be reached — " +
                "Settings ▸ Sync says why"
    }
}

/// The heading of the "last sync" block: when, and in how many words.
fun roundHeading(round: SyncRound, now: Long = System.currentTimeMillis()): String {
    val ago = now - round.endedMs
    val whenSaid = when {
        ago < 60_000 -> "just now"
        ago < 3_600_000 -> "${ago / 60_000} min ago"
        ago < 86_400_000 -> "${ago / 3_600_000} h ago"
        else -> "${ago / 86_400_000} d ago"
    }
    return "Last sync $whenSaid"
}

/// What a found device's row may offer, or null for nothing.
///
/// Being visible on a network has never been permission to sync (§ 3) —
/// pairing still costs the code the other device is showing — but the
/// *address* has never needed typing when a search just produced it, and
/// until this round the row showed it to be copied two sections down. So
/// the row offers the pairing and asks only for the code. A revoked
/// device is offered it too, worded as the new pairing it is rather than
/// an undo (§ 9); an already-paired one and one this build cannot talk
/// to are offered nothing, for the reasons their notes give.
fun foundDeviceOffer(state: String, compatible: Boolean): String? = when {
    !compatible -> null
    state == "paired" -> null
    state == "revoked" -> "Pair again"
    else -> "Pair…"
}

/// What to say above the code field a found device's row opens.
fun foundPairingHint(found: FoundDevice): String =
    "Type the six-digit code that device's Sync settings are showing. If it is not " +
        "showing one, press Start pairing on that device first — a code is what makes " +
        "this a pairing rather than a stranger on the network."

/// What to say when pairing from a found row failed. The kernel's reason
/// comes first; the one cause a person can fix without reading it — no
/// code open over there — is named after it, because an unpaired caller
/// gets silence by design (§ 3) and silence reads as a dead network.
fun foundPairingFailure(found: FoundDevice, detail: String): String =
    "pairing with ${found.fingerprint} failed: $detail — if that device is not showing " +
        "a code right now, press Start pairing there and try again"
