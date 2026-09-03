// Moonshine as the committed-transcript provider (ADR 0018 § 2, § 3, R3).
//
// R1 made the words appear while you speak. This file is where the words
// are **kept** — and the two are deliberately not the same words. § 2 is
// explicit: "the final transcript is a fresh decode of the committed blob,
// never the concatenated caption. Interpretation derives from the preserved
// source, not from a buffer that no longer exists." Nothing in this file
// can see the caption; it reads the digest-verified bytes the kernel hands
// it and decodes them again, with the whole recording's context.
//
// The shape is the processor contract in a different transport (R2):
//
//   claim_job → stream the subject's blob → decode → submit_output
//
// and every guarantee is the kernel's, not ours. The lease, the
// `processing_runs` row keyed `{processor_id}:{event_id}`, the derived
// revision IDs, the `processor:moonshine-transcribe` origin, the
// `create-revision` operation and the `revision-added` event are all
// committed on the far side of `submitOutput`. **The caller proposes; the
// kernel commits.** A crash anywhere in here costs a retry and never a
// double transcript.
//
// Three rules this file keeps that are worth naming:
//
//  - **Nothing here is on the capture path.** Every entry point runs after
//    a recording has already committed durably (rule 1). A model that will
//    not load, an engine that throws, a process killed mid-decode: the
//    recording is untouched and its job comes back.
//  - **A job whose subject already has words is completed with no
//    output.** That is the shipped "nothing to do" outcome — a Rust
//    processor gets it for a capture it has nothing to say about
//    (`FakeTranscriber` on a text capture) — and it is what scopes a
//    provider switch's catch-up to wordless captures (§ 3).
//  - **A blank transcript writes nothing at all.** § Consequences makes
//    that the rule for both engines; here it is free, because R0 measured
//    that Moonshine's silence produces *no events* rather than whisper's
//    `[BLANK_AUDIO]` stage direction.

package org.gilune.app

import android.util.Log
import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import org.json.JSONObject
import uniffi.gilune_ffi.ClaimedJob
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.OutputTarget
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.ProposedOutput
import java.io.File
import java.util.function.Consumer

private const val TAG = "GiluneMoonshine"

/// The kernel's own type id for a capture. A revise-subject output keeps
/// the subject's type and the kernel ignores both fields, but sending the
/// truth costs nothing and reads correctly beside whisper's.
private const val SOURCE_TYPE = "org.memory.source"

/// How many jobs one pass will take before returning. A bound rather than
/// a policy: a catch-up over a long-neglected vault must not hold a
/// coroutine (or a foreground service) for an unbounded time, and the next
/// pass simply continues.
const val MOONSHINE_JOB_LIMIT = 25

/// What the provider should do with a claimed job, decided from the
/// subject's own document alone — no engine, no bytes, no clock.
sealed interface ProviderAction {
    data object Decode : ProviderAction

    /// Complete the job, write nothing. The subject is not a recording, or
    /// it already has words.
    data class NothingToDo(val why: String) : ProviderAction

    /// Fail the job. Deliberately distinct from [NothingToDo]: a document
    /// that cannot be read is a fault to retry and eventually quarantine,
    /// exactly as `whisper.rs` treats an unparseable payload.
    data class Fault(val why: String) : ProviderAction
}

/// The decision, pure over the subject's head document.
///
/// **The wordless gate lives here** (§ 3). It is asked of every job, not
/// only of a catch-up, which means it also narrows § 3's named hole: with
/// both providers somehow enabled, this one will not overwrite words the
/// other already wrote. It narrows rather than closes — two engines
/// decoding the same silent-so-far capture at once still race — and only
/// the shell's one-active rule stops that.
fun providerAction(subjectPayload: String): ProviderAction {
    val document = runCatching { JSONObject(subjectPayload) }.getOrNull()
        ?: return ProviderAction.Fault("the subject's document could not be read")
    if (document.optString("media") != "wav") {
        return ProviderAction.NothingToDo("not a recording")
    }
    if (document.optString("text").isNotBlank()) {
        return ProviderAction.NothingToDo("already has words")
    }
    return ProviderAction.Decode
}

/// The subject's document with the transcript in it — **whisper's shape,
/// field for field**: parse the head document, replace `text`, keep
/// everything else (`media`, `rel_path`, the capture's own facts) exactly
/// as it was, and submit it as a revision of the subject (ADR 0013). The
/// only difference between the two engines in the vault is the origin the
/// kernel stamps, which is the whole point of distinct processor ids.
fun transcriptDocument(subjectPayload: String, text: String): String =
    JSONObject(subjectPayload).put("text", text).toString()

/// The transcript as the engine's events build it. Deliberately **not**
/// `CaptionBuffer`: that one keeps the last six lines because a caption is
/// three rows tall, and a transcript that dropped everything said before
/// the last six sentences would be a data loss dressed as a display cap.
///
/// The fold **is** the caption's — `foldCaption` with nothing thrown away —
/// for the same measured reasons (R0 § Question 5): a settled line never
/// changes again, a blank line never opens, and completion is read off the
/// flag rather than the event name. It was a second hand-written copy of
/// those rules until this round, which is one copy that could drift from the
/// spike that measured them; retention was always the only difference, so
/// retention is now the only parameter.
class TranscriptCollector {
    private var lines: List<CaptionLine> = emptyList()

    @Synchronized
    fun accept(event: CaptionEvent) {
        lines = foldCaption(lines, event, retain = Int.MAX_VALUE)
    }

    /// The words, in the order they were spoken. Whitespace is collapsed
    /// and the ends trimmed; nothing else is touched.
    ///
    /// **No non-speech marker strip, and that is a finding rather than an
    /// omission.** whisper narrates silence (`[BLANK_AUDIO]`, `[MUSIC]`)
    /// and `whisper.rs::clean_transcript` exists to delete those stage
    /// directions before they become a note body. R0 measured Moonshine's
    /// equivalent: five seconds of digital silence produced **zero
    /// events** — "the VAD does not hallucinate" — so there is no marker
    /// vocabulary here to strip. Porting that deleter anyway would add a
    /// second, drifting copy of a rule whose own comment says it "deletes
    /// a person's words if it is wrong", to protect against output this
    /// engine has never been observed to produce.
    @Synchronized
    fun text(): String =
        lines.joinToString(" ") { it.text.trim() }.split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    @Synchronized
    fun clear() {
        lines = emptyList()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/// What the loop needs of an engine, which is one method. It exists so the
/// route — claim, stream, submit, skip, fail — can be driven against a real
/// vault and the real FFI without a 45 MB model in the way; the engine is
/// then the only part a test has to stand in for, and everything the ADR
/// makes a promise about is the part that ran.
interface TranscriptEngine : AutoCloseable {
    /// The recording's committed bytes, chunk by chunk, as words. An empty
    /// or blank answer means "no words", never an error.
    fun decode(chunks: Sequence<ByteArray>): String
}

/// The engine, loaded once and used for as many jobs as one pass has.
///
/// **Not [MoonshineSession].** That one exists to keep a microphone loop
/// free: it decodes on its own thread behind a bounded queue and **drops
/// audio when it falls behind**, because a dropped chunk costs the caption
/// a word and nothing else. A committed transcript may not lose a word, so
/// this decodes synchronously on the caller's thread with no queue and no
/// dropping. Same engine, opposite trade, and they are separate files so
/// that neither trade can be applied to the wrong one by accident.
class MoonshineDecoder(modelDir: File, arch: Int) : TranscriptEngine {
    private val transcriber: Transcriber
    @Volatile
    private var collector: TranscriptCollector? = null

    init {
        JNI.ensureLibraryLoaded()
        val opening = Transcriber()
        try {
            // R0's two API traps, as `MoonshineSession` records them: the
            // bare int is the model architecture, and `model_arch` is a
            // manifest option the loader rejects, so no options here.
            opening.loadFromFiles(modelDir.absolutePath, arch)
            // The vendor default interval (0.5 s) is deliberately kept
            // rather than R1's 0.25 s. That number buys a caption its
            // time-to-first-token; a file decode has nobody watching, and
            // fewer, larger passes are cheaper.
            opening.addListener(Consumer { event -> accept(event) })
        } catch (e: Throwable) {
            runCatching { opening.close() }
            throw e
        }
        transcriber = opening
    }

    /// Decode one recording. The chunks are pulled lazily, so the blob
    /// crosses the boundary and reaches the engine a piece at a time and
    /// is never held whole (ADR 0001's boundary rules).
    override fun decode(chunks: Sequence<ByteArray>): String {
        val wav = WavPcmDecoder()
        val collected = TranscriptCollector()
        collector = collected
        val stream = transcriber.createStream()
        try {
            transcriber.startStream(stream)
            for (chunk in chunks) {
                val samples = wav.feed(chunk)
                wav.complaint?.let { throw IllegalArgumentException(it) }
                if (samples.isNotEmpty()) {
                    transcriber.addAudioToStream(stream, samples, WAV_SAMPLE_RATE)
                }
            }
            if (!wav.sawHeader) throw IllegalArgumentException("not a canonical Gilune WAV")
            // A recording ends when the speaker stops, but the VAD only
            // closes a line when it hears silence — so the file's last
            // sentence would otherwise sit unsettled forever. Feeding a
            // second of digital silence is what ends it, and R0 measured
            // that silence produces no events of its own.
            repeat(SILENCE_BLOCKS) {
                transcriber.addAudioToStream(stream, SILENCE, WAV_SAMPLE_RATE)
            }
            return collected.text()
        } finally {
            collector = null
            runCatching { transcriber.stopStream(stream) }
            runCatching { transcriber.freeStream(stream) }
        }
    }

    /// The vendor's events, flattened onto the provider-neutral
    /// [CaptionEvent] the caption already speaks by the one shared
    /// [captionEventOf]. R0 found these fire inline inside
    /// `addAudioToStream`, on the thread that called it.
    ///
    /// **This caller's null policy**: ignore it. An event with no line
    /// carries no words, and an engine error here is answered by the
    /// decode throwing — which the loop turns into `failJob`, a retry and
    /// eventually a quarantine. There is nothing to tear down.
    private fun accept(event: TranscriptEvent) {
        val flattened = captionEventOf(event) ?: return
        collector?.accept(flattened)
    }

    override fun close() {
        runCatching { transcriber.close() }
    }

    private companion object {
        /// Half a second of 16 kHz silence, twice — one update interval
        /// each, so the VAD gets a pass to notice the speech ended.
        val SILENCE = FloatArray(WAV_SAMPLE_RATE / 2)
        const val SILENCE_BLOCKS = 2
    }
}

/// Turns a stream of WAV file chunks into PCM samples, a chunk at a time.
///
/// **The `data` size field is never consulted**, exactly as the kernel's
/// own `canonical_wav_samples` never consults it: a streamed capture writes
/// `0xFFFFFFFF` there because a stream cannot know its own length and a
/// content-addressed blob cannot be patched afterwards (`Audio.kt`). The
/// samples are everything from byte 44 to the end of the blob, and the
/// shape comes off the fixed header offsets.
class WavPcmDecoder {
    private val header = ByteArray(WAV_HEADER_BYTES)
    private var headerFilled = 0
    private var odd: Byte? = null

    /// What is wrong with this file, once enough of it has been seen to
    /// tell. Non-null means the decode must stop.
    var complaint: String? = null
        private set

    val sawHeader: Boolean get() = headerFilled == WAV_HEADER_BYTES

    fun feed(chunk: ByteArray): FloatArray {
        var offset = 0
        if (!sawHeader) {
            val take = minOf(WAV_HEADER_BYTES - headerFilled, chunk.size)
            System.arraycopy(chunk, 0, header, headerFilled, take)
            headerFilled += take
            offset = take
            if (sawHeader) complaint = canonicalWavComplaint(header)
        }
        if (complaint != null || offset >= chunk.size) return EMPTY
        val carried = odd
        val body = if (carried == null) {
            chunk.copyOfRange(offset, chunk.size)
        } else {
            odd = null
            byteArrayOf(carried) + chunk.copyOfRange(offset, chunk.size)
        }
        // A 64 KiB chunk boundary can fall between a sample's two bytes;
        // the odd byte waits for its partner rather than being decoded as
        // half a sample.
        val whole = body.size - body.size % 2
        if (whole < body.size) odd = body[body.size - 1]
        if (whole == 0) return EMPTY
        return pcm16ToFloat(body, whole)
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}

const val WAV_HEADER_BYTES = 44

/// Whether these 44 bytes are the shape every Gilune recording takes.
/// The wording mirrors `whisper.rs` so a person meeting either message is
/// meeting the same complaint.
fun canonicalWavComplaint(header: ByteArray): String? {
    if (header.size < WAV_HEADER_BYTES) return "not a canonical Gilune WAV"
    if (!header.matches(0, "RIFF") || !header.matches(8, "WAVE")) {
        return "not a canonical Gilune WAV"
    }
    // `VaultFiles`' readers rather than three local copies: the duration
    // row and this check read the same header, and a disagreement between
    // them would be one of them wrong about somebody's recording.
    val channels = header.littleEndianShort(22)
    val rate = header.littleEndianInt(24)
    val bits = header.littleEndianShort(34)
    if (channels != WAV_CHANNELS || rate != WAV_SAMPLE_RATE || bits != WAV_BITS_PER_SAMPLE) {
        return "unsupported WAV shape: ${channels}ch ${rate}Hz ${bits}bit " +
            "(expected ${WAV_CHANNELS}ch ${WAV_SAMPLE_RATE}Hz ${WAV_BITS_PER_SAMPLE}bit)"
    }
    return null
}

/// The subject's preserved bytes, pulled one chunk at a time from the
/// claim. Lazy: a chunk crosses only when the decoder asks for it.
private fun claimChunks(claim: ClaimedJob): Sequence<ByteArray> =
    generateSequence { claim.nextChunk() }

/// **Register this host as the implementation of Moonshine's processor**
/// (ADR 0018 § 2). It creates no subscription — `registerPlugin` already
/// did that — and queues nothing. What it does is stop `runProcessing`
/// from claiming these jobs, finding no Rust implementation for them, and
/// burning a lease and an attempt on every pass.
///
/// Called on **every handle that opens the vault where a Moonshine
/// implementation exists**: the activity's and the recording service's.
/// Registration is per-handle and per-session by design — which process
/// carries an implementation is a fact about that process, never about the
/// vault — so a handle that skipped this would silently park its own jobs.
///
/// Gated on the plugin being enabled, which the kernel enforces anyway;
/// asking first keeps a disabled provider from filling the log with
/// refusals on every refresh. Returns whether this handle may now claim.
fun registerMoonshineProcessor(gilune: Gilune, plugins: List<PluginInfo>): Boolean {
    val installed = plugins.firstOrNull { it.pluginId == MOONSHINE_PLUGIN } ?: return false
    if (installed.state != "enabled") return false
    return runCatching { gilune.registerExternalProcessor(MOONSHINE_PLUGIN, MOONSHINE_PROCESSOR) }
        .onFailure { Log.w(TAG, "the provider could not register", it) }
        .isSuccess
}

/// Drive the route until nothing is waiting: claim, stream, decode, submit.
///
/// The decoder is opened **lazily and at most once per pass**: loading the
/// model cost R0 1,158 ms cold, and a pass that finds only jobs with
/// nothing to do must not pay it. It is closed when the pass ends, so
/// between recordings the phone carries no resident model.
fun runMoonshineJobs(
    gilune: Gilune,
    openEngine: () -> TranscriptEngine,
    limit: Int = MOONSHINE_JOB_LIMIT,
): ProviderPass {
    var pass = ProviderPass()
    var engine: TranscriptEngine? = null
    try {
        while (pass.claimed < limit) {
            val claim = runCatching { gilune.claimJob(MOONSHINE_PROCESSOR) }
                .onFailure { Log.w(TAG, "claim failed", it) }
                .getOrNull() ?: break
            claim.use { held ->
                pass = when (val action = providerAction(held.job().subjectPayload)) {
                    is ProviderAction.NothingToDo -> {
                        gilune.submitOutput(held, emptyList())
                        pass.copy(skipped = pass.skipped + 1)
                    }
                    is ProviderAction.Fault -> {
                        gilune.failJob(held, action.why)
                        pass.copy(failed = pass.failed + 1)
                    }
                    ProviderAction.Decode -> {
                        if (engine == null) engine = openEngine()
                        transcribe(gilune, held, engine, pass)
                    }
                }
            }
        }
    } finally {
        runCatching { engine?.close() }
    }
    return pass
}

/// One recording, decoded and submitted. Every failure mode here ends in
/// `failJob`, which is the shipped backoff-then-quarantine path a Rust
/// processor's `Err` takes — an app-side engine that throws is quarantined
/// like a bundled one rather than retried forever.
private fun transcribe(
    gilune: Gilune,
    claim: ClaimedJob,
    engine: TranscriptEngine?,
    pass: ProviderPass,
): ProviderPass {
    val job = claim.job()
    if (job.blob == null || engine == null) {
        // A subject with no preserved bytes has nothing to decode. Not a
        // failure: the job is done and no words exist to write.
        gilune.submitOutput(claim, emptyList())
        return pass.copy(skipped = pass.skipped + 1)
    }
    val decoded = runCatching { engine.decode(claimChunks(claim)) }
    val text = decoded.getOrElse { error ->
        val why = error.message ?: error.javaClass.simpleName
        Log.w(TAG, "decode failed for ${job.subjectId}: $why", error)
        runCatching { gilune.failJob(claim, why) }
        return pass.copy(failed = pass.failed + 1)
    }
    if (text.isBlank()) {
        // § Consequences: "a blank transcript writes nothing". The job is
        // done; the note keeps the empty body a recording of silence
        // honestly has.
        gilune.submitOutput(claim, emptyList())
        return pass.copy(skipped = pass.skipped + 1)
    }
    // **Compare-and-set on the subject's head.** A decode takes seconds,
    // and the note it is about is a file a person can be typing in — or
    // that a sync can be revising — for the whole of them. Without this the
    // revision was written over whatever the note had become, so words
    // typed in the seventh second of an eight-second transcription were
    // gone, silently, and the only trace was a revision nobody asked for.
    //
    // The head is the one the claim handed over with the payload this
    // transcript was written from, so "unchanged" means exactly "still the
    // document these words belong to".
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
        // The note moved on. That is **not** a failure: nothing is wrong
        // with the recording, the engine or this device — somebody else's
        // words are simply newer than ours, and rule 1 says theirs win over
        // an interpretation. So the job is completed with nothing written,
        // which is the same "nothing to do" a job whose subject already had
        // words gets, and no retry is queued for work that would be refused
        // again for the same reason.
        //
        // **Narrowed as far as the boundary allows**: only when this
        // submission asked for a compare-and-set, and only for the refusal
        // class the kernel raises for it. The FFI has one `Invalid`
        // variant, so a malformed payload would land here too — the detail
        // is logged verbatim rather than swallowed, and the payload is
        // derived from the subject's own document, so there is one way in.
        if (expected != null && refusal is uniffi.gilune_ffi.GiluneException.Invalid) {
            Log.i(TAG, "the note changed while ${job.subjectId} was being transcribed: ${refusal.message}")
            runCatching { gilune.submitOutput(claim, emptyList()) }
            return pass.copy(skipped = pass.skipped + 1)
        }
        val why = refusal.message ?: refusal.javaClass.simpleName
        Log.w(TAG, "submitting the transcript for ${job.subjectId} failed: $why", refusal)
        runCatching { gilune.failJob(claim, why) }
        return pass.copy(failed = pass.failed + 1)
    }
    // A skipped duplicate is at-least-once working as promised, not a
    // failure: another claimant landed these words first.
    return pass.copy(transcribed = pass.transcribed + receipt.outputs)
}
