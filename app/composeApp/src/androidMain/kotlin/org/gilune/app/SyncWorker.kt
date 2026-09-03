// The phone's background sync (ADR 0019 § 10).
//
// **Not a foreground service, deliberately.** Android 15 caps a `dataSync`
// foreground service at six hours and Android 16 makes jobs started from
// one obey their runtime quotas, so routine sync as an FGS would buy a
// notification and a countdown rather than reliability. The manifest's own
// comment records that there is no boot receiver and no scheduler in it;
// this keeps that posture — WorkManager's schedule is the only standing
// thing, and it exists only while the user has sync switched on.
//
// **The constraints are the ADR's, including the one that is absent.**
// UNMETERED, so a background pass never spends somebody's data allowance;
// battery-not-low, so it stands aside when the phone needs what it has
// left. **No charging requirement** — the ADR is explicit that requiring
// it would make the job mean "overnight only" for most people.
//
// **Fifteen minutes is a floor, not a cadence**, and the settings screen
// says so in the same words: WorkManager clamps anything shorter, Doze
// then batches deferred work into maintenance windows that lengthen the
// longer a device stays idle, and App Standby buckets gate it further.
// The honest statement is near-fifteen-minutes while the phone is in use,
// degrading toward hourly or worse in a pocket overnight.
//
// The work is unique and enqueued with KEEP, so coming back to the app
// never resets the interval — a schedule that restarted on every resume
// would mean a phone picked up often never reaches its own first run.

package org.gilune.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import uniffi.gilune_ffi.Gilune
import java.util.concurrent.TimeUnit

/// The unique name the schedule is enqueued under. One schedule per
/// device, ever: enqueueing again finds this and keeps it.
const val SYNC_WORK = "gilune-background-sync"

/// The floor WorkManager clamps to. Named rather than inlined, because
/// the settings screen makes a claim about it and the two must not drift.
const val SYNC_PERIOD_MINUTES = 15L

private const val TAG = "GiluneSync"

/// The constraints of § 10, in one place so the screen's sentence and the
/// scheduler's behaviour cannot disagree.
fun syncWorkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .setRequiresBatteryNotLow(true)
    // No `setRequiresCharging` — see the file comment. It is absent on
    // purpose and its absence is the decision.
    .build()

fun syncWorkRequest(): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<SyncWorker>(SYNC_PERIOD_MINUTES, TimeUnit.MINUTES)
        .setConstraints(syncWorkConstraints())
        .build()

/// Start the background schedule. Called when a person switches sync on,
/// and **the standing schedule is what remembers that they did**: the
/// kernel's `enabled` is a bound socket in one process and does not
/// outlive it, so nothing else here survives the app being killed.
fun scheduleBackgroundSync(context: Context) {
    runCatching {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            SYNC_WORK,
            // KEEP, never UPDATE: a resume must not reset the interval.
            ExistingPeriodicWorkPolicy.KEEP,
            syncWorkRequest(),
        )
    }.onFailure { Log.w(TAG, "the background sync schedule was not created", it) }
}

/// Stop it. Switching sync off is the only thing that does this, so a
/// session in which nobody touched the switch leaves the schedule alone.
fun cancelBackgroundSync(context: Context) {
    runCatching {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(SYNC_WORK)
    }.onFailure { Log.w(TAG, "the background sync schedule was not cancelled", it) }
}

/// One background exchange.
///
/// It opens a `Gilune` handle of its own, on `RecordService`'s idiom and
/// for the same reason: multi-instance on one vault is supported use
/// (owner decision 2026-08-31) and a scheduled job cannot borrow an
/// activity's state. It binds nothing — `sync_now` dials paired peers and
/// the listener stays where § 2 put it, behind the switch — so a
/// background pass never opens an inbound socket.
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        return try {
            Gilune.open(vaultDir(context)).use { gilune ->
                gilune.configureSync(syncDir(context), deviceName(), KeystoreKeyWrap())
                // Registered so a peer whose cached address has gone stale
                // can still be found (§ 1's second step). Registering
                // binds nothing and browses nothing; it is only consulted
                // when a direct connection fails.
                runCatching { gilune.setDiscovery(NsdDiscovery(context)) }
                // Nothing to sync with is not a failure and not a retry —
                // it is the ordinary state of a phone that has never been
                // paired, and burning a maintenance window on it would be
                // the one background cost with nothing to show for it.
                if (gilune.syncStatus().peers.none { it.state == "paired" }) {
                    return Result.success()
                }
                val report = gilune.syncNow()
                gilune.project()
                if (report.halted.isNotEmpty()) {
                    // Halting is visible in Settings through the integrity
                    // alerts the kernel raised; the job itself succeeded
                    // in doing exactly what § 8 asks of it.
                    Log.i(TAG, "a background sync halted a peer: ${report.halted.first()}")
                }
                Result.success()
            }
        } catch (e: Throwable) {
            // Retried with WorkManager's own backoff. A phone that is on
            // the wrong network, or whose peer is asleep, is the ordinary
            // case rather than an error worth surfacing.
            Log.w(TAG, "the background sync did not complete", e)
            Result.retry()
        }
    }
}
