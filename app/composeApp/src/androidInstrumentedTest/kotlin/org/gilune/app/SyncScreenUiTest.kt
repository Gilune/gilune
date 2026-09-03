// Settings ▸ Sync on a device (ADR 0019 § 1, § 8, § 9).
//
// M5b shipped this screen with no instrumented test at all, which is the
// gap M5c closes: the surfaces below are the ones that carry a claim — a
// paired device, a standing halt, a network with devices on it — and each
// of them is exactly the surface that would be silently absent if a state
// stopped reaching it.
//
// The screen is driven from injected facts rather than a live kernel.
// `SyncSection` refreshes only when a vault is open, so a state with no
// handle leaves what the test put there standing, which is what makes
// these cheap enough to be worth having.

package org.gilune.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import uniffi.gilune_ffi.FoundDevice
import uniffi.gilune_ffi.IntegrityAlert
import java.nio.file.Files
import kotlin.test.assertTrue

class SyncScreenUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /// A state that never opened a vault, so nothing overwrites the facts
    /// a test hands it.
    private fun state(): GiluneState =
        GiluneState(Files.createTempDirectory("gilune-sync").toString(), scope)

    private fun show(state: GiluneState) {
        rule.setContent {
            GiluneTheme(dark = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { SyncSection(state) }
            }
        }
    }

    @Test
    fun theScreenDrawsThisDeviceAndItsPairingPaths() {
        val state = state()
        state.syncStatus.value = syncStatus()
        show(state)

        rule.onNodeWithTag("sync-switch").assertExists()
        rule.onNodeWithTag("sync-fingerprint").assertIsDisplayed()
        rule.onNodeWithTag("sync-protection").assertExists()
        // Both pairing paths, always (§ 3): the camera is a convenience
        // and never a condition of syncing.
        rule.onNodeWithTag("sync-scan").assertExists()
        rule.onNodeWithTag("sync-code").assertExists()
        // Nobody paired yet, and the screen says so rather than showing
        // an empty list.
        rule.onNodeWithTag("no-peers").assertExists()
        rule.onNodeWithTag("revoke-truth").assertDoesNotExist()
    }

    /// A vault whose sync never configured — the honest failure the ADR
    /// asks to be visible rather than a screen that looks broken.
    @Test
    fun withNoSyncFactsTheScreenSaysSoAndStopsThere() {
        val state = state()
        show(state)
        rule.onNodeWithTag("sync-unavailable").assertExists()
        rule.onNodeWithTag("sync-switch").assertDoesNotExist()
    }

    @Test
    fun aPairedDeviceGetsARowWithRevokeAndTheTruthBesideIt() {
        val state = state()
        state.syncStatus.value = syncStatus(peers = listOf(peer(name = "Davids laptop")))
        show(state)

        rule.onNodeWithTag("peer-row").assertExists()
        rule.onNodeWithText("Davids laptop").assertExists()
        rule.onNodeWithTag("peer-revoke").assertExists()
        // § 9: revocation protects what comes next and is not an undo, and
        // the sentence saying so is on the screen beside the button.
        rule.onNodeWithTag("revoke-truth").assertExists()
        // § 1's manual step, per peer.
        rule.onNodeWithTag("peer-address-edit").assertExists()
    }

    @Test
    fun aPeersAddressCanBeTypedIn() {
        val state = state()
        state.syncStatus.value = syncStatus(peers = listOf(peer(address = "")))
        show(state)

        rule.onNodeWithTag("peer-address-field").assertDoesNotExist()
        // Scrolled to first: this screen is longer than a phone, and a
        // click on a node clipped out of the viewport lands nowhere at
        // all rather than failing — which is how it reads as the button
        // simply not working.
        rule.onNodeWithTag("peer-address-edit").performScrollTo().performClick()
        rule.onNodeWithTag("peer-address-field").assertExists()
        // Nothing to save until it looks like host:port — the kernel
        // refuses an address with no colon and the button says so first.
        rule.onNodeWithTag("peer-address-save").assertIsNotEnabled()
    }

    /// § 8's whole point: a halted stream must not look like an idle one.
    @Test
    fun aStandingAlertIsDrawnAtTheTopInItsOwnWords() {
        val state = state()
        state.syncStatus.value = syncStatus()
        state.syncAlerts.add(
            IntegrityAlert(
                alertId = "alert-1",
                kind = "sync-gap",
                subject = "device-1",
                detail = "sequence 7 arrived after 5",
                raisedMs = 0,
            ),
        )
        show(state)

        rule.onNodeWithTag("sync-alerts").assertExists()
        val drawn = rule.onNodeWithTag("sync-alert").assertExists()
        assertTrue(state.syncAlerts.isNotEmpty())
        drawn.assertIsDisplayed()
        rule.onNodeWithText(alertSentence("sync-gap", "device-1", "")).assertExists()
    }

    @Test
    fun nothingStandingDrawsNoBanner() {
        val state = state()
        state.syncStatus.value = syncStatus()
        show(state)
        rule.onNodeWithTag("sync-alerts").assertDoesNotExist()
    }

    // -------------------------------------------------- discovery (§ 1)

    @Test
    fun theNetworkSectionOffersALookAndSaysWhatFindableMeans() {
        val state = state()
        state.syncStatus.value = syncStatus(advertising = true)
        show(state)

        rule.onNodeWithTag("sync-look").assertExists()
        rule.onNodeWithTag("sync-discovery").assertExists()
        rule.onNodeWithTag("sync-cadence").assertExists()
        // Nobody has looked, so nothing is claimed about the network.
        rule.onNodeWithTag("sync-none-found").assertDoesNotExist()
    }

    /// Off means nothing is bound and nothing is announced, so a look
    /// could only find devices that could not answer back.
    @Test
    fun lookingIsRefusedWhileSyncIsOff() {
        val state = state()
        state.syncStatus.value = syncStatus(enabled = false, advertising = false, listeningOn = null)
        show(state)

        rule.onNodeWithTag("sync-look").assertIsNotEnabled()
        rule.onNodeWithTag("sync-look-off").assertExists()
        rule.onNodeWithTag("found-row").assertDoesNotExist()
    }

    @Test
    fun aDeviceSeenOnTheNetworkGetsARow() {
        val state = state()
        state.syncStatus.value = syncStatus()
        state.discovered.add(
            FoundDevice(
                fingerprint = "ABCD-EFGH",
                address = "192.168.1.20:51837",
                compatible = true,
                staticPub = "",
                state = "unknown",
            ),
        )
        show(state)

        rule.onNodeWithTag("found-row").assertExists()
        rule.onNodeWithText("192.168.1.20:51837").assertExists()
        rule.onNodeWithText("not paired yet").assertExists()
    }
}
