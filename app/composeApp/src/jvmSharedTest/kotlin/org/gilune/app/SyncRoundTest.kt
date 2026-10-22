// What a round of syncing says, pinned without a kernel (ADR 0019 § 1).
//
// `SyncRound.kt` is shared by both shells, so its words are pinned here
// where both suites run them — the same reason `SyncAddressesTest` and
// `SyncAlertsTest` live in this source set. Every sentence is a decision:
// the progress line is present tense and names the device; a failed
// peer's line is the kernel's own sentence and nothing is prefixed to it;
// the status-bar summary says only whether the sync screen is worth a
// look; and a found device is offered a pairing exactly when one is
// possible.

package org.gilune.app

import uniffi.gilune_ffi.FoundDevice
import uniffi.gilune_ffi.SyncProgressEvent
import uniffi.gilune_ffi.SyncReport
import uniffi.gilune_ffi.SyncStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncRoundTest {

    private fun event(
        peer: String,
        stage: SyncStage,
        applied: Long = 0,
        sent: Long = 0,
        reason: String = "",
    ) = SyncProgressEvent(peer = peer, stage = stage, applied = applied, sent = sent, reason = reason)

    private fun report(
        applied: Long = 0,
        sent: Long = 0,
        halted: List<String> = emptyList(),
        incomplete: Boolean = false,
    ) = SyncReport(
        applied = applied,
        duplicates = 0,
        sent = sent,
        blobsCompleted = 0,
        blobsPending = 0,
        bytes = 0,
        halted = halted,
        incomplete = incomplete,
    )

    private fun round(peers: List<PeerOutcome>, report: SyncReport = report()) =
        SyncRound(endedMs = 1_000_000L, report = report, peers = peers)

    // ------------------------------------------------------ while it runs

    @Test
    fun theProgressLineNamesTheDeviceAndWhatIsBeingDoneWithIt() {
        assertEquals("contacting NB1606…", progressSentence(event("NB1606", SyncStage.CONNECTING)))
        val looking = progressSentence(event("NB1606", SyncStage.LOOKING))
        assertTrue(looking.startsWith("NB1606 did not answer"), looking)
        assertTrue(looking.contains("looking for it"), "the second step is said as a step: $looking")
        assertEquals("exchanging notes with NB1606…", progressSentence(event("NB1606", SyncStage.EXCHANGING)))
        assertEquals("finished with NB1606", progressSentence(event("NB1606", SyncStage.DONE)))
        assertEquals("could not sync with NB1606", progressSentence(event("NB1606", SyncStage.FAILED)))
    }

    // ------------------------------------------------------- when it ends

    @Test
    fun eachPeersOutcomeIsItsLastStageInTheOrderPeersWereFirstMentioned() {
        val outcomes = peerOutcomes(
            listOf(
                event("Laptop", SyncStage.CONNECTING),
                event("Laptop", SyncStage.LOOKING),
                event("Phone", SyncStage.CONNECTING),
                event("Laptop", SyncStage.FAILED, reason = "Laptop answered a search but…"),
                event("Phone", SyncStage.EXCHANGING),
                event("Phone", SyncStage.DONE, applied = 3, sent = 1),
            ),
        )
        assertEquals(listOf("Laptop", "Phone"), outcomes.map { it.peer })
        assertEquals(SyncStage.FAILED, outcomes[0].stage)
        assertEquals("Laptop answered a search but…", outcomes[0].reason)
        assertEquals(PeerOutcome("Phone", SyncStage.DONE, applied = 3, sent = 1), outcomes[1])
        assertTrue(peerOutcomes(emptyList()).isEmpty())
    }

    @Test
    fun aFailedPeersLineIsTheKernelsSentenceWithNothingPrefixed() {
        // The kernel's reason already starts with the device's name; a
        // second "NB1606:" in front of it is the kind of doubling that
        // made the old status line unreadable.
        val said = outcomeSentence(
            PeerOutcome("NB1606", SyncStage.FAILED, reason = "NB1606 answered a search on this network but…"),
        )
        assertEquals("NB1606 answered a search on this network but…", said)
        assertEquals(
            "NB1606: could not be reached",
            outcomeSentence(PeerOutcome("NB1606", SyncStage.FAILED)),
            "a failure with no words still gets a line",
        )
    }

    @Test
    fun aFinishedPeersLineCountsWhatMovedOrSaysNothingDid() {
        assertEquals(
            "Phone: 3 received, 1 sent",
            outcomeSentence(PeerOutcome("Phone", SyncStage.DONE, applied = 3, sent = 1)),
        )
        assertEquals("Phone: already up to date", outcomeSentence(PeerOutcome("Phone", SyncStage.DONE)))
    }

    @Test
    fun aPeerTheRoundDiedUnderIsWordedAsInterruptedNotAsFinished() {
        for (stage in listOf(SyncStage.CONNECTING, SyncStage.LOOKING, SyncStage.EXCHANGING)) {
            val said = outcomeSentence(PeerOutcome("Phone", stage))
            assertTrue(said.contains("interrupted"), "$stage: $said")
            assertFalse(said.contains("up to date"), "$stage: $said")
        }
    }

    @Test
    fun theStatusLineSaysOnlyWhetherTheSyncScreenIsWorthALook() {
        val done = PeerOutcome("Phone", SyncStage.DONE, applied = 2, sent = 0)
        val failed = PeerOutcome("NB1606", SyncStage.FAILED, reason = "NB1606 answered a search…")

        assertEquals("device sync: nothing to sync with", roundSummary(round(emptyList())))
        assertEquals(
            "devices are already up to date",
            roundSummary(round(listOf(PeerOutcome("Phone", SyncStage.DONE)))),
        )
        assertEquals(
            "device sync: 2 received, 0 sent",
            roundSummary(round(listOf(done), report(applied = 2))),
        )
        assertEquals(
            "device sync: 2 received, 0 sent (more to come)",
            roundSummary(round(listOf(done), report(applied = 2, incomplete = true))),
        )
        val one = roundSummary(round(listOf(failed), report(halted = listOf("NB1606: …"))))
        assertEquals("device sync: NB1606 could not be reached — Settings ▸ Sync says why", one)
        val two = roundSummary(round(listOf(failed, done), report(applied = 2, halted = listOf("NB1606: …"))))
        assertEquals("device sync: 1 of 2 devices could not be reached — Settings ▸ Sync says why", two)
        // A stream that halted *inside* a session is a halt too, and the
        // screen is where its words are.
        val halted = roundSummary(round(listOf(done), report(applied = 2, halted = listOf("device-x: …"))))
        assertTrue(halted.contains("Settings ▸ Sync"), halted)
    }

    @Test
    fun theHeadingSaysHowLongAgoInTheCoarsestHonestUnit() {
        val ended = 10_000_000L
        val r = SyncRound(endedMs = ended, report = report(), peers = emptyList())
        assertEquals("Last sync just now", roundHeading(r, now = ended + 30_000))
        assertEquals("Last sync 5 min ago", roundHeading(r, now = ended + 5 * 60_000))
        assertEquals("Last sync 3 h ago", roundHeading(r, now = ended + 3 * 3_600_000))
        assertEquals("Last sync 2 d ago", roundHeading(r, now = ended + 2 * 86_400_000))
    }

    // ------------------------------------------------ pairing from a row

    @Test
    fun aFoundDeviceIsOfferedAPairingExactlyWhenOneIsPossible() {
        assertEquals("Pair…", foundDeviceOffer("unknown", compatible = true))
        assertEquals("Pair again", foundDeviceOffer("revoked", compatible = true), "a new pairing, not an undo")
        assertNull(foundDeviceOffer("paired", compatible = true), "already paired is not an invitation")
        assertNull(foundDeviceOffer("unknown", compatible = false), "a version this build cannot talk to")
        assertNull(foundDeviceOffer("revoked", compatible = false))
    }

    @Test
    fun theRowAsksForTheCodeAndNamesWhatToDoWhenThereIsNone() {
        val found = FoundDevice(
            fingerprint = "ABCD-EFGH",
            address = "192.168.1.137:51837",
            compatible = true,
            staticPub = "",
            state = "unknown",
        )
        val hint = foundPairingHint(found)
        assertTrue(hint.contains("six-digit code"), hint)
        assertTrue(hint.contains("Start pairing"), "and what to press over there when there is none: $hint")
        assertFalse(hint.contains("address"), "the address is the one thing the row no longer asks for: $hint")

        val failed = foundPairingFailure(found, "that device did not answer")
        assertTrue(failed.startsWith("pairing with ABCD-EFGH failed: that device did not answer"), failed)
        assertTrue(failed.contains("Start pairing"), "silence is what an unpaired caller gets by design: $failed")
    }
}
