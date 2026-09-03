// Settings ▸ Sync in front of a screen, for the two things only a screen
// can answer: what it knows when it opens, and what it draws once it knows
// (ADR 0019 § 8).
//
// **The defect this pins.** The section drew `state.syncStatus` and
// `state.syncAlerts` and never loaded either. The phone's version had done
// so on entry since M5c; the desktop's had not, so it showed whatever the
// last action in that session had left behind — which on a fresh launch is
// nothing at all. A stream that halted overnight was therefore invisible
// until somebody happened to press a button, which is § 8's own "failure
// mode that would make sync look like it works" with a longer fuse.
//
// So: put stale values in, open the section, and assert the kernel's own
// answers replaced them. Reading the kernel for real is the point — a
// fixture could not tell a screen that reads from one that does not.

package org.gilune.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.IntegrityAlert
import uniffi.gilune_ffi.SyncStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncSettingsUiTest {
    @get:Rule
    val compose = createComposeRule()

    /// A real enrolled handle. The platform adapter is offered when there
    /// is one and `null` is a supported answer — the kernel then records
    /// `keyProtection = "none"`, which this very screen says out loud — so
    /// this works on a machine with no DPAPI as well as on one with it.
    private fun enrolled(): Pair<Gilune, SyncStatus> {
        val vault = Files.createTempDirectory("gilune-syncui").toString()
        val gilune = Gilune.open(vault)
        val identity = Files.createTempDirectory("gilune-identity").toFile()
        return gilune to gilune.configureSync(identity.absolutePath, "A test machine", platformKeyWrap())
    }

    private fun state(vault: String) = GiluneState(vault, CoroutineScope(Dispatchers.Default))

    /// The whole fix, in one row.
    @Test
    fun theSectionReadsTheKernelsFactsAndAlertsOnEntryRatherThanDrawingWhatWasLeftBehind() {
        val (gilune, _) = enrolled()
        val subject = state(gilune.vaultPath())
        subject.gilune.value = gilune
        // Deliberately stale. A status of `null` is what a fresh launch
        // has; an alert nothing raised is what a previous vault in the
        // same session could have left in the list.
        assertNull(subject.syncStatus.value, "the fixture must start with nothing loaded")
        subject.syncAlerts.add(
            IntegrityAlert(
                alertId = "left-over",
                kind = SYNC_GAP,
                subject = "a device this vault has never met",
                detail = "",
                raisedMs = 0,
            ),
        )

        compose.setContent { Column { SyncSection(subject) } }
        // Both halves, because they are two reads and the fix is that
        // entry performs both: waiting on the status alone would let the
        // alert assertion race the second half of `refreshSync`.
        compose.waitUntil(10_000) {
            subject.syncStatus.value != null && subject.syncAlerts.none { it.alertId == "left-over" }
        }
        compose.waitForIdle()

        // The facts arrived: this device's own fingerprint is drawn, where
        // before entry the section stopped at "has not enrolled yet".
        val status = assertNotNull(subject.syncStatus.value)
        assertTrue(status.fingerprint.isNotBlank(), "a device with no fingerprint is not enrolled")
        compose.onNodeWithText(status.fingerprint, substring = true).assertExists()
        // And an entry that only *added* would show a halt that had
        // already cleared, which is the same lie in the other direction.
        assertTrue(subject.syncAlerts.isEmpty(), "a fresh vault has no standing conditions")
    }

    /// What a standing condition looks like once one is there: above the
    /// switch, headed, and in words rather than in the kernel's kind
    /// string.
    @Test
    fun aStandingConditionIsDrawnAboveEverythingElseAndInWords() {
        val (gilune, status) = enrolled()
        // Deliberately **not** published to the state: with no handle the
        // section's entry read does nothing, so what is drawn is exactly
        // what this test put there and nothing races it.
        val subject = state(gilune.vaultPath())
        subject.syncStatus.value = status
        subject.syncAlerts.add(
            IntegrityAlert(
                alertId = "alert-1",
                kind = SYNC_GAP,
                subject = "device-7",
                detail = "seq 41",
                raisedMs = 0,
            ),
        )

        compose.setContent { Column { SyncSection(subject) } }
        compose.waitForIdle()

        compose.onNodeWithText("Sync stopped short of taking everything in").assertExists()
        compose.onNodeWithText(
            alertSentence(SYNC_GAP, "device-7", "seq 41", DESKTOP_ALERT_WORDS),
        ).assertExists()
        gilune.close()
    }
}
