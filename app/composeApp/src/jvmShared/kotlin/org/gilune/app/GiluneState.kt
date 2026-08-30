// Shared UI state over the boundary: one open vault, its capture feed,
// and the sync/processing cycle. Composables render this; every decision
// stays across the boundary (conventions § Kotlin).

package org.gilune.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.SourceSummary

class GiluneState(val vaultDir: String, private val scope: CoroutineScope) {
    val gilune = mutableStateOf<Gilune?>(null)
    val message = mutableStateOf("opening vault…")
    val recent = mutableStateListOf<SourceSummary>()

    /// Bumped whenever vault contents may have changed; file panels watch it.
    val vaultTick = mutableStateOf(0)

    fun openVault(onOpened: (Gilune) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            try {
                val g = Gilune.open(vaultDir)
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
        vaultTick.value += 1
    }

    /// Reconcile external edits, run local processing, canonicalise, refresh.
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
}
