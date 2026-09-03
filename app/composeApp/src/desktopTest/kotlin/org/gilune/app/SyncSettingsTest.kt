// The words Settings ▸ Sync says, asserted without a screen (ADR 0019
// §§ 1, 8, 9).
//
// These are the milestone's user-visible contract as much as any struct
// is: § 8's whole argument is that a halted stream which says nothing
// makes sync "look like it works", and the only defence against that is
// a sentence. A sentence nothing tests is a sentence that quietly
// becomes wrong, so every one of them is pinned here — that it names
// what stopped, that it promises the resumption the frontier actually
// performs, and that it never leaks the kernel's own name for the
// policy that raised it.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSettingsTest {

    /// Every kind the FFI documents. If the kernel adds one, this list is
    /// where the omission shows up.
    private val kinds = listOf(
        "sync-gap",
        "sync-corrupt-operation",
        "sync-unsupported-version",
        "sync-unknown-op-kind",
        "sync-limit-exceeded",
        "sync-blob-digest-mismatch",
        "sync-revoked-device",
    )

    // ------------------------------------------------------------------
    // Alerts (§ 8)
    // ------------------------------------------------------------------

    @Test
    fun everyAlertKindBecomesASentenceRatherThanAnEmptyBanner() {
        for (kind in kinds) {
            val said = alertSentence(kind, "device-7", "seq 41")
            assertTrue(said.length > 40, "$kind produced nothing worth showing: '$said'")
            assertTrue(said.trim().endsWith("."), "$kind did not end in a sentence: '$said'")
        }
    }

    @Test
    fun everyAlertPromisesThatApplyingResumesWhereItStopped() {
        // True of all of them, because halting resumes from the frontier.
        // Without it each of these reads as a loss, which is the one
        // thing none of them is.
        for (kind in kinds) {
            assertTrue(
                alertSentence(kind, "", "").contains("picks up where it left off"),
                "$kind never said the condition clearing puts sync back to work",
            )
        }
    }

    @Test
    fun noAlertEverShowsTheKindStringTheKernelRaisedItUnder() {
        for (kind in kinds) {
            val said = alertSentence(kind, "device-7", "seq 41")
            assertFalse(said.contains(kind), "$kind leaked its own kind string")
            assertFalse(said.contains("sync-"), "$kind leaked a kernel policy name: '$said'")
        }
    }

    @Test
    fun theSubjectAndDetailAreCarriedIntoTheSentenceSoTheAlertIsIdentifiable() {
        for (kind in kinds) {
            val said = alertSentence(kind, "blob-9f2", "declared 4 MiB")
            assertTrue(said.contains("blob-9f2"), "$kind dropped its subject")
            assertTrue(said.contains("declared 4 MiB"), "$kind dropped its detail")
        }
    }

    @Test
    fun anAlertWithNothingToIdentifyItLeavesNoEmptyBracketsBehind() {
        for (kind in kinds) {
            val said = alertSentence(kind, "", "")
            assertFalse(said.contains("()"), "$kind left an empty aside: '$said'")
            assertFalse(said.contains(" (, "), "$kind left a dangling separator: '$said'")
        }
    }

    @Test
    fun aGapSaysNothingPastItWasTakenInAndWhyThatIsRight() {
        val said = alertSentence("sync-gap", "device-7", "seq 41")
        assertTrue(said.contains("missing"), "a gap that never says something is missing: '$said'")
        assertTrue(
            said.contains("nothing after the gap has been taken in"),
            "a gap must say what stopped: '$said'",
        )
    }

    @Test
    fun anUnknownOperationKindIsNotDescribedAsDamage() {
        // § 8's distinction, and the reason it is a separate kind at all:
        // a newer version's entry is kept byte for byte and forwarded,
        // and calling that damage would send somebody looking for a fault
        // that does not exist.
        val said = alertSentence("sync-unknown-op-kind", "op-3", "kind 9")
        for (alarm in listOf("damaged", "corrupt", "thrown away", "refused", "could not")) {
            assertFalse(said.contains(alarm), "an unknown kind read as damage: '$said'")
        }
        assertTrue(said.contains("newer version"), "it must say where it came from: '$said'")
        assertTrue(said.contains("kept exactly as it arrived"), "it must say it is kept: '$said'")
        assertTrue(said.contains("passed on"), "a full replica forwards it: '$said'")
    }

    @Test
    fun aDigestMismatchSaysTheBytesWereThrownAwayRatherThanSavedWrong() {
        val said = alertSentence("sync-blob-digest-mismatch", "blob-1", "")
        assertTrue(said.contains("thrown away"), "the bytes' fate is the point: '$said'")
    }

    @Test
    fun aRevokedDeviceAlertRefusesToImplyRevocationIsAnUndo() {
        // § 9 says revocation protects what comes next and does not
        // retract history, and "the UI must say so too rather than
        // implying an undo".
        val said = alertSentence("sync-revoked-device", "device-2", "")
        assertTrue(said.contains("still in your vault"), "history is not retracted: '$said'")
        assertTrue(said.contains("does not undo"), "it must say so plainly: '$said'")
    }

    @Test
    fun aKindThisBuildHasNeverHeardOfStillGetsWordsRatherThanSilence() {
        val said = alertSentence("sync-something-from-2027", "device-4", "")
        assertTrue(said.length > 40, "an unrecognised halt still has to be visible")
        assertFalse(said.contains("sync-something-from-2027"), "and still not by its kind string")
        assertTrue(said.contains("picks up where it left off"))
    }

    @Test
    fun aBannerOfOnlyUnknownKindsDoesNotOpenBySayingSyncStopped() {
        assertEquals(
            "Sync kept something it could not read",
            alertsHeading(listOf("sync-unknown-op-kind", "sync-unknown-op-kind")),
        )
    }

    @Test
    fun aBannerHoldingARealHaltSaysSoAndCountsItsReasonsWhenThereAreSeveral() {
        assertEquals(
            "Sync stopped short of taking everything in",
            alertsHeading(listOf("sync-gap")),
        )
        assertTrue(
            alertsHeading(listOf("sync-gap", "sync-unknown-op-kind")).contains("2 reasons"),
            "a mixed banner counts what it holds",
        )
    }

    @Test
    fun noAlertsMeansNoHeadingAtAll() {
        assertEquals("", alertsHeading(emptyList()))
    }

    // ------------------------------------------------------------------
    // Findable as against reachable (§ 1)
    // ------------------------------------------------------------------

    @Test
    fun syncSwitchedOffIsNeitherFindableNorApologisedFor() {
        assertEquals("no — sync is off", discoveryFact(false, false, "mdns-sd"))
        assertTrue(
            discoveryNote(false, false, "mdns-sd").contains("while sync is off"),
            "the off state is the switch working, not a fault",
        )
    }

    @Test
    fun anAdvertisingDeviceNamesTheRouteItIsAdvertisingOver() {
        // A diagnostic has to be able to say *which* route answered, or
        // "not found" and "could not look" become the same report.
        assertEquals("yes, over mdns-sd", discoveryFact(true, true, "mdns-sd"))
        assertEquals("yes, over NsdManager", discoveryFact(true, true, "NsdManager"))
    }

    @Test
    fun reachableButNotFindableSaysBothHalvesAndPointsAtTheAddress() {
        // The honest case, and the state the whole fallback chain exists
        // for: TCP bound, UDP 5353 did not.
        val fact = discoveryFact(true, false, "mdns-sd")
        assertTrue(fact.contains("reachable"), "it is reachable and must say so: '$fact'")
        assertTrue(fact.startsWith("no"), "and it is not findable: '$fact'")
        val note = discoveryNote(true, false, "mdns-sd")
        assertTrue(note.contains("not announcing itself"), "'$note'")
        assertTrue(note.contains("address"), "the fallback is a typed address: '$note'")
        // Not findable is a supported configuration, not a fault, and the
        // paragraph has to say so or it reads as something to go and fix.
        assertTrue(note.contains("not a fault"), "'$note'")
    }

    @Test
    fun aHostWithNoWayToLookBlamesItselfRatherThanTheNetwork() {
        assertEquals("no — nothing here can announce it", discoveryFact(true, false, "none"))
        val note = discoveryNote(true, false, "none")
        assertTrue(note.contains("no way to announce itself"), "'$note'")
        assertTrue(note.contains("address"), "and still points at the route that works: '$note'")
    }

    @Test
    fun anEmptyDiscoveryRouteIsTreatedAsNoRouteRatherThanAsARouteCalledNothing() {
        assertEquals(discoveryFact(true, false, "none"), discoveryFact(true, false, ""))
        assertEquals(discoveryNote(true, false, "none"), discoveryNote(true, false, ""))
        assertEquals(emptyDiscoveryNote("none"), emptyDiscoveryNote(""))
    }

    // ------------------------------------------------------------------
    // What a browse found (§§ 1, 3)
    // ------------------------------------------------------------------

    @Test
    fun aStrangerIsOfferedTheFactAndTheCodePathAndNothingElse() {
        // Being visible on a network is not permission to sync: pairing
        // costs a code whichever way a device was found.
        val said = foundDeviceNote("unknown", compatible = true)
        assertTrue(said.contains("Not paired"), "'$said'")
        assertTrue(said.contains("code"), "the row has to say what pairing still costs: '$said'")
    }

    @Test
    fun aDeviceThisBuildCannotTalkToIsToldApartFromOneThatIsSimplyUnpaired() {
        val incompatible = foundDeviceNote("unknown", compatible = false)
        assertTrue(incompatible.contains("cannot talk to"), "'$incompatible'")
        assertTrue(incompatible.contains("same version"), "and what fixes it: '$incompatible'")
        // Incompatibility outranks state: a paired device running a
        // version this one cannot speak to is not a working pairing.
        assertEquals(incompatible, foundDeviceNote("paired", compatible = false))
    }

    @Test
    fun anAlreadyPairedDeviceSaysSoRatherThanInvitingASecondPairing() {
        assertTrue(foundDeviceNote("paired", compatible = true).contains("Already paired"))
    }

    @Test
    fun aRevokedDeviceOnTheNetworkIsNotAnInvitationToUndoTheRevocation() {
        val said = foundDeviceNote("revoked", compatible = true)
        assertTrue(said.contains("cannot connect"), "'$said'")
        assertTrue(said.contains("new pairing"), "re-pairing is not an undo (§ 9): '$said'")
    }

    @Test
    fun findingNothingBlamesTheNetworkAndSaysWaitingWillNotHelp() {
        // § 1: the last step of the chain is words, not a spinner.
        val said = emptyDiscoveryNote("mdns-sd")
        assertTrue(said.contains("guest"), "'$said'")
        assertTrue(said.contains("no amount of waiting"), "'$said'")
        assertTrue(said.contains("address and code"), "and it hands over the route that works")
    }

    @Test
    fun findingNothingWithNoRouteToLookSaysNothingWasEverGoingToBeFound() {
        val said = emptyDiscoveryNote("none")
        assertTrue(said.contains("could look"), "'$said'")
        assertFalse(said.contains("guest"), "this is not the network's doing: '$said'")
    }

    // ------------------------------------------------------------------
    // Peer rows (§§ 1, 9)
    // ------------------------------------------------------------------

    @Test
    fun aPairedDeviceThatHasNeverSyncedReadsAsWaitingRatherThanAsNever() {
        assertEquals("paired · not synced yet", peerActivity("paired", 0L))
        assertEquals("paired · not synced yet", peerActivity("paired", -1L))
    }

    @Test
    fun aRevokedDeviceKeepsItsStandingInFrontOfWhateverItLastDid() {
        assertEquals("revoked · not synced yet", peerActivity("revoked", 0L))
        assertTrue(peerActivity("revoked", 1_756_000_000_000L).startsWith("revoked · last synced "))
    }

    @Test
    fun aDeviceThatHasSyncedShowsWhenRatherThanThatItNeverDid() {
        val said = peerActivity("paired", 1_756_000_000_000L)
        assertTrue(said.startsWith("paired · last synced "), "'$said'")
        assertFalse(said.contains("never"), "'$said'")
        assertFalse(said.endsWith("last synced "), "the stamp itself must be there: '$said'")
    }
}
