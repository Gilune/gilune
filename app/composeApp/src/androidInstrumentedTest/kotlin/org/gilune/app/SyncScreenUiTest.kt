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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import uniffi.gilune_ffi.FoundDevice
import uniffi.gilune_ffi.IntegrityAlert
import kotlin.test.assertTrue

class SyncScreenUiTest {
    private val resources = UiTestResources()
    private val rule = createDeviceComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(rule)

    /// A state that never opened a vault, so nothing overwrites the facts
    /// a test hands it.
    private fun state(): GiluneState =
        resources.state(resources.directory("gilune-sync").path)

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

    /// Where this phone answers, so the other device can be told it when
    /// discovery is filtered (§ 1's typed-address step). A phone on Wi-Fi
    /// and a VPN at once has more than one and cannot know which the
    /// desktop shares, so it shows them all rather than picking.
    @Test
    fun thePhoneListsEveryAddressItAnswersOnAndSaysWhichToTryFirst() {
        val state = state()
        state.syncStatus.value = syncStatus(
            listeningOn = "192.168.1.20:51837",
            listeningAddresses = listOf("192.168.1.20:51837", "10.8.0.3:51837"),
        )
        show(state)

        // Asserted against the row itself rather than by searching the
        // screen for the text: the addresses are drawn as one block, and a
        // search that missed would not say what the block actually held.
        rule.onNodeWithTag("sync-addresses")
            .assertTextContains("192.168.1.20:51837", substring = true)
        rule.onNodeWithTag("sync-addresses")
            .assertTextContains("10.8.0.3:51837", substring = true)
        rule.onNodeWithText("The first is the likeliest", substring = true).assertExists()
    }

    /// And the case that must draw nothing: an address a phone does not
    /// answer on is worse than none, because somebody will type it in.
    @Test
    fun aPhoneThatIsNotListeningShowsNoAddressAtAll() {
        val state = state()
        state.syncStatus.value = syncStatus(
            enabled = false,
            listeningOn = null,
            listeningAddresses = emptyList(),
        )
        show(state)
        rule.onNodeWithTag("sync-addresses").assertDoesNotExist()
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
        val edit = rule.onNodeWithTag("peer-address-edit")
        val scrollable = rule.onNode(hasScrollAction())
        val distance = with(rule.density) {
            (edit.getUnclippedBoundsInRoot().top - scrollable.getUnclippedBoundsInRoot().top).toPx()
        }
        // In this Compose version performScrollTo loops before the queued
        // ScrollBy coroutine runs. Issue one scroll and pump its scheduler.
        scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            assertTrue(scroll(0f, distance))
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitUntil(5_000) { edit.isDisplayed() }
        edit.performClick()
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
                // Damage is the kernel's call and travels on the row.
                damage = true,
            ),
        )
        show(state)

        rule.onNodeWithTag("sync-alerts").assertExists()
        val drawn = rule.onNodeWithTag("sync-alert").assertExists()
        assertTrue(state.syncAlerts.isNotEmpty())
        drawn.assertIsDisplayed()
        // The phone's own wording, from the table both shells share. The
        // subject and detail are deliberately absent from it — a device id
        // is not something a person holding a phone can act on — which is
        // why the sentence asked for here carries neither.
        rule.onNodeWithText(alertSentence(SYNC_GAP, "device-1", "", PHONE_ALERT_WORDS))
            .assertExists()
        rule.onNodeWithTag("sync-alerts-heading").assertExists()
    }

    /// The one unavailable state with a way out (§ 4). Before this round a
    /// phone that restored a backup got the generic "not set up yet" line
    /// and no action at all — the identity file was there, the Keystore key
    /// that sealed it was not, and nothing in the app could clear it.
    @Test
    fun aSealedIdentityWhoseKeyIsGoneOffersTheResetAndSaysWhatItCosts() {
        val state = state()
        // The typed answer enrolment recorded — **not** a phrase for the
        // screen to recognise. The message line still carries the kernel's
        // words, and deliberately in a shape the old prose matcher would
        // not have matched: the button is offered because of the type.
        state.identityUnreadable.value = true
        state.message.value = "sync unavailable: sealed elsewhere"
        show(state)

        rule.onNodeWithTag("sync-identity-unreadable").assertExists()
        rule.onNodeWithTag("sync-reset").assertExists()
        // And the generic line stands aside: two explanations of the same
        // state is one too many.
        rule.onNodeWithTag("sync-unavailable").assertDoesNotExist()
        rule.onNodeWithText(identityUnreadableSentence("this phone")).assertExists()
        // And the kernel's own wording is not drawn above it as well: the
        // same news twice, once in a form nobody can act on.
        rule.onNodeWithTag("sync-message").assertDoesNotExist()
    }

    /// Every other failure keeps the generic line and is offered nothing:
    /// a reset throws away a pairing, and offering it for a fault it
    /// cannot fix would cost somebody their roster for nothing.
    @Test
    fun anyOtherUnavailableStateIsNotOfferedAReset() {
        val state = state()
        state.message.value = "sync unavailable: the port was already taken"
        show(state)

        rule.onNodeWithTag("sync-unavailable").assertExists()
        rule.onNodeWithTag("sync-reset").assertDoesNotExist()
    }

    /// **The second-order case for typing this signal.** A failure that is
    /// not the identity one, whose *words* read exactly like the identity
    /// one. Under the shipped prose matching this offered to throw away a
    /// working identity and its whole roster.
    @Test
    fun aFailureThatMerelySoundsLikeTheIdentityOneIsOfferedNothing() {
        val state = state()
        state.identityUnreadable.value = false
        state.message.value =
            "sync unavailable: could not open the identity key file — the disk is full"
        show(state)

        rule.onNodeWithTag("sync-reset").assertDoesNotExist()
        rule.onNodeWithTag("sync-identity-unreadable").assertDoesNotExist()
        rule.onNodeWithTag("sync-unavailable").assertExists()
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
        // A different address from this phone's own, because it is a
        // different machine — and because the screen now shows both. The
        // fixture reused one string, so an assertion for it matched the
        // row *and* this phone's own "listening on" block.
        state.discovered.add(
            FoundDevice(
                fingerprint = "ABCD-EFGH",
                address = "192.168.1.44:51837",
                compatible = true,
                staticPub = "",
                state = "unknown",
            ),
        )
        show(state)

        rule.onNodeWithTag("found-row").assertExists()
        rule.onNodeWithText("192.168.1.44:51837").assertExists()
        rule.onNodeWithText("not paired yet").assertExists()
    }
}
