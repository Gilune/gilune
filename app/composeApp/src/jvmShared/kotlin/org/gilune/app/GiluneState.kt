// Shared UI state over the boundary: one open vault, its capture feed,
// the installed plugins, and the sync/processing cycle. Composables
// render this; every decision stays across the boundary (conventions
// § Kotlin).

package org.gilune.app

import org.gilune.plugins.tasks.tasksFilter

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.gilune_ffi.Cursor
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.SourceSummary
import uniffi.gilune_ffi.TombstoneProposal

/// How much of a recording crosses the boundary at a time. Streamed
/// capture is chunk-by-chunk so a process kill loses only the tail.
const val CAPTURE_CHUNK = 64 * 1024

/// How many changes one read of the feed takes. The shell only wants to
/// know **that** the vault moved, so the page is a bound on memory and
/// nothing more; a long feed is several short reads.
private const val CHANGE_PAGE = 500u

/// Whether a device exchange left this vault anything to re-project.
///
/// Projection is a full pass over the vault's objects. A sync that applied
/// nothing and completed nothing changed nothing here, so there is nothing
/// for it to write — and the phone's background schedule runs on a
/// fifteen-minute floor, where a pointless full pass is the difference
/// between a cheap job and a battery complaint. Sending deliberately does
/// not count: what this device sent is already on its own disk.
///
/// **Blobs count, and the first round of this fix missed them.** An
/// operation and the bytes it refers to arrive on their own schedules: a
/// recording's note can apply in one session and its audio complete in the
/// next, and that second session applies *nothing*. Projecting only on
/// `applied` left the note pointing at a file the vault now had, with no
/// pass to write the link — a recording that stayed silent until something
/// unrelated happened to project. So either half is reason enough.
///
/// Pure, and named rather than inlined, so the rule reads the same in the
/// activity and in the worker.
fun syncNeedsProjection(applied: Long, blobsCompleted: Long): Boolean =
    applied > 0L || blobsCompleted > 0L

/// What one pass of a host's **external** provider did (ADR 0018 § 2).
/// `transcribed` is words written; `skipped` is jobs completed with nothing
/// to say — both are successes — and `failed` is jobs the provider ran and
/// could not finish.
///
/// **Shared rather than Android-only, because the seam carries it.** It
/// lived beside the Moonshine loop until this round, so the only way to
/// hand it to `GiluneState` was to throw away everything but `transcribed`
/// — which is exactly what both hosts did, and why a provider that failed
/// every recording it was given produced the same "0 transcribed" sentence
/// as a vault with nothing to transcribe. A failure that is not thrown is
/// still a failure, and a count that drops it is a silence.
data class ProviderPass(
    val transcribed: Long = 0,
    val skipped: Long = 0,
    val failed: Long = 0,
) {
    val claimed: Long get() = transcribed + skipped + failed
}

class GiluneState(val vaultDir: String, private val scope: CoroutineScope) {
    val gilune = mutableStateOf<Gilune?>(null)
    val message = mutableStateOf("opening vault…")

    /// A line the next editor to open its note should land on (a task or
    /// a search match clicked in a list); that editor clears it.
    val reveal = mutableStateOf<NoteLine?>(null)

    fun revealNext(target: NoteLine) {
        reveal.value = target
    }
    val recent = mutableStateListOf<SourceSummary>()
    val plugins = mutableStateListOf<PluginInfo>()

    /// Deletions made outside the app that await a keep-or-delete
    /// decision. In-app deletions answer their own proposal, so anything
    /// here came from another tool — supported use (rule 13).
    val proposals = mutableStateListOf<TombstoneProposal>()

    /// Where this shell has read up to in the vault's change feed
    /// (ADR 0020 § 2.1). Panels, the tree and every query watch it and
    /// re-run when it moves.
    ///
    /// **A cursor, not a tick.** The tick it replaces was bumped by
    /// anything that might have touched the vault and carried no identity
    /// at all, so it fired when nothing had changed and stayed silent
    /// when another process changed everything. A cursor is the kernel's
    /// answer to "what has happened since I last looked", so it moves
    /// exactly when the vault's files did — a second desktop instance's
    /// save and the phone's recording service included, because the feed
    /// lives beside the vault rather than in a process.
    ///
    /// Null until the vault is open. The first advance discards the
    /// history it finds: a shell opening a vault has nothing to catch up
    /// on, it has everything to draw.
    val vaultCursor = mutableStateOf<Cursor?>(null)
    val searchRefresh = mutableStateOf(0)

    fun retrySearchIndex() {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { g.advanceIndex() }
                .onFailure { GiluneLog.warn("could not refresh the search index", it) }
            searchRefresh.value += 1
        }
    }

    /// Bring the cursor up to the end of the feed, and say whether it
    /// moved. Cheap on a settled vault: one indexed read that finds
    /// nothing waiting.
    ///
    /// A cursor the vault will not answer from — a different vault's
    /// database swapped in behind an open handle — is not an error to
    /// report but a reader to reseat, which is what `change_origin` is
    /// for.
    private fun advanceCursor(g: Gilune): Boolean {
        val from = vaultCursor.value ?: runCatching { g.changeOrigin() }.getOrNull() ?: return false
        var at = from
        var reseated = false
        while (true) {
            val page = runCatching { g.changesSince(at, CHANGE_PAGE) }.getOrNull()
            if (page == null) {
                // Once. A second refusal from the beginning of the feed
                // is the vault failing, not this reader being stale.
                if (reseated) return false
                reseated = true
                at = runCatching { g.changeOrigin() }.getOrNull() ?: return false
                continue
            }
            at = page.next
            if (page.complete) break
        }
        vaultCursor.value = at
        return at != from
    }

    /// Processors this host implements **outside** the kernel (ADR 0018
    /// § 2). `runProcessing` deliberately leaves their jobs alone — the
    /// registered host claims them itself — so wherever this state runs
    /// the kernel's loop it must run theirs too, or an external provider's
    /// words would arrive only when something else happened to ask.
    ///
    /// Null everywhere but the Android shell, where a Moonshine provider
    /// is registered on this handle. The desktop keeps whisper in the
    /// kernel (§ 4) and never sets this, so its behaviour is unchanged by
    /// construction rather than by care. Returns the **whole** pass — words
    /// written and jobs failed — so the messages below can count both
    /// routes without knowing which engine produced them, and so a
    /// provider that failed a recording cannot report it as a quiet zero.
    var externalProcessing: ((Gilune) -> ProviderPass)? = null

    /// What one processing pass did, both routes counted together — plus
    /// the two things the output count on its own cannot say.
    ///
    /// [providerFailure] is non-null exactly when this host's external
    /// provider threw. [providerFailed] counts the jobs it ran and failed
    /// **without** throwing — the ordinary case, since the provider loop
    /// catches a decode that goes wrong and calls `failJob` rather than
    /// letting the exception out. All three are different facts: `failed`
    /// is the kernel recording a job of its own that did not succeed,
    /// `providerFailed` is the external route recording the same thing,
    /// and `providerFailure` is a provider that never got as far as a job.
    ///
    /// The first round of this fix carried only the third, so the common
    /// failure — a model that loads and then cannot decode — still read as
    /// "nothing to do".
    data class ProcessingPass(
        val outputs: Long,
        val failed: Long,
        val providerFailed: Long = 0L,
        val providerFailure: String? = null,
    ) {
        /// Whether any job, on either route, ran and did not succeed.
        val anyFailed: Boolean get() = failed > 0L || providerFailed > 0L

        /// Everything a count of words cannot say, in the order a person
        /// needs it: what failed, then why nothing ran at all.
        fun trouble(): String =
            providerFailedSuffix(providerFailed) + providerFailureSuffix(providerFailure)
    }

    /// One processing pass: the kernel's own processors, then this host's
    /// external ones.
    ///
    /// **A provider that throws is reported, not swallowed.** Until this
    /// round the external half was wrapped in a bare `runCatching { }
    /// .getOrNull() ?: 0L`, so a Moonshine session that failed to load its
    /// model produced the same summary as a vault with nothing to
    /// transcribe — "0 transcribed", which reads as *nothing to do*. The
    /// callers below append [providerFailureSuffix] so the sentence says
    /// which of the two it was, and the detail goes to the platform log.
    internal fun processAll(g: Gilune): ProcessingPass {
        val summary = g.runProcessing()
        val hook = externalProcessing
            ?: return ProcessingPass(summary.outputs, summary.failed)
        val outcome = runCatching { hook(g) }
        val thrown = outcome.exceptionOrNull()
        if (thrown != null) {
            GiluneLog.warn("the external transcription provider failed", thrown)
        }
        val external = outcome.getOrNull() ?: ProviderPass()
        if (external.failed > 0L) {
            GiluneLog.warn("the external transcription provider failed ${external.failed} job(s)")
        }
        return ProcessingPass(
            outputs = summary.outputs + external.transcribed,
            failed = summary.failed,
            providerFailed = external.failed,
            providerFailure = thrown?.let { it.message?.takeIf(String::isNotBlank) ?: it.toString() },
        )
    }

    // ------------------------------------------------------------ sync

    /// This device's sync facts, or null until a shell has said where its
    /// device-local state lives. Read by the sync settings screen; every
    /// action below refreshes it.
    val syncStatus = mutableStateOf<uniffi.gilune_ffi.SyncStatus?>(null)

    /// Standing integrity conditions (ADR 0019 § 8).
    ///
    /// **The surface that makes halting visible.** The kernel has raised
    /// these since M5a and nothing could show them, which made a halted
    /// stream look exactly like an idle one — the failure mode § 8 calls
    /// "the one that would make sync look like it works". Refreshed
    /// alongside the facts, so anything that changes them shows them.
    val syncAlerts = mutableStateListOf<uniffi.gilune_ffi.IntegrityAlert>()

    /// Devices seen on the network by the last `discoverDevices`. Empty
    /// until somebody looks: browsing is a button and a background pass,
    /// never something a screen does on its own — a browse costs seconds
    /// and multicast traffic, and § 1 is explicit that discovery stays
    /// off the connect path whenever a cached address will do.
    val discovered = mutableStateListOf<uniffi.gilune_ffi.FoundDevice>()

    /// True while a browse is in flight, so a screen can say "looking…"
    /// rather than showing an empty list that means nothing yet.
    val discovering = mutableStateOf(false)

    /// True while a round of `syncDevices` is running, so the button that
    /// starts one can say so and refuse a second.
    val syncing = mutableStateOf(false)

    /// Everything the running round has said so far, in order — the
    /// kernel reports each stage with each peer the moment it begins
    /// (`SyncProgress`), and a screen draws the last of these as "what is
    /// happening now". Cleared when a round starts.
    val syncProgress = mutableStateListOf<uniffi.gilune_ffi.SyncProgressEvent>()

    /// The most recent finished round, for the screen that has the
    /// button. The foot-of-window message line is not where a person
    /// looks for why their notebook did not sync.
    val lastSyncRound = mutableStateOf<SyncRound?>(null)

    /// How this shell enrols the device (ADR 0019 § 4). The identity key
    /// lives **outside the vault** in a directory only the shell knows —
    /// `%LOCALAPPDATA%\Gilune` on Windows, `filesDir` on Android — and the
    /// platform's `KeyWrap` adapter is likewise the shell's to supply, so
    /// the seam is a hook rather than a shared constant. Set before
    /// `openVault`; the same shape as `externalProcessing` above.
    var syncSetup: ((Gilune) -> uniffi.gilune_ffi.SyncStatus)? = null

    /// Enrol on open, and say so if opening this vault here meant
    /// something. `enrolmentNotice` is non-null exactly when the vault
    /// turned out to have come from another device: the ADR asks the UI
    /// to say so plainly "rather than letting a user wonder why their
    /// laptop appears twice".
    /// Whether enrolment failed because this machine cannot open the
    /// identity it holds (ADR 0019 § 4) — the one sync failure a person can
    /// recover from, and so the one the screens offer a button for.
    ///
    /// **A typed fact, not a phrase.** Until this round the screens decided
    /// this by reading the message line for words like "could not unwrap",
    /// which made the wording of an error message load-bearing: rewording
    /// the kernel's sentence would have taken the recovery button off the
    /// screen silently, and any other failure whose text happened to match
    /// would have offered to throw away a working identity. `configureSync`
    /// raises its own error variant for exactly this, so the answer comes
    /// from the type.
    val identityUnreadable = mutableStateOf(false)

    private fun enrol(g: Gilune): String? {
        val setup = syncSetup ?: return null
        return try {
            val status = setup(g)
            identityUnreadable.value = false
            // Registering how this device finds others binds nothing —
            // the responder starts with the listener, at the switch, and
            // for the same reason: UDP 5353 is a second inbound bind, not
            // an escape from the first (§ 2). A discovery adapter that
            // will not register is not a reason to fail enrolment: cached
            // and typed addresses both work without one.
            runCatching { syncDiscovery?.invoke(g) }
            syncStatus.value = runCatching { g.syncStatus() }.getOrNull() ?: status
            status.enrolmentNotice
        } catch (e: Throwable) {
            identityUnreadable.value = e is uniffi.gilune_ffi.GiluneException.IdentityUnreadable
            "sync unavailable: ${e.message}"
        }
    }

    /// Come back reachable if the user left sync switched on — **behind
    /// the vault opening rather than in front of it**.
    ///
    /// **Not a weakening of § 2's lazy bind.** That rule is that nothing
    /// binds before the user first opts in, so a user who never turns sync
    /// on never meets the firewall prompt and capture is never behind it.
    /// Once they have opted in, coming back up reachable is the feature
    /// working. On a device that never opted in this is a no-op, so the
    /// property is kept by the kernel rather than by each shell
    /// remembering to ask.
    ///
    /// It moved off the open path because it is **slow by construction**:
    /// binding is quick, but advertising waits on a registration latch —
    /// up to four seconds on Android, and the desktop's responder is no
    /// promise either — and until this round every vault open paid that
    /// before the first note could be drawn. Nothing downstream needs it
    /// to have finished: the facts are re-read when it lands, and a shell
    /// that syncs on open watches `syncStatus` for the moment it does.
    ///
    /// Failing to bind is not a failure: a busy port leaves the choice
    /// recorded and the device simply not listening, which `SyncStatus`
    /// reports as `wanted` without `enabled`.
    private fun resumeSyncInBackground(g: Gilune) {
        if (syncSetup == null) return
        scope.launch(Dispatchers.IO) {
            runCatching { g.resumeSync() }
            refreshSync()
        }
    }

    /// How this shell finds other devices (ADR 0019 § 1). Desktop calls
    /// `useBundledDiscovery` and gets the Rust `mdns-sd` implementation;
    /// Android registers a Kotlin adapter over `NsdManager`, because the
    /// platform owns the multicast socket there. Set before `openVault`,
    /// on `syncSetup`'s idiom.
    ///
    /// A shell that sets nothing still syncs at cached and typed
    /// addresses — a supported configuration, not a broken one.
    var syncDiscovery: ((Gilune) -> Unit)? = null

    fun refreshSync() {
        val g = gilune.value ?: return
        syncStatus.value = runCatching { g.syncStatus() }.getOrNull() ?: syncStatus.value
        val standing = runCatching { g.integrityAlerts() }.getOrNull()
        if (standing != null) {
            syncAlerts.clear()
            syncAlerts.addAll(standing)
        }
    }

    /// Look for devices on this network. Blocking on the kernel's side,
    /// so it runs off the main thread like every other sync action.
    ///
    /// Deliberately explicit: nothing browses on its own. A cold browse
    /// measured 2.5 s against an exchange's 1.2 s on the development
    /// machine, which is exactly why § 1 keeps discovery off the connect
    /// path — a screen that browsed on every recomposition would spend
    /// the whole sync budget looking for devices it already knows.
    fun discoverDevices(timeoutMs: ULong = 2500u) {
        val g = gilune.value ?: return
        if (discovering.value) return
        discovering.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val found = g.discoverDevices(timeoutMs)
                discovered.clear()
                discovered.addAll(found)
                message.value = when {
                    found.isEmpty() ->
                        "no devices found on this network — many guest and office " +
                            "networks stop devices seeing each other"
                    found.size == 1 -> "found 1 device"
                    else -> "found ${found.size} devices"
                }
            } catch (e: Throwable) {
                message.value = "looking for devices failed: ${e.message}"
            } finally {
                discovering.value = false
            }
        }
    }

    /// Tell this device where a paired one can be reached — the manual
    /// step of § 1's chain, for a network where discovery is filtered and
    /// the address pairing recorded has gone stale.
    fun setPeerAddress(staticPub: String, address: String, called: String) {
        syncAction("$called can now be reached at $address") {
            it.setPeerAddress(staticPub, address)
        }
    }

    /// Welcome back an earlier identity a peer **claimed** — the action
    /// behind a `sync-previous-identity-claimed` notice, whose `subject` is
    /// the device id to pass here.
    ///
    /// Pairing deliberately does not do this on its own: the ancestry in a
    /// greeting is a sentence the peer wrote about itself, so believing it
    /// would let any device somebody is willing to pair with lift the
    /// refusal on any device they had removed. The person is the one who
    /// knows whether that really was their old machine, so it is a tap.
    fun readmitDevice(deviceId: String) {
        syncAction("that device's earlier history is welcome again") {
            it.readmitDevice(deviceId)
        }
    }

    /// Run a sync action and report it, refreshing the facts afterwards.
    /// `pluginAction`'s shape, and for the same reason: the IO dispatch,
    /// the refresh and the error wording come free and read the same.
    fun syncAction(describe: String, act: (Gilune) -> Unit) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                act(g)
                refreshSync()
                message.value = describe
            } catch (e: Throwable) {
                message.value = "$describe failed: ${e.message}"
            }
        }
    }

    /// Exchange operations with every paired device this one has an
    /// address for.
    ///
    /// Deliberately **not** called `sync`: `sync()` above has meant
    /// "reconcile the vault's files" since M2, and the two are different
    /// enough that sharing a name would be a bug waiting to be written.
    fun syncDevices() {
        val g = gilune.value ?: return
        // One round at a time: a second press while the first is mid-dial
        // would open a second session to every peer and report twice.
        if (syncing.value) return
        // A device with sync switched off dials nobody (§ 2) and the
        // kernel answers with an empty report rather than an error, which
        // read as "nothing to sync with" — true, and not the reason.
        if (syncStatus.value?.wanted == false) {
            message.value = "sync is switched off on this device — turn it on to exchange notes"
            return
        }
        syncing.value = true
        syncProgress.clear()
        message.value = "syncing with your devices…"
        // The kernel calls this on the thread that is running the round —
        // this coroutine's IO thread — and snapshot state may be written
        // from any thread, so each stage reaches the screen as it begins.
        val ear = object : uniffi.gilune_ffi.SyncProgress {
            override fun report(event: uniffi.gilune_ffi.SyncProgressEvent) {
                syncProgress.add(event)
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val report = g.syncNowReporting(ear)
                // Only when something arrived — an operation or the bytes
                // an earlier operation was waiting for. Projection walks
                // every object in the vault and rewrites what changed; an
                // exchange that received neither has given it nothing to
                // walk, and on the phone's fifteen-minute schedule that
                // was a full pass over the vault for no reason at all.
                // Sending does not project: what this device sent is
                // already on disk here.
                if (syncNeedsProjection(report.applied, report.blobsCompleted)) g.project()
                refresh()
                refreshSync()
                val round = SyncRound(
                    endedMs = System.currentTimeMillis(),
                    report = report,
                    peers = peerOutcomes(syncProgress.toList()),
                )
                lastSyncRound.value = round
                message.value = roundSummary(round)
            } catch (e: Throwable) {
                message.value = "device sync failed: ${e.message}"
            } finally {
                syncing.value = false
            }
        }
    }

    /// Pair with a device a search produced, at the address the search
    /// gave — the person supplies only the code that device is showing.
    /// Its own reporting rather than `syncAction`'s, because the one
    /// failure a person can fix without reading it (no code open over
    /// there) deserves to be named beside the kernel's reason.
    fun pairWithFound(found: uniffi.gilune_ffi.FoundDevice, code: String) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val peer = g.pairWithCode(found.address, code)
                refreshSync()
                message.value = "paired with ${peer.name.ifBlank { found.fingerprint }}"
            } catch (e: Throwable) {
                message.value = foundPairingFailure(found, e.message ?: "no reason given")
            }
        }
    }

    fun openVault(onOpened: (Gilune) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            try {
                val g = Gilune.open(vaultDir)
                // The mic is this shell's contribution, so this shell
                // declares it. Registering is idempotent and never
                // resurrects a plugin the user removed. Host policy: the
                // adapter's local recording capability is consented by
                // installing the app, granted once on first install —
                // never re-granted after a revoke or removal.
                val fresh = g.plugins().none { it.pluginId == VOICE_CAPTURE_PLUGIN }
                val registered = g.registerPlugin(voiceCaptureDeclaration())
                if (fresh && registered == "enabled") {
                    g.grantCapability(VOICE_CAPTURE_PLUGIN, "platform.audio-record")
                }
                // The composition root supplies domain declarations and their
                // implementations through the same registry on both platforms.
                BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(g)
                // What this process can derive about the vault, declared
                // straight after the plugins it belongs to and before the
                // first pass, so the projection below brings the index up
                // with it rather than leaving a pass to catch up
                // (ADR 0020 § 2.3).
                registerShellIndexers(g)
                // Enrolment happens on open (ADR 0019 § 4) and before
                // anything else reads the vault, because it is what
                // decides whether this machine is the device the vault
                // believes it belongs to. Nothing binds a socket here.
                val notice = enrol(g)
                val initializationProblems = BUNDLED_PLUGIN_EXTENSIONS.initializePages(g)
                g.project()
                gilune.value = g
                refresh()
                // A re-enrolment notice outranks the routine line: it is
                // the one thing about opening this vault that the user
                // has to be told.
                message.value = notice ?: initializationProblems.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: "vault: ${g.vaultPath()}"
                onOpened(g)
                // Last, and on a thread of its own: the vault is open and
                // usable before this begins.
                resumeSyncInBackground(g)
            } catch (e: Throwable) {
                message.value = "failed to open vault: ${e.message}"
            }
        }
    }

    /// A save this shell made, reaching the feed and the index before
    /// anything refreshes (ADR 0020 § 2.2, gate 7).
    ///
    /// The order is the whole of it: reconcile **that one file**, let the
    /// indexers run over it, and only then move the cursor. A consumer
    /// woken by the cursor therefore reads an index that already knows
    /// about the note that woke it — which is what "the agenda updates
    /// when you tick a box" turns out to require.
    ///
    /// `relPath` is the note that was written, or null for a save whose
    /// path the caller does not know; without one the file still reaches
    /// the feed, on the next full pass rather than now.
    fun noteSaved(relPath: String? = null) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) { applySave(g, relPath) }
    }

    /// [noteSaved] without the dispatch, for a caller that is already off
    /// the main thread and for tests that want the ordering to be a fact
    /// rather than a race.
    internal fun applySave(g: Gilune, relPath: String?) {
        if (relPath != null) {
            runCatching { g.reconcilePath(relPath) }
                .onFailure { GiluneLog.warn("could not reconcile $relPath after a save", it) }
        }
        // Belt and braces, and cheap: `reconcile_path` advances the index
        // itself, and a save with no path has nothing to reconcile but may
        // still have left the feed something from elsewhere.
        runCatching { g.advanceIndex() }
        advanceCursor(g)
    }

    fun refresh() {
        val g = gilune.value ?: return
        val sources = g.recentSources(100u)
        recent.clear()
        recent.addAll(sources)
        val installed = g.plugins()
        plugins.clear()
        plugins.addAll(installed)
        val open = g.openProposals()
        proposals.clear()
        proposals.addAll(open)
        advanceCursor(g)
        refreshCount.value += 1
    }

    /// How many times this state has re-read the kernel's own facts —
    /// plugins, proposals, counts. Deliberately **not** the cursor: a
    /// plugin switched off or a job finishing changes what Settings
    /// should draw and changes no file in the vault, and a screen that
    /// watched the cursor for it would not refresh at all.
    val refreshCount = mutableStateOf(0)

    /// Reconcile external edits, run local processing, canonicalise,
    /// refresh. Reconcile precedes projection deliberately: the reverse
    /// order re-creates a moved note at its old path (ADR 0012).
    fun sync(announce: Boolean = true) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val r = g.reconcile()
                val p = processAll(g)
                g.project()
                refresh()
                if (announce) {
                    message.value =
                        "synced: ${r.edits} edits, ${r.imports} imports, " +
                            "${p.outputs} transcribed, ${r.tombstoneProposals} proposals" +
                            p.trouble()
                }
            } catch (e: Throwable) {
                message.value = "sync failed: ${e.message}"
            }
        }
    }

    fun captureText(text: String) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                g.captureText(text)
                g.project()
                refresh()
                message.value = "captured"
            } catch (e: Throwable) {
                message.value = "capture failed: ${e.message}"
            }
        }
    }

    /// The writer is closed once it has finished: it holds a reference to
    /// the vault's handle, and an unclosed one kept the vault's database
    /// open until the garbage collector got round to it.
    private fun writeChunked(writer: uniffi.gilune_ffi.CaptureWriter, wav: ByteArray): uniffi.gilune_ffi.CaptureReceipt =
        writer.use {
            var offset = 0
            while (offset < wav.size) {
                val end = minOf(offset + CAPTURE_CHUNK, wav.size)
                it.writeChunk(wav.copyOfRange(offset, end))
                offset = end
            }
            it.finish()
        }

    /// Speech becomes a note of its own: the audio commits durably, the
    /// transcript becomes the note's body (ADR 0013), and the note opens.
    fun captureAudio(audio: RecordedAudio, onOpenNote: ((String) -> Unit)? = null) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val wav = wavBytes(audio)
                val receipt = writeChunked(g.beginCapture("wav"), wav)
                message.value = "voice captured (${wav.size / 1024} KiB); transcribing…"
                val p = processAll(g)
                g.project()
                refresh()
                message.value = when {
                    p.outputs > 0 -> "spoken note ready"
                    // Either route's failure, because a person who has just
                    // spoken cares that the words are not coming yet, not
                    // which engine was asked for them.
                    p.anyFailed ->
                        "voice captured; transcription failed (will retry)" +
                            providerFailureSuffix(p.providerFailure)
                    // Before the suffix existed this said plain "voice
                    // captured", which is true and reads as complete — the
                    // words were never coming.
                    else -> "voice captured" + p.trouble()
                }
                if (onOpenNote != null) {
                    recent.firstOrNull { it.objectId == receipt.sourceId }
                        ?.projectionRelPath
                        ?.let(onOpenNote)
                }
            } catch (e: Throwable) {
                message.value = "voice capture failed: ${e.message}"
            }
        }
    }

    /// Dictation into an open note: the audio commits as an attached
    /// capture — durable, transcribed in the kernel, but never a note of
    /// its own — and the words come back for the caller to place at the
    /// caret. A crash before insertion loses nothing: the audio and its
    /// transcript stay in the kernel (ADR 0013).
    ///
    /// With no `onText` the words were already placed as they were spoken
    /// (ADR 0027): the recording is still kept and transcribed, so the
    /// kernel holds the source and its interpretation, but nothing more is
    /// inserted — the same words twice would be worse than none.
    fun dictate(audio: RecordedAudio, onText: ((String) -> Unit)?) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val wav = wavBytes(audio)
                val receipt = writeChunked(g.beginAttachedCapture("wav"), wav)
                if (onText == null) {
                    processAll(g)
                    refresh()
                    return@launch
                }
                message.value = "transcribing…"
                val p = processAll(g)
                val text = g.objectText(receipt.sourceId)
                refresh()
                if (!text.isNullOrBlank()) {
                    message.value = "dictated"
                    onText(text)
                } else if (p.anyFailed) {
                    message.value = "dictation saved; transcription failed (will retry)" +
                        providerFailureSuffix(p.providerFailure)
                } else {
                    message.value = "dictation saved; no words recognised" +
                        p.trouble()
                }
            } catch (e: Throwable) {
                message.value = "dictation failed: ${e.message}"
            }
        }
    }

    /// The tasks plugin's global filter (ADR 0015 § 1), read from the
    /// plugin list so it is live: `pluginAction` refreshes that list
    /// after a settings write, the read below is a snapshot read, and a
    /// composition that asked for the filter recomposes on the next
    /// recognise/menu pass. Nothing is rewritten and nothing restarts.
    fun tasksFilter(): String = plugins.tasksFilter()

    /// Run a plugin action and report it. Every plugin change refreshes
    /// the contribution lists, so controls appear and disappear with it.
    fun pluginAction(describe: String, act: (Gilune) -> Unit) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                act(g)
                val initializationProblems = BUNDLED_PLUGIN_EXTENSIONS.initializePages(g)
                refresh()
                message.value = initializationProblems.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: describe
            } catch (e: Throwable) {
                message.value = "$describe failed: ${e.message}"
            }
        }
    }

    /// Process captures no plugin ever saw — ones made before a plugin
    /// was installed or while it was off.
    ///
    /// **The queue is blanket; the writing is scoped** (ADR 0018 § 3).
    /// `backfillJobs` queues one job per enabled subscription per past
    /// event, which under a *changed* provider is every capture in the
    /// vault — and a blanket re-decode would rewrite every note body with
    /// the new engine's words. What stops it is the provider itself: a
    /// claimed job whose subject already has words is completed with **no
    /// output**, which is the shipped "nothing to do" outcome a Rust
    /// processor gets for a capture it has nothing to say about. So the
    /// only notes that change are the ones that had no words, which is the
    /// scope § 3 asks for, and the count the caller shows first is
    /// `wordlessRecordings`.
    fun backfill() {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val queued = g.backfillJobs()
                val p = processAll(g)
                g.project()
                refresh()
                // Both numbers, because under a provider that scopes its
                // own writing they are deliberately different: every past
                // capture may be queued while only the wordless ones gain
                // words.
                message.value =
                    if (queued == 0L && p.outputs == 0L) {
                        "nothing was waiting" + p.trouble()
                    } else {
                        "caught up $queued capture(s): ${p.outputs} transcribed" +
                            p.trouble()
                    }
            } catch (e: Throwable) {
                message.value = "catch-up failed: ${e.message}"
            }
        }
    }
}
