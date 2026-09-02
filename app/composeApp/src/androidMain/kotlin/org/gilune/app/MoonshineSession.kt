// The streaming session behind the live caption (ADR 0018 § 1).
//
// This is the only file in the shell that names a Moonshine type. Above it
// the caption is `CaptionEvent`s and a `CaptionDisplay` (`Caption.kt`);
// below it is the vendor's `Transcriber`. Keeping the boundary here is
// what lets § 3's future `capture.live` slot arrive without the caption's
// own logic moving.
//
// **Three rules govern this file, and the third is the reason it exists.**
//
//  1. **A tee, never a second microphone.** The PCM handed to [feed] is
//     the same buffer the recorder is already writing to its capture
//     (§ 1). `MicTranscriber` — the AAR's convenience wrapper — opens its
//     own `AudioRecord` and is deliberately not used. R0 drove the engine
//     the same way for the same reason.
//
//  2. **The recording's thread is never made to wait.** R0 decompiled
//     `addAudioToStream` and found it decodes **on the calling thread**,
//     inline, with no worker and no queue: feeding from the microphone
//     loop would put a 100–400 ms decode between two `AudioRecord.read`
//     calls and drop audio out of the *recording*. So audio crosses into
//     this file through a bounded queue and is decoded on a thread of our
//     own; when decode falls behind, chunks are DROPPED. A dropped chunk
//     costs the caption a word. Nothing about the recording changes,
//     because the recorder buffered those bytes before we ever saw them.
//
//  3. **A failure here is not a failure of anything else** (§ 1, and
//     rule 1 of the constitution behind it). Every native call is
//     wrapped; the first fault tears the session down, reports once, and
//     leaves the recording running with no caption. There is no path from
//     this file to the capture, the blob, the vault or the kernel — the
//     only thing it can affect is what is drawn.

package org.gilune.app

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/// R0's condition 1, in one line. The library's default of 0.5 s gives a
/// time-to-first-token of 1,149–1,188 ms — the wrong side of ADR 0018
/// § 6's "under about a second". 0.25 s gives **649 ms**, and the caption
/// then sits ~150 ms behind the speaker instead of ~290 ms.
///
/// **Not "smaller is faster".** 0.1 s measured *worse* than 0.25 s
/// (1,160 ms), because `isUpdateDue` clamps its threshold against the
/// previous pass's cost. This number is measured, not tuned by intuition,
/// and it is x86_64-under-swiftshader: the accumulation term is
/// configuration but the decode term is hardware, so MS2 re-measures it on
/// ARM64 before anyone quotes it as ours.
const val MOONSHINE_INTERVAL_SECONDS = 0.25

/// About four seconds of 16 kHz mono audio at the recorder's 4 KiB reads.
/// Deep enough to ride out a slow decode pass, shallow enough that the
/// caption cannot drift minutes behind the speaker while claiming to be
/// live.
private const val QUEUE_CHUNKS = 32

private const val TAG = "GiluneCaption"

/// Signed 16-bit little-endian PCM to the float samples the engine wants,
/// which is the same conversion R0's WAV reader did (`getShort / 32768`).
/// Pure and separate so the arithmetic is tested without a microphone.
fun pcm16ToFloat(bytes: ByteArray, length: Int = bytes.size): FloatArray {
    val samples = length / 2
    val out = FloatArray(samples)
    for (i in 0 until samples) {
        val low = bytes[i * 2].toInt() and 0xFF
        val high = bytes[i * 2 + 1].toInt()
        out[i] = (((high shl 8) or low).toShort()).toFloat() / 32768f
    }
    return out
}

/// What this model's files are, asked of the engine rather than listed in
/// our source.
///
/// `getSttDependencies` is a **native** call — the manifest lives inside
/// `libmoonshine.so` — so it is the only description of the download that
/// cannot drift from what the loader will demand. R0 proved the answer
/// matches what actually lands, byte for byte, and measured it: 8 files,
/// 45,233,659 B.
///
/// The `model_arch` option is required **here** and rejected at load time
/// — R0's second API trap. This is the one place it may be passed.
fun moonshineManifest(model: MoonshineModel): List<MoonshineAsset> {
    JNI.ensureLibraryLoaded()
    val json = Transcriber.getSttDependencies(
        model.language,
        listOf(TranscriberOption("model_arch", model.arch.toString())),
    )
    Log.i(TAG, "model manifest for ${model.key}: $json")
    return parseMoonshineManifest(json)
}

/// One streaming session, alive only while a recording is (§ 1's battery
/// courtesy: "the session exists only while recording").
class MoonshineSession(
    private val modelDir: File,
    private val arch: Int,
    /// Called whenever the caption changes, on this session's own thread.
    /// Compose snapshot state is safe to write from it.
    private val onDisplay: (CaptionDisplay) -> Unit,
    /// Called at most once, when the caption dies. The recording is
    /// unaffected and the caller is expected to do nothing but stop
    /// drawing.
    private val onFailure: (String) -> Unit = {},
) {
    private val queue = ArrayBlockingQueue<FloatArray>(QUEUE_CHUNKS)
    private val stopping = AtomicBoolean(false)
    private val broken = AtomicBoolean(false)
    private val dropped = AtomicInteger(0)
    private val buffer = CaptionBuffer()
    private var worker: Thread? = null

    val alive: Boolean get() = !stopping.get() && !broken.get()

    /// Opens the session on its own thread and returns immediately. Model
    /// load cost R0 **1,158 ms cold / 142–494 ms warm**, which is far too
    /// long to spend on the tap that starts a recording — so the recording
    /// starts now and the caption catches up. Audio fed before the engine
    /// is ready is queued, and dropped if the queue fills first.
    fun start() {
        check(worker == null) { "already started" }
        worker = Thread { run() }.apply {
            isDaemon = true
            name = "gilune-caption"
            start()
        }
    }

    /// Hand over a slice of the recorder's PCM. Called on the microphone
    /// thread, so it does no work beyond a conversion and a non-blocking
    /// offer — and never throws, whatever state the session is in.
    fun feed(pcm: ByteArray, length: Int = pcm.size) {
        if (!alive || length < 2) return
        runCatching {
            if (!queue.offer(pcm16ToFloat(pcm, length))) dropped.incrementAndGet()
        }
    }

    /// End the session and clear the caption. Returns at once: teardown
    /// happens on the session's own thread, because freeing the native
    /// stream can take longer than a frame and this is called from a tap.
    fun stop() {
        stopping.set(true)
        queue.clear()
        onDisplay(CaptionDisplay.EMPTY)
    }

    private fun run() {
        // Stopped before the thread got going — a very short recording, or
        // a screen left immediately. Loading a model to close it again
        // would cost a second of CPU for nothing.
        if (stopping.get()) return
        val transcriber = open() ?: return
        try {
            while (!stopping.get()) {
                val chunk = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                // The decode that R0 found runs inline. On this thread it
                // is exactly where it should be.
                transcriber.addAudioToStream(stream, chunk, WAV_SAMPLE_RATE)
            }
        } catch (e: Throwable) {
            fail("the live caption stopped", e)
        } finally {
            close(transcriber)
        }
    }

    /// The vendor's stream handle: an `int`, not a pointer-width value.
    private var stream: Int = 0

    private fun open(): Transcriber? {
        // Held outside the try so that a throw part-way through — the
        // likeliest being `loadFromFiles` on a model that is not there —
        // can still close the native object that was already constructed.
        var opening: Transcriber? = null
        return try {
            JNI.ensureLibraryLoaded()
            val transcriber = Transcriber()
            opening = transcriber
            // Two R0 traps, both of which cost it a build cycle. The bare
            // `int` is the MODEL ARCHITECTURE, not flags; and `model_arch`
            // is a manifest option the native loader rejects outright, so
            // no options are passed here. Both failures surface as "Failed
            // to load transcriber from files: '<path>'", which names the
            // path and so reads like a missing file when it is neither.
            transcriber.loadFromFiles(modelDir.absolutePath, arch)
            transcriber.updateInterval = MOONSHINE_INTERVAL_SECONDS
            transcriber.addListener(Consumer { event -> accept(event) })
            stream = transcriber.createStream()
            transcriber.startStream(stream)
            transcriber
        } catch (e: Throwable) {
            runCatching { opening?.close() }
            fail("the live caption could not start", e)
            null
        }
    }

    private fun close(transcriber: Transcriber) {
        Log.i(TAG, "caption session ended; ${dropped.get()} chunk(s) dropped behind the decoder")
        runCatching { transcriber.stopStream(stream) }
        runCatching { transcriber.freeStream(stream) }
        runCatching { transcriber.close() }
    }

    /// The vendor's events, flattened onto [CaptionEvent] and folded. Runs
    /// inline on this thread inside `addAudioToStream`, which is what makes
    /// the caption's timestamps honest and a plain buffer safe.
    private fun accept(event: TranscriptEvent) {
        val line = when (event) {
            is TranscriptEvent.LineStarted -> event.line
            is TranscriptEvent.LineUpdated -> event.line
            is TranscriptEvent.LineTextChanged -> event.line
            is TranscriptEvent.LineCompleted -> event.line
            else -> null
        }
        if (line == null) {
            // An `Error` event is the engine telling us it has stopped
            // being useful. The words already on screen stay put; nothing
            // new will arrive.
            if (event is TranscriptEvent.Error) fail("the live caption stopped", event.cause)
            return
        }
        if (stopping.get()) return
        val display = buffer.accept(
            CaptionEvent(
                kind = event.javaClass.simpleName,
                lineId = line.id,
                text = line.text.orEmpty(),
                complete = line.isComplete,
            ),
        )
        onDisplay(display)
    }

    /// One report, whatever went wrong and however often. The caption goes
    /// quiet; nothing else is touched.
    private fun fail(message: String, cause: Throwable?) {
        if (!broken.compareAndSet(false, true)) return
        Log.w(TAG, "$message: ${cause?.message}", cause)
        stopping.set(true)
        queue.clear()
        onDisplay(CaptionDisplay.EMPTY)
        onFailure(message)
    }

    private companion object {
        const val POLL_MS = 100L
    }
}
