// Speaker labels on the desktop (ADR 0030): the speaker models, the engine
// that finds who spoke when, and the labeller both transcribers ask. The
// matching of words to voices is shared (`SpeakerLabels.kt`).
//
// The NeMo plugin's rules, because it is the same library: nothing
// downloads without the plugin's `network.request` grant, every file is
// checked against a SHA-256 before it is used, the engine loads on first
// use and is dropped after ten idle minutes, and a recording never waits.

package org.gilune.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.SpeakerLabeller
import uniffi.gilune_ffi.TimedWord
import java.io.File
import java.io.InputStream

/// One file the speaker models need. sherpa-onnx's releases publish no
/// checksums for these two, so the SHA-256 is the one taken when this was
/// written (2026-10-30) and pinned here: a changed file is refused.
data class SpeakerModelFile(
    val name: String,
    val url: String,
    val fileName: String,
    val sha256: String,
    val bytes: Long,
    val licence: String,
)

private const val SPEAKER_RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

/// Where speech starts and stops, and where voices overlap.
val SPEAKER_SEGMENTATION = SpeakerModelFile(
    name = "pyannote segmentation 3.0",
    url = "$SPEAKER_RELEASES/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
    fileName = "sherpa-onnx-pyannote-segmentation-3-0",
    sha256 = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488",
    bytes = 6_958_444,
    licence = "MIT",
)

/// What each voice sounds like. Chosen against two other candidates on a
/// two-voice recording (ADR 0030): this one and a larger one split all
/// eight turns correctly; the third merged them wrongly.
val SPEAKER_EMBEDDING = SpeakerModelFile(
    name = "WeSpeaker ResNet34 (English)",
    url = "$SPEAKER_RELEASES/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx",
    fileName = "wespeaker_en_voxceleb_resnet34_LM.onnx",
    sha256 = "e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012",
    bytes = 26_530_550,
    licence = "CC-BY-4.0",
)

/// The two downloads together, as a person is told.
const val SPEAKER_MODELS_SIZE = "34 MB"

fun speakerModelsRoot(): File = File(localGiluneDir("models"), "speakers")

fun speakerModelsPresent(root: File = speakerModelsRoot()): Boolean =
    File(File(root, SPEAKER_SEGMENTATION.fileName), READY_MARKER).isFile &&
        File(root, SPEAKER_EMBEDDING.fileName).isFile

/// Fetch and verify both models into `root`. The embedding model is
/// written to a `.part` file and renamed once verified, so a stopped
/// download never reads as a model; the segmentation archive unpacks the
/// way a NeMo model does.
fun installSpeakerModels(
    root: File = speakerModelsRoot(),
    onProgress: (Long) -> Unit = {},
    open: (String) -> InputStream = { java.net.URI(it).toURL().openStream() },
) {
    root.mkdirs()
    val segmentation = SPEAKER_SEGMENTATION
    if (!File(File(root, segmentation.fileName), READY_MARKER).isFile) {
        val archive = File(root, "${segmentation.fileName}.tar.bz2.part")
        try {
            fetchVerified(segmentation.url, segmentation.name, segmentation.bytes, segmentation.sha256, archive, onProgress, open)
            unpack(archive, root, segmentation.fileName)
        } finally {
            archive.delete()
        }
    }
    val embedding = SPEAKER_EMBEDDING
    val target = File(root, embedding.fileName)
    if (!target.isFile) {
        val partial = File(root, "${embedding.fileName}.part")
        try {
            fetchVerified(embedding.url, embedding.name, embedding.bytes, embedding.sha256, partial, {
                onProgress(segmentation.bytes + it)
            }, open)
            check(partial.renameTo(target)) { "could not move the ${embedding.name} model into place" }
        } finally {
            partial.delete()
        }
    }
}

/// Recordings shorter than this are not split: there is too little of any
/// voice to tell it from another.
private const val SHORTEST_LABELLED_MS = 2_000

/// The loaded speaker models, opened on first use and dropped after ten
/// idle minutes. Every call holds this object's lock, on NeMo's reading of
/// the native library.
class SpeakerEngine(private val dir: File, private val idleMs: Long = 10 * 60 * 1000L) : AutoCloseable {
    private var diarization: OfflineSpeakerDiarization? = null
    private var loadedCount: Int? = null
    private var lastUsed = System.currentTimeMillis()
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    @Volatile
    private var closed = false
    private val sweeper = Thread {
        while (!closed) {
            Thread.sleep(60_000)
            synchronized(this) {
                if (System.currentTimeMillis() - lastUsed >= idleMs) release()
            }
        }
    }.apply { isDaemon = true; start() }

    private fun config(count: Int?): OfflineSpeakerDiarizationConfig {
        val segmentation = File(File(dir, SPEAKER_SEGMENTATION.fileName), "model.onnx")
        return OfflineSpeakerDiarizationConfig.builder()
            .setSegmentation(
                OfflineSpeakerSegmentationModelConfig.builder()
                    .setPyannote(OfflineSpeakerSegmentationPyannoteModelConfig.builder().setModel(segmentation.path).build())
                    .setNumThreads(threads)
                    .build(),
            )
            .setEmbedding(
                SpeakerEmbeddingExtractorConfig.builder()
                    .setModel(File(dir, SPEAKER_EMBEDDING.fileName).path)
                    .setNumThreads(threads)
                    .build(),
            )
            // -1 asks the model to decide, by how alike the voices are.
            .setClustering(FastClusteringConfig.builder().setNumClusters(count ?: -1).setThreshold(0.5f).build())
            .setMinDurationOn(0.3f)
            .setMinDurationOff(0.5f)
            .build()
    }

    /// Who spoke when in 16 kHz mono samples, `count` voices or as many as
    /// the model hears.
    fun turns(samples: FloatArray, count: Int?): List<SpeakerTurn> = synchronized(this) {
        lastUsed = System.currentTimeMillis()
        val engine = diarization ?: OfflineSpeakerDiarization(config(count)).also {
            diarization = it
            loadedCount = count
        }
        if (loadedCount != count) {
            engine.setConfig(config(count))
            loadedCount = count
        }
        engine.process(samples).map { SpeakerTurn((it.start * 1000).toInt(), (it.end * 1000).toInt(), it.speaker) }
    }

    private fun release() {
        diarization?.release()
        diarization = null
    }

    override fun close() = synchronized(this) {
        closed = true
        release()
    }
}

/// The labeller whisper asks across the kernel boundary, and NeMo asks
/// directly. Never throws: a failure is logged and the plain transcript
/// stands.
class DesktopSpeakerLabeller(
    private val engine: SpeakerEngine,
    private val count: () -> Int?,
) : SpeakerLabeller {
    override fun label(wav: ByteArray, words: List<TimedWord>): String? =
        if (wav.size < 44) null else labelPcm(wav.copyOfRange(44, wav.size), words)

    /// The same for 16 kHz mono 16-bit PCM with no header.
    fun labelPcm(pcm: ByteArray, words: List<TimedWord>): String? = runCatching {
        if (words.size < 2 || pcm.size / 32 < SHORTEST_LABELLED_MS) return null
        labelSpeakers(words, engine.turns(pcmToFloats(pcm), count()))
    }.getOrElse {
        GiluneLog.warn("speaker labels skipped", it)
        null
    }
}

/// Where speaker labels stand on this machine, for the settings screen.
sealed interface SpeakerPhase {
    data object Off : SpeakerPhase
    data object NeedsConsent : SpeakerPhase
    data class Downloading(val percent: Int) : SpeakerPhase
    data object Ready : SpeakerPhase
    data class Failed(val why: String) : SpeakerPhase
}

/// The speaker engine this window has loaded, if speaker labels are on.
/// Held outside composition, like the NeMo engines: a whisper job, a NeMo
/// job and the settings screen all reach the same one.
class SpeakerHost {
    var phase: SpeakerPhase by mutableStateOf(SpeakerPhase.Off)

    @Volatile
    var labeller: DesktopSpeakerLabeller? = null
        private set
    private var engine: SpeakerEngine? = null

    @Synchronized
    fun replace(next: SpeakerEngine?, count: () -> Int?) {
        if (next === engine) return
        runCatching { engine?.close() }
        engine = next
        labeller = next?.let { DesktopSpeakerLabeller(it, count) }
    }

    /// A NeMo engine's transcribe, with speakers named, while labels are
    /// ready; null otherwise, so NeMo transcribes exactly as before.
    fun labelling(engines: NemoEngines): ((ByteArray) -> String)? {
        val labeller = labeller ?: return null
        return { pcm ->
            val (plain, words) = engines.transcribeTimed(pcm)
            labeller.labelPcm(pcm, words) ?: plain
        }
    }
}

val LocalSpeakers = staticCompositionLocalOf { SpeakerHost() }

/// Install the plugin on this handle: switched off on a vault that has
/// never had it (it is opt-in, and its models are a download), its local
/// capability granted only when newly declared.
fun registerSpeakersOnHandle(gilune: Gilune) {
    runCatching {
        val declaration = speakersDeclaration()
        val installed = gilune.plugins().firstOrNull { it.pluginId == SPEAKERS_PLUGIN }
        val newly = newlyDeclaredLocalCapabilities(installed, declaration.capabilities)
        val state = if (installed == null) gilune.registerPluginDisabled(declaration) else gilune.registerPlugin(declaration)
        if (state != "removed") {
            for (capability in newly) runCatching { gilune.grantCapability(SPEAKERS_PLUGIN, capability) }
        }
    }.onFailure { GiluneLog.warn("the speaker labels plugin could not register", it) }
}

/// Bring speaker labels up, or down, to match the plugin: off clears the
/// labeller on this handle; on downloads the models once the person has
/// allowed it, then sets the labeller whisper asks. Run off the UI thread
/// whenever the plugin's state, grant or settings change.
fun startSpeakerLabels(state: GiluneState, gilune: Gilune, host: SpeakerHost, root: File = speakerModelsRoot()) {
    val plugins = runCatching { gilune.plugins() }.getOrElse {
        host.phase = SpeakerPhase.Failed(it.message ?: "plugins unreadable")
        return
    }
    val plugin = plugins.firstOrNull { it.pluginId == SPEAKERS_PLUGIN }
    if (plugin == null || plugin.state != "enabled") {
        gilune.setSpeakerLabeller(null)
        host.replace(null) { null }
        host.phase = SpeakerPhase.Off
        return
    }
    val downloading = !speakerModelsPresent(root)
    if (downloading) {
        if (NETWORK_CAPABILITY !in plugin.granted) {
            gilune.setSpeakerLabeller(null)
            host.phase = SpeakerPhase.NeedsConsent
            state.message.value = "speaker labels need permission to download their models " +
                "($SPEAKER_MODELS_SIZE) — Settings ▸ Plugins ▸ Speaker labels"
            return
        }
        host.phase = SpeakerPhase.Downloading(0)
        state.message.value = "downloading the speaker models ($SPEAKER_MODELS_SIZE, one time)…"
        val total = SPEAKER_SEGMENTATION.bytes + SPEAKER_EMBEDDING.bytes
        val fetched = runCatching {
            installSpeakerModels(root, onProgress = { done -> host.phase = SpeakerPhase.Downloading((done * 100 / total).toInt()) })
        }
        if (fetched.isFailure) {
            val why = fetched.exceptionOrNull()?.message ?: "download failed"
            GiluneLog.warn("could not download the speaker models", fetched.exceptionOrNull())
            host.phase = SpeakerPhase.Failed(why)
            state.message.value = "could not download the speaker models ($why); transcripts stay unlabelled"
            return
        }
    }
    if (host.labeller == null) host.replace(SpeakerEngine(root)) { state.plugins.speakerCount() }
    gilune.setSpeakerLabeller(host.labeller)
    host.phase = SpeakerPhase.Ready
    // The download said so in the status line; so does its end.
    if (downloading) state.message.value = "speaker labels ready"
}
