// How this phone finds other devices, and lets itself be found
// (ADR 0019 § 1).
//
// The kernel owns the `PeerDiscovery` capability and two hosts implement
// it: `mdns-sd` in Rust for the desktop and any future headless agent,
// and this adapter over `NsdManager` for Android. That split is ADR 0001's
// rule applied literally — Rust does not call a platform API, an adapter
// implements the trait.
//
// **There is no `MulticastLock` here, and no `CHANGE_WIFI_MULTICAST_STATE`,
// and that is the entire reason `NsdManager` was chosen** over running an
// mDNS stack of our own in this process. Android suppresses multicast
// delivery to app sockets to save battery, so an in-process stack needs a
// permission and a held lock, and behaves worst exactly when the screen is
// off. The platform owns the socket here, so battery behaviour is the
// platform's problem rather than ours. A future reader will be tempted to
// add the lock when discovery finds nothing on some network; it would not
// help, and it would buy a new permission for the privilege.
//
// **The advertisement carries no personal data.** The DNS-SD instance name
// is the device's static-key fingerprint and the TXT record carries the
// protocol version and that fingerprint and nothing else — no device name,
// no user, no vault. A paired peer recognises the fingerprint; an observer
// on a café network learns that a Gilune device is present, which is
// unavoidable for any advertised service, and not whose nor what it is
// called. The human name is exchanged inside the encrypted handshake.
//
// Nothing here is on the connect path when it can be avoided: § 1 tries a
// paired peer's cached address first and browses only when that fails,
// which is what makes the phone's background job cheap.

package org.gilune.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import uniffi.gilune_ffi.DiscoveredDevice
import uniffi.gilune_ffi.GiluneException
import uniffi.gilune_ffi.PeerDiscovery
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/// The service type as `NsdManager` wants it spelled — **without** the
/// trailing `.local.` that `mdns-sd` requires. The kernel's own copy of
/// both spellings lives in `gilune-sync::discovery`; this one exists
/// because the platform API takes the short form and a mismatch registers
/// a service nothing browses for.
const val SYNC_SERVICE_TYPE = "_gilune._tcp"

/// The wire protocol version, matching `gilune_sync::PROTOCOL`. It is
/// carried verbatim in the TXT record and compared verbatim in Rust: a
/// device advertising something else is shown as present and unreachable
/// rather than hidden, because "there is a Gilune here I cannot talk to"
/// is a fact worth seeing.
const val SYNC_PROTOCOL = "gilune-sync/1"

/// The two TXT keys, and that is the whole record.
const val TXT_VERSION = "v"
const val TXT_FINGERPRINT = "fp"

/// What this route calls itself in a diagnostic that has to say whether
/// nothing was found or nothing could look. Never `none` — that name
/// belongs to the absence of an adapter.
const val DISCOVERY_NAME = "NsdManager"

private const val TAG = "GiluneNsd"

/// How long registration is given to confirm before advertising is
/// reported as failed. Registration normally settles in well under a
/// second; a timeout means the multicast half did not come up, which is
/// the "reachable but not findable" state § 1's fallback chain exists for
/// and which Settings says out loud.
private const val REGISTER_WAIT_MS = 4_000L

/// How long a **browse** may spend waiting for discovery to start.
///
/// Deliberately not [REGISTER_WAIT_MS]. Registration is a one-off at the
/// switch and four seconds of patience there costs nothing; a browse is
/// asked for inside § 1's five-second sync budget, and until this round it
/// charged the same four seconds against a 2 500 ms deadline. A slow
/// `onDiscoveryStarted` therefore burned the whole budget before a single
/// answer was read, and the screen said "no devices found" while the peer
/// was advertising — the one wrong answer this surface can give, because
/// it sends somebody to a firewall that is not the problem.
const val DISCOVERY_START_CAP_MS = 1_000L

/// A grace period for answers **already in hand** when the deadline
/// passes. A queued `onServiceFound` is a device that answered; reporting
/// nothing while holding it is the same wrong answer by a different route.
/// Bounded so a drain cannot become a second browse.
const val DISCOVERY_DRAIN_MS = 500L

/// How long to wait for discovery to start, given the browse's own budget.
///
/// Capped, and never longer than the budget itself: a caller that asked for
/// 300 ms means 300 ms, and a platform that never calls back must not be
/// able to hold a browse open past what was asked for plus the cap.
fun discoveryStartWaitMs(budgetMs: Long, cap: Long = DISCOVERY_START_CAP_MS): Long =
    budgetMs.coerceAtMost(cap).coerceAtLeast(0)

/// What one resolve is allowed, given how long is left of the browse and
/// how long is left of the drain that follows it.
///
/// Inside the deadline it is simply what remains. Past it — which is where
/// a queued answer being drained lands — it is what remains of the drain,
/// so an answer the browse already has is resolved rather than dropped on
/// the boundary, and the drain still ends.
fun resolveBudgetMs(untilDeadlineMs: Long, untilDrainEndsMs: Long): Long =
    if (untilDeadlineMs > 0) untilDeadlineMs else untilDrainEndsMs.coerceAtLeast(0)

/// A TXT value, read defensively. It arrives as `Map<String, ByteArray?>`
/// with every part of it optional, and anything on the network can put
/// anything in it, so nothing here trusts what it reads: an unreadable
/// value is an empty one, and an empty protocol is what makes a device
/// show as incompatible rather than crash a browse.
fun txtString(attributes: Map<String, ByteArray?>?, key: String): String {
    val bytes = attributes?.get(key) ?: return ""
    return String(bytes, Charsets.UTF_8).trim().take(TXT_VALUE_CAP)
}

/// A TXT value is at most 255 bytes on the wire; this caps what a
/// malformed record can drag into a settings screen.
private const val TXT_VALUE_CAP = 255

/// The one thread every adapter's resolve callbacks land on.
///
/// File-level rather than per-instance because an adapter is created per
/// vault open and nothing ever closes one — the capability trait has no
/// verb for it — so a per-instance executor leaked a daemon thread on
/// every vault switch. Sharing is free here: resolution is serial by
/// design already (below API 34 the platform answers a second concurrent
/// resolve with `FAILURE_ALREADY_ACTIVE`), and only one adapter is live
/// at a time.
private val NSD_CALLBACKS: java.util.concurrent.ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gilune-nsd").apply { isDaemon = true }
    }

/// Which of a resolved service's addresses to dial.
///
/// IPv4 first, because that is what a home network routes and what the
/// desktop listener binds. A link-local IPv6 address is skipped outright:
/// its literal carries a `%wlan0` scope that no `host:port` parser on
/// either side accepts, so offering one would produce an address that
/// looks dialable and never dials.
fun preferredHost(addresses: List<InetAddress>): InetAddress? =
    addresses.firstOrNull { it is Inet4Address && it.isUsableHost() }
        ?: addresses.firstOrNull { it is Inet6Address && it.isUsableHost() && !it.isLinkLocalAddress }

private fun InetAddress.isUsableHost(): Boolean =
    !isLoopbackAddress && !isAnyLocalAddress && !isMulticastAddress

/// `host:port`, ready to dial, or null when there is nothing to dial.
/// IPv6 is bracketed because that is what makes a literal parse beside a
/// port at all.
fun dialAddress(host: InetAddress?, port: Int): String? {
    if (host == null || port !in 1..65535) return null
    val literal = host.hostAddress?.substringBefore('%')?.takeIf { it.isNotBlank() } ?: return null
    return if (host is Inet6Address) "[$literal]:$port" else "$literal:$port"
}

/// What an `NsdManager` failure code means, in words. The codes are
/// otherwise bare integers in a log line nobody can act on.
fun nsdErrorSentence(code: Int): String = when (code) {
    NsdManager.FAILURE_ALREADY_ACTIVE -> "the same request was already running"
    NsdManager.FAILURE_INTERNAL_ERROR -> "the phone's network service reported an internal error"
    NsdManager.FAILURE_MAX_LIMIT -> "the phone is already running as many of these as it allows"
    else -> "the phone's network service refused it (code $code)"
}

/// The adapter itself.
///
/// Both verbs block by contract: the kernel calls them from a background
/// thread and expects an answer, not a promise. `NsdManager` is
/// callback-driven, so each one waits on a latch and always stops what it
/// started in a `finally`.
class NsdDiscovery(context: Context) : PeerDiscovery {
    private val nsd =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /// The live registration, or null while this device is not
    /// advertising. Held so `withdraw` has something to unregister and so
    /// a second `advertise` replaces rather than stacks.
    private val advertised = AtomicReference<NsdManager.RegistrationListener?>(null)

    /// Where the API 34+ resolve callbacks land. Ours rather than the
    /// caller's, so a resolution never runs on the thread that is blocked
    /// waiting for it.
    ///
    /// **Shared across instances rather than one per adapter.** An
    /// adapter is created per vault open, so a per-instance executor
    /// would leak a daemon thread on every vault switch — nothing shuts
    /// one down, because an adapter has no close and the trait has no
    /// verb for it. Sharing costs nothing here: resolution is already
    /// serial by design (below API 34 the platform answers a second
    /// concurrent resolve with `FAILURE_ALREADY_ACTIVE`), and only one
    /// adapter is ever live.
    private val callbacks get() = NSD_CALLBACKS

    override fun name(): String = DISCOVERY_NAME

    @Synchronized
    override fun advertise(fingerprint: String, port: UShort) {
        requireBackgroundThread("advertising")
        withdraw()

        val info = NsdServiceInfo()
        // The instance name IS the fingerprint (§ 1) — the type cannot be
        // asked to carry anything else, and neither can the record below.
        info.serviceName = fingerprint
        info.serviceType = SYNC_SERVICE_TYPE
        info.port = port.toInt()
        info.setAttribute(TXT_VERSION, SYNC_PROTOCOL)
        info.setAttribute(TXT_FINGERPRINT, fingerprint)

        val settled = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) = settled.countDown()
            override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                failure.set(nsdErrorSentence(errorCode))
                settled.countDown()
            }
            override fun onServiceUnregistered(unregistered: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "the advertisement did not withdraw: ${nsdErrorSentence(errorCode)}")
            }
        }
        advertised.set(listener)
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            advertised.set(null)
            throw GiluneException.Invalid("this phone could not advertise itself: ${e.message}")
        }

        // Waited for, rather than assumed. `SyncStatus.advertising` is
        // this call's outcome, and § 1 is explicit that one flag for both
        // halves would tell a person their device is findable when it is
        // only reachable — so an unconfirmed registration is reported as
        // a failure, and the screen says the honest thing.
        val confirmed = settled.await(REGISTER_WAIT_MS, TimeUnit.MILLISECONDS)
        val why = failure.get()
        if (why != null) {
            withdraw()
            throw GiluneException.Invalid("this phone could not advertise itself: $why")
        }
        if (!confirmed) {
            throw GiluneException.Invalid(
                "this phone did not confirm it is advertising within " +
                    "${REGISTER_WAIT_MS / 1000} seconds",
            )
        }
    }

    @Synchronized
    override fun withdraw() {
        val listener = advertised.getAndSet(null) ?: return
        runCatching { nsd.unregisterService(listener) }
            .onFailure { Log.w(TAG, "the advertisement did not withdraw cleanly", it) }
    }

    /// Browse for up to `timeoutMs` and answer with what resolved inside
    /// it. Whatever is found by the deadline is the answer: § 1 puts a
    /// cold browse inside the standing five-second sync budget, so a
    /// browse that waited for the network to go quiet would have spent
    /// the budget before a byte moved.
    override fun browse(timeoutMs: ULong): List<DiscoveredDevice> {
        requireBackgroundThread("browsing")
        val budget = timeoutMs.toLong()
        val queue = LinkedBlockingQueue<NsdServiceInfo>()
        val started = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = started.countDown()
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                failure.set(nsdErrorSentence(errorCode))
                started.countDown()
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "the browse did not stop: ${nsdErrorSentence(errorCode)}")
            }
            override fun onServiceFound(found: NsdServiceInfo) {
                queue.put(found)
            }
            override fun onServiceLost(lost: NsdServiceInfo) = Unit
        }

        try {
            nsd.discoverServices(SYNC_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            throw GiluneException.Invalid("this phone could not look for devices: ${e.message}")
        }

        val found = LinkedHashMap<String, DiscoveredDevice>()
        try {
            // **The budget for answers begins after discovery has
            // started**, and starting is capped separately. Charging the
            // registration await against the browse deadline is what made
            // a slow `onDiscoveryStarted` produce "no devices found" while
            // a peer was advertising: the pump below exited before it had
            // read a single queued answer. The worst case is now the
            // caller's budget plus the cap plus the drain, all bounded and
            // all stated.
            started.await(discoveryStartWaitMs(budget), TimeUnit.MILLISECONDS)
            failure.get()?.let {
                throw GiluneException.Invalid("this phone could not look for devices: $it")
            }
            val deadline = SystemClock.elapsedRealtime() + budget
            val drainEnds = deadline + DISCOVERY_DRAIN_MS
            // Resolved one at a time, deliberately. Below API 34 the
            // platform runs exactly one resolve and answers a second with
            // FAILURE_ALREADY_ACTIVE, so a serial pump is the shape that
            // works on both paths rather than two shapes to keep in step.
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                // Past the deadline the queue is **drained** rather than
                // abandoned: an answer already in hand is a device that
                // announced itself, and throwing it away to report nothing
                // is the same wrong answer the deadline fix exists to
                // stop. `poll()` without a timeout takes what is there and
                // returns null the moment there is not.
                val next = (if (left > 0) queue.poll(left, TimeUnit.MILLISECONDS) else queue.poll())
                    ?: break
                if (SystemClock.elapsedRealtime() >= drainEnds) break
                val instance = next.serviceName ?: continue
                if (found.containsKey(instance)) continue
                val now = SystemClock.elapsedRealtime()
                resolve(next, resolveBudgetMs(deadline - now, drainEnds - now))
                    ?.let { found[instance] = it }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
                .onFailure { Log.w(TAG, "the browse did not stop cleanly", it) }
        }
        return found.values.toList()
    }

    private fun resolve(found: NsdServiceInfo, budgetMs: Long): DiscoveredDevice? {
        if (budgetMs <= 0) return null
        // Both paths, because the platform changed under us and minSdk is
        // 29. `resolveService` was deprecated at API 34 in favour of
        // `registerServiceInfoCallback`, which resolves concurrently and
        // keeps answering as a service changes; the deprecated call is
        // still the only one that exists below 34, so this build carries
        // each on the versions it is correct for rather than suppressing
        // the deprecation everywhere and losing the newer behaviour on
        // every recent phone.
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveViaCallback(found, budgetMs)
        } else {
            resolveViaResolveService(found, budgetMs)
        } ?: return null
        return describe(resolved)
    }

    private fun resolveViaCallback(found: NsdServiceInfo, budgetMs: Long): NsdServiceInfo? {
        val answer = AtomicReference<NsdServiceInfo?>(null)
        val settled = CountDownLatch(1)
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "a device did not resolve: ${nsdErrorSentence(errorCode)}")
                settled.countDown()
            }

            override fun onServiceUpdated(updated: NsdServiceInfo) {
                answer.compareAndSet(null, updated)
                settled.countDown()
            }

            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        return try {
            nsd.registerServiceInfoCallback(found, callbacks, callback)
            settled.await(budgetMs, TimeUnit.MILLISECONDS)
            answer.get()
        } catch (e: Throwable) {
            Log.w(TAG, "a device did not resolve", e)
            null
        } finally {
            // Refuses a callback it never registered, which is exactly the
            // path a registration failure takes.
            runCatching { nsd.unregisterServiceInfoCallback(callback) }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveViaResolveService(found: NsdServiceInfo, budgetMs: Long): NsdServiceInfo? {
        val answer = AtomicReference<NsdServiceInfo?>(null)
        val settled = CountDownLatch(1)
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "a device did not resolve: ${nsdErrorSentence(errorCode)}")
                settled.countDown()
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                answer.set(resolved)
                settled.countDown()
            }
        }
        return try {
            nsd.resolveService(found, listener)
            settled.await(budgetMs, TimeUnit.MILLISECONDS)
            answer.get()
        } catch (e: Throwable) {
            Log.w(TAG, "a device did not resolve", e)
            null
        }
    }

    /// What the kernel is told about one resolved service. The
    /// fingerprint comes from the TXT record and falls back to the
    /// instance name, which is the same string by construction — the
    /// fallback exists for a record that did not survive the resolver,
    /// not for a device that named itself something else.
    private fun describe(info: NsdServiceInfo): DiscoveredDevice? {
        val address = dialAddress(preferredHost(hostsOf(info)), info.port) ?: return null
        val attributes = info.attributes
        val fingerprint = txtString(attributes, TXT_FINGERPRINT)
            .ifBlank { info.serviceName?.trim().orEmpty() }
        if (fingerprint.isBlank()) return null
        return DiscoveredDevice(
            fingerprint = fingerprint,
            // Verbatim, never judged here: an adapter reports what it
            // read and Rust decides what it means.
            protocol = txtString(attributes, TXT_VERSION),
            address = address,
        )
    }

    @Suppress("DEPRECATION")
    private fun hostsOf(info: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }

    /// Both verbs block, and `NsdManager` has delivered its callbacks on
    /// the main looper in some platform versions — which would make a
    /// call from the main thread wait for an answer only it could
    /// deliver. Said plainly rather than hanging.
    private fun requireBackgroundThread(what: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw GiluneException.Invalid("$what blocks and cannot run on the main thread")
        }
    }
}
