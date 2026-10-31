// Transcription (NVIDIA NeMo) on the desktop — ADR 0027 S3.
//
// NVIDIA's speech models run app-side through sherpa-onnx's JVM library.
// They do the two jobs a transcriber has: words while someone dictates (a
// live session the editor drives exactly as it drives whisper's), and the
// committed transcript of every recording, claimed and submitted through
// the kernel's external processor route as Moonshine's are on the phone.
//
// Three rules, each the shipped transcriber's:
//  - Nothing downloads without the plugin's `network.request` grant, and
//    every archive is checked against the SHA-256 its release publishes
//    before a byte of it is unpacked.
//  - The models are big (~0.6 GB resident for Nemotron), so they load on
//    first use and are dropped after ten idle minutes (memory round).
//  - A recording never waits on any of this.

package org.gilune.app

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.gilune.plugin.sdk.jsonBoolean
import uniffi.gilune_ffi.ClaimedJob
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.LiveLine
import uniffi.gilune_ffi.LiveTranscriptionInterface
import uniffi.gilune_ffi.OutputTarget
import uniffi.gilune_ffi.ProposedOutput
import uniffi.gilune_ffi.TimedWord
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/// Where a model lives once unpacked: one folder per archive under the
/// machine's models folder, beside whisper's files.
fun nemoModelsRoot(): File = File(localGiluneDir("models"), "nemo")

internal const val READY_MARKER = ".ready"

/// Whether a model is unpacked and complete. The marker is written last, so
/// a download or an unpack that stopped part-way never reads as a model.
fun nemoModelPresent(model: NemoModel, root: File = nemoModelsRoot()): Boolean =
    File(File(root, model.archive), READY_MARKER).isFile

/// Fetch, verify and unpack one model into `root`. `open` is the network,
/// so a test can stand a local file in for it. The archive streams to a
/// `.part` file through a digest; a size or digest that differs from what
/// the release publishes deletes it and throws. Unpacking goes to a
/// folder of its own and is renamed into place once complete.
fun installNemoModel(
    model: NemoModel,
    root: File = nemoModelsRoot(),
    onProgress: (Long) -> Unit = {},
    open: (String) -> InputStream = { java.net.URI(it).toURL().openStream() },
) {
    root.mkdirs()
    val archive = File(root, "${model.archive}.tar.bz2.part")
    try {
        fetchVerified(model.url, model.name, model.bytes, model.sha256, archive, onProgress, open)
        unpack(archive, root, model.archive)
    } finally {
        archive.delete()
    }
}

/// Stream `url` into `into` through a digest. A size or SHA-256 that
/// differs from the one expected throws, and the caller deletes the file.
internal fun fetchVerified(
    url: String,
    name: String,
    bytes: Long,
    sha256: String,
    into: File,
    onProgress: (Long) -> Unit,
    open: (String) -> InputStream,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    var done = 0L
    DigestInputStream(open(url), digest).use { input ->
        into.outputStream().use { output ->
            val buffer = ByteArray(1 shl 16)
            var announced = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                done += read
                if (done - announced >= 4_000_000) {
                    announced = done
                    onProgress(done)
                }
            }
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    check(done == bytes) { "the $name download was $done bytes, not $bytes" }
    check(actual == sha256) { "the $name download does not match its published checksum" }
}

/// Unpack `<folder>/...` entries into `root/<folder>`, refusing anything
/// that would land outside it.
internal fun unpack(archive: File, root: File, folder: String) {
    val staging = File(root, "$folder.unpacking")
    staging.deleteRecursively()
    staging.mkdirs()
    val base = staging.canonicalFile
    TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            val inside = entry.name.substringAfter('/', "")
            if (inside.isEmpty() || entry.isDirectory) continue
            val target = File(staging, inside).canonicalFile
            check(target.path.startsWith(base.path + File.separator)) { "archive entry ${entry.name} escapes its folder" }
            target.parentFile.mkdirs()
            target.outputStream().use { tar.copyTo(it) }
        }
    }
    File(staging, READY_MARKER).writeText("")
    val final = File(root, folder)
    final.deleteRecursively()
    check(staging.renameTo(final)) { "could not move the unpacked model into place" }
}

private fun File.pick(part: String): String =
    listFiles().orEmpty()
        .filter { it.name.endsWith(".onnx") && part in it.name }
        .sortedByDescending { "int8" in it.name }
        .firstOrNull()?.path ?: error("the model folder $name has no $part")

/// The kernel's source type, as `whisper.rs` and the phone's provider spell it.
private const val SOURCE_TYPE = "org.memory.source"

internal fun pcmToFloats(bytes: ByteArray, from: Int = 0): FloatArray {
    val count = (bytes.size - from) / 2
    return FloatArray(count) { i ->
        val lo = bytes[from + 2 * i].toInt() and 0xff
        val hi = bytes[from + 2 * i + 1].toInt()
        ((hi shl 8) or lo).toShort() / 32768f
    }
}

/// The loaded recognisers for one model, opened on first use and dropped
/// after ten idle minutes. Every call into sherpa-onnx holds this object's
/// lock: a live dictation and a saved transcript may want the same model
/// at once, and one at a time is the safe reading of a native library.
class NemoEngines(
    val model: NemoModel,
    private val dir: File,
    private val idleMs: Long = 10 * 60 * 1000L,
) : AutoCloseable {
    private var online: OnlineRecognizer? = null
    private var offline: OfflineRecognizer? = null
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

    private val tokens get() = File(dir, "tokens.txt").path

    /// Run `block` with the recogniser loaded, holding the lock.
    fun <T> withOnline(block: (OnlineRecognizer) -> T): T = synchronized(this) {
        lastUsed = System.currentTimeMillis()
        val recognizer = online ?: OnlineRecognizer(
            OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(
                    OnlineModelConfig.builder()
                        .setTransducer(
                            OnlineTransducerModelConfig.builder()
                                .setEncoder(dir.pick("encoder"))
                                .setDecoder(dir.pick("decoder"))
                                .setJoiner(dir.pick("joiner"))
                                .build(),
                        )
                        .setTokens(tokens)
                        .setNumThreads(threads)
                        .build(),
                )
                .setEnableEndpoint(true)
                .setDecodingMethod("greedy_search")
                .build(),
        ).also { online = it }
        block(recognizer)
    }

    fun <T> withOffline(block: (OfflineRecognizer) -> T): T = synchronized(this) {
        lastUsed = System.currentTimeMillis()
        val recognizer = offline ?: OfflineRecognizer(
            OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(
                    OfflineModelConfig.builder()
                        .setTransducer(
                            OfflineTransducerModelConfig.builder()
                                .setEncoder(dir.pick("encoder"))
                                .setDecoder(dir.pick("decoder"))
                                .setJoiner(dir.pick("joiner"))
                                .build(),
                        )
                        .setModelType("nemo_transducer")
                        .setTokens(tokens)
                        .setNumThreads(threads)
                        .build(),
                )
                .build(),
        ).also { offline = it }
        block(recognizer)
    }

    /// A live session, or null for a model that only hears whole recordings.
    fun liveSession(): LiveTranscriptionInterface? =
        if (model.kind == NemoKind.STREAMING) SherpaLiveSession(this) else null

    /// A whole recording's words: 16 kHz mono 16-bit PCM in, text out.
    fun transcribe(pcm: ByteArray): String = when (model.kind) {
        NemoKind.WHOLE_RECORDING -> withOffline { recognizer ->
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(pcmToFloats(pcm), WAV_SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
        NemoKind.STREAMING -> {
            val session = SherpaLiveSession(this)
            session.accept(pcm)
            (session.update() + session.finish())
                .filter { it.complete }
                .joinToString(" ") { it.text.trim() }
                .trim()
        }
    }

    /// A whole recording's words with when each was said, and its plain
    /// text, for speaker labels (ADR 0030). A streaming model hears the
    /// recording as one stream here — no endpoint resets — so every time
    /// counts from the start of the recording.
    fun transcribeTimed(pcm: ByteArray): Pair<String, List<TimedWord>> {
        val recordingMs = pcm.size / 32
        return when (model.kind) {
            NemoKind.WHOLE_RECORDING -> withOffline { recognizer ->
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(pcmToFloats(pcm), WAV_SAMPLE_RATE)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    result.text.trim() to
                        wordsFromTokens(result.tokens, result.timestamps, result.durations, recordingMs)
                } finally {
                    stream.release()
                }
            }
            NemoKind.STREAMING -> withOnline { recognizer ->
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(pcmToFloats(pcm), WAV_SAMPLE_RATE)
                    stream.acceptWaveform(FloatArray(WAV_SAMPLE_RATE / 2), WAV_SAMPLE_RATE)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    result.text.trim() to wordsFromTokens(result.tokens, result.timestamps, null, recordingMs)
                } finally {
                    stream.release()
                }
            }
        }
    }

    private fun release() {
        online?.release()
        offline?.release()
        online = null
        offline = null
    }

    override fun close() = synchronized(this) {
        closed = true
        release()
    }
}

/// A streaming model as a live dictation engine: the same three calls the
/// kernel's whisper session answers, so the editor cannot tell them apart.
/// Settling is the model's own endpoint — a pause — and the line in flight
/// is the provisional rest. Cache-aware streaming re-decodes nothing, so an
/// update costs only the audio that is new.
class SherpaLiveSession(private val engines: NemoEngines) : LiveTranscriptionInterface {
    private val stream = engines.withOnline { it.createStream() }
    private var line = 0UL

    override fun accept(pcm: ByteArray) {
        val samples = pcmToFloats(pcm)
        engines.withOnline { stream.acceptWaveform(samples, WAV_SAMPLE_RATE) }
    }

    override fun update(): List<LiveLine> = engines.withOnline { recognizer ->
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        if (!recognizer.isEndpoint(stream)) return@withOnline listOf(LiveLine(line, text, false))
        recognizer.reset(stream)
        val settled = if (text.isEmpty()) emptyList() else listOf(LiveLine(line++, text, true))
        settled + LiveLine(line, "", false)
    }

    override fun finish(): List<LiveLine> = engines.withOnline { recognizer ->
        // A little silence lets the model's lookahead hear the last word out.
        stream.acceptWaveform(FloatArray(WAV_SAMPLE_RATE / 2), WAV_SAMPLE_RATE)
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        stream.release()
        if (text.isEmpty()) emptyList() else listOf(LiveLine(line, text, true))
    }
}

/// Every recording this provider has been given to transcribe, claimed and
/// answered through the external processor route (ADR 0018 § 2): the same
/// decisions as the phone's provider (`providerAction`, `transcriptDocument`
/// in shared code), the same compare-and-set on the subject's head, and
/// every failure ending in `failJob` — backoff, then quarantine.
///
/// `labelled`, when given, transcribes a recording with its speakers named
/// (ADR 0030); it is never used for an attached capture — dictation into a
/// note is one person at their cursor.
fun runNemoJobs(
    gilune: Gilune,
    transcribe: (ByteArray) -> String,
    limit: Int = 25,
    labelled: ((ByteArray) -> String)? = null,
): ProviderPass {
    var pass = ProviderPass()
    while (pass.claimed < limit) {
        val claim = runCatching { gilune.claimJob(NEMO_PROCESSOR) }
            .onFailure { GiluneLog.warn("the NeMo provider could not claim a job", it) }
            .getOrNull() ?: break
        claim.use { held ->
            pass = when (val action = providerAction(held.job().subjectPayload, held.job().eventPayload)) {
                is ProviderAction.NothingToDo -> {
                    gilune.submitOutput(held, emptyList())
                    pass.copy(skipped = pass.skipped + 1)
                }
                is ProviderAction.Fault -> {
                    gilune.failJob(held, action.why)
                    pass.copy(failed = pass.failed + 1)
                }
                ProviderAction.Decode -> {
                    val attached = jsonBoolean(held.job().subjectPayload, "attached") == true
                    transcribeClaim(gilune, held, if (attached || labelled == null) transcribe else labelled, pass)
                }
            }
        }
    }
    return pass
}

private fun transcribeClaim(gilune: Gilune, claim: ClaimedJob, transcribe: (ByteArray) -> String, pass: ProviderPass): ProviderPass {
    val job = claim.job()
    if (job.blob == null) {
        gilune.submitOutput(claim, emptyList())
        return pass.copy(skipped = pass.skipped + 1)
    }
    val text = runCatching {
        val bytes = generateSequence { claim.nextChunk() }.fold(java.io.ByteArrayOutputStream()) { out, chunk ->
            out.apply { write(chunk) }
        }.toByteArray()
        require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a canonical Gilune WAV" }
        transcribe(bytes.copyOfRange(44, bytes.size))
    }.getOrElse { error ->
        val why = error.message ?: error.javaClass.simpleName
        GiluneLog.warn("NeMo could not transcribe ${job.subjectId}: $why", error)
        runCatching { gilune.failJob(claim, why) }
        return pass.copy(failed = pass.failed + 1)
    }
    if (text.isBlank()) {
        gilune.submitOutput(claim, emptyList())
        return pass.copy(skipped = pass.skipped + 1)
    }
    val expected = claim.subjectHead()
    val receipt = runCatching {
        gilune.submitOutput(
            claim,
            listOf(
                ProposedOutput(
                    typeId = SOURCE_TYPE,
                    typeSchema = 1,
                    payload = transcriptDocument(job.subjectPayload, text),
                    target = OutputTarget.REVISE_SUBJECT,
                    expectedHead = expected,
                ),
            ),
        )
    }.getOrElse { refusal ->
        // The note moved on while it was being heard: somebody's newer
        // words win over an interpretation (ADR 0018), and nothing is
        // retried that would be refused again.
        if (expected != null && refusal is uniffi.gilune_ffi.GiluneException.Invalid) {
            runCatching { gilune.submitOutput(claim, emptyList()) }
            return pass.copy(skipped = pass.skipped + 1)
        }
        runCatching { gilune.failJob(claim, refusal.message ?: refusal.javaClass.simpleName) }
        return pass.copy(failed = pass.failed + 1)
    }
    return pass.copy(transcribed = pass.transcribed + receipt.outputs)
}

/// Register the plugin on this handle, on the phone's rules for a second
/// transcriber: a vault whose notes another transcriber is already writing
/// gets this one installed **switched off** (ADR 0018 § 3 — registering is
/// not choosing), local capabilities are granted only when newly declared,
/// and the external processor is registered when the plugin is on.
fun registerNemoOnHandle(gilune: Gilune) {
    runCatching {
        val declaration = nemoDeclaration()
        val installed = gilune.plugins().firstOrNull { it.pluginId == NEMO_PLUGIN }
        val newly = newlyDeclaredLocalCapabilities(installed, declaration.capabilities)
        val registered = registerProviderRespectingTheRule(gilune, declaration, installed)
        if (registered != "removed") {
            for (capability in newly) runCatching { gilune.grantCapability(NEMO_PLUGIN, capability) }
        }
        registerNemoProcessor(gilune)
    }.onFailure { GiluneLog.warn("the NeMo plugin could not register", it) }
}

/// Tell the kernel this process carries the NeMo processor, so its jobs are
/// left for [runNemoJobs] rather than burned by `runProcessing`. Refused
/// (harmlessly) while the plugin is off; called again whenever it may have
/// changed.
fun registerNemoProcessor(gilune: Gilune): Boolean {
    val on = runCatching { gilune.plugins() }.getOrNull()
        ?.any { it.pluginId == NEMO_PLUGIN && it.state == "enabled" } ?: false
    if (!on) return false
    return runCatching { gilune.registerExternalProcessor(NEMO_PLUGIN, NEMO_PROCESSOR) }.isSuccess
}
