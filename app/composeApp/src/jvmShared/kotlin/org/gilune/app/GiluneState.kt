// Shared UI state over the boundary: one open vault, its capture feed,
// the installed plugins, and the sync/processing cycle. Composables
// render this; every decision stays across the boundary (conventions
// § Kotlin).

package org.gilune.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.SourceSummary
import uniffi.gilune_ffi.TombstoneProposal

/// How much of a recording crosses the boundary at a time. Streamed
/// capture is chunk-by-chunk so a process kill loses only the tail.
const val CAPTURE_CHUNK = 64 * 1024

class GiluneState(val vaultDir: String, private val scope: CoroutineScope) {
    val gilune = mutableStateOf<Gilune?>(null)
    val message = mutableStateOf("opening vault…")
    val recent = mutableStateListOf<SourceSummary>()
    val plugins = mutableStateListOf<PluginInfo>()

    /// Deletions made outside the app that await a keep-or-delete
    /// decision. In-app deletions answer their own proposal, so anything
    /// here came from another tool — supported use (rule 13).
    val proposals = mutableStateListOf<TombstoneProposal>()

    /// Bumped whenever vault contents may have changed; file panels watch it.
    val vaultTick = mutableStateOf(0)

    /// Processors this host implements **outside** the kernel (ADR 0018
    /// § 2). `runProcessing` deliberately leaves their jobs alone — the
    /// registered host claims them itself — so wherever this state runs
    /// the kernel's loop it must run theirs too, or an external provider's
    /// words would arrive only when something else happened to ask.
    ///
    /// Null everywhere but the Android shell, where a Moonshine provider
    /// is registered on this handle. The desktop keeps whisper in the
    /// kernel (§ 4) and never sets this, so its behaviour is unchanged by
    /// construction rather than by care. Returns how many outputs the
    /// external pass wrote, so the messages below can count words from
    /// both routes without knowing which engine produced them.
    var externalProcessing: ((Gilune) -> Long)? = null

    /// One processing pass: the kernel's own processors, then this host's
    /// external ones. Returns the outputs both wrote.
    private fun processAll(g: Gilune): uniffi.gilune_ffi.ProcessingSummary {
        val summary = g.runProcessing()
        val external = runCatching { externalProcessing?.invoke(g) }.getOrNull() ?: 0L
        return summary.copy(outputs = summary.outputs + external)
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
    private fun enrol(g: Gilune): String? {
        val setup = syncSetup ?: return null
        return try {
            val status = setup(g)
            // Registering how this device finds others binds nothing —
            // the responder starts with the listener, at the switch, and
            // for the same reason: UDP 5353 is a second inbound bind, not
            // an escape from the first (§ 2). A discovery adapter that
            // will not register is not a reason to fail enrolment: cached
            // and typed addresses both work without one.
            runCatching { syncDiscovery?.invoke(g) }
            // Come back reachable if the user left sync switched on.
            //
            // **Not a weakening of § 2's lazy bind.** That rule is that
            // nothing binds before the user first opts in, so a user who
            // never turns sync on never meets the firewall prompt and
            // capture is never behind it. Once they have opted in, coming
            // back up reachable is the feature working — the prompt
            // happened at the first enable, and a device that forgot it
            // was reachable on every launch could never be synced with
            // unprompted. On a device that never opted in this is a
            // no-op, so the property is kept by the kernel rather than by
            // each shell remembering to ask.
            //
            // After discovery is registered, so the responder comes up
            // with the listener rather than a beat later. Failing to bind
            // is not a reason to fail enrolment: a busy port leaves the
            // choice recorded and the device simply not listening, which
            // `SyncStatus` reports as `wanted` without `enabled`.
            runCatching { g.resumeSync() }
            syncStatus.value = runCatching { g.syncStatus() }.getOrNull() ?: status
            status.enrolmentNotice
        } catch (e: Throwable) {
            "sync unavailable: ${e.message}"
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
        scope.launch(Dispatchers.IO) {
            try {
                val report = g.syncNow()
                g.project()
                refresh()
                refreshSync()
                message.value = when {
                    report.halted.isNotEmpty() ->
                        "device sync stopped: ${report.halted.first()}"
                    report.applied == 0L && report.sent == 0L -> "devices are already up to date"
                    else ->
                        "device sync: ${report.applied} received, ${report.sent} sent" +
                            if (report.incomplete) " (more to come)" else ""
                }
            } catch (e: Throwable) {
                message.value = "device sync failed: ${e.message}"
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
                // Tasks is this shell's too (ADR 0015 § 7): no processor,
                // no events and no capabilities, so there is nothing to
                // grant — consent is the enable switch. Registered on the
                // same idempotent path, so enable, disable, remove and
                // tombstone all apply with no new machinery, and a plugin
                // the user removed is never resurrected.
                g.registerPlugin(tasksDeclaration())
                // Enrolment happens on open (ADR 0019 § 4) and before
                // anything else reads the vault, because it is what
                // decides whether this machine is the device the vault
                // believes it belongs to. Nothing binds a socket here.
                val notice = enrol(g)
                g.project()
                gilune.value = g
                refresh()
                // A re-enrolment notice outranks the routine line: it is
                // the one thing about opening this vault that the user
                // has to be told.
                message.value = notice ?: "vault: ${g.vaultPath()}"
                onOpened(g)
            } catch (e: Throwable) {
                message.value = "failed to open vault: ${e.message}"
            }
        }
    }

    /// The editor's own writes announce themselves: the agenda panel,
    /// the search panel and every filter block read the vault off
    /// `vaultTick`, and a save nobody hears about leaves them showing a
    /// task that is already done (owner report, 2026-08-31). Kernel
    /// ingestion still waits for sync — this is a display fact, not a
    /// history one.
    fun noteSaved() {
        vaultTick.value += 1
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
        vaultTick.value += 1
    }

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
                            "${p.outputs} transcribed, ${r.tombstoneProposals} proposals"
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

    private fun writeChunked(writer: uniffi.gilune_ffi.CaptureWriter, wav: ByteArray): uniffi.gilune_ffi.CaptureReceipt {
        var offset = 0
        while (offset < wav.size) {
            val end = minOf(offset + CAPTURE_CHUNK, wav.size)
            writer.writeChunk(wav.copyOfRange(offset, end))
            offset = end
        }
        return writer.finish()
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
                message.value =
                    if (p.outputs > 0) "spoken note ready"
                    else if (p.failed > 0) "voice captured; transcription failed (will retry)"
                    else "voice captured"
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
    fun dictate(audio: RecordedAudio, onText: (String) -> Unit) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val wav = wavBytes(audio)
                val receipt = writeChunked(g.beginAttachedCapture("wav"), wav)
                message.value = "transcribing…"
                val p = processAll(g)
                val text = g.objectText(receipt.sourceId)
                refresh()
                if (!text.isNullOrBlank()) {
                    message.value = "dictated"
                    onText(text)
                } else if (p.failed > 0) {
                    message.value = "dictation saved; transcription failed (will retry)"
                } else {
                    message.value = "dictation saved; no words recognised"
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
                refresh()
                message.value = describe
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
                    if (queued == 0L && p.outputs == 0L) "nothing was waiting"
                    else "caught up $queued capture(s): ${p.outputs} transcribed"
            } catch (e: Throwable) {
                message.value = "catch-up failed: ${e.message}"
            }
        }
    }
}
