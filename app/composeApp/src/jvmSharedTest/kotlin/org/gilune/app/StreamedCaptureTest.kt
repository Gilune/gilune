// The mathematics the Record widget's service depends on (ADR 0017 § 4:
// "the service streams PCM into the kernel chunk-by-chunk, so a process
// kill loses only the tail").
//
// Both halves are pure, so both are tested here — on the desktop suite
// and, because `jvmSharedTest` is on the instrumented source set too, on
// the device that actually runs the service.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamedCaptureTest {
    private fun littleEndianInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    @Test
    fun theStreamHeaderIsTheCanonicalShapeInTheCanonicalPlaces() {
        val header = wavStreamHeader()
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        // The kernel's transcriber reads the shape off exactly these
        // offsets and refuses anything else by name.
        assertEquals(1, littleEndianInt(header, 20) and 0xFFFF, "PCM")
        assertEquals(WAV_CHANNELS, littleEndianInt(header, 22) and 0xFFFF)
        assertEquals(WAV_SAMPLE_RATE, littleEndianInt(header, 24))
        assertEquals(WAV_BITS_PER_SAMPLE, littleEndianInt(header, 34) and 0xFFFF)
        assertEquals(32_000, littleEndianInt(header, 28), "byte rate")
    }

    @Test
    fun aStreamDeclaresItsLengthUnknownRatherThanGuessingOne() {
        val header = wavStreamHeader()
        // A committed blob is content-addressed, so neither field can be
        // patched afterwards. Unknown is the only honest value.
        assertEquals(WAV_SIZE_UNKNOWN, littleEndianInt(header, 4), "RIFF size")
        assertEquals(WAV_SIZE_UNKNOWN, littleEndianInt(header, 40), "data size")
    }

    @Test
    fun aWholeRecordingStillDeclaresItsRealLength() {
        // The in-app path knows its size and must go on saying so: the
        // streaming header is an addition, never a replacement.
        val wav = wavBytes(RecordedAudio(ByteArray(320), WAV_SAMPLE_RATE, 1, 16))
        assertEquals(320, littleEndianInt(wav, 40))
        assertEquals(356, littleEndianInt(wav, 4))
    }

    @Test
    fun everyByteAppendedIsEmittedOnceAndInOrder() {
        val blocks = mutableListOf<ByteArray>()
        val sink = ChunkedSink(chunkBytes = 8) { blocks += it }
        val source = ByteArray(30) { it.toByte() }
        // Reads off a microphone are whatever the driver had, so feed it
        // uneven ones.
        sink.append(source.copyOfRange(0, 3))
        sink.append(source.copyOfRange(3, 20))
        sink.append(source.copyOfRange(20, 30))
        sink.flush()

        assertContentEquals(source, blocks.reduce { a, b -> a + b })
        assertEquals(30L, sink.emitted)
    }

    @Test
    fun everyBlockButTheLastIsExactlyTheDurabilityWindow() {
        val blocks = mutableListOf<Int>()
        val sink = ChunkedSink(chunkBytes = 8) { blocks += it.size }
        sink.append(ByteArray(20))
        // Nothing is emitted that is not a full block, so the kernel only
        // ever sees whole windows until the recording ends.
        assertEquals(listOf(8, 8), blocks)
        sink.flush()
        assertEquals(listOf(8, 8, 4), blocks)
    }

    @Test
    fun anExactMultipleLeavesNothingBehind() {
        val blocks = mutableListOf<Int>()
        val sink = ChunkedSink(chunkBytes = 8) { blocks += it.size }
        sink.append(ByteArray(16))
        sink.flush()
        assertEquals(listOf(8, 8), blocks, "flush must not emit an empty tail")
    }

    @Test
    fun aRecordingThatSaidNothingCommitsNothingExtra() {
        val blocks = mutableListOf<ByteArray>()
        ChunkedSink(chunkBytes = 8) { blocks += it }.flush()
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun theTailIsNotCountedUntilItIsDurable() {
        val sink = ChunkedSink(chunkBytes = 8) { }
        sink.append(ByteArray(12))
        // Four bytes are still buffered in the shell. Claiming them would
        // be claiming durability the kernel has not been given.
        assertEquals(8L, sink.emitted)
        sink.flush()
        assertEquals(12L, sink.emitted)
    }

    @Test
    fun appendHonoursALengthShorterThanItsBuffer() {
        // AudioRecord fills a reusable buffer and reports how much of it
        // is real; handing on the unread remainder would append silence.
        val blocks = mutableListOf<ByteArray>()
        val sink = ChunkedSink(chunkBytes = 4) { blocks += it }
        sink.append(ByteArray(8) { 9 }, length = 2)
        sink.flush()
        assertContentEquals(byteArrayOf(9, 9), blocks.single())
    }
}
