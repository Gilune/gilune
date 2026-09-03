// The background schedule (ADR 0019 § 10).
//
// The constraints are the decision, so they are what is pinned — including
// the one that is absent. Requiring charging would turn "about every
// fifteen minutes" into "overnight only" for most people, and the absence
// of that requirement is easy to add back by accident.

package org.gilune.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncWorkerTest {

    @Test
    fun theConstraintsAreUnmeteredAndBatteryNotLowAndNotCharging() {
        val constraints = syncWorkConstraints()
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresCharging(), "§ 10: charging would mean overnight only")
    }

    /// Fifteen minutes is WorkManager's own floor, and the settings screen
    /// makes a claim about it. Both read the same constant so they cannot
    /// drift apart.
    @Test
    fun thePeriodIsTheFloorWorkManagerClampsTo() {
        assertEquals(15L, SYNC_PERIOD_MINUTES)
        val request = syncWorkRequest()
        assertEquals(
            TimeUnit.MINUTES.toMillis(SYNC_PERIOD_MINUTES),
            request.workSpec.intervalDuration,
        )
    }

    /// The schedule is unique and enqueued with KEEP, so a resume never
    /// resets the interval — a phone picked up often would otherwise never
    /// reach its own first run. Enqueued here for real, because the thing
    /// worth knowing is that WorkManager initialised at all: `androidx.work`
    /// contributes an `androidx.startup.InitializationProvider` to the
    /// merged manifest, and that provider is the defect the Moonshine AAR's
    /// exclusion exists for.
    @Test
    fun theScheduleIsEnqueuedOnceUnderItsOwnName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = WorkManager.getInstance(context)
        try {
            scheduleBackgroundSync(context)
            scheduleBackgroundSync(context)

            val infos = work.getWorkInfosForUniqueWork(SYNC_WORK).get(10, TimeUnit.SECONDS)
            assertEquals(1, infos.size, "unique means one, however often it is asked for")
            val info = infos.first()
            assertTrue(
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING,
                "the schedule stands: ${info.state}",
            )
            assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
            assertTrue(info.constraints.requiresBatteryNotLow())
            assertFalse(info.constraints.requiresCharging())
        } finally {
            // Switching sync off is the only thing that cancels it in the
            // app; a test cleans up after itself so it leaves no schedule
            // running against the emulator's scratch vault.
            cancelBackgroundSync(context)
        }
    }

    // ------------------------------------------------------------------
    // What a background pass is allowed to do (§ 2, § 10)
    //
    // **The defect this pins.** The worker opened a handle, configured
    // sync, checked only that somebody was paired, and exchanged. Nothing
    // in it consulted whether sync was switched **on** — and nothing below
    // it would have: the kernel's outbound permission is process-local and
    // starts permissive by design, so that a background pass can dial
    // without ever binding a listener. A phone whose owner had switched
    // sync off therefore kept syncing every fifteen minutes.
    //
    // The field it has to read is `wanted`, not `enabled`. `enabled` is a
    // bound listener in this process and this process binds nothing, so
    // gating on it would have stopped the background sync altogether —
    // the same bug pointing the other way.
    // ------------------------------------------------------------------

    @Test
    fun aBackgroundPassRunsOnlyWhenTheSwitchIsOnAndSomebodyIsPaired() {
        val paired = listOf(peer())
        assertTrue(shouldSyncInBackground(syncStatus(peers = paired)))
        // Switched off: nothing to do, and not a failure or a retry.
        assertFalse(shouldSyncInBackground(syncStatus(enabled = false, peers = paired)))
        // Nobody paired: dialling nothing costs a thread for the privilege.
        assertFalse(shouldSyncInBackground(syncStatus(peers = emptyList())))
        assertFalse(shouldSyncInBackground(null))
        // A revoked peer is not somebody to sync with.
        assertFalse(shouldSyncInBackground(syncStatus(peers = listOf(peer(state = "revoked")))))
    }

    @Test
    fun theBackgroundGateReadsThePersistedSwitchRatherThanABoundListener() {
        // The state a worker is actually in: the person left sync on, and
        // this fresh process has bound nothing. Reading `enabled` here
        // would answer "off" and the background pass would never run —
        // which is why the two gates are two functions.
        val worker = syncStatus(enabled = false, wanted = true, peers = listOf(peer()))
        assertTrue(shouldSyncInBackground(worker), "a worker binds nothing and still syncs")
        assertFalse(shouldSyncOnForeground(worker), "the foreground gate is the listener's")

        // And the reverse, which is the case that was leaking: a listener
        // somehow up while the persisted choice is off.
        val contradiction = syncStatus(enabled = true, wanted = false, peers = listOf(peer()))
        assertFalse(shouldSyncInBackground(contradiction), "the switch is what decides")
    }

    @Test
    fun cancellingLeavesNothingStanding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = WorkManager.getInstance(context)
        scheduleBackgroundSync(context)
        cancelBackgroundSync(context)
        val infos = work.getWorkInfosForUniqueWork(SYNC_WORK).get(10, TimeUnit.SECONDS)
        assertTrue(
            infos.all { it.state == WorkInfo.State.CANCELLED },
            "nothing enqueued survives a switch-off: ${infos.map { it.state }}",
        )
    }
}
