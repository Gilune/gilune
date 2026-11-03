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
import com.k2fsa.sherpa.onnx.OnlineStream
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

internal fun pcmToFloats(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): FloatArray {
    val count = (to - from) / 2
    return FloatArray(count) { i ->
        val lo = bytes[from + 2 * i].toInt() and 0xff
        val hi = bytes[from + 2 * i + 1].toInt()
        ((hi shl 8) or lo).toShort() / 32768f
    }
}

/// The canonical WAV's header, which every recording's bytes open with.
internal const val WAV_HEADER_BYTES = 44

/// How much PCM a piece carries when a whole recording is read a piece at
/// a time: the kernel's own chunk, two seconds of sound.
internal const val PCM_PIECE_BYTES = 1 shl 16

/// A recording on its way to a transcriber: how many samples it holds, and
/// its 16 kHz mono 16-bit PCM a piece at a time, each piece a whole number
/// of samples. The pieces can be read once.
///
/// Never the whole recording at once (memory round 2026-11-02): a user's
/// hour-long recording, held as bytes, then as bytes without the header,
/// then as floats, ran the desktop out of heap at every attempt.
class RecordingPcm(val samples: Long, val pieces: Sequence<ByteArray>) {
    companion object {
        /// PCM already in memory, in pieces of [PCM_PIECE_BYTES].
        fun of(pcm: ByteArray): RecordingPcm = RecordingPcm(
            pcm.size / 2L,
            sequence {
                val end = pcm.size - pcm.size % 2
                var at = 0
                while (at < end) {
                    val to = minOf(at + PCM_PIECE_BYTES, end)
                    yield(pcm.copyOfRange(at, to))
                    at = to
                }
            },
        )
    }

    /// The same recording, telling `heard` how much of it has been read, in
    /// whole percent: 0 before the first piece, then each time the figure
    /// changes, and 100 once the last piece is heard whatever [samples]
    /// claimed. A piece counts once the transcriber asks for the next, so
    /// the figure is how far through the recording it has got.
    fun reporting(heard: (Int) -> Unit): RecordingPcm = RecordingPcm(
        samples,
        sequence {
            heard(0)
            var said = 0
            var read = 0L
            for (piece in pieces) {
                yield(piece)
                read += piece.size / 2
                val percent = if (samples <= 0) 99 else (read * 100 / samples).toInt().coerceIn(0, 99)
                if (percent != said) {
                    said = percent
                    heard(percent)
                }
            }
            heard(100)
        },
    )
}

/// A canonical WAV's chunks as PCM pieces: the header checked and taken
/// off, and a byte split between chunks carried into the next piece so
/// every piece is a whole number of samples.
internal fun wavPieces(chunks: Sequence<ByteArray>): Sequence<ByteArray> = sequence {
    var header = ByteArray(0)
    var odd: Byte? = null
    for (chunk in chunks) {
        var piece = chunk
        if (header.size < WAV_HEADER_BYTES) {
            val take = minOf(WAV_HEADER_BYTES - header.size, piece.size)
            header += piece.copyOfRange(0, take)
            piece = piece.copyOfRange(take, piece.size)
            if (header.size == WAV_HEADER_BYTES) {
                require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a canonical Gilune WAV" }
            }
        }
        odd?.let { piece = byteArrayOf(it) + piece }
        odd = null
        if (piece.size % 2 == 1) {
            odd = piece.last()
            piece = piece.copyOf(piece.size - 1)
        }
        if (piece.isNotEmpty()) yield(piece)
    }
    require(header.size == WAV_HEADER_BYTES) { "not a canonical Gilune WAV" }
}

/// The longest stretch a whole-recording model hears at once. Such a model
/// takes its audio in one call and attends across all of it, so an hour
/// in one call is an hour of its working memory; it hears a long
/// recording as segments of at most this, cut at the quietest moment of
/// the segment's last [SEGMENT_SEARCH_SAMPLES] so a cut lands between words.
///
/// Twenty seconds, measured (Parakeet TDT 0.6B, 843 s two-voice recording,
/// 2026-11-02): the runtime keeps the largest working memory a segment has
/// needed until the model is let go, and that grows faster than the
/// segment — 60 s segments held 1.9 GB, 30 s 1.37 GB, 20 s 1.1 GB, of which
/// the model itself is 0.67. Shorter was also better heard: 60 s dropped
/// 46 of 2,161 words (2.8% error), 20 s dropped none (1.3%), and ran a
/// little faster. Below 20 s nothing more was saved.
internal const val SEGMENT_SAMPLES = 20 * WAV_SAMPLE_RATE
internal const val SEGMENT_SEARCH_SAMPLES = 5 * WAV_SAMPLE_RATE

/// The length of the stretch whose loudness is weighed when choosing a cut.
private const val QUIET_FRAME_SAMPLES = WAV_SAMPLE_RATE / 10

/// A recording's samples cut into segments of at most `max`, each cut at
/// the quietest [QUIET_FRAME_SAMPLES] in the segment's last `search`
/// samples. `emit` receives each segment and the sample it starts at.
internal class QuietSegments(
    private val max: Int = SEGMENT_SAMPLES,
    private val search: Int = SEGMENT_SEARCH_SAMPLES,
    private val emit: (FloatArray, Long) -> Unit,
) {
    private val buffer = FloatArray(max)
    private var filled = 0
    private var start = 0L

    fun push(samples: FloatArray) {
        var at = 0
        while (at < samples.size) {
            val take = minOf(samples.size - at, max - filled)
            samples.copyInto(buffer, filled, at, at + take)
            filled += take
            at += take
            if (filled == max) cut()
        }
    }

    fun finish() {
        if (filled > 0) emit(buffer.copyOf(filled), start)
        start += filled
        filled = 0
    }

    private fun cut() {
        val from = (max - search).coerceAtLeast(0)
        var quietest = max
        var least = Double.MAX_VALUE
        var frame = from
        while (frame + QUIET_FRAME_SAMPLES <= max) {
            var energy = 0.0
            for (k in frame until frame + QUIET_FRAME_SAMPLES) energy += buffer[k] * buffer[k]
            if (energy < least) {
                least = energy
                quietest = frame + QUIET_FRAME_SAMPLES / 2
            }
            frame += QUIET_FRAME_SAMPLES / 2
        }
        emit(buffer.copyOf(quietest), start)
        start += quietest
        buffer.copyInto(buffer, 0, quietest, max)
        filled = max - quietest
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

    /// Dictations listening now. Their streams belong to the recogniser,
    /// so it stays while any is open, whoever asks it to go.
    private var liveStreams = 0

    @Volatile
    private var closed = false
    private val sweeper = Thread {
        while (!closed) {
            Thread.sleep(60_000)
            synchronized(this) {
                if (System.currentTimeMillis() - lastUsed >= idleMs && liveStreams == 0) release()
            }
        }
    }.apply { isDaemon = true; start() }

    internal fun streamOpened() = synchronized(this) { liveStreams++ }

    internal fun streamClosed() = synchronized(this) { liveStreams = (liveStreams - 1).coerceAtLeast(0) }

    /// Give the model's memory back now rather than after ten idle minutes,
    /// unless a dictation is listening. A recordings pass calls this when
    /// it ends: Parakeet held ~1 GB until the sweep, for a note that was
    /// already written (owner report, 2026-11-02). The next recording loads
    /// it again, which costs seconds, not the gigabyte.
    fun letGo() = synchronized(this) {
        if (liveStreams == 0) release()
    }

    private val tokens get() = File(dir, "tokens.txt").path

    /// Whether a recogniser is in memory now — for tests, as the kernel's
    /// `whisper_model_resident` is.
    val resident: Boolean get() = synchronized(this) { online != null || offline != null }

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

    /// A whole recording's words: 16 kHz mono 16-bit PCM in, text out. The
    /// recording is heard as it is read, a piece at a time.
    fun transcribe(recording: RecordingPcm): String = when (model.kind) {
        NemoKind.WHOLE_RECORDING -> segmented(recording) {}.first
        NemoKind.STREAMING -> {
            val session = SherpaLiveSession(this)
            val settled = mutableListOf<String>()
            try {
                for (piece in recording.pieces) {
                    session.accept(piece)
                    session.update().filter { it.complete }.mapTo(settled) { it.text.trim() }
                }
                session.finish().filter { it.complete }.mapTo(settled) { it.text.trim() }
            } finally {
                session.abandon()
            }
            settled.filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /// A whole recording's words with when each was said, and its plain
    /// text, for speaker labels (ADR 0030). A streaming model hears the
    /// recording as one stream here — no endpoint resets — so every time
    /// counts from the start of the recording. `heard` is given each
    /// piece's samples as they go by, so the speaker model can listen to
    /// the same reading.
    fun transcribeTimed(recording: RecordingPcm, heard: (FloatArray) -> Unit = {}): Pair<String, List<TimedWord>> =
        when (model.kind) {
            NemoKind.WHOLE_RECORDING -> segmented(recording, heard)
            NemoKind.STREAMING -> withOnline { recognizer ->
                val stream = recognizer.createStream()
                try {
                    var samples = 0L
                    for (piece in recording.pieces) {
                        val floats = pcmToFloats(piece)
                        samples += floats.size
                        heard(floats)
                        stream.acceptWaveform(floats, WAV_SAMPLE_RATE)
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                    }
                    stream.acceptWaveform(FloatArray(WAV_SAMPLE_RATE / 2), WAV_SAMPLE_RATE)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    val recordingMs = (samples * 1000 / WAV_SAMPLE_RATE).toInt()
                    result.text.trim() to wordsFromTokens(result.tokens, result.timestamps, null, recordingMs)
                } finally {
                    stream.release()
                }
            }
        }

    /// A whole-recording model's hearing of a recording, segment by
    /// segment ([QuietSegments]): the segments' words joined, each word's
    /// time counted from the start of the recording.
    private fun segmented(recording: RecordingPcm, heard: (FloatArray) -> Unit): Pair<String, List<TimedWord>> =
        withOffline { recognizer ->
            val texts = mutableListOf<String>()
            val words = mutableListOf<TimedWord>()
            val segments = QuietSegments { samples, start ->
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, WAV_SAMPLE_RATE)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    result.text.trim().takeIf { it.isNotEmpty() }?.let(texts::add)
                    val offset = (start * 1000 / WAV_SAMPLE_RATE).toUInt()
                    val segmentMs = (samples.size.toLong() * 1000 / WAV_SAMPLE_RATE).toInt()
                    wordsFromTokens(result.tokens, result.timestamps, result.durations, segmentMs)
                        .mapTo(words) { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
                } finally {
                    stream.release()
                }
            }
            for (piece in recording.pieces) {
                val floats = pcmToFloats(piece)
                heard(floats)
                segments.push(floats)
            }
            segments.finish()
            texts.joinToString(" ") to words
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
///
/// Making a session costs nothing: the stream — and with it the model, if
/// it is not in memory — opens with the first audio, on the dictation's
/// own thread. Opening it here put a 3 s model load on the click that
/// started the microphone.
class SherpaLiveSession(private val engines: NemoEngines) : LiveTranscriptionInterface {
    private var opened: OnlineStream? = null
    private var line = 0UL

    private fun stream(recognizer: OnlineRecognizer): OnlineStream =
        opened ?: recognizer.createStream().also {
            opened = it
            engines.streamOpened()
        }

    override fun accept(pcm: ByteArray) {
        val samples = pcmToFloats(pcm, 0, pcm.size - pcm.size % 2)
        engines.withOnline { stream(it).acceptWaveform(samples, WAV_SAMPLE_RATE) }
    }

    override fun update(): List<LiveLine> = engines.withOnline { recognizer ->
        val stream = stream(recognizer)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        if (!recognizer.isEndpoint(stream)) return@withOnline listOf(LiveLine(line, text, false))
        recognizer.reset(stream)
        val settled = if (text.isEmpty()) emptyList() else listOf(LiveLine(line++, text, true))
        settled + LiveLine(line, "", false)
    }

    override fun finish(): List<LiveLine> {
        // Nothing was heard: there is no stream, and no reason to load a
        // model to say so.
        val stream = opened ?: return emptyList()
        try {
            return engines.withOnline { recognizer ->
                // A little silence lets the model's lookahead hear the last word out.
                stream.acceptWaveform(FloatArray(WAV_SAMPLE_RATE / 2), WAV_SAMPLE_RATE)
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                if (text.isEmpty()) emptyList() else listOf(LiveLine(line, text, true))
            }
        } finally {
            abandon()
        }
    }

    /// Close the stream without hearing the rest: after [finish], or when
    /// the session stops part-way. A stream left open would keep the model
    /// in memory for good, since nothing lets go of it while one listens.
    internal fun abandon() {
        val stream = opened ?: return
        opened = null
        runCatching { stream.release() }
        engines.streamClosed()
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
///
/// `heard` is told how far through each recording the engine is, in whole
/// percent (see [RecordingPcm.reporting]).
fun runNemoJobs(
    gilune: Gilune,
    transcribe: (RecordingPcm) -> String,
    limit: Int = 25,
    labelled: ((RecordingPcm) -> String)? = null,
    heard: (Int) -> Unit = {},
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
                    transcribeClaim(gilune, held, if (attached || labelled == null) transcribe else labelled, pass, heard)
                }
            }
        }
    }
    return pass
}

private fun transcribeClaim(
    gilune: Gilune,
    claim: ClaimedJob,
    transcribe: (RecordingPcm) -> String,
    pass: ProviderPass,
    heard: (Int) -> Unit,
): ProviderPass {
    val job = claim.job()
    val blob = job.blob
    if (blob == null) {
        gilune.submitOutput(claim, emptyList())
        return pass.copy(skipped = pass.skipped + 1)
    }
    val text = runCatching {
        // Read as it is heard: the claim's chunks go to the engine one at
        // a time, and the recording is never held whole.
        val samples = (blob.bytes.toLong() - WAV_HEADER_BYTES).coerceAtLeast(0) / 2
        transcribe(RecordingPcm(samples, wavPieces(generateSequence { claim.nextChunk() })).reporting(heard))
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
