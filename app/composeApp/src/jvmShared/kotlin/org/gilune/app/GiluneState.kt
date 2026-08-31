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
                g.project()
                gilune.value = g
                refresh()
                message.value = "vault: ${g.vaultPath()}"
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
                val p = g.runProcessing()
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
                val p = g.runProcessing()
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
                val p = g.runProcessing()
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
    fun backfill() {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val queued = g.backfillJobs()
                val p = g.runProcessing()
                g.project()
                refresh()
                message.value =
                    if (queued == 0L) "nothing was waiting"
                    else "caught up $queued capture(s): ${p.outputs} new"
            } catch (e: Throwable) {
                message.value = "catch-up failed: ${e.message}"
            }
        }
    }
}
