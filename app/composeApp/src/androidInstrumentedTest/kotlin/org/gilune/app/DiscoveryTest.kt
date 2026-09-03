// The `NsdManager` adapter on a device (ADR 0019 § 1).
//
// What can be checked without a second phone on the network is the
// contract rather than the finding: a browse answers inside its own
// timeout, an advertisement can be raised and withdrawn, and **every
// failure leaves as a `GiluneException`**. That last one is the property
// that matters most across this seam — the kernel calls these through a
// uniffi callback whose only error channel is `GiluneError`, so a stray
// platform exception is an unexpected-error panic rather than a message.

package org.gilune.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import uniffi.gilune_ffi.GiluneException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoveryTest {

    private fun adapter() =
        NsdDiscovery(InstrumentationRegistry.getInstrumentation().targetContext)

    /// Never `none` — that name belongs to the absence of an adapter, and
    /// a diagnostic has to be able to tell "nothing was found" from
    /// "nothing could look".
    @Test
    fun theRouteNamesItself() {
        assertEquals("NsdManager", adapter().name())
        assertTrue(DISCOVERY_NAME != "none")
    }

    /// Blocking by contract, and bounded by the timeout it was given. An
    /// emulator with nothing else on its network answers empty, which is
    /// the honest answer and not a failure.
    @Test
    fun aBrowseAnswersWithinItsOwnTimeout() {
        val started = System.currentTimeMillis()
        val found = adapter().browse(1_500u)
        val took = System.currentTimeMillis() - started
        assertTrue(took < 8_000, "a browse answers inside its budget, took ${took}ms")
        // Whatever it saw, every row is dialable and carries a fingerprint
        // — an adapter never reports a device nothing can be done with.
        for (device in found) {
            assertTrue(device.fingerprint.isNotBlank(), "a nameless row is not worth reporting")
            assertTrue(device.address.contains(':'), device.address)
        }
    }

    /// Both verbs block, and `NsdManager` has delivered its callbacks on
    /// the main looper in some platform versions — so a main-thread call
    /// would wait for an answer only it could deliver. Said, rather than
    /// hung.
    @Test
    fun theMainThreadIsRefusedRatherThanDeadlocked() {
        val thrown = AtomicReference<Throwable?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            thrown.set(runCatching { adapter().browse(50u) }.exceptionOrNull())
        }
        val error = thrown.get()
        assertTrue(error is GiluneException, "the error channel is the FFI's: $error")
        assertTrue(error.message.orEmpty().contains("main thread"), error.message.orEmpty())
    }

    /// Advertising is switched on and off, and either outcome is
    /// expressible: a network that will not carry the announcement is the
    /// "reachable but not findable" state § 1 exists for, and it arrives
    /// as a `GiluneException` rather than as a platform one.
    @Test
    fun anAdvertisementIsRaisedAndWithdrawnOrRefusedInWords() {
        val discovery = adapter()
        val outcome = runCatching { discovery.advertise("ABCDEFGH", 51837u) }
        val error = outcome.exceptionOrNull()
        if (error != null) {
            assertTrue(error is GiluneException, "the error channel is the FFI's: $error")
        }
        // Idempotent either way: withdrawing something that never
        // registered is a no-op, not a throw.
        discovery.withdraw()
        discovery.withdraw()
    }

    // ------------------------------------------------------------------
    // The browse's clock (§ 1)
    //
    // **The defect these pin.** `browse` waited up to `REGISTER_WAIT_MS`
    // (4 000) for `onDiscoveryStarted` and charged that against a deadline
    // the caller had set to 2 500. A slow start therefore spent the entire
    // budget before the resolve pump had read a single queued answer, and
    // the screen said "no devices found" while the peer was advertising —
    // the one wrong answer this surface can give, because it sends
    // somebody to a firewall that is not the problem.
    //
    // The arithmetic is pure and is where the fix actually lives; the
    // callbacks around it need a second phone.
    // ------------------------------------------------------------------

    @Test
    fun theRegistrationAwaitCannotEatTheBrowseBudget() {
        // Capped, whatever the browse was given. Four seconds of patience
        // is right at the switch and wrong inside a 2 500 ms browse.
        assertEquals(DISCOVERY_START_CAP_MS, discoveryStartWaitMs(2_500))
        assertTrue(
            discoveryStartWaitMs(2_500) < 2_500,
            "the wait for a start must be a fraction of the budget, not a multiple",
        )
    }

    @Test
    fun aShortBrowseIsNeverHeldOpenLongerThanItAskedFor() {
        // A caller that asks for 300 ms means it. The cap is a ceiling and
        // never a floor.
        assertEquals(300L, discoveryStartWaitMs(300))
        assertEquals(0L, discoveryStartWaitMs(0))
        assertEquals(0L, discoveryStartWaitMs(-1))
    }

    @Test
    fun anAnswerAlreadyInHandIsResolvedRatherThanDroppedOnTheDeadline() {
        // Inside the deadline a resolve gets what is left of it.
        assertEquals(900L, resolveBudgetMs(untilDeadlineMs = 900, untilDrainEndsMs = 1_400))
        // Past it — where a queued `onServiceFound` lands — it gets what
        // is left of the drain, so a device that answered is not thrown
        // away to report that nothing did.
        assertEquals(400L, resolveBudgetMs(untilDeadlineMs = -100, untilDrainEndsMs = 400))
        assertTrue(resolveBudgetMs(-100, 400) > 0, "a drained answer must get a chance")
    }

    @Test
    fun theDrainItselfEnds() {
        // The bound that stops a drain becoming a second browse.
        assertEquals(0L, resolveBudgetMs(untilDeadlineMs = 0, untilDrainEndsMs = 0))
        assertEquals(0L, resolveBudgetMs(untilDeadlineMs = -5, untilDrainEndsMs = -5))
        assertTrue(DISCOVERY_DRAIN_MS < DISCOVERY_START_CAP_MS * 2, "and it is small")
    }
}
