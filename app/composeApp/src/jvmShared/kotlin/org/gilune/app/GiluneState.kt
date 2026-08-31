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
                            "${p.outputs} transcripts, ${r.tombstoneProposals} proposals"
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

    fun captureAudio(audio: RecordedAudio) {
        val g = gilune.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val wav = wavBytes(audio)
                val writer = g.beginCapture("wav")
                var offset = 0
                while (offset < wav.size) {
                    val end = minOf(offset + CAPTURE_CHUNK, wav.size)
                    writer.writeChunk(wav.copyOfRange(offset, end))
                    offset = end
                }
                writer.finish()
                message.value = "voice captured (${wav.size / 1024} KiB); transcribing…"
                val p = g.runProcessing()
                g.project()
                refresh()
                message.value =
                    if (p.outputs > 0) "voice captured and transcribed"
                    else if (p.failed > 0) "voice captured; transcription failed (will retry)"
                    else "voice captured"
            } catch (e: Throwable) {
                message.value = "voice capture failed: ${e.message}"
            }
        }
    }

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
