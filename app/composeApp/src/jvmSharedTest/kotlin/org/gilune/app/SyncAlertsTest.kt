// The words a standing integrity condition says (ADR 0019 § 8), pinned
// once for both shells.
//
// These sentences are the milestone's user-visible contract as much as any
// struct is: § 8's whole argument is that a halted stream which says
// nothing makes sync "look like it works", and the only defence against
// that is a sentence. A sentence nothing tests is a sentence that quietly
// becomes wrong — and until this round there were **two** of them, one per
// shell, which is the same defect with twice the surface.
//
// This file lives in `jvmSharedTest`, so it runs in the desktop suite and
// on the device.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncAlertsTest {

    private val shells = listOf(DESKTOP_ALERT_WORDS, PHONE_ALERT_WORDS)

    // ------------------------------------------------------------------
    // True of every kind, on every shell
    // ------------------------------------------------------------------

    @Test
    fun everyKindBecomesASentenceRatherThanAnEmptyBanner() {
        for (words in shells) {
            for (kind in ALERT_KINDS) {
                val said = alertSentence(kind, "device-7", "seq 41", words)
                assertTrue(said.length > 80, "$kind produced nothing worth showing: '$said'")
                assertTrue(said.trim().endsWith("."), "$kind did not end in a sentence: '$said'")
            }
        }
    }

    @Test
    fun everyKindSaysSomethingDifferentFromEveryOther() {
        // A table where two conditions read alike is a table that tells a
        // person nothing about which one they have.
        for (words in shells) {
            val said = ALERT_KINDS.map { alertSentence(it, "", "", words) }
            assertEquals(ALERT_KINDS.size, said.toSet().size, "two kinds share a sentence")
        }
    }

    @Test
    fun everyKindPromisesThatApplyingResumesWhereItStopped() {
        // True of all of them, because applying resumes from the frontier.
        // Without it each of these reads as a loss, which is the one thing
        // none of them is.
        for (words in shells) {
            for (kind in ALERT_KINDS + "sync-something-from-2027") {
                assertTrue(
                    alertSentence(kind, "", "", words).contains("picks up where it left off"),
                    "$kind never said the condition clearing puts sync back to work",
                )
            }
        }
    }

    @Test
    fun noSentenceEverShowsTheKindStringTheKernelRaisedItUnder() {
        for (words in shells) {
            for (kind in ALERT_KINDS) {
                val said = alertSentence(kind, "device-7", "seq 41", words)
                assertFalse(said.contains(kind), "$kind leaked its own kind string")
                assertFalse(said.contains("sync-"), "$kind leaked a kernel policy name: '$said'")
                assertFalse(said.contains("vault-"), "$kind leaked a kernel policy name: '$said'")
            }
        }
    }

    @Test
    fun theKindStringsAreTheOnesTheKernelActuallyRaises() {
        // Spelled here as literals rather than only as constants, because
        // the constants are this table's copy of `gilune-kernel::alerts`
        // and a copy is only as good as the thing that pins it. A kind
        // renamed on one side falls through to the unknown-kind sentence —
        // which is visible, and wrong.
        assertEquals("sync-gap", SYNC_GAP)
        assertEquals("sync-corrupt-operation", SYNC_CORRUPT_OPERATION)
        assertEquals("sync-unsupported-version", SYNC_UNSUPPORTED_VERSION)
        assertEquals("sync-unknown-op-kind", SYNC_UNKNOWN_OP_KIND)
        assertEquals("sync-limit-exceeded", SYNC_LIMIT_EXCEEDED)
        assertEquals("sync-blob-digest-mismatch", SYNC_BLOB_DIGEST_MISMATCH)
        assertEquals("sync-revoked-device", SYNC_REVOKED_DEVICE)
        assertEquals("sync-awaiting-referent", SYNC_AWAITING_REFERENT)
        assertEquals("vault-deleted-note-edited", VAULT_DELETED_NOTE_EDITED)
        assertEquals(ALERT_KINDS.size, ALERT_KINDS.toSet().size, "no kind is listed twice")
    }

    @Test
    fun theShellsDifferOnlyInTheirNounAndWhetherTheyCarryIdentifiers() {
        // The point of one table: strip the two parameters and the two
        // shells say the same thing, so a kind cannot be worded on one and
        // silent on the other.
        for (kind in ALERT_KINDS) {
            val desktop = alertSentence(kind, "", "", DESKTOP_ALERT_WORDS)
                .replace("this device", "«here»")
            val phone = alertSentence(kind, "", "", PHONE_ALERT_WORDS)
                .replace("this phone", "«here»")
            assertEquals(desktop, phone, "$kind drifted between the shells")
        }
    }

    // ------------------------------------------------------------------
    // What each shell shows of the kernel's own identifiers
    // ------------------------------------------------------------------

    @Test
    fun theDesktopCarriesTheSubjectAndDetailAndThePhoneDoesNot() {
        for (kind in ALERT_KINDS) {
            val desktop = alertSentence(kind, "blob-9f2", "declared 4 MiB", DESKTOP_ALERT_WORDS)
            assertTrue(desktop.contains("blob-9f2"), "$kind dropped its subject")
            assertTrue(desktop.contains("declared 4 MiB"), "$kind dropped its detail")
            val phone = alertSentence(kind, "blob-9f2", "declared 4 MiB", PHONE_ALERT_WORDS)
            assertFalse(phone.contains("blob-9f2"), "$kind put an id on a phone screen")
            assertFalse(phone.contains("declared 4 MiB"), "$kind put a kernel detail on a phone")
        }
    }

    @Test
    fun nothingToIdentifyLeavesNoEmptyBracketsBehind() {
        for (kind in ALERT_KINDS) {
            val said = alertSentence(kind, "", "", DESKTOP_ALERT_WORDS)
            assertFalse(said.contains("()"), "$kind left an empty aside: '$said'")
            assertFalse(said.contains(" (, "), "$kind left a dangling separator: '$said'")
        }
    }

    // ------------------------------------------------------------------
    // Kind by kind: the fact each one exists to convey
    // ------------------------------------------------------------------

    @Test
    fun aGapSaysNothingPastItWasTakenInAndWhyThatIsRight() {
        val said = alertSentence(SYNC_GAP, "device-7", "seq 41", DESKTOP_ALERT_WORDS)
        assertTrue(said.contains("missing"), "a gap that never says something is missing: '$said'")
        assertTrue(
            said.contains("nothing after the gap has been taken in"),
            "a gap must say what stopped: '$said'",
        )
    }

    @Test
    fun aCorruptOperationSaysItWasNotGuessedAt() {
        val said = alertSentence(SYNC_CORRUPT_OPERATION, "", "", PHONE_ALERT_WORDS)
        assertTrue(said.contains("not something to guess at"), "'$said'")
    }

    @Test
    fun anUnsupportedVersionNamesTheFixRatherThanTheFault() {
        for (words in shells) {
            val said = alertSentence(SYNC_UNSUPPORTED_VERSION, "", "", words)
            assertTrue(said.contains("same version"), "it must say what clears it: '$said'")
            assertTrue(said.contains(words.thisDevice), "and name the machine: '$said'")
        }
    }

    @Test
    fun anUnknownOperationKindIsNotDescribedAsDamage() {
        // § 8's distinction, and the reason it is a separate kind at all:
        // a newer version's entry is kept byte for byte and forwarded, and
        // calling that damage would send somebody looking for a fault that
        // does not exist.
        for (words in shells) {
            val said = alertSentence(SYNC_UNKNOWN_OP_KIND, "op-3", "kind 9", words)
            for (alarm in listOf("damaged", "corrupt", "thrown away", "refused", "could not")) {
                assertFalse(said.contains(alarm), "an unknown kind read as damage: '$said'")
            }
            assertTrue(said.contains("newer version"), "it must say where it came from: '$said'")
            assertTrue(said.contains("Nothing stopped and nothing is lost"), "'$said'")
            assertTrue(said.contains("kept exactly as it arrived"), "it must say it is kept")
            assertTrue(said.contains("passed on"), "a full replica forwards it: '$said'")
        }
        assertFalse(alertIsDamage(SYNC_UNKNOWN_OP_KIND))
    }

    @Test
    fun aLimitReachedSaysTheExchangeWasEndedTidilyRatherThanCutOff() {
        val said = alertSentence(SYNC_LIMIT_EXCEEDED, "", "", DESKTOP_ALERT_WORDS)
        assertTrue(said.contains("tidily"), "'$said'")
        assertTrue(said.contains("Nothing is half applied"), "'$said'")
    }

    @Test
    fun aDigestMismatchSaysTheBytesWereThrownAwayRatherThanSavedWrong() {
        val said = alertSentence(SYNC_BLOB_DIGEST_MISMATCH, "blob-1", "", DESKTOP_ALERT_WORDS)
        assertTrue(said.contains("thrown away"), "the bytes' fate is the point: '$said'")
    }

    @Test
    fun aRevokedDeviceAlertRefusesToImplyRevocationIsAnUndo() {
        // § 9 says revocation protects what comes next and does not
        // retract history, and the UI must say so rather than implying an
        // undo.
        for (words in shells) {
            val said = alertSentence(SYNC_REVOKED_DEVICE, "device-2", "", words)
            assertTrue(said.contains("still in your vault"), "history is not retracted: '$said'")
            assertTrue(said.contains("does not undo"), "it must say so plainly: '$said'")
        }
    }

    @Test
    fun awaitingAnotherDevicesHistoryReadsAsAWaitAndNotAsAFault() {
        // The kernel's newest condition, and the one most likely to be
        // misread: nothing stopped, nothing is lost, and it clears itself.
        for (words in shells) {
            val said = alertSentence(SYNC_AWAITING_REFERENT, "op-9", "", words)
            for (alarm in listOf("damaged", "corrupt", "thrown away", "refused", "could not")) {
                assertFalse(said.contains(alarm), "a wait read as damage: '$said'")
            }
            assertTrue(said.contains("Nothing stopped and nothing is lost"), "'$said'")
            assertTrue(said.contains("wrong order"), "it must say why it happened: '$said'")
            assertTrue(
                said.contains("without anybody doing anything"),
                "a self-clearing condition must say it is self-clearing: '$said'",
            )
        }
        assertFalse(alertIsDamage(SYNC_AWAITING_REFERENT))
    }

    @Test
    fun aNoteKeptOverAnotherDevicesDeleteSaysBothIntentionsAreHeld() {
        // Rule 6 working rather than a race being lost — and the one
        // condition here that hands a person a decision. It is raised by
        // the vault rather than by a stream, so its kind is `vault-`
        // prefixed and its detail names a file.
        for (words in shells) {
            val said = alertSentence(VAULT_DELETED_NOTE_EDITED, "object-4", "Notes/milk.md", words)
            assertTrue(said.contains("was kept"), "'$said'")
            assertTrue(said.contains("offered as an import"), "where the file went: '$said'")
            assertTrue(said.contains("both intentions are held"), "'$said'")
            assertTrue(
                said.contains("keeping or deleting the file here is what settles it"),
                "the person is the only one who can settle it: '$said'",
            )
            assertTrue(said.contains("nothing was lost"), "'$said'")
            for (alarm in listOf("damaged", "corrupt", "thrown away", "refused")) {
                assertFalse(said.contains(alarm), "a kept file read as damage: '$said'")
            }
        }
        assertFalse(alertIsDamage(VAULT_DELETED_NOTE_EDITED))
        // The desktop's aside earns its place most here: the detail is a
        // path, which is the one identifier a person can act on.
        assertTrue(
            alertSentence(VAULT_DELETED_NOTE_EDITED, "object-4", "Notes/milk.md", DESKTOP_ALERT_WORDS)
                .contains("Notes/milk.md"),
        )
    }

    @Test
    fun everyOtherKindIsTreatedAsDamage() {
        // Including one this build has never heard of: assuming a newer
        // kernel's condition is harmless is the assumption that could hide
        // a real one.
        for (kind in ALERT_KINDS - NON_DAMAGE_ALERT_KINDS) {
            assertTrue(alertIsDamage(kind), "$kind stopped counting as damage")
        }
        assertTrue(alertIsDamage("sync-something-from-2027"))
    }

    // ------------------------------------------------------------------
    // The kind this build has never heard of
    // ------------------------------------------------------------------

    @Test
    fun anUnrecognisedKindStillGetsWordsRatherThanSilence() {
        for (words in shells) {
            val said = alertSentence("sync-something-from-2027", "device-4", "", words)
            assertTrue(said.length > 80, "an unrecognised halt still has to be visible")
            assertFalse(said.contains("sync-something-from-2027"), "and not by its kind string")
        }
    }

    @Test
    fun anUnrecognisedKindCarriesTheKernelsWordsOnBothShells() {
        // The one case where the identifiers reach a phone: they are the
        // only words there are, and saying nothing would be the silence
        // this surface exists to end.
        for (words in shells) {
            assertTrue(
                alertSentence("sync-something-new", "device-1", "the frobnicator overflowed", words)
                    .contains("the frobnicator overflowed"),
            )
            assertTrue(
                alertSentence("sync-something-new", "device-1", "", words).contains("device-1"),
                "with no detail the subject is what there is",
            )
            assertTrue(
                alertSentence("sync-something-new", "", "", words).contains("no further detail"),
                "with nothing to say it still says something",
            )
        }
    }

    // ------------------------------------------------------------------
    // The heading
    // ------------------------------------------------------------------

    @Test
    fun noAlertsMeansNoHeadingAtAll() {
        assertEquals("", alertsHeading(emptyList()))
    }

    @Test
    fun aBannerHoldingARealHaltSaysSoAndCountsItsReasonsWhenThereAreSeveral() {
        assertEquals(
            "Sync stopped short of taking everything in",
            alertsHeading(listOf(SYNC_GAP)),
        )
        assertTrue(
            alertsHeading(listOf(SYNC_GAP, SYNC_UNKNOWN_OP_KIND)).contains("2 reasons"),
            "a mixed banner counts what it holds",
        )
    }

    @Test
    fun aBannerOfOnlyUnknownKindsDoesNotOpenBySayingSyncStopped() {
        assertEquals(
            "Sync kept something it could not read",
            alertsHeading(listOf(SYNC_UNKNOWN_OP_KIND, SYNC_UNKNOWN_OP_KIND)),
        )
    }

    @Test
    fun aBannerOfOnlyWaitsAndKeepsNeverSaysSyncStopped() {
        assertEquals(
            "Sync is waiting for the rest of another device's history",
            alertsHeading(listOf(SYNC_AWAITING_REFERENT)),
        )
        assertEquals(
            "A note deleted on another device was kept here",
            alertsHeading(listOf(VAULT_DELETED_NOTE_EDITED)),
        )
        val mixed = alertsHeading(listOf(SYNC_AWAITING_REFERENT, SYNC_UNKNOWN_OP_KIND))
        assertTrue(mixed.startsWith("Nothing has stopped"), "'$mixed'")
        assertFalse(mixed.contains("stopped short"), "'$mixed'")
    }

    @Test
    fun oneRealHaltAmongTheHarmlessOnesStillOpensBySayingSyncStopped() {
        val said = alertsHeading(listOf(SYNC_AWAITING_REFERENT, SYNC_GAP, SYNC_UNKNOWN_OP_KIND))
        assertTrue(said.startsWith("Sync stopped short"), "'$said'")
    }
}
