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

    /// One row as the kernel hands it over. [damage] is the kernel's own
    /// classification travelling on the alert — the shell has no copy of
    /// the rule any more, which is the whole point of the field, so a test
    /// that wants a damaging condition says so here.
    private fun alert(
        kind: String,
        damage: Boolean = false,
        subject: String = "",
        detail: String = "",
    ) = uniffi.gilune_ffi.IntegrityAlert(
        alertId = "alert-$kind",
        kind = kind,
        subject = subject,
        detail = detail,
        raisedMs = 0L,
        damage = damage,
    )

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
        assertEquals("sync-referent-refused", SYNC_REFERENT_REFUSED)
        assertEquals("vault-content-unreadable", VAULT_CONTENT_UNREADABLE)
        assertEquals("sync-previous-identity-claimed", SYNC_PREVIOUS_IDENTITY_CLAIMED)
        assertEquals(ALERT_KINDS.size, ALERT_KINDS.toSet().size, "no kind is listed twice")
    }

    @Test
    fun aClaimedEarlierIdentityIsOfferedAsADecisionRatherThanReportedAsAFault() {
        // The one condition in the table that carries an action, and the
        // one where the wording has to do two jobs at once: say that
        // nothing has been changed on the strength of what a device
        // claimed, and hand the person the decision only they can take.
        for (words in listOf(DESKTOP_ALERT_WORDS, PHONE_ALERT_WORDS)) {
            val said = alertSentence(SYNC_PREVIOUS_IDENTITY_CLAIMED, "device-4", "", words)
            assertTrue(
                said.contains("Nothing has been changed"),
                "a claim that reads as something already done: '$said'",
            )
            assertTrue(
                said.contains("re-admit its earlier history"),
                "the notice must name the way out: '$said'",
            )
            assertTrue(
                said.contains("If it is not, leave this as it is"),
                "and must not push somebody towards yes: '$said'",
            )
            assertFalse(
                said.contains("stopped short") || said.contains("damaged"),
                "nothing stopped, so it may not read as damage: '$said'",
            )
        }
        assertEquals(
            "Re-admit its earlier history",
            alertAction(SYNC_PREVIOUS_IDENTITY_CLAIMED),
        )
        assertEquals(
            "A device says it used to be one you removed",
            alertsHeading(listOf(alert(SYNC_PREVIOUS_IDENTITY_CLAIMED))),
        )
    }

    @Test
    fun noOtherConditionOffersAnAction() {
        // The action is deliberately narrow: it lifts a refusal, which is
        // not something to attach to conditions that merely describe a
        // wait. A kind added without a decision about this would otherwise
        // inherit a button by accident.
        for (kind in ALERT_KINDS.filter { it != SYNC_PREVIOUS_IDENTITY_CLAIMED }) {
            assertEquals(null, alertAction(kind), "$kind grew a button nobody decided on")
        }
        assertEquals(null, alertAction("sync-something-from-2027"))
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
        // The desktop's aside earns its place most here: the detail is a
        // path, which is the one identifier a person can act on.
        assertTrue(
            alertSentence(VAULT_DELETED_NOTE_EDITED, "object-4", "Notes/milk.md", DESKTOP_ALERT_WORDS)
                .contains("Notes/milk.md"),
        )
    }

    @Test
    fun aRefusedReferentReadsAsATidyUpRatherThanALoss() {
        // The exit from `sync-awaiting-referent`, not another way in: the
        // thing named belongs to a removed device and is never coming, so
        // the change is set aside and the stream carries on.
        for (words in shells) {
            val said = alertSentence(SYNC_REFERENT_REFUSED, "op-3", "", words)
            assertTrue(said.contains("device you removed"), "'$said'")
            assertTrue(said.contains("nothing is lost"), "'$said'")
            assertTrue(said.contains("set aside"), "'$said'")
            for (alarm in listOf("damaged", "corrupt", "thrown away")) {
                assertFalse(said.contains(alarm), "a tidy-up read as damage: '$said'")
            }
        }
    }

    @Test
    fun anUnreadableNoteFileSaysWhatToDoAboutIt() {
        // Raised by the vault, about bytes beside the note rather than the
        // note itself — and the person is the only one who can put them
        // back, so the sentence says where to look.
        for (words in shells) {
            val said = alertSentence(VAULT_CONTENT_UNREADABLE, "object-1", "Notes/milk.md", words)
            assertTrue(said.contains("could not be read"), "'$said'")
            assertTrue(said.contains("put it back"), "the way out is the point: '$said'")
            assertTrue(said.contains("Nothing was lost"), "'$said'")
        }
    }

    @Test
    fun damageIsWhateverTheKernelSaidAndNothingHereSecondGuessesIt() {
        // The classification moved to the kernel, which owns the kinds.
        // Two copies of it — one per shell — meant a kind the kernel added
        // read as breakage in both until somebody remembered to edit them.
        assertTrue(alertIsDamage(alert(SYNC_GAP, damage = true)))
        assertFalse(alertIsDamage(alert(SYNC_UNKNOWN_OP_KIND, damage = false)))
        // Including for a kind this build has never heard of, where the
        // kernel's answer is "not damage": a newer condition is not
        // knowable here, and calling it breakage is an over-claim.
        assertFalse(alertIsDamage(alert("sync-something-from-2027", damage = false)))
        assertTrue(alertIsDamage(alert("sync-something-from-2027", damage = true)))
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
            alertsHeading(listOf(alert(SYNC_GAP, damage = true))),
        )
        assertTrue(
            alertsHeading(listOf(alert(SYNC_GAP, damage = true), alert(SYNC_UNKNOWN_OP_KIND)))
                .contains("2 reasons"),
            "a mixed banner counts what it holds",
        )
    }

    @Test
    fun aBannerOfOnlyUnknownKindsDoesNotOpenBySayingSyncStopped() {
        assertEquals(
            "Sync kept something it could not read",
            alertsHeading(listOf(alert(SYNC_UNKNOWN_OP_KIND), alert(SYNC_UNKNOWN_OP_KIND))),
        )
    }

    @Test
    fun aBannerOfOnlyWaitsAndKeepsNeverSaysSyncStopped() {
        assertEquals(
            "Sync is waiting for the rest of another device's history",
            alertsHeading(listOf(alert(SYNC_AWAITING_REFERENT))),
        )
        assertEquals(
            "A note deleted on another device was kept here",
            alertsHeading(listOf(alert(VAULT_DELETED_NOTE_EDITED))),
        )
        assertEquals(
            "Sync set aside a change about a device you removed",
            alertsHeading(listOf(alert(SYNC_REFERENT_REFUSED))),
        )
        assertEquals(
            "A note's text file could not be read",
            alertsHeading(listOf(alert(VAULT_CONTENT_UNREADABLE))),
        )
        val mixed = alertsHeading(listOf(alert(SYNC_AWAITING_REFERENT), alert(SYNC_UNKNOWN_OP_KIND)))
        assertTrue(mixed.startsWith("Nothing has stopped"), "'$mixed'")
        assertFalse(mixed.contains("stopped short"), "'$mixed'")
    }

    @Test
    fun oneRealHaltAmongTheHarmlessOnesStillOpensBySayingSyncStopped() {
        val said = alertsHeading(
            listOf(
                alert(SYNC_AWAITING_REFERENT),
                alert(SYNC_GAP, damage = true),
                alert(SYNC_UNKNOWN_OP_KIND),
            ),
        )
        assertTrue(said.startsWith("Sync stopped short"), "'$said'")
    }

    @Test
    fun aBannerOfKindsThisBuildNeverHeardOfDoesNotClaimBreakage() {
        // The second-order case for taking damage off the kind: an unknown
        // kind the kernel called harmless must not paint the banner red or
        // open by saying sync stopped.
        val unknown = listOf(alert("sync-something-from-2027"))
        assertFalse(alertsHeading(unknown).contains("stopped short"), alertsHeading(unknown))
        assertFalse(unknown.any { alertIsDamage(it) })
        // And it counts in English. One unrecognised harmless condition is
        // now a reachable state — it was not while every unknown kind was
        // treated as damage — and it must not read "1 things".
        assertEquals("Nothing has stopped — one thing worth knowing", alertsHeading(unknown))
        assertTrue(
            alertsHeading(unknown + alert(SYNC_AWAITING_REFERENT)).contains("2 things"),
        )
    }
}
