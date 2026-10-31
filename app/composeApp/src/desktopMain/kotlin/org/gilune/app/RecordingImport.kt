// Loading a recording made somewhere else (ADR 0029).
//
// The transcribers read one shape — 16 kHz, mono, 16-bit PCM WAV, what the
// microphone records — so a file from a phone's recorder or a meeting
// export is converted to it before it is captured. The converted bytes are
// what the vault keeps as the note's recording; the file that was loaded
// is left exactly where it was, untouched.
//
// javax.sound reads WAV, AIFF and AU and decodes their PCM variants
// (8/24/32-bit, float, big-endian) to 16-bit; the down-mix and the
// resampling are done here, in a stream, so neither depends on which
// converters a runtime happens to carry and an hour-long recording is
// never held at its original rate in memory. MP3 and M4A need a decoder
// the JVM does not have: they are refused in words, not guessed at.

package org.gilune.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException

/// What the file picker offers and the tree's menu recognises.
val LOADABLE_RECORDING_SUFFIXES = setOf("wav", "wave", "aif", "aiff", "aifc", "au")

/// A file that cannot become a recording, with the reason in words a
/// person can act on.
class RecordingUnreadable(message: String) : Exception(message)

/// The file as the canonical WAV the transcribers read.
fun loadRecording(file: File): ByteArray {
    val suffix = file.extension.lowercase()
    if (suffix !in LOADABLE_RECORDING_SUFFIXES) {
        throw RecordingUnreadable(
            "Gilune can transcribe WAV and AIFF recordings; ${file.name} is " +
                (if (suffix.isEmpty()) "not one" else "${suffix.uppercase()} — convert it to WAV first"),
        )
    }
    val opened = try {
        AudioSystem.getAudioInputStream(file)
    } catch (e: UnsupportedAudioFileException) {
        throw RecordingUnreadable("${file.name} is not a recording Gilune can read")
    }
    return opened.use { source -> canonicalWav(source) }
}

/// Any PCM stream javax.sound can decode, as canonical WAV bytes.
internal fun canonicalWav(source: AudioInputStream): ByteArray {
    val format = source.format
    val channels = format.channels
    val rate = format.sampleRate
    if (channels < 1 || rate <= 0f || rate == AudioSystem.NOT_SPECIFIED.toFloat()) {
        throw RecordingUnreadable("the recording does not say its sample rate and channels")
    }
    val pcm16 = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false)
    val decoded = if (format.matches(pcm16)) {
        source
    } else {
        try {
            AudioSystem.getAudioInputStream(pcm16, source)
        } catch (e: IllegalArgumentException) {
            throw RecordingUnreadable("its ${format.encoding} audio cannot be decoded here")
        }
    }
    val out = ByteArrayOutputStream()
    val resampler = Resampler(rate.toDouble(), WAV_SAMPLE_RATE.toDouble()) { sample ->
        out.write(sample.toInt() and 0xFF)
        out.write((sample.toInt() shr 8) and 0xFF)
    }
    forEachMonoSample(decoded, channels) { resampler.push(it) }
    resampler.finish()
    if (out.size() == 0) throw RecordingUnreadable("the recording holds no sound")
    return wavBytes(RecordedAudio(out.toByteArray(), WAV_SAMPLE_RATE, WAV_CHANNELS, WAV_BITS_PER_SAMPLE))
}

/// Each frame of interleaved 16-bit little-endian PCM, averaged to one
/// sample. Averaging rather than taking a channel: a recording with the
/// speaker on the right must not become silence.
internal fun forEachMonoSample(stream: InputStream, channels: Int, sample: (Double) -> Unit) {
    val frame = channels * 2
    val buffer = ByteArray(frame * 4096)
    var held = 0
    while (true) {
        val read = stream.read(buffer, held, buffer.size - held)
        if (read < 0) break
        held += read
        val whole = held - held % frame
        var at = 0
        while (at < whole) {
            var sum = 0.0
            for (c in 0 until channels) {
                val lo = buffer[at + c * 2].toInt() and 0xFF
                val hi = buffer[at + c * 2 + 1].toInt()
                sum += ((hi shl 8) or lo).toShort()
            }
            sample(sum / channels)
            at += frame
        }
        buffer.copyInto(buffer, 0, whole, held)
        held -= whole
    }
}

/// A streaming rate converter, one sample in at a time.
///
/// Down (the ordinary case: 44.1 or 48 kHz to 16): each output sample is
/// the mean of the input samples its interval covers — a box filter, so
/// what is above the new rate is averaged away rather than folded back
/// in as noise. Up (8 kHz telephone audio): linear interpolation between
/// neighbours. Equal rates pass through untouched.
internal class Resampler(sourceRate: Double, targetRate: Double, private val emit: (Short) -> Unit) {
    private val step = sourceRate / targetRate
    private var index = 0L
    private var produced = 0L

    // Down: the window being averaged.
    private var sum = 0.0
    private var count = 0

    // Up: the previous sample.
    private var previous = 0.0

    fun push(sample: Double) {
        when {
            step == 1.0 -> out(sample)
            step > 1.0 -> {
                // Input i belongs to window floor(i / step); a window is
                // complete when the next input would start another. A
                // step over 1 means one input never completes two.
                sum += sample
                count++
                if (index + 1 >= (produced + 1) * step) {
                    out(sum / count)
                    sum = 0.0
                    count = 0
                }
            }
            else -> {
                if (index == 0L) previous = sample
                // Every output position in [index - 1, index].
                while (produced * step <= index) {
                    val x = produced * step
                    val value = if (index == 0L) sample else previous + (sample - previous) * (x - (index - 1))
                    out(value)
                }
                previous = sample
            }
        }
        index++
    }

    /// The tail: a part-filled window is still sound.
    fun finish() {
        if (step > 1.0 && count > 0) out(sum / count)
        count = 0
        sum = 0.0
    }

    private fun out(value: Double) {
        emit(value.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).let { Math.round(it).toInt().toShort() })
        produced++
    }
}
