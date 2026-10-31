// Loading a recording made elsewhere (ADR 0029): whatever WAV or AIFF
// shape it arrives in, what is captured is the 16 kHz mono 16-bit WAV the
// transcribers read, with the sound — and its length — intact.

package org.gilune.app

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecordingImportTest {
    private val dir: File = Files.createTempDirectory("gilune-recording").toFile()

    /// A tone written as `format` into a file of `type`. Only the left
    /// channel sounds, so a down-mix that took the right one would be heard
    /// as silence.
    private fun tone(name: String, format: AudioFormat, type: AudioFileFormat.Type, seconds: Double): File {
        val frames = (format.sampleRate * seconds).toInt()
        val bytesPerSample = format.sampleSizeInBits / 8
        val buffer = ByteBuffer.allocate(frames * format.channels * bytesPerSample)
            .order(if (format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val value = sin(2 * PI * 440.0 * i / format.sampleRate) * 0.5
            for (c in 0 until format.channels) {
                val v = if (c == 0) value else 0.0
                when (bytesPerSample) {
                    // WAV's 8-bit samples are unsigned, centred on 128.
                    1 -> buffer.put(((v * 127).toInt() + if (format.encoding == AudioFormat.Encoding.PCM_UNSIGNED) 128 else 0).toByte())
                    2 -> buffer.putShort((v * Short.MAX_VALUE).toInt().toShort())
                    else -> error("unused")
                }
            }
        }
        val file = File(dir, name)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(buffer.array()), format, frames.toLong()), type, file)
        return file
    }

    private class Canonical(val sampleRate: Int, val channels: Int, val bits: Int, val samples: ShortArray)

    private fun read(wav: ByteArray): Canonical {
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val samples = ShortArray((wav.size - 44) / 2) { b.getShort(44 + it * 2) }
        return Canonical(b.getInt(24), b.getShort(22).toInt(), b.getShort(34).toInt(), samples)
    }

    private fun loudest(samples: ShortArray) = samples.maxOf { abs(it.toInt()) }

    @Test
    fun aStereoCdRateWavBecomesSixteenKilohertzMonoOfTheSameLength() {
        val file = tone("meeting.wav", AudioFormat(44_100f, 16, 2, true, false), AudioFileFormat.Type.WAVE, 1.5)
        val out = read(loadRecording(file))
        assertEquals(Triple(16_000, 1, 16), Triple(out.sampleRate, out.channels, out.bits))
        assertTrue(abs(out.samples.size - 24_000) <= 2, "1.5 s at 16 kHz: ${out.samples.size}")
        // The left channel's tone, halved by the average with a silent right.
        assertTrue(loudest(out.samples) in 7_000..9_000, "the tone survived: ${loudest(out.samples)}")
    }

    @Test
    fun aBigEndianAiffAtTelephoneRateIsReadAndRaised() {
        val file = tone("call.aiff", AudioFormat(8_000f, 16, 1, true, true), AudioFileFormat.Type.AIFF, 1.0)
        val out = read(loadRecording(file))
        assertEquals(16_000, out.sampleRate)
        assertTrue(abs(out.samples.size - 16_000) <= 2, "1 s at 16 kHz: ${out.samples.size}")
        assertTrue(loudest(out.samples) in 14_000..17_000, "${loudest(out.samples)}")
    }

    @Test
    fun eightBitAudioIsDecodedNotMisreadAsSixteen() {
        val unsigned = AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 16_000f, 8, 1, 1, 16_000f, false)
        val file = tone("old.wav", unsigned, AudioFileFormat.Type.WAVE, 0.5)
        val out = read(loadRecording(file))
        assertEquals(8_000, out.samples.size)
        assertTrue(loudest(out.samples) in 14_000..17_000, "${loudest(out.samples)}")
    }

    @Test
    fun aRecordingAlreadyInTheCanonicalShapeIsKeptSampleForSample() {
        val file = tone("mic.wav", AudioFormat(16_000f, 16, 1, true, false), AudioFileFormat.Type.WAVE, 0.25)
        val original = file.readBytes()
        val out = loadRecording(file)
        assertTrue(original.copyOfRange(original.size - 8_000, original.size).contentEquals(out.copyOfRange(44, out.size)))
    }

    @Test
    fun whatTheJvmCannotDecodeIsRefusedInWordsAPersonCanActOn() {
        val mp3 = File(dir, "voice memo.m4a").apply { writeBytes(ByteArray(64)) }
        val refused = assertFailsWith<RecordingUnreadable> { loadRecording(mp3) }
        assertTrue("convert it to WAV" in refused.message!!, refused.message)
        val notAudio = File(dir, "notes.wav").apply { writeText("this is not a recording") }
        assertTrue("not a recording" in assertFailsWith<RecordingUnreadable> { loadRecording(notAudio) }.message!!)
    }

    @Test
    fun theResamplerAveragesDownInterpolatesUpAndPassesEqualRatesThrough() {
        fun run(from: Double, to: Double, input: List<Double>): List<Int> {
            val out = mutableListOf<Int>()
            val r = Resampler(from, to) { out.add(it.toInt()) }
            input.forEach(r::push)
            r.finish()
            return out
        }
        assertEquals(listOf(1, 2, 3), run(16.0, 16.0, listOf(1.0, 2.0, 3.0)))
        assertEquals(listOf(2, 5, 7), run(48.0, 16.0, listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)))
        assertEquals(listOf(0, 50, 100, 150, 200), run(8.0, 16.0, listOf(0.0, 100.0, 200.0)))
    }

    // ------------------------------------------------- where the note goes

    @Test
    fun theLoadedNoteIsNamedAfterItsFileAndTakesItsRecordingWhereverItGoes() {
        val vault = Files.createTempDirectory("gilune-place").toFile()
        File(vault, "Captures").mkdirs()
        File(vault, "Meetings").mkdirs()
        File(vault, "Captures/capture-ab12.md").writeText("words")
        File(vault, "Captures/capture-ab12.wav").writeText("bytes")
        assertEquals("Meetings/Standup.md", placeLoadedNote(vault.path, "Captures/capture-ab12.md", "Standup", "Meetings"))
        assertTrue(File(vault, "Meetings/Standup.wav").isFile, "the recording went with it")

        // A name already taken gets the next free one, and null leaves it in Captures.
        File(vault, "Captures/capture-cd34.md").writeText("words")
        File(vault, "Captures/Standup.md").writeText("another")
        assertEquals("Captures/Standup 1.md", placeLoadedNote(vault.path, "Captures/capture-cd34.md", "Standup", null))
        // A folder that has gone leaves the note where it is, named.
        File(vault, "Captures/capture-ef56.md").writeText("words")
        assertEquals("Captures/Retro.md", placeLoadedNote(vault.path, "Captures/capture-ef56.md", "Retro", "Gone"))
        // A name free in Captures but taken where it is going — a recording
        // beside its own note — takes the next one free in both, and moves.
        File(vault, "Captures/capture-gh78.md").writeText("words")
        File(vault, "Meetings/Kitchen.md").writeText("the meeting's own note")
        assertEquals("Meetings/Kitchen 1.md", placeLoadedNote(vault.path, "Captures/capture-gh78.md", "Kitchen", "Meetings"))
        assertEquals("the meeting's own note", File(vault, "Meetings/Kitchen.md").readText())
    }
}
