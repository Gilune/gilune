// Spike R0 — the rig behind `R0MoonshineSpikeTest` (ADR 0018 § 6).
//
// SPIKE SCAFFOLDING. Nothing here is product code and nothing here is
// imported by product code: the Moonshine AAR is scoped to the instrumented
// test source set in build.gradle.kts, so the shipped APK never sees it.
// The report is `spikes/r0-moonshine/README.md`.
//
// Two things this rig exists to do honestly.
//
// FIRST, it drives the streaming engine with OUR OWN PCM rather than
// `MicTranscriber`. That is deliberate and it is the shape ADR 0018 § 1
// describes: "the live path is a tee off that buffer, not a second
// microphone". `Transcriber.createStream`/`addAudioToStream` is exactly that
// seam, so what is measured here is the code path R1 would actually ship —
// not a convenience wrapper that opens its own recorder.
//
// SECOND, it makes time-to-first-token measurable at all. Decompiling the
// AAR settles the semantics: `addAudioToStream` buffers, then — on the
// CALLING thread, when `isUpdateDue` says half a second of new audio has
// landed — runs `moonshineTranscribeStream` and notifies listeners inline.
// There is no worker thread and no queue between feeding and hearing. So the
// wall clock from the first sample handed over to the first `LineTextChanged`
// callback is a true end-to-end figure, and the pacing loop below can charge
// decode against a real-time clock the way a live microphone would.

package org.gilune.app

import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Consumer

/** One callback as it actually arrived, with the clock it arrived on. */
data class R0Event(
    /** Wall ms since the first PCM sample was handed to the engine. */
    val atMs: Long,
    /** Audio ms handed over by the time it fired — the "how far had I spoken". */
    val audioFedMs: Long,
    val kind: String,
    val lineId: Long,
    val text: String,
    val complete: Boolean,
    /** The library's own self-reported cost of the pass that produced it. */
    val libLatencyMs: Int,
)

/** One streaming session, start to finish. */
class R0Run(
    val events: List<R0Event>,
    val loadMs: Long,
    val streamOpenMs: Long,
    val audioMs: Long,
    val feedWallMs: Long,
    /** Wall ms spent inside `addAudioToStream` — the CPU the live path costs. */
    val decodeWallMs: Long,
    val chunkMs: Int,
    val paced: Boolean,
    val nativeHeapBeforeLoad: Long,
    val nativeHeapAfterLoad: Long,
    val nativeHeapAfterRun: Long,
) {
    val firstText: R0Event? get() = events.firstOrNull { it.kind == "LineTextChanged" && it.text.isNotBlank() }
    val completed: List<R0Event> get() = events.filter { it.kind == "LineCompleted" }
    val finalText: String get() = completed.joinToString(" ") { it.text.trim() }.trim()

    /** The number ADR 0018 § 6 gates on: how long before the first word shows. */
    val ttftMs: Long? get() = firstText?.atMs

    /**
     * How far BEHIND the speaker the first word was, when the audio is fed at
     * the rate a person produces it. On an unpaced run this is meaningless,
     * because the machine is ahead of the speech by construction.
     */
    val firstTextLagMs: Long? get() = firstText?.let { it.atMs - it.audioFedMs }

    fun summary(): String = buildString {
        appendLine("R0Run(paced=$paced, chunk=${chunkMs}ms, audio=${audioMs}ms)")
        appendLine("  load=${loadMs}ms  streamOpen=${streamOpenMs}ms")
        appendLine("  feedWall=${feedWallMs}ms  decodeWall=${decodeWallMs}ms  " +
            "rtf=${"%.3f".format(decodeWallMs.toDouble() / audioMs.coerceAtLeast(1))}")
        appendLine("  TTFT=${ttftMs}ms  lagAtFirstText=${firstTextLagMs}ms")
        appendLine("  events=${events.size}  completedLines=${completed.size}")
        appendLine("  nativeHeap MB: beforeLoad=${nativeHeapBeforeLoad / 1048576}" +
            " afterLoad=${nativeHeapAfterLoad / 1048576} afterRun=${nativeHeapAfterRun / 1048576}")
        appendLine("  text=\"$finalText\"")
    }
}

object R0 {
    const val TAG = "R0Moonshine"
    const val SAMPLE_RATE = 16_000

    /**
     * Streaming tiny, English. `model_arch = 2` is
     * `JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING` — the smallest member of the
     * 2026 streaming family, which is the only family that can produce
     * partials at all (research § 1.2).
     */
    const val ARCH_TINY_STREAMING = 2

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun spec(): ModelSpec = ModelSpec.stt("en", ARCH_TINY_STREAMING, false)

    /** The options the AAR itself would build for that spec, for `getSttDependencies`. */
    fun specOptions(): List<TranscriberOption> =
        listOf(TranscriberOption("model_arch", ARCH_TINY_STREAMING.toString()))

    /** Where a fetched model is written. App-private, so it survives `install -r`. */
    fun downloadRoot(): File = File(context.filesDir, "r0-moonshine")

    /**
     * Roots searched for an already-present model, in order: the one the fetch
     * test writes, then an adb-pushed cache. A run that finds neither SKIPS —
     * the suite must never require a 77 MB download (ADR 0018 § 6, and the MS1
     * precedent of gating rather than assuming).
     */
    private fun roots(): List<File> = listOf(
        downloadRoot(),
        File("/data/local/tmp/gilune-r0/models"),
    )

    /**
     * A directory holds the model when the tokenizer is beside the encoder.
     * Measured, not assumed: `AssetDownloader.ensureModelPresent(root, …)`
     * writes the files STRAIGHT INTO the root it is handed, while
     * `ModelCache.directoryFor` appends a `stt-en-2` key — so the two halves
     * of the AAR's own cache API disagree about where a model lives, and a
     * caller that trusts either one alone looks in the wrong place. R1 must
     * pick one and stick to it.
     */
    private fun holdsModel(dir: File?): Boolean =
        dir != null && dir.isDirectory && File(dir, "tokenizer.bin").isFile

    fun modelDir(): File? = roots().firstNotNullOfOrNull { root ->
        val keyed = runCatching { ModelCache.directoryFor(context, spec(), root) }.getOrNull()
        when {
            holdsModel(root) -> root
            holdsModel(keyed) -> keyed
            else -> null
        }
    }

    fun assumeModel(): File {
        val dir = modelDir()
        assumeTrue(
            "R0 SKIP: no streaming-tiny model on the device. Fetch it once with " +
                "`-Pandroid.testInstrumentationRunnerArguments.gilune.r0.fetch=1` " +
                "(see spikes/r0-moonshine/README.md).",
            dir != null,
        )
        return dir!!
    }

    /** True only when the run explicitly asked to hit the network. */
    fun fetchRequested(): Boolean =
        InstrumentationRegistry.getArguments().getString("gilune.r0.fetch") == "1"

    /** The host-generated TTS clip, pushed by adb. Absent on a plain suite run. */
    fun speechWav(): File? =
        listOf(
            File("/data/local/tmp/gilune-r0/speech.wav"),
            File(context.filesDir, "r0-speech.wav"),
        ).firstOrNull { it.isFile && it.length() > 44 }

    // ------------------------------------------------------------------ audio

    /**
     * Minimal 16 kHz mono s16 WAV reader. Deliberately ours rather than the
     * AAR's `WavReader` (which is package-private): the spike should not
     * depend on the library to prepare the library's own input.
     */
    fun readWav16kMonoS16(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "not a RIFF/WAVE file: ${file.absolutePath}"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var dataAt = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    channels = buf.getShort(pos + 10).toInt()
                    rate = buf.getInt(pos + 12)
                    bits = buf.getShort(pos + 22).toInt()
                }
                "data" -> { dataAt = pos + 8; dataLen = size }
            }
            if (dataAt >= 0) break
            pos += 8 + size + (size and 1)
        }
        require(dataAt >= 0) { "no data chunk" }
        require(channels == 1 && rate == SAMPLE_RATE && bits == 16) {
            "expected 16 kHz mono s16, got ${rate}Hz ${channels}ch ${bits}bit"
        }
        val n = minOf(dataLen, bytes.size - dataAt) / 2
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = buf.getShort(dataAt + i * 2) / 32768f
        return out
    }

    /** Digital silence — the control that proves the VAD is not hallucinating. */
    fun silence(ms: Int) = FloatArray(SAMPLE_RATE * ms / 1000)

    // ---------------------------------------------------------------- driving

    /**
     * Feed [pcm] to a fresh streaming session and record every callback.
     *
     * [paced] true sleeps between chunks so the audio arrives at the rate a
     * person speaks it — the only way a latency figure means anything to a
     * user. [paced] false feeds flat out, which measures the machinery's
     * floor: how much audio the engine must see before it will say anything.
     */
    fun streamPcm(
        modelDir: File,
        pcm: FloatArray,
        chunkMs: Int = 100,
        paced: Boolean = true,
        /** `null` keeps the library default of 0.5 s. */
        updateInterval: Double? = null,
    ): R0Run {
        val heapBefore = Debug.getNativeHeapAllocatedSize()
        // Two traps here, both cost a build cycle to find and both belong in
        // R1's notes.
        //
        // 1. The bare `int` in `loadFromFiles(path, int)` is the MODEL
        //    ARCHITECTURE, not flags. `MicTranscriber.load` passes its `arch`
        //    field into that slot. Passing 0 asks for legacy non-streaming
        //    tiny and fails against streaming files.
        // 2. `model_arch` is a MANIFEST option, not a transcriber option.
        //    `getSttDependencies` requires it; the native loader rejects it
        //    outright — "Unknown transcriber option: 'model_arch'" — so the
        //    options list must be empty at load time.
        //
        // Both failures surface as the same message, "Failed to load
        // transcriber from files: '<path>'", which names the PATH and so
        // reads like a missing-file fault when it is neither.
        val transcriber = Transcriber()
        val loadStart = System.nanoTime()
        transcriber.loadFromFiles(modelDir.absolutePath, ARCH_TINY_STREAMING)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        val heapAfterLoad = Debug.getNativeHeapAllocatedSize()
        updateInterval?.let { transcriber.updateInterval = it }

        val openStart = System.nanoTime()
        val stream = transcriber.createStream()
        transcriber.startStream(stream)
        val streamOpenMs = (System.nanoTime() - openStart) / 1_000_000

        val events = mutableListOf<R0Event>()
        var t0 = 0L
        var fedSamples = 0L

        // Decode runs inline on this thread (see the header), so a plain
        // mutable list is safe and the timestamps are the feeder's own clock.
        transcriber.addListener(
            Consumer { event ->
                val now = System.nanoTime()
                val line = when (event) {
                    is TranscriptEvent.LineStarted -> event.line
                    is TranscriptEvent.LineUpdated -> event.line
                    is TranscriptEvent.LineTextChanged -> event.line
                    is TranscriptEvent.LineCompleted -> event.line
                    else -> null
                }
                events += R0Event(
                    atMs = if (t0 == 0L) 0 else (now - t0) / 1_000_000,
                    audioFedMs = fedSamples * 1000 / SAMPLE_RATE,
                    kind = event.javaClass.simpleName,
                    lineId = line?.id ?: -1,
                    text = line?.text ?: (event as? TranscriptEvent.Error)?.cause?.message.orEmpty(),
                    complete = line?.isComplete ?: false,
                    libLatencyMs = line?.lastTranscriptionLatencyMs ?: -1,
                )
            },
        )

        val chunk = SAMPLE_RATE * chunkMs / 1000
        var decodeNs = 0L
        t0 = System.nanoTime()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunk, pcm.size)
            val slice = pcm.copyOfRange(offset, end)
            val callStart = System.nanoTime()
            transcriber.addAudioToStream(stream, slice, SAMPLE_RATE)
            decodeNs += System.nanoTime() - callStart
            fedSamples += slice.size
            offset = end
            if (paced) {
                // Sleep only for whatever the decode did not already consume,
                // so a slow pass eats into the budget exactly as it would on a
                // live microphone rather than being papered over.
                val owedMs = fedSamples * 1000 / SAMPLE_RATE
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                if (owedMs > elapsedMs) Thread.sleep(owedMs - elapsedMs)
            }
        }
        val feedWallMs = (System.nanoTime() - t0) / 1_000_000

        // Stopping flushes the tail: the line in flight is finalised here.
        transcriber.stopStream(stream)
        transcriber.freeStream(stream)
        val heapAfterRun = Debug.getNativeHeapAllocatedSize()
        transcriber.close()

        val run = R0Run(
            events = events.toList(),
            loadMs = loadMs,
            streamOpenMs = streamOpenMs,
            audioMs = pcm.size.toLong() * 1000 / SAMPLE_RATE,
            feedWallMs = feedWallMs,
            decodeWallMs = decodeNs / 1_000_000,
            chunkMs = chunkMs,
            paced = paced,
            nativeHeapBeforeLoad = heapBefore,
            nativeHeapAfterLoad = heapAfterLoad,
            nativeHeapAfterRun = heapAfterRun,
        )
        log(run.summary())
        run.events.forEach {
            log("  [%5dms | audio %5dms] %-18s line=%d complete=%s lat=%dms %s"
                .format(it.atMs, it.audioFedMs, it.kind, it.lineId, it.complete, it.libLatencyMs,
                    it.text.take(90)))
        }
        return run
    }

    fun log(message: String) = message.lines().forEach { Log.i(TAG, it) }
}
