// Platform-neutral audio contract. Each shell supplies a recorder; the
// shared screen only ever sees PCM frames and turns them into a WAV
// capture. Capture must not depend on any platform quirk beyond "give me
// microphone bytes".

package org.gilune.app

class RecordedAudio(
    val pcm: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
)

interface AudioRecorder {
    fun start()
    fun stop(): RecordedAudio
}

/// The canonical shape every Gilune recording takes, on every shell:
/// 16 kHz mono signed 16-bit PCM. The kernel's transcriber refuses
/// anything else by name, so this is a contract and not a default.
const val WAV_SAMPLE_RATE = 16_000
const val WAV_CHANNELS = 1
const val WAV_BITS_PER_SAMPLE = 16

/// The 44-byte canonical PCM header. `dataSize` is what the header will
/// *claim* the `data` chunk holds; see [wavStreamHeader] for the one
/// caller that cannot know it.
private fun wavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int, dataSize: Int): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val riffSize = if (dataSize == WAV_SIZE_UNKNOWN) WAV_SIZE_UNKNOWN else 36 + dataSize

    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(riffSize)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1) // PCM
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(blockAlign.toShort())
    header.putShort(bitsPerSample.toShort())
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataSize)

    return header.array()
}

/// A minimal canonical WAV container (PCM, little-endian). Pure function
/// so the byte layout is testable without a microphone.
fun wavBytes(audio: RecordedAudio): ByteArray =
    wavHeader(audio.sampleRate, audio.channels, audio.bitsPerSample, audio.pcm.size) + audio.pcm

/// `0xFFFFFFFF` — the streaming convention for "this chunk runs to the
/// end of the file". Read back as a little-endian *signed* int it is -1,
/// which is why every reader in this repo that meets it answers "unknown"
/// rather than a wrong number.
const val WAV_SIZE_UNKNOWN = -1

/// The header a **streamed** recording opens with (ADR 0017 § 4: the
/// service streams PCM into the kernel chunk-by-chunk, so a process kill
/// loses only the tail).
///
/// A stream cannot know its own length, and a committed blob is
/// content-addressed, so the two size fields can never be patched
/// afterwards. They are therefore written as [WAV_SIZE_UNKNOWN] rather
/// than as a guess. What that costs, honestly:
///
/// - **The kernel is unaffected.** `canonical_wav_samples` reads the
///   shape off the fixed header offsets and then takes everything from
///   byte 44 to the end of the blob; it never consults either size field
///   (`kernel/crates/gilune-ffi/src/whisper.rs`). A streamed recording
///   transcribes exactly like an in-app one.
/// - **Duration is computed from the file's own length instead**, which
///   is what [wavDurationMs]'s `totalBytes` fallback is for.
/// - **Third-party players must clamp an over-declared `data` chunk to
///   the file.** Most do; this is the honest gap, and it is a gap in
///   presentation, never in preservation.
fun wavStreamHeader(
    sampleRate: Int = WAV_SAMPLE_RATE,
    channels: Int = WAV_CHANNELS,
    bitsPerSample: Int = WAV_BITS_PER_SAMPLE,
): ByteArray = wavHeader(sampleRate, channels, bitsPerSample, WAV_SIZE_UNKNOWN)

/// Gathers a stream of arbitrary reads into fixed-size blocks and hands
/// each full block on. The streamed-capture FFI wants chunks, and a
/// microphone hands out whatever the driver had — so this is the join
/// between them, and it is pure over `emit` so the mathematics can be
/// tested without a kernel or a microphone.
///
/// The block size is the durability window: everything already emitted is
/// staged by the kernel, so a kill loses at most one block's worth — at
/// the canonical rate, [CAPTURE_CHUNK] is about two seconds.
class ChunkedSink(
    private val chunkBytes: Int = CAPTURE_CHUNK,
    private val emit: (ByteArray) -> Unit,
) {
    init {
        require(chunkBytes > 0) { "a chunk must hold something" }
    }

    private val pending = java.io.ByteArrayOutputStream(chunkBytes)

    /// Bytes handed across the boundary so far. The tail still buffered
    /// here is deliberately not counted: it is not durable yet.
    var emitted: Long = 0L
        private set

    fun append(bytes: ByteArray, length: Int = bytes.size) {
        var offset = 0
        while (offset < length) {
            val take = minOf(chunkBytes - pending.size(), length - offset)
            pending.write(bytes, offset, take)
            offset += take
            if (pending.size() == chunkBytes) release()
        }
    }

    /// Hands on whatever is left. Called once, at the end of a recording.
    fun flush() {
        if (pending.size() > 0) release()
    }

    private fun release() {
        val block = pending.toByteArray()
        pending.reset()
        emitted += block.size
        emit(block)
    }
}
